(ns superlifter.lacinia
  (:require [superlifter.core :as s]
            [superlifter.api :as api]
            [io.pedestal.interceptor :refer [interceptor]]
            [com.walmartlabs.lacinia.resolve :as resolve]))

(defn inject-superlifter [superlifter-args]
  (interceptor
   {:name ::inject-superlifter
    :enter (fn [ctx]
             (assoc-in ctx [:request :superlifter] (s/start! superlifter-args)))
    :leave (fn [ctx]
             (update-in ctx [:request :superlifter] s/stop!))}))

(defn ->lacinia-promise [sl-result]
  (let [l-prom (resolve/resolve-promise)]
    (api/unwrap #(resolve/deliver! l-prom %) sl-result)
    l-prom))

(defmacro with-superlifter [ctx body]
  `(api/with-superlifter (get-in ~ctx [:request :superlifter])
     (->lacinia-promise ~body)))
