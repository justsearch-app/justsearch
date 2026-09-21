# D1 configuration apply register reconciliation

Owner audit at `6c95d7989`, while integrated2298 runs. This records decisions and
remaining obligations; no register or D1-3 acceptance is implemented by this file.

Bounded source audits: [fingerprint inputs](fingerprint-apply-scope-audit-2026-09-21.md),
[encoder/generative owners](encoder-apply-scope-audit-2026-09-21.md), and
[process/front owners](process-apply-scope-audit-2026-09-21.md).
The uncovered declarations are reconciled in the
[search/index remainder](remaining-index-apply-audit-2026-09-21.md),
[front/control remainder](remaining-front-apply-audit-2026-09-21.md), and
[inference remainder](remaining-inference-apply-audit-2026-09-21.md).

## Key identity and lifecycle precedence

After fourteen justified retirements, the declaration union is236 EnvRegistry
plus53 ConfigKey entries, with12 aliases:277 unique keys. The preceding audit
measured291 before retirement. Use the existing declaration-parity parsers in
`scripts/docs/runtime-config-matrix-lib.mjs`, not a line-based enum regex.
`EnvRegistry.configKey()` equals `sysProp()` verbatim, including any `justsearch.`
prefix. The extracted inventory is retained in `tmp/2298-declared-key-union.json`.
Runtime JUnit must validate against the actual enum values, not this audit artifact.

Apply scope describes the minimum lifecycle needed to apply a setting. Resolve
overlap with precedence **restart-required > generation-bound > component > hot**.
A process-root setting can affect the fingerprint while still requiring restart;
the former D1-3 wording requiring every fingerprint contributor to have exactly
generation-bound scope conflicts with its own data-directory restart rule.
Revise that acceptance wording to compare the direct semantic generation inputs
after explicitly accounting for higher-precedence restart selectors. Preserve
both obligations; do not omit root selectors from dependency/fingerprint analysis.

Component dependencies and apply scope remain separate axes. A shared key must
appear in every affected component's dependency set. Reconfiguration must reconcile
all matching owners, even if a component-scoped row names its primary owner;
the register label cannot authorize skipping another affected owner. D1-4 must
test this dispatch behavior. No duplicate owner list is needed in the register.

## Findings awaiting the complete reconciliation

- `index.boosts` is query parity metadata, not a generation fingerprint input
  (`SsotCommitMetadataSource`, `ParityDiagnostics`). Its mismatch can force the
  opened index read-only (`IndexMetadataParityGuard`), so it needs a component:index
  row and the actual captured boosts map in the index dependency/value projection.
- Root/model/SSOT discovery selectors contribute to fingerprint construction and
  must be classified using actual readers, not a key prefix. The direct input
  audit owns the exact list; the narrow model-path/HNSW parenthetical is incomplete.
- Encoder/generative dependency audits found missing shared GPU-policy/root/gate
  inputs. Reconcile these once as part of D1-3 rather than issuing one speculative
  correction/checkpoint for each newly found reader.
- Persisted-output controls and BGE-M3 identity are not all represented in the
  current `IndexFingerprint.Inputs`. D1-12's model-binding work is the current
  destination for that compatibility gap; decide which fingerprint changes must
  move earlier to satisfy D1-3 without claiming future behavior as current proof.
- `FIELD_CATALOG` changes the runtime mapper while current fingerprint assembly
  fingerprints the repo-root catalog. D1-12 must connect actual catalog identity;
  calling the declaration generation-bound alone cannot close that mismatch.
- `INDEX_WATCHER_RESCAN_ON_OVERFLOW` and `INDEX_COMMIT_DEBOUNCE_MS` remain declaration
  constants without operational readers. The watcher record explicitly documents
  its field's earlier removal. Complete the source/fixture audit before deciding
  whether to retire these stale declarations; do not invent a working hot-update
  capability for them merely to fill register rows.
- The remainder audit adds37 frozen search/hybrid/RAG values and the parity-open
  decision to the index projection gaps. A per-request accessor of a captured
  startup snapshot is still frozen. Project the actual normalized owner fields;
  first bind the parity guard's current global read to that same capture.
- API composition retains RAG_TOP_K; generative launch and citation wiring have
  additional frozen readers. Register ownership must cover the actual replaceable
  owner, not merely the native child or bound socket. Scope and recompose design
  must agree before the register claims these keys can apply at component scope.
- Infra-health replacement, query-understanding and filter-normalization have
  potential hot paths but currently disconnected ConfigStore apply wiring.
  Tesseract paths are live environment reads as well. Do not equate a live direct
  environment reader with persisted-settings application; connect and prove the
  intended path before accepting hot scope.
