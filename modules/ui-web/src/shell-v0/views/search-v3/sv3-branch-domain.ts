// SPDX-License-Identifier: Apache-2.0
/** Branch/version derivation and orchestration; sessions, drafts, Ask and runs keep their owners. */
import type { Conversation } from '../../state/conversationListStore.js';
import { siblingSessionsAt } from '../../state/conversationListStore.js';
import { EMPTY_PREFIX_SENTINEL } from '../unifiedChatRequest.js';
import type { Sv3DraftDomain, HumanTask } from './sv3-draft-domain.js';
import type { Sv3AskDomain } from './sv3-ask-domain.js';
import type { Sv3SessionsDomain } from './sv3-sessions-domain.js';
import type { Sv3SearchController, Sv3AskScope } from './sv3-search.js';
import type { Sv3ComposerTier } from './sv3-run.js';
import type { RecoveryShape } from './sv3-recovery.js';
import { sv3TurnMessageIds, type Sv3Session } from './sv3-sessions.js';
import { projectSv3TurnLineage, sv3FirstOwnTurnIndex, sv3LineageFor,
  type Sv3BranchAction, type Sv3TurnLineage, type Sv3VersionSet } from './sv3-branch.js';
import { sv3ExplainsRankingTrace } from './sv3-honesty.js';
import { SV3_ASK_SHAPE_ID, SV3_MODEL_SHAPE_ID } from './sv3-ask.js';
import { BRANCH_FAILED, BRANCH_RERUN_BUSY } from './fixtures.js';

type BranchDraft = Pick<Sv3DraftDomain, 'mayForkDraft' | 'humanUnavailable' | 'reserveHumanTask'
  | 'humanOwned' | 'recoveryError' | 'preparedHandoff' | 'handoffBlocked' | 'handoffNotice'
  | 'tierFollowsPlace' | 'tier'>;
type BranchAsk = Pick<Sv3AskDomain, 'summaryInput' | 'structuredInput' | 'runAsk'>;
type BranchSearch = Pick<Sv3SearchController, 'hasTurnScope' | 'scopeOfTurn' | 'filtersOfTurn'>;

interface BranchHost {
  activeSession(): Sv3Session | null;
  activeConversationId(): string | null;
  conversations(): readonly Conversation[];
  draft(): BranchDraft;
  ask(): BranchAsk;
  search(): BranchSearch;
  sessions(): Pick<Sv3SessionsDomain, 'openConversation'>;
  connected(): boolean;
  streaming(): boolean;
  historyLocked(): boolean;
  lockedRefusal(): boolean;
  delegateUnavailableReason(): string;
  runInFlight(): boolean;
  delegate(text: string, turnAction: boolean): void;
  announceWarning(message: string): void;
  requestUpdate(): void;
}

type BranchTransport = Pick<typeof import('../../state/conversationListStore.js'),
  'loadForkConversations' | 'branchConversation'>;

export class Sv3BranchDomain {
  constructor(private readonly host: BranchHost, private readonly transport: BranchTransport,
    private readonly composer: { replace(text: string): void }) {}

  private get activeSession(): Sv3Session | null { return this.host.activeSession(); }
  private get draftDomain(): BranchDraft { return this.host.draft(); }
  private get askDomain(): BranchAsk { return this.host.ask(); }
  private get search(): BranchSearch { return this.host.search(); }
  private get historyLocked(): boolean { return this.host.historyLocked(); }
  private get lockedRefusal(): boolean { return this.host.lockedRefusal(); }
  private get streaming(): boolean { return this.host.streaming(); }
  private set tier(value: Sv3ComposerTier) { this.draftDomain.tier = value; }

  private readonly forkReads = new Map<string, {
    abort: AbortController;
    rows: readonly Conversation[] | null;
    versions: readonly string[] | null;
  }>();
  private forkListLoading = false;

  clearForkReads(): void {
    for (const read of this.forkReads.values()) read.abort.abort();
    this.forkReads.clear();
  }

  invalidateForkReads(loading: boolean, changed: boolean): void {
    if (changed) this.clearForkReads();
    this.forkListLoading = loading;
  }

