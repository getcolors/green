# Green Kubernetes controller

## Goal

Add `green.kubernetes`, a Clojure/Babashka control loop that calls registered
package functions and Green workflows directly. Develop and verify it entirely
against Docker and a dedicated local kind cluster. Existing packages and their
launchers remain outside this first implementation.

## Design

- Each package owns a separate CRD. The example is `LocalServiceDeployment`
  in `colors.getcolors.ai/v1alpha1`, backed by a local Kubernetes Deployment.
- `spec.config` holds package configuration. Common fields are `state`
  (`running` or `stopped`), `suspend`, `reconcileInterval`, and `deletionPolicy`
  (`Retain` by default or `Destroy`).
- Register package functions for validation, state identity, observation,
  convergence, and deletion. Workflow callbacks invoke `green.workflow/run`.
- Use a bounded worker pool and a queue coalesced by resource identity. All
  operations acquire a process-wide lock keyed by package-defined infrastructure
  state identity plus resolved profile, including operations across types.
- Start with one controller process. Poll and reread resources to discover
  changes, manual request annotations, and periodic drift checks. Polling is an
  explicit first-version choice, avoiding a fragile partial watch implementation.
- Track generation, request acknowledgement, conditions, retry timing, and
  immutable resolved profile. Keep credentials and arbitrary opts out of status.
- Use optimistic concurrency for metadata/status writes. Add a finalizer before
  effects; honor protection before destroy; retain does not destroy resources.
- Suspension prevents new convergence; an active run finishes. Deletion remains
  governed by deletion policy and protection. Do not cancel infrastructure work
  merely because a newer generation arrives.
- No distributed failover or exactly-once claim. A controller must be confirmed
  stopped before replacement. Human/CI concurrency is outside this initial lock.

## Implementation

1. Implement the callback-based controller and deterministic lifecycle tests.
2. Implement a kubectl JSON transport with explicit context and request timeouts.
3. Add the local example, strict CRD, standalone Clojure entry point, Docker
   image, namespace-scoped RBAC, and a one-replica Recreate controller Deployment.
4. Implement installation as a Green workflow with build/create/check/delete.
   Uninstall preserves CRDs and refuses while managed custom resources exist.
5. Add repeatable kind integration verification. Test apply, update, manual
   request, drift repair, stop/start, suspend/resume, controller restart,
   validation, retries, deletion protection, destroy, and retain. Use unit tests
   for deterministic contention and stale-generation races.
6. Run Babashka and JVM tests, build the Docker image, and execute local cluster
   checks. Record exact results and limits in `HANDOFF.md`.
7. Review the diff, commit all task files, and push `main`.

## Acceptance

The example autonomously restores declared local infrastructure state and uses
the same package workflow callbacks for lifecycle execution. Duplicate triggers
cannot overlap for one state identity within the supported controller process.
Status cannot claim a newer generation succeeded on an older run. No cloud
resources or remote Kubernetes contexts are used. Installation and cleanup are
documented and reproducible.

## Progress

- Repository synchronized with `git pull --ff-only origin main`; clean main.
- Implemented the controller, explicit-context kubectl client, separate example
  CRD, Clojure package, Docker image, and Green bootstrap workflow.
- Added API documentation, example instructions, and a Docker image import helper.
- Babashka and JVM suites both passed: 139 tests, 560 assertions. The existing
  Ansible inventory parser check skipped because `ansible-inventory` is absent.
- Built the library jar and the ARM64 Docker controller image successfully.
- Passed host-controller integration and the full container-controller integration
  on kind v0.33.0 / Kubernetes v1.37.0 / Docker 29.1.3.
- Verified bootstrap build/create/check/delete and refusal to uninstall while
  custom resources remain. Final uninstall preserved the CRD and namespace.
- Fixed issues found by tests/review: in-cluster authentication with request
  timeouts, deletion UID/version preconditions, invalid-deletion polling,
  validation exception retries, and matching-but-not-ready observation.
- `HANDOFF.md` records the completed implementation, commands, and remaining
  boundaries. All planned implementation and local verification steps are complete.
