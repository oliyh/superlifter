(ns superlifter.logging
  #?(:clj (:require [clojure.tools.logging])))

#?(:clj
   (defmacro log [level & args]
     (if (:ns &env)
       `(~(condp = level
            :debug 'js/console.debug
            :info 'js/console.info
            :warn 'js/console.warn
            :error 'js/console.error)
         ~@args)
       `(~(condp = level
            :debug 'clojure.tools.logging/debug
            :info 'clojure.tools.logging/info
            :warn 'clojure.tools.logging/warn
            :error 'clojure.tools.logging/error)
         ~@args))))
