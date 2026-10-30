// SPDX-License-Identifier: Apache-2.0
/** This window's retained human work and preparations. Storage and other areas keep their owners. */
import { forgetContainsPath, type ForgetScope } from '../../../api/domains/forget.js';
import type { SelectionPayload } from '../../../api/types/selection.js';
import type { ShapeId } from '../unifiedChatRequest.js';
import type { ComposerCapture } from '../../state/composerPort.js';
import type { DraftPersistence } from '../../controllers/draftPersistence.js';
import type { SearchFilterSpec } from '../../state/searchFiltersState.js';
import type { ChatRefusalDetail, requestLoadAnyway } from '../../state/chatResidency.js';
import type { peekSearchV3Handoff, takeSearchV3Handoff } from '../../utils/compose.js';
import type { AiState } from '../../state/aiStateStore.js';
import type { Sv3ContextDomain } from './sv3-context-domain.js';
import type { Sv3Session } from './sv3-sessions.js';
import type { Sv3SearchController, Sv3AskScope } from './sv3-search.js';
import type { sv3FollowUpContext } from './sv3-context.js';
import { projectAvailability } from '../../state/availability.js';
import { reasonFor } from '../../state/readinessNotice.js';
import { SV3_MODEL_SHAPE_ID, SV3_ASK_SHAPE_ID, SV3_EFFORT_DEFAULT, isSv3Effort, type Sv3Effort } from './sv3-ask.js';
import { SV3_TIER_DEFAULT, isSv3Tier, type Sv3ComposerTier } from './sv3-run.js';
import { SV3_RESULT_NO_DOCUMENT, type Sv3ComposerState } from './fixtures.js';
import { isSv3Recovery, isSv3Preparation, type Sv3Preparation, type Sv3RecoveryDraft,
  type Sv3Recovery, type Sv3RecoveryTask, type RecoveryShape } from './sv3-recovery.js';

const RESULTS_PLACE = '\u0000results';

export interface HumanTask {
  saved: Sv3RecoveryTask;
  question: string;
  scope: Sv3AskScope | null;
  scopeOwner: Sv3AskScope | null;
  filters: SearchFilterSpec;
  selection: SelectionPayload | null;
  draft: ComposerCapture;
  epoch: number;
  ownsComposer: boolean;
  revoked: boolean;
  abandoned: boolean;
  restored: boolean;
  context: ReturnType<typeof sv3FollowUpContext> | null;
  refusal: ChatRefusalDetail | null;
  retryAt: number;
}

type DraftSearch = Pick<Sv3SearchController, 'showing' | 'pendingScope' | 'query' | 'hide' | 'clearScope'
  | 'prepare' | 'show' | 'type' | 'reset' | 'scopeFromSelection' | 'prepareFromSelection' | 'reconcileForgotten'>;
type DraftStore = Pick<DraftPersistence, 'restoreUnavailable' | 'adoptLegacy' | 'openSessionAttachment'
  | 'saveSessionAttachment' | 'retireRawDraft'>;
type DraftContext = Pick<Sv3ContextDomain, 'clearRefusedQuestion' | 'followUpContextOf' | 'hasPendingWrite'>;

interface DraftHost {
  connected(): boolean;
  activeConversationId(): string | null;
  lastTurnShape(): string | undefined;
  session(id: string): Sv3Session | null;
  startNewSession(): void;
  keepUnsentConversation(id: string, text: string, now: number): void;
  releaseDraftOwnership(): void;
  abortRemovalConfirmation(): void;
  clearRenaming(): void;
  clearRecordNotice(): void;
  detachRun(): void;
  dismissPalette(): void;
  focusIncomingComposer(): void;
  searchFilterLabels(): string[];
  search(): DraftSearch;
  persistence(): DraftStore;
  context(): DraftContext;
  historyLocked(): boolean;
  aiSnapshot(): AiState | null;
  streaming(): boolean;
  shapeId(): string | undefined;
  initialNavigationPending(): boolean;
  writeDraft(text: string): void;
  closePane(): void;
  leaveForgetPlacement(): void;
  restoreLastViewed(): void;
  revealRecoveryOutcome(): void;
  focusComposer(): Promise<void>;
  abortAsk(): void;
  setComposerState(state: Sv3ComposerState): Promise<void>;
  runAsk(question: string, scope: Sv3AskScope | null, schema: string | undefined, model: boolean,
    options: { task: HumanTask; continuation: boolean }): Promise<void>;
  peekHandoff: typeof peekSearchV3Handoff;
  takeHandoff: typeof takeSearchV3Handoff;
  refreshProjection(): void;
  flushNavigation(): void;
  clearLastViewed(): boolean;
  readLastViewed(): string | null;
  setActiveConversation(id: string | null): void;
  createConversationId(): string;
  filters(): SearchFilterSpec;
  announce(message: string, severity: 'info' | 'warning'): void;
  loadAnyway(signal: AbortSignal): ReturnType<typeof requestLoadAnyway>;
  requestUpdate(name?: 'tier' | 'effort', previous?: unknown): void;
}

export class Sv3DraftDomain {
  constructor(private readonly host: DraftHost, private readonly composer: {
    read(): string;
    snapshot(): ComposerCapture;
  }) {}

  private tierValue: Sv3ComposerTier = SV3_TIER_DEFAULT;
  private effortValue: Sv3Effort = SV3_EFFORT_DEFAULT;
  get tier(): Sv3ComposerTier { return this.tierValue; }
  set tier(value: Sv3ComposerTier) {
    const previous = this.tierValue;
    this.tierValue = value;
    this.host.requestUpdate('tier', previous);
  }
  get effort(): Sv3Effort { return this.effortValue; }
  set effort(value: Sv3Effort) {
    const previous = this.effortValue;
    this.effortValue = value;
    this.host.requestUpdate('effort', previous);
  }
  private get search(): DraftSearch { return this.host.search(); }
  private get draftPersistence(): DraftStore { return this.host.persistence(); }
  private get contextDomain(): DraftContext { return this.host.context(); }

  mountDraftPending = false;

  mountHasIncoming = false;

  /** A refused send outlives context recovery: the reader may have typed a different draft. */
  private humanTaskValue: HumanTask | null = null;
  get humanTask(): HumanTask | null { return this.humanTaskValue; }

  private humanEpoch = 0;

  private recoveryOpenedValue = false;
  get recoveryOpened(): boolean { return this.recoveryOpenedValue; }

  private recoveryDraftsValue: Sv3Recovery['drafts'] = [];
  get recoveryDrafts(): readonly Sv3RecoveryDraft[] { return this.recoveryDraftsValue; }

  recoveryError = '';

  private refusedHandoffValue: { text: string; files: number } | null = null;
  get refusedHandoff(): { text: string; files: number } | null { return this.refusedHandoffValue; }

  /** A later accepted navigation leaves this refusal, rather than retrying it elsewhere. */
  retireRefusedHandoff(): boolean {
    if (this.refusedHandoff === null) return false;
    if (this.host.peekHandoff()?.refused) this.host.takeHandoff();
    this.refusedHandoffValue = null;
    this.recoveryError = '';
    return true;
  }

  /** Diagnostic phase of the synchronous session write, never issuance authority. */
  private recoveryWriteUnconfirmed = false;

  /** Address/store moved, but this view could not safely leave its retained work. */
  departureBlocked = false;

  private humanLoadValue: AbortController | null = null;
  get humanLoad(): AbortController | null { return this.humanLoadValue; }

  private humanRetryTimer: ReturnType<typeof setTimeout> | null = null;

  assigningHumanDraft = false;

  private draftReviewValue = false;
  get draftReview(): boolean { return this.draftReviewValue; }

  /** Separately known saved settings; equality with the raw string never binds their owner. */
  private draftProposalValue: Sv3Preparation | null = null;
  get draftProposal(): Sv3Preparation | null { return this.draftProposalValue; }

  private reviewConfigured = false;

  private preparationRevision = 0;

  /** HO9 retires one-shot context, but its unchanged text is not an all-files choice. */
  private retiredHandoffTextValue: string | null = null;
  get retiredHandoffText(): string | null { return this.retiredHandoffTextValue; }

