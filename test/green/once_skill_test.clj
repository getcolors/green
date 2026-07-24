(ns green.once-skill-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private repo-root
  (.getCanonicalPath (io/file ".")))

(def ^:private launcher
  (.getCanonicalPath (io/file "examples/once-skill/green")))

(def ^:private desired-state
  {:profile "test"
   :workdir ".green"
   :domain "example.com"
   :package "test"
   :deploy-pubkey "ssh-ed25519 AAAATest deploy"
   :once {:applications [{:host "www.example.com"
                          :image "ghcr.io/example/app:latest"
                          :env {"APP_SECRET" "ONCE_APP_SECRET"}}]}
   :provider-compute "no-infra"
   :provider-smtp "no-infra"
   :provider-dns "no-infra"
   :provider-backend "local"
   :compute-prevent-destroy true
   :no-infra-compute-ip "192.0.2.10"
   :no-infra-compute-user "root"
   :no-infra-compute-sudoer "root"
   :no-infra-compute-uid 0
   :no-infra-compute-name "test"
   :no-infra-smtp-server "smtp.example.net"
   :no-infra-smtp-port 587
   :no-infra-smtp-username "smtp-user"})

(defn- bb-available? []
  (try
    (zero? (:exit (sh/sh "bb" "--version")))
    (catch Exception _ false)))

(defn- tmpdir []
  (str (Files/createTempDirectory "green-once-skill"
                                  (make-array FileAttribute 0))))

(defn- write-state! [dir]
  (spit (io/file dir "green.edn") (str (pr-str desired-state) "\n")))

(defn- process-env
  [overrides]
  (merge (dissoc (into {} (System/getenv))
                 "ONCE_SMTP_PASSWORD" "ONCE_APP_SECRET")
         {"GREEN_LIB_ROOT" repo-root}
         overrides))

(defn- run-green
  [dir env & args]
  (apply sh/sh
         (concat ["bb" launcher]
                 args
                 [:dir dir :env (process-env env)])))

(defn- eval-launcher
  [form]
  (sh/sh "bb" "-e"
         (str "(load-file " (pr-str launcher) ")\n" form)
         :env (process-env {})))

(defn- generated-text
  [dir]
  (->> (file-seq (io/file dir ".green"))
       (filter #(.isFile ^java.io.File %))
       (map slurp)
       (str/join "\n")))

(deftest once-skill-package
  (testing "Agent Skill metadata is discoverable"
    (let [skill (slurp "examples/once-skill/SKILL.md")]
      (is (str/starts-with? skill "---\nname: once-skill\n"))
      (is (str/includes? skill "description:"))
      (is (.canExecute (io/file launcher)))))

  (if-not (bb-available?)
    (println "SKIP green.once-skill-test launcher checks: bb not on PATH")
    (do
      (testing "build renders the complete project without exposing environment secrets"
        (let [dir (tmpdir)
              _state (write-state! dir)
              secret "must-not-appear-in-generated-files"
              result (run-green dir
                                {"ONCE_SMTP_PASSWORD" secret
                                 "ONCE_APP_SECRET" secret}
                                "build")
              text (generated-text dir)
              module (io/file dir ".green" "test" "ansible-remote" "library" "once")
              args-file (io/file dir "module-args.json")
              _args (spit args-file "{\"applications\":[]}\n")
              module-result (when (.exists module)
                              (sh/sh "bb" (.getPath module) (.getPath args-file)))]
          (is (= 0 (:exit result)) (:err result))
          (doseq [path ["tofu-compute/main.tf.json"
                        "tofu-smtp/main.tf.json"
                        "tofu-dns/main.tf.json"
                        "tofu-smtp-post/main.tf.json"
                        "ansible-local/config"
                        "ansible-remote/main.json"
                        "ansible-remote/once.json"
                        "ansible-remote/library/once"
                        "ansible-remote/files/deploy"]]
            (is (.exists (io/file dir ".green" "test" path)) path))
          (is (not (str/includes? text secret)))
          (is (str/includes? text "lookup('env', 'ONCE_APP_SECRET')"))
          (is (str/includes? text "lookup('env', 'ONCE_SMTP_PASSWORD')"))
          (when (.exists module)
            (is (str/starts-with? (slurp module)
                                  "#!/usr/local/bin/bb\n;; WANT_JSON\n"))
            (is (= 1 (:exit module-result)) (:err module-result))
            (is (str/includes? (:out module-result)
                               "Missing required parameter: applications")))))

      (testing "Cloudflare DNS priorities are valid numbers or omitted"
        (let [result (eval-launcher
                      "(let [f (ns-resolve 'user 'dns-record)]\n  (prn [(f \"zone\" {:name \"txt\" :type \"TXT\" :value \"v=spf1\" :priority \"\"})\n        (f \"zone\" {:name \"mx\" :type \"MX\" :value \"smtp.example.com\" :priority \"10\"})]))")]
          (is (= 0 (:exit result)) (:err result))
          (when (zero? (:exit result))
            (let [[txt mx] (edn/read-string (str/trim (:out result)))]
              (is (not (contains? txt :priority)))
              (is (= 10 (:priority mx)))
              (is (number? (:priority mx)))))))

      (testing "dry-run touches no generated work"
        (let [dir (tmpdir)
              _ (write-state! dir)
              result (run-green dir {} "create" "--dry-run")]
          (is (= 0 (:exit result)) (:err result))
          (is (not (.exists (io/file dir ".green"))))))

      (testing "real create fails before tools run when secrets are absent"
        (let [dir (tmpdir)
              _ (write-state! dir)
              result (run-green dir {} "create")]
          (is (= 2 (:exit result)))
          (is (str/includes? (:err result) "ONCE_SMTP_PASSWORD"))
          (is (str/includes? (:err result) "ONCE_APP_SECRET"))
          (is (not (.exists (io/file dir ".green")))))))))
