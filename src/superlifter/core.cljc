(ns superlifter.core
  (:require [urania.core :as u]
            [promesa.core :as prom]
            #?(:clj [superlifter.logging :refer [log]]
               :cljs [superlifter.logging :refer-macros [log]]))
  (:refer-clojure :exclude [resolve])
  #?(:clj (:import [java.util UUID])))

#?(:cljs (def Throwable js/Error))

#?(:clj
   (defn- random-uuid []
     (UUID/randomUUID)))

(defprotocol Cache
  (->urania [this])
  (urania-> [this new-value]))

(extend-protocol Cache
  #?(:clj clojure.lang.Atom
     :cljs cljs.core/Atom)
  (->urania [this]
    (deref this))
  (urania-> [this new-value]
    (reset! this new-value)))

(def default-bucket-id :default)

(defn- bucket-for [context bucket-id]
  (let [buckets @(:buckets context)]
    (get buckets bucket-id
         (get buckets default-bucket-id))))

(defn- fetch-bucket! [bucket]
  (let [muses (:ready (first (swap-vals! (:queue bucket) (fn [queue] (assoc queue :ready [] :last-fetch-id (random-uuid))))))
        cache (get-in bucket [:urania-opts :cache])]
    (if (pos? (count muses))
      (do (log :info "Fetching" (count muses) "muses from bucket" (:id bucket))
          (-> (u/execute! (u/collect muses)
                          (merge (:urania-opts bucket)
                                 (when cache
                                   {:cache (->urania cache)})))
              (prom/then
               (fn [[result new-cache-value]]
                 (when cache
                   (urania-> cache new-cache-value))
                 result))))
      (do (log :info "Nothing ready to fetch, only" (count (:waiting @(:queue bucket))) "waiting")
          (prom/resolved nil)))))

(defn- ready-all! [bucket]
  (swap! (:queue bucket) (fn [queue]
                           (-> queue
                               (update :ready into (:waiting queue))
                               (assoc :waiting [])))))

(defn fetch!
  "Performs a fetch of all muses in the queue"
  ([context] (fetch! context default-bucket-id))
  ([context bucket-id]
   (let [bucket (bucket-for context bucket-id)]
     (ready-all! bucket)
     (fetch-bucket! bucket))))

(defn fetch-all! [context]
  (prom/then (prom/all (map (partial fetch! context) (keys @(:buckets context))))
             (fn [results]
               (reduce into [] results))))

(defn enqueue!
  "Enqueues a muse describing work to be done and returns a promise which will be delivered with the result of the work.
   The muses in the queue will all be fetched together when a trigger condition is met."
  ([context muse] (enqueue! context default-bucket-id muse))
  ([context bucket-id muse]
   (let [bucket (bucket-for context bucket-id)
         p (prom/deferred)
         delivering-muse (u/map (fn [result]
                                  (prom/resolve! p result)
                                  result)
                                muse)
         trigger-fns (keep :queue-fn (vals (:triggers bucket)))]
     (log :info "Enqueuing muse into" bucket-id (:id muse))
     ;; atomically add the muse to the queue
     ;; and let the triggers with queue predicates move items from :waiting to :ready
     (swap! (:queue bucket) (fn [queue]
                              (reduce (fn [q trigger-fn]
                                        (trigger-fn q))
                                      (update queue :waiting conj delivering-muse)
                                      trigger-fns)))
     (fetch-bucket! bucket)
     p)))

(defmulti start-trigger! (fn [_bucket kind _opts] kind))

(defn- fetch-all-handling-errors! [bucket]
  (ready-all! bucket)
  (try (prom/catch (fetch-bucket! bucket)
           (fn [error]
             (log :warn "Fetch failed" error)))
       (catch Throwable t
         (log :warn "Fetch failed" t))))