- Inert summary, embedding-dimension, retired IPC and other carrier declarations
  need source/fixture retirement or deliberate connection. `unresolved` is audit
  language only, not an extra register scope. Complete the existing four-scope
  contract without filling holes with fictional hot behavior.

## Implementation and proof still required

### Front recompose boundary decision

The [front boundary audit](front-recompose-boundary-audit-2026-09-21.md) refutes
the earlier candidate `component:api`/`component:generative` labels for three keys.
These are audit corrections, not a reduction of implemented apply behavior:

- API_PORT is restart-required. It selects the bound listener and published
  discovery/trust-boundary address. There is no current rebind operation; D1-2
  explicitly excludes API RELOADING. Preserve that lifecycle and use the required
  requested-restart apply path, with successor bind/discovery proof. It remains an
  API applied-value dependency.
- RAG_TOP_K will become hot through an injected typed supplier of the current
  ConfigStore value, captured once per request. Do not rebuild the immutable
  conversation registry or introduce another retained configuration projection.
- CITATION_MATCH_THRESHOLD will use the same current ConfigStore authority in
  both streaming and agent citation paths, captured once per matching operation.
  A supplier is enough; generative native-child compose must not claim to update
  unrelated front/agent objects. Prove both consumers see a changed threshold
  without reconstruction before accepting the hot row.

These live-reader extensions belong to D1-3 reconciliation and require behavioral
tests. Until implemented, the current frozen front readers are restart-bound;
their proposed hot register rows are not acceptance evidence.

Complete every declaration's owner/read evidence and emit one governed row per
unique key. Add JUnit missing/unknown/duplicate-key and component-name checks,
with direct semantic generation-set validation and the precedence exceptions
above. Prove the missing/unknown negative controls bite and restore sources.
Add `applyScopeCount` to the existing generated matrix and config-surface scalar
ratchet, preserving its semantic boundary: JUnit validates meaning; the gate
ratchets the count. Update fixtures, governed baseline/change record and canonical
configuration matrix as required. No declaration-count or proof-tier reduction
is justified merely to make this batch pass.


## Retirement and next executable batch decision, 2026-09-21

A declaration without a reader is not automatically obsolete. The declared-key
count must be recomputed after justified retirements; do not fabricate hot rows to
retain the old291 total. The existing SettingsCommitCoordinator remains the sole
accepted-settings transaction owner. D1-3 does not add a post-commit listener that
pretends to perform D1-4 partition/lease/restart/generation dispatch.

Root accepts the bounded next implementation batch: RAG_TOP_K and both citation
threshold consumers read the installed ConfigStore through injected suppliers,
capture once per request/operation, preserve explicit request overrides and
normalization, and prove A-to-B store swaps without reconstructing services.
ConversationApiAssembly and AgentLoopWiring capture the store authority once;
consumers do not look up a global store independently during a request.

Retire only declarations whose purpose is superseded or contradicts the actual
owner: summary.pipeline (explicit conversation shapes now select summary behavior),
embed.dimension (model output plus index.vector.dimension compatibility owns it),
the six retired indexer transport/backpressure carriers, deprecated index.collection
(replaced by index.collections), commit.debounce_ms (distinct commit timer/triggers
own timing), and watcher.overflow.rescan_on_overflow (overflow reconcile is an
unconditional safety behavior). This is11 candidate unique keys; recompute the
normalized declaration union after editing instead of assuming a new total.

Keep summary.max_tokens and path_resolution.retention_days as connection
obligations. Canonical docs promise a summary-input guard and90-day deletion-history
retention respectively; completion-token limits and unrelated job/ledger pruning
are not substitutes. Search pipeline/profile need their intended default-selection
semantics reconciled before classification; absence of a current reader alone is
insufficient justification to delete the public surface. These decisions preserve
work required by the design, rather than lowering scope to make the register green.

Primary ownership anchors from the read-only audit: SettingsCommitCoordinator
accepted-settings publication; ConfigStore.replace immutable snapshots;
CoreConversationShapeCatalog and AiRoutes summary selection; EmbeddingService /
OnnxEmbeddingEncoder output dimension; EngineRoot in-process composition;
WorkerScanOps admission; config-lifecycle register's index.collections replacement;
CommitOps timer; WorkerMethvinWatcher overflow reconcile; SqlitePathResolutionStore
prune operation and KnowledgeServer sentinel. Reverify line numbers when editing.


### Apply lease teardown obligation

