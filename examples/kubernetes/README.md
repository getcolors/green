# Green on a local Kubernetes cluster

This example registers a `LocalServiceDeployment` package with `green.kubernetes`.
The controller calls a Clojure package workflow directly. Its infrastructure is
a disposable Deployment running the Kubernetes pause image; `message` is recorded
on the pod template. It exposes no network service and needs no cloud credentials.

`colors.yml` and `manifests/deployment.yml` contain equivalent package settings.
The custom resource adds reconciliation policy. Each package has its own CRD;
there is no runtime package selector or arbitrary code loading.

## Prerequisites

Install Docker, kind, kubectl, and Babashka. Commands below run from the Green
repository root and target the dedicated `kind-colors-green` context explicitly.
Use Kubernetes 1.37 for the local cluster to match the image's kubectl version.
Only one controller process may manage these resources. Stop a host controller
before installing the container controller, or vice versa.

After rebuilding and loading the same `:local` image tag, run
`kubectl --context kind-colors-green rollout restart deployment/green-controller -n colors-dev`.
The Recreate strategy stops the old pod before starting its replacement.
Applying an unchanged controller manifest does not restart it.

## Container controller

```bash
kind create cluster --name colors-green --image kindest/node:v1.37.0
docker build -f examples/kubernetes/Dockerfile -t colors-green-controller:local .
kind load docker-image colors-green-controller:local --name colors-green
docker pull registry.k8s.io/pause:3.10
kind load docker-image registry.k8s.io/pause:3.10 --name colors-green
examples/kubernetes/bootstrap create kind-colors-green
kubectl --context kind-colors-green apply -f examples/kubernetes/manifests/deployment.yml
kubectl --context kind-colors-green get localservicedeployments -n colors-dev -w
```

If Docker's containerd image store causes `kind load` to report missing platform
layers, use `examples/kubernetes/load-image IMAGE` for each image instead. This
imports the host platform into the dedicated kind node. It uses `sudo -n docker`
when the current session cannot access the Docker socket.

To keep kubeconfig separate, create the cluster with
`--kubeconfig /tmp/green-kubernetes-kubeconfig` and export
`KUBECONFIG=/tmp/green-kubernetes-kubeconfig` for subsequent commands.

The bootstrap script implements installation as a Green workflow. `build` prints
the CRD/controller manifests without accessing the cluster; `create` installs and
waits; `check` checks the rollout; `delete` removes the controller and its RBAC.
Uninstall refuses while any instance of this type remains and preserves the CRD,
namespace, and retained infrastructure.

## Host controller during development

```bash
kubectl --context kind-colors-green apply -f examples/kubernetes/manifests/crd.yml
kubectl --context kind-colors-green create namespace colors-dev
examples/kubernetes/controller kind-colors-green
```

Run the apply command for `manifests/deployment.yml` from a second terminal.
The controller uses polling, bounded workers, and a process-wide lock per package
state identity. It does not use Jobs or run package skill instructions.

## Lifecycle

```bash
# Desired configuration update
kubectl --context kind-colors-green patch lsd local-service -n colors-dev \
  --type=merge -p '{"spec":{"config":{"replicas":2}}}'

# Request an immediate check
kubectl --context kind-colors-green annotate lsd local-service -n colors-dev \
  colors.getcolors.ai/reconcile-request="$(date -u +%s%N)" --overwrite

# Maintain stopped infrastructure (zero replicas)
kubectl --context kind-colors-green patch lsd local-service -n colors-dev \
  --type=merge -p '{"spec":{"state":"stopped"}}'

# Resume running infrastructure
kubectl --context kind-colors-green patch lsd local-service -n colors-dev \
  --type=merge -p '{"spec":{"state":"running"}}'

# Pause automatic management; an active workflow finishes
kubectl --context kind-colors-green patch lsd local-service -n colors-dev \
  --type=merge -p '{"spec":{"suspend":true}}'

kubectl --context kind-colors-green patch lsd local-service -n colors-dev \
  --type=merge -p '{"spec":{"suspend":false}}'
```

Manual configuration edits should also be recorded in the manifest. Status reports
the processed generation, conditions, retry timing, and manual request acknowledgement.
A matching Deployment is not ready until Kubernetes reports its rollout complete.

The profile defaults to `<namespace>--<name>` when omitted. Explicit profile values
are immutable, including adding or removing the field after creation. Workload
names are a hash of the resolved profile. A UID annotation establishes ownership:
a second resource with the same profile cannot adopt the first resource's workload.
Deleting and recreating a retained resource deliberately does not adopt it either.
No owner reference is attached, so Kubernetes garbage collection cannot bypass Retain.

## Deletion

The example defaults to `deletionPolicy: Retain`. Deleting its custom resource
leaves the workload in place. To destroy disposable infrastructure, explicitly set
Destroy and lift the protection in that resource:

```bash
kubectl --context kind-colors-green patch lsd local-service -n colors-dev \
  --type=merge -p '{"spec":{"deletionPolicy":"Destroy","config":{"compute-prevent-destroy":false}}}'
kubectl --context kind-colors-green delete lsd local-service -n colors-dev --timeout=120s
examples/kubernetes/bootstrap delete kind-colors-green
kind delete cluster --name colors-green
```

This prototype deliberately does not overlay `COLORS_PAR_*` from the controller
host. Its custom resource is the configuration authority. Existing package
credential and destruction conventions are unaffected.

## Integration verification

With the container controller installed:

```bash
python3 examples/kubernetes/integration.py
```

Use `--host` when running the controller from your checkout. The script creates
its own disposable resource and tests convergence, drift, stop/start, suspension,
manual requests, deletion protection, and retain/destroy behavior. Container mode
also exercises controller restart and the bootstrap uninstall guard.

## Limits

One process is supported, with a Recreate Deployment strategy. This is not a
fenced distributed lock: confirm the previous controller and its subprocesses have
stopped before replacing it after a failure. Human and CI runs are not coordinated.
The polling transport invokes kubectl with bounded requests; it is intended for
local development, not large installations. Retained workload adoption, upgrades
across CRD versions, and cloud packages are outside this example.
