# Lane F handoff: implementation orchestrator

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

Candidate-context checkpoint78fca2b67 is pushed. The handoff correction commit that
follows it retires audit residue; use branch HEAD as the next tested candidate.
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

Previous `23cc8929a` (coherent applied revision),
CI35669241131 completed successfully, including system integration and Windows-native.
Documentation checkpoint53a64edb6 records integrated/installed proof locally.
Previous4bec3f00b implements the complete apply register;
921053650 connects captured live query feature controls. `51b01365d` declares schema2 wire3.0.0 migration;
its Public claims and Windows-native passed, integration was cancelled by successor.
`c948cf85f` registered the generated status consumer;
its wire gate exposed the missing major declaration, now locally proved by2401.
`ecfa96797` contains captured LLM existence/config,
citation defaults,14th retirement and hosted fixture/schema corrections. Its Public
claims failed the omitted registration; other unfinished jobs were superseded.
Previous `d87a0e60c` (live RAG/citation readers,
summary source limits, path-history retention,13 retired keys, schema2 hosted-test
correction, UI mount diagnostics). Previous architecture checkpoint4e4cd88f6
contains schema2/process-resource ownership. CI35659220060 atd87a0e60c completed red;
previous CI35651061321 failures are corrected and passed locally. No merge before F.

Checkpoint proof:2367 full static/unit/stress/installDist passed11465 cases,
31 skips (5253 executed,6212 reused);2371 frontend6596 pass.2370 live runtime-client
contract0.4/model query/all4READY pass;2372 live UI passes0axe/console/overflow after
owned Vite dependency-cache restart. Both stacks stopped with portsClosed:true,
MCP client closed.2374 mount diagnostics39tests include real Chromium. Earlier
negative controls and corrected failures remain in the evidence record.

Verified checkpoint:2385 full Java/static/stress/installDist + schema2 boot recovery
passes11487 cases/0 failures/31 skips (10859 executed/628 reused).2386 live standard
Qwen9B query exact1.0, runtime0.4.0/all4READY, summary trace rejection21711>20000/no
chunks. Stack stopped portsClosed:true; persistent MCP client terminated. Evidence
captured.2386 regeneration passes8 sets.

Verified ecfa checkpoint: workers.indexer.enabled retired (14total ->277unique keys),
LLM hard-disable connected through captured configuration/actual manager creation,
and captured scorer threshold now supplies unset wire defaults. Root owns LLM files;
workers finished retirement and scorer connection, source-frozen. Independent LLM
review completed clear after root fixed a hidden global snapshot read/cache.
2387 passes380 fresh cases;2388 passes62 fresh cases but reports one redundant
qualifier PMD failure;2389 fixes it and passes static checks, reusing those62 cases.
2390 watched-root fixture regression passes13 fresh cases and spotlessCheck.
2391 integrated Java/static/stress/installDist completed11490 cases/1 funnel failure/
31 skips;2392 fixes both process-global reads and retires3 obsolete allowlist rows.
2392 passes71 fresh focused cases/static/installDist. All evidence captured.
Frontend2391 passes6598 cases/typecheck; focused42 cases and generated schema check
pass.2392 installed llm.enabled=false proves generative ABSENT with index READY;
2393 normal standard Qwen9B query exact1.0/all4READY/runtime0.4/UI zero issues pass.
Both owned stacks stopped portsClosed:true. No build/stack active. Next run2394.
Hosted app-ui failed during TempDir cleanup because its test executor did not wait;
fixture now matches production close semantics. Public claims dead-code failure
reproduces as generated status-response unused exports6→8; actual StatusDeck type
and validator vocabulary-test consumers now connect both exports. Dead-code passes
without baseline changes. The tentative isRecovering attribution was disproved,
not implemented. Existing CI artifact upload now retains SARIF and Knip input.
All other hosted jobs, including integration and Windows-native, passed.
Matrix/gate now102 YAML/236 EnvRegistry/53 ConfigKey; downward pins pass. Canonical
LLM promise/retirement docs updated; llms/skills/matrix verification pass.

