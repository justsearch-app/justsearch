// SPDX-License-Identifier: Apache-2.0
/**
 * The Search v3 window's MOVABLE BOUNDARIES — the sidebar's (tempdoc 822 Phase F5) and the citation
 * pane's (Phase F8), in one module because they are two halves of one arithmetic.
 *
 * (Renamed from `sv3-sidebar-sizing.ts` in F8: the moment a second boundary appeared, a file called
 * "sidebar sizing" holding the pane's ceiling would have been the name lying about the contents, and
 * two files would have been two places for the main column's 640 to drift apart in.)
 *
 * Derived from a third-party design system (MIT) — see THIRD-PARTY-NOTICES.md in this directory. Every
 * number below is the design spec's own:
 *
 *  - sidebar default 16rem
 *  - sidebar drag floor 13rem
 *  - collapsed icon rail 3rem
 *  - pane default 540px, floor 360px, ceiling 560px (the design system's details pane)
 *  - pane inline/overlay switch 980px
 *  - **neither ceiling is the region's own number.** The sidebar's is derived from the MAIN pane's
 *    40rem minimum: `max(sidebarMin, available - 640)`. A region may
 *    take everything that is not the main pane's honest reading width, and no more — the same shape
 *    as 818's L13 ("every movable boundary is clamped by the minimum honest forms on both sides"),
 *    which is why the two windows' rules agree without either importing the other.
 *
 * TWO DELIBERATE DEVIATIONS from the spec's arithmetic, and both are the spec's own rule applied
 * honestly (charter law 11, improve-don't-copy):
 *
 *  1. **The box is the WINDOW HOST, not the viewport.** The spec measures `window.innerWidth`
 *     because its sidebar+main wrapper IS the viewport. Search v3 is a surface
 *     mounted INSIDE the shipped shell, so the viewport is wider than the box its regions share;
 *     deriving from the viewport would let the main pane fall under 640px — the exact thing the
 *     spec's rule exists to prevent. Callers pass the window host's own width, the spec's `wrapper`.
 *  2. **The pane is clamped by the main column too.** The spec has NO main-column clamp for its
 *     right panel: its pane may take 70vw beside an open sidebar and
 *     squeeze the conversation to nothing. Here {@link sv3PaneMaxWidth} subtracts the sidebar's
 *     CURRENT occupancy and the main column's 640 first, and {@link sv3SidebarMaxWidth} subtracts the
 *     pane's, so the reading column survives BOTH boundaries being pushed.
 *
 * The impure edge at the bottom is 818's L13 remember/reset asymmetry, and it is NOT a breach of
 * `sv3-sessions.ts`'s no-persistence rule: that rule is about SESSION RECORDS, whose authority is the
 * open Phase-D question. A sidebar width, a collapsed rail and a pane width are UI PREFERENCES about
 * this window's chrome — they describe no domain object, fork no authority, and have exactly the
 * durable home (`localStorage`, not the shared user document) the retired search-v2 window's rail
 * sizing already chose for the same kind of value.
 */

import {
  DETAILS_PANE_DEFAULT_PX,
  DETAILS_PANE_INLINE_MIN_PX,
  DETAILS_PANE_MAX_PX,
  DETAILS_PANE_MIN_PX,
  DETAILS_PANE_WIDTH_KEY,
  detailsPaneIsInline,
  forgetDetailsPaneWidth,
  readDetailsPaneWidth,
  storeDetailsPaneWidth,
} from '../../components/detailsPane/detailsPaneSizing.js';

/** The spec's default — 16rem. */
export const SV3_SIDEBAR_DEFAULT_PX = 256;

/** The spec's drag floor — 13rem. */
export const SV3_SIDEBAR_MIN_PX = 208;

/** The spec's main-pane minimum — 40rem. Both ceilings come FROM it. */
export const SV3_MAIN_MIN_PX = 640;

/** The spec's icon rail — 3rem; the collapsed sidebar's whole width. */
export const SV3_SIDEBAR_COLLAPSED_PX = 48;

/** The spec's right-panel default — 540px. */
export const SV3_PANE_DEFAULT_PX = DETAILS_PANE_DEFAULT_PX;

/** The details pane's floor — 360px. */
export const SV3_PANE_MIN_PX = DETAILS_PANE_MIN_PX;

