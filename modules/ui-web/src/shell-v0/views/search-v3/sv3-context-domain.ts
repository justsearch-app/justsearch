// SPDX-License-Identifier: Apache-2.0
/** This window's record reads and context ledger. The host owns the sessions and rendered surface. */
import type { ResumedConversation } from '../../state/conversationListStore.js';
import type { ThreadResponse } from '../unifiedThreadClient.js';
import type { InspectorView } from '../../components/ContextInspectorPane.js';
import type { Sv3ContextUsage, Sv3Session, Sv3SessionHistory } from './sv3-sessions.js';
import {
  projectSv3ContextInspector, projectSv3TurnContexts, sv3ExcludedMessageIds,
  sv3FollowUpContext, sv3TurnContextFor,
  type Sv3ContextAction, type Sv3TurnContext,
} from './sv3-context.js';
import {
  CONTEXT_COMPACT_FAILED, CONTEXT_EXCLUDE_FAILED, CONTEXT_FLOOR_FAILED,
  CONTEXT_INCLUDE_ALL_FAILED, CONTEXT_INCLUDE_FAILED, CONTEXT_RESTORE_FAILED,
  CONTEXT_SUMMARY_FAILED,
} from './fixtures.js';

interface ContextHost {
  activeConversationId(): string | null;
  session(id: string): Sv3Session | null;
  connected(): boolean;
  applyHistory(id: string, history: Sv3SessionHistory): void;
  setContextUsage(id: string, usage: Sv3ContextUsage): void;
  contextWindow(): number | null;
  recordReadWait(id: string): number | undefined;
  retainedAttempt?(): { target: string; key: string } | null;
  recordArrival(id: string, record: ThreadResponse): void;
  scheduleRecordRetry(): void;
  clearRecordRetry(): void;
  retryRecords(): void;
  noteRefusedWhileLocked(): void;
  revokeHumanTask(): void;
  openContextInspector(): void;
  announceWarning(message: string): void;
  requestUpdate(): void;
}

type ContextTransport = Pick<typeof import('../../state/conversationListStore.js'),
  'setContextFloor' | 'clearContextFloor' | 'compactContext' | 'editContextFloorSummary' | 'setMessageExcluded'> & {
  readHistory(id: string): Promise<ResumedConversation>;
  fetchRecord(id: string, signal: AbortSignal, onFailure: () => void): Promise<ThreadResponse>;
};

/** What a `/history` read says about the conversation's context and lineage, as the window keeps it. */
const sv3HistoryOf = (history: ResumedConversation): Sv3SessionHistory => ({
  parentSessionId: history.parentSessionId,
  branchPointMessageId: history.branchPointMessageId,
  parentFirstUserMessage: history.parentFirstUserMessage,
  contextFloor: history.contextFloor,
  contextFloorSummary: history.contextFloorSummary,
  excludedMessageIds: history.excludedMessageIds,
  excludedSourceIds: history.excludedSourceIds,
  messageIds: history.messages.flatMap((m) => (typeof m.id === 'string' ? [m.id] : [])),
  locked: history.locked,
});

export class Sv3ContextDomain {
  /** Reads supersede only the same conversation, including Stop reconciliation after navigation. */
  private readonly recordReads = new Map<string, { controller: AbortController; bound(): void }>();
  private retainedCheck: { target: string; key: string; until: number } | null = null;
  private retainedTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly forgotten = new Set<string>();
  private readonly historyReads = new Map<string, AbortController>();
  /** A write includes its response AND saved-history refresh; later controls queue behind it. */
  private readonly contextWrites = new Map<string, Promise<void>>();
  private readonly contextNotices = new Map<string, string>();
  private readonly refusedQuestions = new Map<string, string>();
  private contextRetryTimer: ReturnType<typeof setTimeout> | null = null;
  private contextRetryDelay = 1000;
  private recordNoticeValue = false;
  private compactingValue = false;

  constructor(private readonly host: ContextHost, private readonly transport: ContextTransport) {}

  get recordNotice(): boolean { return this.recordNoticeValue; }
  set recordNotice(value: boolean) {
    if (this.recordNoticeValue === value) return;
    this.recordNoticeValue = value;
    this.host.requestUpdate();
  }

