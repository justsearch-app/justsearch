// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 549 — Canonical (SearchTrace, search-explain) strategy.
 *
 * Renders the unified stage-keyed search trace as the "explain panel" alongside
 * the result list. This is the FE consumer of the single canonical artifact: it
 * reads ONLY `trace.stages` + the query-level scalars (`effectiveMode`,
 * `decisionKind`, `qpp`, `degradation`) — never the legacy `SearchIntrospection`
 * companions, `pipelineExecution`, or per-hit `provenance`. It supersedes
 * `searchIntrospectionExplain`, which retired in Phase E4.
 *
 * Tempdoc 577 Goal 1 Phase 3 (Ext I + Move D) — the ALTITUDE CUT: every facet
 * of the trace carries a declared audience altitude ({@link TRACE_FACET_ALTITUDE},
 * the one authority), and the template derives prominence from the tier instead
 * of authoring it per-facet. User-tier facets (mode legibility, expansion,
 * correction, degradation-in-words, a cross-encoder drop per register F-052)
 * project into one small summary line in a
 * human grammar; diagnostic-tier facets (decision kind, QPP scalars, the full
 * stage list) project into a collapsed disclosure. A diagnostic facet minted
 * into the primary flow is unrepresentable here — there is no field for it.
 *
 * Per-stage timing is read from each stage's `ms` (e.g. the FUSION stage carries
 * the retrieval-phase elapsed, Phase D0). Privacy-safe by construction: the trace
 * carries no query text / filter values (the correction stage's detail is the
 * corrected query, already user-visible via SearchResponse.correctedQuery).
 */

import { html, nothing } from 'lit';
import type { SearchTrace, TraceStage } from '../../../api/generated/index.js';
import type { AggregateStrategy } from '../aggregateRegistry.js';
import { registerAggregateStrategy } from '../aggregateRegistry.js';
import { assertFieldRoles, type FieldRoles } from '../assertExhaustive.js';

/**
 * Field-role classification — drives the behavioral Pass-8 test. Every SearchTrace
 * field except `version` (structural compatibility hint, not rendered) is `visual`.
 */
export const SEARCH_TRACE_EXPLAIN_ROLES: FieldRoles<SearchTrace> = {
  version: 'elided',
  stages: 'visual',
  effectiveMode: 'visual',
  decisionKind: 'visual',
  qpp: 'visual',
  degradation: 'visual',
  // Tempdoc 564 Phase 3: SearchTrace is now the generated Zod-projected plain object (no protobuf
  // `$typeName`/`$unknown` meta-keys), so the exhaustive FieldRoles check covers exactly the wire fields.
};
assertFieldRoles<SearchTrace>(SEARCH_TRACE_EXPLAIN_ROLES);

/**
 * The closed stage vocabulary, keyed by the stable wireId, mapped to a human label.
 * This is the FE half of the stage-completeness invariant (tempdoc 549 principle 9):
 * the Phase F `stage-completeness` governance gate cross-checks these keys against
 * the Java `SearchTrace.StageId` enum — a new stage without a label entry fails the
 * build. Keep in sync with `SearchTrace.StageId#wireId` (app-api).
 */
export const STAGE_LABELS: Record<string, string> = {
  'query-understanding': 'Query understanding',
  expansion: 'Expansion',
  correction: 'Correction',
  'sparse-retrieval': 'Sparse (BM25)',
  'dense-retrieval': 'Dense (vector)',
  'splade-retrieval': 'SPLADE',
  fusion: 'Fusion',
  'chunk-merge': 'Chunk merge',
  'branch-fusion': 'Branch fusion',
  lambdamart: 'LambdaMART',
  'cross-encoder': 'Cross-encoder',
  freshness: 'Freshness',
};

/**
 * Tempdoc 577 Phase 3 (Ext I) — the per-facet altitude authority. Every renderable
 * facet of the trace is assigned 'user' (the primary-flow summary line, worded) or
 * 'diagnostic' (the collapsed disclosure, raw). Exhaustiveness over this closed
 * facet list is pinned by the strategy test; prominence in the template derives
 * from the tier, never from per-facet authoring.
 */
export const TRACE_FACETS = [
  'mode',
  'expansion',
  'correction',
  'degradation',
  'reranking',
  'decision-kind',
  'qpp',
  'stage-list',
] as const;
export type TraceFacet = (typeof TRACE_FACETS)[number];

export const TRACE_FACET_ALTITUDE: Record<TraceFacet, 'user' | 'diagnostic'> = {
  mode: 'user',
  expansion: 'user',
  correction: 'user',
  degradation: 'user',
  // Register F-052: a cross-encoder DROP (deadline miss / RPC failure / model absent) changes the
  // order the user is looking at, so the drop class is user-tier. The by-design skips stay inside
  // the diagnostic stage list — nothing degraded, so there is nothing to tell the user.
  reranking: 'user',
  'decision-kind': 'diagnostic',
  qpp: 'diagnostic',
  'stage-list': 'diagnostic',
};

