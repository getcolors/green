# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`green` is the canonical colour of the three getcolors workflow SDKs: green
(Clojure/Babashka, this repository), red (TypeScript/Bun) and blue
(Python/uv). They share one model: steps are `opts -> opts`; a workflow is a
static `wire-fn` graph plus an optional dynamic `next-fn`; successors fan out
in parallel and converging branches join; Emacs-style advice layers behaviour
onto steps; desired state is YAML (or EDN) with `COLORS_PAR_*` overlay; and
dry-run, progress and lifecycle preflight are optional helpers. It is the
foundation for deployment code written by coding agents: a package's steps
call cloud CLIs or APIs directly. PocketDeploy
(https://github.com/pocketcontext/pocketdeploy, built on blue) is the starter
package. The full specification lives in `index.html`.

OpenTofu, Ansible, Selmer scaffolding, the Kubernetes controller, provider
registries, process helpers and resource scopes were removed on 2026-10-29.
Do not reintroduce them; steps shell out or call APIs themselves.

`cli/read-state` picks the reader from the file extension — `.yml`/`.yaml`
through yamlstar, anything else as EDN. Both are supported; the in-repo
example and downstream `dotfiles` use YAML. Do not describe either as the
only format.

The yamlstar version in `deps.edn` and `bb.edn` is **pinned exactly on
purpose** and must stay in lockstep across the two files. It follows the YAML
1.2 core schema, and downstream parity depends on that: the red and blue ports
read the same file with `Bun.YAML` and PyYAML. Bumping it is a deliberate,
cross-repo change, not a routine dependency update.

## Commands

```sh
bb test             # run the test suite under babashka
clojure -X:test     # run the test suite under the JVM
bb docco            # regenerate docco.html from source + tasks/docco.clj notes

clojure -T:build jar       # build target/green-<version>.jar + pom
clojure -T:build install   # install into the local ~/.m2
clojure -T:build deploy    # deploy to Clojars (needs CLOJARS_USERNAME/CLOJARS_PASSWORD)
```

Run a single test namespace under the JVM: `clojure -X:test :nses '[green.advice-test]'`.
Under babashka, `bb.edn`'s `test` task explicitly requires and runs the
namespaces named there — add new test namespaces to both `:requires` and the
`run-tests` call. Under the JVM, `clojure -X:test` uses `deps.edn`'s `:test`
dirs (`test`, `examples/hello/test`) with `cognitect.test-runner` discovery.
`hello.launcher-test` runs the example launcher as a subprocess, so both
runners need `bb` on `PATH`.

Dependency rule: `green` has not been published to Clojars. External consumer
docs and launcher snippets must use a git dependency with an explicit
`:git/sha`; do not recommend `:mvn/version` until a Clojars release exists.
The in-repo example resolves `green` via `:local/root "../.."` (or
`GREEN_LIB_ROOT`).

## Consumer contract

The external consumer is `getcolors/dotfiles`, pinned to an older SHA. It uses
`green.cli/run-cli`, `read-pars`, `par-name` and `stage-dir`;
`green.lifecycle/preflight`; `green.workflow/workflow` and `run`;
`green.progress/advise`; and `green.dry-run/advise`. Keep those vars and their
behaviour compatible so it can re-pin. Verify against a scratch copy of
dotfiles with a `{:local/root ...}` green override in its `bb.edn` (its tests
are Babashka-only) and `GREEN_LIB_ROOT` for its launcher; never edit the
dotfiles checkout from here.

## Example

`examples/hello` is a complete package: `green` (the babashka launcher holding
the workflow), `green.yml` (desired state) and `test/hello/launcher_test.clj`.
`create` runs a lifecycle preflight, then forks `uname -s` and a file write,
joining at a check step; `delete` removes the file; `--dry-run` skips the
write/check/remove steps; `COLORS_PAR_NAME` overlays `name`. It is offline and
harmless. Keep its test passing when the CLI or engine changes.

## Architecture

Six namespaces under `src/green/`:

- **`workflow.clj`** — the engine. A **step** is a plain function
  `opts -> opts`, named by a qualified keyword. A `wire-fn` (`step run-opts ->
  [fn & next-steps]`) is the static happy-path graph for that run; it may
  depend on stable run-level inputs such as `:green/event`. An optional
  `next-fn` (`step default-next opts -> [[next-step opts] ...]`) does dynamic
  routing — fan-out, conditional branching, error short-circuiting.
  Multiple successors run in parallel (real `future`s); branches that
  converge on the same step **join** it once, with per-branch results
  under `:green/branches`. If any branch fails mid-fork, in-flight
  siblings finish their current step and the fork collapses without
  running the join, propagating the worst exit.
  Outcomes are Unix-style: `:green/exit` (0 ok, >0 error), `:green/err`,
  `:green/trace`; thrown exceptions inside a step are caught and converted
  automatically.
  `wf/step` turns a whole workflow into an ordinary step function so
  workflows compose (`:in-fn`/`:out-fn` shape opts crossing the boundary). The
  engine preserves inherited advice even when `:in-fn` rebuilds opts from
  scratch; a custom `:in-fn` should carry ambient keys such as `:green/event`
  and `:green/dry-run` if the sub-workflow needs them.

  The scheduler is basically a small fork/join workflow runner.
  In simple words:

  1. Start with one live task
     - A "live" task means: "run step X with this opts map."
     - Initially there is only the workflow's `:start` step.
  2. Keep looping while there is work
     - The scheduler keeps two piles:
       - `live`: branches still running
       - `finished`: branches that reached the end
  3. Group live branches by step
     - If multiple branches are waiting at the same step, that may mean they
       need to join.
  4. Decide which steps are ready
     - Before running a step, the scheduler asks:
       - "Could another currently-running branch still reach this same step later?"
     - If yes, it waits, so the branches can join together.
     - This uses the static workflow graph from this run's `wire-fn`.
  5. Run ready work in parallel
     - Ready steps are executed using `future`, so independent branches run
       concurrently.
  6. After a step runs, choose what happens next
     - No next step: branch is finished.
     - One next step: continue normally.
     - Multiple next steps: this is a fork; create one branch per successor.
  7. Handle joins
     - If branches from different origins arrive at the same step, the scheduler
       runs that step once.
     - It gives the join step:
       - the opts from the fork point
       - all branch results under `:green/branches`
  8. Handle failures inside forks
     - If one branch fails, siblings already running finish their current step.
     - Then the whole fork collapses.
     - The join is skipped.
     - The final result carries the worst exit code and all branch results.
  9. Finish
     - When there are no live branches left, the scheduler returns the final
       opts.
     - If multiple terminal branches exist, it returns the first failed one,
       otherwise the last successful one.

  Short version: it repeatedly runs all safe-to-run steps in parallel, waits
  when branches may need to join, joins converged branches once, and collapses
  forks cleanly on failure.
- **`advice.clj`** — Emacs `nadvice`-style combinators (`:around`,
  `:before`, `:after`, `:override`, `:before-while`, `:before-until`,
  `:after-while`, `:after-until`, `:filter-args`, `:filter-return`) for
  wrapping step functions without touching the wiring. `wf/advice-add`
  targets one step; `wf/advice-add-all` targets every step. Advice stacks
  in strict add order across both registries (most recently added is
  outermost) unless a `:depth` prop (-100..100) overrides placement,
  exactly like Emacs hook depths. Use cases by `how`: `:around` = dry-run,
  retry, timing, locks; `:override` = replace/stub; `:before` = setup or
  prerequisites; `:after` = audit/metrics/cleanup; `:before-while` =
  precondition gates; `:before-until` = fast-path/no-op; `:after-while` =
  success-only follow-up; `:after-until` = recovery/fallback; `:filter-args`
  = normalize/scope input; `:filter-return` = normalize/enrich output.
  Registries are plain immutable values — advice is workflow-scoped, not
  process-global, and all operations (`add`, `remove-id`, `merge-entries`,
  ...) are pure.
  **Advice inheritance across `wf/step` embeds** is the trickiest part of
  the engine: a run stamps its effective registries into opts under a
  private key, the nested run merges them over its own via
  `merge-registry`/`merge-entries`, and this is transitive through nested
  embeds. Step names match flat at any depth — an ancestor's advice on a
  step name reaches an embedded sub-workflow's step of that name. At equal
  `:depth`, ancestor advice stacks outside child advice; an ancestor entry
  with the same `:id` replaces a child's (e.g. swapping a sub-workflow's
  default advice from the parent). Use
  `wf/advice-plan` to inspect the composed stack for a step, with
  provenance (`:scope :step`/`:all`, `:level` = chain depth).
