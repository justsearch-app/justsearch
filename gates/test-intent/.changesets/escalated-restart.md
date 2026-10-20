---
schema: test-intent.v1
task: escalated-restart
author-role: builder
author-session: codex-escalated-restart-builder
---

# Supervisor fixture startup on hosted Windows

Owner source: the builder assignment in this session: "Windows CI fails only on real defects."
It requests a startup regression that fails before by assertion, preservation of escalation when
an Engine never publishes its port, and a harness-only repair unless product evidence requires more.

Only Node harness code under `scripts/supervisor-conformance/` changes. These scripts are outside
the test-intent gate's product-test scope. No contract scenario, assertion, product default or
restart/escalation policy is removed or relaxed. Ordinary fixture startup receives 60 seconds per
phase, with an observation window covering discovery, readiness and runner setup. Explicit case
startup deadlines are preserved. Lifecycle observation also includes this fixture setup allowance
before its existing 40-second recovery allowance; the supervisor enforces the same fault deadlines.

The new `startup-budget.test.mjs` runs the real adapter and supervisor against the existing fake
Engine modes. Its delayed-manifest check asserts that an 11-second first start reaches RUNNING
without spending an incarnation or restart. The no-port check withholds the manifest until long
after the startup deadline and requires both the shutdown request and forced kill. It exercises
the ordinary default startup budget, not a shorter test override.

Baseline: f003a5c6476776ed2e56d9e0882f8bbaf55fc731, shared detached clone under the task's TMPDIR,
with only the candidate check overlaid and the Linux bound-exec helper compiled as in CI.
Direct logs and the measurement report are under `tmp/escalated-restart/`.

The requested execution-evidence producer was invoked with `--check
scripts/supervisor-conformance/startup-budget.test.mjs --before f003a5c6476776ed2e56d9e0882f8bbaf55fc731`.
It exits 2: `no runner for this path (JVM test source set, ui-web test file, or shell crate .rs)`.
It cannot produce evidence for a Node script check. Extending governance tooling or relocating a
supervisor test into a product suite would expand this builder assignment; that decision is left
to the orchestrator. No execution-evidence JSON or independent acceptance is claimed.

```json
{ "entries": [] }
```
