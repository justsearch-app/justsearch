// SPDX-License-Identifier: Apache-2.0
/**
 * FocusKeeper — keyboard focus never falls to the page when a list renders under it (L21).
 *
 * Browsers drop focus to the page body when the focused control is moved (a keyed list reorders) or
 * removed (its item left the list, a confirmed delete). The host's renders cause that, so the host
 * puts it right: after the host and its nested renderers settle, focus returns to the same control.
 *
 * A control is known by its row and its role in that row, not by where it sits: a row is the nearest
 * element marked `data-focus-row="<key>"` (a list item's key), the role is the control's name
 * (`aria-controls`, `label` or `aria-label`), else its place among the row's controls. However the rows
 * reorder, focus stays on the same row's same control. Only when that row leaves does focus move on: to
 * the next row's same control (in the same list), else the host's fallback (the list's heading or its
 * add button), else the nearest control that is still there. For a control inside an in-place
 * confirmation band, row and role are those of the control that opened the band.
 *
 * It never moves focus the person moved: a loss with a new focus target, a focus already elsewhere when
 * the host settles, or a loss on a control that stayed in place (a press elsewhere, a blur) are left
 * alone. "Stayed in place" is what the DOM says: no ancestor of the control was taken out since it got
 * focus, never a comparison of positions.
 */
import type { ReactiveController, ReactiveControllerHost } from 'lit';
import { deepActiveElement } from '../utils/keyboardHandler.js';

const FOCUSABLE = ':is(button, [href], input, select, textarea, [tabindex]):not([tabindex="-1"])';
const ROW = 'data-focus-row';

/** Every focusable control under `root` in tree order, through open shadow roots. */
function focusables(root: ParentNode, out: HTMLElement[] = []): HTMLElement[] {
  for (const el of root.querySelectorAll<HTMLElement>('*')) {
    if (el.matches(FOCUSABLE)) out.push(el);
    if (el.shadowRoot) focusables(el.shadowRoot, out);
  }
  return out;
}

/** Every keyed row under `root` in tree order, through open shadow roots. */
export function focusRowsUnder(root: ParentNode, out: HTMLElement[] = []): HTMLElement[] {
  for (const el of root.querySelectorAll<HTMLElement>('*')) {
    if (el.getAttribute(ROW)) out.push(el);
    if (el.shadowRoot) focusRowsUnder(el.shadowRoot, out);
  }
  return out;
}

const up = (node: Node): Node | null => node.parentNode ?? (node instanceof ShadowRoot ? node.host : null);

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

/** Whether `node` is inside `container`, across shadow roots. */
function inside(node: Node, container: Node): boolean {
  for (let at: Node | null = node; at; at = up(at)) {
    if (at === container) return true;
  }
  return false;
}

/** The control's name, from itself up to (not including) `stop`: what it is, whichever element says it. */
function nameOf(el: HTMLElement, stop: Node | null): string | null {
  for (let at: Node | null = el; at && at !== stop; at = up(at)) {
    if (!(at instanceof Element)) continue;
    const name = at.getAttribute('aria-controls') ?? at.getAttribute('label') ?? at.getAttribute('aria-label');
    if (name) return name;
  }
  return null;
}

/**
 * A row-less control's identity across renders that replace elements: what it is and where (the nearest
 * enclosing `id`, such as a section). Empty when it names nothing.
 */
function identity(el: HTMLElement): string {
  let name: string | null = null;
  for (let at: Node | null = el; at; at = up(at)) {
    if (!(at instanceof Element)) continue;
    name ??= at.getAttribute('aria-controls') ?? at.getAttribute('label') ?? at.getAttribute('aria-label');
    if (at !== el && at.id) return name ? `${at.id}|${name}` : '';
  }
  return name ?? '';
}

function rowOf(el: HTMLElement): HTMLElement | null {
  for (let at: Node | null = el; at; at = up(at)) {
    if (at instanceof HTMLElement && at.getAttribute(ROW)) return at;
  }
  return null;
}

/** Which control of its row it is: its name, else its place among the row's controls. */
interface Role { readonly name: string | null; readonly index: number }

function roleOf(el: HTMLElement, row: HTMLElement): Role {
  return { name: nameOf(el, row), index: focusables(row).indexOf(el) };
}

/** The row's control with this role: the one with its name, else the one at its place, else its first. */
function controlIn(row: HTMLElement, role: Role): HTMLElement | null {
  const controls = focusables(row);
  const named = role.name ? controls.filter(control => nameOf(control, row) === role.name) : [];
  if (named.length === 1) return named[0]!;
  return controls[role.index] ?? controls[0] ?? null;
}

interface Snapshot {
  readonly element: HTMLElement;
  /** Where its row and role are read from: the band's opener for a control inside a band. */
  readonly anchor: HTMLElement;
  /** Controls to skip as neighbours (the band's own answers). */
  readonly within: Element | null;
  readonly order: readonly HTMLElement[];
  /** The anchor's row key and its role there; null for a control outside any keyed row. */
  readonly row: string | null;
  readonly role: Role | null;
  /** Every keyed row in order, with the list it belongs to. */
  readonly rows: readonly { readonly key: string; readonly list: Node | null }[];
}

