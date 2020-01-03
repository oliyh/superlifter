(ns example.server
  (:require [io.pedestal.http :as server]
            [io.pedestal.interceptor :refer [interceptor]]
            [com.walmartlabs.lacinia.pedestal :as lacinia]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [com.walmartlabs.lacinia.schema :as schema]
            [superlifter.core :as sl]
            [urania.core :as u]
            [promesa.core :as prom]))

(defrecord Fetch [v]
  u/DataSource
  (-identity [this] v)
  (-fetch [this env]
    (prom/create (fn [resolve reject]
                   (resolve v)))))

(defn- resolve-pets [context args parent]
  (let [result (resolve/resolve-promise)
        superlifter (get-in context [:request :superlifter])]
    (-> (sl/enqueue! superlifter
                     (->Fetch [{:id "a"}
                               {:id "b"}
                               {:id "c"}]))
        (prom/then #(do (sl/adjust-queue-trigger-threshold! superlifter :size-limit (dec (count %)))
                        %))
        (prom/then #(resolve/deliver! result %)))
    result))

(defn- resolve-pet-name [context args {:keys [id]}]
  (let [result (resolve/resolve-promise)]
    (prom/then (sl/enqueue! (get-in context [:request :superlifter])
                            (->Fetch (str id "-name")))
               #(resolve/deliver! result %))
    result))

(defn compile-schema []
  (schema/compile
   {:objects {:Pet {:fields {:id {:type 'String}
                             :name {:type 'String
                                    :resolve resolve-pet-name}}}}
    :queries {:pets
              {:type '(list :Pet)
               :resolve resolve-pets}}}))

(def lacinia-opts {:graphiql true})

(def superlifter-args
  {:triggers {:size-limit {:kind :queue-size
                           :threshold 1}
              :time-limit {:kind :interval
                           :interval 100}}})

(def inject-superlifter
  (interceptor
   {:name ::inject-superlifter
    :enter (fn [ctx]
             (assoc-in ctx [:request :superlifter] (sl/start! superlifter-args)))
    :leave (fn [ctx]
             (update-in ctx [:request :superlifter] sl/stop!))}))

(def service (lacinia/service-map
              (fn [] (compile-schema))
              (assoc lacinia-opts
                     :interceptors (into [inject-superlifter]
                                         (lacinia/default-interceptors (fn [] (compile-schema)) lacinia-opts)))))

;; This is an adapted service map, that can be started and stopped
;; From the REPL you can call server/start and server/stop on this service
(defonce runnable-service (server/create-server service))

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  (println "\nCreating your server...")
  (server/start runnable-service))
