---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b98-proc-devtools
---

# Process ownership and exit accounting in dev tools

The changed checks are script self-tests under `scripts/`, excluded from the product-test
gate by `docs/reference/testing/test-intent.md`. The frontend change is a development launcher,
not frontend test code or test selection. There are no Java, Rust, governance tooling or build
configuration changes. No supported D4 product evidence runner is claimed for these Node and
PowerShell script checks. This record awaits independent verification; the builder is not its
acceptor.

```json
{ "entries": [] }
```

Intent source: **delegated-choice**, label **agent choice**. Delegation quote:
"do whatever you think is best. your working autonomously". Delegation location:
owner turn 2026-10-11T16:05Z, archived in
`F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94`.
Scope: task t-20261005-b6d536, process ownership and exit accounting.

Choices within that delegation:

- Root exit and closed ports cannot settle a partially terminated process tree. Keep root and
  descendant outcomes through both actuator adapters, retain uncertainty in the run record across
  repeated stop attempts, block cleanup/takeover, and count only confirmed root terminations.
- A delayed identity lookup may establish ownership only while the same spawned incarnation is
  still alive. Bind run-record publication to the incarnation and state object, not just its PID.
  Apply the same rule to frontend capture and initial/ready exit-census capture.
- A fallback HTTP shutdown requires a matching recorded backend birth. The current Engine only
  logs mismatched HTTP incarnations, so adding a request field cannot establish ownership.
- Keep unknown parent links and every unresolved descendant below them. Pin verified parent
  handles, stop and wait for the proven processes, and re-enumerate at most three times. A scan
  budget exhausted while finding new descendants is partial; only a scan with no new descendants
  can complete the tree. Use linear collections for the walk and result accumulation.
- The dev-all launcher, token probe, benchmark telemetry and four Node cleanup fixtures must
  carry spawn-time identities through the termination handle. The non-distinctive Engine fallback
  is reported as `identity-fallback`; retain the old ledger ending alias for compatibility.

`scripts/dev/test-dev-runner-process-races.mjs` contains 16 deterministic assertion checks through
the production closures, stop operation, actuator wrappers and cleanup calls. All 16 fail by
assertion on 28ac1ab35 and pass on the candidate. No OS process is stopped by these simulations.

`scripts/dev/test-dev-runner-tree-handles.mjs` exercises the real Windows helper against its own
recorded fixture processes, with injected snapshots for otherwise nondeterministic races. Six
new checks fail by assertion before: unresolved descendants below an unknown ancestor, a real
partial stop through the persisted-run boundary, a late child, exhausted enumeration, and the
probe/telemetry cleanup races. Five existing checks preserve root-reuse refusal, unknown-birth
refusal, descendant-reuse refusal, broken-ancestry exclusion and legacy-cleanup refusal.

The existing `missingBirthNeverEstablishesOwnership` case now also supplies a live backend PID
and an API port and asserts that graceful shutdown was never called: assertion failure before,
pass after. The terminal-children fixture explicitly supplies the matched backend identity and
injected lookup required to reach its existing graceful-shutdown observation; all original
assertions are unchanged and pass before and after.

The four Node fixtures keep their existing product oracles. Three now capture a cleanup identity
when spawning their sleeper; the frozen-runner cleanup uses the run's recorded identity. The new
race checks prove that each cleanup refuses a replacement. Other ChildProcess cleanup in these
suites remains covered by the required external identity guard during execution.

Fail-before runs use a detached worktree at 28ac1ab35. Logs and the final validation/limitations
record are under `F:/agent-sandbox/design-b6d536/codex/b98-proc-devtools/`, including
`before-races-final.log`, `candidate-races-final.log`, `before-tree-final.log`,
`candidate-tree-final.log`, `before-live-missing-birth.log`, `candidate-live-missing-birth.log`,
the dev-runner and conformance suite logs, frontend suite/typecheck logs, and `report.md`.
No benchmark, live dev stack, llama-server or GPU workload is run by these checks.

## Acceptance records

Pending the orchestrator's independent verifier.
