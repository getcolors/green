(ns green.kubernetes.client
  "Explicit-context kubectl transport for the local Kubernetes controller.

  Kubernetes authentication remains kubectl's responsibility. The controller
  receives maps and functions so lifecycle tests need no cluster or subprocess."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [green.process :as process])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- command [client args]
  (into (cond-> [(or (:kubectl client) "kubectl")]
          (:kubeconfig client) (into ["--kubeconfig" (:kubeconfig client)])
          (:context client) (into ["--context" (:context client)])
          true (conj (str "--request-timeout=" (:request-timeout-seconds client 30) "s")))
        args))

(defn- in-cluster-config [client]
  (let [host (or (:service-host client) (System/getenv "KUBERNETES_SERVICE_HOST"))
        port (or (:service-port client) (System/getenv "KUBERNETES_SERVICE_PORT") "443")
        dir "/var/run/secrets/kubernetes.io/serviceaccount"]
    (when (str/blank? host)
      (throw (ex-info "In-cluster Kubernetes service address is unavailable" {})))
    {:apiVersion "v1" :kind "Config"
     :clusters [{:name "in-cluster"
                 :cluster {:server (str "https://" (if (str/includes? host ":")
                                                    (str "[" host "]") host) ":" port)
                           :certificate-authority (str dir "/ca.crt")}}]
     ;; tokenFile follows token rotation and avoids putting credentials in argv
     ;; or copying credentials into the temporary kubeconfig.
     :users [{:name "service-account" :user {:tokenFile (str dir "/token")}}]
     :contexts [{:name "green-in-cluster"
                 :context {:cluster "in-cluster" :user "service-account"}}]
     :current-context "green-in-cluster"}))

(defn request!
  "Execute argv through kubectl. Returns parsed JSON, nil for empty output, or
  text with :raw? true. :input supplies a JSON/YAML document for `-f -`; a private
  temporary file is removed on success and failure. No shell interpolation.
  :timeout-ms overrides the default process deadline for waits and rollouts."
  ([client args] (request! client args {}))
  ([client args {:keys [input raw? timeout-ms]}]
   (let [temporary-files (atom [])
         temporary! (fn []
                      (let [path (Files/createTempFile "green-kubernetes-" ".json"
                                                       (make-array FileAttribute 0))]
                        (swap! temporary-files conj path)
                        path))]
     (try
       (let [file (when input (temporary!))
             _ (when file (spit (str file) input))
             client (if (:in-cluster? client)
                      (let [config (in-cluster-config client)
                            path (temporary!)]
                        (spit (str path) (json/generate-string config))
                        (assoc client :kubeconfig (str path) :context "green-in-cluster"))
                      client)
             args (vec args)
             input-index (when input
                           (first (keep-indexed
                                   (fn [i x]
                                     (when (and (= x "-") (pos? i)
                                                (#{"-f" "--filename"} (nth args (dec i)))) i))
                                   args)))
             _ (when (and input (nil? input-index))
                 (throw (ex-info "request input requires -f -" {})))
             args (if input-index (assoc args input-index (str file)) args)
             runner (or (:runner client) process/run-with-timeout)
             result (runner (command client args) {} (or timeout-ms (:timeout-ms client) 45000))]
         (when-not (zero? (:exit result))
           ;; Provider output can contain credentials. Keep errors suitable for
           ;; callers to expose without leaking raw stdout/stderr or arguments.
           (throw (ex-info "Kubernetes request failed"
                           {:exit (:exit result) :operation (first args)})))
         (if raw? (:out result)
             (when-not (str/blank? (:out result))
               (json/parse-string (:out result) true))))
       (finally
         (doseq [file @temporary-files] (Files/deleteIfExists file)))))))

(defn- resource-type [{:keys [plural group]}]
  (str plural "." group))

(defn- status-path [{:keys [group version plural]} resource]
  (str "/apis/" group "/" version "/namespaces/"
       (get-in resource [:metadata :namespace]) "/" plural "/"
       (get-in resource [:metadata :name]) "/status"))

(defn kubectl-client
  "Create a transport with explicit :context, or :in-cluster? true. Optional
  :kubeconfig selects a dedicated file without modifying the user's context.
  Metadata and status updates carry resourceVersion for conflict detection."
  [{:keys [context in-cluster?] :as config}]
  (when-not (or (and (string? context) (not (str/blank? context)))
                (true? in-cluster?))
    (throw (ex-info "An explicit Kubernetes context or :in-cluster? true is required" {})))
  (assoc config
         :list-resources
         (fn [descriptor namespace]
           (:items (request! config ["get" (resource-type descriptor)
                                     "-n" namespace "-o" "json"])))
         :get-resource
         (fn [descriptor namespace name]
           (request! config ["get" (resource-type descriptor) name "-n" namespace
                             "--ignore-not-found" "-o" "json"]))
         :update-status!
         (fn [descriptor resource status]
           (request! config ["replace" "--raw" (status-path descriptor resource) "-f" "-"]
                     {:input (json/generate-string
                              {:apiVersion (:apiVersion resource) :kind (:kind resource)
                               :metadata (select-keys (:metadata resource)
                                                      [:name :namespace :uid :resourceVersion])
                               :status status})}))
         :update-finalizers!
         (fn [descriptor resource finalizers]
           (request! config ["patch" (resource-type descriptor)
                             (get-in resource [:metadata :name]) "-n"
                             (get-in resource [:metadata :namespace]) "--type=merge"
                             "-p" (json/generate-string
                                   {:metadata {:resourceVersion (get-in resource [:metadata :resourceVersion])
                                               :finalizers (vec finalizers)}})
                             "-o" "json"]))))