export class FocusKeeper implements ReactiveController {
  private snapshot: Snapshot | null = null;
  /** The latest loss being put right; a newer loss or a focus elsewhere replaces it. */
  private loss: Snapshot | null = null;
  private pressedAt = -Infinity;
  /** Controls an ancestor of which was taken out of the DOM (moved or removed) since they got focus. */
  private moved = new WeakSet<HTMLElement>();
  private readonly watcher = new MutationObserver(records => {
    const element = this.loss?.element ?? this.snapshot?.element;
    if (!element) return;
    for (const record of records) {
      for (const node of record.removedNodes) if (inside(element, node)) this.moved.add(element);
    }
  });

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
    this.watcher.disconnect();
    this.snapshot = null;
  }

  /** Before each render, the rows and controls as they are now: a row that then leaves is placed by them. */
  hostUpdate(): void {
    const snapshot = this.snapshot;
    const active = deepActiveElement();
    if (!snapshot || this.loss || !active || !inside(active, this.root as Node) || !snapshot.anchor.isConnected) return;
    this.snapshot = { ...snapshot, order: focusables(this.root), rows: this.rows() };
  }

  private readonly onPress = (): void => { this.pressedAt = performance.now(); };

  private rows(): Snapshot['rows'] {
    return focusRowsUnder(this.root).map(row => ({ key: row.getAttribute(ROW)!, list: row.parentNode }));
  }

  /**
   * The focused control, where its row and role are read from, and every control and row in order: as
   * read at the last focus or render inside this root when that still places the anchor (a render may
   * already have removed it), else as it is now.
   */
  private snapshotOf(event: FocusEvent): Snapshot | null {
    const path = event.composedPath();
    const element = path[0];
    if (!(element instanceof HTMLElement)) return null;
    const band = path.find((node): node is HTMLElement & { opener?: HTMLElement | null } =>
      node instanceof HTMLElement && node.localName === 'jf-confirm-band');
    const anchor = band?.opener ?? element;
    const earlier = this.snapshot;
    const stale = !anchor.isConnected && earlier?.order.includes(anchor) ? earlier : null;
    // A detached row keeps its key and controls, so a removed anchor still names its row and role.
    const row = rowOf(anchor);
    return { element, anchor, within: band ?? null, order: stale?.order ?? focusables(this.root),
      row: row?.getAttribute(ROW) ?? null, role: row ? roleOf(anchor, row) : null, rows: stale?.rows ?? this.rows() };
  }

  /** Watch every tree between the focused control and this root for an ancestor being taken out. */
  private watch(element: HTMLElement): void {
    this.watcher.disconnect();
    this.moved = new WeakSet();
    for (let at: Node | null = element; at; at = up(at)) {
      if (at instanceof ShadowRoot || at === this.root) this.watcher.observe(at, { childList: true, subtree: true });
      if (at === this.root) break;
    }
  }

  private readonly onFocusIn = (event: FocusEvent): void => {
    this.snapshot = this.snapshotOf(event);
    this.loss = null;
    if (this.snapshot) this.watch(this.snapshot.element);
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
    const { element, anchor, within, order, row, role, rows } = snapshot;
    if (element.isConnected) {
      // Moved by a reorder: it keeps focus. One that stayed in place lost focus on purpose (a press
      // elsewhere, a blur) and is left alone.
      if (!pressed && this.moved.has(element)) tryFocus(element);
      return;
    }
    // A render that replaced the elements: a row-less control is found again by its identity, when only
    // one control has it.
    const now = new Map<string, HTMLElement[]>();
    for (const control of focusables(this.root)) {
      const key = identity(control);
      if (key) now.set(key, [...now.get(key) ?? [], control]);
    }
    const current = (control: HTMLElement): HTMLElement | null => {
      if (control.isConnected) return control;
      if (rowOf(control)) return null;
      const same = now.get(identity(control));
      return same?.length === 1 ? same[0]! : null;
    };
    const live = new Map<string, HTMLElement>();
    for (const el of focusRowsUnder(this.root)) live.set(el.getAttribute(ROW)!, el);
    // The control itself, found again; for a control inside a band, the control that opened it.
    const candidates: (HTMLElement | null)[] = [current(element), current(anchor)];
    if (row && role) {
      // The same row's same control, else (the row left) the next row's in the same list.
      const at = rows.findIndex(entry => entry.key === row);
      const list = rows[at]?.list;
      const keys = [row, ...rows.slice(at + 1).filter(entry => entry.list === list).map(entry => entry.key)];
      for (const key of keys) {
        const target = live.get(key);
        if (target) candidates.push(controlIn(target, role));
        if (target && key !== row) break;
      }
      if (!live.has(row)) candidates.push(this.fallback() ?? null);
    }
    const from = order.indexOf(anchor);
    if (from >= 0) candidates.push(...order.slice(from + 1).map(current));
    candidates.push(this.fallback() ?? null);
    if (from >= 0) candidates.push(...order.slice(0, from).reverse().map(current));
    for (const candidate of candidates) {
      if (!candidate || within && candidate !== element && inside(candidate, within)) continue;
      if (tryFocus(candidate)) return;
    }
  }
}
