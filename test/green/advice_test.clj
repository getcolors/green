(ns green.advice-test
  (:require [clojure.test :refer [deftest is testing]]
            [green.advice :as advice]
            [green.workflow :as wf]))

(defn- log [o x] (update o :log (fnil conj []) x))

(defn- single-step-wf []
  (wf/workflow {:start :t/step
                :wire-fn (fn [_ _] [(fn [o] (log o :base))])}))

(deftest filter-return-and-lifo-stacking
  (let [base (single-step-wf)
        advised (-> base
                    (wf/advice-add :t/step :filter-return ::a #(log % :a))
                    (wf/advice-add :t/step :filter-return ::b #(log % :b)))]
    (testing "most recently added advice is outermost"
      (is (= [:base :a :b] (:log (wf/run advised {})))))
    (testing "the original workflow is untouched (workflow-scoped advice)"
      (is (= [:base] (:log (wf/run base {})))))))

(deftest advice-remove-by-id
  (let [advised (-> (single-step-wf)
                    (wf/advice-add :t/step :filter-return ::a #(log % :a))
                    (wf/advice-add :t/step :filter-return ::b #(log % :b))
                    (wf/advice-remove :t/step ::a))]
    (is (= [:base :b] (:log (wf/run advised {}))))))

(deftest re-adding-same-id-replaces-and-moves-to-top
  (let [advised (-> (single-step-wf)
                    (wf/advice-add :t/step :filter-return ::a #(log % :a1))
                    (wf/advice-add :t/step :filter-return ::b #(log % :b))
                    (wf/advice-add :t/step :filter-return ::a #(log % :a2)))]
    (is (= [:base :b :a2] (:log (wf/run advised {}))))))

(deftest override-replaces-base
  (let [advised (-> (single-step-wf)
                    (wf/advice-add :t/step :override ::o #(log % :override)))]
    (is (= [:override] (:log (wf/run advised {}))))))

(deftest around-controls-the-call
  (let [advised (-> (single-step-wf)
                    (wf/advice-add :t/step :around ::ar
                                   (fn [f o] (log (f (log o :in)) :out))))]
    (is (= [:in :base :out] (:log (wf/run advised {}))))))

(deftest filter-args-transforms-input
  (let [advised (-> (single-step-wf)
                    (wf/advice-add :t/step :filter-args ::fa #(assoc % :x 1)))]
    (is (= 1 (:x (wf/run advised {}))))))

(deftest before-and-after-run-for-side-effects
  (let [seen (atom [])
        advised (-> (single-step-wf)
                    (wf/advice-add :t/step :before ::b
                                   (fn [o] (swap! seen conj [:before (:log o)]) o))
                    (wf/advice-add :t/step :after ::a
                                   (fn [o] (swap! seen conj [:after (:log o)]) o)))
        res (wf/run advised {})]
    (testing "before and after both see the step's input opts (Emacs: both
              get r); their returns are ignored and OLDFUN's value flows out"
      (is (= [:base] (:log res)))
      (is (= [[:before nil] [:after nil]] @seen)))))

(deftest before-runs-newest-to-oldest-after-runs-oldest-to-newest
  (let [seen (atom [])
        note (fn [k] (fn [o] (swap! seen conj k) o))
        advised (-> (single-step-wf)
                    (wf/advice-add :t/step :before ::b1 (note :b1))
                    (wf/advice-add :t/step :before ::b2 (note :b2))
                    (wf/advice-add :t/step :after ::a1 (note :a1))
                    (wf/advice-add :t/step :after ::a2 (note :a2)))]
    (wf/run advised {})
    (is (= [:b2 :b1 :a1 :a2] @seen))))

(deftest throwing-advice-fails-the-step-through-the-contract
  (let [advised (-> (single-step-wf)
                    (wf/advice-add :t/step :before ::boom
                                   (fn [_] (throw (ex-info "advice boom" {})))))
        res (wf/run advised {})]
    (is (= 1 (:green/exit res)))
    (is (= "advice boom" (:green/err res)))
    (is (string? (:green/trace res)))))

(defn- two-step-wf []
  (wf/workflow {:start :t/a
               :wire-fn (fn [s _]
                          (case s
                            :t/a [(fn [o] (log o :a)) :t/b]
                            :t/b [(fn [o] (log o :b))]))}))

(deftest advice-add-all-applies-to-every-step
  (let [advised (-> (two-step-wf)
                    (wf/advice-add-all :filter-return ::tag #(log % :all)))]
    (is (= [:a :all :b :all] (:log (wf/run advised {}))))))

(deftest advice-add-all-interleaves-in-strict-add-order-with-per-step
  (let [advised (-> (two-step-wf)
                    (wf/advice-add-all :filter-return ::g1 #(log % :g1))
                    (wf/advice-add :t/a :filter-return ::pa #(log % :pa))
                    (wf/advice-add-all :filter-return ::g2 #(log % :g2)))
        res (wf/run advised {})]
    (testing ":t/a ran with both global entries plus its own, in add order"
      (is (= [:a :g1 :pa :g2] (subvec (:log res) 0 4))))
    (testing ":t/b, with no per-step advice, only saw the globals in add order"
      (is (= [:b :g1 :g2] (subvec (:log res) 4))))))

(deftest advice-remove-all-removes-the-global-entry
  (let [advised (-> (two-step-wf)
                    (wf/advice-add-all :filter-return ::g #(log % :g))
                    (wf/advice-remove-all ::g))]
    (is (= [:a :b] (:log (wf/run advised {}))))))

;; --- the while/until combinators -----------------------------------------

(defn- entry [how f seq]
  {:id (gensym "adv") :how how :fn f :seq seq :depth 0})

(deftest before-while-gates-the-inward-call
  (let [seen (atom [])
        base (fn [o] (swap! seen conj :base) (log o :base))]
    (testing "truthy advice lets the chain run; base sees the original opts"
      (reset! seen [])
      (let [f (advice/compose base [(entry :before-while
                                           (fn [_] (swap! seen conj :w) true) 0)])]
        (is (= [:base] (:log (f {}))))
        (is (= [:w :base] @seen))))
    (testing "nil advice short-circuits: nothing inward runs, result is nil"
      (reset! seen [])
      (let [f (advice/compose base [(entry :before-while
                                           (fn [_] (swap! seen conj :w) nil) 0)])]
        (is (nil? (f {})))
        (is (= [:w] @seen))))))

(deftest before-while-stack-runs-newest-to-oldest-and-stops-on-nil
  (let [seen (atom [])
        base (fn [o] (swap! seen conj :base) o)
        f (advice/compose base
                          [(entry :before-while (fn [_] (swap! seen conj :old) true) 0)
                           (entry :before-while (fn [_] (swap! seen conj :new) nil) 1)])]
    (is (nil? (f {})))
    (is (= [:new] @seen) "the newest gate is outermost and stops the chain")))

(deftest before-until-short-circuits-on-non-nil
  (testing "a non-nil advice return is the result; base never runs"
    (let [advised (-> (single-step-wf)
                      (wf/advice-add :t/step :before-until ::bu #(log % :bu)))]
      (is (= [:bu] (:log (wf/run advised {}))))))
  (testing "a nil advice return falls through to the chain"
    (let [advised (-> (single-step-wf)
                      (wf/advice-add :t/step :before-until ::bu (fn [_] nil)))]
      (is (= [:base] (:log (wf/run advised {})))))))

(deftest after-while-runs-oldest-to-newest-and-stops-on-nil
  (let [seen (atom [])
        base (fn [o] (swap! seen conj :base) o)
        f (advice/compose base
                          [(entry :after-while (fn [_] (swap! seen conj :old) nil) 0)
                           (entry :after-while (fn [_] (swap! seen conj :new) true) 1)])]
    (is (nil? (f {})))
    (is (= [:base :old] @seen)
        "base first, then oldest advice; its nil skips the newer one")))

(deftest after-while-and-after-until-use-green-exit-as-truth
  (testing ":after-while does not run after a failing step result"
    (let [seen (atom [])
          f (advice/compose (fn [o]
                              (swap! seen conj :base)
                              (assoc (log o :base) :green/exit 7))
                            [(entry :after-while
                                    (fn [o]
                                      (swap! seen conj :after)
                                      (assoc (log o :after) :green/exit 0))
                                    0)])
          ret (f {})]
      (is (= 7 (:green/exit ret)))
      (is (= [:base] (:log ret)))
      (is (= [:base] @seen))))
  (testing ":after-until falls through to advice after a failing step result"
    (let [seen (atom [])
          f (advice/compose (fn [o]
                              (swap! seen conj :base)
                              (assoc (log o :base) :green/exit 7))
                            [(entry :after-until
                                    (fn [o]
                                      (swap! seen conj :recover)
                                      (assoc (log o :recover) :green/exit 0))
                                    0)])
          ret (f {})]
      (is (= 0 (:green/exit ret)))
      (is (= [:recover] (:log ret)))
      (is (= [:base :recover] @seen)))))

(deftest after-until-supplies-a-result-when-the-chain-returns-nil
  (testing "compose level: advice only fires when the inward call is nil"
    (let [f (advice/compose (fn [_] nil)
                            [(entry :after-until (fn [o] (log o :fallback)) 0)])]
      (is (= [:fallback] (:log (f {})))))
    (let [f (advice/compose (fn [o] (log o :base))
                            [(entry :after-until (fn [o] (log o :fallback)) 0)])]
      (is (= [:base] (:log (f {}))) "a non-nil chain result short-circuits")))
  (testing "in a workflow: a before-while gate nils the step out and an
            outer after-until turns that into a real result"
    (let [advised (-> (single-step-wf)
                      (wf/advice-add :t/step :before-while ::gate (fn [_] nil))
                      (wf/advice-add :t/step :after-until ::fallback #(log % :fallback)))
          res (wf/run advised {})]
      (is (= [:fallback] (:log res)))
      (is (= 0 (:green/exit res))))))

(deftest after-until-stack-runs-oldest-to-newest
  (let [seen (atom [])
        f (advice/compose (fn [_] (swap! seen conj :base) nil)
                          [(entry :after-until (fn [_] (swap! seen conj :old) nil) 0)
                           (entry :after-until (fn [_] (swap! seen conj :new) :hit) 1)])]
    (is (= :hit (f {})))
    (is (= [:base :old :new] @seen))))

;; --- depth ----------------------------------------------------------------

(deftest depth-overrides-add-order
  (let [advised (-> (single-step-wf)
                    (wf/advice-add :t/step :filter-return ::outer #(log % :outer) {:depth -50})
                    (wf/advice-add :t/step :filter-return ::mid #(log % :mid))
                    (wf/advice-add :t/step :filter-return ::inner #(log % :inner) {:depth 50}))]
    (testing "lower depth is more outward, higher more inward, whatever the add order"
      (is (= [:base :inner :mid :outer] (:log (wf/run advised {})))))))

(deftest equal-depth-falls-back-to-newest-outermost
  (let [advised (-> (single-step-wf)
                    (wf/advice-add :t/step :filter-return ::a #(log % :a) {:depth -50})
                    (wf/advice-add :t/step :filter-return ::b #(log % :b) {:depth -50}))]
    (is (= [:base :a :b] (:log (wf/run advised {}))))))

(deftest depth-applies-across-per-step-and-all-steps-advice
  (let [advised (-> (two-step-wf)
                    (wf/advice-add-all :filter-return ::g #(log % :g))
                    (wf/advice-add :t/a :filter-return ::p #(log % :p) {:depth 100}))
        res (wf/run advised {})]
    (testing "depth 100 pushes the later-added per-step advice inside the global one"
      (is (= [:a :p :g] (subvec (:log res) 0 3))))
    (is (= [:b :g] (subvec (:log res) 3)))))

(deftest invalid-how-and-out-of-range-depth-are-rejected-at-add-time
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsupported advice combinator"
                        (wf/advice-add (single-step-wf) :t/step :befor ::typo identity)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":depth must be in -100..100"
                        (wf/advice-add (single-step-wf) :t/step :before ::deep identity
                                       {:depth 101}))))

;; --- cross-workflow inheritance (advice through wf/step) -------------------

(defn- embed
  "A parent workflow that runs :p/a, then `sub` as an embedded step, then :p/z."
  [sub]
  (wf/workflow {:start :p/a
                :wire-fn (fn [s _]
                           (case s
                             :p/a [(fn [o] (log o :p-a)) :p/sub]
                             :p/sub [(wf/step sub) :p/z]
                             :p/z [(fn [o] (log o :p-z))]))}))

(deftest parent-advice-reaches-an-embedded-step
  (let [sub (single-step-wf)
        parent (-> (embed sub)
                   (wf/advice-add :t/step :filter-return ::p #(log % :p)))]
    (is (= [:p-a :base :p :p-z] (:log (wf/run parent {}))))
    (testing "the child value is untouched — standalone runs stay bare"
      (is (= [:base] (:log (wf/run sub {})))))))

(deftest parent-override-redefines-an-embedded-step
  (let [parent (-> (embed (single-step-wf))
                   (wf/advice-add :t/step :override ::o #(log % :redefined)))]
    (is (= [:p-a :redefined :p-z] (:log (wf/run parent {}))))))

(deftest inherited-advice-is-outermost
  (let [sub (-> (single-step-wf)
                (wf/advice-add :t/step :filter-return ::c #(log % :child)))
        parent (-> (embed sub)
                   (wf/advice-add :t/step :filter-return ::p #(log % :parent)))]
    (is (= [:p-a :base :child :parent :p-z] (:log (wf/run parent {}))))))

(deftest same-id-parent-advice-replaces-the-childs
  (let [sub (-> (single-step-wf)
                (wf/advice-add :t/step :filter-return ::x #(log % :child-x)))
        parent (-> (embed sub)
                   (wf/advice-add :t/step :filter-return ::x #(log % :parent-x)))]
    (is (= [:p-a :base :parent-x :p-z] (:log (wf/run parent {}))))))

(deftest depth-overrides-inheritance-order
  (let [sub (-> (single-step-wf)
                (wf/advice-add :t/step :filter-return ::c #(log % :child) {:depth -50}))
        parent (-> (embed sub)
                   (wf/advice-add :t/step :filter-return ::p #(log % :parent)))]
    (testing "the child's depth -50 stays outside the parent's depth-0 advice"
      (is (= [:p-a :base :parent :child :p-z] (:log (wf/run parent {})))))))

(deftest advice-add-all-propagates-into-embeds
  (let [parent (-> (embed (single-step-wf))
                   (wf/advice-add-all :filter-return ::g #(log % :g)))]
    (testing "every inner step is wrapped, and so is the embed step itself"
      (is (= [:p-a :g :base :g :g :p-z :g] (:log (wf/run parent {})))))))

(deftest inheritance-survives-a-scoping-in-fn
  (let [parent (-> (wf/workflow {:start :p/sub
                                 :wire-fn (fn [_ _]
                                            [(wf/step (single-step-wf)
                                                      {:in-fn (fn [o] {:n (:n o)})})])})
                   (wf/advice-add :t/step :filter-return ::p #(log % :p)))]
    (testing ":in-fn built sub-opts from scratch; the engine re-stamped the registry"
      (is (= [:base :p] (:log (wf/run parent {:n 1})))))))

(deftest inheritance-is-transitive-through-nested-embeds
  (let [inner (single-step-wf)
        mid (wf/workflow {:start :m/sub :wire-fn (fn [_ _] [(wf/step inner)])})
        top (-> (wf/workflow {:start :top/sub :wire-fn (fn [_ _] [(wf/step mid)])})
                (wf/advice-add :t/step :filter-return ::p #(log % :top)))]
    (is (= [:base :top] (:log (wf/run top {}))))))

(deftest the-inherited-registry-does-not-leak-into-results
  (let [parent (-> (embed (single-step-wf))
                   (wf/advice-add :t/step :filter-return ::p identity))
        res (wf/run parent {})]
    (is (not (contains? res :green.workflow/inherited)))))

(deftest advice-plan-shows-the-cross-workflow-stack
  (let [sub (-> (single-step-wf)
                (wf/advice-add :t/step :before ::backend identity)
                (wf/advice-add :t/step :filter-return ::inner identity {:depth 50}))
        parent (-> (embed sub)
                   (wf/advice-add :t/step :before ::backend identity)
                   (wf/advice-add-all :around ::audit (fn [f o] (f o))))]
    (testing "outermost first, with provenance; the parent's ::backend replaced the child's"
      (is (= [[::audit :all 0] [::backend :step 0] [::inner :step 1]]
             (mapv (juxt :id :scope :level) (wf/advice-plan [parent sub] :t/step)))))
    (testing "a single workflow works too"
      (is (= [[::backend :step 0] [::inner :step 0]]
             (mapv (juxt :id :scope :level) (wf/advice-plan sub :t/step)))))))

(deftest advice-add-all-workflow-is-untouched-by-later-adds
  (let [base (two-step-wf)
        g1 (wf/advice-add-all base :filter-return ::g1 #(log % :g1))
        g2 (wf/advice-add-all g1 :filter-return ::g2 #(log % :g2))]
    (is (= [:a :g1 :b :g1] (:log (wf/run g1 {}))) "g1 is untouched by adding g2")
    (is (= [:a :g1 :g2 :b :g1 :g2] (:log (wf/run g2 {}))))))