  get retiredHandoffNotice(): string {
    return this.retiredHandoffText === null ? ''
      : 'The incoming task’s files and mode were not kept after leaving. Sending is blocked: choose files or a mode, or change the question before sending.';
  }

  clearRetiredHandoff(): void {
    if (this.retiredHandoffText === null) return;
    this.retiredHandoffTextValue = null;
    this.host.requestUpdate();
  }

  markDraftReview(proposal: Sv3Preparation | null = null): void {
    this.draftReviewValue = true;
    this.draftProposalValue = proposal;
    this.reviewConfigured = false;
    this.preparationRevision += 1;
    this.host.requestUpdate();
  }

  livePreparation(tier = this.tier, shape: string | null | undefined = this.preparedHandoff?.shapeId ?? this.host.shapeId(),
    paths = [...(this.search.pendingScope?.paths ?? [])], selection = this.preparedHandoff?.selection,
    blocked = this.handoffBlocked): Sv3Preparation {
    if (tier === 'search') return { kind: 'search' };
    if (tier === 'delegate') return { kind: 'delegate' };
    const effectiveShape = shape ?? this.host.lastTurnShape();
    if (effectiveShape === SV3_MODEL_SHAPE_ID && tier !== 'structured') return { kind: 'model' };
    const needsReselection = blocked || selection != null
      && ['text-range', 'citation', 'search-trace', 'health-condition'].includes(selection.kind);
    paths = [...new Set(paths)];
    const scope = needsReselection ? { kind: 'reselect' as const, paths }
      : paths.length > 0 ? { kind: 'files' as const, paths } : null;
    if (tier === 'structured') return { kind: 'structured', schema: this.schemaDraft,
      scope: scope ?? { kind: 'typed' } };
    if (shape === 'core.summarize') return { kind: 'summarize', scope: scope ?? { kind: 'reselect', paths: [] } };
    return { kind: 'ask', scope: scope ?? { kind: 'all' } };
  }

  currentPreparation(): Sv3Preparation | null {
    // Existing recovery already represents unknown input without granting all-files authority.
    if (this.retiredHandoffText !== null) return { kind: 'ask', scope: { kind: 'reselect', paths: [] } };
    return this.draftReview ? this.reviewConfigured ? this.draftProposal : null : this.livePreparation();
  }

  preparationSummary(preparation: Sv3Preparation | null): string {
    if (preparation === null) return 'The original mode, files and schema are unknown.';
    if (preparation.kind === 'search') {
      const filters = this.host.searchFilterLabels();
      return filters.length === 0 ? 'Search: all files, with no active filters.' : `Search with current filters: ${filters.join('; ')}.`;
    }
    if (preparation.kind === 'delegate') return 'Delegate: the agent chooses its actions; no chosen-file Ask restriction is applied.';
    if (preparation.kind === 'model') return 'Model answer: no file retrieval.';
    const input = preparation.scope.kind === 'all' ? 'all files'
      : preparation.scope.kind === 'typed' ? 'typed input, no files'
      : preparation.scope.kind === 'reselect' ? 'original input must be reselected'
      : `chosen files: ${preparation.scope.paths.join(', ')}`;
    return `${preparation.kind === 'structured' ? 'Structured' : preparation.kind === 'summarize' ? 'Summarize' : 'Ask'}: ${input}.${preparation.kind === 'structured' ? ` Schema: ${preparation.schema}` : ''}`;
  }

  preparationUsable(preparation: Sv3Preparation | null): preparation is Sv3Preparation {
    if (!isSv3Preparation(preparation)) return false;
    if ('scope' in preparation && preparation.scope.kind === 'reselect') return false;
    if (preparation.kind === 'structured') {
      try {
        const schema: unknown = JSON.parse(preparation.schema);
        if (schema === null || typeof schema !== 'object' || Array.isArray(schema)) return false;
      } catch { return false; }
    }
    return true;
  }

  applyPreparation(preparation: Sv3Preparation): void {
    this.search.clearScope();
    this.preparedHandoff = null;
    this.handoffNotice = ''; this.handoffBlocked = false;
    this.tierFollowsPlace = false;
    this.tier = preparation.kind === 'search' ? 'search' : preparation.kind === 'delegate' ? 'delegate'
      : preparation.kind === 'structured' ? 'structured' : 'ask';
    if (preparation.kind === 'model') this.preparedHandoff = { shapeId: SV3_MODEL_SHAPE_ID, selection: null };
    if ('scope' in preparation) {
      if (preparation.scope.kind === 'files') this.search.prepare({ paths: [...preparation.scope.paths] });
      if (preparation.kind === 'structured') this.schemaDraftValue = preparation.schema;
      else this.preparedHandoff = { shapeId: preparation.kind === 'summarize' ? 'core.summarize' : SV3_ASK_SHAPE_ID,
        selection: preparation.scope.kind === 'files'
          ? { kind: 'result-set', items: preparation.scope.paths.map(id => ({ id, kind: 'doc' as const })) } : null };
    }
  }

  sameDraft(a: Sv3RecoveryDraft, b: Sv3RecoveryDraft): boolean {
    return a.place === b.place && a.text === b.text
      && JSON.stringify(a.preparation ?? null) === JSON.stringify(b.preparation ?? null);
  }

  draftHasInputs(draft: Sv3RecoveryDraft): boolean {
    const preparation = draft.preparation;
    return draft.text !== '' || preparation?.kind === 'structured' && preparation.schema !== ''
      || preparation != null && 'scope' in preparation
        && (preparation.scope.kind === 'files' || preparation.scope.kind === 'reselect');
  }

  /** ACK the next current snapshot before replacement/retirement; never grow a third slot. */
  persistDraft(next: Sv3RecoveryDraft, keepCurrent = false, leaving?: Sv3RecoveryDraft, retired?: Sv3RecoveryDraft): boolean {
    if (!this.recoveryOpened) this.openRecovery();
    const draft = this.composer.snapshot();
    const revision = this.preparationRevision, place = this.humanPlace(), text = this.composer.read();
    const previous = this.recoveryPayload();
    const retained = keepCurrent ? [leaving ?? previous.drafts[0]!, ...previous.drafts.slice(1)] : previous.drafts.slice(1);
    const drafts = [next];
    for (const draft of retained) {
      const snapshot = { ...draft, preparation: draft.preparation ?? null };
      if (this.draftHasInputs(snapshot) && !(retired && this.sameDraft(snapshot, retired))
        && !drafts.some(d => this.sameDraft(d, snapshot))) drafts.push(snapshot);
    }
    const payload: Sv3Recovery = { version: 2, task: previous.task, drafts };
    if (!this.saveRecoveryPayload(payload)) return false;
    if (!this.host.connected() || !draft.port.isCurrent(draft)
      || this.preparationRevision !== revision || this.humanPlace() !== place || this.composer.read() !== text) {
      this.recoveryError = 'The draft or its settings changed while recovery was being saved. Current text is kept; review again.';
      this.host.requestUpdate(); return false;
    }
    this.recoveryDraftsValue = payload.drafts.slice(1);
    return true;
  }

  reviewDraft(search: boolean): void {
    const preparation = search ? { kind: 'search' as const } : this.draftProposal;
    if (!this.draftReview || !this.preparationUsable(preparation)) return;
    const draft = this.composer.snapshot();
    const revision = this.preparationRevision;
    const place = this.humanPlace();
    const summary = this.preparationSummary(preparation);
    if (!this.persistDraft({ place, text: this.recoveryText(this.composer.read()), preparation })) return;
    if (!this.host.connected() || !draft.port.isCurrent(draft)
      || this.preparationRevision !== revision || this.humanPlace() !== place
      || this.preparationSummary(preparation) !== summary) return;
    this.revokeHumanTask();
    this.clearRetiredHandoff();
    this.applyPreparation(preparation);
    this.draftReviewValue = false;
    this.draftProposalValue = null;
    this.reviewConfigured = false;
    this.recoveryError = '';
    this.host.requestUpdate();
  }

  private acknowledgePreparation(preparation: Sv3Preparation): boolean {
    const draft = this.composer.snapshot();
    const revision = this.preparationRevision;
    const place = this.humanPlace();
    if (!this.persistDraft({ place, text: this.recoveryText(this.composer.read()), preparation })) return false;
    return this.host.connected() && draft.port.isCurrent(draft)
      && this.preparationRevision === revision && this.humanPlace() === place;
  }

