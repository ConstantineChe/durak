(ns durak.handler
  (:require [compojure.core :refer [routes wrap-routes]]
            [durak.layout :refer [error-page]]
            [durak.routes.home :refer [home-routes]]
            [durak.routes.durak :refer [durak-routes]]
            [compojure.route :as route]
            [durak.env :refer [defaults]]
            [mount.core :as mount]
            [durak.middleware :as middleware]
            ))

(mount/defstate init-app
                :start ((or (:init defaults) identity))
                :stop  ((or (:stop defaults) identity)))

(def app-routes
  (routes
   #'durak-routes
   (-> #'home-routes
        (wrap-routes middleware/wrap-csrf)
        (wrap-routes middleware/wrap-formats))
    (route/not-found
      (:body
        (error-page {:status 404
                     :title "page not found"})))))


(defn app [] (middleware/wrap-base #'app-routes))
