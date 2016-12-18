(ns durak.env
  (:require [clojure.tools.logging :as log]))

(def defaults
  {:init
   (fn []
     (log/info "\n-=[durak started successfully]=-"))
   :stop
   (fn []
     (log/info "\n-=[durak has shut down successfully]=-"))
   :middleware identity})
