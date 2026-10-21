// SPDX-License-Identifier: Apache-2.0
/**
 * <jf-ai-activity-digest> — Tempdoc 543 §32 U3 ("What the AI did").
 *
 * Compact surface answering the 2026 "can't tell human from AI" gap for the
 * single user: "Since you last looked, the assistant ran 3 searches, opened
 * 2 documents, …" + N pending proposals + one-tap "Undo all AI actions".
 *
 * Tempdoc 577 §2.14 Root I (#20) — projects the ONE 550 federated ledger (openActionLedgerStream,
 * the same live read-view <jf-action-ledger> renders) since the ONE shared seen-cursor
 * (recallCursor), plus the PendingEffect queue (agent-originated proposals). Before this it read the
 * FE Effect Journal alone behind a private localStorage cursor — a different authority from the
 * timeline (the 553 drift). The undo/macro paths still read the journal (the undo authority); this
 * owns only the count/summary. "Mark as seen" advances the shared cursor so EVERY recall surface
 * highlights only NEW agent activity. Collapses (data-empty) when nothing is new and nothing is
 * pending — zero surface area in user-only sessions.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import './Button.js';
import {
  subscribeJournal,
  undoAllByOriginator,
  previewUndoAllByOriginator,
  listJournalByOriginator,
  type JournalEntry,
} from '../substrates/effects/index.js';
// 543-fwd UPDATE 10 P1 — shared effect-presentation primitive (replaces a bare
// describeEffect label in the undo-confirm preview). EffectLine routes its label
// through describeEffect — the same authority present({kind:'effect'}) wraps.
import './EffectLine.js';
import { applyEffect } from '../substrates/actions/index.js';
// 543-fwd #9 — one-tap "save what the assistant just did as a macro" (teach-mode).
import { defineMacroFromEffects } from '../substrates/macros/index.js';
import {
  listPending,
  subscribePending,
} from '../substrates/pending-effects/index.js';
// Tempdoc 577 §2.14 Root I (#20) — the digest now projects the ONE 550 federated ledger (not a
// second read of the FE Effect Journal), since the ONE shared seen-cursor (not a private one).
import {
  openActionLedgerStream,
  type UnifiedActionEntry,
} from '../operations/ActionLedgerClient.js';
import type { MultiplexedStream } from '../streaming/MultiplexedStream.js';
import {
  summarizeAgentRecall,
  type AgentRecallDigest,
} from '../substrates/recall/agentRecall.js';
import {
  getSeenCursor,
  markSeen,
  subscribeSeenCursor,
} from '../substrates/recall/recallCursor.js';

/**
 * Tempdoc 806 B.2 (round-12 F4 / round-7 B6-8) — how long the digest stays on screen for ONE piece
 * of activity before it collapses. Long enough to read a sentence and reach "Undo all AI actions";
 * short enough that it cannot become the ~10-minute header occlusion round 12 recorded.
 */
export const AUTO_HIDE_MS = 20_000;

export class AiActivityDigest extends JfElement {
  static properties = {
    apiBase: { type: String, attribute: 'api-base' },
    // Tempdoc 662: the shared MultiplexedStream (mirrors AdvisoryToastHost's `store` property
    // shape — an object, not an attribute-able primitive).
    multiplex: { attribute: false },
    digest: { state: true },
    pendingAgent: { state: true },
    // Tempdoc 806 B.2 — see AUTO_HIDE_MS. Reactive so expiry re-renders the collapsed state.
    autoHidden: { state: true },
    // 543-fwd #8 — entries staged for the mass-undo confirm preview; empty = not confirming.
    undoPreview: { state: true },
  };

  declare apiBase: string;
  declare multiplex?: MultiplexedStream;
  declare digest: AgentRecallDigest;
  declare pendingAgent: number;
  declare undoPreview: ReadonlyArray<JournalEntry>;
  declare autoHidden: boolean;

  /** Injected only by tests; production uses the real EventSource via openActionLedgerStream. */
  eventSourceFactory?: (url: string) => EventSource;

  // Tempdoc 577 §2.14 Root I (#20) — the latest unified-ledger rows (the ONE log), held so the
  // digest projects from the same stream History/Timeline render, not the FE journal alone.
  private entries: ReadonlyArray<UnifiedActionEntry> = [];
  private autoHideTimer: ReturnType<typeof setTimeout> | null = null;
  /** The digest content the current auto-hide window was armed for; a change re-shows the digest. */
  private shownFor = '';
  private stopStream: (() => void) | null = null;
  private unsubJournal: (() => void) | null = null;
  private unsubPending: (() => void) | null = null;
  private unsubSeenChanged: (() => void) | null = null;

