# C1 acceptance reconciliation

Status: OPEN, candidate8f8c7d775da4f4f69bc21c8399e3c69d0a90cd41,2026-09-09.
This table maps the current stage contract to evidence; implementation, local verification,
hosted verification and live acceptance are separate. No pending row below is stage completion.

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
| C1-10 | Foreground actual dispatch increments exactly once; background and observer polling do not. Root walk and handoff corrections retain actual work. [Corrections](independent-review-fixes.md), [final candidate](final-candidate.md). | Standard-active primary460 passes all469 files and308/308 searches; chat-offline full enrichment was paused before completion (NER404/469); [partial proof](final-live.md). |
| C1-11 | Durable work detaches urgency; interactive cancellation retains actual child lifetimes. Double-release and shutdown freeze are mutation-proven. [Cancellation sweep](cancellation-sweep.md). | Persisted operation-row urgency flip belongs to C2, as stage section9 specifies. |
| C1-12 | Packaged retained policy equals authority, duplicate activation refuses, caps and stale-handle release are tested; resource-policy6 and retained-budget3 pass. [Batch1](batch-1.md). | All five accounting producers await D1/D2: cursors/readers in D2; generations, co-resident encoders and attempted configurations in D1. Current generations can retain three against target two; there is no live bound claim. |
| C1-13 | Direct-memory launcher pins and [sourced memory budget](memory-budget.md) recorded with [batch1](batch-1.md); later launch verification unchanged. | Process memory/heap-growth measurements belong to E. |
| C1-14 | Broken-child routed-family refusal preserves plain-text ingestion; extraction confinement and routing seam are mutation-proven. Windows native descendants are witnessed and terminated. [Parser containment](parser-containment.md), [governance](governance-sweep.md). | Named VDU exception, IN_PROCESS operator override and non-Windows proof limits remain explicit in the stage/design. |
| C1-15 | Admission-aware clients honor Retry-After only with retrySafe; parity, abort and no error-toast behavior tested. Typecheck437 and6474 unit442 cases pass. [Batch3](batch-3.md), [dependency correction](dependency-advisory.md). | None within this item. |
| C1-16 | Whole-program dead-code test passes, retired producer references are historical,17-seam gate and regeneration checks pass. [Governance sweep](governance-sweep.md). | Current hosted and Python pass; chat-offline full enrichment and closure record remain stage-wide obligations. |

Hosted34390502944 is green at preceding7ff787cf3. Current8f8 CI34392044686 is fully green; full Python456 passes3640 cases with16 skips and83 warnings. Prior failed448 and live423 remain recorded; no successful narrow rerun erases
them. Merge placement stays F/PR1, and C2/D1/D2/E/F remain mandatory after C1 closure.

User-requested pause: evaluation470 is interrupted, not a pass. The stack is stopped and
raw data retained. C2 implementation has not started. See [live proof](final-live.md).
