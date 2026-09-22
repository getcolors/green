(ns green.scope
  "Caller-owned runtime cleanup. Keep register! and handles in closures, not opts.")

(defn with-scope
  "Call body with (register! phase cleanup). Phases are :process and :resource.
  Cleanup runs process first, LIFO within each phase, on every catchable exit.
  A body failure wins over cleanup failures. All cleanup callbacks are attempted.
  Callbacks must stop AND await their owned work before returning."
  [body]
  (let [state (atom {:open? true :process [] :resource []})
        register! (fn [phase cleanup]
                    (when-not (and (#{:process :resource} phase) (ifn? cleanup))
                      (throw (ex-info "Invalid scope finalizer" {})))
                    (swap! state (fn [s]
                                   (when-not (:open? s)
                                     (throw (ex-info "Scope is closed" {})))
                                   (update s phase conj cleanup)))
                    nil)
        result (try {:value (body register!)} (catch Throwable e {:error e}))
        finalizers (swap! state assoc :open? false)
        interrupted? (Thread/interrupted)
        cleanup-interruption (atom nil)
        errors (reduce (fn [errors cleanup]
                         (let [task (future (try (cleanup) nil
                                                (catch Throwable e e)))
                               error (loop []
                                       (let [r (try {:error @task}
                                                    (catch InterruptedException e
                                                      (reset! cleanup-interruption e)
                                                      {:retry true}))]
                                         (if (:retry r) (recur) (:error r))))]
                           (cond-> errors error (conj error))))
                       [] (concat (reverse (:process finalizers))
                                  (reverse (:resource finalizers))))]
    (when (or interrupted? @cleanup-interruption)
      (.interrupt (Thread/currentThread)))
    (if-let [error (or (:error result) (first errors))]
      (do (doseq [other errors :when (not (identical? error other))]
            (.addSuppressed ^Throwable error other))
          (throw error))
      (:value result))))
