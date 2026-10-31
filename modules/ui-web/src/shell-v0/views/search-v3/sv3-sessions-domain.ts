// SPDX-License-Identifier: Apache-2.0
/** Conversation selection and navigation; storage, records, drafts and runs keep their owners. */
import type { ComposerCapture } from '../../state/composerPort.js';
import type { Sv3DraftDomain, HumanTask } from './sv3-draft-domain.js';
import type { Sv3ContextDomain } from './sv3-context-domain.js';
import type { Sv3RunDomain } from './sv3-run-domain.js';
import type { Sv3SearchController } from './sv3-search.js';
import type { Sv3ComposerState } from './fixtures.js';
import { SV3_RENAME_FAILED } from './fixtures.js';
import { reasonFor } from '../../state/readinessNotice.js';
import { focusSession, mergeStoreConversations, renameSession, toggleSessionPin, removeSession,
  restoreSessionTitle, sessionById, startNewSession, type Sv3SessionList, type Sv3RunGate } from './sv3-sessions.js';
import type { Conversation, DeleteCascadeResult, DeleteTreeEntry } from '../../state/conversationListStore.js';

type SessionsDraft = Pick<Sv3DraftDomain, 'mountHasIncoming' | 'draftReview' | 'humanPlace' | 'persistDraft'
  | 'recoveryText' | 'currentPreparation' | 'rememberPlace' | 'revokeHumanTask' | 'departureBlocked'
  | 'returnToResults' | 'resetToHero' | 'swapPlace' | 'askInConversation' | 'saveRecovery'
  | 'placeScopes' | 'placeTiers' | 'startFreshSession' | 'retireRefusedHandoff'>;
type SessionsContext = Pick<Sv3ContextDomain, 'recordNotice' | 'refreshRecord' | 'refreshHistory' | 'forgetConversation'>;

export interface Sv3RecentItem {
  readonly kind: 'search' | 'conversation';
  readonly value: string;
  readonly label: string;
}

interface SessionsHost {
  sessions(): Sv3SessionList;
  setSessions(sessions: Sv3SessionList): void;
  draft(): SessionsDraft;
  context(): SessionsContext;
  run(): Pick<Sv3RunDomain, 'forgetConversation'>;
  search(): Pick<Sv3SearchController, 'hasPlace' | 'hide'>;
  renamingId(): string | null;
  setRenamingId(id: string | null): void;
  connected(): boolean;
  historyLocked(): boolean;
  apiBase(): string;
  runGate(): Sv3RunGate;
  closePane(): void;
  setComposerState(state: Sv3ComposerState): Promise<void>;
  focusComposer(): Promise<void>;
  revealRecoveryOutcome(): void;
  invalidateForkReads(loading: boolean, changed: boolean): void;
  resetProfilePresentation(): void;
  searchNow(text: string): boolean;
  writeDraft(text: string): void;
  confirmTree(children: string[], preview: DeleteTreeEntry[], apiBase: string, signal: AbortSignal): Promise<boolean>;
  deletionOutcome(result: DeleteCascadeResult, tree: DeleteTreeEntry[]): string;
  announce(message: string, severity: 'warning'): void;
  requestUpdate(): void;
}

type SessionsTransport = Pick<typeof import('../../state/conversationListStore.js'),
  'setConversationApiBase' | 'subscribeConversationList' | 'loadConversations' | 'getConversationListState'
  | 'restoreActiveConversation' | 'setActiveConversation' | 'setConversationTitle' | 'deleteConversationWithCascade'>
  & { readLastViewed(): string | null; setLastViewed(id: string): void; clearLastViewed(): boolean;
    hasFreshHandoff(): boolean };

export class Sv3SessionsDomain {
  constructor(private readonly host: SessionsHost, private readonly transport: SessionsTransport,
    private readonly composer: { read(): string; snapshot(): ComposerCapture }) {}

  private get sessions(): Sv3SessionList { return this.host.sessions(); }
  private set sessions(value: Sv3SessionList) { this.host.setSessions(value); }
  private get draftDomain(): SessionsDraft { return this.host.draft(); }
  private get contextDomain(): SessionsContext { return this.host.context(); }
  private get runDomain(): Pick<Sv3RunDomain, 'forgetConversation'> { return this.host.run(); }
  private get search(): Pick<Sv3SearchController, 'hasPlace' | 'hide'> { return this.host.search(); }
  private get renamingId(): string | null { return this.host.renamingId(); }
  private set renamingId(id: string | null) { this.host.setRenamingId(id); }
  private get historyLocked(): boolean { return this.host.historyLocked(); }
  private get apiBase(): string { return this.host.apiBase(); }

