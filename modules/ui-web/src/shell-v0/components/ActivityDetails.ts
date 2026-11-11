// SPDX-License-Identifier: Apache-2.0
/**
 * <jf-activity-details> — what Activity's details pane shows for the selected feed record (S6
 * Activity, draft §4). Activity composes it inside the shared `jf-details-pane`; it is content, not
 * a pane, and holds no selection of its own.
 *
 * Every record says when it happened, how long it took, who did it, where it came from and which
 * records it joins. A burst says how long and from where across its members: from the earliest
 * start to the latest end (only when every member's duration is recorded), and their shared source or
 * each of theirs. A scan says it from its own rollup record. By kind:
 *   - an operation reads its receipt (`/api/operation-history/{key}`) through a {@link ReceiptWatch},
 *     which follows a running invocation and never moves back from a terminal state; a receipt that
 *     is unknown, expired or unreadable is said as such, never as a failed execution;
 *   - a record this window journaled carries the journal's actions: Ask the engine to undo (an
 *     execution association, also one restored after a reload), Restore to this point and Undo this
 *     turn (each previewed, naming what cannot be undone), the causation trace, and a place in the
 *     macro / export selection. A proposal decision is a decision, never something to undo;
 *   - a backend copy of another window's effect has none of those, and says so;
 *   - gate and grant decisions list the other records of the same request;
 *   - index records, bursts and scans name their files once resolved, and offer Open file and Show
 *     in folder only where the runtime can do it (the desktop app);
 *   - a record from an agent run links to it: Replay in Search for a finished background run, the
 *     run scope for the current run, and nothing invented for an outside caller.
 */
