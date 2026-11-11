// SPDX-License-Identifier: Apache-2.0
/**
 * <jf-autonomy-dial> — Tempdoc 543 §32 U1 (Autonomy Dial).
 *
 * A 3-position segmented control for the agent autonomy level
 * (watch / assist / auto). Reads/writes the autonomy substrate; the agent
 * invocation path (`invokeAndApply`) consults it to decide propose-vs-dispatch.
 * NOT an accept-count learner — the destructive-op gate is backend-enforced
 * (§32.9), and this dial only tunes the FE's proactive oversight.
 */

import { SETTINGS_CONTROL_LABELS } from '../views/settingsControlLabels.js';
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import {
  getAutonomyLevel,
  setAutonomyLevel,
  listAutonomyLevels,
  subscribeAutonomy,
  hasAutoConsent,
  setAutoConsent,
  type AutonomyLevel,
} from '../substrates/autonomy/index.js';
// Search Thread S7 (tempdoc decision 6) — the ONE modal primitive (ModalController, composed
// inside ConfirmDialog); reused via its Promise-based helper rather than authoring a new modal.
import { confirmAsync } from './ConfirmDialog.js';

const LABELS: Record<AutonomyLevel, string> = {
  watch: SETTINGS_CONTROL_LABELS['agent-autonomy'].watch,
  assist: SETTINGS_CONTROL_LABELS['agent-autonomy'].assist,
  auto: SETTINGS_CONTROL_LABELS['agent-autonomy'].auto,
};

// Honest copy: the three levels are genuinely distinct (keyed on whether the
// agent effect calls the backend), and confirmation of destructive actions is
// always enforced by the BACKEND trust lattice regardless of this dial.
const HINTS: Record<AutonomyLevel, string> = {
  watch: 'Every agent action waits in the queue for your approval.',
  assist:
    'Agent operates the UI freely; backend actions wait for your one-click approval.',
  auto: 'Agent acts freely; the backend still confirms destructive actions.',
};

export class AutonomyDial extends JfElement {
  static properties = {
    level: { state: true },
    compact: { type: Boolean, reflect: true },
  };

  declare level: AutonomyLevel;
  /**
   * Compact variant: renders just the 3-segment control (no title/hint block),
   * with the per-segment hint surfaced via the `title=` tooltip. Used inline in
   * the agent surface header; the full dial is used in Settings. Both share the
   * same autonomy substrate state.
   */
  declare compact: boolean;

  private unsub: (() => void) | null = null;
  /**
   * Search Thread Round-2 R3 — consent is required for the STANDING 'auto' state, not just the
   * transition: a profile already at 'auto' with no recorded consent (grandfathered / a pre-release
   * dev profile) is asked ONCE on the first interaction with the dial, not on every render. This
   * plain (non-reactive) field guards that "once": set on the first {@link select} call, so a later
   * click never re-checks even if consent is still somehow missing.
   */
  private standingConsentChecked = false;

  constructor() {
    super();
    this.level = getAutonomyLevel();
    this.compact = false;
  }

  connectedCallback(): void {
    super.connectedCallback();
    this.level = getAutonomyLevel();
    this.unsub = subscribeAutonomy(() => {
      this.level = getAutonomyLevel();
    });
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsub?.();
    this.unsub = null;
  }

  static styles = css`
    .dial {
      display: flex;
      flex-direction: column;
      gap: 0.375rem;
      font-size: var(--font-size-sm);
      color: var(--text-primary);
    }
    .title {
      font-weight: 600;
    }
    .segments {
      display: inline-flex;
      border: 1px solid var(--border-default);
      border-radius: 0.375rem;
      overflow: hidden;
      width: fit-content;
    }
    .seg {
      background: var(--surface-1);
      color: var(--text-secondary);
      border: none;
      border-right: 1px solid var(--border-default);
      padding: 0.3rem 0.85rem;
      font: inherit;
      font-size: var(--font-size-xs);
      cursor: pointer;
    }
    .seg:last-child {
      border-right: none;
    }
    .seg:hover {
      background: var(--surface-2);
    }
    .seg[data-active] {
      background: var(--accent-info);
      /* 559 contrast-pair token: white on the light-teal accent failed WCAG AA (1.97:1); the
         on-tint ink is dark in the dark theme / near-white in the light theme (>=4.5:1). */
      color: var(--accent-on-tint);
      font-weight: 600;
    }
    .hint {
      color: var(--text-tertiary);
      font-size: var(--font-size-xs);
    }
  `;

  /**
   * Search Thread S7 (tempdoc decision 6) — the FIRST switch to Auto (per browser profile,
   * `hasAutoConsent`) opens a consent modal naming what Auto may do (mirrors postureChrome(3)'s
   * honest "confirming irreversible writes" framing); Confirm persists the flag and applies the
   * level, Cancel applies nothing — the dial simply never optimistically flips, so there is
   * nothing to revert. Every later switch to Auto is silent.
   */
  private async select(level: AutonomyLevel): Promise<void> {
    // Search Thread Round-2 R3 — the FIRST interaction with the dial checks the STANDING state, not
    // just this click's transition: a dial already sitting on 'auto' with no recorded consent asks
    // once, right now, before processing the click that triggered it. Confirm persists the flag
    // (the dial stays on 'auto', silently); Cancel is a DOWNGRADE ("not consented" must not leave the
    // profile standing on 'auto') — the maintainer-recommended semantics, distinct from the ordinary
    // transition-cancel below (which just declines to move OFF a lower level).
    if (!this.standingConsentChecked) {
      this.standingConsentChecked = true;
      if (this.level === 'auto' && !hasAutoConsent()) {
        const ok = await confirmAsync({
          title: 'Confirm Auto mode?',
          message:
            'This profile is set to Auto mode, which acts without asking for your approval on each step. Irreversible writes still confirm.',
          confirmLabel: 'Keep Auto',
          cancelLabel: 'Switch to Assist',
          variant: 'info',
        });
        if (ok) {
          setAutoConsent();
        } else {
          setAutonomyLevel('assist');
        }
        return;
      }
    }
    if (level === 'auto' && this.level !== 'auto' && !hasAutoConsent()) {
      const ok = await confirmAsync({
        title: 'Turn on Auto mode?',
        message:
          'Auto mode acts without asking for your approval on each step. Irreversible writes still confirm.',
        confirmLabel: 'Turn on Auto',
        cancelLabel: 'Cancel',
        variant: 'info',
      });
      if (!ok) return;
      setAutoConsent();
    }
    setAutonomyLevel(level);
  }

  render(): TemplateResult {
    return html`
      <div class="dial" role="radiogroup" aria-label="Agent autonomy">
        ${this.compact
          ? nothing
          : html`<span class="title">Agent autonomy</span>`}
        <div class="segments">
          ${listAutonomyLevels().map(
            (l) => html`<button
              class="seg"
              role="radio"
              aria-checked=${this.level === l}
              ?data-active=${this.level === l}
              data-testid="autonomy-${l}"
              title=${HINTS[l]}
              @click=${() => this.select(l)}
            >
              ${LABELS[l]}
            </button>`,
          )}
        </div>
        ${this.compact
          ? nothing
          : html`<span class="hint" data-testid="autonomy-hint"
              >${HINTS[this.level]}</span
            >`}
      </div>
    `;
  }
}

if (!customElements.get('jf-autonomy-dial')) {
  customElements.define('jf-autonomy-dial', AutonomyDial);
}
