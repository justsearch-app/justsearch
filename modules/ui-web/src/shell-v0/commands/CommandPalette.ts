// SPDX-License-Identifier: Apache-2.0
/**
 * CommandPalette — Tempdoc 508 §3.3 — searchable overlay for all
 * invocable actions.
 *
 * Activated via Ctrl+K (configurable). Projects the CommandRegistry
 * with fuzzy search, category grouping, and shortcut display.
 *
 * Target: sub-50ms render (per Superhuman research).
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { repeat } from 'lit/directives/repeat.js';
import {
  invokeCommand,
  parsePaletteQuery,
} from './CommandRegistry.js';
// §21.C — palette consumes the unified projection over Action.listActions
// + legacy Commands. ScoredPaletteEntry retires in favor of ScoredPaletteEntry.
import {
  searchPaletteEntries,
  type ScoredPaletteEntry,
  type PaletteEntry,
} from './CommandPaletteProjection.js';
import { listEmptyStates } from './EmptyStateRegistry.js';
import {
  setSlotPromptProvider,
  type NamedArgumentSlot,
} from './TemplateCatalog.js';

/**
 * Tempdoc 508-followup §δ1 — wizard step: one resolved (or pending)
 * slot prompt. Completed steps appear as breadcrumbs above the input.
 */
interface WizardStep {
  readonly slot: NamedArgumentSlot;
  readonly templateId: string;
  readonly resolver: (value: string | null) => void;
}

export class CommandPalette extends JfElement {
  static properties = {
    open: { type: Boolean, reflect: true },
    query: { state: true },
    results: { state: true },
    selectedIndex: { state: true },
    wizardActive: { state: true },
    wizardInput: { state: true },
    wizardCompletedLabels: { state: true },
  };

  declare open: boolean;
  declare query: string;
  declare results: ScoredPaletteEntry[];
  declare selectedIndex: number;
  /** Tempdoc 508-followup §δ1 — palette is in slot-collection mode. */
  declare wizardActive: boolean;
  /** Tempdoc 508-followup §δ1 — current wizard input value (prefilled with default). */
  declare wizardInput: string;
  /** Tempdoc 508-followup §δ1 — completed step breadcrumb labels. */
  declare wizardCompletedLabels: ReadonlyArray<{ name: string; value: string }>;

  /** Tempdoc 508-followup §δ1 — pending wizard step (the slot we're prompting for). */
  private wizardStep: WizardStep | null = null;

  constructor() {
    super();
    this.open = false;
    this.query = '';
    this.results = [];
    this.selectedIndex = 0;
    this.wizardActive = false;
    this.wizardInput = '';
    this.wizardCompletedLabels = [];
  }

  connectedCallback(): void {
    super.connectedCallback();
    // Tempdoc 508-followup §δ1 — register as the slot prompt provider
    // so template invocations resolve through the inline wizard
    // instead of window.prompt.
    setSlotPromptProvider((slot, templateId) => this.promptForSlot(slot, templateId));
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    setSlotPromptProvider(null);
    // If a wizard was active, resolve with null (cancel) so any
    // in-flight template expansion unblocks.
    if (this.wizardStep) {
      const step = this.wizardStep;
      this.wizardStep = null;
      step.resolver(null);
    }
  }

  /**
   * Tempdoc 508-followup §δ1 — promise-returning entry point for
   * TemplateCatalog. Opens the palette in wizard mode for the slot,
   * waits for Enter (resolve with value) or Esc (resolve null).
   * Multiple-slot templates call this once per slot in declaration
   * order; completed slots accumulate in wizardCompletedLabels.
   */
  private promptForSlot(slot: NamedArgumentSlot, templateId: string): Promise<string | null> {
    return new Promise<string | null>((resolve) => {
      this.wizardStep = { slot, templateId, resolver: resolve };
      this.wizardActive = true;
      this.wizardInput = slot.default ?? '';
      this.open = true;
      this.updateComplete.then(() => {
        const input = this.shadowRoot?.querySelector('input');
        input?.focus();
        input?.select();
      });
    });
  }

