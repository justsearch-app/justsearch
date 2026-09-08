// SPDX-License-Identifier: Apache-2.0
/**
 * Control — tempdoc 559 Part II, Authority V (Operability): the one interactive-affordance primitive.
 *
 * THE single control every clickable affordance is built from, so operability —
 * keyboard activation, focus, role, accessible name — is a property of the
 * primitive, not something each component must remember to hand-roll (the defect:
 * a mouse-only `<div @click>` with no role/tabindex/keydown). It renders a NATIVE
 * `<button>`, which gives Enter/Space activation + focus + the `button` role for
 * free. The **accessible name projects from a declaration** (557 display
 * authority): pass an `operationId` and the name comes from
 * `present({kind:'operation', id})`; otherwise pass an explicit `label`. The
 * action is the `onActivate` callback (operation-backed callers pass an invoke).
 *
 * Appearance is the consumer's, not the primitive's: the button is exposed as
 * `part="control"` (style via `::part(control)`) and content via the default
 * `<slot>`. So a chip, an icon-only "…", and a text button are all the same
 * operable primitive with different skins — the bad state (an interactive element
 * that isn't keyboard-operable / has no name) is unrepresentable through it. Keeping
 * interaction handlers ON this primitive (never on a bare div) is the standing rule; the
 * static `controls-a11y` gate that used to scan for it was retired in 930 chunk H.
 *
 * Tempdoc 596 — typed AVAILABILITY (see the `availability` property): an unavailable
 * affordance renders `aria-disabled` + a REACHABLE reason and a NON-SILENT blocked
 * activation. §17: the reason-surface is a WCAG-1.4.13 tooltip (dismissable + hoverable +
 * collision-safe via the native Popover API), and the blocked-activation toast POINTS AT the
 * remedy carried on the availability (the one reason-and-remedy vocabulary).
 */
import { html, css, type TemplateResult, nothing } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { present } from '../display/present.js';
import { emitEphemeralToast } from './advisory/ephemeralToast.js';
import { type Availability, type NoticeRemedy } from '../state/availability.js';
import { recordBlockedAttempt } from '../state/availabilityTelemetry.js';
import { icon } from './Icon.js';

/** Shadow-scoped id for the accessible reason element (aria-describedby target). */
const REASON_ID = 'jf-ctrl-reason';

/** The native Popover API gives top-layer (no clip) + manual show/hide for a WCAG-1.4.13 tooltip. */
const POPOVER_OK =
  typeof HTMLElement !== 'undefined' && 'popover' in HTMLElement.prototype;

/**
 * Tempdoc 596 §16.2 polish — a short hover open-delay so a pointer passing OVER an unavailable control
 * on its way elsewhere doesn't flash the tooltip (a named tooltip-UX win). Keyboard focus opens with NO
 * delay (a focus is a deliberate landing, not a pass-through), so only the hover path is debounced.
 */
const HOVER_OPEN_DELAY_MS = 120;

export class Control extends JfElement {
  static properties = {
    label: { type: String },
    operationId: { type: String, attribute: 'operation-id' },
    disabled: { type: Boolean },
    availability: { attribute: false },
    pressed: { attribute: false },
    busy: { state: true },
    showDelayMs: { attribute: 'show-delay-ms', type: Number },
    minVisibleMs: { attribute: 'min-visible-ms', type: Number },
  };

