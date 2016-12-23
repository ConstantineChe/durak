(ns durak.validation
  (:require [bouncer.core :as b]
            [bouncer.validators :as v]))

(def card-ranks [:6 :7 :8 :9 :10 :J :Q :K :A])

(def card-suits [:clubs :diamonds :hearts :spades])

(defn get-numeric-rank [{:keys [rank]}]
  (.indexOf card-ranks rank))

(defn get-beat-ranks [{:keys [beat]}]
  (set (map :rank beat)))

(defn valid-attack? [card table]
  (or (empty? (:beat table))
      ((get-beat-ranks table) (:rank card))))

(defn valid-defence? [card {:keys [attacking trump-card]}]
  (let [card-rank (get-numeric-rank card)
        attacking-card-rank (get-numeric-rank attacking)
        trump (:suit trump-card)]
    (and (or (= (:suit card) (:suit attacking)) (= (:suit card) trump))
         (if (= (:suit attacking) trump)
           (> card-rank attacking-card-rank)
           (or (= (:suit card) trump)
               (> card-rank  attacking-card-rank)))
           )))

