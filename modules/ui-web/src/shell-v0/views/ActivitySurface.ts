// SPDX-License-Identifier: Apache-2.0
/**
 * ActivitySurface — Lit-side Activity surface (slice 486 F15-narrow; S6 one feed and details).
 *
 * One chronological feed (`<jf-action-ledger>`) joins the action ledger, the committed
 * `core.operation-history` stream and this window's Effect Journal by exact identities. Selecting a
 * record opens its details in the shared details pane (`jf-details-pane`, with Activity's own
 * content `<jf-activity-details>`): inline from 980 px of this window's width, a sheet over the
 * window below, at the width every window's pane shares. Each Activity holds its own selection.
 *
 * Per-record actions live in the details: the receipt, Ask the engine to undo, the causation trace,
 * Restore to this point, Undo this AI turn, related files and the run link, and choosing journal
 * records for a macro or an export (saved or exported, in journal order, from the controls at the
 * end of the details, where they scroll with them rather than taking their room).
 * What is not about one record stays a utility of Activity: your own reversals, the AI-wide undo and
 * its preview, saving recent AI actions as a macro, importing a journal archive, and background
 * runs with their launcher. What one run did, and what this window recorded, are scopes of the feed.
 *
 * Side-effect registers `<jf-activity-surface>` for the chrome dispatcher.
 *
 * Backend declaration: `CoreSurfaceCatalog.ACTIVITY_SURFACE_ID`
 * (`core.activity-surface`) → mountTag `jf-activity-surface`,
 * Audience.USER, Placement.DEEPLINK, consumes
 * `core.operation-history`.
 */

