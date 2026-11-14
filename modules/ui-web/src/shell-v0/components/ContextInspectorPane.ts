// SPDX-License-Identifier: Apache-2.0
/**
 * <jf-context-inspector-pane> — Tempdoc 610 §K/§J — the details pane's Context tab.
 *
 * Shows the WHOLE prompt the LAST completed turn saw, as a legible projection: the system phase, the
 * in-context conversation turns, and the retrieved documents — each with its estimated token cost and
 * a position marker (§L.1: start/end attend well, the middle is "weaker"). Presentational only: the
 * window, which holds the thread, floor, breakdown and sources, computes the `view` and passes it in.
 *
 * Honest framing: it shows what the model was GIVEN (input), with position as the visible caveat that
 * given ≠ uniformly used; tokens are estimates (the real total is the authoritative figure).
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';

/** One segment of the assembled prompt (a conversation turn or a retrieved source). */
export interface InspectorSegment {
  readonly label: string;
  readonly text: string;
  readonly tokens: number | null;
  /** §L.1 — 'weak' = mid-context (the model attends to it least). */
  readonly position: 'strong' | 'weak';
}

/** A phase of the assembled prompt (Conversation / Documents). */
export interface InspectorPhase {
  readonly name: string;
  readonly tokens: number | null;
  readonly segments: readonly InspectorSegment[];
}

/** The whole-prompt projection the inspector renders (the last completed turn). */
export interface InspectorView {
  readonly systemTokens: number | null;
  readonly phases: readonly InspectorPhase[];
  readonly totalTokens: number | null;
  readonly windowTokens: number | null;
}

export class ContextInspectorPane extends JfElement {
  static properties = {
    view: { attribute: false },
  };

  declare view: InspectorView | null;

  constructor() {
    super();
    this.view = null;
  }

  static styles = css`
    :host {
      display: block;
      height: 100%;
    }
    .panel {
      height: 100%;
      color: var(--text-primary);
      display: flex;
      flex-direction: column;
    }
    .title {
      margin: 0;
      padding: 0.75rem 1rem 0;
      font-weight: 600;
      font-size: var(--font-size-sm);
    }
    .subtitle {
      color: var(--text-muted);
      font-size: var(--font-size-xs);
      padding: 0 1rem 0.5rem;
    }
    .scroll {
      flex: 1;
      overflow-y: auto;
      padding: 0.5rem 1rem 1rem;
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }
    .empty-state {
      flex: 1;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 2rem;
      text-align: center;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
    .phase-head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 0.5rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      text-transform: uppercase;
      letter-spacing: 0.03em;
      border-bottom: 1px solid var(--border-subtle);
      padding-bottom: 0.25rem;
    }
    .phase-tokens {
      color: var(--text-muted);
      text-transform: none;
      letter-spacing: normal;
    }
    .seg {
      padding: 0.4rem 0.55rem;
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      border-radius: 8px;
      margin-top: 0.35rem;
    }
    .seg-head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 0.5rem;
      font-size: var(--font-size-xs);
    }
    .seg-label {
      font-weight: 600;
      color: var(--text-primary);
    }
    .seg-tokens {
      color: var(--text-muted);
      white-space: nowrap;
    }
    .seg-text {
      margin-top: 0.2rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      overflow: hidden;
      text-overflow: ellipsis;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
    }
    .seg-weak {
      margin-top: 0.25rem;
      font-size: var(--font-size-xs);
      color: var(--text-warning);
    }
    .system-line {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .foot {
      flex-shrink: 0;
      padding: 0.6rem 1rem;
      border-top: 1px solid var(--border-default);
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .est {
      color: var(--text-secondary);
      font-style: italic;
    }
    :host .segment-empty { padding:0.35rem 0.1rem; }

  `;

  private renderSegment(seg: InspectorSegment): TemplateResult {
    return html`<div class="seg">
      <div class="seg-head">
        <span class="seg-label">${seg.label}</span>
        ${seg.tokens != null
          ? html`<span class="seg-tokens">~${seg.tokens} tok</span>`
          : nothing}
      </div>
      <div class="seg-text">${seg.text}</div>
      ${seg.position === 'weak'
        ? html`<div class="seg-weak">mid-context · the model attends to this least</div>`
        : nothing}
    </div>`;
  }

  render(): TemplateResult {
    const v = this.view;
    return html`<div class="panel" data-testid="context-inspector">
      <h2 class="title">What the assistant sees</h2>
      <div class="subtitle">The last turn's prompt — tokens are <span class="est">estimates</span>.</div>
      ${!v || (v.phases.length === 0 && v.systemTokens == null)
        ? html`<div class="empty-state">Send a turn to see what the assistant's context held.</div>`
        : html`<div class="scroll">
              ${v.systemTokens != null
                ? html`<div class="system-line">
                    System instructions — <span class="est">~${v.systemTokens} tok</span>
                  </div>`
                : nothing}
              ${v.phases.map(
                (p) => html`
                  <div>
                    <div class="phase-head">
                      <span>${p.name}</span>
                      ${p.tokens != null
                        ? html`<span class="phase-tokens">~${p.tokens} tok</span>`
                        : nothing}
                    </div>
                    ${p.segments.length === 0
                      ? html`<div class="seg-text segment-empty">— none —</div>`
                      : p.segments.map((s) => this.renderSegment(s))}
                  </div>
                `,
              )}
            </div>
            <div class="foot">
              ${v.totalTokens != null && v.windowTokens != null
                ? html`Total: ${v.totalTokens} of ${v.windowTokens} tokens used (real).`
                : html`Total occupancy unknown until a turn completes.`}
            </div>`}
    </div>`;
  }
}

if (!customElements.get('jf-context-inspector-pane')) {
  customElements.define('jf-context-inspector-pane', ContextInspectorPane);
}

declare global {
  interface HTMLElementTagNameMap {
    'jf-context-inspector-pane': ContextInspectorPane;
  }
}
