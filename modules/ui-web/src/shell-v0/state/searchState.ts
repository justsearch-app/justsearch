// SPDX-License-Identifier: Apache-2.0
/**
 * searchState — Lit-side search store (slice 463 phase 1).
 * Pub-sub. Owns query, debounced query, results, loading state.
 *
 * Tempdoc 577 Goal 1 Phases 4+5 (Move G + Ext II):
 *  - ONE intent seam: {@link buildSearchIntent} is the single place a search
 *    request body is constructed; every issuance path (typing debounce, explicit
 *    submit, pinned-chip restore via {@link restoreSearch}, URL `?query=`) flows
 *    through {@link setQuery}/{@link submitSearch} → {@link runSearch} → that
 *    one constructor. A second request-shaping site is structurally absent.
 *  - STAGED execution: the keystroke path runs a QUICK pass (BM25-only pipeline
 *    override — no LLM expansion, no rerankers; ~10× faster with AI online) and
 *    schedules a REFINED pass (full default pipeline) after the input settles;
 *    an explicit submit runs the refined pass immediately. Results carry their
 *    `passStage` so the surface can label quick results as provisional.
 *  - HONEST latency: `processingTimeMs` is the measured wall time of the pass
 *    that produced the current results (was: the trace FUSION stage's
 *    worker-side ms — that retrieval-phase number now lives in `retrievalMs`
 *    and renders only at diagnostic altitude). A stale response (superseded
 *    generation) is dropped, never rendered.
 */

import { authorizedFetch } from '../api/authorizedFetch.js';
import { createApiError } from '../../api/http.js';
import { localizeError } from '../../i18n/errorCatalog.js';
import type { SelectedItem } from './inspectorState.js';
// Tempdoc 549 (Slice 1) — query-level search trace (SearchIntrospection) is
// captured into search state so the SearchSurface can mount the explain panel
// (G33). Already emitted on every response by the worker (525, always-on).
import type { SearchTrace, HitStage } from '../../api/generated/index.js';
// Tempdoc 564 Phase 3: the trace types are the generated Zod projections (record → JSON Schema →
// {TS, Zod}); the raw REST JSON is validated here at the single parse boundary via Zod — the FE no
// longer depends on protobuf-es (`knowledge_pb`) for SearchTrace.
import { searchTraceSchema } from '../../api/generated/schema-types/search-trace.js';
import { hitStageSchema } from '../../api/generated/schema-types/hit-stage.js';
// Slice 486 G36-widening (filter-snapshot) — date-range filter spec sourced from
// searchFiltersState. searchState reads (does not mutate) the
// active filter and includes it in the request body when set.
import {
  getFilters,
  hasActiveFilter,
  getFacetSelections,
  type SearchFilterSpec,
} from './searchFiltersState.js';

export interface SearchHit {
  id: string;
  title: string;
  path: string;
  snippet?: string;
  score?: number;
  /**
   * Per-hit slice of the unified stage vocabulary (tempdoc 549 / G111). Path-sparse —
   * only the stages that touched this hit. The sole per-hit ranking-provenance source
   * (the leg-keyed `provenance` was retired in Phase E2).
   */
  trace?: HitStage[];
  /** Tempdoc 577 Phase 7 (Move B) — typed identity from the wire fields. */
  kind?: string;
  mimeBase?: string;
  /** Tempdoc 577 Phase 7 — excerpt regions (refined pass, `includeExcerpts`): the
   *  worker-computed best passages; preferred snippet source over content_preview. */
  excerptRegions?: Array<{ text?: string; approxLine?: number }>;
  /** Tempdoc 811 (C-1a) — the corpus this hit came from (`fields.collection`). Absent or
   *  `default` for the user's own documents; a named value (e.g. `justsearch-help`) marks the
   *  row so app-internal docs are distinguishable in the result list. */
  collection?: string;
}

/** Tempdoc 577 Phase 5 — which execution pass produced the current results. */
export type SearchPassStage = 'quick' | 'refined';

