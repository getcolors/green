# green

The canonical colour of the three getcolors workflow SDKs — **green**
(Clojure/Babashka), [red](https://github.com/getcolors/red) (TypeScript/Bun)
and [blue](https://github.com/getcolors/blue) (Python/uv) — which share one
model:

- a **step** is a function `opts -> opts`;
- a workflow is a static `wire-fn` graph plus an optional dynamic `next-fn`;
- multiple successors **fan out** in parallel and converging branches **join**;
- **advice** (Emacs `nadvice`-style) layers behaviour such as dry-run, progress,
  validation or retries onto steps without touching the wiring;
- desired state is a **YAML** (or EDN) file, with secrets overlaid from
  `COLORS_PAR_*` environment variables;
- optional dry-run, progress and lifecycle-preflight helpers, and a
  `./green <event>` launcher.

Coding agents now write deployment code directly. The colours are the
foundation for that code: a package's steps call cloud CLIs or APIs directly
(`gcloud`, `doctl`, `oci`, an HTTP client), and green supplies the graph,
the advice and the CLI. [PocketDeploy](https://github.com/pocketcontext/pocketdeploy),
built on blue, is the starter package. OpenTofu and Ansible support was
removed on 2026-10-29.

**Docs:** [specification](https://getcolors.github.io/green/) ([repo](index.html)) ·
[source tour](https://getcolors.github.io/green/docco.html) ([repo](docco.html)) ·
[www.getcolors.ai](https://www.getcolors.ai/).

## The model in one glance

```clojure
(require '[green.workflow :as wf]
         '[green.cli :as cli])

(defn wire-fn [step run-opts]            ;; static graph for this run
  (case step
    :app/start  [start-step  :app/a :app/b] ;; two successors: parallel fork
    :app/a      [a-step      :app/join]
    :app/b      [b-step      :app/join]
    :app/join   [join-step]))              ;; runs once, with :green/branches

(def workflow (wf/workflow {:start :app/start :wire-fn wire-fn}))

(cli/exec workflow)                      ;; ./green create | ./green delete
```

- Outcomes are Unix-style: `:green/exit` (0 ok, >0 error), `:green/err`,
  `:green/trace`. Thrown exceptions and non-map step returns are converted to
  that contract. The engine stamps `:green/step` before each step runs.
- `wire-fn` is called as `(wire-fn step run-opts)` with the run's initial opts.
  It may switch the static graph on stable inputs such as `:green/event`;
  result-dependent routing belongs in `next-fn`
  (`(next-fn step default-next opts) -> [[next-step opts] ...]`).
- Branches converging on a step **join** it once with branch results under
  `:green/branches`. If a branch fails inside a fork, running siblings finish
  their current step, the join is skipped, and the worst exit propagates.
- `wf/advice-add` targets one step, `wf/advice-add-all` every step. Supported
  `how` values: `:around`, `:override`, `:before`, `:after`, `:before-while`,
  `:before-until`, `:after-while`, `:after-until`, `:filter-args`,
  `:filter-return`. At equal `:depth`, newest advice is outermost.
- `(wf/step sub-workflow {:in-fn … :out-fn …})` turns a workflow into an
  ordinary step. Parent advice is inherited by embedded workflows by step name;
  `wf/advice-plan` shows the composed stack.
- `green.cli` reads the desired-state file (YAML by `.yml`/`.yaml`, EDN
  otherwise), overlays `COLORS_PAR_*` (`COLORS_PAR_DO_TOKEN` → `:do-token`,
  coerced to the replaced value's type), stamps `:green/event`, and runs.
- `green.dry-run/advise` + `--dry-run` skips the named side-effecting steps.
  `green.progress/advise` prints per-step timing. `green.lifecycle/preflight`
  applies defaults, the overlay and ordered validators (exit 2 on failure).

## Use it

`green` is not on Clojars. Use a git dependency with an explicit SHA:

```clojure
io.github.getcolors/green {:git/url "https://github.com/getcolors/green.git"
                           :git/sha "REPLACE_WITH_COMMIT_SHA"}
```

A package's `./green` launcher is a babashka script that adds that dependency
(or a working tree via `GREEN_LIB_ROOT`), defines its workflow and calls
`green.cli/exec`.

## Example

`examples/hello` is a complete package in one launcher file: a lifecycle
preflight, then `uname -s` (a read-only local CLI call) and a file write fork in
parallel and join at a check step; `delete` removes the file.

```sh
cd examples/hello
./green create --dry-run           # uname runs; the write and check are skipped
./green create                     # hello, world (Linux)
COLORS_PAR_NAME=agent ./green create
./green delete
```

## Tests

```sh
bb test                  # under babashka
clojure -X:test          # under the JVM
clojure -T:build jar     # build target/green-<version>.jar
bb docco                 # regenerate docco.html
```

`examples/hello/test` drives the real launcher as a subprocess, so both test
runners need `bb` on `PATH`.