  declare label: string;
  declare operationId: string | undefined;
  declare disabled: boolean;
  /**
   * Tempdoc 596 — typed availability (the AVAILABILITY half of the operability authority).
   * OPTIONAL and ADDITIVE: when unset, the legacy `disabled` boolean governs (the ~10 existing
   * hard-disabled consumers are untouched). When set, it supersedes `disabled`:
   *   - `blocked`     → native `disabled` (hard, inert) — same as `disabled=true`.
   *   - `unavailable` → `aria-disabled` (focusable, reason reachable via aria-describedby + a visible
   *                     tooltip), and an activation attempt surfaces the reason (and points at the
   *                     remedy) via the one message channel instead of silently no-op'ing.
   *   - `available`   → operable.
   */
  declare availability: Availability | undefined;
  /**
   * Tempdoc 840 — the TOGGLE-BUTTON arm of the primitive. OPTIONAL and TRI-STATE: left `undefined` the
   * button emits no `aria-pressed` at all (every existing consumer renders byte-identically), and set
   * it emits `true`/`false`.
   *
   * It exists because a switch whose state is carried by thumb position and fill — not by a changing
   * label — has nothing an assistive technology can read; WAI-ARIA's toggle-button pattern says that
   * state belongs in `aria-pressed`. The alternative (a name that changes with state, "Turn off X") is
   * also sanctioned, but it makes the visual and the accessible representation disagree about what the
   * control IS, so the state goes on the button where it can be observed either way.
   */
  declare pressed: boolean | undefined;
  /**
   * The action. A property (not an attribute) so callers pass a closure.
   *
   * Tempdoc 608 — command acknowledgement: activation is PROMISE-AWARE. If the closure returns a
   * thenable, the control tracks it and renders the `busy` overlay (spinner + `aria-disabled` + `aria-busy`,
   * focus-preserving) until it settles. A `void` return is unchanged (additive). The returned promise is the
   * caller-defined command (dispatch → observable result); the control owns *acknowledgement of* it, the
   * caller owns *what it is*. The overlay is gated by spin-delay timing (see `showDelayMs`/`minVisibleMs`).
   */
  onActivate: (() => void | Promise<unknown>) | null = null;
  /**
   * Tempdoc 608 — the in-flight acknowledgement overlay. INTERNAL control state (not a host-supplied
   * `availability` kind): it is computed from the activation promise the control tracks, composed with —
   * not added to — the steady-state `availability`. Reachable only from an operable activation (a blocked /
   * soft-unavailable control never fires `onActivate`), so `busy ⟸ was-operable` holds by construction.
   * It sits OUTSIDE the `transient` queue path (`resolveQueued`), so "already running, do not re-fire" is
   * structural — a non-idempotent command is never auto-replayed on settle.
   */
  declare busy: boolean;
  /**
   * Tempdoc 608 — spin-delay timing (the anti-flicker double-threshold; Nielsen + the `spin-delay` pattern).
   * `showDelayMs`: don't render the overlay until the command has been pending this long (a sub-threshold
   * command shows nothing — Nielsen: under ~1s no feedback is needed, so a flashed spinner is pure jank).
   * `minVisibleMs`: once shown, keep the overlay at least this long so it never flickers. Tunable per call
   * site; tests set both to 0 to isolate the state machine from timing.
   */
  declare showDelayMs: number;
  declare minVisibleMs: number;

  private hintOpen = false;
  private hideTimer: ReturnType<typeof setTimeout> | null = null;
  private showTimer: ReturnType<typeof setTimeout> | null = null;
  // Tempdoc 608 spin-delay timers + the timestamp the overlay actually became visible (for the min-visible
  // hold). `busyShowTimer` is the delay-before-show; `busyHideTimer` is the min-visible hold-then-clear.
  private busyShowTimer: ReturnType<typeof setTimeout> | null = null;
  private busyHideTimer: ReturnType<typeof setTimeout> | null = null;
  private busyShownAt = 0;
  /** Tempdoc 859 — the deferred accessible-name selfcheck's pending slot ({@link scheduleNameCheck}). */
  private nameCheckTimer: ReturnType<typeof setTimeout> | null = null;
  /**
   * Tempdoc 608 — the command-in-flight flag that arms the re-entrancy guard. DISTINCT from `busy` (the
   * VISUAL overlay): with spin-delay there is a window where a command is in flight but `busy` is still
   * false (pre-delay) — guarding on `busy` would let a double-click in that window re-fire. So the guard
   * keys on this flag, set synchronously the instant a command is dispatched and cleared on settle.
   */
  private busyInFlight = false;
  /**
   * Tempdoc 596 §16.5 — queue-and-auto-run. Set when the user activates a TRANSIENT-unavailable control
   * (still loading): instead of a dead no-op, we hold the intent and auto-fire `onActivate` the moment the
   * control becomes operable. Strictly transient-only — a SETTLED block (offline) never queues (that would
   * mislead). Re-firing replays the host's own closure, so it rides the same gated path a real click does.
   */
  private queued = false;

  constructor() {
    super();
    this.label = '';
    this.operationId = undefined;
    this.disabled = false;
    this.availability = undefined;
    this.pressed = undefined;
    this.busy = false;
    this.showDelayMs = 200; // spin-delay: skip the spinner for sub-200ms commands (no flash)
    this.minVisibleMs = 500; // spin-delay: once shown, hold ≥500ms so it never flickers
  }