  private configureDraft(): void {
    this.clearRetiredHandoff();
    this.preparationRevision += 1;
    if (this.draftReview) { this.reviewConfigured = true; this.draftProposalValue = this.livePreparation(); }
    this.host.requestUpdate();
  }

  draftMayIssue(retain = true): boolean {
    if (this.departureBlocked || this.draftReview || this.handoffBlocked || this.retiredHandoffText !== null) { this.host.requestUpdate(); return false; }
    return !retain || this.saveRecovery();
  }

  mayForkDraft(): boolean {
    const payload = this.recoveryPayload();
    // The backend chooses the new place id. Budget its full accepted bound rather than
    // guessing a future id or storing a placeholder as callback authority.
    // Every accepted id byte may require a six-byte JSON escape. The future target
    // appears in both the current draft and (for a saved action) the submitted task.
    const futurePlace = '\u0000'.repeat(4096);
    const drafts: Sv3RecoveryDraft[] = [{ place: futurePlace, text: '', preparation: null }];
    for (const draft of payload.drafts) {
      if (this.draftHasInputs(draft) && !drafts.some(d => this.sameDraft(d, draft))) drafts.push(draft);
    }
    const planned = { ...payload, task: payload.task === null ? null : { ...payload.task, target: futurePlace }, drafts };
    if (!isSv3Recovery(planned) || new TextEncoder().encode(JSON.stringify({ version: 1,
      incarnation: 'x'.repeat(128), payload: planned })).length > 262144) {
      this.recoveryError = 'Review or discard a retained draft before opening another place. Existing text and settings are kept.';
      this.host.requestUpdate(); return false;
    }
    return this.saveRecovery();
  }

  humanPlace(): string | null { return this.search.showing ? RESULTS_PLACE : this.host.activeConversationId(); }

  recoveryText(text: string, selection = this.humanTask?.selection ?? this.preparedHandoff?.selection): string {
    // Explain's sender embeds this backend-owned block in its prepared draft. Keep only the
    // instructions in the extra copy; the live request keeps the exact trace and needs reselection.
    return selection?.kind === 'search-trace' && typeof selection.summary === 'string' && selection.summary
      ? text.replaceAll(`\n\nRanking trace:\n${selection.summary}`, '') : text;
  }

  private recoveryPayload(task = this.humanTask): Sv3Recovery {
    const place = this.humanPlace();
    const drafts: Sv3RecoveryDraft[] = [{ place,
      text: this.recoveryText(this.composer.read(), task?.selection ?? this.preparedHandoff?.selection),
      preparation: this.currentPreparation() }];
    // A composer submission can create a conversation. New input then belongs to that target;
    // a saved-turn action instead leaves its unrelated composer at the original place.
    const origin = task?.saved.record === null ? task.saved.target ?? task.saved.place : task?.saved.place;
    if (origin !== undefined && origin !== place) {
      const saved = this.placeDrafts.get(origin ?? '') ?? this.recoveryDrafts.find(d => d.place === origin);
      if (saved !== undefined && this.draftHasInputs({ place: origin, ...saved })) drafts.push({ place: origin, text: this.recoveryText(saved.text, task?.selection),
        preparation: saved.preparation ?? null });
    }
    for (const draft of this.recoveryDrafts) {
      const snapshot = { ...draft, preparation: draft.preparation ?? null };
      if (this.draftHasInputs(snapshot) && !drafts.some(d => this.sameDraft(d, snapshot))) drafts.push(snapshot);
    }
    return { version: 2, task: task?.saved ?? null, drafts };
  }

  private saveRecoveryPayload(payload: Sv3Recovery): boolean {
    this.recoveryWriteUnconfirmed = false;
    // The live payload keeps the current composer first, even when it is empty.
    // Returning to that place needs no third persisted slot beside two retained inputs.
    // Keep the live ordering so callers still retain every companion after this ACK.
    const current = payload.drafts[0];
    if (payload.drafts.length > 2 && current?.place != null && current.place !== RESULTS_PLACE
      && !this.draftHasInputs(current)) {
      payload = { ...payload, drafts: payload.drafts.slice(1) };
    }
    const drafts = payload.drafts;
    if (!isSv3Recovery(payload)) {
      const preparations = drafts.map(d => d.preparation);
      let reason = 'This preparation contains invalid or over-limit settings. Review the mode, files and schema before continuing.';
      if (drafts.length > 2) reason = 'Only two draft preparations can be retained. Review or discard a retained draft before continuing.';
      else if (drafts.some(d => new TextEncoder().encode(d.text).length > 65536)) {
        reason = 'Draft text exceeds the 65536-byte recovery limit. Reduce the text before continuing.';
      } else if (preparations.some(p => p != null && 'scope' in p && 'paths' in p.scope && p.scope.paths.length > 128)) {
        reason = 'Chosen files exceed the 128-file recovery limit. Reduce the file selection before continuing.';
      } else if (preparations.some(p => p != null && 'scope' in p && 'paths' in p.scope
        && p.scope.paths.some(path => new TextEncoder().encode(path).length > 4096))) {
        reason = 'A chosen file path exceeds the 4096-byte recovery limit. Review the file selection before continuing.';
      } else if (preparations.some(p => p?.kind === 'structured' && new TextEncoder().encode(p.schema).length > 16384)) {
        reason = 'Structured schema exceeds the 16384-byte recovery limit. Reduce the schema before continuing.';
      }
      this.recoveryError = `Recovery could not be saved safely: ${reason} Existing copies are kept.`;
      this.host.requestUpdate();
      return false;
    }
    const result = this.draftPersistence.saveSessionAttachment(payload);
    this.recoveryWriteUnconfirmed = result === 'unconfirmed';
    return result === 'saved';
  }

  saveRecovery(task = this.humanTask): boolean {
    if (!this.recoveryOpened) return false;
    const payload = this.recoveryPayload(task);
    if (!this.saveRecoveryPayload(payload)) return false;
    this.recoveryDraftsValue = payload.drafts.slice(1);
    return true;
  }

  private openRecovery(): void {
    if (this.recoveryOpened) return;
    this.recoveryOpenedValue = true;
    const supported = ['core.rag-ask', 'core.free-chat', 'core.extract', 'core.summarize'];
    const owner = this.host.shapeId() && supported.includes(this.host.shapeId()!) ? this.host.shapeId() : 'main';
    const payload = this.draftPersistence.openSessionAttachment(`search-v3.${owner}`, isSv3Recovery,
      () => this.recoveryPayload(), () => {
        this.recoveryError ||= 'Recovery could not be saved safely. Keep or copy your text and settings before leaving. No new question will be sent.';
        this.host.requestUpdate();
      });
    if (!isSv3Recovery(payload)) return;
    this.recoveryDraftsValue = payload.drafts.slice(1);
    for (const draft of payload.drafts) {
      if (draft.place !== null && draft.place !== this.humanPlace()) this.placeDrafts.set(draft.place,
        { text: draft.text, preparation: draft.preparation ?? null, review: true });
    }
    // Incoming work wins. A different nonempty raw draft may have been saved AFTER a failed
    // attachment write: keep it in the composer and expose the extra session copy for review.
    const current = payload.drafts[0];
    if (current !== undefined && current.place === this.humanPlace()) {
      const hadRaw = this.composer.read() !== '';
      if (!this.mountHasIncoming && (this.composer.read() || this.draftHasInputs(current))) {
        this.markDraftReview(current.preparation ?? null);
        if (!hadRaw) {
          this.reviewConfigured = current.preparation != null;
          this.host.writeDraft(current.text);
        }
      }
      // A typed snapshot remains a separate proposal even when an old raw writer returned
      // to identical bytes. Only a person's review can apply it to the current text.
      if (this.draftHasInputs(current) && (hadRaw && current.preparation != null || current.text !== this.composer.read() || this.mountHasIncoming)
        && !this.recoveryDrafts.some(d => this.sameDraft(d, current))) this.recoveryDraftsValue.push(current);
    } else if (current !== undefined && this.draftHasInputs(current)) {
      if (!this.recoveryDrafts.some(d => this.sameDraft(d, current))) this.recoveryDraftsValue.push(current);
      if (!this.mountHasIncoming && this.composer.read().trim()) this.markDraftReview(current.preparation ?? null);
    }
    if (payload.task !== null) {
      this.humanTaskValue = {
        saved: payload.task, question: '', scope: null, scopeOwner: null, filters: {}, selection: null,
        draft: this.composer.snapshot(), epoch: this.humanEpoch, ownsComposer: false, revoked: true, abandoned: true,
        restored: true, context: null, refusal: null, retryAt: 0,
      };
    }
    this.host.requestUpdate();
  }

