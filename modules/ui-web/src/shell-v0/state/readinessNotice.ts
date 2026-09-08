// SPDX-License-Identifier: Apache-2.0
/**
 * readinessNotice — Tempdoc 577 Goal 1 Phase 2 (Ext III).
 *
 * The one projection from the observed-state readiness record (`ReadinessView`,
 * the aiStateStore authority fed by `/api/status` composites) to a degradation
 * NOTICE that can explain itself: *state* (the headline), *cause* (worded from
 * the same closed reason-code vocabulary the backend emits —
 * `LifecycleReasonCode` + the worker-health probe codes), and *remedy* (a
 * reference the surface dispatches: an operation id through the one
 * `OperationClient` seam, or a surface navigation). A notice minted from this
 * projection cannot be cause-less: an unknown code degrades to honest generic
 * wording + the "Open Health" remedy, never silence.
 *
 * Pure data → data; the surface owns rendering and dispatch. Unknown codes are
 * BY DESIGN not dropped — they word generically and keep the Health remedy.
 */
import type { SystemHealthVerdict } from './verdict.js';

export type NoticeRemedy =
  /** Dispatched via `<jf-op-button>` — the label/risk/ceremony come from the
   *  operation catalog, never from here (the hardcoded-label path stays
   *  structurally unreachable, per OpButton's design). */
  | { kind: 'operation'; operationId: string }
  | { kind: 'navigate'; target: string; label: string };

export interface ReadinessNoticeView {
  /** Bold lead-in ("Reindex required." / "Semantic search degraded."). */
  headline: string;
  /** The standing consequence sentence (kept from the B3 banner wording). */
  body: string;
  /** Worded causes projected from `reasonCodes` (deduped; [] when none known). */
  causes: string[];
  /** The highest-priority actionable remedy for the worded causes. */
  remedy: NoticeRemedy;
}

/**
 * The "Open Health" fallback remedy — always actionable, never wrong.
 *
 * Exported for the affordance-scoped `degraded` caveat (round-14 finding 8: the info-tier caveat
 * carried no route to the detail the warn tier already routes to), so the two cannot drift apart.
 */
export const OPEN_HEALTH: NoticeRemedy = {
  kind: 'navigate',
  target: 'core.health-surface',
  label: 'Open Health',
};

/**
 * The closed cause vocabulary: backend reason code → (wording, remedy?).
 * Codes the backend emits today (`LifecycleReasonCode` + StatusLifecycleHandler's
 * worker-health probes). A row WITHOUT a remedy falls back to Open Health.
 * Order in the list is priority order for picking the notice's single remedy.
 */
/**
 * Presentation severity (tempdoc 595 §10.5). `ok`/`busy` are produced by the
 * verdict (operational / transitioning); reason codes only ever map to the
 * degradation subset (`info`/`warn`/`error`). One vocabulary, no second authority.
 */
export type Severity = 'ok' | 'info' | 'busy' | 'warn' | 'error';

/** Per-reason severity. Unset ⇒ `warn` (the honest default for an impairing code). */
type ReasonSeverity = 'info' | 'warn' | 'error';