/** The details pane's ceiling — 560px, shared with every window's pane (design system §2). */
export const SV3_PANE_MAX_PX = DETAILS_PANE_MAX_PX;

/**
 * The spec's inline/overlay switch — 980px, asked of the WINDOW box rather
 * than the viewport for the same reason every other number here is. Below it the pane presents as a
 * window-scoped overlay and therefore occupies NO layout width, which is why it is
 * {@link sv3PaneOccupiedWidth} and not the raw chosen width that reaches the sidebar's ceiling.
 *
 * The presentation half of this switch reads the SAME predicate ({@link sv3PaneIsInline}) through an
 * attribute the window reflects, rather than a CSS `@container` query — `Sv3Pane`'s styles record
 * why (the container would re-anchor F7's fixed-position hover card). One number, one owner:
 * geometry cannot quietly drift apart.
 */
export const SV3_PANE_INLINE_MIN_PX = DETAILS_PANE_INLINE_MIN_PX;

/**
 * One keyboard nudge, shared by both grips. The spec's rail is `tabIndex={-1}` and its pane handle
 * has no keyboard half either, so this is the repo's own answer to the same boundary: the 24px step
 * the retired search-v2 window's rail grip already used.
 */
export const SV3_GRIP_KEY_STEP_PX = 24;

/**
 * The ONE law both ceilings are cut from: what is left of the shared box once the main column's
 * honest reading width and whatever the OTHER movable region currently occupies are taken out.
 *
 * An UNMEASURABLE box (`Infinity`, i.e. not laid out yet) yields no ceiling rather than a tiny one:
 * an unknown width is not a narrow width, and treating it as one would collapse a remembered
 * preference to the floor as a side effect of the window not having been painted. The floors still
 * apply — they sit on the OUTSIDE of every clamp below — so nothing illegal gets through.
 */
function mainSafeRemainder(hostWidth: number, occupiedByOther: number): number {
  return Math.floor(hostWidth) - occupiedByOther - SV3_MAIN_MIN_PX;
}

/**
 * Whether the pane presents INLINE (beside the main column) rather than as a window-scoped overlay.
 * An unmeasured box (`Infinity`) is inline, consistent with every other rule here: an unknown width
 * is not a narrow width.
 */
export const sv3PaneIsInline = detailsPaneIsInline;

/**
 * How much layout width an open pane actually takes from the sidebar's ceiling. A pane in overlay
 * presentation takes none — it is painted OVER the window, not beside it — so a narrow window does
 * not squeeze its own sidebar on account of a pane that is not in the flow.
 */
export function sv3PaneOccupiedWidth(paneWidth: number | null, hostWidth: number): number {
  if (paneWidth === null) return 0;
  return sv3PaneIsInline(hostWidth) ? paneWidth : 0;
}

/**
 * The widest the sidebar may be beside a main pane that still has its 640px AND whatever the citation
 * pane currently occupies. The floor WINS when the terms cross (`Math.max` on the outside), which is
 * the spec's own resolution: a box too narrow for every minimum keeps
 * the sidebar legible rather than collapsing it to a sliver.
 */
export function sv3SidebarMaxWidth(hostWidth: number, paneOccupiedWidth: number): number {
  return Math.max(SV3_SIDEBAR_MIN_PX, mainSafeRemainder(hostWidth, paneOccupiedWidth));
}

/** The spec's `clampSidebarWidth`, with the ceiling derived per above. */
export function clampSv3SidebarWidth(
  width: number,
  hostWidth: number,
  paneOccupiedWidth: number,
): number {
  return Math.round(
    Math.max(SV3_SIDEBAR_MIN_PX, Math.min(width, sv3SidebarMaxWidth(hostWidth, paneOccupiedWidth))),
  );
}

/**
 * The width the window opens at: the remembered one if there is one, the default otherwise, and in
 * both cases clamped into the box actually available. Per the design spec's initial-width
 * resolution — a remembered width from a wider window must not reopen this one
 * in a shape its own minimums reject.
 */