  /**
   * Collapse `availability` (preferred) and the legacy `disabled` boolean into the one render
   * decision. `hardDisabled` → native disabled (inert); `softReason` → aria-disabled + reachable
   * reason + non-silent block; `caveat` (596 §16.4 degraded) → OPERABLE but a reachable caveat on the
   * same tooltip; `remedy` → the actionable fix to point at. `softReason`/`caveat` are mutually exclusive.
   */
  private effective(): {
    hardDisabled: boolean;
    softReason: string | null;
    caveat: string | null;
    transient: boolean;
    remedy: NoticeRemedy | null;
  } {
    const a = this.availability;
    if (a) {
      if (a.kind === 'blocked')
        return { hardDisabled: true, softReason: null, caveat: null, transient: false, remedy: null };
      if (a.kind === 'unavailable')
        return {
          hardDisabled: false,
          softReason: a.reason,
          caveat: null,
          transient: a.transient === true,
          remedy: a.remedy ?? null,
        };
      if (a.kind === 'degraded')
        // Operable (no hardDisabled, no softReason → onActivate fires), but the caveat is reachable on
        // the same hover/focus tooltip. The affordance works; it just warns about reduced quality.
        return { hardDisabled: false, softReason: null, caveat: a.caveat, transient: false, remedy: a.remedy ?? null };
      return { hardDisabled: false, softReason: null, caveat: null, transient: false, remedy: null };
    }
    return { hardDisabled: this.disabled, softReason: null, caveat: null, transient: false, remedy: null };
  }

  /** The single text the reason tooltip shows — the block reason OR the degraded caveat (mutually exclusive). */
  private reasonText(): string | null {
    const { softReason, caveat } = this.effective();
    return softReason ?? caveat;
  }

  static styles = css`
    :host {
      display: inline-flex;
      /* Anchor for the absolutely-positioned reason hint (no-popover fallback path). */
      position: relative;
    }
    /* Honor the global hidden attribute (the author :host display otherwise wins
       over the UA [hidden] rule). */
    :host([hidden]) {
      display: none;
    }
    button {
      all: unset;
      box-sizing: border-box;
      display: inline-flex;
      align-items: center;
      cursor: pointer;
      font: inherit;
      color: inherit;
    }
    button:disabled,
    button[aria-disabled='true'] {
      cursor: default;
      opacity: 0.5;
    }
    /* Keyboard focus indicator — operability is visible, not just functional. */
    button:focus-visible {
      outline: 2px solid var(--accent-tint);
      outline-offset: 2px;
      border-radius: 0.25rem;
    }
    /* Tempdoc 596 section 9.4/17 — the VISIBLE reason surface. A host SIBLING of the button (so the
       button's aria-disabled opacity:0.5 doesn't dim it), and always the aria-describedby target for
       AT. Two render paths:
       (1) Popover path (the default where supported): popover=manual gives top-layer (never clipped);
           JS positions it near the control and drives show/hide on hover/focus, with Esc to dismiss
           and a hover bridge (WCAG 1.4.13). It is opaque when open; the UA hides it until showPopover.
       (2) No-popover fallback: the absolutely-positioned span revealed by the host-hover CSS rule. */
    .reason-hint {
      position: absolute;
      bottom: calc(100% + 6px);
      left: 0;
      z-index: var(--z-overlay-transient);
      max-width: 16rem;
      padding: 0.2rem 0.45rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      background: var(--surface-3);
      color: var(--text-primary);
      font-size: var(--font-size-xs);
      white-space: nowrap;
      box-shadow: var(--shadow-lift);
      opacity: 0;
      pointer-events: none;
      transition: opacity var(--duration-fast) var(--ease-standard);
    }
    /* No-popover fallback reveal. */
    :host(:hover) .reason-hint,
    button:focus-visible ~ .reason-hint {
      opacity: 1;
    }
    /* Popover path — opaque + interactive (hoverable, 1.4.13) when shown; JS owns position/inset. */
    .reason-hint:popover-open {
      opacity: 1;
      pointer-events: auto;
      inset: auto;
      margin: 0;
    }
  `;

  /** Accessible name = a projection of the declaration (operationId) or the explicit label. */
  private resolvedName(): string {
    if (this.operationId) return present({ kind: 'operation', id: this.operationId }).label;
    return this.label ?? '';
  }

