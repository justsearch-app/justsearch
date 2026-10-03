# C1 batch 2: required contexts and durable provenance

Status: complete, locally verified and independently reviewed on 2026-09-09.
Candidate: working tree above `dd11e372d` on `worktree-lane-F-A`, Windows/Temurin 25.0.2.
The required build, full suite and live model plumbing proof below pass. Hosted C1 proof remains
at the design's final publication boundary; this is a local batch checkpoint, not C1 completion.
Merge placement remains F/PR1; no separate C1 publication is implied.

## Implementation and ownership

SearchPort, IndexingService and DocumentService take required EngineContext parameters. The
KnowledgeClient facade remains one shared owner; every asynchronous callback forwards context
into EngineKnowledgeClient and the Worker CallContext. Agent runs, resumes, forks, workflow
children and final URL actions preserve the current caller while rebasing transport/trust and
run correlation at the existing owning boundary. Pending authorization carries the exact
original context and InvocationProvenance through server-side execute:true.

The HTTP filter resolves a single cached request context after security checks. Client labels
are cooperative attribution, never authority. MCP forces MCP/UNTRUSTED. Browser unknown hints
retain the established BUTTON fallback; known backend-only transports are rejected. Work axes
are front-owned. Completion spans consume the cached context and export only the existing
originator projection plus transport, excluding client/session/grant identifiers.

The Engine bridge projects originator through ActionLedgerProjection. V14 adds nullable
originator/transport to jobs and ingestion_ledger, preserving legacy-null data and transactional
migration rollback. Atomic claims snapshot provenance through extraction, stale resolution and
writing. An explicit legacy-null snapshot never borrows a later caller on the same path.

Normal internal maintenance preserves prior jobs attribution; explicit admissions replace it.
Versioned UPSERT and SYNC_ROOT codecs retain attribution through restart/cutover replay and
reject malformed/future payloads without acknowledging loss. SYNC_ROOT has one shared codec
across admission, coalescing and replay. Its typed queue method uses the existing queue lock to
preserve prior buffered attribution on null maintenance while retaining incoming root/force.
Explicit replacement remains possible; unreadable prior work refuses maintenance replacement.
No new database, state machine, key namespace or authorization representation was introduced.

## Indexing method migration map

All methods below take the caller context, including defaults and unavailable implementations.
The port catalogue enumerates typed callers, composition carriers and adapter chains.

| Method family | Context forwarding owner |
| --- | --- |
| getWatchedPaths, getWatchedRoots, addWatchedPath, addWatchedRoot, removeWatchedPath, removeWatchedRoot, clearAllRoots | KnowledgeClient and RootLifecycleOps; agent roots receive their run context |
| reindex, both reindexWatchedRoots overloads, reconcileRoot | RootLifecycleOps/SyncOps, explicit sync and root scan producers |
| deleteDocsByPathPrefix, deleteDocById, deleteDocsByCollection | KnowledgeClient, Engine ingest bridge and Worker service |
| startMigration, requestCutover, rollbackMigration, pauseMigration, resumeMigration, runIndexGc | MigrationOps and Engine ingest bridge |
| settleIndex, resetIndex, reloadRuntime, flush | KnowledgeClient and Engine ingest bridge |
| listFailedJobs, listFailedJobsByPathPrefix, clearFailedJobs, countJobsByPathPrefix | Index-status/read helpers and Engine ingest bridge |
| recentIngestionEvents, ingestionOutcomeSummary, resolvePathHash, cancelIndexingJob, retryIndexingJob | Diagnostic/operation handlers and Engine ingest bridge |

## Verification and retained evidence

Raw logs are in the lane worktree `tmp/c1-batch2-*`; XML snapshots are in
`tmp/c1-batch2-xml/`. Retain through lane completion plus 30 days. Snapshot directories may
contain prior module results; only tests named by their corresponding command/log are proof.

