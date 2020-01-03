(ns superlifter.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [superlifter.core :as s]
            [urania.core :as u]
            [promesa.core :as prom])
  (:refer-clojure :exclude [resolve]))

(defn- fetchable [v]
  (let [fetched? (atom false)]
    (with-meta
      (reify u/DataSource
        (u/-identity [this] v)
        (u/-fetch [this _]
          (prom/create (fn [resolve reject]
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
      (is (empty? @(:queue (s/stop! s)))))))

(deftest interval-trigger-test
  (testing "Interval trigger mode means the fetch is run every n millis"
    (let [s (s/start! {:triggers {:hundred-millis {:kind :interval
                                                   :interval 100}}})
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
        (is (empty? @(:queue (s/stop! s))))))))

(deftest queue-size-trigger-test
  (testing "Queue size trigger mode means the fetch is run when queue size reaches n"
    (let [s (s/start! {:triggers {:max-two {:kind :queue-size
                                            :threshold 2}}})
          foo (fetchable :foo)
          bar (fetchable :bar)
          foo-promise (s/enqueue! s foo)]

      (is (not (prom/resolved? foo-promise)))
      (is (not (fetched? foo bar)))

      (testing "when the queue size reaches 2 the fetch is triggered"
        (let [bar-promise (s/enqueue! s bar)]
          (is (= :foo (deref foo-promise 100 nil)))
          (is (= :bar (deref bar-promise 100 nil)))

          (is (fetched? foo bar))
          (is (empty? @(:queue (s/stop! s))))))))

  (testing "The queue size can be increased during operation"
    (let [s (s/start! {:triggers {:max-two {:kind :queue-size
                                            :threshold 2}}})
          foo (fetchable :foo)
          bar (fetchable :bar)
          quu (fetchable :quu)
          foo-promise (s/enqueue! s foo)]

      (is (not (prom/resolved? foo-promise)))
      (is (not (fetched? foo bar)))

      (testing "can increase the queue size by 1 to be 3"
        (s/adjust-queue-trigger-threshold! s :max-two 1)
        (is (= 3 @(get-in s [:triggers :max-two :current-threshold])))

        (testing "so when the queue size reaches 2 the fetch is not triggered"
          (let [bar-promise (s/enqueue! s bar)]

            (is (not (prom/resolved? foo-promise)))
            (is (not (prom/resolved? bar-promise)))
            (is (not (fetched? foo bar)))

            (testing "but when the 3rd item is added the fetch is triggered"
              (let [quu-promise (s/enqueue! s quu)]

                (is (= :foo (deref foo-promise 100 nil)))
                (is (= :bar (deref bar-promise 100 nil)))
                (is (= :quu (deref quu-promise 100 nil)))

                (is (fetched? foo bar quu))
                (is (empty? @(:queue (s/stop! s))))
                (is (= 2 @(get-in s [:triggers :max-two :current-threshold])))))))))))

(deftest fetch-failure-test
  (let [s (s/start! {})
        foo (reify u/DataSource
              (u/-identity [this] :foo)
              (u/-fetch [this _]
                (prom/create (fn [resolve reject]
                               (reject (ex-info "I blew up!" {}))))))
        foo-promise (s/enqueue! s foo)]

    (is (thrown-with-msg? Exception #"I blew up!" @(s/fetch! s)))
    (is (not (prom/resolved? foo-promise)))))
