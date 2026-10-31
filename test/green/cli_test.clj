(ns green.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [green.cli :as cli]))

(deftest colors-par-variables-overlay-desired-state
  (testing "the file supplies structure, the environment supplies secrets"
    (is (= {:do-token "tok" :profile "prod"}
           (cli/read-pars {:profile "prod"} {"COLORS_PAR_DO_TOKEN" "tok"
                                             "PATH" "/usr/bin"}))))

  (testing "hyphens in the key are underscores in the variable"
    (is (= "COLORS_PAR_R2_ACCESS_KEY_ID" (cli/par-name :r2-access-key-id)))
    (is (= {:r2-access-key-id "id"}
           (cli/read-pars {} {"COLORS_PAR_R2_ACCESS_KEY_ID" "id"}))))

  (testing "an override takes the type of the value it replaces"
    (is (= {:prevent-destroy false}
           (cli/read-pars {:prevent-destroy true}
                          {"COLORS_PAR_PREVENT_DESTROY" "false"})))
    (is (= {:port 25}
           (cli/read-pars {:port 587} {"COLORS_PAR_PORT" "25"})))
    (is (= {:name "x"} (cli/read-pars {:name "y"} {"COLORS_PAR_NAME" "x"}))))

  (testing "applying the overlay twice changes nothing"
    (let [env {"COLORS_PAR_PREVENT_DESTROY" "false"}
          once (cli/read-pars {:prevent-destroy true} env)]
      (is (= once (cli/read-pars once env)))))

  (testing "the bare prefix is not a key"
    (is (= {} (cli/read-pars {} {"COLORS_PAR_" "x"}))))

  (testing "no colour keeps a prefix of its own"
    (is (= {} (cli/read-pars {} {"GREEN_PAR_DO_TOKEN" "tok"
                                 "RED_PAR_DO_TOKEN" "tok"
                                 "BLUE_PAR_DO_TOKEN" "tok"
                                 "ONCE_PAR_DO_TOKEN" "tok"})))))

(deftest desired-state-is-read-by-extension
  (testing "yaml keys arrive as keywords, nesting included"
    (is (= {:profile "prod" :once {:applications [{:host "www.example.com"}]}}
           (cli/read-state "colors.yml"
                           "profile: prod\nonce:\n  applications:\n    - host: www.example.com\n"))))

  (testing "the 1.2 core schema leaves no/yes/on as the strings they look like"
    (is (= {:a "no" :b "yes" :c "on" :d true :e 12}
           (cli/read-state "colors.yaml" "a: no\nb: yes\nc: on\nd: true\ne: 012\n"))))

  (testing "edn is still read, so existing projects keep working"
    (is (= {:profile "prod" :port 587}
           (cli/read-state "green.edn" "{:profile \"prod\" :port 587}")))))

(deftest core-scalar-numbers-and-strings
  (let [state (cli/read-state "state.yml"
                (str "exponent: 1e3\nfractional-exponent: 1.0e3\nsmall-exponent: 1.25e-2\n"
                     "date: 2026-09-11\nclock: 1:30.5\noctal: 0o17\nhex: 0x1f\n"
                     "quoted: [\"1e3\", \"0o17\", \"0x1f\", \"2026-09-11\"]\n"
                     "nested: {number: &n 0o17, alias: *n}\n"
                     "tagged: !!str 0o17\nblock: |\n  0o17\n"))]
    (is (== 1000 (:exponent state) (:fractional-exponent state)))
    (is (= 0.0125 (:small-exponent state)))
    (is (= "2026-09-11" (:date state)))
    (is (= "1:30.5" (:clock state)))
    (is (= 15 (:octal state)))
    (is (= 31 (:hex state)))
    (is (= ["1e3" "0o17" "0x1f" "2026-09-11"] (:quoted state)))
    (is (= {:number 15 :alias 15} (:nested state)))
    (is (= "0o17" (:tagged state)))
    (is (= "0o17\n" (:block state)))))

(deftest core-signed-infinity
  (let [state (cli/read-state "state.yml" "positive: +.INF\nnegative: -.Inf\nnan: .NaN\nquoted: \"-.Inf\"\n")]
    (is (= Double/POSITIVE_INFINITY (:positive state)))
    (is (= Double/NEGATIVE_INFINITY (:negative state)))
    (is (Double/isNaN (:nan state)))
    (is (= "-.Inf" (:quoted state)))))