export interface SearchState {
  query: string;
  results: SearchHit[];
  /** The bounded fused-candidate-union (retrieval window). Diagnostic only — NOT the headline. */
  totalHits: number;
  /** Tempdoc 597: the TRUE matched-document count. The headline reads "Top N of M matches" off
   *  this (M), so it can't contradict the facet chips (which count the same matched population). */
  matchCount: number;
  /** Advanced quick results await the refined population count. */
  countsPending?: boolean;
  /** Tempdoc 597 §16.2: true when the match scan hit its `maxDocsScanned` cap, so `matchCount` is a
   *  lower bound — the headline renders "M+" (the Elasticsearch `gte` / Lucene relation convention). */
  facetsTruncated: boolean;
  isSearching: boolean;
  /** Wall-clock ms of the pass that produced the current results (577 Phase 4 — honest latency). */
  processingTimeMs: number | null;
  error: string | null;
  /** Validated failure facts; retryability remains the server's decision, including unknown. */
  errorInfo?: ReturnType<typeof createApiError> | null;
  /**
   * Tempdoc 549 — the unified stage-keyed search trace: the single canonical
   * artifact the explain panel renders. Supersedes the retired `introspection` (E4) and the
   * retired `pipelineExecution` (E3 — timing is now on the trace's stage `ms`). Null until
   * a search completes.
   */
  searchTrace: SearchTrace | null;
  /** Worker-side retrieval-phase ms (the trace FUSION stage) — diagnostic altitude only. */
  retrievalMs?: number | null;
  /** True while an in-flight pass has exceeded the slow-hint threshold (577 Phase 4). */
  slowSearch?: boolean;
  /** Which pass produced the current results (quick = provisional, refined = authoritative). */
  passStage?: SearchPassStage | null;
  /** True while the refined pass is in flight BEHIND already-displayed quick results. */
  isRefining?: boolean;
  /** Tempdoc 577 Phase 6 (Move E) — the response's emitted facet counts (field → value → count).
   *  Requested on the refined pass only; a quick pass keeps the previous counts. */
  facets?: SearchFacetCounts | null;
  /** Tempdoc 580 §17 P3 — the search's stable join key; the FE echoes it back with a result
   *  disposition (opened/dwelled/refined-without-opening) so feedback joins the ranking features. */
  interactionId?: string | null;
}

/** The wire facet payload shape (KnowledgeSearchResponse.facets). */
export type SearchFacetCounts = Record<string, Record<string, number>>;

let state: SearchState = {
  query: '',
  results: [],
  totalHits: 0,
  matchCount: 0,
  facetsTruncated: false,
  isSearching: false,
  processingTimeMs: null,
  error: null,
  searchTrace: null,
  retrievalMs: null,
  slowSearch: false,
  passStage: null,
  isRefining: false,
};

const listeners = new Set<(s: SearchState) => void>();
let apiBase = '';
let debounceTimer: number | null = null;
let settleTimer: number | null = null;
let slowTimer: number | null = null;
let inflightAbort: AbortController | null = null;
/** Generation counter — a response or scheduled pass from a superseded generation is dropped. */
let generation = 0;
/** Tempdoc 580 §17 P3 — true once the user opened a result this query; gates the
 *  refined-without-opening recall signal (§16's one signal that escapes recall-blindness). */
let openedThisQuery = false;

/** Tempdoc 580 §17 P3 — the pending dwell timer + the (interactionId, docId) captured when the
 *  result was opened. Captured at open time so a later DWELLED posts under the RIGHT query even if a
 *  new search has since changed `state.interactionId`. */
let dwellTimer: number | null = null;
let dwellIid: string | null = null;
let dwellDocId: string | null = null;

const DEBOUNCE_MS = 200;
/** A result kept open at least this long counts as DWELLED (the graded positive above OPENED). */
const DWELL_THRESHOLD_MS = 3000;
/** The refined pass runs this long after the quick pass lands (input settled). */
const SETTLE_MS = 600;
/** An in-flight pass longer than this flips `slowSearch` (the in-flight legibility hint). */
const SLOW_HINT_MS = 400;

export function getSearchState(): SearchState {
  return state;
}

export function setSearchApiBase(base: string): void {
  apiBase = base;
}

function emit(): void {
  for (const l of listeners) l(state);
}

/** Tempdoc 580 §17 P3 — POST a result disposition to the canonical stream (fire-and-forget). */
function postDisposition(interactionId: string, docId: string, kind: string): void {
  void authorizedFetch((apiBase || '') + '/api/knowledge/disposition', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ interactionId, docId, kind }),
  }).catch(() => {}); // feedback never disrupts the UI
}

