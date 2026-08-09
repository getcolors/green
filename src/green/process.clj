(ns green.process
  "Shelling out, with a timeout that actually stops the command.

  `clojure.java.shell/sh` has no timeout, so a hung `tofu apply` or a probe
  against an unreachable host blocks its branch — and with it the whole run —
  for as long as the command cares to sit there. `run-with-timeout` bounds the
  wait and kills the process tree, not just the direct child, so a wrapper
  script cannot leave its children running.

  Both functions return a plain map rather than throwing: a command that could
  not even be started reports exit -1, so callers stay in the Unix-style
  outcome world the rest of green lives in."
  (:require
   [clojure.string :as str])
  (:import
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

(defn- start-process
  [args opts]
  (let [process (.start (process-builder args opts))
        out (future (slurp (.getInputStream process)))
        err (future (slurp (.getErrorStream process)))]
    (.close (.getOutputStream process))
    {:process process :out out :err err}))

(defn- process-result
  [process out err]
  {:exit (.exitValue process)
   :out @out
   :err @err})

(defn- destroy-process-tree!
  [^Process process]
  (with-open [descendants (.descendants (.toHandle process))]
    (doseq [handle (iterator-seq (.iterator descendants))]
      (.destroyForcibly handle)))
  (.destroyForcibly process))

(defn run
  "Run `args` and return `{:exit :out :err}`.

  `opts` supports `:dir` and `:extra-env`. Command start failures are returned
  with exit -1 rather than thrown."
  ([args] (run args {}))
  ([args opts]
   (try
     (let [{:keys [process out err]} (start-process args opts)]
       (.waitFor process)
       (process-result process out err))
     (catch Exception e
       {:exit -1 :out "" :err (or (.getMessage e) (str (class e)))}))))

(defn run-inherit
  "Run argv with the caller's terminal streams attached."
  [args]
  (try
    (let [process (-> (process-builder args {}) .inheritIO .start)]
      {:exit (.waitFor process)})
    (catch Exception e
      {:exit -1 :err (or (.getMessage e) (str (class e)))})))

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
  "Run `args`, forcibly stop it and its descendants after `timeout-ms`, and
  return `{:ok? :exit :out :err}`. Supports the same options as `run`."
  [args opts timeout-ms]
  (try
    (let [{:keys [process out err]} (start-process args opts)
          finished? (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)]
      (if finished?
        (assoc (process-result process out err) :ok? (zero? (.exitValue process)))
        (do
          (destroy-process-tree! process)
          (.waitFor process)
          {:ok? false
           :exit -1
           :out @out
           :err (let [captured @err
                      timeout (format "command timed out after %dms" timeout-ms)]
                  (if (str/blank? captured) timeout (str captured "\n" timeout)))})))
    (catch Exception e
      {:ok? false :exit -1 :out "" :err (or (.getMessage e) (str (class e)))})))
