// SPDX-License-Identifier: Apache-2.0
/**
 * Button (`jf-button`) + `buttonBaseStyles` — tempdoc 574 Move 3 (the visual-atom tier). @atom
 *
 * The ONE skinned button atom. §14 V2 found the button *look* re-authored ~215× across 53 files,
 * with the base block (`display:inline-flex;align-items:center;gap;border;background:var(--surface-*)`)
 * re-declared 8×+ identically — because `jf-control` deliberately owns operability but NOT appearance
 * (it exposes `part="control"`). This atom closes that gap: it COMPOSES `jf-control` (keyboard,
 * focus, role, accessible-name — operability for free, the proven ProvenanceBadge pattern) and skins
 * the native button via `::part(control)` with token-driven variants. So a button is operable AND
 * visually consistent by construction; the bad states (a mouse-only div, an off-token button) are
 * unrepresentable through it.
 *
 * `buttonBaseStyles` is exported for the migration interim (a hand-authored `<button>` can adopt the
 * shared base before it becomes a full `jf-button`). New buttons should use `jf-button`.
 *
 * **Accessible-name rule (WCAG 2.5.3 "label in name"):** `label` sets `aria-label`, which OVERRIDES the
 * slot text as the accessible name. So set `label` ONLY on icon-only buttons (`size="icon"`, no visible
 * text). For a button with visible slot text, DO NOT set `label` — the slot text IS the accessible name,
 * and it must equal/contain the visible text. (Setting a `label` shorter than the visible text — e.g. a
 * stable `"Export selected"` against a visible `"Export selected (3)"` — drops the count from the name and
 * fails 2.5.3 for voice-control users.)
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import type { Availability } from '../state/availability.js';
import './Control.js';

/** The shared button base look (574 Move 3) — token-driven; the 8×-duplicated base, once. */
export const buttonBaseStyles = css`
  .jf-btn,
  ::part(control) {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 0.4rem;
    padding: 0.4rem 0.75rem;
    border: 1px solid var(--border-subtle);
    border-radius: 0.5rem;
    background: var(--surface-2);
    color: var(--text-primary);
    font-size: var(--font-size-sm);
    font-weight: 500;
    cursor: pointer;
    transition: background var(--duration-fast) var(--ease-standard),
      border-color var(--duration-fast) var(--ease-standard);
  }
`;

type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';
type ButtonSize = 'sm' | 'md' | 'icon';
/**
 * 574 B (remediation) — the SOLID high-emphasis CTA tone, projected from the 565 `statusTone`
 * authority (the same axis `jf-status-dot` / `jf-status-badge` read), so the atom tier has ONE
 * tone→accent mapping. `tone` is the high-emphasis CTA fill a confirm/submit needs and that the
 * UI-intent `variant` set deliberately does not carry (its `danger` is a *soft* tint). When set,
 * `tone` wins over `variant`. This is what folds the per-dialog bespoke CTA colours (ElicitHost's
 * solid-green submit, ConfirmDialog's solid risk-tone confirm) onto a single authority instead of
 * each dialog hand-picking `var(--accent-*)`.
 */
type ButtonTone = 'success' | 'warning' | 'error' | 'info';

export class JfButton extends JfElement {
  static properties = {
    variant: { type: String, reflect: true },
    tone: { type: String, reflect: true },
    size: { type: String, reflect: true },
    label: { type: String },
    operationId: { type: String, attribute: 'operation-id' },
    disabled: { type: Boolean, reflect: true },
    availability: { attribute: false },
  };

  declare variant: ButtonVariant;
  /** Solid high-emphasis CTA tone (success/warning/error/info), projected from statusTone; wins over `variant`. */
  declare tone?: ButtonTone;
  /** `sm` = compact; `icon` = square, centred icon-only (the `label` still names it for a11y). */
  declare size: ButtonSize;
  declare label?: string;
  declare operationId?: string;
  declare disabled: boolean;
  /**
   * Tempdoc 596 — typed availability, forwarded verbatim to the composed `jf-control` (which owns
   * the operability authority: reachable reason + non-silent block). ADDITIVE: when unset, the legacy
   * `disabled` boolean governs. When set, it supersedes `disabled` (jf-control decides), and the host
   * is kept interactive for the `unavailable` kind so the click reaches jf-control's reason toast.
   */
  declare availability?: Availability;
  /**
   * The action (a closure, mirroring jf-control). Tempdoc 608 — promise-aware: if the closure returns a
   * thenable, the composed `jf-control` tracks it and shows the in-flight `busy` overlay until it settles.
   * Forwarded verbatim to `jf-control`; a `void` return is unchanged.
   */
  onActivate: (() => void | Promise<unknown>) | null = null;

  constructor() {
    super();
    this.variant = 'secondary';
    this.size = 'md';
    this.disabled = false;
  }

  static styles = [
    buttonBaseStyles,
    css`
      :host {
        display: inline-flex;
      }
      jf-control::part(control):hover {
        background: var(--surface-3);
      }
      :host([variant='primary']) jf-control::part(control) {
        background: var(--accent-tint);
        border-color: transparent;
        color: var(--accent-on-tint);
      }
      @media (forced-colors: active) {
        :host([variant='primary']) jf-control::part(control) {
          background: ButtonFace;
          color: ButtonText;
        }
      }
      :host([variant='ghost']) jf-control::part(control) {
        background: transparent;
        border-color: transparent;
      }
      :host([variant='danger']) jf-control::part(control) {
        background: var(--accent-danger-16);
        border-color: transparent;
        color: var(--text-danger);
      }
      /* Solid CTA tones — the ONE tone→accent fill (565 statusTone), high-emphasis (solid bg +
         on-accent text). \`tone\` overrides \`variant\` (later rules / equal specificity win). */
      :host([tone='success']) jf-control::part(control) {
        background: var(--accent-success);
        border-color: transparent;
        color: var(--accent-on-success);
      }
      :host([tone='warning']) jf-control::part(control) {
        background: var(--accent-warning);
        border-color: transparent;
        color: var(--accent-on-warning);
      }
      :host([tone='error']) jf-control::part(control) {
        background: var(--accent-danger);
        border-color: transparent;
        color: var(--accent-on-danger);
      }
      :host([tone='info']) jf-control::part(control) {
        background: var(--accent-tint);
        border-color: transparent;
        color: var(--accent-on-tint);
      }
      :host([tone]) jf-control::part(control):hover {
        filter: brightness(1.08);
      }
      :host([size='sm']) jf-control::part(control) {
        padding: 0.2rem 0.5rem;
        font-size: var(--font-size-xs);
      }
      :host([size='icon']) jf-control::part(control) {
        padding: 0.4rem;
        aspect-ratio: 1;
        width: 2rem;
        gap: 0;
      }
      :host([disabled]) {
        opacity: 0.5;
        pointer-events: none;
      }
    `,
  ];

  /** Delegate focus to the composed native <button> (custom-element hosts aren't focusable). */
  override focus(options?: FocusOptions): void {
    const inner = this.shadowRoot
      ?.querySelector('jf-control')
      ?.shadowRoot?.querySelector('button');
    (inner as HTMLButtonElement | null | undefined)?.focus(options);
  }

  override render(): TemplateResult {
    const av = this.availability;
    return html`<jf-control
      exportparts="control"
      .label=${this.label ?? ''}
      operation-id=${this.operationId ?? nothing}
      ?disabled=${av ? false : this.disabled}
      .availability=${av}
      .onActivate=${this.onActivate}
      ><slot></slot
    ></jf-control>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-button')) {
  customElements.define('jf-button', JfButton);
}