  get compacting(): boolean { return this.compactingValue; }
  private set compacting(value: boolean) {
    if (this.compactingValue === value) return;
    this.compactingValue = value;
    this.host.requestUpdate();
  }

  private get activeSession(): Sv3Session | null {
    const id = this.host.activeConversationId();
    return id === null ? null : this.host.session(id);
  }

  hasPendingWrite(id: string): boolean { return this.contextWrites.has(id); }
  refuseQuestion(id: string, question: string): void { this.refusedQuestions.set(id, question); }
  clearRefusedQuestion(id: string): void { this.refusedQuestions.delete(id); }

  recordUsage(id: string, usage: Sv3ContextUsage | null): void {
    if (usage !== null) this.host.setContextUsage(id, usage);
  }

  forgetConversation(id: string): void {
    this.forgotten.add(id);
    if (this.retainedCheck?.target === id) this.clearRetainedTimer();
    this.recordReads.get(id)?.controller.abort();
    this.recordReads.delete(id);
  }

  disconnect(): void {
    this.clearRetainedTimer();
    this.retainedCheck = null;
    for (const read of this.recordReads.values()) read.controller.abort();
    this.recordReads.clear();
    this.host.clearRecordRetry();
    for (const [id, read] of this.historyReads) {
      this.markContextUnconfirmed(id);
      read.abort();
    }
    this.historyReads.clear();
    this.clearContextRetry();
  }

  /**
   * D1 — project the conversation from its canonical record. The window is not the authority: the
   * turns it renders for a settled conversation are `GET /api/thread/{id}` projected through the two
   * SHARED authorities, so a run's history outlives the controller that produced it and survives a
   * reload the controller singleton does not.
   *
   * D2 (tempdoc 727 F-8) — a failed fetch returns EMPTY by contract precisely so it cannot wipe the
   * caller's live state, which is right and was also completely silent. `onFailure` is the out-of-band
   * signal; the notice is raised and the merge is SKIPPED, so a failure never re-renders the thread.
   */
  async refreshRecord(conversationId: string, automatic = false): Promise<boolean> {
    if (!this.host.connected() || this.forgotten.has(conversationId)) return false;
    if (!automatic && this.host.retainedAttempt?.()?.target === conversationId) this.checkRetainedAttempt(true);
    this.recordReads.get(conversationId)?.controller.abort();
    const abort = new AbortController();
    let failed = false;
    const watching = this.host.retainedAttempt?.()?.target === conversationId;
    const wait = this.host.recordReadWait(conversationId) ?? (watching ? 10000 : undefined);
    // Race the signal as well as passing it to fetch: a stalled/abort-ignoring transport must not
    // hold the checker forever. Its eventual response has no authority after this read retires.
    let timer: ReturnType<typeof setTimeout> | undefined;
    const cancelled = new Promise<undefined>(resolve => {
      abort.signal.addEventListener('abort', () => resolve(undefined), { once: true });
      if (wait !== undefined) timer = setTimeout(() => abort.abort(), wait);
    });
    const read = { controller: abort, bound: () => {
      if (timer === undefined) timer = setTimeout(() => abort.abort(), 10000);
    } };
    this.recordReads.set(conversationId, read);
    try {
      const record = await Promise.race([
        this.transport.fetchRecord(conversationId, abort.signal, () => { failed = true; })
          .catch(() => { failed = true; return undefined; }),
        cancelled,
      ]);
      if (abort.signal.aborted || this.recordReads.get(conversationId) !== read || record === undefined) return false;
      if (this.host.activeConversationId() === conversationId) this.recordNotice = failed;
      if (!failed) this.host.recordArrival(conversationId, record);
      return !failed;
    } finally {
      clearTimeout(timer);
      if (this.recordReads.get(conversationId) === read) this.recordReads.delete(conversationId);
      this.host.scheduleRecordRetry();
      this.checkRetainedAttempt();
    }
  }

  private clearRetainedTimer(): void {
    if (this.retainedTimer !== null) clearTimeout(this.retainedTimer);
    this.retainedTimer = null;
  }

