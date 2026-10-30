// SPDX-License-Identifier: Apache-2.0
/** This window's run bookkeeping. The shared controller owns execution; the host owns turns. */
import { STOP_RESPONSE_WAIT_MS } from '../../state/stopAttempt.js';
import type { AgentSessionController } from '../../controllers/AgentSessionController.js';
import type { LiveRunRow } from '../../controllers/liveRuns.js';
import { directiveAvailable, dispatchRunControl, type RunControlRefusal } from '../../controllers/runControlIntent.js';
import { agentAnswerEvidence, agentDeltaEvidence } from '../../components/chat/agentEvidence.js';
import type { Sv3RunDecision, Sv3StopState } from './Sv3Main.js';
import type { ThreadResponse } from '../unifiedThreadClient.js';
import type { Sv3Turn, Sv3TurnEvidence, Sv3TurnRef, Sv3TurnStatus } from './sv3-sessions.js';
import { sv3RecordItemStopsRun } from './sv3-honesty.js';
import {
  deriveSv3RunPhase, hasServerAcknowledgedLocalDispatch, projectSv3RunFeed,
  projectSv3RunPrompts, shouldAutoContinue, sv3RunNeedsPresence, sv3RunOutcome,
  sv3RunPresenceStart, sv3RunPresenceTitle, sv3RunSessionStatus, sv3UnconfirmedSteerNotes,
  SV3_RUN_FEED_EMPTY, type Sv3RunLocal, type Sv3RunFeed, type Sv3RunFeedItem,
  type Sv3RunTurnState, type Sv3RunView,
} from './sv3-run.js';

interface RunHost {
  createConversationId(): string;
  activeConversationId(): string | null;
  showingResults(): boolean;
  hasPreparedHandoff(): boolean;
  connected(): boolean;
  turn(ref: Sv3TurnRef): Sv3Turn | undefined;
  lastTurn(id: string): Sv3Turn | undefined;
  adoptRun(title: string, now: number, id: string, turnId: string | null): Sv3TurnRef;
  ensureConversation(id: string, now: number): void;
  setEvidence(ref: Sv3TurnRef, evidence: Sv3TurnEvidence): void;
  settleRun(ref: Sv3TurnRef, outcome: Exclude<Sv3TurnStatus, 'streaming'>, calls: number, now: number, disposition: string | null): void;
  applyRecord(id: string, record: ThreadResponse): void;
  preserveFailedReceipt(ref: Sv3TurnRef, detail: string): void;
  refreshRecord(id: string): Promise<boolean>;
  announceStop(text: string): void;
  persistSteerDraft(): boolean;
  dockComposer(): void;
  focusDecision(): void;
  requestUpdate(): void;
}

interface RunAgents {
  peek(): AgentSessionController | null;
  get(): AgentSessionController;
  discover(): Promise<string | null>;
}

interface RunClock {
  now(): number;
  setTimeout(callback: () => void, ms: number): number;
  clearTimeout(timer: number): void;
}

// Stop disappears on DONE; an in-flight DELETE can still lag (DS4 observed ~1.1s).
// Allow 5s for cross-window/network delivery. The Engine has no finished-run deadline;
// this bounds clean-DONE reads, never our own Halt or Stop awaiting its checkpoint.
const COMPLETED_RECORD_GRACE_MS = 5000;

export class Sv3RunDomain {
  run: Sv3RunLocal | null = null;
  private runLive = false;
  unconfirmedSteerNotes: ReadonlyMap<string, readonly Sv3RunFeedItem[]> = new Map();
  private readonly adoptedRunIds = new Set<string>();
  private reattachChecked = false;
  private reattachRecordTurn: { runId: string; sessionId: string; turnId: string } | null = null;
  private readonly pendingRunRecords = new Map<string, {
    local: Sv3RunLocal; runId: string; conversationId: string; doneGraceUntil?: number;
  }>();
  private readonly announcedStops = new Set<string>();
  private recordRetryTimer: number | null = null;

  constructor(
    private readonly host: RunHost,
    private readonly composer: { clear(): void },
    private readonly agents: RunAgents,
    private readonly clock: RunClock,
  ) {}

