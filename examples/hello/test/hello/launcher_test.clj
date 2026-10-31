(ns hello.launcher-test
  "Drives examples/hello through its real ./green launcher in a scratch
  directory, so the example keeps working as green changes. Needs `bb` on
  PATH (as CI provides); green itself is resolved from this checkout."
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

(def ^:private launcher
  (.getCanonicalPath (io/file "examples/hello/green")))

(def ^:private green-root
  (.getCanonicalPath (io/file ".")))

(defn- scratch-dir []
  (let [dir (.toFile (Files/createTempDirectory "green-hello" (make-array FileAttribute 0)))]
    (io/copy (io/file "examples/hello/green.yml") (io/file dir "green.yml"))
    dir))

(defn- env
  "The inherited environment minus any COLORS_PAR_* (so the operator's shell
  cannot change the result), plus `extra`."
  [extra]
  (merge (into {} (remove (fn [[k _]] (str/starts-with? k "COLORS_PAR_")))
               (System/getenv))
         {"GREEN_LIB_ROOT" green-root}
         extra))

(defn- green [dir extra-env & args]
  (apply shell/sh "bb" launcher
         (concat args [:dir (str dir) :env (env extra-env)])))

(deftest create-check-delete
  (let [dir (scratch-dir)
        greeting (io/file dir "work/hello.txt")]
    (testing "dry-run runs the read-only uname step and writes nothing"
      (let [{:keys [exit out]} (green dir {} "create" "--dry-run")]
        (is (= 0 exit))
        (is (str/includes? out "dry-run: would run :hello/write"))
        (is (str/includes? out "<<< :hello/uname"))
        (is (not (.exists greeting)))))
    (testing "create forks uname and an artifact subworkflow, then joins at check"
      (let [{:keys [exit out]} (green dir {} "create")]
        (is (= 0 exit))
        (is (re-find #"hello, world \(\S+\)" out))
        (is (= "hello, world\n" (slurp greeting)))
        (is (str/includes? out "<<< :hello/write"))
        (is (str/includes? out "<<< :hello/verify"))
        (is (str/includes? out "<<< :hello/artifact"))))
    (testing "COLORS_PAR_* overlays the desired state"
      (let [{:keys [exit out]} (green dir {"COLORS_PAR_NAME" "agent"} "create")]
        (is (= 0 exit))
        (is (str/includes? out "hello, agent"))
        (is (= "hello, agent\n" (slurp greeting)))))
    (testing "delete removes the file and is idempotent"
      (is (= 0 (:exit (green dir {} "delete"))))
      (is (not (.exists greeting)))
      (is (= 0 (:exit (green dir {} "delete")))))))

(deftest preflight-and-usage-errors-exit-2
  (let [dir (scratch-dir)]
    (testing "a blank parameter fails application validation"
      (let [{:keys [exit err]} (green dir {"COLORS_PAR_NAME" ""} "create")]
        (is (= 2 exit))
        (is (str/includes? err "COLORS_PAR_NAME"))
        (is (not (.exists (io/file dir "work/hello.txt"))))))
    (testing "events outside the package's set are usage errors"
      (let [{:keys [exit err]} (green dir {} "destroy")]
        (is (= 2 exit))
        (is (str/includes? err "Usage:"))))))

(deftest application-parser-selects-files-and-rejects-usage-errors
  (let [dir (scratch-dir)
        selected (io/file dir "selected.edn")]
    (spit selected "{:name \"selected\" :greeting-file \"work/selected.txt\"}")
    (is (= 0 (:exit (green dir {} "create" "-f" (str selected)))))
    (is (= "hello, selected\n" (slurp (io/file dir "work/selected.txt"))))
    (doseq [argv [["create" "--unknown"] ["create" "extra"] []]]
      (is (= 2 (:exit (apply green dir {} argv)))))
    (spit selected "[]")
    (is (= 2 (:exit (green dir {} "create" "-f" (str selected)))))))