  // ── WCAG 1.4.13 tooltip controller (§17 Move B) — self-contained; a tooltip is point-wise, NOT a
  // single-open transient, so it deliberately does NOT compose TransientController (cf. HoverPreviewHost).
  // The tooltip is the HOVER/FOCUS reason surface. TOUCH has no hover: a tap on an unavailable control
  // hits the `aria-disabled` (not native-disabled, so still click-receiving) button → activate() → the
  // reason TOAST (with its remedy action). So the touch reason path is the non-silent activation, a
  // better touch affordance than a transient hover tooltip — no separate tap-to-reveal is needed (596 §16.2).

  private get hintEl(): (HTMLElement & { showPopover?: () => void; hidePopover?: () => void }) | null {
    return (this.renderRoot?.querySelector?.('.reason-hint') as HTMLElement) ?? null;
  }

  /** Hover entry: open after a short delay (debounced pass-through). Focus entry calls showHint directly. */
  private readonly scheduleShow = (): void => {
    if (!POPOVER_OK) return;
    if (this.reasonText() === null) return;
    if (this.showTimer) clearTimeout(this.showTimer);
    this.showTimer = setTimeout(this.showHint, HOVER_OPEN_DELAY_MS);
  };

  private readonly cancelShow = (): void => {
    if (this.showTimer) {
      clearTimeout(this.showTimer);
      this.showTimer = null;
    }
  };

  private readonly showHint = (): void => {
    if (this.showTimer) {
      clearTimeout(this.showTimer);
      this.showTimer = null;
    }
    if (!POPOVER_OK) return; // CSS fallback handles the reveal
    if (this.reasonText() === null) return;
    const el = this.hintEl;
    if (!el || typeof el.showPopover !== 'function') return;
    if (this.hideTimer) {
      clearTimeout(this.hideTimer);
      this.hideTimer = null;
    }
    try {
      if (!el.matches(':popover-open')) el.showPopover();
    } catch {
      return;
    }
    this.positionHint(el);
    this.hintOpen = true;
  };

  private readonly hideHint = (): void => {
    const el = this.hintEl;
    if (el && typeof el.hidePopover === 'function') {
      try {
        if (el.matches(':popover-open')) el.hidePopover();
      } catch {
        /* ignore */
      }
    }
    this.hintOpen = false;
  };

  private readonly scheduleHide = (): void => {
    if (!POPOVER_OK) return;
    this.cancelShow(); // leaving before the open-delay elapsed → cancel the pending open
    if (this.hideTimer) clearTimeout(this.hideTimer);
    this.hideTimer = setTimeout(this.hideHint, 140);
  };

  private readonly cancelHide = (): void => {
    if (this.hideTimer) {
      clearTimeout(this.hideTimer);
      this.hideTimer = null;
    }
  };

  /** Place the top-layer tooltip just above the control; flip below when there is no room (collision). */
  private positionHint(el: HTMLElement): void {
    const r = this.getBoundingClientRect();
    el.style.position = 'fixed';
    el.style.margin = '0';
    el.style.inset = 'auto';
    const h = el.offsetHeight || 26;
    const above = r.top - h - 6;
    el.style.top = `${Math.round(above >= 4 ? above : r.bottom + 6)}px`;
    el.style.left = `${Math.round(Math.max(4, r.left))}px`;
  }

  private readonly onKeyDown = (e: KeyboardEvent): void => {
    // WCAG 1.4.13 dismissable — Esc closes the tooltip without moving focus.
    if (e.key === 'Escape' && this.hintOpen) this.hideHint();
  };