  private agentController(): AgentSessionController | null { return this.agents.peek(); }

  dispatch(ref: Sv3TurnRef, text: string, effort: string): void {
    this.composer.clear();
    this.host.dockComposer();

    const ctrl = this.agents.get();
    // The run's thread events are stamped with THIS window's session, so a delegated run lands under
    // the conversation that asked for it rather than under whatever the controller ran last.
    ctrl.conversationId = ref.sessionId;
    this.run = {
      sessionId: ref.sessionId,
      turnId: ref.turnId,
      // The window's slice of a product-wide conversation: everything before this belongs to someone
      // else's run and must never be counted into this one's receipt.
      entryStart: ctrl.conversation.length,
      sessionIdAtDispatch: ctrl.sessionId,
      acknowledged: false,
      haltRequested: false,
      haltDispatched: false,
      // Tempdoc 859 §D §2.5 — a fresh run starts asking again. The reader's "don't ask again" is
      // scoped to the run they said it about, and this object IS that scope.
      autoContinueTokens: null,
      autoContinuesUsed: 0,
    };
    // Cleared here as well as on the first gate-free frame: a run that ended WHILE a gate was held
    // leaves the latch set, and the reset would then depend on the new run producing a gate-free
    // frame before its first gate. It does today; relying on that ordering would be the stale-flag
    // short-circuit this window has been bitten by before.
    this.budgetGateHandled = false;
    this.runLive = false;
    this.host.requestUpdate();
    // Tempdoc 859 §D §2.1 — the composer's rung travels with the dispatch, as a NAME. It reaches
    // `AgentBudgetPolicy`, which is the only place that can see the model's `n_ctx` and therefore
    // the only place that may turn a rung into a token count.
    void dispatchRunControl(ctrl, { kind: 'initiate', prompt: text, effort });
  }

  /**
   * The live run this window owns AND that accepts a steer, or null. Both halves matter: the
   * controller is product-wide, so a run this window did not dispatch is not this window's to
   * redirect, and only an `agent` run has an interject channel at all (a workflow or background run
   * does not) — the seam's own lifecycle predicate decides the rest.
   */
  get steerableRun(): AgentSessionController | null {
    const ctrl = this.agentController();
    if (ctrl === null || this.run === null || (this.run.sessionId !== this.host.activeConversationId() && !(this.host.activeConversationId() === null && !this.host.hasPreparedHandoff())) || this.host.showingResults()) return null;
    if (ctrl.runKind !== 'agent') return null;
    return directiveAvailable(ctrl, { kind: 'interject', text: '' }) ? ctrl : null;
  }

  /** Ask and Halt share the same pending and unconfirmed presentation. */
  runStopping(): Sv3StopState | null {
    const ctrl = this.agentController();
    const local = this.run;
    if (!ctrl?.stopAttempt?.intent || ctrl.terminalOutcome !== null || local === null
        || local.sessionId !== this.host.activeConversationId()) return null;
    return { turnId: local.turnId, state: ctrl.stopAttempt.outcome === 'refused'
      || ctrl.stopAttempt.outcome === 'unknown' || ctrl.stopObservationLost ? 'failed' : 'stopping' };
  }

  /** Latch before dispatch; the controller keeps observing until the backend ends this run. */
  haltRun(): void {
    const local = this.run;
    if (local === null) return;
    local.haltRequested = true;
    local.haltDispatched = false;
    this.deliverHalt();
    this.host.requestUpdate();
  }

  private deliverHalt(): void {
    const ctrl = this.agentController();
    const local = this.run;
    if (ctrl === null || local === null || local.haltDispatched) return;
    local.haltDispatched = true;
    void dispatchRunControl(ctrl, { kind: 'halt' }).then((result) => {
      // A lifecycle refusal may be retried by the next update. HTTP failures require another press.
      if ((result as RunControlRefusal | undefined)?.refused === true) local.haltDispatched = false;
    });
  }

