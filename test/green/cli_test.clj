(ns green.cli-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [green.cli :as cli]
   [green.lifecycle :as lifecycle]
   [green.providers :as providers]
   [green.workflow :as wf])
  (:import
   [java.io File]))

(defn- state-file
  ([content] (state-file content ".edn"))
  ([content ext]
   (let [f (File/createTempFile "green-state" ext)]
     (spit f content)
     (str f))))

(defn- probe-wf []
  (wf/workflow {:start :t/a
                :wire-fn (fn [s _]
                           (case s
                             :t/a [(fn [o] (update o :seen (fnil conj []) [:a (:green/event o) (:x o)]))
                                   :t/b]
                             :t/b [(fn [o] (update o :seen conj :b))]))}))

(deftest event-and-state-flow-into-the-workflow
  (let [res (cli/run-cli (probe-wf) ["create" "-f" (state-file "{:x 1}")])]
    (is (= 0 (:green/exit res)))
    (is (= [[:a :create 1] :b] (:seen res)))))

(deftest arbitrary-events-are-allowed
  (let [res (cli/run-cli (probe-wf) ["provision" "-f" (state-file "{:x 2}")])]
    (is (= [[:a :provision 2] :b] (:seen res)))))

(deftest slices-via-start-and-end
  (testing "--end is an inclusive boundary"
    (let [res (cli/run-cli (probe-wf)
                           ["create" "-f" (state-file "{:x 1}") "--end" "t/a"])]
      (is (= [[:a :create 1]] (:seen res)))))
  (testing "--start skips earlier steps"
    (let [res (cli/run-cli (probe-wf)
                           ["create" "-f" (state-file "{:x 1}") "--start" "t/b"])]
      (is (= [:b] (:seen res))))))

(deftest dry-run-flag-stamps-the-key
  (let [wf (wf/workflow {:start :t/a
                         :wire-fn (fn [_ _] [(fn [o] (assoc o :dry (:green/dry-run o)))])})]
    (is (true? (:dry (cli/run-cli wf ["create" "-f" (state-file "{}") "--dry-run"]))))
    (is (nil? (:dry (cli/run-cli wf ["create" "-f" (state-file "{}")]))))))

(deftest usage-errors-exit-2
  (testing "missing event"
    (let [res (cli/run-cli (probe-wf) [])]
      (is (= 2 (:green/exit res)))
      (is (re-find #"Usage" (:green/err res)))))
  (testing "missing state file"
    (let [res (cli/run-cli (probe-wf) ["create" "-f" "/nonexistent/green.edn"])]
      (is (= 2 (:green/exit res)))
      (is (re-find #"not found" (:green/err res))))))

(deftest colors-par-variables-overlay-desired-state
  (testing "the file supplies structure, the environment supplies secrets"
    (is (= {:do-token "tok" :profile "prod"}
           (cli/read-pars {:profile "prod"} {"COLORS_PAR_DO_TOKEN" "tok"
                                             "PATH" "/usr/bin"}))))

  (testing "hyphens in the key are underscores in the variable"
    (is (= "COLORS_PAR_R2_ACCESS_KEY_ID" (cli/par-name :r2-access-key-id)))
    (is (= {:r2-access-key-id "id"}
           (cli/read-pars {} {"COLORS_PAR_R2_ACCESS_KEY_ID" "id"}))))

  (testing "an override takes the type of the value it replaces"
    (is (= {:prevent-destroy false}
           (cli/read-pars {:prevent-destroy true}
                          {"COLORS_PAR_PREVENT_DESTROY" "false"})))
    (is (= {:port 25}
           (cli/read-pars {:port 587} {"COLORS_PAR_PORT" "25"})))
    (is (= {:name "x"} (cli/read-pars {:name "y"} {"COLORS_PAR_NAME" "x"}))))

  (testing "applying the overlay twice changes nothing"
    (let [env {"COLORS_PAR_PREVENT_DESTROY" "false"}
          once (cli/read-pars {:prevent-destroy true} env)]
      (is (= once (cli/read-pars once env)))))

  (testing "the bare prefix is not a key"
    (is (= {} (cli/read-pars {} {"COLORS_PAR_" "x"}))))

  (testing "no colour keeps a prefix of its own"
    (is (= {} (cli/read-pars {} {"GREEN_PAR_DO_TOKEN" "tok"
                                 "RED_PAR_DO_TOKEN" "tok"
                                 "BLUE_PAR_DO_TOKEN" "tok"
                                 "ONCE_PAR_DO_TOKEN" "tok"})))))

(deftest shared-package-conventions
  (let [file (state-file "{}")]
    (is (= (str (.getParent (io/file file)) "/.colors/demo/tool")
           (cli/stage-dir {:green/state-file file :profile "demo"} "tool"))))
  (is (= {:green/event :create :x 1 :green/exit 0}
         (lifecycle/preflight {:green/event :create}
                              {:defaults {:x 1} :validators [(fn [_ _ _] [])]}
                              {})))
  (is (= [:token] (providers/missing-keys {} [:token])))
  (is (= {"TOKEN" "secret"}
         (providers/tool-env {:provider {"x" {:tofu-env {:token "TOKEN"}}}}
                             {:provider "x" :token "secret"} [:provider]))))

(deftest desired-state-is-read-by-extension
  (testing "yaml keys arrive as keywords, nesting included"
    (is (= {:profile "prod" :once {:applications [{:host "www.example.com"}]}}
           (cli/read-state "colors.yml"
                           "profile: prod\nonce:\n  applications:\n    - host: www.example.com\n"))))

  (testing "the 1.2 core schema leaves no/yes/on as the strings they look like"
    (is (= {:a "no" :b "yes" :c "on" :d true :e 12}
           (cli/read-state "colors.yaml" "a: no\nb: yes\nc: on\nd: true\ne: 012\n"))))

  (testing "edn is still read, so existing projects keep working"
    (is (= {:profile "prod" :port 587}
           (cli/read-state "green.edn" "{:profile \"prod\" :port 587}"))))

  (testing "run-cli reads a yaml state file end to end"
    (let [res (cli/run-cli (probe-wf) ["create" "-f" (state-file "x: 3\n" ".yml")])]
      (is (= 0 (:green/exit res)))
      (is (= [[:a :create 3] :b] (:seen res))))))

(deftest run-cli-overlays-pars-onto-the-state-file
  (let [wf (wf/workflow {:start :t/a
                         :wire-fn (fn [_ _] [(fn [o] (assoc o :seen (:token o)))])})]
    (with-redefs [cli/read-pars (fn [opts] (assoc opts :token "from-env"))]
      (is (= "from-env"
             (:seen (cli/run-cli wf ["create" "-f" (state-file "{:token \"REPLACE_ME\"}")])))))))