  /** Only read records. A finite budget never changes delivery state or grants sending authority. */
  checkRetainedAttempt(rearm = false): void {
    const attempt = this.host.retainedAttempt?.();
    if (!this.host.connected() || !attempt || this.forgotten.has(attempt.target)) {
      this.clearRetainedTimer(); this.retainedCheck = null; return;
    }
    if (rearm || this.retainedCheck?.key !== attempt.key) {
      this.clearRetainedTimer();
      this.retainedCheck = { ...attempt, until: Date.now() + 30000 };
    }
    // Opening reads can predate recovery, or start while the stream still owns the attempt.
    // Bound them on adoption without extending an existing deadline or timing out other reads.
    this.recordReads.get(attempt.target)?.bound();
    if (this.retainedTimer !== null || Date.now() >= this.retainedCheck!.until) return;
    this.retainedTimer = setTimeout(() => {
      this.retainedTimer = null;
      const pending = this.retainedCheck;
      if (!pending || this.host.retainedAttempt?.()?.key !== pending.key) { this.checkRetainedAttempt(); return; }
      if (Date.now() >= pending.until) { this.host.requestUpdate(); return; }
      // An opening/manual read already in flight gets its bounded wait; ticks never supersede it.
      if (this.recordReads.has(pending.target)) { this.checkRetainedAttempt(); return; }
      void this.refreshRecord(pending.target, true);
    }, Math.min(500, this.retainedCheck!.until - Date.now()));
  }

  /** A saved outcome can arrive after cached context. Force a companion history read before send. */
  invalidateContext(sessionId: string): void { this.markContextUnconfirmed(sessionId); }

  /**
   * Read saved context without claiming the shared pointer. A later read of this conversation
   * supersedes an earlier one. Writes may refresh their own session after navigation; ordinary
   * opens only apply to the active session. Failed reads never invent a floor or exclusions.
   */
  async refreshHistory(conversationId: string, forWrite = false): Promise<boolean> {
    if (!this.host.connected() || (!forWrite && this.host.activeConversationId() !== conversationId)) return false;
    this.historyReads.get(conversationId)?.abort();
    const abort = new AbortController();
    this.historyReads.set(conversationId, abort);
    const history = await this.transport.readHistory(conversationId);
    if (abort.signal.aborted) return false;
    this.historyReads.delete(conversationId);
    if (history.locked === true && this.host.activeConversationId() === conversationId) this.host.noteRefusedWhileLocked();
    const landed = (forWrite || this.host.activeConversationId() === conversationId)
      && history.failed !== true && history.locked !== true;
    if (landed) {
      this.host.applyHistory(conversationId, sv3HistoryOf(history));
      // A read during a pending write cannot confirm that write's eventual result.
      if (!this.contextWrites.has(conversationId)) this.confirmContext(conversationId);
    } else {
      this.markContextUnconfirmed(conversationId);
    }
    this.scheduleContextRetry();
    return landed;
  }

  private markContextUnconfirmed(sessionId: string): void {
    this.contextNotices.set(sessionId, 'Saved context could not be confirmed. Retrying…');
    this.host.requestUpdate();
  }

  contextNotice(sessionId: string): string {
    const context = this.contextNotices.get(sessionId)
      ?? (this.contextWrites.has(sessionId) ? 'Saving context… Later changes will follow in order.' : '');
    const question = this.refusedQuestions.get(sessionId);
    // Keep the exact submitted text selectable even after a retry confirms context. Never replace
    // newer input to recover it; the reader can copy these words back when ready to try again.
    return [context, question === undefined ? ''
      : `Question not sent. Unsent question (copy to reuse): “${question}”`]
      .filter(Boolean).join(' ');
  }

  private confirmContext(sessionId: string): void {
    this.contextNotices.delete(sessionId);
    this.contextRetryDelay = 1000;
    this.clearContextRetry();
    this.host.requestUpdate();
    this.scheduleContextRetry();
  }

  private clearContextRetry(): void {
    if (this.contextRetryTimer !== null) clearTimeout(this.contextRetryTimer);
    this.contextRetryTimer = null;
  }

