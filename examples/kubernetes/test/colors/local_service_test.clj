(ns colors.local-service-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [colors.local-service :as local]
            [green.kubernetes.client :as client]))

(def opts
  {:profile "dev-service" :replicas 1 :message "hello"
   :compute-prevent-destroy false
   :green.kubernetes/resource
   {:metadata {:name "demo" :namespace "colors-dev" :uid "owner-1"}
    :spec {:state "running"}}})

(defn- workload [options]
  (-> (local/desired options)
      (update :metadata assoc :uid "workload-1" :resourceVersion "42" :generation 3)
      (assoc :status {:observedGeneration 3 :readyReplicas 1 :replicas 1 :updatedReplicas 1})))

(defn- fake-client [actual calls]
  (client/kubectl-client
   {:context "kind-colors-test"
    :runner (fn [args _ _]
              (let [file-index (.indexOf args "-f")
                    body (when (pos? file-index)
                           (json/parse-string (slurp (nth args (inc file-index))) true))]
                (swap! calls conj {:args args :body body})
                {:exit 0 :out (if (some #{"get"} args)
                               (if actual (json/generate-string actual) "")
                               "{}")}))}))

(deftest controlled-drift-and-readiness
  (let [actual (workload opts)
        observe #(local/observe (fake-client % (atom [])) opts)]
    (is (= {:exists? true :matches? true :ready? true} (observe actual)))
    (is (= {:exists? false :matches? false :ready? false} (observe nil)))
    (doseq [[label path value]
            [["replicas" [:spec :replicas] 2]
             ["message" [:spec :template :metadata :annotations :colors.getcolors.ai/message] "drift"]
             ["selector" [:spec :selector :matchLabels] {:app "other"}]
             ["labels" [:spec :template :metadata :labels] {:app "other"}]
             ["image" [:spec :template :spec :containers 0 :image] "other:image"]
             ["name" [:spec :template :spec :containers 0 :name] "other"]
             ["pull policy" [:spec :template :spec :containers 0 :imagePullPolicy] "Always"]
             ["resources" [:spec :template :spec :containers 0 :resources :limits :memory] "64Mi"]]]
      (testing label
        (is (false? (:matches? (observe (assoc-in actual path value)))))))
    (is (false? (:matches? (observe (update-in actual [:spec :template :spec :containers]
                                              conj {:name "extra" :image "other:image"})))))
    (testing "an old rollout or missing ready replica is not healthy"
      (is (false? (:ready? (observe (assoc-in actual [:status :observedGeneration] 2)))))
      (is (false? (:ready? (observe (assoc-in actual [:status :readyReplicas] 0)))))))
  (testing "stopped means zero replicas, including actual rollout status"
    (let [stopped (assoc-in opts [:green.kubernetes/resource :spec :state] "stopped")
          actual (assoc (workload stopped) :status {:observedGeneration 3})]
      (is (= 0 (get-in (local/desired stopped) [:spec :replicas])))
      (is (:ready? (local/observe (fake-client actual (atom [])) stopped))))))

(deftest foreign-and-retained-workloads-are-not-adopted
  (doseq [owner [nil "another-resource" "deleted-owner"]]
    (let [calls (atom [])
          actual (assoc-in (workload opts) [:metadata :annotations local/owner-key] owner)
          transport (fake-client actual calls)
          package (local/package transport)]
      (testing (str "owner " owner)
        (is (thrown? clojure.lang.ExceptionInfo (local/observe transport opts)))
        (is (not= 0 (:green/exit ((:converge package) opts))))
        (is (not= 0 (:green/exit ((:delete package) opts))))
        (is (every? #(some #{"get"} (:args %)) @calls))))))

(deftest deletion-is-conditional-on-the-observed-workload
  (let [calls (atom [])
        package (local/package (fake-client (workload opts) calls))]
    (is (= 0 (:green/exit ((:delete package) opts))))
    (let [{:keys [args body]} (last @calls)]
      (is (some #{"delete"} args))
      (is (= (str "/apis/apps/v1/namespaces/colors-dev/deployments/"
                  (local/workload-name (:profile opts)))
             (nth args (inc (.indexOf args "--raw")))))
      (is (= "DeleteOptions" (:kind body)))
      (is (= "Foreground" (:propagationPolicy body)))
      (is (= {:uid "workload-1" :resourceVersion "42"} (:preconditions body)))))
  (testing "already absent is a successful no-op"
    (let [calls (atom [])]
      (is (= 0 (:green/exit ((:delete (local/package (fake-client nil calls))) opts))))
      (is (= 1 (count @calls))))))
