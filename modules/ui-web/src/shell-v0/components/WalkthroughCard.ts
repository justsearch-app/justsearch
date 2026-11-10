// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 521 §16.4 — `<jf-walkthrough-card>` Lit element.
 *
 * Mounted once near the bottom of Shell. Picks the highest-priority
 * non-dismissed, non-complete walkthrough from {@link WalkthroughRegistry}
 * and renders its current step (title, body, step counter, Next +
 * Dismiss buttons). State persists in
 * {@link '../state/UserStateDocument.ts'} so progress survives reloads.
 *
 * Step completion modes:
 *   - `Next` button: marks the current step complete and advances.
 *   - `completionEvent: 'onCommand:<id>'`: when the CommandRegistry fires
 *     that command, the step is marked complete automatically. (V1
 *     event vocabulary; future kinds fold in additively.)
 */

import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import './Button.js';

import {
  listWalkthroughs,
  onWalkthroughCatalogChange,
  type WalkthroughContribution,
  type WalkthroughStep,
} from '../commands/WalkthroughRegistry.js';
import { onCommandInvoked } from '../commands/CommandRegistry.js';
import {
  dismissWalkthrough,
  getWalkthroughProgress,
  markWalkthroughStepComplete,
  setWalkthroughActiveStep,
  subscribeWalkthroughProgress,
  onSettingChanged,
  type WalkthroughProgress,
} from '../state/UserStateDocument.js';
import { getSessionPluginRegistry } from '../plugin-api/sessionRegistry.js';

interface ActiveStepView {
  readonly walkthrough: WalkthroughContribution;
  readonly step: WalkthroughStep;
  readonly stepIndex: number;
  readonly totalSteps: number;
}

export class WalkthroughCard extends JfElement {
  static properties = {
    active: { state: true },
    embedded: { type: Boolean },
  };

  declare active: ActiveStepView | null;
  declare embedded: boolean;

  private unsubs: Array<() => void> = [];
  // Tempdoc 521 §22 Phase C — the active step's onSettingChanged
  // subscription is rebuilt every refresh() to track the current
  // step's key (each step may target a different dotted-path).
  private settingChangedUnsub: (() => void) | null = null;

  constructor() {
    super();
    this.active = null;
    this.embedded = false;
  }

  static styles = css`
    :host { display: block; min-inline-size: 0; }
    :host([hidden]) { display: none; }
    .card { padding: var(--jf-space-4); color: var(--text-primary);
      border-block-start: var(--frame-hairline) solid var(--border-subtle); }
    .header, .actions { display: flex; flex-wrap: wrap; gap: var(--jf-space-2); justify-content: space-between; }
    .header { font-size: var(--font-size-xs); color: var(--text-secondary); }
    .title { font-size: var(--font-size-md); margin-block: var(--jf-space-2); }
    .body { margin-block: var(--jf-space-3); white-space: pre-wrap; overflow-wrap: anywhere; }
    @media (forced-colors: active) { .card { border-color: CanvasText; } }
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    this.unsubs.push(onWalkthroughCatalogChange(() => this.refresh()));
    this.unsubs.push(subscribeWalkthroughProgress(() => this.refresh()));
    if (!this.embedded) this.unsubs.push(
      onCommandInvoked((commandId) =>
        this.handleCompletionTrigger('onCommand', commandId),
      ),
    );
    // Plugin-installed: any successful install triggers the dispatcher,
    // which advances iff the active step's completionEvent's pluginId
    // matches the installed id.
    if (!this.embedded) this.unsubs.push(
      getSessionPluginRegistry().onInstalled((pluginId) =>
        this.handleCompletionTrigger('extensionInstalled', pluginId),
      ),
    );
    this.refresh();
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    for (const u of this.unsubs) u();
    this.unsubs = [];
    this.settingChangedUnsub?.();
    this.settingChangedUnsub = null;
  }

  /** Tempdoc 521 §22 Phase C — bind / rebind the onSettingChanged
   *  subscription so it targets the active step's dotted-path key
   *  (instead of an upfront allowlist of four V1 keys). Called from
   *  refresh() whenever the active step changes. */
  private updateSettingChangedSubscription(): void {
    this.settingChangedUnsub?.();
    this.settingChangedUnsub = null;
    if (this.embedded) return;
    const event = this.active?.step.completionEvent;
    if (!event || !event.startsWith('onSettingChanged:')) return;
    const key = event.slice('onSettingChanged:'.length);
    this.settingChangedUnsub = onSettingChanged(key, () =>
      this.handleCompletionTrigger('onSettingChanged', key),
    );
  }

  private handleCompletionTrigger(
    kind: 'onCommand' | 'onSettingChanged' | 'extensionInstalled',
    value: string,
  ): void {
    const active = this.active;
    if (!active) return;
    const expected = active.step.completionEvent;
    if (!expected) return;
    const prefix = `${kind}:`;
    if (!expected.startsWith(prefix)) return;
    if (expected.slice(prefix.length) !== value) return;
    this.advance();
  }

  private refresh(): void {
    const walkthroughs = listWalkthroughs();
    for (const w of walkthroughs) {
      const progress = getWalkthroughProgress(w.id);
      if (progress?.dismissed) continue;
      const stepIndex = progress?.activeStepIndex ?? 0;
      if (stepIndex >= w.steps.length) continue; // already complete
      const step = w.steps[stepIndex]!;
      this.active = {
        walkthrough: w,
        step,
        stepIndex,
        totalSteps: w.steps.length,
      };
      this.hidden = !this.embedded;
      this.updateSettingChangedSubscription();
      return;
    }
    this.active = null;
    this.hidden = true;
    this.updateSettingChangedSubscription();
  }

  private advance(): void {
    const active = this.active;
    if (!active) return;
    markWalkthroughStepComplete(active.walkthrough.id, active.step.id);
    const next = active.stepIndex + 1;
    setWalkthroughActiveStep(active.walkthrough.id, next);
    // subscribeWalkthroughProgress will fire refresh()
  }

  private handleDismiss(): void {
    if (!this.active) return;
    dismissWalkthrough(this.active.walkthrough.id);
  }

  override render(): TemplateResult {
    const active = this.active;
    if (!active || !this.embedded) return html``;
    const finalStep = active.stepIndex === active.totalSteps - 1;
    return html`
      <div class="card" role="status" aria-live="polite">
        <div class="header">
          <span>${active.walkthrough.title}</span>
          <span>Step ${active.stepIndex + 1} of ${active.totalSteps}</span>
        </div>
        <h3 class="title">${active.step.title}</h3>
        <div class="body">${active.step.body}</div>
        <div class="actions">
          <jf-button label="Dismiss" .onActivate=${() => this.handleDismiss()}>Dismiss</jf-button>
          <jf-button variant="primary" label=${finalStep ? 'Done' : 'Next'} .onActivate=${() => this.advance()}>
            ${finalStep ? 'Done' : 'Next'}
          </jf-button>
        </div>
      </div>
    `;
  }

  // Visible for testing: shape inspection of the currently-rendered
  // step. The shadow-DOM render path is exercised by the
  // WalkthroughCard.render.test happy-dom suite.
  /** @internal */ getActiveStep(): ActiveStepView | null {
    return this.active;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-walkthrough-card')) {
  customElements.define('jf-walkthrough-card', WalkthroughCard);
}

/** Convenience helper for callers that want to read raw progress. */
export function readWalkthroughProgress(id: string): WalkthroughProgress | undefined {
  return getWalkthroughProgress(id);
}
