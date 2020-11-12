(ns superlifter.core
  (:require [urania.core :as u]
            [promesa.core :as prom]
            #?(:clj [superlifter.logging :refer [log]]
               :cljs [superlifter.logging :refer-macros [log]]))
  (:refer-clojure :exclude [resolve]))

#?(:cljs (def Throwable js/Error))

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

(defn- clear-ready [bucket]
  (update bucket :queue assoc :ready []))

(defn- ready-all [bucket]
  (update bucket :queue (fn [queue]
                          (-> (assoc queue :waiting [])
                              (assoc :ready (:waiting queue))))))

(defn- describe-queue [queue]
  (str "ready: " (count (:ready queue)) ", waiting: " (count (:waiting queue))))

(defn- update-bucket! [context bucket-id f]
  (let [[old new] (map #(get % bucket-id) (swap-vals! (:buckets context) #(update % bucket-id (comp f clear-ready))))
        _ (log :debug "Update bucket called:" bucket-id (describe-queue (:queue old)) "->" (describe-queue (:queue new)))
        fetches (if-let [muses (not-empty (get-in new [:queue :ready]))]
                  (let [cache (get-in new [:urania-opts :cache])]
                    (log :info "Fetching" (count muses) "muses from bucket" bucket-id)
                    (-> (u/execute! (u/collect muses)
                                    (merge (:urania-opts new)
                                           (when cache
                                             {:cache (->urania cache)})))
                        (prom/then
                         (fn [[result new-cache-value]]
                           (when cache
                             (urania-> cache new-cache-value))
                           result))))
                  (do (log :debug "Nothing ready to fetch for" bucket-id)
                      (prom/resolved nil)))]

    {:old old
     :new new
     :fetches fetches}))

(defn- fetch-bucket! [context bucket-id]
  ;; todo this can just return the fetches promise
  (update-bucket! context bucket-id ready-all))

(defn fetch!
  "Performs a fetch of all muses in the queue"
  ([context] (fetch! context default-bucket-id))
  ([context bucket-id]
   (:fetches (fetch-bucket! context bucket-id))))

(defn fetch-all! [context]
  (prom/then (prom/all (map (partial fetch! context) (keys @(:buckets context))))
             (fn [results]
               (reduce into [] results))))

(defn enqueue!
  "Enqueues a muse describing work to be done and returns a promise which will be delivered with the result of the work.
   The muses in the queue will all be fetched together when a trigger condition is met."
  ([context muse] (enqueue! context default-bucket-id muse))
  ([context bucket-id muse]
   (let [p (prom/deferred)
         delivering-muse (u/map (fn [result]
                                  (prom/resolve! p result)
                                  result)
                                muse)]
     (log :debug "Enqueuing muse into" bucket-id (:id muse))
     (update-bucket! context
                     bucket-id
                     (fn [bucket]
                       (update bucket :queue (fn [queue]
                                               (reduce (fn [q trigger-fn]
                                                         (trigger-fn q))
                                                       (update queue :waiting conj delivering-muse)
                                                       (keep :queue-fn (vals (:triggers bucket))))))))
     p)))

(defn- fetch-all-handling-errors! [context bucket-id]
  (try (prom/catch (:fetches (fetch-bucket! context bucket-id))
           (fn [error]
             (log :warn "Fetch failed" error)))
       (catch Throwable t
         (log :warn "Fetch failed" t))))

(defmulti start-trigger! (fn [kind _context _bucket-id _opts] kind))

(defmethod start-trigger! :queue-size [_ _context bucket-id {:keys [threshold] :as opts}]
  (assoc opts :queue-fn (fn [queue]
                          (log :debug "Bucket" bucket-id "queue-size trigger(" threshold "):" (describe-queue queue))
                          (if (<= threshold (count (:waiting queue)))
                            (-> queue
                                (assoc :ready (take threshold (:waiting queue)))
                                (update :waiting #(drop threshold %)))
                            queue))))

(defmethod start-trigger! :interval [_ context bucket-id opts]
  (let [watcher #?(:clj (future (loop []
                                  (Thread/sleep (:interval opts))
                                  (log :debug "Bucket" bucket-id "interval trigger(" (:interval opts) ") running")
                                  (fetch-all-handling-errors! context bucket-id)
                                  (recur)))
                   :cljs (js/setInterval #(fetch-all-handling-errors! context bucket-id)
                                         (:interval opts)))]
    ;; todo rewrite this to be a queue-fn that checks if the interval has elapsed since the last update
    ;; could write current timestamp into the queue, and check if it is more than the interval ago
    ;; when it's next updated, if so then move everything into ready
    ;; does this make sense when it's all been quiet and then the first of 8 muses is added? they might normally
    ;; fall into one bucket but this would force two bucktes - the first with the first, and the second with the rest
    (assoc opts :stop-fn #?(:clj #(future-cancel watcher)
                            :cljs #(js/clearInterval watcher)))))

#?(:cljs
   (defn- check-debounced [context bucket-id interval last-updated]
     (let [lu @last-updated]
       (cond
         (nil? lu) (js/setTimeout check-debounced interval context bucket-id interval last-updated)

         (= :exit lu) nil

         (<= interval (- (js/Date.) lu))
         (do (fetch-all-handling-errors! context bucket-id)
             (reset! last-updated nil)
             (js/setTimeout check-debounced 0 context bucket-id interval last-updated))

         :else
         (js/setTimeout check-debounced (- interval (- (js/Date.) lu)) context bucket-id interval last-updated)))))

(defmethod start-trigger! :debounced [_ context bucket-id opts]
  (let [interval (:interval opts)
        last-updated (atom nil)
        watcher #?(:clj (future (loop []
                                  (let [lu @last-updated]
                                    (cond
                                      (nil? lu) (do (Thread/sleep interval)
                                                    (recur))

                                      (= :exit lu) nil

                                      (<= interval (- (System/currentTimeMillis) lu))
                                      (do (fetch-all-handling-errors! context bucket-id)
                                          (reset! last-updated nil)
                                          (recur))

                                      :else
                                      (do (Thread/sleep (- interval (- (System/currentTimeMillis) lu)))
                                          (recur))))))
                   :cljs (js/setTimeout check-debounced 0 context bucket-id interval last-updated))]
    ;; todo remove the watcher thread, implement in a similar way to the interval trigger
    (assoc opts
           :queue-fn (fn [queue]
                       (log :debug "Bucket" bucket-id "debounced trigger(" interval "):" (describe-queue queue))
                       (reset! last-updated #?(:clj (System/currentTimeMillis)
                                               :cljs (js/Date.)))
                       queue)
           :stop-fn #(do #?(:clj (future-cancel watcher)
                            :cljs (js/clearInterval watcher))
                         (reset! last-updated :exit)))))

