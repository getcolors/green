(ns green.tofu
  "Event-aware OpenTofu steps: any non-:delete event (conventionally
  :create) -> init + apply, :delete -> init + destroy. After apply,
  `tofu output -json` is merged into opts under a namespaced key
  (:tofu/outputs by default). The backend is not hardwired: attach a
  :before advice built by `backend-advice`, `local-backend-advice`,
  `s3-backend-advice`, `gcs-backend-advice`, or `r2-backend-advice` to write
  backend.tf.json before the tofu command runs, or `backends` to choose among
  them per run.

  `tofu-with-spec` pairs a scaffold with the step. For configuration that is
  computed rather than templated, `construct` and `constructs-json` generate
  deterministic .tf.json, and `hcl-list`/`hcl-map` encode values a template
  interpolates."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.string :as str]
   [green.scaffold :as sc]))

(def ^:private init-args ["init" "-input=false" "-no-color"])
(def ^:private apply-args ["apply" "-auto-approve" "-input=false" "-no-color"])
(def ^:private destroy-args ["destroy" "-auto-approve" "-input=false" "-no-color"])

(defn- env-with [extra]
  (merge (into {} (System/getenv)) extra))

(defn- tofu! [dir env & args]
  (apply sh/sh "tofu" (concat args [:dir dir :env env])))

(defn- action-args [delete?]
  (if delete? destroy-args apply-args))

(defn- failed? [{:keys [exit]}]
  (pos? exit))

(defn- fail [opts {:keys [exit err out]} cmd]
  (assoc opts
         :green/exit exit
         :green/err (str "tofu " cmd " failed: "
                         (or (not-empty err) (not-empty out) "(no output)"))))

(defn- parse-outputs [out]
  (into {}
        (map (fn [[k v]] [(keyword k) (get v "value")]))
        (json/parse-string out)))

(defn outputs
  "Parse `tofu output -json` in `dir` into a plain map of keyword -> value."
  ([dir] (outputs dir nil))
  ([dir env]
   (let [{:keys [exit out err]} (tofu! dir env "output" "-json")]
     (when (pos? exit)
       (throw (ex-info (str "tofu output failed: " err) {:dir dir})))
     (parse-outputs out))))

(defn tofu-step
  "Run OpenTofu in `dir` according to :green/event. On success, apply merges
  the outputs under `output-key` (default :tofu/outputs) — never top-level.
  `env` adds variables to the tofu process environment — typically provider
  credentials, so they never have to be rendered into .tf files. Without it
  the environment is left untouched."
  [opts {:keys [dir output-key env] :or {output-key :tofu/outputs}}]
  (let [delete? (= :delete (:green/event opts))
        env (some-> env env-with)
        init (apply tofu! dir env init-args)]
    (if (failed? init)
      (fail opts init "init")
      (let [cmd (action-args delete?)
            res (apply tofu! dir env cmd)]
        (cond
          (failed? res) (fail opts res (first cmd))
          delete? (assoc opts :green/exit 0)
          :else (assoc opts :green/exit 0 output-key (outputs dir env)))))))

(defn- json-key [k]
  (if (keyword? k) (name k) (str k)))

(defn- json-value [v]
  (cond
    (keyword? v) (name v)
    (map? v) (into (sorted-map)
                   (map (fn [[k value]]
                          [(json-key k) (json-value value)]))
                   v)
    (vector? v) (mapv json-value v)
    :else v))

(defn- backend-json [type config]
  (str (json/generate-string
        {"terraform" {"backend" {(json-key type) (json-value config)}}}
        {:pretty true})
       "\n"))

(defn- resolve-config [config opts]
  (if (fn? config) (config opts) config))

(defn- write-backend! [dir type config]
  (let [dir (io/file dir)]
    (.mkdirs dir)
    (spit (io/file dir "backend.tf.json") (backend-json type config))))