  /**
   * The shared controller moved. Three things happen: the optimistic handoff latches the first time
   * the SERVER says anything, a pending halt is delivered once the run can honour it, and the run's
   * TERMINAL edge is detected so exactly one receipt lands.
   */
  /**
   * Does this window have an OPEN turn standing for a run? A settled turn stands for nothing: its
   * receipt is written and a later run is not the same run.
   */
  private get runRepresented(): boolean {
    const local = this.run;
    if (local === null) return false;
    const turn = this.host.turn(local);
    return turn?.status === 'streaming';
  }

  /**
   * PRESENCE (tempdoc 822 Phase F3) — the fix for F2's named finding: *window-local in-memory
   * sessions orphan a live run on reload*. A fresh window showed zero sessions while the run went on
   * holding server-side, so the window was silently disagreeing with the product about what was
   * happening.
   *
   * The rule this establishes: the SHARED CONTROLLER is the authority on whether a run is live, and
   * this window's memory is not. When the controller reports a live or holding run that no session
   * here accounts for, the window synthesises one — titled with the run's own task text, carrying an
   * open agent turn, landing on the Active shelf — and adopts it as the run it renders. `entryStart`
   * is 0 because this window dispatched nothing: the whole conversation the controller holds is that
   * run's. `acknowledged` is true for the same reason — there is no optimistic local echo to yield;
   * every frame of it came from the server already.
   *
   * It reads through `peek`, so a window that never delegates still constructs no controller and
   * starts no polling by being mounted (the F2 law). And it does not CLAIM the adopted session: the
   * run is news, not a navigation.
   */
  syncRunPresence(): void {
    const ctrl = this.agentController();
    if (ctrl === null) return;
    // The controller's conversation accumulates across runs, so the LIVE run's slice starts at the
    // task it was given — not at 0, which would read a finished run's steps as this one's.
    const start = sv3RunPresenceStart(ctrl);
    const feed = projectSv3RunFeed(ctrl, start);
    const probe = {
      status: sv3RunSessionStatus(ctrl, feed),
      represented: this.runRepresented,
      runId: ctrl.sessionId,
      adoptedRunIds: this.adoptedRunIds,
    };
    if (!sv3RunNeedsPresence(probe)) return;
    // The controller's own conversationId when it has one (a run dispatched from another surface
    // carries the conversation it belongs to), so the adopted session IS that conversation rather
    // than a second row for it. Only a run with no conversation gets a freshly minted id.
    const ref = this.host.adoptRun(
      sv3RunPresenceTitle(ctrl),
      this.clock.now(),
      ctrl.conversationId ?? this.host.createConversationId(),
      this.reattachRecordTurn?.runId === ctrl.sessionId
        && this.reattachRecordTurn.sessionId === ctrl.conversationId
        ? this.reattachRecordTurn.turnId : null,
    );
    this.reattachRecordTurn = null;
    this.run = {
      sessionId: ref.sessionId,
      turnId: ref.turnId,
      entryStart: start,
      sessionIdAtDispatch: null,
      acknowledged: true,
      haltRequested: false,
      haltDispatched: false,
      // An ADOPTED run is one this window did not dispatch: nobody here made an auto-continue
      // choice about it, so its gates ask. Inheriting a choice made for a different run would be
      // exactly the standing autonomy the per-run scope exists to prevent.
      autoContinueTokens: null,
      autoContinuesUsed: 0,
    };
    this.budgetGateHandled = false;
    // Observed live at adoption, so the run's terminal is an EDGE for this window too and the
    // adopted turn gets its one receipt instead of streaming forever.
    this.runLive = true;
    if (ctrl.sessionId !== null) this.adoptedRunIds.add(ctrl.sessionId);
  }

