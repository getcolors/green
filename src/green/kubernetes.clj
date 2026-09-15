(ns green.kubernetes
  "A polling Kubernetes control loop for package-owned Green workflows.

  The transport and package functions are injected. No CLI environment overlay
  is applied. Locks are process-local: run one controller process and confirm it
  has stopped before replacing it. Human/CI writers need separate coordination."
  (:import [java.time Instant]
           [java.security MessageDigest]))

(def finalizer "colors.getcolors.ai/infrastructure")
(def request-annotation :colors.getcolors.ai/reconcile-request)

;; Shared across controller instances and CRD types, including namespace scopes.
;; Remove acquired identities on release so deleted profiles do not leak locks.
(defonce ^:private held-identities (atom #{}))

(defn controller
  "Construct controller configuration without starting threads.

  Each package supplies :resource, :validate, :identity, :observe, :converge,
  and :delete. :identity returns the full backend/profile state identity.
  Client callbacks receive a resource descriptor, then namespace/name for reads
  or a resource snapshot and replacement status/finalizers for optimistic writes.
  :now is an optional millisecond clock for deterministic reconciliation tests."
  [{:keys [packages client workers poll-ms] :as options}]
  (when-not (and (seq packages) (map? client)
                 (every? #(fn? (get client %))
                         [:list-resources :get-resource :update-status! :update-finalizers!])
                 (every? (fn [package]
                           (and (every? #(string? (get-in package [:resource %]))
                                        [:group :version :plural :kind])
                                (every? #(fn? (get package %))
                                        [:validate :identity :observe :converge :delete])))
                         packages)
                 (pos-int? (or workers 2)) (pos-int? (or poll-ms 500)))
    (throw (ex-info "Invalid Kubernetes controller configuration" {})))
  (merge {:workers 2 :poll-ms 500 :namespace "default"
          :now #(System/currentTimeMillis) :retry-base-ms 1000 :retry-max-ms 60000}
         options))

(defn- instant [ms] (str (Instant/ofEpochMilli (long ms))))
(defn- millis [s]
  (try (.toEpochMilli (Instant/parse s)) (catch Exception _ 0)))

(defn- interval-ms [resource]
  (let [value (get-in resource [:spec :reconcileInterval] "60s")
        [_ amount unit] (when (string? value) (re-matches #"([1-9][0-9]*)(ms|s|m|h)" value))]
    (when amount
      (try (* (Long/parseLong amount) ({"ms" 1 "s" 1000 "m" 60000 "h" 3600000} unit))
           (catch Exception _ nil)))))

(defn- request-id [resource]
  (or (get-in resource [:metadata :annotations request-annotation]) ""))

(defn- digest [identity]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str identity) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and 255 %)) bytes))))

(defn- options [resource]
  (assoc (get-in resource [:spec :config] {})
         :profile (or (get-in resource [:spec :config :profile])
                      (str (get-in resource [:metadata :namespace]) "--"
                           (get-in resource [:metadata :name])))
         :green/event (if (get-in resource [:metadata :deletionTimestamp])
                        :delete
                        (if (= "stopped" (get-in resource [:spec :state])) :stop :create))
         :green.kubernetes/resource resource))

