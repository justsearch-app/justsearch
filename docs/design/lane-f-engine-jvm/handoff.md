# Lane F handoff: implementation orchestrator

Start with the [continuation brief](continuation-brief.md): the next two batches,
owner boundaries, acceptance endpoints and execution protocol. This handoff owns
current evidence; the brief does not narrow the remaining lane scope.

## Current state (2026-09-22)

Continue autonomously in `.claude/worktrees/lane-f-pr1-verify`, branch
`codex/lane-f-pr1`. Main contains unrelated work; never edit or clean it.
Existing checkpoint commits/pushes to [PR727](https://github.com/justsearch-app/justsearch/pull/727)
are authorized; merge remains at stage F. No routine owner approval is pending.
Continue after status answers, commits and reviews. C2 is accepted; D1/D2/E/F
remain open. Root owns all Gradle runs, stack lifecycle and integration.

Handoff requested by the user after a usage-limit interruption. Do not mistake this
for scope completion. No user decision or permission is pending. Preserve the worktree;
a lifecycle hold is recorded through2026-09-29 under the current session owner.
No build, stack, or subagent is running. Official quick_health reports ABSENT,
no foreign runs and no inference orphan. Next free verification run2466.

Candidate-context checkpoint78fca2b67 and correction22800c842 are pushed.
The correction retires audit residue; its Java state is the next tested candidate.
Retrospective checkpointf38c9eebb and the subsequent continuation-brief checkpoint
change documentation/evidence only; they do not add runtime verification.
Resolve actual HEAD at startup and verify equivalence to the22800c842 runtime code.
Root now owns all files; previous subagents are no longer active. Main remains untouched.

Implemented: captured inference/resolved configuration and strict adoption policy;
logical versus physical server ownership; publication after health/hash proof; rollback
to actual serving A; manager-serialized recovery; stale callback suppression; monitoring
only after health; preserved crash budgets and dead-row cleanup; strict terminal cleanup;
serving-based vision capability during APPLY_ONLY. No executor was added. Runtime register
F-019 and both inference-runtime skills carry the current ownership rules. D1-5 stage text
clarifies desired CONFIGURED versus serving APPLIED; this does not waive D1-4 composition.

Evidence:
-2459 full inference/static passes359 cases, zero failures/errors/skips,33 suites.
 Independent source/proof review was clear. Tests include actual distinct OS child retries,
 B launch paths/reasoning fallback under global C, detach cleanup refusal, both vision
 directions, and manager A preservation followed by manager B strict same-child adoption.
-2451–2455 negative controls each failed as intended (owner guard, post-close mode,
 transition lock, crash budget, clean exit); exact source bytes restored.2457 independently
 demonstrated both vision directions fail before the serving-context correction.
-2460 full Java/static/stress/installDist at78fca2b67:11559 cases,2 failures,31 skips,
 1805 suites,34 test tasks (27 reused),9m21s. Both failures were migration residue:
 UnreferencedCodeTest found3 no-arg test-only wrappers; SystemAccessFunnelTest found2 stale
 allowlist entries. Full log/XML/counts/source inventory are tmp/2460-candidate-integrated*.
-Hosted CI35678039813 at78fca2b67 failed those same audit test classes in app-ui and
 platform-contracts. All other jobs, including Windows-native and system integration,
 passed. Failed hosted logs retained in tmp/2465-hosted-failed.txt.
-The correction retires the3 wrappers, null-owner crash path and2 stale allowlist entries.
 Tests use narrow src/test LlamaServerTestAccess to capture the current real owner and
 invoke guarded production methods. Telemetry fixtures install logical ownership, close
 their schedulers and no longer carry obsolete Windows-only race exclusions.
-2462 passed94 audit cases but inference tests did not compile due to one missed method
 reference. Fixed;2464 passes453 cases/zero failures/errors/skips,58 suites:359 fresh
 inference cases plus94 audit cases reused from2462. Spotless/PMD pass. Complete evidence
 is tmp/2464-candidate-residue-focused*. This is NOT a corrected full integrated pass.

Immediate continuation:
1. Run `./gradlew.bat spotlessCheck pmdAll test -PincludeStress=true :modules:ui:installDist
   --continue --console=plain` on corrected HEAD. Preserve complete generation with
   tmp/c2-capture-generation.py and source inventory before any targeted rerun.
2. Check the correction commit's hosted CI; fix any real failure without audit exemptions.
3. Official dev-stack start using existing retained data, standard profile; runtime-client
   smoke and jseval real-model query; official stop and verify portsClosed. No installed
   proof exists for this candidate-context slice. Earlier215af/2434 proof is not reusable.
4. Then continue D1-4 prepared component composition/publication and all remaining stage
   acceptance. The current coordinator ownership decisions and typed API-port design are
   in evidence/D1. A read-only next-step collaborator investigation was interrupted and
   returned no final proposal; do not assume D1-4 design/implementation is settled.

The apply-drain proposal is still refuted for shared-freeze release and durable-work
survival; no teardown implementation has begun. D1/D2/E/F remain open. No scope was waived.
Closeout sweep reaped nothing: it retained two stale ui-shot records whose PIDs no longer
exist and reported the intentionally persistent OTLP sink. Do not force-kill these.

## Remaining design obligations

D1-4 ownership decisions remain in
[evidence/D1/reconfigure-owner-decisions-2026-09-22.md](evidence/D1/reconfigure-owner-decisions-2026-09-22.md).
Full-witness C2 authority, the typed patch domain, dependency/value projections,
D1 dispatch and all D1/D2/E/F acceptance remain binding, including the14 identity
gaps. Historical checkpoints are in a [separate evidence record](handoff-checkpoint-history-2026-09-22.md);
their old next-run and next-action statements are not the continuation queue.

D1 Flow A must reconcile streaming core.reindex versus captured core.bulk-reindex:
abandonment cannot delete C2 evidence before terminal ownership and exact queue
ACK. COMPLETE_WITH_GAPS/accept-gaps/live activation requires explicit design/proof.

## Active ownership

No subagents remain active. The successor owns continuation in the retained worktree;
root integrated all worker files and preserves commit/push authorization to PR727.

## Evidence and owner map

| Concern | Governing record |
| --- | --- |
| Predecessor teachings and honest self-audit | [Resumption contract](evidence/C2/resume-2026-09-21.md) |
| In-depth workflow findings, measurements and proposed trial | [Workflow retrospective](evidence/workflow-retrospective-2026-09-22.md) |
| C2 accepted proof, including installed/stress/hosted/real-model | [C2 acceptance](evidence/C2/verification-2026-09-21.md) |
| D1 actual owners and captured configuration | [Owner map](evidence/D1/regrounding-2026-09-21.md), [component plan](evidence/D1/component-plan-2026-09-21.md), [wiring proof](evidence/D1/owner-wiring-2026-09-21.md) |
| Broad affected-module proof2298 at6c95d7989 | [Integrated verification](evidence/D1/owner-integrated-verification-2026-09-21.md) |
| Full-snapshot CAS and stateless reason retention | [Publication seam](evidence/D1/publication-seam-verification-2026-09-21.md) |
| Pure schema2 projection, trigger feedback and tool composition | [Schema/trigger proof](evidence/D1/schema-trigger-verification-2026-09-21.md) |
| Physical-health initialization and close/retry ownership | [Bootstrap proof](evidence/D1/bootstrap-initialization-verification-2026-09-21.md) |
| Runtime readiness and six-state decisions | [Readiness plan](evidence/D1/readiness-plan-2026-09-21.md) |
| Schema and host migration | [Schema consumers](evidence/D1/schema2-consumer-plan-2026-09-21.md), [host plan](evidence/D1/host-readiness-plan-2026-09-21.md) |
| Apply classification and audited missing readers | [Apply-register plan](evidence/D1/apply-register-plan-2026-09-21.md) |

## Working rules to retain

Start with the acceptance path, then reuse actual owners. Delegate bounded files
and deliverables; consolidate review and reassess after two substantive rounds.
Root owns shared state, stack and Gradle. Freeze compiled sources before a build.
Run focused checks while correcting and integrated checks at coherent boundaries;
collect independent static failures using `spotlessCheck pmdAll --continue`.
Workflow correction `b4d01c1cc` already records this in canonical guidance and both
CI-triage skills; no extra always-loaded policy copy is needed.

Keep compiling checkpoint commits per item, push each checkpoint and preserve WIP
at least hourly. Stage explicit paths; check native exit codes before mutations.
Preserve XML before reruns. Name tested revision, reuse, failures, skips and proof
tier; BUILD SUCCESSFUL or hosted job success alone is not test-level acceptance.
Refute expected-looking passes and reviewer mechanisms against the actual owner.
Do not replace a concurrency defect with an unnecessary marker, store or timer.

Discover paths with `rg --files`; use `-g` for filename globs and bounded excerpts.
Use UTF-8 editing. Root has repeatedly failed to apply the path/output discipline;
more wording is not evidence of improvement. The concrete context correction here
is to keep this handoff current, with history in a separate archive.

Raw logs/XML under this worktree's `tmp/` remain accessible through lane acceptance
plus30days; export before deleting the worktree. Use `--no-ignore` to discover
ignored evidence. Hashes supplement accessible artifacts rather than replace them.

[Historical handoff archive](handoff-history-through-2026-09-21.md) preserves the
prior1850-line record and old decisions. It is background, not a resumption queue;
read only the historical section needed to resolve a specific uncertainty.
