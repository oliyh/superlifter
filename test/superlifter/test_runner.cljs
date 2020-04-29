;; This test runner is intended to be run from the command line
(ns superlifter.test-runner
  (:require
   [superlifter.core-test]
   [superlifter.api-test]
   [figwheel.main.testing :refer [run-tests-async]]))

(defn -main [& args]
  (run-tests-async 5000))
