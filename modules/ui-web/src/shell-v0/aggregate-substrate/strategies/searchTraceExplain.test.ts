// @vitest-environment happy-dom

/**
 * Tempdoc 549 (Phase D1) — (SearchTrace, search-explain) strategy.
 * Tempdoc 577 Goal 1 Phase 3 (Ext I + Move D) — the altitude cut.
 *
 * These tests pin:
 *  - the per-facet altitude authority is exhaustive over the closed facet list;
 *  - user-tier facets word themselves (mode legibility, expansion honesty,
 *    correction echo, degradation-in-words) in the summary line;
 *  - diagnostic-tier facets (QPP, decision kind, the stage list) render ONLY
 *    inside the collapsed disclosure — never in the user line;
 *  - the stage list renders one labeled row per stage (status / reason / ms /
 *    detail), fixing the run-on form;
 *  - STAGE_LABELS covers the closed StageId vocabulary verbatim (the FE half of
 *    the Phase F stage-completeness invariant). The Pass-8 FieldRoles
 *    exhaustiveness is enforced at import time by assertFieldRoles.
 */

import { describe, it, expect } from 'vitest';
import { render } from 'lit';
import {
  searchTraceExplainStrategy,
  userSummaryParts,
  STAGE_LABELS,
  TRACE_FACETS,
  TRACE_FACET_ALTITUDE,
  DEGRADATION_REASON_WORDING,
  CROSS_ENCODER_SKIP_WORDING,
  LEG_CAUSE_WORDING,
} from './searchTraceExplain.js';
import type { SearchTrace } from '../../../api/generated/index.js';
// Tempdoc 564 Phase 3: SearchTrace is the generated Zod projection; build instances via parse().
import { searchTraceSchema } from '../../../api/generated/schema-types/search-trace.js';

function renderToHost(trace: SearchTrace): HTMLElement {
  const host = document.createElement('div');
  const result = searchTraceExplainStrategy(trace, {} as never, { apiBase: '' });
  render(result as never, host);
  return host;
}

const STAGE_WIRE_IDS = [
  'query-understanding',
  'expansion',
  'correction',
  'sparse-retrieval',
  'dense-retrieval',
  'splade-retrieval',
  'fusion',
  'chunk-merge',
  'branch-fusion',
  'lambdamart',
  'cross-encoder',
  'freshness',
];

