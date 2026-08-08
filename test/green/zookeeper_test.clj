(ns green.zookeeper-test
  "Flagship end-to-end test: a fake ZooKeeper cluster. Real Selmer renders,
  real `tofu apply`/`destroy` — but the HCL contains only locals and outputs,
  so nothing real is created and no credentials are needed."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [green.scaffold :as sc]
            [green.tofu :as tofu]
            [green.workflow :as wf])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- tofu-available? []
  (try (zero? (:exit (sh/sh "tofu" "version")))
       (catch Exception _ false)))

(defn- tmpdir []
  (str (Files/createTempDirectory "green-zk" (make-array FileAttribute 0))))

(defn- node-dir [opts node]
  (str (:zk/workdir opts) "/nodes/" (:id node)))

;; --- steps -----------------------------------------------------------------

(defn start-step [opts] opts)

(defn node-step
  "Scaffold one node's main.tf and drive tofu over it. On delete, destroy
  before removing the files tofu still needs."
  [opts]
  (let [node (:zk/node opts)
        dir (node-dir opts node)
        specs [{:template :zk/main.tf
                :target (str dir "/main.tf")
                :data {:node node}}]]
    (if (= :delete (:green/event opts))
      (let [opts (tofu/tofu-step opts {:dir dir})]
        (if (pos? (:green/exit opts 0)) opts (sc/scaffold opts specs)))
      (let [opts (sc/scaffold opts specs)]
        (tofu/tofu-step opts {:dir dir})))))

(defn zoo-cfg-step
  "Join step: render zoo.cfg (listing every member) into each server's
  directory. On create the member list comes from the branches' observed
  tofu outputs; on delete the desired state names the targets to remove."
  [opts]
  (let [nodes (:zk/servers opts)
        servers (if (= :delete (:green/event opts))
                  nodes
                  (->> (:green/branches opts)
                       (map :tofu/outputs)
                       (sort-by :id)))
        specs (for [n nodes]
                {:template :zk/zoo.cfg
                 :target (str (:zk/workdir opts) "/nodes/" (:id n) "/zoo.cfg")
                 :data {:servers servers}})]
    (sc/scaffold opts specs)))

;; --- workflow ----------------------------------------------------------------

(defn wire-fn [step _]
  (case step
    :zk/start   [start-step :zk/node]
    :zk/node    [node-step :zk/zoo-cfg]
    :zk/zoo-cfg [zoo-cfg-step]))

(defn next-fn [step default-next opts]
  (cond
    (pos? (:green/exit opts 0)) []
    ;; fan out: one :zk/node branch per server, in parallel
    (= step :zk/start) (mapv (fn [n] [:zk/node (assoc opts :zk/node n)])
                             (:zk/servers opts))
    :else (mapv (fn [s] [s opts]) default-next)))

