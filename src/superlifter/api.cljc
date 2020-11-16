(ns superlifter.api
  (:require [superlifter.core :as core]
            [promesa.core :as prom]
            [urania.core :as u]))

(defn unwrap
  ([p] (unwrap identity p))
  ([f p]
   (if (prom/promise? p)
     (prom/then p f)
     (prom/resolved (f p)))))

#?(:clj (defmacro def-fetcher [sym bindings do-fetch-fn]
          `(defrecord ~sym ~bindings
             u/DataSource
             (-identity [this#] (:id this#))
             (-fetch [this# env#]
               (unwrap (~do-fetch-fn this# env#))))))

#?(:clj (defmacro def-superfetcher [sym bindings do-fetch-fn]
          `(defrecord ~sym ~bindings
             u/DataSource
             (-identity [this#] (:id this#))
             (-fetch [this# env#]
               (unwrap first (~do-fetch-fn [this#] env#)))

             u/BatchedSource
             (-fetch-multi [muse# muses# env#]
               (let [muses# (cons muse# muses#)]
                 (unwrap (fn [responses#]
                           (zipmap (map u/-identity muses#)
                                   responses#))
                         (~do-fetch-fn muses# env#)))))))


(def ^:dynamic *instance*)

#?(:clj (defmacro with-superlifter [instance & body]
          `(binding [*instance* ~instance]
             ~@body)))

(defn enqueue!
  "Enqueues a muse describing work to be done and returns a promise which will be delivered with the result of the work.
   The muses in the queue will all be fetched together when the trigger condition is met."
  [& args]
  (apply core/enqueue! *instance* args))

(defn fetch!
  "Performs a fetch of all muses in the queue for the given bucket, or the default bucket if not specified"
  [& args]
  (apply core/fetch! *instance* args))

(defn fetch-all!
  "Performs a fetch of all muses in the queues of all buckets"
  [& args]
  (apply core/fetch-all! *instance* args))

(defn add-bucket! [p id opts-fn]
  (unwrap (bound-fn [result]
            (core/add-bucket! *instance* id (opts-fn result))
            result)
          p))

(defn update-trigger! [p bucket-id trigger-kind opts-fn]
  (unwrap (bound-fn [result]
            (core/update-trigger! *instance* bucket-id trigger-kind #(opts-fn % result))
            result)
          p))

(def start! core/start!)
(def stop! core/stop!)