  readonly onHumanAssignment = (): void => {
    this.preparationRevision += 1;
    if (this.assigningHumanDraft) return;
    if (this.humanTask !== null) this.revokeHumanTask();
  };

  /** Revocation is latched before any callback can arrive. Leaving never sends Stop. */
  revokeHumanTask(abandonInitial = false): void {
    this.humanEpoch += 1;
    if (this.humanTask !== null) {
      this.humanTask.revoked = true;
      // Input changes revoke force continuation, but an initial captured A may still send.
      // Leaving its placement/lifecycle latches abandonment even after an ABA return.
      this.humanTask.abandoned ||= abandonInitial;
    }
    this.humanLoad?.abort();
    this.humanLoadValue = null;
    if (this.humanRetryTimer !== null) clearTimeout(this.humanRetryTimer);
    this.humanRetryTimer = null;
    this.host.requestUpdate();
  }

  readonly leaveHumanTask = (): void => { this.revokeHumanTask(true); this.saveRecovery(); };

  humanOwned(task: HumanTask): boolean {
    return this.host.connected() && this.humanTask === task && !task.revoked && !task.restored
      && this.humanEpoch === task.epoch && this.host.activeConversationId() === task.saved.target
      && task.draft.port.isCurrent(task.draft);
  }

  humanUnavailable(shape: RecoveryShape, continuation = false): string {
    if (this.departureBlocked) return 'The address moved but this view did not leave safely. Return to this conversation before sending or changing its task.';
    if (this.host.historyLocked()) return reasonFor('conversations.locked').wording;
    const availability = projectAvailability(shape === 'core.rag-ask' ? 'documents' : 'extract', this.host.aiSnapshot(), true, continuation);
    return availability.kind === 'unavailable' ? availability.reason : '';
  }

  reserveHumanTask(question: string, scope: Sv3AskScope | null, schema: string | undefined,
    shape: RecoveryShape, selection: SelectionPayload | null, ownsComposer: boolean,
    record: string | null = null, authored?: string, filters?: SearchFilterSpec): HumanTask | null {
    if (this.humanTask !== null) {
      this.recoveryError = 'Review the retained question, then explicitly discard it before starting another submission.';
      this.host.requestUpdate(); return null;
    }
    const task: HumanTask = {
      saved: { id: crypto.randomUUID(), place: this.humanPlace(), target: this.host.activeConversationId(),
        raw: this.recoveryText(authored ?? (ownsComposer ? this.composer.read() : record === null ? question : ''), selection), schema: schema ?? null,
        shape, scope: [...(scope?.paths ?? [])], record, needsReselection: selection !== null,
        effort: this.effort, delivery: 'unaccepted' },
      filters: filters ?? (shape === SV3_ASK_SHAPE_ID && scope === null && selection === null ? structuredClone(this.host.filters()) : {}),
      question, scope: scope === null ? null : { paths: [...scope.paths] }, scopeOwner: scope,
      selection: selection === null ? null : structuredClone(selection), draft: this.composer.snapshot(),
      epoch: this.humanEpoch, ownsComposer,
      revoked: false, abandoned: false, restored: false, context: null, refusal: null, retryAt: 0,
    };
    // Reserve before context waits or forks. A failed ACK never sacrifices live input or old bytes.
    if (!this.saveRecovery(task)) return null;
    this.humanTaskValue = task;
    // A reserved submission retains its input independently of its conversation's lifetime.
    if (ownsComposer) { this.host.releaseDraftOwnership(); }
    this.recoveryError = '';
    this.host.requestUpdate();
    return task;
  }

  retireHumanTask(task: HumanTask): void {
    if (this.humanTask !== task) return;
    // Preserve companion drafts in the bounded attachment, even after acceptance/discard.
    const payload = this.recoveryPayload(task);
    payload.task = null;
    if (!this.saveRecoveryPayload(payload)) { task.revoked = true; return; }
    this.recoveryDraftsValue = payload.drafts.slice(1);
    this.humanTaskValue = null;
    this.recoveryError = '';
    this.host.requestUpdate();
  }

  discardHumanTask(): void {
    this.revokeHumanTask(true);
    if (this.humanTask !== null) {
      const task = this.humanTask;
      this.retireHumanTask(task);
      if (this.humanTask === null) {
        if (task.saved.place !== null) this.contextDomain.clearRefusedQuestion(task.saved.place);
        if (task.saved.target !== null) this.contextDomain.clearRefusedQuestion(task.saved.target);
      }
    }
  }

  discardRecoveryDraft(draft: Sv3RecoveryDraft): void {
    const payload = this.recoveryPayload();
    payload.drafts = payload.drafts.filter((d, index) => index === 0 || !this.sameDraft(d, draft));
    if (!this.saveRecoveryPayload(payload)) return;
    this.recoveryDraftsValue = payload.drafts.slice(1);
    const saved = draft.place === null ? undefined : this.placeDrafts.get(draft.place);
    if (saved && this.sameDraft({ place: draft.place, ...saved }, draft)) this.placeDrafts.delete(draft.place!);
    this.recoveryError = '';
    const pending = this.host.peekHandoff();
    if (pending?.refused) {
      pending.refused = false;
      this.receiveHandoff();
    }
    this.host.requestUpdate();
  }

  reviewRecoveryDraft(draft: Sv3RecoveryDraft): void {
    const next = { ...draft, place: this.humanPlace(), preparation: draft.preparation ?? null };
    if (!this.persistDraft(next, true, undefined, draft)) return;
    this.clearRetiredHandoff();
    if (this.retireRefusedHandoff()) this.host.refreshProjection();
    this.revokeHumanTask();
    this.markDraftReview(next.preparation);
    this.reviewConfigured = next.preparation !== null;
    this.host.writeDraft(next.text);
    this.host.requestUpdate();
  }

  async continueHumanTask(force: boolean): Promise<void> {
    const task = this.humanTask;
    if (task === null || this.humanLoad !== null || this.host.streaming() || !this.humanOwned(task)
      || task.saved.delivery !== 'unaccepted' || task.refusal === null
      || (force ? task.refusal.forceAllowed !== true : Date.now() < task.retryAt)) return;
    const operation = new AbortController();
    this.humanLoadValue = operation;
    this.host.requestUpdate();
    try {
      if (force) {
        const result = await this.host.loadAnyway(operation.signal);
        if (!this.humanOwned(task) || operation.signal.aborted) return;
        if (!result.ok) {
          task.refusal = result.refusal;
          task.retryAt = Date.now() + (result.refusal?.retryAfterMs ?? 0);
          this.scheduleHumanRetry(task);
          this.recoveryError = result.refusal?.message ?? 'Chat could not be loaded. The question is retained.';
          return;
        }
      }
      if (!this.humanOwned(task) || operation.signal.aborted) return;
      const reason = this.humanUnavailable(task.saved.shape, force);
      const session = task.saved.target === null ? null : this.host.session(task.saved.target);
      const context = task.saved.shape === SV3_MODEL_SHAPE_ID || session === null ? []
        : session.history === null && task.saved.place === null && session.turns.length === 0 ? []
        : this.contextDomain.followUpContextOf(session.id);
      if (reason || (task.saved.target !== null && this.contextDomain.hasPendingWrite(task.saved.target))
        || JSON.stringify(context) !== JSON.stringify(task.context)) {
        task.revoked = true;
        this.recoveryError = reason || 'Saved context changed. Review the retained question before a new submission.';
        return;
      }
      await this.host.runAsk(task.question, task.scope, task.saved.schema ?? undefined,
        task.saved.shape === SV3_MODEL_SHAPE_ID, { task, continuation: force });
    } catch {
      if (!operation.signal.aborted) this.recoveryError = 'Chat could not be loaded. The question is retained.';
    } finally {
      if (this.humanLoad === operation) this.humanLoadValue = null;
      this.host.requestUpdate();
    }
  }

