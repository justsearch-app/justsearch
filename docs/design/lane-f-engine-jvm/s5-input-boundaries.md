# S5 input boundary map and build handoff

The two committed S5 drafts are retained as implementation inputs. This map records the
remaining corrections and their checks. X05-F1 and review 2 I1 are **DEFERRED-BY-ROOT**:
the orchestrator explicitly reserved durable root witnesses and handle-bound source reads
for a separate design follow-up. A persisted ingestion boundary is only an exclusion
boundary; it does not establish filesystem containment.

## Rendering and image allocation map

Limits are 8192 per dimension, 16,000,000 pixels per raster and 64,000,000 reserved
raster pixels per PDF. Charges are conservative, including repeated draws of cached
resources. A rejection is latched because PDFBox can swallow operator IOExceptions;
`PdfImageRenderer` checks the latch before saving any returned page.

| Path | Enforcement before allocation |
| --- | --- |
| VduBatchProcessor -> VduProcessor -> PDF pages | `PdfImageRenderer.render` validates every selected crop box at 100 DPI before the first render. All selected pages reserve two rasters each against the renderer's shared budget, covering the page and an optional blend-mode RGB copy. Rotation swaps dimensions without changing area. The existing 50-page cap remains. |
| Standalone images and rendered PNGs -> VduProcessor raw legibility read | `VduImageLimits.read` checks dimensions on the same ImageReader/stream used by `read(0)`. Only the first frame is read. The processor-level raw-read regression checks the actual call site. |
| ImagePreparer -> JPEG preparation | The same guarded reader precedes decoding. RGB conversion only allocates at or below 1280 by 1280. `scaleToFit` computes its bounded target before constructing the raster. |
| ImageLegibility -> scale/grayscale/Laplacian | The only caller supplies an already guarded image. Analysis first bounds the long edge to 512, before allocating grayscale rows and Laplacian responses. |
| PDF image XObjects, including forms, annotation appearances, Type 3 content, patterns and transparency groups | `BoundedPdfRenderer.drawImage` charges source dimensions before `super.drawImage` can decode. Reservations cover source samples, RGB conversion and transfer/stencil conversion. Color-key masks reserve two additional rasters. Nested content uses the same drawer and document counter. |
| Inline PDF images | `processOperator(BI)` charges the image dictionary before PDFBox constructs PDInlineImage and inflates its data. Both long and abbreviated dictionary keys are checked. |
| Filter decode parameters, including inline images and explicit/soft masks | `checkFilters` validates every filter/parameter pair before a decoder or JPEG-header prefilter runs. Full/abbreviated aliases may not conflict; parameter arrays must correspond to filter arrays. Flate/LZW predictors validate integral Colors, BitsPerComponent and Columns, use checked row arithmetic and reserve both row buffers in four-byte pixel equivalents against the same document counter. CCITT validates its effective dimensions and reserves its output/row buffers and two column-change arrays. Only codecs with established parameter allocation behavior are permitted; ambiguous or unknown combinations fail closed. |
| JPEG codestream dimensions | The JPEG header is inspected without decoding and must match the already bounded dictionary. Inline multi-filter JPEGs are refused before construction. JPX and JBIG2 are refused before decoding because their allocation dimensions cannot be established by this guard. |
| Explicit image masks and image soft masks | Dictionary recursion checks and charges each mask, rejects cycles, then checks the composed rectangle using the maximum source/mask width and maximum source/mask height. Two composed rasters are reserved for the scaled gray mask and ARGB output before any image decode. |
| Image placement and smooth scaling, including pattern stencil paint/mask rasters | `drawImage` bounds the CTM-transformed unit rectangle at device DPI before PDFBox's un-clipped composition/scaling. Three destination rasters are reserved. Source conversion/inversion is included in the source reservation. |
| Transparency groups and backdrops | `showTransparencyGroup` computes the transformed group box intersected with the current clipping path, adds outward-rounding allowance and reserves two rasters before the group's constructor allocates. Nested groups share the counter. |
| Graphics-state soft masks, gray conversion and rotation adjustment | `getPaint`, `drawImage`, `showTransparencyGroup` and `shadingFill` charge the clipped mask group before PDFBox applies it. Four rasters cover group/backdrop, gray conversion and adjusted gray output. Page rotation can swap the dimensions. |
| Tiling pattern cell raster | `getPaint` checks the pattern steps, initial/pattern matrices and device DPI before calling PDFBox's tiling paint factory. Nested pattern content retains the same drawer/counter. |
| Java2D drawing and shading tiles, PNG/JPEG encoding | Their destinations are the already bounded page, group, pattern, preparation or analysis rasters. Encoding does not introduce an untrusted-size bitmap decode. |

