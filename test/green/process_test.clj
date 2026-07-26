(ns green.process-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [green.process :as process]))

(deftest run-captures-the-outcome
  (let [{:keys [exit out]} (process/run ["echo" "hello"])]
    (is (= 0 exit))
    (is (= "hello" (str/trim out))))

  (testing "a non-zero exit is a value, not an exception"
    (is (pos? (:exit (process/run ["sh" "-c" "exit 3"])))))

  (testing "a command that cannot start reports exit -1"
    (is (= -1 (:exit (process/run ["definitely-not-a-real-command-xyz"])))))

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
      (is (= -1 exit))
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