- **`dry_run.clj`** — dry-run is built on the advice facility rather than
  hardwired into steps: `dry-run/advise` attaches `:around` advice (id
  `::skip`) to a list of steps; when `:green/dry-run` is set (stamped by
  the CLI's `--dry-run` flag) the step prints what it would do and is
  skipped instead of run.
- **`progress.clj`** — progress reporting, built on the advice facility:
  `progress/advise` attaches an `:around` advice (id `::progress`) to every
  step; it prints the step name on entry and elapsed time on exit, reading
  `:green/step` from opts. Takes only the workflow as argument (no step list
  needed, unlike dry-run).
- **`cli.clj`** — the thinnest layer: parses `<event> [-f|--file green.yml]
  [--start step] [--end step] [--dry-run]` (optionally restricted to
  `:allowed-events`), loads the desired-state file as
  YAML or EDN by its extension, overlays `COLORS_PAR_*`, stamps
  `:green/event`, and calls `wf/run`.
  `cli/exec` is what a project's `./green` babashka script calls;
  `cli/run-cli` is the testable, non-exiting version. `read-pars` is the
  secret channel: desired state is a file on disk and must never hold
  credentials, so every flat key can be supplied by an environment variable
  instead (`:do-token` ← `COLORS_PAR_DO_TOKEN`), coerced to the type of the
  value it replaces. It is idempotent, so a project may re-apply it in a
  validation step without caring whether the CLI already did.
  `find-up` locates the nearest desired-state file (`run-cli`'s
  `:search-parents`), and `stage-dir` resolves
  `<state-file dir>/<workdir or .colors>/<profile>/<tool>` for packages that
  render files (dotfiles uses it).
- **`lifecycle.clj`** — configurable package preflight: defaults, parameter
  overlay, ordered validators, exit-2 aggregation, and a success callback.

## Conventions

- All cross-step state is a single `opts` map threaded through the graph;
  steps read and return it, never mutate anything external without going
  through `opts`.
- Namespaced keywords under `:green/*` are reserved for the engine's own
  control keys (`:green/exit`, `:green/err`, `:green/trace`,
  `:green/event`, `:green/dry-run`, `:green/step`, `:green/branches`); everything
  project- or library-specific uses its own namespace
  (`:hello/kernel`, `:dotfiles/...`).
- Every public workflow constructor/advice-transforming function (`workflow`,
  `advice-add`, `advice-add-all`, `step`, etc.) is pure — it returns a new
  workflow value or step function rather than mutating one, so workflows are
  safe to branch and share.
- Ignore `dist/`: it is generated output, not source. Do not read, edit, or
  use files under `dist/` as documentation; update the source files instead.

## Documentation

`index.html` is both the full specification and this repository's landing page,
and it carries two analytics tags: GA4 measurement ID `G-4VKP1WY4QJ`, whose
explicit `page_title` must exactly equal the decoded HTML `<title>` and stay
distinct and stable so one Analytics property can separate repositories, and the
self-hosted Rybbit snippet
`<script src="https://rybbit.getcolors.ai/api/script.js" data-site-id="9fb9c41a6d49" defer></script>`,
which shares one site ID across every page because `getcolors.github.io/<repo>/`
paths already encode the repository. Never add one tag without the other.

The page is styled like www.getcolors.ai and shares its header, footer, IBM
Plex fonts and colour tokens with the red and blue pages; this page's
`--accent` is `var(--green)`. Keep it accurate to the code. `docco.html` is
generated by `bb docco` from the sources plus the notes in `tasks/docco.clj`;
regenerate it rather than editing it.