  /** Tempdoc 508-followup §δ1 — confirm the current step. */
  private confirmWizardStep(): void {
    if (!this.wizardStep) return;
    const step = this.wizardStep;
    const value = this.wizardInput;
    // Add to breadcrumb so the user can see what's been answered if
    // a subsequent prompt arrives on the same palette instance.
    this.wizardCompletedLabels = [
      ...this.wizardCompletedLabels,
      { name: step.slot.name, value },
    ];
    this.wizardStep = null;
    this.wizardInput = '';
    this.wizardActive = false;
    step.resolver(value);
  }

  /** Tempdoc 508-followup §δ1 — cancel resolves with null. */
  private cancelWizard(): void {
    if (!this.wizardStep) {
      this.hide();
      return;
    }
    const step = this.wizardStep;
    this.wizardStep = null;
    this.wizardInput = '';
    this.wizardActive = false;
    this.wizardCompletedLabels = [];
    this.hide();
    step.resolver(null);
  }

  static styles = css`
    :host {
      /* 559 Authority I: placement owned by the OverlayHost center slot. */
      display: none;
      align-items: flex-start;
      justify-content: center;
      padding-top: 15vh;
    }
    :host([open]) {
      display: flex;
    }
    .backdrop {
      /* 559 Authority I: absolute within the OverlayHost center slot (fixed;inset:0). */
      position: absolute;
      inset: 0;
      background: rgba(0, 0, 0, 0.5);
    }
    .panel {
      position: relative;
      z-index: 1;
      width: min(540px, 90vw);
      max-height: 60vh;
      background: var(--surface-1);
      border: 1px solid var(--border-subtle);
      border-radius: 0.75rem;
      box-shadow: var(--shadow-float);
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }
    .search-row {
      display: flex;
      align-items: center;
      padding: 0.75rem 1rem;
      border-bottom: 1px solid var(--border-subtle);
      gap: 0.5rem;
    }
    .search-row input {
      flex: 1;
      background: transparent;
      border: none;
      color: var(--text-primary);
      font-size: var(--font-size-md);
      outline: none;
      font-family: inherit;
    }
    /* Transparent, not none: invisible here as before, but forced colors repaint it, so a High
       Contrast reader still sees where focus is once the panel's own styling is gone. */
    .search-row input:focus-visible {
      outline: 2px solid transparent;
      outline-offset: -2px;
    }
    .search-hint {
      font-size: var(--font-size-xs);
      color: var(--text-muted);
      white-space: nowrap;
    }
    .mode-chip {
      font-size: var(--font-size-xs);
      font-weight: 600;
      padding: 0.125rem 0.375rem;
      background: var(--accent-tint);
      color: var(--accent-on-tint);
      border-radius: 0.25rem;
      white-space: nowrap;
    }
    .results {
      overflow-y: auto;
      max-height: calc(60vh - 3.5rem);
    }
    .category-header {
      font-size: var(--font-size-xs);
      font-weight: 600;
      color: var(--text-secondary);
      text-transform: uppercase;
      letter-spacing: 0.05em;
      padding: 0.5rem 1rem 0.25rem;
    }
    .result-row {
      display: flex;
      align-items: center;
      padding: 0.5rem 1rem;
      cursor: pointer;
      gap: 0.5rem;
    }
    .result-row:hover,
    .result-row[data-selected] {
      background: var(--surface-hover);
    }
    .result-label {
      flex: 1;
      font-size: var(--font-size-sm);
      color: var(--text-primary);
    }
    .result-shortcut {
      font-size: var(--font-size-xs);
      color: var(--text-muted);
      font-family: var(--font-mono);
      padding: 0.125rem 0.375rem;
      background: var(--surface-2);
      border-radius: 0.25rem;
      border: 1px solid var(--border-subtle);
    }
    .result-source {
      font-size: var(--font-size-xs);
      color: var(--text-ghost);
    }
    .empty {
      padding: 1.5rem;
      text-align: center;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
    /* §11.9 polish — fuzzy-match highlight inside .result-label */
    .result-label mark {
      background: transparent;
      color: var(--text-tint);
      font-weight: 600;
    }
    .empty-headline { margin-bottom: 0.75rem; }
    .empty-fallbacks { display: flex; flex-direction: column; gap: 0.25rem; align-items: stretch; }
    .empty-fallback {
      padding: 0.5rem 0.75rem;
      border-radius: 0.25rem;
      background: var(--surface-hover);
      color: var(--text-primary);
      cursor: pointer;
      text-align: left;
    }
    .empty-fallback:hover { background: var(--surface-active); }
  `;

