(ns green.kubernetes-test
  (:require [clojure.test :refer [deftest is testing]]
            [green.kubernetes :as k8s]))

(def descriptor {:group "colors.test" :version "v1" :plural "examples" :kind "Example"})
(defn resource
  ([] (resource "demo"))
  ([name]
   {:apiVersion "colors.test/v1" :kind "Example"
    :metadata {:name name :namespace "dev" :uid name :generation 1 :resourceVersion "1"}
    :spec {:state "running" :reconcileInterval "1s" :config {:profile name}}}))

(defn fixture
  ([] (fixture [(resource)]))
  ([resources]
   (let [objects (atom (into {} (map (fn [r] [[(:kind r) (get-in r [:metadata :name])] r]) resources)))
         now (atom 100000)
         actual (atom {})
         effects (atom [])
         update! (fn [desc r k value]
                   (let [key [(:kind desc) (get-in r [:metadata :name])]
                         result (atom nil)]
                     (swap! objects
                            (fn [items]
                              (let [current (get items key)]
                                (when-not (= (get-in current [:metadata :resourceVersion])
                                             (get-in r [:metadata :resourceVersion]))
                                  (throw (ex-info "Conflict" {:status 409})))
                                (let [next (-> current (assoc k value)
                                               (update-in [:metadata :resourceVersion]
                                                          #(str (inc (Long/parseLong %)))))]
                                  (reset! result next)
                                  (assoc items key next)))))
                     @result))
         client {:list-resources (fn [desc _] (vec (filter #(= (:kind desc) (:kind %)) (vals @objects))))
                 :get-resource (fn [desc _ name] (get @objects [(:kind desc) name]))
                 :update-status! #(update! %1 %2 :status %3)
                 :update-finalizers! #(update! %1 %2 :metadata
                                              (assoc (:metadata %2) :finalizers %3))}
         package {:resource descriptor
                  :identity (fn [opts] ["test-backend" (:profile opts)])
                  :validate (fn [opts] (when (:invalid opts) ["SECRET validator details"]))
                  :observe (fn [opts]
                             (let [found (get @actual (:profile opts))]
                               {:exists? (some? found)
                                :matches? (= found (select-keys opts [:green/event :value]))
                                :ready? true}))
                  :converge (fn [opts]
                              (swap! effects conj [:converge (:profile opts) (:green/event opts)])
                              (swap! actual assoc (:profile opts) (select-keys opts [:green/event :value]))
                              {:green/exit 0})
                  :delete (fn [opts]
                            (swap! effects conj [:delete (:profile opts)])
                            (swap! actual dissoc (:profile opts))
                            {:green/exit 0})}
         cfg (k8s/controller {:packages [package] :client client :namespace "dev" :now #(deref now)})]
     {:objects objects :now now :actual actual :effects effects :package package :cfg cfg})))

(defn current [f] (get @(:objects f) ["Example" "demo"]))
(defn phase [f] (get-in (current f) [:status :phase]))
(defn reconcile [f] (k8s/reconcile! (:cfg f) (:package f) "dev" "demo"))
(defn edit! [f edit]
  (swap! (:objects f) update ["Example" "demo"]
         #(-> % edit (update-in [:metadata :generation] inc)
              (update-in [:metadata :resourceVersion] (fn [rv] (str (inc (Long/parseLong rv))))))))

(deftest converge-maintain-and-state-changes
  (let [f (fixture)]
    (reconcile f)
    (is (= "Ready" (phase f)))
    (is (= [k8s/finalizer] (get-in (current f) [:metadata :finalizers])))
    (is (= 1 (count @(:effects f))))
    (reconcile f)
    (is (= 1 (count @(:effects f))) "Unchanged resource waits until interval")
    (reset! (:actual f) {})
    (swap! (:now f) + 1001)
    (reconcile f)
    (is (= 2 (count @(:effects f))) "Periodic check repairs drift")
    (edit! f #(assoc-in % [:spec :state] "stopped"))
    (reconcile f)
    (is (= [:converge "demo" :stop] (last @(:effects f))))
    (edit! f #(assoc-in % [:spec :suspend] true))
    (reconcile f)
    (reset! (:actual f) {})
    (swap! (:now f) + 1001)
    (reconcile f)
    (is (= "Suspended" (phase f)))
    (is (= 3 (count @(:effects f))))
    (edit! f #(assoc-in % [:spec :suspend] false))
    (reconcile f)
    (is (= 4 (count @(:effects f))))))

(deftest request-annotation-triggers-early-check
  (let [f (fixture)]
    (reconcile f)
    (reset! (:actual f) {})
    ;; An annotation does not increment metadata.generation.
    (swap! (:objects f) update ["Example" "demo"]
           #(-> % (assoc-in [:metadata :annotations k8s/request-annotation] "request-1")
                (assoc-in [:metadata :resourceVersion] "10")))
    (reconcile f)
    (is (= 2 (count @(:effects f))))
    (is (= "request-1" (get-in (current f) [:status :lastHandledRequest])))))

(deftest invalid-config-and-immutable-identity
  (let [f (fixture)]
    (edit! f #(assoc-in % [:spec :config :invalid] true))
    (reconcile f)
    (is (= "Invalid" (phase f)))
    (is (empty? @(:effects f)))
    (is (not (.contains (pr-str (:status (current f))) "SECRET")))
    (let [rv (get-in (current f) [:metadata :resourceVersion])]
      (swap! (:now f) + 100000)
      (reconcile f)
      (is (= rv (get-in (current f) [:metadata :resourceVersion]))))
    (edit! f #(update-in % [:spec :config] dissoc :invalid))
    (reconcile f)
    (is (= "Ready" (phase f)))
    (edit! f #(assoc-in % [:spec :config :profile] "different"))
    (reconcile f)
    (is (= "Invalid" (phase f)))
    (is (= "demo" (get-in (current f) [:status :profile])))
    (is (= 1 (count @(:effects f))))
    (edit! f #(assoc-in % [:spec :config :profile] "another"))
    (reconcile f)
    (is (= "demo" (get-in (current f) [:status :profile])))))

(deftest readiness-retries-without-reapplying-matching-state
  (let [f (fixture)
        package (assoc (:package f) :observe (constantly {:exists? true :matches? true :ready? false}))
        f (assoc f :package package)]
    (reconcile f)
    (is (= "Failed" (phase f)))
    (is (= 1 (get-in (current f) [:status :retryCount])))
    (is (empty? @(:effects f)))
    (swap! (:now f) + 1001)
    (reconcile f)
    (is (= 2 (get-in (current f) [:status :retryCount])))
    (is (empty? @(:effects f)))))

(deftest exceptions-are-sanitized-and-retried
  (let [f (fixture)
        attempts (atom 0)
        normal (:converge (:package f))
        package (assoc (:package f) :converge
                       (fn [opts]
                         (if (= 1 (swap! attempts inc))
                           (throw (ex-info "SECRET credentials" {:opts opts}))
                           (normal opts))))
        f (assoc f :package package)]
    (reconcile f)
    (is (= "Failed" (phase f)))
    (is (not (.contains (pr-str (:status (current f))) "SECRET")))
    (reconcile f)
    (is (= 1 @attempts))
    (swap! (:now f) + 1001)
    (reconcile f)
    (is (= "Ready" (phase f)))
    (is (= 2 @attempts))))

(deftest deletion-protection-destroy-and-retain
  (testing "Destroy obeys protection even while suspended"
    (let [f (fixture)]
      (reconcile f)
      (edit! f #(-> % (assoc-in [:metadata :deletionTimestamp] "2026-01-01T00:00:00Z")
                     (assoc-in [:spec :deletionPolicy] "Destroy")
                     (assoc-in [:spec :suspend] true)
                     (assoc-in [:spec :config :compute-prevent-destroy] true)))
      (reconcile f)
      (is (= "Blocked" (phase f)))
      (is (= 1 (count @(:effects f))))
      (is (= [k8s/finalizer] (get-in (current f) [:metadata :finalizers])))
      (edit! f #(assoc-in % [:spec :config :compute-prevent-destroy] false))
      (reconcile f)
      (is (= [:delete "demo"] (last @(:effects f))))
      (is (empty? (get-in (current f) [:metadata :finalizers])))))
  (testing "Default retain removes only Green's finalizer"
    (let [f (fixture)]
      (reconcile f)
      (edit! f #(-> % (assoc-in [:metadata :deletionTimestamp] "2026-01-01T00:00:00Z")
                     (update-in [:metadata :finalizers] conj "another.example/cleanup")))
      (reconcile f)
      (is (= ["another.example/cleanup"] (get-in (current f) [:metadata :finalizers])))
      (is (= 1 (count @(:effects f))))
      (is (seq @(:actual f))))))

(deftest stale-run-cannot-acknowledge-new-generation
  (let [f (fixture)
        original (:converge (:package f))
        package (assoc (:package f) :converge
                       (fn [opts]
                         (edit! f #(assoc-in % [:spec :config :value] "new"))
                         (original opts)))]
    (k8s/reconcile! (:cfg f) package "dev" "demo")
    (is (= 2 (get-in (current f) [:metadata :generation])))
    (is (= 1 (get-in (current f) [:status :observedGeneration])))
    (is (= "Reconciling" (phase f)))
    (reconcile f)
    (is (= "Ready" (phase f)))
    (is (= 2 (get-in (current f) [:status :observedGeneration])))
    (is (= "new" (get-in @(:actual f) ["demo" :value])))))

(deftest state-lock-spans-resources-types-and-controller-instances
  (let [second-resource (-> (resource "second") (assoc :kind "Other")
                            (assoc-in [:spec :config :profile] "demo"))
        f (fixture [(resource) second-resource])
        entered (promise)
        release (promise)
        original (:converge (:package f))
        package (assoc (:package f) :converge (fn [opts] (deliver entered true) @release (original opts)))
        other (assoc (:package f) :resource (assoc descriptor :plural "others" :kind "Other"))
        run (future (k8s/reconcile! (:cfg f) package "dev" "demo"))]
    (try
      (is (= true (deref entered 3000 :timeout)))
      (is (= :busy (k8s/reconcile! (:cfg f) other "dev" "second")))
      (is (empty? @(:effects f)))
      (finally (deliver release true) @run))
    (k8s/reconcile! (:cfg f) other "dev" "second")
    (is (= 1 (count @(:effects f))) "Second owner observes shared state, no duplicate effect")
    (is (= "Ready" (get-in @(:objects f) [["Other" "second"] :status :phase])))))

(deftest restart-observes-infrastructure-before-repeating-effects
  (let [f (fixture)]
    (reconcile f)
    ;; Simulate losing the worker after its effects and before reporting Ready.
    (swap! (:objects f) update-in [["Example" "demo"] :status]
           #(-> % (assoc :phase "Reconciling") (dissoc :nextReconcileTime)))
    (reconcile f)
    (is (= "Ready" (phase f)))
    (is (= 1 (count @(:effects f))))))

(deftest shutdown-waits-for-active-workers
  (let [f (fixture)
        entered (promise)
        release (promise)
        original (:converge (:package f))
        package (assoc (:package f) :converge (fn [opts] (deliver entered true) @release (original opts)))
        cfg (assoc (:cfg f) :packages [package] :poll-ms 10 :workers 2)
        runtime (k8s/start! cfg)]
    (try
      (is (= true (deref entered 3000 :timeout)))
      (let [stopped (future (k8s/stop! runtime))]
        (is (= :waiting (deref stopped 50 :waiting)))
        (deliver release true)
        (is (nil? (deref stopped 3000 :timeout)))
        (is (= 1 (count @(:effects f)))))
      (finally (deliver release true) (k8s/stop! runtime)))))

(deftest metadata-conflicts-prevent-infrastructure-effects
  (doseq [operation [:update-finalizers! :update-status!]]
    (let [f (fixture)
          cfg (assoc-in (:cfg f) [:client operation]
                        (fn [& _] (throw (ex-info "Conflict" {:status 409}))))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (k8s/reconcile! cfg (:package f) "dev" "demo")))
      (is (empty? @(:effects f)))
      ;; The lock is always released, including transport failure before effects.
      (reconcile f)
      (is (= "Ready" (phase f))))))

(deftest incomplete-deletion-keeps-finalizer
  (let [f (fixture)]
    (reconcile f)
    (edit! f #(-> % (assoc-in [:metadata :deletionTimestamp] "2026-01-01T00:00:00Z")
                   (assoc-in [:spec :deletionPolicy] "Destroy")))
    (let [package (assoc (:package f) :delete (constantly {:green/exit 0}))]
      (k8s/reconcile! (:cfg f) package "dev" "demo")
      (is (= "Failed" (phase f)))
      (is (= [k8s/finalizer] (get-in (current f) [:metadata :finalizers]))))))

(deftest worker-pool-is-bounded-and-allows-independent-profiles
  (let [f (fixture (mapv resource ["a" "b" "c" "d"]))
        running (atom 0)
        max-running (atom 0)
        entered (promise)
        release (promise)
        original (:converge (:package f))
        package (assoc (:package f) :converge
                       (fn [opts]
                         (let [n (swap! running inc)]
                           (swap! max-running max n)
                           (when (= 2 n) (deliver entered true))
                           (try @release (original opts)
                                (finally (swap! running dec))))))
        cfg (assoc (:cfg f) :packages [package] :poll-ms 10 :workers 2)
        runtime (k8s/start! cfg)]
    (try
      (is (= true (deref entered 3000 :timeout)) "Two different profiles can progress together")
      (is (= 2 @max-running))
      (let [stopped (future (k8s/stop! runtime))]
        (is (= :waiting (deref stopped 50 :waiting)))
        (deliver release true)
        (is (nil? (deref stopped 3000 :timeout)))
        (is (= 2 (count @(:effects f))) "Shutdown discards queued work while active work completes")
        (is (= 2 @max-running)))
      (finally (deliver release true) (k8s/stop! runtime)))))

(deftest invalid-deletion-waits-for-edit-but-retain-can-release
  (let [f (fixture)
        validations (atom 0)
        package (assoc (:package f) :validate
                       (fn [opts] (swap! validations inc)
                         (when (:invalid opts) ["invalid"])))
        f (assoc f :package package)]
    (reconcile f)
    (edit! f #(-> % (assoc-in [:spec :config :invalid] true)
                   (assoc-in [:spec :deletionPolicy] "Destroy")))
    (reconcile f)
    (is (= "Invalid" (phase f)))
    ;; Kubernetes deletion changes metadata, not generation.
    (swap! (:objects f) update ["Example" "demo"]
           #(-> % (assoc-in [:metadata :deletionTimestamp] "2026-01-01T00:00:00Z")
                (assoc-in [:metadata :resourceVersion] "20")))
    (reconcile f)
    (is (true? (get-in (current f) [:status :deletionObserved])))
    (let [count-before @validations
          rv-before (get-in (current f) [:metadata :resourceVersion])]
      (swap! (:now f) + 100000)
      (dotimes [_ 3] (reconcile f))
      (is (= count-before @validations))
      (is (= rv-before (get-in (current f) [:metadata :resourceVersion]))))
    (edit! f #(assoc-in % [:spec :deletionPolicy] "Retain"))
    (reconcile f)
    (is (empty? (get-in (current f) [:metadata :finalizers]))))
  (let [f (fixture)]
    (reconcile f)
    (edit! f #(assoc-in % [:spec :config :invalid] true))
    (reconcile f)
    (is (= "Invalid" (phase f)))
    (swap! (:objects f) update ["Example" "demo"]
           #(-> % (assoc-in [:metadata :deletionTimestamp] "2026-01-01T00:00:00Z")
                (assoc-in [:metadata :resourceVersion] "20")))
    (reconcile f)
    (is (empty? (get-in (current f) [:metadata :finalizers]))
        "Initial deletion releases a previously Invalid resource under Retain")))

(deftest validator-exceptions-report-safe-retry-and-edits-reset-backoff
  (let [f (fixture)
        package (assoc (:package f) :validate
                       (fn [_] (throw (ex-info "SECRET validation exception" {}))))
        f (assoc f :package package)]
    (reconcile f)
    (is (= "Failed" (phase f)))
    (is (= "ValidationFailed" (get-in (current f) [:status :conditions 0 :reason])))
    (is (not (.contains (pr-str (:status (current f))) "SECRET")))
    (is (empty? @(:effects f)))
    (swap! (:now f) + 1001)
    (reconcile f)
    (is (= 2 (get-in (current f) [:status :retryCount])))
    (edit! f #(assoc-in % [:spec :config :value] 1))
    (reconcile f)
    (is (= 1 (get-in (current f) [:status :retryCount])))
    (swap! (:now f) + 1001)
    (reconcile f)
    (is (= 2 (get-in (current f) [:status :retryCount])))
    (swap! (:objects f) update ["Example" "demo"]
           #(-> % (assoc-in [:metadata :annotations k8s/request-annotation] "retry-now")
                (assoc-in [:metadata :resourceVersion] "100")))
    (reconcile f)
    (is (= 1 (get-in (current f) [:status :retryCount])))))
