// SPDX-License-Identifier: Apache-2.0
/**
 * TransientController — tempdoc 574 §22.F + §22.G (Move 4: transient single-open + dismiss, BY CONSTRUCTION).
 *
 * §16 S10: menus / popovers / the dismiss-triad (ContextMenu, Peek, BookmarksPopover,
 * SelectionActionsMenu) each hand-rolled the `registerTransient` + `closeOthersInLayer` +
 * `unregisterTransient` arbitration triad AND their own document outside-click + Escape dismiss listeners.
 * This controller bundles BOTH into the host lifecycle: composing it + calling {@link open}/{@link close}
 * is the ONLY wiring a transient needs. Arbitration (single-open) is always managed; the outside-click +
 * Escape dismiss is opt-in via {@link TransientOptions.managesDismiss} (a transient with its own native
 * light-dismiss — BookmarksPopover's Popover API — opts out). "A transient that does not arbitrate /
 * dismiss" is then unrepresentable for adopters (the `check-transient-arbitration` gate locks the catalog's
 * positive coverage). It is the §19.3 "DismissController" made real — the transient sibling of
 * {@link ModalController}.
 */
import type { ReactiveController, ReactiveControllerHost } from 'lit';
import {
  registerTransient,
  unregisterTransient,
  closeOthersInLayer,
} from '../state/transientLayerArbiter.js';

export interface TransientOptions {
  /** The arbiter layer (transients share `'transient'`). */
  layer: string;
  /** Stable id within the layer (e.g. `'peek'`, or a per-instance `context-menu-N`). */
  id: string;
  /** How this overlay closes ITSELF when a peer opens / a dismiss fires — the callback the arbiter + the
      dismiss handler invoke. */
  close: () => void;
  /**
   * When true, `open()` installs ONE shared capture-phase outside-`pointerdown` + `Escape` handler that
   * calls {@link close}; `close()`/`hostDisconnected` removes it. Default false — opt out for a transient
   * with its own native light-dismiss (e.g. the Popover API), which a shared handler would fight.
   */
  managesDismiss?: boolean;
  /**
   * Optional predicate over a dismiss `pointerdown`'s composed path (only consulted when `managesDismiss`):
   * return true to SUPPRESS the dismiss. For a transient opened BY an external control that lives outside the
   * host — e.g. AdvisoryInboxDrawer's rail badge — so a click on that control doesn't immediately re-close it.
   * Default undefined ⇒ every outside-host click dismisses (all current adopters unchanged).
   */
  dismissExclude?: (path: EventTarget[]) => boolean;
  /**
   * Optional (only consulted when `managesDismiss`): Escape asks this first; returning true means the
   * overlay handled the key itself (e.g. a panel stepping back from a nested view) and stays open.
   */
  escape?: () => boolean;
  /**
   * 574 §25 Edge 5 (the controller-shrink) — POPOVER MODE. When supplied, the controller drives this
   * element's native Popover API (the element must carry `popover="auto"`) for **single-open + light-dismiss
   * by the platform** instead of the arbiter + `managesDismiss` triad: {@link open} calls `showPopover()`
   * (which the UA light-dismisses on Escape / outside-click and closes when a peer auto-popover opens), and a
   * `toggle` listener syncs {@link close} back to the host when the UA closes it. The accessor returns the
   * popover element (typically a shadow-DOM panel), or null until rendered. The `transient` layer's overlays
   * use this. Mutually exclusive with `managesDismiss`.
   */
  popoverEl?: () => HTMLElement | null;
}

export class TransientController implements ReactiveController {
  /** The Lit host element (for the "is this pointerdown inside ME?" composedPath check). */
  private readonly hostEl: Element;
  private dismissActive = false;

  private readonly onPointerDown = (e: Event): void => {
    const path = e.composedPath();
    if (this.opts.dismissExclude?.(path)) return;
    if (!path.includes(this.hostEl)) this.opts.close();
  };
  private readonly onKeyDown = (e: KeyboardEvent): void => {
    if (e.key === 'Escape' && !this.opts.escape?.()) this.opts.close();
  };

