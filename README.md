# green

A babashka-compatible Clojure library for building idempotent devops CLIs:
desired state in YAML or EDN, workflows as step graphs threaded by one map,
Selmer-scaffolded configuration files, OpenTofu as the muscle, and Ansible for
SSH provisioning when you need it.

**Docs:** [specification](https://amiorin.github.io/green/) ([repo](index.html)) · [source tour](https://amiorin.github.io/green/docco.html) ([repo](docco.html)).

## The model in one glance

```clojure
(require '[green.workflow :as wf]
         '[green.cli :as cli])

(defn wire-fn [step run-opts]           ;; static graph for this run
  (case step
    :zk/start   [start-step :zk/node]
    :zk/node    [node-step  :zk/zoo-cfg]
    :zk/zoo-cfg [zoo-cfg-step]))

(defn next-fn [step default-next opts]  ;; dynamic router: fan-out, error routing
  (cond
    (pos? (:green/exit opts 0)) []
    (= step :zk/start) (for [n (:zk/servers opts)]
                         [:zk/node (assoc opts :zk/node n)])
    :else (map (fn [s] [s opts]) default-next)))

(def workflow
  (wf/workflow {:start :zk/start :wire-fn wire-fn :next-fn next-fn}))

(cli/exec workflow)                     ;; ./green create | ./green delete
```

- A **step** is a function `opts -> opts`, named by a qualified keyword.
- Outcomes are Unix-style: `:green/exit` (0 ok), `:green/err`,
  `:green/trace`. Thrown exceptions and non-map step returns are converted to
  that contract. The engine stamps `:green/step` before each step runs.
- `wire-fn` is called as `(wire-fn step run-opts)`, where `run-opts` is the
  initial opts for this run. It may switch the static graph on stable inputs
  such as `:green/event`; step-result-dependent routing belongs in `next-fn`.
- Multiple successors run in **parallel** using `future`s. Branches converging
  on a step **join** it once with branch results under `:green/branches`. If a
  branch fails inside a fork, running siblings finish their current step, the
  join is skipped, and the worst exit propagates.
- **Advice** is Emacs `nadvice`-style, workflow-scoped, and pure:
  `wf/advice-add` targets one step and `wf/advice-add-all` targets every step.
  Supported `how` values are `:around`, `:override`, `:before`, `:after`,
  `:before-while`, `:before-until`, `:after-while`, `:after-until`,
  `:filter-args`, and `:filter-return`. At equal `:depth`, newest advice is
  outermost; lower `:depth` runs farther outside.
- **Composition:** `(wf/step sub-workflow {:in-fn … :out-fn …})` turns a workflow
  into an ordinary step — wire it, advise it, and fan it out. Parent advice is
  inherited by embedded workflows; same step names match flat at any depth, and
  a parent advice entry with the same id replaces the child's entry.
  `wf/advice-plan` shows the composed stack.
- `green.scaffold/scaffold` renders flat Selmer file specs on create; on
  `:delete` the same specs name targets to remove, pruning immediate empty
  parent directories.
- `green.tofu/tofu-step` runs `tofu init` + `apply` for any non-`:delete`
  event and `init` + `destroy` for `:delete`. Apply outputs land under
  `:tofu/outputs` by default. Backends are explicit `:before` advices:
  `backend-advice`, `local-backend-advice`, `s3-backend-advice`, and
  `gcs-backend-advice`.
- `green.ansible/ansible-step` runs `ansible-playbook` event-aware
  (`create.yml` for non-delete, `delete.yml` for delete), parses PLAY RECAP
  under `:ansible/recap`, and pairs with `inventory-advice` for generated INI
  inventories.
- `green.dry-run/advise` + `--dry-run` skips the named side-effecting steps and
  prints what would run. `green.progress/advise` adds all-step timing output.
- `green.cli/exec` reads the desired-state file — `green.yml` by default,
  overridden with `-f/--file`. `cli/read-state` picks the reader from the
  extension: `.yml`/`.yaml` through yamlstar, anything else as EDN. Because that
  file is on disk it must never hold secrets; `COLORS_PAR_*` environment
  variables overlay flat keys after it is read (`COLORS_PAR_DO_TOKEN` →
  `:do-token`), coerced to the type of the value they replace.

## Scheduler algorithm in plain English

The scheduler is a small fork/join workflow runner:

1. Start with one live task: the workflow's `:start` step and the initial opts.
2. Keep two piles: `live` branches still running and `finished` terminal
   branches.
3. Group live branches by step. Multiple branches waiting at the same step may
   need to join.
4. A step is ready only when no other live branch can still statically reach
   that same step through this run's `wire-fn` edges. This lets longer branches
   arrive at a join before the join runs.
5. Ready work runs concurrently in `future`s.
6. After a step, zero next pairs terminates, one continues, several fork.
7. Branches from different origins at the same step join: the join step runs
   once from the fork-point opts with `:green/branches` attached.
8. A failed fork collapses: siblings finish their current step, no new fork work
   starts, the join is skipped, and the result carries the worst exit plus all
   branch results.
9. When no live branches remain, the final result is the single terminal opts;
   with multiple terminals, the first failure wins, otherwise the last success.

## Install

`green` has not been published to Clojars yet. Use a git dependency with an
explicit commit SHA:

```clojure
io.github.amiorin/green {:git/sha "REPLACE_WITH_COMMIT_SHA"}
```

In-repo examples use `:local/root "../.."` for development. Publishing for a
future Clojars release: `clojure -T:build jar` (or `install` / `deploy`; deploy
reads `CLOJARS_USERNAME`/`CLOJARS_PASSWORD`). Do not recommend `:mvn/version`
for consumers until a Clojars release exists.

## Try it

Each example's `./green` is a self-contained babashka script. Mock examples use
OpenTofu configs made only of `locals`/`output` blocks, so non-dry-run paths
need `tofu` on `PATH` but create no real infrastructure.

- `examples/zookeeper` — dynamic fan-out/join, scaffold + tofu,
  backend-as-advice, dry-run.
- `examples/multi-zookeeper` — two clusters from one workflow via `wf/step`;
  parent advice reaches embedded steps.
- `examples/once` — a Basecamp ONCE-style single-VPS PaaS: provider-swap
  advice, `compute ∥ smtp → dns → smtp-post → (ansible-local ∥ ansible-remote)`,
  threaded opts, per-step tofu state, and scaffold-only Ansible config. See
  `examples/once/SPEC.md`.
- `examples/multi-once` — many ONCE boxes from one `once-wf`; the parent swaps
  inherited `::provider` and `::backend` advice, using S3 state keys isolated by
  deployment and step. S3 is demonstration-only; `create` needs a real bucket.
- `examples/floci-zookeeper` — the real example: OpenTofu's AWS provider talks
  to floci on `localhost:4566`, creating Docker-backed EC2 instances, then
  `green.ansible` provisions a real ZooKeeper ensemble over SSH and a health
  step verifies quorum. Linux-only at runtime; `--dry-run` works offline.

```sh
cd examples/zookeeper
./green create --dry-run
./green create
./green delete

cd ../multi-zookeeper
./green create --dry-run
./green create
./green delete

cd ../once
./green create --dry-run
./green create
./green delete

cd ../multi-once
./green create --dry-run   # offline path; real create needs an S3 bucket
./green create
./green delete

cd ../floci-zookeeper
./green create --dry-run   # validates and prints; touches nothing
./green create             # real local cluster on floci + Ansible
./green delete             # Ansible delete.yml first, then tofu destroys
```

## Tests

```sh
bb test             # under babashka
clojure -X:test     # under the JVM
```

`test/green/zookeeper_test.clj` drives real `tofu` when it is on `PATH` over
resource-free HCL. `test/green/tofu_test.clj` and
`test/green/ansible_test.clj` cover backend/inventory/playbook helpers without
invoking `tofu` or `ansible-playbook`.
