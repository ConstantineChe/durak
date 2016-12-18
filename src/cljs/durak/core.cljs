(ns durak.core
  (:require [reagent.core :as r]
            [reagent.session :as session]
            [durak.ajax :refer [load-interceptors!]]
            [ajax.core :refer [GET POST]]
            [durak.durak :as durak]))

(defn card [card f]
  [:span {:on-click f} (str (name (:rank card)) (name (:suit card)) "  ")])

(defn table-info [table]
  [:div.info
   [:div.deck "Deck: " (count (:deck table))]
;   [:div.debug (str "table: " table)]
   [:div.trump "Trump: " [card (:trump-card table)]]])

(defn oponent [op-info]
  [:div.opponent
   [:div.status "Opponent status: " (:status op-info)]
   [:div.hand "Opponent hand: " (:hand op-info) " cards"]])

(defn attack [card]
  (durak/send-transit-msg! {:type :attack
                            :card card}))

(defn ready [_]
  (durak/send-transit-msg! {:type :ready})
  (session/put! :status :ready))

(defn submit [table status]
  (if-not status [:button {:on-click ready} "Ready"]))

(defn invalid-card [card]
  (fn [_] (js/alert (str "You can't use " (name (:rank card)) (name (:suit card))))))

(defn get-beat-ranks [{:keys [beat]}]
  (set (map :rank beat)))

(defn valid-attack? [card table]
  (or (empty? (:beat table))
      ((get-beat-ranks table) (:rank card))))

(defn valid-defence? [card table]
  (let [attacking (:attacking table)
        trump (:suit (:trump-card table))]
    (and (or (= (:suit card) (:suit attacking)) (= (:suit card)) trump)
         (if (= (:suit attacking))
           (> (:rank card) (:rank attacking))
           (or (= (:suit card) trump)
               (> (:rank card) (:rank attacking)))))))

(defn get-card-fn [card status table]
  (case status
    :defending (if (valid-defence? card table)
                 #(durak/send-transit-msg! {:type :defence
                                            :msg card})
                 invalid-card)
    :attacking (if (valid-defence? card table)
                 #(durak/send-transit-msg! {:type :attack
                                            :msg card})
                 invalid-card)
    #(js/alert "none")))

(defn hand [cards turn status table]
  [:div.hand "Your Hand:"
   [:div.cards
    (map-indexed
     (fn [i c]
       (let [f (if turn
                 (get-card-fn c status table)
                 #(js/alert "It is not your turn"))]
         [:span {:key i} [card c f]]))
     cards)]
   [:div.action
    (case status
      :attacking [:button (merge
                           {:on-click
                            #(durak/send-transit-msg!
                              {:type :attack
                               :msg :pass})}
                           (if-not turn
                             {:disabled true}))
               "Pass"]
      :defending [:button (merge
                           {:on-click
                             #(durak/send-transit-msg!
                               {:type :defence
                                :msg :take})}
                           (if-not turn
                             {:disabled true}))
                  "Take"]
      [submit table status])]])


(defn main []
  (fn []
    (let [table (session/get :table)
          status (session/get :status)]
      [:div [:h2 "Durak"]
       (if (session/get :turn)
         [:div.turn [:h3 "Your turn"]]
         [:div.turn [:h3 "opponents turn"]])
       [oponent (session/get :opponent)]
       [:div.table "Table"
        ()
        (if-not (empty? table) [table-info table])]
       [:div.cards
        [:div.attacking (if (:attacking table) [card (:attacking table)])]
        [:div.beat (:beat table)]]
       [hand (session/get :hand) (session/get :turn) (session/get :status) table]]
      )))


(defn mount-components []
  (r/render [main] (.getElementById js/document "app")))

(defn update-state! [{:keys [table hand opponent status turn]}]
  (prn table hand opponent status)
  (session/put! :turn turn)
  (session/put! :opponent opponent)
  (session/put! :table table)
  (session/put! :hand hand)
  (session/put! :status status))

(defn init! []
  (durak/make-websocket! (str "ws://" (.-host js/location) "/ws") update-state!)
  (mount-components))