export function resolveInitialSv3SidebarWidth(
  storedWidth: number | null,
  hostWidth: number,
  paneOccupiedWidth: number,
): number {
  const preferred =
    storedWidth === null ? SV3_SIDEBAR_DEFAULT_PX : Math.max(SV3_SIDEBAR_MIN_PX, storedWidth);
  return Math.min(preferred, sv3SidebarMaxWidth(hostWidth, paneOccupiedWidth));
}

/**
 * The widest the citation pane may be: the details pane's 560px, and — deviation 2 — never more than the
 * box has left once the sidebar's current occupancy and the main column's 640 are taken out.
 * `sidebarOccupiedWidth` is the rendered width, so a COLLAPSED sidebar hands the pane the 48px rail's
 * worth of room back rather than the width it will have when it expands again.
 */
export function sv3PaneMaxWidth(hostWidth: number, sidebarOccupiedWidth: number): number {
  return Math.max(
    SV3_PANE_MIN_PX,
    Math.min(SV3_PANE_MAX_PX, mainSafeRemainder(hostWidth, sidebarOccupiedWidth)),
  );
}

export function clampSv3PaneWidth(
  width: number,
  hostWidth: number,
  sidebarOccupiedWidth: number,
): number {
  return Math.round(
    Math.max(SV3_PANE_MIN_PX, Math.min(width, sv3PaneMaxWidth(hostWidth, sidebarOccupiedWidth))),
  );
}

/** The pane's own {@link resolveInitialSv3SidebarWidth} — same rule, the pane's numbers. */
export function resolveInitialSv3PaneWidth(
  storedWidth: number | null,
  hostWidth: number,
  sidebarOccupiedWidth: number,
): number {
  const preferred =
    storedWidth === null ? SV3_PANE_DEFAULT_PX : Math.max(SV3_PANE_MIN_PX, storedWidth);
  return Math.min(preferred, sv3PaneMaxWidth(hostWidth, sidebarOccupiedWidth));
}

const SIDEBAR_WIDTH_KEY = 'justsearch.searchV3.sidebar.width.v1';
const COLLAPSED_KEY = 'justsearch.searchV3.sidebar.collapsed.v1';

/** A remembered width, or null for automatic. A stored non-number is treated as no memory at all. */
function readStoredWidth(key: string): number | null {
  try {
    const raw = localStorage.getItem(key);
    if (raw === null) return null;
    const px = Number.parseInt(raw, 10);
    return Number.isFinite(px) ? px : null;
  } catch {
    // Storage unavailable (private mode, quota) costs the memory, never the gesture.
    return null;
  }
}

function storeWidth(key: string, px: number): void {
  try {
    localStorage.setItem(key, String(Math.round(px)));
  } catch {
    // As above.
  }
}

/** L13 — returning a boundary to automatic FORGETS it: the width was a choice the reader withdrew. */
function forgetWidth(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    // As above.
  }
}

export const readStoredSv3SidebarWidth = (): number | null => readStoredWidth(SIDEBAR_WIDTH_KEY);
export const storeSv3SidebarWidth = (px: number): void => storeWidth(SIDEBAR_WIDTH_KEY, px);
export const forgetSv3SidebarWidth = (): void => forgetWidth(SIDEBAR_WIDTH_KEY);

export const readStoredSv3PaneWidth = readDetailsPaneWidth;
export const storeSv3PaneWidth = storeDetailsPaneWidth;
export const forgetSv3PaneWidth = forgetDetailsPaneWidth;

/**
 * Whether the rail was left collapsed. The design spec keeps this in a cookie
 * because its shell renders server-side and needs the state before
 * hydration; this app has no server render, so the preference lives beside the width instead of
 * introducing a second storage medium for one boolean.
 */
export function readStoredSv3SidebarCollapsed(): boolean {
  try {
    return localStorage.getItem(COLLAPSED_KEY) === '1';
  } catch {
    return false;
  }
}

export function storeSv3SidebarCollapsed(collapsed: boolean): void {
  try {
    localStorage.setItem(COLLAPSED_KEY, collapsed ? '1' : '0');
  } catch {
    // As above.
  }
}

export const sv3BoundaryStorageKeys = {
  sidebarWidth: SIDEBAR_WIDTH_KEY,
  sidebarCollapsed: COLLAPSED_KEY,
  paneWidth: DETAILS_PANE_WIDTH_KEY,
} as const;