  constructor() {
    super();
    this.apiBase = '';
    this.digest = summarizeAgentRecall(this.entries, getSeenCursor());
    this.pendingAgent = this.countAgentPending();
    this.undoPreview = [];
    this.autoHidden = false;
  }

  connectedCallback(): void {
    super.connectedCallback();
    // The ONE live read path (same as ActionLedgerView): the stream delivers the snapshot frame +
    // every subsequent row, re-folding the FE Effect Journal each change — so the digest's count is
    // a projection of the same unified log the timeline renders (550 thesis I / 577 #20). This is
    // ALWAYS-MOUNTED chrome, so it degrades when fetch is unavailable (non-browser test env /
    // SSR) exactly as startEffectIngest guards a missing fetch — the digest just stays empty.
    const canStream =
      this.multiplex !== undefined ||
      this.eventSourceFactory !== undefined ||
      // eslint-disable-next-line no-restricted-globals -- Availability probe; requests use authorizedFetch.
      typeof fetch !== 'undefined';
    this.stopStream = canStream
      ? openActionLedgerStream({
          apiBase: this.apiBase,
          ...(this.multiplex ? { multiplex: this.multiplex } : {}),
          ...(this.eventSourceFactory ? { eventSourceFactory: this.eventSourceFactory } : {}),
          onActivity: (rows) => {
            this.entries = rows;
            this.refresh();
          },
        })
      : null;
    // The undo/macro paths and the pending-proposal count still read the journal (the undo
    // authority); a journal/pending change refreshes the pending badge. A cursor advance (mark-seen,
    // possibly from another recall surface) re-projects "what's new".
    this.unsubJournal = subscribeJournal(() => this.refresh());
    this.unsubPending = subscribePending(() => this.refresh());
    this.unsubSeenChanged = subscribeSeenCursor(() => this.refresh());
    this.refresh();
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this.clearAutoHideTimer();
    this.stopStream?.();
    this.stopStream = null;
    this.unsubJournal?.();
    this.unsubJournal = null;
    this.unsubPending?.();
    this.unsubPending = null;
    this.unsubSeenChanged?.();
    this.unsubSeenChanged = null;
  }

  private countAgentPending(): number {
    return listPending().filter((p) => p.originator === 'agent').length;
  }

  private refresh(): void {
    this.digest = summarizeAgentRecall(this.entries, getSeenCursor());
    this.pendingAgent = this.countAgentPending();
    // Tempdoc 806 B.2 — an auto-hide is about THIS content having had its turn on screen. When the
    // assistant does something new, that is new information and the digest earns a fresh window.
    const signature = `${this.digest.total}|${this.digest.latestIso}|${this.pendingAgent}`;
    if (signature !== this.shownFor) {
      this.shownFor = signature;
      this.autoHidden = false;
      this.clearAutoHideTimer();
    }
  }

  static styles = css`
    :host {
      /* 559 Authority I: placement owned by the OverlayHost top-right slot. */
      max-width: 30rem;
      pointer-events: none;
    }
    :host([data-empty]) {
      display: none;
    }
    .digest {
      pointer-events: auto;
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.5rem;
      background: var(--surface-1);
      border: 1px solid var(--border-default);
      border-left: 3px solid var(--accent-info);
      border-radius: 0.5rem;
      padding: 0.5rem 0.75rem;
      font-size: var(--font-size-sm);
      color: var(--text-primary);
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
    }
    .label {
      color: var(--text-secondary);
    }
    .summary {
      font-weight: 600;
    }
    .pending {
      font-size: var(--font-size-xs);
      text-transform: uppercase;
      letter-spacing: 0.05em;
      padding: 0.125rem 0.5rem;
      border-radius: 9999px;
      background: var(--accent-warning);
      color: var(--accent-on-warning);
      font-weight: 600;
    }
    .actions {
      display: flex;
      gap: 0.5rem;
      margin-left: auto;
    }
    /* 574 B (remediation) — the digest actions are jf-button(sm) atoms; undo-confirm-yes is
       variant="danger". The bespoke button/.danger fork is deleted. */
    /* 543-fwd #8 — mass-undo confirm preview. */
    .confirm {
      pointer-events: auto;
      flex-basis: 100%;
      margin-top: 0.5rem;
      border-top: 1px solid var(--border-subtle);
      padding-top: 0.5rem;
    }
    .confirm-title {
      font-size: var(--font-size-xs);
      font-weight: 600;
      margin-bottom: 0.375rem;
    }
    .confirm-list {
      list-style: none;
      margin: 0 0 0.5rem;
      padding: 0;
      max-height: 9rem;
      overflow-y: auto;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .confirm-list li {
      padding: 0.125rem 0;
    }
    /* Tempdoc 577 §2.14 Root III (#16) — the irreversible-remainder honesty line. */
    .confirm-irreversible {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-bottom: 0.5rem;
    }
    .confirm-actions {
      display: flex;
      gap: 0.5rem;
    }
  `;

