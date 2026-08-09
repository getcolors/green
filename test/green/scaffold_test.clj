(ns green.scaffold-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [green.scaffold :as sc])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- tmpdir []
  (str (Files/createTempDirectory "green-scaffold" (make-array FileAttribute 0))))

(deftest template-path-convention
  (is (= "zk/main.tf" (sc/template-path :zk/main.tf)))
  (is (= "my/app/zoo.cfg" (sc/template-path :my.app/zoo.cfg)))
  (is (= "plain.txt" (sc/template-path :plain.txt))))

(deftest scaffold-create-and-delete
  (let [dir (tmpdir)
        specs [{:template :greentest/hello.txt
                :target (str dir "/{{who}}/hello.txt")
                :data {:who "world" :name "green"}}]
        created (sc/scaffold {:green/event :create} specs)]
    (testing "create renders the template into the Selmer-rendered target"
      (is (= 0 (:green/exit created)))
      (is (= [(str dir "/world/hello.txt")] (:green.scaffold/written created)))
      (is (= "Hello green!\n" (slurp (str dir "/world/hello.txt")))))
    (testing "create is idempotent"
      (sc/scaffold {:green/event :create} specs)
      (is (= "Hello green!\n" (slurp (str dir "/world/hello.txt")))))
    (testing "delete removes targets and prunes emptied directories"
      (let [deleted (sc/scaffold {:green/event :delete} specs)]
        (is (= 0 (:green/exit deleted)))
        (is (not (.exists (io/file dir "world" "hello.txt"))))
        (is (not (.exists (io/file dir "world"))))))))

(deftest custom-delimiters-pass-jinja2-through
  (let [dir (tmpdir)
        specs [{:template :greentest/ansible-hello.yml
                :target (str dir "/play.yml")
                :data {:group "web"}
                :opts {:tag-open \< :tag-close \>
                       :filter-open \{ :filter-close \}}}]
        created (sc/scaffold {:green/event :create} specs)]
    (is (= 0 (:green/exit created)))
    (let [content (slurp (str dir "/play.yml"))]
      (is (re-find #"hosts: web" content)
          "Selmer <{group}> is rendered")
      (is (re-find #"\{\{ ansible_var \}\}" content)
          "Jinja2 {{ }} passes through unchanged"))))

(deftest direct-content-is-written-exactly
  (let [dir (tmpdir) target (str dir "/raw.txt")]
    (sc/scaffold {:green/event :create} [(sc/content-spec target "{{ untouched }}\n")])
    (is (= "{{ untouched }}\n" (slurp target)))))

(deftest missing-template-throws-with-context
  (is (thrown-with-msg? Exception #"template not found"
        (sc/scaffold {:green/event :create}
                     [{:template :nope/missing.txt :target "/tmp/x" :data {}}]))))