  show(): void {
    this.open = true;
    this.query = '';
    this.results = searchPaletteEntries('');
    this.selectedIndex = 0;
    this.updateComplete.then(() => {
      const input = this.shadowRoot?.querySelector('input');
      input?.focus();
    });
  }

  hide(): void {
    this.open = false;
    this.query = '';
    this.results = [];
    this.selectedIndex = 0;
    this.wizardCompletedLabels = [];
  }

  private handleInput(e: Event): void {
    const value = (e.target as HTMLInputElement).value;
    if (this.wizardActive) {
      this.wizardInput = value;
      return;
    }
    this.query = value;
    this.results = searchPaletteEntries(this.query);
    this.selectedIndex = 0;
  }

  private handleKeydown(e: KeyboardEvent): void {
    // Tempdoc 508-followup §δ1 — wizard mode: Enter confirms,
    // Escape cancels; arrow keys are no-ops since there's no result list.
    if (this.wizardActive) {
      if (e.key === 'Escape') {
        e.preventDefault();
        this.cancelWizard();
      } else if (e.key === 'Enter') {
        e.preventDefault();
        this.confirmWizardStep();
      }
      return;
    }
    if (e.key === 'Escape') {
      e.preventDefault();
      this.hide();
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      this.selectedIndex = Math.min(this.selectedIndex + 1, this.results.length - 1);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      this.selectedIndex = Math.max(this.selectedIndex - 1, 0);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const selected = this.results[this.selectedIndex];
      if (selected) {
        this.invokeEntry(selected.entry);
        this.hide();
      }
    }
  }

  private handleSelect(index: number): void {
    const selected = this.results[index];
    if (selected) {
      this.invokeEntry(selected.entry);
      this.hide();
    }
  }

  /**
   * Tempdoc 548 §8 (intent surface, v0) — dispatch a palette entry. The
   * synthetic search entry (origin 'intent') routes its free-text THROUGH the
   * intent pipeline (navigate-with-state → IntentRouter → searchState restore),
   * not via a direct `setSearchQuery` side-channel; everything else goes through
   * the normal command/action invoke path unchanged.
   */
  private invokeEntry(entry: PaletteEntry): void {
    if (entry.origin === 'intent' && entry.id === 'intent.search') {
      const text = entry.intentText ?? parsePaletteQuery(this.query).text;
      // Tempdoc 548 §4.5 (S4-B) — authority collapse: the query travels through
      // the navigation INTENT, giving "the user asked to search" a single
      // authority instead of a setSearchQuery side-channel + a separate navigate.
      // Mirrors the shipped `core.palette.search-from-here` empty state
      // (Shell.activateSurface('core.search-v3-surface', { query }, …)), so URL
      // projection / telemetry / observability all see the navigation. Fired as a
      // composed event so it crosses the shadow boundary up to Shell's
      // `navigate-with-context` listener.
      // Search Thread S5b — the standalone Search rail surface is retired; the retrieve tier
      // folded into the one window (core.search-v3-surface).
      this.dispatchEvent(
        new CustomEvent('navigate-with-context', {
          detail: { target: 'core.search-v3-surface', state: { query: text } },
          bubbles: true,
          composed: true,
        }),
      );
      return;
    }
    invokeCommand(entry.id);
  }

  /**
   * Tempdoc 508-followup §δ2 — hover-preview dispatch. When a result
   * row is hovered with Alt held, we map the command to a surfaceId
   * (today: `shell.go-to-<id>` navigation commands) and fire a
   * `jf-peek-request` event picked up by `<jf-peek>`. Releasing Alt
   * or moving the cursor off the row fires `jf-peek-dismiss`.
   *
   * Working assumption: Alt is the least-collidey modifier per the
   * plan's flagged decision.
   */
  private peekActive = false;
  private peekKeyupHandler: ((e: KeyboardEvent) => void) | null = null;

