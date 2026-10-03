# D1 index applied-configuration capture

The initial WorkerConfig-only digest is insufficient and must be replaced before
D1-1/3 acceptance. Prefix inventory: `tmp/2277-index-config-scopes.json` (97 keys,
62 component:index, 22 hot, 3 generation-bound, 10 unresolved). That inventory is
not a complete owner input set and does not make nullable desired values effective
runtime values.

## Capture already implemented in WIP

EngineRoot captures one ResolvedConfig for a physical index start, passes it to
WorkerConfig.load(snapshot) and KnowledgeServer. Legacy constructors capture at
start. The same snapshot now drives index root lock/layout, migration cutover,
LuceneRuntimeBuilder.withConfig for writable/read-only/reload runtimes, sparse
dimension choice, deferred encoder composition and service wiring, pacing, tracing,
and snapshot-bound SsotCommitMetadataSource instances for expected/runtime metadata.
The intentional live search/config and chunk-SPLADE readers remain live.

Configuration gained typed projections of existing master GPU/policy veto, worker
service version and index tracing declarations. These add no operator settings.
Encoder composition carries one typed role projection into service construction;
snapshot-aware model discovery preserves legacy entry points for other callers.

Proof in progress: KnowledgeServerStartupConfigurationTest opens the actual runtime
after replacing global config and asserts captured path, cutover policy, runtime
config identity and sparse dimension. Metadata tests compare actual commit input
JSON after global replacement. Focused 2284/2285/2286 runs exposed compile mistakes;
their artifacts are retained, not counted as complete proof. Configuration tests
passed in 2284 (109 cases); app-services tests passed in 2286 (21 cases). Integration
retry 2287 passes 72 cases/16 suites without skips. This includes actual index
startup from captured A after installing global B, metadata capture, API fallback,
and bounded adapter delivery. It does not complete the index applied-value map.

## Remaining applied-value owners

Project actual owner values; do not hash the whole desired config or reconstruct
defaults in a digest-only helper. Static dependencies and applied values share the
same declared keys. Retire WorkerConfig.APPLIED/appliedVersion and replace its new
test with actual-owner change/stability proof. Copied, unused IPC fields are not
index dependencies. Existing unused carrier cleanup can be scoped separately;
none may masquerade as an applied value.

- Runtime index settings: use the captured RuntimeSession/Components inputs and
  effective normalized values. Include shared sparse selection, effective HNSW and
  quantization, index path, cutover policy, and constructor-captured hybrid fields.
  Keep hot query-only fields outside the composed version. Nullable automatic
  settings cannot simply be declared equivalent to the owner's effective defaults.
- Extraction: DefaultWorkerAppServices still reads global OCR/worker limits and
  EnvRegistry sandbox inputs. Bind its constructor/reconstruction to the captured
  snapshot. An extraction-owned configuration object should call the existing
  normalizers once and pass those exact objects to both factory and projection.
- OCR's eight keys project OcrRoutingConfig AFTER withWorkerLimit(executor limit):
  language/default/size/DPI normalization and worker clamping belong there.
- Tika projects the effective max content and file-size values from the exact
  TikaExtractionPolicy.fromWorkerLimits result, not new duplicate defaults.
- Sandbox's five existing keys (mode, command, heap, pool, max_requests) need typed
  captured representation. In IN_PROCESS, unused command/heap/pool/recycle values
  are null. Otherwise reuse actual parsed mode, tokenized/default command and
  normalized PoolSettings; heap applies only to the built-in command.
- DWAS additionally captures ingestion skip patterns/extensions/directory_names;
  these need the same snapshot discipline. Its chunk-reranker/citation factories
  and index tracing must use the captured config instead of fromEnv/global calls.

Actual extraction source anchors: DefaultWorkerAppServices constructors and
buildContentExtractor; OcrRoutingConfig.from/withWorkerLimit;
TikaExtractionPolicy.fromWorkerLimits; ExtractionSandboxFactory.PoolSettings;
ExtractionSandboxCommand; IngestionSkipPolicy. The existing four-component design
does not justify adding an independent extraction lifecycle registry.

## Selected runtime projection seam (reviewed after2288)

ComponentsFactory already constructs and normalizes IndexWriterConfig, merge
policy, similarity, sort and NRT settings before its read-only branch. Capture an
immutable value projection there; RuntimeSession adds its actual queue/validation
and read-policy fields. Expose that projection through LuceneRuntime. A separate
IndexRuntimePolicy normalizer would duplicate existing ownership and is rejected.
Deferred upgrade reuses the captured ResolvedConfig through the origin builder;
prove its digest equals the deferred digest. Writer presence remains lifecycle.

