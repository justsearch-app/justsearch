// SPDX-License-Identifier: Apache-2.0
/**
 * ActivitySurface — Lit-side Activity surface (slice 486 F15-narrow; S6 one feed).
 *
 * One chronological feed (`<jf-action-ledger>`) joins the action ledger, the committed
 * `core.operation-history` stream and this window's Effect Journal by exact identities, with the
 * Activity utilities beside it: own reversals, the AI-wide undo/preview and macro actions, background
 * runs, and the local effect journal (its per-entry actions and archive).
 *
 * Side-effect registers `<jf-activity-surface>` for the chrome dispatcher.
 *
 * Backend declaration: `CoreSurfaceCatalog.ACTIVITY_SURFACE_ID`
 * (`core.activity-surface`) → mountTag `jf-activity-surface`,
 * Audience.USER, Placement.DEEPLINK, consumes
 * `core.operation-history`.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { surfaceLayoutStyles } from '../primitives/surfaceLayout.js';
// Tempdoc 550 thesis II: mount the unified action-ledger read-view (the Outcome face — one
// chronological, originator-attributed, LIVE stream over operations + navigations + gates + FE
// effects). Side-effect import registers <jf-action-ledger>.
import '../components/ActionLedgerView.js';
import '../components/RunActivity.js';
import type { RunActivity } from '../components/RunActivity.js';
import '../components/EffectAuditLog.js';
import { peekActivitySection, subscribeActivitySection, takeActivitySection } from '../state/activitySection.js';
import '../components/Button.js';
import '../components/EffectLine.js';
import { listJournalByOriginator, previewUndoAllByOriginator, undoEntries, subscribeJournal,
  type JournalEntry } from '../substrates/effects/index.js';
import { applyEffect } from '../substrates/actions/index.js';
import { defineMacroFromEffects } from '../substrates/macros/index.js';
import { isNewSinceSeen } from '../substrates/recall/recallCursor.js';
import { reportUndoRun } from '../components/undoReport.js';
import { listOpenReversals, subscribeReversals } from '../state/ownReversals.js';

export class ActivitySurface extends JfElement {
  static properties = {
    apiBase: { type: String, attribute: 'api-base' },
    undoPreview: { state: true },
  } as const;

  declare apiBase: string;
  declare undoPreview: readonly JournalEntry[];
  private stopJournal: (() => void) | undefined;
  private stopReversals: (() => void) | undefined;
  private stopSection: (() => void) | undefined;

  constructor() {
    super();
    this.apiBase = '';
    this.undoPreview = [];
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this.stopJournal = subscribeJournal(() => this.requestUpdate());
    this.stopReversals = subscribeReversals(() => this.requestUpdate());
    this.stopSection = subscribeActivitySection(() => this.arriveAtSection());
    this.arriveAtSection();
  }

  /**
   * A pointer from elsewhere (the status panel, Ctrl+K) lands on the section it named. The request
   * is taken only by an Activity that is still on screen once rendered, so a second Activity (another
   * pane) or one closing meanwhile cannot consume it without revealing it. The journal's own filters
   * are cleared on arrival, so a filter left from earlier cannot hide what the pointer named.
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
      const journal = this.renderRoot.querySelector<HTMLElement & { updateComplete?: Promise<unknown>;
        originator?: string; outcome?: string }>('jf-effect-audit-log');
      if (journal) { journal.originator = 'all'; journal.outcome = 'all'; }
      await journal?.updateComplete;
      const panel = journal?.shadowRoot?.querySelector<HTMLElement>('[data-testid="effect-audit-journal"]');
      panel?.scrollIntoView?.({ block: 'start' });
      panel?.focus();
    });
  }
  override disconnectedCallback(): void {
    this.stopJournal?.(); this.stopJournal = undefined;
    this.stopReversals?.(); this.stopReversals = undefined;
    this.stopSection?.(); this.stopSection = undefined;
    super.disconnectedCallback();
  }

  /** DE6 — runs exactly the previewed set (nothing that arrived since), once, and says what was not undone. */
  private confirmUndo(): void {
    const previewed = this.undoPreview;
    this.undoPreview = [];
    if (!previewed.length) return;
    reportUndoRun(undoEntries(previewed, (effect) => applyEffect(effect)),
      (reversed) => `Undid ${reversed} AI action${reversed === 1 ? '' : 's'}`);
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

  // Tempdoc 559 Authority I: the :host / .header / .body region contract is
  // owned by surfaceLayoutStyles (the one layout authority); only bespoke rules
  // live here.
  static styles = [
    surfaceLayoutStyles,
    css`
    /* One scroller, the surface itself, holds the header and the feed: at 600×400 with 200% text a
       fixed header would leave the feed a sliver of the window. */
    :host {
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
    .agent-actions { display: flex; flex-wrap: wrap; gap: var(--jf-space-2); margin-block: var(--jf-space-3); }
    .undo-preview { padding: var(--jf-space-3); border: var(--frame-hairline) solid var(--border-default); }
    .own-reversals ul { margin: 0; padding: 0; list-style: none; }
    .own-reversals li { display: flex; flex-wrap: wrap; align-items: center; gap: var(--jf-space-2); padding-block: var(--jf-space-1); }
  `,
  ];

  override render(): TemplateResult {
    return html`
      <div class="header">
        <!-- Tempdoc 571 §11 / 578: shell topbar owns the page <h1>; <h2> here so this TRUST surface
             is embeddable as a host member (System) without emitting a second <h1>. -->
        <h2>Activity</h2>
        <div class="sub">
          What happened, who did it and how it ended — your actions, the
          assistant's, indexing and the engine's own work, in one list
          updated live. For raw diagnostic output, see Logs.
        </div>
      </div>
      <div class="body">
        ${this.renderOwnReversals()}
        ${listJournalByOriginator('agent').some(e => e.pendingOutcome !== 'rejected') ? html`
          <div class="agent-actions">
            ${previewUndoAllByOriginator('agent').length ? html`<jf-button size="sm" label="Undo all AI actions" data-testid="activity-undo-all"
              .onActivate=${() => { this.undoPreview = previewUndoAllByOriginator('agent');
                if (!this.undoPreview.length) applyEffect({ kind: 'toast', message: 'Nothing to undo', severity: 'info' }); }}>Undo all AI actions</jf-button>` : nothing}
            <jf-button size="sm" label="Save as macro" data-testid="activity-save-macro"
              .onActivate=${() => void this.saveMacro()}>Save as macro</jf-button>
          </div>` : nothing}
        ${this.undoPreview.length ? html`<section class="undo-preview" aria-label="Confirm undo AI actions" data-testid="activity-undo-confirm">
          <p>Undo these ${this.undoPreview.length} AI actions?</p>
          <ul>${this.undoPreview.map(e => html`<li data-testid="activity-undo-row"><jf-effect-line .effect=${e.effect}></jf-effect-line></li>`)}</ul>
          ${listJournalByOriginator('agent').filter(e => e.pendingOutcome !== 'rejected'
            && !this.undoPreview.some(p => p.id === e.id)).length ? html`<p data-testid="activity-irreversible">${listJournalByOriginator('agent').filter(e => e.pendingOutcome !== 'rejected'
              && !this.undoPreview.some(p => p.id === e.id)).length} other actions can't be undone and will remain.</p>` : nothing}
          <jf-button size="sm" variant="danger" label="Undo all" data-testid="activity-undo-confirm-yes" .onActivate=${() => this.confirmUndo()}>Undo all</jf-button>
          <jf-button size="sm" label="Cancel" data-testid="activity-undo-confirm-cancel" .onActivate=${() => { this.undoPreview = []; }}>Cancel</jf-button>
        </section>` : nothing}
        <jf-run-activity api-base=${this.apiBase} data-testid="activity-run-activity"></jf-run-activity>
        <!-- The one feed: ledger, committed operation history and this window's journal, joined. -->
        <div class="section-label">Activity</div>
        <jf-action-ledger
          api-base=${this.apiBase}
          data-testid="activity-surface-action-ledger"
        ></jf-action-ledger>

        <!-- §25.δ2 — the local effect journal with its per-entry actions, selection and archive. -->
        <div class="section-label">Effects recorded in this browser</div>
        <jf-effect-audit-log data-testid="activity-effect-journal"></jf-effect-audit-log>
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-activity-surface')) {
  customElements.define('jf-activity-surface', ActivitySurface);
}
