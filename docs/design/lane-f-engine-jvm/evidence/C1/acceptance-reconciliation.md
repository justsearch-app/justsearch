# C1 acceptance reconciliation

Current status, September13 R10: C1 COMPLETE including the later MCP quota correction.
Its pending integrated/hosted obligation below is satisfied by executed local863
UI cases, successful full869 and successful CI34730328727. The
[quota correction record](mcp-session-quota.md) distinguishes execution from reuse
and links the retained artifacts. The earlier closure table remains historical.

Status: historical COMPLETE at2026-09-12; current verification amended September13 R9. Application candidate8f8c7d775 is unchanged through
source checkpoint35d03f7c4 and documentation checkpointa480ac6f6.
The later MCP quota correction fc67f6ed9 has negative599/positive601 proof,
separate from this closure. R10 reconciles current integration and hosted coverage.
This historical table maps the then-current stage contract to evidence; implementation, local verification,
hosted verification and live acceptance are separate. Every C1 item is reconciled below; later-stage obligations retain their named placement.

Full455 invokes build, test, system integration and installDist with includeStress=true and
skipErrorProneTests=false on the clean candidate. It passes in5s with2 executed and367
up-to-date tasks:9783 represented tests (25 skips),118 integration (52 skips),50 schema,
one load-sensitive and9 system cases; zero failures/errors. The represented XML reuses
unchanged Java inputs. The source inventory, XML and last-full-run-summary identify revision
and reuse; this is not a claim that every test physically re-executed at455.
Raw: `tmp/c1-final-integrated-455.txt`, `tmp/c1-final-integrated-results-455/manifest.json`.

| Item | Implemented contract and local evidence | Remaining acceptance |
|---|---|---|
| C1-1 | Independent axes and explicit provenance projection; EngineContextTest2 and EngineProvenance tests pass. [Batch1](batch-1.md), [batch2](batch-2.md). | None within this item. |
| C1-2 | Required search context reaches actual dispatch; EngineContextPortPropagationTest6 passes. Consumer register/gate in [batch2](batch-2.md). | None within this item. |
| C1-3 | Indexing methods require context without compatibility overloads; read/mutate forwarding and whole-program dead-code checks pass. [Batch2](batch-2.md). | None within this item. |
| C1-4 | Schema14 migrates legacy-null and persists originator/transport through reopen and replay; persistence7 and replay6 cases pass. [Batch2](batch-2.md), [schema pin](governance-sweep.md). | None within this item. |
| C1-5 | Unique runtime registrations and source-scan census floors65/80; registry20 and architecture4 cases pass. [Governance sweep](governance-sweep.md). | None within this item. |
| C1-6 | Bare async/common-pool guard covers arity and method references, with negative mutations. [Async guard](async-guard.md), [executor guards](executor-guards.md). | None within this item. |
| C1-7 | Finite queues, typed submitter refusals and separate urgency registrations; actual-exit resources retained across cancellation. [Batch4](batch-4.md), [correction ledger](independent-review-fixes.md). | None within this item; live aggregate proof is C1-9. |
| C1-8 | Real filter-chain transport tests8, observer exclusions, CORS and explicit MCP retry safety pass. [CORS](cors-admission.md), [MCP](mcp-retry-safety.md). | Fresh default-limit fairness459 passes all wire probes and sixteen completed holders; [live proof](final-live.md). |
| C1-9 | Aggregate controller7 cases and strict63-case capture oracle distinguish front admission from later executor refusal. [Oracle correction](admission-refusal-proof.md). | Fresh one/many paired capture467 and saved-file analysis468 pass; [live proof](final-live.md). Cursor clause belongs to D2 and is unmeasurable here. |
| C1-10 | Foreground actual dispatch increments exactly once; background and observer polling do not. Root walk and handoff corrections retain actual work. [Corrections](independent-review-fixes.md), [final candidate](final-candidate.md). | Standard-active primary460 passes all469 files and308/308 searches; fresh chat-offline483 completes full enrichment for469/469 and9720/9720 continuous searches, zero errors; [live proof](final-live.md). Historical470 remains interrupted. |
| C1-11 | Durable work detaches urgency; interactive cancellation retains actual child lifetimes. Double-release and shutdown freeze are mutation-proven. [Cancellation sweep](cancellation-sweep.md). | Persisted operation-row urgency flip belongs to C2, as stage section9 specifies. |
| C1-12 | Packaged retained policy equals authority, duplicate activation refuses, caps and stale-handle release are tested; resource-policy6 and retained-budget3 pass. [Batch1](batch-1.md). | All five accounting producers await D1/D2: cursors/readers in D2; generations, co-resident encoders and attempted configurations in D1. Current generations can retain three against target two; there is no live bound claim. |
| C1-13 | Direct-memory launcher pins and [sourced memory budget](memory-budget.md) recorded with [batch1](batch-1.md); later launch verification unchanged. | Process memory/heap-growth measurements belong to E. |
| C1-14 | Broken-child routed-family refusal preserves plain-text ingestion; extraction confinement and routing seam are mutation-proven. Windows native descendants are witnessed and terminated. [Parser containment](parser-containment.md), [governance](governance-sweep.md). | Named VDU exception, IN_PROCESS operator override and non-Windows proof limits remain explicit in the stage/design. |
| C1-15 | Admission-aware clients honor Retry-After only with retrySafe; parity, abort and no error-toast behavior tested. Typecheck437 and6474 unit442 cases pass. [Batch3](batch-3.md), [dependency correction](dependency-advisory.md). | None within this item. |
| C1-16 | Whole-program dead-code test passes, retired producer references are historical,17-seam gate and regeneration checks pass. [Governance sweep](governance-sweep.md). | Full478, Python456, hosted34683617524 and fresh offline483 pass; this record closes the stage-wide obligations. |

Full478 at merge9762cf593 passes with3 executed/366 up-to-date tasks and the same
represented counts as455. No modules changed since8f8; Java proof is reused only on
that unchanged-input basis. Focused hook/tooling486-495 and all13 successful hosted
jobs at35d03f7c4 cover the newly merged agent/lifecycle tooling and import correction.
[Hosted evidence](hosted-ci.md) includes inspected XML, native logs and axe captures.
Python456 remains3640 passed,16 skips,83 warnings; prior failures remain recorded.

Fresh offline483 closes the remaining live arm: full readiness,469/469 materialized
files and9720/9720 searches with zero errors. The owned evaluator exits0 and stack
stops clean:none with portsClosed:true; its final artifacts are retained. SPLADE
reset/backfill churn is explicit in [live proof](final-live.md), not hidden by the
final pass. Optional index settling and E quality/latency claims are not inferred.

A read-only independent reconciliation at35d03f7c4..a480ac6f6 found offline483 and
this closure record as the only outstanding C1 obligations. Root verified the final
summary/timeline, hosted XML and source explanation after it completed. No required
C1 row is red or unperformed. The persisted urgency flip stays C2; live retained
producers stay D1/D2; process-memory and cross-platform measurements stay E.

C1 closure is a stage boundary, not a merge or pause. Fresh origin/main is already
contained (fetch2026-09-12); C2 begins next with its re-grounded contract. C2/D1/D2/E/F
remain mandatory, with merge placement F/PR1 (#718). Retain raw evidence through lane
acceptance plus30 days and export it before releasing the held worktree.