  branchOrigin(): { parent: string; name: string } | null {
    const session = this.activeSession;
    if (session === null) return null;
    const ownRead = this.forkReads.get(JSON.stringify([session.id, EMPTY_PREFIX_SENTINEL]));
    const metadata = session.history ?? this.host.conversations().find(row => row.id === session.id)
      ?? ownRead?.rows?.find(row => row.id === session.id);
    const parent = metadata?.parentSessionId;
    const point = metadata?.branchPointMessageId;
    if (!parent || !point) return null;
    const read = this.forkReads.get(JSON.stringify([parent, point]));
    const name = (!this.historyLocked && !this.lockedRefusal
      ? read?.rows?.find(row => row.id === parent)?.firstUserMessage?.trim() : '') || 'Original conversation';
    return { parent, name };
  }

  /* ── Branch, edit / retry and the version pager (852 S3) ──────────────────────────────────── */

  /**
   * What each turn can do about branching, derived once per render from the SAME three inputs the
   * acts below read: the turns on screen, the conversation's `/history`, and complete fork reads.
   * The trigger's gate and the act's target are therefore one derivation, which is what stops a menu
   * offering a fork the window would then compute differently.
   */
  turnLineage(): readonly Sv3TurnLineage[] {
    const session = this.activeSession;
    if (session === null) return [];
    return projectSv3TurnLineage(session.turns, session.history, session.id, [],
      (base, point) => this.forkReads.get(JSON.stringify([base, point]))?.versions ?? null);
  }

  /** A continuation with no question yet still has a fork point, including on an empty branch. */
  private continuationFork(): { base: string; point: string } | null {
    const session = this.activeSession;
    if (this.historyLocked || session === null || session.history === null) return null;
    const parent = session.history?.parentSessionId;
    const point = session.history?.branchPointMessageId;
    if (parent && point && sv3FirstOwnTurnIndex(session.turns, session.history) >= session.turns.length) {
      return { base: parent, point };
    }
    const last = session.turns.at(-1);
    const answer = last === undefined ? null : sv3TurnMessageIds(last).assistantMsgId;
    return answer === null ? null : { base: session.id, point: answer };
  }

  continuationVersions(): Sv3VersionSet | null {
    const fork = this.continuationFork();
    if (fork === null) return null;
    const versions = this.forkReads.get(JSON.stringify([fork.base, fork.point]))?.versions;
    const index = versions?.indexOf(this.activeSession?.id ?? '') ?? -1;
    return versions && versions.length > 1 && index >= 0 ? { sessions: versions, index } : null;
  }

  /** Only fork points on the open transcript are read. Requests are parallel and never gate sends. */
  syncForkReads(): void {
    const session = this.activeSession;
    const needed = new Map<string, { base: string; point: string }>();
    if (!this.historyLocked && session !== null && session.history !== null) {
      for (const turn of projectSv3TurnLineage(session.turns, session.history, session.id, [])) {
        if (turn.forkKey !== null) needed.set(JSON.stringify([turn.forkFromId, turn.forkKey]),
          { base: turn.forkFromId, point: turn.forkKey });
      }
      const parent = session.history?.parentSessionId;
      const point = session.history?.branchPointMessageId;
      // Empty branches still need their origin, even before they have a divergent turn.
      if (parent && point) needed.set(JSON.stringify([parent, point]), { base: parent, point });
    }
    // A locked history returns 423 without lineage. The base row of this same lock-safe filter
    // supplies structural parent pointers without opening content or growing the sidebar.
    if (this.historyLocked && session !== null && session.history === null
      && !this.host.conversations().some(row => row.id === session.id)) {
      needed.set(JSON.stringify([session.id, EMPTY_PREFIX_SENTINEL]),
        { base: session.id, point: EMPTY_PREFIX_SENTINEL });
    }
    const continuation = this.continuationFork();
    if (continuation !== null) needed.set(JSON.stringify([continuation.base, continuation.point]), continuation);
    for (const [key, read] of this.forkReads) {
      if (!needed.has(key)) {
        read.abort.abort();
        this.forkReads.delete(key);
      }
    }
    if (this.forkListLoading || !this.host.connected()) return;
    for (const [key, { base, point }] of needed) {
      if (this.forkReads.has(key)) continue;
      const read = { abort: new AbortController(), rows: null as readonly Conversation[] | null,
        versions: null as readonly string[] | null };
      this.forkReads.set(key, read);
      void this.transport.loadForkConversations(base, point, read.abort.signal).then(rows => {
        if (!this.host.connected() || this.forkReads.get(key) !== read) return;
        read.rows = rows;
        read.versions = rows === null ? null : siblingSessionsAt(rows, base, point);
        this.host.requestUpdate();
      });
    }
  }