The process-resource review found no current production caller of `tryApply()`.
When actual settings dispatch acquires that lease, prove it drains before all
composition-root teardown or explicitly retain dependent config/instance locks
on refusal. Current launcher cleanup aggregates process-resource failure before
restoring config/releasing its lock; Headless fatal cleanup can propagate bundle
close refusal before final manifest/lock cleanup. Those branches become reachable
with a production apply owner. The low-level resource bundle already retains
executors when component closure refuses; that alone does not prove whole-root
teardown. Include normal, fatal-startup and library-launcher paths in D1-4 proof.


Retirement scope check: `scripts/jseval/jseval/index_identity.py` still hashes
JUSTSEARCH_EMBED_DIM despite no Engine reader. Its identity knob and matching
fixture must retire with embed.dimension, not leave a false operational promise.
Canonical SUMMARY_PIPELINE/INDEX_COLLECTION descriptions, obsolete lifecycle row,
config schema/profile values and dead-config baseline also belong to the same
retirement. Recompute the normalized union with the existing matrix parser;280
is an expectation after11 removals, not a hard-coded acceptance replacement.


## Retirement implementation reconciliation, 2026-09-21

The11 approved removals are implemented in declarations, carriers, YAML/schema,
fixtures and the jseval identity projection. The primary index path now derives
from the already-parsed plural collection projection; it does not keep a shadow
singular setting. Focused runtime tests remain pending. Actual parser counts in
2375 are239 EnvRegistry +53 ConfigKey -12 aliases =280 unique keys. Matrix rows
are292 because aliases remain distinct declaration rows. The config-surface gate
passes after regenerating its registered input and shrinking pins to104 YAML,
239 env/sysprop and53 ConfigKey. No growth waiver was added.


## Missing-reader connection design, 2026-09-21

Both retained promises use hot readers of one captured ConfigStore, without a
listener or component rebuild. Summary input limits apply to single-pass source
material (whole document, concatenated batch, or summarize selection), estimated
before existing truncation. Resolve default20000 and clamp>=1 in the typed builder;
read once per eligible operation. A shared narrow input-limit helper centralizes
estimation/refusal, using the existing CONTEXT_TOO_LARGE error vocabulary and an
explicit configured-limit message. Hierarchical summarization remains multi-pass
and exempt from this single-pass guard; a regression must prove a >20000-token
source still splits. This clarifies the old unqualified summary-limit prose,
without capping long-document hierarchical capability or replacing output budgets.
Selection variants must be checked before their own truncation; non-summary uses
of the shared selection injector remain unchanged.

Path-resolution retention adds a typed Paths value, default90 days, normalized to
at least1 day. This explicit minimum preserves a bounded recent-deletion history
rather than silently making nonpositive input an immediate erase. The existing
KnowledgeServer daily sentinel cleanup calls the existing store prune method with
now minus the sampled duration. Reuse its job/ledger cleanup owner and close order;
no new scheduler, persistent marker or duplicated retention store. The EngineRoot
composition supplies its captured ConfigStore authority. Legacy explicit-snapshot
construction stays fixed to that snapshot. A deterministic package-private cleanup
method permits real store/cutoff tests without waiting a day; prove current-store
swaps, replacement-global isolation and untouched live/recent rows. This auxiliary
history setting remains outside the physical index dependency digest.

Acceptance includes valid defaults/clamping, strict boundary behavior, actual
composition wiring, once-per-operation sampling and canonical docs. Configuration
register rows may claim hot only after those behaviors are implemented and proved.


## Pipeline-selector retirement decision, 2026-09-21

A separate source review confirms that justsearch.search.pipeline is a residue
of the definition-file system explicitly retired by ADR-0014. The profile key
has no implemented mapping (desktop-default in YAML versus default/semantic/hybrid
in old prose); its only read prints a launcher marker. Retire both selectors and
the marker/smoke requirement rather than introduce a new global override over
settled request behavior. Preserve explicit PipelineConfig, explicit mode, call
surface defaults, and capability-derived AUTO when the request supplies neither.
The refined UI uses AUTO; quick UI, MCP and RAG keep their explicit choices.
Canonical search overview must correct its obsolete universal-hybrid-default
claim. An actual KnowledgeSearchEngine precedence regression must prove the
outgoing request, not just helper preset behavior. Recompute counts after edits;
these two justified retirements are additional to the11 above.


###2380 reconciliation and retention review corrections

