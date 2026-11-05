// SPDX-License-Identifier: Apache-2.0
/**
 * FocusKeeper — keyboard focus never falls to the page when a list renders under it (L21).
 *
 * Browsers drop focus to the page body when the focused control is moved (a keyed list reorders) or
 * removed (its item left the list, a confirmed delete). The host's renders cause that, so the host
 * puts it right: after the host and its nested renderers settle, focus returns to the same control
 * when it is still there; otherwise to its nearest neighbour that is — the next row's control, else
 * the previous one's; else the host's fallback (the list's heading or its add button). For a control
 * inside an in-place confirmation band, the neighbour is taken from the control that opened the band.
 *
 * It never moves focus the person moved: a loss with a new focus target, a focus already elsewhere
 * when the host settles, or a press elsewhere on a control that is still in place are left alone.
 */
import type { ReactiveController, ReactiveControllerHost } from 'lit';
import { deepActiveElement } from '../utils/keyboardHandler.js';

const FOCUSABLE = ':is(button, [href], input, select, textarea, [tabindex]):not([tabindex="-1"])';

/** Every focusable control under `root` in tree order, through open shadow roots. */
function focusables(root: ParentNode, out: HTMLElement[] = []): HTMLElement[] {
  for (const el of root.querySelectorAll<HTMLElement>('*')) {
    if (el.matches(FOCUSABLE)) out.push(el);
    if (el.shadowRoot) focusables(el.shadowRoot, out);
  }
  return out;
}

const pageHasFocus = (): boolean => {
  const active = deepActiveElement();
  return !active || active === document.body || active === document.documentElement;
};

/** Focus a control that is shown and enabled (a custom control may delegate inward); true when it took focus. */
function tryFocus(el: HTMLElement | null | undefined): boolean {
  if (!el?.isConnected || el.matches(':disabled') || el.closest('[inert]') || el.getClientRects().length === 0) return false;
  el.focus({ preventScroll: true });
  return !pageHasFocus();
}

/**
 * A control's identity across renders that replace elements: what it is (its `aria-controls`, `label`
 * or `aria-label`) and where (the nearest enclosing `id`, such as a folder's details). Empty when it
 * names nothing.
 */
function identity(el: HTMLElement): string {
  let name: string | null = null;
  for (let at: Node | null = el; at; at = at.parentNode ?? (at instanceof ShadowRoot ? at.host : null)) {
    if (!(at instanceof Element)) continue;
    name ??= at.getAttribute('aria-controls') ?? at.getAttribute('label') ?? at.getAttribute('aria-label');
    if (at !== el && at.id) return name ? `${at.id}|${name}` : '';
  }
  return name ?? '';
}

/** Whether `node` is inside `container`, across shadow roots. */
function inside(node: Node, container: Node): boolean {
  for (let at: Node | null = node; at; at = at.parentNode ?? (at instanceof ShadowRoot ? at.host : null)) {
    if (at === container) return true;
  }
  return false;
}

interface Snapshot {
  readonly element: HTMLElement;
  /** Where its neighbours are counted from: the band's opener for a control inside a band. */
  readonly anchor: HTMLElement;
  /** Controls to skip as neighbours (the band's own answers). */
  readonly within: Element | null;
  readonly order: readonly HTMLElement[];
}

export class FocusKeeper implements ReactiveController {
  private snapshot: Snapshot | null = null;
  /** The latest loss being put right; a newer loss or a focus elsewhere replaces it. */
  private loss: Snapshot | null = null;
  private pressedAt = -Infinity;

  constructor(
    private readonly host: ReactiveControllerHost & HTMLElement,
    private readonly fallback: () => HTMLElement | null | undefined = () => null,
  ) {
    host.addController(this);
  }

  /**
   * Focus moving between two controls inside the host's shadow tree never reaches the host element (both
   * ends retarget to it), so the keeper listens on the shadow root itself.
   */
  private get root(): ParentNode & EventTarget {
    return this.host.shadowRoot ?? this.host;
  }

  hostConnected(): void {
    this.root.addEventListener('focusin', this.onFocusIn as EventListener, true);
    this.root.addEventListener('focusout', this.onFocusOut as EventListener, true);
    document.addEventListener('pointerdown', this.onPress, true);
  }

  hostDisconnected(): void {
    this.root.removeEventListener('focusin', this.onFocusIn as EventListener, true);
    this.root.removeEventListener('focusout', this.onFocusOut as EventListener, true);
    document.removeEventListener('pointerdown', this.onPress, true);
    this.snapshot = null;
  }

  private readonly onPress = (): void => { this.pressedAt = performance.now(); };

  /**
   * The focused control, where its neighbours are counted from, and every control in order: as read
   * at the last focus inside this root when that still places the anchor (a render may already have
   * removed it), else as it is now.
   */
  private snapshotOf(event: FocusEvent): Snapshot | null {
    const path = event.composedPath();
    const element = path[0];
    if (!(element instanceof HTMLElement)) return null;
    const band = path.find((node): node is HTMLElement & { opener?: HTMLElement | null } =>
      node instanceof HTMLElement && node.localName === 'jf-confirm-band');
    const anchor = band?.opener ?? element;
    const earlier = this.snapshot?.order;
    return { element, anchor, within: band ?? null,
      order: earlier?.includes(anchor) && !anchor.isConnected ? earlier : focusables(this.root) };
  }

  private readonly onFocusIn = (event: FocusEvent): void => {
    this.snapshot = this.snapshotOf(event);
    this.loss = null;
  };

  private readonly onFocusOut = (event: FocusEvent): void => {
    if (event.relatedTarget) return;
    // A move inside a nested component (an action's own band) never reached this root as a focusin:
    // the list is read now, while the control that lost focus is still in it.
    const snapshot = this.snapshot?.element === event.composedPath()[0] ? this.snapshot : this.snapshotOf(event);
    if (!snapshot) return;
    this.loss = snapshot;
    // A loss caused by a press elsewhere is the person's move: the press comes first, in the same moment.
    const pressed = performance.now() - this.pressedAt < 50;
    void this.restore(snapshot, pressed);
  };

  private async restore(snapshot: Snapshot, pressed: boolean): Promise<void> {
    // The host's render (and its nested renderers') decides what is still there.
    await this.host.updateComplete;
    for (let i = 0; i < 2; i++) await new Promise(resolve => requestAnimationFrame(resolve));
    if (this.loss !== snapshot || !this.host.isConnected || !pageHasFocus()) return;
    this.loss = null;
    const { element, anchor, within, order } = snapshot;
    if (element.isConnected) {
      // Moved by a reorder: it keeps focus. One left in its place lost focus on purpose (a press
      // elsewhere, a blur) and is left alone.
      if (!pressed && focusables(this.root).indexOf(element) !== order.indexOf(element)) tryFocus(element);
      return;
    }
    // A render that replaced the elements: each one is found again by its identity, when only one has it.
    const now = new Map<string, HTMLElement[]>();
    for (const control of focusables(this.root)) {
      const key = identity(control);
      if (key) now.set(key, [...now.get(key) ?? [], control]);
    }
    const current = (control: HTMLElement): HTMLElement | null => {
      if (control.isConnected) return control;
      const same = now.get(identity(control));
      return same?.length === 1 ? same[0]! : null;
    };
    const at = order.indexOf(anchor);
    const candidates = [element, ...at < 0 ? [] : [anchor, ...order.slice(at + 1), ...order.slice(0, at).reverse()]];
    for (const candidate of candidates) {
      if (within && candidate !== element && inside(candidate, within)) continue;
      if (tryFocus(current(candidate))) return;
    }
    tryFocus(this.fallback());
  }
}