describe('searchTraceExplain strategy (549 D1 + 577 Phase 3 altitude cut)', () => {
  it('TRACE_FACET_ALTITUDE is exhaustive over the closed facet list', () => {
    expect(Object.keys(TRACE_FACET_ALTITUDE).sort()).toEqual([...TRACE_FACETS].sort());
    for (const facet of TRACE_FACETS) {
      expect(['user', 'diagnostic']).toContain(TRACE_FACET_ALTITUDE[facet]);
    }
  });

  it('words the user line: mode legibility + expansion honesty + degradation-in-words', () => {
    const trace: SearchTrace = searchTraceSchema.parse({
      version: 1,
      decisionKind: 'sparse_shortcut',
      effectiveMode: 'TEXT',
      degradation: { vectorBlocked: true, vectorBlockedReason: 'ENCODER_OOM' },
      stages: [{ id: 'expansion', status: 'skipped', reason: 'TIMEOUT' }],
    });
    const parts = userSummaryParts(trace);
    expect(parts).toContain('Keyword search');
    expect(parts).toContain('AI expansion timed out — keyword results only');
    expect(parts).toContain('semantic ranking blocked (ENCODER_OOM)');
    const user = renderToHost(trace).querySelector('[data-testid="search-explain-user"]');
    expect(user?.textContent).toContain('Keyword search');
  });

  it('echoes a correction and an executed expansion in the user grammar', () => {
    const trace: SearchTrace = searchTraceSchema.parse({
      version: 1,
      decisionKind: 'multi_leg',
      effectiveMode: 'HYBRID',
      stages: [
        { id: 'expansion', status: 'executed' },
        { id: 'correction', status: 'executed', detail: 'pipeline' },
      ],
    });
    const parts = userSummaryParts(trace);
    expect(parts).toContain('Hybrid search');
    expect(parts).toContain('AI-expanded query');
    expect(parts.some((p) => p.includes('pipeline') && p.includes('instead'))).toBe(true);
  });

  it('diagnostic facets render only inside the collapsed disclosure, never the user line', () => {
    const trace: SearchTrace = searchTraceSchema.parse({
      version: 1,
      decisionKind: 'sparse_shortcut',
      effectiveMode: 'TEXT',
      qpp: { maxIdf: 5.91, avgIctf: 8.07, queryScope: 0.13 },
      stages: [{ id: 'fusion', status: 'executed', ms: 42 }],
    });
    const host = renderToHost(trace);
    const user = host.querySelector('[data-altitude="user"]');
    const diag = host.querySelector('details[data-altitude="diagnostic"]');
    expect(diag).not.toBeNull();
    // QPP, decision kind, and stages live in the diagnostic tier...
    expect(diag?.textContent).toContain('QPP: maxIdf=5.91');
    expect(diag?.textContent).toContain('decision: sparse_shortcut');
    expect(diag?.textContent).toContain('Fusion');
    // ...and never leak into the user line.
    expect(user?.textContent ?? '').not.toContain('QPP');
    expect(user?.textContent ?? '').not.toContain('sparse_shortcut');
    // The disclosure is collapsed by default (no `open` attribute).
    expect((diag as HTMLDetailsElement).open).toBe(false);
  });

  it('renders one labeled stage row per stage with status, reason, ms and detail', () => {
    const trace: SearchTrace = searchTraceSchema.parse({
      version: 1,
      decisionKind: 'multi_leg',
      effectiveMode: 'HYBRID',
      stages: [
        { id: 'fusion', status: 'executed', detail: 'cc', ms: 42 },
        { id: 'dense-retrieval', status: 'skipped', reason: 'dense-only' },
        { id: 'cross-encoder', status: 'executed', ms: 8 },
      ],
    });
    const host = renderToHost(trace);
    const rows = host.querySelectorAll('.search-explain-stage');
    expect(rows.length).toBe(3);
    expect(rows[0]?.textContent?.replace(/\s+/g, ' ')).toContain('Fusion');
    expect(rows[0]?.textContent).toContain('executed (cc) · 42ms');
    expect(rows[1]?.textContent).toContain('skipped (dense-only)');
    expect(rows[2]?.textContent).toContain('executed · 8ms');
  });

  it('STAGE_LABELS covers the closed StageId vocabulary exactly', () => {
    // The FE half of the stage-completeness invariant (principle 9): every wireId in the
    // Java SearchTrace.StageId enum must have a label. A drift here is what Phase F's gate
    // mechanizes; this test is the local backstop.
    expect(Object.keys(STAGE_LABELS).sort()).toEqual([...STAGE_WIRE_IDS].sort());
  });

  // Tempdoc 602 R6 — the user-tier degradation reason is worded, never the raw code.
  it('602 R6 — words a known degradation reason code (no raw code in the user line)', () => {
    const trace: SearchTrace = searchTraceSchema.parse({
      version: 1,
      decisionKind: 'multi_leg',
      effectiveMode: 'TEXT',
      degradation: {
        vectorBlocked: true,
        vectorBlockedReason: 'LEGACY_INDEX_NO_FINGERPRINT',
      },
    });
    const parts = userSummaryParts(trace);
    expect(parts).toContain('semantic ranking blocked — the index needs a one-time rebuild');
    // The raw enum code never appears in the user line.
    expect(parts.join(' · ')).not.toContain('LEGACY_INDEX_NO_FINGERPRINT');
  });

  // The FE half of the degradation-wording correspondence (602 R6): every SearchReasonCode
  // member that can populate a user-tier degradation field is worded. The chunk-merge codes
  // never reach these fields and stay unworded. This list is a hand-kept mirror of the Worker
  // enum — the generated wire schema types the reason fields as plain `string`, so no typed
  // source binds it. The offline check that scraped the Java file was retired in tempdoc 930;
  // this assertion is the surviving authority for both directions.
  const DEGRADATION_WORDED_CODES = [
    'INITIALIZING',
    'NO_EMBEDDING_MODEL',
    'NEW_INDEX_NO_FINGERPRINT',
    'LEGACY_INDEX_NO_FINGERPRINT',
    'FINGERPRINT_MATCH',
    'FINGERPRINT_MISMATCH',
    'REBUILD_IN_PROGRESS',
    'REBUILD_COMPLETED',
    'REBUILD_FAILED_NO_VECTORS',
    'EMBEDDING_COMPATIBILITY_UNKNOWN',
    'UNKNOWN',
    'EMBEDDING_COMPATIBILITY_BLOCKED',
    'NO_EMBEDDING_SERVICE',
    'EMBEDDING_GENERATION_FAILED',
    'EMBEDDING_EXCEPTION',
    'SKIPPED_SHORT_QUERY',
    'SKIPPED_NO_DISCRIMINATIVE_TERM',
    'SEMANTIC_BUDGET_EXCEEDED',
  ];

  it('602 R6 — DEGRADATION_REASON_WORDING covers the user-tier reason vocabulary exactly', () => {
    expect(Object.keys(DEGRADATION_REASON_WORDING).sort()).toEqual(
      [...DEGRADATION_WORDED_CODES].sort(),
    );
  });

  // Spec D §6.3 (step 2b) — the per-leg cause of a semantic budget miss (`SearchReasonCode.LegCause`
  // wire values, the same hand-kept-mirror caveat as above) is worded in both directions.
  const LEG_CAUSES = ['contended', 'loading', 'stuck'];

  it('Spec D U1 — LEG_CAUSE_WORDING covers the leg-cause vocabulary exactly', () => {
    expect(Object.keys(LEG_CAUSE_WORDING).sort()).toEqual([...LEG_CAUSES].sort());
  });

  it('Spec D U1 — a semantic budget miss is worded with its cause on each leg', () => {
    const trace: SearchTrace = searchTraceSchema.parse({
      version: 1,
      decisionKind: 'multi_leg',
      effectiveMode: 'TEXT',
      degradation: {
        hybridFallback: true,
        hybridFallbackReason: 'SEMANTIC_BUDGET_EXCEEDED',
        hybridFallbackCause: 'contended',
        spladeExecuted: false,
        spladeSkipReason: 'SEMANTIC_BUDGET_EXCEEDED',
        spladeSkipCause: 'loading',
      },
    });
    const parts = userSummaryParts(trace);
    expect(parts).toContain(
      'fell back from hybrid — semantic search did not answer in time (encoder busy with other work)',
    );
    expect(parts).toContain(
      'SPLADE skipped — semantic search did not answer in time (search models still loading)',
    );
    const line = parts.join(' · ');
    expect(line).not.toContain('SEMANTIC_BUDGET_EXCEEDED');
    expect(line).not.toContain('contended');
  });

  it('Spec D U1 — a fallback without a cause keeps its reason wording unchanged', () => {
    const trace: SearchTrace = searchTraceSchema.parse({
      version: 1,
      effectiveMode: 'TEXT',
      degradation: { hybridFallback: true, hybridFallbackReason: 'NO_EMBEDDING_SERVICE' },
    });
    const line = userSummaryParts(trace).join(' · ');
    expect(line).toContain('fell back from hybrid — ');
    for (const worded of Object.values(LEG_CAUSE_WORDING)) {
      expect(line).not.toContain(worded);
    }
  });

  it('planned dense omission is worded as a skip, not an embedding block', () => {
    const trace: SearchTrace = searchTraceSchema.parse({
      version: 1,
      effectiveMode: 'TEXT',
      degradation: {
        vectorBlocked: true,
        vectorBlockedReason: 'SKIPPED_NO_DISCRIMINATIVE_TERM',
      },
    });
    expect(userSummaryParts(trace)).toContain(
      'Semantic ranking was skipped — every word in this query is common across your documents.',
    );
  });

  // Register F-052 (production side) — a cross-encoder deadline miss or RPC drop used to reach
  // the wire ONLY as the cross-encoder stage's raw reason, and the stage list is a diagnostic-tier
  // facet inside the collapsed disclosure. Results came back fusion-ordered with no user-tier
  // signal at all. The drop class is now worded at the user tier, like the expansion timeout.
  it('F-052 — a cross-encoder deadline miss is worded in the user line', () => {
    const trace: SearchTrace = searchTraceSchema.parse({
      version: 1,
      decisionKind: 'multi_leg',
      effectiveMode: 'HYBRID',
      stages: [{ id: 'cross-encoder', status: 'skipped', reason: 'DEADLINE_EXCEEDED' }],
    });
    const parts = userSummaryParts(trace);
    expect(parts).toContain('Relevance re-ranking ran out of time — results are ranked without it');
    // The raw wire code never reaches the user line.
    expect(parts.join(' · ')).not.toContain('DEADLINE_EXCEEDED');
    const user = renderToHost(trace).querySelector('[data-testid="search-explain-user"]');
    expect(user?.textContent).toContain('Relevance re-ranking ran out of time');
  });

  it('F-052 — an RPC drop is worded, and a by-design skip stays diagnostic-only', () => {
    const dropped: SearchTrace = searchTraceSchema.parse({
      version: 1,
      effectiveMode: 'HYBRID',
      stages: [{ id: 'cross-encoder', status: 'skipped', reason: 'RPC_FAILED' }],
    });
    expect(userSummaryParts(dropped)).toContain(
      'Relevance re-ranking was unavailable — results are ranked without it',
    );

    // A deterministic, by-design skip is NOT a degradation — it must not nag the user line.
    // (These are the `CrossEncoderSkipReason.isDrop() == false` members.)
    for (const reason of [
      'NAVIGATIONAL_QUERY',
      'DISABLED',
      'BELOW_MIN_THRESHOLD',
      'DOCS_TOO_LONG',
      'PIPELINE_NOT_ELIGIBLE',
      'MODEL_NOT_CONFIGURED',
      'FUSION_CONFIDENT',
    ]) {
      const byDesign: SearchTrace = searchTraceSchema.parse({
        version: 1,
        effectiveMode: 'HYBRID',
        stages: [{ id: 'cross-encoder', status: 'skipped', reason }],
      });
      const line = userSummaryParts(byDesign).join(' · ');
      expect(line).not.toContain('Relevance re-ranking');
      expect(line).not.toContain(reason);
    }
  });

  // Register F-054 — an inference failure (measured: ONNX Runtime arena exhaustion on 199/200 of a
  // campaign's "deadline misses") used to reach the user as "ran out of time", which is both false
  // and points at a knob that cannot fix it. It is now its own worded drop.
  it('F-054 — an inference failure is worded as a failure, never as running out of time', () => {
    const trace: SearchTrace = searchTraceSchema.parse({
      version: 1,
      effectiveMode: 'HYBRID',
      stages: [{ id: 'cross-encoder', status: 'skipped', reason: 'INFERENCE_FAILED' }],
    });
    const parts = userSummaryParts(trace);
    expect(parts).toContain('Relevance re-ranking failed to run — results are ranked without it');
    const line = parts.join(' · ');
    expect(line).not.toContain('ran out of time');
    expect(line).not.toContain('INFERENCE_FAILED');
  });

  // Spec D (S1): a search whose rerank could not finish inside the search's own deadline answers
  // on time with its fused order. The user line says the re-ranking did not fit the search's time,
  // without the raw code.
  it('Spec D — a rerank cut short by the search deadline is worded, without the raw code', () => {
    const trace: SearchTrace = searchTraceSchema.parse({
      version: 1,
      effectiveMode: 'HYBRID',
      stages: [{ id: 'cross-encoder', status: 'skipped', reason: 'SEARCH_DEADLINE_EXCEEDED' }],
    });
    const parts = userSummaryParts(trace);
    expect(parts).toContain(
      'Relevance re-ranking did not fit in the time this search had — results are ranked without it',
    );
    expect(parts.join(' · ')).not.toContain('SEARCH_DEADLINE_EXCEEDED');
  });

  // The FE half of the cross-encoder vocabulary correspondence. The Java producer is
  // `CrossEncoderSkipReason`, whose exhaustive `isDrop()` switch is the exempt/worded split;
  // this list mirrors its drop members. The assertion below holds both directions (nothing
  // mirrored goes unworded, no worded key is dead).
  const CROSS_ENCODER_WORDED_CODES = [
    'DEADLINE_EXCEEDED',
    'SEARCH_DEADLINE_EXCEEDED',
    'RPC_FAILED',
    'MODEL_NOT_LOADED',
    'INFERENCE_FAILED',
    'UNKNOWN',
  ];

  it('F-052 — CROSS_ENCODER_SKIP_WORDING covers the drop-class vocabulary exactly', () => {
    expect(Object.keys(CROSS_ENCODER_SKIP_WORDING).sort()).toEqual(
      [...CROSS_ENCODER_WORDED_CODES].sort(),
    );
  });
});