  /** Only the shown conversation needs background retries; opening another reads it afresh. */
  private scheduleContextRetry(): void {
    const id = this.host.activeConversationId();
    if (this.contextRetryTimer !== null || !this.host.connected() || id === null
      || !this.contextNotices.has(id) || this.contextWrites.has(id)) return;
    this.contextRetryTimer = setTimeout(() => {
      this.contextRetryTimer = null;
      this.contextRetryDelay = Math.min(this.contextRetryDelay * 2, 30000);
      if (this.host.activeConversationId() === id && !this.contextWrites.has(id)) void this.refreshHistory(id);
      else this.scheduleContextRetry();
    }, this.contextRetryDelay);
  }

  retryContextOnReconnect = (): void => {
    this.checkRetainedAttempt(true);
    this.host.retryRecords();
    this.clearContextRetry();
    this.contextRetryDelay = 1000;
    const id = this.host.activeConversationId();
    if (id !== null && this.contextNotices.has(id) && !this.contextWrites.has(id)) {
      void this.refreshHistory(id);
    }
  };

  /**
   * What the effective context does with each turn on screen. Computed ONCE per render and handed to
   * both consumers (the transcript's frames, the bar's aggregate) plus every act below, so what the
   * window renders and what it writes are derived from one reading of one record.
   */
  turnContexts(): readonly Sv3TurnContext[] {
    const session = this.activeSession;
    if (session === null) return [];
    return projectSv3TurnContexts(session.turns, session.history);
  }

  /** Keep controls usable: capture their targets now, then send each act in selection order. */
  runContextAction(detail: Sv3ContextAction): Promise<void> {
    const session = this.activeSession;
    if (session === null) return Promise.resolve();
    if (detail.action === 'inspect') {
      this.host.openContextInspector();
      return Promise.resolve();
    }
    this.host.revokeHumanTask();
    const turn = detail.turnId === undefined ? null : sv3TurnContextFor(this.turnContexts(), detail.turnId);
    const previous = this.contextWrites.get(session.id) ?? Promise.resolve();
    const pending = previous.then(() => this.writeContext(session.id, detail, turn)).finally(() => {
      if (this.contextWrites.get(session.id) === pending) this.contextWrites.delete(session.id);
      this.host.requestUpdate();
      this.scheduleContextRetry();
    });
    this.contextWrites.set(session.id, pending);
    this.clearContextRetry();
    this.host.requestUpdate();
    return pending;
  }

  private async writeContext(
    sessionId: string,
    detail: Sv3ContextAction,
    turn: Sv3TurnContext | null,
  ): Promise<void> {
    switch (detail.action) {
      case 'floor': {
        const messageId = turn?.floorMessageId ?? null;
        if (messageId === null) return;
        await this.settleContextWrite(sessionId, await this.transport.setContextFloor(sessionId, messageId), CONTEXT_FLOOR_FAILED);
        return;
      }
      case 'restore':
        await this.settleContextWrite(sessionId, await this.transport.clearContextFloor(sessionId), CONTEXT_RESTORE_FAILED);
        return;
      case 'compact': {
        const messageId = turn?.floorMessageId ?? null;
        if (messageId === null) return;
        this.compacting = true;
        try {
          const summary = await this.transport.compactContext(sessionId, messageId);
          await this.settleContextWrite(sessionId, summary !== null, CONTEXT_COMPACT_FAILED);
        } finally {
          this.compacting = false;
        }
        return;
      }
      case 'summary':
        if (detail.text === undefined) return;
        await this.settleContextWrite(sessionId, await this.transport.editContextFloorSummary(sessionId, detail.text), CONTEXT_SUMMARY_FAILED);
        return;
      case 'exclude':
      case 'include': {
        const ids = turn?.messageIds ?? [];
        if (ids.length === 0) return;
        const excluded = detail.action === 'exclude';
        const landed = await this.excludeInTurn(sessionId, ids, excluded);
        await this.settleContextWrite(sessionId, landed.length === ids.length,
          excluded ? CONTEXT_EXCLUDE_FAILED : CONTEXT_INCLUDE_FAILED);
        return;
      }
      case 'include-all': {
        // Derive the WHOLE saved ledger when this queued action runs, including prior exclusions.
        // If the previous refresh failed we must read it first, rather than omit unknown ids.
        if (this.contextNotices.has(sessionId)) {
          if (!await this.refreshHistory(sessionId, true)) {
            this.host.announceWarning(CONTEXT_INCLUDE_ALL_FAILED);
            return;
          }
          this.confirmContext(sessionId);
        }
        const ids = sv3ExcludedMessageIds(this.host.session(sessionId)?.history ?? null);
        if (ids.length === 0) return;
        const landed = await this.excludeInTurn(sessionId, ids, false);
        await this.settleContextWrite(sessionId, landed.length === ids.length, CONTEXT_INCLUDE_ALL_FAILED);
        return;
      }
      default:
        return;
    }
  }