Project writer RAM/maxBufferedDocs and merge values from actual Lucene getters;
sort uses actual mapper-derived field/type/reverse, not the ignored declared type.
Directory SIMPLEFS/NIOFS share the NIOFS value; unknown/default is MMAP. NRT values
come from normalized factory locals. Queue and validation come from RuntimeSession.
The unused Components.vectorEfSearch default100 is not an applied value: the
session's actual override is positive-or-null. Include effective mapper dimension
alongside its nullable validation guard. IndexGenerationManager's normalized base
path is configuration; active generation path/id is separate evidence.

HybridSearchOps reads frozen RuntimeSession config per query, not live ConfigStore.
Its always-used candidate/multiplier/fusion/recall values are component dependencies;
RRF-only values are inactive under CC, and CC-only values inactive under RRF.
Arbitration thresholds are inactive when arbitration is disabled. Preserve these
distinctions when proving unrelated-key stability. Exclude dirty-open FULL escalation,
time-dependent retention Query objects and unused index.commit.debounce_ms.

Run2288 compiled the capture changes and passed104 configuration cases; one of16
worker-service cases failed because the new heap assertion assumed inline argv.
Windows legitimately uses a JDK argument file for the long test classpath. Preserve
the heap assertion for both transports. A generated argument-file path and JVM
classpath are not operator settings and must not enter the applied digest; project
the stable built-in command policy plus effective heap, or custom normalized argv.
Encoder/API tasks had not run when this failure stopped Gradle. Artifacts are
retained under `tmp/2288-captured-owner-configuration*`.

Required normalization proof: deferred/writable equality, unset/explicit effective
defaults, directory aliases, mapper-derived sort types, inactive CC/RRF settings,
and stable built-in command identity across distinct temporary argument files.

Run2289 passes45 cases/10 suites without skips, including the corrected heap
transport check and actual encoder composition/service wiring with native assembly
mocked. A later root gap audit found chunk-reranker and health discovery still
global-capable. Snapshot overloads now cover both. Root also replaced repeated
service-construction discovery with one immutable WorkerServiceConfiguration held
by KnowledgeServer for its physical start. Alternative repeated discovery against
the same snapshot was rejected: files can change without a configuration mutation.
The new regression deletes a captured fixture model and changes global config before
real service reconstruction, then requires the same typed config objects. No native
session or lifecycle authority is added by this retained configuration value.

IndexConfigurationProjection now combines runtime values with exact extraction,
skip, chunk/citation, normalized base path, identity grace and pacing owner values.
Backfill and chunk flags are dependencies because their loop reads use the frozen
runtime snapshot; the deliberately live status supplier stays outside. Collection
names and watched-roots file contents are not the index.collections value: project
the configured normalized root sequence. The old WorkerConfig-only API and its new
test have been retired in favor of actual opened-owner regression coverage. These
post2289 changes passed2290 (91 cases/11 suites). Root review then found that commit
refresh consumes separate thresholds even in on-demand mode and that disabled
background/recall knobs were still represented. Those corrections and regressions
passed2291 (16 cases/4 suites). Independent review and integrated proof remain due;
these runs do not establish D1 acceptance.

Acceptance remains the full declaration/register union, real four-owner wiring,
dependency-change/unrelated-key stability, changed-global adversarial capture,
negative controls, and required integrated/live proof. Factory-path unit tests and
manufactured successful observation records do not prove real composition/wiring.

## Independent owner review after2291

The consolidated review found three remaining applied-value errors. Root owns the
corrections: include the captured hot-reload boolean because it selects the actual
DevReloadManager; project the retained health-service reranker discovery (found,
normalized path and automatic selection) instead of the raw path declaration; and
project the index tracing sampler only when its bootstrap actually acquired global
registration, alongside the service's span-authoring gate. Head-owned tracing makes
the index's requested sample/detailed distinction inactive. The real registration
conflict also required closing a newly constructed tracing SDK when registration
fails; preserve the incumbent global owner and original failure.

The reviewer found no additional concrete issue in runtime normalization, deferred
upgrade, held service capture or shutdown ordering. This is read-only review, not
executed proof. All three corrections, acquired-sampler normalization, actual
four-owner registration and exporter cleanup passed2296 (22 tests/8 suites), with
spotlessCheck and pmdAll green.2297 deliberately omitted SDK cleanup and failed on
the surviving exporter thread; production source was restored byte-exactly. The
cleanup regression allows ten seconds because the installed SDK can exit only after
its default five-second poll, even after exporter shutdown completes (initial2295
two-second fixture failed). Integrated affected-module proof still remains.
