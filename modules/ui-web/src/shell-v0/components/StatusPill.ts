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
import type { StatusAction } from '../state/frameStatus.js';
import type { AdvisoryStore } from './advisory/AdvisoryStore.js';
import type { AuthorizationHost } from './AuthorizationHost.js';
import './StatusPanel.js';
import type { StatusPanel } from './StatusPanel.js';

/** Self-contained title-bar status. Presentation visibility only hides plugin contributions. */
export class StatusPill extends JfElement {
  static override properties = {
    store: { attribute: false }, apiBase: { attribute: 'api-base' }, pluginsVisible: { type: Boolean, attribute: false },
    multiplex: { attribute: false }, authorizationHost: { attribute: false }, open: { state: true },
  };
  declare store: AdvisoryStore | null;
  declare apiBase: string;
  declare pluginsVisible: boolean;
  declare multiplex: MultiplexedStream | null;
  declare authorizationHost: (() => AuthorizationHost | null) | null;
  declare open: boolean;
  readonly status = new FrameStatusController(this);
  private readonly transient = new TransientController(this, {
    layer: 'transient', id: 'frame-status', managesDismiss: true, close: () => this.close(),
  });
  constructor() {
    super(); this.store = null; this.apiBase = ''; this.pluginsVisible = true;
    this.multiplex = null; this.authorizationHost = null; this.open = false;
  }
  static styles = css`
    :host { display: inline-flex; min-width: 0; max-width: 100%; font-size: var(--font-size-sm); }
    .trigger { position: relative; z-index: var(--z-overlay-top); display: inline-flex; min-width: 0; max-width: 100%; padding: 0; border: 0;
      border-radius: var(--frame-pill-radius); background: transparent; font: inherit; cursor: pointer; }
    jf-status-badge { min-width: 0; max-width: 100%; }
    jf-status-badge::part(badge) { min-width: 0; max-width: 100%; box-sizing: border-box; }
    .transient-action { font: inherit; color: var(--text-tint); background: var(--surface-2); border: 0; border-radius: var(--frame-pill-radius); padding: var(--jf-space-1) var(--jf-space-2); }
    .content { display: inline-flex; align-items: center; gap: var(--jf-space-2); min-width: 0; }
    .trigger.stale { outline: var(--frame-hairline) dashed var(--border-emphasized); }
    .label { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .symbol { color: var(--text-secondary); }
    .calm .symbol { color: var(--text-success); }
    .needs .symbol, .limited .symbol { color: var(--text-warning); }
    .problems .symbol, .unreachable .symbol { color: var(--text-error); }
    button:focus-visible { outline: var(--frame-focus-width) solid var(--focus-ring-color); outline-offset: var(--jf-space-1); }
    .overlay { position: fixed; inset: 0; z-index: var(--z-overlay-transient); background: var(--frame-status-dim); }
    jf-status-panel { position: fixed; top: calc(var(--frame-status-panel-top) + var(--jf-space-2)); right: max(var(--jf-space-4), var(--frame-status-panel-right, var(--jf-space-4)));
      z-index: var(--z-overlay-transient); text-align: start; }
    [hidden] { display: none; }
    .visually-hidden { position: absolute; width: var(--frame-hairline); height: var(--frame-hairline); overflow: hidden; clip-path: inset(50%); }
    @media (max-width: 999px) { .calm .label { display: none; } }
    @media (forced-colors: active) {
      .trigger { color: ButtonText; background: ButtonFace; border-color: ButtonText; }
      .symbol { color: ButtonText !important; } button:focus-visible { outline-color: Highlight; }
      .overlay { background: transparent; }
    }
  `;
  override connectedCallback(): void {
    super.connectedCallback();
    document.addEventListener('keydown', this.keydown);
    this.status.setMultiplex(this.multiplex);
    window.addEventListener('blur', this.windowBlur);
    document.addEventListener('visibilitychange', this.visibility);
  }
  override disconnectedCallback(): void {
    super.disconnectedCallback();
    document.removeEventListener('keydown', this.keydown);
    window.removeEventListener('blur', this.windowBlur);
    document.removeEventListener('visibilitychange', this.visibility);
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
        const panel = this.shadowRoot!.querySelector<StatusPanel>('jf-status-panel');
        void panel?.updateComplete.then(() => {
          this.style.setProperty('--frame-status-panel-right', `${Math.max(0, Math.min(window.innerWidth - rect.right, window.innerWidth - panel.getBoundingClientRect().width))}px`);
          this.style.setProperty('--frame-status-arrow-right', `${rect.width / 2}px`);
          panel.shadowRoot?.querySelector<HTMLElement>('[role="dialog"]')?.focus();
        });
      } else this.transient.close();
    }
  }
  toggle(): void { if (this.open) this.close(); else if (!modalOwnsFocus()) this.open = true; }
  close(restoreFocus = true): void {
    if (!this.open) return;
    this.open = false;
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
  override render() {
    const model = this.status.model;
    const pill = model.pill;
    const plugins = this.pluginsVisible ? listStatusBarItems().filter(item => item.source === 'plugin') : [];
    const transient = this.status.snapshot.advisories.find(row => row.key === this.status.transientKey);
    const label = transient?.toast?.message ?? pill.label;
    const symbols = { calm: '●', working: '◌', needs: '!', problems: '!', unreachable: '×', limited: '!', finished: '✓', unknown: '?' };
    return html`<button type="button" class="trigger ${transient ? 'notice' : pill.kind} ${pill.stale ? 'stale' : ''}"
      aria-label=${label} aria-expanded=${this.open} aria-haspopup="dialog" title="What's going on · Ctrl+Shift+S"
      @click=${() => this.toggle()} @mouseenter=${() => this.status.pauseTransient(true)} @mouseleave=${() => this.status.pauseTransient(false)}
      @focus=${() => this.status.pauseTransient(true)} @blur=${() => this.status.pauseTransient(false)}>
      <jf-status-badge .tone=${pill.kind === 'needs' || pill.kind === 'limited' ? 'warning' : pill.kind === 'unreachable' || pill.kind === 'problems' ? 'error' : 'neutral'}><span class="content"><span class="symbol" aria-hidden="true">${symbols[pill.kind]}</span><span class="label">${label}${pill.percent != null ? ` · ${pill.percent}%` : ''}</span>
      ${pill.kind === 'calm' && plugins.length ? html`<span aria-label="Plugin status items">${plugins.length}</span>` : nothing}</span></jf-status-badge></button>
      ${transient?.toast?.onAction ? html`<button class="transient-action" type="button" @mouseenter=${() => this.status.pauseTransient(true)} @mouseleave=${() => this.status.pauseTransient(false)} @focus=${() => this.status.pauseTransient(true)} @blur=${() => this.status.pauseTransient(false)} @click=${() => this.act({ kind: 'advisory', target: transient.key, label: transient.toast!.actionLabel ?? 'Undo' })}>${transient.toast.actionLabel ?? 'Undo'}</button>` : nothing}
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
