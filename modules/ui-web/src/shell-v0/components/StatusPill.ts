// SPDX-License-Identifier: Apache-2.0
import { html, css, nothing, type PropertyValues } from 'lit';
import { keyed } from 'lit/directives/keyed.js';
import { JfElement } from '../primitives/JfElement.js';
import { TransientController } from '../primitives/transientController.js';
import { FrameStatusController } from '../controllers/frameStatusController.js';
import { requestSurfaceNavigation } from '../controllers/navigateRequest.js';
import { cancelTask } from '../substrates/tasks/index.js';
import { listPending, rejectPending } from '../substrates/pending-effects/index.js';
import { needsTypedConfirm, acceptPendingNow, acceptPendingWithConsent } from './acceptPendingEffect.js';
import { requestAuthorization } from '../operations/authorizationBroker.js';
import { modalOwnsFocus } from '../primitives/modality.js';
import { deepActiveElement, isTypingTarget, shouldIgnoreKeyEvent } from '../utils/keyboardHandler.js';
import { listStatusBarItems } from '../commands/StatusBarRegistry.js';
import { openRetrospectiveAt } from '../state/retrospectiveDrawer.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
import { refreshAiInstallNow } from '../utils/aiInstallPoll.js';
import { aiInstallStatusSchema } from '../../api/generated/schema-types/ai-install-status.js';
import { getOperationClient } from '../operations/OperationClient.js';
import { emitEphemeralToast } from './advisory/ephemeralToast.js';
import type { MultiplexedStream } from '../streaming/MultiplexedStream.js';
import './StatusBadge.js';
import './SystemNotice.js';
import { transientPlacement, STATUS_REVIEW_EVENT, type StatusAction } from '../state/frameStatus.js';
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
    .beside-notice { align-self: center; min-width: 0; max-width: var(--frame-menu-width); padding-inline: var(--jf-space-2); color: var(--text-secondary);
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .transient-action { white-space: nowrap; flex: none; }
    .content { display: inline-flex; align-items: center; gap: var(--jf-space-2); min-width: 0; }
    .trigger.stale { outline: var(--frame-hairline) dashed var(--border-emphasized); }
    /* L3 — the pill's words never truncate: the top bar makes room for them (it is a grid whose
       brand side gives way), and below 720 px the pill is its icon alone. A notice cannot fit
       beside it there, so it moves under the top bar, where it wraps instead of being cut. */
    .label { white-space: nowrap; flex: none; }
    .notice-below { display: none; }
    @media (max-width: 719px) {
      .label, .beside-notice { display: none; }
      .notice-below { display: block; position: fixed; top: var(--frame-topbar-height); inset-inline-end: var(--jf-space-2);
        max-width: calc(100vw - 2 * var(--jf-space-2)); box-sizing: border-box; z-index: var(--z-overlay-top);
        padding: var(--jf-space-1) var(--jf-space-2); border: var(--frame-hairline) solid var(--border-subtle);
        border-radius: var(--frame-control-radius); background: var(--surface-1); color: var(--text-primary);
        white-space: normal; overflow-wrap: anywhere; }
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
    .ring { width: var(--frame-status-ring); height: var(--frame-status-ring); flex-shrink: 0; }
    .ring circle { fill: none; stroke-width: 2.5; }
    .ring .track { stroke: var(--border-emphasized); }
    .ring .arc { stroke: var(--accent); }
    kbd { font: inherit; font-size: var(--font-size-xs); color: var(--text-secondary); margin-inline-start: var(--jf-space-2); }
    @media (forced-colors: active) {
      .trigger { color: ButtonText; background: ButtonFace; border-color: ButtonText; }
      .symbol { color: ButtonText !important; } button:focus-visible { outline-color: Highlight; }
      .count { background: ButtonText; color: ButtonFace; forced-color-adjust: none; }
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
    this.status.setMultiplex(this.multiplex);
    window.addEventListener('blur', this.windowBlur);
    document.addEventListener('visibilitychange', this.visibility);
    this.narrowQuery = typeof window.matchMedia === 'function' ? window.matchMedia('(max-width: 999px)') : null;
    this.narrowQuery?.addEventListener('change', this.narrowChange);
    this.narrowChange();
  }
  override disconnectedCallback(): void {
    super.disconnectedCallback();
    if (connectedPill === this) connectedPill = null;
    document.removeEventListener('keydown', this.keydown);
    document.removeEventListener(STATUS_REVIEW_EVENT, this.reviewRequest);
    window.removeEventListener('blur', this.windowBlur);
    document.removeEventListener('visibilitychange', this.visibility);
    this.narrowQuery?.removeEventListener('change', this.narrowChange);
    this.narrowQuery = null;
    this.open = false;
  }
  protected override willUpdate(changed: PropertyValues): void {
    if (changed.has('store')) this.status.setStore(this.store);
    if (changed.has('multiplex')) this.status.setMultiplex(this.multiplex);
    if (changed.has('authorizationHost')) this.status.refresh();
  }
  protected override updated(changed: PropertyValues): void {
    if (changed.has('open')) {
      if (this.open) {
        this.transient.open();
        const rect = this.shadowRoot!.querySelector('.trigger')!.getBoundingClientRect();
        this.style.setProperty('--frame-status-panel-top', `${rect.bottom}px`);
        // R2 — the dim starts below the bar the pill sits in: the pill and its neighbours keep
        // their own contrast while the panel is open.
        this.style.setProperty('--frame-status-dim-top', `${Math.max(rect.bottom, this.parentElement?.getBoundingClientRect().bottom ?? 0)}px`);
        const panel = this.shadowRoot!.querySelector<StatusPanel>('jf-status-panel');
        void panel?.updateComplete.then(() => {
          // R4 — under the pill, kept a margin away from both window edges; the arrow points at the
          // pill's centre and stays on the straight part of the panel's top edge.
          const margin = this.cssPx('--jf-space-2');
          const width = panel.getBoundingClientRect().width;
          const right = Math.max(margin, Math.min(window.innerWidth - rect.right, window.innerWidth - margin - width));
          this.style.setProperty('--frame-status-panel-right', `${right}px`);
          const placed = panel.getBoundingClientRect();
          const inset = this.cssPx('--frame-panel-radius') + margin / 2;
          const fromRight = Math.min(Math.max(inset, placed.right - (rect.left + rect.width / 2)), Math.max(inset, placed.width - inset));
          this.style.setProperty('--frame-status-arrow-right', `calc(${fromRight}px - var(--jf-space-2) / 2)`);
          // The panel's answers just appeared where the pointer may already be: they arm from now (C12).
          panel.placeControls();
          panel.shadowRoot?.querySelector<HTMLElement>('[role="dialog"]')?.focus();
        });
      } else this.transient.close();
    }
  }
  /** A length token in px (rem resolves against the root font size); 0 when the token is not defined. */
  private cssPx(name: string): number {
    const value = getComputedStyle(this).getPropertyValue(name).trim();
    const n = parseFloat(value);
    if (!Number.isFinite(n)) return 0;
    return value.endsWith('rem') ? n * (parseFloat(getComputedStyle(document.documentElement).fontSize) || 16) : n;
  }
  toggle(): void { if (this.open) this.close(); else if (!modalOwnsFocus()) this.open = true; }
  close(restoreFocus = true): void {
    if (!this.open) return;
    this.open = false;
    const panel = this.shadowRoot?.querySelector<StatusPanel>('jf-status-panel');
    if (panel) { panel.reviewing = null; panel.gone = false; }
    if (restoreFocus) this.shadowRoot?.querySelector<HTMLButtonElement>('.trigger')?.focus({ preventScroll: true });
  }
  /**
   * Another window or the Settings window takes over: close, and return focus to the pill only
   * when it was inside the panel (so the next window's own focus target is not stolen).
   */
  dismissForWindowChange(): void { this.close(document.activeElement === this); }
  private readonly windowBlur = (): void => this.close(false);
  private readonly visibility = (): void => { if (document.hidden) this.close(false); };
  private readonly keydown = (event: KeyboardEvent): void => {
    if (event.defaultPrevented || shouldIgnoreKeyEvent(event) || modalOwnsFocus()) return;
    if ((event.ctrlKey || event.metaKey) && event.shiftKey && event.key.toLowerCase() === 's'
      && !isTypingTarget(deepActiveElement())) { event.preventDefault(); this.toggle(); }
  };
  /** Open the panel on one Needs-you row's review (a waiting run's Review); a typed one opens its dialog. */
  private readonly reviewRequest = (event: Event): void => {
    const id = (event as CustomEvent<string>).detail;
    const row = this.status.model.sections.needs.find(fact => fact.id === id);
    if (modalOwnsFocus()) return;
    const typed = row?.review?.typed ? row.actions?.find(action => action.kind === 'authorization') : undefined;
    if (typed) { void this.act(typed); return; }
    this.open = true;
    void this.updateComplete.then(() => {
      const panel = this.shadowRoot?.querySelector<StatusPanel>('jf-status-panel');
      if (row?.review && this.open) panel?.openReview(row);
    });
  };
  private readonly executing = new Set<string>();
  private async act(action: StatusAction): Promise<void> {
    if (action.kind === 'background-runs') { this.close(); openRetrospectiveAt('inbox'); return; }
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
          gateBehavior: 'TYPED_CONFIRM', allowAlwaysSupported: false, purpose: p.rationale ?? '' });
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
  /** Same label the panel's Earlier row uses for this record: an unlabelled action is not claimed as an undo. */
  private transientActionLabel(record: AdvisoryRecord): string { return record.toast?.actionLabel ?? 'Review'; }
  /**
   * The shell's Undo command (Ctrl+Z) reverses the undo notice shown in the pill, when there is one.
   * Returns false when none is showing so the command falls back to the effect journal.
   */
  undoNotice(): boolean {
    const record = this.status.snapshot.advisories.find(row => row.key === this.status.transientKey);
    if (!record?.toast?.onAction || this.transientActionLabel(record) !== 'Undo') return false;
    void this.act({ kind: 'advisory', target: record.key, label: 'Undo' });
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
    const icon = !transient && pill.kind === 'needs' && pill.count != null
      ? html`<span class="count" aria-hidden="true">${pill.count}</span>`
      : !transient && pill.kind === 'working' && pill.percent != null
        ? html`<svg class="ring" viewBox="0 0 20 20" aria-hidden="true" data-percent=${pill.percent}><circle class="track" cx="10" cy="10" r="8"></circle>
          <circle class="arc" cx="10" cy="10" r="8" pathLength="100" stroke-dasharray="${pill.percent} 100" transform="rotate(-90 10 10)"></circle></svg>`
        : html`<span class="symbol" aria-hidden="true">${symbols[pill.kind]}</span>`;
    const text = transient?.toast?.message ?? (pill.kind === 'needs' && pill.count != null ? (pill.count === 1 ? 'Needs you' : 'Need you') : pill.label);
    const shown = transient ?? beside;
    const undo = shown?.toast?.onAction && this.transientActionLabel(shown) === 'Undo';
    return html`<button type="button" class="trigger ${transient ? 'notice' : pill.kind} ${pill.stale ? 'stale' : ''}"
      aria-label=${label} aria-expanded=${this.open} aria-haspopup="dialog" title="What's going on · Ctrl+Shift+S"
      @click=${() => this.toggle()} @mouseenter=${() => this.status.pauseTransient(true)} @mouseleave=${() => this.status.pauseTransient(false)}
      @focus=${() => this.status.pauseTransient(true)} @blur=${() => this.status.pauseTransient(false)}>
      <jf-status-badge .tone=${pill.kind === 'needs' || pill.kind === 'limited' ? 'warning' : pill.kind === 'unreachable' || pill.kind === 'problems' ? 'error' : 'neutral'}><span class="content">${icon}${this.narrow && !transient ? nothing : html`<span class="label">${text}</span>`}
      ${pill.kind === 'calm' && plugins.length ? html`<span aria-label="Plugin status items">${plugins.length}</span>` : nothing}</span></jf-status-badge></button>
      ${beside?.toast ? html`<span class="beside-notice" data-testid="status-notice" title=${beside.toast.message} @mouseenter=${() => this.status.pauseTransient(true)}
        @mouseleave=${() => this.status.pauseTransient(false)}>${beside.toast.message}</span>` : nothing}
      ${shown?.toast ? html`<span class="notice-below">${shown.toast.message}</span>` : nothing}
      ${shown?.toast?.onAction ? html`<button class="transient-action" type="button" @mouseenter=${() => this.status.pauseTransient(true)} @mouseleave=${() => this.status.pauseTransient(false)} @focus=${() => this.status.pauseTransient(true)} @blur=${() => this.status.pauseTransient(false)} @click=${() => this.act({ kind: 'advisory', target: shown.key, label: this.transientActionLabel(shown) })}
        aria-keyshortcuts=${undo ? 'Control+Z' : nothing}>${this.transientActionLabel(shown)}${undo ? html`<kbd aria-hidden="true">Ctrl+Z</kbd>` : nothing}</button>` : nothing}
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
