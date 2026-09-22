# Lane F handoff: implementation orchestrator

## Current state (2026-09-22)

Continue autonomously in `.claude/worktrees/lane-f-pr1-verify`, branch
`codex/lane-f-pr1`. Main contains unrelated work; never edit or clean it.
Existing checkpoint commits/pushes to [PR727](https://github.com/justsearch-app/justsearch/pull/727)
are authorized; merge remains at stage F. No routine owner approval is pending.
Continue after status answers, commits and reviews. C2 is accepted; D1/D2/E/F
remain open. Root owns all Gradle runs, stack lifecycle and integration.

Latest committed/pushed checkpoint: `215af7809` (generative preflight, failed
restoration OFFLINE and single failure telemetry). Full2433 passes at this revision
in9m18s,11530 cases/zero failures/31skips,1803suites,34tasks (27reused), evidence
captured under tmp/2433-generative-integrated*. Installed standard2434 all4READY,
runtime0.4.0 smoke and realmodelquery exact/intersection/context100% pass; official
stop confirms portsClosed:true. No build or stack active. Hosted35671548458 passed all jobs.
Latest local docs commit is `1d89a465f`; candidate-context code remains uncommitted.
Next free run2461; integrated2460 is running (root exec session80877), all Java frozen.
Root owns every edited file. Candidate-context implementation follows
 evidence/D1/candidate-context-plan-2026-09-22.md. The independent reviewer is
clear on source and new test proof after2459; worker source has been integrated.

Candidate context captures inference/resolved configuration plus adoption policy,
separates logical start identity from physical process identity, publishes candidates
only after health, and restores actual serving A on rollback. Manager transition
locking serializes physical lifecycle changes; the short server ownership monitor
fences health/props publication. Existing small props model-ID persistence stays
serialized there by explicit design exception. Monitoring starts only after health;
healthy recovery resets its crash budget before exposing monitors. Failed attempts
retire dead child rows while retaining the logical recovery token. Uncancelled clean
exits recover; terminal failure performs strict cleanup and reports OFFLINE even if
the child refuses termination. No executor was added. Vision capability now uses
serving configuration, so APPLY_ONLY cannot falsely advertise desired model features.

Focused/static2459 passes359 tests, zero failures/errors/skips,33 suites,33s. Complete
XML/counts/source inventory and log are tmp/2459-candidate-focused*. This includes
real-child context/rule retries, captured B launch-log/runtime PATH/build marker
under global C, detach cleanup refusal and both causes, add/remove vision capability,
and a real child surviving manager A close then strict same-hash manager B adoption
before final cleanup. All static checks pass; existing compiler advisories remain.

Negative controls2451–2455 each produced the intended single failure for owner guard,
post-close mode, transition lock, crash budget and clean-exit recovery; exact source
bytes were restored and root re-read saved XML.2457 proved both vision directions
fail against the former desired-config read before the one-line correction.
Earlier2448/2449 failures were invalid recovery fixtures (thread-local constructor
mock and missing GPU collaborator);2450 corrected them.2456 was a matcher compile
failure, not behavioral evidence. See the candidate plan for commands and limitations.

Remaining proof: integrated/stress2460,
installed standard-model query and hosted CI for this candidate-context revision.
The nine-row acceptance map is in the candidate plan; no D1-4 integration is claimed.
The earlier installed2434/hosted evidence applies only to215af7809. Do not reuse it
as proof of this WIP. The apply-drain proposal remains refuted for shared-freeze
release and durable-work survival; no teardown implementation has begun. Typed
API-port design is preserved in evidence/D1/api-port-design-2026-09-22.md.

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

- Root: all production integration, manager tests, docs, builds, stack, evidence and scoped publication.
- `d1_encoder_projection`: stopped; root integrated its preserved-child test.
- `query_http_composition`: source frozen; root owns its integrated candidate-context tests.
- `d1_owner_review`: independent read-only contract and proof review.

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