  readonly onAgentUpdate = (): void => {
    // Presence first: a run this window has no session for gets one before the frame is read, so the
    // rest of this method has something to write the run's progress into.
    this.syncRunPresence();
    const ctrl = this.agentController();
    const local = this.run;
    if (ctrl !== null && local !== null) {
      // Latched once and never read again: a run whose evidence later disappears cannot push this
      // window back into claiming it is still sending.
      if (!local.acknowledged && hasServerAcknowledgedLocalDispatch(local, ctrl)) {
        local.acknowledged = true;
      }
      // A run this window is already rendering is a run it must never ALSO adopt as presence — the
      // id is only knowable once the server names it, which is here rather than at dispatch.
      if (ctrl.sessionId !== null) this.adoptedRunIds.add(ctrl.sessionId);
      if (ctrl.stopAttempt?.intent && !local.haltRequested) local.haltRequested = true;
      const recordConversationId = ctrl.runConversationId ?? ctrl.conversationId;
      if (local.haltRequested && ctrl.sessionId !== null && ctrl.terminalOutcome === null
          && recordConversationId !== null) {
        local.stopRecordGraceUntil ??= this.clock.now() + COMPLETED_RECORD_GRACE_MS;
        const pending = this.pendingRunRecords.get(local.turnId);
        if (!pending && this.clock.now() < local.stopRecordGraceUntil) {
          this.pendingRunRecords.set(local.turnId, {
            local, runId: ctrl.sessionId, conversationId: recordConversationId,
            doneGraceUntil: local.stopRecordGraceUntil,
          });
          void this.host.refreshRecord(recordConversationId);
        }
      }
      const feed = projectSv3RunFeed(ctrl, local.entryStart);
      this.keepUnconfirmedSteerNotes(local.turnId, sv3UnconfirmedSteerNotes(ctrl, local.entryStart, feed));
      const status = sv3RunSessionStatus(ctrl, feed);
      if (status === 'live' || status === 'holding') {
        this.runLive = true;
        if (local.haltRequested) this.deliverHalt();
        this.maybeAutoContinue(ctrl, local);
      } else if (this.runLive) {
        this.runLive = false;
        this.concludeRun(local, feed);
      }
    }
    this.host.requestUpdate();
  };

  /** Replaced only when the turn's set of notes changes, so an unchanged frame re-renders nothing new. */
  private keepUnconfirmedSteerNotes(turnId: string, notes: readonly Sv3RunFeedItem[]): void {
    const kept = this.unconfirmedSteerNotes.get(turnId) ?? [];
    if (kept.length === notes.length && kept.every((n, i) => n.id === notes[i]!.id)) return;
    const next = new Map(this.unconfirmedSteerNotes);
    if (notes.length > 0) next.set(turnId, notes);
    else next.delete(turnId);
    this.unconfirmedSteerNotes = next;
  }

  /**
   * Tempdoc 859 §D §2.5 / P3 — a decision made once is RE-APPLIED, not re-asked.
   *
   * The reader ticked "don't ask again for this run" and chose an amount; the next gate takes that
   * same amount without stopping them. Three guard rails keep this from becoming standing autonomy:
   * it is scoped to ONE run (the state lives on {@link Sv3RunLocal}, which a new dispatch replaces),
   * it is BOUNDED (after {@link AUTO_CONTINUE_LIMIT} the gate asks again rather than continuing
   * forever), and every silent continue is NARRATED — the backend emits a `budget_raised` progress
   * note, which the feed renders like any other step.
   *
   * `budgetGateHandled` is the idempotence latch: the controller notifies on every frame, and the
   * gate stays held until the raise lands, so without it one gate would fire a raise per frame.
   */
  private maybeAutoContinue(ctrl: AgentSessionController, local: Sv3RunLocal): void {
    if (ctrl.stopAttempt?.intent) return;
    if (ctrl.budgetGate === null) {
      // The gate cleared — re-arm for the NEXT one.
      this.budgetGateHandled = false;
      return;
    }
    if (this.budgetGateHandled) return;
    if (!shouldAutoContinue(local)) return;
    const addTokens = local.autoContinueTokens;
    if (addTokens === null || addTokens <= 0) return;
    this.budgetGateHandled = true;
    local.autoContinuesUsed += 1;
    void dispatchRunControl(ctrl, { kind: 'raise-budget', addTokens });
  }

  /** One raise per held gate — see {@link maybeAutoContinue}. */
  private budgetGateHandled = false;

