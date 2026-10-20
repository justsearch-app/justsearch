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
 *    scrolled past is a change to every future answer with nothing on screen that says so. Hidden
 *    messages no turn on screen names are counted beside it, as MESSAGES (S2.7): the bulk undo puts
 *    them back too, so the bar has to say they exist.
 *  - **A floor no divider can carry** (S2.8) — the floor sits on a message this window does not draw
 *    as a turn, so the transcript has nowhere to put its divider. The bar says so, and carries what
 *    the divider would: the summary (shown, edited) and Restore.
 *
 * It renders NOTHING when the conversation has reported no occupancy and hides nothing — a resting
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
  CONTEXT_FLOOR_GROUP_LABEL,
  CONTEXT_FLOOR_RESTORE,
  CONTEXT_FLOOR_RESTORE_LABEL,
  CONTEXT_FLOOR_UNSEEN,
  CONTEXT_FLOOR_UNSEEN_SUMMARIZED,
  CONTEXT_INCLUDE_ALL,
  CONTEXT_INCLUDE_ALL_LABEL,
  CONTEXT_METER_BAR_LABEL,
  CONTEXT_SUMMARY_CANCEL,
  CONTEXT_SUMMARY_EDIT,
  CONTEXT_SUMMARY_EDIT_LABEL,
  CONTEXT_SUMMARY_HIDE,
  CONTEXT_SUMMARY_INPUT_LABEL,
  CONTEXT_SUMMARY_SAVE,
  CONTEXT_SUMMARY_SHOW,
  contextHiddenLabel,
  contextMeterLabel,
  contextMeterTrigger,
  contextSplitLabel,
  contextUnseenLabel,
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
         same division the composer's '.band' makes, at the same measure.

         AND IT NEEDS ITS OWN SURFACE. In the flow it sat on the window's own '--background' and its
         contrast was a fixed, checkable pair. Floating, it sits over ARBITRARY SCROLLING CONTENT,
         so a bare '--secondary-label' on transparency has no contrast floor at all — the text is
         legible or not depending on which line of the transcript happens to be passing underneath.

         The material is the composer's glass recipe, not a second one: the same
         '--glass-blur-scale' multiplier drives the blur AND the fill's translucency, so
         '[data-surface-mode=\"solid\"]' and 'prefers-reduced-transparency' degrade this strip and the
         composer identically, from one source. Kept visually light — a small radius and a shallow
         inset, because this is a meter strip, not a panel; it is the composer's box one notch down,
         not a second kind of chrome. */
      .bar {
        pointer-events: auto;
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: var(--space-2);
        max-inline-size: var(--measure-prose);
        margin-inline: auto;
        padding: var(--space-1) var(--space-3);
        border-radius: var(--radius-lg);
        background: color-mix(
          in srgb,
          var(--composer-glass-surface)
            calc(100% - (100% - var(--glass-opacity)) * var(--glass-blur-scale)),
          transparent
        );
        -webkit-backdrop-filter: blur(calc(var(--glass-blur) * var(--glass-blur-scale)))
          saturate(var(--glass-saturation));
        backdrop-filter: blur(calc(var(--glass-blur) * var(--glass-blur-scale)))
          saturate(var(--glass-saturation));
        color: var(--secondary-label);
        font-size: var(--font-size-sv3-xs);
        font-variant-numeric: tabular-nums;
      }
      /* The mandatory companion to any glass surface in this window: where blur is unsupported the
         fill goes opaque, because a translucent surface with nothing blurred behind it is
         unreadable, not subtle. */
      @supports not ((-webkit-backdrop-filter: blur(1px)) or (backdrop-filter: blur(1px))) {
        .bar {
          background: var(--composer-glass-surface);
        }
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
      .floor-unseen {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: var(--space-1);
        inline-size: 100%;
      }
      .floor-summary {
        inline-size: 100%;
        margin: 0;
        padding: var(--space-2);
        border-radius: var(--control-radius);
        background: var(--muted);
        line-height: 1.5;
      }
      .floor-input {
        inline-size: 100%;
        min-block-size: var(--space-12);
        box-sizing: border-box;
        padding: var(--space-1);
        border: 1px solid var(--border);
        border-radius: var(--control-radius);
        background: var(--background);
        color: var(--foreground);
        font: inherit;
      }
      .floor-input:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: 1px;
      }
      .floor-summary-acts {
        display: flex;
        gap: var(--space-2);
        margin-top: var(--space-1);
      }
    `,
  ];

  static properties = {
    notice: { attribute: false },
    usage: { attribute: false },
    contextWindow: { attribute: false },
    hiddenTurns: { type: Number, attribute: 'hidden-turns' },
    unseenHidden: { type: Number, attribute: 'unseen-hidden' },
    floorUnseen: { type: Boolean, attribute: 'floor-unseen' },
    floorSummary: { attribute: false },
    showSummary: { state: true },
    editingSummary: { state: true },
    summaryDraft: { state: true },
  };

  /** The last completed turn's own report, or null while this conversation has reported none. */
  declare notice: string;
  declare usage: Sv3ContextUsage | null;
  /** The runtime's `n_ctx`, or null when the observed state has not named one. */
  declare contextWindow: number | null;
  /** How many TURNS the reader has hidden something of. */
  declare hiddenTurns: number;
  /** How many hidden MESSAGES no turn on screen names (unique). */
  declare unseenHidden: number;
  /** The floor names no turn on screen, so the bar carries it. */
  declare floorUnseen: boolean;
  /** That floor's compaction summary, or null for a plain rewind. */
  declare floorSummary: string | null;
  declare showSummary: boolean;
  declare editingSummary: boolean;
  declare summaryDraft: string;
  /** The summary text a save is waiting on — the editor closes when the store says it landed. */
  private pendingSummary: string | null = null;

  constructor() {
    super();
    this.notice = '';
    this.usage = null;
    this.contextWindow = null;
    this.hiddenTurns = 0;
    this.unseenHidden = 0;
    this.floorUnseen = false;
    this.floorSummary = null;
    this.showSummary = false;
    this.editingSummary = false;
    this.summaryDraft = '';
  }

  /**
   * The summary editor answers to the STORE, as the transcript's divider does: it stays open until
   * the summary comes back as what was saved, so a refused save leaves the reader's correction where
   * it was. Any other change of summary (or of floor) resets it.
   */
  protected willUpdate(changed: Map<string, unknown>): void {
    if (this.pendingSummary !== null && this.floorSummary === this.pendingSummary) {
      this.pendingSummary = null;
      this.editingSummary = false;
      return;
    }
    if (!changed.has('floorSummary') && !changed.has('floorUnseen')) return;
    this.pendingSummary = null;
    this.editingSummary = false;
    this.summaryDraft = '';
    this.showSummary = false;
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
    if (!this.notice && meter === nothing && this.hiddenTurns === 0 && this.unseenHidden === 0 && !this.floorUnseen) {
      return nothing;
    }
    return html`<div class="bar" data-testid="sv3-context-bar">
      ${this.notice ? html`<span role="status">${this.notice}</span>` : nothing}
      ${meter}${this.hiddenTurnsSummary()}${this.unseenFloor()}
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
    if (this.hiddenTurns === 0 && this.unseenHidden === 0) return nothing;
    return html`<span class="hidden-turns">
      ${this.hiddenTurns === 0
        ? nothing
        : html`<span data-testid="sv3-context-hidden">${contextHiddenLabel(this.hiddenTurns)}</span>`}
      ${this.unseenHidden === 0
        ? nothing
        : html`<span data-testid="sv3-context-hidden-unseen"
            >${contextUnseenLabel(this.unseenHidden)}</span
          >`}
      <jf-control
        class="hidden-act"
        data-testid="sv3-context-include-all"
        label=${CONTEXT_INCLUDE_ALL_LABEL}
        .onActivate=${() => this.act({ action: 'include-all' })}
        >${CONTEXT_INCLUDE_ALL}</jf-control
      >
    </span>`;
  }

  private unseenFloor(): TemplateResult | typeof nothing {
    if (!this.floorUnseen) return nothing;
    const summary = this.floorSummary;
    const compacted = summary !== null && summary !== '';
    return html`<div class="floor-unseen" role="group" aria-label=${CONTEXT_FLOOR_GROUP_LABEL}>
      <span data-testid="sv3-context-floor-unseen"
        >${compacted ? CONTEXT_FLOOR_UNSEEN_SUMMARIZED : CONTEXT_FLOOR_UNSEEN}</span
      >
      ${compacted
        ? html`<jf-control
            class="hidden-act"
            data-testid="sv3-context-floor-summary-toggle"
            label=${this.showSummary ? CONTEXT_SUMMARY_HIDE : CONTEXT_SUMMARY_SHOW}
            .onActivate=${() => {
              this.showSummary = !this.showSummary;
              if (!this.showSummary) this.editingSummary = false;
            }}
            >${this.showSummary ? CONTEXT_SUMMARY_HIDE : CONTEXT_SUMMARY_SHOW}</jf-control
          >`
        : nothing}
      ${compacted && this.showSummary && !this.editingSummary
        ? html`<jf-control
            class="hidden-act"
            data-testid="sv3-context-floor-summary-edit"
            label=${CONTEXT_SUMMARY_EDIT_LABEL}
            .onActivate=${() => {
              this.summaryDraft = summary ?? '';
              this.editingSummary = true;
            }}
            >${CONTEXT_SUMMARY_EDIT}</jf-control
          >`
        : nothing}
      <jf-control
        class="hidden-act"
        data-testid="sv3-context-floor-restore"
        label=${CONTEXT_FLOOR_RESTORE_LABEL}
        .onActivate=${() => this.act({ action: 'restore' })}
        >${CONTEXT_FLOOR_RESTORE}</jf-control
      >
      ${compacted && this.showSummary ? this.summaryBody(summary ?? '') : nothing}
    </div>`;
  }

  private summaryBody(summary: string): TemplateResult {
    if (!this.editingSummary) {
      return html`<p class="floor-summary" data-testid="sv3-context-floor-summary">${summary}</p>`;
    }
    return html`<div class="floor-summary">
      <textarea
        class="floor-input"
        data-testid="sv3-context-floor-summary-input"
        aria-label=${CONTEXT_SUMMARY_INPUT_LABEL}
        .value=${this.summaryDraft}
        @input=${(event: Event) => {
          this.summaryDraft = (event.target as HTMLTextAreaElement).value;
        }}
      ></textarea>
      <div class="floor-summary-acts">
        <jf-control
          class="hidden-act"
          data-testid="sv3-context-floor-summary-save"
          label=${CONTEXT_SUMMARY_SAVE}
          .onActivate=${() => {
            this.pendingSummary = this.summaryDraft;
            this.act({ action: 'summary', text: this.summaryDraft });
          }}
          >${CONTEXT_SUMMARY_SAVE}</jf-control
        >
        <jf-control
          class="hidden-act"
          data-testid="sv3-context-floor-summary-cancel"
          label=${CONTEXT_SUMMARY_CANCEL}
          .onActivate=${() => {
            this.editingSummary = false;
          }}
          >${CONTEXT_SUMMARY_CANCEL}</jf-control
        >
      </div>
    </div>`;
  }
}

customElements.define('jf-sv3-context-bar', Sv3ContextBar);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-context-bar': Sv3ContextBar;
  }
}