The composition dimensions match the pinned
[PDFBox 3.0.6 applyMask implementation](https://raw.githubusercontent.com/apache/pdfbox/3.0.6/pdfbox/src/main/java/org/apache/pdfbox/pdmodel/graphics/image/PDImageXObject.java).
Group clipping, pattern/stencil intermediates and smooth scaling were checked against
[PageDrawer](https://raw.githubusercontent.com/apache/pdfbox/3.0.6/pdfbox/src/main/java/org/apache/pdfbox/rendering/PageDrawer.java)
and the pinned TilingPaint/PDFRenderer sources. These are raster bounds, not a general
PDF parser heap quota; VDU's permitted in-Engine placement remains as designed.

Regressions: `VduImageLimitsTest` includes page/aggregate bounds, XObjects inside forms,
inline images, JPEG header disagreement, explicit/soft masks, opposite aspect ratios,
composition aggregate charges, transformed placement, group/graphics-mask charges and
pattern cells. Positive ordinary JPEG, PDF and mask fixtures retain rendering coverage.
`VduProcessorAbstentionTest#oversizedRawImageIsRejectedBeforeLegibilityMeasurement`
guards the processor call site rather than only the helper.

## Indexing queue entry and exclusion map

An explicit directory root is admitted as the boundary even if its own basename matches
an excluded directory. Excluded ancestors above that boundary never affect the admission.
Descendant directories and filenames remain filtered. Consumer policy is rechecked using
the saved boundary, independently of later watch registration/removal.

| Entry route | Producer and consumer enforcement |
| --- | --- |
| Initial scan, recorded directory scan and force/manual directory reindex | `WorkerScanOps.preVisitDirectory` prunes excluded descendants, but not the requested root. Filename and request-specific excludes are applied during visitation. Each EnqueueEntry carries `withinRoot(root)`. |
| Live watcher CREATE/MODIFY | Every supported WorkerMethvinWatcher constructor uses a WitnessedUpsertSink or enqueues within the subscription root directly. The unwitnessed service/callback overloads were removed. `handleUpsert` checks `shouldSkipWithinRoot(path, witness.root())`; the witnessed WorkerIngestService route repeats the check and retains that root. |
| Queued-job replacement, maintenance resubmission and retry | SqliteJobQueue enqueue/reenqueue SQL preserves the old ingestion_root when maintenance supplies none. A new rooted admission replaces it with its actual boundary. Polling returns that persisted boundary; current watchers are never used as a fallback. |
| Reconciliation and candidate discovery | SyncDirectoryOps prunes excluded descendants and filenames. Its queued entries retain the sync root. WorkerIngestService's candidate/root convergence retains that root in captured entries. |
| Manual explicit-file submission or recorded single-file scan | WorkerIngestService.submitBatch uses the file itself as the boundary. Single-file scans retain their file root. This preserves intentional file submissions without checking unrelated ancestors. |
| Candidate generation switch buffering and replay | SwitchBufferUpsert version 3 retains the boundary; queue and buffer restart preserve it. Historical raw/v1/v2 payloads without a boundary remain unrooted when replayed. |
| Upgrade/restart of pre-boundary pending or processing work | Migration does not invent authority. `WorkerIngestionAuthority.admit(IndexJob)` retires an unrooted claim as SKIPPED_POLICY/MISSING_INGESTION_BOUNDARY before freshness capture, hashing or extraction. A fresh scan, witnessed event or explicit-file request is needed to re-admit it. Unknown legacy explicit-file intent cannot safely be reconstructed. |
| Consumer for every modern route | WorkerIngestionAuthority checks the filename and root-relative descendant directory names before source admission. The path-only overload represents an explicit-file admission; queued production work uses the IndexJob overload. |

Regressions: WorkerMethvinWatcherTest's CREATE/MODIFY fixture; WorkerScanOpsTest's
pruning and explicit excluded-name root fixtures; IngestionSkipPolicyTest's boundary
tests; WorkerIngestionAuthorityTest's changed-policy and legacy/replay tests;
LegacyIngestionBoundaryTest's real SQLite V21 migration, switch replay, unwatched
restart and maintenance replacement tests; JobBatchExtractorForcedPathTest's missing
boundary extraction refusal. Existing ingestion tests now give intentional file claims
explicit file boundaries so they continue exercising extraction, not the legacy refusal.
JobQueueTest and SwitchBufferVersionTest retain persistence/replacement coverage.

## Extraction and archive budget map

The committed draft already fixes original X05-F3 and review 1 I2. No archive code is
changed in this follow-up. Limits remain input/output caps plus 256 embedded resources,
depth 8 and compression ratio 100 by default; configured policy values are retained.

| Extraction path | Counter/limit enforcement |
| --- | --- |
| PolicyDrivenTikaExtractor, in-process or extraction child | PreparedExtractionInput copies through an input-size cap into a private snapshot. ContainerExpansionBudget inspects this immutable snapshot before MIME detection/structured parsing. Office input caps and artifact validation remain. |
| Standalone StructuredContentExtractor | The public entry point prepares the same bounded snapshot and expansion inspector. Structured parsing installs EmbeddedResourceBudget in its ParseContext. |
| Standalone ContentExtractor | The public entry point prepares a snapshot and installs the same embedded budget before flat parsing. |
| Structured -> flat fallback | The fallback retains the same ParseContext, embedded counters, depth and expansion budget. Latched limit violations are checked before fallback and after parser success/failure, including exceptions swallowed by Tika. |
| ZIP and specialized ZIP-based Office/ODF/EPUB document internals | ContainerExpansionBudget reads actual inflated bytes for every central-directory member and, for ordinary ZIP signatures, every local-header member. Per-member, per-package and shared document expansion limits precede the native parser. Comments and other non-output XML count. A local-only uninspected member is refused. |
| Generic archive/compressor members and nested embedded documents | EmbeddedResourceBudget checks count/depth before delegation, records actual resources/max depth, and spools expanded bytes through a cumulative expansion cap before a nested parser gets a seekable stream. Nested ZIP packages also use the shared container inspector. |
| Artifact creation/validation | StructuredExtractionResult carries observed resource/depth counters into ExtractionArtifact; validation checks those counters instead of fabricated zeros. Specialized package parts without embedded callbacks correctly report zero embedded resources while still receiving container expansion checks. |

Pinned Tika 3.2.3 PackageParser, CompressorParser and RarParser were inspected: member
parsing uses EmbeddedDocumentExtractor. Directory entries do not become embedded
resources; ZIP directory payload expansion is nevertheless counted by preflight.
Generic stream expansion and specialized package expansion have separate conservative
work counters, both retained across fallback and bounded by the configured policy.

Regressions retained: ArchiveExtractionLimitsTest's 300-member/deep/expansion ZIPs,
strict-ratio ODT/XLSX comments, embedded XLSX, flat ODT, fallback policy retention and
immutable-input test; PolicyDrivenFormatCapabilityTest's nonzero generic ZIP counters and
zero-callback specialized packages.

## Orchestrator build commands

Run the focused checks first, then each affected module's full tests and hygiene checks:

```powershell
./gradlew.bat :modules:app-services:test --tests '*VduImageLimitsTest' --tests '*VduProcessorAbstentionTest'
./gradlew.bat :modules:worker-core:test --tests '*IngestionSkipPolicyTest'
./gradlew.bat :modules:worker-services:test --tests '*ArchiveExtractionLimitsTest' --tests '*PolicyDrivenFormatCapabilityTest' --tests '*WorkerIngestionAuthorityTest' --tests '*WorkerMethvinWatcherTest' --tests '*WorkerWatcherEventOwnershipTest' --tests '*WorkerScanOpsTest' --tests '*SyncDirectoryOpsWalkSkipPolicyTest' --tests '*SyncDirectoryOpsCandidateDiscoveryTest' --tests '*WorkerIngestServiceCandidateReconciliationTest' --tests '*JobBatchExtractorForcedPathTest' --tests '*IndexingLoopTest' --tests '*IndexingLoopRestartTest' --tests '*IndexingLoopCutoverPauseTest' --tests '*AdversarialCorpusIngestionTest'
./gradlew.bat :modules:indexer-worker:test --tests '*LegacyIngestionBoundaryTest' --tests '*WatcherConstructorBoundaryTest' --tests '*JobQueueTest' --tests '*JobQueueMigrationTest' --tests '*SwitchBufferVersionTest'
./gradlew.bat :modules:app-services:test :modules:worker-core:test :modules:worker-services:test :modules:indexer-worker:test
./gradlew.bat :modules:app-services:spotlessCheck :modules:worker-core:spotlessCheck :modules:worker-services:spotlessCheck :modules:indexer-worker:spotlessCheck
```

No Gradle, stack, commit or publishing commands were run by this builder. Runtime module
verification is reserved for the orchestrator; isolated checks, if run, are reported
separately and do not replace these build commands.

## Observed checks

An isolated `javac` compilation against PDFBox 3.0.6, SLF4J and JUnit succeeded for the
VDU renderer/limits/preparer, TempFileManager, IngestionSkipPolicy and their two focused
test classes. This is not a module build. The isolated command was:

```powershell
java -Xmx768m '-Djava.awt.headless=true' '-Djava.io.tmpdir=tmp/s5' -cp 'tmp/s5/classes;tmp/s5/lib/*' org.junit.platform.console.ConsoleLauncher execute --select-class io.justsearch.app.services.vdu.VduImageLimitsTest --select-class io.justsearch.indexerworker.ingest.IngestionSkipPolicyTest --details summary --disable-banner
```

Result: **36 tests started, 36 successful, zero failures/skips**, exit 0. The source
files and disposable dependency jars were compiled under the worktree's ignored scratch
directory. No application server was started.

The new `oppositeAspectRatioMasksRejectCompositionBeforeDecoding` and
`compositionAndScalingShareTheDocumentAllocationBudget` methods were also run against
disposable copies of HEAD's committed draft renderer. Result: **two tests failed**, exit 1,
both with `Expected java.io.IOException to be thrown, but nothing was thrown`. This
confirms that the draft accepts their over-budget allocations. The current renderer
passes both methods in the 36-test run above.

`git diff --check` passed. No Node unit tests were run. SQLite migration/replay, worker
extraction/admission and archive tests still require the orchestrator's module build.

## Files changed in this follow-up

- `docs/design/lane-f-engine-jvm/s5-input-boundaries.md`
- `modules/app-services/src/main/java/io/justsearch/app/services/vdu/BoundedPdfRenderer.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/vdu/PdfImageRenderer.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduImageLimitsTest.java`
- `modules/worker-core/src/main/java/io/justsearch/indexerworker/ingest/IngestionReasonCodes.java`
- `modules/worker-core/src/main/java/io/justsearch/indexerworker/ingest/IngestionSkipPolicy.java`
- `modules/worker-core/src/test/java/io/justsearch/indexerworker/ingest/IngestionSkipPolicyTest.java`
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/WorkerIngestionAuthority.java`
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/WorkerScanOps.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AdversarialCorpusIngestionTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopCutoverPauseTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopRestartTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/JobBatchExtractorForcedPathTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/WorkerIngestionAuthorityTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerScanOpsTest.java`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/LegacyIngestionBoundaryTest.java`

## S5n independent-review follow-up

All three findings were verified against the committed source.

- I1: Candidate reconciliation still called the removed two-argument helper. It now passes
  the validated root. The shared helper rechecks root-relative exclusions before queue
  admission on every watcher/reconciliation route, and returns whether a job was admitted
  so policy-skipped discoveries do not inflate the response's added count.
  WorkerIngestServiceCandidateReconciliationTest now checks the captured entry's root,
  ordinary/forced pruning with an excluded ancestor above the root, and a policy change
  between discovery and the second admission. The original source cannot compile; the
  added assertions also reject a superficial signature repair that loses the boundary
  or bypasses the final policy check.
- I2: PDFBox shadingFill applies a graphics soft mask directly, bypassing getPaint.
  BoundedPdfRenderer now checks the rejection latch and reserves the mask's rasters before
  delegating shadingFill. The pinned PageDrawer's other applySoftMaskToPaint callers are
  covered by getPaint, drawImage or showTransparencyGroup.
  VduImageLimitsTest#repeatedShadingWithEmptyGraphicsMaskChargesEveryAllocation uses a
  valid axial shading and ten sh operators on a 2000-point square page, with empty alpha
  and luminosity mask groups. No incidental painting operator can make the old guard
  charge those groups. A small positive shading fixture retains ordinary rendering.
- I3: The second historical payload reused a database whose first iteration had installed
  an explicit-file boundary. Each unrooted replay case now uses its own database.
  LegacyIngestionBoundaryTest#historicalReplayPreservesTheExistingBoundaryAcrossRestart
  separately covers both historical payload formats over an existing directory boundary
  and over an intentional explicit-file boundary. Replay preserves the existing boundary:
  excluded descendants remain skipped, while explicit-file admission remains explicit.

Observed S5n checks: isolated javac compilation of the actual VDU classes and updated
VduImageLimitsTest passed. Its isolated JUnit run completed with **18 tests successful,
zero failures/skips**, exit 0. The repeated-shading regression run against HEAD's
committed renderer failed, exit 1, with **Expected java.io.IOException to be thrown,
but nothing was thrown**. This confirms the stated allocation bypass in the old code.
Only import ordering changed in that VDU test after the successful run.

Candidate helper invocations and the worker/SQLite tests' imports, method names and
signatures were checked against their real classes/interfaces. Their module compilation
and runtime execution remain pending; no Gradle or Node unit tests were run. The focused
build commands above include all three affected test classes. Scratch jars/classes were
created under tmp/s5n for isolated checks and removed afterwards.

S5n changes are limited to WorkerIngestService.java,
WorkerIngestServiceCandidateReconciliationTest.java, BoundedPdfRenderer.java,
VduImageLimitsTest.java, LegacyIngestionBoundaryTest.java and this handoff map.

## S5n2 independent-review follow-up

Both findings were verified against HEAD before editing.

- I1: Filter.getDecodeParams, Predictor, LZWFilter, CCITTFaxFilter and
  CCITTFaxDecoderStream were inspected at the pinned PDFBox 3.0.6 tag, including
  [Predictor](https://raw.githubusercontent.com/apache/pdfbox/3.0.6/pdfbox/src/main/java/org/apache/pdfbox/filter/Predictor.java)
  (the earlier reviewer could not retrieve its pinned source). Width and
  Height do not constrain predictor buffers or fax Columns. BoundedPdfRenderer now
  guards these independent parameters, filter arrays and inline aliases before decode,
  and charges the buffers to the shared budget. Unsupported or ambiguous codecs/
  parameter shapes are refused. VduImageLimitsTest adds real Flate/LZW XObjects,
  inline images, explicit/soft masks, checked integer conversion, fax dimensions,
  shared buffer-budget rejection and positive decoded-sample assertions.
- I2: The compatibility constructors adapted witnessed delivery into a two-argument
  callback, and WorkerIngestService queued that callback with a null root. Those
  unwitnessed callbacks and the service overload were removed; all supported
  constructors now retain the subscription. Production composition and its ownership
  test use the witnessed signature. WatcherConstructorBoundaryTest exercises CREATE
  through all six constructors with an empty SQLite queue per case, restart, actual
  consumer admission, exclusions below the root and excluded ancestors above it.
  Reflection accesses the existing package-private consumer without changing its API.

Observed S5n2 checks (isolated components, not module builds):

- Actual VDU sources and VduImageLimitsTest compiled with javac against PDFBox 3.0.6.
  The final JUnit run passed **26 tests**, zero failures/skips, exit 0.
- Actual WorkerMethvinWatcher, SQLite queue, consumer authority and dependencies,
  WorkerMethvinWatcherTest and WatcherConstructorBoundaryTest compiled with javac.
  The final JUnit run passed **19 tests**, zero failures/skips, exit 0. All six
  constructor cases run in the SQLite test. The ownership test's changed call was
  checked against the actual witnessed method and its in-scope Subscription variable;
  its full module compilation remains pending.
- Four new VDU regression methods ran against HEAD's exact renderer in disposable
  classes. All four failed, exit 1, with Expected java.io.IOException to be thrown,
  but nothing was thrown: XObject parameters, inline parameters, shared predictor
  buffer charges and conflicting parameter aliases. No huge-buffer fixture was
  executed against the unguarded renderer.
- A disposable watcher mutation restoring unrooted admission in the six-argument
  constructor made the real SQLite consumer regression fail, exit 1, with expected
  ADMIT but was SKIP_DONE. The new callback API requires a witness, so this mutation
  recreates the old queue behavior rather than restoring the removed signature.
- Initial isolated fixtures exposed two harness/setup errors: a single synthetic
  raster reservation exceeded the per-raster cap (corrected to four bounded copies),
  and relative temp paths disagreed with the watcher's absolute paths (the new test
  normalizes its root; the final watcher run uses an absolute scratch temp directory).
  The old watcher suite's first relative-temp run had 12 passes and 6 failures;
  the final absolute-temp run passed all 18 existing methods.
- git diff --check passed. No Gradle, Node unit tests, dev stack or commits ran.
  Disposable sources, dependencies, classes and databases stayed under tmp/s5n2
  and were removed after verification.

The final runtime commands were (with jars/classes prepared in ignored scratch):

```powershell
java -Xmx768m '-Djava.awt.headless=true' '-Djava.io.tmpdir=F:\justsearch-public\.claude\worktrees\lane-f-s5\tmp\s5n2' -cp 'tmp/s5n2/classes;tmp/s5n2/lib/*' org.junit.platform.console.ConsoleLauncher execute --select-class io.justsearch.app.services.vdu.VduImageLimitsTest --details summary --disable-banner
java -Xmx768m --enable-native-access=ALL-UNNAMED '-Djava.io.tmpdir=F:\justsearch-public\.claude\worktrees\lane-f-s5\tmp\s5n2' '-Dorg.sqlite.tmpdir=F:\justsearch-public\.claude\worktrees\lane-f-s5\tmp\s5n2' -cp 'tmp/s5n2/classes;tmp/s5n2/lib/*' org.junit.platform.console.ConsoleLauncher execute --select-class io.justsearch.indexerworker.services.WorkerMethvinWatcherTest --select-class io.justsearch.indexerworker.services.WatcherConstructorBoundaryTest --details summary --disable-banner
```

Full module compilation, tests and spotless remain with the orchestrator. This round's
focused commands, followed by full affected-module tests and spotless, are:

```powershell
./gradlew.bat :modules:app-services:test --tests '*VduImageLimitsTest'
./gradlew.bat :modules:worker-services:test --tests '*WorkerMethvinWatcherTest' --tests '*WorkerWatcherEventOwnershipTest'
./gradlew.bat :modules:indexer-worker:test --tests '*WatcherConstructorBoundaryTest'
./gradlew.bat :modules:app-services:test :modules:worker-services:test :modules:indexer-worker:test
./gradlew.bat :modules:app-services:spotlessCheck :modules:worker-services:spotlessCheck :modules:indexer-worker:spotlessCheck
```