| Check | Result and evidence |
| --- | --- |
| Required context reaches actual search/read/mutation bridge | PASS: EngineContextPortPropagationTest in review-corrections-2; both axes and Worker CallContext asserted |
| Real Engine MCP submission to terminal SQLite outcome | PASS: claim-tests-2; EngineRootInProcessPortsTest checks searchable content and agent/MCP ledger row |
| Engine submission during SWITCHING, reopen and real replay | PASS: review-corrections-2; EngineContextPortPropagationTest |
| V13 migration, rollback, restart, claim snapshot | PASS: claim-tests-2 and coalescing-tests-1; IngestionProvenancePersistenceTest |
| Claim snapshot regression sensitivity | Expected FAIL: claim-mutation-both-red, preserved XML; old lookup produces user for both expected agent and expected null; implementation restored |
| Normal agent/internal/CLI sync projections and buffered replay | PASS: coalescing-tests-1; SyncDirectoryCallerProvenanceTest, SyncRootReplayProvenanceTest, WorkerIngestServiceTest |
| Buffered maintenance regression sensitivity | Expected FAIL: coalescing-mutation-red-2, expected agent/MCP but actual null; implementation restored. First mutation attempt only failed compilation and is not regression evidence |
| Request logging/export, approval, unknown-header compatibility | PASS: review-corrections-2; RequestEngineContextTest, LocalApiServerRequestSpanTest, TracingLocalExportTest, OperationsControllerTest, UpgradeControllerTransactionTest |
| Agent fresh/resume/fork, URL/workflow child context, cache misses | PASS: final full suite; app-agent 672, app-agent-api 228, app-services 2534 (3 skipped) |
| engine-port and register-guard-resolution | PASS: engine-port-2 and register-guard-1 |
| store-recoverability | PASS: store-gate-1, V14 register with all earlier versions readable |
| Derived artifacts (except notices) | PASS: regen-all-1, seven generated sets; docs regenerated after subsequent canonical edits |
| Full build including every Java source set and PMD | PASS: full-build-6, `./gradlew.bat build -x test -PskipErrorProneTests=false --continue --console=plain`; prior attempts exposed fixture migrations, unused local-only context parameters and redundant qualifications; none suppressed |
| Full suite | PASS: full-suite-3, `./gradlew.bat test -PskipErrorProneTests=false --continue --console=plain`; 9503 tests, 0 failures/errors, 25 skipped across 34 modules. Full-suite-2 ran all changed modules and exposed only 20 old reflection signatures in worker-services; corrected fixtures reran green in full-suite-3, other results reused without intervening changes. Final XML: `full-suite-green`; counts: `full-suite-green-counts.json` |
| Final gates and generated artifacts | PASS: final-gate-0 (engine-port), final-gate-1 (store-recoverability), final-gate-3 (canonical links), final-regen-all (seven generated sets). The first final regen invocation used a nonexistent scripts/docs path; corrected to scripts/ci/regen-all.mjs, no product change |
| Live API plus real model query | PASS for plumbing: live-search.json, live-state.json, live-chat-2.sse and live-chat-summary.json; owned run a0dacbc2-580c-4d19-9e09-e9ae8fbca937, worktree distribution, one Engine PID 21632, API 64213, compact Qwen3.5-4B-Q4_K_M with CUDA12. Hybrid search returned 5 hits in 409 ms and actual dense/cross-encoder scores. HTTP 200 summarize loaded a real indexed document, generated 2048 tokens and scored 104 sentences against its citation source. This token-limited compact output is not a quality acceptance |

The live directory is `tmp/c1-context-live-data`; the owned stack was stopped after proof.
The first launch inherited the old Claude session identity from the attached MCP and was reaped
as abandoned. Explicit current Codex sessionId fixed ownership; a relative dataDir fixed the old
MCP's double worktree-prefix projection. Its preflight also still required the retired workerDist:
the worktree server already removed that requirement, and its normal start runner installed and
launched the single Engine distribution. No fake Worker artifact or ownership bypass was used.
The first 256-token summarize reached only reasoning output; the 2048-token run exercised the
document/generation/citation path with nonempty output but hit its cap. These are model-plumbing
observations, not standard-model quality measurements. Request-span export is established by the
roundtrip/privacy tests above; the default live trace sampling is not claimed as an export proof.

## Independent review

Read-only ingress and queue reviewers checked trust projection, original approval context,
agent/run correlation, queue migration/recovery and terminal attribution. Accepted findings
were missing request-span export, explicit sync propagation, claim-vs-current-row attribution,
malformed replay false acknowledgements and maintenance replacement of buffered attribution.
The root consolidated corrections after the bounded review rounds and retained runnable
regressions and adverse mutation evidence. A final read-only queue review accepted the typed
coalescing correction; the post-restoration full suite supplies its final green proof. No finding
is owner-gated or waived.

C1 batch 3 owns admission, cancellation reasons and urgency accounting; batch 4 owns bounded
executors and the final live envelope proof. C2 starts only after this batch is reviewed and green.

## Evidence integrity

Paths below are worktree-relative and accessible on this machine; hashes supplement access.

| Artifact | SHA-256 |
| --- | --- |
| `tmp/c1-batch2-full-build-6.txt` | `80d7aeab04b4987568c5640d6ae198bce734c6bc7a23e929a4b51b66c0efdd1f` |
| `tmp/c1-batch2-full-suite-2.txt` | `01c9e7fef2c13753e066b2897d9ea31b823608ad8c55d814a7a6b591fb196372` |
| `tmp/c1-batch2-full-suite-3.txt` | `736a1fe3bae3ee867cc7ada5ac48efa0901449fa1510ea869d21147516e3c7a7` |
| `tmp/c1-batch2-full-suite-green-counts.json` | `6256ce94d08adb11a83559de35fdc5ce4b8087301f3176e0195c222309b7ba5a` |
| `tmp/c1-batch2-claim-mutation-both-red.txt` | `7dca54bbe115623d5370f1d3118736891a2c2cece2a6135032a04bc860ef6ad8` |
| `tmp/c1-batch2-coalescing-mutation-red-2.txt` | `2ce6b746562c3191417c5aa8ce3e76ce67a8c4666cb8c5d5c9eee923de5b1eea` |
| `tmp/c1-batch2-live-search.json` | `608996d49ce89a7581fb6bbad438daac3892abe1d1b83007b7c9a46314541bc9` |
| `tmp/c1-batch2-live-state.json` | `ebc3fd978f676dd5c45be1a51197ef8768fef7af2dc78a681482387e17547696` |
| `tmp/c1-batch2-live-chat-2.sse` | `7d0dcae53d8f1d574833ec270daf571f0d9bba5a550bbdd51edb7cb5bb3cc2ba` |
| `tmp/c1-batch2-live-chat-summary.json` | `16a9201b24e6cb7d347ff9cab46cee6d81719c95c7422a27892536483adb971d` |
| `tmp/c1-batch2-final-regen-all.txt` | `2763674f267e0ba516d1254a2526a779bf05189720fa7480e8998615e86ed125` |