  /** These are the store's recent rows, not another session projection. */
  conversations: readonly Conversation[] = [];
  private recentSearches: readonly string[] = [];
  private recentClearTime = -Infinity;
  private recentConversationsReady = true;
  private recentEpoch = 0;
  private convListUnsubscribe: (() => void) | null = null;
  private liveDraftOwner: { id: string; draft: ComposerCapture } | null = null;
  private removalDraft: { id: string; draft: ComposerCapture } | null = null;
  private removalConfirmation: AbortController | null = null;
  private removing = false;

  connect(): void {
    this.transport.setConversationApiBase(this.apiBase || '');
    this.convListUnsubscribe = this.transport.subscribeConversationList((state, change) => {
      this.host.invalidateForkReads(state.loading, state.loading || this.conversations !== state.conversations);
      this.host.requestUpdate();
      this.forgetDeletedConversations(state.deletedIds);
      this.sessions = mergeStoreConversations(this.sessions, state.conversations);
      this.conversations = state.conversations;
      // Only restoration follows another caller. A shared claim belongs to its own window.
      if (change === 'restore') {
        if (state.activeId !== null) this.draftDomain.mountHasIncoming = true;
        this.followStoreConversation(state.activeId);
      }
    });
  }

  loadConversations(): void { void this.transport.loadConversations(); }
  disconnect(): void { this.convListUnsubscribe?.(); this.convListUnsubscribe = null; }
  releaseDraftOwnership = (): void => { this.removalDraft = null; this.liveDraftOwner = null; };
  abortRemovalConfirmation(): void { this.removalConfirmation?.abort(); }
  disconnectDraft(): void { this.abortRemovalConfirmation(); this.releaseDraftOwnership(); }
  noteLiveDraft(id: string | null): void {
    this.liveDraftOwner = id === null ? null : { id, draft: this.composer.snapshot() };
  }

  resetProfileRecents(): void {
    this.recentEpoch += 1;
    this.recentSearches = [];
    this.recentClearTime = -Infinity;
    this.recentConversationsReady = false;
    this.host.requestUpdate();
    this.host.resetProfilePresentation();
    void this.refreshRecents();
  }
  invalidateRecentEpoch(): void { this.recentEpoch += 1; }
  refreshUnlockedRecents(): void {
    this.recentConversationsReady = false;
    this.host.requestUpdate();
    void this.refreshRecents();
  }
  recordSearch(query: string): void {
    this.recentSearches = [query, ...this.recentSearches.filter(value => value !== query)].slice(0, 8);
    this.host.requestUpdate();
  }

  restoreLastViewed(): void {
    // Tempdoc 864 PR C — THE URL OUTRANKS THE POINTER. A deep link or a reload carrying
    // `?conversationId=` has already been distributed to the store by the NavigationHandler, and
    // the subscription above has already opened it; re-reading this tab's pointer here would
    // second-guess the address the reader actually arrived on.
    if (this.transport.getConversationListState().activeId !== null) return;
    const id = this.transport.readLastViewed();
    if (id === null || sessionById(this.sessions, id) !== null) return;
    const now = Date.now();
    this.sessions = mergeStoreConversations(this.sessions, [
      { id, title: null, firstUserMessage: '', createdAt: now, lastActiveAt: now },
    ]);
    this.sessions = focusSession(this.sessions, id, now);
    // A conversation is open, so Enter continues it (WP1.1: only a window with none starts in Search).
    this.draftDomain.askInConversation();
    // A REHYDRATION, not a claim (tempdoc 864 PR C): this is the position the tab already had, so
    // it corrects the URL in place. Pushing here would leave a history entry for the bare surface
    // address behind the one the reader is on, and Back would look broken on the very first press.
    this.transport.restoreActiveConversation(id);
    void this.host.setComposerState('docked');
    void this.contextDomain.refreshRecord(id);
    void this.contextDomain.refreshHistory(id);
  }

