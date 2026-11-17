// SPDX-License-Identifier: Apache-2.0
// The docked pane's automatic width (the picture author's decision of 17 November, as the
// orchestrator's PJ2 reads it): "Pane width: 400 px at the default window, then 30 % of the window
// width, up to 592 px (560 of content plus 16 padding each side)." The frame publishes the 30 % as
// `--surface-pane-share`; where it publishes none (a split stage, a DOM without layout) the automatic
// width is the one from before the page-width rule. The main column's 640 still binds first, so a
// window between 980 and 1040 px keeps the narrower pane it had.
// A namespace import, so a build without the rule fails these by assertion rather than at import.
import { describe, expect, it } from 'vitest';
import * as boundaries from './sv3-boundaries.js';

const SHARE = 0.3;
const NO_SIDEBAR = 0;
const COLLAPSED = boundaries.SV3_SIDEBAR_COLLAPSED_PX;
const EXPANDED = boundaries.SV3_SIDEBAR_DEFAULT_PX;

type Auto = (host: number, sidebar: number, share: number | null) => number;
const auto: Auto = (host, sidebar, share) => {
  const fn = (boundaries as unknown as Record<string, unknown>).sv3AutomaticPaneWidth;
  expect(typeof fn, 'sv3AutomaticPaneWidth exists').toBe('function');
  return (fn as Auto)(host, sidebar, share);
};

describe('PJ2 the docked pane: max(400, 30 % of the window), at most 592', () => {
  it.each([
    // [window, sidebar, expected pane]
    [1024, NO_SIDEBAR, 384],
    [1100, NO_SIDEBAR, 400],
    [1280, NO_SIDEBAR, 400],
    [1333, NO_SIDEBAR, 400],
    [1400, NO_SIDEBAR, 420],
    [1600, NO_SIDEBAR, 480],
    [1920, NO_SIDEBAR, 576],
    [1920, COLLAPSED, 576],
    [1920, EXPANDED, 576],
    [1973, NO_SIDEBAR, 592],
    [2560, NO_SIDEBAR, 592],
    [3840, EXPANDED, 592],
  ])('at %i px with a %i px sidebar the automatic pane is %i px', (host, sidebar, pane) => {
    expect(auto(host, sidebar, SHARE)).toBe(pane);
  });

  it('592 is 560 of content and 16 px of padding on each side', () => {
    expect(auto(5000, NO_SIDEBAR, SHARE) - 2 * 16).toBe(560);
  });

  it('the main column keeps its 640 beside the automatic pane', () => {
    for (const host of [980, 1000, 1040, 1100, 1300, 1920, 2560]) {
      for (const sidebar of [NO_SIDEBAR, COLLAPSED, EXPANDED]) {
        const pane = auto(host, sidebar, SHARE);
        if (pane > boundaries.SV3_PANE_MIN_PX) expect(host - sidebar - pane, `${host}/${sidebar}`).toBeGreaterThanOrEqual(boundaries.SV3_MAIN_MIN_PX);
        expect(pane, `${host}/${sidebar} floor`).toBeGreaterThanOrEqual(boundaries.SV3_PANE_MIN_PX);
      }
    }
  });
});

describe('PJ4 what the docked pane leaves alone', () => {
  it('at or below 1040 px it is the constrained-window width it had (no change at the default window)', () => {
    expect(auto(1024, NO_SIDEBAR, SHARE)).toBe(boundaries.sv3PaneMaxWidth(1024, NO_SIDEBAR));
    expect(auto(1000, NO_SIDEBAR, SHARE)).toBe(360);
    expect(auto(980, NO_SIDEBAR, SHARE)).toBe(boundaries.SV3_PANE_MIN_PX);
    expect(auto(1040, NO_SIDEBAR, SHARE)).toBe(400);
    expect(auto(1100, EXPANDED, SHARE)).toBe(360);
  });

  it('with no published share (split stage, no layout) it is the automatic width from before the rule', () => {
    for (const [host, sidebar] of [[1920, NO_SIDEBAR], [960, NO_SIDEBAR], [1920, EXPANDED], [1200, COLLAPSED]] as const) {
      expect(auto(host, sidebar, null)).toBe(boundaries.resolveInitialSv3PaneWidth(null, host, sidebar));
    }
  });

  it('a remembered or dragged width is still clamped 360-560', () => {
    expect(boundaries.resolveInitialSv3PaneWidth(450, 2560, NO_SIDEBAR)).toBe(450);
    expect(boundaries.resolveInitialSv3PaneWidth(600, 2560, NO_SIDEBAR)).toBe(560);
    expect(boundaries.clampSv3PaneWidth(300, 2560, EXPANDED)).toBe(360);
    expect(boundaries.clampSv3PaneWidth(900, 2560, EXPANDED)).toBe(560);
  });
});