const CAUSE_ROWS: ReadonlyArray<{
  code: string;
  wording: string;
  remedy?: NoticeRemedy;
  /**
   * User-impact tier (595 §10.3): `info` = graceful/transient/cosmetic (search
   * still serves); `warn` = impairing (default); `error` = search broken.
   */
  severity?: ReasonSeverity;
}> = [
  {
    // Tempdoc 637 #1 — FE-derived (declared in readiness-reason-codes.v1.json feDerived); the
    // backend never emits it. Minted by computeVerdict when the FE→backend binding is dead.
    code: 'binding.unreachable',
    wording: 'The connection to the search backend was lost',
    severity: 'error',
  },
  {
    code: 'worker.health.embedding_not_ready',
    wording: 'The semantic embedding index is not ready',
    remedy: { kind: 'operation', operationId: 'core.trigger-offline-processing' },
  },
  {
    code: 'chunk_embedding.not_ready',
    wording: 'Passage embeddings are not ready',
    remedy: { kind: 'operation', operationId: 'core.trigger-offline-processing' },
  },
  {
    code: 'chunk_embedding.in_progress',
    wording: 'Passage embeddings are still being computed',
    // In-progress: nothing to trigger — watching it in Health is the action.
    severity: 'info',
  },
  {
    code: 'worker.health.embedding_probe_missing',
    wording: 'The embedding model could not be probed',
  },
  {
    code: 'inference.offline',
    wording: 'The local AI model is offline',
    remedy: { kind: 'operation', operationId: 'core.reload-inference' },
  },
  { code: 'inference.starting', wording: 'The local AI model is still starting', severity: 'info' },
  // Tempdoc 656 — the AI capability's previously-generic "offline" reason, now specific. No
  // remedy operation is registered for install/import actions today (unlike
  // core.reload-inference), so these fall back to the Open-Health reference — same pattern as
  // vdu.missing_mmproj / ocr.engine_missing below, rather than pointing at a nonexistent operation.
  {
    code: 'inference.model_not_configured',
    wording: 'No local AI chat model is configured yet',
    severity: 'warn',
  },
  {
    code: 'inference.model_not_found',
    wording: 'The configured local AI chat model file could not be found',
    severity: 'warn',
  },
  {
    code: 'inference.runtime_not_installed',
    wording: 'The local AI runtime (llama-server) is not installed',
    severity: 'warn',
  },
  {
    code: 'inference.policy_online_ai_disabled',
    wording: 'Local AI is disabled by administrator policy',
    severity: 'info',
  },
  {
    code: 'inference.policy_gpu_disabled',
    wording: 'GPU acceleration for local AI is disabled by administrator policy',
    severity: 'info',
  },
  // Tempdoc 837 S4 — the GPU was handed to indexing. `info` on purpose: the state is scheduled and
  // self-clearing, so it belongs in Health rather than the banner, and the honest wording is
  // reassuring where "the model is offline" was alarming. Severity here is not a label — `info`
  // withholds it from the banner (warrantsSearchDegradationBanner) and keeps the affordance caveat
  // calm; upgrading it to `warn` for visibility would make the banner worse than the collapsed
  // state this replaces.
  {
    code: 'inference.gpu_yielded_to_indexing',
    wording: 'The GPU is indexing your files — chat resumes when it finishes',
    severity: 'info',
  },
  // Tempdoc 837 S4 — a correctness fix, not an enrichment: the engine is UP (a background procedure
  // is using it) while the user's chat spec is off, and the previous wording said it was offline.
  {
    code: 'inference.up_for_background',
    wording: 'Chat is turned off; the AI engine is running background document processing',
    severity: 'info',
  },
  // Tempdoc 837 S5 — the engine stopped on its own (TransitionReason.CRASH_RECOVERY). `warn`, not
  // `info`: nobody chose it and there IS an action (reload). It is also the one new code in this
  // slice that must join AI_MODEL_UNAVAILABLE_CODES — see that set's comment for why the other three
  // must not.
  {
    code: 'inference.crashed',
    wording: 'The local AI model stopped unexpectedly',
    remedy: { kind: 'operation', operationId: 'core.reload-inference' },
    severity: 'warn',
  },
  // Tempdoc 837 S5 — the user turned chat off (TransitionReason.USER_SWITCH / ADMIN_TRIGGERED). The
  // most FREQUENT of the four collapsed cases, so wording it as a fault is what trained
  // alarm-blindness. `info`: it is a choice, not a failure, and it self-clears by re-enabling.
  {
    code: 'inference.deactivated',
    wording: 'The local AI model is turned off',
    remedy: { kind: 'operation', operationId: 'core.reload-inference' },
    severity: 'info',
  },
  {
    // Tempdoc 656 (post-implementation review fix): this code is the shared catch-all for both
    // activation failures (self-test/apply) AND deactivation failures (rollback) in
    // RuntimeActivationService.mapToLifecycleReason — worded direction-neutrally so it isn't wrong
    // when the user was actually deactivating.
    code: 'inference.activation_failed',
    wording: 'The local AI runtime failed to switch modes',
    remedy: { kind: 'operation', operationId: 'core.reload-inference' },
    severity: 'warn',
  },
  {
    code: 'ocr.disabled',
    wording: 'OCR is disabled, so scanned text cannot be indexed yet',
    severity: 'warn',
  },
  {
    code: 'ocr.engine_missing',
    wording: 'OCR is unavailable because Tesseract is not installed or not on PATH',
    severity: 'warn',
  },
  {
    code: 'ocr.language_missing',
    wording: 'OCR is missing a required language pack',
    severity: 'warn',
  },
  {
    code: 'vdu.ai_offline',
    wording: 'Visual document understanding is waiting for the local AI model',
    remedy: { kind: 'operation', operationId: 'core.reload-inference' },
    severity: 'info',
  },
  {
    code: 'vdu.insufficient_vram',
    wording: 'Visual document understanding needs more GPU memory for the selected vision model',
    severity: 'warn',
  },
  {
    code: 'vdu.missing_mmproj',
    wording: 'Visual document understanding is missing its vision projector file',
    severity: 'warn',
  },
  {
    code: 'vdu.circuit_open',
    wording: 'Visual document understanding is paused after repeated failures',
    severity: 'warn',
  },
  { code: 'worker.throughput_stalled', wording: 'Indexing throughput has stalled' },
  {
    code: 'worker.throughput_degraded',
    wording: 'Indexing throughput is degraded',
    severity: 'info',
  },
  {
    code: 'worker.starting',
    wording: 'The knowledge server is still starting',
    severity: 'info',
  },
  { code: 'worker.spawn.failed', wording: 'The knowledge server failed to start', severity: 'error' },
  // Tempdoc 825 — the terminal twin of the row above: it failed to start AND the bounded boot-recovery
  // budget is spent, so nothing is retrying any more. Distinct wording is the whole point of the code:
  // `worker.spawn.failed` now means "failed, recovery pending or in flight", and telling a user that
  // while the Head keeps re-attempting reads as a dead end it isn't. No one-click remedy — a respawn is
  // exactly what just failed four times ⇒ Open-Health fallback.
  {
    code: 'worker.spawn_recovery_exhausted',
    wording: 'The knowledge server failed to start and could not be recovered',
    severity: 'error',
  },
  // Tempdoc 627 — transient: a supervised restart is in flight. Calm (info) + no remedy — it self-recovers;
  // the verdict promotes this to a "Restarting…" transitioning state so a routine self-heal isn't alarming.
  {
    code: 'worker.recovering',
    wording: 'The knowledge server is restarting',
    severity: 'info',
  },
  // Tempdoc 837 S3 — the worker WAS serving and stopped answering. `worker.spawn.failed` told these
  // users their knowledge server "failed to start", which is false: it started fine and then died.
  //
  // Lane F stage A item A11 made the SECOND sentence of this row load-bearing. This row used to
  // carry no remedy on the grounds that "a supervised restart is already in flight, so there is
  // nothing to click" — that supervisor is deleted. A11 removed crash detection, the restart budget
  // and the cooldown along with the Worker child process (stage A §10.1 lists the loss as
  // deliberate until stage B restores supervision), so a stopped Engine now STAYS stopped. Leaving
  // the old wording would have left the user waiting for a recovery that is never coming — the
  // worst failure mode a readiness notice has, because it reads as reassuring.
  //
  // The remedy has to ride in the wording: `NoticeRemedy` can only be an operation id or a surface
  // navigation, and "restart the application" is neither. `core.restart-worker` still exists but
  // its handler answers `restart required` (stage A §5), so pointing at it would be a button that
  // tells you to do the thing it was supposed to do. Open Health stays the fallback remedy.
  {
    code: 'worker.lost',
    wording:
      'The knowledge server stopped responding and does not restart itself — restart JustSearch to recover it',
    severity: 'error',
  },
  // Tempdoc 837 S3 — the highest-value row in the batch: this cause is DETECTED today (the dying
  // worker stamps a fatal-reason marker) and was discarded before the user saw it. No one-click
  // remedy exists for index.recovery.policy=BACKUP_REBUILD, so this takes the Open-Health fallback
  // rather than pointing at an operation that does not exist (the vdu.missing_mmproj precedent);
  // the configuration sentence rides on the Health surface as the condition's detail.
  {
    code: 'worker.index_corrupt',
    wording: 'The search index is corrupt and could not be repaired automatically',
    severity: 'error',
  },
  // Tempdoc 915 (live validation) - the sibling cause, and the same shape of loss: the worker
  // REFUSED to start because the index has a different shape than this version writes, and under
  // FAIL_CLOSED that refusal reached the user as "worker process crashed". The index is intact, so
  // the wording says shape, not damage. Open-Health fallback for the same reason as its sibling:
  // the remedy is a configuration change, which no one-click operation performs.
  {
    code: 'worker.index_schema_mismatch',
    wording: 'The search index was built for a different version and the server refused to open it',
    severity: 'error',
  },
  // Tempdoc 837 S3 — orderly teardown, not a fault: calm `info`. Distinguishing it from
  // worker.not_configured keeps "we stopped it" from reading as "it was never set up".
  {
    code: 'worker.shut_down',
    wording: 'The knowledge server has shut down',
    severity: 'info',
  },
  // Tempdoc 837 S3 — the pre-transition default: nothing has been observed about the worker yet.
  // Sibling of worker.starting (which means a start was actually attempted) and worded to claim no
  // more than it knows.
  {
    code: 'worker.not_connected',
    wording: 'The knowledge server has not connected yet',
    severity: 'info',
  },
  // Tempdoc 600 PART X — the GPU-saturation readiness code (aiFeatures composite). When retrieval is
  // independently degraded, the verdict appends this as a SECONDARY cause; without a row it rendered the
  // raw `Degraded: gpu.saturated` (reproduced live). A transient performance dip, not a broken
  // capability ⇒ calm `info`, no one-click remedy (Open-Health fallback).
  {
    code: 'gpu.saturated',
    wording: 'The GPU is busy; results may be slower',
    severity: 'info',
  },
  // Tempdoc 586 P-1b — the LambdaMART (learned-ranking) reason codes the backend
  // emits (LifecycleReasonCode.LAMBDAMART_*). Informational: re-ranking quality is
  // reduced but keyword/semantic retrieval still serves, so no remedy operation —
  // these fall back to the Open Health reference rather than promising a one-click fix.
  // 595 §10.3: an optional re-ranker being off must NOT alarm like a broken index.
  {
    code: 'lambdamart.not_configured',
    wording: 'Learned re-ranking (LambdaMART) is not configured',
    severity: 'info',
  },
  {
    code: 'lambdamart.training',
    wording: 'Learned re-ranking (LambdaMART) is still training',
    severity: 'info',
  },
  {
    code: 'lambdamart.failed',
    wording: 'Learned re-ranking (LambdaMART) failed to load',
    severity: 'info',
  },
  // Tempdoc 596 §17 — FE-derived: a control gated on "no documents indexed". The backend does NOT
  // emit this code (it never appears in `reasonCodes`, so the banner/verdict are unaffected); it
  // exists here only so the per-affordance availability projection (`reasonFor`) speaks ONE vocabulary
  // with the banner. The remedy is to add a folder, which lives on the Library surface.
  {
    code: 'no_documents',
    wording: 'No documents indexed yet',
    remedy: { kind: 'navigate', target: 'core.library-surface', label: 'Add documents' },
    severity: 'info',
  },
  // Tempdoc 629 (#3) — FE-derived: the conversation store is encrypted + locked (history 423'd). The
  // backend never emits this readiness code; it lives here so the locked-chat affordance speaks the ONE
  // CAUSE_ROWS vocabulary instead of hardcoding its wording (the honesty-as-typed-guarantee for the gate).
  //
  // SCOPE (tempdoc 806 W1): this row words the CHAT store only. Every AUTHORED store locks together on
  // ONE data key, but the wording names WHICH data is unreadable, so a second locked store gets its own
  // row rather than reusing this one — a Memory surface saying "Your chat history is encrypted and
  // locked" would be a true statement about the wrong store. Sibling: `memory.locked` below (same
  // remedy: one key, one unlock).
  {
    code: 'conversations.locked',
    wording: 'Your chat history is encrypted and locked',
    // The remedy must point at the surface that OWNS the unlock capability: `unlockEncryption()`
    // lives on SecuritySurface (`core.security-surface`, CorePlugin.ts), NOT on Settings — 629 moved
    // the encryption control out of Settings and this remedy was left behind pointing one hop short.
    remedy: { kind: 'navigate', target: 'core.security-surface', label: 'Unlock in Security' },
    severity: 'warn',
  },
  // Tempdoc 806 W1 — FE-derived sibling of `conversations.locked`: the learned-memory store is
  // encrypted + locked (`GET /api/memory` answers `locked: true`, and every mutation 423s). Same key,
  // same remedy, different data — so the Memory surface renders a locked state instead of "No learned
  // memory yet.", which claimed the AI had learned nothing when it simply could not read.
  {
    code: 'memory.locked',
    wording: 'What the AI has learned is encrypted and locked',
    remedy: { kind: 'navigate', target: 'core.security-surface', label: 'Unlock in Security' },
    severity: 'warn',
  },
  // Tempdoc 600 Design A — the embedding/schema compatibility causes the worker emits on the
  // `retrieval` readiness composite (StatusLifecycleHandler.compatBlockedReason). Previously these
  // reached the verdict only as a coarse `reindexRequired` boolean → a generic, cause-less banner;
  // now each names its specific, actionable cause. All are impairing (semantic search is off until a
  // rebuild) but NOT broken (keyword search still serves) ⇒ `warn`, with the rebuild remedy.
  {
    code: 'index.blocked_legacy',
    wording:
      'The index was built before semantic search was available — rebuild it to enable meaning-based results.',
    remedy: { kind: 'operation', operationId: 'core.rebuild-index' },
    severity: 'warn',
  },
  {
    code: 'index.embedding_legacy',
    wording:
      "Semantic search isn't available on this index yet — rebuild it to enable meaning-based results.",
    remedy: { kind: 'operation', operationId: 'core.rebuild-index' },
    severity: 'warn',
  },
  // Tempdoc 804 §D1, amended by tempdoc 915 — still ADVISORY, and therefore still NOT in
  // REINDEX_CAUSE_CODES below, but the reason changed. The compared value is no longer
  // `index_schema_fp` (a content hash of the whole `fields.v1.json` file, which flipped on
  // annotation-only edits); it is `index_fingerprint`, a hash of the effective PHYSICAL index shape
  // (IndexFingerprint.java, compared at IndexStatusOps.safeSchemaCompatState,
  // IndexStatusOps.java:1097-1180). It still has ZERO query-path consumers: the dense leg is gated
  // by the EMBEDDING fingerprint (SearchPlanner.java:87 via allowQueryEmbeddings()), never by this
  // one. Sandbox round 10 reproduced the consequence of getting this wrong — `schema_mismatch` true
  // while dense retrieval was provably live, under a red "results may be keyword-only" banner. What
  // did change is that a mismatch is now acted on: under the production BLUE_GREEN_MIGRATE default
  // the Worker rebuilds beside the live index, so search keeps working throughout ⇒ `info`.
  {
    code: 'index.schema_mismatch',
    wording:
      'The index format is out of date — search is fully working; rebuilding picks up newer index features.',
    remedy: { kind: 'operation', operationId: 'core.rebuild-index' },
    severity: 'info',
  },
  // Tempdoc 915 §C — the Worker gave up rebuilding this index shape by itself after three
  // attempts. Unlike `index.schema_mismatch` (which resolves itself), this one stays until a person
  // acts, and ingestion does not resume meanwhile ⇒ `warn` and a place in REINDEX_CAUSE_CODES.
  {
    code: 'index.rebuild_brake_exhausted',
    wording:
      'The index could not be rebuilt after repeated attempts — search still works on what is already indexed, but new files are not being added. Rebuild the index to try again.',
    remedy: { kind: 'operation', operationId: 'core.rebuild-index' },
    severity: 'warn',
  },
  // Tempdoc 837 S6 removed the `index.rebuilding` row that used to sit here. Its code was emitted
  // only inside the window the verdict forces to `transitioning`, where this projection returns null,
  // so the row was unreachable UI. Its wording was not lost — it moved, word for word, into
  // `verdictBody`'s rebuilding branch, which is the authority that actually renders during a rebuild.
  //
  // An in-place embedding rebuild (embeddingCompatState=REBUILDING): the Worker refuses dense
  // queries until it finishes, so ONLY the semantic leg is affected — keyword results stay
  // complete. Nothing to click (it is already running) ⇒ Open-Health fallback; impairing but
  // self-healing ⇒ `warn`. Unlike a generation rebuild this leaves stability SETTLED, which is why
  // this row is reachable and its retired neighbour was not.
  {
    code: 'index.embedding_rebuilding',
    wording:
      'Semantic search is being rebuilt — keyword results are complete, semantic ranking resumes when it finishes.',
    severity: 'warn',
  },
  {
    code: 'index.embedding_mismatch',
    wording: 'The embedding model changed — rebuild the index to restore semantic search.',
    remedy: { kind: 'operation', operationId: 'core.rebuild-index' },
    severity: 'warn',
  },
  // Tempdoc 598 reopen (B-3) — the dense leg can't run for a reason a REBUILD does NOT fix (the
  // embedding model isn't loaded, or the embedder is down on a COMPATIBLE index). Distinct from the
  // index.*_legacy/_mismatch reindex causes: there is no one-click rebuild remedy (a reindex won't add
  // a missing model), so it falls back to the Open Health reference. Impairing (semantic genuinely off,
  // AUTO degraded to keyword) but NOT broken (keyword search still serves) ⇒ `warn`. This row stops the
  // banner over-claiming "fully semantic" while a query actually ran keyword (the §59 hole). NOTE: the
  // final wording is co-owned with tempdoc 600 (the consumed-copy authority); this is the source-side
  // vocabulary entry the produce-side (StatusLifecycleHandler.denseUnavailableReason) requires.
  {
    code: 'index.dense_unavailable',
    wording: 'Semantic search is unavailable right now — showing keyword results.',
    severity: 'warn',
  },
];

