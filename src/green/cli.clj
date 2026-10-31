(ns green.cli
  "Configuration helpers kept at the existing green.cli import path.
  Parse YAML or EDN desired state and explicitly overlay COLORS_PAR_* values.
  Applications own argument parsing, validation, reporting and process exits."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [yamlstar.parser :as yaml-parser]
   [yamlstar.composer :as yaml-composer]
   [yamlstar.resolver :as yaml-resolver]
   [yamlstar.constructor :as yaml-constructor]))

(def ^:private par-prefix
  "The parameter namespace every colour shares, so one variable serves green,
  red and blue without naming any of them."
  "COLORS_PAR_")

(defn par-name
  "The `COLORS_PAR_*` environment variable that supplies flat key `k`:
  uppercased, hyphens as underscores. :do-token -> COLORS_PAR_DO_TOKEN."
  [k]
  (str par-prefix (-> (name k) str/upper-case (str/replace "-" "_"))))

(defn- par-key
  [env-name]
  (-> env-name
      (subs (count par-prefix))
      str/lower-case
      (str/replace "_" "-")
      keyword))

(defn- coerce
  "Environment variables are strings; match the type of the value already in
  opts so a boolean key stays a boolean. Without this, COLORS_PAR_X=false would
  overlay the truthy string \"false\"."
  [old value]
  (cond
    (boolean? old) (case (str/lower-case value)
                     "true" true
                     "false" false
                     value)
    (integer? old) (or (parse-long value) value)
    :else value))

(defn read-pars
  "Overlay `COLORS_PAR_*` environment variables onto flat keys in `opts`.

  `COLORS_PAR_DO_TOKEN=xxx` becomes `{:do-token \"xxx\"}`. This is how secrets
  reach a workflow without being written to the desired-state file. Overrides
  are coerced to the type of the value they replace, and applying them twice
  changes nothing."
  ([opts] (read-pars opts (System/getenv)))
  ([opts env]
   (reduce-kv (fn [result k v]
                (let [k (str k)]
                  (if (and (str/starts-with? k par-prefix)
                           (< (count par-prefix) (count k)))
                    (let [key (par-key k)]
                      (assoc result key (coerce (get result key) v)))
                    result)))
              opts
              env)))

(defn- keywordize
  "YAML gives string keys; desired state is addressed by keyword throughout,
  the way the EDN reader already delivered it. Applied to every map in the
  tree, so nested collections read the same as flat ones."
  [x]
  (cond
    (map? x) (reduce-kv (fn [m k v]
                          (assoc m (cond-> k (string? k) keyword) (keywordize v)))
                        {}
                        x)
    (sequential? x) (mapv keywordize x)
    :else x))

(defn- core-scalar-node
  "Fill yamlstar 0.1.17 scalar gaps before resolution. Quoted strings and
  explicit tags retain the parser's original nodes."
  [node]
  (if (and (map? node) (= :scalar (:kind node))
           (nil? (:style node)) (nil? (:tag node)))
    (let [value (:value node)]
      (cond
        (re-matches #"0(?:o[0-7]+|x[0-9a-fA-F]+)" value)
        (assoc node :value (str (java.math.BigInteger.
                                 (subs value 2) (if (= \o (second value)) 8 16))))
        ;; The upstream constructor only accepts signed lowercase infinity.
        (re-matches #"[-+]?\.(?:inf|Inf|INF)" value)
        (assoc node :value (str/lower-case value))
        :else node))
    node))

(defn read-state
  "Parse desired-state `text` written in `file`'s language: YAML for .yml and
  .yaml, EDN otherwise. YAML is read by yamlstar, whose 1.2 core schema leaves
  `no`, `yes` and `on` as the strings they look like."
  [file text]
  (if (re-find #"(?i)\.ya?ml$" (str file))
    (->> text
         yaml-parser/parse
         yaml-composer/compose
         (walk/postwalk core-scalar-node)
         yaml-resolver/resolve
         yaml-constructor/construct
         keywordize)
    (edn/read-string text)))