  /** Popover mode: the UA closed the popover (Escape / outside-click / peer-open / our hidePopover) → sync
   *  the host's open state. `opts.close` is idempotent for every adopter, so firing it on our own close is
   *  harmless. */
  /** The popover element the `toggle` listener is currently bound to (re-bound when the host re-creates the
   *  popover element across open/close cycles — a boolean flag would leave a re-rendered panel unlistened). */
  private boundPopoverEl: HTMLElement | null = null;
  private readonly onToggle = (e: Event): void => {
    if ((e as Event & { newState?: string }).newState === 'closed') this.opts.close();
  };

  constructor(
    host: ReactiveControllerHost,
    private readonly opts: TransientOptions,
  ) {
    host.addController(this);
    this.hostEl = host as unknown as Element;
  }

  private get popoverMode(): boolean {
    return typeof this.opts.popoverEl === 'function';
  }

  /**
   * Call when this overlay OPENS. Popover mode (574 §25 Edge 5): `showPopover()` — the platform gives
   * single-open (a peer auto-popover opening closes this) + Escape/outside-click light-dismiss by
   * construction, so NO arbiter registration + NO `managesDismiss` listeners. Arbiter mode (unchanged):
   * register its closer, close any open peer, and — when `managesDismiss` — install the shared dismiss.
   */
  open(): void {
    if (this.popoverMode) {
      const el = this.opts.popoverEl!();
      if (!el) return;
      if (this.boundPopoverEl !== el) {
        this.boundPopoverEl?.removeEventListener('toggle', this.onToggle);
        el.addEventListener('toggle', this.onToggle);
        this.boundPopoverEl = el;
      }
      try {
        if (!el.matches(':popover-open')) el.showPopover();
        // Move focus INTO the popover so Escape light-dismiss fires (a programmatically-shown popover does
        // not auto-focus) and for a11y. Baseline: focus the popover element itself (made programmatically
        // focusable); an overlay that wants a specific item focused overrides this after open().
        if (!el.hasAttribute('tabindex')) el.setAttribute('tabindex', '-1');
        el.focus({ preventScroll: true });
      } catch {
        /* WebViews where showPopover throws (no Top Layer) — the host's own display still renders it. */
      }
      return;
    }
    registerTransient(this.opts.layer, this.opts.id, this.opts.close);
    closeOthersInLayer(this.opts.layer, this.opts.id);
    if (this.opts.managesDismiss && !this.dismissActive && typeof document !== 'undefined') {
      this.dismissActive = true;
      document.addEventListener('pointerdown', this.onPointerDown, true);
      document.addEventListener('keydown', this.onKeyDown, true);
    }
  }

  /** Call when this overlay CLOSES itself. Popover mode: `hidePopover()` (the `toggle` listener then syncs
   *  `opts.close`). Arbiter mode: forget it + tear down the dismiss listeners. */
  close(): void {
    if (this.popoverMode) {
      const el = this.opts.popoverEl!();
      try {
        if (el && el.matches(':popover-open')) el.hidePopover();
      } catch {
        /* tolerated */
      }
      return;
    }
    unregisterTransient(this.opts.layer, this.opts.id);
    this.removeDismiss();
  }

  hostDisconnected(): void {
    if (this.popoverMode) {
      const el = this.opts.popoverEl?.() ?? null;
      this.boundPopoverEl?.removeEventListener('toggle', this.onToggle);
      this.boundPopoverEl = null;
      try {
        if (el && el.matches(':popover-open')) el.hidePopover();
      } catch {
        /* tolerated */
      }
      return;
    }
    // A transient torn down while open must not leak a stale closer or document listeners.
    unregisterTransient(this.opts.layer, this.opts.id);
    this.removeDismiss();
  }

  private removeDismiss(): void {
    if (!this.dismissActive || typeof document === 'undefined') return;
    this.dismissActive = false;
    document.removeEventListener('pointerdown', this.onPointerDown, true);
    document.removeEventListener('keydown', this.onKeyDown, true);
  }
}