/** User-tier wording for the effective retrieval mode (the closed wire vocabulary). */
const MODE_WORDING: Record<string, string> = {
  TEXT: 'Keyword search',
  VECTOR: 'Semantic search',
  HYBRID: 'Hybrid search',
  SPLADE: 'SPLADE search',
};

/** User-tier wording for the expansion stage's skip reasons (emitted live today). */
const EXPANSION_SKIP_WORDING: Record<string, string> = {
  TIMEOUT: 'AI expansion timed out',
  AI_UNAVAILABLE: 'AI expansion unavailable',
  FAILED: 'AI expansion failed',
};

/**
 * Tempdoc 602 R6 — user-tier wording for the search-trace DEGRADATION reasons
 * (`vectorBlockedReason` / `hybridFallbackReason` / `spladeSkipReason`). All three
 * fields carry a `SearchReasonCode` wire string (the worker enum's `.name()`); the
 * 593 walkthrough showed the raw code interpolated to the user ("blocked
 * (LEGACY_INDEX_NO_FINGERPRINT)") — the Nielsen "no error codes" violation. These
 * are lowercase cause phrases so they read after a context prefix
 * ("semantic ranking blocked — the index needs a one-time rebuild"). The two planner-owned
 * `SKIPPED_*` values are complete sentences because the deliberate omission is not a block.
 *
 * Covers every embedding-compat + routing code that can populate the three
 * fields. The chunk-merge codes (APPLIED / SKIPPED_*) feed the chunk-merge stage
 * (a diagnostic-tier stage reason), not these fields, so they are deliberately unworded.
 * The keys of this map are pinned against a declared code list in
 * `searchTraceExplain.test.ts` (both directions: nothing declared goes unworded, no worded
 * key is dead). That list is a hand-kept mirror of the Worker's `SearchReasonCode` — the
 * generated wire schema types these fields as plain `string`, so nothing binds the mirror to
 * the enum. The offline `search-degradation-reason-codes` register + check that used to
 * scrape the Java file were retired in tempdoc 930 (they ran in no workflow).
 */
export const DEGRADATION_REASON_WORDING: Record<string, string> = {
  // embedding / index-model readiness
  INITIALIZING: 'semantic search is still warming up',
  NO_EMBEDDING_MODEL: 'no embedding model is loaded',
  NEW_INDEX_NO_FINGERPRINT: 'the index is still being prepared',
  LEGACY_INDEX_NO_FINGERPRINT: 'the index needs a one-time rebuild',
  FINGERPRINT_MATCH: 'the embedding index is compatible',
  FINGERPRINT_MISMATCH: 'the index needs rebuilding',
  REBUILD_IN_PROGRESS: 'the index is rebuilding',
  REBUILD_COMPLETED: 'the index rebuild just finished',
  REBUILD_FAILED_NO_VECTORS: 'the index rebuild finished without producing any semantic data',
  EMBEDDING_COMPATIBILITY_UNKNOWN: 'embedding compatibility is unknown',
  // live search-routing encode failures
  UNKNOWN: 'the cause is unknown',
  EMBEDDING_COMPATIBILITY_BLOCKED: 'the index needs rebuilding',
  NO_EMBEDDING_SERVICE: 'the embedding service is offline',
  EMBEDDING_GENERATION_FAILED: 'query encoding failed',
  EMBEDDING_EXCEPTION: 'query encoding hit an error',
  SKIPPED_SHORT_QUERY:
    'Semantic ranking was skipped because the query is too short to produce a useful semantic signal.',
  SKIPPED_NO_DISCRIMINATIVE_TERM:
    'Semantic ranking was skipped — every word in this query is common across your documents.',
  // Spec D §6.3 (ADR-0050 decision 1): a query encoder leg did not finish inside the search's
  // semantic sub-budget, so the search answered with the legs it had.
  SEMANTIC_BUDGET_EXCEEDED: 'semantic search did not answer in time',
};

/**
 * Spec D §6.3 — user-tier wording for the cause a semantic budget miss carries
 * (`hybridFallbackCause` / `spladeSkipCause`, lower-case wire strings, present only with
 * `SEMANTIC_BUDGET_EXCEEDED`). Keys are pinned in `searchTraceExplain.test.ts` in both directions.
 */
export const LEG_CAUSE_WORDING: Record<string, string> = {
  contended: 'encoder busy with other work',
  loading: 'search models still loading',
  stuck: 'encoder stalled',
};