(defmethod start-trigger! :default [_context _bucket-id _trigger-kind opts]
  opts)

(defn- start-triggers! [context id {:keys [triggers] :as opts}]
  (update opts :triggers
          #(do
             (log :debug "Starting" (count triggers) "triggers")
             (reduce-kv (fn [ts trigger-kind trigger-opts]
                          (log :debug "Starting trigger" trigger-kind trigger-opts)
                          (assoc ts trigger-kind (start-trigger! trigger-kind context id trigger-opts)))
                        {}
                        %))))

(defn- start-bucket! [context id opts]
  (start-triggers! context id (-> (assoc opts :queue {:ready [] :waiting []} :id id)
                                  (update :urania-opts #(merge (:urania-opts context) %)))))

(defn- start-buckets! [{:keys [buckets] :as context}]
  (swap! buckets
         #(reduce-kv (fn [buckets id _opts]
                       (log :debug "Starting bucket" id)
                       (update buckets id (partial start-bucket! context id)))
                     %
                     %))
  context)

(defn- stop-bucket! [context bucket-id]
  (doseq [{:keys [stop-fn]} (vals (:triggers (get @(:buckets context) bucket-id)))
          :when stop-fn]
    (stop-fn)))

(defn add-bucket! [context bucket-id opts]
  (swap-vals! (:buckets context)
              (fn [buckets]
                (if (contains? buckets bucket-id)
                  (do (log :warn "Bucket" bucket-id "already exists")
                      buckets)
                  ;; todo weird that context is passed into start-bucket?
                  (assoc buckets bucket-id (start-bucket! context bucket-id opts)))))
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
  (run! (partial stop-bucket! context) (keys @(:buckets context)))
  context)
