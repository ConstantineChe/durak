(ns durak.env
  (:require [selmer.parser :as parser]
            [clojure.tools.logging :as log]
            [durak.dev-middleware :refer [wrap-dev]]))

(def defaults
  {:init
   (fn []
     (parser/cache-off!)
     (log/info "\n-=[durak started successfully using the development profile]=-"))
   :stop
   (fn []
     (log/info "\n-=[durak has shut down successfully]=-"))
   :middleware wrap-dev})
