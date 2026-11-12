// SPDX-License-Identifier: Apache-2.0
/**
 * The one placement rule for what opens under the top bar (its menus and the status panel): it hangs
 * under its button and moves along the bar only as far as it must to stay `margin` inside the window.
 * When it is wider than that room, its start edge stays in view, so its words start where they can
 * be read.
 */
export function shiftIntoWindow(box: { readonly left: number; readonly right: number }, margin: number,
  width: number = window.innerWidth): number {
  return Math.max(margin - box.left, Math.min(0, width - margin - box.right));
}

/** A length token (px or rem) of `element`'s computed style, in pixels; 0 when it is not a length. */
export function cssLength(element: Element, name: string): number {
  const value = getComputedStyle(element).getPropertyValue(name).trim();
  const n = parseFloat(value);
  if (!Number.isFinite(n)) return 0;
  return value.endsWith('rem') ? n * (parseFloat(getComputedStyle(document.documentElement).fontSize) || 16) : n;
}
