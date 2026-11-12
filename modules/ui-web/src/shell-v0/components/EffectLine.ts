// SPDX-License-Identifier: Apache-2.0
/**
 * <jf-effect-line> — Tempdoc 543-fwd UPDATE 10, Principle 1.
 *
 * The single presentational unit for displaying an Effect in human terms. Before
 * this, every effect surface rendered effects its own way — the confirm bars and
 * dry-run used `describeEffect`, but the journal LIST row bypassed it and dumped a
 * raw `JSON.stringify` payload plus a bare `kind`. This primitive makes the display
 * a property of the component graph: one place derives the label (via the exhaustive
 * `describeEffect`), the relative time, and a *structured* detail view (a key/value
 * `<dl>`, not a raw blob) so `invoke-operation` args stay legible.
 *
 * Props (all reactive):
 *   - `effect`      (required) — the Effect to display; label via `describeEffect`.
 *   - `timestamp`   (optional, ISO string) — when set, an inline relative `<time>`
 *                   (house convention `formatRelative`) with the absolute time on hover.
 *   - `showDetail`  (optional) — when set AND the effect carries fields beyond `kind`,
 *                   a collapsible `<details>` structured key/value view.
 *
 * Host chrome (selection checkbox, originator chip, per-entry action buttons) stays in
 * the consuming component and composes around this line.
 */

import { formatDateTime } from '../display/format.js';
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import type { Effect } from '../substrates/effect.js';
// 557 §2.A — render the effect label via the one display projector, not the raw
// describeEffect resolver (present()'s effect case delegates to describeEffect).
import { present } from '../display/present.js';
import { formatRelative } from '../utils/relativeTime.js';

export class EffectLine extends JfElement {
  static properties = {
    effect: { attribute: false },
    timestamp: { type: String },
    showDetail: { type: Boolean },
  };

  declare effect: Effect;
  declare timestamp?: string;
  declare showDetail: boolean;

  constructor() {
    super();
    this.showDetail = false;
  }

  static styles = css`
    :host {
      display: block;
      min-width: 0;
    }
    .line {
      display: flex;
      gap: 0.5rem;
      align-items: baseline;
      min-width: 0;
    }
    .label {
      font-weight: 500;
      overflow-wrap: anywhere;
      min-width: 0;
    }
    .time {
      color: var(--text-tertiary);
      font-size: var(--font-size-xs);
      white-space: nowrap;
      flex-shrink: 0;
    }
    .detail {
      margin-block-start: 0.25rem;
      font-size: var(--font-size-xs);
    }
    .detail summary {
      cursor: pointer;
      color: var(--text-tertiary);
      font-size: var(--font-size-xs);
      user-select: none;
    }
    dl {
      margin: 0.25rem 0 0;
      display: grid;
      grid-template-columns: max-content 1fr;
      gap: 0.0625rem 0.5rem;
    }
    dt {
      color: var(--text-secondary);
      font-family: var(--font-mono);
    }
    dd {
      margin: 0;
      overflow-wrap: anywhere;
      font-family: var(--font-mono);
    }
  `;

  /** The effect's fields beyond `kind`, as display key/value pairs. */
  private detailRows(): Array<readonly [string, string]> {
    const { kind, ...rest } = this.effect as Record<string, unknown>;
    void kind;
    return Object.entries(rest).map(([k, v]) => {
      const display =
        v !== null && typeof v === 'object' ? safeStringify(v) : String(v);
      return [k, display] as const;
    });
  }

  override render(): TemplateResult {
    if (!this.effect) return html``;
    const label = present({ kind: 'effect', effect: this.effect }).label;
    const rows = this.showDetail ? this.detailRows() : [];

    let timeNode: TemplateResult | typeof nothing = nothing;
    if (this.timestamp) {
      const ms = new Date(this.timestamp).getTime();
      if (!Number.isNaN(ms)) {
        timeNode = html`<time
          class="time"
          datetime=${this.timestamp}
          title=${formatDateTime(new Date(ms))}
          >${formatRelative(ms)}</time
        >`;
      }
    }

    return html`
      <div class="line">
        <span class="label" data-testid="effect-label">${label}</span>
        ${timeNode}
      </div>
      ${rows.length > 0
        ? html`<details class="detail">
            <summary data-testid="effect-detail-toggle">Recorded fields (for support)</summary>
            <dl>
              ${rows.map(
                ([k, v]) =>
                  html`<dt>${k}</dt>
                    <dd>${v}</dd>`,
              )}
            </dl>
          </details>`
        : nothing}
    `;
  }
}

function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value);
  } catch {
    return '[unrenderable]';
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-effect-line')
) {
  customElements.define('jf-effect-line', EffectLine);
}