  scheduleHumanRetry(task: HumanTask): void {
    if (this.humanRetryTimer !== null) clearTimeout(this.humanRetryTimer);
    // A timer only refreshes the affordance. It never issues a request.
    this.humanRetryTimer = setTimeout(() => { this.humanRetryTimer = null; this.host.requestUpdate(); }, Math.min(2147483647, Math.max(0, task.retryAt - Date.now())));
  }

  /**
   * WP1.1 S1.13 — each place's own unsent draft: a conversation's follow-up, or the result list's
   * query. Window-local, like the result list itself; the one persisted draft is still the composer's.
   */
  private readonly placeDrafts = new Map<string, { text: string; preparation: Sv3Preparation | null; review: boolean }>();

  rememberPlace(place: string, text: string): void {
    this.placeDrafts.set(place, { text, preparation: this.currentPreparation(), review: this.draftReview || this.retiredHandoffText !== null });
  }

  /**
   * WP1.1 — the mode is Ask only because a conversation was opened from Search, not because the
   * reader chose it. A New session undoes exactly that switch and keeps a mode the reader chose.
   */
  tierFollowsPlace = false;

  /**
   * WP1.1 — the mode the result list was left in, so going back to it restores an Ask or Delegate
   * draft written there as that, rather than as a query it never was.
   */
  private resultsTier: Sv3ComposerTier = 'search';

  /* ── The conversation record (tempdoc 822 Phase F6) ───────────────────────────────────────── */

  settleMountDraft(): void {
    if (!this.host.connected() || !this.mountDraftPending || this.host.initialNavigationPending()) return;
    this.mountDraftPending = false;
    if (!this.host.shapeId() && !this.mountHasIncoming) {
      this.draftPersistence.adoptLegacy('unified-chat.composer');
      this.host.restoreLastViewed();
    }
    this.openRecovery();
    if (this.draftPersistence.restoreUnavailable) {
      this.markDraftReview();
      this.recoveryError = 'A saved draft could not be read. Keep or copy your current text and review its settings; unreadable storage is not an empty draft.';
    }
  }

  /**
   * WP1.1 S1.10 — the shared query moved without this window: the default window searched while this
   * one was away (or beside it), or an address set it. On the hero or the result list in Search mode
   * the composer's text IS the query, so it follows and the list shows what the query found — on a
   * first entry too. A reader's own text is never the price: an Ask or Delegate draft stays as it
   * is, and so does text this window never searched (a restored draft), with no list beside it
   * claiming to be its results. An open conversation is left alone; Back to the list arrives at the
   * shared query (`returnToResults`).
   */
  followSharedQuery(previous: string): void {
    if (this.draftReview) return;
    if (this.tier !== 'search' || this.host.activeConversationId() !== null) return;
    if (this.composer.read().trim() !== previous.trim()) {
      this.search.hide();
      return;
    }
    const query = this.search.query;
    if (query.trim() !== '') { if (!this.enterResults('', query)) return; }
    else if (!this.persistDraft({ place: this.humanPlace(), text: query, preparation: { kind: 'search' } })) return;
    this.host.writeDraft(query);
  }

  /**
   * Show the result list. From a conversation this is a MOVE, not a teardown: the conversation keeps
   * streaming, running or waiting for its decision (nothing is aborted or detached), stays in the
   * sidebar one click away, and keeps its unsent draft (`leftDraft`, the text before this keystroke)
   * for when the reader goes back. The address moves to the bare surface, so Back returns to it.
   */
  enterResults(leftDraft: string, query = this.search.query): boolean {
    this.host.abortRemovalConfirmation();
    if (!this.search.showing) {
      this.host.leaveForgetPlacement();
      this.revokeHumanTask(true);
      const leaving = this.host.activeConversationId();
      if (!this.persistDraft({ place: RESULTS_PLACE, text: query, preparation: { kind: 'search' } },
        leaving !== null, { place: leaving, text: leftDraft, preparation: this.currentPreparation() })) return false;
      if (leaving !== null) {
        this.rememberPlace(leaving, leftDraft);
        this.host.startNewSession();
        this.host.setActiveConversation(null);
        this.host.clearLastViewed();
      }
      this.host.clearRenaming();
      this.host.clearRecordNotice();
      // A cited document belonged to the conversation's answer, not to this list.
      this.host.closePane();
      this.search.show();
    }
    if (this.retireRefusedHandoff()) {
      this.host.flushNavigation();
      this.host.refreshProjection();
    }
    void this.host.setComposerState('docked');
    return true;
  }

  /** Back/Forward onto the bare address while this window has a result list. */
  returnToResults(): boolean {
    this.host.leaveForgetPlacement();
    this.host.abortRemovalConfirmation();
    this.revokeHumanTask(true);
    const saved = this.placeDrafts.get(RESULTS_PLACE);
    const preparation = saved?.preparation ?? (this.search.pendingScope !== null
      ? this.livePreparation('ask') : this.livePreparation(this.resultsTier));
    const text = preparation.kind === 'search' ? this.search.query : saved?.text ?? this.search.query;
    const retired = saved?.review === false && saved.preparation?.kind === 'search'
      ? { place: RESULTS_PLACE, text: saved.text, preparation: saved.preparation } : undefined;
    if (!this.persistDraft({ place: RESULTS_PLACE, text, preparation }, true, undefined, retired)) return false;
    this.retireRefusedHandoff();
    const leaving = this.host.activeConversationId();
    if (leaving !== null) this.rememberPlace(leaving, this.composer.read());
    this.host.clearRenaming(); this.host.clearRecordNotice(); this.host.closePane();
    this.host.startNewSession();
    this.search.show();
    if (saved?.review) { this.markDraftReview(preparation); this.reviewConfigured = true; }
    else { this.draftReviewValue = false; this.draftProposalValue = null; this.reviewConfigured = false; this.applyPreparation(preparation); }
    this.host.writeDraft(text);
    void this.host.setComposerState('docked');
    return true;
  }

  /**
   * A conversation is being opened by navigation (a row, Back/Forward, a branch, a version). The
   * place being left keeps its draft and the one arrived at gets its own back; the result list is
   * no longer on screen, and Enter continues the conversation rather than searching away from it.
   * Leaving the hero is not leaving a place: its draft travels, as it always has.
   */
  swapPlace(to: string, task?: HumanTask): boolean {
    if (this.host.activeConversationId() !== to) this.host.abortRemovalConfirmation();
    if (!this.recoveryOpened) this.openRecovery();
    const from = this.search.showing ? RESULTS_PLACE : this.host.activeConversationId();
    if (from === to) return true;
    if (task !== this.humanTask) this.revokeHumanTask(true);
    const saved = this.placeDrafts.get(to);
    const carry = from === null && saved === undefined && this.preparedHandoff === null;
    const restoringRaw = this.mountDraftPending && this.composer.read() !== '';
    const text = restoringRaw || carry ? this.composer.read() : saved?.text ?? '';
    const carried = carry ? this.currentPreparation() : null;
    const preparation = restoringRaw ? null : carried?.kind === 'search' ? { kind: 'ask' as const, scope: { kind: 'all' as const } }
      : carry ? carried : saved?.preparation ?? null;
    const review = restoringRaw || (carry ? this.draftReview : saved?.review ?? false);
    if (!this.mountDraftPending && !this.persistDraft({ place: to, text: this.recoveryText(text), preparation }, !carry)) return false;
    this.retireRefusedHandoff();
    const leavingHandoff = this.preparedHandoff !== null;
    if (from !== null) this.rememberPlace(from, this.composer.read());
    if (!carry) this.clearRetiredHandoff();
    if (from === RESULTS_PLACE) this.resultsTier = this.tier;
    this.preparedHandoff = null;
    this.handoffNotice = ''; this.handoffBlocked = false;
    this.host.leaveForgetPlacement();
    this.search.hide(); this.askInConversation();
    if (review) {
      this.markDraftReview(preparation);
      this.reviewConfigured = preparation !== null;
    } else {
      this.draftReviewValue = false; this.draftProposalValue = null; this.reviewConfigured = false;
      if (preparation !== null) this.applyPreparation(preparation);
      this.askInConversation();
    }
    this.assigningHumanDraft = task !== undefined;
    try { this.host.writeDraft(text); } finally { this.assigningHumanDraft = false; }
    if (preparation === null && (leavingHandoff || this.placeScopes.has(to))) {
      this.search.clearScope(); this.search.prepare(this.placeScopes.get(to) ?? null);
    }
    const savedTier = this.placeTiers.get(to);
    if (savedTier !== undefined && preparation === null) this.tier = savedTier;
    return true;
  }

