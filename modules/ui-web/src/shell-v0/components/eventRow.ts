// SPDX-License-Identifier: Apache-2.0
/**
 * The ONE ledger-row projection (tempdoc 558 §S1) — the convergence the D3 timing change surfaced.
 *
 * The Activity surface (`<jf-action-ledger>`) and the agent retrospective's History tab both render a
 * {@link UnifiedActionEntry} as `who · label · outcome · source · when`. Before this they FORKED: the
 * Activity row showed absolute clock time + the outcome baked into the label; History re-derived a
 * separate `HistoryEntry` and showed relative time + an accent-fill-as-text colour. That is 548 §1's
 * "two authorities for one concept, neither subordinate" at the row level. This module is the single
 * projection both surfaces now compose: a render function + its styles, reusing the existing time
 * ({@link formatRelativeIso}) and status-tone ({@link statusToTone}/{@link toneText}) authorities — no
 * new formatting is introduced here.
 *
 * It is a LEDGER row, deliberately NOT the agent run-step primitive (`<jf-run-node>` /
 * `projectUnifiedThread`): it composes none of those, so it stays outside the run-renderers register
 * (565 §12.6) by construction.
 */
import { html, css, type CSSResult, type TemplateResult } from 'lit';
import type { UnifiedActionEntry } from '../operations/ActionLedgerClient.js';
import { formatRelativeIso } from '../../utils/relativeTime.js';
import { statusToTone, statusGlyph, glyphChar, toneText } from '../utils/statusTone.js';

/** Human absolute time for the `<time>` title/hover (machine ISO in `datetime`, relative form visible). */
function absoluteTime(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

/**
 * The timing cell — relative visible text (the scannable default), with the absolute time available via
 * the `<time datetime>` (machine) + `title` (human) for hover / a11y (tempdoc 558 P1; the Cloudscape
 * relative-with-absolute rule). Exported so the Activity surface's burst-summary row reuses it.
 */
export function renderEventWhen(iso: string): TemplateResult {
  if (!iso) return html`${''}`;
  return html`<time
    class="when"
    datetime=${iso}
    title=${absoluteTime(iso)}
    data-testid="ledger-time"
    >${formatRelativeIso(iso)}</time
  >`;
}

/**
 * The outcome cell — a semantic glyph + the outcome word, tinted via the ONE status-tone authority at
 * TEXT grade (`toneText` → `var(--text-*)`, surface-legible — never the accent fill, which would be
 * accent-as-text). Rows with no outcome (navigation/effect) render nothing.
 */
function renderEventOutcome(outcome: string | undefined): TemplateResult {
  if (!outcome) return html`${''}`;
  const tone = statusToTone(outcome);
  const glyph = glyphChar(statusGlyph(outcome));
  return html`<span
    class="outcome"
    data-testid="ledger-outcome"
    data-tone=${tone}
    style=${`color: ${toneText(tone)}`}
    >${glyph ? html`<span aria-hidden="true">${glyph}</span> ` : ''}${outcome}</span
  >`;
}

/**
 * Optional per-render facets the host may layer onto the shared row. Tempdoc 612 §UX — `isNew` marks a
 * row the user has not seen since they last looked. Activity sets it; History/Timeline pass nothing, so
 * the "new" affordance never leaks to surfaces that don't want it — the 558 §S1 shared-row stays intact.
 */
export interface EventRowOptions {
  readonly isNew?: boolean;
}

/** The ONE ledger-row projection over a {@link UnifiedActionEntry}. */
export function renderEventRow(entry: UnifiedActionEntry, opts: EventRowOptions = {}): TemplateResult {
  return html`<div class="row" data-testid="ledger-row" data-kind=${entry.kind} ?data-new=${!!opts.isNew}>
    ${opts.isNew
      ? html`<span class="new-dot" data-testid="ledger-new-dot" role="img" aria-label="new since you looked"></span>`
      : ''}
    <span class="who" data-originator=${entry.originator}>${entry.originator}</span>
    <span class="label">${entry.label}</span>
    ${renderEventOutcome(entry.outcome)}
    <span class="src">${entry.source === 'backend' ? 'backend' : 'local'}</span>
    ${renderEventWhen(entry.occurredAt)}
  </div>`;
}

/** The single source of the ledger-row styles — every host that renders an event row includes this. */
export const eventRowStyles: CSSResult = css`
  .row {
    display: flex;
    align-items: baseline;
    gap: 0.5rem;
    padding: 0.25rem 0;
    border-bottom: 1px solid var(--border-subtle);
  }
  /* tempdoc 612 §UX/§F2.1 — "new since you looked" affordance (Activity-only, set via the renderEventRow
     opt). ONE calm signal: a small dot (the badge-UX norm); the data-new attribute stays a non-visual
     hook. Reuses the existing accent token, no new colour authority. (The earlier redundant left
     box-shadow accent was dropped per §F2.1 — a single signal for one fact.) */
  .new-dot {
    flex: 0 0 auto;
    width: 0.45rem;
    height: 0.45rem;
    border-radius: 50%;
    background: var(--accent-tint);
    align-self: center;
  }
  .who {
    flex: 0 0 auto;
    font-family: ui-monospace, monospace;
    font-size: var(--font-size-xs);
    padding: 0.05rem 0.35rem;
    border-radius: 0.25rem;
    background: var(--surface-2);
  }
  .who[data-originator='agent'] {
    color: var(--text-tint);
  }
  .who[data-originator='system'] {
    color: var(--text-secondary);
  }
  .who[data-originator='user'] {
    color: var(--text-primary);
  }
  .label {
    flex: 1 1 auto;
  }
  .src {
    flex: 0 0 auto;
    color: var(--text-secondary);
    font-size: var(--font-size-xs);
  }
  /* The "timing" the surface subtitle promises, projected from occurredAt. Tabular figures keep the
     column aligned; muted so it reads as metadata. */
  .when {
    flex: 0 0 auto;
    font-variant-numeric: tabular-nums;
    font-size: var(--font-size-xs);
    color: var(--text-secondary);
  }
  /* The structured outcome cell (glyph + word); colour is applied inline from the statusTone authority
     (text grade), so no status colour literal lives here. */
  .outcome {
    flex: 0 0 auto;
    font-size: var(--font-size-xs);
  }
`;
