// SPDX-License-Identifier: Apache-2.0
/**
 * whyThisResult — Tempdoc 577 Goal 3 §3.9a (the §1.9 shared-primitive bridge).
 *
 * The ONE per-hit "Why this result?" disclosure, shared by the standalone
 * SearchSurface and the unified window's retrieve tier (UnifiedChatView). Both
 * render the same ranking-provenance chips from the per-hit `trace` slice
 * (`Hit.trace` — the per-doc slice of the unified stage vocabulary, the sole
 * source since the leg-keyed provenance was retired, Phase E2) and the same
 * "Explain in words" action (routes through the one `compose()` seam to
 * `core.summarize` with a `search-trace` selection — verified to narrate the
 * injected trace rather than retrieving corpus docs).
 *
 * Extracted from SearchSurface's private `renderWhy`/`traceChipsFor`/
 * `tracePartsFor`/`handleExplainWhy` so the in-window tier is a true peer, not a
 * fork. Input is structural (`{ title, trace? }`) so both `SearchHit`
 * (searchState) and `SearchHitSnapshot` (plugin wire) satisfy it without coupling.
 */

import { html, css, nothing, type TemplateResult, type CSSResult } from 'lit';
import type { HitStage } from '../../../api/generated/index.js';
import { STAGE_LABELS } from '../../aggregate-substrate/strategies/searchTraceExplain.js';
import { compose } from '../../utils/compose.js';

/** The minimal hit shape the disclosure reads — satisfied by SearchHit + SearchHitSnapshot. */
export interface WhyHit {
  title: string;
  trace?: HitStage[];
}

/**
 * Tempdoc 577 Phase 3 (Ext I) — the per-hit rank rationale in a user-tier grammar:
 * each stage signal is a labeled chip (`Sparse (BM25) · #2 · 3.32`), labels from the
 * one STAGE_LABELS vocabulary, signed deltas worded ("ranked down") instead of bare
 * negatives. Path-sparse — only the stages that touched this hit appear.
 */
export function traceChipsFor(hit: WhyHit): string[] {
  const trace = hit.trace;
  if (!trace || trace.length === 0) return [];
  return trace.map((s) => {
    const label = (s.id && STAGE_LABELS[s.id]) || s.id || 'stage';
    const bits: string[] = [label];
    if (s.rank != null) bits.push(`#${s.rank}`);
    if (s.score != null) {
      bits.push(s.score < 0 ? `ranked down (${s.score.toFixed(2)})` : s.score.toFixed(2));
    }
    return bits.join(' · ');
  });
}

/** The compact raw form the LLM-narration summary (core.summarize) keeps. */
export function tracePartsFor(hit: WhyHit): string[] {
  const trace = hit.trace;
  if (!trace || trace.length === 0) return [];
  return trace.map((s) => {
    const rank = s.rank != null ? ` #${s.rank}` : '';
    const score = s.score != null ? ` ${s.score.toFixed(2)}` : '';
    return `${s.id ?? 'stage'}${rank}${score}`.trim();
  });
}

/**
 * Tempdoc 549 Slice 4 — route the per-hit trace to `core.summarize` (NOT
 * `core.rag-ask`: rag-ask retrieves corpus docs and ignores the injected trace;
 * SummarizeShape runs the SelectionContextInjector without RAG retrieval, so the
 * model narrates the injected trace itself). Shared verbatim by both consumers.
 */
function explainWhy(hit: WhyHit): void {
  const parts = tracePartsFor(hit);
  if (parts.length === 0) return;
  compose({
    operation: 'core.summarize',
    source: 'SEARCH_WHY',
    userPrompt: `Explain why this result ("${hit.title}") ranked where it did.`,
    selection: { kind: 'search-trace', scope: 'hit', summary: parts.join(' · ') },
  });
}

/**
 * Native `<details>` so no component state is needed; click is stopped from
 * propagating so toggling does not select/open the row. Returns `nothing` when
 * the hit carries no trace (path-sparse).
 */
export function renderWhyDisclosure(hit: WhyHit): TemplateResult | typeof nothing {
  const chips = traceChipsFor(hit);
  if (chips.length === 0) return nothing;
  return html`<details
    class="hit-why"
    data-testid="hit-why"
    @click=${(e: Event) => e.stopPropagation()}
  >
    <summary>Why this result?</summary>
    <div class="hit-why-body">
      ${chips.map((c) => html`<span class="hit-why-signal">${c}</span>`)}
      <button
        class="hit-why-explain"
        data-testid="hit-why-explain"
        title="Ask the assistant to explain this ranking"
        @click=${(e: Event) => {
          e.stopPropagation();
          explainWhy(hit);
        }}
      >Explain in words</button>
    </div>
  </details>`;
}

/** The one set of styles for the disclosure — both consumers add this to `static styles`. */
export const whyThisResultStyles: CSSResult = css`
  /* Per-hit "Why this result?" — labeled, separated chips (the run-on fix). */
  .hit-why {
    margin-top: 0.25rem;
    font-size: var(--font-size-xs);
    color: var(--text-secondary);
  }
  .hit-why summary {
    cursor: pointer;
    color: var(--text-tertiary);
  }
  .hit-why summary:hover,
  .hit-why summary:focus-visible {
    color: var(--text-secondary);
  }
  .hit-why-body {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.4rem;
    margin: 0.3rem 0 0.1rem 0;
  }
  .hit-why-signal {
    padding: 0.1rem 0.5rem;
    border: 1px solid var(--border-subtle);
    border-radius: 1rem;
    background: var(--surface-2);
    white-space: nowrap;
  }
  .hit-why-explain {
    padding: 0.1rem 0.5rem;
    font-size: var(--font-size-xs);
    font-family: inherit;
    border: 1px solid var(--border-subtle);
    border-radius: 4px;
    background: none;
    color: var(--text-secondary);
    cursor: pointer;
  }
  .hit-why-explain:hover,
  .hit-why-explain:focus-visible {
    color: var(--text-primary);
    border-color: var(--accent-command);
    outline: 2px solid transparent;
  }
`;
