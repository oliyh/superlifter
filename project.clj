(defproject superlifter "0.1.6-SNAPSHOT"
  :description "A DataLoader for Clojure/script"
  :url "https://github.com/oliyh/superlifter"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[funcool/urania "0.2.0"]
                 [funcool/promesa "10.0.594"]
                 [org.clojure/tools.logging "1.2.4"]]
  :profiles {:provided {:dependencies [[org.clojure/clojure "1.11.1"]
                                       [org.clojure/clojurescript "1.11.60"]]}
             :test     {:dependencies [[com.walmartlabs/lacinia-pedestal "1.1"]]}
             :dev      {:dependencies [[binaryage/devtools "1.0.7"]
                                       [com.bhauman/figwheel-main "0.2.18"]
                                       [org.clojure/tools.reader "1.3.6"]
                                       [cider/piggieback "0.5.2"]
                                       [org.clojure/tools.nrepl "0.2.13"]]
                        :repl-options {:nrepl-middleware [cider.piggieback/wrap-cljs-repl]}}}
  :aliases {"fig"       ["trampoline" "run" "-m" "figwheel.main"]
            "fig:build" ["trampoline" "run" "-m" "figwheel.main" "-b" "dev" "-r"]
            "fig:min"   ["run" "-m" "figwheel.main" "-O" "advanced" "-bo" "dist"]
            "fig:test"  ["run" "-m" "figwheel.main" "-co" "test.cljs.edn" "-m" superlifter.test-runner]
            "test" ["do" ["clean"] ["test"] ["fig:test"]]})