(defn backend-advice
  "Build a :before advice that writes a backend config into the directory
  returned by (dir-fn opts) before the step runs. `type` is the backend
  name (\"local\", \"s3\", \"gcs\", …); `config` is a map of backend
  attributes, or a function of opts returning one. Keywords become JSON
  strings while maps, vectors, booleans, numbers, and nil retain their shape."
  [dir-fn type config]
  (fn [opts]
    (write-backend! (io/file (dir-fn opts)) type (resolve-config config opts))
    opts))

(defn local-backend-advice
  "Backend advice for the local filesystem backend."
  ([dir-fn] (local-backend-advice dir-fn {}))
  ([dir-fn config] (backend-advice dir-fn "local" config)))

(defn s3-backend-advice
  "Backend advice for the S3 backend, e.g.
  {:bucket \"my-state\" :key \"green/node-1.tfstate\" :region \"eu-west-1\"}."
  [dir-fn config]
  (backend-advice dir-fn "s3" config))

(defn gcs-backend-advice
  "Backend advice for the GCS backend, e.g.
  {:bucket \"my-state\" :prefix \"green/node-1\"}."
  [dir-fn config]
  (backend-advice dir-fn "gcs" config))

(defn r2-backend-advice
  "Backend advice for Cloudflare R2, which is S3-compatible but needs a fixed
  region, an endpoint override, and the four validation skips that keep the
  AWS SDK from probing for services R2 does not implement. `config` is
  {:bucket .. :key .. :endpoint ..}, or a function of opts returning one.

  Credentials are deliberately not part of the config: R2 authenticates
  through AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY in the environment, and
  naming them here would write them into backend.tf.json and into OpenTofu's
  resolved-config cache under .terraform/."
  [dir-fn config]
  (s3-backend-advice
   dir-fn
   (fn [opts]
     (let [{:keys [bucket key endpoint]} (resolve-config config opts)]
       {:bucket bucket
        :key key
        :region "auto"
        :endpoints {:s3 endpoint}
        :skip_credentials_validation true
        :skip_metadata_api_check true
        :skip_region_validation true
        :skip_requesting_account_id true
        :use_path_style false}))))

(defn backends
  "Build a :before advice that picks one of `advices` — a map of name ->
  advice — by (choose opts), so the backend can be desired state rather than a
  build-time decision."
  [choose advices]
  (fn [opts]
    (let [name (choose opts)]
      (if-let [advice (get advices name)]
        (advice opts)
        (throw (ex-info "unsupported OpenTofu backend" {:backend name}))))))

;; --- scaffold + run -------------------------------------------------------

(defn tofu-with-spec
  "Scaffold `specs`, then run `tofu-step` with `config` — or, on
  :green/event :delete, scaffold, run the destroy, and only then remove the
  rendered tree. Deleting has to render before it can destroy: OpenTofu needs
  the .tf files that describe what it is tearing down.

  :green/event :build renders and stops, which is how a project offers a
  \"show me what you would write\" command that needs no credentials.

  Mirrors `green.ansible/ansible-with-spec`."
  [opts specs config]
  (case (:green/event opts)
    :build (sc/scaffold opts specs)

    :delete (let [rendered (-> opts
                               (assoc :green/event :create)
                               (sc/scaffold specs)
                               (assoc :green/event :delete))
                  result (tofu-step rendered config)]
              (if (pos? (:green/exit result 0))
                result
                (sc/scaffold result specs)))

    (tofu-step (sc/scaffold opts specs) config)))

;; --- generating HCL and .tf.json ------------------------------------------

(defn hcl-list
  "Encode `xs` as an HCL list of strings, for interpolation into a template:
  [\"a\", \"b\"]."
  [xs]
  (str "[" (str/join ", " (map json/generate-string xs)) "]"))

(defn hcl-map
  "Encode `m` as an HCL object of string keys to string values, indented to sit
  inside a `locals` block."
  [m]
  (if (seq m)
    (str "{\n"
         (str/join ",\n"
                   (map (fn [[k v]]
                          (format "    %s : %s"
                                  (json/generate-string k)
                                  (json/generate-string v)))
                        m))
         "\n  }")
    "{}"))

(defn construct-name
  "The OpenTofu label for a qualified keyword. Tofu identifiers allow neither
  dots nor dashes, so both become underscores and the namespace is prefixed —
  which keeps labels generated from different namespaces from colliding."
  [kw]
  (let [sanitize #(str/replace % #"[-\.]" "_")
        ns (some-> (namespace kw) sanitize)
        n (sanitize (name kw))]
    (str ns (when ns "_") n)))

(defn construct
  "A single OpenTofu construct as nested data:
  (construct :resource :cloudflare_dns_record ::www {...})."
  [group type kw block]
  {group {type {(construct-name kw) block}}})

(defn deep-merge
  "Recursively merge maps; later values win at the leaves."
  [& maps]
  (apply merge-with (fn [a b]
                      (if (and (map? a) (map? b))
                        (deep-merge a b)
                        b))
         maps))

(defn- sort-nested
  [x]
  (cond
    (map? x) (into (sorted-map)
                   (map (fn [[k v]] [k (sort-nested v)]))
                   x)
    (sequential? x) (mapv sort-nested x)
    :else x))

(defn constructs-json
  "Merge `constructs` into one document and render it as pretty JSON with every
  map sorted, so a `.tf.json` file is byte-identical for identical inputs and a
  diff shows real changes rather than reordering."
  [constructs]
  (json/generate-string
   (if (seq constructs) (sort-nested (apply deep-merge constructs)) {})
   {:pretty true}))
