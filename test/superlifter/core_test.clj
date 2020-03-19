(ns superlifter.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [superlifter.core :as s]
            [urania.core :as u]
            [promesa.core :as prom]
            [clojure.tools.logging :as log])
  (:refer-clojure :exclude [resolve]))

(defn- fetchable [v]
  (let [fetched? (atom false)]
    (with-meta
      (reify u/DataSource
        (u/-identity [this] v)
        (u/-fetch [this _]
          (log/info "Fetching" v)
          (prom/create (fn [resolve _reject]
                         (log/info "Delivering promise for " v)
                         (reset! fetched? true)
                         (resolve v)))))
      {:fetched? fetched?})))

(defn- fetched? [& fetchables]
  (every? true? (map (comp deref :fetched? meta) fetchables)))

(deftest callback-trigger-test
  (testing "Callback trigger mode means fetch must be run manually"
    (let [s (s/start! {})
          foo (fetchable :foo)
          bar (fetchable :bar)
          foo-promise (s/enqueue! s foo)
          bar-promise (s/enqueue! s bar)]

      (is (not (prom/resolved? foo-promise)))
      (is (not (prom/resolved? bar-promise)))
      (is (not (fetched? foo bar)))

      @(s/fetch! s)

      (is (= :foo @foo-promise))
      (is (= :bar @bar-promise))

      (is (fetched? foo bar))
      (is (empty? (-> (s/stop! s) :buckets deref :default :queue deref))))))

(deftest interval-trigger-test
  (testing "Interval trigger mode means the fetch is run every n millis"
    (let [s (s/start! {:buckets {:default {:triggers {:interval {:interval 100}}}}})
          foo (fetchable :foo)
          bar (fetchable :bar)
          foo-promise (s/enqueue! s foo)
          bar-promise (s/enqueue! s bar)]

      (is (not (prom/resolved? foo-promise)))
      (is (not (prom/resolved? bar-promise)))
      (is (not (fetched? foo bar)))

      (testing "within the next 100ms the fetch should be triggered"
        (is (= :foo (deref foo-promise 200 nil)))
        (is (= :bar (deref bar-promise 200 nil)))

        (is (fetched? foo bar))
        (is (empty? (-> (s/stop! s) :buckets deref :default :queue deref)))))))

(deftest queue-size-trigger-test
  (testing "Queue size trigger mode means the fetch is run when queue size reaches n"
    (let [s (s/start! {:buckets {:default {:triggers {:queue-size {:threshold 2}}}}})
          foo (fetchable :foo)
          bar (fetchable :bar)
          foo-promise (s/enqueue! s foo)]

      (testing "not triggered when queue size below threshold"
        (is (not (prom/resolved? foo-promise)))
        (is (not (fetched? foo bar))))

      (testing "when the queue size reaches 2 the fetch is triggered"
        (let [bar-promise (s/enqueue! s bar)]
          (is (= :foo (deref foo-promise 100 nil)))
          (is (= :bar (deref bar-promise 100 nil)))

          (is (fetched? foo bar))
          (is (empty? (-> (s/stop! s) :buckets deref :default :queue deref))))))))

(deftest multi-buckets-test
  (let [s (s/start! {:buckets {:default {:triggers {:queue-size {:threshold 1}}}
                               :ten {:triggers {:queue-size {:threshold 10}}}}})
        foo (fetchable :foo)
        bars (repeatedly 10 #(fetchable :bar))]

    (testing "default queue fetched immediately"
      (let [foo-promise (s/enqueue! s foo)]
        (is (= :foo (deref foo-promise 100 ::timeout)))
        (is (fetched? foo))))

    (testing "ten queue not fetched until queue size is 10"
      (let [first-bar-promise (s/enqueue! s :ten (first bars))]
        (is (not (prom/resolved? first-bar-promise)))
        (is (not (fetched? (first bars))))

        (let [rest-bar-promises (mapv #(s/enqueue! s :ten %) (rest bars))]
          (is (every? #(= :bar %)
                      (map #(deref % 100 ::timeout)
                           (cons first-bar-promise rest-bar-promises))))
          ;; only the first bar is fetched because urania deduped them
          (is (fetched? (first bars))))))

    (testing "adding an adhoc bucket"
      (s/add-bucket! s :pairs {:triggers {:queue-size {:threshold 2}}})
      (let [h1 (fetchable 1)
            h2 (fetchable 2)
            h1-promise (s/enqueue! s :pairs h1)]
        (is (not (prom/resolved? h1-promise)))
        (is (not (fetched? h1)))

        (let [h2-promise (s/enqueue! s :pairs h2)]
          (is (= [1 2]
                 (map #(deref % 100 ::timeout) [h1-promise h2-promise])))
          (is (fetched? h1 h2)))))

    (s/stop! s)))

(deftest cache-test
  (testing "The cache is shared across fetches and prevents dupe calls being made"
    (let [cache (atom {})
          s (s/start! {:buckets {:default {}}
                       :urania-opts {:cache cache}})
          foo (fetchable :foo)
          bar (fetchable :bar)
          foo-promise (s/enqueue! s foo)
          bar-promise (s/enqueue! s bar)]

      (is (= [:foo :bar] (deref (s/fetch-all! s) 500 ::timed-out)))

      (is (= [:foo :bar] (map deref [foo-promise bar-promise])))
      (is (fetched? foo bar))

      (let [foo-2 (fetchable :foo)
            foo-2-promise (s/enqueue! s foo-2)]
        (is (= [:foo] (deref (s/fetch-all! s) 500 ::timed-out)))
        (is (= :foo @foo-2-promise))
        (is (not (fetched? foo-2))))

      (s/stop! s))))

(deftest fetch-failure-test
  (let [s (s/start! {})
        foo (reify u/DataSource
              (u/-identity [this] :foo)
              (u/-fetch [this _]
                (prom/create (fn [_resolve reject]
                               (reject (ex-info "I blew up!" {}))))))
        foo-promise (s/enqueue! s foo)]

    (is (thrown-with-msg? Exception #"I blew up!" @(s/fetch! s)))
    (is (not (prom/resolved? foo-promise)))))
