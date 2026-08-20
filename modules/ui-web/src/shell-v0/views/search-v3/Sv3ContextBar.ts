// SPDX-License-Identifier: Apache-2.0
/**
 * jf-sv3-context-bar — what the next prompt will contain, in one line above the composer
 * (tempdoc 610 §E.4 + §I.2, ported by 852 S2).
 *
 * Two facts, and only when there are any:
 *
 *  - **The meter** — how full the model's context window was on the last completed turn, read off
 *    the SHARED `projectContextHorizon` projection (the same one the agent rail's headroom meter
 *    uses) so the two cannot disagree about what 80% means. It is a BUTTON as well as a bar: it
 *    opens the shared context inspector, which is the only place the split becomes segments.
 *  - **The hidden-turn aggregate** — how many turns the reader has individually excluded, with the
 *    bulk undo beside it. A per-turn act needs a whole-conversation readout, or a turn hidden and
 *    scrolled past is a change to every future answer with nothing on screen that says so.
 *
 * It renders NOTHING when the conversation has reported no occupancy and hides no turn — a resting
 * row that says "Context 0%" would be chrome asserting a number the window was never told.
 *
 * Presentational: it holds no store, reads no history, and raises its two acts as events. The
 * placement mirrors the reference window's (`views/UnifiedChatView.ts:2726-2727` — the meter and the
 * excluded summary sit between the transcript and the composer).
 *
 * Side-effect registers <jf-sv3-context-bar>.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { sv3Shared } from './sv3-shared-styles.js';
import '../../components/Control.js';
import { projectContextHorizon } from '../budgetProjection.js';
import {
  CONTEXT_INCLUDE_ALL,
  CONTEXT_INCLUDE_ALL_LABEL,
  CONTEXT_METER_BAR_LABEL,
  contextHiddenLabel,
  contextMeterLabel,
  contextMeterTrigger,
  contextSplitLabel,
} from './fixtures.js';
import { SV3_CONTEXT_ACTION, type Sv3ContextAction } from './sv3-context.js';
import type { Sv3ContextUsage } from './sv3-sessions.js';

export class Sv3ContextBar extends JfElement {
  static styles = [
    sv3Shared,
    css`
      :host {
        display: block;
        padding-inline: var(--space-4);
      }
      /* Tempdoc 859 §B — the bar rides the window's floating '.dock', which is click-through so the
         transcript stays wheel-scrollable beneath it. This row carries real controls (the context
         meter, the hidden-turn act), so it takes pointer events back for exactly its own box — the
         same division the composer's '.band' makes, at the same measure. */
      .bar {
        pointer-events: auto;
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: var(--space-2);
        max-inline-size: 48rem;
        margin-inline: auto;
        color: var(--secondary-label);
        font-size: var(--font-size-sv3-xs);
        font-variant-numeric: tabular-nums;
      }
      .meter {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        min-width: 0;
      }
      .meter-trigger::part(control) {
        padding-inline: var(--space-1);
        border-radius: var(--control-radius);
        color: inherit;
        font: inherit;
        cursor: pointer;
      }
      .meter-trigger::part(control):hover {
        color: var(--foreground);
      }
      /* The bar is the SHARED budget visual's geometry, at this window's scale. The fill's three
         rungs are the one fullness→colour authority's verdict, not a per-surface palette. */
      .track {
        inline-size: var(--space-12);
        block-size: var(--space-1);
        border-radius: var(--control-radius);
        background: var(--muted);
        overflow: hidden;
      }
      .fill {
        block-size: 100%;
      }
      .fill[data-color='green'] {
        background: var(--success-foreground);
      }
      .fill[data-color='yellow'] {
        background: var(--warning-foreground);
      }
      .fill[data-color='red'] {
        background: var(--error-foreground);
      }
      .hidden-turns {
        display: flex;
        align-items: center;
        gap: var(--space-1);
        margin-inline-start: auto;
      }
      .hidden-act::part(control) {
        padding-inline: var(--space-1);
        border-radius: var(--control-radius);
        color: inherit;
        font: inherit;
        cursor: pointer;
      }
      .hidden-act::part(control):hover {
        color: var(--foreground);
      }
    `,
  ];

  static properties = {
    usage: { attribute: false },
    contextWindow: { attribute: false },
    hiddenTurns: { type: Number, attribute: 'hidden-turns' },
  };

  /** The last completed turn's own report, or null while this conversation has reported none. */
  declare usage: Sv3ContextUsage | null;
  /** The runtime's `n_ctx`, or null when the observed state has not named one. */
  declare contextWindow: number | null;
  /** How many TURNS the reader has hidden something of. */
  declare hiddenTurns: number;

  constructor() {
    super();
    this.usage = null;
    this.contextWindow = null;
    this.hiddenTurns = 0;
  }

  private act(detail: Sv3ContextAction): void {
    this.dispatchEvent(
      new CustomEvent<Sv3ContextAction>(SV3_CONTEXT_ACTION, {
        detail,
        bubbles: true,
        composed: true,
      }),
    );
  }

  render(): TemplateResult | typeof nothing {
    const meter = this.meter();
    if (meter === nothing && this.hiddenTurns === 0) return nothing;
    return html`<div class="bar" data-testid="sv3-context-bar">
      ${meter}${this.hiddenTurnsSummary()}
    </div>`;
  }

  private meter(): TemplateResult | typeof nothing {
    // The SHARED projection decides whether there is anything honest to show: no denominator or no
    // occupancy yet ⇒ no meter, rather than a confident 0%.
    const horizon = projectContextHorizon({
      tokensConsumed: 0,
      tokensRemaining: 0,
      promptTokens: this.usage?.promptTokens ?? 0,
      contextWindow: this.contextWindow ?? 0,
    });
    if (horizon === null) return nothing;
    const breakdown = this.usage?.breakdown ?? null;
    // §I.2 — the per-phase split is an ESTIMATE and says so wherever it appears. It rides the
    // trigger's title so it is reachable without opening the inspector, and never replaces the
    // authoritative total the bar itself draws.
    const split =
      breakdown === null
        ? ''
        : ` — ${contextSplitLabel(breakdown.system, breakdown.conversation, breakdown.retrieved)}`;
    return html`<span class="meter">
      <jf-control
        class="meter-trigger"
        data-testid="sv3-context-meter"
        label=${contextMeterTrigger(horizon.pct)}
        title=${`${contextMeterLabel(horizon.pct, horizon.occupancy, horizon.window)}${split}`}
        .onActivate=${() => this.act({ action: 'inspect' })}
        >${contextMeterLabel(horizon.pct, horizon.occupancy, horizon.window)}</jf-control
      >
      <span
        class="track"
        role="meter"
        aria-label=${CONTEXT_METER_BAR_LABEL}
        aria-valuenow=${horizon.pct}
        aria-valuemin="0"
        aria-valuemax="100"
      >
        <span
          class="fill"
          data-color=${horizon.color}
          data-testid="sv3-context-meter-fill"
          style=${`width:${horizon.pct}%`}
        ></span>
      </span>
    </span>`;
  }

  private hiddenTurnsSummary(): TemplateResult | typeof nothing {
    if (this.hiddenTurns === 0) return nothing;
    return html`<span class="hidden-turns">
      <span data-testid="sv3-context-hidden">${contextHiddenLabel(this.hiddenTurns)}</span>
      <jf-control
        class="hidden-act"
        data-testid="sv3-context-include-all"
        label=${CONTEXT_INCLUDE_ALL_LABEL}
        .onActivate=${() => this.act({ action: 'include-all' })}
        >${CONTEXT_INCLUDE_ALL}</jf-control
      >
    </span>`;
  }
}

customElements.define('jf-sv3-context-bar', Sv3ContextBar);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-context-bar': Sv3ContextBar;
  }
}