  private handlePeekMouseEnter(e: MouseEvent, item: ScoredPaletteEntry): void {
    if (!e.altKey) return;
    const surfaceId = this.commandToSurfaceId(item.entry.id);
    if (!surfaceId) return;
    document.dispatchEvent(new CustomEvent('jf-peek-request', { detail: { surfaceId } }));
    this.peekActive = true;
    // Hook keyup so releasing Alt dismisses without requiring mouseleave.
    if (!this.peekKeyupHandler) {
      this.peekKeyupHandler = (ev: KeyboardEvent) => {
        if (ev.key === 'Alt' && this.peekActive) {
          this.dismissPeek();
        }
      };
      document.addEventListener('keyup', this.peekKeyupHandler);
    }
  }

  private handlePeekMouseLeave(): void {
    if (this.peekActive) this.dismissPeek();
  }

  private dismissPeek(): void {
    this.peekActive = false;
    document.dispatchEvent(new CustomEvent('jf-peek-dismiss'));
    if (this.peekKeyupHandler) {
      document.removeEventListener('keyup', this.peekKeyupHandler);
      this.peekKeyupHandler = null;
    }
  }

  /**
   * Map a command id to a surface id. V1 only handles navigation
   * commands (`shell.go-to-<id>` → `core.<id>-surface`). Future:
   * a richer registration map keyed by command id.
   */
  private commandToSurfaceId(commandId: string): string | null {
    const navPrefix = 'shell.go-to-';
    if (commandId.startsWith(navPrefix)) {
      const tail = commandId.slice(navPrefix.length);
      // Match the names registered in CommandRegistry.registerShellCommands.
      // Search Thread S5b — 'search' now resolves to the one window (the standalone Search
      // rail surface is retired; its retrieve tier folded into core.search-v3-surface).
      if (tail === 'search') return 'core.search-v3-surface';
      if (tail === 'library') return 'core.library-surface';
      if (tail === 'settings') return 'core.settings-surface';
      if (tail === 'health') return 'core.health-surface';
      if (tail === 'chat') return 'core.search-v3-surface';
      if (tail === 'browse') return 'core.browse-surface';
    }
    return null;
  }

  override render(): TemplateResult {
    if (!this.open) return html``;

    // Tempdoc 508-followup §δ1 — wizard render path. Replaces the
    // result list with a single-slot prompt UI while a template's
    // expandTemplate is awaiting input. Esc cancels (resolves null);
    // Enter advances or completes.
    if (this.wizardActive && this.wizardStep) {
      const slot = this.wizardStep.slot;
      return html`
        <div class="backdrop" @click=${() => this.cancelWizard()}></div>
        <div class="panel">
          <div class="search-row">
            <span class="mode-chip">${this.wizardStep.templateId}</span>
            <input
              type="text"
              placeholder=${slot.name}
              .value=${this.wizardInput}
              @input=${this.handleInput}
              @keydown=${this.handleKeydown}
            />
            <span class="search-hint">Enter to continue · Esc to cancel</span>
          </div>
          <div class="results">
            <div class="category-header" data-wizard-slot-name=${slot.name}>
              ${slot.name}${slot.default !== undefined ? html` <span class="result-source">default: ${slot.default}</span>` : nothing}
            </div>
            ${this.wizardCompletedLabels.length > 0
              ? html`<div class="category-header">Completed</div>
                ${this.wizardCompletedLabels.map(
                  (c) => html`<div class="result-row"><span class="result-label">${c.name}</span><span class="result-source">${c.value}</span></div>`,
                )}`
              : nothing}
          </div>
        </div>
      `;
    }

    const grouped = this.groupByCategory(this.results);
    const parsed = parsePaletteQuery(this.query);
    const modeLabel = parsed.mode === 'commands' ? '' : parsed.mode === 'surfaces' ? 'Surfaces' : 'Settings';
    const placeholder = parsed.mode === 'commands'
      ? "Type a command... (> commands, # surfaces, @ settings)"
      : `Filter ${modeLabel.toLowerCase()}...`;

    return html`
      <div class="backdrop" @click=${() => this.hide()}></div>
      <div class="panel">
        <div class="search-row">
          ${modeLabel ? html`<span class="mode-chip">${modeLabel}</span>` : ''}
          <input
            type="text"
            placeholder=${placeholder}
            .value=${this.query}
            @input=${this.handleInput}
            @keydown=${this.handleKeydown}
          />
          <span class="search-hint">Esc to close</span>
        </div>
        <div class="results">
          ${this.results.length === 0 && this.query.length > 0
            ? this.renderEmptyState()
            : nothing}
          ${grouped.map(([category, items]) => html`
            <div class="category-header">${category || 'Commands'}</div>
            ${items.map((item) => {
              const globalIndex = this.results.indexOf(item);
              return html`
                <div
                  class="result-row"
                  id=${`palette-opt-${globalIndex}`}
                  role="option"
                  aria-selected=${globalIndex === this.selectedIndex}
                  ?data-selected=${globalIndex === this.selectedIndex}
                  @click=${() => this.handleSelect(globalIndex)}
                  @mouseenter=${(e: MouseEvent) => this.handlePeekMouseEnter(e, item)}
                  @mouseleave=${() => this.handlePeekMouseLeave()}
                >
                  <span class="result-label">${this.renderLabelWithMatches(item.entry.label, item.matches)}</span>
                  ${item.entry.shortcut
                    ? html`<span class="result-shortcut">${item.entry.shortcut}</span>`
                    : nothing}
                  <span class="result-source">${item.entry.origin}</span>
                </div>
              `;
            })}
          `)}
        </div>
      </div>
    `;
  }