  private followStoreConversation(storeActiveId: string | null): void {
    if (storeActiveId === this.sessions.activeId) {
      if (!this.transport.hasFreshHandoff()) this.draftDomain.retireRefusedHandoff();
      this.draftDomain.departureBlocked = false; return;
    }
    this.removalConfirmation?.abort();
    if (storeActiveId === null && this.draftDomain.draftReview && this.composer.read().trim()) {
      const leaving = this.draftDomain.humanPlace();
      if (!this.draftDomain.persistDraft({ place: null, text: this.draftDomain.recoveryText(this.composer.read()), preparation: this.draftDomain.currentPreparation() }, true)) { this.blockDeparture(); return; }
      if (leaving !== null) this.draftDomain.rememberPlace(leaving, this.composer.read());
      this.draftDomain.revokeHumanTask(true);
      this.search.hide();
      this.sessions = startNewSession(this.sessions);
      this.draftDomain.departureBlocked = false;
      this.transport.clearLastViewed();
      this.host.requestUpdate();
      return;
    }
    if (storeActiveId === null && this.search.hasPlace) {
      // WP1.1 S1.13 — the bare address is the result list when this window has one: Back/Forward
      // between results and a conversation returns to the list, its query and its chosen files.
      if (!this.draftDomain.returnToResults()) { this.blockDeparture(); return; }
      this.draftDomain.departureBlocked = false;
      this.transport.clearLastViewed();
      return;
    }
    if (storeActiveId === null) {
      // The hero is a real address (the bare surface URL), so arriving back at it is the teardown
      // New session does — minus the focus move, which belongs to the affordance, and minus the
      // CLAIM, which would push a history entry for a move the browser already made and truncate
      // the forward tail with it. See {@link resetToHero}.
      if (!this.draftDomain.resetToHero(true, true)) { this.blockDeparture(); return; }
      this.draftDomain.departureBlocked = false;
      // The pointer still follows: the tab is on the hero now, so a reload must not resurrect the
      // conversation the reader just left. This one is a browser-local write with no emission, so
      // it cannot reach the projector.
      this.transport.clearLastViewed();
      return;
    }
    // An edit in another row is DROPPED rather than committed, the same rule every other move
    // between conversations applies.
    this.renamingId = null;
    if (!this.draftDomain.swapPlace(storeActiveId)) { this.blockDeparture(); return; }
    this.draftDomain.departureBlocked = false;
    const now = Date.now();
    if (sessionById(this.sessions, storeActiveId) === null) {
      this.sessions = mergeStoreConversations(this.sessions, [
        { id: storeActiveId, title: null, firstUserMessage: '', createdAt: now, lastActiveAt: now },
      ]);
    }
    this.sessions = focusSession(this.sessions, storeActiveId, now);
    // The tab is reading this conversation now, so a reload must land on it — the pointer follows
    // the address rather than competing with it.
    this.transport.setLastViewed(storeActiveId);
    this.contextDomain.recordNotice = false;
    this.host.closePane();
    void this.host.setComposerState('docked');
    void this.contextDomain.refreshRecord(storeActiveId);
    void this.contextDomain.refreshHistory(storeActiveId);
  }

  private blockDeparture(): void {
    this.draftDomain.departureBlocked = true;
    this.draftDomain.revokeHumanTask(true);
    this.host.revealRecoveryOutcome();
  }

  claimConversation(id: string): void {
    this.draftDomain.departureBlocked = false;
    this.transport.setActiveConversation(id);
    this.transport.setLastViewed(id);
  }

  private async writeTitleThrough(
    id: string,
    title: string,
    before: string,
    wasRenamed: boolean,
  ): Promise<void> {
    const written = await this.transport.setConversationTitle(id, title, 'user');
    if (written.ok) return;
    this.sessions = restoreSessionTitle(this.sessions, id, before, wasRenamed);
    this.host.announce(
      written.status === 423
          ? `${reasonFor('conversations.locked').wording} — that name was not saved.`
          : SV3_RENAME_FAILED,
      'warning',
    );
  }

