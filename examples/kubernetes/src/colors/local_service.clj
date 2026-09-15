(ns colors.local-service
  "A disposable package demonstrating direct Green workflow reconciliation."
  (:require [cheshire.core :as json]
            [green.kubernetes.client :as client]
            [green.workflow :as wf])
  (:import [java.security MessageDigest]))

(def resource {:group "colors.getcolors.ai" :version "v1alpha1"
               :plural "localservicedeployments" :kind "LocalServiceDeployment"})
(def owner-key :colors.getcolors.ai/owner-uid)
(def image "registry.k8s.io/pause:3.10")

(defn workload-name [profile]
  (str "colors-" (subs (apply str (map #(format "%02x" (bit-and 255 %))
                                      (.digest (MessageDigest/getInstance "SHA-256")
                                               (.getBytes (str profile) "UTF-8")))) 0 20)))

(defn validate [opts]
  (cond-> []
    (not (and (integer? (:replicas opts)) (<= 0 (:replicas opts) 5)))
    (conj "replicas must be an integer from 0 to 5")
    (not (and (string? (:message opts)) (<= (count (:message opts)) 256)))
    (conj "message must be a string of at most 256 characters")
    (not (boolean? (:compute-prevent-destroy opts)))
    (conj "compute-prevent-destroy must be boolean")))

(defn- namespace-of [opts]
  (get-in opts [:green.kubernetes/resource :metadata :namespace]))
(defn- uid-of [opts]
  (get-in opts [:green.kubernetes/resource :metadata :uid]))
(defn- replicas [opts]
  (if (= "stopped" (get-in opts [:green.kubernetes/resource :spec :state]))
    0 (:replicas opts)))

(defn desired [opts]
  (let [labels {:app.kubernetes.io/name (workload-name (:profile opts))}]
    {:apiVersion "apps/v1" :kind "Deployment"
     :metadata {:name (workload-name (:profile opts)) :namespace (namespace-of opts)
                :annotations {owner-key (uid-of opts)}}
     :spec {:replicas (replicas opts)
            :selector {:matchLabels labels}
            :template {:metadata {:labels labels
                                  :annotations {:colors.getcolors.ai/message (:message opts)}}
                       :spec {:containers [{:name "pause" :image image
                                            :imagePullPolicy "IfNotPresent"
                                            :resources {:requests {:cpu "1m" :memory "4Mi"}
                                                        :limits {:cpu "50m" :memory "32Mi"}}}]}}}}))

(defn- read-workload [transport opts]
  (client/request! transport ["get" "deployment" (workload-name (:profile opts))
                              "-n" (namespace-of opts) "--ignore-not-found" "-o" "json"]))

(defn- ensure-owned! [actual opts]
  (when (and actual (not= (uid-of opts) (get-in actual [:metadata :annotations owner-key])))
    (throw (ex-info "Refusing to adopt or mutate a Deployment owned by another resource"
                    {:category :ownership}))))

(defn observe [transport opts]
  (let [actual (read-workload transport opts)
        _ (ensure-owned! actual opts)
        target (desired opts)
        container (get-in actual [:spec :template :spec :containers 0])
        expected-container (get-in target [:spec :template :spec :containers 0])
        matches? (and actual
                      (= (get-in target [:spec :replicas]) (get-in actual [:spec :replicas]))
                      (= (:message opts) (get-in actual [:spec :template :metadata :annotations :colors.getcolors.ai/message]))
                      (= (get-in target [:spec :selector]) (get-in actual [:spec :selector]))
                      (= (get-in target [:spec :template :metadata :labels])
                         (get-in actual [:spec :template :metadata :labels]))
                      (= 1 (count (get-in actual [:spec :template :spec :containers])))
                      (= expected-container (select-keys container (keys expected-container))))
        ready? (and actual
                    (>= (get-in actual [:status :observedGeneration] 0)
                        (get-in actual [:metadata :generation] 1))
                    (= (replicas opts) (get-in actual [:status :readyReplicas] 0))
                    (= (replicas opts) (get-in actual [:status :replicas] 0))
                    (= (replicas opts) (get-in actual [:status :updatedReplicas] 0)))]
    {:exists? (boolean actual) :matches? (boolean matches?) :ready? (boolean ready?)}))

(defn package [transport]
  (let [step (fn [opts]
               (let [actual (read-workload transport opts)]
                 (ensure-owned! actual opts)
                 (if (= :delete (:green/event opts))
                   (when actual
                     (client/request! transport
                                      ["delete" "--raw"
                                       (str "/apis/apps/v1/namespaces/" (namespace-of opts)
                                            "/deployments/" (workload-name (:profile opts)))
                                       "-f" "-"]
                                      {:input (json/generate-string
                                               {:apiVersion "v1" :kind "DeleteOptions"
                                                :propagationPolicy "Foreground"
                                                :preconditions (select-keys (:metadata actual)
                                                                           [:uid :resourceVersion])})}))
                   (client/request! transport [(if actual "replace" "create") "-f" "-" "-o" "json"]
                                    {:input (json/generate-string (cond-> (desired opts)
                                              actual (assoc-in [:metadata :resourceVersion]
                                                               (get-in actual [:metadata :resourceVersion]))))})))
               opts)
        workflow (wf/workflow {:start ::apply :wire-fn (fn [_ _] [step])})]
    {:resource resource
     :validate validate
     ;; Include target namespace: the same profile in this namespace shares one workload.
     :identity (fn [opts] ["local-kubernetes" (namespace-of opts) (:profile opts)])
     :observe #(observe transport %)
     :converge #(wf/run workflow %)
     :delete #(wf/run workflow (assoc % :green/event :delete))}))
