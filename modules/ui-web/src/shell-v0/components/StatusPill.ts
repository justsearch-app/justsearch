// SPDX-License-Identifier: Apache-2.0
import { html, css, nothing, type PropertyValues } from 'lit';
import { keyed } from 'lit/directives/keyed.js';
import { JfElement } from '../primitives/JfElement.js';
import { TransientController } from '../primitives/transientController.js';
import { cssLength, shiftIntoWindow } from '../chrome/menuPlacement.js';
import { FrameStatusController } from '../controllers/frameStatusController.js';
import { requestSurfaceNavigation } from '../controllers/navigateRequest.js';
import { cancelTask } from '../substrates/tasks/index.js';
import { listPending, rejectPending } from '../substrates/pending-effects/index.js';
import { needsTypedConfirm, acceptPendingNow, acceptPendingWithConsent } from './acceptPendingEffect.js';
import { requestAuthorization } from '../operations/authorizationBroker.js';
import { modalOwnsFocus, onModalsClosed } from '../primitives/modality.js';
import { deepActiveElement, isTypingTarget, shouldIgnoreKeyEvent } from '../utils/keyboardHandler.js';
import { listStatusBarItems } from '../commands/StatusBarRegistry.js';
import { requestActivitySection } from '../state/activitySection.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
import { refreshAiInstallNow } from '../utils/aiInstallPoll.js';
import { aiInstallStatusSchema } from '../../api/generated/schema-types/ai-install-status.js';
import { getOperationClient } from '../operations/OperationClient.js';
import { emitEphemeralToast } from './advisory/ephemeralToast.js';
import type { MultiplexedStream } from '../streaming/MultiplexedStream.js';
import './StatusBadge.js';
import './SystemNotice.js';
import { installNoticeActivationBridge } from '../router/tauriBridge.js';
import { transientPlacement, toastActionAvailable, folderName, STATUS_REVIEW_EVENT, type StatusAction } from '../state/frameStatus.js';
import { noticeRowWords } from '../state/noticePolicy.js';
import type { AdvisoryStore, AdvisoryRecord } from './advisory/AdvisoryStore.js';
import type { AuthorizationHost } from './AuthorizationHost.js';
import './StatusPanel.js';
import type { StatusPanel } from './StatusPanel.js';

/** The connected pill; shell commands registered once per page reach it across shell remounts. */
let connectedPill: StatusPill | null = null;
/**
 * Shell Undo (Ctrl+Z): reverse the undo notice showing in the pill. False when none is showing, and
 * while typing or under a modal, where Undo keeps its previous meaning.
 */
export function undoStatusNotice(): boolean {
  if (isTypingTarget(deepActiveElement()) || modalOwnsFocus()) return false;
  return connectedPill?.undoNotice() ?? false;
}

