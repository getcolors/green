(ns green.scope-test
  (:require [clojure.test :refer [deftest is]] [green.scope :as scope]
            [green.workflow :as wf]))

(deftest ordered-cleanup
  (let [events (atom [])]
    (is (= 42 (scope/with-scope
               (fn [register!]
                 (register! :resource #(swap! events conj :agent))
                 (register! :process #(swap! events conj :first))
                 (register! :process #(swap! events conj :second))
                 42))))
    (is (= [:second :first :agent] @events))))

(deftest failures-still-clean-up
  (let [events (atom []) error (ex-info "body" {})]
    (is (identical? error
          (try (scope/with-scope
                 (fn [register!]
                   (register! :resource #(swap! events conj :agent))
                   (register! :process #(throw (ex-info "cleanup" {})))
                   (throw error)))
               (catch Throwable e e))))
    (is (= [:agent] @events))
    (is (= 1 (count (.getSuppressed error))))))

(deftest scope-is-closed-after-body
  (let [register (atom nil)]
    (scope/with-scope #(reset! register %))
    (is (thrown? Exception (@register :resource identity)))))

(deftest parallel-workflow-finishes-before-cleanup
  (let [events (atom [])
        w (wf/workflow
           {:start :t/start
            :wire-fn (fn [step _]
                       (case step
                         :t/start [identity :t/fail :t/slow]
                         :t/fail [(fn [o] (assoc o :green/exit 1))]
                         :t/slow [(fn [o] (Thread/sleep 30)
                                    (swap! events conj :child) o)]))})]
    (scope/with-scope
      (fn [register!]
        (register! :resource #(swap! events conj :agent))
        (wf/run w {})))
    (is (= [:child :agent] @events))))

(deftest interruption-during-cleanup-does-not-abandon-finalizers
  (let [started (promise) events (atom [])
        runner (Thread.
                (fn []
                  (scope/with-scope
                    (fn [register!]
                      (register! :resource #(swap! events conj :agent))
                      (register! :process #(do (deliver started true)
                                              (Thread/sleep 30)
                                              (swap! events conj :process)))))))]
    (.start runner)
    @started
    (.interrupt runner)
    (.join runner 2000)
    (is (not (.isAlive runner)))
    (is (= [:process :agent] @events))))

(deftest interrupted-workflow-drains-siblings-before-cleanup
  (let [events (atom []) started (java.util.concurrent.CountDownLatch. 2)
        w (wf/workflow
           {:start :t/start
            :wire-fn (fn [step _]
                       (if (= :t/start step)
                         [identity :t/a :t/b]
                         [(fn [o] (.countDown started)
                            (Thread/sleep 50) (swap! events conj :child) o)]))})
        runner (Thread.
                (fn []
                  (try
                    (scope/with-scope
                      (fn [register!]
                        (register! :resource #(swap! events conj :agent))
                        (wf/run w {})))
                    (catch InterruptedException _ nil))))]
    (.start runner)
    (is (.await started 2 java.util.concurrent.TimeUnit/SECONDS))
    (.interrupt runner)
    (.join runner 2000)
    (is (not (.isAlive runner)))
    (is (= [:child :child :agent] @events))))
