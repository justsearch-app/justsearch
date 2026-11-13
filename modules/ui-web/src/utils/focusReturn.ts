// SPDX-License-Identifier: Apache-2.0
/**
 * The one focus-return rule (F-desktop B2 FD2, FW1): when something takes the person's focus for a
 * while, the engine-loss treatment or a window control that minimizes or hides the window, focus goes
 * back to where it was before, or, when that element is gone or can no longer take focus, to the main
 * content. Never to the page body, and never left on the control that sent the window away.
 */

/** The element that has focus, through open shadow roots; null when nothing but the page has it. */
export function focusedElement(): HTMLElement | null {
  let active: Element | null = document.activeElement;
  while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
  return active instanceof HTMLElement && active !== document.body && active !== document.documentElement ? active : null;
}

/** `node` is `scope` or inside it, across shadow roots. */
export function composedContains(scope: Node, node: Node | null): boolean {
  for (let at: Node | null = node; at; at = at.parentNode ?? (at instanceof ShadowRoot ? at.host : null)) {
    if (at === scope) return true;
  }
  return false;
}

/** Inside an inert subtree, across shadow roots: focus cannot land there. */
function inert(element: Element): boolean {
  for (let at: Node | null = element; at; at = at.parentNode ?? (at instanceof ShadowRoot ? at.host : null)) {
    if (at instanceof HTMLElement && at.inert) return true;
  }
  return false;
}

/** The page's main landmark, through open shadow roots (the shell's window area). */
function mainContent(scope: Document | ShadowRoot = document): HTMLElement | null {
  const direct = scope.querySelector<HTMLElement>('main, [role="main"]');
  if (direct) return direct;
  for (const element of scope.querySelectorAll('*')) {
    const found = element.shadowRoot ? mainContent(element.shadowRoot) : null;
    if (found) return found;
  }
  return null;
}

function tryFocus(element: HTMLElement): boolean {
  if (!element.isConnected || inert(element)) return false;
  element.focus({ preventScroll: true });
  return composedContains(element, focusedElement());
}

/**
 * Put focus back on `remembered` when it can still take it, else on the main content (made focusable
 * for this one visit). Returns where focus went; null when neither could take it.
 */
export function returnFocus(remembered: Element | null): HTMLElement | null {
  if (remembered instanceof HTMLElement && tryFocus(remembered)) return remembered;
  const main = mainContent();
  if (!main) return null;
  if (main.hasAttribute('tabindex')) return tryFocus(main) ? main : null;
  main.setAttribute('tabindex', '-1');
  if (!tryFocus(main)) { main.removeAttribute('tabindex'); return null; }
  main.addEventListener('blur', () => main.removeAttribute('tabindex'), { once: true });
  return main;
}
