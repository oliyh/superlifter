(ns example.server
  (:require [io.pedestal.http :as server]
            [com.walmartlabs.lacinia.pedestal :as lacinia]
            [com.walmartlabs.lacinia.schema :as schema]
            [superlifter.lacinia :refer [inject-superlifter with-superlifter]]
            [superlifter.api :as s]
            [promesa.core :as prom]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

(def pet-db (atom {"abc-123" {:name "Lyra"
                              :age 11}
                   "def-234" {:name "Pantalaimon"
                              :age 11}
                   "ghi-345" {:name "Iorek"
                              :age 41}}))

;; def-fetcher - a convenience macro like defrecord for things which cannot be combined
(s/def-fetcher FetchPets []
  (fn [_this env]
    (map (fn [id] {:id id}) (keys (:db env)))))

;; def-superfetcher - a convenience macro like defrecord for combinable things
(s/def-superfetcher FetchPet [id]
  (fn [many env]
    (log/info "Combining request for" (count many) "pets" (map :id many))
    (map (:db env) (map :id many))))

(defn- resolve-pets [context _args _parent]
  (with-superlifter context
    (-> (s/enqueue! (->FetchPets))
        (s/add-bucket! :pet-details
                       (fn [pet-ids]
                         {:instance-id (UUID/randomUUID)
                          :triggers {:queue-size {:threshold (count pet-ids)}}})))))

(defn- resolve-pet [context args _parent]
  (with-superlifter context
    (-> (prom/promise {:id (:id args)})
        (s/add-bucket! :pet-details
                       (fn [pet-ids]
                         {:instance-id (UUID/randomUUID)
                          :triggers {:queue-size {:threshold (count pet-ids)}}})))))

(defn- resolve-pet-details [context _args {:keys [id]}]
  (with-superlifter context
    (s/enqueue! :pet-details (->FetchPet id))))

(def schema
  {:objects {:PetDetails {:fields {:name {:type 'String}
                                   :age {:type 'Int}}}
             :Pet {:fields {:id {:type 'String}
                            :details {:type :PetDetails
                                      :resolve resolve-pet-details}}}}
   :queries {:pets
             {:type '(list :Pet)
              :resolve resolve-pets}
             :pet
             {:type :Pet
              :resolve resolve-pet
              :args {:id {:type 'String}}}}})

(def lacinia-opts {:graphiql true})

(def superlifter-args
  {:buckets {:default {:triggers {:queue-size {:threshold 1}}}}
   :urania-opts {:env {:db @pet-db}}})

(def service
  (lacinia/service-map
   (fn [] (schema/compile schema))
   (assoc lacinia-opts
          :interceptors (into [(inject-superlifter superlifter-args)]
                              (lacinia/default-interceptors (fn [] (schema/compile schema)) lacinia-opts)))))

;; This is an adapted service map, that can be started and stopped
;; From the REPL you can call server/start and server/stop on this service
(defonce runnable-service (server/create-server service))

(defn -main
  "The entry-point for 'lein run'"
  [& _args]
  (log/info "\nCreating your server...")
  (server/start runnable-service))

(comment
  (do (server/stop s)
      (def runnable-service (server/create-server service))
      (def s (server/start runnable-service))))