/**
 * Tempdoc 600 Design A — the reason codes that mean "a reindex/rebuild restores semantic search"
 * (the worker's embedding/schema BLOCKED_* compat states, surfaced on the `retrieval` composite).
 * The ONE place that knows which codes carry the "Reindex required" headline, so the 595 verdict
 * (`verdictHeadline`/`verdictBody`) and this banner cannot disagree. Replaces the old synthetic
 * `reindex-required` token that was minted from a boolean (tempdoc 600 PART III §16).
 *
 * Tempdoc 804 §D1 — this is the DEGRADING bucket: every member genuinely gates the dense leg
 * (embedding fingerprint / no fingerprint at all), so the "results may be keyword-only" consequence
 * is true of all of them. `index.schema_mismatch` was removed from this set: it is advisory (see its
 * CAUSE_ROWS row) and lumping it here made the banner assert a degradation that measurably was not
 * happening.
 */
const REINDEX_CAUSE_CODES: ReadonlySet<string> = new Set([
  'index.blocked_legacy',
  'index.embedding_legacy',
  'index.embedding_mismatch',
  'index.rebuild_brake_exhausted',
]);

/**
 * The advisory index-format code (804 §D1, 915): the stored index fingerprint differs from the one
 * this runtime would produce, with no query-path consequence. Named here so surfaces that already headline the rebuild
 * story do not have to string-match it (the round-2 "dedup by code, not wording" ruling).
 */
