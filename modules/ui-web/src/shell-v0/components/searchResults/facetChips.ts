// SPDX-License-Identifier: Apache-2.0
/**
 * facetChips — Tempdoc 577 Goal 3 §3.9a (the §1.9 shared-primitive bridge).
 *
 * The ONE facet-chip render (Tempdoc 577 Phase 6 Move E — "the set describes
 * itself; nothing here is hand-authored"), shared by the standalone SearchSurface
 * and the unified window's retrieve tier. Chips project from the response's
 * emitted counts (`searchState.facets`) plus the current selections
 * (`searchFiltersState`); selected values absent from the current counts still
 * render so they stay dismissable. A chip click is delegated to the consumer's
 * `onToggle` (each owns its re-run seam — SearchSurface via the plugin host,
 * the retrieve tier via `submitSearch`); the shared concern is the RENDER.
 *
 * Extracted from SearchSurface's private `renderFacetRow` + `FACET_LABELS` so the
 * in-window tier filters identically, not as a fork.
 */

import { html, css, nothing, type TemplateResult, type CSSResult } from 'lit';
import type { SearchFacetCounts } from '../../state/searchState.js';

/** Human labels for the facet fields (the order here is the render order). */
export const FACET_LABELS: Record<string, string> = {
  file_kind: 'Type',
  mime_base: 'Format',
  language: 'Language',
  meta_author: 'Author',
};

/**
 * Render the facet chip row. Returns `nothing` when there is nothing to show
 * (no emitted counts and no active selections). `onToggle(field, value)` is the
 * full per-consumer handler (toggle the selection + re-run the search).
 */
export function renderFacetChips(
  facets: SearchFacetCounts | null | undefined,
  selections: Record<string, string[]>,
  opts: { onToggle: (field: string, value: string) => void },
): TemplateResult | typeof nothing {
  const hasAny =
    (facets && Object.keys(facets).length > 0) || Object.keys(selections).length > 0;
  if (!hasAny) return nothing;
  const groups: TemplateResult[] = [];
  for (const field of Object.keys(FACET_LABELS)) {
    const counts = facets?.[field] ?? {};
    const selected = selections[field] ?? [];
    // Top values by count, plus any selected value not in the current counts.
    const values = Object.entries(counts)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 6)
      .map(([v]) => v);
    for (const v of selected) if (!values.includes(v)) values.push(v);
    if (values.length === 0) continue;
    groups.push(html`<span class="facet-group" data-facet-field=${field}>
      <span class="facet-group-label">${FACET_LABELS[field]}</span>
      ${values.map((v) => {
        const isSel = selected.includes(v);
        const count = counts[v];
        return html`<button
          class="facet-chip ${isSel ? 'selected' : ''}"
          aria-pressed=${isSel ? 'true' : 'false'}
          aria-label=${`Filter by ${FACET_LABELS[field]}: ${v}${count != null ? ` (${count} matches)` : ''}`}
          title=${count != null ? `${count} matches across your library` : nothing}
          @click=${() => opts.onToggle(field, v)}
        >${v}${count != null ? html`<span class="facet-count">${count}</span>` : nothing}</button>`;
      })}
    </span>`);
  }
  if (groups.length === 0) return nothing;
  return html`<div class="facet-row" data-testid="facet-row" aria-label="Filter by facets">
    ${groups}
  </div>`;
}

/** The one set of styles for the facet chips — both consumers add this to `static styles`. */
export const facetChipStyles: CSSResult = css`
  /* Tempdoc 577 Phase 6 (Move E) — facet chips: the set describes itself. */
  .facet-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.35rem 0.75rem;
    margin-top: 0.35rem;
    font-size: var(--font-size-xs);
  }
  .facet-group {
    display: inline-flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.3rem;
  }
  .facet-group-label {
    color: var(--text-tertiary);
  }
  .facet-chip {
    display: inline-flex;
    align-items: center;
    gap: 0.4ch;
    padding: 0.1rem 0.5rem;
    font-size: var(--font-size-xs);
    font-family: inherit;
    border: 1px solid var(--border-subtle);
    border-radius: 1rem;
    background: var(--surface-2);
    color: var(--text-secondary);
    cursor: pointer;
  }
  .facet-chip:hover,
  .facet-chip:focus-visible {
    background: var(--surface-hover);
    color: var(--text-primary);
    border-color: var(--accent-command);
    outline: 2px solid transparent;
  }
  .facet-chip.selected {
    background: var(--accent-tint);
    color: var(--accent-on-tint);
    border-color: var(--accent-command);
  }
  /* Forced colors (Windows High Contrast) erase the selected chip's fill, so it takes the selection
     pair ('forced-color-adjust: none' keeps the browser's Canvas backplate from landing behind its
     text). That also stops the transparent focus outline above from being repainted, so a selected
     chip states its ring. */
  @media (forced-colors: active) {
    .facet-chip.selected {
      forced-color-adjust: none;
      background: Highlight;
      border-color: Highlight;
    }
    .facet-chip.selected,
    .facet-chip.selected * {
      color: HighlightText;
    }
    .facet-chip.selected:focus-visible {
      outline-color: Highlight;
    }
  }
  .facet-count {
    font-variant-numeric: tabular-nums;
    opacity: 0.75;
  }
`;