/**
 * Register F-052 — user-tier wording for the cross-encoder stage's DROP reasons.
 *
 * A rerank deadline miss (the 200ms budget against a ~165ms p50 on long documents) or an RPC
 * failure returns the fusion/LambdaMART order instead of the relevance model's. Before this map
 * that reached the user as nothing at all: the reason existed only as the `cross-encoder` stage's
 * raw code inside the collapsed diagnostic disclosure.
 *
 * Keyed by `CrossEncoderSkipReason` (app-api) wire strings, drop class only — the by-design skips
 * (DISABLED / NAVIGATIONAL_QUERY / BELOW_MIN_THRESHOLD / DOCS_TOO_LONG / PIPELINE_NOT_ELIGIBLE /
 * MODEL_NOT_CONFIGURED / FUSION_CONFIDENT) are deliberately unworded because they never reach
 * this line — `CrossEncoderSkipReason.isDrop()` is the producer-side split, an exhaustive switch.
 * This map's keys are pinned against the drop list in `searchTraceExplain.test.ts`.
 *
 * Tone: state what did not run and what the ranking is instead. No alarm — the results are real.
 */
export const CROSS_ENCODER_SKIP_WORDING: Record<string, string> = {
  DEADLINE_EXCEEDED: 'Relevance re-ranking ran out of time — results are ranked without it',
  // Spec D: the search's own deadline, not the rerank budget, cut the rerank short (too little
  // time left, or the reranker did not answer in time), so the search answered on time without it.
  SEARCH_DEADLINE_EXCEEDED:
    'Relevance re-ranking did not fit in the time this search had — results are ranked without it',
  RPC_FAILED: 'Relevance re-ranking was unavailable — results are ranked without it',
  MODEL_NOT_LOADED: 'Relevance re-ranking model was not loaded — results are ranked without it',
  MODEL_LOADING: 'Relevance re-ranking model is still loading — results are ranked without it',
  // Register F-054: split out of DEADLINE_EXCEEDED. The model was reached and failed (an ONNX
  // Runtime out-of-memory, a dead session) — saying "ran out of time" pointed at a knob that
  // cannot fix it, and pointed the user at their own patience instead of a real fault.
  INFERENCE_FAILED: 'Relevance re-ranking failed to run — results are ranked without it',
  UNKNOWN: 'Relevance re-ranking was skipped — results are ranked without it',
};

/**
 * Word a degradation reason as "<context> — <cause>", falling back to the raw code. A semantic
 * budget cause, when present, follows as "(<cause wording>)".
 */
function degradationPhrase(context: string, reason: string, cause?: string): string {
  const worded = DEGRADATION_REASON_WORDING[reason];
  const phrase = worded ? `${context} — ${worded}` : `${context} (${reason})`;
  if (!cause) return phrase;
  return `${phrase} (${LEG_CAUSE_WORDING[cause] ?? cause})`;
}

/** The semantic fallback explanation shared by both Ask surfaces. */
export function retrievalFallbackPhrase(reason?: string, cause?: string): string {
  return reason === 'SEMANTIC_BUDGET_EXCEEDED'
    ? degradationPhrase('Keyword retrieval', reason, cause) : '';
}

function stageLabel(stage: TraceStage): string {
  return (stage.id && STAGE_LABELS[stage.id]) || stage.id || 'stage';
}

function stageById(trace: SearchTrace, id: string): TraceStage | undefined {
  return (trace.stages ?? []).find((s) => s.id === id);
}

/** Classifier bypasses stay beside the raw type in the diagnostic disclosure. */
function stageDetail(stage: TraceStage): string {
  let reason = stage.reason;
  if (stage.id === 'query-understanding') {
    if (reason === 'EXPLICIT_PIPELINE') reason = 'classifier policy bypassed: explicit pipeline';
    else if (reason === 'LUCENE_SYNTAX') reason = 'classifier policy bypassed: Lucene syntax';
    return [stage.detail, reason].filter(Boolean).join('; ');
  }
  if (stage.id === 'expansion' && reason === 'PER_SOURCE_RETRIEVAL') {
    reason = 'per-source retrieval uses separate queries';
  }
  return stage.detail || reason || '';
}

/**
 * The user-tier summary parts, in a human grammar — one short phrase per
 * user-altitude facet that has something to say. Exported for the test that
 * pins "diagnostic facets never appear in the user line".
 */
