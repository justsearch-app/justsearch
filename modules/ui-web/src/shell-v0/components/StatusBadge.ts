// SPDX-License-Identifier: Apache-2.0
/**
 * StatusBadge (`jf-status-badge`) — tempdoc 574 Move 3 (the visual-atom tier). @atom
 *
 * The ONE status-badge atom: a pill with a tone-tinted background + text-grade label (the
 * HealthSurface healthy/warn pattern, re-authored across §14's badge sites with drifting
 * padding/radius/colour). Colour is PROJECTED from an authority — the 565 `statusTone` one
 * (`toneAccentSoft` bg + `toneText` text) for a lifecycle `tone`/`status`, or the 574 §23.B
 * `originatorTone` one for an originator `origin` (agent/user/system) — so a badge can never carry an
 * off-palette colour on either axis.
 *
 * Scope (the §21/P3 AHA cut): this is the *status-indicator* badge only. The semantically-distinct
 * chips — `jf-provenance-chip` (attribution/trust), `jf-capability-pills` (capability state) — stay
 * their own components; they are not "a badge with a tone" and must not be collapsed into one.
 */
import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { type NoticeTone, statusToTone, toneText, toneAccentSoft } from '../utils/statusTone.js';
import { toOriginator, originatorAccent, originatorAccentSoft } from '../utils/originatorTone.js';

export class StatusBadge extends JfElement {
  static properties = {
    tone: { type: String },
    status: { type: String },
    origin: { type: String },
    label: { type: String },
  };

  declare tone?: NoticeTone;
  declare status?: string;
  /** Originator role (`agent`/`user`/`system`); when set, colour projects from the `originatorTone`
   *  authority instead of the status-tone one (the 574 §23.B originator axis). Named `origin` (not
   *  `role`) to avoid colliding with HTMLElement's native ARIA `role` IDL attribute. */
  declare origin?: string;
  declare label?: string;

  static styles = css`
    :host {
      display: inline-flex;
    }
    .badge {
      display: inline-flex;
      align-items: center;
      gap: 0.375rem;
      padding: 0.125rem 0.5rem;
      border-radius: 9999px;
      font-size: var(--font-size-xs);
      font-weight: 500;
      background: var(--badge-bg, var(--surface-2));
      color: var(--badge-fg, var(--text-secondary));
      /* Vivid tones read as a pill via their tinted fill (border transparent); the NEUTRAL case (status
         'neutral' / originator 'system') has a near-backdrop fill, so it gets a delineating border to
         still read as a badge rather than plain text (574 §23.C live-validation finding). */
      border: 1px solid var(--badge-border, transparent);
      white-space: nowrap;
    }
  `;

  override render(): TemplateResult {
    let bg: string;
    let fg: string;
    let neutral: boolean;
    if (this.origin) {
      const origin = toOriginator(this.origin);
      bg = originatorAccentSoft(origin);
      fg = originatorAccent(origin);
      neutral = origin === 'system';
    } else {
      const tone = this.tone ?? statusToTone(this.status);
      bg = toneAccentSoft(tone);
      fg = toneText(tone);
      neutral = tone === 'neutral';
    }
    const border = neutral ? 'var(--border-strong)' : 'transparent';
    return html`<span
      class="badge"
      part="badge"
      style=${`--badge-bg: ${bg}; --badge-fg: ${fg}; --badge-border: ${border}`}
    >
      <slot>${this.label ?? ''}</slot>
    </span>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-status-badge')) {
  customElements.define('jf-status-badge', StatusBadge);
}