import { html, css, nothing, type PropertyValues, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import './Button.js';
import './EffectLine.js';
import {
  contributionsOf,
  durationOf,
  groupDurationOf,
  operationKeyOf,
  runOf,
  transportOf,
  type FeedRow,
  type SelectedRecord,
} from '../operations/activityFeed.js';
import { formatScanDuration, indexResultOf } from '../operations/ActionLedgerClient.js';
import { ReceiptWatch, isPending, isTerminalReceipt, type ReceiptState } from '../operations/operationReceipt.js';
import {
  getCausationChain,
  getGroupRoot,
  getUndoableOperation,
  listJournal,
  previewUndoGroup,
  previewUndoToEntry,
  requestOperationUndo,
  undoEntries,
  type JournalEntry,
  type OperationRequestOutcome,
} from '../substrates/effects/index.js';
import { applyEffect } from '../substrates/actions/index.js';
import { reportUndoRun } from './undoReport.js';
import { present } from '../display/present.js';
import { formatDateTime } from '../display/format.js';
import { isTauriRuntime } from '../../utils/tauriRuntime.js';
import { detailsDocumentActions } from './detailsPane/detailsDocumentActions.js';
import { getAgentSessionController } from '../state/agentSessionStore.js';
import { requestSurfaceNavigation } from '../controllers/navigateRequest.js';

const SEARCH_WINDOW = 'core.search-v3-surface';

/** A journal preview awaiting confirmation: restoring to an entry, or undoing its turn. */
interface JournalPreview {
  readonly kind: 'restore' | 'turn';
  readonly entryId: number;
  /** For a turn, its causation root. */
  readonly root?: number;
  readonly entries: readonly JournalEntry[];
}

const SOURCE_WORDS: Record<string, string> = {
  BUTTON: 'You, in the app',
  KEYBOARD: 'You, from the keyboard',
  COMMAND_PALETTE: 'You, from the command palette',
  LLM_EMISSION: 'The assistant',
  AGENT_LOOP: 'The assistant',
  WORKFLOW: 'A workflow',
  MCP: 'An outside assistant (MCP)',
  HTTP_API: 'The local API',
  SYSTEM_INTERNAL: 'The engine',
  SCHEDULED: 'A schedule',
  RULE_ENGINE: 'A rule',
};

const RECORD_WORDS = { ledger: 'Activity ledger', history: 'Operation history', journal: "This window's journal" } as const;

const WHO_WORDS: Record<string, string> = { user: 'You', agent: 'The assistant', system: 'The engine' };

const GATE_WORDS: Record<string, string> = {
  GATED: 'Asked for approval', APPROVED: 'Approved', DENIED: 'Denied',
  EXPIRED: 'Expired without an answer', WITHDRAWN: 'Withdrawn',
};

const GRANT_WORDS: Record<string, string> = {
  ISSUED: 'Permission granted', CONSUMED: 'Permission used', REVOKED: 'Permission withdrawn',
};

const RECEIPT_WORDS: Record<string, string> = {
  accepted: 'Accepted; waiting to run', running: 'Running', complete: 'Completed', failed: 'Failed',
};

function count(n: number, noun: string): string {
  return `${n} ${noun}${n === 1 ? '' : 's'}`;
}

/** Where a record came from, in words: its recorded transport, this window's journal, or not recorded. */
function sourceWords(row: FeedRow): string {
  const transport = transportOf(row);
  return transport ? (SOURCE_WORDS[transport] ?? transport) : row.journal ? 'This window' : 'Not recorded';
}

/** A group's source: the one its members share, else each of theirs, in the order the members are listed. */
function groupSourceWords(members: readonly FeedRow[]): string {
  const words = [...new Set(members.map(sourceWords))];
  if (words.length === 0) return 'Not recorded';
  return words.length === 1 ? words[0]! : `Several: ${words.join(' · ')}`;
}

function durationWords(ms: number | undefined): string {
  return ms !== undefined ? (formatScanDuration(ms) || 'Under a second') : 'Not recorded';
}

function when(iso: string): string {
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? iso : formatDateTime(parsed);
}

export class ActivityDetails extends JfElement {
  static properties = {
    record: { attribute: false },
    evicted: { type: Boolean },
    apiBase: { type: String, attribute: 'api-base' },
    journalSelection: { attribute: false },
    receipt: { state: true },
    preview: { state: true },
    undoRequest: { state: true },
  };

  declare record: SelectedRecord | null;
  /** No loaded record answers to the selection any more: what it last showed stays, without actions. */
  declare evicted: boolean;
  declare apiBase: string;
  /** Journal entry ids chosen for a macro or an export (held by Activity, across records). */
  declare journalSelection: ReadonlySet<number>;
  declare private receipt: ReceiptState | null;
  declare private preview: JournalPreview | null;
  /** The backend undo asked for from these details, by journal entry, and the Engine's answer once given. */
  declare private undoRequest: { readonly entryId: number; readonly answer: OperationRequestOutcome | 'asked' | 'unobservable' } | null;

  private watch: ReceiptWatch | null = null;
  /** Bumped when the record changes or the details close: an answer for an earlier record changes nothing. */
  private generation = 0;

  constructor() {
    super();
    this.record = null;
    this.evicted = false;
    this.apiBase = '';
    this.journalSelection = new Set();
    this.receipt = null;
    this.preview = null;
    this.undoRequest = null;
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.generation += 1;
    this.watch?.stop();
    this.watch = null;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    // Reattached after an update: follow the receipt again from a fresh read (the first update starts it).
    if (this.hasUpdated && this.record) this.follow(this.record.row ? operationKeyOf(this.record.row) : undefined, true);
  }

  protected override willUpdate(changed: PropertyValues<this>): void {
    if (!changed.has('record')) return;
    const before = changed.get('record') as SelectedRecord | null | undefined;
    // Another record (not the same one updated in place, which keeps its key or gains an alias).
    const same = !!before && !!this.record && (before.key === this.record.key || !!this.record.row?.aliases.includes(before.key));
    if (!same) {
      this.generation += 1;
      this.preview = null;
      this.undoRequest = null;
    }
    this.follow(this.record?.row ? operationKeyOf(this.record.row) : undefined, !same);
  }

  /** Follow the receipt for `key`; a different key starts over, so a previous record's answer cannot show. */
  private follow(key: string | undefined, restart: boolean): void {
    if (!restart && this.watch !== null && this.watch.operationKey === key) return;
    this.watch?.stop();
    this.watch = null;
    this.receipt = null;
    if (!key || !this.isConnected) return;
    const watch = new ReceiptWatch(this.apiBase, key, (state) => { if (this.watch === watch) this.receipt = state; });
    this.watch = watch;
    watch.read();
  }

  static styles = css`
    :host {
      display: block;
      padding: var(--jf-space-3) var(--jf-space-4);
      overflow-wrap: anywhere;
      font-size: var(--font-size-sm);
      color: var(--text-primary);
    }
    h3 { margin: 0 0 var(--jf-space-2); font-size: var(--font-size-md); font-weight: 600; }
    h4 {
      margin: var(--jf-space-4) 0 var(--jf-space-1);
      font-size: var(--font-size-xs);
      font-weight: 600;
      letter-spacing: 0.03em;
      text-transform: uppercase;
      color: var(--text-secondary);
    }
    dl { display: grid; grid-template-columns: minmax(6rem, max-content) 1fr; gap: var(--jf-space-1) var(--jf-space-3); margin: 0; }
    dt { color: var(--text-secondary); }
    dd { margin: 0; min-inline-size: 0; }
    p { margin: var(--jf-space-1) 0; }
    .note { color: var(--text-secondary); }
    .alert { color: var(--text-danger); }
    ul, ol { margin: 0; padding-inline-start: var(--jf-space-4); }
    li { padding-block: var(--jf-space-1); }
    .actions { display: flex; flex-wrap: wrap; gap: var(--jf-space-2); margin-block: var(--jf-space-2); }
    .confirm {
      margin-block: var(--jf-space-2);
      padding: var(--jf-space-2) var(--jf-space-3);
      border: var(--frame-hairline) solid var(--border-default);
      border-radius: var(--frame-control-radius);
    }
    label.choose { display: flex; align-items: center; gap: var(--jf-space-2); margin-block: var(--jf-space-2); }
    .evicted {
      padding: var(--jf-space-2) var(--jf-space-3);
      border: var(--frame-hairline) solid var(--border-default);
      border-radius: var(--frame-control-radius);
    }
  `;

  override render(): TemplateResult | typeof nothing {
    const record = this.record;
    if (!record) return nothing;
    return html`<div data-testid="activity-details" data-kind=${record.kind}>
      <h3 data-testid="activity-details-title">${record.label}</h3>
      ${this.evicted ? html`<p class="evicted" role="status" data-testid="activity-details-evicted">This record is no longer
        among the loaded records. What it showed when it was last loaded is kept below; its actions are not offered.</p>` : nothing}
      ${record.row ? this.renderFacts(record.row) : this.renderGroupFacts(record)}
      ${record.row ? this.renderKind(record.row, record) : nothing}
      ${record.members.length || record.kind !== 'record' ? this.renderMembers(record) : nothing}
      ${record.related.length ? this.renderRelated(record.related) : nothing}
      ${record.row && !this.evicted ? this.renderRun(record.row) : nothing}
      ${record.row?.journal && !this.evicted ? this.renderJournal(record.row.journal) : nothing}
      ${record.row && !record.row.journal && record.row.ledger?.kind === 'effect' ? html`<p class="note" data-testid="activity-details-elsewhere">
        Recorded by another window or app. Its local details, proposal status and undo are only available there.</p>` : nothing}
    </div>`;
  }

  /** When, how long, who, from where, and which records this row joins. */
  private renderFacts(row: FeedRow): TemplateResult {
    return html`<dl data-testid="activity-details-facts">
      <dt>When</dt><dd>${when(row.entry.occurredAt)}</dd>
      <dt>Duration</dt><dd data-testid="activity-details-duration">${durationWords(durationOf(row) ?? this.receiptDuration())}</dd>
      <dt>Who</dt><dd>${WHO_WORDS[row.entry.originator] ?? row.entry.originator}</dd>
      <dt>Source</dt><dd data-testid="activity-details-source">${sourceWords(row)}</dd>
      <dt>Recorded in</dt><dd data-testid="activity-details-records">${contributionsOf(row).map((r) => RECORD_WORDS[r]).join(' · ')}</dd>
      ${row.entry.kind === 'operation' || operationKeyOf(row) ? html`<dt>Outcome</dt><dd data-testid="activity-details-outcome">${this.outcomeWords(row)}</dd>` : nothing}
    </dl>`;
  }

  /** A burst is a summary of its members: when, and how long and from where, across them. */
  private renderGroupFacts(record: SelectedRecord): TemplateResult {
    return html`<dl data-testid="activity-details-facts">
      <dt>When</dt><dd>${when(record.occurredAt)}</dd>
      <dt>Duration</dt><dd data-testid="activity-details-duration">${durationWords(groupDurationOf(record.members))}</dd>
      <dt>Source</dt><dd data-testid="activity-details-source">${groupSourceWords(record.members)}</dd>
    </dl>`;
  }

  private receiptDuration(): number | undefined {
    const outcome = this.receipt?.outcome;
    return outcome?.acceptedAt !== undefined && outcome.completedAt !== undefined && outcome.completedAt >= outcome.acceptedAt
      ? outcome.completedAt - outcome.acceptedAt : undefined;
  }

  /**
   * The committed outcome is the authority once recorded; before that, the receipt's terminal state,
   * then its progress. A receipt still running behind a committed outcome is a stale sample.
   */
  private outcomeWords(row: FeedRow): string {
    const committed = row.history[0]?.outcome ?? (row.ledger?.kind === 'operation' ? row.ledger.outcome : undefined);
    if (committed) return committed;
    const outcome = this.receipt?.outcome;
    if (outcome && (isTerminalReceipt(outcome.state) || isPending(outcome)) && RECEIPT_WORDS[outcome.state]) return RECEIPT_WORDS[outcome.state]!;
    if (row.journal?.pendingOutcome === 'rejected') return 'Not carried out (the proposal was rejected)';
    if (row.journal && !row.ledger && !row.history.length) return 'Asked for in this window; no result recorded yet';
    return 'Outcome unavailable';
  }

  private renderKind(row: FeedRow, record: SelectedRecord): TemplateResult | typeof nothing {
    const ledger = row.ledger;
    if (ledger?.kind === 'gate') {
      return html`<h4>Approval</h4><dl data-testid="activity-details-gate">
        <dt>Decision</dt><dd>${GATE_WORDS[ledger.disposition ?? ''] ?? ledger.disposition ?? 'Not recorded'}</dd>
        <dt>Operation</dt><dd>${present({ kind: 'operation', id: ledger.operationId ?? '' }).label}</dd>
        ${ledger.gateBehavior ? html`<dt>Asked as</dt><dd>${ledger.gateBehavior}</dd>` : nothing}
        ${ledger.pendingId ? html`<dt>Request</dt><dd>${ledger.pendingId}</dd>` : nothing}
      </dl>`;
    }
    if (ledger?.kind === 'grant') {
      return html`<h4>Permission</h4><dl data-testid="activity-details-grant">
        <dt>What happened</dt><dd>${GRANT_WORDS[ledger.action ?? ''] ?? ledger.action ?? 'Not recorded'}</dd>
        ${ledger.subject ? html`<dt>For</dt><dd>${present({ kind: 'operation', id: ledger.subject }).label}</dd>` : nothing}
      </dl>`;
    }
    if (ledger?.kind === 'navigation') {
      const target = ledger.targetSurface;
      return html`<h4>Navigation</h4>
        <p>Went to ${target ? present({ kind: 'surface', id: target }).label : 'a page not recorded'}.</p>
        ${target && !this.evicted ? html`<div class="actions"><jf-button size="sm" variant="ghost" data-testid="activity-details-go"
          label="Go there" .onActivate=${() => requestSurfaceNavigation(target)}>Go there</jf-button></div>` : nothing}`;
    }
    if (row.entry.kind === 'index') return this.renderIndex(row, record.paths);
    if (ledger?.kind === 'operation' || row.history.length || operationKeyOf(row)) return this.renderOperation(row);
    return nothing;
  }

  /** The receipt's metadata, and what it means when there is none. */
  private renderOperation(row: FeedRow): TemplateResult {
    const key = operationKeyOf(row);
    const folder = row.entry.removedFolder?.path;
    const message = row.history.find((h) => h.message)?.message;
    return html`<h4>Operation</h4>
      <p>${present({ kind: 'operation', id: row.entry.operationId ?? row.history[0]?.operationId ?? '' }).label}</p>
      ${message ? html`<p data-testid="activity-details-message">${message}</p>` : nothing}
      ${key ? this.renderReceipt(!!(row.history[0]?.outcome ?? (row.ledger?.kind === 'operation' ? row.ledger.outcome : undefined))) : html`<p class="note" data-testid="activity-details-receipt" data-state="none">No detailed
        receipt can be read for this record: it carries no invocation key.</p>`}
      ${folder ? html`<p>Folder: ${folder}</p>${this.fileActions(folder)}` : nothing}`;
  }

  /** `committed`: the record already has its recorded outcome, which a receipt still running cannot undo. */
  private renderReceipt(committed: boolean): TemplateResult {
    const receipt = this.receipt;
    const outcome = receipt?.outcome ?? null;
    const retry = html`<jf-button size="sm" variant="ghost" data-testid="activity-details-receipt-retry" label="Try again"
      .onActivate=${() => this.watch?.read()}>Try again</jf-button>`;
    if (!outcome) {
      if (receipt?.readFailed) {
        return html`<p role="status" data-testid="activity-details-receipt" data-state="unreadable">The receipt could not be
          read. That says nothing about how the operation ended. ${retry}</p>`;
      }
      return html`<p class="note" data-testid="activity-details-receipt" data-state="loading">Reading the receipt…</p>`;
    }
    if (outcome.state === 'unknown') {
      return html`<p class="note" data-testid="activity-details-receipt" data-state="unknown">The engine keeps no detailed
        receipt for this operation. That does not mean it failed.</p>`;
    }
    if (outcome.state === 'expired') {
      return html`<p class="note" data-testid="activity-details-receipt" data-state="expired">The detailed receipt has expired.
        The record above remains.</p>`;
    }
    if (committed && isPending(outcome)) {
      return html`<p class="note" data-testid="activity-details-receipt" data-state="behind">Finished as recorded above; its
        receipt has not caught up yet.</p>`;
    }
    const code = outcome.result?.code ?? outcome.reason;
    return html`<dl data-testid="activity-details-receipt" data-state=${outcome.state}>
      <dt>Receipt</dt><dd>${RECEIPT_WORDS[outcome.state] ?? outcome.state}${outcome.phase ? ` · ${outcome.phase}` : ''}</dd>
      ${outcome.unitsCompleted !== undefined || outcome.unitsFailed !== undefined ? html`<dt>Items</dt><dd>${
        [outcome.unitsCompleted !== undefined ? `${outcome.unitsCompleted} done` : '',
          outcome.unitsFailed ? `${outcome.unitsFailed} failed` : ''].filter(Boolean).join(' · ')}</dd>` : nothing}
      ${outcome.message ? html`<dt>Message</dt><dd class=${outcome.state === 'failed' ? 'alert' : ''}>${outcome.message}</dd>` : nothing}
      ${code ? html`<dt>Code</dt><dd>${code}</dd>` : nothing}
      ${outcome.result?.gaps?.length ? html`<dt>Gaps</dt><dd>${count(outcome.result.gaps.length, 'item')} could not be completed</dd>` : nothing}
    </dl>
    ${receipt?.readFailed ? html`<p role="status" data-testid="activity-details-receipt-stale">The latest read failed; this is
      the last receipt read. ${retry}</p>` : nothing}`;
  }

  private renderIndex(row: FeedRow, paths: Readonly<Record<string, string>>): TemplateResult {
    const state = row.entry.indexState;
    const result = indexResultOf(state);
    const path = row.entry.pathHash ? paths[row.entry.pathHash] : undefined;
    return html`<h4>Indexing</h4><dl data-testid="activity-details-index">
      <dt>Result</dt><dd>${result === 'indexed' ? 'Indexed' : result === 'failed'
        ? (state === 'RETRY_EXHAUSTED' ? 'Gave up after retrying' : 'Failed') : `Unknown${state ? ` (${state})` : ''}`}</dd>
      <dt>Collection</dt><dd>${row.entry.collection ?? 'default'}</dd>
      <dt>File</dt><dd data-testid="activity-details-file">${path ?? 'Not resolved yet'}</dd>
    </dl>${path ? this.fileActions(path) : nothing}`;
  }

  /**
   * Open file and Show in folder only where they can work: the desktop app opens and reveals local
   * files; a browser can do neither for a file on the engine's disk, so it is said instead.
   */
  private fileActions(path: string): TemplateResult | typeof nothing {
    if (this.evicted) return nothing;
    return isTauriRuntime()
      ? html`<div class="actions" data-testid="activity-details-file-actions">${detailsDocumentActions(path)}</div>`
      : html`<p class="note" data-testid="activity-details-file-unavailable">Open file and Show in folder are available in the
        desktop app.</p>`;
  }

  private renderMembers(record: SelectedRecord): TemplateResult {
    const failed = record.members.filter((m) => m.problem).length;
    return html`<h4>${record.kind === 'scan' ? 'Documents retained' : 'Records'}</h4>
      ${record.members.length === 0 ? html`<p class="note" data-testid="activity-details-members-none">No per-document records
        are retained for this scan.</p>` : html`
        ${failed ? html`<p data-testid="activity-details-members-failed">${count(failed, 'record')} failed.</p>` : nothing}
        <ul data-testid="activity-details-members">${record.members.map((member) => html`<li data-testid="activity-details-member"
          ?data-problem=${member.problem}>${member.entry.label} · ${when(member.entry.occurredAt)}</li>`)}</ul>`}`;
  }

  private renderRelated(related: readonly FeedRow[]): TemplateResult {
    return html`<h4>Same request</h4><ul data-testid="activity-details-related">${related.map((r) => html`<li>${r.entry.label}${
      r.entry.gate ? ` · ${GATE_WORDS[r.entry.gate.disposition] ?? r.entry.gate.disposition}` : ''}${
      r.entry.grant ? ` · ${GRANT_WORDS[r.entry.grant.action] ?? r.entry.grant.action}` : ''}${
      r.entry.outcome ? ` · ${r.entry.outcome}` : ''} · ${when(r.entry.occurredAt)}</li>`)}</ul>`;
  }

  /** The agent run a record names, linked only where Activity knows that run. */
  private renderRun(row: FeedRow): TemplateResult | typeof nothing {
    const sessionId = runOf(row);
    if (!sessionId) return nothing;
    const ctrl = getAgentSessionController(this.apiBase);
    const run = ctrl.presence.find((candidate) => candidate.sessionId === sessionId);
    const terminal = run && (run.state === 'DONE' || run.state === 'ERROR');
    const current = ctrl.sessionId === sessionId;
    return html`<h4>Run</h4><div data-testid="activity-details-run">
      ${terminal ? html`<p>A background run that has finished.</p><div class="actions"><jf-button size="sm" data-testid="activity-details-replay"
        label="Replay in Search" .onActivate=${() => { void ctrl.loadReplay(sessionId); requestSurfaceNavigation(SEARCH_WINDOW); }}>Replay in Search</jf-button></div>`
        : current ? html`<p>Part of the current agent run.</p><div class="actions"><jf-button size="sm" variant="ghost" data-testid="activity-details-run-scope"
          label="Show this run's records" .onActivate=${() => this.dispatchEvent(new CustomEvent('activity-run-scope', { bubbles: true, composed: true }))}
          >Show this run's records</jf-button></div>`
          : run ? html`<p>Part of a background run that is still ${run.state === 'WAITING_APPROVAL' ? 'waiting for approval' : 'working'}.</p>`
            : html`<p class="note">Recorded with the run identifier ${sessionId}. Activity has no run of this name to open.</p>`}
    </div>`;
  }

  /** The journal's record and its actions: undo, restore, the turn, the trace and the selection. */
  private renderJournal(entry: JournalEntry): TemplateResult {
    const rejected = entry.pendingOutcome === 'rejected';
    const undoable = rejected ? undefined : getUndoableOperation(entry.id);
    const chain = entry.causation !== undefined ? getCausationChain(entry.id) : [];
    return html`<h4>Recorded in this window</h4>
      <jf-effect-line .effect=${entry.effect} .timestamp=${entry.invokedAt} showDetail></jf-effect-line>
      <dl data-testid="activity-details-journal">
        <dt>Recorded by</dt><dd>${entry.invokedBy?.contributorId
          ? `${entry.invokedBy.contributorId} (${entry.invokedBy.tier})` : 'Not recorded'}</dd>
        ${entry.pendingOutcome ? html`<dt>Proposal</dt><dd data-testid="activity-details-proposal">${rejected
          ? 'Rejected. It was never carried out, so there is nothing to undo.'
          : 'Accepted. This records the decision; carrying it out is recorded on its own.'}</dd>` : nothing}
        <dt>Undo</dt><dd data-testid="activity-details-reversible">${rejected ? 'Nothing to undo'
          : entry.inverse !== null ? 'Can be reversed in this window'
            : undoable ? 'The engine can be asked to undo it; it decides whether that is still possible' : 'Cannot be undone'}</dd>
      </dl>
      ${chain.length ? html`<h4>Why this happened</h4><ol data-testid="activity-details-trace">${[...chain, entry].map((step, i) =>
        html`<li data-testid="activity-details-trace-step">${i === 0 ? 'Started by: ' : ''}${present({ kind: 'effect', effect: step.effect }).label}</li>`)}</ol>` : nothing}
      <div class="actions">
        ${undoable ? html`<jf-button size="sm" data-testid="activity-details-undo-operation" label="Ask the engine to undo"
          .disabled=${this.undoRequest?.entryId === entry.id && this.undoRequest.answer === 'asked'}
          .onActivate=${() => this.askUndo(entry.id)}>Ask the engine to undo</jf-button>` : nothing}
        <jf-button size="sm" variant="ghost" data-testid="activity-details-restore" label="Restore to this point"
          title="Reverse everything done after this action that can be reversed"
          .onActivate=${() => this.stage('restore', entry)}>Restore to this point</jf-button>
        ${entry.causation !== undefined ? html`<jf-button size="sm" variant="ghost" data-testid="activity-details-undo-turn"
          label="Undo this AI turn" .onActivate=${() => this.stage('turn', entry)}>Undo this AI turn</jf-button>` : nothing}
      </div>
      ${this.renderUndoAnswer(entry.id)}
      ${this.preview?.entryId === entry.id ? this.renderPreview(this.preview) : nothing}
      <label class="choose"><input type="checkbox" data-testid="activity-details-choose" .checked=${this.journalSelection.has(entry.id)}
        @change=${(event: Event) => this.dispatchEvent(new CustomEvent<{ id: number; chosen: boolean }>('journal-choose',
          { detail: { id: entry.id, chosen: (event.target as HTMLInputElement).checked }, bubbles: true, composed: true }))}>
        Include in a macro or export</label>`;
  }

  private renderUndoAnswer(entryId: number): TemplateResult | typeof nothing {
    const request = this.undoRequest;
    if (!request || request.entryId !== entryId) return nothing;
    const words = request.answer === 'asked' ? 'Asked the engine to undo this action…'
      : request.answer === 'confirmed' ? 'The engine undid this action.'
        : request.answer === 'not-done' ? 'The engine did not undo this action.'
          : 'Asked the engine to undo this action; its answer is not shown here.';
    return html`<p role="status" data-testid="activity-details-undo-answer" data-answer=${request.answer}>${words}</p>`;
  }

  /** One request per click: the button stays unavailable until the Engine answers. */
  private askUndo(entryId: number): void {
    if (this.undoRequest?.entryId === entryId && this.undoRequest.answer === 'asked') return;
    const generation = this.generation;
    const run = requestOperationUndo(entryId, (effect) => applyEffect(effect));
    const request = run.requested[0];
    if (!request) {
      this.undoRequest = null;
      applyEffect({ kind: 'toast', message: 'This action can no longer be undone', severity: 'warning' });
      return;
    }
    if (!request.settled) { this.undoRequest = { entryId, answer: 'unobservable' }; return; }
    this.undoRequest = { entryId, answer: 'asked' };
    void request.settled.then((answer) => {
      if (generation === this.generation && this.isConnected) this.undoRequest = { entryId, answer };
    });
  }

  private stage(kind: JournalPreview['kind'], entry: JournalEntry): void {
    if (kind === 'restore') {
      const entries = previewUndoToEntry(entry.id);
      if (!entries.length) { applyEffect({ kind: 'toast', message: 'Nothing to undo after this point', severity: 'info' }); return; }
      this.preview = { kind, entryId: entry.id, entries };
      return;
    }
    const root = getGroupRoot(entry.id);
    const entries = root === null ? [] : previewUndoGroup(root);
    if (root === null || !entries.length) { applyEffect({ kind: 'toast', message: 'Nothing reversible in this turn', severity: 'info' }); return; }
    this.preview = { kind, entryId: entry.id, root, entries };
  }

  /** DE6 — what the preview will not undo, said before the person commits. */
  private remainder(preview: JournalPreview): number {
    return listJournal().filter((e) => e.pendingOutcome !== 'rejected' && !preview.entries.some((p) => p.id === e.id)
      && (preview.kind === 'restore' ? e.id > preview.entryId : getGroupRoot(e.id) === preview.root)).length;
  }

  private renderPreview(preview: JournalPreview): TemplateResult {
    const remainder = this.remainder(preview);
    const restore = preview.kind === 'restore';
    return html`<section class="confirm" data-testid="activity-details-preview" data-preview=${preview.kind}
      aria-label=${restore ? 'Confirm restore' : 'Confirm undo turn'}>
      <p>${restore ? `Restore to this point — reverse these ${count(preview.entries.length, 'action')} done after it?`
        : `Undo this AI turn — reverse these ${count(preview.entries.length, 'action')}?`}</p>
      <ul>${preview.entries.map((e) => html`<li data-testid="activity-details-preview-row">${present({ kind: 'effect', effect: e.effect }).label}</li>`)}</ul>
      ${remainder ? html`<p data-testid="activity-details-irreversible">${remainder} other action${remainder === 1 ? '' : 's'} can't be
        undone and will remain.</p>` : nothing}
      <div class="actions">
        <jf-button size="sm" variant="danger" data-testid="activity-details-preview-confirm" label=${restore ? 'Restore' : 'Undo turn'}
          .onActivate=${() => this.confirm()}>${restore ? 'Restore' : 'Undo turn'}</jf-button>
        <jf-button size="sm" data-testid="activity-details-preview-cancel" label="Cancel"
          .onActivate=${() => { this.preview = null; }}>Cancel</jf-button>
      </div>
    </section>`;
  }

  /** DE6 — exactly the previewed entries, once; a second activation finds the preview gone. */
  private confirm(): void {
    const preview = this.preview;
    this.preview = null;
    if (!preview) return;
    const scope = preview.kind === 'turn' ? ' in this turn' : '';
    reportUndoRun(undoEntries(preview.entries, (effect) => applyEffect(effect)),
      (reversed) => `Reverted ${reversed} action${reversed === 1 ? '' : 's'}${scope}`);
  }

  /** Close what is open for the record: a preview, a request's pending answer. */
  settle(): void {
    this.generation += 1;
    this.preview = null;
    this.undoRequest = null;
    this.watch?.stop();
    this.watch = null;
    this.receipt = null;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-activity-details')) {
  customElements.define('jf-activity-details', ActivityDetails);
}

declare global {
  interface HTMLElementTagNameMap {
    'jf-activity-details': ActivityDetails;
  }
}
