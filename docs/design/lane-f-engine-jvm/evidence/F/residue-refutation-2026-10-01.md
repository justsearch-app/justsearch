# F-1/F-5 refutation repair, 2026-10-01

Design recorded before implementation at `248876013`, following review of `47cdea384`.
The original 1,373-hit disposition counts remain historical evidence, not proof of the
current tree's prose. No build, npm suite, stack, commit or publication is authorized here.

The central allowlist remains a projection of the reviewed disposition, but file × term
alone is insufficient. Require exact repository-relative paths with no wildcard syntax.
Each entry records `occurrenceCount` and an `anchors` array of trimmed matching line texts,
one element per term occurrence (including duplicates). Compare multisets, consuming an
anchor for each occurrence. New text, replacement text, excess copies and missing anchors
fail; shifts in line numbers and surrounding indentation do not. Count must equal the
nonempty anchor array length. Scan all tracked/pending files for staleness even with
`--paths`. Only existing reviewed entries are regenerated after semantic repairs; entries
whose terms disappear are removed, not silently expanded to unrelated hits.

## Verified corrections

- Bootstrap readiness/recovery: `KnowledgeServerBootstrap.java:326-329,359-383,862-868`
  operates on a bound index component and uses `INDEX_FAILED`; the taxonomy is
  `LifecycleReasonCode.java:19-35` (component/index codes). Remove claims that the old
  reason-code vocabulary survives and its exemption. Also correct the adjacent start-budget
  recovery comment and stale signal-bus/gauge description while in the owner.
- Shutdown: `KnowledgeServerBootstrap.java:980-984` stops `energyPoller` before client/host
  teardown; `GpuSchedulingGauge.java:57` has no close/unmap. State this order only.
- MCP fallback: `RemoteDocumentService.java:408-413` catches a port-call exception and
  `:668` returns the compatibility reason string `GRPC_FAILED`. Explain that distinction in
  `McpToolSurface`, preserving the emitted string.
- Pacing: shared `modules/ui/src/main/resources/logback.xml:136` controls
  `io.justsearch` with `JUSTSEARCH_LOG_LEVEL`; no separate logging configuration.
- Watched roots: `KnowledgeClient.java:164-170` owns the watched-roots state and
  `WatchedRootsStore.java:44-51` owns persistence. Correct the dev-script pointer.
- Queue metrics: `CoreApiAssembly.java:328-333` publishes arrays through
  `publishFromValues`; `StatusLifecycleHandler.java:389-395,736-738` supplies the view tap.
  Describe array projection and the producer's separate optional RRD tick, not a push
  transport into a gateway RRD.
- Runtime lease/install: `RuntimeStatus.deriveLease` returns the holder's name;
  `AiInstallService.java:2474-2483` reports restart guidance and returns false without
  restarting a component. Describe configuration stages and the procedure bracket.
- Runtime-image dependencies: `modules/ui/gradle.lockfile:8` includes Logback classic
  1.5.32. Its cached `module-info.class` names `java.naming`; its
  `ContextJNDISelector.class` references `javax/naming/Context` and `NamingException`.
  Use this dependency reason, not index service discovery. The local Temurin 25
  `lib/src.zip` entry `java.naming/module-info.java` explicitly contains
  `requires java.security.sasl;`, verifying that module's inclusion as a transitive JDK
  dependency. Module lists and dependency declarations remain unchanged.

## Audit of the other prior rewrites

Review every added rewritten line in `git show 47cdea384 --unified=0`, excluding the
disposition tables/allowlist projection itself. Follow mechanism claims to the current
owner; neutral DTO/port-boundary descriptions and fixture prose need no invented transport.
The following additional claims already fail that review and will be corrected:

- `RunningRuntime`, `RuntimeSession`, `IndexingCoordinator`, `WritePathOps`: universal
  ISE → UNAVAILABLE/retry mapping is false. `IndexingCoordinator.java:511` actually throws
  `IndexRuntimeIOException(DRAINING)`; `WritePathOps.java:812` throws ISE;
  `EngineKnowledgeClient.java:557-561` translates only `WorkerServiceException`, while
  `WorkerIngestService.java:865-867` maps a queue ISE to INTERNAL. Describe local guards.
