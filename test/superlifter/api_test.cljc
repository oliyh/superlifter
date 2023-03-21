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

(def-superfetcher SuperPlainCustomMatch [id]
  (fn [many _env]
    (mapv (fn [m] (:id m)) many))
  (fn [_id _results])
  (fn [_id]))

(def-superfetcher SuperPromise [id]
  (fn [many _env]
    (prom/all (map (fn [m]
                     (prom/create (fn [resolve _reject]
                                    (resolve (:id m)))))
                   many))))

(def-superfetcher SuperPromiseCustomMatch [id]
  (fn [many _env]
    (prom/all (map (fn [m]
                     (prom/create (fn [resolve _reject]
                                    (resolve (:id m)))))
                   many)))
  (fn [_id _results])
  (fn [_id]))

(deftest superfetcher-test
  (async
   done
   (testing "Can use def-fetcher, def-superfetcher and all the api commands"
     (let [s (api/start! {})
           fetcher-plain (->FetcherPlain :fetcher-plain)
           fetcher-promise (->FetcherPromise :fetcher-promise)
           super-plain (->SuperPlain :super-plain)
           super-plain-custom (->SuperPlainCustomMatch :super-plain-custom)
           super-promise (->SuperPromise :super-promise)
           super-promise-custom (->SuperPromiseCustomMatch :super-promise-custom)]

       (with-superlifter s
         (doseq [fetcher [fetcher-plain fetcher-promise super-plain
                          super-plain-custom super-promise super-promise-custom]]
           (api/enqueue! fetcher))

         (prom/then (api/fetch!)
                    (fn [v]
                      (is (= [:fetcher-plain :fetcher-promise :super-plain
                              :super-plain-custom :super-promise :super-promise-custom]
                             v))

                      (is (queue-empty? (-> (s/stop! s) :buckets deref :default)))
                      (done))))))))

(defn- custom-match-data
  [ids]
  (vals (select-keys {1 {:id 1 :val "first"}
                      3 {:id 3 :val "third"}
                      4 {:id 4 :val "fourth"}
                      5 {:id 5 :val "fifth"}}
                     ids)))

(def-superfetcher SuperWithCustomMatcher [id]
  (fn [many _env]
    (custom-match-data (map :id many)))
  (fn [id results]
    (first (filter #(= id (:id %)) results)))
  (fn [id]
    {:id id :val "missing"}))

(def-superfetcher SuperPromiseWithCustomMatcher [id]
  (fn [many _env]
    (prom/all
     (list (prom/create (fn [resolve _reject]
                          (resolve (custom-match-data (map :id many))))))))
  (fn [id promises]
    (reduce (fn [_a v]
              (if-let [res (first (filter #(= id (:id %)) v))]
                (reduced res)
                nil))
            nil
            promises))
  (fn [id]
    {:id id :val "missing"}))

(deftest superfetcher-custom-matcher-test
  (async
   done
    (testing "Custom matcher is used, and missing fn is triggered when there is no match"
      (let [s (api/start! {})]
        (with-superlifter s
          (doseq [id (range 1 7)]
            (api/enqueue! (->SuperWithCustomMatcher id))
            (api/enqueue! (->SuperPromiseWithCustomMatcher id)))

          (prom/then (api/fetch!)
                     (fn [v]
                       (is (= [{:id 1 :val "first"}
                               {:id 1 :val "first"}
                               {:id 2 :val "missing"}
                               {:id 2 :val "missing"}
                               {:id 3 :val "third"}
                               {:id 3 :val "third"}
                               {:id 4 :val "fourth"}
                               {:id 4 :val "fourth"}
                               {:id 5 :val "fifth"}
                               {:id 5 :val "fifth"}
                               {:id 6 :val "missing"}
                               {:id 6 :val "missing"}]
                              v))

                       (is (queue-empty? (-> (s/stop! s) :buckets deref :default)))
                       (done))))))))
