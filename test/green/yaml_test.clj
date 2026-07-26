(ns green.yaml-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [green.yaml :as yaml]))

(deftest scalars-are-quoted-so-readers-cannot-reinterpret-them
  (is (= "a: \"1\"\n" (yaml/generate-string {:a "1"})))
  (is (= "a: 1\n" (yaml/generate-string {:a 1})))
  (is (= "a: true\n" (yaml/generate-string {:a true})))
  (is (= "a: null\n" (yaml/generate-string {:a nil})))
  (testing "a string that looks like a boolean stays a string"
    (is (= "a: \"yes\"\n" (yaml/generate-string {:a "yes"}))))
  (testing "keywords render as their names"
    (is (= "a: \"b\"\n" (yaml/generate-string {:a :b})))))

(deftest nested-structures-render-in-block-style
  (is (= (str "name: \"reconcile\"\n"
              "become: true\n"
              "once:\n"
              "  applications:\n"
              "    - host: \"www.example.com\"\n"
              "      env:\n"
              "        - \"A=1\"\n")
         (yaml/generate-string (array-map
                                :name "reconcile"
                                :become true
                                :once {:applications [(array-map
                                                       :host "www.example.com"
                                                       :env ["A=1"])]})))))

(deftest empty-collections-are-flow-scalars
  (is (= "a: {}\nb: []\n" (yaml/generate-string (array-map :a {} :b [])))))

(deftest a-sequence-at-the-top-level-is-a-document
  (is (= "- a: \"1\"\n- a: \"2\"\n"
         (yaml/generate-string [{:a "1"} {:a "2"}]))))