- `IngestionDiagnosticsContractTest.java:137-139,295-297,322-325`: no circuit breaker exists
  (`EngineKnowledgeClient.java:49-50`); neither fixed model-load timing nor automatic
  warm-up success is guaranteed. Describe tolerated HTTP statuses/I/O retries within
  the test's own deadline.
- `EnvRegistry.java:1560-1564`: no port handshake or two independent process environments.
  `WorkerHealthService.java:289-291` projects these keys into effective configuration.
- `ResolvedConfig.java:921`: batching is a local operation limit, not transport framing.
- `modules/indexer-worker/README.md:22-24`: the removed thing is gRPC protocol service
  declarations/generator, not the live in-process port layer.
- `contract_events.proto:16-22` and `contractEvents.ts:25-27`: additive changes are not
  inherently validator-safe; retain the actual four-event schema and absence of the
  version-change variant without claiming automatic compatibility.

## Implementation and additional audit corrections

All five review findings are repaired. The bootstrap's remaining transport exemption
now covers only the reviewed negative assertion that calls are direct, and its historical
process/log comparisons. Its reason-code exemption was removed. The MCP and dev-script
transport/name exemptions were removed entirely. Pacing's obsolete logging sentence was
removed; its two comparisons to old pacing behavior remain explicitly anchored history.

The complete added-line diff of `47cdea384` was re-read in module groups, including the
Kotlin, proto, properties, Python, TS and README changes. Besides the requested repairs,
the audit corrected these claims (not just spellings):

| Additional correction | Current owner/evidence |
|---|---|
| Four adapter drain comments no longer promise universal status conversion/retry | `IndexingCoordinator.java:508-513`, `WritePathOps.java:810-813`, `RunningRuntime.java:190-192`, `RuntimeSession.java:227-229`; actual translator `EngineKnowledgeClient.java:557-561` |
| Test no longer asserts a circuit breaker, fixed warm-up duration, or inevitable success | `IngestionDiagnosticsContractTest.java:137-139,294-296,320-322`; direct client has no breaker (`EngineKnowledgeClient.java:49-50`) |
| Effective configuration is a health DTO projection, not a handshake between environments | `EnvRegistry.java:1560-1562`; `WorkerHealthService.java:289-291` |
| Batch/queue limits are operation limits, not transport concerns | `ResolvedConfig.java:919-923`; `WorkerIngestService.java:104-107,768-776` |
| Removed protocol service declarations distinguished from live ports | `modules/indexer-worker/README.md:22-24`; message-only `modules/ipc-common/src/main/proto/indexing.proto` |
| Contract-event comments no longer assert automatic validator compatibility | `contracts/wire/contract_events.proto:16-21`, `contractEvents.ts:24-27`; four variants remain unchanged |
| Metric siblings/view tap describe array projection without assuming an empty gateway RRD | `DocumentsIndexedRateMetricProducer.java:103-105`, `StatusLifecycleHandler.java:389-391`; `CoreApiAssembly.java:328-333` |
| Loop/encoder publication comments no longer invent a particular caller thread | `IndexingLoop.java:1359-1361` (thread-start visibility), `EncoderBindings.java:21-23,45` (volatile snapshot) |
| Session-policy operation and search response described as a port call and DTO | `WorkerAppServices.java:133-134`, `SearchResponseBuilder.java:15`; `KnowledgeClient.java:1340-1342` |
| Annotation ownership no longer claimed to need a future foundational-module move | `NativeSessionHandle.java:49-54`; existing `modules/ort-common/build.gradle.kts:24` dependency and `modules/core-contracts/.../BuildContract.java` owner |
| Model discovery describes a DTO consumed by the API gateway, not a separate Head process | `WorkerModelDiscovery.java:14`; health projection `WorkerHealthService.java:275-285` |

Also corrected the bootstrap's signal-bus opening, transitional dual publication and
adjacent health-budget recovery narration; removed the obsolete React reference from the
queue metric window comment; corrected a grammar error in `IndexGenerationManager`.
The two fixture consistency repairs in `AnswerSegmentationTest.java` and
`MarkdownBlock.anchoring.test.ts` appeared during this turn through concurrent work and
were preserved: the sentence and assertions now both use “API layer”. They are included
in the handoff manifest without claiming them as edits made by this agent.