export function userSummaryParts(trace: SearchTrace): string[] {
  const parts: string[] = [];

  // mode (user) — worded, never the raw wire enum.
  const mode = trace.effectiveMode ?? '';
  if (mode) parts.push(MODE_WORDING[mode] ?? `${mode.toLowerCase()} search`);
  else if (trace.decisionKind === 'empty_query') parts.push('Empty query');

  // expansion (user) — the AI either widened the query or honestly didn't.
  const expansion = stageById(trace, 'expansion');
  if (expansion?.status === 'executed') parts.push('AI-expanded query');
  else if (expansion?.status === 'skipped' && expansion.reason) {
    const worded = EXPANSION_SKIP_WORDING[expansion.reason];
    if (worded) parts.push(worded);
  }

  // correction (user) — "searched for X instead" when the corrector rewrote.
  const correction = stageById(trace, 'correction');
  if (correction?.status === 'executed' && correction.detail) {
    parts.push(`searched for “${correction.detail}” instead`);
  }

  // degradation (user) — worded (602 R6), never the raw reason code; the
  // window-level banner carries the full cause+remedy.
  const d = trace.degradation;
  if (d?.vectorBlocked && d.vectorBlockedReason) {
    if (d.vectorBlockedReason.startsWith('SKIPPED_')) {
      parts.push(
        DEGRADATION_REASON_WORDING[d.vectorBlockedReason]
          ?? `Semantic ranking was skipped (${d.vectorBlockedReason})`,
      );
    } else {
      parts.push(degradationPhrase('semantic ranking blocked', d.vectorBlockedReason));
    }
  }
  if (d?.hybridFallback && d.hybridFallbackReason) {
    parts.push(
      degradationPhrase('fell back from hybrid', d.hybridFallbackReason, d.hybridFallbackCause),
    );
  }
  if (d && !d.spladeExecuted && d.spladeSkipReason) {
    parts.push(degradationPhrase('SPLADE skipped', d.spladeSkipReason, d.spladeSkipCause));
  }

  // reranking (user, F-052) — a cross-encoder DROP is a degradation of the order the user sees;
  // a by-design skip has no wording entry and stays inside the diagnostic stage list.
  const crossEncoder = stageById(trace, 'cross-encoder');
  if (crossEncoder?.status === 'skipped' && crossEncoder.reason) {
    const worded = CROSS_ENCODER_SKIP_WORDING[crossEncoder.reason];
    if (worded) parts.push(worded);
  }

  return parts;
}

export const searchTraceExplainStrategy: AggregateStrategy<'SearchTrace', 'search-explain'> = (
  trace,
  _ctx,
  _host,
) => {
  const userParts = userSummaryParts(trace);

  // Diagnostic tier — raw facets, collapsed by default.
  const qpp = trace.qpp;
  const qppLine =
    qpp && (qpp.maxIdf || qpp.avgIctf || qpp.queryScope)
      ? `QPP: maxIdf=${(qpp.maxIdf ?? 0).toFixed(2)} · avgIctf=${(qpp.avgIctf ?? 0).toFixed(2)} · queryScope=${(qpp.queryScope ?? 0).toFixed(2)}`
      : '';
  const stages = trace.stages ?? [];

  return html`
    <div
      class="search-explain"
      data-decision-kind=${trace.decisionKind ?? 'unknown'}
      data-effective-mode=${trace.effectiveMode ?? ''}
    >
      ${userParts.length > 0
        ? html`<div class="search-explain-user" data-altitude="user" data-testid="search-explain-user">
            ${userParts.join(' · ')}
          </div>`
        : nothing}
      <details class="search-explain-diagnostics" data-altitude="diagnostic">
        <summary class="search-explain-diagnostics-summary">Pipeline details</summary>
        <div class="search-explain-diagnostics-body">
          <div class="search-explain-decision">
            decision: ${trace.decisionKind ?? 'unknown'}${trace.effectiveMode
              ? ` · mode: ${trace.effectiveMode}`
              : ''}
          </div>
          ${qppLine ? html`<div class="search-explain-qpp">${qppLine}</div>` : nothing}
          ${stages.length > 0
            ? html`<div class="search-explain-stages" data-testid="search-explain-stages">
                ${stages.map(
                  (s) => html`<div
                    class="search-explain-stage"
                    data-stage=${s.id ?? 'unknown'}
                    data-status=${s.status ?? 'unknown'}
                  >
                    <span class="search-explain-stage-label">${stageLabel(s)}</span>
                    <span class="search-explain-stage-status"
                      >${s.status ?? 'unknown'}${stageDetail(s)
                        ? ` (${stageDetail(s)})`
                        : ''}${typeof s.cardinality === 'number'
                        ? ` · ${s.cardinality.toLocaleString()} docs`
                        : ''}${typeof s.ms === 'number' ? ` · ${s.ms}ms` : ''}</span
                    >
                  </div>`,
                )}
              </div>`
            : nothing}
        </div>
      </details>
    </div>
  `;
};

export function registerSearchTraceExplainStrategy(): () => void {
  return registerAggregateStrategy({
    aggregate: 'SearchTrace',
    context: 'search-explain',
    rank: 0,
    strategy: searchTraceExplainStrategy,
    source: 'core',
  });
}

void searchTraceExplainStrategy;