/** Tempdoc 580 §17 P3 — record that the user OPENED a result (the positive disposition). */
export function recordOpenDisposition(docId: string): void {
  const iid = state.interactionId;
  if (!iid || !docId) return;
  openedThisQuery = true;
  postDisposition(iid, docId, 'opened');
}

/** Tempdoc 580 §17 P3 — if the user refined the query without opening anything, emit the
 *  recall-failure signal for the prior top result (the §16 negative that escapes recall-blindness). */
function recordRefineWithoutOpening(): void {
  const prior = state;
  const top = prior.results[0];
  if (!openedThisQuery && prior.interactionId && top) {
    postDisposition(prior.interactionId, top.id, 'refined_without_opening');
  }
}

/** Cancel any pending dwell timer (the opened result was closed, switched, or superseded). */
function cancelDwell(): void {
  if (dwellTimer !== null) {
    window.clearTimeout(dwellTimer);
    dwellTimer = null;
  }
  dwellIid = null;
  dwellDocId = null;
}

/** Tempdoc 580 §17 P3 — the user opened a result; arm a DWELLED timer. If the result stays open
 *  past {@link DWELL_THRESHOLD_MS}, emit the graded-positive DWELLED disposition (stronger than the
 *  immediate OPENED). The (interactionId, docId) are captured NOW so the later POST keys the query
 *  the user actually dwelled on, even if a new search has since changed the live interactionId. */
export function recordInspectorOpen(docId: string): void {
  cancelDwell();
  const iid = state.interactionId;
  if (!iid || !docId) return; // no join key → nothing to attribute
  // Only attribute dwell to a result from THIS search — a deep-link / non-search open would
  // otherwise be mis-keyed to the stale interactionId (and dropped at projection as noise).
  if (!state.results.some((r) => r.id === docId)) return;
  dwellIid = iid;
  dwellDocId = docId;
  dwellTimer = window.setTimeout(() => {
    if (dwellIid && dwellDocId) {
      postDisposition(dwellIid, dwellDocId, 'dwelled');
    }
    dwellTimer = null;
    dwellIid = null;
    dwellDocId = null;
  }, DWELL_THRESHOLD_MS);
}

/** Tempdoc 580 §17 P3 — the result was closed or switched before the dwell threshold; cancel. */
export function recordInspectorClose(): void {
  cancelDwell();
}

export function subscribeSearch(listener: (s: SearchState) => void): () => void {
  listeners.add(listener);
  listener(state);
  return () => listeners.delete(listener);
}

// Raw REST shape (plain JSON). The trace fields are `unknown` here — they are validated into the
// generated `SearchTrace`/`HitStage` types via the generated Zod at the parse boundary (564 Phase 3;
// the FE is proto-free for SearchTrace — the 553 Phase D knowledge_pb/fromJson route was reverted).
interface SearchResponse {
  results?: Array<{
    id: string;
    score?: number;
    fields?: Record<string, string>;
    trace?: unknown;
    excerptRegions?: Array<{ text?: string; approxLine?: number }>;
  }>;
  advancedSyntax?: boolean;
  totalHits?: number;
  /** Tempdoc 597: the true matched-document count (additive wire field; cast-tolerant parse). */
  matchCount?: number;
  /** Tempdoc 597 §16.2: the facet match scan hit its cap, so `matchCount` is a lower bound ("M+"). */
  facetsTruncated?: boolean;
  searchTrace?: unknown;
  facets?: SearchFacetCounts;
}

/** The search transport stays authorizedFetch; the general HTTP client's automatic retries
 * are inappropriate here. Decode only the failure envelope at this existing issuance seam. */
async function readSearchFailure(res: Response): Promise<ReturnType<typeof createApiError>> {
  let raw: unknown;
  try {
    raw = await res.json();
  } catch (err) {
    if (err instanceof Error && err.name === 'AbortError') throw err;
    // Proxies may return HTML or an empty body. Neither is user-facing error prose.
  }
  const data = raw && typeof raw === 'object' && !Array.isArray(raw)
    ? raw as Record<string, unknown> : {};
  const text = (value: unknown): string | undefined =>
    typeof value === 'string' && value.trim() ? value : undefined;
  const code = text(data.errorCode);
  const wireKey = text(data.i18nKey);
  const message = code === 'SEARCH_SYNTAX_INVALID'
    ? text(data.error) ?? 'Check the search syntax.'
    : localizeError({
    code,
    i18nKey: wireKey?.startsWith('errors.') ? wireKey : undefined,
    // ApiErrorHandler supplies sanitized English when the catalog is unavailable.
    message: code ? text(data.error) ?? 'Search could not be completed.' : 'Search could not be completed.',
  }).message;
  return createApiError(message, code, res.status, text(data.requestId), text(data.errorClass),
    typeof data.retryable === 'boolean' ? data.retryable : undefined);
}

