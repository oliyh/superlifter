(ns superlifter.api-test
  (:require [superlifter.api :as api]
            [superlifter.core :as s]
            [promesa.core :as prom]
            [clojure.test :refer [deftest testing is]]))

(api/def-fetcher FetcherPlain [id]
  (fn [_this _env]
    id))

(api/def-fetcher FetcherPromise [id]
  (fn [_this _env]
    (prom/resolved id)))

(api/def-superfetcher SuperPlain [id]
  (fn [many _env]
    (mapv (fn [m] (:id m)) many)))

(api/def-superfetcher SuperPromise [id]
  (fn [many _env]
    (prom/all (map (fn [m]
                     (prom/create (fn [resolve _reject]
                                    (resolve (:id m)))))
                   many))))

(deftest superfetcher-test
  (testing "Can use def-fetcher, def-superfetcher and all the api commands"
    (let [s (s/start! {})
          fetcher-plain (->FetcherPlain :fetcher-plain)
          fetcher-promise (->FetcherPromise :fetcher-promise)
          super-plain (->SuperPlain :super-plain)
          super-promise (->SuperPromise :super-promise)]

      (api/with-superlifter s
        (doseq [fetcher [fetcher-plain fetcher-promise super-plain super-promise]]
          (api/enqueue! fetcher)))

      (is (= [:fetcher-plain :fetcher-promise :super-plain :super-promise]
             @(s/fetch! s)))

      (is (empty? (-> (s/stop! s) :buckets deref :default :queue deref))))))
