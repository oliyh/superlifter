(ns superlifter.lacinia-test
  (:require
   [clojure.test :refer :all]
   [com.walmartlabs.lacinia.resolve :refer [on-deliver!]]
   [promesa.core :as prom]
   [superlifter.lacinia :as sl]))

(deftest ->lacinia-promise
  (let [ex (ex-info "Expected" {})]
    (are [input expected] (= expected
                             (let [v (promise)]
                               (on-deliver! (sl/->lacinia-promise input) #(deliver v %))
                               (deref v 5 ::timeout)))
      ::value                                           ::value
      (prom/promise ::value)                            ::value
      (doto (prom/deferred) (prom/resolve! ::value))    ::value
      (doto (prom/deferred) (prom/reject! ex))          ex)))
