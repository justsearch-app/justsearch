// SPDX-License-Identifier: Apache-2.0
// The page-width rule's arithmetic for Search (owner decision, 15 November: "Search with the side pane
// open: list and pane together, centred, at most 1280 px, the pane 400 px growing to at most 560"),
// as the delegated choices W2'/W2'' read it: the sidebar stays outside the cap at the left edge; with
// no remembered width the pane takes the room the main column's 640 leaves in the capped box,
// 400-560; a remembered width is clamped as before (360-560); the 980 switch measures the whole window.
// A namespace import, so a build without the rule fails these by assertion rather than at import.
import { describe, expect, it } from 'vitest';
import * as boundaries from './sv3-boundaries.js';

const CAP = 1280;
const NO_SIDEBAR = 0;
const COLLAPSED = boundaries.SV3_SIDEBAR_COLLAPSED_PX;
const EXPANDED = boundaries.SV3_SIDEBAR_DEFAULT_PX;

type Fn = (host: number, sidebar: number, cap: number) => number;
const rule = (name: 'sv3AutomaticPaneWidth' | 'sv3PageGutter'): Fn => {
  const fn = (boundaries as unknown as Record<string, unknown>)[name];
  expect(typeof fn, `${name} exists`).toBe('function');
  return fn as Fn;
};

describe('Search page width — the automatic pane (W2′)', () => {
  it.each([
    // [window, sidebar, expected pane]
    [1920, NO_SIDEBAR, 560],
    [1920, COLLAPSED, 560],
    [1920, EXPANDED, 560],
    [1280, NO_SIDEBAR, 560],
    [1100, NO_SIDEBAR, 460],
    [1088, COLLAPSED, 400],
    [1040, NO_SIDEBAR, 400],
  ])('at %i px with a %i px sidebar the automatic pane is %i px', (host, sidebar, pane) => {
    expect(rule('sv3AutomaticPaneWidth')(host, sidebar, CAP)).toBe(pane);
  });

  it('grows from 400 at the 1040 / 1088 boundaries by the room it gains, up to 560', () => {
    const auto = rule('sv3AutomaticPaneWidth');
    expect(auto(1041, NO_SIDEBAR, CAP)).toBe(401);
    expect(auto(1089, COLLAPSED, CAP)).toBe(401);
    expect(auto(1200, NO_SIDEBAR, CAP)).toBe(560);
    expect(auto(1300, NO_SIDEBAR, CAP)).toBe(560);
  });

  it('below 1040 keeps the existing constrained-window ceiling (no worse than before the rule)', () => {
    const auto = rule('sv3AutomaticPaneWidth');
    // 1000 - 640 = 360: the ceiling the pane already had there.
    expect(auto(1000, NO_SIDEBAR, CAP)).toBe(boundaries.sv3PaneMaxWidth(1000, NO_SIDEBAR));
    expect(auto(980, NO_SIDEBAR, CAP)).toBe(boundaries.SV3_PANE_MIN_PX);
  });

  it('with no published cap (split stage, no layout) is the automatic width from before the rule', () => {
    const auto = rule('sv3AutomaticPaneWidth');
    for (const [host, sidebar] of [[1920, NO_SIDEBAR], [960, NO_SIDEBAR], [1920, EXPANDED]] as const) {
      expect(auto(host, sidebar, Number.POSITIVE_INFINITY))
        .toBe(boundaries.resolveInitialSv3PaneWidth(null, host, sidebar));
    }
  });
});

describe('Search page width — the gutter beside the list and the pane (W2′)', () => {
  it.each([
    [1920, NO_SIDEBAR, 320],
    [1920, COLLAPSED, 296],
    [1920, EXPANDED, 192],
    [1280, NO_SIDEBAR, 0],
    [1100, NO_SIDEBAR, 0],
  ])('at %i px with a %i px sidebar the list and pane are centred with %i px each side', (host, sidebar, gutter) => {
    expect(rule('sv3PageGutter')(host, sidebar, CAP)).toBe(gutter);
  });

  it('list plus pane fill at most the cap, and the main column keeps its 640 at 1920', () => {
    const gutter = rule('sv3PageGutter');
    const auto = rule('sv3AutomaticPaneWidth');
    for (const sidebar of [NO_SIDEBAR, COLLAPSED, EXPANDED]) {
      const room = 1920 - sidebar;
      const box = room - 2 * gutter(1920, sidebar, CAP);
      expect(box).toBeLessThanOrEqual(CAP + 1);
      expect(box).toBeGreaterThanOrEqual(CAP);
      expect(box - auto(1920, sidebar, CAP)).toBeGreaterThanOrEqual(boundaries.SV3_MAIN_MIN_PX);
    }
  });

  it('is zero for an unmeasured window or without a published cap', () => {
    const gutter = rule('sv3PageGutter');
    expect(gutter(Number.POSITIVE_INFINITY, NO_SIDEBAR, CAP)).toBe(0);
    expect(gutter(1920, NO_SIDEBAR, Number.POSITIVE_INFINITY)).toBe(0);
  });
});

describe('Search page width — what the rule leaves alone (W2′/W2″)', () => {
  it('the inline/overlay switch still measures the whole window at 979 / 980', () => {
    expect(boundaries.sv3PaneIsInline(979)).toBe(false);
    expect(boundaries.sv3PaneIsInline(980)).toBe(true);
  });

  it('a remembered or dragged width is clamped as before, 360-560', () => {
    expect(boundaries.resolveInitialSv3PaneWidth(360, 1920, NO_SIDEBAR)).toBe(360);
    expect(boundaries.resolveInitialSv3PaneWidth(450, 1920, NO_SIDEBAR)).toBe(450);
    expect(boundaries.resolveInitialSv3PaneWidth(600, 1920, NO_SIDEBAR)).toBe(560);
    expect(boundaries.clampSv3PaneWidth(300, 1920, EXPANDED)).toBe(360);
    expect(boundaries.clampSv3PaneWidth(900, 1920, EXPANDED)).toBe(560);
  });

  it('the sidebar ceiling still measures the whole window', () => {
    expect(boundaries.sv3SidebarMaxWidth(1920, 560)).toBe(1920 - 560 - boundaries.SV3_MAIN_MIN_PX);
  });
});
