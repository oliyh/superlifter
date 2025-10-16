(defproject example "0.0.1-SNAPSHOT"
  :description "An example use of superlifter for lacinia"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.12.3"]
                 [io.pedestal/pedestal.service "0.8.0"]
                 [io.pedestal/pedestal.jetty "0.8.0"]
                 [ch.qos.logback/logback-classic "1.5.19" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "2.0.17"]
                 [org.slf4j/jcl-over-slf4j "2.0.17"]
                 [org.slf4j/log4j-over-slf4j "2.0.17"]
                 [com.walmartlabs/lacinia-pedestal "1.3.1" :exclusions [com.fasterxml.jackson.core/jackson-core]]
                 [cheshire/cheshire "6.1.0"]
                 [superlifter "0.1.6-SNAPSHOT"]
                 [io.aviso/logging "1.0"]]
  :min-lein-version "2.0.0"
  :source-paths ["src" "../src"]
  :resource-paths ["config", "resources"]
  ;; If you use HTTP/2 or ALPN, use the java-agent to pull in the correct alpn-boot dependency
  ;:java-agents [[org.mortbay.jetty.alpn/jetty-alpn-agent "2.0.5"]]
  :profiles {:dev {:aliases {"run-dev" ["trampoline" "run" "-m" "example.server/run-dev"]}
                   :dependencies [[io.pedestal/pedestal.service-tools "0.7.2"]
                                  [clj-http "3.13.1"]]}
             :uberjar {:aot [example.server]}}
  :main ^{:skip-aot true} example.server)