(defn- status [cfg resource profile identity phase reason ready? extra]
  (let [now ((:now cfg))
        previous (first (filter #(= "Ready" (:type %)) (get-in resource [:status :conditions])))
        ready (if ready? "True" "False")
        generation (get-in resource [:metadata :generation] 1)]
    (merge {:phase phase :profile profile :stateIdentity identity
            :observedGeneration generation :lastHandledRequest (request-id resource)
            :lastReconcileTime (instant now) :retryCount 0
            :deletionObserved (boolean (get-in resource [:metadata :deletionTimestamp]))
            :conditions [{:type "Ready" :status ready :reason reason
                          :observedGeneration generation
                          :lastTransitionTime (if (and (= ready (:status previous))
                                                       (= reason (:reason previous)))
                                                (:lastTransitionTime previous) (instant now))}]}
           extra)))

(defn- publish! [cfg package resource opts identity phase reason ready? extra]
  ((get-in cfg [:client :update-status!]) (:resource package) resource
   (status cfg resource (:profile opts) identity phase reason ready? extra)))

(defn- desired-changed? [resource]
  (or (not= (get-in resource [:status :observedGeneration])
            (get-in resource [:metadata :generation] 1))
      (not= (get-in resource [:status :lastHandledRequest]) (request-id resource))))

(defn- retry-count [resource]
  (if (desired-changed? resource) 0 (get-in resource [:status :retryCount] 0)))

(defn- failure! [cfg package resource opts identity reason]
  (let [attempt (inc (retry-count resource))
        delay (min (:retry-max-ms cfg)
                   (* (:retry-base-ms cfg) (long (Math/pow 2 (min 20 (dec attempt))))))]
    (publish! cfg package resource opts identity "Failed" reason false
              {:retryCount attempt :nextReconcileTime (instant (+ ((:now cfg)) delay))})))

(defn- due? [cfg resource]
  (let [s (:status resource)
        changed? (desired-changed? resource)
        deleting? (get-in resource [:metadata :deletionTimestamp])]
    (or changed?
        ;; A suspended or invalid resource still needs deletion handling.
        (and deleting? (not (:deletionObserved s)))
        (and (not (#{"Invalid" "Suspended"} (:phase s)))
             (<= (millis (:nextReconcileTime s)) ((:now cfg)))))))

(defn- successful! [result]
  (when-not (and (map? result) (zero? (get result :green/exit 0)))
    (throw (ex-info "Package workflow failed" {}))))

(defn- matches? [observation]
  (and (true? (:matches? observation)) (true? (:ready? observation))))

(defn- reconcile-locked! [cfg package resource opts identity]
  (let [deleting? (get-in resource [:metadata :deletionTimestamp])
        policy (get-in resource [:spec :deletionPolicy] "Retain")
        finalizers (vec (get-in resource [:metadata :finalizers] []))
        finalized? (some #{finalizer} finalizers)
        write-finalizers (get-in cfg [:client :update-finalizers!])
        old-status (:status resource)
        validation (delay (try {:errors ((:validate package) opts)}
                               (catch Exception _ {:failed? true})))
        immutable? (or (and (:profile old-status) (not= (:profile old-status) (:profile opts)))
                       (and (:stateIdentity old-status) (not= (:stateIdentity old-status) identity)))]
    (cond
      ;; A retained resource needs no package execution or valid new config.
      (and deleting? (or (= "Retain" policy) (not finalized?)))
      (when finalized?
        (write-finalizers (:resource package) resource (vec (remove #{finalizer} finalizers))))

      immutable?
      ;; Preserve the original identity in status so another edit cannot reset it.
      (publish! cfg package resource (assoc opts :profile (:profile old-status))
                (:stateIdentity old-status) "Invalid" "ImmutableStateIdentity" false {})

      (or (not (#{"running" "stopped"} (get-in resource [:spec :state] "running")))
          (not (#{"Retain" "Destroy"} policy))
          (nil? (interval-ms resource)))
      (publish! cfg package resource opts identity "Invalid" "InvalidConfiguration" false {})

      (:failed? @validation)
      (failure! cfg package resource opts identity "ValidationFailed")

      (seq (:errors @validation))
      (publish! cfg package resource opts identity "Invalid" "InvalidConfiguration" false {})

      (and deleting? (true? (:compute-prevent-destroy opts)))
      (publish! cfg package resource opts identity "Blocked" "DestructionProtected" false
                {:nextReconcileTime (instant (+ ((:now cfg)) (interval-ms resource)))})

      (and (not deleting?) (true? (get-in resource [:spec :suspend])))
      (publish! cfg package resource opts identity "Suspended" "Suspended" false {})

      :else
      (let [resource (if (or deleting? finalized?) resource
                         (write-finalizers (:resource package) resource (conj finalizers finalizer)))
            ;; Persist state identity before the first infrastructure side effect.
            ;; A conflict here prevents execution; completion also uses this RV.
            active (publish! cfg package resource opts identity
                             (if deleting? "Deleting" "Reconciling")
                             (if deleting? "Deleting" "Reconciling") false
                             {:retryCount (retry-count resource)})]
        (try
          (if deleting?
            (do
              (when (true? (:exists? ((:observe package) opts)))
                (successful! ((:delete package) opts)))
              (when-not (false? (:exists? ((:observe package) opts)))
                (throw (ex-info "Infrastructure remains after deletion" {})))
              (write-finalizers (:resource package) active (vec (remove #{finalizer} finalizers))))
            (do
              (when-not (true? (:matches? ((:observe package) opts)))
                (successful! ((:converge package) opts)))
              (if (matches? ((:observe package) opts))
                (publish! cfg package active opts identity "Ready" "Converged" true
                          {:nextReconcileTime (instant (+ ((:now cfg)) (interval-ms resource)))})
                (failure! cfg package active opts identity "NotReady"))))
          (catch Exception error
            ;; Never expose exception messages, workflow errors or opts in status.
            ;; Transport conflicts retry with a fresh snapshot on the next poll.
            (when-not (= 409 (:status (ex-data error)))
              (failure! cfg package active opts identity "ExecutionFailed"))))))))

(defn reconcile!
  "Reread and reconcile one resource. Useful for deterministic drivers/tests.
  Returns :busy when another run owns the state identity. Transport failures
  propagate to the caller; start! catches them and retries on a later poll."
  [cfg package namespace name]
  (when-let [resource ((get-in cfg [:client :get-resource]) (:resource package) namespace name)]
    (when (due? cfg resource)
      (let [opts (options resource)
            identity-value ((:identity package) opts)
            identity (digest identity-value)
            acquired? (loop []
                        (let [held @held-identities]
                          (cond (contains? held identity-value) false
                                (compare-and-set! held-identities held (conj held identity-value)) true
                                :else (recur))))]
        (if-not acquired? :busy
          (try
            ;; A contender may have completed between the first read and locking.
            (when-let [latest ((get-in cfg [:client :get-resource]) (:resource package) namespace name)]
              (when (and (= (get-in resource [:metadata :uid]) (get-in latest [:metadata :uid]))
                         (= identity-value ((:identity package) (options latest)))
                         (due? cfg latest))
                (reconcile-locked! cfg package latest (options latest) identity)))
            (finally (swap! held-identities disj identity-value))))))))

(defn start!
  "Start polling and a fixed number of workers. Queue entries coalesce per CR.
  Errors are counted in :errors without storing potentially sensitive exceptions.
  Polling errors do not stop unrelated resource types or active workflows."
  [cfg]
  (let [running (atom true)
        stop-signal (promise)
        queue (atom clojure.lang.PersistentQueue/EMPTY)
        pending (atom #{})
        errors (atom 0)
        enqueue! (fn [package resource]
                   (let [namespace (get-in resource [:metadata :namespace] (:namespace cfg))
                         name (get-in resource [:metadata :name])
                         key [(:resource package) namespace name]
                         added? (atom false)]
                     (swap! pending #(if (contains? % key) %
                                         (do (reset! added? true) (conj % key))))
                     (when @added? (swap! queue conj [key package namespace name]))))
        take! (fn []
                (loop []
                  (let [q @queue]
                    (when (seq q)
                      (if (compare-and-set! queue q (pop q)) (peek q) (recur))))))
        workers (mapv (fn [_]
                        (future
                          (while @running
                            (if-let [[key package namespace name] (take!)]
                              (try (reconcile! cfg package namespace name)
                                   (catch Exception _ (swap! errors inc))
                                   (finally (swap! pending disj key)))
                              (deref stop-signal 10 nil)))))
                      (range (:workers cfg)))
        poller (future
                 (while @running
                   (doseq [package (:packages cfg)]
                     (when @running
                       (try
                         (doseq [resource ((get-in cfg [:client :list-resources])
                                          (:resource package) (:namespace cfg))]
                           (when @running (enqueue! package resource)))
                         (catch Exception _ (swap! errors inc)))))
                   (deref stop-signal (:poll-ms cfg) nil)))]
    {:config cfg :running running :stop-signal stop-signal :workers workers
     :poller poller :errors errors}))

(defn stop!
  "Stop accepting work and wait for active workflows, including subprocesses.
  Does not cancel a running workflow or allow unsafe automatic takeover."
  [runtime]
  (reset! (:running runtime) false)
  (deliver (:stop-signal runtime) true)
  @(:poller runtime)
  (doseq [worker (:workers runtime)] @worker)
  nil)
