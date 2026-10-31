// SPDX-License-Identifier: Apache-2.0
/** Ask lifecycle and conversation-local stream handles. The view supplies the rendered projection. */
import type { SelectionPayload } from '../../../api/types/selection.js';
import type { ReasoningController, ReasoningBlock } from '../../controllers/ReasoningController.js';
import { StopAttempt } from '../../state/stopAttempt.js';
import { hasActiveFilter, restrictionWire } from '../../state/searchFiltersState.js';
import { reasonFor } from '../../state/readinessNotice.js';
import { localizeResourceKey } from '../../../i18n/resourceCatalog.js';
import type { Sv3StopState } from './Sv3Main.js';
import type { Sv3DraftDomain, HumanTask } from './sv3-draft-domain.js';
import type { Sv3ContextDomain } from './sv3-context-domain.js';
import type { Sv3SearchController, Sv3AskScope } from './sv3-search.js';
import type { Sv3Session, Sv3TurnRef, Sv3TurnEvidence, Sv3TurnStatus } from './sv3-sessions.js';
import { sv3ShouldGenerateTitle } from './sv3-sessions.js';
import type { ThreadResponse } from '../unifiedThreadClient.js';
import { projectSv3RecordTurns } from './sv3-record.js';
import type { RecoveryShape } from './sv3-recovery.js';
import type { sv3FollowUpContext } from './sv3-context.js';
import type { Sv3ComposerState } from './fixtures.js';
import { restrictionLabels } from './sv3-filter-input.js';
import { sv3Ask, sv3StopAsk, SV3_ASK_SHAPE_ID, SV3_MODEL_SHAPE_ID } from './sv3-ask.js';

type AskDraft = Pick<Sv3DraftDomain, 'draftMayIssue' | 'preparedHandoff' | 'humanUnavailable' | 'reserveHumanTask'
  | 'humanTask' | 'humanOwned' | 'recoveryError' | 'saveRecovery' | 'handoffNotice' | 'handoffBlocked'
  | 'leaveResultsBySending' | 'placeScopes' | 'assigningHumanDraft' | 'revokeHumanTask' | 'retireHumanTask'
  | 'scheduleHumanRetry'>;
type AskContext = Pick<Sv3ContextDomain, 'followUpContext' | 'refuseQuestion' | 'clearRefusedQuestion'
  | 'recordUsage' | 'refreshRecord' | 'checkRetainedAttempt' | 'invalidateContext'>;
type AskSearch = Pick<Sv3SearchController, 'recordTurnScope' | 'pendingScope' | 'clearScope' | 'prepare'>;

interface AskHost {
  activeConversationId(): string | null;
  session(id: string): Sv3Session | null;
  connected(): boolean;
  streaming(): boolean;
  shapeId(): string | undefined;
  apiBase(): string;
  draft(): AskDraft;
  context(): AskContext;
  search(): AskSearch;
  submitTurn(question: string, now: number, kind: 'ask' | 'model', target: string, shape: RecoveryShape,
    selectionKind: SelectionPayload['kind'] | undefined, dispatchTurnId: string): Sv3TurnRef | null;
  dropTurn(ref: Sv3TurnRef): void;
  setReasoning(ref: Sv3TurnRef, blocks: ReasoningBlock[]): void;
  settleTurn(ref: Sv3TurnRef, status: Exclude<Sv3TurnStatus, 'streaming' | 'left'>,
    now: number, detail: string, model: string | null): void;
  appendDelta(ref: Sv3TurnRef, text: string): void;
  setEvidence(ref: Sv3TurnRef, evidence: Sv3TurnEvidence): void;
  setRewrite(ref: Sv3TurnRef, standalone: string): void;
  projectActiveAsk(streaming: boolean, stopping: Sv3StopState | null, checkingTurnId: string | null): void;
  createReasoning(): ReasoningController;
  runInFlight(): boolean;
  modelAnswerUnavailableReason(): string;
  currentModelLabel(): string | null;
  claimConversation(id: string): void;
  setComposerState(state: Sv3ComposerState): Promise<void>;
  focusComposer(): Promise<void>;
  writeDraft(text: string): void;
  startActivity(shapeId: string, now: number, stop: () => void): void;
  settleAiActivity(): void;
  noteRefusedWhileLocked(): void;
  announce(message: string, severity: 'info' | 'warning'): void;
  requestUpdate(): void;
}

