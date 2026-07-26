(ns green.tofu-test
  "Backend advices need no tofu binary — they only write backend.tf.json."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [green.tofu :as tofu])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

(defn- tmpdir []
  (str (Files/createTempDirectory "green-tofu" (make-array FileAttribute 0))))

(defn- backend-config [dir]
  (json/parse-string (slurp (io/file dir "backend.tf.json"))))

(deftest local-backend-is-the-default
  (let [dir (tmpdir)
        advice (tofu/local-backend-advice (constantly dir))
        opts {:x 1}]
    (is (= opts (advice opts)) "before-advice passes opts through")
    (is (= {"terraform" {"backend" {"local" {}}}}
           (backend-config dir)))))

(deftest s3-backend-advice-writes-attributes
  (let [dir (tmpdir)]
    ((tofu/s3-backend-advice (constantly dir)
                             {:bucket "my-state"
                              :key "green/node-1.tfstate"
                              :region "eu-west-1"
                              :encrypt true})
     {})
    (is (= {"terraform"
            {"backend"
             {"s3" {"bucket" "my-state"
                    "encrypt" true
                    "key" "green/node-1.tfstate"
                    "region" "eu-west-1"}}}}
           (backend-config dir)))))

(deftest backend-config-retains-native-json-shapes
  (let [dir (tmpdir)]
    ((tofu/backend-advice (constantly dir)
                          :test
                          {:keyword :value
                           :enabled true
                           :retries 3
                           :nested {:endpoint "https://example.test"}
                           :items [:one "two"]
                           :unset nil})
     {})
    (is (= {"terraform"
            {"backend"
             {"test" {"keyword" "value"
                      "enabled" true
                      "retries" 3
                      "nested" {"endpoint" "https://example.test"}
                      "items" ["one" "two"]
                      "unset" nil}}}}
           (backend-config dir)))))

(deftest backend-config-can-be-a-function-of-opts
  (let [dir (tmpdir)]
    ((tofu/gcs-backend-advice (constantly dir)
                              (fn [opts] {:bucket "state"
                                          :prefix (str "green/" (:node opts))}))
     {:node "n1"})
    (is (= {"terraform"
            {"backend"
             {"gcs" {"bucket" "state"
                     "prefix" "green/n1"}}}}
           (backend-config dir)))))

(deftest r2-backend-advice-fills-in-the-s3-compatibility-flags
  (let [dir (tmpdir)]
    ((tofu/r2-backend-advice (constantly dir)
                             (fn [opts] {:bucket "state"
                                         :key (str (:profile opts) "/dns.tfstate")
                                         :endpoint "https://acct.r2.cloudflarestorage.com"}))
     {:profile "production"})
    (let [config (get-in (backend-config dir) ["terraform" "backend" "s3"])]
      (is (= "state" (get config "bucket")))
      (is (= "production/dns.tfstate" (get config "key")))
      (is (= "auto" (get config "region")))
      (is (= {"s3" "https://acct.r2.cloudflarestorage.com"} (get config "endpoints")))
      (testing "credentials come from the environment, never from the file"
        (is (not (contains? config "access_key")))
        (is (not (contains? config "secret_key")))))))

(deftest backends-picks-the-advice-per-run
  (let [dir (tmpdir)
        advice (tofu/backends :backend
                              {"local" (tofu/local-backend-advice (constantly dir))
                               "gcs" (tofu/gcs-backend-advice (constantly dir)
                                                              {:bucket "b" :prefix "p"})})]
    (advice {:backend "local"})
    (is (= {"local" {}} (get-in (backend-config dir) ["terraform" "backend"])))
    (advice {:backend "gcs"})
    (is (= {"gcs" {"bucket" "b" "prefix" "p"}}
           (get-in (backend-config dir) ["terraform" "backend"])))
    (testing "an unknown backend is a configuration error, not a silent local one"
      (is (thrown? clojure.lang.ExceptionInfo (advice {:backend "azure"}))))))

(deftest hcl-encodes-values-for-interpolation
  (is (= "[\"example.com\", \"example.net\"]"
         (tofu/hcl-list ["example.com" "example.net"])))
  (is (= "[]" (tofu/hcl-list [])))
  (is (= "{}" (tofu/hcl-map {})))
  (is (= "{\n    \"a\" : \"1\",\n    \"b\" : \"2\"\n  }"
         (tofu/hcl-map {"a" "1" "b" "2"}))))

(deftest construct-names-are-valid-tofu-labels
  (is (= "green_tofu_test_app_dns_www_example_com"
         (tofu/construct-name ::app-dns-www.example.com)))
  (is (= "plain_name" (tofu/construct-name :plain-name))))

(deftest constructs-json-is-deterministic
  (let [a (tofu/construct :resource :dns_record ::b {:name "b" :ttl 1})
        c (tofu/construct :resource :dns_record ::a {:name "a" :ttl 1})]
    (is (= (tofu/constructs-json [a c]) (tofu/constructs-json [c a]))
        "input order must not change the bytes")
    (let [parsed (json/parse-string (tofu/constructs-json [a c]))]
      (is (= 2 (count (get-in parsed ["resource" "dns_record"])))))
    (testing "nothing to render is an empty document, not nil"
      (is (= "{ }" (tofu/constructs-json []))))))

(deftest tofu-with-spec-follows-the-event
  (let [dir (tmpdir)
        specs [{:template :zk/main.tf
                :target (str dir "/main.tf")
                :data {:node {:id "1"} :servers []}}]
        calls (atom [])
        stub (fn [opts _] (swap! calls conj (.exists (io/file dir "main.tf")))
               (assoc opts :green/exit 0))]
    (testing "build renders and stops"
      (with-redefs [tofu/tofu-step (fn [& _] (throw (ex-info "tofu must not run" {})))]
        (is (= 0 (:green/exit (tofu/tofu-with-spec {:green/event :build} specs {:dir dir})))))
      (is (.exists (io/file dir "main.tf"))))

    (testing "delete renders before destroying, then removes the tree"
      (with-redefs [tofu/tofu-step stub]
        (is (= 0 (:green/exit (tofu/tofu-with-spec {:green/event :delete} specs {:dir dir})))))
      (is (= [true] @calls) "the .tf files describe what is being destroyed")
      (is (not (.exists (io/file dir "main.tf")))))

    (testing "a failed destroy leaves the tree in place to retry"
      (with-redefs [tofu/tofu-step (fn [opts _] (assoc opts :green/exit 1))]
        (is (= 1 (:green/exit (tofu/tofu-with-spec {:green/event :delete} specs {:dir dir})))))
      (is (.exists (io/file dir "main.tf"))))))
