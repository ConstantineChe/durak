(ns durak.routes.durak
  (:require [compojure.core :refer [GET defroutes wrap-routes]]
            [clojure.tools.logging :as log]
            [immutant.web.async :as async]
            [cognitect.transit :as t]
            [durak.validation :refer [card-ranks card-suits get-numeric-rank]])
  (import [java.io ByteArrayInputStream ByteArrayOutputStream]))


(defonce table (ref {}))

(defn write-transit [x]
  (let [baos (ByteArrayOutputStream.)
        w    (t/writer baos :json)
        _    (t/write w x)
        ret  (.toString baos)]
    (.reset baos)
    ret))



(defn read-transit [msg]
  (let [bytes (.getBytes msg)
        bais  (ByteArrayInputStream. bytes)
        r     (t/reader bais :json)
        ret (t/read r)]
    (.reset bais)
    ret))


(defonce channels (atom #{}))

(defonce players (ref {}))

(defn new-deck []
  (shuffle (for [suit card-suits rank card-ranks]
             {:suit suit :rank rank})))

(defn pick-card! [target]
  (dosync
   (let [card (first (:deck @table))]
     (alter table assoc :deck (rest (:deck @table)))
     (alter players update-in [target :hand] conj card))))

(defn new-table! []
  (dosync
   (alter table assoc :deck (new-deck))
   (let [trump (first (:deck @table))]
     (alter table assoc :trump-card trump))))

(defn set-attacker [attacker defender]
  (dosync (alter players update attacker assoc :turn true :status :attacking)
          (alter players update defender assoc :turn false :status :defending)))

(defn switch-turn! []
  (doseq [[player-channel] @players]
    (alter players update-in [player-channel :turn] not)))

(defn get-lowest-trump [hand trump]
  (apply min (concat [99]
                     (map (fn [card]
                            (get-numeric-rank card))
                          (filter (fn [card]
                                    (= (:suit card) trump))
                                  hand)))))

(defn connect! [channel]
  (swap! channels conj channel))

(defn disconnect! [channel {:keys [code reason]}]
  (log/info "close code:" code "reason:" reason)
  (swap! channels #(remove #{channel} %)))

(defn send-transit-message! [channel message]
  (->> (write-transit message) (async/send! channel)))

(defn notify-players! []
  (doseq [[channel player] @players]
    (let [opponent (-> @players player :opponent)]
      (send-transit-message! channel
                             {:table @table
                              :hand (:hand player)
                              :status (:status player)
                              :turn (:turn player)
                              :opponent {:status (:status opponent)
                                         :hand (count (:hand opponent))}}))))

(defn set-first-turn! []
  (let [trump (:suit (:trump-card @table))
        [player1 player2] (map (fn [[channel player-state]]
                       {:channel      channel
                        :lowest-trump (get-lowest-trump (:hand player-state) trump)
                        :total-rank   (reduce + (map (fn [card]
                                                     (get-numeric-rank card))
                                                   (:hand player-state)))})
                     @players)]
    (if (not= (:lowest-trump player1) (:lowest-trump player2))
      (if (> (:lowest-trump player1) (:lowest-trump player2))
        (set-attacker (:channel  player2) (:channel  player1))
        (set-attacker (:channel  player1) (:channel  player2)))))
      (if (> (:total-rank player2) (:total-rank player1))
        (set-attacker (:channel  player2) (:channel  player1))
        (set-attacker (:channel  player1) (:channel  player2))))

(defn set-opponents! []
  (doseq [[channel] @players]
    (let [player (get @players channel)
          [opponent-channel] (first (dissoc @players channel))]
      (dosync (alter players update channel assoc :opponent opponent-channel))
      )))

(defn refill-hands! [attacker-channel defender-channel]
  (doseq [[channel] [(get @players attacker-channel) (get @players defender-channel)]]
    (while (or (< 6 (-> @players channel hand count))
               (-> @table :deck empty?))
      (pick-card! channel))))

(defn put-card! [card player-channel]
  (dosync (alter players update-in [player-channel :hand]
                 (fn [cards]
                   (->> (set cards)
                        (remove #{card})
                        (vec))))
          (alter table assoc :attacking card)))

(defn take-cards! [player-channel]
  (dosync (alter players update-in [player-channel :hand]
                 (fn [hand] (concat hand (:attacking @table) (:beat @table))))
          (alter table assoc :attacking nil :beat [])))

(defn beat-card! [card player-channel]
  (dosync (alter players update-in [player-channel :hand]
                 (fn [cards]
                   (->> (set cards)
                        (remove #{card})
                        (vec))))
          (alter table (fn [table]
                         (-> table
                             (update :beat concat [card (:attacking table)])
                             (assoc :attacking nil))))))

(defn discard-beat! []
  (dosync (alter table assoc :beat [])))

(defn init-game! []
  (new-table!)
  (apply refill-hands! (keys @players))
  (set-first-turn!)
  (set-opponents!)
  (notify-players!))

(defmulti process-message!
          (fn [_ message] (:type message)))

(defmethod process-message! :ready [chan _]
  (when (< (count @players) 2)
    (dosync (alter players assoc chan {:hand []}))
    (if (= 2 (count @players))
      (dosync (alter players assoc chan {:hand []})
              (init-game!))
      (doseq [channel @channels]
        (send-transit-message! channel
                               {:table @table
                                :hand []
                                :opponent (if-not (= channel chan) {:status :ready})})))))

(defmethod process-message! :attack [sender-channel {:keys [msg]}]
  (prn "attack")
  (let [opponent-channel (:opponent (get @players sender-channel))
        {:keys [action card]} msg]
    (case action
      :pass (do (discard-beat!)
                (refill-hands! sender-channel opponent-channel)
                (set-attacker opponent-channel sender-channel)
                (notify-players!))
      :put-card (do (put-card! card sender-channel)
                    (switch-turn!)
                    (notify-players!)))))

(defmethod process-message! :defence [sender-channel {:keys [msg]}]
  (prn "def")
  (let [opponent-channel (:opponent (get @players sender-channel))
        {:keys [action card]} msg]
    (case action
      :take (do (take-cards! sender-channel)
                (switch-turn!)
                (notify-players!))
      :beat-card (do (beat-card! card sender-channel)
                     (if (or (empty? (-> @players sender-channel :hand))
                             (empty? (-> @players opponent-channel :hand)))
                       (do (refill-hands! opponent-channel sender-channel)
                           (set-attacker sender-channel opponent-channel)
                           (notify-players!))
                       (do (switch-turn!)
                           (notify-players!)))
                     ))))


(def websocket-callbacks
  "WebSocket callback functions"
  {:on-open connect!
   :on-close disconnect!
   :on-message (fn [channel message]
                 (->> (read-transit message)
                      (process-message! channel)))})

(defn ws-handler [request]
  (async/as-channel request websocket-callbacks))

(defroutes durak-routes
  (GET "/ws" [] ws-handler))