function searchFailureMessage(error: ReturnType<typeof createApiError>): string {
  if (error.code === 'SEARCH_SYNTAX_INVALID') return error.message;
  const recovery = error.retryable === true
    ? 'Try searching again. If the problem continues, open Health for details.'
    : error.retryable === false
      ? 'Review the query and any restrictions. Open Health for details before trying again.'
      : 'Open Health to check the connection and service status before trying again.';
  return `${error.message} ${recovery}`;
}

/** Validate the raw per-hit trace JSON into HitStage[] via the generated Zod (564 Phase 3). */
function toHitStages(raw: unknown): HitStage[] | undefined {
  if (!Array.isArray(raw) || raw.length === 0) return undefined;
  // Display-only trace: tolerate an anomalous element (fall back to the raw value) rather than
  // dropping the whole per-hit trace — mirrors the non-fail-open-but-resilient parse boundary.
  return raw.map((s) => {
    const r = hitStageSchema.safeParse(s);
    return r.success ? r.data : (s as HitStage);
  });
}

/**
 * Tempdoc 577 Phase 5 — the QUICK pass's pipeline override: BM25 only, every
 * AI-bound stage off (expansion = the 1.5 s LLM budget; rerankers; freshness).
 * Mirrors the wire `PipelineConfig` shape (KnowledgeSearchController).
 */
export const QUICK_PIPELINE = {
  sparseEnabled: true,
  denseEnabled: false,
  spladeEnabled: false,
  fusionAlgorithm: 'none',
  lambdamartEnabled: false,
  crossEncoderEnabled: false,
  crossEncoderWindow: 0,
  expansionEnabled: false,
  freshnessEnabled: false,
} as const;

/**
 * Tempdoc 577 Phase 6 (Move E) — the facet fields the refined pass requests
 * (facet-capable keyword fields from fields.v1.json), and the map from facet
 * field name → the wire's existing keyword-filter key
 * (KnowledgeSearchController.java filters parsing).
 */
export const FACET_FIELDS = ['file_kind', 'mime_base', 'language', 'meta_author'] as const;
export const FACET_FILTER_KEYS: Record<(typeof FACET_FIELDS)[number], string> = {
  file_kind: 'fileKind',
  mime_base: 'mimeBase',
  language: 'language',
  meta_author: 'metaAuthor',
};

/** The wire request body (the subset this store sends). */
export interface SearchRequestBody {
  query: string;
  querySyntax: 'search';
  limit: number;
  mode?: string;
  pipeline?: typeof QUICK_PIPELINE;
  includeExcerpts?: boolean;
  facets?: { include: boolean; fields: Array<{ field: string }> };
  filters?: { modifiedAt?: SearchFilterSpec } & Partial<Record<string, unknown>>;
}

/**
 * Tempdoc 577 Ext II — the ONE search-intent constructor. Every issuance path
 * builds its request body here; identical inputs yield identical bodies
 * regardless of how the search was initiated. The quick stage pins the cheap
 * pipeline override; the refined stage runs the backend's default pipeline and
 * additionally requests excerpt regions (Move B's snippet source) + the facet
 * counts (Move E's set self-description). Facet SELECTIONS filter both stages.
 */