(def cluster-wf
  (-> (wf/workflow {:start :zk/start :wire-fn wire-fn :next-fn next-fn})
      ;; the backend is not hardwired: the local filesystem backend is
      ;; injected with the advice facility
      (wf/advice-add :zk/node :before ::backend
                     (tofu/local-backend-advice #(node-dir % (:zk/node %))))))

(def desired-state
  {:zk/servers [{:id 1 :name "zk1" :ip "10.0.0.1"}
                {:id 2 :name "zk2" :ip "10.0.0.2"}
                {:id 3 :name "zk3" :ip "10.0.0.3"}]})

;; --- composition: two clusters from the same workflow ------------------------
;; `wf/step` turns cluster-wf into an ordinary step; the parent fans it out
;; once per cluster (in parallel), scoping each run with :in-fn.

(def two-clusters-wf
  (wf/workflow
   {:start :clusters/start
    :wire-fn (fn [step _]
               (case step
                 :clusters/start [start-step :clusters/cluster]
                 :clusters/cluster [(wf/step cluster-wf
                                             {:in-fn (fn [opts]
                                                       (let [c (:zk/cluster opts)]
                                                         (assoc opts
                                                                :zk/servers (:servers c)
                                                                :zk/workdir (str (:zk/workdir opts)
                                                                                 "/" (:name c)))))})
                                    :clusters/report]
                 :clusters/report [(fn [opts]
                                     (assoc opts :clusters/reported
                                            (set (map :zk/workdir (:green/branches opts)))))]))
    :next-fn (fn [step default-next opts]
               (cond
                 (pos? (:green/exit opts 0)) []
                 (= step :clusters/start) (mapv (fn [c] [:clusters/cluster (assoc opts :zk/cluster c)])
                                                (:zk/clusters opts))
                 :else (mapv (fn [s] [s opts]) default-next)))}))

(def two-clusters-state
  {:zk/clusters [{:name "alpha"
                  :servers [{:id 1 :name "zk1" :ip "10.0.1.1"}
                            {:id 2 :name "zk2" :ip "10.0.1.2"}]}
                 {:name "beta"
                  :servers [{:id 1 :name "zk1" :ip "10.0.2.1"}
                            {:id 2 :name "zk2" :ip "10.0.2.2"}]}]})

(deftest two-zookeeper-clusters
  (if-not (tofu-available?)
    (println "SKIP green.zookeeper-test/two-zookeeper-clusters: tofu not on PATH")
    (let [work (tmpdir)
          state (assoc two-clusters-state :zk/workdir work)]

      (testing "create: the cluster workflow runs twice, in parallel"
        (let [res (wf/run two-clusters-wf (assoc state :green/event :create))]
          (is (= 0 (:green/exit res)) (str (:green/err res)))
          (is (= 2 (count (:green/branches res))))
          (is (= #{(str work "/alpha") (str work "/beta")} (:clusters/reported res)))
          (doseq [{cname :name servers :servers} (:zk/clusters state)
                  {:keys [id]} servers]
            (let [dir (str work "/" cname "/nodes/" id)]
              (is (.exists (io/file dir "main.tf")) dir)
              (is (.exists (io/file dir "terraform.tfstate")) dir)
              (let [cfg (slurp (io/file dir "zoo.cfg"))]
                (doseq [{:keys [id ip]} servers]
                  (is (str/includes? cfg (str "server." id "=" ip ":2888:3888"))
                      "zoo.cfg lists this cluster's members")))))
          (testing "clusters stay isolated"
            (is (not (str/includes? (slurp (io/file (str work "/alpha/nodes/1") "zoo.cfg"))
                                    "10.0.2."))
                "alpha's zoo.cfg must not list beta's nodes"))))

      (testing "delete tears down both clusters"
        (let [res (wf/run two-clusters-wf (assoc state :green/event :delete))]
          (is (= 0 (:green/exit res)) (str (:green/err res)))
          (doseq [{cname :name servers :servers} (:zk/clusters state)
                  {:keys [id]} servers]
            (let [dir (str work "/" cname "/nodes/" id)]
              (is (not (.exists (io/file dir "main.tf"))))
              (is (not (.exists (io/file dir "zoo.cfg")))))))))))

(deftest zookeeper-fake-cluster
  (if-not (tofu-available?)
    (println "SKIP green.zookeeper-test: tofu not on PATH")
    (let [state (assoc desired-state :zk/workdir (tmpdir))
          work (:zk/workdir state)]

      (testing "create: fan-out, parallel tofu applies, join renders zoo.cfg"
        (let [res (wf/run cluster-wf (assoc state :green/event :create))]
          (is (= 0 (:green/exit res)) (str (:green/err res)))
          (is (= 3 (count (:green/branches res))))
          (testing "observed outputs flowed back into each branch"
            (is (= #{"zk1" "zk2" "zk3"}
                   (set (map (comp :name :tofu/outputs) (:green/branches res))))))
          (doseq [{:keys [id]} (:zk/servers state)]
            (let [dir (str work "/nodes/" id)]
              (is (.exists (io/file dir "main.tf")))
              (is (.exists (io/file dir "backend.tf.json")) "advice wrote the backend")
              (is (.exists (io/file dir "terraform.tfstate")) "local backend state")
              (let [cfg (slurp (io/file dir "zoo.cfg"))]
                (doseq [{:keys [id ip]} (:zk/servers state)]
                  (is (str/includes? cfg (str "server." id "=" ip ":2888:3888"))
                      "every zoo.cfg lists every member")))))))

      (testing "create is idempotent"
        (is (= 0 (:green/exit (wf/run cluster-wf (assoc state :green/event :create))))))

      (testing "delete: destroys each node and removes the generated files"
        (let [res (wf/run cluster-wf (assoc state :green/event :delete))]
          (is (= 0 (:green/exit res)) (str (:green/err res)))
          (doseq [{:keys [id]} (:zk/servers state)]
            (let [dir (str work "/nodes/" id)]
              (is (not (.exists (io/file dir "main.tf"))))
              (is (not (.exists (io/file dir "zoo.cfg")))))))))))
