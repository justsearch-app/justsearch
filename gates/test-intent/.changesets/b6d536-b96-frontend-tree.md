---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b96-frontend-tree
---

# Recorded process termination in dev tools

All changed checks are script self-tests under `scripts/`, which
`docs/reference/testing/test-intent.md` explicitly excludes from the product-test gate.
No Java, frontend-module, Rust, build-selection or governance-tooling file changes.
The empty gate entry list reflects that scope; the intent and execution record below still
require the orchestrator's independent verifier. The builder supplies no acceptance record.

```json
{ "entries": [] }
```

Intent source: **delegated-choice**, label **agent choice**. Delegation quote:
"do whatever you think is best. your working autonomously". Delegation location:
owner turn 2026-10-11T16:05Z, archived in
`F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94`.
Scope: task t-20261005-b6d536, process ownership and exit accounting.

Choices under that delegation:

- A frontend tree stop is successful only after an identity-checked tree termination and root
  exit. An unrecorded birth, reused PID, already-exited root with no tree proof, partial helper
  result, or unobserved exit is incomplete. Keep the 5,000 ms synchronous termination bound,
  3,000 ms default exit wait, boolean return contract and existing caller decisions.
- The Engine fallback, agent-spawn reaper, worktree frontend and legacy cleanup terminate the
  recorded birth through the same handle. Unsupported detached-PID termination fails closed.
- Descendants whose birth is unreadable or changes after enumeration remain untouched and make
  the tree outcome partial. A child older than its current parent proves a broken ancestry edge;
  that child and everything below it remain outside the kill scope.
- Manual remedies carry the birth, or withhold a termination command when none is recorded.

Adaptation, follow-up to the merge into b6d536/integration (fix(b6d536): end every process a dev tool
starts, without parent links). Decided by the orchestrator under the owner's delegation (same
delegation quote, location and scope as above, label agent choice): keep the identity-only rule (never
find processes through parent PIDs, names or PID alone) and close the tree-stop gaps with job objects.
The frontend tree stop of this lane is replaced as follows. serve-worktree-fe.cjs starts
`node <ui-web>/node_modules/vite/bin/vite.js` directly (absolute node, shell:false, no npx or cmd shim)
through `scripts/dev/lib/job-run.cjs`: a PowerShell host (`job-host.ps1` with `job-run.cs`) creates the
root suspended inside a new job whose only limit is KILL_ON_JOB_CLOSE, so the kernel adds every
descendant at creation, and records the host, the root and every member by PID plus creation time.
A failed readiness gate, a signal or the script's exit stops the run with `stop-job-run.ps1`, which
ends the host only when its handle shows the recorded birth (the job's only handle closes, so Windows
ends every member) and then verifies each recorded identity has ended; a PID showing another birth is
never touched. The Engine fallback, reaper, worktree-frontend register and legacy cleanup choices above
are unchanged; the registered port owner is still the recorded identity the reaper may end. None of
this lane's checks changed (frontend-tree 15/15 and 861-w3-serve-worktree-fe pass unmodified), so the
gate entry list stays empty. The new script check `scripts/dev/test-dev-runner-job-stops.mjs` runs the
real serve-worktree-fe.cjs with a CPU-only Vite fixture that never listens and starts a helper; after the
readiness gate's stop no identity the fixtures or the job record named survives, and a same-name
node.exe outside the run is alive. It fails by assertion on d52ee5e7a (Vite and its helper survive)
and passes after.

`scripts/dev/test-dev-runner-frontend-tree.mjs` executes the production frontend closure with
deterministic PID reuse and partial-result interleavings, the Engine fallback and its caller,
the reaper/worktree termination boundaries, unsupported-platform refusal, and printed remedies.
It starts no processes. On a164833f5, 13 of 14 checks fail by assertion; the unchanged exit-wait
preservation case passes. All 14 pass after the repair.

`scripts/dev/test-dev-runner-tree-handles.mjs` drives the real Windows termination helper against
only its own recorded sleepers, injecting process-table snapshots to force ancestry and birth
ambiguities. On a164833f5, the two incomplete-accounting checks and the legacy cleanup check fail
by assertion; the root-birth and reused-parent ancestry preservation checks pass. All five pass
after. The wrapper explicitly forwards PowerShell's script exit code.

Existing reaper checks retain their eligibility, refusal, confirmation, retention and retirement
oracles. Their fake termination result now supplies the helper's JSON outcome; the command
assertion requires the recorded birth and excludes tree scope. Matrix/manual-remedy assertions
follow the choices above. The worktree teardown fixture's dependency list includes the new small
termination module and PowerShell helper; its process cleanup fixtures are unchanged.

Execution logs (outside the repository):
`F:/agent-sandbox/design-b6d536/codex/b96-frontend-tree/` contains
`before-focused-final.log`, `after-focused-final.log`, `before-handles-final.log`,
`final-dev-runner.log`, conformance logs, reaper logs, `exits.txt` and `report.md`.
Fail-before checks ran in a private detached worktree at a164833f5. Every Node run loaded the
provided identity guard from a private copy. No D4 product evidence is claimed: the repository's
evidence runner supports Java, frontend-module and Rust checks, not these script self-tests.

## Acceptance records

Pending independent verification by the orchestrator.
