# Lane F handoff: implementation orchestrator

## Current state (2026-09-21)

Continue autonomously in `.claude/worktrees/lane-f-pr1-verify`, branch
`codex/lane-f-pr1`. Main contains unrelated work; never edit or clean it.
Existing checkpoint commits/pushes to [PR727](https://github.com/justsearch-app/justsearch/pull/727)
are authorized; merge remains at stage F. No routine owner approval is pending.
Continue after status answers, commits and reviews. C2 is accepted; D1/D2/E/F
remain open. Root owns all Gradle runs, stack lifecycle and integration.

Latest committed/pushed checkpoint: `78f58bd6c` (registry readiness and manifest
projections). Large uncommitted D1 migration follows it: physical index/generative
handles, read-only adapters, explicit recovery occurrences, schema2 status/health,
wire generation, test fixtures and host/frontend consumers. Do not mistake WIP
for accepted D1. C2 proof remains in its acceptance record.

Latest proof:2365 corrections114 fresh cases pass across6 modules,0 failures/skips.
2366 old close-order negative fails with executor CLOSED as intended; exact source restored.
2356 boot-recovery/production-order34 fresh cases pass;2353 intended negative
emits unwanted RECOVERED and fails, source restored.2363 collected one PMD unused resource-alias finding, corrected.
Full static/unit/stress2364 finished:11380 executed cases plus83 reused,6 failures,31 skips; all static checks passed. XML/source inventory preserved. 2367 full static/unit/stress plus installDist passed:11465 cases,0 failures,31 skips;5253 executed,6212 reused. Sources unfrozen.
Full frontend2367 exposed live-manifest test pollution; minimal Vite test-mode discovery guard fixes it:2371 all6596 tests pass. 2368/2369 explicit-env reproduction was a different mechanism; see evidence.2370 live run d8eb5a19-e383-46cc-bce9-8e1223721b7b active, API53671/UI5173, worktree MCP client session61226. Runtime-client/model query/all4READY pass. Live home capture fails empty mount; investigating before closing slice. Next unused run2372. No Java source edits during builds.

Live2358 tested installed2356, before current process-resource extraction:
contract0.4 runtime-client smoke passes; all4components READY; standard Qwen query
returns Captain Mortimer Flux with exact1.0/valid anchors.245 live routes recaptured.
Activation variant endpoint respects operator executable lock; existing online
intent API enabled configured runtime at revision1. Stack0c431f46 stopped with
portsClosed:true. Data path discrepancy and proof artifacts are in
[schema2 owner proof](evidence/D1/schema2-owner-verification-2026-09-21.md).
2360 affected UI fixture capture and2359 livehome capture have0axe/console/overflow.
Earlier capture failures were missing --fixtures for chat-chip-yield, not a
schema/product defect. Harness now fails early with the flag remedy;14 tests pass.
UI typecheck and53 focused unit tests pass2359; step coverage passes2362.

Earlier registry checkpoint proof remains in
[registry connection proof](evidence/D1/registry-readiness-verification-2026-09-21.md):
2322 fresh EngineRoot/trigger13 and UI132;2325 UI132 FROM-CACHE; five intended
negative omissions fail2323/2324. No live-model proof covers the new WIP.

## Next coherent work

1. Finish review/static/integrated verification of the current process-resource
   extraction. `EngineProcessResources` in app-api and its app-engine implementation
   now own shared policy/admission/executors/registry. EngineRoot delegates; launcher
   uses one SPI, registers absent index and passes real registry to HeadAssembly.
   Superseded independent SPI descriptors/loaders are removed. Root changed Headless
   ordered/final shutdown to close the process bundle. Review found required fixes
   implemented after2364, verified in2365: sequential components/executors close
   retains execution after held-apply refusal; ordered close requires both drains;
   failed-drain executor fallback is removed. Fatal finally retains/closes monitor
   before API. Held-lease, Head/index refusal, normal/fatal blocked-recovery
   regressions pass2365; independent rereview finds no residual defect in this slice. No new lifecycle marker/state machine needed.
   Fullrun also finds five dead-code violations: four old captured-config wrappers
   (worker-services, adapters-lucene, indexer-worker) plus new launcher test accessor.
   Child retired four wrappers and migrated explicit-config test calls; root
   removed launcher accessor and captures the real SPI owner in its test. Two UI fixture classes (four failures) now have explicit non-api registrations; focused tests pass2365.
   Sixth failure: EngineRootTerminalWriterFailureTest swap thread alive after5s;
   reviewer traced14ms overrun to synchronous Tika external-tool discovery during reload. Fixture now replaces only reload-time services through the existing method seam after real initial boot; timeout and ownership assertions remain.2365 includes the real boot/ingest/search test.
2. Recovery late-completion gates plus monitor-before-API shutdown are implemented
   and tested. Mutable capability retirement, producers, schema2 and host/frontend
   consumers remain the larger WIP. Full unit/stress/static/integrated proof and
   live proof after the new owner extraction are still needed before acceptance.
   Preserve a compiling checkpoint and scoped push to PR727 once coherent.
3. Continue the291-key apply register and actual readers/dispatch. The apply plan
   distinguishes obsolete keys from unconnected promised readers; classification
   does not implement apply behavior. D1/D2/E/F remain binding.

D1 Flow A must reconcile streaming core.reindex versus captured core.bulk-reindex:
abandonment cannot delete C2 evidence before terminal ownership and exact queue
ACK. COMPLETE_WITH_GAPS/accept-gaps/live activation replaces current behavior only
with an explicit design and proof. All acceptance items remain binding.

## Active ownership

- Root: shared-owner integration, all builds, stack, evidence and scoped publication.
- `bulk_engine_restart_proof`: UI and runtime-swap fixtures frozen; no active edits.
- `d1_encoder_projection`: hot-reader brief ready; currently read-only live UI empty-mount diagnosis.
- `d1_owner_review`: resource and Vite diagnosis complete. Future apply-lease teardown obligation recorded in apply-register plan.

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