export function buildSearchIntent(
  q: string,
  filters: SearchFilterSpec,
  stage: SearchPassStage,
  facetSelections: Record<string, string[]> = {},
  // Tempdoc 585 §D Phase 4 (D4b) — the search scope. 'documents' (default) sends no collection
  // filter, so the backend default-EXCLUDES the reserved agent-history collection; 'agent-history'
  // scopes the query to it (the user searching their own agent history).
  scope: SearchScope = 'documents',
  // Search-Thread S3 — pinned scope chips (a file / a result set). Explicit parameter (not read
  // from module state) so this constructor stays pure/testable, mirroring `scope` above.
  chips: readonly SearchScopeChip[] = [],
): SearchRequestBody {
  const body: SearchRequestBody = { query: q, querySyntax: 'search', limit: 50 };
  if (stage === 'quick') {
    body.mode = 'text';
    body.pipeline = QUICK_PIPELINE;
  } else {
    body.includeExcerpts = true;
    body.facets = { include: true, fields: FACET_FIELDS.map((field) => ({ field })) };
  }
  const filterObj: { modifiedAt?: SearchFilterSpec } & Record<string, unknown> = {};
  if (hasActiveFilter(filters)) {
    // Backend accepts `filters.modifiedAt: { fromMs?, toMs? }`
    // (KnowledgeSearchController.java:170-173). Map the FE
    // `modifiedFromMs/modifiedToMs` shape to the wire shape.
    const modifiedAt: { fromMs?: number; toMs?: number } = {};
    if (typeof filters.modifiedFromMs === 'number') {
      modifiedAt.fromMs = filters.modifiedFromMs;
    }
    if (typeof filters.modifiedToMs === 'number') {
      modifiedAt.toMs = filters.modifiedToMs;
    }
    filterObj.modifiedAt = modifiedAt as SearchFilterSpec;
  }
  // Facet selections → the wire's existing keyword filters (clickable half of dual filtering).
  for (const field of FACET_FIELDS) {
    const selected = facetSelections[field];
    if (selected && selected.length > 0) {
      filterObj[FACET_FILTER_KEYS[field]] = [...selected];
    }
  }
  // Tempdoc 585 §D Phase 4 (D4b) — scope to the agent-history collection (else the backend default
  // excludes it). The wire key is the camelCase Java record component name (`collection`).
  if (scope === 'agent-history') {
    filterObj.collection = ['agent-history'];
  }
  // Search-Thread S3 — scope chips constrain both instant search and AI retrieval to the union of
  // their pinned docIds (`KnowledgeSearchRequest.Filters.docIds`, applied in QueryFilterBuilder).
  if (chips.length > 0) {
    const docIdSet = new Set<string>();
    for (const c of chips) for (const id of c.docIds) docIdSet.add(id);
    if (docIdSet.size > 0) {
      filterObj.docIds = [...docIdSet];
    }
  }
  if (Object.keys(filterObj).length > 0) {
    body.filters = filterObj;
  }
  return body;
}

// ---------------------------------------------------------------------------
// Collection enumeration is unattended Library hydration on a dedicated observation route.

/** The facet field that names a document's corpus (`fields.v1.json` `roles: [filter, facet]`). */
export const COLLECTION_FACET_FIELD = 'collection';

/**
 * Issues the enumeration probe and returns the raw response body for `deriveOtherSources`.
 *
 * `baseUrl` is explicit because this module's `apiBase` is set when the chat surface mounts
 * ({@link setSearchApiBase}), which the Library cannot assume has happened; it falls back to the
 * module value. Throws on a non-2xx so the caller decides how loud the failure is.
 */