  override connectedCallback(): void {
    super.connectedCallback();
    this.addEventListener('mouseenter', this.scheduleShow); // hover: debounced open (596 §16.2)
    this.addEventListener('mouseleave', this.scheduleHide);
    this.addEventListener('focusin', this.showHint); // focus: instant (a deliberate landing)
    this.addEventListener('focusout', this.scheduleHide);
    if (typeof document !== 'undefined') document.addEventListener('keydown', this.onKeyDown, true);
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.removeEventListener('mouseenter', this.scheduleShow);
    this.removeEventListener('mouseleave', this.scheduleHide);
    this.removeEventListener('focusin', this.showHint);
    this.removeEventListener('focusout', this.scheduleHide);
    if (typeof document !== 'undefined') document.removeEventListener('keydown', this.onKeyDown, true);
    this.cancelHide();
    this.cancelShow();
    this.queued = false; // 596 §16.5 — a queued intent does not survive the control leaving the DOM.
    // Tempdoc 608 — drop the spin-delay timers so neither fires a requestUpdate after the control is gone.
    if (this.busyShowTimer !== null) {
      clearTimeout(this.busyShowTimer);
      this.busyShowTimer = null;
    }
    if (this.busyHideTimer !== null) {
      clearTimeout(this.busyHideTimer);
      this.busyHideTimer = null;
    }
    // Tempdoc 859 — the deferred name check must not outlive the control either: a pending timer on a
    // torn-down element is the stray-async-after-teardown shape the timers above already refuse.
    if (this.nameCheckTimer !== null) {
      clearTimeout(this.nameCheckTimer);
      this.nameCheckTimer = null;
    }
    this.busy = false;
    this.busyInFlight = false;
  }

  override updated(): void {
    const el = this.hintEl as (HTMLElement & { dataset: DOMStringMap }) | null;
    if (el && !el.dataset.wired) {
      // Hover bridge (WCAG 1.4.13 hoverable): moving onto the tooltip keeps it open.
      el.dataset.wired = '1';
      el.addEventListener('mouseenter', this.cancelHide);
      el.addEventListener('mouseleave', this.scheduleHide);
    }
    // Became available again while a hint was open → close it.
    if (!el && this.hintOpen) this.hideHint();
    // 596 §16.5 — fire (or drop) a queued transient activation once the state has resolved.
    this.resolveQueued();
    this.scheduleNameCheck();
  }

  /**
   * The accessible name AS AN AT COMPUTES IT, not as one render frame happens to see it.
   *
   * <p>Two forms count beyond the declaration projection: the control's own light-DOM text, and text
   * projected THROUGH its light DOM by an outer slot. The second is what {@link JfButton} builds —
   * `<jf-control><slot></slot></jf-control>` — so the inner control's only light child is a `<slot>`
   * element, whose `textContent` is empty even when the button reads "Save". `assignedNodes` with
   * `flatten` resolves that chain the way the flat tree (and therefore the a11y tree) does.
   */
  private hasAccessibleName(): boolean {
    if (this.resolvedName() !== '') return true;
    if ((this.textContent ?? '').trim() !== '') return true;
    const slot = this.renderRoot?.querySelector?.('slot') as HTMLSlotElement | null;
    const assigned = slot?.assignedNodes?.({ flatten: true }) ?? [];
    return assigned.some((node) => (node.textContent ?? '').trim() !== '');
  }

  /**
   * 559 Authority V §11 — the typed seam at the Lit-template ceiling (557 §5): a control with no
   * resolvable name (no operationId, no label, no slot text) is the bad form. This runtime dev
   * signal (tree-shaken in production via `import.meta.env.DEV`) is the live check since the static
   * `controls-a11y` build gate was retired (930 chunk H); measured axe covers the rendered surfaces.
   *
   * <p>Tempdoc 859 (live console, 2026-08-25) — it used to run INSIDE `render()`, where it measured
   * a half-built element: a control whose `label` binding or slot text has not been committed yet
   * (a first update reached the primitive before its own attribute parts landed) is nameless for
   * exactly one frame, and the check shouted about it on every sv3 and unified-chat session even
   * though nothing was actually nameless. Reproduced in-suite: the offenders were `jf-button`'s
   * inner control (a name arriving through a slot) and a first-frame `<jf-control></jf-control>`
   * in the composer. So the check is DEFERRED past the render that scheduled it and re-reads the
   * name then — a control that is still nameless once the DOM has settled is the real defect, and
   * that one still reports.
   */
  private scheduleNameCheck(): void {
    if (!(import.meta as ImportMeta & { env?: { DEV?: boolean } }).env?.DEV) return;
    if (this.nameCheckTimer !== null) return; // one pending check per control, not one per render
    this.nameCheckTimer = setTimeout(() => {
      this.nameCheckTimer = null;
      // A control torn down before it settled never faced a reader; naming it would be noise.
      if (!this.isConnected) return;
      if (this.hasAccessibleName()) return;
      console.error(
        '[jf-control] no accessible name — set `operation-id`, a non-empty `label`, or slot text ' +
          '(559 Authority V §11: a nameless control is unrepresentable through the primitive).',
      );
    }, 0);
  }

