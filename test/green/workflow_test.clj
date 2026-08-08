(ns green.workflow-test
  (:require [clojure.test :refer [deftest is testing]]
            [green.workflow :as wf]))

(defn- mark [k] (fn [o] (update o :seen (fnil conj []) k)))

(deftest linear-happy-path
  (let [w (wf/workflow {:start :t/a
                        :wire-fn (fn [s _]
                                   (case s
                                     :t/a [(mark :a) :t/b]
                                     :t/b [(mark :b) :t/c]
                                     :t/c [(mark :c)]))})
        res (wf/run w {})]
    (is (= [:a :b :c] (:seen res)))
    (is (= 0 (:green/exit res)))))

(deftest error-halts-without-next-fn
  (let [w (wf/workflow {:start :t/a
                        :wire-fn (fn [s _]
                                   (case s
                                     :t/a [(mark :a) :t/b]
                                     :t/b [(fn [o] (assoc o :green/exit 3)) :t/c]
                                     :t/c [(mark :c)]))})
        res (wf/run w {})]
    (is (= 3 (:green/exit res)))
    (is (= [:a] (:seen res)) ":t/c must not run")))

(deftest exception-becomes-exit-err-trace
  (let [w (wf/workflow {:start :t/a
                        :wire-fn (fn [s _]
                                   (case s
                                     :t/a [(fn [_] (throw (ex-info "boom" {})))
                                           :t/b]
                                     :t/b [(mark :b)]))})
        res (wf/run w {})]
    (is (= 1 (:green/exit res)))
    (is (= "boom" (:green/err res)))
    (is (string? (:green/trace res)))
    (is (nil? (:seen res)) ":t/b must not run")))

(deftest end-step-is-an-inclusive-slice-boundary
  (let [wire (fn [s _]
               (case s
                 :t/a [(mark :a) :t/b]
                 :t/b [(mark :b) :t/c]
                 :t/c [(mark :c)]))]
    (testing "end stops after running it"
      (is (= [:a :b]
             (:seen (wf/run (wf/workflow {:start :t/a :end :t/b :wire-fn wire}) {})))))
    (testing "start skips earlier steps"
      (is (= [:b :c]
             (:seen (wf/run (wf/workflow {:start :t/b :wire-fn wire}) {})))))))

(deftest wire-fn-can-select-an-event-specific-static-graph
  (let [w (wf/workflow
           {:start :t/start
            :wire-fn (fn [step run-opts]
                       (case [(:green/event run-opts) step]
                         [:create :t/start] [(mark :start) :t/node]
                         [:create :t/node] [(mark :node) :t/ansible]
                         [:create :t/ansible] [(mark :ansible)]
                         [:delete :t/start] [(mark :start) :t/ansible]
                         [:delete :t/ansible] [(mark :ansible) :t/node]
                         [:delete :t/node] [(mark :node)]))})]
    (is (= [:start :node :ansible]
           (:seen (wf/run w {:green/event :create}))))
    (is (= [:start :ansible :node]
           (:seen (wf/run w {:green/event :delete}))))))

(deftest wire-fn-uses-stable-run-opts-not-branch-opts
  (let [w (wf/workflow
           {:start :t/start
            :wire-fn (fn [step run-opts]
                       (case step
                         :t/start [identity :t/work]
                         :t/work [(mark :work)
                                  (if (= :branch (:route run-opts))
                                    :t/branch-done
                                    :t/run-done)]
                         :t/run-done [(mark :run-done)]
                         :t/branch-done [(mark :branch-done)]))
            :next-fn (fn [step default-next opts]
                       (if (= step :t/start)
                         [[:t/work (assoc opts :route :branch)]]
                         (mapv (fn [s] [s opts]) default-next)))})
        res (wf/run w {:route :run})]
    (is (= [:work :run-done] (:seen res)))
    (is (= :branch (:route res)) "the step still receives branch-local opts")))

(deftest next-fn-reroutes-errors
  (let [w (wf/workflow {:start :t/a
                        :wire-fn (fn [s _]
                                   (case s
                                     :t/a [(fn [o] (assoc o :green/exit 7)) :t/b]
                                     :t/b [(mark :b)]
                                     :t/cleanup [(fn [o] (-> o (assoc :green/exit 0)
                                                             ((mark :cleanup))))]))
                        :next-fn (fn [step dn opts]
                                   (cond
                                     (pos? (:green/exit opts 0)) (when-not (= step :t/cleanup)
                                                                   [[:t/cleanup opts]])
                                     :else (map (fn [s] [s opts]) dn)))})
        res (wf/run w {})]
    (is (= [:cleanup] (:seen res)))
    (is (= 0 (:green/exit res)))))

(deftest next-fn-nil-terminates
  (let [w (wf/workflow {:start :t/a
                        :wire-fn (fn [s _]
                                   (case s
                                     :t/a [(mark :a) :t/b]
                                     :t/b [(mark :b)]))
                        :next-fn (fn [_ _ _] nil)})
        res (wf/run w {})]
    (is (= [:a] (:seen res)))))

