(ns superlifter.logging
  #?(:clj (:require [clojure.tools.logging :as log])))

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
            :debug 'log/debug
            :info 'log/info
            :warn 'log/warn
            :error 'log/error)
         ~@args))))
