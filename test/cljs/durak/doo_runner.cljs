(ns durak.doo-runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [durak.core-test]))

(doo-tests 'durak.core-test)