type AskTransport = Pick<typeof import('../../state/conversationListStore.js'),
  'createConversationId' | 'rememberServerConversation' | 'loadConversations' | 'generateConversationTitle'>;

export class Sv3AskDomain {
  constructor(private readonly host: AskHost, private readonly transport: AskTransport) {
    this.askReasoning = host.createReasoning();
  }

  private get draftDomain(): AskDraft { return this.host.draft(); }
  private get contextDomain(): AskContext { return this.host.context(); }
  private get search(): AskSearch { return this.host.search(); }
  private get activeSession(): Sv3Session | null {
    const id = this.host.activeConversationId();
    return id === null ? null : this.host.session(id);
  }
  private get streaming(): boolean { return this.host.streaming(); }

  get contextPending(): boolean { return this.contextAskPending.size > 0; }
  summaryInput(turnId: string): { scope: Sv3AskScope | null; selection: SelectionPayload | null } | undefined {
    return this.summaryInputs.get(turnId);
  }
  structuredInput(turnId: string): { schema: string; scope: Sv3AskScope | null } | undefined {
    return this.structuredInputs.get(turnId);
  }
  liveReasoning(): ReasoningController | null { return this.streaming ? this.askReasoning : null; }
  liveReasoningTurnId(): string | null { return this.streaming ? this.askReasoningTurnId : null; }
  stop(): void { this.askStop?.(); }
  abortAsk(): void { this.askAbort?.abort(); }

  ownsAttempt(task: HumanTask): boolean {
    const ask = this.liveAsks.get(task.saved.target ?? '');
    return ask !== undefined && !ask.abort.signal.aborted
      && ask.dispatchTurnId === task.saved.dispatchTurnId;
  }

  retainedAttempt(): { target: string; key: string } | null {
    const task = this.draftDomain.humanTask;
    return task?.saved.delivery === 'unknown' && task.saved.target !== null
      && task.saved.dispatchTurnId !== undefined && !this.ownsAttempt(task)
      ? { target: task.saved.target, key: JSON.stringify([task.saved.id, task.saved.target, task.saved.dispatchTurnId]) } : null;
  }

  recordArrival(id: string, record: ThreadResponse): void {
    const task = this.draftDomain.humanTask;
    if (record.incomplete || task?.saved.delivery !== 'unknown' || task.saved.target !== id
      || task.saved.dispatchTurnId === undefined || this.ownsAttempt(task)) return;
    const outcome = projectSv3RecordTurns(record.events).find(turn => turn.kind !== 'agent'
      && turn.shapeId === task.saved.shape && turn.dispatchTurnId === task.saved.dispatchTurnId && turn.savedOutcome);
    if (outcome === undefined) return;
    this.contextDomain.invalidateContext(id);
    this.draftDomain.retireHumanTask(task);
  }

  disconnect(): void {
    for (const ask of this.liveAsks.values()) { ask.abort.abort(); ask.reasoning.destroy(); }
    this.askReasoning.destroy();
  }

  /** The in-flight ask's abort handle; null exactly when no response is streaming. */
  private askAbort: AbortController | null = null;
  /** The reader's Stop for that ask: the Engine is asked to end the turn, and its stream says how. */
  private askStop: (() => void) | null = null;
  private readonly liveAsks = new Map<string, {
    abort: AbortController; stop: () => void; reasoning: ReasoningController;
    dispatchTurnId: string; turnId: string; stopping: Sv3StopState | null; checkingTurnId: string | null;
  }>();

