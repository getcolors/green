(ns green.ansible
  "Event-aware Ansible steps: any non-:delete event (conventionally :create)
  runs the create playbook, :delete runs the delete playbook — both via
  `ansible-playbook` over SSH. After a successful run the PLAY RECAP is
  parsed and merged into opts under a namespaced key (:ansible/recap by
  default). The inventory is not hardwired: attach `inventory-advice` as a
  :before advice to write an INI inventory from a function of opts before
  the step runs, the way green.tofu attaches backends."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.string :as str]
   [green.scaffold :as sc]))

(def default-playbooks
  "Event -> playbook file, relative to the step's :dir."
  {:create "create.yml" :delete "delete.yml"})

(defn playbook
  "The playbook `ansible-step` runs for opts' :green/event: :delete selects
  the :delete entry, every other event the :create entry."
  ([opts] (playbook opts default-playbooks))
  ([opts playbooks]
   (let [playbooks (merge default-playbooks playbooks)]
     (if (= :delete (:green/event opts))
       (:delete playbooks)
       (:create playbooks)))))

(def ^:private recap-line-re
  #"(?m)^(\S+)\s+:\s+ok=(\d+)\s+changed=(\d+)\s+unreachable=(\d+)\s+failed=(\d+)\s+skipped=(\d+)\s+rescued=(\d+)\s+ignored=(\d+)")

(defn parse-recap
  "Parse the PLAY RECAP section of `ansible-playbook` output into
  {host {:ok n :changed n :unreachable n :failed n :skipped n :rescued n
  :ignored n}}."
  [out]
  (into {}
        (map (fn [[_ host & counts]]
               [host (zipmap [:ok :changed :unreachable :failed
                              :skipped :rescued :ignored]
                             (map parse-long counts))]))
        (re-seq recap-line-re (str out))))

(defn- env-with [extra]
  (merge (into {} (System/getenv)) extra))

(defn- ansible! [dir env & args]
  (apply sh/sh "ansible-playbook" (concat args [:dir dir :env env])))

(defn- fail [opts {:keys [exit err out]} pb]
  (assoc opts
         :green/exit exit
         :green/err (str "ansible-playbook " pb " failed: "
                         (or (not-empty out) (not-empty err) "(no output)"))))

(defn ansible-step
  "Run `ansible-playbook` in `dir` according to :green/event (see
  `playbook`). On success the parsed PLAY RECAP is merged under `recap-key`
  (default :ansible/recap) — never top-level.

  Options:
    :dir               working directory; playbook and inventory paths are
                       resolved relative to it
    :inventory         inventory file (default \"inventory.ini\")
    :playbooks         {:create ... :delete ...} overriding `default-playbooks`
    :private-key       SSH private key file passed as --private-key
    :user              remote user passed as -u
    :extra-vars        map passed as -e in JSON form
    :host-key-checking set to false to export ANSIBLE_HOST_KEY_CHECKING=False
                       for the run — for ephemeral or emulated hosts whose
                       host keys change on every create. Omitted or true,
                       the environment is left untouched.
    :recap-key         namespaced key for the parsed recap"
  [opts {:keys [dir inventory playbooks private-key user extra-vars
                host-key-checking recap-key]
         :or {inventory "inventory.ini" recap-key :ansible/recap}}]
  (let [pb (playbook opts playbooks)
        env (cond-> nil
              (false? host-key-checking)
              (assoc "ANSIBLE_HOST_KEY_CHECKING" "False"))
        args (cond-> ["-i" inventory]
               private-key (into ["--private-key" (str private-key)])
               user (into ["-u" user])
               extra-vars (into ["-e" (json/generate-string extra-vars)])
               true (conj pb))
        res (apply ansible! dir (some-> env env-with) args)]
    (if (pos? (:exit res))
      (fail opts res pb)
      (assoc opts :green/exit 0 recap-key (parse-recap (:out res))))))

(defn ansible-with-spec
  "Scaffold ansible config files (playbooks, ansible.cfg, …) then run
  ansible-playbook; or, on :green/event :delete, scaffold, run the delete
  playbook, and only then remove the rendered tree.

  Deleting renders first because ansible-playbook cannot run a playbook that
  has already been deleted — the teardown play is itself one of the
  scaffolded files. The render is done under :green/event :create so the specs
  materialize rather than delete; the event the playbook sees is unchanged.

  :green/event :build renders and stops, for a command that shows what would
  be written without reaching a host. Mirrors `green.tofu/tofu-with-spec`."
  [opts ansible-config specs]
  (case (:green/event opts)
    :build (sc/scaffold opts specs)

    :delete (let [rendered (-> opts
                               (assoc :green/event :create)
                               (sc/scaffold specs)
                               (assoc :green/event :delete))
                  result (ansible-step rendered ansible-config)]
              (if (pos? (:green/exit result 0))
                result
                (sc/scaffold result specs)))

    (ansible-step (sc/scaffold opts specs) ansible-config)))

;; --- inventory ------------------------------------------------------------

(defn- ini-name [x]
  (if (keyword? x) (name x) (str x)))

(defn- ini-vars [vars]
  (map (fn [[k v]] (str (ini-name k) "=" (ini-name v)))
       (sort-by (comp ini-name key) vars)))

(defn- host-line [{host :name vars :vars}]
  (str/join " " (cons (ini-name host) (ini-vars vars))))

(defn- group-section [[group {:keys [hosts vars]}]]
  (cond-> (str "[" (ini-name group) "]\n"
               (str/join "" (map #(str (host-line %) "\n") hosts)))
    (seq vars) (str "\n[" (ini-name group) ":vars]\n"
                    (str/join "" (map #(str % "\n") (ini-vars vars))))))

(defn inventory-ini
  "Render an Ansible INI inventory from
  {group {:hosts [{:name \"zk1\" :vars {:ansible_host \"172.17.0.3\"}} ...]
          :vars {:ansible_user \"root\"}}}.
  Groups and vars are emitted in sorted order for deterministic output."
  [groups]
  (str/join "\n" (map group-section (sort-by (comp ini-name key) groups))))

(defn- resolve-config [config opts]
  (if (fn? config) (config opts) config))

(defn inventory-advice
  "Build a :before advice that writes an INI inventory to the file returned
  by (file-fn opts) before the step runs. `groups` is the inventory data
  (see `inventory-ini`), or a function of opts returning it."
  [file-fn groups]
  (fn [opts]
    (let [f (io/file (file-fn opts))]
      (io/make-parents f)
      (spit f (inventory-ini (resolve-config groups opts))))
    opts))
