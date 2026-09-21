# Lane F handoff: implementation orchestrator

## Current state (2026-09-21)

Continue autonomously in `.claude/worktrees/lane-f-pr1-verify`, branch
`codex/lane-f-pr1`. Main contains unrelated work; never edit or clean it.
Existing checkpoint commits/pushes to [PR727](https://github.com/justsearch-app/justsearch/pull/727)
are authorized; merge remains at stage F. No routine owner approval is pending.
Continue after status answers, commits and reviews. C2 is accepted; D1/D2/E/F
remain open. Root owns all Gradle runs, stack lifecycle and integration.

Latest committed/pushed checkpoint: `4e4cd88f6` (schema2 projections, physical
handles, recovery occurrences, shared process-resource SPI, host/frontend consumers,
Vite unit-mode discovery). C2 is accepted; this checkpoint is not D1 acceptance.
2376 corrected its orphan runtime-state register row; the11 hosted hermetic gates
pass locally. Hosted CI35651061321 completed red: Public claims orphan plus a
schema1 health assertion in WorkerBootRecoveryE2ETest. Both root causes are
corrected in WIP; hosted successor proof remains outstanding. Other hosted jobs,
including build/app-ui/search-worker/Windows-native/Rust, passed.

Checkpoint proof:2367 full static/unit/stress/installDist passed11465 cases,
31 skips (5253 executed,6212 reused);2371 frontend6596 pass.2370 live runtime-client
contract0.4/model query/all4READY pass;2372 live UI passes0axe/console/overflow after
owned Vite dependency-cache restart. Both stacks stopped with portsClosed:true,
MCP client closed.2374 mount diagnostics39tests include real Chromium. Earlier
negative controls and corrected failures remain in the evidence record.

New batch: hot RAG/citation readers, thirteen justified key retirements, summary
single-pass input guard, and path-history retention are implemented in WIP.
Current parser-verified count is278 (237 EnvRegistry +53 ConfigKey minus12 aliases).
2380 matrix/config gate passes103/237/53 with downward pins;2381 regen7sets passes.

2381 focused verification emitted479 passing cases but failed compile/static checks.
Root corrected test-fixture imports with public APIs, redundant qualifiers and LF.
2382 passes481 cases (163 fresh,318 reused), no failures/skips, and all static checks.
Final summary review has no material issue after correcting trace/health bypasses,
batch metadata timing and all7 selection refusal-attributes assertions.
2383 negative control catches bypassed supplier reads;2384 catches correct reads
with frozen pruning value, specifically old-row deletion. Both restored source.

2385 full Java/static/stress + installDist + schema2 boot-recovery integration PASS:
11487 cases/0 failures/31 skips,10859 executed/628 reused. Evidence captured.
2386 standard live proof PASS: runtime0.4.0, all4READY, real Qwen9B query exact1.0,
summary search-trace rejection21711>20000/no chunks. Owned stack stopped with
portsClosed:true; MCP client closed. No Gradle/stack active. Keep source frozen
until checkpoint commit/push; next unused run2387. Hosted successor remains due.

Apply reconciliation tmp/2381-apply-reconciliation.json covers278 keys with no
missing/extra rows. Candidates:119 index/38 encoders/13 generative/51 hot/34 restart/
22 generation/1 unresolved. Generation splits8 current fingerprint inputs and14
persisted-output identity gaps. Shared GPU policy uses primary encoder scope plus
all-matching-owner dispatch. workers.indexer.enabled is smoke-only; llm.enabled has
a documented disable promise but only smoke reads; citation.scorer.threshold also
has no actual default reader. Decide/connect these without fictional hot rows.

## Next coherent work

1. Commit/push the verified checkpoint to existing PR727; do not merge before F.
2. Continue missing controls: connect llm.enabled hard-disable and scorer threshold
   default; retire smoke-only workers.indexer.enabled (bounded decisions in tmp/2385-control-reader-decisions.md).
3. Reconcile actual fingerprint8 vs output gaps and smoke-only/default controls.
   Corrected local CI assertions are not hosted successor proof.
4. Recompute matrix/normalized union, shrink config pins, regenerate docs/skills
   as applicable, then commit/push the authorized PR727 checkpoint and continue.
5. Complete the governed apply register and actual D1 dispatch/readers. All
   D1/D2/E/F acceptance remains binding; classification is not implementation.

D1 Flow A must reconcile streaming core.reindex versus captured core.bulk-reindex:
abandonment cannot delete C2 evidence before terminal ownership and exact queue
ACK. COMPLETE_WITH_GAPS/accept-gaps/live activation requires explicit design/proof.

## Active ownership

- Root: shared-owner integration, all builds, stack, evidence and scoped publication.
- `bulk_engine_restart_proof`: summary corrections source-frozen, final review clear; idle.
- `d1_encoder_projection`: completed278-key reconciliation in tmp/2381-apply-reconciliation.json; idle.
- `d1_owner_review`: summary/retention review complete and corrected; idle.

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