  private dispatchRemedy(remedy: NoticeRemedy): void {
    // Navigate to where the remedy lives. For an `operation` remedy we point at Health (where the
    // op-button + its consent/risk ceremony live) rather than firing the op directly from a toast —
    // "point at the remedy" (§11.4), not bypass its ceremony.
    const target = remedy.kind === 'navigate' ? remedy.target : 'core.health-surface';
    this.dispatchEvent(
      new CustomEvent('navigate-with-context', {
        detail: { target, state: {} },
        bubbles: true,
        composed: true,
      }),
    );
  }

  private remedyToastAction(remedy: NoticeRemedy | null): {
    actionLabel?: string;
    onAction?: () => void;
  } {
    if (!remedy) return {};
    const label = remedy.kind === 'navigate' ? remedy.label : 'Open Health';
    return { actionLabel: label, onAction: () => this.dispatchRemedy(remedy) };
  }

  private readonly activate = (): void => {
    // Tempdoc 608 — re-entrancy guard: while a command is IN FLIGHT the control is inert (no second
    // dispatch). Keyed on `busyInFlight`, NOT the visual `busy`, so the guard holds even during the
    // spin-delay window where a command is running but the spinner has not yet appeared.
    if (this.busyInFlight) return;
    const { hardDisabled, softReason, transient, remedy } = this.effective();
    if (hardDisabled) return; // intent gate / legacy disabled — genuinely inert.
    if (softReason !== null) {
      // 596 §16.5 — the one block choke point: record the blocked attempt ("what blocks users").
      recordBlockedAttempt(softReason, transient);
      if (transient) {
        // 596 §16.5 — TRANSIENT (still loading): queue the intent and auto-fire when ready, instead of a
        // dead toast. The reason is still surfaced (non-silent), now with a "queued" promise.
        this.queued = true;
        emitEphemeralToast({
          message: `${softReason} — queued, runs when ready`,
          severity: 'info',
        });
        return;
      }
      // SETTLED block: an unavailable affordance is NEVER a silent no-op — surface the reason through the
      // one message channel (559 Authority III) and POINT AT the remedy (§17/§11.4) instead of dying.
      emitEphemeralToast({
        message: softReason,
        severity: 'warning',
        ...this.remedyToastAction(remedy),
      });
      return;
    }
    // Tempdoc 608 — promise-aware activation: capture the command. A thenable lights the `busy` overlay
    // until it settles (resolve OR reject); a `void` return is unchanged (additive). We only reach here from
    // an operable activation, so `busy ⟸ was-operable` and the overlay never appears on a blocked control.
    this.runActivation(this.onActivate);
  };

  /**
   * Tempdoc 608 — fire the activation closure and, if it returns a thenable, track it as `busy` until it
   * settles. Shared by the direct {@link activate} path and the queued-then-ready replay in
   * {@link resolveQueued}, so a queued command also acknowledges. The `busy` flip is what an async command
   * needs and a sync one never sees.
   */
  private runActivation(action: (() => void | Promise<unknown>) | null): void {
    const result = action?.();
    if (result == null || typeof (result as { then?: unknown }).then !== 'function') {
      return; // sync / void command — no acknowledgement overlay (additive: unchanged behaviour)
    }
    // Arm the re-entrancy guard NOW (synchronously) — before any spin-delay — so a second click while the
    // command is in flight but the spinner has not yet appeared cannot re-dispatch.
    this.busyInFlight = true;
    // Tempdoc 608 spin-delay: don't show the overlay immediately. Start a delay timer; only if the command
    // is STILL pending when it fires do we show `busy` (so a sub-`showDelayMs` command shows nothing — no
    // flash). `showDelayMs <= 0` shows synchronously (tests, or a known-slow call site).
    let settled = false;
    const show = (): void => {
      this.busyShowTimer = null;
      if (settled) return;
      this.busy = true;
      this.busyShownAt = Date.now();
      this.requestUpdate();
    };
    if (this.showDelayMs <= 0) {
      show();
    } else {
      this.busyShowTimer = setTimeout(show, this.showDelayMs);
    }
    // Clear on settle (resolve OR reject). The control owns only the overlay, NOT error handling — the
    // caller's own promise consumer surfaces failures — so the tracking chain swallows its own rejection
    // propagation (`.catch`) to avoid an unhandled-rejection.
    void Promise.resolve(result)
      .finally(() => {
        settled = true;
        this.busyInFlight = false; // command done → re-entrancy guard releases (visual `busy` may linger)
        if (this.busyShowTimer !== null) {
          // Settled before the delay elapsed → never showed; nothing to clear, no flash.
          clearTimeout(this.busyShowTimer);
          this.busyShowTimer = null;
          return;
        }
        if (!this.busy) return; // never shown (e.g. showDelayMs window already cancelled)
        // Shown: hold for at least minVisibleMs from when it appeared, so it can't flicker.
        const elapsed = Date.now() - this.busyShownAt;
        const remaining = this.minVisibleMs - elapsed;
        if (remaining <= 0) {
          this.clearBusy();
        } else {
          this.busyHideTimer = setTimeout(() => this.clearBusy(), remaining);
        }
      })
      .catch(() => {});
  }

