// SPDX-License-Identifier: Apache-2.0
/**
 * <jf-effect-audit-log> — Tempdoc 543 §25.δ2.
 *
 * Per-action audit log surface. Reads from the Effect Journal and
 * renders a filterable, scrollable list. Filter dimensions:
 *   - originator: all | user | agent | system
 *   - outcome: all | normal | accepted | rejected
 *
 * Hidden by default; opens via the "core.action.shell.show-audit-log"
 * Action (registered in Shell.ts boot). Closes via Escape or the X
 * button. Subscribes to journal appends so new entries surface live.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import './Button.js';
import './StatusBadge.js';
import { ModalController } from '../primitives/modalController.js';
// Tempdoc 613 §6 — the in-control RECEIPT surface (export-to-clipboard ack is a receipt, not a toast).
import { ReceiptController } from '../primitives/receiptController.js';
import {
  listJournal,
  subscribeJournal,
  undoLastEffectByOriginator,
  undoEntries,
  listJournalByOriginator,
  peekLastUndoableByOriginator,
  previewUndoAllByOriginator,
  getUndoableOperation,
  getCausationChain,
  previewUndoToEntry,
  exportJournalArchive,
  importJournalArchive,
  getGroupRoot,
  previewUndoGroup,
  type JournalEntry,
  type EffectOriginator,
} from '../substrates/effects/index.js';
import { present } from '../display/present.js';
import { unavailableBecause } from '../state/availability.js';
// 543-fwd #6 — fold the flat journal into causation-rooted turns for display.
import { groupJournalForDisplay, type DisplayTurn } from '../substrates/effects/journalView.js';
// 543-fwd UPDATE 10 P1 — the shared effect-presentation primitive (label + relative
// time + structured detail) replaces this row's bare kind + raw-JSON payload.
import './EffectLine.js';
// §28.W4 — dispatch inverse effects through the kernel.
import { applyEffect } from '../substrates/actions/index.js';
// 543-fwd #19 — import archive → define a replayable macro (interactive prompt).
import { elicit } from '../substrates/elicit/index.js';
import { defineMacro } from '../substrates/macros/index.js';
// §28.W5 — macro authoring + elicit for the name prompt.
import { defineMacroFromEffects } from '../substrates/macros/index.js';

type OriginatorFilter = 'all' | EffectOriginator;
type OutcomeFilter = 'all' | 'normal' | 'accepted' | 'rejected';

export class EffectAuditLog extends JfElement {
  static properties = {
    open: { type: Boolean, reflect: true },
    originator: { state: true },
    outcome: { state: true },
    entries: { state: true },
    // §28.W5 — selected entry ids for "save as macro".
    selectedIds: { state: true },
    // 543-fwd #8 — entries staged for the mass-undo confirm; empty = not confirming.
    undoAllPreview: { state: true },
    // 543-fwd #6 — entry id whose "why did this happen" causation chain is expanded.
    tracedId: { state: true },
    // 543-fwd #5 — time-travel: target entry id + the entries a restore would reverse.
    restoreTargetId: { state: true },
    restorePreview: { state: true },
    // 543-fwd #7 — grouped undo: causation-root + the turn's entries to reverse.
    groupRootId: { state: true },
    groupPreview: { state: true },
    // 543-fwd #6 — turn rootIds the user has collapsed (members hidden). Default expanded.
    collapsedTurns: { state: true },
  };

  declare open: boolean;
  declare originator: OriginatorFilter;
  declare outcome: OutcomeFilter;
  declare entries: ReadonlyArray<JournalEntry>;
  declare selectedIds: ReadonlySet<number>;
  declare undoAllPreview: ReadonlyArray<JournalEntry>;
  declare tracedId: number | null;
  declare restoreTargetId: number | null;
  declare restorePreview: ReadonlyArray<JournalEntry>;
  declare groupRootId: number | null;
  declare groupPreview: ReadonlyArray<JournalEntry>;
  declare collapsedTurns: ReadonlySet<number>;

  private unsubJournal: (() => void) | null = null;

  constructor() {
    super();
    this.open = false;
    this.originator = 'all';
    this.outcome = 'all';
    this.entries = [];
    this.selectedIds = new Set<number>();
    this.undoAllPreview = [];
    this.tracedId = null;
    this.restoreTargetId = null;
    this.restorePreview = [];
    this.groupRootId = null;
    this.groupPreview = [];
    this.collapsedTurns = new Set<number>();
  }

  // 543-fwd #6 — collapse/expand a turn's member rows (default expanded).
  private toggleTurnCollapse(rootId: number): void {
    const next = new Set(this.collapsedTurns);
    if (next.has(rootId)) next.delete(rootId);
    else next.add(rootId);
    this.collapsedTurns = next;
  }

  // 543-fwd #7 — stage a confirm to undo a whole agent turn (the causation group
  // this entry belongs to), reversing its reversible/compensable members atomically.
  private handleUndoTurn(id: number): void {
    const root = getGroupRoot(id);
    if (root === null) return;
    const preview = previewUndoGroup(root);
    if (preview.length === 0) {
      applyEffect({ kind: 'toast', message: 'Nothing reversible in this turn', severity: 'info' });
      return;
    }
    this.groupRootId = root;
    this.groupPreview = preview;
  }

  private cancelUndoTurn(): void {
    this.groupRootId = null;
    this.groupPreview = [];
  }

  private confirmUndoTurn(): void {
    const previewed = this.groupPreview;
    this.groupRootId = null;
    this.groupPreview = [];
    if (!previewed.length) return;
    // DE6 — exactly the previewed turn members, nothing that joined the turn since.
    const { reversed, skipped } = undoEntries(previewed, (effect) => applyEffect(effect));
    this.reportUndone(reversed, skipped.length, ' in this turn');
  }

  /** The outcome of a confirmed undo preview, including what could no longer be undone. */
  private reportUndone(reversed: number, skipped: number, scope = ''): void {
    applyEffect({
      kind: 'toast',
      message: `${reversed === 0 ? 'Nothing was undone' : `Reverted ${reversed} action${reversed === 1 ? '' : 's'}${scope}`}${skipped
        ? `; ${skipped} could no longer be undone` : ''}`,
      severity: skipped ? 'warning' : 'info',
    });
  }

  /** DE6 — the entries in a preview's scope that will NOT be reversed, said before the person commits. */
  private irreversibleNote(count: number): TemplateResult | typeof nothing {
    return count > 0 ? html`<p class="confirm-note" data-testid="undo-irreversible">${count} other
      action${count === 1 ? '' : 's'} can't be undone and will remain.</p>` : nothing;
  }

  // 543-fwd #6 — toggle the bottom-up "why did this happen" causation trace.
  private toggleTrace(id: number): void {
    this.tracedId = this.tracedId === id ? null : id;
  }

  // 543-fwd #5 — time-travel: stage a confirm listing everything a "restore to
  // here" would reverse (reversible/compensable entries after this one).
  private handleRestoreToHere(id: number): void {
    const preview = previewUndoToEntry(id);
    if (preview.length === 0) {
      applyEffect({ kind: 'toast', message: 'Nothing to undo after this point', severity: 'info' });
      return;
    }
    this.restoreTargetId = id;
    this.restorePreview = preview;
  }

  private cancelRestore(): void {
    this.restoreTargetId = null;
    this.restorePreview = [];
  }

  private confirmRestore(): void {
    const previewed = this.restorePreview;
    this.restoreTargetId = null;
    this.restorePreview = [];
    if (!previewed.length) return;
    // DE6 — exactly the previewed entries, nothing recorded after the preview was drawn.
    const { reversed, skipped } = undoEntries(previewed, (effect) => applyEffect(effect));
    this.reportUndone(reversed, skipped.length);
  }

  // §28.W4 — undo the last reversible effect from a given originator.
  // The button "Undo last AI action" wires through here.
  // 543-fwd #4 — peek first so the confirmation toast can NAME what was undone.
  private handleUndoLastByOriginator(originator: EffectOriginator): void {
    const target = peekLastUndoableByOriginator(originator);
    const inverse = undoLastEffectByOriginator(originator, (effect) => {
      applyEffect(effect);
    });
    if (inverse && target) {
      applyEffect({
        kind: 'toast',
        message: `Undid: ${present({ kind: 'effect', effect: target.effect }).label}`,
      });
    } else {
      applyEffect({ kind: 'toast', message: 'Nothing to undo', severity: 'info' });
    }
  }

  // §28.W4 — undo ALL reversible effects from a given originator.
  // 543-fwd #8 — first click stages a confirm preview; nothing is undone yet.
  private handleUndoAllByOriginator(originator: EffectOriginator): void {
    const preview = previewUndoAllByOriginator(originator);
    if (preview.length === 0) {
      applyEffect({ kind: 'toast', message: 'Nothing to undo', severity: 'info' });
      return;
    }
    this.undoAllPreview = preview;
  }

  private cancelUndoAll(): void {
    this.undoAllPreview = [];
  }

  // 543-fwd #4 + #8 — commit the confirmed mass-undo + count toast.
  private confirmUndoAll(): void {
    const previewed = this.undoAllPreview;
    this.undoAllPreview = [];
    if (!previewed.length) return;
    // DE6 — exactly the previewed AI actions; a repeated click finds the preview gone and does nothing.
    const { reversed, skipped } = undoEntries(previewed, (effect) => applyEffect(effect));
    applyEffect({
      kind: 'toast',
      message: `${reversed === 0 ? 'Nothing was undone' : `Undid ${reversed} AI action${reversed === 1 ? '' : 's'}`}${skipped.length
        ? `; ${skipped.length} could no longer be undone` : ''}`,
      severity: skipped.length ? 'warning' : 'info',
    });
  }

  // §28.W5 — toggle one entry's membership in the selection set.
  private toggleSelection(id: number): void {
    const next = new Set(this.selectedIds);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    this.selectedIds = next;
  }

  // §28.W5 — "Save selected as macro" — prompts via elicit for a name,
  // calls defineMacro with the selected entries' effects in journal-order.
  private async handleSaveAsMacro(): Promise<void> {
    if (this.selectedIds.size === 0) return;
    const orderedEffects = this.entries
      .filter((e) => this.selectedIds.has(e.id))
      .map((e) => e.effect);
    // 543-fwd #9 — shared interactive author (elicit name → defineMacro).
    const macro = await defineMacroFromEffects(orderedEffects);
    if (macro) this.selectedIds = new Set<number>(); // clear selection on success
  }

  // 543-fwd #19 — export the selected entries as a portable JSON archive (copied
  // to the clipboard). A no-sync-infra way to move/share a session's actions.
  private handleExportArchive(): void {
    const entries = this.entries.filter((e) => this.selectedIds.has(e.id));
    if (entries.length === 0) return;
    const json = exportJournalArchive(entries);
    // Tempdoc 613 §6 — copy WITHOUT a window toast; the confirmation is a RECEIPT flashed in the
    // Export button (`routePushSurface('at-control') === 'receipt'`; a copy ack is not history).
    applyEffect({ kind: 'copy-to-clipboard', text: json });
    this.exportReceipt.flash(
      `Exported ${entries.length} entr${entries.length === 1 ? 'y' : 'ies'}`,
    );
  }

  // 543-fwd #19 — paste a previously-exported archive and replay it: parse the
  // effects and materialize a reusable macro (the macro engine is the replay
  // mechanism). Reuses the now-working elicit text input.
  private async handleImportArchive(): Promise<void> {
    const form = await elicit({
      title: 'Import journal archive',
      description: 'Paste a journal archive (JSON) to replay it as a reusable macro.',
      schema: {
        type: 'object',
        properties: {
          // 543-fwd #2 — a pasted archive is long; render it as a multiline textarea.
          // `x-ui` is a JSON Schema extension keyword (read by defaultUiSchemaFor at
          // runtime); cast past the JsonSchema type which omits extension keywords.
          json: { type: 'string', title: 'Archive JSON', 'x-ui': { multi: true } } as {
            type: 'string';
            title: string;
          },
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

  connectedCallback(): void {
    super.connectedCallback();
    this.entries = listJournal();
    if (!this.unsubJournal) {
      this.unsubJournal = subscribeJournal(() => {
        this.entries = listJournal();
      });
    }
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsubJournal?.();
    this.unsubJournal = null;
  }

  /** The FULL modal contract (574 §22.G) — native <dialog> + scroll-lock + focus-restore, atomic by construction. */
  private readonly modal = new ModalController(this, {
    dialog: () => this.shadowRoot?.querySelector('dialog'),
  });

  /** Tempdoc 613 §6 — the export confirmation is a RECEIPT (a direct copy-to-clipboard ack), flashed
   *  in the Export button, NOT a window toast. */
  private readonly exportReceipt = new ReceiptController(this);

  /** Drive the native <dialog> from the reactive `open` flag (full contract by construction). */
  override updated(changed: Map<string, unknown>): void {
    if (changed.has('open')) {
      if (this.open) this.modal.open();
      else this.modal.close();
    }
  }

  static styles = css`
    /* 543-fwd UPDATE 10 P3 — native <dialog> (showModal) as a right-side drawer:
       focus management, Escape, ::backdrop, aria-modal for free. #12 — width clamps
       so it never overflows a narrow viewport. */
    :host {
      display: contents;
    }
    dialog {
      position: fixed;
      inset: 4rem 1rem 4rem auto;
      margin: 0;
      width: min(32rem, calc(100vw - 2rem));
      max-height: none;
      padding: 0;
      background: var(--surface-1);
      border: 1px solid var(--border-default);
      border-radius: 0.5rem;
      color: var(--text-primary);
      box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
    }
    dialog::backdrop {
      background: rgba(0, 0, 0, 0.4);
    }
    .panel {
      display: flex;
      flex-direction: column;
      max-height: 100%;
    }
    header {
      padding: 0.75rem 1rem;
      border-bottom: 1px solid var(--border-default);
      display: flex;
      align-items: center;
      justify-content: space-between;
    }
    header h2 {
      margin: 0;
      font-size: var(--font-size-md);
      font-weight: 600;
    }
    .filters {
      padding: 0.5rem 1rem;
      display: flex;
      gap: 1rem;
      flex-wrap: wrap;
      border-bottom: 1px solid var(--border-subtle);
      font-size: var(--font-size-xs);
    }
    .filters label {
      display: flex;
      gap: 0.375rem;
      align-items: center;
      color: var(--text-secondary);
    }
    select {
      background: var(--surface-2);
      color: inherit;
      border: 1px solid var(--border-default);
      border-radius: 0.25rem;
      padding: 0.125rem 0.375rem;
      font: inherit;
      font-size: var(--font-size-xs);
    }
    .list {
      flex: 1;
      overflow-y: auto;
      padding: 0.5rem;
    }
    .entry {
      padding: 0.5rem 0.75rem;
      border-radius: 0.25rem;
      margin-bottom: 0.25rem;
      background: var(--surface-2);
      font-size: var(--font-size-sm);
    }
    .entry-head {
      display: flex;
      gap: 0.5rem;
      align-items: flex-start;
      margin-bottom: 0.25rem;
    }
    /* 543-fwd UPDATE 10 P1 — the effect content (label + time + structured detail)
       is owned by <jf-effect-line>; the row composes chrome around it. */
    .grow {
      flex: 1;
      min-width: 0;
    }
    .actions {
      display: flex;
      gap: 0.375rem;
      align-items: center;
      flex-shrink: 0;
    }
    button:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    /* 543-fwd #6 — causation turn grouping. */
    .turn {
      margin-bottom: 0.25rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      overflow: hidden;
    }
    .turn-head {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      width: 100%;
      padding: 0.375rem 0.5rem;
      background: var(--surface-3);
      border: none;
      color: var(--text-secondary);
      font: inherit;
      font-size: var(--font-size-xs);
      cursor: pointer;
      text-align: left;
    }
    .turn-head:hover {
      background: var(--surface-2);
    }
    .turn-caret {
      width: 0.75rem;
      flex-shrink: 0;
    }
    .turn-label {
      font-weight: 600;
    }
    .turn-body {
      padding: 0.25rem;
    }
    /* 543-fwd #6 — clickable causation chip + the expanded "why" trace. */
    button.chip-trace {
      font-family: inherit;
      font-size: var(--font-size-xs);
      padding: 0.0625rem 0.375rem;
      border-radius: 9999px;
      background: var(--surface-3);
      border: none;
      cursor: pointer;
      color: var(--text-secondary);
    }
    button.chip-trace:hover {
      background: var(--surface-3);
    }
    .trace {
      margin-top: 0.375rem;
      padding: 0.5rem 0.625rem;
      border-left: 2px solid var(--accent-info);
      background: var(--surface-3);
      border-radius: 0.25rem;
    }
    .trace-title {
      font-size: var(--font-size-xs);
      font-weight: 600;
      color: var(--text-secondary);
      margin-bottom: 0.25rem;
    }
    .trace-chain {
      list-style: none;
      margin: 0;
      padding: 0;
      font-size: var(--font-size-xs);
    }
    .trace-chain li {
      padding: 0.125rem 0;
    }
    .trace-step-n {
      font-family: var(--font-mono);
      color: var(--text-tertiary);
      margin-right: 0.375rem;
    }
    /* 574 B1 — the per-entry restore/undo-turn/undo-op + the close + confirm-bar buttons are
       jf-button atoms now (size="sm"/ghost-icon/danger); their per-surface skins are deleted.
       The bare action buttons (undo last/all, save/export/import) were browser-default-styled —
       jf-button gives them proper token styling. .chip-trace + .turn-head stay (trace toggle +
       turn disclosure are bespoke affordances, not the action-button base). */
    .empty {
      padding: 2rem 1rem;
      text-align: center;
      color: var(--text-tertiary);
      font-size: var(--font-size-sm);
    }
    /* 543-fwd #8 — mass-undo confirm bar. */
    .undo-all-confirm {
      padding: 0.5rem 1rem;
      border-bottom: 1px solid var(--border-subtle);
      background: var(--surface-2);
    }
    .confirm-title {
      font-size: var(--font-size-sm);
      font-weight: 600;
      margin-bottom: 0.375rem;
    }
    .confirm-list {
      list-style: none;
      margin: 0 0 0.5rem;
      padding: 0;
      max-height: 8rem;
      overflow-y: auto;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .confirm-list li {
      padding: 0.125rem 0;
    }
    .confirm-actions {
      display: flex;
      gap: 0.5rem;
    }
  `;

  private filteredEntries(): ReadonlyArray<JournalEntry> {
    return this.entries.filter((e) => {
      if (this.originator !== 'all' && e.originator !== this.originator) {
        return false;
      }
      if (this.outcome === 'normal' && e.pendingOutcome !== undefined) return false;
      if (this.outcome === 'accepted' && e.pendingOutcome !== 'accepted') return false;
      if (this.outcome === 'rejected' && e.pendingOutcome !== 'rejected') return false;
      return true;
    });
  }

  render(): TemplateResult {
    const filtered = this.filteredEntries();
    // 543-fwd #3 — the AI-undo buttons are actionable only when reversible agent
    // entries exist; otherwise they're a no-op dead-end. Mirror save/export gating.
    const agentUndoableCount = previewUndoAllByOriginator('agent').length;
    return html`
      <dialog
        data-testid="effect-audit-dialog"
        aria-label="Effect Journal"
        @close=${() => (this.open = false)}
        @keydown=${(e: KeyboardEvent) => {
          // Sync `open` on Escape directly — some environments don't fire the
          // dialog `close` event, which would otherwise leave us desynced.
          if (e.key === 'Escape') this.open = false;
        }}
        @click=${(e: MouseEvent) => {
          if (e.target === e.currentTarget) this.open = false;
        }}
      >
        <div class="panel">
        <header>
          <h2>Effect Journal</h2>
          <jf-button
            class="close"
            variant="ghost"
            size="icon"
            label="Close"
            .onActivate=${() => (this.open = false)}
          >
            ✕
          </jf-button>
        </header>
        <div class="filters">
          <label>
            originator
            <select
              .value=${this.originator}
              @change=${(e: Event) =>
                (this.originator = (e.target as HTMLSelectElement)
                  .value as OriginatorFilter)}
            >
              <option value="all">all</option>
              <option value="user">user</option>
              <option value="agent">agent</option>
              <option value="system">system</option>
            </select>
          </label>
          <label>
            outcome
            <select
              .value=${this.outcome}
              @change=${(e: Event) =>
                (this.outcome = (e.target as HTMLSelectElement)
                  .value as OutcomeFilter)}
            >
              <option value="all">all</option>
              <option value="normal">normal</option>
              <option value="accepted">accepted</option>
              <option value="rejected">rejected</option>
            </select>
          </label>
          <span style="margin-left:auto;color:var(--text-tertiary)">
            ${filtered.length} / ${this.entries.length}
          </span>
        </div>
        <div class="filters" style="border-top:1px solid var(--border-subtle);">
          <jf-button
            size="sm"
            data-testid="undo-last-agent"
            label="Undo last AI action"
            .availability=${agentUndoableCount === 0
              ? unavailableBecause('No reversible AI actions to undo')
              : undefined}
            .onActivate=${() => this.handleUndoLastByOriginator('agent')}
          >Undo last AI action</jf-button>
          <jf-button
            size="sm"
            data-testid="undo-all-agent"
            .label=${`Undo all AI actions${agentUndoableCount > 0 ? ` (${agentUndoableCount})` : ''}`}
            .availability=${agentUndoableCount === 0
              ? unavailableBecause('No reversible AI actions to undo')
              : undefined}
            .onActivate=${() => this.handleUndoAllByOriginator('agent')}
          >Undo all AI actions${agentUndoableCount > 0 ? ` (${agentUndoableCount})` : ''}</jf-button>
          <jf-button
            size="sm"
            data-testid="save-as-macro"
            .label=${`Save selected (${this.selectedIds.size}) as macro`}
            .availability=${this.selectedIds.size === 0
              ? unavailableBecause('Select one or more entries to save as a macro')
              : undefined}
            .onActivate=${() => void this.handleSaveAsMacro()}
          >Save selected (${this.selectedIds.size}) as macro</jf-button>
          <jf-button
            size="sm"
            data-testid="export-archive"
            .label=${this.exportReceipt.active
              ? `✓ ${this.exportReceipt.active.message}`
              : `Export selected (${this.selectedIds.size})`}
            .availability=${this.selectedIds.size === 0
              ? unavailableBecause('Select one or more entries to export')
              : undefined}
            .onActivate=${() => this.handleExportArchive()}
            >${this.exportReceipt.active
              ? `✓ ${this.exportReceipt.active.message}`
              : `Export selected (${this.selectedIds.size})`}</jf-button
          >
          <jf-button
            size="sm"
            data-testid="import-archive"
            label="Import archive"
            .onActivate=${() => void this.handleImportArchive()}
            title="543-fwd #19 — paste a journal archive to replay it as a macro"
          >Import archive</jf-button>
        </div>
        ${this.undoAllPreview.length > 0 ? this.renderUndoAllConfirm() : nothing}
        ${this.restoreTargetId !== null ? this.renderRestoreConfirm() : nothing}
        ${this.groupRootId !== null ? this.renderUndoTurnConfirm() : nothing}
        <div class="list">
          ${filtered.length === 0
            ? this.entries.length === 0
              ? html`<div class="empty">
                  No actions recorded yet — anything you or the AI does will
                  appear here, newest first.
                </div>`
              : html`<div class="empty">No entries match the current filter</div>`
            : groupJournalForDisplay([...filtered].reverse()).map((turn) => {
                if (turn.grouped) return this.renderTurn(turn);
                const only = turn.members[0];
                return only ? this.renderEntryRow(only) : nothing;
              })}
        </div>
      </div>
      </dialog>
    `;
  }

  // 543-fwd #6 — a causation turn: a collapsible header (originator + step count)
  // over its member rows. Default expanded; the header toggles collapse.
  private renderTurn(turn: DisplayTurn): TemplateResult {
    const collapsed = this.collapsedTurns.has(turn.rootId);
    return html`
      <div class="turn" data-testid="turn-${turn.rootId}">
        <button
          class="turn-head"
          type="button"
          aria-expanded=${collapsed ? 'false' : 'true'}
          data-testid="turn-toggle-${turn.rootId}"
          @click=${() => this.toggleTurnCollapse(turn.rootId)}
        >
          <span class="turn-caret">${collapsed ? '▸' : '▾'}</span>
          <jf-status-badge origin=${turn.originator}>${turn.originator}</jf-status-badge>
          <span class="turn-label">${turn.members.length}-step turn</span>
        </button>
        ${collapsed
          ? nothing
          : html`<div class="turn-body">
              ${turn.members.map((m) => this.renderEntryRow(m))}
            </div>`}
      </div>
    `;
  }

  // 543-fwd — a single journal entry row (label + time + detail via <jf-effect-line>),
  // with selection, the causation chip, and the per-entry undo affordances. Every entry
  // gets its own row (Fix C — no run-collapse hiding rows/actions).
  private renderEntryRow(e: JournalEntry): TemplateResult {
    return html`
      <div class="entry" data-entry-id=${e.id}>
        <div class="entry-head">
          <input
            type="checkbox"
            data-testid="entry-select-${e.id}"
            ?checked=${this.selectedIds.has(e.id)}
            @change=${() => this.toggleSelection(e.id)}
          />
          <jf-status-badge origin=${e.originator}>${e.originator}</jf-status-badge>
          ${e.pendingOutcome
            ? html`<jf-status-badge status=${e.pendingOutcome}>${e.pendingOutcome}</jf-status-badge>`
            : nothing}
          <jf-effect-line
            class="grow"
            .effect=${e.effect}
            .timestamp=${e.invokedAt}
            showDetail
          ></jf-effect-line>
          <span class="actions">
            ${e.causation !== undefined
              ? html`<button
                  class="chip-trace"
                  data-testid="causation-${e.id}"
                  title="Why did this happen? Show the causation chain to the root (§32 R-E3)"
                  @click=${() => this.toggleTrace(e.id)}
                  >↳ #${e.causation} why?</button
                >`
              : nothing}
            ${this.renderUndoOp(e)}
            <jf-button
              size="sm"
              data-testid="restore-to-${e.id}"
              label="Restore to this point"
              title="Restore to this point — reverse everything done after this action (reversible / compensable only)"
              .onActivate=${() => this.handleRestoreToHere(e.id)}
            >↶ restore</jf-button>
            ${e.causation !== undefined
              ? html`<jf-button
                  size="sm"
                  data-testid="undo-turn-${e.id}"
                  label="Undo this AI turn"
                  title="543-fwd #7 — undo this whole AI turn (all steps sharing its causation root) in one step"
                  .onActivate=${() => this.handleUndoTurn(e.id)}
                >↶ undo turn</jf-button>`
              : nothing}
          </span>
        </div>
        ${this.tracedId === e.id ? this.renderTrace(e) : nothing}
      </div>
    `;
  }

  // 543-fwd #6 — the bottom-up "why did this happen" causation trace: the chain
  // of ancestor effects from the root down to this entry. Powered by
  // getCausationChain (513 lazy-pointer DAG walk). For a real agent turn this is
  // the tool-call sequence that led here (bridge enrichment chains them).
  private renderTrace(entry: JournalEntry): TemplateResult {
    const ancestors = getCausationChain(entry.id); // root … direct parent
    const lineage = [...ancestors, entry]; // root → … → this
    return html`
      <div class="trace" data-testid="trace-${entry.id}">
        <div class="trace-title">Why this happened</div>
        <ol class="trace-chain">
          ${lineage.map(
            (n, i) => html`<li data-testid="trace-step-${entry.id}">
              <span class="trace-step-n">${i === 0 ? 'root' : `#${n.id}`}</span>
              ${present({ kind: 'effect', effect: n.effect }).label}
            </li>`,
          )}
        </ol>
      </div>
    `;
  }

  // 543-fwd #8 — the confirm bar shown before committing "Undo all AI actions".
  private renderUndoAllConfirm(): TemplateResult {
    return html`
      <div class="undo-all-confirm" data-testid="undo-all-confirm">
        <div class="confirm-title">
          Undo these ${this.undoAllPreview.length} AI
          action${this.undoAllPreview.length === 1 ? '' : 's'}?
        </div>
        <ul class="confirm-list">
          ${this.undoAllPreview.map(
            (e) => html`<li data-testid="undo-all-row">${present({ kind: 'effect', effect: e.effect }).label}</li>`,
          )}
        </ul>
        ${this.irreversibleNote(listJournalByOriginator('agent').filter((e) => e.pendingOutcome !== 'rejected'
          && !this.undoAllPreview.some((p) => p.id === e.id)).length)}
        <div class="confirm-actions">
          <jf-button
            variant="danger"
            size="sm"
            data-testid="undo-all-confirm-yes"
            label="Undo all"
            .onActivate=${() => this.confirmUndoAll()}
          >Undo all</jf-button>
          <jf-button
            size="sm"
            data-testid="undo-all-confirm-cancel"
            label="Cancel"
            .onActivate=${() => this.cancelUndoAll()}
          >Cancel</jf-button>
        </div>
      </div>
    `;
  }

  // 543-fwd #5 — the restore (time-travel) confirm bar: list everything a
  // "restore to here" would reverse before committing.
  private renderRestoreConfirm(): TemplateResult {
    return html`
      <div class="undo-all-confirm" data-testid="restore-confirm">
        <div class="confirm-title">
          Restore to this point — reverse these ${this.restorePreview.length}
          action${this.restorePreview.length === 1 ? '' : 's'} done after it?
        </div>
        <ul class="confirm-list">
          ${this.restorePreview.map(
            (e) => html`<li data-testid="restore-row">${present({ kind: 'effect', effect: e.effect }).label}</li>`,
          )}
        </ul>
        ${this.irreversibleNote(this.entries.filter((e) => this.restoreTargetId !== null && e.id > this.restoreTargetId
          && e.pendingOutcome !== 'rejected' && !this.restorePreview.some((p) => p.id === e.id)).length)}
        <div class="confirm-actions">
          <jf-button
            variant="danger"
            size="sm"
            data-testid="restore-confirm-yes"
            label="Restore"
            .onActivate=${() => this.confirmRestore()}
          >Restore</jf-button>
          <jf-button
            size="sm"
            data-testid="restore-confirm-cancel"
            label="Cancel"
            .onActivate=${() => this.cancelRestore()}
          >Cancel</jf-button>
        </div>
      </div>
    `;
  }

  // 543-fwd #7 — confirm bar for "undo this AI turn" (the causation group).
  private renderUndoTurnConfirm(): TemplateResult {
    return html`
      <div class="undo-all-confirm" data-testid="undo-turn-confirm">
        <div class="confirm-title">
          Undo this AI turn — reverse these ${this.groupPreview.length}
          action${this.groupPreview.length === 1 ? '' : 's'}?
        </div>
        <ul class="confirm-list">
          ${this.groupPreview.map(
            (e) => html`<li data-testid="undo-turn-row">${present({ kind: 'effect', effect: e.effect }).label}</li>`,
          )}
        </ul>
        ${this.irreversibleNote(this.entries.filter((e) => this.groupRootId !== null && getGroupRoot(e.id) === this.groupRootId
          && e.pendingOutcome !== 'rejected' && !this.groupPreview.some((p) => p.id === e.id)).length)}
        <div class="confirm-actions">
          <jf-button variant="danger" size="sm" data-testid="undo-turn-confirm-yes" label="Undo turn" .onActivate=${() => this.confirmUndoTurn()}>Undo turn</jf-button>
          <jf-button size="sm" data-testid="undo-turn-confirm-cancel" label="Cancel" .onActivate=${() => this.cancelUndoTurn()}>Cancel</jf-button>
        </div>
      </div>
    `;
  }

  // §32 U2 — "Undo" affordance for an invoke-operation entry whose backend
  // executionId was captured (undoSupported ops). Dispatches an
  // undo-operation Effect → Shell jf-undo-operation listener → OperationClient
  // .undoWithConsent → POST /api/undo/{operationId} (trust-gated; tempdoc 875 §C.7).
  private renderUndoOp(e: JournalEntry): TemplateResult | typeof nothing {
    const u = getUndoableOperation(e.id);
    if (!u) return nothing;
    return html`<jf-button
      class="undo-op"
      size="sm"
      data-testid="undo-op-${e.id}"
      label="Undo operation"
      title="reverse this operation on the backend"
      .onActivate=${() => this.handleUndoOp(u.operationId, u.executionId)}
    >
      Undo
    </jf-button>`;
  }

  private handleUndoOp(operationId: string, executionId: string): void {
    applyEffect({ kind: 'undo-operation', operationId, executionId });
  }
}

if (!customElements.get('jf-effect-audit-log')) {
  customElements.define('jf-effect-audit-log', EffectAuditLog);
}