  /**
   * The three acts, all of them ONE backend act with a different `fromMsgId` and a different thing
   * to do afterwards (`views/UnifiedChatView.ts:1471-1497` — there is no edit endpoint and no retry
   * endpoint, and inventing one here would be inventing a contract the backend does not hold).
   *
   *  - **Branch** forks after this turn's ANSWER and continues there; nothing is re-sent.
   *  - **Retry** forks before this turn's QUESTION and re-sends it unchanged.
   *  - **Edit** forks at the same point and sends the rewrite, so the new text is the first
   *    divergent message rather than a second one appended below the old exchange.
   *
   * The ids come from {@link projectSv3TurnLineage} and nowhere else, so an act can never be pointed
   * at a message `?fromMsgId=` would reject — and a turn that names none renders no affordance to
   * press in the first place.
   *
   * A turn re-runs in ITS OWN tier (WP1.2 S2.5): an agent turn's Retry/Edit runs the agent again, an
   * ask turn's asks again, whatever tier the composer happens to show — the reader is repeating that
   * turn, not composing a new one. When the agent cannot run, the act is refused BEFORE anything is
   * forked and says why (S2.6): there is no falling back to an ask, and the editor keeps the text.
   */
  async runBranchAction(detail: Sv3BranchAction): Promise<void> {
    const session = this.activeSession;
    if (session === null || this.streaming) return;
    const lineage = sv3LineageFor(this.turnLineage(), detail.turnId);
    if (lineage === null) return;
    const turn = session.turns.find((t) => t.id === detail.turnId) ?? null;
    const tier: Sv3ComposerTier | 'model' = turn?.shapeId === 'core.extract' ? 'structured' : turn?.kind === 'agent' ? 'delegate' : turn?.kind === 'model' ? 'model' : 'ask';
    const summary = turn?.shapeId === 'core.summarize';
    const text = detail.action === 'edit' ? (detail.text ?? '').trim() : turn?.question ?? '';
    if (detail.action !== 'branch' && tier === 'ask' && !summary && !this.search.hasTurnScope(detail.turnId)) {
      this.draftDomain.recoveryError = 'The original Ask inputs are not known after reload. Prepare a new Ask and choose or review its files before sending.';
      this.host.requestUpdate();
      this.host.announceWarning(this.draftDomain.recoveryError);
      return;
    }
    if (detail.action !== 'branch' && (summary && this.askDomain.summaryInput(detail.turnId) === undefined
      || tier === 'structured' && this.askDomain.structuredInput(detail.turnId) === undefined)) {
      this.draftDomain.recoveryError = summary && turn && sv3ExplainsRankingTrace(turn)
        ? 'Retry/Edit cannot restore the original Explain input after reload. Start Explain from the result again.'
        : 'The original schema or summary input is not available. Prepare a new task and review its inputs before sending.';
      this.host.requestUpdate();
      this.host.announceWarning(this.draftDomain.recoveryError);
      return;
    }
    if (!this.draftDomain.mayForkDraft()) return;
    let humanTask: HumanTask | undefined;
    if (detail.action !== 'branch' && tier !== 'delegate') {
      const shape: RecoveryShape = summary ? 'core.summarize' : tier === 'structured' ? 'core.extract' : tier === 'model' ? SV3_MODEL_SHAPE_ID : SV3_ASK_SHAPE_ID;
      const reason = this.draftDomain.humanUnavailable(shape);
      if (reason) { this.host.announceWarning(reason); return; }
      if (lineage.forkKey === null || text === '' || (detail.action === 'edit' && !lineage.canEdit)) return;
      const inputs = summary ? this.askDomain.summaryInput(detail.turnId) : this.askDomain.structuredInput(detail.turnId);
      // Missing schema/passage already has an explicit review path below, with no dispatch.
      if ((!summary && tier !== 'structured') || inputs !== undefined) {
        const task = this.draftDomain.reserveHumanTask(text,
          summary ? this.askDomain.summaryInput(detail.turnId)?.scope ?? null
            : tier === 'structured' ? this.askDomain.structuredInput(detail.turnId)?.scope ?? null : this.search.scopeOfTurn(detail.turnId),
          tier === 'structured' ? this.askDomain.structuredInput(detail.turnId)?.schema : undefined,
          shape, summary ? this.askDomain.summaryInput(detail.turnId)?.selection ?? null : null,
          false, lineage.forkKey, detail.action === 'edit' ? detail.text : undefined, this.search.filtersOfTurn(detail.turnId));
        if (task === null) return;
        humanTask = task;
      }
    }
    if (turn?.shapeId === 'core.summarize' && detail.action !== 'branch') {
      const text = detail.action === 'edit' ? (detail.text ?? '').trim() : turn.question;
      if (lineage.forkKey === null || text === '' || !lineage.canEdit) return;
      const inputs = this.askDomain.summaryInput(turn.id);
      if (!await this.branchInto(lineage.forkFromId, lineage.forkKey, null, 'ask', null, humanTask)) return;
      this.tier = 'ask';
      this.draftDomain.preparedHandoff = { shapeId: 'core.summarize', selection: inputs?.selection ?? null };
      if (inputs === undefined) {
        this.draftDomain.handoffBlocked = true;
        this.draftDomain.handoffNotice = sv3ExplainsRankingTrace(turn)
          ? 'Retry/Edit cannot restore the original Explain input after reload. Start Explain from the result again.'
          : 'No readable summary input is saved. Choose the original files or passage before sending.';
        this.composer.replace(text);
        this.host.requestUpdate();
      } else {
        await this.askDomain.runAsk(text, inputs.scope, undefined, false, { task: humanTask, turnAction: true });
      }
      return;
    }
    if (tier === 'structured' && detail.action !== 'branch') {
      const text = detail.action === 'edit' ? (detail.text ?? '').trim() : turn?.question ?? '';
      if (lineage.forkKey === null || text === '' || !lineage.canEdit) return;
      const inputs = this.askDomain.structuredInput(detail.turnId);
      if (!await this.branchInto(lineage.forkFromId, lineage.forkKey, null, tier, null, humanTask)) return;
      this.tier = 'structured';
      this.draftDomain.tierFollowsPlace = false;
      if (inputs === undefined) {
        this.composer.replace(text);
        this.host.requestUpdate();
      } else {
        await this.askDomain.runAsk(text, inputs.scope, inputs.schema, false, { task: humanTask, turnAction: true });
      }
      return;
    }
    switch (detail.action) {
      case 'branch':
        if (lineage.branchFromId === null) return;
        // A branch re-sends nothing; it continues the conversation in the tier of the turn it forked
        // after, so the next send from a Delegate turn's branch is a delegation.
        if ((await this.branchInto(session.id, lineage.branchFromId, null, tier)) && (tier === 'delegate' || tier === 'structured')) {
          this.tier = tier;
          this.draftDomain.tierFollowsPlace = false;
        }
        return;
      case 'retry': {
        const question = turn?.question ?? '';
        if (lineage.forkKey === null || question === '') return;
        if (!this.canRerun(tier)) return;
        await this.branchInto(
          lineage.forkFromId,
          lineage.forkKey,
          question,
          tier,
          this.search.scopeOfTurn(detail.turnId),
          humanTask,
        );
        return;
      }
      case 'edit': {
        const text = (detail.text ?? '').trim();
        if (lineage.forkKey === null || text === '' || !lineage.canEdit) return;
        if (!this.canRerun(tier)) return;
        await this.branchInto(
          lineage.forkFromId,
          lineage.forkKey,
          text,
          tier,
          this.search.scopeOfTurn(detail.turnId),
          humanTask,
        );
        return;
      }
      default:
        return;
    }
  }

