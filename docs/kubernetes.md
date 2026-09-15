# Kubernetes package contract

`green.kubernetes` is an optional controller library. It calls Clojure package
functions directly. It does not execute skill documents, launch Jobs, or load
code selected by a custom resource. Register trusted packages in the controller
program. Each package owns a distinct namespaced CRD.

## Register a package

```clojure
(require '[green.kubernetes :as k8s]
         '[green.kubernetes.client :as client]
         '[green.workflow :as wf])

(def transport (client/kubectl-client {:context "kind-colors-green"}))

;; Package-specific functions and workflows are supplied by your application.
(def package
  {:resource {:group "colors.getcolors.ai" :version "v1alpha1"
              :plural "exampledeployments" :kind "ExampleDeployment"}
   :validate validate-config
   :identity state-identity
   :observe observe-infrastructure
   :converge #(wf/run converge-workflow %)
   :delete #(wf/run delete-workflow %)})

(def runtime
  (k8s/start!
    (k8s/controller {:packages [package] :client transport
                     :namespace "colors-dev" :workers 2 :poll-ms 500})))

;; Waits for active work. Does not cancel infrastructure operations.
(k8s/stop! runtime)
```

`controller` constructs a configuration value without starting threads.
`start!` starts polling and bounded workers. `reconcile!` reconciles a named
resource synchronously, using the same locking and lifecycle logic.

## Callback inputs and results

Every package callback receives the keywordized `spec.config` map with:

- `:profile`, defaulting to `<namespace>--<name>`.
- `:green/event`, `:create` for running state, `:stop` for stopped state, or
  `:delete` during deletion. The package selects its concrete start/stop actions.
- `:green.kubernetes/resource`, the complete custom resource snapshot.

The controller does not overlay host `COLORS_PAR_*` values. Packages needing
credentials must resolve them explicitly and keep them out of configuration,
status, and logs. The local example needs none.

| Callback | Result |
| --- | --- |
| `:validate` | Empty collection or nil for valid configuration; a collection of errors otherwise. Error text is not copied to status. |
| `:identity` | Stable immutable value identifying the backend and profile. Equal identities share a process-wide lock, even across types or controller instances. Prefer a vector of strings. |
| `:observe` | `{:exists? boolean :matches? boolean :ready? boolean}` describing actual infrastructure. Must be read-only. |
| `:converge` | Green outcome map. Nonzero `:green/exit` or an exception causes a retry. |
| `:delete` | Green outcome map. Must be repeatable, including partial cleanup. Finalization waits for observation to report absence. |

Ready means the requested state is verified, including stopped state. Matching
configuration with `:ready? false` waits and retries observation without repeating
convergence. Callbacks own their timeouts and subprocess cleanup. Reconciliation
does not promise workflow checkpoint persistence or exactly-once execution.

## Custom resource fields

Package CRDs define the precise schema of `spec.config`. Common fields are:

| Field | Default | Meaning |
| --- | --- | --- |
| `spec.state` | `running` | `running` or `stopped`. |
| `spec.suspend` | `false` | Prevent new convergence. Active work finishes; deletion policy still applies. |
| `spec.reconcileInterval` | `60s` | Positive duration using `ms`, `s`, `m`, or `h`. |
| `spec.deletionPolicy` | `Retain` | Retain infrastructure or `Destroy` it before removing the finalizer. |
| `spec.config.compute-prevent-destroy` | Package-defined; example defaults to `true` | A true value blocks Destroy. Packages should validate and default this field explicitly. |

The `colors.getcolors.ai/reconcile-request` annotation accepts an opaque request
token. A changed token schedules a check and respects suspension. An unchanged
manifest does not force execution. Polling detects updates within `poll-ms` plus
queue delay; the interval controls periodic infrastructure observation.

The example CRD prevents changes to explicit profile identity with validation.
The controller also persists profile and a state-identity hash before effects
and refuses changes. Default names are not a global infrastructure namespace:
packages must include the actual backend boundary in their identity function.

Validation errors remain Invalid until configuration or the request token changes.
Validator exceptions retry with a generic failure reason. Configuration changes
and manual requests reset retry backoff.

## Status and deletion

Status reports `phase`, `conditions`, `observedGeneration`, `profile`,
`stateIdentity`, `lastHandledRequest`, `lastReconcileTime`, `nextReconcileTime`,
`retryCount`, and `deletionObserved`. Only controller-selected fields are published. Error reasons
are generic and do not contain exception messages or the workflow opts map.

Writes carry the snapshot's `resourceVersion`. A concurrent spec edit prevents
an old result from acknowledging new configuration. The next pass rereads the
resource and observes infrastructure before deciding whether effects are needed.

The controller adds `colors.getcolors.ai/infrastructure` before effects.
Destroy runs under the same identity lock as convergence. Retain removes only
this finalizer. Packages must avoid owner references that would cause Kubernetes
garbage collection to delete retained infrastructure. The example instead checks
an ownership UID annotation and uses UID/version preconditions when deleting.

## Supported execution boundary

This version supports one controller process. A Recreate Deployment and graceful
shutdown help ordinary local upgrades, but do not prevent simultaneous writers
during a node failure or network partition. Confirm the old controller and its
operations have stopped before replacement. Human/CI runs and controllers in
other processes do not share this lock. Distributed leases and fencing are not
implemented.

Polling invokes kubectl with explicit context or in-cluster authentication.
It is intended for local development. A watch client, pagination, multi-cluster
coordination, durable workflow recovery, and production credential management
are separate future work.

See the [working local example](../examples/kubernetes/README.md) and
[implementation plan](../PLAN.md).