  /** The slot belongs to the place on screen; streams keep their own handles until they settle. */
  syncActiveAsk(): void {
    const ask = this.liveAsks.get(this.host.activeConversationId() ?? '');
    const streaming = ask !== undefined;
    this.askAbort = ask?.abort ?? null;
    this.askStop = ask?.stop ?? null;

    this.askReasoningTurnId = ask?.turnId ?? null;
    if (ask !== undefined) this.askReasoning = ask.reasoning;
    this.host.projectActiveAsk(streaming, ask?.stopping ?? null, ask?.checkingTurnId ?? null);
  }
  /**
   * Conversations this window has already asked the model to name (inventory A11). Once per
   * conversation: the title is the row's label forever, so a second generation would rename a row
   * the reader has already learned to recognise.
   */
  private readonly titledSessionIds = new Set<string>();
  /**
   * The SHARED reasoning model for the ask that is streaming (inventory C9). One controller, reset
   * per ask, exactly as the shipped window holds it (`views/UnifiedChatView.ts:645`) — its blocks are
   * handed to the turn at the terminal, which is what makes a past turn's thinking survive the reset.
   */
  private askReasoning: ReasoningController;
  /**
   * The turn the ask controller above is streaming for, or null (tempdoc 848 §2.7). Handed down so
   * the transcript binds the live thinking to ONE turn by id, exactly as it binds the run feed —
   * `streaming` status alone is per-turn and reachable for two turns at once.
   */
  private askReasoningTurnId: string | null = null;

  private readonly contextAskPending = new Set<string>();
  private readonly summaryInputs = new Map<string, { scope: Sv3AskScope | null; selection: SelectionPayload | null }>();
  private readonly structuredInputs = new Map<string, { schema: string; scope: Sv3AskScope | null }>();
  structuredError = '';
  askFilterError = '';

  structuredNote(turnId: string): string {
    const inputs = this.structuredInputs.get(turnId);
    return inputs === undefined
      ? 'Original Structured inputs are unavailable. Prepare a new Structured task and review its schema and inputs.'
      : `Runs Structured with schema ${inputs.schema}; inputs: ${inputs.scope?.paths.join(', ') ?? 'typed text'} and this conversation.`;
  }

  modelAnswer(id: string | undefined): void {
    const turn = this.activeSession?.turns.at(-1);
    if (turn === undefined || turn.shapeId === 'core.extract' || turn.id !== id || this.streaming
      || this.contextAskPending.size > 0 || this.host.runInFlight()) return;
    if (turn.status !== 'no-evidence'
      && !(turn.kind === 'model' && ['failed', 'halted', 'refused'].includes(turn.status))) return;
    const reason = this.host.modelAnswerUnavailableReason();
    if (reason !== '') {
      this.host.announce(reason, 'warning');
      return;
    }
    void this.runAsk(turn.question, null, undefined, true, { turnAction: true, record: turn.id });
    void this.host.focusComposer();
  }