The actual parser union after both batches is278 (237 EnvRegistry +53 ConfigKey
minus12 aliases); YAML mappings103, declaration rows290. Regenerated config gate
passes with pins shrunk to103/237/53. Removed path-retention's dead-key baseline
entry because the new typed reader now reaches the actual store. Matrix/canonical
links and synchronized skill embeds pass; manually reconciled the Codex search
skill's copied overview as well. Runtime Java verification remains pending.

Independent review found two defects before acceptance. Public embedded EngineRoot
construction must remain valid before global configuration exists: the production
forProcess factory now receives Headless's already-owned ConfigStore explicitly,
while compatibility constructors retain lazy start-time resolution. A real factory
composition test covers store identity/current snapshots; another covers no-global
construction. Queue retention can deliberately throw RecordedWalkGapException to
protect terminal evidence, so job, ledger and path cleanup now have separate
nonfatal boundaries. The new fault regression requires old path rows to expire
while preserving queue refusal and live/recent paths. No new scheduler is needed.


## Complete inventory reconciliation,2381 audit (2026-09-21)

`tmp/2381-apply-reconciliation.json` projects all278 current unique declarations,
with parser parity and source/audit evidence per row. It is an audit artifact,
not the governed apply register or proof that dispatch exists. Counts:119 index,
38 encoders,13 generative,51 hot candidates,34 restart-required,22 generation
candidates and1 unresolved.45 readers connected;232 require reader/owner work;
1 unresolved. Historical291-key audit remains intact.

The22 generation candidates split eight existing direct fingerprint inputs from
fourteen persisted-output identity gaps (embedding4, SPLADE3, NER3, BGE3 and actual
field catalog1). These gaps retain their D1-12 identity/binding obligation; an
accepted register cannot claim current fingerprint coverage from a scope label.
Five former candidates were refuted by their actual consumers: SPLADE query_mode
installs a query encoder, and citation scorer enabled/model/max-sequence/threshold
serve MatchCitations after retrieval without writing index fields. Their component
scope follows actual ownership, not configuration prefixes. Citation scorer
threshold additionally needs its default reader connected; the current service
uses a request value or hard-coded0.5 while composition only stores/logs config.

Shared GPU policy is settled: primary component:encoders plus generative dependency
and all-matching-owner dispatch. Do not invent restart scope to accommodate a
single label. The remaining workers.indexer.enabled decision needs a smoke-only
reader/architecture audit. justsearch.llm.enabled likewise lacks an operational
reader despite a documented disable promise; it needs deliberate connection or
an explicit superseding contract, not a fictional hot row. The next bounded
reader decision audit is tmp/2385-control-reader-decisions.md.


## Next reader connections after d87a0e60c (2026-09-21)

The source-backed decisions in tmp/2385-control-reader-decisions.md are accepted.
Retire workers.indexer.enabled: its only effect was a misleading smoke diagnostic;
the required embedded index owns actual lifecycle. Preserve worker version. This
fourteenth retirement yields277 unique keys; root regenerates matrix and shrinks pins.

Connect llm.enabled as a restart-required hard disable through the existing
InferenceDecision, using HeadAssembly's captured configuration for both capability
and actual BootstrapInferenceFactory creation. Remove the duplicate creation wrapper
that re-sampled global settings. Headless declared launch identity uses the same
predicate. Add the three manager-existence gates to generative dependencies;
an actual manager proves llm.enabled=true, ai.disabled=false and lite.mode=false.
Lite mode remains a process launch flag, not a newly claimed hot setting.

Connect citation.scorer.threshold at its existing captured CitationMatchOps owner.
Positive explicit request thresholds retain precedence; an unset/nonpositive wire
value uses valid captured configuration in(0,1], otherwise the existing0.5 default.
Both cross-encoder and embedding fallback must use that selected threshold.
These reader connections do not implement D1-4 dispatch or close D1 acceptance.

The next QU/filter normalization connection must preserve request-level sampling:
KSE currently checks availability and then service dispatch checks it again.
Resolve this at the existing service/dispatch boundary; preserve KSE's full feature
gate and RetrieveContextController's always-available deterministic normalization.
A new supplier alone would leave an operation able to see two snapshots.

The successor audit `tmp/2391-apply-reconciliation.json` preserves the historical
2381 artifact and reconciles277 distinct rows/14 retirements. LLM existence and
scorer defaults now have actual readers; their register/owner work remains open.
Counts remain45 connected hot rows,230 owner-work rows and2 pending readers
(QU/filter). This deliberately does not promote reader tests to apply-dispatch
acceptance. Candidate scopes:34 restart,119 index,38 encoders,13 generative,
51 hot and22 generation-bound. No unresolved indexer-enabled row remains.
