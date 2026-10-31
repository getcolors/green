# Green

Read README.md before changing code. Green is the canonical Clojure/Babashka
implementation of the shared Green/Red/Blue workflow model. Its maintained
core is DAG execution, subworkflows, the full advice system and configuration
helpers. Application packages own argument parsing, validation, planning,
reporting and durable recovery state.

OpenTofu/Ansible integration, scaffolding, resource scopes and process helpers
were removed on 2026-10-29. Generic CLI runners, file discovery/stage paths,
lifecycle preflight and bundled dry-run/progress policies were removed on
2026-10-31. Do not reintroduce those application responsibilities into the SDK.

## Source and contracts

Three namespaces live under src/green:

- `workflow.clj`: immutable workflow constructors, static `wire-fn`, dynamic
  `next-fn`, parallel fork/join execution, failure collapse, engine `:start`/
  `:end` slicing, subworkflow `step` and advice registration/inspection.
- `advice.clj`: all ten Emacs-style combinators, ID replacement/removal,
  depth and insertion ordering, and inherited registries.
- `cli.clj`: configuration helpers under the existing import path:
  `read-state`, `read-pars`, `par-name`. It does not run a CLI.

Keep shared behavior aligned with Red and Blue. The engine/advice behavior
remains intact during application-plumbing cleanup. Preserve immutable opts,
qualified step names, failure short-circuiting, branch outcomes and pure
workflow/advice constructors. Engine keys include `:green/exit`, `:green/err`,
`:green/trace`, `:green/step` and `:green/branches`. Applications use their own
namespaces for events, file paths and dry-run flags.

Advice inheritance through `wf/step` is transitive. Parent advice matches
child steps by flat name, stacks outside at equal depth, and replaces same-ID
child entries. The engine re-stamps inherited advice after `:in-fn`, even if
it builds a new map. Input adapters still must carry application context;
output adapters must preserve or explicitly handle child failures. Use
`wf/advice-plan` to inspect the composed stack.

`read-state` selects YAML for `.yml`/`.yaml`, EDN otherwise. YAML map keys
become keywords. Keep the yamlstar version pinned exactly and identical in
`bb.edn` and `deps.edn`; its YAML 1.2 scalar behavior and the compatibility
normalization in `cli.clj` matter across all three languages.

Dotfiles now uses Python/Blue; it is no longer a Green consumer. Historical
consumers keep their published pins. Green is not on Clojars: external examples
must use an explicit published git SHA, never an unpublished Maven version.

## Example and checks

`examples/hello/green` is an application-owned Babashka launcher, using the
built-in babashka.cli parser. Its create graph forks uname and an embedded
write/verify workflow, then joins at check. It defines reporting and dry-run
advice itself. Configuration defaults to green.yml in the caller's directory;
-f selects another file. GREEN_LIB_ROOT is only the example's working-tree
override. Keep subprocess tests isolated in temporary directories.

```sh
bb test
clojure -X:test
clojure -T:build jar
bb docco
```

Babashka's test task explicitly requires and runs namespaces in bb.edn;
update both lists when adding/removing test namespaces. JVM discovery uses
`test` and `examples/hello/test`. Both test runners require bb on PATH for the
launcher integration tests. The source-tour generator is Babashka-only.

## Documentation

`index.html` is the specification and GitHub Pages landing page. Preserve its
stable title, GA4 measurement ID G-4VKP1WY4QJ with page_title equal to the
decoded title, and the Rybbit snippet at
https://rybbit.getcolors.ai/api/script.js with data-site-id 9fb9c41a6d49.
Keep its design aligned with the other SDK pages. Update tasks/docco.clj notes
and regenerate docco.html with bb docco; never hand-edit the generated tour.
Ignore dist/ as generated output. CLAUDE.md is an existing symlink to this file.