import { html, css, nothing, type PropertyValues, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { surfaceLayoutStyles } from '../primitives/surfaceLayout.js';
// Tempdoc 550 thesis II: mount the unified action-ledger read-view (the Outcome face — one
// chronological, originator-attributed, LIVE stream over operations + navigations + gates + FE
// effects). Side-effect import registers <jf-action-ledger>.
import '../components/ActionLedgerView.js';
import type { ActionLedgerView } from '../components/ActionLedgerView.js';
import '../components/RunActivity.js';
import type { RunActivity } from '../components/RunActivity.js';
import '../components/ActivityDetails.js';
import type { ActivityDetails } from '../components/ActivityDetails.js';
import '../components/detailsPane/DetailsPane.js';
import type { DetailsPane } from '../components/detailsPane/DetailsPane.js';
import {
  DETAILS_PANE_DEFAULT_PX,
  DETAILS_PANE_KEY_STEP_PX,
  clampDetailsPaneWidth,
  detailsPaneIsInline,
  forgetDetailsPaneWidth,
  readDetailsPaneWidth,
  storeDetailsPaneWidth,
} from '../components/detailsPane/detailsPaneSizing.js';
import { peekActivitySection, subscribeActivitySection, takeActivitySection } from '../state/activitySection.js';
import '../components/Button.js';
import '../components/EffectLine.js';
import { exportJournalArchive, importJournalArchive, listJournal, listJournalByOriginator, peekLastUndoableByOriginator,
  previewUndoAllByOriginator, undoEntries, subscribeJournal, type JournalEntry } from '../substrates/effects/index.js';
import { applyClipboardEffect, applyEffect } from '../substrates/actions/index.js';
import { defineMacro, defineMacroFromEffects } from '../substrates/macros/index.js';
import { elicit } from '../substrates/elicit/index.js';
import { isNewSinceSeen } from '../substrates/recall/recallCursor.js';
import { reportUndoRun } from '../components/undoReport.js';
import { listOpenReversals, subscribeReversals } from '../state/ownReversals.js';
import { getAgentSessionController, subscribeAgentSession } from '../state/agentSessionStore.js';
import { subscribeProfileSwitch } from '../state/UserStateDocument.js';
import { DEFAULT_FEED_FILTER, type SelectedRecord } from '../operations/activityFeed.js';
import { present } from '../display/present.js';
import { ReceiptController } from '../primitives/receiptController.js';
import { modalOwnsFocus } from '../primitives/modality.js';
import { deepActiveElement } from '../utils/keyboardHandler.js';

const DETAILS_TAB = 'record';
/** One empty list, so a re-render without run records is not a change the feed must answer. */
const NO_RUN_RECORDS: readonly never[] = [];

export class ActivitySurface extends JfElement {
  static properties = {
    apiBase: { type: String, attribute: 'api-base' },
    undoPreview: { state: true },
    selectedKey: { state: true },
    record: { state: true },
    evicted: { state: true },
    overlay: { type: Boolean, reflect: true },
    widthPx: { state: true },
    chosen: { state: true },
    exportFailed: { state: true },
  } as const;

  declare apiBase: string;
  declare undoPreview: readonly JournalEntry[];
  /** The selected feed record's key; null when no details are open. */
  declare selectedKey: string | null;
  /** What the selected key stood for when last loaded. */
  declare record: SelectedRecord | null;
  /** No loaded record answers to the selection any more. */
  declare evicted: boolean;
  /** Details are a sheet over this window (below 980 px of its width), not a pane beside the feed. */
  declare overlay: boolean;
  declare widthPx: number;
  /** Journal entry ids chosen in details for a macro or an export. */
  declare chosen: ReadonlySet<number>;
  /** The last export of the chosen records could not be written to the clipboard. */
  declare exportFailed: boolean;

  private stops: Array<() => void> = [];
  private observer: ResizeObserver | null = null;
  /** The run whose session-filtered read this Activity asked for last (one read per run). */
  private askedRun: string | null = null;

  constructor() {
    super();
    this.apiBase = '';
    this.undoPreview = [];
    this.selectedKey = null;
    this.record = null;
    this.evicted = false;
    this.overlay = false;
    this.widthPx = clampDetailsPaneWidth(readDetailsPaneWidth() ?? DETAILS_PANE_DEFAULT_PX);
    this.chosen = new Set();
    this.exportFailed = false;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this.stops = [
      subscribeJournal(() => this.requestUpdate()),
      subscribeReversals(() => this.requestUpdate()),
      subscribeActivitySection(() => this.arriveAtSection()),
      subscribeAgentSession(() => this.onAgentSession()),
      // Activity's records are not a profile's (the journal, the seen cursor and macros are shared):
      // a switch relabels nothing. What was open — the details, a receipt read, an undo preview, the
      // records chosen — was drawn before it, so it is closed rather than confirmed after it.
      subscribeProfileSwitch(() => this.settleOpenWork()),
    ];
    if (typeof ResizeObserver !== 'undefined') {
      this.observer = new ResizeObserver(() => this.measure());
      this.observer.observe(this);
    }
    this.measure();
    this.onAgentSession();
    this.arriveAtSection();
  }

  override disconnectedCallback(): void {
    for (const stop of this.stops.splice(0)) stop();
    this.observer?.disconnect();
    this.observer = null;
    super.disconnectedCallback();
  }

  private ctrl() {
    return getAgentSessionController(this.apiBase);
  }

  /** The current run's own read: asked once per run, so a failed read cannot loop on its own notice. */
  private onAgentSession(): void {
    const ctrl = this.ctrl();
    if (ctrl.sessionId && ctrl.sessionId !== this.askedRun) {
      this.askedRun = ctrl.sessionId;
      void ctrl.loadHistory();
    }
    this.requestUpdate();
  }

  private measure(): void {
    const width = this.getBoundingClientRect().width;
    this.overlay = !detailsPaneIsInline(width > 0 ? width : Number.POSITIVE_INFINITY);
  }

  private feed(): ActionLedgerView | null {
    return this.renderRoot.querySelector<ActionLedgerView>('jf-action-ledger');
  }

  /**
   * A pointer from elsewhere (the status panel, Ctrl+K) lands on the section it named. The request
   * is taken only by an Activity that is still on screen once rendered, so a second Activity (another
   * pane) or one closing meanwhile cannot consume it without revealing it. The journal's arrival
   * shows the feed scoped to what this window recorded, with every other filter cleared, so a filter
   * left from earlier cannot hide what the pointer named.
   */
  private arriveAtSection(): void {
    if (!this.isConnected || peekActivitySection() === null) return;
    void this.updateComplete.then(async () => {
      if (!this.isConnected) return;
      const section = takeActivitySection();
      if (section === null) return;
      if (section === 'background-runs') {
        const runs = this.renderRoot.querySelector<RunActivity>('jf-run-activity');
        await runs?.updateComplete;
        runs?.revealBackgroundRuns();
        return;
      }
      const feed = this.feed();
      if (!feed) return;
      feed.filter = { ...DEFAULT_FEED_FILTER, scope: 'local' };
      await feed.updateComplete;
      feed.scrollIntoView?.({ block: 'start' });
      const target = feed.shadowRoot?.querySelector<HTMLElement>('.entry[tabindex="0"]')
        ?? feed.shadowRoot?.querySelector<HTMLElement>('[data-testid="feed-scope"] button');
      target?.focus();
    });
  }

  // ---------------------------------------------------------------- selection and details

  private onSelect(key: string): void {
    if (key === this.selectedKey) return;
    const opening = this.selectedKey === null;
    this.selectedKey = key;
    this.evicted = false;
    if (opening && this.overlay) void this.focusPane();
  }

  private onRecord(key: string, record: SelectedRecord | null): void {
    if (key !== this.selectedKey) return;
    if (record === null) { this.evicted = this.record !== null; return; }
    this.evicted = false;
    this.record = record;
    // The record gained a partner and answers to a new key: the selection follows it.
    if (record.key !== key) this.selectedKey = record.key;
  }

  private async focusPane(): Promise<void> {
    // The record arrives from the feed's own update, which follows this one.
    await this.updateComplete;
    await this.feed()?.updateComplete;
    await this.updateComplete;
    const pane = this.renderRoot.querySelector<DetailsPane>('jf-details-pane');
    await pane?.updateComplete;
    pane?.focusActiveTab();
  }

  /**
   * The details' control that had the keyboard is gone: it goes to the selected record, or, while the
   * details are a sheet over the window (the feed behind it is inert), to the sheet's own tab.
   */
  private async focusSelectedRecord(): Promise<void> {
    const key = this.selectedKey;
    if (key === null) return;
    if (this.overlay) { this.renderRoot.querySelector<DetailsPane>('jf-details-pane')?.focusActiveTab(); return; }
    await this.feed()?.focusRecord(key);
  }

  /**
   * Close the details and put the keyboard back on the record they were for: the feed brings it
   * back into its windowed list if it scrolled away; if it is no longer shown, the list itself.
   * The Shell calls this for Escape with the keyboard on the page (through the System host).
   */
  closeDetails(): boolean {
    const key = this.selectedKey;
    if (key === null) return false;
    const returnFocus = this.keyboardHereOrNowhere();
    this.selectedKey = null;
    this.record = null;
    this.evicted = false;
    if (returnFocus) {
      void this.updateComplete.then(async () => {
        const feed = this.feed();
        if (!feed || !this.isConnected) return;
        if (!(await feed.focusRecord(key))) feed.shadowRoot?.querySelector<HTMLElement>('[data-testid="feed-list"], .empty')?.focus();
      });
    }
    return true;
  }

  /**
   * The keyboard is in Activity, or on nothing in particular: no element, the page, or a container
   * Activity sits in (the Shell's stage). Anywhere else it stays where it is when the details close.
   */
  private keyboardHereOrNowhere(): boolean {
    const active = deepActiveElement();
    if (active === null || active === document.body || this.matches(':focus-within')) return true;
    for (let node: Node | null = this; node !== null; node = node.parentNode ?? (node as ShadowRoot).host ?? null) {
      if (node === active) return true;
    }
    return false;
  }

  private readonly onKeydown = (event: KeyboardEvent): void => {
    if (event.key !== 'Escape' || event.isComposing || event.defaultPrevented || this.selectedKey === null || modalOwnsFocus()) return;
    event.preventDefault();
    event.stopPropagation();
    this.closeDetails();
  };

  /** A profile switch closes what was open, without confirming anything drawn before it. */
  private settleOpenWork(): void {
    this.renderRoot.querySelector<ActivityDetails>('jf-activity-details')?.settle();
    this.undoPreview = [];
    this.clearChosen();
    this.selectedKey = null;
    this.record = null;
    this.evicted = false;
  }

  private setWidth(px: number): void {
    this.widthPx = clampDetailsPaneWidth(px);
    storeDetailsPaneWidth(this.widthPx);
  }

  private readonly onGripPointerDown = (event: PointerEvent): void => {
    if (event.button !== 0) return;
    event.preventDefault();
    const startX = event.clientX;
    const startWidth = this.widthPx;
    const move = (moved: PointerEvent): void => { this.widthPx = clampDetailsPaneWidth(startWidth - (moved.clientX - startX)); };
    const end = (): void => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', end);
      window.removeEventListener('pointercancel', end);
      storeDetailsPaneWidth(this.widthPx);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', end);
    window.addEventListener('pointercancel', end);
  };

  private readonly onGripKeydown = (event: KeyboardEvent): void => {
    if (event.key === 'Home') {
      event.preventDefault();
      forgetDetailsPaneWidth();
      this.widthPx = DETAILS_PANE_DEFAULT_PX;
    } else if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      event.preventDefault();
      // Right-anchored: LEFT grows the pane, the direction the pointer drags it.
      this.setWidth(this.widthPx + (event.key === 'ArrowLeft' ? DETAILS_PANE_KEY_STEP_PX : -DETAILS_PANE_KEY_STEP_PX));
    }
  };

  // ---------------------------------------------------------------- the journal selection

  private choose(id: number, chosen: boolean): void {
    const next = new Set(this.chosen);
    if (chosen) next.add(id); else next.delete(id);
    this.chosen = next;
    this.exportFailed = false;
  }

  /** The chosen entries in journal order, whatever order they were chosen in. */
  private chosenEntries(): JournalEntry[] {
    return listJournal().filter((entry) => this.chosen.has(entry.id));
  }

  private async saveChosen(): Promise<void> {
    const entries = this.chosenEntries();
    if (!entries.length) return;
    const macro = await defineMacroFromEffects(entries.map((entry) => entry.effect));
    if (macro) this.clearChosen();
  }

  /**
   * Tempdoc 613 §6 — the export confirmation is a receipt flashed in its button, not a window toast.
   * It is flashed only once the clipboard took the archive; a refused write says so and stays.
   */
  private readonly exportReceipt = new ReceiptController(this);

  private async exportChosen(): Promise<void> {
    const entries = this.chosenEntries();
    if (!entries.length) return;
    this.exportFailed = false;
    const copied = await applyClipboardEffect(exportJournalArchive(entries));
    if (!this.isConnected) return;
    if (copied) this.exportReceipt.flash(`Exported ${entries.length} entr${entries.length === 1 ? 'y' : 'ies'}`);
    else this.exportFailed = true;
  }

  private clearChosen(): void {
    this.chosen = new Set();
    this.exportFailed = false;
  }

  // ---------------------------------------------------------------- Activity utilities

  /** DE6 — runs exactly the previewed set (nothing that arrived since), once, and says what was not undone. */
  private confirmUndo(): void {
    const previewed = this.undoPreview;
    this.undoPreview = [];
    if (!previewed.length) return;
    reportUndoRun(undoEntries(previewed, (effect) => applyEffect(effect)),
      (reversed) => `Undid ${reversed} AI action${reversed === 1 ? '' : 's'}`);
  }

  /** The newest reversible AI action, through the one run that tells an Engine request from a reversal here. */
  private undoLastAiAction(): void {
    const target = peekLastUndoableByOriginator('agent');
    if (!target) {
      applyEffect({ kind: 'toast', message: 'Nothing to undo', severity: 'info' });
      return;
    }
    reportUndoRun(undoEntries([target], (effect) => applyEffect(effect)),
      () => `Undid: ${present({ kind: 'effect', effect: target.effect }).label}`);
  }

  /** DE6 — your own recent actions that can still be reversed in this window, with their reversal. */
  private renderOwnReversals(): TemplateResult | typeof nothing {
    const open = listOpenReversals();
    if (!open.length) return nothing;
    return html`<section class="own-reversals" aria-label="You can still undo" data-testid="activity-own-reversals">
      <div class="section-label">You can still undo</div>
      <ul>${open.map(offer => html`<li><span>${offer.message}</span>
        <jf-button size="sm" .onActivate=${offer.run}>${offer.actionLabel}</jf-button></li>`)}</ul>
    </section>`;
  }

  private async saveMacro(): Promise<void> {
    const effects = listJournalByOriginator('agent')
      .filter(e => isNewSinceSeen(e.invokedAt) && e.pendingOutcome !== 'rejected').map(e => e.effect);
    if (effects.length) await defineMacroFromEffects(effects, {
      description: `Save what the assistant just did (${effects.length} action${effects.length === 1 ? '' : 's'}) as a reusable macro. It will surface in the command palette under "Macros".`,
    });
  }

  /** 543-fwd #19 — paste a previously exported archive and keep it as a reusable macro. */
  private async importArchive(): Promise<void> {
    const form = await elicit({
      title: 'Import journal archive',
      description: 'Paste a journal archive (JSON) to replay it as a reusable macro.',
      schema: {
        type: 'object',
        properties: {
          // A pasted archive is long: a multiline field (`x-ui`, read by defaultUiSchemaFor).
          json: { type: 'string', title: 'Archive JSON', 'x-ui': { multi: true } } as { type: 'string'; title: string },
          label: { type: 'string', title: 'Macro label' },
        },
        required: ['json', 'label'],
      },
      initialData: { json: '', label: '' },
    });
    if (!form) return;
    const { json, label } = form as { json?: string; label?: string };
    const effects = importJournalArchive(json ?? '');
    const name = label?.trim();
    if (effects.length === 0 || !name) {
      applyEffect({ kind: 'toast', message: 'No valid entries in that archive', severity: 'warning' });
      return;
    }
    try {
      defineMacro({ id: `import.${Date.now()}`, label: name, effects });
      applyEffect({ kind: 'toast', message: `Imported ${effects.length} action(s) as macro "${name}"`, severity: 'success' });
    } catch {
      applyEffect({ kind: 'toast', message: 'Import failed (duplicate id)', severity: 'error' });
    }
  }

  private renderAiActions(): TemplateResult | typeof nothing {
    if (!listJournalByOriginator('agent').some(e => e.pendingOutcome !== 'rejected')) return nothing;
    const reversible = previewUndoAllByOriginator('agent').length;
    return html`<div class="agent-actions">
      ${reversible ? html`<jf-button size="sm" label="Undo last AI action" data-testid="activity-undo-last"
        .onActivate=${() => this.undoLastAiAction()}>Undo last AI action</jf-button>
      <jf-button size="sm" label="Undo all AI actions" data-testid="activity-undo-all"
        .onActivate=${() => { this.undoPreview = previewUndoAllByOriginator('agent');
          if (!this.undoPreview.length) applyEffect({ kind: 'toast', message: 'Nothing to undo', severity: 'info' }); }}>Undo all AI actions</jf-button>` : nothing}
      <jf-button size="sm" label="Save as macro" data-testid="activity-save-macro"
        .onActivate=${() => void this.saveMacro()}>Save as macro</jf-button>
    </div>`;
  }

  private renderUndoPreview(): TemplateResult | typeof nothing {
    if (!this.undoPreview.length) return nothing;
    const remainder = listJournalByOriginator('agent').filter(e => e.pendingOutcome !== 'rejected'
      && !this.undoPreview.some(p => p.id === e.id)).length;
    return html`<section class="undo-preview" aria-label="Confirm undo AI actions" data-testid="activity-undo-confirm">
      <p>Undo these ${this.undoPreview.length} AI actions?</p>
      <ul>${this.undoPreview.map(e => html`<li data-testid="activity-undo-row"><jf-effect-line .effect=${e.effect}></jf-effect-line></li>`)}</ul>
      ${remainder ? html`<p data-testid="activity-irreversible">${remainder} other actions can't be undone and will remain.</p>` : nothing}
      <jf-button size="sm" variant="danger" label="Undo all" data-testid="activity-undo-confirm-yes" .onActivate=${() => this.confirmUndo()}>Undo all</jf-button>
      <jf-button size="sm" label="Cancel" data-testid="activity-undo-confirm-cancel" .onActivate=${() => { this.undoPreview = []; }}>Cancel</jf-button>
    </section>`;
  }

  // ---------------------------------------------------------------- layout

  // Tempdoc 559 Authority I: the :host / .header / .body region contract is
  // owned by surfaceLayoutStyles (the one layout authority); only bespoke rules
  // live here.
  static styles = [
    surfaceLayoutStyles,
    css`
    /* The window holds the feed's column and, from 980 px, the details pane beside it. One scroller,
       the column, holds the header and the feed: at 600×400 with 200% text a fixed header would
       leave the feed a sliver of the window. */
    :host {
      position: relative;
      flex-direction: row;
      overflow: hidden;
    }
    .main {
      flex: 1 1 auto;
      min-inline-size: 0;
      display: flex;
      flex-direction: column;
      overflow-y: auto;
    }
    .body {
      flex: none;
      overflow-y: visible;
    }
    jf-action-ledger {
      display: block;
      width: 100%;
    }
    .section-label {
      margin: 0.75rem 0 0.25rem 0;
      font-size: var(--font-size-xs);
      font-weight: 600;
      letter-spacing: 0.03em;
      text-transform: uppercase;
      color: var(--text-secondary);
    }
    .section-label:first-child {
      margin-top: 0;
    }
    .agent-actions, .utilities { display: flex; flex-wrap: wrap; gap: var(--jf-space-2); margin-block: var(--jf-space-3); }
    .undo-preview { padding: var(--jf-space-3); border: var(--frame-hairline) solid var(--border-default); }
    .own-reversals ul { margin: 0; padding: 0; list-style: none; }
    .own-reversals li { display: flex; flex-wrap: wrap; align-items: center; gap: var(--jf-space-2); padding-block: var(--jf-space-1); }
    .side {
      position: relative;
      flex: none;
      inline-size: var(--details-width);
      max-inline-size: 50%;
      min-block-size: 0;
    }
    .side jf-details-pane { block-size: 100%; }
    .grip {
      position: absolute;
      inset-block: 0;
      inset-inline-start: calc(-1 * var(--jf-space-2));
      z-index: var(--z-base);
      inline-size: var(--jf-space-4);
      padding: 0;
      border: 0;
      background: transparent;
      cursor: col-resize;
    }
    .grip:focus-visible { outline: var(--frame-focus-width) solid var(--focus-ring-color); }
    jf-activity-details { flex: 1 1 auto; min-block-size: 0; overflow-y: auto; }
    /* The choice's controls end the details' content and scroll with it, at every window size. */
    .chosen {
      margin: var(--jf-space-4) calc(-1 * var(--jf-space-4)) 0;
      padding: var(--jf-space-3) var(--jf-space-4) 0;
      border-block-start: var(--frame-hairline) solid var(--border-subtle);
    }
    .chosen .actions { display: flex; flex-wrap: wrap; gap: var(--jf-space-2); }
    .chosen p { margin: var(--jf-space-2) 0 0; }
    .details { display: contents; }
  `,
  ];

  protected override updated(changed: PropertyValues<this>): void {
    if (changed.has('widthPx')) this.style.setProperty('--details-width', `${this.widthPx}px`);
    // Opened while inline, then the window narrowed into a sheet: the keyboard goes to the sheet.
    if (changed.has('overlay') && this.overlay && this.selectedKey !== null && changed.get('overlay') === false) void this.focusPane();
  }

  private renderDetails(): TemplateResult | typeof nothing {
    const record = this.record;
    if (this.selectedKey === null || record === null) return nothing;
    const chosen = this.chosen.size;
    const pane = html`<jf-details-pane ?overlay=${this.overlay} region-label="Activity details" close-label="Close details"
        data-testid="activity-details-pane"
        .tabs=${[{ id: DETAILS_TAB, label: record.label }]} .activeTab=${DETAILS_TAB}
        @details-close=${() => this.closeDetails()}>
        <jf-activity-details .record=${record} ?evicted=${this.evicted} api-base=${this.apiBase} .journalSelection=${this.chosen}
          @journal-choose=${(event: CustomEvent<{ id: number; chosen: boolean }>) => this.choose(event.detail.id, event.detail.chosen)}
          @activity-details-focus-record=${() => void this.focusSelectedRecord()}
          @activity-run-scope=${() => this.scopeToRun()}>${chosen ? html`<div slot="chosen" class="chosen" role="group"
            aria-label="Chosen records" data-testid="activity-details-chosen">
          <div class="actions">
            <jf-button size="sm" data-testid="activity-save-chosen" label=${`Save chosen (${chosen}) as macro`}
              .onActivate=${() => void this.saveChosen()}>Save chosen (${chosen}) as macro</jf-button>
            <jf-button size="sm" data-testid="activity-export-chosen"
              .label=${this.exportReceipt.active ? `✓ ${this.exportReceipt.active.message}` : `Export chosen (${chosen})`}
              .onActivate=${() => void this.exportChosen()}>${this.exportReceipt.active ? `✓ ${this.exportReceipt.active.message}` : `Export chosen (${chosen})`}</jf-button>
            <jf-button size="sm" variant="ghost" data-testid="activity-clear-chosen" label="Clear choice"
              .onActivate=${() => this.clearChosen()}>Clear choice</jf-button>
          </div>
          ${this.exportFailed ? html`<p role="alert" data-testid="activity-export-failed">Not exported: the clipboard refused
            the copy, so nothing was copied. Allow this app to use the clipboard, then try again.</p>` : nothing}
        </div>` : nothing}</jf-activity-details>
      </jf-details-pane>`;
    return this.overlay ? pane : html`<div class="side">
      <button type="button" class="grip" data-testid="details-pane-grip"
        aria-label="Resize details — arrow keys resize, Home returns to automatic"
        title="Resize details — arrow keys resize, Home returns to automatic"
        @pointerdown=${this.onGripPointerDown} @keydown=${this.onGripKeydown}></button>${pane}</div>`;
  }

  private scopeToRun(): void {
    const feed = this.feed();
    const run = this.ctrl().sessionId;
    if (feed && run) feed.filter = { ...feed.filter, scope: 'run', runId: run };
  }

  override render(): TemplateResult {
    const ctrl = this.ctrl();
    const run = ctrl.sessionId;
    const sheet = this.overlay && this.selectedKey !== null && this.record !== null;
    return html`
      <div class="main" ?inert=${sheet} @keydown=${this.onKeydown}>
        <div class="header">
          <!-- Tempdoc 571 §11 / 578: shell topbar owns the page <h1>; <h2> here so this TRUST surface
               is embeddable as a host member (System) without emitting a second <h1>. -->
          <h2>Activity</h2>
          <div class="sub">
            What happened, who did it and how it ended — your actions, the
            assistant's, indexing and the engine's own work, in one list
            updated live. Choose a record for its details. For raw diagnostic output, see Logs.
          </div>
        </div>
        <div class="body">
          ${this.renderOwnReversals()}
          ${this.renderAiActions()}
          ${this.renderUndoPreview()}
          <div class="utilities">
            <jf-button size="sm" variant="ghost" label="Import journal archive" data-testid="activity-import-archive"
              title="Paste a journal archive to keep it as a reusable macro"
              .onActivate=${() => void this.importArchive()}>Import journal archive</jf-button>
          </div>
          <jf-run-activity api-base=${this.apiBase} data-testid="activity-run-activity"></jf-run-activity>
          <!-- The one feed: ledger, committed operation history and this window's journal, joined. -->
          <div class="section-label">Activity</div>
          <jf-action-ledger
            api-base=${this.apiBase}
            data-testid="activity-surface-action-ledger"
            .selectedKey=${this.selectedKey}
            .currentRun=${run}
            .runRecords=${run && ctrl.historySessionId === run ? ctrl.historyRecords : NO_RUN_RECORDS}
            @feed-select=${(event: CustomEvent<{ key: string }>) => this.onSelect(event.detail.key)}
            @feed-record=${(event: CustomEvent<{ key: string; record: SelectedRecord | null }>) => this.onRecord(event.detail.key, event.detail.record)}
          ></jf-action-ledger>
        </div>
      </div>
      <div class="details" @keydown=${this.onKeydown}>${this.renderDetails()}</div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-activity-surface')) {
  customElements.define('jf-activity-surface', ActivitySurface);
}