  /**
   * The run's terminal: exactly ONE receipt, written to the turn the dispatch opened. The count comes
   * from the SAME feed projection the cards were rendered from, so the receipt can never disagree
   * with what the reader watched — and it is addressed by ref, so a later run started from another
   * surface cannot write its numbers into this window's turn.
   */
  private concludeRun(local: Sv3RunLocal, feed: Sv3RunFeed): void {
    // Tempdoc 859 §3b — the run's EVIDENCE, written at the same terminal as its receipt and through
    // the same shared projection the record reader uses, so live and record produce one value from
    // one function over the same bytes. This is the C-small defect: the delegate path resolved
    // grounding on the backend, persisted it, and then never wrote it onto the turn, so a delegate
    // answer rendered with no marks and no sources while the evidence sat on the wire.
    //
    // §3c — GUARDED by the run the evidence belongs to. `concludeRun` fires on every terminal,
    // including the ones that emit no `done` (error, abort, watchdog, budget stop), and only `onDone`
    // writes the controller's evidence fields. Without this test a failed run N would settle wearing
    // run N-1's sources.
    //
    // TWO conditions, because the id test alone has a hole: a dispatch the server never named
    // leaves `ctrl.sessionId` still holding the PREVIOUS run's id, which the id test would then
    // read as a match. `acknowledged` is the window's own latch for "the server said something
    // about the run I opened" — the same identity discipline `entryStart` and `adoptedRunIds`
    // apply, and the reason it is not `runInFlight`/`isStreaming` (both local optimism, set inside
    // `send()` before the server answers).
    const ctrl = this.agentController();
    // Tempdoc 859 §D §2.6 (review F9) — ONE predicate, named, read by both the evidence write below
    // and the disposition write further down. They were two hand-copied condition lists that had
    // already drifted apart by one clause; a shared name is what makes "the same guard" checkable
    // rather than a claim in a comment.
    const terminalBelongsToThisRun =
      ctrl !== null &&
      local.acknowledged &&
      ctrl.answerEvidenceRunId !== null &&
      ctrl.answerEvidenceRunId === ctrl.sessionId;
    // Tempdoc 865 §7.1 / §7.9 A9 — PLANE AUTHORITY. The terminal is authoritative when it MADE A
    // GROUNDING CLAIM; the per-call deltas fill the gap when it did not. A run that ends without a
    // grounded terminal — cancelled, errored — establishes real evidence and then has nothing to
    // report it, which is exactly why the mint moved onto the tool events. The deltas are never
    // ADDED to a terminal that spoke: they are the same set, so accumulating both would double every
    // source and break the positional index the inline marks resolve through.
    //
    // OWNERSHIP IS NOT A CLAIM, and separating the two is the whole correctness of this gate.
    // `terminalBelongsToThisRun` answers "whose evidence is this" — it stays exactly as it was, and
    // the disposition write below still reads it, because a cut-short run must keep saying so. But
    // an answerless terminal still emits a `done` whose payload always carries a `sources` key
    // (`AgentEventPayloads` puts it unconditionally), and `onDone` writes `answerEvidenceRunId`
    // unconditionally — so ownership alone is TRUE for a terminal that reports nothing, and an empty
    // terminal would win over the deltas that hold the run's real evidence. The extra clause is the
    // record plane's own rule, mirrored: `AgentInteractionMapper` attaches `sources` only
    // `&& !srcs.isEmpty()`. Two planes, one test for "this terminal said something about grounding".
    //
    // Tempdoc 878 §D.1 — MAX_ITERATIONS has LEFT that list. The step ceiling now finalizes through
    // the same grounded seam as every other terminal, so it carries its evidence and this gate
    // simply resolves TRUE for it. Nothing here changes: the gate always asked what the terminal
    // said, never which disposition it wore, which is why the behaviour follows the backend fix
    // without an edit.
    const terminalMadeAGroundingClaim =
      terminalBelongsToThisRun && ctrl !== null && ctrl.answerSources.length > 0;
    if (terminalMadeAGroundingClaim) {
      const evidence = agentAnswerEvidence(
        ctrl.answerSources,
        ctrl.answerCitations,
        ctrl.answerCitationScorer,
      );
      this.host.setEvidence(
        { sessionId: local.sessionId, turnId: local.turnId },
        { ...evidence, retrievalMode: '' },
      );
    } else if (
      ctrl !== null &&
      local.acknowledged &&
      ctrl.groundingDeltasRunId !== null &&
      ctrl.groundingDeltasRunId === ctrl.sessionId &&
      ctrl.groundingDeltas.length > 0
    ) {
      this.host.setEvidence(
        { sessionId: local.sessionId, turnId: local.turnId },
        { ...agentDeltaEvidence(ctrl.groundingDeltas), retrievalMode: '' },
      );
    }
    this.host.settleRun(
      { sessionId: local.sessionId, turnId: local.turnId },
      ctrl?.terminalOutcome === 'failed' ? 'failed' : sv3RunOutcome(feed, ctrl?.terminalOutcome === 'cancelled'
        || (ctrl?.terminalOutcome === 'completed' && local.haltRequested && ctrl.stopAttempt.outcome === 'accepted' && ctrl.answerEvidenceRunId === ctrl.sessionId)),
      feed.toolCallCount,
      this.clock.now(),
      // Tempdoc 859 §D §2.6 — the terminal DISPOSITION, guarded by the SAME predicate the evidence
      // above is (literally the same expression now) and for the same reason: `concludeRun` fires on
      // every terminal, including the ones that emit no `done`, and only `onDone` writes the
      // controller's disposition. Without the guard a failed run N would settle wearing run N-1's
      // cut-short badge.
      terminalBelongsToThisRun && ctrl !== null ? ctrl.terminalDisposition : null,
    );
    const recordConversationId = ctrl?.runConversationId ?? ctrl?.conversationId;
    if (local.acknowledged && ctrl?.sessionId != null && recordConversationId != null) {
      this.pendingRunRecords.set(local.turnId, {
        local, runId: ctrl.sessionId, conversationId: recordConversationId,
        ...(local.haltRequested
          ? { doneGraceUntil: this.clock.now() + COMPLETED_RECORD_GRACE_MS } : {}),
      });
    }
    // The live feed was ATTENTION; the record is what survives it. Refreshing at the terminal is what
    // makes the yield happen (inventory D1): the settled turn re-renders from the canonical record's
    // interleaved items instead of vanishing with the controller that produced them.
    if (ctrl?.sessionId != null && recordConversationId != null) void this.host.refreshRecord(recordConversationId);
  }