  /** Tempdoc 608 — drop the busy overlay and its hide timer. */
  private clearBusy(): void {
    if (this.busyHideTimer !== null) {
      clearTimeout(this.busyHideTimer);
      this.busyHideTimer = null;
    }
    this.busy = false;
    this.requestUpdate();
  }

  /**
   * 596 §16.5 — resolve a queued transient activation. Called from `updated()` after every render: when the
   * control becomes operable, auto-fire the held intent; if it instead settles to a NON-transient block
   * (e.g. the model failed to load), drop the queue and say so (never silently swallow it).
   */
  private resolveQueued(): void {
    if (!this.queued) return;
    const { hardDisabled, softReason, transient, remedy } = this.effective();
    const operable = !hardDisabled && softReason === null; // available OR degraded → fire
    if (operable) {
      this.queued = false;
      this.runActivation(this.onActivate); // 608 — a queued command also acknowledges (busy) when it fires.
      emitEphemeralToast({ message: 'Ran your queued action', severity: 'success' });
    } else if (softReason !== null && !transient) {
      // Settled to a real block before it ever became ready → drop, with an honest rollback.
      this.queued = false;
      emitEphemeralToast({
        message: `Couldn't run — ${softReason}`,
        severity: 'warning',
        ...this.remedyToastAction(remedy),
      });
    }
    // still transient → keep waiting.
  }

  override render(): TemplateResult {
    const name = this.resolvedName();
    const { hardDisabled, softReason } = this.effective();
    // The tooltip text is the block reason OR the degraded caveat (596 §16.4). aria-disabled tracks the
    // BLOCK only — a degraded control stays operable — but the reachable reason surface covers both.
    const reason = this.reasonText();
    return html`<button
        part="control"
        type="button"
        ?disabled=${hardDisabled}
        aria-disabled=${softReason !== null || this.busy ? 'true' : nothing}
        aria-busy=${this.busy ? 'true' : nothing}
        aria-pressed=${this.pressed === undefined ? nothing : this.pressed ? 'true' : 'false'}
        aria-label=${name || nothing}
        aria-describedby=${reason !== null ? REASON_ID : nothing}
        @click=${this.activate}
      >
        ${/* Tempdoc 608 — command acknowledgement. While busy the control is `aria-disabled` (NOT native
             `disabled`), so it stays keyboard-focusable and the user keeps their place; the re-entrancy
             guard in activate() makes the still-clickable button a no-op (matches the 596 `unavailable`
             pattern). The shared loader-2 spinner (global `jf-spin`) is the visual acknowledgement; the
             visually-hidden role=status region is its polite AT equivalent (WCAG 2.1 SC 4.1.3). Both are
             gated by spin-delay, so a sub-threshold command shows/announces nothing. */ ''}
        ${this.busy ? icon({ name: 'loader-2', size: 14, spin: true }) : nothing}
        <slot>${name}</slot>
        <span class="visually-hidden" role="status" aria-live="polite"
          >${this.busy ? 'Working…' : ''}</span
        >
      </button>
      ${/* Tempdoc 596 §9.4/§17 — the visible+AT reason surface (a host SIBLING of the button). A
           popover (top-layer, collision-safe) where supported; the aria-describedby AT target always. */ ''}
      ${reason !== null
        ? html`<span
            id=${REASON_ID}
            role="tooltip"
            class="reason-hint"
            popover=${POPOVER_OK ? 'manual' : nothing}
            >${reason}</span
          >`
        : nothing}`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-control')) {
  customElements.define('jf-control', Control);
}
