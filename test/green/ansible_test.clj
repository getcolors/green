(ns green.ansible-test
  "Playbook selection, recap parsing, inventory rendering, and
  ansible-with-spec scaffolding need no ansible binary — only
  `ansible-step` itself shells out."
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.test :refer [deftest is testing]]
   [green.ansible :as ansible])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

(defn- tmpdir []
  (str (Files/createTempDirectory "green-ansible" (make-array FileAttribute 0))))

(deftest playbook-follows-the-event
  (is (= "create.yml" (ansible/playbook {:green/event :create})))
  (is (= "create.yml" (ansible/playbook {:green/event :rotate}))
      "any non-delete event provisions")
  (is (= "delete.yml" (ansible/playbook {:green/event :delete})))
  (testing "partial overrides keep the other default"
    (is (= "teardown.yml"
           (ansible/playbook {:green/event :delete} {:delete "teardown.yml"})))
    (is (= "create.yml"
           (ansible/playbook {:green/event :create} {:delete "teardown.yml"})))))

(deftest recap-parses-per-host-counters
  (let [out (str "PLAY RECAP *********************************************\n"
                 "zk1                        : ok=7    changed=4    unreachable=0    failed=0    skipped=1    rescued=0    ignored=0\n"
                 "zk2                        : ok=7    changed=0    unreachable=0    failed=1    skipped=1    rescued=0    ignored=0\n")]
    (is (= {"zk1" {:ok 7 :changed 4 :unreachable 0 :failed 0
                   :skipped 1 :rescued 0 :ignored 0}
            "zk2" {:ok 7 :changed 0 :unreachable 0 :failed 1
                   :skipped 1 :rescued 0 :ignored 0}}
           (ansible/parse-recap out))))
  (is (= {} (ansible/parse-recap "no recap here"))))

(deftest inventory-ini-renders-groups-hosts-and-vars
  (is (= (str "[zookeeper]\n"
              "zk1 ansible_host=10.0.0.1 zk_id=1\n"
              "zk2 ansible_host=10.0.0.2 zk_id=2\n"
              "\n"
              "[zookeeper:vars]\n"
              "ansible_python_interpreter=/usr/bin/python3\n"
              "ansible_user=root\n")
         (ansible/inventory-ini
          {"zookeeper"
           {:hosts [{:name "zk1" :vars {:ansible_host "10.0.0.1" :zk_id 1}}
                    {:name "zk2" :vars {:ansible_host "10.0.0.2" :zk_id 2}}]
            :vars {:ansible_user "root"
                   :ansible_python_interpreter "/usr/bin/python3"}}}))))

(deftest inventory-ini-without-group-vars
  (is (= "[web]\nw1\n"
         (ansible/inventory-ini {:web {:hosts [{:name "w1"}]}}))))

(deftest inventory-advice-writes-the-file
  (let [dir (tmpdir)
        file (str dir "/hosts/inventory.ini")
        advice (ansible/inventory-advice
                (constantly file)
                (fn [opts]
                  {"zookeeper"
                   {:hosts (mapv (fn [{:keys [name ip]}]
                                   {:name name :vars {:ansible_host ip}})
                                 (:servers opts))}}))
        opts {:servers [{:name "zk1" :ip "10.0.0.1"}]}]
    (is (= opts (advice opts)) "before-advice passes opts through")
    (is (= "[zookeeper]\nzk1 ansible_host=10.0.0.1\n"
           (slurp file)))))

(def ^:private fake-recap
  (str "PLAY RECAP *********************************************\n"
       "localhost                  : ok=1    changed=0    unreachable=0"
       "    failed=0    skipped=0    rescued=0    ignored=0\n"))

(defn- stub-sh [& args]
  (let [cmd (first args)]
    (if (= "ansible-playbook" cmd)
      {:exit 0 :out fake-recap :err ""}
      (apply sh/sh args))))

(deftest ansible-with-spec-scaffolds-on-create
  (let [dir (tmpdir)
        specs [{:template :greentest/create.yml
                :target (str dir "/create.yml")
                :data {:group "web" :name "test"}}
               {:template :greentest/ansible.cfg
                :target (str dir "/ansible.cfg")
                :data {:inventory "inventory.ini"
                       :host_key_checking "False"}}]]
    (with-redefs [sh/sh stub-sh]
      (let [opts (ansible/ansible-with-spec
                  {:green/event :create}
                  {:dir dir :inventory "inventory.ini"}
                  specs)]
        (testing "scaffolded playbook is rendered"
          (is (.exists (io/file dir "create.yml")))
          (is (re-find #"hosts: web" (slurp (str dir "/create.yml")))))
        (testing "scaffolded ansible.cfg is rendered"
          (is (.exists (io/file dir "ansible.cfg")))
          (is (re-find #"host_key_checking = False"
                       (slurp (str dir "/ansible.cfg")))))
        (testing "ansible-step ran and returned recap"
          (is (= 0 (:green/exit opts)))
          (is (= {"localhost" {:ok 1 :changed 0 :unreachable 0 :failed 0
                               :skipped 0 :rescued 0 :ignored 0}}
                 (:ansible/recap opts))))))))

(deftest ansible-with-spec-cleans-up-on-delete
  (let [dir (tmpdir)
        specs [{:template :greentest/create.yml
                :target (str dir "/create.yml")
                :data {:group "web" :name "test"}}
               {:template :greentest/ansible.cfg
                :target (str dir "/ansible.cfg")
                :data {:inventory "inventory.ini"
                       :host_key_checking "False"}}]
        present (atom nil)]
    (with-redefs [sh/sh (fn [& args]
                          ;; the teardown play cannot run from files that
                          ;; have already been deleted
                          (reset! present (.exists (io/file dir "create.yml")))
                          (apply stub-sh args))]
      (testing "ansible-step ran the delete playbook"
        (is (= 0 (:green/exit (ansible/ansible-with-spec
                               {:green/event :delete}
                               {:dir dir :inventory "inventory.ini"}
                               specs)))))
      (testing "the scaffold was rendered before the play ran"
        (is (true? @present)))
      (testing "scaffolded files are removed afterwards"
        (is (not (.exists (io/file dir "create.yml"))))
        (is (not (.exists (io/file dir "ansible.cfg"))))))))

(deftest ansible-with-spec-renders-only-on-build
  (let [dir (tmpdir)
        specs [{:template :greentest/create.yml
                :target (str dir "/create.yml")
                :data {:group "web" :name "test"}}]]
    (with-redefs [sh/sh (fn [& _] (throw (ex-info "ansible must not run" {})))]
      (let [opts (ansible/ansible-with-spec
                  {:green/event :build}
                  {:dir dir :inventory "inventory.ini"}
                  specs)]
        (is (= 0 (:green/exit opts)))
        (is (.exists (io/file dir "create.yml")))))))
