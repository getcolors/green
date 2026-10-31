# green

The canonical Clojure/Babashka implementation of the Colors workflow model,
alongside [red](https://github.com/getcolors/red) (TypeScript/Bun) and
[blue](https://github.com/getcolors/blue) (Python/uv).

- **DAG execution:** steps are `opts -> opts`; `wire-fn` declares dependencies,
  `next-fn` routes dynamically, parallel branches converge at joins.
- **Subworkflows:** `wf/step` makes a whole workflow an ordinary step, with
  optional input and output adapters.
- **Advice:** attach behavior to one step or all steps, with IDs, ordering,
  inheritance and stack inspection.
- **Configuration:** parse YAML or EDN and explicitly overlay `COLORS_PAR_*`.

Applications own their CLI, validation, planning, reporting and durable state.
Steps call cloud CLIs or APIs directly. The SDK has no generic CLI runner,
lifecycle preflight, bundled dry-run/progress policies, or infrastructure-tool
integration. [PocketDeploy](https://github.com/pocketcontext/pocketdeploy),
built on Blue, is one application of this model. Execution itself does not
provide a persistent recovery journal.

**Docs:** [specification](https://getcolors.github.io/green/) ([repo](index.html)) ·
[source tour](https://getcolors.github.io/green/docco.html) ·
[www.getcolors.ai](https://www.getcolors.ai/).

## The model in one glance

```clojure
(require '[green.workflow :as wf])

(def child
  (wf/workflow {:start :child/build
                :wire-fn (fn [_ _] [(fn [opts] (assoc opts :child/value 42))])}))

(def workflow
  (wf/advice-add-all
    (wf/workflow
      {:start :app/start
       :wire-fn (fn [step _]
                  (case step
                    :app/start [identity :app/child :app/check]
                    :app/child [(wf/step child) :app/join]
                    :app/check [identity :app/join]
                    :app/join [(fn [opts] (assoc opts :app/results (:green/branches opts)))]))})
    :around :app/report
    (fn [inner opts]
      (println "running" (:green/step opts))
      (inner opts))))

(wf/run workflow {})
```

The parent advice reaches the child steps. Use `wf/advice-plan` to inspect
ordering, and namespace step names to avoid unintended matches.

- Outcomes are `:green/exit` (0 ok, >0 error), `:green/err`, `:green/trace`.
  Thrown exceptions and non-map returns become failures. The engine stamps
  `:green/step` before each step.
- `wire-fn` receives the run's initial opts. Result-dependent routing belongs
  in `next-fn`: `(step default-next opts) -> [[next-step opts] ...]`.
- Joins run once with fork-point opts and branch results in `:green/branches`.
  A failed fork lets in-flight siblings finish their current step, skips the
  join and propagates the worst exit.
- `:start` and optional inclusive `:end` select a workflow slice in the API.
- `wf/step` accepts `:in-fn` and `:out-fn`. Input adapters must carry any
  application context needed inside the child; advice inheritance is retained
  automatically. Output adapters own preservation or explicit handling of
  the child's failure result.
- Advice supports `:around`, `:before`, `:after`, `:override`, `:before-while`,
  `:before-until`, `:after-while`, `:after-until`, `:filter-args` and
  `:filter-return`. Lower `:depth` wraps outside; at equal depth newest wraps
  outside. Same-ID ancestor advice replaces child advice.

## Configuration and installation

`green.cli` keeps its existing name for the configuration helpers:
`read-state` parses `.yml`/`.yaml` as YAML 1.2 core-schema values and other
extensions as EDN; `read-pars` overlays `COLORS_PAR_*` with boolean/integer
coercion; `par-name` maps a flat key to its environment variable name.
Applications explicitly load files and call these helpers. Quote version-like
YAML values such as `"3.10"`; keep secrets outside configuration files.

Green is not on Clojars. Use a git dependency with an explicit published SHA:

```clojure
io.github.getcolors/green {:git/url "https://github.com/getcolors/green.git"
                           :git/sha "REPLACE_WITH_COMMIT_SHA"}
```

## Example

`examples/hello` owns its Babashka CLI. It validates inputs, forks `uname -s`
and an embedded write → verify workflow, then joins to report the greeting.
Application-defined advice logs steps and skips writes under its own dry-run
flag. Neither policy is a bundled SDK module.

```sh
cd examples/hello
./green create --dry-run
./green create
COLORS_PAR_NAME=agent ./green create
./green delete
```

The example resolves Green from this checkout or `GREEN_LIB_ROOT`; `-f FILE`
selects configuration, otherwise `green.yml` in the caller's directory.

## Tests

```sh
bb test
clojure -X:test
clojure -T:build jar
bb docco
```

The example tests drive the real launcher in temporary directories, so both
runners need `bb` on `PATH`. Tests cover the engine, all advice combinators,
composition/inheritance, YAML scalars and environment overlays.