  /** Opening a conversation from Search mode: Enter now continues it. */
  askInConversation(): void {
    if (this.tier !== 'search') return;
    this.tier = 'ask';
    this.tierFollowsPlace = true;
  }

  /** A send opened a conversation from the result list: the list keeps its query for Back. */
  leaveResultsBySending(): void {
    if (!this.search.showing) return;
    this.placeDrafts.set(RESULTS_PLACE, { text: this.search.query, preparation: { kind: 'search' }, review: false });
    this.resultsTier = 'search';
    this.search.hide();
  }

  /**
   * Make the next Ask one about these files: fixed now, so a later re-rank or filter cannot change
   * it (S1.6). Refused — never widened — when the choice names no document, and refused with the
   * existing reason when chat cannot run, keeping the query and the choice (S1.4).
   */
  prepareAsk(scope: Sv3AskScope | null): void {
    const unavailable = this.humanUnavailable(SV3_ASK_SHAPE_ID);
    if (unavailable !== '') {
      this.host.announce(unavailable, 'warning');
      return;
    }
    if (scope === null) {
      this.host.announce(SV3_RESULT_NO_DOCUMENT, 'warning');
      return;
    }
    this.revokeHumanTask();
    if (!this.acknowledgePreparation({ kind: 'ask', scope: { kind: 'files', paths: [...scope.paths] } })) return;
    if (this.preparedHandoff !== null || this.retiredHandoffText !== null) this.preparedHandoff = { shapeId: SV3_ASK_SHAPE_ID, selection: null };
    this.handoffNotice = ''; this.handoffBlocked = false;
    this.search.prepare(scope);
    this.tier = 'ask';
    this.tierFollowsPlace = false;
    this.configureDraft();
    void this.host.focusComposer();
  }

  preparedHandoff: { shapeId: ShapeId | 'core.summarize' | null; selection: SelectionPayload | null } | null = null;

  handoffNotice = '';

  handoffBlocked = false;

  readonly placeScopes = new Map<string, Sv3AskScope | null>();

  readonly placeTiers = new Map<string, Sv3ComposerTier>();

  /** Compatibility surfaces keep their mode preset but never run on arrival. */
  prepareCompatibilityShape(): void {
    const shape = this.host.shapeId();
    this.tierFollowsPlace = false;
    if (shape === 'core.workflow-run') {
      this.tier = 'search';
      this.handoffNotice = 'Workflow runs are not available here';
      this.host.announce(this.handoffNotice, 'info');
    } else if (shape === 'core.agent-run') this.tier = 'delegate';
    else {
      this.tier = shape === 'core.extract' ? 'structured' : 'ask';
      this.preparedHandoff = { shapeId: shape === 'core.free-chat' ? 'core.free-chat' : shape === 'core.extract' ? 'core.extract' : 'core.rag-ask', selection: null };
    }
  }

  readonly receiveHandoff = (): boolean => {
    if (this.host.shapeId()) return false;
    const incoming = this.host.peekHandoff();
    if (incoming === null) {
      if (this.retireRefusedHandoff()) this.host.requestUpdate();
      return false;
    }
    if (incoming.refused) return true;
    if (incoming.origin === 'restore') {
      this.host.takeHandoff();
      this.retireRefusedHandoff();
      this.revokeHumanTask(true);
      // A journal/traversal entry describes old navigation, not the current raw draft.
      // Never reapply its original query, clear newer B, or resurrect consumed work.
      if (this.composer.read().trim() && this.retiredHandoffText === null) {
        const proposal = this.currentPreparation();
        this.markDraftReview(proposal);
        this.reviewConfigured = proposal !== null;
      }
      // Review is ephemeral authority, not a data replacement. The actual place
      // transition below must validate its whole target/origin capacity before writing.
      return true;
    }
    this.host.abortRemovalConfirmation();
    this.mountHasIncoming = true;
    this.openRecovery();
    const { state, selection } = incoming;
    const shape = typeof state.forceShapeId === 'string' ? state.forceShapeId : (state.affordance === 'extract' || state.mode === 'structured' ? 'core.extract'
      : state.affordance === 'documents' || state.mode === 'ask' || Object.hasOwn(state, 'docIds') ? 'core.rag-ask' : null);
    const query = typeof state.query === 'string' ? state.query : '';
    const tier: Sv3ComposerTier = shape === null || shape === 'core.workflow-run' ? 'search' : shape === 'core.extract' ? 'structured' : 'ask';
    const allowed = ['core.rag-ask', 'core.free-chat', 'core.summarize', 'core.extract'];
    let shapeId: ShapeId | 'core.summarize' = allowed.includes(shape ?? '') ? shape as ShapeId | 'core.summarize' : 'core.rag-ask';
    let notice = '';
    let blocked = false;
    let review = false;
    if (shape !== null && !allowed.includes(shape) && shape !== 'core.workflow-run') {
      notice = 'This shape is unavailable. Prepared in Ask; review before sending.';
      blocked = true;
    }
    let paths: string[] = [];
    if (Array.isArray(state.docIds)) paths = state.docIds.filter((id): id is string => typeof id === 'string' && id.trim() !== '');
    else if (typeof state.docIds === 'string' && state.docIds.trim() !== '') paths = [state.docIds];
    if (selection !== null) {
      switch (selection.kind) {
        case 'item': paths = [selection.itemId]; break;
        case 'text-range': paths = [selection.address?.docId]; break;
        case 'citation': paths = [selection.citation?.parentDocId]; break;
        case 'result-set': paths = Array.isArray(selection.items) ? selection.items.map(item => item?.id) : []; break;
      }
    }
    paths = [...new Set(paths)].filter(path => typeof path === 'string' && path.trim() !== '');
    if (paths.length > 0 && shapeId === 'core.free-chat') {
      shapeId = 'core.rag-ask';
      notice = 'This mode cannot read files. Prepared in Ask over the chosen files; review before sending.';
      review = true;
    }
    const invalidSelection = selection !== null &&
      (!['item', 'text-range', 'citation', 'result-set', 'search-trace', 'health-condition'].includes(selection.kind) ||
        ['item', 'text-range', 'citation', 'result-set'].includes(selection.kind) && paths.length === 0);
    if (shape !== null && (Object.hasOwn(state, 'docIds') && paths.length === 0 && shapeId !== 'core.extract' && selection === null ||
      shapeId === 'core.summarize' && paths.length === 0 && selection === null)) {
      blocked = true; notice = 'No readable document was supplied. Choose a file before sending.';
    }
    if (invalidSelection) { blocked = true; notice = 'Invalid selection. Choose the files or passage again before sending.'; }
    if (Object.hasOwn(state, 'mode') && (typeof state.mode !== 'string' || !['search', 'ask', 'structured'].includes(state.mode))) {
      blocked = true; notice = 'Unknown hand-off mode. Review the task before sending.';
    }
    if (Object.hasOwn(state, 'selection')) { blocked = true; notice = 'Invalid selection link. Choose the files or passage again before sending.'; }
    if (Object.hasOwn(state, 'forceShapeId') && typeof state.forceShapeId !== 'string' ||
      Object.hasOwn(state, 'docIds') && !(typeof state.docIds === 'string' && state.docIds.trim() !== '' ||
        Array.isArray(state.docIds) && (state.docIds.length > 0 || shapeId === 'core.extract') && state.docIds.every(id => typeof id === 'string' && id.trim() !== '')) && selection === null) {
      blocked = true; notice = 'Invalid task data. Choose the files or mode again before sending.';
    }
    if (shape === 'core.workflow-run') notice = 'Workflow runs are not available here';
    const preparation = this.livePreparation(tier, shape === null ? null : shapeId, paths, selection, blocked);
    // Intake is synchronous: reserve the incoming current snapshot plus necessary outgoing
    // copies before changing place, text, schema or scope. Capacity/readback failure rejects it.
    // Cold raw restoration has no settings authority. When an existing proposal already
    // retains exactly that text at that place, keep the proposal for review rather than
    // reserving another slot for its raw bytes. Never apply its settings to the live draft.
    const leaving = this.mountDraftPending && this.draftReview && this.draftProposal === null
      ? this.recoveryDrafts.find(d => d.place === this.humanPlace() && d.text === this.recoveryText(this.composer.read()))
      : undefined;
    if (!this.persistDraft({ place: null, text: this.recoveryText(query, selection), preparation }, true, leaving)) {
      incoming.refused = true;
      this.refusedHandoffValue = { text: query, files: paths.length };
      this.host.requestUpdate();
      return true;
    }
    this.host.takeHandoff();
    this.clearRetiredHandoff();
    this.retireRefusedHandoff();
    this.revokeHumanTask(true);
    const from = this.search.showing ? RESULTS_PLACE : this.host.activeConversationId();
    const restoredDraftOwner = from === null ? this.host.readLastViewed() : null;
    if (from !== null && from !== RESULTS_PLACE) {
      this.rememberPlace(from, this.composer.read());
      this.placeScopes.set(from, this.search.pendingScope);
      this.placeTiers.set(from, this.tier);
    } else if (restoredDraftOwner !== null && this.preparedHandoff === null) this.rememberPlace(restoredDraftOwner, this.composer.read());
    else if (from !== RESULTS_PLACE && this.tier !== 'search' && this.preparedHandoff === null && this.composer.read().trim() !== '') {
      const id = this.host.createConversationId();
      const now = Date.now();
      this.host.keepUnsentConversation(id, this.composer.read(), now);
      this.rememberPlace(id, this.composer.read());
      this.placeScopes.set(id, this.search.pendingScope);
      this.placeTiers.set(id, this.tier);
    }
    this.host.clearRenaming();
    this.host.dismissPalette();
    this.host.startNewSession();
    this.search.hide(); this.search.clearScope(); this.host.closePane();
    this.host.setActiveConversation(null); this.host.clearLastViewed();
    this.host.refreshProjection();
    this.preparedHandoff = shape === 'core.workflow-run' ? null : { shapeId: shape === null ? null : shapeId,
      selection: shape === null ? null : selection ?? (paths.length > 0 ? { kind: 'result-set', items: paths.map(id => ({ id, kind: 'doc' as const })) } : null) };
    this.handoffNotice = notice; this.handoffBlocked = blocked;
    this.tier = tier; this.tierFollowsPlace = false;
    if (paths.length > 0 && shape !== 'core.workflow-run') this.search.prepare({ paths });
    this.draftReviewValue = review || blocked;
    this.draftProposalValue = this.draftReview ? preparation : null;
    this.reviewConfigured = this.draftReview;
    this.host.writeDraft(query);
    this.saveRecovery();
    if (shape !== 'core.workflow-run' && !this.handoffBlocked && !this.draftReview && tier === 'search' && query.trim()
      && this.persistDraft({ place: RESULTS_PLACE, text: query, preparation: { kind: 'search' } })) {
      this.search.type(query); this.search.show();
    }
    void this.host.setComposerState('docked'); this.host.requestUpdate();
    this.host.focusIncomingComposer();
    this.host.announce(notice || `${tier === 'search' ? 'Search' : shapeId === 'core.summarize' ? 'Summarize' : tier === 'structured' ? 'Structured' : 'Ask'} prepared${paths.length ? ': ' + paths.join(', ') : ''}.`, 'info');
    return true;
  };

