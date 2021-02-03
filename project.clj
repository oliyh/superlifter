(defproject superlifter "0.1.4-SNAPSHOT"
  :description "A DataLoader for Clojure/script"
  :url "https://github.com/oliyh/superlifter"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[funcool/urania "0.2.0"]
                 [funcool/promesa "5.1.0"]
                 [org.clojure/tools.logging "0.6.0"]]
  :profiles {:provided {:dependencies [[org.clojure/clojure "1.10.1"]
                                       [org.clojure/clojurescript "1.10.597"]]}
             :test     {:dependencies [[com.walmartlabs/lacinia-pedestal "0.14.0-alpha-2"]]}
             :dev      {:dependencies [[binaryage/devtools "1.0.0"]
                                       [com.bhauman/figwheel-main "0.2.1"]
                                       [org.clojure/tools.reader "1.2.2"]
                                       [cider/piggieback "0.4.1"]
                                       [org.clojure/tools.nrepl "0.2.13"]]
                        :repl-options {:nrepl-middleware [cider.piggieback/wrap-cljs-repl]}}}
  :aliases {"fig"       ["trampoline" "run" "-m" "figwheel.main"]
            "fig:build" ["trampoline" "run" "-m" "figwheel.main" "-b" "dev" "-r"]
            "fig:min"   ["run" "-m" "figwheel.main" "-O" "advanced" "-bo" "dist"]
            "fig:test"  ["run" "-m" "figwheel.main" "-co" "test.cljs.edn" "-m" superlifter.test-runner]
            "test" ["do" ["clean"] ["test"] ["fig:test"]]})