Apply audit tmp/2381-apply-reconciliation.json is historical at278 keys; successor
tmp/2391-apply-reconciliation.json records277/14 retirements,45 connected hot rows,
230 owner-work and2 pending readers. Preserve both. Generation candidates split8
current fingerprint inputs/14 persisted-output gaps; primary GPU encoder scope
still dispatches to generative dependency too. QU/filter hot-reader design identified
double availability sampling and distinct KSE-vs-retrieve deterministic semantics.
Do not connect suppliers while retaining those double reads.

## Next coherent work

1. Inspect23cc8929a's hosted successor while continuing implementation.
2. Completed QU/filter ConfigStore wiring with once-per-operation sampling while
   preserving KSE feature gating and retrieve-context deterministic normalization.
   The bounded plan is evidence/D1/query-reader-plan-2026-09-22.md.
   Focused2395 passes377 cases/3 existing skips. HTTP2397 passes13 UI cases but
   exposes an orphan internal overload and11 redundant qualifiers;2398 corrects
   both and passes52 cases/static. Full2400 passes11500 cases/0 failures/errors/
   31 skips plus static/stress/installDist. Installed2402 all4READY/runtime0.4.0/
   real-model query passes; actual QU author boost and hybrid filter projection
   return200. Owned stack stopped portsClosed:true. Reader slice complete; preserve
   source inventory and raw proof in tmp/2400* and tmp/2402*. Next run2403.
3. Governed277-key register/validator and fourth scalar are implemented.2403 count
   gate passes5 informational findings; all31 governance
   Node test files pass.2404 found locale-vs-natural sorting mismatch in the draft;
   corrected register preserves strict validation.2405 passes4cases,2406/2407 real
   missing/unknown mutations fail for the intended reasons and exactbytes restore.
   Register is a declared Gradle test input; no new dependency.2408 fullconfiguration
   passes294cases/static. Review removes duplicate277JUnitcountauthority;2409
   passes4cases/configSpotless. Review also closes deleted-pin bypass;2410all31
   governance files and actual count gate pass. Overall applied revision implementation follows
   evidence/D1/applied-revision-plan-2026-09-22.md; runtime-owner/client/registry
   fences are implemented. Evidence/D1/applied-revision-verification-2026-09-22.md
   records2416realcommits,2417actualabsence/failure33cases,2418root/static47cases,
   and2419/2420/2421independentfence-removalreds with exactsourcebytesrestored.
   Full2422 Java/static/stress/installDist passes at23cc8929a in10m31s:
   11523 cases/zero failures/31 skips,1802 suites,34 tasks (18 reused).
   Full log/XML/counts/15-file source inventory captured at
   tmp/2422-applied-revision-integrated*. Installed2423 hard-disable proves
   generative ABSENT with the other three READY. Installed2424 standard-model
   proves all four READY, runtime contract0.4.0 smoke and real-model query
   exact/intersection/context100%,zero errors. Both official stops confirm
   portsClosed:true. Current run numbering and proof are in Current state above.
   D1-4 ownership decisions are in evidence/D1/reconfigure-owner-decisions-2026-09-22.md;
   full-witness C2 authority and the typed patch domain remain binding.
   Root has moved candidate VRAM preflight before incumbent destruction in
   InferenceLifecycleManager; focused tests and explicit OFFLINE failure-restoration
   work are implemented.2430 full inference module/static passes330 fresh cases;
   2426/2427/2431/2432 prove stop-order, OFFLINE restoration, exactly-once failure
   telemetry and cache-preservation tests fail under their intended mutations.
   Exact source bytes restored. Independent review clear after duplicate telemetry
   correction. See generative-preflight-plan-2026-09-22.md and
   reconfigure-owner-decisions-2026-09-22.md in evidence/D1. Integrated2433 and
   installed2434 passed for that checkpoint. Candidate-context implementation and
   its still-open proof follow evidence/D1/candidate-context-plan-2026-09-22.md;
   do not use its superseded private design draft as the continuation authority.
   Continue dependency/value projections and D1 dispatch. All D1/D2/E/F acceptance
   remains binding, including the14 identity gaps.
4. Preserve compiling checkpoints/push authorization to PR727 and inspect hosted
   successor results. Status answers and commits do not stop work.

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
