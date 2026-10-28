// SPDX-License-Identifier: Apache-2.0
/**
 * The result list of Search mode (WP1.1) — what a plain search shows in the Search v3 window.
 *
 * The rows are the SHARED results card (`jf-results-card`), the one the default window renders, so
 * title, path, snippet, facet chips, the quick/refining/refined meta line and multi-select are the
 * same component in both windows. What this region adds around it is what the card does not own:
 *
 *  - every restriction on the list, visible and removable (S1.10) — scope chips the default window
 *    pinned, a date range no control in either window sets, and facet values that left zero hits;
 *  - the chosen files and the one act on them, "Ask about these files" (S1.6), carrying the ask
 *    tier's existing unavailable reason when chat cannot run (S1.4);
 *  - four outcomes that read differently (S1.9): searching, results, "no documents matched", and
 *    "search failed".
 *
 * It decides nothing. Every press is an event the window answers.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { sv3Shared } from './sv3-shared-styles.js';
import { MAIN_EMPTY, MAIN_UNREACHABLE } from './fixtures.js';
import {
  SV3_RESULTS_IDLE,
  sv3AskAboutLabel,
  type Sv3ActiveFilter,
  type Sv3ResultsView,
} from './sv3-search.js';
import '../../components/searchResults/ResultsCard.js';
import '../../components/Control.js';
import './Sv3Empty.js';

/** A restriction's remove control was pressed. */
const SV3_RESULTS_FILTER_REMOVE = 'sv3-results-filter-remove';
export type Sv3ResultsFilterRemove = Sv3ActiveFilter;

/** "Ask about these files" — prepare an Ask limited to the chosen rows' documents. */
const SV3_RESULTS_ASK = 'sv3-results-ask';

/** Forget the chosen rows. */
const SV3_RESULTS_CLEAR_SELECTION = 'sv3-results-clear-selection';

const SKELETON_ROWS = 3;

export class Sv3Results extends JfElement {
  static properties = {
    view: { attribute: false },
    forgetAvailable: { type: Boolean, attribute: false },
  };

  declare view: Sv3ResultsView;
  declare forgetAvailable: boolean;

  constructor() {
    super();
    this.view = SV3_RESULTS_IDLE;
    this.forgetAvailable = false;
  }

  private emit<T>(name: string, detail?: T): void {
    this.dispatchEvent(new CustomEvent(name, { detail, bubbles: true, composed: true }));
  }

  override render(): TemplateResult {
    const view = this.view;
    return html`
      <section class="results" aria-label="Search results" data-testid="sv3-results">
        <h2 class="heading" data-testid="sv3-results-heading">Results for “${view.query}”</h2>
        ${this.filters(view.filters)} ${this.selection(view)} ${this.body(view)}
      </section>
    `;
  }

  private filters(filters: readonly Sv3ActiveFilter[]): TemplateResult | typeof nothing {
    if (filters.length === 0) return nothing;
    return html`<div
      class="filters"
      role="group"
      aria-label="Filters on these results"
      data-testid="sv3-results-filters"
    >
      ${filters.map(
        (filter) => html`<button
          type="button"
          class="filter"
          data-filter=${filter.key}
          aria-label=${`Remove filter: ${filter.label}`}
          title=${`Remove filter: ${filter.label}`}
          @click=${() => this.emit<Sv3ResultsFilterRemove>(SV3_RESULTS_FILTER_REMOVE, filter)}
        >
          <span>${filter.label}</span><span class="remove" aria-hidden="true">×</span>
        </button>`,
      )}
    </div>`;
  }

  /** The chosen files and the one act on them. Nothing at all while nothing is chosen. */
  private selection(view: Sv3ResultsView): TemplateResult | typeof nothing {
    if (view.chosenCount === 0) return nothing;
    const count = view.chosenCount;
    return html`<div class="selection" data-testid="sv3-results-selection-bar">
      <span class="chosen" role="status" data-testid="sv3-results-selection"
        >${count} ${count === 1 ? 'file' : 'files'} chosen</span
      >
      <jf-control
        class="ask"
        data-testid="sv3-results-ask"
        label=${sv3AskAboutLabel(count)}
        .availability=${view.askAvailability}
        .onActivate=${() => this.emit(SV3_RESULTS_ASK)}
        >${sv3AskAboutLabel(count)}</jf-control
      >
      <jf-control
        class="clear"
        data-testid="sv3-results-clear"
        label="Clear chosen files"
        .onActivate=${() => this.emit(SV3_RESULTS_CLEAR_SELECTION)}
        >Clear</jf-control
      >
    </div>`;
  }

