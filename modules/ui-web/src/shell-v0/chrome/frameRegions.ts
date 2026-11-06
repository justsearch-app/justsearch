// SPDX-License-Identifier: Apache-2.0
/**
 * F6 region cycling for the app frame (shell slice 2, FR8; design-system §9: "Keyboard order:
 * top bar → window tabs → content → details pane; F6 cycles regions").
 *
 * A region is found inside open shadow roots (the window tab row lives in a window's
 * `<jf-surface-tabs>`, the details pane in the window's own tree). A region that is not shown —
 * Search has no tab row; the details pane is closed — is skipped, never focused while hidden.
 */
import { deepActiveElement } from '../utils/keyboardHandler.js';

export type FrameRegionKind = 'top-bar' | 'window-tabs' | 'content' | 'details';

export interface FrameRegion {
  readonly kind: FrameRegionKind;
  /** Where to look for the region; null when its owner is not mounted. */
  readonly root: Element | null;
}

const DETAILS_SELECTOR = 'jf-document-pane, [data-details-pane]';

/** Elements under `root`, descending into open shadow roots, in document order. */
function* deepElements(root: Element | ShadowRoot): Generator<Element> {
  for (const child of Array.from(root.children)) {
    yield child;
    if (child.shadowRoot) yield* deepElements(child.shadowRoot);
    yield* deepElements(child);
  }
}

function deepQuery(root: Element, selector: string, accept: (el: Element) => boolean = () => true): Element | null {
  if (root.matches(selector) && accept(root)) return root;
  const scopes: Array<Element | ShadowRoot> = root.shadowRoot ? [root.shadowRoot, root] : [root];
  for (const scope of scopes) {
    for (const el of deepElements(scope)) if (el.matches(selector) && accept(el)) return el;
  }
  return null;
}

/** The flat-tree parent: the parent element, or the shadow host when crossing out of a shadow root. */
function flatParent(el: Element): Element | null {
  if (el.assignedSlot) return el.assignedSlot;
  if (el.parentElement) return el.parentElement;
  const root = el.getRootNode();
  return root instanceof ShadowRoot ? root.host : null;
}

/** Shown: no ancestor (across shadow boundaries) is `hidden` or `display: none`. */
function isShown(el: Element): boolean {
  for (let node: Element | null = el; node; node = flatParent(node)) {
    if (node.hasAttribute('hidden')) return false;
    if (typeof getComputedStyle === 'function' && getComputedStyle(node).display === 'none') return false;
  }
  return true;
}

function contains(ancestor: Element, el: Element | null): boolean {
  for (let node = el; node; node = flatParent(node)) if (node === ancestor) return true;
  return false;
}

/** A window's own tab row: the tabs of a details pane inside the window are the details region. */
function windowTabs(root: Element): Element | null {
  return deepQuery(root, '[role="tablist"]', el => {
    if (!isShown(el)) return false;
    for (let node: Element | null = el; node; node = flatParent(node)) if (node.hasAttribute('data-details-pane')) return false;
    return true;
  });
}

const FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Make a region container focusable for this one F6 move only. A permanent `tabindex` would make
 * every click on blank content focus the container instead of leaving focus where it was.
 */
function focusableOnce(el: HTMLElement): HTMLElement {
  if (el.hasAttribute('tabindex')) return el;
  el.setAttribute('tabindex', '-1');
  el.addEventListener('blur', () => el.removeAttribute('tabindex'), { once: true });
  return el;
}

/** The element F6 focuses for a region, or null when the region is absent. */
function target(region: FrameRegion): HTMLElement | null {
  const root = region.root;
  if (!root || !root.isConnected) return null;
  switch (region.kind) {
    case 'top-bar': {
      const shown = (el: Element): boolean => isShown(el);
      return (deepQuery(root, '[aria-current="page"]', shown)
        ?? deepQuery(root, FOCUSABLE, shown)) as HTMLElement | null;
    }
    case 'window-tabs': {
      const tablist = windowTabs(root);
      if (!tablist) return null;
      return (tablist.querySelector('[role="tab"][aria-selected="true"]')
        ?? tablist.querySelector('[role="tab"]')) as HTMLElement | null;
    }
    case 'content':
      return isShown(root) ? focusableOnce(root as HTMLElement) : null;
    case 'details': {
      const pane = deepQuery(root, DETAILS_SELECTOR, isShown);
      if (!pane) return null;
      const inner = deepQuery(pane, '[role="tab"][aria-selected="true"]', isShown) ?? deepQuery(pane, FOCUSABLE, isShown);
      if (inner) return inner as HTMLElement;
      return focusableOnce(pane as HTMLElement);
    }
  }
}

/** The region that holds focus now: the most specific one (tabs and details sit inside content). */
function currentRegion(regions: readonly FrameRegion[]): number {
  const active = deepActiveElement();
  const order: FrameRegionKind[] = ['details', 'window-tabs', 'top-bar', 'content'];
  for (const kind of order) {
    const index = regions.findIndex(r => r.kind === kind);
    const region = regions[index];
    if (!region?.root) continue;
    const scope = kind === 'details' ? deepQuery(region.root, DETAILS_SELECTOR, isShown)
      : kind === 'window-tabs' ? windowTabs(region.root)
        : region.root;
    if (scope && contains(scope, active)) return index;
  }
  return -1;
}

/**
 * Move focus to the next (`1`) or previous (`-1`) region that is present, wrapping around.
 * Returns the region focused, or null when none is present.
 */
export function focusRegion(regions: readonly FrameRegion[], direction: 1 | -1): FrameRegionKind | null {
  const from = currentRegion(regions);
  for (let step = 1; step <= regions.length; step++) {
    const index = ((from === -1 && direction === -1 ? 0 : from) + direction * step + regions.length * 2) % regions.length;
    const region = regions[index]!;
    const el = target(region);
    if (!el) continue;
    el.focus({ preventScroll: false });
    return region.kind;
  }
  return null;
}