export const INDEX_SCHEMA_MISMATCH = 'index.schema_mismatch';

/** True when {@code code} is an embedding/schema compat cause that a rebuild fixes. */
export function isReindexCause(code: string): boolean {
  return REINDEX_CAUSE_CODES.has(code);
}

/**
 * Tempdoc 804 §B5 (live round-11 finding) — the reason codes that mean the RETRIEVAL pipeline
 * itself fell back (or cannot be asserted live). Same shape as REINDEX_CAUSE_CODES above: the ONE
 * place that knows which causes justify the "showing keyword results" consequence, so the impairing
 * banner stops deriving that claim from SEVERITY alone. Observed defect: on a fully-enriched index
 * with only `lambdamart.not_configured` (info) + `inference.offline` (warn), the verdict is warn and
 * the banner read "Semantic search degraded / Showing keyword results" while dense retrieval AND the
 * cross-encoder were provably live — an AI-features cause worded as a retrieval fallback.
 *
 * The three REINDEX_CAUSE_CODES are retrieval-impairing too, but can never reach the branch this set
 * guards (the reindex branch returns first), so they are not restated here.
 */
const RETRIEVAL_IMPAIRING_CODES: ReadonlySet<string> = new Set([
  // The dense leg is positively known not to serve (StatusLifecycleHandler.denseUnavailableReason).
  'index.dense_unavailable',
  // The embedder is down ⇒ query embeddings unavailable ⇒ AUTO degrades to keyword.
  'worker.health.embedding_not_ready',
  // The embedder could not be probed: we do NOT know both legs are live, and the reassuring wording
  // requires positive knowledge (same doctrine as severityForCodes' unknown ⇒ warn default).
  'worker.health.embedding_probe_missing',
  // (Tempdoc 837 S6 removed `index.rebuilding` here with its row: a generation rebuild is a
  // TRANSITION, and this set only ever gates the degraded branch, which that state cannot reach.)
  // An in-place embedding rebuild: the Worker refuses dense queries (REBUILD_IN_PROGRESS) until it
  // finishes, so search is genuinely serving keyword-only for its duration.
  'index.embedding_rebuilding',
  // The knowledge server is not serving (or not serving yet): retrieval as a whole is impaired, so
  // the "search is fully working" claim would be flatly false.
  'worker.starting',
  'worker.recovering',
  'worker.spawn.failed',
  // Tempdoc 825 — the boot-recovery budget is spent and no worker is serving. Same rule as its
  // siblings: omission would let the banner claim "search is fully working" over nothing at all.
  'worker.spawn_recovery_exhausted',
  // Tempdoc 837 S3 — same rule, four more ways for the knowledge server not to be serving. Omission
  // is not neutral here: a recognized row outside this set is classified as NOT impairing, which
  // would let the banner claim "search is fully working" while nothing is serving it.
  'worker.lost',
  'worker.index_corrupt',
  'worker.index_schema_mismatch',
  'worker.shut_down',
  'worker.not_connected',
]);

