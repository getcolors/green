(ns green.progress-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [green.progress :as progress]
            [green.workflow :as wf]))

(defn- simple-wf []
  (-> (wf/workflow {:start :t/a
                    :wire-fn (fn [s _]
                               (case s
                                 :t/a [(fn [o] o) :t/b]
                                 :t/b [(fn [o] o)]))})
      (progress/advise)))

(deftest progress-prints-step-names
  (let [out (with-out-str
              (wf/run (simple-wf) {:green/event :create}))]
    (is (str/includes? out ">>> :t/a (create)"))
    (is (str/includes? out "<<< :t/a"))
    (is (str/includes? out ">>> :t/b (create)"))
    (is (str/includes? out "<<< :t/b"))
    (is (str/includes? out "ms)") "elapsed time is printed")))

(deftest progress-prints-event-name
  (let [out (with-out-str
              (wf/run (simple-wf) {:green/event :delete}))]
    (is (str/includes? out ">>> :t/a (delete)"))))

(deftest progress-is-removable
  (let [w (wf/advice-remove-all (simple-wf) ::progress/progress)
        out (with-out-str (wf/run w {:green/event :create}))]
    (is (= "" out))))

(deftest progress-with-forks
  (let [w (-> (wf/workflow
                {:start :t/start
                 :wire-fn (fn [s _]
                            (case s
                              :t/start [(fn [o] o) :t/join]
                              :t/join [(fn [o] o)]))
                 :next-fn (fn [step dn o]
                            (if (= step :t/start)
                              [[:t/join (assoc o :branch :a)]
                               [:t/join (assoc o :branch :b)]]
                              (mapv (fn [s] [s o]) dn)))})
              (progress/advise))
        out (with-out-str (wf/run w {:green/event :create}))]
    (is (str/includes? out ">>> :t/start"))
    (is (str/includes? out ">>> :t/join"))))

(deftest green-step-is-set-in-opts
  (let [seen (atom [])
        w (wf/workflow {:start :t/a
                        :wire-fn (fn [s _]
                                   (case s
                                     :t/a [(fn [o] (swap! seen conj (:green/step o)) o) :t/b]
                                     :t/b [(fn [o] (swap! seen conj (:green/step o)) o)]))})
        res (wf/run w {:green/event :create})]
    (is (= [:t/a :t/b] @seen))
    (is (= 0 (:green/exit res)))))