  /**
   * Whether a re-run in `tier` can start now, saying why not when it cannot (S2.6). Model and
   * delegate reruns check availability here; an Ask rerun keeps its existing path. Both need
   * the model (their own availability gates, which do not require indexed documents)
   * and no other run in flight on the one shared controller.
   */
  private canRerun(tier: Sv3ComposerTier | 'model'): boolean {
    const unavailable = tier === 'delegate' ? this.host.delegateUnavailableReason()
      : this.draftDomain.humanUnavailable(tier === 'model' ? SV3_MODEL_SHAPE_ID : tier === 'structured' ? 'core.extract' : SV3_ASK_SHAPE_ID);
    if (unavailable !== '') {
      this.host.announceWarning(unavailable);
      return false;
    }
    if (this.host.runInFlight()) {
      this.host.announceWarning(BRANCH_RERUN_BUSY);
      return false;
    }
    return true;
  }

  /**
   * Fork, open the fork, and — for the two acts that re-send — ask again IN it.
   *
   * ORDER IS THE WHOLE THING. The branch is opened and CLAIMED before the re-send, because
   * {@link runAsk} appends to whatever conversation is active: sending first would append the
   * rewritten question to the conversation the reader was replacing, which is the failure this act
   * exists to avoid and would look entirely plausible on screen.
   *
   * A refused branch changes nothing and says so. There is no local fallback — a window that
   * appended the re-send to the current conversation "because the branch failed" would silently do
   * the one thing the reader did not ask for.
   */
  async branchInto(
    sessionId: string,
    fromMsgId: string,
    resend: string | null,
    tier: Sv3ComposerTier | 'model',
    scope: Sv3AskScope | null = null,
    task?: HumanTask,
  ): Promise<boolean> {
    if (!this.draftDomain.mayForkDraft()) return false;
    // The preview is the conversation's OPENING question, the same thing the shipped window sends
    // (`:1485`): it is what makes the new session surface as a recent one with a name.
    const preview = this.activeSession?.turns.find((t) => t.question !== '')?.question ?? '';
    const branched = await this.transport.branchConversation(sessionId, fromMsgId, preview);
    if (branched === null) {
      this.host.announceWarning(BRANCH_FAILED);
      return false;
    }
    if (task !== undefined && !this.draftDomain.humanOwned(task)) return false;
    if (!await this.openBranch(branched, task)) return false;
    if (resend === null) return true;
    // The same ORDER for both tiers: the branch is claimed first, so the re-run lands in it.
    // An ask re-send keeps the files its turn was limited to (WP1.1 S1.7) — or, for a turn this
    // window did not limit, asks all files, which is what its Retry/Edit said it would do.
    if (tier === 'delegate') {
      // The agent can stop being able to run while the fork is in flight (S2.6). The fork stands —
      // it is the new version — and the text waits in its composer, in Delegate, saying why; it is
      // never run as an Ask and never dropped.
      if (!this.canRerun(tier)) {
        this.tier = 'delegate';
        this.composer.replace(resend);
        return true;
      }
      this.host.delegate(resend, true);
    } else {
      if (!this.canRerun(tier)) return true;
      await this.askDomain.runAsk(resend, scope, undefined, tier === 'model', { task, turnAction: true });
    }
    return true;
  }

  openBranch(id: string, task?: HumanTask): Promise<boolean> {
    return this.host.sessions().openConversation(id, task);
  }
  /** A version selection opens an existing conversation through the sessions owner. */
  selectVersion(id: string): void {
    if (id === '' || id === this.host.activeConversationId()) return;
    void this.openBranch(id);
  }
}