  private schemaDraftValue = '';
  get schemaDraft(): string { return this.schemaDraftValue; }

  /**
   * The STATE half of a new session, without the focus move — the form a DISCARD reaches it in.
   *
   * After a discard the sidebar owns where the keyboard goes: the successor's row button, falling
   * back to the new-session control when nothing survives (`Sv3Sidebar.placeFocusAfterDiscard`,
   * tempdoc 831's "never simply drop the focus"). That placement runs in the sidebar's own `updated`,
   * i.e. after this window's, so a composer focus on this path would not compete with it — it would
   * silently win, and retire an a11y guarantee. Tempdoc 864 §3.3(c) keeps that guarantee deliberately
   * and puts the remaining hazard — the parked control is an armed trigger — on the row's focus
   * INDICATOR instead, which is a later PR's presentation work.
   */
  startFreshSession(): void {
    if (!this.resetToHero(true)) return;
    // New conversation means "do not restore the one I just left" (tempdoc 609 Phase 3).
    this.host.setActiveConversation(null);
    this.departureBlocked = false;
    // Settle this accepted navigation before immediate reload or traversal can read the old URL.
    this.host.flushNavigation();
  }

  /**
   * The hero teardown without clearing the composer's unsent draft or making a claim.
   * Explicit New/navigation callers clear the draft; confirmed deletion only retires the transcript.
   * Tempdoc 864 PR C — the claim split matters more than it looks.
   *
   * {@link startFreshSession} is the reader STARTING a new session, so it claims: it writes the
   * app-wide pointer, which the URL projects as a navigation and therefore as a history entry.
   * {@link followStoreConversation} arriving at the hero is the opposite — the browser has already
   * moved and the store is already null. Calling the claiming form there would write during
   * popstate handling, and the projector cannot tell that write from a fresh one: it would push an
   * entry onto a stack the browser had just walked back through, TRUNCATING the forward tail. Back
   * onto the hero would silently destroy Forward.
   *
   * That was live in the first cut of this PR and masked by two accidents — production listener
   * order (the projector's adapter subscribes synchronously in `activateProjection`, this window's
   * listener only after its dynamic import) and the `isCurrentUrl` downgrade. Neither is a
   * guarantee, so the fix is to not make the write at all.
   */
  resetToHero(clearDraft = false, retainLeaving = false): boolean {
    this.host.leaveForgetPlacement();
    this.host.abortRemovalConfirmation();
    this.revokeHumanTask(true);
    const preparation = clearDraft ? { kind: 'search' as const } : this.currentPreparation();
    const text = clearDraft ? '' : this.composer.read();
    const leaving = this.humanPlace();
    if (!this.persistDraft({ place: null, text: this.recoveryText(text), preparation }, retainLeaving)) {
      if (clearDraft && !retainLeaving) {
        this.recoveryError = this.recoveryWriteUnconfirmed
          ? 'Partial, unconfirmed retirement: New could not be confirmed. This draft’s saved settings may already have been retired and exact settings may not survive closing or reloading. Current text and settings are still visible here; raw draft and reload pointer have not been retired. Keep or copy current text and settings before leaving.'
          : `New was not confirmed. ${this.recoveryError || 'Recovery could not be acknowledged. Current text and settings are kept here.'}`;
        this.host.revealRecoveryOutcome();
      }
      return false;
    }
    if (clearDraft && !retainLeaving
      && (!this.draftPersistence.retireRawDraft() || !this.host.clearLastViewed())) {
      // Independent stores are not transactional: keep the live owner and its recoverable proposal.
      this.saveRecovery();
      this.recoveryError = 'New was not confirmed: raw draft or reload-pointer retirement could not be acknowledged. Current text and settings are kept here; saved copies may remain. Raw saving may be unavailable. Saved settings may not survive reload. Keep or copy current text and settings before closing, or reload to review available saved copies.';
      this.host.revealRecoveryOutcome();
      return false;
    }
    this.retireRefusedHandoff();
    if (leaving !== null) {
      if (retainLeaving) this.rememberPlace(leaving, this.composer.read());
      else if (clearDraft) {
        this.placeDrafts.delete(leaving);
        this.placeScopes.delete(leaving);
        this.placeTiers.delete(leaving);
      }
    }
    this.preparedHandoff = null;
    this.handoffNotice = ''; this.handoffBlocked = false;
    this.host.abortAsk();
    this.clearRetiredHandoff();
    // DETACHED, not halted (Slice 516 FIX-T1's rule, applied to the run half). A delegated run is
    // hosted by the product-wide controller and may be watched elsewhere, so this window stops
    // RENDERING it and never stops it. Dropping the local handle is also what keeps the composer's
    // slot honest: Send belongs in a fresh conversation, not a Stop for a run that is not in it.
    this.host.detachRun();
    this.host.clearRenaming();
    this.host.clearRecordNotice();
    // The open document was a citation of THIS conversation's answer; it says nothing about the one
    // being started, and a pane left open would attach another session's evidence to a blank hero.
    this.host.closePane();
    this.host.startNewSession();
    // WP1.1 — a new session leaves the result list, its choice and its prepared Ask behind. Its
    // mode is the reader's: Search, unless they chose another one themselves.
    this.search.reset();
    this.placeDrafts.delete(RESULTS_PLACE);
    this.resultsTier = 'search';
    if (this.tierFollowsPlace) this.tier = SV3_TIER_DEFAULT;
    this.tierFollowsPlace = false;
    if (clearDraft) {
      this.draftReviewValue = false; this.draftProposalValue = null; this.reviewConfigured = false;
      this.recoveryError = '';
      this.schemaDraftValue = ''; this.preparationRevision += 1;
      this.host.writeDraft('');
    } else if (preparation !== null && !this.draftReview) this.applyPreparation(preparation);
    void this.host.setComposerState('hero');
    return true;
  }
  changeEffort(effort: unknown): void {
    this.revokeHumanTask();
    if (!isSv3Effort(effort)) return;
    this.effort = effort;
  }
  changeTier(tier: unknown): void {
    // Choosing the shown mode only changes intent when retired hand-off text needs review.
    if (tier === this.tier && this.retiredHandoffText === null) return;
    this.revokeHumanTask();
    if (!isSv3Tier(tier)) return;
    const paths = this.preparedHandoff !== null || tier === 'search' || tier === 'delegate' ? []
      : [...(this.search.pendingScope?.paths ?? [])];
    if (tier === 'structured' && this.search.showing && paths.length === 0) paths.push(...(this.search.scopeFromSelection()?.paths ?? []));
    if (!this.acknowledgePreparation(this.livePreparation(tier, this.host.shapeId(), paths, null, false))) return;
    if (this.retireRefusedHandoff()) {
      this.host.flushNavigation();
      this.host.refreshProjection();
    }
    if (this.preparedHandoff !== null || tier === 'search' || tier === 'delegate') this.search.clearScope();
    this.preparedHandoff = null;
    this.handoffNotice = ''; this.handoffBlocked = false;
    this.tier = tier;
    this.tierFollowsPlace = false;
    if (tier === 'structured' && this.search.showing && this.search.pendingScope === null) {
      this.search.prepareFromSelection();
    }
    this.configureDraft();
    // WP1.1 S1.10 — beside the result list, Search mode's text is the query: text written in another
    // mode is searched as it stands, so the composer and the list never describe different queries.
    if (tier === 'search' && this.search.showing && this.draftMayIssue()) this.search.type(this.composer.read());
  }
  reconcileForgotten(forgottenPath: string, scope: ForgetScope): void {
    if (!this.recoveryOpened) this.openRecovery();
    const forgotten = (path: string) => forgetContainsPath(forgottenPath, scope, path);
    const pruneScope = (scope: Sv3AskScope | null): Sv3AskScope | null => {
      if (scope === null) return null;
      const paths = scope.paths.filter(path => !forgotten(path));
      return paths.length === scope.paths.length ? scope : paths.length ? { paths } : null;
    };
    const prunePreparation = (preparation: Sv3Preparation | null): Sv3Preparation | null => {
      if (preparation === null || !('scope' in preparation) || !('paths' in preparation.scope)) return preparation;
      const paths = preparation.scope.paths.filter(path => !forgotten(path));
      if (paths.length === preparation.scope.paths.length) return preparation;
      return { ...preparation, scope: { kind: paths.length ? preparation.scope.kind : 'reselect', paths } };
    };
    const pruneSelection = (selection: SelectionPayload | null): SelectionPayload | null => {
      if (selection === null) return null;
      if (selection.kind === 'result-set') {
        if (!Array.isArray(selection.items) || selection.items.some(item => typeof item?.id !== 'string')) return null;
        const items = selection.items.filter(item => !forgotten(item.id));
        return items.length === selection.items.length ? selection : items.length ? { ...selection, items } : null;
      }
      const path = selection.kind === 'text-range' ? selection.address?.docId
        : selection.kind === 'citation' ? selection.citation?.parentDocId
        : selection.kind === 'search-trace' ? selection.docId
        : selection.kind === 'item' ? selection.itemId : undefined;
      return typeof path === 'string' && forgotten(path) ? null : selection;
    };
    for (const [place, draft] of this.placeDrafts) {
      const preparation = prunePreparation(draft.preparation);
      const reselect = preparation !== null && 'scope' in preparation && preparation.scope.kind === 'reselect';
      this.placeDrafts.set(place, { ...draft, preparation, review: draft.review || reselect });
    }
    for (const [place, scope] of this.placeScopes) this.placeScopes.set(place, pruneScope(scope));
    this.recoveryDraftsValue = this.recoveryDrafts.map(draft => ({ ...draft, preparation: prunePreparation(draft.preparation ?? null) }))
      .filter((draft, index, drafts) => drafts.findIndex(other => this.sameDraft(draft, other)) === index);
    this.draftProposalValue = prunePreparation(this.draftProposal);
    if (this.preparedHandoff !== null) {
      const selection = pruneSelection(this.preparedHandoff.selection);
      if (selection !== this.preparedHandoff.selection) {
        if (selection === null) {
          this.handoffBlocked = true;
          this.handoffNotice = 'Choose files or a passage again before sending.';
        }
        this.preparedHandoff = { ...this.preparedHandoff, selection };
      }
    }
    if (this.humanTask !== null) {
      const task = this.humanTask;
      const scope = task.saved.scope.filter(path => !forgotten(path));
      const selection = pruneSelection(task.selection);
      if (scope.length !== task.saved.scope.length || JSON.stringify(selection) !== JSON.stringify(task.selection)) {
        this.revokeHumanTask(true);
        task.saved = { ...task.saved, scope, needsReselection: true };
        task.scope = pruneScope(task.scope);
        task.scopeOwner = pruneScope(task.scopeOwner);
        task.selection = selection;
      }
    }
    const preparation = prunePreparation(this.currentPreparation());
    const cleared = this.search.reconcileForgotten(forgottenPath, scope);
    if (cleared && this.tier === 'ask') this.tier = 'search';
    else if (cleared && preparation !== null && 'scope' in preparation && preparation.scope.kind === 'reselect') {
      this.markDraftReview(preparation);
      this.reviewConfigured = true;
    }
    this.preparationRevision += 1;
    this.saveRecovery();
  }
  retireDepartedHandoff(): void {
      if (!this.host.connected() && this.preparedHandoff !== null) {
        if (this.composer.read() !== '' && (this.search.pendingScope !== null || this.handoffBlocked
          || this.preparedHandoff.shapeId !== null && this.preparedHandoff.shapeId !== SV3_ASK_SHAPE_ID)) {
          this.retiredHandoffTextValue = this.composer.read();
          this.draftReviewValue = false; this.draftProposalValue = null; this.reviewConfigured = false;
        }
        this.preparedHandoff = null;
        this.handoffNotice = ''; this.handoffBlocked = false;
        this.search.clearScope();
        this.host.requestUpdate();
      }
  }
  changeSchema(text: string): void {
          this.revokeHumanTask();
          if (!this.acknowledgePreparation({ kind: 'structured', schema: text,
            scope: this.search.pendingScope === null ? { kind: 'typed' } : { kind: 'files', paths: [...this.search.pendingScope.paths] } })) { this.host.requestUpdate(); return; }
          this.schemaDraftValue = text;
          this.configureDraft();
          this.host.requestUpdate();
  }
  clearScope(): void {
    this.revokeHumanTask(); if (!this.acknowledgePreparation(this.livePreparation(this.tier, null, [], null, false))) return; this.search.clearScope(); this.preparedHandoff = null; this.handoffNotice = ''; this.handoffBlocked = false; this.configureDraft();
  }
}