  /** A typed prompt resolved by its OWN control — never by anything typed into the composer. */
  decide(detail: Sv3RunDecision | undefined): void {
    const ctrl = this.agentController();
    if (ctrl === null || detail === undefined || this.host.hasPreparedHandoff() || (this.host.activeConversationId() !== null && this.run?.sessionId !== this.host.activeConversationId())) return;
    if (detail.kind === 'budget') {
      // B8 — RAISE is a different directive, not a third value of the gate's decision: the gate is
      // resolved by finalize/stop, whereas raising extends the run's allowance and leaves the gate to
      // clear itself (tempdoc 577 Ext III, `views/UnifiedChatView.ts:3748-3755`). Same seam either
      // way — `dispatchRunControl` is the only way a directive leaves this window.
      if (detail.decision === 'raise') {
        // Tempdoc 859 §D §2.5 — the arm carries its OWN amount, already floored to clear this gate.
        // The fixed 4,096-token step it replaced could be smaller than the shortfall, in which case
        // the click resumed the loop straight into an immediate re-gate — visibly doing nothing.
        const addTokens = detail.addTokens ?? 0;
        if (addTokens <= 0) return;
        if (detail.autoContinue === true && this.run !== null) {
          // Remembered on the RUN, with the amount it applies to: one decision, one object, and it
          // dies with the run rather than becoming a standing preference.
          this.run.autoContinueTokens = addTokens;
        }
        void dispatchRunControl(ctrl, { kind: 'raise-budget', addTokens });
        return;
      }
      if (detail.decision === 'stop') this.markHaltRequested();
      void dispatchRunControl(ctrl, { kind: 'budget-decision', decision: detail.decision });
      return;
    }
    if (detail.decision === 'stop') this.markHaltRequested();
    void dispatchRunControl(ctrl, { kind: 'context-decision', decision: detail.decision });
  }

  /** A gate resolved with "stop" IS the reader halting, so the receipt must say so. */
  private markHaltRequested(): void {
    if (this.run !== null) this.run.haltRequested = true;
  }

