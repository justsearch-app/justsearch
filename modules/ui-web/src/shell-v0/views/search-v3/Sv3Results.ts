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
import type { CardHit } from '../../components/searchResults/ResultsCard.js';
import { formatLocationBreadcrumb } from '../../components/searchResults/resultRowPresentation.js';
import { projectResultView, type ResultViewInput } from '../searchResultViewModel.js';
import { openLocalFile } from '../../plugin-api/capabilities/platform.js';
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
    previewId: { state: true },
  };

  declare view: Sv3ResultsView;
  declare forgetAvailable: boolean;
  /** The result the Space preview is showing; null while none is. */
  declare private previewId: string | null;
  private previewRow: HTMLElement | null = null;

  constructor() {
    super();
    this.view = SV3_RESULTS_IDLE;
    this.forgetAvailable = false;
    this.previewId = null;
  }

  /** Read by the window's Escape order: the preview is more local than the details pane. */
  get previewOpen(): boolean {
    return this.previewId !== null;
  }

  private previewHit(): CardHit | null {
    return (this.view.snapshot?.results as readonly CardHit[] | undefined)?.find((hit) => hit.id === this.previewId) ?? null;
  }

  private readonly closePreview = (): void => {
    this.previewRow?.removeEventListener('focusout', this.closePreview);
    this.previewId = null;
    this.previewRow = null;
  };

  private readonly onCardPreview = (event: Event): void => {
    const detail = (event as CustomEvent<{ id: string; row: HTMLElement }>).detail;
    const again = this.previewId === detail.id;
    this.closePreview();
    if (again) return;
    this.previewId = detail.id;
    this.previewRow = detail.row;
    // On the row, not around the list: a move inside the card's shadow tree never reaches this one.
    detail.row.addEventListener('focusout', this.closePreview);
  };

  /** While the card is open the row keeps the keyboard, so the card's keys arrive here. */
  private readonly onPreviewKeydown = {
    capture: true, // Ahead of the row, whose own Enter opens details.
    handleEvent: (event: KeyboardEvent): void => this.previewKey(event),
  };

  private previewKey(event: KeyboardEvent): void {
    const hit = this.previewHit();
    if (hit === null) return;
    if (event.key === 'Escape') {
      event.stopPropagation();
      this.closePreview();
    } else if (event.key === 'Enter') {
      event.stopPropagation();
      event.preventDefault();
      this.closePreview();
      void openLocalFile(hit.path).catch(() => { /* Already reported. */ });
    } else if (event.key === 'ArrowRight') {
      event.preventDefault();
      this.closePreview();
      this.emit('card-open', { id: hit.id });
    }
  }

  override connectedCallback(): void {
    super.connectedCallback();
    window.addEventListener('wheel', this.closePreview, { passive: true });
    window.addEventListener('resize', this.closePreview);
  }

  override disconnectedCallback(): void {
    window.removeEventListener('wheel', this.closePreview);
    window.removeEventListener('resize', this.closePreview);
    this.closePreview();
    super.disconnectedCallback();
  }

  /** New results may have dropped the row on preview; the card never outlives what it describes. */
  override willUpdate(changed: Map<string, unknown>): void {
    if (changed.has('view') && this.previewId !== null && this.previewHit() === null) this.closePreview();
  }

  override updated(): void {
    const card = this.renderRoot.querySelector<HTMLElement>('.preview');
    const row = this.previewRow?.getBoundingClientRect();
    if (card === null || row === undefined) return;
    const { width, height } = card.getBoundingClientRect();
    // The card keeps one step of the scale from the window's edges.
    const edge = 'var(--jf-space-2)';
    card.style.insetInlineStart = `clamp(${edge}, calc(${row.right - width}px - ${edge}), calc(100vw - ${width}px - ${edge}))`;
    card.style.insetBlockStart = `clamp(${edge}, calc(${row.top}px + ${edge}), calc(100vh - ${height}px - ${edge}))`;
  }

  /** The floating card (design system §7, ref-04). Everything in it is already on the row's record. */
  private preview(): TemplateResult | typeof nothing {
    const hit = this.previewHit();
    if (hit === null) return nothing;
    const view = projectResultView(hit as unknown as ResultViewInput);
    return html`<section class="preview" role="status" aria-label=${`Preview of ${view.title}`} data-testid="sv3-result-preview">
      <h3 class="preview-title">${view.title}</h3>
      <p class="preview-path" title=${hit.path}>${formatLocationBreadcrumb(hit.path)}</p>
      <p class="preview-text">${view.snippet || 'No preview available.'}</p>
      <p class="preview-keys">
        <span><kbd>Space</kbd> close</span><span><kbd>Enter</kbd> open</span><span><kbd>→</kbd> details</span>
      </p>
    </section>`;
  }

  private emit<T>(name: string, detail?: T): void {
    this.dispatchEvent(new CustomEvent(name, { detail, bubbles: true, composed: true }));
  }

  override render(): TemplateResult {
    const view = this.view;
    return html`
      <section class="results" aria-label="Search results" data-testid="sv3-results"
        @card-preview=${this.onCardPreview} @keydown=${this.onPreviewKeydown}>
        <h2 class="heading" data-testid="sv3-results-heading">Results for “${view.query}”</h2>
        ${this.filters(view.filters)} ${this.selection(view)} ${this.body(view)}
      </section>
      ${this.preview()}
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
      .preview {
        position: fixed;
        z-index: var(--z-overlay);
        box-sizing: border-box;
        inline-size: min(var(--frame-details-min), calc(100vw - var(--space-4)));
        max-block-size: calc(100vh - var(--space-4));
        overflow: auto;
        padding: var(--space-4);
        border: 1px solid var(--border);
        border-radius: var(--radius-lg);
        background: var(--popover);
        color: var(--popover-foreground);
        box-shadow: var(--dialog-shadow);
      }
      .preview-title {
        margin: 0;
        font-size: var(--font-size-sv3-sm);
        font-weight: 500;
        overflow-wrap: anywhere;
      }
      .preview-path {
        margin: var(--space-1) 0 var(--space-3);
        color: var(--secondary-label);
        font-size: var(--font-size-sv3-xs);
        overflow-wrap: anywhere;
      }
      .preview-text {
        margin: 0;
        font-size: var(--font-size-sv3-sm);
        overflow-wrap: anywhere;
      }
      .preview-keys {
        display: flex;
        flex-wrap: wrap;
        gap: var(--space-3);
        margin: var(--space-3) 0 0;
        padding-block-start: var(--space-3);
        border-block-start: 1px solid var(--border);
        color: var(--secondary-label);
        font-size: var(--font-size-sv3-xs);
      }
      kbd {
        padding-inline: var(--space-1);
        border: 1px solid var(--border);
        border-radius: var(--radius-sm);
        font: inherit;
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