  updated(): void {
    const empty = this.digest.total === 0 && this.pendingAgent === 0;
    // Tempdoc 806 B.2 — a digest that never expires is not chrome, it is occlusion: round 12 had it
    // covering the header for ~10 minutes across every surface, and round 7's B6-8 already asked for
    // a bounded lifetime (798 line 974: "Toasts also do not auto-dismiss"). Hidden here means hidden
    // ONLY — the shared seen-cursor is untouched, so nothing is marked read on the user's behalf and
    // every recall surface still shows the same activity. New activity re-shows it (see `shownFor`).
    if (empty || this.autoHidden) this.setAttribute('data-empty', '');
    else this.removeAttribute('data-empty');
    this.armAutoHide(empty);
  }

  /**
   * Arms (or re-arms) the auto-hide window. Held open while the mass-undo confirm preview is
   * staged — collapsing a dialog the user is reading would be a worse defect than the one this
   * fixes.
   */
  private armAutoHide(empty: boolean): void {
    if (empty || this.autoHidden || this.undoPreview.length > 0) {
      this.clearAutoHideTimer();
      return;
    }
    if (this.autoHideTimer !== null) return;
    this.autoHideTimer = setTimeout(() => {
      this.autoHideTimer = null;
      // Re-check: a confirm preview may have opened inside the window.
      if (this.undoPreview.length === 0) this.autoHidden = true;
    }, AUTO_HIDE_MS);
  }

  private clearAutoHideTimer(): void {
    if (this.autoHideTimer !== null) {
      clearTimeout(this.autoHideTimer);
      this.autoHideTimer = null;
    }
  }

  // 543-fwd #8 — first click stages a confirm preview instead of undoing
  // immediately. The user sees exactly which actions would be reversed.
  private handleUndoAllClick(): void {
    const preview = previewUndoAllByOriginator('agent');
    if (preview.length === 0) {
      applyEffect({ kind: 'toast', message: 'Nothing to undo', severity: 'info' });
      return;
    }
    this.undoPreview = preview;
  }

  private cancelUndoAll(): void {
    this.undoPreview = [];
  }

  // 543-fwd #4 + #8 — commit the confirmed mass-undo and surface the count.
  private confirmUndoAll(): void {
    this.undoPreview = [];
    const count = undoAllByOriginator('agent', (e) => {
      applyEffect(e);
    });
    applyEffect({
      kind: 'toast',
      message:
        count === 0
          ? 'Nothing to undo'
          : `Undid ${count} AI action${count === 1 ? '' : 's'}`,
      severity: 'info',
    });
    this.refresh();
  }

  private handleMarkSeen(): void {
    // Tempdoc 577 #20 — advance the ONE shared seen-cursor; subscribeSeenCursor re-projects this
    // digest (and any other recall surface) off the new mark. No private cursor remains.
    markSeen(this.digest.latestIso);
  }

  // 543-fwd #9 — one-tap "save what the assistant just did as a macro". Collects
  // the agent's journaled actions since the shared seen-cursor (577 #20: the journal entry's
  // ISO `invokedAt` compared to the one cursor — agent-originated, not vetoed) and opens the
  // shared interactive author. Replay re-runs the ops, guarded by the macro dry-run /
  // allowBackendReplay path. Demonstration-as-authoring.
  private async handleSaveAsMacro(): Promise<void> {
    const since = getSeenCursor();
    const effects = listJournalByOriginator('agent')
      .filter((e) => e.invokedAt > since && e.pendingOutcome !== 'rejected')
      .map((e) => e.effect);
    if (effects.length === 0) return;
    await defineMacroFromEffects(effects, {
      description: `Save what the assistant just did (${effects.length} action${effects.length === 1 ? '' : 's'}) as a reusable macro. It will surface in the command palette under "Macros".`,
    });
  }