/**
 * Tempdoc 805 §G.2 — the PASSAGE leg, and only the passage leg, is reduced: chunk (passage) vectors
 * are absent or still being computed, so passage-level precision drops — while the DOCUMENT-level
 * dense leg and the cross-encoder keep serving. Round 11 measured exactly this state and the banner
 * claimed "Showing keyword results" over a trace showing dense retrieval + reranking executing, so
 * these two codes move OUT of `RETRIEVAL_IMPAIRING_CODES`: a keyword-fallback claim is not licensed
 * by a passage-vector gap.
 */
const PASSAGE_REDUCED_CODES: ReadonlySet<string> = new Set([
  'chunk_embedding.not_ready',
  'chunk_embedding.in_progress',
]);

/**
 * True when {@code code} means retrieval itself is impaired. An UNKNOWN code counts as impairing:
 * the calm "search is fully working" wording is an assertion, and we never assert full retrieval
 * health from a code we cannot classify (mirrors `severityForCodes`, which refuses to downgrade an
 * unrecognized degradation to `info`).
 */
function isRetrievalImpairing(code: string): boolean {
  if (RETRIEVAL_IMPAIRING_CODES.has(code)) return true;
  if (PASSAGE_REDUCED_CODES.has(code)) return false;
  return !CAUSE_ROWS.some((row) => row.code === code);
}

