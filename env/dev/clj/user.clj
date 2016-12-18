(ns user
  (:require [mount.core :as mount]
            [durak.figwheel :refer [start-fw stop-fw cljs]]
            durak.core))

(defn start []
  (mount/start-without #'durak.core/http-server
                       #'durak.core/repl-server))

(defn stop []
  (mount/stop-except #'durak.core/http-server
                     #'durak.core/repl-server))

(defn restart []
  (stop)
  (start))


