# Lane F continuation brief

Updated 2026-09-23 after design resolution for a Sol orchestrator. This is the successor's
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

**Current D1-9 boundary:** `fc5b444d6` passed the serial 358-task local gate,
hosted run 36055201547 (including system integration) and the standard-model
installed A/B file-mutation path. Six current-revision installed installer
pointer/settings crash cuts passed at `tmp/3389` and `tmp/3392`–`tmp/3396`;
the [handoff](handoff.md) names each artifact. The next D1-9 work is no-file
projection admission/replay, nonterminal gap acceptance and positive
cancel/abandon transfer. Two refused-candidate transfer regressions passed
focused checks locally; they were not in hosted run 36055201547. This paragraph is status,
not a release of any later D1/D2/E/F acceptance.

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

## Batch 1: close the candidate-context verification slice

**Outcome:** corrected candidate-context behavior has all required local,
installed and hosted proof, or each remaining failure is explicitly open with
its cause. This closes that slice only, not D1-4 or stage D1.

Start from [candidate plan and proof](evidence/D1/candidate-context-plan-2026-09-22.md)
and the handoff's2459-2464 inventory. Root owns
`modules/app-inference`, any regression corrections, and existing dead-code/config
audit owners. Do not start D1-4 implementation while this batch is being corrected.

1. Inspect the correction revision's hosted results early and verify job/test
   selection. Pre-correction CI35678039813 is red; its passing jobs alone do not
   close corrected-revision proof. Reuse only documented content-equivalent checks.
2. Run from the worktree:
   `./gradlew.bat spotlessCheck pmdAll test -PincludeStress=true :modules:ui:installDist --continue --console=plain`.
   Capture the complete log and native exit code. Preserve XML before any focused
   rerun, including failed and unperformed tasks. Existing local collectors are
   `tmp/c2-capture-generation.py LABEL REVISION COMMAND` (expects `tmp/LABEL.txt`)
   and `tmp/c2-source-inventory.py LABEL [COMMIT]`. Inspect their argument semantics:
   the latter inventories selected changed Java/proto files, not the whole tree.
   A docs-only HEAD can legitimately yield no changed Java files; preserve actual
   tested HEAD, the22800c842 code baseline and any subsequent correction delta.
3. Correct concrete failures with one consolidated list. Search ordinary calls,
   method references, fixtures and relevant allowlists before changing a seam;
   compile affected tests and run relevant focused audits before another full run.
   Preserve existing positive/negative-control evidence when still applicable.
4. Load dev-stack and jseval skills. Use the official owned stack from the freshly
   installed worktree distribution, retained data and standard model profile.
   Run the runtime-client smoke and a real-model query; preserve revision/profile,
   results and source provenance. Officially stop and verify `portsClosed`.
   Earlier215af/2434 installed evidence is not this slice's proof.
5. Reconcile named acceptance with local, installed and hosted results; checkpoint
   and push corrections within existing authorization, then continue Batch2.

Escalate a newly exposed ownership/design issue to the root's decision process;
do not waive a red check or treat it as a reason to ask for routine permission.
Resource contention blocks dependent runtime work only. No new benchmark, model
selection, unrelated refactor or weaker audit is part of this batch.

## Batch 2: implement the selected D1-4 publication and lifetime protocol

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

After these batches continue all remaining D1/D2/E/F acceptance, including Flow A
bulk evidence/ACK and gap activation, Flow B/model-binding gaps, native quiescence,
D2 profile/library/durability work, E proof and F publication. These two batches
are the first continuation steps, not a reduced lane scope.

Update the owning decision and the handoff once per coherent batch. Record its
accepted outcome, substantive correction rounds, invalidated verification and
repeated discovery in that existing evidence record; do not create another status
database. Use bounded excerpts and owning wait tools. Answer status questions in
commentary and continue authorized work. Stop only for completion, explicit user
pause/handoff, or a real external dependency with no independent work available.
