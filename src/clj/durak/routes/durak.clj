(ns durak.routes.durak
  (:require [compojure.core :refer [GET defroutes wrap-routes]]
            [clojure.tools.logging :as log]
            [immutant.web.async :as async]
            [cognitect.transit :as t])
  (import [java.io ByteArrayInputStream ByteArrayOutputStream]))


(def card-ranks [:6 :7 :8 :9 :10 :J :Q :K :A])

(def card-suits [:clubs :diaminds :hearts :spades])

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


(defn pick-card [target]
  (dosync
   (let [card (first (:deck @table))]
     (alter table assoc :deck (rest (:deck @table)))
     (alter players update-in [target :hand] conj card))))

(defn new-table []
  (dosync
   (alter table assoc :deck (new-deck))
   (let [trump (first (:deck @table))]
     (alter table assoc :trump-card trump))))


(defn connect! [channel]
  (swap! channels conj channel))

(defn disconnect! [channel {:keys [code reason]}]
  (log/info "close code:" code "reason:" reason)
  (swap! channels #(remove #{channel} %)))

(defn set-first-turn! []
  (let [trump (:suit (:trump-card @table))
        [p1 p2] (map (fn [[chan data]]
                       {:player chan
                        :lowest-trump (apply min (concat [99]
                                                   (map (fn [c]
                                                          (.indexOf card-ranks (:rank c)))
                                                        (filter (fn [c]
                                                                  (= (:suit c) trump))
                                                                (:hand data)))))
                        :total-rank (reduce + (map (fn [c]
                                                     (.indexOf card-ranks (:rank c)))
                                                   (:hand data)))})
                     @players)]
    (if (not= (:lowest-trump p1) (:lowest-trump p2))
      (if (> (:lowest-trump p1) (:lowest-trump p2))
        (dosync
         (alter players update (:player p2) assoc :turn true :status :attacking)
         (alter players update (:player p1) assoc :turn false :status :defending))
        (dosync
         (alter players update (:player p2) assoc :turn false :status :defending)
         (alter players update (:player p1) assoc :turn true :status :attacking)))
      (if (> (:total-rank p2) (:total-rank p1))
                (dosync
         (alter players update (:player p2) assoc :turn true :status :attacking)
         (alter players update (:player p1) assoc :turn false :status :defending))
        (dosync
         (alter players update (:player p2) assoc :turn false :status :defending)
         (alter players update (:player p1) assoc :turn true :status :attacking))))
    ))


(defn init-game! []
  (new-table)
  (doseq [[chan data] @players]
    (doall (repeatedly 6 #(pick-card chan))))
  (set-first-turn!)
  (doseq [[chan data] @players]
    (let [player (get @players chan)
          [op-chan opponent] (first (dissoc @players chan))]
      (dosync (alter players update chan assoc :opponent op-chan))
      (async/send! chan (write-transit
                         {:table @table
                          :hand (:hand player)
                          :status (:status player)
                          :turn (:turn player)
                          :opponent {:status (:status opponent)
                                     :hand (count (:hand opponent))}})))))

(defmulti process-msg!
  (fn [_ msg] (:type (read-transit msg))))

(defmethod process-msg! :ready [chan msg]
  (when (< (count @players) 2)
    (dosync (alter players assoc chan {:hand []}))
    (if (= 2 (count @players))
      (dosync (alter players assoc chan {:hand []})
              (init-game!))
      (doseq [channel @channels]
        (async/send! channel (write-transit
                              {:table @table
                               :hand []
                               :opponent (if-not (= channel chan) {:status :ready})}))))))

(defmethod process-msg! :attack [chan msg]
  (prn "attack")
  (let [msg (:msg (read-transit msg))
        op-chan (:opponent (get @players chan))
        player (get @players chan)
        opponent (get @players op-chan)]
    (if (= :pass msg)
      (dosync (alter players update chan assoc :turn false :status :defending)
              (alter players update op-chan assoc :turn true :status :attacking)
              (doseq [channel @channels]
                (async/send! channel (write-transit
                                      {:table @table
                                       :hand (:hand player)
                                       :status (:status player)
                                       :turn (:turn player)
                                       :opponent {:status (:status opponent)
                                                  :hand (count (:hand opponent))}}))))
      (let [card (:card msg)]
        (dosync (alter players update-in [chan :hand] #(vec (remove #{card} (set %))))
                (alter table assoc :attacking card))))))

(defmethod process-msg! :defence [chan msg]
  (prn )
  (let [msg (:msg (read-transit msg))
        op-chan (:opponent (get @players chan))]
    (if (= :take msg)
      (dosync (alter players update-in [chan :hand]
                     #(concat % (:attacking @table) (:beat @table)))
              (alter table assoc :attacking nil :beat [])))))


(def websocket-callbacks
  "WebSocket callback functions"
  {:on-open connect!
   :on-close disconnect!
   :on-message process-msg!})

(defn ws-handler [request]
  (async/as-channel request websocket-callbacks))

(defroutes durak-routes
  (GET "/ws" [] ws-handler))