  private async deleteThroughStore(id: string): Promise<void> {
    this.removing = true;
    const consent = new AbortController();
    this.removalConfirmation = consent;
    // Capture before discovery, not when its reads eventually return.
    const owner = this.liveDraftOwner;
    const originalDraft = owner !== null && owner.id === this.sessions.activeId
      && owner.draft.port.isCurrent(owner.draft) ? owner : null;
    this.removalDraft = null;
    let tree: DeleteTreeEntry[] = [];
    const report = (result: DeleteCascadeResult) => {
      this.forgetDeletedConversations(result.deletedIds ?? []);
      if (!result.ok && !result.cancelled) {
        this.host.announce(this.host.deletionOutcome(result, tree), 'warning');
      }
    };
    try {
      const result = await this.transport.deleteConversationWithCascade(id, async (children, preview) => {
        tree = preview;
        const confirmed = await this.host.confirmTree(children, preview, this.apiBase || '', consent.signal);
        if (!confirmed || consent.signal.aborted) return false;
        if (originalDraft !== null && this.liveDraftOwner === originalDraft
          && preview.some(row => row.id === originalDraft.id)) this.removalDraft = originalDraft;
        // Once accepted, navigation cancels neither the Engine write nor its truthful reports.
        this.removalConfirmation = null;
        return true;
      }, report);
      report(result);
      // Only an unresolved identity can still be confirmed by this operation's later reconciliation.
      if (originalDraft === null || !result.unconfirmedIds?.includes(originalDraft.id)) this.removalDraft = null;
    } finally {
      if (this.removalConfirmation === consent) this.removalConfirmation = null;
      this.removing = false;
    }
  }

  private forgetDeletedConversations(ids: readonly string[]): void {
    for (const gone of ids) {
      const owned = this.removalDraft;
      if (owned?.id === gone) {
        if (this.host.connected() && this.sessions.activeId === gone && owned.draft.port.isCurrent(owned.draft)) owned.draft.port.clearIf(owned.draft);
        this.removalDraft = null;
      }
      this.draftDomain.placeScopes.delete(gone);
      this.draftDomain.placeTiers.delete(gone);
      this.contextDomain.forgetConversation(gone);
      this.runDomain.forgetConversation(gone);
      if (this.sessions.activeId === gone) this.draftDomain.resetToHero();
      if (this.transport.readLastViewed() === gone) this.transport.clearLastViewed();
      if (this.renamingId === gone) this.renamingId = null;
      // Server confirmation wins even over stale local run state: the content is already gone.
      this.sessions = { ...this.sessions, sessions: this.sessions.sessions.filter(s => s.id !== gone) };
    }
  }

  selectSession(id: string): void {
    // An edit in another row is DROPPED rather than committed, the spec's rule:
    // navigating away must not write text the reader walked away from.
    this.renamingId = null;
    const before = this.sessions;
    const prevId = before.activeId;
    if (sessionById(before, id) === null) return; // unknown id — nothing was claimed, so nothing to record
    if (!this.draftDomain.swapPlace(id)) return;
    this.sessions = focusSession(this.sessions, id, Date.now());
    if (this.sessions === before) return;
    this.claimConversation(id);
    // 857 D4 (drafted as 854) — the evidence pane belongs to the conversation it was opened against; a real switch
    // closes it. Guarded on the id captured BEFORE the claim, not object identity: focusSession
    // returns a NEW list even when id === prevId (it stamps lastVisitedAt on the row), so an
    // unguarded closePane() here would also fire on a re-click of the already-active row.
    if (prevId !== id) this.host.closePane();
    // The RECORD is what the reader is being shown, so it is fetched on the claim rather than trusted
    // from whatever this window happened to still hold (inventory D1).
    void this.contextDomain.refreshRecord(id);
    void this.contextDomain.refreshHistory(id);
    void this.host.setComposerState('docked');
    // Tempdoc 864 Layer 1(a) — a claim leaves the reader at a ready composer, so the caret goes there.
    // This is also what takes focus OFF the row button the click parked it on: that control swaps the
    // conversation on a bare `Space`, and a reader who believes they are typing is one keystroke from
    // swapping the thread they just opened (§2.7b).
    void this.host.focusComposer();
  }