  /**
   * Several exclusion toggles, ONE AT A TIME — never `Promise.all`.
   *
   * The endpoint's write is read-modify-write over a SHARED document and the store takes no lock:
   * `FileConversationStore.toggleStringInMeta` reads `meta.json`, adds or removes the id, and calls
   * `writeMetaAtomic` (`:503-527`). The write is atomic per file; the SEQUENCE is not, and each POST
   * is served on its own HTTP thread. Two toggles in flight together therefore race on one snapshot,
   * and the loser's id is silently dropped.
   *
   * What that costs here is specific and invisible: a turn's two ids go out together, one lands, and
   * the turn is left HALF excluded — the prompt carries a question whose answer was dropped, while
   * the transcript dims the turn either way (`hasExcluded` is true on one id as on two). Serializing
   * removes the race rather than detecting it afterwards, and it is what the shipped window already
   * does (`views/UnifiedChatView.ts:1767-1776`, a sequential `for`-await).
   *
   * Returns the successful ids to report partial refusal; only a saved-history read confirms state.
   */
  private async excludeInTurn(
    sessionId: string,
    ids: readonly string[],
    excluded: boolean,
  ): Promise<readonly string[]> {
    const landed: string[] = [];
    for (const id of ids) {
      if (await this.transport.setMessageExcluded(sessionId, id, excluded)) landed.push(id);
    }
    return landed;
  }

  /** A write's response alone cannot confirm saved context, including a lost or partial response. */
  private async settleContextWrite(sessionId: string, ok: boolean, failure: string): Promise<void> {
    if (await this.refreshHistory(sessionId, true)) {
      this.confirmContext(sessionId);
    } else {
      this.markContextUnconfirmed(sessionId);
    }
    if (!ok) this.host.announceWarning(failure);
  }

  /** Wait for queued actions, then confirm uncertainty before a question can consume this state. */
  private async confirmContextForAsk(sessionId: string): Promise<boolean> {
    while (this.contextWrites.has(sessionId)) await this.contextWrites.get(sessionId);
    if (!this.contextNotices.has(sessionId) && !this.historyReads.has(sessionId)
      && this.host.session(sessionId)?.history != null) return true;
    const read = await this.refreshHistory(sessionId);
    // A control used during that read must also settle before this question is sent.
    if (this.contextWrites.has(sessionId)) return this.confirmContextForAsk(sessionId);
    if (read) return true;
    this.markContextUnconfirmed(sessionId);
    this.scheduleContextRetry();
    return false;
  }

  /** Resolve saved context before opening a turn. Null refuses; [] means confirmed empty context. */
  async followUpContext(
    sessionId: string,
  ): Promise<ReturnType<typeof sv3FollowUpContext> | null> {
    if (!await this.confirmContextForAsk(sessionId)
      || !this.host.connected() || this.host.activeConversationId() !== sessionId) return null;
    return this.followUpContextOf(sessionId);
  }

  followUpContextOf(sessionId: string): ReturnType<typeof sv3FollowUpContext> | null {
    const session = this.host.session(sessionId);
    if (session?.history == null) return null;
    return sv3FollowUpContext(session.turns, projectSv3TurnContexts(session.turns, session.history), session.history);
  }

  buildContextInspectorView(): InspectorView {
    const session = this.activeSession;
    return projectSv3ContextInspector(
      session?.turns ?? [],
      this.turnContexts(),
      session?.history ?? null,
      session?.contextUsage ?? null,
      this.host.contextWindow(),
    );
  }

}
