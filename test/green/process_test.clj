(ns green.process-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [green.process :as process]))

(deftest run-captures-the-outcome
  (let [{:keys [exit out]} (process/run ["echo" "hello"])]
    (is (= 0 exit))
    (is (= "hello" (str/trim out))))

  (testing "a non-zero exit is a value, not an exception"
    (is (pos? (:exit (process/run ["sh" "-c" "exit 3"])))))

  (testing "a command that cannot start reports exit 127"
    (is (= 127 (:exit (process/run ["definitely-not-a-real-command-xyz"])))))

  (testing "opts set the directory and add to the environment"
    (is (= "/" (str/trim (:out (process/run ["pwd"] {:dir "/"})))))
    (is (= "sentinel"
           (str/trim (:out (process/run ["sh" "-c" "echo $GREEN_TEST_VAR"]
                                        {:extra-env {"GREEN_TEST_VAR" "sentinel"}})))))))

(deftest run-with-timeout-stops-a-hung-command
  (testing "a command that finishes reports its own outcome"
    (let [{:keys [ok? exit out]} (process/run-with-timeout ["echo" "quick"] {} 10000)]
      (is (true? ok?))
      (is (= 0 exit))
      (is (= "quick" (str/trim out)))))

  (testing "a command that overruns is killed and reported, not waited on"
    (let [started (System/currentTimeMillis)
          {:keys [ok? exit err]} (process/run-with-timeout ["sleep" "30"] {} 300)
          elapsed (- (System/currentTimeMillis) started)]
      (is (false? ok?))
      (is (= 124 exit))
      (is (str/includes? err "timed out after 300ms"))
      (is (< elapsed 15000) "the caller is not blocked for the command's full run")))

  (testing "descendants die with the command, not after it"
    (let [marker (str "/tmp/green-process-test-" (System/currentTimeMillis))
          ;; the child outlives the shell unless the whole tree is killed
          {:keys [ok?]} (process/run-with-timeout
                         ["sh" "-c" (str "sh -c 'sleep 2; touch " marker "' & wait")]
                         {} 300)]
      (is (false? ok?))
      (Thread/sleep 3000)
      (is (not (.exists (java.io.File. marker)))
          "a grandchild process must not survive the timeout"))))

(deftest strip-ansi-leaves-plain-text
  (is (= "plain" (process/strip-ansi "plain")))
  (is (= "" (process/strip-ansi nil)))
  (testing "colour codes"
    (is (= "red" (process/strip-ansi "[31mred[0m"))))
  (testing "OSC 8 hyperlinks"
    (is (= "label"
           (process/strip-ansi "]8;;https://example.comlabel]8;;"))))
  (testing "cursor and mode sequences, not just colours"
    (is (= "done" (process/strip-ansi "[?25ldone[?25h")))))

(defn- temp-path [label]
  (let [file (java.io.File/createTempFile (str "green-process-" label) ".tmp")]
    (.delete file)
    (str file)))

(defn- wait-for-file [path]
  (let [deadline (+ (System/nanoTime) 3000000000)]
    (loop []
      (cond
        (.exists (io/file path)) true
        (> (System/nanoTime) deadline) false
        :else (do (Thread/sleep 10) (recur))))))

(defn- kill-recorded-process [path]
  (when (.exists (io/file path))
    (let [pid (str/trim (slurp path))]
      ;; The detached-child test records its own session leader. Ordinary
      ;; children may have no group under this PID; also try the direct PID.
      (process/run ["kill" "-KILL" "--" (str "-" pid)])
      (process/run ["kill" "-KILL" pid]))
    (io/delete-file path true)))

(defn- child-command [marker pid-file parent-exits? detached?]
  ["sh" "-c"
   (str (when detached? "setsid ")
        "sh -c " (process/posix-quote
                   (str "sleep 0.7; touch " (process/posix-quote marker) "; sleep 30"))
        " & echo $! > " (process/posix-quote pid-file) "; "
        "printf 'started\\n'; printf 'diagnostic\\n' >&2; "
        (if parent-exits? "sleep 0.05" "wait"))])

(deftest timeout-covers-output-after-the-leader-exits
  (let [marker (temp-path "timeout-marker")
        pid-file (temp-path "timeout-pid")
        started (System/nanoTime)]
    (try
      (let [result (process/run-with-timeout (child-command marker pid-file true false) {} 200)]
        (is (= 124 (:exit result)))
        (is (false? (:ok? result)))
        (is (= "started\n" (:out result)))
        (is (= "diagnostic\n\ncommand timed out after 200ms" (:err result)))
        (is (< (/ (- (System/nanoTime) started) 1e6) 1800))
        (Thread/sleep 800)
        (is (not (.exists (io/file marker))) "a reparented child cannot run past the timeout"))
      (finally
        (kill-recorded-process pid-file)
        (io/delete-file marker true)))))