export async function fetchCollectionFacet(
  maxDocsScanned: number,
  baseUrl?: string,
  signal?: AbortSignal,
): Promise<unknown> {
  const res = await authorizedFetch(((baseUrl ?? apiBase) || '') + '/api/knowledge/collection-facet', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ maxDocsScanned }),
    ...(signal ? { signal } : {}),
  });
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`);
  }
  return res.json();
}

function clearTimers(): void {
  if (debounceTimer !== null) {
    window.clearTimeout(debounceTimer);
    debounceTimer = null;
  }
  if (settleTimer !== null) {
    window.clearTimeout(settleTimer);
    settleTimer = null;
  }
  if (slowTimer !== null) {
    window.clearTimeout(slowTimer);
    slowTimer = null;
  }
}

export function setQuery(q: string): void {
  generation += 1;
  inflightAbort?.abort();
  state = { ...state, query: q, totalHits: 0, matchCount: 0, countsPending: true,
    facetsTruncated: false, passStage: null, searchTrace: null,
    error: null, errorInfo: null, isSearching: !!q.trim(), isRefining: false, slowSearch: false };
  emit();
  clearTimers();
  cancelDwell(); // a new query means the user has moved on from any opened result
  if (!q.trim()) {
    state = {
      ...state,
      results: [],
      totalHits: 0,
      matchCount: 0,
      facetsTruncated: false,
      isSearching: false,
      processingTimeMs: null,
      error: null,
      errorInfo: null,
      searchTrace: null,
      retrievalMs: null,
      slowSearch: false,
      passStage: null,
      isRefining: false,
      countsPending: false,
      facets: null,
    };
    emit();
    return;
  }
  const gen = generation;
  debounceTimer = window.setTimeout(() => {
    void runStagedSearch(q, gen);
  }, DEBOUNCE_MS);
}

// ===== Tempdoc 585 §D Phase 4 (D4b) — the search scope (documents vs the agent-history collection) =====
export type SearchScope = 'documents' | 'agent-history';
let _searchScope: SearchScope = 'documents';

/** The active search scope ('documents' by default; 'agent-history' searches the run transcripts). */
export function getSearchScope(): SearchScope {
  return _searchScope;
}

/** Set the search scope and, if a query is active, re-issue it so results reflect the change. */
export function setSearchScope(scope: SearchScope): void {
  if (_searchScope === scope) return;
  _searchScope = scope;
  if (state.query.trim()) submitSearch();
}

// ===== Search-Thread S3 — scope chips (constrain instant search + AI retrieval to a file or a
// result set) =====
//
// A scope chip is a session-ephemeral constraint the user pins onto the search: "just this file"
// or "just this result set". Distinct from {@link SearchScope}, which toggles the collection
// (documents vs agent-history) — chips narrow WITHIN the active scope via `filters.docIds`.
export interface SearchScopeChip {
  kind: 'file' | 'result-set' | 'path';
  label: string;
  docIds: readonly string[];
}

let scopeChips: SearchScopeChip[] = [];
const scopeChipListeners = new Set<(chips: SearchScopeChip[]) => void>();

function emitScopeChips(): void {
  for (const l of scopeChipListeners) l(scopeChips);
}

/** Snapshot of the active scope chips. */
export function getScopeChips(): SearchScopeChip[] {
  return scopeChips;
}

/** Subscribe to scope-chip changes. Listener fires once with the current value on subscribe,
 *  then on every mutating call. Returns an unsubscribe handle (mirrors {@link subscribeSearch}). */
export function subscribeScopeChips(listener: (chips: SearchScopeChip[]) => void): () => void {
  scopeChipListeners.add(listener);
  listener(scopeChips);
  return () => scopeChipListeners.delete(listener);
}

/** Same kind + same docId set (order-independent) — the dedup key for {@link addScopeChip}. */
function sameScopeChip(a: SearchScopeChip, b: SearchScopeChip): boolean {
  if (a.kind !== b.kind) return false;
  if (a.docIds.length !== b.docIds.length) return false;
  const bSet = new Set(b.docIds);
  return a.docIds.every((id) => bSet.has(id));
}

/**
 * Add a scope chip; a duplicate (same kind + same docId set) is a no-op. Deliberately does NOT
 * re-run the active search — mirrors the facet-selection precedent (`toggleFacetValue` in
 * searchFiltersState.ts, which only mutates + notifies; SearchSurface/UnifiedChatView call
 * `submitSearch()` themselves after toggling). The host does the same here.
 */
export function addScopeChip(chip: SearchScopeChip): void {
  if (scopeChips.some((c) => sameScopeChip(c, chip))) return;
  scopeChips = [...scopeChips, chip];
  emitScopeChips();
}

/** Remove the chip at `index`. Out-of-range indices are a no-op. Does NOT re-run the search
 *  (same posture as {@link addScopeChip} — the host re-issues). */
export function removeScopeChip(index: number): void {
  if (index < 0 || index >= scopeChips.length) return;
  scopeChips = scopeChips.filter((_, i) => i !== index);
  emitScopeChips();
}

/** Clear every scope chip. Does NOT re-run the search (same posture as {@link addScopeChip}). */
export function clearScopeChips(): void {
  if (scopeChips.length === 0) return;
  scopeChips = [];
  emitScopeChips();
}

/**
 * Tempdoc 577 Phase 5 — explicit submit (Enter): skip the staging and run the
 * full refined pass immediately. Supersedes any pending quick/refined pass.
 */
export function submitSearch(): void {
  const q = state.query;
  if (!q.trim()) return;
  generation += 1;
  clearTimers();
  cancelDwell();
  void runSearch(q, 'refined', generation);
}

/** The staged keystroke path: quick pass now, refined pass after the input settles. */
async function runStagedSearch(q: string, gen: number): Promise<void> {
  const accepted = await runSearch(q, 'quick', gen);
  if (!accepted || gen !== generation || state.error) return;
  settleTimer = window.setTimeout(() => {
    void runSearch(q, 'refined', gen);
  }, SETTLE_MS);
}

async function runSearch(q: string, stage: SearchPassStage, gen: number): Promise<boolean> {
  if (gen !== generation) return false;
  // Tempdoc 580 §17 P3 — a new query replacing a settled, unopened result set is the
  // recall-failure signal (the user looked at the whole set and opened nothing).
  if (stage === 'quick' && state.passStage === 'refined') {
    recordRefineWithoutOpening();
  }
  inflightAbort?.abort();
  inflightAbort = new AbortController();
  // A refined pass behind displayed results refines quietly; everything else is a full search.
  const refiningBehindResults = stage === 'refined' && state.results.length > 0 && !state.error;
  state = {
    ...state,
    isSearching: !refiningBehindResults,
    isRefining: refiningBehindResults,
    slowSearch: false,
    error: null,
    errorInfo: null,
  };
  emit();
  if (slowTimer !== null) window.clearTimeout(slowTimer);
  slowTimer = window.setTimeout(() => {
    if (gen === generation && (state.isSearching || state.isRefining)) {
      state = { ...state, slowSearch: true };
      emit();
    }
  }, SLOW_HINT_MS);
  const t0 = performance.now();
  try {
    const body = buildSearchIntent(
      q,
      getFilters(),
      stage,
      getFacetSelections(),
      _searchScope,
      scopeChips,
    );
    const res = await authorizedFetch((apiBase || '') + '/api/knowledge/search', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: inflightAbort.signal,
    });
    if (gen !== generation) return false; // superseded while in flight — drop silently
    if (!res.ok) {
      const errorInfo = await readSearchFailure(res);
      if (gen !== generation) return false; // also guard the asynchronous body read
      const syntaxInvalid = errorInfo.code === 'SEARCH_SYNTAX_INVALID';
      state = { ...state, isSearching: false, isRefining: false, slowSearch: false,
        ...(syntaxInvalid ? { results: [], totalHits: 0, matchCount: 0, facets: null,
          facetsTruncated: false, passStage: null, searchTrace: null, countsPending: false } : {}),
        error: syntaxInvalid && stage === 'quick' ? null : searchFailureMessage(errorInfo),
        errorInfo };
      emit();
      return false;
    }
    const data = (await res.json()) as SearchResponse;
    if (gen !== generation) return false;
    const hits: SearchHit[] = (data.results ?? []).map((r) => {
      // A pure-dense hit whose chunk-merge was skipped has no fields.path; r.id is then a raw
      // `chunk:<uuid>` — don't surface that scheme as the displayed title/path (searchState chunk-id leak).
      const rawPath = r.fields?.path ?? r.id ?? '';
      const path = rawPath.startsWith('chunk:') ? '' : rawPath;
      const filename = path.split(/[\\/]/).pop() || 'Untitled';
      return {
        id: r.id,
        title: filename,
        path,
        snippet: r.fields?.content_preview ?? '',
        score: r.score,
        trace: toHitStages(r.trace),
        // Phase 7 (Move B): typed identity + the worker's best passages.
        kind: r.fields?.file_kind,
        mimeBase: r.fields?.mime_base,
        excerptRegions: Array.isArray(r.excerptRegions) ? r.excerptRegions : undefined,
        // Tempdoc 811 (C-1a): the corpus marker's input — the row says which collection it came
        // from when that is not the user's own documents.
        collection: r.fields?.collection,
      };
    });
    // Tempdoc 564 Phase 3: validate the raw trace JSON against the generated SearchTrace Zod once,
    // at this boundary (Zod strips unknown keys → additive-tolerant). Display-only, so an anomalous
    // trace falls back to the raw value rather than blanking the explain panel.
    const searchTrace: SearchTrace | null = (() => {
      if (data.searchTrace == null) return null;
      const r = searchTraceSchema.safeParse(data.searchTrace);
      return r.success ? r.data : (data.searchTrace as SearchTrace);
    })();
    // Tempdoc 549 Phase E3 → 577 Phase 4: the trace FUSION stage ms is the worker-side
    // retrieval-phase elapsed — a DIAGNOSTIC number. The user-facing processingTimeMs is
    // the measured wall time of this pass (what the user actually waited).
    const retrievalMs = (searchTrace?.stages ?? []).find((s) => s.id === 'fusion')?.ms ?? null;
    state = {
      ...state,
      results: hits,
      totalHits: data.totalHits ?? hits.length,
      // Tempdoc 597: the true matched total (M in "Top N of M matches"). Fall back to totalHits then
      // the hit count if an older worker omits matchCount.
      matchCount: data.matchCount ?? data.totalHits ?? hits.length,
      countsPending: data.advancedSyntax === true && stage === 'quick',
      // §16.2: the scan capped at `maxDocsScanned`, so the matched total above is a lower bound.
      facetsTruncated: data.facetsTruncated === true,
      isSearching: false,
      processingTimeMs: Math.round(performance.now() - t0),
      error: null,
      searchTrace,
      retrievalMs,
      slowSearch: false,
      passStage: stage,
      isRefining: false,
      // Phase 6: facet counts come from the refined pass; a quick pass keeps the
      // previous counts (stale-but-present beats flicker; refined replaces shortly).
      facets: stage === 'quick' && data.advancedSyntax === true ? null
        : data.facets ?? (stage === 'refined' ? null : state.facets),
      // Tempdoc 580 §17 P3 — the per-query join key (additive wire field; FE parse is cast-tolerant).
      interactionId: (data as { interactionId?: string | null }).interactionId ?? null,
    };
    openedThisQuery = false;
    emit();
    return true;
  } catch (err) {
    if (err instanceof Error && err.name === 'AbortError') return false;
    if (gen !== generation) return false;
    state = {
      ...state,
      isSearching: false,
      isRefining: false,
      slowSearch: false,
      error: 'Search could not be completed. Open Health to check the connection and service status before trying again.',
    };
    emit();
    return false;
  }
}

/**
 * Tempdoc 508-followup §β4 — wipe transient search state as part of a
 * profile switch. Cancels any in-flight request, clears query + results
 * + error, and preserves the configured apiBase. Listeners receive a
 * single emit with the reset snapshot.
 */
export function resetSearchState(): void {
  generation += 1;
  clearTimers();
  inflightAbort?.abort();
  inflightAbort = null;
  _searchScope = 'documents'; // Tempdoc 585 §D Phase 4 (D4b) — back to the default scope.
  state = {
    query: '',
    results: [],
    totalHits: 0,
    matchCount: 0,
    facetsTruncated: false,
    isSearching: false,
    processingTimeMs: null,
    error: null,
    searchTrace: null,
    retrievalMs: null,
    slowSearch: false,
    passStage: null,
    isRefining: false,
    facets: null,
  };
  emit();
}

export function hitToSelectedItem(hit: SearchHit): SelectedItem {
  return {
    id: hit.id,
    title: hit.title,
    path: hit.path,
  };
}

// ----- Slice 489 §5 — URL substrate adapter -----
//
// Exposes searchState as a URL-addressable store under the abstract storeId
// "search". The router's URLProjector subscribes for state→URL writes; the
// NavigationHandler (slice 492) calls restoreFromSnapshot when realizing a
// Navigation Intent (URL boot, popstate, backend-broadcast, deep-link, etc.).
//
// Note: subscribe / serialize / restore mirror the StoreAdapter contract in
// modules/ui-web/src/shell-v0/router/storeRegistry.ts without importing it
// (keeps the state module free of router-side dependencies).

export interface SearchSnapshot {
  query?: string | string[];
}

export function serializeSearch(): SearchSnapshot {
  // Only the addressable subset — results / loading / error are derived state.
  return state.query ? { query: state.query } : {};
}

export function restoreSearch(snapshot: SearchSnapshot): void {
  const raw = snapshot.query;
  const q = Array.isArray(raw) ? (raw[0] ?? '') : (raw ?? '');
  if (typeof q !== 'string') return;
  if (q === state.query) return;
  // Run through setQuery so the debounce + staged-fetch path is exercised
  // consistently with user-typed input (the ONE intent seam). Empty string
  // clears results (mirrors clear-on-empty behavior in setQuery).
  setQuery(q);
}