(defmethod start-trigger! :queue-size [_bucket _ {:keys [threshold] :as opts}]
  (assoc opts :queue-fn (fn [{:keys [waiting] :as queue}]
                          (log :info "Queue size trigger(" threshold "):" (count waiting) "in waiting," (count (:ready queue)) "are ready")
                          (if (<= threshold (count waiting))
                            (-> queue
                                (update :ready into (take threshold waiting))
                                (update :waiting #(drop threshold %)))
                            queue))))

(defmethod start-trigger! :interval [bucket _ opts]
  (let [watcher #?(:clj (future (loop []
                                  (Thread/sleep (:interval opts))
                                  (fetch-all-handling-errors! bucket)
                                  (recur)))
                   :cljs (js/setInterval #(fetch-all-handling-errors! bucket)
                                         (:interval opts)))]
    (assoc opts :stop-fn #?(:clj #(future-cancel watcher)
                            :cljs #(js/clearInterval watcher)))))

#?(:cljs
   (defn- check-debounced [bucket interval last-updated]
     (let [lu @last-updated]
       (cond
         (nil? lu) (js/setTimeout check-debounced interval bucket interval last-updated)

         (= :exit lu) nil

         (<= interval (- (js/Date.) lu))
         (do (fetch-all-handling-errors! bucket)
             (reset! last-updated nil)
             (js/setTimeout check-debounced 0 bucket interval last-updated))

         :else
         (js/setTimeout check-debounced (- interval (- (js/Date.) lu)) bucket interval last-updated)))))

(defmethod start-trigger! :debounced [bucket _ opts]
  (let [interval (:interval opts)
        watch-id ::queue-size
        last-updated (atom nil)
        watcher #?(:clj (future (loop []
                                  (let [lu @last-updated]
                                    (cond
                                      (nil? lu) (do (Thread/sleep interval)
                                                    (recur))

                                      (= :exit lu) nil

                                      (<= interval (- (System/currentTimeMillis) lu))
                                      (do (fetch-all-handling-errors! bucket)
                                          (reset! last-updated nil)
                                          (recur))

                                      :else
                                      (do (Thread/sleep (- interval (- (System/currentTimeMillis) lu)))
                                          (recur))))))
                   :cljs (js/setTimeout check-debounced 0 bucket interval last-updated))]
    (add-watch (:queue bucket)
               watch-id
               (fn [_ _ _ new-state]
                 (log :debug "Watching debounced" interval (count new-state) bucket)
                 (reset! last-updated #?(:clj (System/currentTimeMillis)
                                         :cljs (js/Date.)))))

    (assoc opts :stop-fn #(do #?(:clj (future-cancel watcher)
                                 :cljs (js/clearInterval watcher))
                              (reset! last-updated :exit)
                              (remove-watch (:queue bucket) watch-id)))))

(defmethod start-trigger! :default [_bucket _ opts]
  opts)

(defn- start-triggers! [{:keys [triggers] :as bucket}]
  (update bucket :triggers
          #(do
             (log :debug "Starting" (count triggers) "triggers")
             (reduce-kv (fn [ts trigger-kind trigger-opts]
                          (log :debug "Starting trigger" trigger-kind trigger-opts)
                          (assoc ts trigger-kind (start-trigger! bucket trigger-kind trigger-opts)))
                        {}
                        %))))

(defn- start-bucket! [context id opts]
  (-> opts
      (assoc :queue (atom {:ready []
                           :waiting []})
             :id id)
      (update :urania-opts #(merge (:urania-opts context) %))
      start-triggers!))

(defn- start-buckets! [{:keys [buckets] :as context}]
  (swap! buckets
         #(reduce-kv (fn [buckets id _opts]
                       (log :debug "Starting bucket" id)
                       (update buckets id (partial start-bucket! context id)))
                     %
                     %))
  context)

(defn- stop-bucket! [bucket]
  (doseq [{:keys [stop-fn]} (vals (:triggers bucket))
          :when stop-fn]
    (stop-fn))
  (fetch-all-handling-errors! bucket))

(defn add-bucket! [context id opts]
  (let [[old-buckets] (swap-vals! (:buckets context)
                                  (fn [buckets]
                                    (assoc buckets id (start-bucket! context id opts))))]
    (when-let [existing-bucket (get old-buckets id)]
      (log :warn "Overwriting bucket" id)
      (let [[existing-queue] (swap-vals! (:queue existing-bucket) assoc :waiting [])]
        (doseq [muse (:waiting existing-queue)]
          (enqueue! context id muse))
        (stop-bucket! existing-bucket))))
  context)

(defn default-opts []
  {:urania-opts {:cache (atom {})}})

(defn start!
  "Starts a superlifter with the supplied options, which can contain:

  :buckets {:default bucket-opts
            :my-bucket bucket-opts
            ...}

  :urania-opts The options map supplied to urania for running muses.
               Contains :env, :cache and :executor
               :cache must implement the Cache protocol
               See urania documentation for details

  The `:default` bucket is used for all activity not associated with a named bucket.

  Bucket options can contain the following:

  :triggers    Conditions to perform a fetch of all muses in the queue.
               Triggers is a map of trigger-kind to trigger, looking like:

               {:queue-size {:threshold 10}
                :interval   {:interval 100}

               The fetch will be performed whenever any single trigger condition is met.

               Triggers can be of several types:

               Queue size trigger, which performs the fetch when the queue reaches n items
               {:queue-size {:threshold n}}

               Interval trigger, which performs the fetch every n milliseconds
               {:interval {:interval n}}

               If no triggers are supplied, superlifter runs in 'manual' mode and fetches will only be performed when you call `fetch!`

               You can supply your own trigger definition by participating in the `start-trigger!` multimethod.

  :urania-opts Override the top-level urania-opts at the bucket level


  Returns a context which can be used to stop superlifter, enqueue muses and trigger fetches.
  "
  [opts]
  (-> (merge (default-opts) opts)
      (update-in [:buckets default-bucket-id] #(or % {}))
      (update :buckets atom)
      (start-buckets!)))

(defn stop!
  "Stops superlifter"
  [context]
  (run! stop-bucket! (vals @(:buckets context)))
  context)