  private body(view: Sv3ResultsView): TemplateResult {
    if (view.status === 'loading') {
      return html`<div
        class="pending"
        role="status"
        aria-label="Searching"
        aria-busy="true"
        data-testid="sv3-results-loading"
      >
        ${Array.from(
          { length: SKELETON_ROWS },
          () => html`<div class="skeleton-row" aria-hidden="true">
            <span class="skeleton-sheen sv3-anim-skeleton"></span>
          </div>`,
        )}
      </div>`;
    }
    if (view.status === 'unreachable') {
      return html`<jf-sv3-empty
        roomy
        role="alert"
        data-testid="sv3-results-failed"
        glyph="&#9634;"
        heading=${MAIN_UNREACHABLE.title}
        description=${MAIN_UNREACHABLE.description}
      >
        ${view.failure === ''
          ? nothing
          : html`<span class="failure-detail" data-testid="sv3-results-failure-detail"
              >${view.failure}</span
            >`}
      </jf-sv3-empty>`;
    }
    if (view.status === 'empty' || view.snapshot === null) {
      return html`<jf-sv3-empty
        roomy
        role="status"
        data-testid="sv3-results-empty"
        glyph="&#9634;"
        heading=${MAIN_EMPTY.title}
        description=${view.filters.length > 0 ? MAIN_EMPTY.filteredDescription : MAIN_EMPTY.description}
      ></jf-sv3-empty>`;
    }
    return html`<jf-results-card
      data-testid="sv3-results-card"
      selection-mode="explicit"
      .snapshot=${view.snapshot}
      .facetSelections=${view.facetSelections}
      .selectedIds=${view.selectedIds}
      .askAvailability=${null}
      .forgetAvailable=${this.forgetAvailable}
    ></jf-results-card>`;
  }

  static styles = [
    sv3Shared,
    css`
      :host {
        display: block;
        width: 100%;
        max-inline-size: var(--measure-prose);
        margin-inline: auto;
        min-width: 0;
      }
      .results {
        display: flex;
        flex-direction: column;
        gap: var(--space-2);
      }
      .heading {
        margin: 0;
        font-size: var(--font-size-sv3-sm);
        font-weight: 600;
        overflow-wrap: anywhere;
      }
      .filters,
      .selection {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: var(--space-2);
      }
      .filter {
        display: inline-flex;
        align-items: center;
        gap: var(--space-1);
        min-height: 28px;
        padding: 0 var(--space-2);
        border: 1px solid var(--border);
        border-radius: var(--radius-md);
        background: var(--muted);
        color: var(--foreground);
        font: inherit;
        font-size: var(--font-size-sv3-xs);
        cursor: pointer;
      }
      .filter:hover {
        background: color-mix(in srgb, var(--foreground) 8%, var(--muted));
      }
      .filter:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: 2px;
      }
      .remove {
        color: var(--secondary-label);
      }
      .chosen {
        font-size: var(--font-size-sv3-xs);
        color: var(--secondary-label);
      }
      .pending {
        display: flex;
        flex-direction: column;
        gap: var(--space-1);
      }
      .skeleton-row {
        position: relative;
        overflow: hidden;
        height: var(--space-9);
        border-radius: var(--radius-md);
        background: var(--muted);
      }
      .skeleton-sheen {
        position: absolute;
        inset: 0;
        background: linear-gradient(
          90deg,
          transparent,
          color-mix(in srgb, var(--foreground) 8%, transparent),
          transparent
        );
      }
      .failure-detail {
        color: var(--secondary-label);
        font-family: var(--font-mono);
        font-size: var(--font-size-sv3-xs);
      }
    `,
  ];
}

customElements.define('jf-sv3-results', Sv3Results);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-results': Sv3Results;
  }
}
