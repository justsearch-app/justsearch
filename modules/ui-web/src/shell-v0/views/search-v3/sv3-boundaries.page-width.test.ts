// SPDX-License-Identifier: Apache-2.0
// The page-width rule's arithmetic for Search (owner decision, 15 November), as the delegated choices
// W2'/W2'' read it, with the list+pane half replaced by the picture author's decision of 17 November
// (PJ2/PJ3): the pane docks to the window's right edge, max(400, 30 % of the window) up to 592, and
// there is no list+pane cap or gutter any more (sv3-boundaries.pane-dock.test.ts holds the new rule).
// What stays: the sidebar's ceiling measures the whole window; a remembered width is clamped as before
// (360-560); the 980 switch measures the whole window; below 1040 the constrained-window width.
// A namespace import, so a build without the rule fails these by assertion rather than at import.
import { describe, expect, it } from 'vitest';
import * as boundaries from './sv3-boundaries.js';

const SHARE = 0.3;
const NO_SIDEBAR = 0;
const COLLAPSED = boundaries.SV3_SIDEBAR_COLLAPSED_PX;
const EXPANDED = boundaries.SV3_SIDEBAR_DEFAULT_PX;

type Fn = (host: number, sidebar: number, share: number | null) => number;
const rule = (name: 'sv3AutomaticPaneWidth'): Fn => {
  const fn = (boundaries as unknown as Record<string, unknown>)[name];
  expect(typeof fn, `${name} exists`).toBe('function');
  return fn as Fn;
};

describe('Search page width — the automatic pane (W2′)', () => {
  it.each([
    // [window, sidebar, expected pane]
    [1920, NO_SIDEBAR, 576],
    [1920, COLLAPSED, 576],
    [1920, EXPANDED, 576],
    [1280, NO_SIDEBAR, 400],
    [1100, NO_SIDEBAR, 400],
    [1088, COLLAPSED, 400],
    [1040, NO_SIDEBAR, 400],
  ])('at %i px with a %i px sidebar the automatic pane is %i px', (host, sidebar, pane) => {
    expect(rule('sv3AutomaticPaneWidth')(host, sidebar, SHARE)).toBe(pane);
  });

  it('below 1040 keeps the existing constrained-window ceiling (no worse than before the rule)', () => {
    const auto = rule('sv3AutomaticPaneWidth');
    // 1000 - 640 = 360: the ceiling the pane already had there.
    expect(auto(1000, NO_SIDEBAR, SHARE)).toBe(boundaries.sv3PaneMaxWidth(1000, NO_SIDEBAR));
    expect(auto(980, NO_SIDEBAR, SHARE)).toBe(boundaries.SV3_PANE_MIN_PX);
  });

  it('with no published share (split stage, no layout) is the automatic width from before the rule', () => {
    const auto = rule('sv3AutomaticPaneWidth');
    for (const [host, sidebar] of [[1920, NO_SIDEBAR], [960, NO_SIDEBAR], [1920, EXPANDED]] as const) {
      expect(auto(host, sidebar, null))
        .toBe(boundaries.resolveInitialSv3PaneWidth(null, host, sidebar));
    }
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
