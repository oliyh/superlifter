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

(defmulti start-trigger! (fn [context] (get-in context [:trigger :kind])))

(defmethod start-trigger! :queue-size [context]
  (let [threshold (get-in context [:trigger :threshold])
        current-threshold (atom threshold)]
    (add-watch (:queue context)
               :queue-size-trigger
               (fn [_ _ _ new-state]
                 (when (>= (count new-state) @current-threshold)
                   (reset! current-threshold threshold)
                   (future (fetch! context)))))
    (-> context
        (assoc-in [:trigger :current-threshold] current-threshold)
        (assoc :cancel-trigger #(remove-watch (:queue context) :queue-size-trigger)))))

(defmethod start-trigger! :interval [context]
  (let [watcher (future (loop []
                          (Thread/sleep (get-in context [:trigger :interval]))
                          (fetch! context)
                          (recur)))]
    (assoc context :cancel-trigger #(future-cancel watcher))))

(defmethod start-trigger! :default [context]
  context)

(defn adjust-queue-trigger-threshold!
  "Adjusts the queue-size trigger threshold by n until the next fetch is triggered
   at which point it reverts to the original value"
  [context n]
  (when-let [current-threshold (get-in context [:trigger :current-threshold])]
    (swap! current-threshold + n)))

(defn start!
  "Starts a superlifter with the supplied options, which can contain:

  :trigger Condition to perform a fetch of all muses in the queue.
           Can be one of several options:

           Queue size trigger, which performs the fetch when the queue reaches n items
           {:kind :queue-size
            :threshold n}

           Interval trigger, which performs the fetch every n milliseconds
           {:kind :interval
            :millis n}

           Callback trigger, which performs the fetch whenever the callback is called
           {:kind :callback}

  :urania-opts The options map supplied to urania for running muses.
               Contains :env, :cache and :executor
               See urania documentation for details


  Returns a context which can be used to stop superlifter, enqueue muses and trigger a fetch.
  "
  [opts]
  (-> opts
      (assoc :queue (atom []))
      (start-trigger!)))

(defn stop!
  "Stops superlifter"
  [context]
  (when-let [f (:cancel-trigger context)]
    (f))
  context)