## Allowlist projection and verification

388 entries replace the original 391, with 1,184 reviewed term occurrences:

| Category | Entries | Occurrences |
|---|---:|---:|
| b: C2-1 compatibility identities | 33 | 70 |
| c: legitimate vocabulary / explicit historical comparisons | 322 | 1,021 |
| d: guards / checker fixtures | 33 | 93 |

Removed entries: `KnowledgeServerBootstrap.java × WORKER_`,
`modules/ui-web/scripts/dev-all.cjs × RemoteKnowledgeClient`,
`McpToolSurface.java × gRPC`. No new path/term exemptions were introduced. Remaining
anchors were regenerated from the corrected tree; duplicate text is recorded once per
term occurrence. The expanded self-test's reviewed liveness fixture anchors were refreshed
after adding the exact reported transport counterexample. The original category counts in
`residue-dispositions-2026-10-01.md` classify the original 1,373 matching lines and should
not be compared directly with this term-occurrence count.

Light proof on `248876013` plus this working diff:

- `node --check scripts/ci/check-lane-f-residue.mjs`: exit 0.
- `node --check scripts/ci/test-check-lane-f-residue.mjs`: exit 0.
- `node --check modules/ui-web/scripts/dev-all.cjs`: exit 0.
- `node --test scripts/ci/test-check-lane-f-residue.mjs`: exit 0, one passing test file.
  Assertions cover appended/replaced same-term text, shifted lines/indentation,
  repeated text and multiple occurrences per line, stale entries, wrong terms, every
  wildcard grammar, out-of-scope unanchored hits, and the exact reported gRPC counterexample.
- `node scripts/ci/check-lane-f-residue.mjs`: exit 0, no residue/stale diagnostics.
- `git diff --check`: exit 0.

Git emits an advisory inability to access the home-directory ignore file under the
sandbox; tracked/worktree scanning still succeeds. No Gradle/npm checks or stack were
run. No compilation or frontend suite success is claimed. No code/schema value changes
in this repair need regeneration: proto changes are comments only; the existing status
lease field remains an unconstrained string. No lock regeneration is required because
the build-file edits only correct module rationale comments.

Root verification commands:

```text
./gradlew.bat build -PskipWebBuild=true --continue
cd modules/ui-web && npm run typecheck && npm run test:unit:run
node scripts/ci/run-ui-web-gates.mjs
```

## Java/TS files touched since 47cdea384

The manifest below is the complete `git diff 47cdea384 --name-only -- '*.java' '*.ts'`
projection at handoff (22 Java, 2 TS). There are no additional Java/TS differences between
`47cdea384` and the merged `248876013` HEAD. Build compilation and suites belong to root.

- `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/IndexingCoordinator.java`
- `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/RunningRuntime.java`
- `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/RuntimeSession.java`
- `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/WritePathOps.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/observability/metrics/DocumentsIndexedRateMetricProducer.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/observability/metrics/JobQueueDepthMetricProducer.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/runtimestate/RuntimeStatus.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeServerBootstrap.java`
- `modules/configuration/src/main/java/io/justsearch/configuration/EnvRegistry.java`
- `modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedConfig.java`
- `modules/ort-common/src/main/java/io/justsearch/ort/NativeSessionHandle.java`
- `modules/reranker/src/main/java/io/justsearch/reranker/WorkerModelDiscovery.java`
- `modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/api/IngestionDiagnosticsContractTest.java`
- `modules/ui-web/src/api/contract/contractEvents.ts`
- `modules/ui-web/src/shell-v0/components/chat/MarkdownBlock.anchoring.test.ts`
- `modules/ui/src/main/java/io/justsearch/ui/api/StatusLifecycleHandler.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java`
- `modules/worker-core/src/main/java/io/justsearch/indexerworker/index/IndexGenerationManager.java`
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/IndexingLoop.java`
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/pacing/IndexingPacing.java`
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/server/EncoderBindings.java`
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/server/WorkerAppServices.java`
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/respond/SearchResponseBuilder.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/AnswerSegmentationTest.java`
