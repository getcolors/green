# Runtime resource scopes

Wrap a complete workflow in a caller-owned resource scope. Register finalizers
immediately after acquiring a process or resource. Capture the registration
function and runtime handles in closures; never put them in workflow opts,
serialized state, or reusable workflow results.

| Green | Red | Blue |
| --- | --- | --- |
| `green.scope/with-scope` | `red/scope` `withScope` | `blue.scope.with_scope` |
| `(fn [register!] ...)` | `async register => ...` | `async def body(register)` |
| `(register! :process cleanup)` | `register("process", cleanup)` | `register("process", cleanup)` |
| `(register! :resource cleanup)` | `register("resource", cleanup)` | `register("resource", cleanup)` |

The body returns its normal value. Finalizers run on success, thrown errors,
timeouts represented by returns or exceptions, and catchable cancellation.
Process finalizers run first, then resource finalizers; each phase runs in
reverse registration order. A process callback must stop and await its owned
work before returning. Resource cleanup can therefore safely stop a temporary
SSH agent after its users have stopped. Every callback is attempted; an existing
body exception takes precedence over cleanup errors. A cleanup failure fails an
otherwise successful scope. Registration after the body returns is rejected.

Await all spawned work inside the body. Workflow schedulers drain already
started siblings before propagating errors. Green drains running futures on
thread interruption; Blue cancels and drains asyncio siblings; Red waits for
all settled promises. Red cancellation is cooperative: abort dependent work
and await its completion or rejection rather than racing the scope against a
rejected promise. The scope itself does not install process-global signal
handlers, terminate arbitrary processes, or cancel unregistered work.

Green shields each cleanup callback in a future from caller interruption.
Blue shields the cleanup task from repeated cancellation. Finalizers must be
bounded by the owning resource's cleanup policy. No finalizer is guaranteed
after SIGKILL, power loss, or immediate process exit. Agent callers should also
use bounded identity lifetimes and explicit orphan handling.

Ansible removes all `COLORS_PAR_*` environment bindings before spawning its
child. Supply `env` for the scoped socket and `secret-env` / `secretEnv` /
`secret_env` for additional passphrase-binding names to remove. Explicit
overrides cannot reintroduce these names. Green generic process callers can
merge `green.process/secret-env-removals` into `:extra-env`; nil values remove
inherited variables. Generic provider processes retain their existing default
environment behavior.

Green captured subprocess supervision requires POSIX `kill`, `ps`, and either
util-linux `setsid` or Perl with its standard POSIX module (available on macOS).
It refuses to start captured commands without a session launcher. Each captured
command has its own process group, so cleanup can terminate descendants even
after their original parent exits. A child deliberately creating another
session can escape group cleanup; output-drain cleanup remains bounded.
