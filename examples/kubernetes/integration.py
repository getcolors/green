#!/usr/bin/env python3
"""Exercise the installed local controller. Requires only Python and kubectl.

Run after bootstrap create. --host skips controller Deployment restart/uninstall
checks when developing with the host launcher. Uses only kind-colors-green.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
CONTEXT = "kind-colors-green"
NAMESPACE = "colors-dev"
TYPE = "localservicedeployments.colors.getcolors.ai"
NAME = "integration-service"
OWNER = "colors.getcolors.ai/owner-uid"


def command(args, *, body=None, check=True):
    result = subprocess.run(args, input=None if body is None else json.dumps(body),
                            text=True, capture_output=True, timeout=150)
    if check and result.returncode:
        raise RuntimeError(f"Command failed: {args}\n{result.stderr}")
    return result


def kubectl(*args, body=None, check=True):
    return command(["kubectl", "--context", CONTEXT, "--request-timeout=20s",
                    "-n", NAMESPACE, *args], body=body, check=check)


def get(kind, name):
    out = kubectl("get", kind, name, "--ignore-not-found", "-o", "json").stdout
    return json.loads(out) if out.strip() else None


def patch(kind, name, value, check=True):
    return kubectl("patch", kind, name, "--type=merge", "-p", json.dumps(value), check=check)


def wait(label, predicate, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            print(f"PASS {label}", flush=True)
            return
        time.sleep(0.3)
    raise AssertionError(f"Timed out: {label}\n{json.dumps(get(TYPE, NAME), indent=2)}")


def ready(name=NAME):
    obj = get(TYPE, name)
    return (obj and obj.get("status", {}).get("phase") == "Ready"
            and obj["status"].get("observedGeneration") == obj["metadata"]["generation"])


def phase(value):
    return (get(TYPE, NAME) or {}).get("status", {}).get("phase") == value


def workload(profile):
    return "colors-" + hashlib.sha256(profile.encode()).hexdigest()[:20]


def resource(name):
    return {"apiVersion": "colors.getcolors.ai/v1alpha1", "kind": "LocalServiceDeployment",
            "metadata": {"name": name, "namespace": NAMESPACE},
            "spec": {"state": "running", "reconcileInterval": "2s", "suspend": False,
                     "deletionPolicy": "Retain", "config": {"profile": name,
                     "replicas": 1, "message": "integration", "compute-prevent-destroy": True}}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", action="store_true")
    args = parser.parse_args()
    # Refuse remote endpoints even if a kubeconfig uses our expected context name.
    cfg = json.loads(kubectl("config", "view", "--minify", "-o", "json").stdout)
    server = cfg["clusters"][0]["cluster"]["server"]
    if not (server.startswith("https://127.0.0.1:") or server.startswith("https://localhost:")):
        raise SystemExit("Integration requires a loopback API server for kind-colors-green")
    for name in [NAME, NAME + "-retained"]:
        if get(TYPE, name) or get("deployment", workload(name)):
            raise SystemExit(f"Refusing to overwrite existing integration resource: {name}")

    invalid = resource(NAME)
    invalid["spec"]["config"]["replicas"] = -1
    assert kubectl("apply", "-f", "-", body=invalid, check=False).returncode != 0
    print("PASS CRD rejects invalid configuration", flush=True)
    kubectl("apply", "-f", "-", body=resource(NAME))
    wait("initial convergence and readiness", ready)
    target = workload(NAME)
    uid = get(TYPE, NAME)["metadata"]["uid"]
    assert "colors.getcolors.ai/infrastructure" in get(TYPE, NAME)["metadata"]["finalizers"]

    assert patch(TYPE, NAME, {"spec": {"config": {"profile": "different"}}}, check=False).returncode != 0
    print("PASS profile identity is immutable", flush=True)
    if not args.host:
        result = command([str(ROOT / "examples/kubernetes/bootstrap"), "delete", CONTEXT], check=False)
        assert result.returncode != 0
        assert get("deployment", "green-controller")
        print("PASS uninstall refuses managed instances", flush=True)

    patch(TYPE, NAME, {"spec": {"config": {"replicas": 2, "message": "updated"}}})
    wait("configuration update", ready)
    assert get("deployment", target)["spec"]["replicas"] == 2
    for i in range(6):
        token = f"integration-{time.time_ns()}-{i}"
        kubectl("annotate", TYPE, NAME, f"colors.getcolors.ai/reconcile-request={token}", "--overwrite")
    wait("burst manual request acknowledgement", lambda: ready() and
         get(TYPE, NAME)["status"].get("lastHandledRequest") == token)

    patch("deployment", target, {"spec": {"replicas": 0}})
    wait("periodic drift repair", lambda: get("deployment", target)["spec"]["replicas"] == 2 and ready())
    patch(TYPE, NAME, {"spec": {"state": "stopped"}})
    wait("stop", ready)
    assert get("deployment", target)["spec"]["replicas"] == 0
    patch(TYPE, NAME, {"spec": {"state": "running"}})
    wait("start", ready)
    patch(TYPE, NAME, {"spec": {"suspend": True}})
    wait("suspension acknowledged", lambda: phase("Suspended"))
    patch("deployment", target, {"spec": {"replicas": 0}})
    time.sleep(3)
    assert get("deployment", target)["spec"]["replicas"] == 0
    patch(TYPE, NAME, {"spec": {"suspend": False}})
    wait("resume repairs drift", lambda: ready() and get("deployment", target)["spec"]["replicas"] == 2)

    patch("deployment", target, {"metadata": {"annotations": {OWNER: "foreign-owner"}}})
    kubectl("annotate", TYPE, NAME, f"colors.getcolors.ai/reconcile-request=failure-{time.time_ns()}", "--overwrite")
    wait("ownership failure reported", lambda: phase("Failed"))
    wait("failure retried with backoff", lambda: get(TYPE, NAME).get("status", {}).get("retryCount", 0) >= 2)
    patch("deployment", target, {"metadata": {"annotations": {OWNER: uid}}})
    wait("recovery after transient failure", ready)

    if not args.host:
        kubectl("scale", "deployment/green-controller", "--replicas=0")
        wait("previous controller stopped", lambda: not json.loads(
             kubectl("get", "pods", "-l", "app=green-controller", "-o", "json").stdout)["items"])
        patch("deployment", target, {"spec": {"replicas": 0}})
        kubectl("scale", "deployment/green-controller", "--replicas=1")
        wait("controller restart repairs drift", lambda: ready() and get("deployment", target)["spec"]["replicas"] == 2)

    patch(TYPE, NAME, {"spec": {"deletionPolicy": "Destroy"}})
    kubectl("delete", TYPE, NAME, "--wait=false")
    wait("destruction protection blocks deletion", lambda: phase("Blocked"))
    assert get("deployment", target)
    patch(TYPE, NAME, {"spec": {"config": {"compute-prevent-destroy": False}}})
    wait("destroy and finalizer completion", lambda: get(TYPE, NAME) is None and get("deployment", target) is None)

    retained = NAME + "-retained"
    kubectl("apply", "-f", "-", body=resource(retained))
    wait("retained example ready", lambda: ready(retained))
    kubectl("delete", TYPE, retained, "--wait=false")
    wait("retain removes only custom resource", lambda: get(TYPE, retained) is None)
    assert get("deployment", workload(retained))
    kubectl("delete", "deployment", workload(retained), "--wait=true", "--timeout=60s")
    print("PASS all local integration checks; test resources removed", flush=True)


if __name__ == "__main__":
    main()
