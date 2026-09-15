# Green Kubernetes controller handoff

## Result

Implemented the [plan](PLAN.md) in the Green repository. The new
`green.kubernetes` namespace calls registered Clojure package functions and Green
workflows directly. Each package owns its custom resource type. Existing packages
and their launchers have not been migrated.

The controller supports polling, periodic drift repair, manual request tokens,
running/stopped desired state, suspension, retries, profile identity checks,
optimistic status updates, finalizers, and protected destroy/retain policies.
Bounded workers share a process-wide lock per infrastructure state identity.

## Files to read

- [Controller API and package contract](docs/kubernetes.md)
- [Controller implementation](src/green/kubernetes.clj)
- [kubectl transport](src/green/kubernetes/client.clj)
- [Local example and commands](examples/kubernetes/README.md)
- [Example package workflows](examples/kubernetes/src/colors/local_service.clj)
- [Installation workflow](examples/kubernetes/bootstrap)
- [Integration checks](examples/kubernetes/integration.py)

The `LocalServiceDeployment` example owns a disposable Deployment running
`registry.k8s.io/pause:3.10`. Its message is stored in the pod template. It exposes
no network service and uses no cloud credentials. `colors.yml` and the example
custom resource carry equivalent package settings.

## Verification on 2026-09-15

| Check | Result |
| --- | --- |
| `bb test` | Passed, 139 tests and 560 assertions. |
| `clojure -X:test` | Passed, 139 tests and 560 assertions. |
| `clojure -T:build jar` | Passed. |
| Docker build | Passed on ARM64, Babashka 1.12.218 and kubectl v1.37.0. |
| `integration.py --host` | Passed against the host controller. |
| `integration.py` | Passed against the Docker controller and namespace-scoped service account. |
| Bootstrap workflow | build/create/check/delete passed; uninstall refused while instances existed. |
| Source checks | `git diff --check`, shell syntax, and Python parsing passed. |

The existing Ansible inventory parsing check skipped because `ansible-inventory`
is not installed. OpenTofu was available; the existing local workflow tests ran.
No remote cluster or cloud resources were used. Git synchronization and the
requested publication to main are the remote repository operations.

Integration verified schema rejection, immutable profile, initial readiness,
configuration updates, burst manual requests, drift repair, stop/start,
suspend/resume, ownership failure and retry recovery, controller restart,
protected deletion, destroy completion, and retain behavior. Unit tests cover
cross-type profile contention, bounded workers, stale generations, shutdown,
metadata conflicts, and incomplete deletion.

The first container run exposed kubectl's in-cluster fallback changing when a
request timeout is supplied. The transport now writes a private temporary
kubeconfig referencing the service account's CA and rotating token file. It does
not copy token contents or put credentials in arguments. A regression test and
the passing container integration cover the fix.

## Local environment left available

- Docker 29.1.3 is installed and running.
- kind v0.33.0 is installed at `/home/ubuntu/.local/bin/kind`.
- The dedicated `colors-green` cluster runs Kubernetes v1.37.0 with a loopback API.
- Its kubeconfig is `/tmp/green-kubernetes-kubeconfig`, owned by ubuntu, mode 0600.
- The context is `kind-colors-green`; the user's normal kubeconfig was not changed.
- The CRD and `colors-dev` namespace remain. The controller, its RBAC/service
  account, all test custom resources, and all test workloads were removed.
- The local controller image and pause image remain loaded in the kind node.
- Docker socket access in the current session requires `sudo -n docker`.
  ubuntu was added to the docker group for future sessions. Socket permissions
  remain restricted to root and that group.

Resume on this machine from the repository root:

```bash
export KUBECONFIG=/tmp/green-kubernetes-kubeconfig
examples/kubernetes/bootstrap create kind-colors-green
kubectl --context kind-colors-green apply -f examples/kubernetes/manifests/deployment.yml
kubectl --context kind-colors-green get localservicedeployments -n colors-dev -w
```

To test without creating the separate demo instance:

```bash
export KUBECONFIG=/tmp/green-kubernetes-kubeconfig
examples/kubernetes/bootstrap create kind-colors-green
python3 examples/kubernetes/integration.py
examples/kubernetes/bootstrap delete kind-colors-green
```

After code changes, rebuild and load the image, then restart the controller:

```bash
sudo -n docker build -t colors-green-controller:local -f examples/kubernetes/Dockerfile .
examples/kubernetes/load-image colors-green-controller:local
kubectl --context kind-colors-green rollout restart deployment/green-controller -n colors-dev
kubectl --context kind-colors-green rollout status deployment/green-controller -n colors-dev
```

The image helper addresses a Docker containerd-store issue encountered here:
`kind load docker-image` reported missing platform layers. Exporting and importing
the host platform explicitly worked. No image was pushed to a registry.

To remove only the dedicated test cluster when finished:

```bash
sudo -n /home/ubuntu/.local/bin/kind delete cluster --name colors-green
```

## Boundaries and next work

This is a local development implementation with one controller process. Recreate
and graceful shutdown support ordinary replacement, but do not fence a disconnected
old process or coordinate human/CI writers. Confirm old work has stopped before
replacement after an uncertain failure. Multi-process failover, distributed locks,
and durable workflow checkpoints remain future work.

The kubectl polling transport is for small local installations. It does not
implement watch recovery, controller health endpoints, metrics, or rich transport
error reporting. Unexpected identity/transport exceptions increment the runtime
`:errors` counter; raw exceptions are deliberately not stored or published.

Retained workloads keep the old owner UID. Recreating a resource does not adopt
them; explicit cleanup or a future adoption protocol is required. Credential
resolution for real infrastructure, package-specific production CRDs, and CRD
version migrations are not implemented. Green does not apply controller-host
`COLORS_PAR_*` overrides to custom resources.

Development used subagents for the controller, example/installer, local cluster
setup, package tests, and independent review. Changes are limited to this repo;
all infrastructure execution and verification were local.