  /**
   * TWO AXES, ONE PHASE. The SESSION axis is what the shared controller says; the
   * TURN axis is what this window's own turn is doing — including the optimistic window before the
   * server has acknowledged the dispatch, which no controller field can report because the controller
   * is optimistic too. `deriveSv3RunPhase` collapses them into the one value everything renders from,
   * so the slot, the feed, the sidebar colour and the receipt cannot each decide separately.
   */
  snapshot(): Sv3RunView | null {
    const local = this.run;
    if (local === null) return null;
    const ctrl = this.agentController();
    const feed = ctrl === null ? SV3_RUN_FEED_EMPTY : projectSv3RunFeed(ctrl, local.entryStart);
    const turn = this.host.turn(local);
    const prompts =
      ctrl === null
        ? []
        : // Tempdoc 859 §D §2.4 — elapsed comes from the TURN's own `askedAt`, which the record
          // carries too, so a gate met after a reload still says how long the run has been going.
          // The clock is read HERE and passed in; the projection stays pure.
          projectSv3RunPrompts(ctrl, feed, { askedAt: turn?.askedAt ?? null, now: this.clock.now() });
    const turnState: Sv3RunTurnState =
      turn === undefined || turn.status !== 'streaming'
        ? 'settled'
        : local.acknowledged
          ? 'open'
          : 'dispatching';
    return {
      turnId: local.turnId,
      phase: deriveSv3RunPhase({ session: sv3RunSessionStatus(ctrl, feed), turn: turnState }),
      feed,
      prompts,
    };
  }

  async reattachLiveRun(): Promise<void> {
    if (this.reattachChecked) return;
    this.reattachChecked = true;
    const existing = this.agentController();
    if (existing !== null) {
      void existing.reattachActiveRunOnLoad(this.prepareReattachedRecord);
      return;
    }
    if ((await this.agents.discover()) === null) return;
    if (!this.host.connected()) return;
    void this.agents.get().reattachActiveRunOnLoad(this.prepareReattachedRecord);
    // The mount-time presence look already ran, synchronously, while this round-trip was still in
    // flight — and it found no controller because there was none yet to find. Take it again now
    // that there is one, so a recovered run reaches the window on this path directly rather than
    // only through whatever the controller happens to notify next.
    this.syncRunPresence();
  }

  /** Bind an observer to the canonical turn before its first notification can create a placeholder. */
  private prepareReattachedRecord = async (run: LiveRunRow): Promise<void> => {
    const id = run.conversationId;
    if (!this.host.connected() || !id || !run.runId) return;
    const now = this.clock.now();
    this.host.ensureConversation(id, now);
    if (!await this.host.refreshRecord(id) || !this.host.connected()) return;
    const turn = this.host.lastTurn(id);
    if (turn?.kind === 'agent' && turn.recordId !== null) {
      this.reattachRecordTurn = { runId: run.runId, sessionId: id, turnId: turn.id };
    }
  };


  steerLiveRun(text: string): void {
    const ctrl = this.steerableRun;
    if (ctrl === null) return;
    if (!this.host.persistSteerDraft()) return;
    this.composer.clear();
    void dispatchRunControl(ctrl, { kind: 'interject', text });
  }

  answer(): void { this.host.focusDecision(); }

  detach(): void { this.run = null; this.runLive = false; }

  forgetConversation(id: string): void {
    for (const [key, pending] of this.pendingRunRecords) {
      if (pending.local.sessionId === id) this.pendingRunRecords.delete(key);
    }
    if (this.pendingRunRecords.size === 0) this.clearRecordRetry();
  }

  retryRecords(): void {
    this.clearRecordRetry();
    for (const id of new Set([...this.pendingRunRecords.values()].map(p => p.conversationId))) {
      void this.host.refreshRecord(id);
    }
  }

  recordReadWait(conversationId: string): number | undefined {
    const stopping = this.agentController();
    return stopping?.stopAttempt?.intent
      && (stopping.runConversationId ?? stopping.conversationId) === conversationId
      ? STOP_RESPONSE_WAIT_MS : undefined;
  }