  /** The complete issuance sequence, including ownership checks on both sides of each await. */
  async runAsk(rawQuestion: string, scope: Sv3AskScope | null = null, schema?: string, modelAnswer = false,
    options: { task?: HumanTask; continuation?: boolean; turnAction?: boolean; record?: string; authored?: string } = {}): Promise<void> {
    let question = rawQuestion.trim();
    if (question === '' || this.streaming) return;
    if (!options.task && !options.turnAction && !this.draftDomain.draftMayIssue(false)) return;
    const handoff = options.task ? { shapeId: options.task.saved.shape, selection: options.task.selection }
      : modelAnswer ? null : this.draftDomain.preparedHandoff;
    modelAnswer = modelAnswer || handoff?.shapeId === SV3_MODEL_SHAPE_ID
      || (schema === undefined && handoff === null && (this.host.shapeId() === SV3_MODEL_SHAPE_ID || this.activeSession?.turns.at(-1)?.shapeId === SV3_MODEL_SHAPE_ID));
    const shapeId: RecoveryShape = modelAnswer ? SV3_MODEL_SHAPE_ID : schema !== undefined ? 'core.extract' : (handoff?.shapeId as RecoveryShape | null) ?? SV3_ASK_SHAPE_ID;
    const unavailable = this.draftDomain.humanUnavailable(shapeId, options.continuation);
    if (unavailable !== '') { this.host.announce(unavailable, 'warning'); return; }
    if (schema !== undefined) {
      try {
        const parsed: unknown = JSON.parse(schema);
        if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('object');
      } catch { this.structuredError = 'Enter a JSON schema object.'; this.host.requestUpdate(); return; }
    }
    // Keep the actual trace visible in the durable user turn even if its instructions were edited.
    if (shapeId === 'core.summarize' && handoff?.selection?.kind === 'search-trace') {
      const trace = handoff.selection.summary;
      if (typeof trace === 'string' && trace && !question.includes(trace)) question += `\n\nRanking trace:\n${trace}`;
    }
    if (schema !== undefined && (scope?.paths.length ?? 0) > 5) {
      this.structuredError = 'Choose at most 5 files for one extraction.';
      this.host.requestUpdate();
      return;
    }
    this.structuredError = '';
    const task = options.task ?? this.draftDomain.reserveHumanTask(question, modelAnswer ? null : scope, schema, shapeId,
      modelAnswer ? null : handoff?.selection ?? null, options.turnAction !== true, options.record ?? null, options.authored);
    if (task === null || this.draftDomain.humanTask !== task) return;
    if (options.task && !this.draftDomain.humanOwned(task)) return;
    const draft = task.draft;
    const sessionId = this.host.activeConversationId();
    if (sessionId !== null && this.contextAskPending.has(sessionId)) return;
    let context: ReturnType<typeof sv3FollowUpContext> = [];
    if (sessionId !== null && !modelAnswer) {
      this.contextAskPending.add(sessionId);
      try {
        const resolved = await this.contextDomain.followUpContext(sessionId);
        if (resolved === null || !this.host.connected() || this.host.activeConversationId() !== sessionId) {
          this.contextDomain.refuseQuestion(sessionId, question);
          this.host.requestUpdate();
          return;
        }
        context = resolved;
        this.contextDomain.clearRefusedQuestion(sessionId);
      } finally {
        this.contextAskPending.delete(sessionId);
      }
    }
    if (!this.host.connected() || this.host.activeConversationId() !== sessionId || this.draftDomain.humanTask !== task || task.abandoned) return;
    if (options.task && !this.draftDomain.humanOwned(task)) return;
    if (options.task && task.context !== null && JSON.stringify(context) !== JSON.stringify(task.context)) {
      task.revoked = true;
      this.draftDomain.recoveryError = 'Saved context changed. Review the retained question before a new submission.';
      this.host.requestUpdate();
      return;
    }
    if (this.draftDomain.humanUnavailable(shapeId, options.continuation) !== '') { this.host.requestUpdate(); return; }
    task.context = context.map(m => ({ ...m }));
    task.saved.target = sessionId ?? this.transport.createConversationId();
    const dispatchId = crypto.randomUUID();
    task.saved.dispatchTurnId = dispatchId;
    task.saved.delivery = 'unknown';
    task.refusal = null;
    if (!this.draftDomain.saveRecovery(task) || !this.host.connected() || this.host.activeConversationId() !== sessionId
      || this.draftDomain.humanTask !== task || task.abandoned || this.draftDomain.humanUnavailable(shapeId, options.continuation) !== '') return;
    if (options.task && !this.draftDomain.humanOwned(task)) return;
    if (!modelAnswer && task.ownsComposer && draft.port.isCurrent(draft) && this.draftDomain.preparedHandoff === handoff) {
      this.draftDomain.preparedHandoff = null;
      this.draftDomain.handoffNotice = ''; this.draftDomain.handoffBlocked = false;
    }
    this.draftDomain.leaveResultsBySending();
    const ref = this.host.submitTurn(task.question, Date.now(), modelAnswer ? 'model' : 'ask', task.saved.target!, shapeId, task.selection?.kind, dispatchId);
    if (ref === null) return;
    if (schema !== undefined) this.structuredInputs.set(ref.turnId, { schema, scope });
    if (shapeId === 'core.summarize') this.summaryInputs.set(ref.turnId, { scope, selection: handoff?.selection ?? null });
    // WP1.1 S1.7 — remembered by turn so its Retry/Edit can keep the same files. Window-local: the
    // store keeps no scope with a turn, so after reload its original scope is unknown and Retry/Edit must refuse.
    if (shapeId === SV3_ASK_SHAPE_ID) this.search.recordTurnScope(ref.turnId, task.scope, task.filters);
    if (schema === undefined && task.ownsComposer && draft.port.isCurrent(draft)
      && this.search.pendingScope === task.scopeOwner) this.search.clearScope();
    if (!modelAnswer) this.draftDomain.placeScopes.delete(ref.sessionId);
    this.host.claimConversation(ref.sessionId);
    // The submitted question is fixed; input typed while confirmation waited belongs to the reader.
    if (task.ownsComposer && schema === undefined && draft.port.isCurrent(draft)) {
      this.draftDomain.assigningHumanDraft = true;
      try { draft.port.clearIf(draft); } finally { this.draftDomain.assigningHumanDraft = false; }
      task.draft = draft.port.snapshot();
    }
    this.draftDomain.saveRecovery(task);
    const sentDraft = draft.port.snapshot();
    void this.host.setComposerState('docked');

    const abort = new AbortController();
    // Stop is explicit: the Engine is told to end THIS turn, and the stream is KEPT until the Engine
    // ends it. Only the Engine knows whether the Stop landed before the answer was saved, so only its
    // terminal says "stopped" (an error with CANCELLED, saved as stopped) or "finished" (`done`), and
    // every word it sent before the Stop landed, which it saves, reaches the turn. A question still
    // waiting to be sent (for the conversation's settings, or for Engine capacity) is sent all the
    // same, and the Engine records it as stopped. Only a window that unmounts or starts afresh lets
    // the stream go (`abortAsk`): the Engine finishes and saves the turn, as it does for a closed tab.
    // The id the Stop names is this dispatch's own, never the turn's place in the list, which another
    // window or a reload can hand out again: the Engine ignores a Stop for a turn that finished.
    const reasoning = this.host.createReasoning();
    const stopAttempt = new StopAttempt();
    const stop = (): void => {
      if (stopAttempt.pending || stopAttempt.outcome === 'accepted') return;
      stopAttempt.latch();
      this.draftDomain.revokeHumanTask();
      this.draftDomain.saveRecovery();
      const ask = this.liveAsks.get(ref.sessionId);
      if (ask === undefined) return;
      ask.stopping = { turnId: ref.turnId, state: 'stopping' };
      this.syncActiveAsk();
      void stopAttempt.deliver((signal) => sv3StopAsk(this.host.apiBase(), ref.sessionId, dispatchId, signal)).then(() => {
        if (stopAttempt.outcome === 'accepted' || this.liveAsks.get(ref.sessionId)?.abort !== abort) return;
        // Refused or unanswered: the answer goes on, and Stop can be pressed again.
        ask.stopping = { turnId: ref.turnId, state: 'failed' };
        this.syncActiveAsk();
      });
    };
    this.liveAsks.set(ref.sessionId, { abort, stop, reasoning, dispatchTurnId: dispatchId, turnId: ref.turnId, stopping: null, checkingTurnId: null });
    this.syncActiveAsk();
    // The controller is per-ASK, not per-turn: it accumulates and times one stream's thinking, and
    // the blocks it finalizes are written onto the turn below. Resetting here is what stops the
    // previous answer's thinking appearing under this one.
    reasoning.reset();
    // The app-wide activity indicator, raised for the duration and settled at the terminal below
    // (inventory G11). Cancelling through the SAME stop the Stop control uses, so there is one way
    // to stop this turn however the reader reaches it.
    this.host.startActivity(shapeId, Date.now(), stop);
    // Every terminal below settles the SAME ref the dispatch opened, so a reader who claims another
    // session mid-stream still gets the answer written where it was asked.
    const settle = (
      status: 'complete' | 'halted' | 'refused' | 'failed' | 'no-evidence' | 'left',
      detail = '',
    ): void => {
      if (status === 'left') {
        // Not stopped and not finished here: the Engine goes on and saves the turn, and the record
        // shows what it saved the next time this conversation is read.
        this.host.dropTurn(ref);
      } else {
        // The thinking is closed out and RECORDED on the turn at every terminal, including a halt:
        // what the model thought before the reader stopped it was really produced, and dropping it
        // would lose evidence the window actually received (inventory C9).
        reasoning.finalize();
        this.host.setReasoning(ref, [...reasoning.reasoningBlocks]);
        this.host.settleTurn(
          ref,
          status,
          Date.now(),
          detail,
          // The model AS OF THIS TERMINAL (inventory C1). Recorded rather than read at render, so a
          // transcript re-read after a model swap still names the one that wrote each answer.
          this.host.currentModelLabel(),
        );
        if (schema !== undefined && (status === 'complete' || status === 'failed' || status === 'refused')) {
          // The existing window message channel supplies the polite live region. Failures also
          // stay in the transcript; this notice must not interrupt speech or take keyboard focus.
          this.host.announce(status === 'complete' ? 'Extraction complete.' : `Extraction failed: ${detail}`, 'info');
        }
      }
      // Only THIS dispatch's terminal may clear the window's busy state: a later ask has already
      // installed its own controller, and clearing it here would strand a live stream with a Send
      // control in the slot.
      if (this.liveAsks.get(ref.sessionId)?.abort === abort) this.liveAsks.delete(ref.sessionId);
      reasoning.destroy();
      this.syncActiveAsk();
      if (this.liveAsks.size === 0 && !this.host.runInFlight()) this.host.settleAiActivity();
      this.contextDomain.checkRetainedAttempt();
      this.host.requestUpdate();
    };
    const currentAttempt = (): boolean => this.draftDomain.humanTask === task && task.saved.dispatchTurnId === dispatchId;
    await sv3Ask(
      {
        apiBase: this.host.apiBase(),
        schema,
        shapeId,
        selection: task.selection,
        question: task.question,
        conversationId: ref.sessionId,
        modelAnswer,
        // The rung AS OF THIS SEND (tempdoc 822 Phase F10). Read here rather than inside the ask
        // client, so the one place a question is dispatched is also the one place its parameters
        // are decided — and a rung changed mid-stream cannot rewrite a request already in flight.
        effort: task.saved.effort,
        // The chosen files' PATHS (what the backend's `docIds` filter matches), or none at all —
        // never an empty list standing in for a choice, which would mean the whole index.
        ...(task.scope !== null ? { docIds: task.scope.paths } : {}),
        filters: restrictionWire(task.filters),
        context,
        turnId: dispatchId,
        signal: abort.signal,
      },
      {
        onDelta: (text) => {
          if (!currentAttempt()) return;
          if (text) this.transport.rememberServerConversation(ref.sessionId);
          this.host.appendDelta(ref, text);
        },
        onChecking: (checking) => {
          if (!currentAttempt()) return;
          const ask = this.liveAsks.get(ref.sessionId);
          if (ask?.abort !== abort) return;
          ask.checkingTurnId = checking ? ref.turnId : null;
          this.syncActiveAsk();
        },
        onEvidence: (evidence) => {
          if (!currentAttempt()) return;
          this.host.setEvidence(ref, evidence);
        },
        onReasoning: (payload) => {
          if (!currentAttempt()) return;
          reasoning.handleReasoningChunk(payload);
        },
        onRewrite: (standalone) => {
          if (!currentAttempt()) return;
          this.host.setRewrite(ref, standalone);
        },
        onDone: (usage) => {
          if (!currentAttempt()) { settle('left'); return; }
          if (schema !== undefined && task.ownsComposer && draft.port.isCurrent(draft) && this.search.pendingScope === task.scopeOwner) this.search.clearScope();
          if (schema !== undefined && task.ownsComposer && draft.port.isCurrent(draft)) {
            this.draftDomain.assigningHumanDraft = true;
            try { draft.port.clearIf(draft); } finally { this.draftDomain.assigningHumanDraft = false; }
          }
          this.draftDomain.retireHumanTask(task);
          // Tempdoc 610 §E.4 — the occupancy this turn reported, recorded on the CONVERSATION that
          // spent it (a terminal that reported none leaves the previous reading standing rather than
          // replacing it with a zero the backend never sent).
          this.contextDomain.recordUsage(ref.sessionId, usage);
          // The answer has arrived, so the thinking that preceded it is over — the shipped window
          // ends it on the first content chunk; ending it at the terminal is the same edge for a
          // window whose transcript renders the block beside the answer rather than instead of it.
          settle('complete');
          // The record now holds this turn; the store now holds the conversation. Both refreshed so
          // a reload projects exactly what is on screen (inventory A1 + D1).
          void this.transport.loadConversations();
          void this.contextDomain.refreshRecord(ref.sessionId);
          void this.maybeGenerateTitle(ref.sessionId);
        },
        // The lock's refusal is worded by the ONE reason vocabulary, not re-phrased here.
        onRefused: () => {
          if (!currentAttempt()) { settle('left'); return; }
          task.saved.delivery = 'unaccepted';
          task.revoked = true;
          this.draftDomain.saveRecovery(task);
          settle('refused', reasonFor('conversations.locked').wording);
          // The SERVER just said the store is locked, which is newer than any poll (inventory E4/E5).
          this.host.noteRefusedWhileLocked();
        },
        onHalted: () => {
          if (!currentAttempt()) { settle('left'); return; }
          this.draftDomain.retireHumanTask(task);
          settle('halted');
          // Saved as stopped: the record gives the turn its store ids (so it can be retried,
          // edited or branched) and the list its conversation, as after a finished answer.
          void this.transport.loadConversations();
          void this.contextDomain.refreshRecord(ref.sessionId);
        },
        onLeft: () => {
          if (currentAttempt() && this.host.connected()) { task.revoked = true; this.draftDomain.saveRecovery(task); }
          settle('left');
        },
        onFailed: (message, code, refusal) => {
          if (!currentAttempt()) { settle('left'); return; }
          if (refusal !== null && refusal !== undefined) {
            task.saved.delivery = 'unaccepted';
            task.refusal = refusal;
            task.retryAt = Date.now() + (refusal.retryAfterMs ?? 0);
            this.draftDomain.scheduleHumanRetry(task);
            this.draftDomain.saveRecovery(task);
            settle('left');
            return;
          }
          // A terminal Structured turn without accepted output is final, never a retryable
          // delivery uncertainty or an answer for the next turn's context.
          if (code === 'NO_CONTENT') this.draftDomain.retireHumanTask(task);
          else { task.saved.delivery = 'unknown'; task.revoked = true; this.draftDomain.saveRecovery(task); }
          if (code === 'INVALID_REQUEST' && /pathPrefix|modifiedAt|sizeBytes/.test(message)) this.askFilterError = message;
          const restricted = hasActiveFilter(task.filters);
          if (code === 'NO_CONTENT' && restricted) message += ` ${localizeResourceKey('search.filters.no-content')} ${restrictionLabels(task.filters).map(f => f.label).join('; ')}`;
          const noEvidence = !restricted && shapeId === SV3_ASK_SHAPE_ID && code === 'NO_CONTENT' && scope === null;
          settle(noEvidence ? 'no-evidence' : 'failed', noEvidence ? '' : message);
          void this.transport.loadConversations();
          void this.contextDomain.refreshRecord(ref.sessionId);
          if (handoff !== null && this.host.activeConversationId() === ref.sessionId && task.ownsComposer && sentDraft.port.isCurrent(sentDraft)) {
            this.draftDomain.preparedHandoff = handoff;
            this.search.prepare(scope);
            this.draftDomain.assigningHumanDraft = true;
            try { this.host.writeDraft(task.saved.raw); } finally { this.draftDomain.assigningHumanDraft = false; }
            this.draftDomain.saveRecovery();
          }
        },
      },
    );
  }

  private async maybeGenerateTitle(sessionId: string): Promise<void> {
    if (this.titledSessionIds.has(sessionId)) return;
    const session = this.host.session(sessionId);
    if (session === null || !sv3ShouldGenerateTitle(session)) return;
    this.titledSessionIds.add(sessionId);
    const turn = session.turns.find((t) => t.kind === 'ask' && t.status === 'complete');
    if (turn === undefined) return;
    await this.transport.generateConversationTitle(sessionId, turn.question, turn.answer);
  }

}