(deftest static-fork-and-join
  ;; :t/a forks to :t/b and :t/c; :t/c has a longer chain (:t/c -> :t/c2);
  ;; both arrive at :t/d, which must run once with both branches collected.
  (let [w (wf/workflow {:start :t/a
                        :wire-fn (fn [s _]
                                   (case s
                                     :t/a [(mark :a) :t/b :t/c]
                                     :t/b [(mark :b) :t/d]
                                     :t/c [(mark :c) :t/c2]
                                     :t/c2 [(mark :c2) :t/d]
                                     :t/d [(fn [o]
                                             (assoc o :joined
                                                    (mapv :seen (:green/branches o))))]))})
        res (wf/run w {})]
    (is (= 0 (:green/exit res)))
    (is (= 2 (count (:green/branches res))))
    (testing "join waited for the longer branch"
      (is (= #{[:a :b] [:a :c :c2]} (set (:joined res)))))
    (testing "join base opts come from the fork point"
      (is (= [:a] (:seen res))
          "join step's own opts derive from fork opts, not a branch"))))

(deftest dynamic-fan-out-and-join
  (let [w (wf/workflow {:start :t/fan
                        :wire-fn (fn [s _]
                                   (case s
                                     :t/fan [(mark :fan) :t/work]
                                     :t/work [(fn [o] (assoc o :done (:n o)))
                                              :t/join]
                                     :t/join [(fn [o]
                                                (assoc o :collected
                                                       (set (map :done (:green/branches o)))))]))
                        :next-fn (fn [step dn opts]
                                   (cond
                                     (pos? (:green/exit opts 0)) []
                                     (= step :t/fan) (for [n [1 2 3]]
                                                       [:t/work (assoc opts :n n)])
                                     :else (map (fn [s] [s opts]) dn)))})
        res (wf/run w {})]
    (is (= 0 (:green/exit res)))
    (is (= 3 (count (:green/branches res))))
    (is (= #{1 2 3} (:collected res)))))

(deftest branch-failure-skips-join-and-propagates-worst-exit
  (let [w (wf/workflow {:start :t/a
                        :wire-fn (fn [s _]
                                   (case s
                                     :t/a [(mark :a) :t/ok :t/bad]
                                     :t/ok [(mark :ok) :t/d]
                                     :t/bad [(fn [o] (assoc o :green/exit 5
                                                            :green/err "bad branch"))
                                             :t/d]
                                     :t/d [(mark :join-ran)]))})
        res (wf/run w {})]
    (is (= 5 (:green/exit res)))
    (is (= "bad branch" (:green/err res)))
    (is (not-any? #(= % :join-ran) (:seen res [])) "join must be skipped")
    (is (= 2 (count (:green/branches res))) "both branches finished")))

(deftest workflows-compose-as-steps
  (let [sub (wf/workflow {:start :s/a
                          :wire-fn (fn [s _]
                                     (case s
                                       :s/a [(mark :sub-a) :s/b]
                                       :s/b [(mark :sub-b)]))})
        parent (wf/workflow {:start :p/a
                             :wire-fn (fn [s _]
                                        (case s
                                          :p/a [(mark :p-a) :p/sub]
                                          :p/sub [(wf/step sub) :p/z]
                                          :p/z [(mark :p-z)]))})
        res (wf/run parent {})]
    (is (= 0 (:green/exit res)))
    (is (= [:p-a :sub-a :sub-b :p-z] (:seen res)))))

(deftest sub-workflow-failure-halts-the-parent
  (let [sub (wf/workflow {:start :s/a
                          :wire-fn (fn [_ _] [(fn [o] (assoc o :green/exit 4
                                                           :green/err "sub failed"))])})
        parent (wf/workflow {:start :p/sub
                             :wire-fn (fn [s _]
                                        (case s
                                          :p/sub [(wf/step sub) :p/z]
                                          :p/z [(mark :p-z)]))})
        res (wf/run parent {})]
    (is (= 4 (:green/exit res)))
    (is (= "sub failed" (:green/err res)))
    (is (nil? (:seen res)) ":p/z must not run")))

(deftest step-in-out-scope-the-sub-workflow
  (let [sub (wf/workflow {:start :s/a
                          :wire-fn (fn [_ _] [(fn [o] (assoc o :result (* 2 (:n o))))])})
        parent (wf/workflow {:start :p/sub
                             :wire-fn (fn [_ _]
                                        [(wf/step sub
                                                  {:in-fn (fn [o] {:green/event (:green/event o)
                                                                   :n (:parent-n o)})
                                                   :out-fn (fn [o r] (assoc o :doubled (:result r)))})])})
        res (wf/run parent {:green/event :create :parent-n 21 :keep-me :yes})]
    (is (= 42 (:doubled res)))
    (is (= :yes (:keep-me res)) ":out-fn preserved the parent opts")))

(deftest parallel-branches-actually-run-concurrently
  (let [in-flight (atom 0)
        peak (atom 0)
        slow (fn [o]
               (swap! peak max (swap! in-flight inc))
               (Thread/sleep 50)
               (swap! in-flight dec)
               o)
        w (wf/workflow {:start :t/a
                        :wire-fn (fn [s _]
                                   (case s
                                     :t/a [identity :t/x :t/y :t/z]
                                     :t/x [slow :t/d]
                                     :t/y [slow :t/d]
                                     :t/z [slow :t/d]
                                     :t/d [identity]))})
        res (wf/run w {:green/exit 0})]
    (is (= 0 (:green/exit res)))
    (is (< 1 @peak) "branches overlapped in time")))
