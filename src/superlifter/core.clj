(ns superlifter.core
  (:require [urania.core :as u]
            [promesa.core :as prom])
  (:refer-clojure :exclude [resolve]))

(defn enqueue!
  "Enqueues a muse describing work to be done and returns a promise which will be delivered with the result of the work.
   The muses in the queue will all be fetched together when the trigger condition is met."
  [context muse]
  (let [p (promise)]
    (swap! (:queue context) conj (u/map (fn [result]
                                          (deliver p result))
                                        muse))
    p))

(defn fetch!
  "Performs a fetch of all muses in the queue"
  [context]
  (let [[muses] (reset-vals! (:queue context) [])]
    (u/run! (u/collect muses)
            (:urania-opts context))))

(defmulti start-trigger! (fn [context trigger-id trigger] (:kind trigger)))

(defmethod start-trigger! :queue-size [context trigger-id trigger]
  (let [threshold (:threshold trigger)
        current-threshold (atom threshold)]
    (add-watch (:queue context)
               trigger-id
               (fn [_ _ _ new-state]
                 (when (>= (count new-state) @current-threshold)
                   (reset! current-threshold threshold)
                   (future (fetch! context)))))
    (-> trigger
        (assoc :current-threshold current-threshold)
        (assoc :stop-fn #(remove-watch (:queue context) trigger-id)))))

(defmethod start-trigger! :interval [context trigger-id trigger]
  (let [watcher (future (loop []
                          (Thread/sleep (:interval trigger))
                          (fetch! context)
                          (recur)))]
    (assoc trigger :stop-fn #(future-cancel watcher))))

(defmethod start-trigger! :default [context trigger-id trigger]
  trigger)

(defn- start-triggers! [{:keys [triggers] :as context}]
  (update context :triggers
          (fn [triggers]
            (reduce-kv (fn [ts trigger-id trigger]
                         (assoc ts trigger-id (start-trigger! context trigger-id trigger)))
                       {}
                       triggers))))

(defn adjust-queue-trigger-threshold!
  "Adjusts the queue-size trigger threshold by n until the next fetch is triggered
   at which point it reverts to the original value"
  [context trigger-id n]
  (when-let [current-threshold (get-in context [:triggers trigger-id :current-threshold])]
    (swap! current-threshold + n)))

(defn start!
  "Starts a superlifter with the supplied options, which can contain:

  :triggers    Conditions to perform a fetch of all muses in the queue.
               Triggers is a map of trigger-id to trigger, looking like:

               {:size-limit {:kind :queue-size
                             :threshold 10}
                :time-limit {:kind :interval
                             :interval 100}

               The fetch will be performed whenever any single trigger condition is met.

               Triggers can be of several types:

               Queue size trigger, which performs the fetch when the queue reaches n items
               {:kind :queue-size
                :threshold n}

               Interval trigger, which performs the fetch every n milliseconds
               {:kind :interval
                :interval n}

               If not supplied, superlifter runs in 'manual' mode and fetches will only be performed when you call `fetch!`

               You can supply your own trigger definition by participating in the `start-trigger!` multimethod.

  :urania-opts The options map supplied to urania for running muses.
               Contains :env, :cache and :executor
               See urania documentation for details


  Returns a context which can be used to stop superlifter, enqueue muses and trigger a fetch.
  "
  [opts]
  (-> opts
      (assoc :queue (atom []))
      (start-triggers!)))

(defn stop!
  "Stops superlifter"
  [context]
  (doseq [[trigger-id {:keys [stop-fn]}] (:triggers context)
          :when stop-fn]
    (stop-fn))
  context)