  renameSession(id: string, title: string): void {
    const previous = sessionById(this.sessions, id);
    const before = previous?.title ?? '';
    const wasRenamed = previous?.renamed ?? false;
    this.sessions = renameSession(this.sessions, id, title);
    const after = sessionById(this.sessions, id)?.title ?? '';
    // WRITTEN THROUGH to the conversation store, which is where a name now LIVES (tempdoc 838: a
    // sealed `title` on the conversation's own record, not a browser-local map) — so it survives the
    // process, a cleared site-data and a second client, and every surface listing this conversation
    // shows the one the reader chose. Derived from the pure module's OUTCOME rather than re-deciding
    // here: `resolveSv3Rename` stays the one place an empty or unchanged title is judged.
    if (after !== before) void this.writeTitleThrough(id, after, before, wasRenamed);
  }

  pinSession(id: string): void {
    this.sessions = toggleSessionPin(this.sessions, id);
  }

  removeSession(id: string): void {
    if (this.removing) return;
    const gate = this.host.runGate();
    // The DECISION, taken now and carried into the write: an unchanged list is the refusal (work in
    // flight), and nothing else may happen on a refusal. The gate travels with it rather than being
    // re-derived after the await — the reader's press is what is being answered, and by the time the
    // store answers it has already deleted the conversation regardless of what has started since.
    if (removeSession(this.sessions, id, gate) === this.sessions) return;
    void this.deleteThroughStore(id);
  }

  newSession(): void {
    this.draftDomain.startFreshSession();
    // Tempdoc 864 Layer 1(a) — a fresh hero exists to be typed into, and the affordance that asked
    // for it is a control the reader's focus is now parked on (the sidebar's new-session button, or
    // the palette's command) — the §2.7b state where a bare `Space` re-activates it.
    void this.host.focusComposer();
  }

  async openConversation(id: string, task?: HumanTask): Promise<boolean> {
    const prevId = this.sessions.activeId;
    if (!this.draftDomain.swapPlace(id, task)) return false;
    const now = Date.now();
    this.sessions = mergeStoreConversations(this.sessions, [
      { id, title: null, firstUserMessage: '', createdAt: now, lastActiveAt: now },
    ]);
    this.sessions = focusSession(this.sessions, id, now);
    if (task !== undefined) {
      task.saved.target = id;
      task.draft = { ...task.draft, edit: this.composer.snapshot().edit };
      this.draftDomain.saveRecovery(task);
    }
    this.claimConversation(id);
    this.contextDomain.recordNotice = false;
    // The same rule `onSessionSelect` applies: an edit in another row is DROPPED rather than
    // committed, because navigating away must not write text the reader walked away from.
    this.renamingId = null;
    // 857 D4 (drafted as 854) — same guard as `onSessionSelect`: close only on a real switch, using the id captured
    // before the claim above (`this.sessions === before` identity is not this method's guard — its
    // callers already establish it is a real switch or a branch that's always new, but comparing ids
    // directly here keeps the two sites' close logic identical rather than relying on caller proofs).
    if (prevId !== id) this.host.closePane();
    void this.host.setComposerState('docked');
    await Promise.all([this.contextDomain.refreshRecord(id), this.contextDomain.refreshHistory(id), this.transport.loadConversations()]);
    return true;
  }

  get recentItems(): readonly Sv3RecentItem[] {
    const searches: Sv3RecentItem[] = this.recentSearches.map(value => ({ kind: 'search', value, label: value }));
    if (this.historyLocked || !this.recentConversationsReady) return searches;
    return [...searches, ...this.conversations.filter(c => c.lastActiveAt > this.recentClearTime)
      .sort((a, b) => b.lastActiveAt - a.lastActiveAt).slice(0, 5)
      .map(c => ({ kind: 'conversation' as const, value: c.id,
        label: c.title || '' }))];
  }

  async refreshRecents(): Promise<void> {
    const epoch = this.recentEpoch;
    const loaded = await this.transport.loadConversations();
    if (loaded && epoch === this.recentEpoch && !this.historyLocked) {
      this.recentConversationsReady = true;
      this.host.requestUpdate();
    }
  }

  clearRecents(): void {
    this.recentSearches = [];
    this.recentClearTime = Date.now();
    this.host.requestUpdate();
  }

  selectRecent(item: Sv3RecentItem): void {
    if (item.kind === 'search') {
      // Do not replace a retained preparation before its existing guards accept the move.
      if (this.host.searchNow(item.value)) this.host.writeDraft(item.value);
    } else {
      this.selectSession(item.value);
    }
  }

}
