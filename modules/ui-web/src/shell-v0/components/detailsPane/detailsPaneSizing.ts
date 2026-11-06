// SPDX-License-Identifier: Apache-2.0
/**
 * The details pane's numbers (design system §2 "Right edge of a window"): 360-560 px, inline from
 * 980 px of WINDOW width, a sheet over the window below. One owner, so Search's boundary arithmetic
 * and every other window's pane cannot drift apart.
 */

export const DETAILS_PANE_MIN_PX = 360;
export const DETAILS_PANE_MAX_PX = 560;
export const DETAILS_PANE_DEFAULT_PX = 540;
export const DETAILS_PANE_INLINE_MIN_PX = 980;
/** One keyboard nudge of a pane's grip. */
export const DETAILS_PANE_KEY_STEP_PX = 24;

/** An unmeasured box (`Infinity`) is inline: an unknown width is not a narrow width. */
export function detailsPaneIsInline(windowWidth: number): boolean {
  return windowWidth >= DETAILS_PANE_INLINE_MIN_PX;
}

export function clampDetailsPaneWidth(width: number): number {
  return Math.round(Math.max(DETAILS_PANE_MIN_PX, Math.min(width, DETAILS_PANE_MAX_PX)));
}

/** The width key every window's pane shares; the key name predates the shared pane. */
export const DETAILS_PANE_WIDTH_KEY = 'justsearch.searchV3.pane.width.v1';

/** The remembered width, or null for automatic. A stored non-number is no memory at all. */
export function readDetailsPaneWidth(): number | null {
  try {
    const raw = localStorage.getItem(DETAILS_PANE_WIDTH_KEY);
    if (raw === null) return null;
    const parsed = Number.parseInt(raw, 10);
    return Number.isFinite(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

export function storeDetailsPaneWidth(px: number): void {
  try {
    localStorage.setItem(DETAILS_PANE_WIDTH_KEY, String(Math.round(px)));
  } catch {
    /* A window that cannot persist keeps the width for this session. */
  }
}

export function forgetDetailsPaneWidth(): void {
  try {
    localStorage.removeItem(DETAILS_PANE_WIDTH_KEY);
  } catch {
    /* Nothing was remembered. */
  }
}
