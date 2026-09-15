(ns green.kubernetes-client-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [green.kubernetes.client :as client]))

(def descriptor {:group "colors.getcolors.ai" :version "v1alpha1"
                 :plural "localservicedeployments"})
(def resource {:apiVersion "colors.getcolors.ai/v1alpha1"
               :kind "LocalServiceDeployment"
               :metadata {:name "demo" :namespace "dev" :resourceVersion "42" :uid "uid"}})

(deftest explicit-target
  (is (thrown? clojure.lang.ExceptionInfo (client/kubectl-client {})))
  (is (thrown? clojure.lang.ExceptionInfo (client/kubectl-client {:context ""})))
  (is (:in-cluster? (client/kubectl-client {:in-cluster? true}))))

(deftest transport-and-concurrency
  (let [calls (atom [])
        runner (fn [args opts timeout]
                 (let [filename (last args)]
                   (swap! calls conj {:args args :opts opts :timeout timeout
                                      :body (when (= "-f" (nth args (- (count args) 2)))
                                              (json/parse-string (slurp filename) true))}))
                 {:exit 0 :out (json/generate-string resource)})
        c (client/kubectl-client {:context "kind-test" :kubeconfig "/tmp/test-kubeconfig" :runner runner})]
    ((:update-status! c) descriptor resource {:phase "Ready"})
    (let [{:keys [args body]} (first @calls)]
      (is (= ["kubectl" "--kubeconfig" "/tmp/test-kubeconfig" "--context" "kind-test"] (subvec args 0 5)))
      (is (= "42" (get-in body [:metadata :resourceVersion])))
      (is (= {:phase "Ready"} (:status body)))
      (is (not (.exists (java.io.File. (last args))))))
    ((:update-finalizers! c) descriptor resource ["colors.getcolors.ai/infrastructure"])
    (let [args (:args (last @calls))
          patch (json/parse-string (nth args (inc (.indexOf args "-p"))) true)]
      (is (= "42" (get-in patch [:metadata :resourceVersion])))
      (is (= ["colors.getcolors.ai/infrastructure"] (get-in patch [:metadata :finalizers]))))))

(deftest empty-results-and-private-errors
  (let [c (client/kubectl-client {:context "kind-test" :runner (fn [& _] {:exit 0 :out ""})})]
    (is (nil? ((:get-resource c) descriptor "dev" "gone"))))
  (let [filename (atom nil)
        c (client/kubectl-client
           {:context "kind-test"
            :runner (fn [args _ _]
                      (reset! filename (last args))
                      {:exit 1 :out "secret" :err "secret"})})
        error (try (client/request! c ["apply" "-f" "-"] {:input "secret"})
                   (catch clojure.lang.ExceptionInfo e e))]
    (is (= {:exit 1 :operation "apply"} (ex-data error)))
    (is (not (.exists (java.io.File. @filename)))))
  (testing "input cannot be silently discarded"
    (is (thrown? clojure.lang.ExceptionInfo
                 (client/request! {:context "kind-test"} ["get" "pods"] {:input "{}"})))))

(deftest in-cluster-authentication-is-explicit-and-follows-token-file
  (let [path (atom nil)
        c (client/kubectl-client
           {:in-cluster? true :service-host "10.96.0.1" :service-port "443"
            :runner (fn [args _ _]
                      (let [file (nth args (inc (.indexOf args "--kubeconfig")))
                            config (json/parse-string (slurp file) true)]
                        (reset! path file)
                        (is (= "https://10.96.0.1:443" (get-in config [:clusters 0 :cluster :server])))
                        (is (= "/var/run/secrets/kubernetes.io/serviceaccount/token"
                               (get-in config [:users 0 :user :tokenFile])))
                        (is (nil? (get-in config [:users 0 :user :token])))
                        (is (some #{"--request-timeout=30s"} args)))
                      {:exit 0 :out "{}"})})]
    (client/request! c ["get" "pods" "-o" "json"])
    (is (not (.exists (java.io.File. @path))))))
