(ns green.cli
  "CLI plumbing: `./green <event> [-f|--file green.yml] [--start step]
  [--end step]`. The first positional argument is the lifecycle event,
  stamped into opts as :green/event. --start/--end run a slice of the graph.

  Desired state is YAML or EDN on disk, selected by the file's extension, so
  it cannot hold secrets. `COLORS_PAR_*` environment variables fill that gap:
  each one overlays the matching flat key after the file is read, and `run-cli`
  applies them for you. The prefix is shared by every colour, so the same
  variable reaches green, red and blue alike."
  (:require
   [babashka.cli :as cli]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [green.workflow :as wf]
   [yamlstar.core :as yaml]))

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

(defn read-state
  "Parse desired-state `text` written in `file`'s language: YAML for .yml and
  .yaml, EDN otherwise. YAML is read by yamlstar, whose 1.2 core schema leaves
  `no`, `yes` and `on` as the strings they look like."
  [file text]
  (if (re-find #"(?i)\.ya?ml$" (str file))
    (keywordize (yaml/load text))
    (edn/read-string text)))

(def ^:private cli-spec
  {:file {:alias :f :default "green.yml" :desc "Desired state file (YAML, or EDN by extension)"}
   :start {:coerce :keyword :desc "Override the workflow start step"}
   :end {:coerce :keyword :desc "Override the workflow end step (slice boundary)"}
   :dry-run {:coerce :boolean :desc "Stamp :green/dry-run — steps advised with green.dry-run are skipped"}})

(def usage
  "Usage: green <event> [-f|--file green.yml] [--start step] [--end step] [--dry-run]")

(defn run-cli
  "Parse `args`, load the desired state, overlay `COLORS_PAR_*`, stamp
  :green/event and :green/state-file, run `workflow`. Returns the final opts
  map (:green/exit 2 on usage/state-file errors).

  :green/state-file is the absolute path the state was read from, so a project
  can resolve its own relative paths against the file rather than against
  whatever directory the command happened to run in."
  ([workflow] (run-cli workflow *command-line-args*))
  ([workflow args]
   (try
     (let [{:keys [args opts]} (cli/parse-args (vec args) {:spec cli-spec})
           event (first args)]
       (if-not event
         {:green/exit 2 :green/err usage}
         (let [file (io/file (:file opts))]
           (if-not (.exists file)
             {:green/exit 2 :green/err (str "desired state file not found: " file)}
             (let [state (-> (read-state file (slurp file))
                             (assoc :green/state-file (.getAbsolutePath file))
                             read-pars)
                   workflow (cond-> workflow
                              (:start opts) (assoc :green.workflow/start (:start opts))
                              (:end opts) (assoc :green.workflow/end (:end opts)))]
               (wf/run workflow
                       (cond-> (assoc state :green/event (keyword event))
                         (:dry-run opts) (assoc :green/dry-run true))))))))
     (catch Throwable t
       {:green/exit 2 :green/err (or (ex-message t) (str (class t)))}))))

(defn exec
  "Run and exit the process with :green/exit, printing :green/err and
  :green/trace to stderr. For use from the project's babashka script."
  ([workflow] (exec workflow *command-line-args*))
  ([workflow args]
   (let [{:green/keys [exit err trace]} (run-cli workflow args)]
     (when err
       (binding [*out* *err*]
         (println err)
         (when trace (println trace))))
     (System/exit (or exit 0)))))