/** Self-contained top-bar status. Presentation visibility only hides plugin contributions. */
export class StatusPill extends JfElement {
  static override properties = {
    store: { attribute: false }, apiBase: { attribute: 'api-base' }, pluginsVisible: { type: Boolean, attribute: false },
    multiplex: { attribute: false }, authorizationHost: { attribute: false }, open: { state: true }, narrow: { state: true },
  };
  declare store: AdvisoryStore | null;
  declare apiBase: string;
  declare pluginsVisible: boolean;
  declare multiplex: MultiplexedStream | null;
  declare authorizationHost: (() => AuthorizationHost | null) | null;
  declare open: boolean;
  /** Window narrower than 1000 px: the state reads from the icon (badge, ring or dot); the name stays on the button. */
  declare narrow: boolean;
  private narrowQuery: MediaQueryList | null = null;
  private noticeResize: ResizeObserver | null = null;
  private readonly narrowChange = (): void => { this.narrow = this.narrowQuery?.matches ?? false; };
  readonly status = new FrameStatusController(this);
  private readonly transient = new TransientController(this, {
    layer: 'transient', id: 'frame-status', managesDismiss: true, close: () => this.close(),
    // Escape inside a request's review steps back to the list; the request keeps waiting.
    escape: () => this.shadowRoot?.querySelector<StatusPanel>('jf-status-panel')?.back() ?? false,
  });
  constructor() {
    super(); this.store = null; this.apiBase = ''; this.pluginsVisible = true;
    this.multiplex = null; this.authorizationHost = null; this.open = false; this.narrow = false;
  }
  static styles = css`
    :host { display: inline-flex; min-width: 0; max-width: 100%; font-size: var(--font-size-sm); }
    .trigger { position: relative; z-index: var(--z-overlay-top); display: inline-flex; min-width: 0; max-width: 100%; padding: 0; border: 0;
      border-radius: var(--frame-pill-radius); background: transparent; font: inherit; cursor: pointer; }
    jf-status-badge { min-width: 0; max-width: 100%; }
    jf-status-badge::part(badge) { min-width: 0; max-width: 100%; box-sizing: border-box; }
    .transient-action { font: inherit; color: var(--text-tint); background: var(--surface-2); border: 0; border-radius: var(--frame-pill-radius); padding: var(--jf-space-1) var(--jf-space-2); }
    /* Measure the full words; notices that need more than two lines move below the bar. */
    .beside-notice { align-self: center; flex: 0 1 auto; min-width: 0; max-width: var(--frame-menu-width); padding-inline: var(--jf-space-2);
      color: var(--text-secondary); font-size: var(--font-size-xs); line-height: 1.2; white-space: normal; overflow-wrap: normal;
    }
    .beside-notice, .notice-below, .trigger.notice .label { white-space: normal; overflow-wrap: anywhere; }
    .trigger.notice { flex: 0 1 auto; }
    .trigger.notice .label { min-width: 0; flex: 0 1 auto; text-align: start; }
    .trigger { flex: none; }
    .transient-action { white-space: nowrap; flex: none; }
    .content { display: inline-flex; align-items: center; gap: var(--jf-space-2); min-width: 0; }
    .trigger.stale { outline: var(--frame-hairline) dashed var(--border-emphasized); }
    /* The status words stay whole. In the minimal bar (the top bar's room below 45rem) the action has its own row. */
    .label { white-space: nowrap; flex: none; }
    .notice-below { display: none; }
    @container (max-width: 44.9375rem) {
      :host { display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: center; }
      .label, .trigger.notice .label { display: none; }
      .trigger { grid-column: 1; grid-row: 1; }
      .beside-notice, .notice-below { grid-column: 2; grid-row: 1; display: block;
        min-width: 0; padding-inline: var(--jf-space-1); font-size: var(--font-size-xs); line-height: 1.2;
        color: var(--text-secondary); }
      .transient-action { grid-column: 1 / -1; grid-row: 2; justify-self: end; font-size: var(--font-size-xs); }
    }
    :host([notice-under]) .trigger.notice .label { display: none; }
    :host([notice-under]) .beside-notice, :host([notice-under]) .notice-below {
      display: block; position: fixed; top: var(--frame-status-notice-top); left: var(--jf-space-2); right: var(--jf-space-2);
      width: auto; max-width: none; box-sizing: border-box; padding: var(--jf-space-2);
      background: var(--surface-1); color: var(--text-secondary); font-size: var(--font-size-xs); line-height: 1.2;
      z-index: var(--z-overlay-top);
    }
    .symbol { color: var(--text-secondary); }
    .calm .symbol { color: var(--text-success); }
    .needs .symbol, .limited .symbol { color: var(--text-warning); }
    .problems .symbol, .unreachable .symbol { color: var(--text-danger); }
    button:focus-visible { outline: var(--frame-focus-width) solid var(--focus-ring-color); outline-offset: var(--jf-space-1); }
    .overlay { position: fixed; top: var(--frame-status-dim-top, 0); right: 0; bottom: 0; left: 0; z-index: var(--z-overlay-transient); background: var(--frame-status-dim); }
    jf-status-panel { position: fixed; top: calc(var(--frame-status-panel-top) + var(--jf-space-2)); right: var(--frame-status-panel-right, var(--jf-space-4));
      z-index: var(--z-overlay-transient); text-align: start; }
    [hidden] { display: none; }
    .visually-hidden { position: absolute; width: var(--frame-hairline); height: var(--frame-hairline); overflow: hidden; clip-path: inset(50%); }
    .count { display: inline-grid; place-items: center; min-width: var(--frame-status-ring); height: var(--frame-status-ring);
      padding-inline: var(--jf-space-1); box-sizing: border-box; border-radius: var(--frame-pill-radius);
      background: var(--accent-warning); color: var(--accent-on-warning); font-size: var(--font-size-xs);
      font-weight: var(--jf-weight-medium); font-variant-numeric: tabular-nums; }
    .count.problems { background: var(--accent-danger); color: var(--accent-on-danger); }
    .ring { width: var(--frame-status-ring); height: var(--frame-status-ring); flex-shrink: 0; }
    .ring circle { fill: none; stroke-width: 2.5; }
    .ring .track { stroke: var(--border-emphasized); }
    .ring .arc { stroke: var(--accent); }
    kbd { font: inherit; font-size: var(--font-size-xs); color: var(--text-secondary); margin-inline-start: var(--jf-space-2); }
    @media (forced-colors: active) {
      .trigger { color: ButtonText; background: ButtonFace; border-color: ButtonText; }
      .symbol { color: ButtonText !important; } button:focus-visible { outline-color: Highlight; }
      .count, .count.problems { background: ButtonText; color: ButtonFace; forced-color-adjust: none; }
      .ring .track { stroke: GrayText; } .ring .arc { stroke: ButtonText; }
      .overlay { background: transparent; }
      .notice-below { border-color: CanvasText; }
    }
  `;
  override connectedCallback(): void {
    super.connectedCallback();
    connectedPill = this;
    document.addEventListener('keydown', this.keydown);
    document.addEventListener(STATUS_REVIEW_EVENT, this.reviewRequest);
    this.stopNoticeActivation = installNoticeActivationBridge(this.noticeActivated);
    // A task later, so a dialog that follows the closed one in the same turn is seen as still blocking.
    this.stopModalWatch = onModalsClosed(() => { setTimeout(this.openClickedNotice); });
    this.status.setMultiplex(this.multiplex);
    window.addEventListener('blur', this.windowBlur);
    document.addEventListener('visibilitychange', this.visibility);
    this.narrowQuery = typeof window.matchMedia === 'function' ? window.matchMedia('(max-width: 999px)') : null;
    this.narrowQuery?.addEventListener('change', this.narrowChange);
    this.narrowChange();
    if (typeof ResizeObserver !== 'undefined') this.noticeResize = new ResizeObserver(this.relayout);
  }
  override disconnectedCallback(): void {
    super.disconnectedCallback();
    if (connectedPill === this) connectedPill = null;
    document.removeEventListener('keydown', this.keydown);
    document.removeEventListener(STATUS_REVIEW_EVENT, this.reviewRequest);
    this.stopNoticeActivation?.(); this.stopNoticeActivation = undefined;
    this.stopModalWatch?.(); this.stopModalWatch = undefined;
    this.clickedNotice = null;
    window.removeEventListener('blur', this.windowBlur);
    document.removeEventListener('visibilitychange', this.visibility);
    this.narrowQuery?.removeEventListener('change', this.narrowChange);
    this.narrowQuery = null;
    this.noticeResize?.disconnect();
    this.noticeResize = null;
    this.open = false;
  }
  protected override willUpdate(changed: PropertyValues): void {
    if (changed.has('store')) this.status.setStore(this.store);
    if (changed.has('multiplex')) this.status.setMultiplex(this.multiplex);
    if (changed.has('authorizationHost')) this.status.refresh();
  }
  protected override updated(changed: PropertyValues): void {
    this.noticeResize?.disconnect();
    this.noticeResize?.observe(this);
    if (this.parentElement) this.noticeResize?.observe(this.parentElement);
    for (const element of this.shadowRoot!.querySelectorAll('.beside-notice, .notice-below, .trigger, .label, .transient-action, jf-status-panel')) {
      this.noticeResize?.observe(element);
    }
    this.placeNotice();
    if (changed.has('open')) {
      if (this.open) {
        this.transient.open();
        this.placePanel();
        const panel = this.shadowRoot!.querySelector<StatusPanel>('jf-status-panel');
        void panel?.updateComplete.then(() => {
          this.placePanel();
          // The panel's answers just appeared where the pointer may already be: they arm from now (C12).
          panel.placeControls();
          panel.shadowRoot?.querySelector<HTMLElement>('[role="dialog"]')?.focus();
        });
      } else this.transient.close();
    }
  }
  /** The window, the text size or the bar changed: the inline notice, and an open panel, are placed again. */
  private readonly relayout = (): void => {
    this.placeNotice();
    if (this.open) this.placePanel();
  };
  /**
   * R4 — the panel hangs under the pill, kept a margin away from both window edges (the top bar's one
   * placement rule); the arrow points at the pill's centre and stays on the straight part of the
   * panel's top edge. Placed on opening and again on every relayout while open (FA1a).
   */
  private placePanel(): void {
    const rect = this.shadowRoot?.querySelector('.trigger')?.getBoundingClientRect();
    if (!rect) return;
    this.style.setProperty('--frame-status-panel-top', `${rect.bottom}px`);
    // R2 — the dim starts below the bar the pill sits in: the pill and its neighbours keep
    // their own contrast while the panel is open.
    this.style.setProperty('--frame-status-dim-top', `${Math.max(rect.bottom, this.parentElement?.getBoundingClientRect().bottom ?? 0)}px`);
    const panel = this.shadowRoot!.querySelector<StatusPanel>('jf-status-panel');
    if (!panel) return;
    const margin = cssLength(this, '--jf-space-2');
    const width = panel.getBoundingClientRect().width;
    const right = window.innerWidth - rect.right - shiftIntoWindow({ left: rect.right - width, right: rect.right }, margin);
    this.style.setProperty('--frame-status-panel-right', `${right}px`);
    const placed = panel.getBoundingClientRect();
    const inset = cssLength(this, '--frame-panel-radius') + margin / 2;
    const fromRight = Math.min(Math.max(inset, placed.right - (rect.left + rect.width / 2)), Math.max(inset, placed.width - inset));
    this.style.setProperty('--frame-status-arrow-right', `calc(${fromRight}px - var(--jf-space-2) / 2)`);
  }
  /** Recheck the inline slot on text, font or frame-size changes, including a return from below. */
  private readonly placeNotice = (): void => {
    this.removeAttribute('notice-under');
    const notice = this.shadowRoot?.querySelector<HTMLElement>('.beside-notice')
      ?? this.shadowRoot?.querySelector<HTMLElement>(window.innerWidth < 720 ? '.notice-below' : '.trigger.notice .label');
    if (!notice || !this.parentElement) return;
    const box = notice.getBoundingClientRect();
    const bar = this.parentElement.getBoundingClientRect();
    const style = getComputedStyle(notice);
    const lineHeight = parseFloat(style.lineHeight) || parseFloat(style.fontSize) * 1.2;
    if (box.width > 0 && box.height <= lineHeight * 2 + 1
      && notice.scrollWidth <= notice.clientWidth + 1 && box.top >= bar.top && box.bottom <= bar.bottom) return;
    this.style.setProperty('--frame-status-notice-top', `${bar.bottom}px`);
    this.setAttribute('notice-under', '');
  };
  toggle(): void { if (this.open) this.close(); else if (!modalOwnsFocus()) this.open = true; }
  close(restoreFocus = true): void {
    if (!this.open) return;
    this.open = false;
    const panel = this.shadowRoot?.querySelector<StatusPanel>('jf-status-panel');
    if (panel) { panel.reviewing = null; panel.gone = false; panel.noticeGone = false; }
    if (restoreFocus) this.shadowRoot?.querySelector<HTMLButtonElement>('.trigger')?.focus({ preventScroll: true });
  }
  /**
   * Another window or the Settings window takes over: close, and return focus to the pill only
   * when it was inside the panel (so the next window's own focus target is not stolen).
   */
  dismissForWindowChange(): void { this.close(!!this.shadowRoot?.activeElement); }
  private readonly windowBlur = (): void => this.close(false);
  private readonly visibility = (): void => { if (document.hidden) this.close(false); };
  private readonly keydown = (event: KeyboardEvent): void => {
    if (event.defaultPrevented || shouldIgnoreKeyEvent(event) || modalOwnsFocus()) return;
    if ((event.ctrlKey || event.metaKey) && event.shiftKey && event.key.toLowerCase() === 's'
      && !isTypingTarget(deepActiveElement())) { event.preventDefault(); this.toggle(); }
  };
  /** A waiting run's Review: nothing happens under a dialog, which the person can see is open. */
  private readonly reviewRequest = (event: Event): void => {
    if (!modalOwnsFocus()) this.openRow((event as CustomEvent<string>).detail);
  };
  /**
   * DE5 — the shell has brought the app forward on a clicked desktop notification. An agent request's
   * open dialog gives way to the one clicked; another dialog stays, and the target waits for it to
   * close. The latest click is the one that counts.
   */
  private readonly noticeActivated = (id: string): void => {
    this.authorizationHost?.()?.yieldDialogTo(id);
    this.clickedNotice = id;
    this.openClickedNotice();
  };
  private readonly openClickedNotice = (): void => {
    const id = this.clickedNotice;
    if (id === null || modalOwnsFocus()) return;
    this.clickedNotice = null;
    // It may have waited a while: what is true now decides.
    this.status.refresh();
    this.openRow(id, true);
  };
  /**
   * Open the panel on one row: a Needs-you row's review, a typed one's dialog, or any other row in its
   * place. A clicked notification whose row has left the panel opens nothing and says so.
   */
  private openRow(id: string, fromNotice = false): void {
    const rows = Object.values(this.status.model.sections).flat();
    const row = rows.find(fact => fact.id === id) ?? rows.find(fact => fact.title === noticeRowWords(id));
    const typed = row?.review?.typed ? row.actions?.find(action => action.kind === 'authorization') : undefined;
    if (typed) { void this.act(typed); return; }
    this.open = true;
    void this.updateComplete.then(() => {
      const panel = this.shadowRoot?.querySelector<StatusPanel>('jf-status-panel');
      if (!panel || !this.open) return;
      panel.noticeGone = fromNotice && !row;
      if (row?.review) panel.openReview(row);
      else if (row) panel.revealRow(row.id);
    });
  }
  private stopNoticeActivation: (() => void) | undefined;
  private stopModalWatch: (() => void) | undefined;
  /** The target of the last clicked notification, until nothing blocks it. */
  private clickedNotice: string | null = null;
  private readonly executing = new Set<string>();
  private async act(action: StatusAction): Promise<void> {
    if (action.kind === 'background-runs') { this.close(); requestActivitySection('background-runs'); requestSurfaceNavigation('core.activity-surface'); return; }
    if (action.kind === 'navigate') { this.close(); requestSurfaceNavigation(action.target); return; }
    if (action.kind === 'install-toggle') {
      if (this.executing.has('install')) return;
      this.executing.add('install');
      try {
        const response = await authorizedFetch(`${this.apiBase}/api/ai/install/${action.target}`, { method: 'POST' });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        aiInstallStatusSchema.parse(await response.json());
        void refreshAiInstallNow();
        emitEphemeralToast({ message: `Download ${action.target === 'pause' ? 'pause' : 'resume'} requested. Checking status.` });
      } catch { emitEphemeralToast({ message: `Could not ${action.target} the download.`, severity: 'error' }); }
      finally { this.executing.delete('install'); }
      return;
    }
    if (action.kind === 'add-folder-again') { await this.addFolderAgain(action); return; }
    if (action.kind === 'plugin-copy') { await this.status.copyPluginError(action.target); return; }
    if (action.kind === 'dismiss-plugin') { this.status.dismissPluginError(action.target); return; }
    if (action.kind === 'cancel-task') { cancelTask(action.target); return; }
    if (action.kind === 'authorization' || action.kind === 'consent') {
      this.close(); this.authorizationHost?.()?.reviewStatusRequest(action.target); return;
    }
    // DE3 — the panel answers an agent request through the host's one decision path (C12).
    if (action.kind === 'authorization-allow' || action.kind === 'authorization-allow-always' || action.kind === 'authorization-deny') {
      await this.authorizationHost?.()?.answerStatusRequest(action.target, action.kind !== 'authorization-deny',
        action.kind === 'authorization-allow-always');
      return;
    }
    if (action.kind === 'authorization-review') { await this.authorizationHost?.()?.openStatusReview(action.target); return; }
    if (action.kind === 'review') return;
    if (action.kind === 'reject-proposal') { rejectPending(Number(action.target)); return; }
    if (action.kind === 'proposal') {
      const p = listPending().find(row => String(row.id) === action.target);
      if (!p) return;
      // Use the protected host for typed ceremonies. No inline copy of the security UI.
      if (needsTypedConfirm(p.effect, p.originator) && p.effect.kind === 'invoke-operation') {
        this.close();
        const id = `proposal:${p.id}`;
        const decision = requestAuthorization({ pendingId: id, operationId: p.effect.operationId,
          gateBehavior: 'TYPED_CONFIRM', allowAlwaysSupported: false, ...(p.rationale ? { reason: p.rationale } : {}) });
        this.authorizationHost?.()?.reviewStatusRequest(id);
        const answer = await decision;
        if (answer.approved && listPending().some(row => row.id === p.id)) acceptPendingWithConsent(p.id);
      } else acceptPendingNow(p.id);
      return;
    }
    if (this.executing.has(action.target)) return;
    const record = this.status.snapshot.advisories.find(row => row.key === action.target);
    if (!record) return;
    this.executing.add(action.target);
    try {
      if (record.toast?.onAction) record.toast.onAction();
      else if (record.event.primaryAction) {
        const primary = record.event.primaryAction;
        const args = primary.defaultArgsJson ? JSON.parse(primary.defaultArgsJson) : {};
        const client = getOperationClient(this.apiBase);
        if (record.event.primaryActionKind === 'undo' && typeof args.executionId === 'string') {
          await client.undoWithConsent(primary.target, args.executionId, { transport: 'BUTTON', requestConsent: requestAuthorization });
        } else await client.invokeWithConsent(primary.target, { args, transport: 'BUTTON' }, { requestConsent: requestAuthorization });
        emitEphemeralToast({ message: `${action.label} completed.`, severity: 'success' });
      }
      this.store?.acknowledge(record.key);
    } catch (error) { emitEphemeralToast({ message: `Action failed: ${error instanceof Error ? error.message : String(error)}`, severity: 'error' }); }
    finally { this.executing.delete(action.target); }
  }
  /**
   * L1 — add a removed folder back from its removal record (any window, after a reload). Once per
   * path at a time; the outcome is said in the pill and Earlier, and a refusal (the folder is already
   * back, or gone from disk) is reported rather than dropped.
   */
  private async addFolderAgain(action: StatusAction): Promise<void> {
    const key = `add-folder:${action.target}`;
    if (this.executing.has(key)) return;
    this.executing.add(key);
    const name = folderName(action.target);
    try {
      const result = await getOperationClient(this.apiBase).invokeWithConsent('core.add-watched-root',
        { args: { path: action.target, ...(action.collection ? { collection: action.collection } : {}) }, transport: 'BUTTON' },
        { requestConsent: requestAuthorization });
      if (!result.success) throw new Error(result.message || 'Add refused');
      emitEphemeralToast({ message: `Added ${name} again. Reading its files.`, severity: 'success' });
    } catch (error) {
      emitEphemeralToast({ message: `Could not add ${name} again. ${error instanceof Error ? error.message : String(error)}`, severity: 'error' });
    } finally { this.executing.delete(key); }
  }
  /** Same label the panel's Earlier row uses for this record: an unlabelled action is not claimed as an undo. */
  private transientActionLabel(record: AdvisoryRecord): string { return record.toast?.actionLabel ?? 'Review'; }
  /**
   * The shell's Undo command (Ctrl+Z) reverses the undo notice shown in the pill, when there is one.
   * Returns false when none is showing so the command falls back to the effect journal.
   */
  undoNotice(): boolean {
    const record = this.status.snapshot.advisories.find(row => row.key === this.status.transientKey);
    if (!record?.toast?.reversal || !toastActionAvailable(record)) return false;
    void this.act({ kind: 'advisory', target: record.key, label: this.transientActionLabel(record) });
    return true;
  }
  override render() {
    const model = this.status.model;
    const pill = model.pill;
    const plugins = this.pluginsVisible ? listStatusBarItems().filter(item => item.source === 'plugin') : [];
    const notice = this.status.snapshot.advisories.find(row => row.key === this.status.transientKey);
    // R1 — a more urgent state keeps the pill; the notice shows beside it, never in its place.
    const placement = notice ? transientPlacement(pill, notice.event.classId) : 'hidden';
    const transient = placement === 'pill' ? notice : undefined;
    const beside = placement === 'beside' ? notice : undefined;
    const label = transient?.toast?.message ?? `${pill.label}${pill.percent != null ? ` · ${pill.percent}%` : ''}`;
    const symbols = { calm: '●', working: '◌', needs: '!', problems: '!', unreachable: '×', limited: '!', finished: '✓', unknown: '?' };
    // ref-09: the count sits in a badge and the words follow it; a known fraction draws a ring.
    // Needs you and problems show their count (ref-22: also when the pill is its icon alone).
    const icon = !transient && (pill.kind === 'needs' || pill.kind === 'problems') && pill.count != null
      ? html`<span class="count ${pill.kind}" aria-hidden="true">${pill.count}</span>`
      : !transient && pill.kind === 'working' && pill.percent != null
        ? html`<svg class="ring" viewBox="0 0 20 20" aria-hidden="true" data-percent=${pill.percent}><circle class="track" cx="10" cy="10" r="8"></circle>
          <circle class="arc" cx="10" cy="10" r="8" pathLength="100" stroke-dasharray="${pill.percent} 100" transform="rotate(-90 10 10)"></circle></svg>`
        : html`<span class="symbol" aria-hidden="true">${symbols[pill.kind]}</span>`;
    const text = transient?.toast?.message ?? (pill.kind === 'needs' && pill.count != null ? (pill.count === 1 ? 'Needs you' : 'Need you')
      : pill.kind === 'problems' && pill.count != null ? 'Problems right now' : pill.label);
    const shown = transient ?? beside;
    // DE6 — the notice's action shows while it can still run; a real reversal carries the Ctrl+Z hint.
    const action = shown && toastActionAvailable(shown) ? shown : undefined;
    const undo = action?.toast?.reversal === true;
    return html`<button type="button" class="trigger ${transient ? 'notice' : pill.kind} ${pill.stale ? 'stale' : ''}"
      aria-label=${label} aria-expanded=${this.open} aria-haspopup="dialog" title=${transient?.toast?.message ?? "What's going on · Ctrl+Shift+S"}
      @click=${() => this.toggle()} @mouseenter=${() => this.status.pauseTransient(true)} @mouseleave=${() => this.status.pauseTransient(false)}
      @focus=${() => this.status.pauseTransient(true)} @blur=${() => this.status.pauseTransient(false)}>
      <jf-status-badge .tone=${pill.kind === 'needs' || pill.kind === 'limited' ? 'warning' : pill.kind === 'unreachable' || pill.kind === 'problems' ? 'error' : 'neutral'}><span class="content">${icon}${this.narrow && !transient ? nothing : html`<span class="label">${text}</span>`}
      ${pill.kind === 'calm' && plugins.length ? html`<span aria-label="Plugin status items">${plugins.length}</span>` : nothing}</span></jf-status-badge></button>
      ${beside?.toast ? html`<span class="beside-notice" role="note" aria-label=${beside.toast.message} data-testid="status-notice" title=${beside.toast.message} @mouseenter=${() => this.status.pauseTransient(true)}
        @mouseleave=${() => this.status.pauseTransient(false)}>${beside.toast.message}</span>` : nothing}
      ${transient?.toast ? html`<span class="notice-below" role="note" title=${transient.toast.message} aria-label=${transient.toast.message}>${transient.toast.message}</span>` : nothing}
      ${action ? html`<button class="transient-action" type="button" @mouseenter=${() => this.status.pauseTransient(true)} @mouseleave=${() => this.status.pauseTransient(false)} @focus=${() => this.status.pauseTransient(true)} @blur=${() => this.status.pauseTransient(false)} @click=${() => this.act({ kind: 'advisory', target: action.key, label: this.transientActionLabel(action) })}
        aria-keyshortcuts=${undo ? 'Control+Z' : nothing}>${this.transientActionLabel(action)}${undo ? html`<kbd aria-hidden="true">Ctrl+Z</kbd>` : nothing}</button>` : nothing}
      <div class="overlay" ?hidden=${!this.open} @click=${() => this.close()}></div>
      <jf-status-panel ?hidden=${!this.open} .model=${model} .plugins=${plugins} api-base=${this.apiBase}
        @status-close=${() => this.close()}
        @status-action=${(e: CustomEvent<StatusAction>) => void this.act(e.detail)}
        @status-read=${(e: CustomEvent<string>) => {
          const record = this.status.snapshot.advisories.find(r => `advisory:${r.key}` === e.detail);
          if (record && !record.acknowledged && record.sourceRenderHint !== 'EPHEMERAL') this.store?.acknowledge(record.key);
        }}
        @status-read-all=${() => this.store?.acknowledgeAll()}></jf-status-panel>
      <jf-system-notice class="visually-hidden" live="status" data-testid="indexing-announcer">${this.status.indexingAnnouncement}</jf-system-notice>
      ${this.status.verdictAnnouncement ? html`<jf-system-notice class="visually-hidden" live=${this.status.verdictAnnouncement.politeness}
        data-testid="verdict-announcer">${this.status.verdictAnnouncement.text}</jf-system-notice>` : nothing}
      ${this.status.aiEngineAnnouncement ? html`<jf-system-notice class="visually-hidden" live=${this.status.aiEngineAnnouncement.politeness}
        data-testid="ai-engine-announcer">${this.status.aiEngineAnnouncement.text}</jf-system-notice>` : nothing}
      ${keyed(this.status.politeKey, html`<span class="visually-hidden" role="status" aria-live="polite">${this.status.polite}</span>`)}
      ${keyed(this.status.assertiveKey, html`<span class="visually-hidden" role="alert" aria-live="assertive">${this.status.assertive}</span>`)}`;
  }
}
if (!customElements.get('jf-status-pill')) customElements.define('jf-status-pill', StatusPill);
