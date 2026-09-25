# Lane F continuation brief

Updated 2026-09-25 after the owner re-cut and improvement merge. This is the successor's
entry point; [handoff](handoff.md) owns current evidence and
[stage D1](stages/D1.md) owns acceptance. Historical checkpoint logs are optional
background, not a work queue. This brief adds no acceptance waiver.

**Current owner re-cut (2026-09-24):** Finish D1-9 streaming correction and
proofs first. D1/D2 scope remains intact. E's paired branch/main gate contains
seven groups only: quality and workflow fixture, search/agent response time
under bulk indexing, indexing speed, memory with an owner-duration no-crash
soak, crash recovery with no orphans, graceful/forced hang, and dead-Engine
upgrade. Every other design §16 row is one-sided D1/D2 feature acceptance;
reuse installed and real-model proof only where it covers a clause and name
the rest. Minimum-spec, other-OS, representative-change and collector
comparisons are conditional; G1 is default unless a response-time group fails.
Merge `origin/main` at every checkpoint. The main-development sequencing
re-cut condition is retired. [Design](design.md#16-what-must-be-measured-and-the-gate-for-flipping-the-default),
[E runbook](stages/E.md), [D1 map](stages/D1.md#6-section-16-rows-d1-must-leave-exercisable),
[D2 map](stages/D2.md#6-section-16-rows-d2-must-leave-exercisable).

**Current D1-9 boundary:** `fc5b444d6` remains the last fully hosted runtime
checkpoint; six installed installer pointer/settings crash cuts and the
standard-model A/B file-mutation path passed there. `7f469d044` is the latest
pushed checkpoint: its hosted system integration and Windows native jobs passed,
but the manual-pointer test raced automatic cutover. The local test correction
is not yet hosted. [Handoff](handoff.md) names the exact artifacts.

**2026-09-25 gap batch:** the uncommitted D1-9/D1-11 gap decision retains a
nonterminal `COMPLETE_WITH_GAPS` row, publishes a candidate-bound gap hash,
restores A for an in-place wait, and requires a distinct HIGH/DURABLE webview
approval after Green drains. Serial `test`, compile/installDist, Spotless, PMD,
stress, UI typecheck/unit and generation checks passed locally. Installed
standard-model A answered a real vector query while B awaited acceptance;
hash-bound approval promoted that B and duplicate acceptance had no second
effect (`tmp/3477`). HEAD `27adef9f0` includes the escalation-policy and
docs-only improvement merges; the policy's two instruction checks passed and
the improvement merge changed no tested runtime source. The gap code remains uncommitted and
unhosted. Installed in-place recovery, no-file projection, positive
cancel/abandon and gap crash cuts are still open. The owner-authorized
improvement package `59da2bc4d` is merged at this batch boundary before the
next installed D1 round. Nothing here releases later D1/D2/E/F acceptance.

**2026-09-25 improvement boundary:** WP1's seven-point migration barrier and
shared operation handshake are implemented. The first installed standard-model
seed and live A/B watcher/accepted-write run with the named before-SWITCHING
hold passed at `tmp/3481`–`tmp/3482`, including STOP 0. The identity repeat
passed 20/20 fresh runs with zero XML failures at `tmp/3483`; after the
environment-access funnel correction, the exact-source repeat passed another
20/20 fresh runs, 20 XMLs, zero failures or errors at `tmp/3492`;
the current distribution's installed gap approval passed again at
`tmp/3485`–`tmp/3486`. The 358-task full serial stress/static/distribution
gate passed on the exact corrected source at `tmp/3489`; its first run found
and fixed one direct environment access at `tmp/3487`–`tmp/3488`. Hosted proof
is still due.
`build -x test` passed at `tmp/3490`; UI typecheck and 6,601 unit tests
passed, with unit output at `tmp/3491`. The installed distribution predates
only the raw environment funnel change, which retains identical lookup
semantics for the harness keys; the exact-source local gates cover it.
WP2 2a corrects `ui-settings` to version 4, binds eligible store rows to code
constants, and preserves the historical reconciliation token for released
updaters; the gate, 78 self-test assertions and 33 Rust updater tests pass.
WP5's design/E/F documentation corrections are present. [Handoff](handoff.md)
has exact proof and remaining gaps. The release-wrapper compatibility baseline
is still WP2 work before E; no later acceptance is waived.

## Adopted remaining-work order (2026-09-25)

The owner authorized [WP3](improvements/WP3-remaining-work-plan.md) after the
gap-decision batch. The improvement package is merged in this worktree. Proceed
in this order while retaining every D1/D2 acceptance item:

1. WP1 transition barrier, WP2 2a register/version-source correction, and WP5
   document corrections before the next installed D1 transition round.
2. D1-16 lifecycle harness skeleton with named pending/failing feature scenarios.
3. D1-9 and D1-8 no-file replay, cancel/abandon, live activation order and
   remaining crash cuts through that harness. Pull forward only the minimal
   D2-5 identity/projection port seam required by D1-9; D2-5 durability and
   deletion acknowledgement remain D2 acceptance.
4. D1-14/D1-13 gap and cancel backfill, installed floor-cap, A recompose and
   held native lease, then D1-12 full Flow B; land WP4 containment with this work.
5. The settled D1-4 connected publication/lifetime path below.
6. Interleave D1-15 reason codes, D1-6 restart-required/`core.restart-worker`
   retirement, D1-7 deadlines/recovery/escalation and D1-2/D1-10 hosted proof
   while installed or hosted rounds run; never run a second Gradle build.
7. D1-17 sweep and D1 feature acceptance; D2 profiles/library/ephemeral stores
   first, then gate/timings, durable write/cursors/MCP harness, and D2-10.
8. WP2 remaining release safety before E; then E's seven paired rows plus its
   downgrade/dead-Engine rounds, then F's sweep, report and conditional merge.

Before each integrated gate, check touched schema generators, store
recoverability, runtime manifest closure when runtime files change,
`regen-all --check`, and Spotless/PMD. If a batch exceeds twice its estimate or
an item needs a third substantive correction, record the plan delta and obtain
an independent refutation before the next correction. A timing failure after
WP1 is barrier evidence to investigate, not an automatic timeout increase.

## Assignment and authorization

Resume the existing Lane F migration autonomously through remaining D1/D2/E/F.
Use `F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify`, branch
`codex/lane-f-pr1`; preserve main and other sessions' files. Existing checkpoint
commits/pushes to [draft PR727](https://github.com/justsearch-app/justsearch/pull/727)
are authorized. Merge remains at stage F after its required acceptance. Routine
design and implementation decisions belong to the successor; no owner decision
is currently pending. Status questions and checkpoints do not end the assignment.

Runtime code baseline is `22800c842177321e22f430e5be339f13465e0242`.
Retrospective checkpoint `f38c9eebb` and this brief add documentation/evidence only.
Resolve actual HEAD and verify that content equivalence before reusing evidence;
record the actual tested revision, not an assumed future commit ID. A new docs-only
CI run is not a substitute for the runtime revision's required test selection.

C2 is accepted; D1/D2/E/F remain open. Do not repeat predecessor transcript
analysis or start another general agent-system redesign. The
[retrospective](evidence/workflow-retrospective-2026-09-22.md) is the supporting
analysis; apply the concrete work protocol below.

## Start checks and ownership

Read AGENTS.md, docs/llms.txt and the relevant canonical owners/skills. Verify
worktree, branch, status and current HEAD. Run world-state and official
quick_health before selecting shared resources; the last check was ABSENT with
no foreign run or inference orphan. That observation must be refreshed.
The worktree is held through2026-09-29; retain it while the lane remains active.
Verification run2466 was the next unused number at handoff; check retained tmp
artifacts before assigning it.

Root owns integration, all Gradle runs, stack lifecycle, settings/publication
authority, and changes crossing lifecycle owners. Only one build and one shared
stack may run. Freeze compiled sources during a build. If delegating, assign one
writer per file and a stable deliverable with proof; read-only exploration/review
can proceed independently. Check module access and actual owner identity before
dispatching an implementation task. Unsettled ownership returns to root.

## Prior candidate-context slice

The candidate-context correction is recorded in
[its plan and proof](evidence/D1/candidate-context-plan-2026-09-22.md) and the
handoff. Its previous execution sequence is closed; do not restart that slice
from the historical `2459`–`2464` inventory. Reuse hosted or installed proof
only while its source content and assumptions still match.

## D1-4 publication and lifetime contract (adopted batch 5)

The remaining solvable design has been settled by the preceding Astra task.
Read the [decision index](evidence/design-resolution-2026-09-23.md), then the linked
publication, generation/native/cursor and D2 composition decisions. They are
implementation contracts, not claims that the missing production proof passed.
No initial Astra investigation remains to dispatch. Sol owns integration and routine
design choices; use normal bounded explorer/worker/reviewer routes when useful.
An Astra escalation is exceptional: first record a concrete counterexample or changed
requirement, the exact unresolved choice and blocked acceptance. Do not dispatch
Astra for every design skill, failed test, concurrency edit or review disagreement.
This is task guidance, not a host-enforced model budget or global configuration change.

Read stage D1-4, [C2 settings transaction](evidence/C2/operations-store-design.md),
the ownership decisions and [typed API-port plan](evidence/D1/api-port-design-2026-09-22.md).
Inspect these verified owner paths before proposing a new representation:

| Owner | Source path relative to worktree |
| --- | --- |
| Settings preparation/replacement/publication | `modules/app-services/src/main/java/io/justsearch/app/services/settings/SettingsCommitCoordinator.java` |
| Accepted row/reservation/committed receipt | `modules/app-observability/src/main/java/io/justsearch/app/observability/operations/OperationAttemptRunnerImpl.java` |
| Reader-facing client/service references | `modules/app-services/src/main/java/io/justsearch/app/services/HeadAssembly.java` |
| Process composition and teardown | `modules/app-engine/src/main/java/io/justsearch/app/engine/EngineRoot.java` |
| Admission/freeze/cancellation | `modules/app-engine/src/main/java/io/justsearch/app/engine/EngineAdmissionController.java` |
| Generative candidate/rollback | `modules/app-inference/src/main/java/io/justsearch/app/inference/InferenceLifecycleManager.java` |

Preserve the full SettingsWitness, including last committed operation key, and
the accepted runner-owned row/reservation. Do not nest a second settings apply.
SettingsCommitCoordinator remains the sole publisher; all fallible composition
precedes file commitment, followed by prepared reference publication and receipt
completion. Prove how each affected reader observes a coherent configuration and
component set: separate volatile fields or an apply lease alone are insufficient.

Implement the selected monotonic closing admission and dependency-aware teardown;
the old shared-freeze/interactive-only drain proposal is retired. Native quiescence
controls process exit selection; embedded composition never owns process termination.

Implement the smallest connected D1-4 path
with its real front/runner/coordinator/readers. Map at least second-component
failure (A/config/file/revision preserved), candidate cleanup, cancellation,
coherent concurrent readers, post-commit outcome recovery, no-dependency changes,
generation-bound refusal, and restart-required persistence to the owning checks.
The full D1-4 acceptance remains binding, including installed restart and kill
mid-compose recovery. Typed API-port proof must avoid the dev-runner environment
override that would mask the persisted value. Retire superseded reload routes
with the stage's required sweep; do not use foundation tests as completion.

Root settles coupled ownership; delegate stable tests or independent review only
after that contract is clear. Review one frozen connected batch and consolidate
feedback. After two substantive correction rounds, reassess scope/owner and take
over coupled changes as needed; this never excuses a defect.

## Continue and retain only current state

After the adopted batches continue all remaining D1/D2/E/F acceptance. The
sequence above changes order, not scope or acceptance.

Update the owning decision and the handoff once per coherent batch. Record its
accepted outcome, substantive correction rounds, invalidated verification and
repeated discovery in that existing evidence record; do not create another status
database. Use bounded excerpts and owning wait tools. Answer status questions in
commentary and continue authorized work. Stop only for completion, explicit user
pause/handoff, or a real external dependency with no independent work available.
