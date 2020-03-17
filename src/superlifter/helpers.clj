(ns superlifter.helpers
  (:require [superlifter.core :as core]
   [promesa.core :as prom]))

(def ^:dynamic *instance*)

(defn enqueue! [& args]
  (apply core/enqueue! *instance* args))

(defmacro with-superlifter [instance body]
  `(binding [*instance* ~instance]
     ~@body))

(defn add-bucket! [p id opts-fn]
  (prom/then
   p
   (bound-fn [result]
     (core/add-bucket! *instance* id (opts-fn result))
     result)))