/** True when {@code code} is a passage-leg-only reduction (805 §G.2). */
function isPassageReduced(code: string): boolean {
  return PASSAGE_REDUCED_CODES.has(code);
}

/**
 * The codes that mean the local AI model is not available, so chat/answer features are off while
 * retrieval is untouched. Positive gate (not merely "no retrieval cause"): several non-retrieval
 * causes are also non-AI (`ocr.*`, `worker.throughput_*`, `conversations.locked`), and wording those
 * as "AI features unavailable" would repeat the very defect this fixes in the other direction.
 * Excluded on purpose: `inference.starting` (transient, owned by the calm `info` branch),
 * `inference.policy_*` (policy-disabled never "comes online"), `vdu.ai_offline` (document
 * understanding, not chat).
 *
 * <p>Tempdoc 837 S4 applies that same doctrine to its two new codes, and both are excluded:
 * `inference.up_for_background` describes an engine that is UP (calling it unavailable would repeat
 * the falsehood S4 exists to fix), and `inference.gpu_yielded_to_indexing` is the scheduled,
 * self-clearing sibling of the policy cases — chat is not offered by design, not because the model
 * is unavailable. Both ship at `info`, and an info-only verdict short-circuits before
 * `classifyConsequence` runs, so membership would only ever be consulted when one of them rides
 * alongside a `warn` cause — exactly where the calmer class must not be inferred from a code that
 * is not evidence for it.
 *
 * <p>Tempdoc 837 S5 splits its two codes on exactly that doctrine. `inference.crashed` JOINS the set
 * — it is a `warn` code, and a recognized `warn` row in none of the consequence sets falls through
 * to `cosmetic`, which would have the banner say "An optional capability is unavailable; results are
 * still fully semantic" about a CRASHED AI model. `inference.deactivated` does not join: it is the
 * policy/user-choice case the exclusion above is written for ("policy-disabled never comes online"),
 * and it ships at `info` where the set is never consulted anyway.
 */
const AI_MODEL_UNAVAILABLE_CODES: ReadonlySet<string> = new Set([
  'inference.offline',
  'inference.model_not_configured',
  'inference.model_not_found',
  'inference.runtime_not_installed',
  'inference.activation_failed',
  'inference.crashed',
]);

/**
 * Tempdoc 805 §G.2 — the consequence CLASS a degradation licenses: the one derivation every
 * degradation claim (this module's banner, `availability.ts`'s affordance caveat) must consume, so a
 * second projection cannot re-derive the keyword-fallback claim from severity and contradict the
 * measured trace (round 11's defect, in two copies).
 *
 * `unknown` is a real class, not a gap: an unrecognized code is not evidence of anything, so it must
 * never license a CALMER claim than the conservative one (same doctrine as `isRetrievalImpairing` /
 * `severityForCodes`). Consumers word `unknown` exactly as `retrieval-impaired`.
 */
export type ConsequenceClass =
  | 'retrieval-impaired'
  | 'passage-reduced'
  | 'ai-unavailable'
  | 'cosmetic'
  | 'unknown';

/**
 * Classify a verdict's reason codes into the ONE consequence class that licenses its wording.
 *
 * Precedence: `retrieval-impaired` > `unknown` > `passage-reduced` > `ai-unavailable` > `cosmetic`.
 * A positively-known retrieval block is the most specific and most severe, so it wins outright; an
 * unrecognized code outranks every remaining class because those are all calmer claims we could not
 * back. An empty code list is `unknown` for the same reason (mirrors `severityForCodes`' empty ⇒
 * `warn`), never the calm `cosmetic`.
 */
export function classifyConsequence(codes: readonly string[]): ConsequenceClass {
  let sawUnrecognized = codes.length === 0;
  let sawPassage = false;
  let sawAi = false;
  for (const code of codes) {
    if (RETRIEVAL_IMPAIRING_CODES.has(code) || REINDEX_CAUSE_CODES.has(code)) {
      return 'retrieval-impaired';
    }
    if (PASSAGE_REDUCED_CODES.has(code)) sawPassage = true;
    else if (AI_MODEL_UNAVAILABLE_CODES.has(code)) sawAi = true;
    else if (!CAUSE_ROWS.some((row) => row.code === code)) sawUnrecognized = true;
  }
  if (sawUnrecognized) return 'unknown';
  if (sawPassage) return 'passage-reduced';
  if (sawAi) return 'ai-unavailable';
  return 'cosmetic';
}

/**
 * Tempdoc 805 §G.2 — the affordance-scoped consequence caveats (`availability.ts`'s `degraded`
 * kind). They live HERE, beside the banner wording and the classifier that licenses them, because
 * round 11 found the keyword-fallback claim in TWO modules disagreeing with the same trace: one
 * module owns the claim's words, every other surface imports them. The
 * `consequence-classification` gate enforces exactly that (no re-authored claim literal).
 */
export const KEYWORD_FALLBACK_CAVEAT =
  'Showing keyword-ranked results — semantic ranking is degraded';

