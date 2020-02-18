(ns example.server
  (:require [io.pedestal.http :as server]
            [io.pedestal.interceptor :refer [interceptor]]
            [com.walmartlabs.lacinia.pedestal :as lacinia]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [com.walmartlabs.lacinia.schema :as schema]
            [superlifter.core :as sl]
            [superlifter.lacinia :refer [inject-superlifter ->lacinia-promise]]
            [urania.core :as u]
            [promesa.core :as prom]
            [clojure.tools.logging :as log]))

(def pet-db (atom {"abc-123" {:name "Lyra"
                              :age 11}
                   "def-234" {:name "Pantalaimon"
                              :age 11}
                   "ghi-345" {:name "Iorek"
                              :age 41}}))

(defrecord FetchPets []
  u/DataSource
  (-identity [this] :fetch-pets)
  (-fetch [this env]
    (prom/create (fn [resolve reject]
                   (resolve (map (fn [id]
                                   {:id id})
                                 (keys (:db env))))))))

(defrecord FetchPet [id]
  u/DataSource
  (-identity [this] id)
  (-fetch [this env]
    (log/info "Fetching pet details" id)
    (prom/create (fn [resolve reject]
                   (resolve (get (:db env) id)))))

  u/BatchedSource
  (-fetch-multi [muse muses env]
    (let [muses (cons muse muses)
          pet-ids (map :id muses)]
      (log/info "Combining request for ids" pet-ids)
      (zipmap (map u/-identity muses)
              (map (:db env) pet-ids)))))

(defn- resolve-pets [context args parent]
  (let [superlifter (get-in context [:request :superlifter])]
    (-> (sl/enqueue! superlifter (->FetchPets))
        (sl/then-add-bucket! superlifter
                             :pet-details
                             (fn [pet-ids]
                               {:triggers {:queue-size {:threshold (count pet-ids)}
                                           :interval {:interval 50}}}))
        ->lacinia-promise)))

(defn- resolve-pet-details [context args {:keys [id]}]
  (-> (sl/enqueue! (get-in context [:request :superlifter]) :pet-details (->FetchPet id))
      (->lacinia-promise)))

(defn compile-schema []
  (schema/compile
   {:objects {:PetDetails {:fields {:name {:type 'String}
                                    :age {:type 'Int}}}
              :Pet {:fields {:id {:type 'String}
                             :details {:type :PetDetails
                                       :resolve resolve-pet-details}}}}
    :queries {:pets
              {:type '(list :Pet)
               :resolve resolve-pets}}}))

(def lacinia-opts {:graphiql true})

(def superlifter-args
  {:buckets {:default {:triggers {:queue-size {:threshold 1}}}}
   :urania-opts {:env {:db @pet-db}}})

(def service (lacinia/service-map
              (fn [] (compile-schema))
              (assoc lacinia-opts
                     :interceptors (into [(inject-superlifter superlifter-args)]
                                         (lacinia/default-interceptors (fn [] (compile-schema)) lacinia-opts)))))

;; This is an adapted service map, that can be started and stopped
;; From the REPL you can call server/start and server/stop on this service
(defonce runnable-service (server/create-server service))

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  (log/info "\nCreating your server...")
  (server/start runnable-service))

(comment
  (do (server/stop s)
      (def runnable-service (server/create-server service))
      (def s (server/start runnable-service))))
