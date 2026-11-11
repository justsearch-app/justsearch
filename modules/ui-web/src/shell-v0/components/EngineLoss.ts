// SPDX-License-Identifier: Apache-2.0
/**
 * `<jf-engine-loss>` — the loaded app's engine-loss treatment (S5c FL5, SC1–SC9; ref-11).
 *
 * Lives in the shell below the top bar. While the engine is lost (`state/engineLoss.ts`) it dims the
 * window area behind "JustSearch can't reach its engine" with the explanation, Restart the engine
 * (desktop) or Try again (browser), Open Health and Copy report, last connected and last tried. The
 * top bar stays usable. With Health on screen it steps aside to a band above Health, so the last
 * known facts stay readable. Security dialogs live in the overlay host and stay above it; nothing
 * here grants, answers or restores an approval.
 *
 * Focus moves to the treatment once per outage and returns when it leaves, to where it was or, if
 * that is gone, to the window area. It never takes focus from an open modal.
 */
import { css, html, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { modalOwnsFocus } from '../primitives/modality.js';
import { deepActiveElement } from '../utils/keyboardHandler.js';
import { icon } from './Icon.js';
import { isTauriRuntime } from '../../utils/tauriRuntime.js';
import { getAiState, getLastContactAttemptMs } from '../state/aiStateStore.js';
import {
  engineLossReason, restartEngine, subscribeEngineLoss, tryEngineAgain, type EngineLoss,
} from '../state/engineLoss.js';
import { engineReportText } from '../state/engineReport.js';
import { requestSurfaceNavigation } from '../controllers/navigateRequest.js';
import './Button.js';

const HEALTH_SURFACE_ID = 'core.health-surface';

/** "just now", "4 s ago", "2 min ago", "3 h ago". */
export function engineLossAge(ms: number | null, now: number = Date.now()): string | null {
  if (ms === null) return null;
  const seconds = Math.max(0, Math.round((now - ms) / 1000));
  if (seconds < 2) return 'just now';
  if (seconds < 60) return `${seconds} s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  return `${Math.floor(minutes / 60)} h ago`;
}

export class EngineLossTreatment extends JfElement {
  static properties = {
    loss: { state: true },
    now: { state: true },
    copied: { state: true },
    report: { state: true },
  };

  static transientState = { copied: '', report: '' };
  declare loss: EngineLoss | null;
  declare now: number;
  /** The result of Copy report, said once in the status line. */
  declare copied: string;
  /** The report, shown ready to select when the clipboard refused it. */
  declare report: string;
  private unsubscribe: (() => void) | null = null;
  private clock: ReturnType<typeof setInterval> | undefined;
  private shownEpisode = 0;
  private returnFocus: Element | null = null;

  constructor() {
    super();
    this.loss = null;
    this.now = Date.now();
    this.copied = '';
    this.report = '';
  }

  static styles = css`
    :host { display: none; min-width: 0; min-height: 0; }
    :host([mode='overlay']) {
      display: grid;
      place-items: center;
      overflow: auto;
      padding: var(--jf-space-4);
      box-sizing: border-box;
      background: var(--frame-dialog-dim);
    }
    :host([mode='band']) { display: block; border-block-end: var(--frame-hairline) solid var(--border-subtle); background: var(--surface-1); }
    section {
      box-sizing: border-box;
      max-inline-size: min(100%, calc(var(--frame-dialog-width) * 1.25));
      padding: var(--jf-space-6) var(--jf-space-8);
      border-radius: var(--frame-panel-radius);
      background: var(--surface-1);
      color: var(--text-primary);
      text-align: center;
      font-size: var(--font-size-md);
    }
    /* Focus arrives here only by the treatment itself, to be announced (its heading names it); the
       actions inside carry the visible focus rings. A ring around the whole panel would read as the
       protected security dialog's border. */
    section:focus { outline: none; }
    :host([mode='band']) section {
      max-inline-size: none;
      display: grid;
      gap: var(--jf-space-2);
      padding: var(--jf-space-3) var(--jf-space-6);
      border-radius: 0;
      text-align: start;
    }
    .mark {
      display: inline-grid;
      place-items: center;
      inline-size: var(--frame-control-size);
      block-size: var(--frame-control-size);
      margin-block-end: var(--jf-space-4);
      border: var(--frame-hairline) solid var(--accent-danger-45);
      border-radius: var(--frame-control-radius);
      background: var(--accent-danger-16);
      color: var(--text-danger);
    }
    :host([mode='band']) .mark { display: none; }
    h2 { margin: 0; font-size: var(--font-size-xl); font-weight: var(--jf-weight-medium, 500); overflow-wrap: anywhere; }
    :host([mode='band']) h2 { font-size: var(--font-size-md); }
    p { margin: var(--jf-space-2) 0 0; color: var(--text-secondary); line-height: 1.5; overflow-wrap: anywhere; }
    :host([mode='band']) p { margin: 0; }
    .actions { display: flex; flex-wrap: wrap; justify-content: center; gap: var(--jf-space-2); margin-block-start: var(--jf-space-6); }
    :host([mode='band']) .actions { justify-content: flex-start; margin: 0; }
    jf-button { min-width: 0; max-width: 100%; }
    jf-button::part(control) { min-width: 0; max-width: 100%; box-sizing: border-box; overflow-wrap: anywhere; }
    .progress:empty { display: none; }
    .meta { display: flex; flex-wrap: wrap; justify-content: center; gap: var(--jf-space-2) var(--jf-space-6);
      margin: var(--jf-space-6) 0 0; padding: 0; list-style: none; color: var(--text-secondary); font-size: var(--font-size-sm); }
    :host([mode='band']) .meta { justify-content: flex-start; margin: 0; }
    .meta li { display: inline-flex; align-items: center; gap: var(--jf-space-1); min-width: 0; overflow-wrap: anywhere; }
    textarea { inline-size: 100%; box-sizing: border-box; min-block-size: var(--frame-topbar-height); margin-block-start: var(--jf-space-3);
      font: inherit; font-size: var(--font-size-sm); color: var(--text-primary); background: var(--surface-2);
      border: var(--frame-hairline) solid var(--border-subtle); border-radius: var(--frame-control-radius); }
    textarea:focus-visible { outline: var(--frame-focus-width) solid var(--focus-ring-color); }
    @media (forced-colors: active) {
      :host([mode='overlay']) { background: Canvas; }
      :host([mode='band']) { border-block-end-color: CanvasText; }
      section { background: Canvas; color: CanvasText; border: var(--frame-hairline) solid CanvasText; }
      .mark { background: Canvas; color: CanvasText; border-color: CanvasText; }
      p, .meta { color: CanvasText; }
      textarea:focus-visible { outline-color: Highlight; }
    }
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    this.unsubscribe = subscribeEngineLoss(loss => this.onLoss(loss));
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsubscribe?.();
    this.unsubscribe = null;
    clearInterval(this.clock);
    this.clock = undefined;
  }

  private onLoss(loss: EngineLoss): void {
    const was = this.loss?.lost ?? false;
    this.loss = loss;
    this.now = Date.now();
    this.setAttribute('mode', !loss.lost ? 'hidden' : loss.healthOpen ? 'band' : 'overlay');
    if (loss.lost && !was) {
      this.copied = ''; this.report = '';
      this.clock = setInterval(() => { this.now = Date.now(); }, 1_000);
    }
    if (!loss.lost && was) {
      clearInterval(this.clock);
      this.clock = undefined;
      void this.leave();
    }
    if (loss.lost && loss.episode !== this.shownEpisode) {
      this.shownEpisode = loss.episode;
      // Remember where the person was before the window area goes inert, then move in once.
      const active = deepActiveElement();
      this.returnFocus = active && active !== document.body ? active : null;
      void this.enter();
    }
  }

  private section(): HTMLElement | null {
    return this.shadowRoot?.querySelector<HTMLElement>('section') ?? null;
  }

  private async enter(): Promise<void> {
    await this.updateComplete;
    if (modalOwnsFocus()) return; // A modal keeps its focus; the treatment waits beneath it.
    this.section()?.focus();
  }

  /** Focus back where it was, or to the window area, when it was in the treatment or nowhere. */
  private async leave(): Promise<void> {
    const active = deepActiveElement();
    const inTreatment = !!active && (active === this || this.shadowRoot?.contains(active) === true);
    const lostFocus = !active || active === document.body;
    await this.updateComplete;
    const target = this.returnFocus;
    this.returnFocus = null;
    if (!inTreatment && !lostFocus) return;
    // The Shell drops `inert` from the window area in the same update.
    await Promise.resolve();
    // A window that placed focus while the engine came back (a section it was asked to show) keeps it.
    const now = deepActiveElement();
    if (now && now !== document.body && now !== this && this.shadowRoot?.contains(now) !== true) return;
    if (target instanceof HTMLElement && target.isConnected && !target.closest('[inert]')) {
      target.focus();
      if (deepActiveElement() === target) return;
    }
    const stage = (this.getRootNode() as ShadowRoot | Document).querySelector<HTMLElement>('.stage');
    if (!stage) return;
    if (!stage.hasAttribute('tabindex')) {
      stage.setAttribute('tabindex', '-1');
      stage.addEventListener('blur', () => stage.removeAttribute('tabindex'), { once: true });
    }
    stage.focus();
  }

  private async copyReport(): Promise<void> {
    const text = engineReportText();
    this.report = '';
    try {
      if (!navigator.clipboard?.writeText) throw new Error('no clipboard');
      await navigator.clipboard.writeText(text);
      this.copied = 'Report copied.';
    } catch {
      this.copied = 'The clipboard refused the report. It is shown below, ready to select.';
      this.report = text;
      await this.updateComplete;
      const field = this.shadowRoot?.querySelector('textarea');
      field?.focus();
      field?.select();
    }
  }

  private progress(loss: EngineLoss): string {
    switch (loss.action.kind) {
      case 'restarting': return 'Restarting the engine… This window stays open.';
      case 'refused': return loss.action.message;
      case 'failed': return loss.action.message;
      case 'trying': return 'Trying again…';
      case 'still-lost': return 'Still can’t reach the engine. JustSearch keeps trying every few seconds.';
      default: return '';
    }
  }

  override render(): TemplateResult | typeof nothing {
    const loss = this.loss;
    if (!loss?.lost) return nothing;
    const desktop = isTauriRuntime();
    const band = loss.healthOpen;
    const restarting = loss.action.kind === 'restarting';
    const reason = engineLossReason(loss);
    const title = loss.cause === 'not-ready' ? 'JustSearch’s engine isn’t ready' : 'JustSearch can’t reach its engine';
    const retries = loss.cause === 'unreachable' ? ' JustSearch tries again every few seconds.' : '';
    const lastContact = engineLossAge(getAiState().connection.lastContactMs, this.now);
    const lastTried = engineLossAge(getLastContactAttemptMs(), this.now);
    const message = [this.progress(loss), this.copied].filter(Boolean).join(' ');
    return html`
      <section tabindex="-1" role="region" aria-labelledby="engine-loss-title" aria-describedby="engine-loss-body"
        data-testid="engine-loss">
        <div class="mark" aria-hidden="true">${icon({ name: 'power', size: 18 })}</div>
        <h2 id="engine-loss-title">${title}</h2>
        <p id="engine-loss-body">Searching and indexing are on hold. Your files and your index are safe.${retries}${reason ? ` ${reason}` : ''}</p>
        ${desktop ? nothing : html`<p>The engine runs outside this browser. Restart it where it runs, then try again.</p>`}
        <div class="actions">
          ${desktop
            ? html`<jf-button variant="primary" aria-busy=${restarting ? 'true' : 'false'}
                .onActivate=${() => { void restartEngine(); }}>${icon({ name: 'refresh-cw', size: 14 })}
                ${restarting ? 'Restarting the engine…' : 'Restart the engine'}</jf-button>`
            : html`<jf-button variant="primary" .onActivate=${() => { void tryEngineAgain(); }}>${icon({ name: 'refresh-cw', size: 14 })}
                Try again</jf-button>`}
          ${band ? nothing : html`<jf-button .onActivate=${() => requestSurfaceNavigation(HEALTH_SURFACE_ID)}>${icon({ name: 'server', size: 14 })}
            Open Health</jf-button>`}
          <jf-button variant="ghost" .onActivate=${() => this.copyReport()}>${icon({ name: 'copy', size: 14 })}
            Copy report</jf-button>
        </div>
        <p class="progress" role="status">${message}</p>
        <ul class="meta">
          <li>${icon({ name: 'clock', size: 14 })} ${lastContact ? `Last connected ${lastContact}` : 'Not connected yet'}</li>
          ${lastTried ? html`<li>${icon({ name: 'refresh-cw', size: 14 })} Tried again ${lastTried}</li>` : nothing}
          <li>${icon({ name: 'shield', size: 14 })} The report leaves out paths and keys</li>
        </ul>
        ${this.report ? html`<textarea readonly aria-label="Report ready to select" .value=${this.report}></textarea>` : nothing}
      </section>
    `;
  }
}

if (!customElements.get('jf-engine-loss')) {
  customElements.define('jf-engine-loss', EngineLossTreatment);
}