  recordArrival(conversationId: string, record: ThreadResponse): void {
    const ctrl = this.agentController();
    if (ctrl?.stopAttempt?.intent && (ctrl.runConversationId ?? ctrl.conversationId) === conversationId) {
      const lifecycle = record.lifecycles.find((row) => row.sessionId === ctrl.sessionId);
      if (lifecycle) ctrl.reconcileRunLifecycle(lifecycle.sessionId, lifecycle.state);
    }
    // Navigation detaches the local handle. Its addressed turn still follows its record.
    for (const { local, runId, conversationId: recordConversationId } of this.pendingRunRecords.values()) {
      if (recordConversationId !== conversationId || this.run === local) continue;
      const state = record.lifecycles.find((row) => row.sessionId === runId)?.state;
      if (state !== 'CANCELLED' && state !== 'DONE' && state !== 'ERROR') continue;
      const turn = this.host.turn(local);
      if (turn?.status !== 'streaming') continue;
      this.host.settleRun(local,
        state === 'CANCELLED' ? 'halted' : state === 'DONE' ? 'complete' : 'failed',
        turn.toolCalls, this.clock.now(), turn.disposition);
    }
    const failedRun = ctrl?.stopAttempt?.intent && ctrl.terminalOutcome === 'failed'
      && (ctrl.runConversationId ?? ctrl.conversationId) === conversationId ? this.run : null;
    const failedTurn = failedRun === null ? undefined
      : this.host.turn(failedRun);
    this.host.applyRecord(conversationId, record);
    // The lifecycle can confirm failure before an ERROR item reaches the record. Preserve the
    // live receipt across that partial snapshot without changing the saved-record projection.
    if (failedRun !== null && failedTurn?.status === 'failed') {
      this.host.preserveFailedReceipt(failedRun, failedTurn.detail);
    }
    const stopIds = new Set(record.events
      .filter((e) => sv3RecordItemStopsRun(e.kind.toLowerCase(), e.attributes))
      .map((e) => e.id));
    for (const [id, pending] of this.pendingRunRecords) {
      const { local, runId } = pending;
      if (pending.conversationId !== conversationId) continue;
      const turn = this.host.turn(local);
      if (turn === undefined) {
        this.pendingRunRecords.delete(id);
        continue;
      }
      const stop = turn.activity.find((item) => item.kind === 'note' && stopIds.has(item.id));
      if (stop?.kind === 'note' && !this.announcedStops.has(runId)) {
        this.announcedStops.add(runId);
        this.host.announceStop(stop.text);
      }
      const state = record.lifecycles.find((r) => r.sessionId === runId)?.state;
      if (state === 'DONE' && stop === undefined && !local.haltRequested) {
        pending.doneGraceUntil ??= this.clock.now() + COMPLETED_RECORD_GRACE_MS;
      } else if (!local.haltRequested || state === 'DONE') {
        // Our Halt still needs its saved confirmation when completion wins the race.
        delete pending.doneGraceUntil;
      }
      // A saved Stop can precede the final checkpoint; keep reading through both edges.
      if ((state === 'DONE' || state === 'ERROR' || state === 'CANCELLED')
        && (stop !== undefined || (!local.haltRequested && state === 'ERROR'))) {
        this.pendingRunRecords.delete(id);
      }
    }
  }

  clearRecordRetry(): void {
    if (this.recordRetryTimer !== null) this.clock.clearTimeout(this.recordRetryTimer);
    this.recordRetryTimer = null;
  }

  private retireExpiredRecordReads(): void {
    const now = this.clock.now();
    for (const [id, pending] of this.pendingRunRecords) {
      if (pending.doneGraceUntil !== undefined
        && now >= pending.doneGraceUntil) this.pendingRunRecords.delete(id);
    }
  }

  scheduleRecordRetry(): void {
    this.retireExpiredRecordReads();
    if (this.pendingRunRecords.size === 0) this.clearRecordRetry();
    if (!this.host.connected() || this.pendingRunRecords.size === 0 || this.recordRetryTimer !== null) return;
    this.recordRetryTimer = this.clock.setTimeout(() => {
      this.recordRetryTimer = null;
      this.retireExpiredRecordReads();
      for (const id of new Set([...this.pendingRunRecords.values()].map((p) => p.conversationId))) {
        void this.host.refreshRecord(id);
      }
    }, 500);
  }

}
