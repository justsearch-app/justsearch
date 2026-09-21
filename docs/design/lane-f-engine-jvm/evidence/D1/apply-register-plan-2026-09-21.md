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

The declaration union is247 EnvRegistry plus56 ConfigKey entries, with12 aliases:
291 unique keys. Use the existing declaration-parity parsers in
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
