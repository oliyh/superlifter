(ns superlifter.api-test
  (:require #?(:clj [superlifter.api :as api :refer [def-fetcher def-superfetcher with-superlifter]]
               :cljs [superlifter.api :as api :refer-macros [def-fetcher def-superfetcher with-superlifter]])
            [superlifter.core :as s]
            [promesa.core :as prom]
            #?(:clj [clojure.test :refer [deftest testing is]]
               :cljs [clojure.test :refer-macros [deftest testing is async]])
            [superlifter.core-test :refer [queue-empty? #?(:clj async)]]))

(def-fetcher FetcherPlain [id]
  (fn [_this _env]
    id))

(def-fetcher FetcherPromise [id]
  (fn [_this _env]
    (prom/resolved id)))

(def-superfetcher SuperPlain [id]
  (fn [many _env]
    (mapv (fn [m] (:id m)) many)))

(def-superfetcher SuperPromise [id]
  (fn [many _env]
    (prom/all (map (fn [m]
                     (prom/create (fn [resolve _reject]
                                    (resolve (:id m)))))
                   many))))

(deftest superfetcher-test
  (async
   done
   (testing "Can use def-fetcher, def-superfetcher and all the api commands"
     (let [s (api/start! {})
           fetcher-plain (->FetcherPlain :fetcher-plain)
           fetcher-promise (->FetcherPromise :fetcher-promise)
           super-plain (->SuperPlain :super-plain)
           super-promise (->SuperPromise :super-promise)]

       (with-superlifter s
         (doseq [fetcher [fetcher-plain fetcher-promise super-plain super-promise]]
           (api/enqueue! fetcher))

         (prom/then (api/fetch!)
                    (fn [v]
                      (is (= [:fetcher-plain :fetcher-promise :super-plain :super-promise]
                             v))

                      (is (queue-empty? (-> (s/stop! s) :buckets deref :default)))
                      (done))))))))