/** 805 §G.2 — the passage-leg-only caveat: document-level semantic ranking still serves. */
export const PASSAGE_REDUCED_CAVEAT =
  'Passage-level precision is reduced — results are still ranked semantically';

/**
 * 805 §G.2 — the calm caveat: nothing about retrieval itself is reduced.
 *
 * Round-14 finding 8: the pre-fix wording ("An optional ranking model is unavailable") named NEITHER
 * the feature nor the model, and a careful reader with full API access resolved it to the WRONG one
 * (the cross-encoder reranker, which was measured `status: active` / `executionProvider: cuda` while
 * the actual gap was `lambdamartModel: DEGRADED / lambdamart.not_configured`). The caveat now names
 * the feature the `cosmetic` class is actually about — the same feature `CAUSE_ROWS`' LambdaMART rows
 * word — so the affordance caveat and the cause list cannot be resolved to different models.
 */
export const OPTIONAL_CAPABILITY_CAVEAT =
  'Learned re-ranking (LambdaMART) is unavailable — results are complete, ranking may be simpler';

/**
 * Round-14 finding 8 — the AI-model-unavailable sibling. `ai-unavailable` is a different consequence
 * class from `cosmetic` (chat/answer features are off; retrieval is untouched), so it must not borrow
 * the learned-re-ranking wording above: naming a feature the cause is not about is the very defect
 * finding 8 records, in the other direction. Same claim the banner's AI branch makes, at affordance scope.
 */
export const AI_UNAVAILABLE_CAVEAT =
  'The local AI model is unavailable — search results are complete, chat and answers are off';

const SEVERITY_RANK: Record<ReasonSeverity, number> = { info: 0, warn: 1, error: 2 };

/**
 * Worst-of reason severity (595 §10.5) — the verdict's degradation severity.
 * An unknown / unmapped code defaults to `warn` (never silently `info`): we don't
 * downgrade an unrecognized degradation to "cosmetic". Empty ⇒ `warn`.
 */
export function severityForCodes(codes: readonly string[]): Severity {
  let worst: ReasonSeverity = codes.length === 0 ? 'warn' : 'info';
  let sawAny = false;
  for (const code of codes) {
    const row = CAUSE_ROWS.find((c) => c.code === code);
    const sev: ReasonSeverity = row?.severity ?? 'warn';
    if (!sawAny || SEVERITY_RANK[sev] > SEVERITY_RANK[worst]) {
      worst = sev;
      sawAny = true;
    }
  }
  return worst;
}

/**
 * Project the ONE system-health verdict (tempdoc 595 §4.2) into the search
 * window's degradation notice. The banner CONSUMES the verdict — it does NOT
 * re-read `readiness.retrieval` (so it can no longer contradict the Health
 * header/footer, the §1.1 third-interpreter split). Returns null unless the
 * verdict is `degraded`; a cosmetic degradation (e.g. LambdaMART off, severity
 * `info`) is worded calmly and accurately — never the over-claiming "showing keyword
 * results" that misdescribes a re-ranking gap as a retrieval failure (595 §10.3).
 * Tempdoc 805 §G.2: the impairing wording is licensed by `classifyConsequence`, not
 * by severity, so a passage-only gap can no longer claim a keyword fallback.
 */
