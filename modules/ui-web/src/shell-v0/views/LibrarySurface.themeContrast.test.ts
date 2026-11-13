// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { applyAppearance, __resetThemeStateForTest } from '../state/themeState.js';
import { contrastRatio, parseColor, WCAG_AA } from '../themes/contrast.js';

// happy-dom drops @layer rules from cssRules. Record each sheet's current text,
// then inspect only sheets adopted by this document (including later overwrites).
let sheetText: WeakMap<CSSStyleSheet, string>;
// The @layer a sheet was first written with stays its owner mark, even if later text drops it.
let sheetLayer: WeakMap<CSSStyleSheet, string>;
beforeEach(() => {
  sheetText = new WeakMap();
  sheetLayer = new WeakMap();
  const { replaceSync, replace } = CSSStyleSheet.prototype;
  vi.spyOn(CSSStyleSheet.prototype, 'replaceSync').mockImplementation(function (this: CSSStyleSheet, text) {
    sheetText.set(this, text);
    markLayer(this, text);
    return replaceSync.call(this, text);
  });
  vi.spyOn(CSSStyleSheet.prototype, 'replace').mockImplementation(function (this: CSSStyleSheet, text) {
    sheetText.set(this, text);
    markLayer(this, text);
    return replace.call(this, text);
  });
});
const markLayer = (sheet: CSSStyleSheet, text: string) => {
  const layer = /^@layer\s+([\w-]+)/.exec(text)?.[1];
  if (layer && !sheetLayer.has(sheet)) sheetLayer.set(sheet, layer);
};
// Current text of every adopted sheet first written with the given layer.
const layerCss = (layer: string) => document.adoptedStyleSheets
  .filter((sheet) => sheetLayer.get(sheet) === layer).map((sheet) => sheetText.get(sheet) ?? '');
const roleCss = () => layerCss('user-override');

// S34.9: Library's primary buttons use accent-on-tint. The OS-following path must
// update this derived ink together with the fill, just like an explicit theme choice.
afterEach(() => {
  __resetThemeStateForTest();
  document.adoptedStyleSheets = [];
  document.documentElement.removeAttribute('data-theme');
  vi.restoreAllMocks();
});

it('keeps Library primary-button ink legible when Follow OS changes light and dark', async () => {
  let dark = false;
  let change: (() => void) | undefined;
  vi.spyOn(window, 'matchMedia').mockImplementation((media) => ({
    media, get matches() { return dark; },
    addEventListener: (_type: string, listener: () => void) => { change = listener; },
    removeEventListener: () => {},
  }) as unknown as MediaQueryList);
  // happy-dom has no renderer: supply the resolved fill at the DOM boundary;
  // the real role derivation, theme listener and injected stylesheet run unchanged.
  vi.spyOn(globalThis, 'getComputedStyle').mockImplementation(() => ({
    backgroundColor: dark ? 'rgb(0, 204, 178)' : 'rgb(0, 95, 82)',
  }) as CSSStyleDeclaration);
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);
  const assertContrast = () => {
    expect(roleCss()).toHaveLength(1);
    const css = roleCss()[0]!;
    const ink = parseColor(css.match(/--accent-on-tint:\s*([^;]+);/)?.[1] ?? '');
    expect(ink).not.toBeNull();
    const fill = parseColor(dark ? 'rgb(0, 204, 178)' : 'rgb(0, 95, 82)')!;
    expect(contrastRatio(fill, ink!), `Library primary ink after OS switched to ${dark ? 'dark' : 'light'}`)
      .toBeGreaterThanOrEqual(WCAG_AA);
  };
  await applyAppearance({ theme: 'system' }); assertContrast();
  dark = true; change?.(); assertContrast();
  dark = false; change?.(); assertContrast();
});
