#!/usr/bin/env bb
(ns tasks.docco
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

;; Babashka-only generator for docco.html. It intentionally avoids evaluating
;; project code: forms are split with a small lexer and described from
;; docstrings, leading comments, and the curated notes below.

(def generated-on "2026-07-06")

(defn globbed [base pattern]
  (->> (fs/glob base pattern)
       (map str)
       sort
       vec))

(def example-rank
  {"examples/zookeeper/green" 0
   "examples/multi-zookeeper/green" 1
   "examples/once/green" 2
   "examples/multi-once/green" 3
   "examples/floci-zookeeper/green" 4})

(defn example-sort-key [path]
  [(get example-rank path 1000) path])

(def file-order
  (vec (concat (globbed "." "src/**/*.clj")
               (globbed "." "test/**/*.clj")
               (sort-by example-sort-key (globbed "." "examples/*/green")))))

(def file-notes
  {"src/green/advice.clj"
   "Pure Emacs-nadvice-style wrapping for Green steps. This namespace owns the registry entry format, ordering rules, inheritance merge helpers, and the actual function composition used by the workflow engine."

   "src/green/ansible.clj"
   "Event-aware Ansible integration plus deterministic inventory rendering. The shelling step is deliberately small; inventory writing is advice so workflows can attach or replace it without changing the graph."

   "src/green/cli.clj"
   "The thin CLI layer: parse args, read EDN desired state, stamp the lifecycle event and dry-run bit, optionally slice the workflow, then delegate to green.workflow."

   "src/green/dry_run.clj"
   "Dry-run is modeled as ordinary advice. Side-effecting steps stay honest and reusable; the CLI merely stamps :green/dry-run into opts."

   "src/green/progress.clj"
   "Progress output is also ordinary all-steps advice. It demonstrates that instrumentation can be layered onto any workflow without changing wiring."

   "src/green/scaffold.clj"
   "Selmer-backed file scaffolding for create/delete workflows. Specs are flat data and use the same target calculation for creation and removal."

   "src/green/tofu.clj"
   "OpenTofu integration and backend-file advice. The step executes init/apply/destroy; backend selection is kept outside the step through :before advice."

   "src/green/workflow.clj"
   "The fork/join workflow engine. Steps are plain opts->opts functions, wire-fn supplies the static graph, next-fn can route dynamically, and advice inheritance makes composed workflows behave like ordinary steps."

   "test/green/advice_test.clj"
   "Unit tests for every advice combinator, strict add ordering, :depth precedence, registry purity, and advice inheritance through embedded workflows."

   "test/green/ansible_test.clj"
   "Tests the Ansible helpers that do not require ansible-playbook: event-to-playbook choice, PLAY RECAP parsing, deterministic INI output, and inventory advice."

   "test/green/cli_test.clj"
   "Covers the CLI contract without exiting the JVM: desired-state loading, event stamping, graph slicing, dry-run stamping, and usage errors."

   "test/green/dry_run_test.clj"
   "Tests that dry-run advice suppresses side effects only when :green/dry-run is present and remains removable like any other advice."

   "test/green/progress_test.clj"
   "Tests progress advice output and confirms the engine stamps :green/step before the advised function runs."

   "test/green/scaffold_test.clj"
   "Tests template keyword conventions, create/delete idempotence, parent-directory pruning, and missing-template diagnostics."

   "test/green/tofu_test.clj"
   "Tests backend advice rendering without invoking tofu. These are pure file-output checks for local, S3, and GCS backend configs."

   "test/green/workflow_test.clj"
   "Scheduler tests for linear graphs, failures, event-specific static graphs, dynamic routing, fork/join behavior, composition, slices, and real parallelism."

   "test/green/zookeeper_test.clj"
   "End-to-end fake ZooKeeper workflows. They run real Selmer and real tofu against locals/output-only HCL, so the workflow mechanics are exercised without creating infrastructure."

   "examples/floci-zookeeper/green"
   "Executable babashka CLI for the real floci ZooKeeper example: OpenTofu creates Docker-backed EC2 instances in a local AWS emulator, Ansible provisions them over SSH, and advice adds validation, setup, inventory, retry, progress, and dry-run layers."

   "examples/multi-once/green"
   "Executable babashka CLI composing the ONCE-style single-VPS workflow once per deployment. Parent advice swaps the child provider and backend under the same ids, and dry-run stays outermost."

   "examples/multi-zookeeper/green"
   "Executable babashka CLI that runs one reusable ZooKeeper cluster workflow per desired cluster via wf/step, showing inherited parent advice across embedded workflows."

   "examples/once/green"
   "Executable babashka CLI for a fake ONCE-style single-server deployment: compute and SMTP fork, DNS joins them, then SMTP post-processing and Ansible scaffolds run in parallel."

   "examples/zookeeper/green"
   "Executable babashka CLI for a fake ZooKeeper cluster. It fans out one tofu root per node, joins the observed outputs, and scaffolds zoo.cfg files for every member."})

(def source-form-notes
  {;; green.advice
   ["src/green/advice.clj" "entry"]
   "Internal constructor for registry entries. It rejects unsupported combinators and invalid :depth values at add time, so later composition can assume entries are well shaped."
   ["src/green/advice.clj" "remove-entry-ids"]
   "Drops every entry whose :id is in the supplied set; nil registries are treated as empty vectors to keep update calls simple."
   ["src/green/advice.clj" "remove-entry-id"]
   "Single-id convenience wrapper around remove-entry-ids. Re-add and remove operations both use this to make ids unique."
   ["src/green/advice.clj" "wrap"]
   "The semantic core of each advice combinator. Given one advice function and the already-built inner chain, it returns the next outer function."

   ;; green.ansible
   ["src/green/ansible.clj" "recap-line-re"]
   "Regular expression for the stable, machine-readable part of Ansible's PLAY RECAP lines. It intentionally ignores the rest of the playbook output."
   ["src/green/ansible.clj" "env-with"]
   "Builds the child-process environment by overlaying Ansible-specific settings on the current process environment."
   ["src/green/ansible.clj" "ansible!"]
   "Small shell wrapper around ansible-playbook. Keeping it isolated makes the public step easy to read and tests able to avoid the binary."
   ["src/green/ansible.clj" "fail"]
   "Converts a non-zero ansible-playbook result into Green's opts-based failure contract, preserving the command output as :green/err."
   ["src/green/ansible.clj" "ini-name"]
   "Normalizes keyword and non-keyword values to the strings Ansible INI inventory expects."
   ["src/green/ansible.clj" "ini-vars"]
   "Formats a var map as sorted key=value tokens, giving deterministic inventory output and stable tests."
   ["src/green/ansible.clj" "host-line"]
   "Renders one host entry: the host name followed by any per-host variables on the same line."
   ["src/green/ansible.clj" "group-section"]
   "Renders one inventory group and, when present, its [group:vars] section."
   ["src/green/ansible.clj" "resolve-config"]
   "Allows inventory data to be either a literal map or a function of opts, matching the backend-advice pattern in green.tofu."

   ;; green.cli
   ["src/green/cli.clj" "cli-spec"]
   "babashka.cli option schema. It keeps coercion and help text close to the parser and lets run-cli focus on workflow setup."
   ["src/green/cli.clj" "usage"]
   "Human-readable usage line returned as :green/err when the caller omits the lifecycle event."

   ;; common small helpers
   ["src/green/dry_run.clj" "logln"]
   "Synchronized println used by advice that may run on parallel branches; it avoids interleaving output from futures."
   ["src/green/progress.clj" "logln"]
   "Synchronized println used by all-steps progress advice, which can run concurrently on forked branches."

   ;; green.scaffold
   ["src/green/scaffold.clj" "prune-empty-dir!"]
   "After deleting a generated file, remove its immediate parent when it became empty. This keeps delete idempotent without recursively pruning user directories."
   ["src/green/scaffold.clj" "target-path"]
   "Renders one spec's target path through Selmer using the spec's own :data map."
   ["src/green/scaffold.clj" "target-paths"]
   "Precomputes all rendered target paths so create and delete report exactly the files they touched or would remove."
   ["src/green/scaffold.clj" "delete-target!"]
   "Deletes one generated target if it exists, then prunes the now-empty parent directory. Missing files are successful no-ops."
   ["src/green/scaffold.clj" "create-target!"]
   "Creates parent directories and writes one rendered template to its target path."
   ["src/green/scaffold.clj" "delete-targets!"]
   "Serially removes the rendered targets. The function is intentionally boring: ordering is deterministic and errors surface normally."
   ["src/green/scaffold.clj" "create-targets!"]
   "Pairs each original spec with its pre-rendered target so the report and the writes stay in lockstep."

   ;; green.tofu
   ["src/green/tofu.clj" "init-args"]
   "Common OpenTofu init flags: non-interactive and no ANSI color, which makes logs and errors deterministic."
   ["src/green/tofu.clj" "apply-args"]
   "Common OpenTofu apply flags for idempotent, non-interactive creates."
   ["src/green/tofu.clj" "destroy-args"]
   "Common OpenTofu destroy flags for non-interactive deletes."
   ["src/green/tofu.clj" "tofu!"]
   "Small shell wrapper around the tofu binary, always executed in the step's working directory."
   ["src/green/tofu.clj" "action-args"]
   "Selects apply or destroy arguments from the event-derived delete? flag."
   ["src/green/tofu.clj" "failed?"]
   "Predicate for clojure.java.shell results: a positive process exit is a Green failure."
   ["src/green/tofu.clj" "fail"]
   "Converts a failing tofu command into Green's opts-based failure contract and keeps the most useful stderr/stdout text."
   ["src/green/tofu.clj" "parse-outputs"]
   "Turns tofu's JSON output shape into Green-friendly keyword keys with the raw output values."
   ["src/green/tofu.clj" "hcl-value"]
   "Escapes flat backend values into the tiny subset of HCL needed for backend.tf attributes."
   ["src/green/tofu.clj" "hcl-attribute"]
   "Formats one backend attribute line with stable indentation."
   ["src/green/tofu.clj" "backend-hcl"]
   "Renders the complete backend.tf body for a backend type and sorted flat config map."
   ["src/green/tofu.clj" "resolve-config"]
   "Allows backend config to be either literal data or a function of opts, which is how examples derive per-step state keys."
   ["src/green/tofu.clj" "write-backend!"]
   "Creates the tofu directory and writes backend.tf immediately before the tofu step runs."

   ;; green.workflow
   ["src/green/workflow.clj" "next-seq"]
   "Reads the next advice insertion number. The workflow value carries this counter so add order remains pure and deterministic."
   ["src/green/workflow.clj" "inherit"]
   "Implements cross-workflow advice inheritance for wf/step. Ancestor entries are merged outside child entries, replacing same-id child entries."
   ["src/green/workflow.clj" "static-successors"]
   "Best-effort lookup of static edges for join scheduling. It is lenient so dynamic routing can own unknown edges and real wiring errors still surface when the step runs."
   ["src/green/workflow.clj" "static-graph"]
   "Walks wire-fn from the selected start step to build the run's static graph. The scheduler uses this only to know when a possible join is still pending."
   ["src/green/workflow.clj" "stack-trace"]
   "Renders a Throwable stack trace as a string for :green/trace."
   ["src/green/workflow.clj" "failed?"]
   "Green failure predicate: any positive :green/exit in opts means the branch should stop unless next-fn explicitly routes it."
   ["src/green/workflow.clj" "step-failure"]
   "Converts exceptions thrown by user step code or advice into the normal opts failure contract."
   ["src/green/workflow.clj" "scheduler-failure"]
   "Converts unexpected scheduler errors into a Green failure without blaming a particular user step."
   ["src/green/workflow.clj" "with-default-exit"]
   "Successful steps may omit :green/exit; this normalizes that to zero at the boundary."
   ["src/green/workflow.clj" "inherited-payload"]
   "Packages a workflow's effective advice registries so nested wf/step runs can inherit them."
   ["src/green/workflow.clj" "stamp-inherited"]
   "Places the inheritance payload into opts under a private key just before the step function sees it."
   ["src/green/workflow.clj" "step-advice"]
   "Combines all-steps and per-step advice for one step; green.advice/compose performs the final ordering."
   ["src/green/workflow.clj" "run-step"]
   "Resolve the step, compose its advice, stamp :green/step and inherited registries, run it, validate it returned a map, and catch exceptions."
   ["src/green/workflow.clj" "children"]
   "Turns a completed unit plus its successor pairs into either terminal branches, a single continuation, or a fork frame with one entry per successor."
   ["src/green/workflow.clj" "terminal-result"]
   "Wraps opts as a finished branch while preserving the fork stack it belongs to."
   ["src/green/workflow.clj" "branch-worst-exit"]
   "Chooses the highest exit code among branch results; used when a join or fork collapse must summarize failures."
   ["src/green/workflow.clj" "first-failed-branch"]
   "Finds the first failed branch so its error text and trace can represent the collapsed fork."
   ["src/green/workflow.clj" "join-forks"]
   "Reads the active fork stack from same-step entries arriving at a join."
   ["src/green/workflow.clj" "failed-join-result"]
   "If branches meet at a join but at least one has failed, skip the join step and emit a terminal result with :green/branches."
   ["src/green/workflow.clj" "run-single-unit"]
   "Runs one branch through one step and expands its next edges."
   ["src/green/workflow.clj" "run-join-unit"]
   "Runs a converged join once, with branch results under :green/branches, unless any branch already failed."
   ["src/green/workflow.clj" "unit-base-opts"]
   "Finds the opts to use when reporting a scheduler-level failure for either a single entry or a join unit."
   ["src/green/workflow.clj" "unit-forks"]
   "Finds the active fork stack to preserve when scheduler-level failure handling creates a terminal branch."
   ["src/green/workflow.clj" "failed-unit-result"]
   "Builds the terminal result for a unit that threw outside the user step boundary."
   ["src/green/workflow.clj" "run-unit"]
   "Dispatches a ready unit to either single-branch or join execution, giving the unit a fresh id for child parentage."
   ["src/green/workflow.clj" "failed-fork-branch?"]
   "Detects a finished failed branch that is still inside a fork and therefore must collapse the fork before any join runs."
   ["src/green/workflow.clj" "in-fork?"]
   "Checks whether a live or finished entry belongs to a particular fork frame."
   ["src/green/workflow.clj" "fork-members"]
   "Collects all live and finished entries currently inside a fork so collapse can summarize every branch's current result."
   ["src/green/workflow.clj" "outside-fork"]
   "Filters entries not belonging to a collapsing fork."
   ["src/green/workflow.clj" "collapsed-fork-entry"]
   "Creates the synthetic branch result that replaces a failed fork: fork-point opts plus worst exit and all branch results."
   ["src/green/workflow.clj" "collapse-fork"]
   "Removes a failed fork's members from live/finished sets and inserts the collapsed synthetic result."
   ["src/green/workflow.clj" "finalize"]
   "Selects the workflow result after all branches terminate: the only result, the first failure, or the last success."
   ["src/green/workflow.clj" "blocked-step?"]
   "A step is blocked when another live branch could still reach it later; waiting lets the branches join instead of running the step twice."
   ["src/green/workflow.clj" "ready-steps"]
   "Chooses runnable steps after join blocking. If every step is blocked, it breaks the stalemate by allowing the current keys."
   ["src/green/workflow.clj" "waiting-steps"]
   "The complement of ready steps for this scheduler turn; their entries stay live."
   ["src/green/workflow.clj" "entries-for-steps"]
   "Flattens a selected set of step groups back into live entries."
   ["src/green/workflow.clj" "same-origin?"]
   "Same-step entries from one fan-out parent should run separately; entries from different parents represent a real convergence and join."
   ["src/green/workflow.clj" "single-units"]
   "Turns same-origin entries into independent runnable units."
   ["src/green/workflow.clj" "step-units"]
   "Chooses between single units and one join unit for a same-step group."
   ["src/green/workflow.clj" "ready-units"]
   "Expands all ready step groups into concrete units to run this turn."
   ["src/green/workflow.clj" "run-units"]
   "Runs ready units concurrently with futures and waits for all of them before advancing the scheduler."
   ["src/green/workflow.clj" "scheduler-step"]
   "One scheduler tick: group live entries, select ready work, run it in parallel, keep waiting entries, and collapse failed forks."})

(def common-form-notes
  {"script-dir" "Directory containing the executable script. In REPL contexts where babashka has no source path, it falls back to the current working directory."
   "repo-root" "Repository root derived from the example directory; used to add green itself as a local dependency when the script runs under babashka."
   "floci-endpoint" "Local AWS-emulator endpoint passed to the AWS provider and helper CLI calls."
   "example-path" "Resolves a user-provided path relative to the example directory, while preserving absolute paths."
   "workdir" "Root work directory from the desired-state opts, normalized through example-path."
   "shared-dir" "Working directory for resources shared by all nodes, such as the floci key pair tofu root."
   "node-dir" "Per-node working directory. Keeping each node in its own tofu root isolates state and generated files."
   "ssh-key" "Path to the SSH private key generated for floci instances and consumed by Ansible."
   "inventory-file" "Path where inventory advice writes the Ansible inventory for the current run."
   "logln" "Synchronized output helper; examples can fork branches, so direct println calls would otherwise interleave."
   "sh!" "Shell helper that returns stdout on success and throws ex-info on non-zero exit, letting the workflow engine turn it into a Green failure."
   "tool?" "Predicate used by requirement gates to probe command availability without throwing."
   "aws-env" "Fake AWS credentials and region for local floci/AWS-emulator CLI calls."
   "running-instances?" "Checks floci for running or pending EC2 instances so setup can avoid restarting a live emulator."
   "floci-healthy?" "Simple health probe for the local floci service."
   "wait-floci!" "Polls floci until its health endpoint responds or the setup advice fails clearly."
   "remove-ec2-containers!" "Cleans up stale Docker containers created by floci's EC2 emulation."
   "restart-floci!" "Restarts the floci container after removing stale EC2 containers, then waits for health."
   "ssh-open?" "Low-level TCP probe used before running Ansible against newly-created instances."
   "four-letter" "Sends one ZooKeeper four-letter word command over TCP and returns the response text."
   "server-mode" "Extracts leader/follower mode from ZooKeeper's srvr four-letter output."
   "start-step" "Identity or setup step that gives the workflow a named graph root and a place to attach validation/setup advice."
   "node-step" "Per-node step: scaffold that node's tofu root, apply or destroy it, and preserve destroy ordering so tofu still has its files."
   "zoo-cfg-step" "Join step that renders ZooKeeper configuration after node branches have produced their observed outputs."
   "wire-fn" "Static graph declaration for the workflow. The engine uses it both to run steps and to reason about possible joins."
   "next-fn" "Dynamic routing function. It fans out branches from the desired state and stops routing when a branch has failed."
   "workflow" "Assembled workflow value with graph wiring plus advice layers such as backends, dry-run, validation, progress, or inherited parent overrides."
   "file-arg?" "Recognizes explicit desired-state file arguments so default-args does not add a duplicate -f option."
   "default-args" "Adds the example's green.edn as the default desired-state file when the caller did not provide one."
   "launched-as-script?" "Distinguishes direct babashka execution from REPL loading so examples can expose functions without immediately exiting."
   "run" "REPL-friendly entrypoint that returns the final opts map instead of calling System/exit."
   "-main" "Process entrypoint used when the file is executed as a script; delegates to green.cli/exec."
   "cluster-wire-fn" "Static graph for one ZooKeeper cluster, reused by the parent multi-cluster workflow."
   "cluster-next-fn" "Dynamic routing for one cluster: fan out one node branch per server, then let branches join at zoo-cfg."
   "cluster-wf" "Reusable single-cluster workflow. It ships with a default backend advice that a parent can replace by reusing the same advice id."
   "clusters-wire-fn" "Parent graph that embeds cluster-wf as an ordinary step and reports after all cluster branches join."
   "clusters-next-fn" "Parent dynamic routing: fan out one embedded cluster workflow per desired cluster."
   "apex" "Extracts the registrable-looking apex domain from a hostname by taking the last two labels."
   "sending-domain" "Derives the SMTP sending domain from the first website hostname in desired state."
   "keywordize" "Converts string-keyed maps from mock provider outputs into keyword-keyed maps convenient for templates."
   "provider-templates" "Data-driven provider selection table for the multi-ONCE parent advice."
   "provider-advice" "Before-advice that writes provider-specific compute HCL, demonstrating provider selection outside the workflow graph."
   "digitalocean-provider" "Default compute provider advice for the single-ONCE example."
   "oci-provider" "Alternate compute provider advice; re-adding it under the same id swaps the implementation."
   "s3-config" "Builds per-deployment, per-step S3 backend keys so composed workflows do not collide in remote state."
   "step-dir" "Builds a named component subdirectory under the current workdir."
   "server-dir" "Working directory for the compute/server tofu root."
   "smtp-dir" "Working directory for the SMTP tofu root."
   "dns-dir" "Working directory for the DNS tofu root."
   "smtp-post-dir" "Working directory for the SMTP post-processing tofu root."
   "compute-step" "Bare tofu step for compute. Provider and backend files are written by advice immediately before it runs."
   "tofu-with-spec" "Shared create/delete pattern: scaffold main.tf before apply, but destroy before deleting files."
   "smtp-step" "Creates the mock SMTP provider resources and exposes records needed by DNS."
   "dns-step" "Join step for compute and SMTP. It combines branch outputs, renders DNS HCL, and carries normalized server/SMTP data forward."
   "smtp-post-step" "Second SMTP-related tofu root that runs only after DNS has the data needed to finalize the sending domain."
   "ansible-local-step" "Scaffold-only step for operator-machine SSH config. It demonstrates that workflow steps need not shell out."
   "ansible-remote-step" "Scaffold-only step for the remote playbook that would configure the box."
   "once-wire-fn" "Static graph for one ONCE-style deployment; the multi example embeds this workflow once per deployment."
   "once-wf" "Reusable single-deployment workflow with default provider and local backends. Parent workflows replace those advice ids to customize behavior."
   "deployments-wire-fn" "Parent graph that embeds once-wf as a step and scopes each branch to one deployment through :in-fn."
   "deployments-next-fn" "Dynamic fan-out for the multi-ONCE parent: one deployment branch per desired-state entry."})

(def html-escape-map
  {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"})

(defn esc [s]
  (str/escape (str s) html-escape-map))

(defn slug [s]
  (let [x (-> (str s)
              (str/lower-case)
              (str/replace #"[^a-z0-9]+" "-")
              (str/replace #"(^-+|-+$)" ""))]
    (if (str/blank? x) "section" x)))

(defn line-starts [s]
  (loop [idx 0 starts [0]]
    (if-let [n (str/index-of s "\n" idx)]
      (recur (inc n) (conj starts (inc n)))
      starts)))

(defn line-number [starts idx]
  (count (take-while #(<= % idx) starts)))

(def opening? #{\( \[ \{} )
(def closing? #{\) \] \}} )

(defn delimiter? [ch]
  (or (nil? ch)
      (Character/isWhitespace ^char ch)
      (#{\, \( \) \[ \] \{ \} \"} ch)))

(defn skip-char-literal [s i]
  (let [n (count s)]
    (loop [j (min n (+ i 2))]
      (if (or (>= j n) (delimiter? (.charAt s j)))
        j
        (recur (inc j))))))

(defn top-level-forms [s]
  (let [n (count s)]
    (loop [i 0 depth 0 start nil forms []]
      (if (>= i n)
        forms
        (let [ch (.charAt s i)]
          (cond
            (= ch \;)
            (recur (or (some-> (str/index-of s "\n" i) inc) n) depth start forms)

            (= ch \")
            (let [j (loop [j (inc i) esc? false]
                      (cond
                        (>= j n) n
                        esc? (recur (inc j) false)
                        (= \\ (.charAt s j)) (recur (inc j) true)
                        (= \" (.charAt s j)) (inc j)
                        :else (recur (inc j) false)))]
              (recur j depth start forms))

            (= ch \\)
            (recur (skip-char-literal s i) depth start forms)

            (opening? ch)
            (let [start' (if (zero? depth) i start)]
              (recur (inc i) (inc depth) start' forms))

            (closing? ch)
            (let [depth' (max 0 (dec depth))]
              (if (and start (zero? depth'))
                (recur (inc i) depth' nil (conj forms {:start start :end (inc i)}))
                (recur (inc i) depth' start forms)))

            :else
            (recur (inc i) depth start forms)))))))

(defn skip-ws-and-comments [s i]
  (let [n (count s)]
    (loop [i i]
      (cond
        (>= i n) i
        (Character/isWhitespace ^char (.charAt s i)) (recur (inc i))
        (= \, (.charAt s i)) (recur (inc i))
        (= \; (.charAt s i)) (recur (or (some-> (str/index-of s "\n" i) inc) n))
        :else i))))

(defn read-string-token [s i]
  (let [n (count s)
        j (loop [j (inc i) esc? false]
            (cond
              (>= j n) n
              esc? (recur (inc j) false)
              (= \\ (.charAt s j)) (recur (inc j) true)
              (= \" (.charAt s j)) (inc j)
              :else (recur (inc j) false)))
        lit (subs s i j)]
    [(try (read-string lit) (catch Exception _ (subs lit 1 (max 1 (dec (count lit)))))) j]))

(defn skip-balanced [s i]
  (let [n (count s)]
    (loop [j i depth 0]
      (if (>= j n)
        n
        (let [ch (.charAt s j)]
          (cond
            (= ch \;)
            (recur (or (some-> (str/index-of s "\n" j) inc) n) depth)

            (= ch \")
            (let [[_ j'] (read-string-token s j)]
              (recur j' depth))

            (= ch \\)
            (recur (skip-char-literal s j) depth)

            (opening? ch)
            (recur (inc j) (inc depth))

            (closing? ch)
            (let [depth' (dec depth)]
              (if (zero? depth')
                (inc j)
                (recur (inc j) depth')))

            :else
            (recur (inc j) depth)))))))

(defn read-token [s i]
  (let [i (skip-ws-and-comments s i)
        n (count s)]
    (cond
      (>= i n) [nil i]
      (= \" (.charAt s i)) (let [[v j] (read-string-token s i)] [{:type :string :value v} j])
      (#{\( \[ \{} (.charAt s i)) [{:type :form :value (subs s i (skip-balanced s i))}
                                    (skip-balanced s i)]
      :else (let [j (loop [j i]
                      (if (or (>= j n) (delimiter? (.charAt s j)))
                        j
                        (recur (inc j))))]
              [{:type :token :value (subs s i j)} j]))))

(defn skip-metadata-token [s i]
  (let [i (skip-ws-and-comments s i)
        n (count s)]
    (cond
      (>= i n) i
      (= \^ (.charAt s i))
      (let [[_ j] (read-token s (inc i))]
        j)
      (and (< (inc i) n) (= \# (.charAt s i)) (= \^ (.charAt s (inc i))))
      (let [[_ j] (read-token s (+ i 2))]
        j)
      :else i)))

(defn read-name-token [s i]
  (loop [i (skip-ws-and-comments s i)]
    (let [i' (skip-metadata-token s i)]
      (if (= i i')
        (read-token s i)
        (recur i')))))

(defn token-name [{:keys [value]}]
  (some-> value
          (str/replace #"^'" "")
          (str/replace #"^#'" "")
          (str/replace #"^:+" "")))

(defn more-before-close? [s i]
  (let [i (skip-ws-and-comments s i)]
    (and (< i (count s))
         (not= \) (.charAt s i)))))

(defn form-info [chunk]
  (let [sig-start (str/index-of chunk "(")
        sig (when sig-start (subs chunk sig-start))
        [_ opener] (when sig (re-find #"(?s)^\(\s*([^\s\)\[\{]+)" sig))
        after-opener (when opener (-> (str/index-of sig opener) (+ (count opener))))
        [name-token after-name] (case opener
                                  nil [nil 0]
                                  "ns" (read-token sig after-opener)
                                  "require" [{:type :token :value "require"} after-opener]
                                  ("def" "defn" "defn-" "defmacro" "deftest" "defonce" "declare")
                                  (read-name-token sig after-opener)
                                  [{:type :token :value opener} after-opener])
        name (or (token-name name-token) opener "preamble")
        after-name (skip-ws-and-comments sig after-name)
        [maybe-doc after-doc] (when (and sig (< after-name (count sig)) (= \" (.charAt sig after-name)))
                                (read-string-token sig after-name))
        def-doc? (or (not (contains? #{"def" "defonce"} opener))
                     (and (string? maybe-doc) (more-before-close? sig after-doc)))]
    {:opener opener
     :name name
     :docstring (when (and def-doc? (string? maybe-doc)) maybe-doc)}))

(defn cleaned-comment-line [line]
  (let [t (str/trim line)]
    (cond
      (str/starts-with? t "#!") nil
      (str/starts-with? t ";")
      (let [body (-> t
                     (str/replace #"^;+\s?" "")
                     (str/replace #"^-{3,}\s*" "")
                     (str/replace #"\s*-{3,}$" "")
                     str/trim)]
        (when-not (str/blank? body) body))
      :else nil)))

(defn prefix-comments [prefix]
  (->> (str/split-lines prefix)
       (keep cleaned-comment-line)
       (str/join "\n")))

(defn chunks-for-file [path]
  (let [text (slurp path)
        starts (line-starts text)
        forms (top-level-forms text)]
    (loop [prev 0 forms forms chunks []]
      (if-let [{:keys [start end]} (first forms)]
        (let [prefix (subs text prev start)
              form (subs text start end)
              code (str prefix form)
              info (form-info form)]
          (recur end (next forms)
                 (conj chunks (assoc info
                                      :path path
                                      :code code
                                      :line (line-number starts start)
                                      :comments (prefix-comments prefix)))))
        (let [tail (subs text prev)]
          (cond-> chunks
            (not (str/blank? tail))
            (conj {:path path :opener "tail" :name "trailing content"
                   :code tail :line (line-number starts prev)
                   :comments (prefix-comments tail)})))))))

(defn opener-kind [{:keys [opener name]}]
  (case opener
    "ns" "namespace"
    "require" "script require"
    "defn" "function"
    "defn-" "private function"
    "defmacro" "macro"
    "def" "var"
    "defonce" "var"
    "deftest" "test"
    "declare" "declaration"
    "when" (if (= name "when") "script entrypoint" "form")
    (or opener "form")))

(defn humanize-name [s]
  (-> (str s)
      (str/replace #"[_\-]+" " ")
      (str/replace #"\?" "")
      (str/replace #"!" "")
      str/trim))

(defn sentence-case [s]
  (if (str/blank? s)
    s
    (str (str/upper-case (subs s 0 1)) (subs s 1))))

(defn auto-note [{:keys [path opener name]}]
  (cond
    (= opener "ns") "Namespace declaration and dependency surface for this file."
    (= opener "require") "Script dependency imports. In examples, dependencies are loaded after the babashka classpath bootstrap has made green and local resources available."
    (= opener "deftest") (str "Verifies that " (humanize-name name) ".")
    (= opener "declare") (str "Forward declaration for " name ", needed because a later function calls it before its definition appears.")
    (= opener "when") "Script guard: only run -main when this file is executed directly, not when it is loaded at a REPL."
    (str/starts-with? path "test/") (str "Test helper for " (-> path
                                                                    (str/replace #"\.clj$" "")
                                                                    (str/replace #"/" ".")
                                                                    (str/replace #"_" "-"))
                                         ".")
    (contains? #{"def" "defonce"} opener) (str "Top-level value used by this namespace: " name ".")
    (= opener "defn-") (str "Private helper used by this namespace: " (humanize-name name) ".")
    (= opener "defn") (str "Function " name " in this file's workflow or public API.")
    :else "Top-level form."))

(defn note-for [chunk]
  (or (source-form-notes [(:path chunk) (:name chunk)])
      (common-form-notes (:name chunk))))

(defn docs-text [chunk]
  (let [pieces [(note-for chunk)
                (:docstring chunk)
                (:comments chunk)]
        pieces (->> pieces
                    (remove str/blank?)
                    distinct
                    vec)]
    (if (seq pieces)
      (str/join "\n\n" pieces)
      (auto-note chunk))))

(defn code-inline [s]
  (str/replace (esc s) #"`([^`]+)`" "<code>$1</code>"))

(defn paragraph-html [para]
  (let [lines (str/split-lines para)]
    (if (every? #(str/starts-with? (str/trim %) "-") lines)
      (str "<ul>"
           (apply str (map #(str "<li>" (code-inline (str/trim (str/replace % #"^-\s*" ""))) "</li>") lines))
           "</ul>")
      (str "<p>" (str/replace (code-inline para) "\n" "<br>") "</p>"))))

(defn docs-html [s]
  (->> (str/split (str/trim (str s)) #"\n\s*\n")
       (remove str/blank?)
       (map paragraph-html)
       (apply str)))

(defn file-id [path]
  (slug path))

(defn chunk-id [chunk]
  (str (file-id (:path chunk)) "-" (slug (:name chunk)) "-" (:line chunk)))

(defn file-group [path]
  (first (str/split path #"/")))

(defn toc-html [paths]
  (let [groups (partition-by file-group paths)]
    (apply str
           (for [g groups
                 :let [group (file-group (first g))]]
             (str "<li class=\"group\">" (esc group) "</li>"
                  (apply str (for [p g]
                               (str "<li><a href=\"#" (file-id p) "\">" (esc p) "</a></li>"))))))))

(defn defs-html [chunks]
  (let [defs (->> chunks
                  (filter #(contains? #{"def" "defonce" "defn" "defn-" "defmacro" "deftest" "declare"} (:opener %)))
                  (map #(str "<a href=\"#" (chunk-id %) "\"><code>" (esc (:name %)) "</code></a>"))
                  (str/join ", "))]
    (str "<p>" (count chunks) " top-level form" (when (not= 1 (count chunks)) "s")
         (when (not (str/blank? defs)) (str "; named definitions/tests: " defs))
         "</p>")))

(defn chunk-html [chunk]
  (str "<article class=\"docco-row\" id=\"" (chunk-id chunk) "\">\n"
       "<div class=\"docs\">\n"
       "<h3><code>" (esc (:name chunk)) "</code> "
       "<span style=\"color:var(--muted);font-weight:400\">(" (esc (opener-kind chunk)) ", line " (:line chunk) ")</span></h3>\n"
       (docs-html (docs-text chunk)) "\n"
       "</div>\n"
       "<div class=\"code\"><pre><code class=\"language-clojure\">" (esc (:code chunk)) "</code></pre></div>\n"
       "</article>\n"))

(defn file-html [path chunks]
  (let [overview (or (file-notes path) (some :docstring chunks) "Source file.")]
    (str "<section class=\"file\" id=\"" (file-id path) "\">\n"
         "<header class=\"file-header\">\n"
         "<h2><code>" (esc path) "</code></h2>\n"
         (docs-html overview) "\n"
         (defs-html chunks) "\n"
         "</header>\n"
         (apply str (map chunk-html chunks))
         "</section>\n")))

(def css
  ":root { --bg:#ffffff; --paper:#fbfbfc; --code:#f6f8fa; --text:#24292f; --muted:#57606a; --border:#d8dee4; --accent:#2da44e; --link:#0969da; }
* { box-sizing:border-box; }
html { scroll-behavior:smooth; color-scheme:light; }
body { margin:0; color:var(--text); background:var(--bg); font:16px/1.55 -apple-system,BlinkMacSystemFont,\"Segoe UI\",Helvetica,Arial,sans-serif; }
a { color:var(--link); text-decoration:none; } a:hover { text-decoration:underline; }
.hero { padding:2.5rem clamp(1rem,4vw,3rem); border-bottom:1px solid var(--border); background:linear-gradient(180deg,#fff,#f6f8fa); }
.hero h1 { margin:0 0 .4rem; font-size:clamp(2rem,5vw,4rem); line-height:1; letter-spacing:-.04em; }
.hero p { max-width:70rem; color:var(--muted); margin:.4rem 0; }
.badges { display:flex; flex-wrap:wrap; gap:.5rem; margin-top:1rem; }
.badge { border:1px solid var(--border); background:#fff; border-radius:999px; padding:.25rem .65rem; color:var(--muted); font-size:.9rem; }
.layout { display:grid; grid-template-columns:minmax(14rem,18rem) minmax(0,1fr); align-items:start; }
.toc { position:sticky; top:0; max-height:100vh; overflow:auto; padding:1.25rem; border-right:1px solid var(--border); background:#fff; }
.toc h2 { margin:0 0 .75rem; font-size:.85rem; text-transform:uppercase; color:var(--muted); letter-spacing:.08em; }
.toc ol { list-style:none; padding:0; margin:0; } .toc li { margin:.3rem 0; } .toc .group { margin-top:1rem; font-weight:700; color:var(--text); }
.toc a { font-size:.9rem; color:var(--muted); } .toc a:hover { color:var(--link); }
main { min-width:0; }
.file-header { padding:2rem clamp(1rem,3vw,2rem); border-bottom:1px solid var(--border); background:#fff; }
.file-header h2 { margin:0 0 .25rem; font-size:1.55rem; } .file-header p { max-width:75rem; margin:.5rem 0 0; color:var(--muted); }
.docco-row { display:grid; grid-template-columns:minmax(18rem,38%) minmax(0,62%); border-bottom:1px solid var(--border); }
.docs { padding:1.35rem clamp(1rem,2.5vw,2rem); background:var(--paper); border-right:1px solid var(--border); min-width:0; }
.docs h3 { font-size:1.05rem; margin:0 0 .7rem; } .docs p { margin:.65rem 0; } .docs ul { padding-left:1.15rem; }
.docs code { background:rgba(175,184,193,.2); padding:.1rem .28rem; border-radius:.25em; font-size:.92em; }
.code { min-width:0; background:var(--code); }
.code pre { margin:0; padding:1.35rem clamp(1rem,2.5vw,2rem); overflow-x:auto; background:transparent; font-size:.9rem; line-height:1.5; }
.code code { font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,\"Liberation Mono\",monospace; }
.defs { margin:.75rem 0 0; padding-left:1.1rem; } .defs li { margin:.2rem 0; }
.footer { padding:2rem; color:var(--muted); border-top:1px solid var(--border); }
@media (max-width:900px) { .layout { grid-template-columns:1fr; } .toc { position:relative; max-height:none; border-right:0; border-bottom:1px solid var(--border); } .docco-row { grid-template-columns:1fr; } .docs { border-right:0; border-bottom:1px solid var(--border); } }")

(defn render []
  (doseq [p file-order]
    (when-not (fs/exists? p)
      (throw (ex-info (str "missing docco input: " p) {:path p}))))
  (let [by-file (into {} (map (fn [p] [p (chunks-for-file p)]) file-order))
        form-count (reduce + (map count (vals by-file)))]
    (str "<!doctype html>\n<html lang=\"en\">\n<head>\n"
         "<meta charset=\"utf-8\">\n"
         "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
         "<title>Green source tour, Docco style</title>\n"
         "<style>\n" css "\n</style>\n"
         "</head>\n<body>\n"
         "<header class=\"hero\" id=\"top\">\n"
         "<h1>Green source tour</h1>\n"
         "<p>A Docco-style, single-page source tour for Green. It is regenerated from the current Clojure sources, tests, and executable example <code>green</code> scripts; commentary is kept at namespace, function, var, test, or script-form granularity.</p>\n"
         "<p><a href=\"index.html\">Back to the specification</a></p>\n"
         "<div class=\"badges\"><span class=\"badge\">static HTML</span><span class=\"badge\">src + test + examples</span><span class=\"badge\">" (count file-order) " files</span><span class=\"badge\">" form-count " forms</span><span class=\"badge\">generated " generated-on "</span></div>\n"
         "</header>\n"
         "<div class=\"layout\">\n"
         "<nav class=\"toc\" aria-label=\"Table of contents\">\n"
         "<h2>Files</h2><ol>\n" (toc-html file-order) "\n</ol><p style=\"margin-top:1rem\"><a href=\"#top\">Back to top</a></p></nav>\n"
         "<main>\n"
         (apply str (map (fn [p] (file-html p (get by-file p))) file-order))
         "<footer class=\"footer\">Generated by <code>bb docco</code> from <code>src/green/*.clj</code>, <code>test/green/*.clj</code>, and <code>examples/*/green</code>. Edit source and <code>tasks/docco.clj</code> commentary, then regenerate this HTML.</footer>\n"
         "</main>\n</div>\n</body>\n</html>\n")))

(spit "docco.html" (render))
(println "Wrote docco.html")