export function readinessNotice(verdict: SystemHealthVerdict): ReadinessNoticeView | null {
  // Tempdoc 637 #1: a dead/replaced FE→backend binding surfaces AS a loud notice at its own
  // layer — never a silent empty result one layer up. The backend is unreachable, so the remedy
  // is a client action (reload), conveyed in the body; the button falls back to Open Health
  // (a backend `operation` remedy would be pointless against a dead backend). In production the
  // shell re-resolves automatically (637 #1 Phase 2), so the notice self-clears on reconnect.
  if (verdict.kind === 'unreachable') {
    return {
      headline: 'Backend disconnected.',
      body: 'Lost the connection to the search backend — reconnecting automatically. Reload the app if this persists.',
      causes: wordCauses(verdict.reasons),
      remedy: OPEN_HEALTH,
    };
  }
  if (verdict.kind !== 'degraded') return null;
  // Tempdoc 600 Design A: the actionable cause now arrives as real reason codes (the worker's
  // embedding/schema compat codes), not a synthetic boolean-derived token — so the `causes` slot
  // names the SPECIFIC cause instead of being empty.
  const codes = verdict.reasons;
  // Tempdoc 804 §B5/§D1 — cause-list SCOPING: the rebuild headline lists only the causes a rebuild
  // clears. Before, every verdict reason was rendered under the one Force-Rebuild remedy, so causes
  // a rebuild cannot fix (`lambdamart.not_configured`, `inference.offline`) read as reindex causes.
  // Non-reindex codes keep their own rows and remedies via the paths below (this branch is only
  // reached when a genuinely degrading reindex cause is present).
  const reindexCauses = codes.filter(isReindexCause);
  if (reindexCauses.length > 0) {
    return {
      headline: 'Reindex required.',
      body: 'Semantic search is degraded until the index is rebuilt — results may be keyword-only.',
      causes: wordCauses(reindexCauses),
      remedy: { kind: 'operation', operationId: 'core.rebuild-index' },
    };
  }
  if (verdict.severity === 'info') {
    // Tempdoc 804 §D1 — the ADVISORY index-format state. Reached only when no degrading cause is
    // present (the reindex branch above and the `info` severity together guarantee that), so the
    // banner can state the measured truth: retrieval is intact on both legs. The rebuild remedy
    // stays — a rebuild is what adopts the newer index format.
    if (codes.includes(INDEX_SCHEMA_MISMATCH)) {
      return {
        headline: 'Index format is out of date.',
        body: 'Search is fully working — both semantic and keyword retrieval are active. Rebuilding will pick up newer index features.',
        causes: wordCauses(codes.filter((c) => c !== INDEX_SCHEMA_MISMATCH)),
        remedy: { kind: 'operation', operationId: 'core.rebuild-index' },
      };
    }
    // §10.3 — cosmetic/optional degradation: search still serves fully; say so
    // accurately and calmly (consistent with the Health header's "Reduced capability").
    return {
      headline: 'Reduced search capability.',
      body: 'An optional capability is unavailable; results are still fully semantic.',
      causes: wordCauses(codes),
      remedy: pickRemedy(codes),
    };
  }
  // Tempdoc 804 §B5 (round-11 live finding), generalized by 805 §G.2 — an impairing degradation is
  // not automatically a RETRIEVAL degradation. The consequence comes from the ONE classifier, the way
  // the reindex branch above selects by cause class: only a retrieval-impairing (or unclassifiable)
  // cause licenses "showing keyword results", a passage-vector gap licenses only the passage claim,
  // and only a positively-known AI-model cause licenses the calm AI-features wording. Each branch
  // also SCOPES its cause list + remedy to the codes of its own class, so a cause a branch cannot
  // speak to is never presented under that branch's consequence or remedy.
  const consequence = classifyConsequence(codes);
  if (consequence === 'passage-reduced') {
    const passageCauses = codes.filter(isPassageReduced);
    return {
      headline: 'Semantic search partially degraded.',
      body: 'Passage-level precision is reduced while passage embeddings are missing — results are still ranked semantically.',
      causes: wordCauses(passageCauses),
      remedy: pickRemedy(passageCauses),
    };
  }
  if (consequence === 'ai-unavailable') {
    const aiCauses = codes.filter((c) => AI_MODEL_UNAVAILABLE_CODES.has(c));
    return {
      headline: 'AI features unavailable.',
      body: 'Search is fully working — both semantic and keyword retrieval are active. Chat and answer features are unavailable until the AI model is online.',
      causes: wordCauses(aiCauses),
      remedy: pickRemedy(aiCauses),
    };
  }
  // Impairing degradation (warn/error): retrieval genuinely fell back, or a code we cannot classify
  // leaves us unable to assert otherwise. The list scopes to the causes that carry THIS consequence
  // (impairing + unclassified). A recognized non-retrieval cause at warn severity (e.g. `ocr.*`)
  // reaches here with no such cause to name — it keeps its own causes rather than a claim with an
  // empty cause list, which is the pre-805 behaviour for that set.
  const impairingCauses = codes.filter(isRetrievalImpairing);
  const listed = impairingCauses.length > 0 ? impairingCauses : codes;
  return {
    headline: 'Semantic search degraded.',
    body: 'Showing keyword results; relevance ranking may be reduced.',
    causes: wordCauses(listed),
    remedy: pickRemedy(listed),
  };
}

/**
 * Round-14 finding 9 — does this verdict warrant the search surface's BANNER-TIER warning?
 *
 * The banner's chrome (alert triangle + a "Reduced search capability" headline in the same slot a
 * genuine retrieval failure uses) is warning-tier presentation. An `info`-severity verdict is by
 * construction a cause that leaves search fully serving — measured live as a permanent, unconfigurable
 * optional gap holding ~25% of the space above the fold indefinitely, which trains the alarm to be
 * ignored. So severity decides the TIER: `info`-only causes drop out of the banner and are carried by
 * Health (which renders the same verdict's causes), while `warn`/`error` — and `unreachable`, which is
 * not a degradation at all — keep the banner.
 *
 * The notice itself is unchanged: `readinessNotice` still projects the info-tier wording (Health reads
 * it), and this predicate never *widens* what the banner shows — it can only withhold it.
 */
export function warrantsSearchDegradationBanner(verdict: SystemHealthVerdict): boolean {
  if (readinessNotice(verdict) === null) return false;
  return !(verdict.kind === 'degraded' && verdict.severity === 'info');
}

/** Word each known code; unknown codes word generically (deduped, original order). */
function wordCauses(codes: readonly string[]): string[] {
  const out: string[] = [];
  for (const code of codes) {
    const row = CAUSE_ROWS.find((c) => c.code === code);
    const wording = row ? row.wording : `Degraded: ${code}`;
    if (!out.includes(wording)) out.push(wording);
  }
  return out;
}

/** First mapped remedy in CAUSE_ROWS priority order; Open Health otherwise. */
function pickRemedy(codes: readonly string[]): NoticeRemedy {
  for (const row of CAUSE_ROWS) {
    if (row.remedy && codes.includes(row.code)) return row.remedy;
  }
  return OPEN_HEALTH;
}

/**
 * Tempdoc 596 §17 — the per-affordance read-seam over the ONE reason vocabulary. The CONTROL-scoped
 * projection (`projectAvailability`) reads `{ wording, remedy }` for a single reason-code here, the
 * same `CAUSE_ROWS` the SYSTEM-scoped banner (`readinessNotice`) and the 595 verdict (`severityForCodes`)
 * project from — so a control's reason and the window's reason, and their remedies, cannot drift. An
 * unknown code words generically (mirrors `wordCauses`' fallback), never silence.
 */
export function reasonFor(code: string): { wording: string; remedy?: NoticeRemedy } {
  const row = CAUSE_ROWS.find((c) => c.code === code);
  return row ? { wording: row.wording, remedy: row.remedy } : { wording: `Degraded: ${code}` };
}