  /**
   * §11.9 polish — render the command label with matched characters
   * highlighted. `matches` is a sorted array of character indices
   * produced by the fuzzy scorer; we wrap each contiguous matched
   * run in a <mark> tag. Empty matches produces plain text.
   */
  private renderLabelWithMatches(label: string, matches: ReadonlyArray<number>): TemplateResult | string {
    if (!matches || matches.length === 0) return label;
    const sorted = [...matches].sort((a, b) => a - b);
    const segments: Array<{ text: string; matched: boolean }> = [];
    let cursor = 0;
    let i = 0;
    while (i < sorted.length) {
      const start = sorted[i]!;
      // Find the contiguous run.
      let end = start;
      while (i + 1 < sorted.length && sorted[i + 1] === end + 1) {
        i++;
        end = sorted[i]!;
      }
      if (start > cursor) {
        segments.push({ text: label.slice(cursor, start), matched: false });
      }
      segments.push({ text: label.slice(start, end + 1), matched: true });
      cursor = end + 1;
      i++;
    }
    if (cursor < label.length) {
      segments.push({ text: label.slice(cursor), matched: false });
    }
    return html`${segments.map((seg) =>
      seg.matched ? html`<mark>${seg.text}</mark>` : seg.text,
    )}`;
  }

  private groupByCategory(results: ScoredPaletteEntry[]): Array<[string, ScoredPaletteEntry[]]> {
    const groups = new Map<string, ScoredPaletteEntry[]>();
    for (const r of results) {
      const cat = r.entry.category ?? '';
      const list = groups.get(cat) ?? [];
      list.push(r);
      groups.set(cat, list);
    }
    return Array.from(groups.entries());
  }

  /**
   * §11.6 / §13.6 — render fallback contributions when the palette
   * has no matches for a non-empty query. Replaces the previous
   * static "No commands match" message with a structured registry
   * query.
   */
  private renderEmptyState(): TemplateResult {
    const contributions = listEmptyStates({
      context: 'palette-no-results',
      query: this.query,
    });
    if (contributions.length === 0) {
      return html`<div class="empty">No commands match "${this.query}"</div>`;
    }
    return html`
      <div class="empty">
        <div class="empty-headline">No commands match "${this.query}"</div>
        <div class="empty-fallbacks">
          ${repeat(contributions, (c) => c.id, (c) => {
            const rendered = c.render({
              context: 'palette-no-results',
              query: this.query,
            });
            if (typeof rendered === 'string') {
              return html`<div class="empty-fallback" data-fallback-id=${c.id}>${rendered}</div>`;
            }
            return html`<div class="empty-fallback" data-fallback-id=${c.id}>${rendered}</div>`;
          })}
        </div>
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-command-palette')) {
  customElements.define('jf-command-palette', CommandPalette);
}
