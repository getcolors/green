(ns green.process
  "Shelling out, with a timeout that actually stops the command.

  `clojure.java.shell/sh` has no timeout, so a hung `tofu apply` or a probe
  against an unreachable host blocks its branch — and with it the whole run —
  for as long as the command cares to sit there. `run-with-timeout` bounds the
  wait and kills the process tree, not just the direct child, so a wrapper
  script cannot leave its children running.

  Execution functions return a plain map rather than throwing: a command that
  cannot start reports exit 127, so callers stay in the Unix-style
  outcome world the rest of green lives in."
  (:require
   [clojure.string :as str])
  (:import
   [java.io ByteArrayOutputStream IOException]
   [java.util.concurrent TimeUnit]))

(defn strip-ansi
  "Remove ANSI escape sequences — OSC 8 hyperlinks and CSI sequences — from a
  command's output, so it can be parsed as plain text."
  [s]
  (-> (or s "")
      (str/replace #"\x1b\]8;[^\x07]*\x07" "")
      (str/replace #"\x1b\[[0-9;?]*[ -/]*[@-~]" "")))

(defn- process-builder
  [args {:keys [dir extra-env]}]
  (let [builder (ProcessBuilder. ^java.util.List (mapv str args))]
    (when dir
      (.directory builder (java.io.File. (str dir))))
    (when (seq extra-env)
      (.putAll (.environment builder)
               (into {} (map (fn [[k v]] [(str k) (str v)])) extra-env)))
    builder))

(defn- executable [name]
  (some (fn [dir]
          (let [file (java.io.File. dir name)]
            (when (and (.isFile file) (.canExecute file))
              (.getAbsolutePath file))))
        (str/split (or (System/getenv "PATH") "")
                   (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator)))))

(defn- daemon-thread [f]
  (doto (Thread. ^Runnable f)
    (.setDaemon true)
    (.start)))

(defn- capture [stream]
  (let [bytes (ByteArrayOutputStream.)
        done (promise)
        reader (daemon-thread
                #(try
                   (with-open [input stream]
                     (let [buffer (byte-array 8192)]
                       (loop []
                         (let [n (.read input buffer)]
                           (when (pos? n)
                             (.write bytes buffer 0 n)
                             (recur))))))
                   (catch IOException _ nil)
                   (finally (deliver done true))))]
    {:bytes bytes :stream stream :done done :reader reader}))

(defn- start-process [args opts inherit?]
  (let [setsid (when-not inherit? (executable "setsid"))
        kill (when setsid (executable "kill"))
        grouped? (boolean (and setsid kill))
        builder (process-builder (if grouped? (into [setsid "--"] args) args) opts)
        process (.start (if inherit? (.inheritIO builder) builder))]
    (if inherit?
      {:process process}
      (do
        (.close (.getOutputStream process))
        {:process process :group-kill (when grouped? kill)
         :group-ps (when grouped? (executable "ps"))
         :out (capture (.getInputStream process))
         :err (capture (.getErrorStream process))}))))

(defn- remaining-ms [deadline]
  (max 0 (long (Math/ceil (/ (- deadline (System/nanoTime)) 1000000.0)))))

(defn- group-active? [{:keys [process group-kill group-ps]} deadline]
  (when group-kill
    (try
      (let [args (if group-ps
                   [group-ps "-o" "stat=" "-g" (str (.pid process))]
                   [group-kill "-0" "--" (str "-" (.pid process))])
            checker (.start (process-builder args {}))]
        (try
          (.close (.getOutputStream checker))
          (if (.waitFor checker (min 200 (remaining-ms deadline)) TimeUnit/MILLISECONDS)
            (let [exit (.exitValue checker)]
              (if group-ps
                (or (> exit 1)
                    (some #(not (re-find #"^[ZX]" %))
                          (remove str/blank? (map str/trim (str/split-lines (slurp (.getInputStream checker)))))))
                (zero? exit)))
            true)
          (finally (.destroyForcibly checker))))
      (catch IOException _ true))))

(defn- await-group [execution deadline]
  ;; Java can close the leader's pipes before reader threads attach. Check its
  ;; session too, so this race cannot hide children behind an apparent EOF.
  (loop []
    (if (and deadline (group-active? execution deadline))
      (let [remaining (remaining-ms deadline)]
        (if (zero? remaining)
          false
          (do (Thread/sleep (min 10 remaining)) (recur))))
      true)))

(defn- await-completion [{:keys [process out err] :as execution} deadline]
  (and (if deadline
         (.waitFor process (remaining-ms deadline) TimeUnit/MILLISECONDS)
         (do (.waitFor process) true))
       (every? (fn [capture]
                 (or (nil? capture)
                     (if deadline
                       (not= ::timeout (deref (:done capture) (remaining-ms deadline) ::timeout))
                       (do @(:done capture) true))))
               [out err])
       (await-group execution deadline)))

(defn- uninterrupted [f]
  (loop []
    (let [result (try (f) (catch InterruptedException _ ::interrupted))]
      (if (= ::interrupted result) (recur) result))))

(defn- cleanup-attempt [errors f]
  (try (f)
       (catch Exception e
         (swap! errors conj (or (.getMessage e) (str (class e))))
         nil)))

(defn- destroy-process-tree! [{:keys [process group-kill]} errors]
  ;; Snapshot descendants before killing their parent. Process groups also
  ;; reach children that have already been reparented after the leader exits.
  (let [handles (cleanup-attempt errors
                  #(with-open [descendants (.descendants (.toHandle process))]
                     (vec (iterator-seq (.iterator descendants)))))]
    (try
      (when group-kill
        (cleanup-attempt errors
          #(let [killer (.start (process-builder [group-kill "-KILL" "--" (str "-" (.pid process))] {}))
                 deadline (+ (System/nanoTime) 200000000)]
             (try
               (.close (.getOutputStream killer))
               (when-not (uninterrupted (fn [] (.waitFor killer (remaining-ms deadline) TimeUnit/MILLISECONDS)))
                 (.destroyForcibly killer))
               (finally (.destroyForcibly killer))))))
      (finally
        (doseq [handle (reverse handles)]
          (cleanup-attempt errors #(.destroyForcibly handle)))
        (cleanup-attempt errors #(.destroyForcibly process))))))

(defn- stop-process! [{:keys [out err] :as execution}]
  (let [errors (atom [])]
    (destroy-process-tree! execution errors)
    (let [deadline (+ (System/nanoTime) 1000000000)
          finished? (cleanup-attempt errors #(uninterrupted (fn [] (await-completion execution deadline))))]
      (when-not finished?
        (doseq [{:keys [stream reader]} (remove nil? [out err])]
          (.interrupt reader)
          ;; A detached descendant can retain a pipe. Closing a Java buffered
          ;; stream can itself wait for its reader, so keep it off the caller.
          (daemon-thread #(try (.close stream) (catch IOException _ nil))))))
    (when (seq @errors)
      (str "process cleanup failed: " (str/join "; " @errors)))))

(defn- captured [capture]
  (if capture (.toString ^ByteArrayOutputStream (:bytes capture) "UTF-8") ""))

(defn- outcome [execution exit diagnostic]
  (let [err (captured (:err execution))]
    {:exit exit
     :out (captured (:out execution))
     :err (if diagnostic
            (if (str/blank? err) diagnostic (str err "\n" diagnostic))
            err)}))

(defn- stopped-outcome [execution exit diagnostic]
  (let [cleanup-error (stop-process! execution)]
    (outcome execution exit (str diagnostic (when cleanup-error (str "\n" cleanup-error))))))

(defn- execute [args opts timeout-ms inherit?]
  (try
    (let [execution (start-process args opts inherit?)
          deadline (when timeout-ms (+ (System/nanoTime) (* (long timeout-ms) 1000000)))]
      (try
        (if (await-completion execution deadline)
          (outcome execution (.exitValue (:process execution)) nil)
          (stopped-outcome execution 124 (format "command timed out after %dms" timeout-ms)))
        (catch InterruptedException _
          (stopped-outcome execution 130 "command interrupted"))
        (catch Exception e
          (stopped-outcome execution 1 (or (.getMessage e) (str (class e)))))))
    (catch Exception e
      {:exit 127 :out "" :err (or (.getMessage e) (str (class e)))})))

(defn run
  "Run `args` and return `{:exit :out :err}`.

  `opts` supports `:dir` and `:extra-env`. Start failures return exit 127;
  interruption stops the process tree and returns exit 130."
  ([args] (run args {}))
  ([args opts] (execute args opts nil false)))

(defn run-inherit
  "Run argv with the caller's terminal streams attached.
  Interruption stops the process tree and returns exit 130."
  [args]
  (execute args {} nil true))

(defn posix-quote
  "Quote one POSIX-shell argument."
  [x]
  (str "'" (str/replace (str x) "'" "'\\''") "'"))

(defn run-plan
  "Run labeled command maps sequentially. Options: `:runner`,
  `:continue?` `(command result)->bool`, and `:cleanup` (always called).
  Returns the first non-tolerated failure with its command, or success."
  [commands {:keys [runner continue? cleanup]
             :or {runner #(run (:args %)) continue? (constantly false)
                  cleanup (fn [])}}]
  (try
    (reduce (fn [_ command]
              (let [result (runner command)]
                (cond
                  (zero? (:exit result)) result
                  (continue? command result) {:exit 0 :out "" :err ""}
                  :else (reduced (assoc result :command command)))))
            {:exit 0 :out "" :err ""}
            commands)
    (finally (cleanup))))

(defn run-with-timeout
  "Run `args` with a deadline covering process exit and captured output.
  Timeout stops the process tree and returns exit 124. Interruption returns
  exit 130 after cleanup. Supports the same options as `run`."
  [args opts timeout-ms]
  (let [result (execute args opts timeout-ms false)]
    (assoc result :ok? (zero? (:exit result)))))
