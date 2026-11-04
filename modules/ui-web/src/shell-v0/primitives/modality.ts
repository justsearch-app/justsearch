// SPDX-License-Identifier: Apache-2.0
/**
 * ModalityController — tempdoc 574 Move 4 (the runtime-modality authority).
 *
 * The ONE place modal background-behaviour lives. §16 S9 / P5 found modal hosts each hand-rolled
 * (or omitted) the modality contract: scroll-lock was MISSING everywhere (0 sites), and focus-restore
 * was copy-pasted in 3 of 6 hosts (ElicitHost / EffectAuditLog / MacroDryRun, each commented
 * "residue #5") and absent in the rest. This controller makes the contract one authority every modal
 * host composes, so "a modal that doesn't lock background scroll / doesn't restore focus" is no longer
 * something each host must remember.
 *
 * Contract on {@link enter}: (a) save the currently-focused element; (b) lock background scroll
 * (reference-counted, so stacked modals release the lock only when the last closes). On {@link exit}:
 * release the scroll-lock and restore focus to the saved element. Native `<dialog>.showModal()` already
 * provides the background-`inert` + focus-trap + top-layer for the dialog-based hosts, so this
 * controller deliberately owns only the two facets the platform does NOT: scroll-lock + focus-restore
 * (the residue-#5 fix). Non-dialog hosts that need inert/trap should move to native `<dialog>` (the
 * structural fix that also drops their hand-picked z-index — S4), not re-hand-roll it here.
 *
 * Tempdoc 864 Layer 2(d) — the same depth count is ALSO the app's answer to "does a modal own the
 * screen right now?" ({@link modalOwnsFocus}). It was already the only thing in the app that knew,
 * and a global-key handler that asked a second source would be the fork §2.2 is about.
 */
import type { ReactiveController, ReactiveControllerHost } from 'lit';

/**
 * How many modals are currently entered. ONE counter, two consumers: the background scroll-lock
 * (release on the 1→0 edge, so stacked modals do not unlock early) and {@link modalOwnsFocus}.
 * Counted independently of `document` so a DOM-less environment still reports modality honestly.
 */
let openModalCount = 0;
let savedHtmlOverflow: string | null = null;

/** Lock background scroll (reference-counted across stacked modals). */
function acquireScrollLock(): void {
  if (openModalCount === 0 && typeof document !== 'undefined') {
    savedHtmlOverflow = document.documentElement.style.overflow;
    document.documentElement.style.overflow = 'hidden';
  }
  openModalCount++;
}

/** Release one scroll-lock reference; restore overflow when the last modal closes. */
function releaseScrollLock(): void {
  if (openModalCount === 0) return;
  openModalCount--;
  if (openModalCount === 0 && typeof document !== 'undefined') {
    document.documentElement.style.overflow = savedHtmlOverflow ?? '';
    savedHtmlOverflow = null;
  }
}

/**
 * Tempdoc 864 Layer 2(d) — **the modal-owns-focus predicate**: is a modal currently entered?
 *
 * The guard every global key handler owes an open modal, and the one the guard set did NOT have
 * (§2.3). The live 2026-08-25 repro (leg L10): with the command palette open and focus on a control
 * inside it, a bare `j` fell through to `Sv3Main`'s window listener — which passed every guard it
 * has, because a non-editable control is not a typing target — and pulled real focus into the
 * transcript *underneath* the open palette, scrolling it to the top. Focus was then outside the
 * popup, so the palette's own Tab trap never saw the next `Tab` and focus walked out to `<body>`.
 *
 * Deliberately a state predicate rather than a DOM scan: walking every shadow root for
 * `[aria-modal]` on each keystroke is both expensive and a second answer to a question this module
 * already owns. Non-blocking dialogs (`ModalController.open({ nonBlocking: true })`) never enter, so
 * they do not claim the keyboard — which is what "non-blocking" means.
 */
export function modalOwnsFocus(): boolean {
  return openModalCount > 0;
}

/**
 * How many modals are entered — for the one window that is itself a place in history (Settings):
 * when it is the only modal, Back and Forward still apply to it as browser Back and Forward do.
 */
export function openModalDepth(): number {
  return openModalCount;
}

/**
 * Test seam: drop every entered modality. Module state outlives a test's DOM, so a case that leaves
 * a modal open would otherwise make the NEXT case's global keys silently inert.
 */
export function __resetModalityForTest(): void {
  openModalCount = 0;
  savedHtmlOverflow = null;
  if (typeof document !== 'undefined') document.documentElement.style.overflow = '';
}

export class ModalityController implements ReactiveController {
  private active = false;
  private savedFocus: HTMLElement | null = null;

  constructor(host: ReactiveControllerHost) {
    host.addController(this);
  }

  /** Call when the modal opens: save focus + lock background scroll. Idempotent. */
  enter(): void {
    if (this.active) return;
    this.active = true;
    this.savedFocus =
      typeof document !== 'undefined' ? (document.activeElement as HTMLElement | null) : null;
    acquireScrollLock();
  }

  /**
   * Call when the modal closes: release scroll-lock + restore focus. Idempotent.
   *
   * `skipFocusRestore` (855 §11.2 merge-blocker): a navigation-initiated close (the address has
   * already moved to a different stage surface — Shell's dismiss-on-realized-stage-navigation) must
   * NOT restore focus to the pre-modal invoker, because that invoker is no longer the rightful focus
   * destination for the surface the user is now on. Scroll-lock release is unconditional either way —
   * only the WAI-ARIA restore-to-invoker behaviour is what a navigation should skip.
   */
  exit(opts?: { skipFocusRestore?: boolean }): void {
    if (!this.active) return;
    this.active = false;
    releaseScrollLock();
    // Restore focus to the element that had it before the modal opened (the residue-#5 fix:
    // native <dialog> only auto-restores for invoker clicks, not property-driven opens).
    const target = this.savedFocus;
    this.savedFocus = null;
    if (opts?.skipFocusRestore) return;
    if (target && typeof target.focus === 'function' && target.isConnected) {
      target.focus();
    }
  }

  hostDisconnected(): void {
    // A modal torn down while open must not leak the scroll-lock.
    this.exit();
  }
}