(deftest interruption-stops-every-execution-helper
  (doseq [[label invoke parent-exits?]
          [["run" #(process/run %) false]
           ["timeout" #(process/run-with-timeout % {} 30000) false]
           ["inherit" #(process/run-inherit %) false]
           ["run after leader exit" #(process/run %) true]
           ["timeout after leader exit" #(process/run-with-timeout % {} 30000) true]]]
    (testing label
      (let [marker (temp-path "cancel-marker")
            pid-file (temp-path "cancel-pid")
            outcome (promise)
            runner (future (deliver outcome (invoke (child-command marker pid-file parent-exits? false))))]
        (try
          (is (wait-for-file pid-file))
          (when parent-exits? (Thread/sleep 120))
          (future-cancel runner)
          (let [result (deref outcome 2500 ::not-finished)]
            (is (map? result) "interruption must finish cleanup before returning")
            (when (map? result)
              (is (= 130 (:exit result)))
              (is (str/includes? (:err result) "interrupted"))))
          (Thread/sleep 800)
          (is (not (.exists (io/file marker))) "cancellation must stop the child before its delayed write")
          (finally
            (future-cancel runner)
            (kill-recorded-process pid-file)
            (io/delete-file marker true)))))))

(deftest detached-output-cannot-block-timeout-cleanup
  (let [marker (temp-path "detached-marker")
        pid-file (temp-path "detached-pid")
        started (System/nanoTime)]
    (try
      (let [result (process/run-with-timeout (child-command marker pid-file true true) {} 200)]
        (is (= 124 (:exit result)))
        (is (= "started\n" (:out result)))
        (is (< (/ (- (System/nanoTime) started) 1e6) 2000)
            "detached children holding pipes must not prevent returning"))
      (finally
        (kill-recorded-process pid-file)
        (io/delete-file marker true)))))

(deftest captured-output-preserves-multibyte-and-partial-text
  (is (= "€\n" (:out (process/run ["sh" "-c" "printf '\\342'; sleep 0.02; printf '\\202\\254\\n'"]))))
  (let [result (process/run-with-timeout
                ["sh" "-c" "printf '\\342\\202'; printf 'error' >&2; exec sleep 30"] {} 100)]
    (is (= 124 (:exit result)))
    (is (= "�" (:out result)))
    (is (= "error\ncommand timed out after 100ms" (:err result)))))

(deftest failed-group-signal-still-stops-the-direct-process-tree
  (let [marker (temp-path "kill-error-marker")
        pid-file (temp-path "kill-error-pid")
        executable @#'green.process/executable]
    (try
      (with-redefs-fn {#'green.process/executable
                      #(if (= "kill" %) "/definitely-missing-green-kill" (executable %))}
        (fn []
          (let [result (process/run-with-timeout (child-command marker pid-file false false) {} 200)]
            (is (= 124 (:exit result)))
            (is (str/includes? (:err result) "process cleanup failed")))))
      (Thread/sleep 800)
      (is (not (.exists (io/file marker))))
      (finally
        (kill-recorded-process pid-file)
        (io/delete-file marker true)))))

(deftest repeated-interruption-does-not-abandon-cleanup
  (let [marker (temp-path "repeat-marker")
        pid-file (temp-path "repeat-pid")
        thread (promise)
        outcome (promise)
        runner (future
                 (deliver thread (Thread/currentThread))
                 (deliver outcome (process/run (child-command marker pid-file true true))))]
    (try
      (is (wait-for-file pid-file))
      (Thread/sleep 120)
      (future-cancel runner)
      (Thread/sleep 100)
      (.interrupt @thread)
      (let [result (deref outcome 2000 ::not-finished)]
        (is (map? result))
        (when (map? result)
          (is (= 130 (:exit result)))
          (is (= "diagnostic\n\ncommand interrupted" (:err result)))))
      (finally
        (future-cancel runner)
        (kill-recorded-process pid-file)
        (io/delete-file marker true)))))

(deftest leader-exit-before-capture-start-cannot-hide-children
  (let [marker (temp-path "early-exit-marker")
        pid-file (temp-path "early-exit-pid")
        capture @#'green.process/capture
        command ["sh" "-c"
                 (str "sh -c " (process/posix-quote
                                 (str "sleep 0.7; touch " (process/posix-quote marker)))
                      " & echo $! > " (process/posix-quote pid-file))]]
    (try
      ;; Force the Java reaper to observe the leader exiting before stream
      ;; readers attach. Pipe EOF alone then says nothing about the child.
      (with-redefs-fn {#'green.process/capture
                      (fn [stream] (Thread/sleep 60) (capture stream))}
        (fn []
          (is (= 124 (:exit (process/run-with-timeout command {} 100))))))
      (Thread/sleep 800)
      (is (not (.exists (io/file marker))))
      (finally
        (kill-recorded-process pid-file)
        (io/delete-file marker true)))))

(deftest exited-background-children-do-not-cause-false-timeouts
  (is (= 0 (:exit (process/run-with-timeout ["sh" "-c" "sleep 0.02 &"] {} 1000)))
      "zombies waiting for the host reaper are not running commands"))