  private summaryText(): string {
    const parts = Object.entries(this.digest.byKind).map(
      ([kind, n]) => `${n} ${kind}${n === 1 ? '' : 's'}`,
    );
    return parts.length ? parts.join(', ') : 'no completed actions';
  }

  render(): TemplateResult {
    return html`
      <div class="digest" data-testid="ai-digest">
        <span class="label">Since you last looked, the assistant:</span>
        <span class="summary" data-testid="ai-digest-summary"
          >${this.summaryText()}</span
        >
        ${this.pendingAgent > 0
          ? html`<span class="pending" data-testid="ai-digest-pending"
              >${this.pendingAgent} pending</span
            >`
          : nothing}
        <span class="actions">
          ${this.digest.total > 0
            ? html`<jf-button
                  size="sm"
                  data-testid="ai-digest-undo-all"
                  label="Undo all AI actions"
                  .onActivate=${() => this.handleUndoAllClick()}
                >
                  Undo all AI actions
                </jf-button>
                <jf-button
                  size="sm"
                  data-testid="ai-digest-save-macro"
                  label="Save as macro"
                  title="Save what the assistant just did as a reusable macro"
                  .onActivate=${() => void this.handleSaveAsMacro()}
                >
                  Save as macro
                </jf-button>`
            : nothing}
          <jf-button
            size="sm"
            data-testid="ai-digest-mark-seen"
            label="Mark as seen"
            .onActivate=${() => this.handleMarkSeen()}
          >
            Mark as seen
          </jf-button>
        </span>
        ${this.undoPreview.length > 0 ? this.renderUndoConfirm() : nothing}
      </div>
    `;
  }

  /**
   * Tempdoc 577 §2.14 Root III (#16) — reversibility honesty: how many of the agent's (non-vetoed)
   * actions CANNOT be undone, so the bulk control is honest that "undo all" is partial — it never
   * silently leaves irreversible actions standing without saying so. An action is irreversible when
   * the journal could derive no inverse (`inverse === null`).
   */
  private irreversibleAgentCount(): number {
    const acted = listJournalByOriginator('agent').filter(
      (e) => e.pendingOutcome !== 'rejected',
    );
    return acted.filter((e) => e.inverse === null).length;
  }

  // 543-fwd #8 — the confirm step: list exactly what would be reversed.
  private renderUndoConfirm(): TemplateResult {
    const irreversible = this.irreversibleAgentCount();
    return html`
      <div class="confirm" data-testid="ai-digest-undo-confirm">
        <div class="confirm-title">
          Undo these ${this.undoPreview.length} AI
          action${this.undoPreview.length === 1 ? '' : 's'}?
        </div>
        <ul class="confirm-list">
          ${this.undoPreview.map(
            (e) => html`<li data-testid="ai-digest-undo-row">
              <jf-effect-line .effect=${e.effect}></jf-effect-line>
            </li>`,
          )}
        </ul>
        ${/* Tempdoc 577 §2.14 Root III (#16) — name the irreversible remainder so "undo all" is
              honestly partial, never silently leaving non-invertible actions standing. */ ''}
        ${irreversible > 0
          ? html`<div class="confirm-irreversible" data-testid="ai-digest-irreversible">
              ${irreversible} other action${irreversible === 1 ? '' : 's'} can't be undone and
              will remain.
            </div>`
          : nothing}
        <div class="confirm-actions">
          <jf-button
            size="sm"
            variant="danger"
            data-testid="ai-digest-undo-confirm-yes"
            label="Undo all"
            .onActivate=${() => this.confirmUndoAll()}
          >
            Undo all
          </jf-button>
          <jf-button
            size="sm"
            data-testid="ai-digest-undo-confirm-cancel"
            label="Cancel"
            .onActivate=${() => this.cancelUndoAll()}
          >
            Cancel
          </jf-button>
        </div>
      </div>
    `;
  }
}

if (!customElements.get('jf-ai-activity-digest')) {
  customElements.define('jf-ai-activity-digest', AiActivityDigest);
}
