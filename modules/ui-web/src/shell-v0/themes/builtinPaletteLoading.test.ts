// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0

import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  __resetThemeStateForTest,
  applyAppearance,
  getActiveThemeId,
} from '../state/themeState.js';
import { __resetUserStateForTest } from '../state/UserStateDocument.js';
import { __resetCatalogForTest } from './themesCatalog.js';

// happy-dom drops @layer rules from cssRules. Record each sheet's current text,
// then inspect only sheets adopted by this document (including later overwrites).
let sheetText: WeakMap<CSSStyleSheet, string>;
beforeEach(() => {
  sheetText = new WeakMap();
  const { replaceSync, replace } = CSSStyleSheet.prototype;
  vi.spyOn(CSSStyleSheet.prototype, 'replaceSync').mockImplementation(function (this: CSSStyleSheet, text) {
    sheetText.set(this, text);
    return replaceSync.call(this, text);
  });
  vi.spyOn(CSSStyleSheet.prototype, 'replace').mockImplementation(function (this: CSSStyleSheet, text) {
    sheetText.set(this, text);
    return replace.call(this, text);
  });
});
afterEach(() => vi.restoreAllMocks());
const adoptedCss = () => document.adoptedStyleSheets.map((sheet) => sheetText.get(sheet) ?? '');
const paletteCss = () => adoptedCss().filter((css) => css.startsWith('@layer user-theme'));

const themesDir = join(__dirname, '../../../public/themes');
const paletteFiles = readdirSync(themesDir)
  .filter((file) => /\.(css|json)$/.test(file) && !file.startsWith('manifest.'));
const assets = new Map(paletteFiles.map((file) => [
  `/themes/${file}`,
  readFileSync(join(themesDir, file), 'utf8'),
]));
const paletteIds = [...new Set(paletteFiles.map((file) => file.replace(/\.(css|json)$/, '')))];

// Only transport is substituted: the production appearance writer chooses the format,
// validates/compiles JSON, falls back to CSS and injects the resulting palette itself.
const fetchAsset: typeof fetch = async (input) => {
  const url = String(input);
  const body = assets.get(url);
  return new Response(body ?? '', {
    status: body === undefined ? 404 : 200,
    headers: { 'content-type': url.endsWith('.json') ? 'application/json' : 'text/css' },
  });
};

function reset(): void {
  __resetThemeStateForTest();
  __resetUserStateForTest();
  __resetCatalogForTest();
  document.documentElement.removeAttribute('data-theme');
}

describe('built-in palettes through the production appearance writer', () => {
  beforeEach(reset);
  afterEach(() => {
    vi.restoreAllMocks();
    reset();
  });

  for (const id of paletteIds) {
    for (const mode of ['light', 'dark'] as const) {
      it(`loads, validates and applies ${id} in ${mode} without rejected tokens`, async () => {
        const debug = vi.spyOn(console, 'debug');

        // Capture a loader rejection as an assertion failure in the evidence JSON report.
        const loadError = await applyAppearance({ theme: mode, paletteId: id }, fetchAsset)
          .then(() => null, (error: unknown) => error);
        expect(loadError, `${id} must load through the appearance writer`).toBeNull();

        expect(debug.mock.calls.filter(([message]) => String(message).includes('validation failed')))
          .toEqual([]);
        expect(document.documentElement.getAttribute('data-theme')).toBe(mode);
        expect(getActiveThemeId()).toBe(id);
        expect(paletteCss()).toHaveLength(1);
        expect(paletteCss()[0]).toContain('@layer user-theme');
        expect(paletteCss()[0]).toContain(':root');
        const css = assets.get(`/themes/${id}.css`);
        if (css !== undefined) expect(paletteCss()[0]).toContain(css);
        if (id === 'core.high-vis') {
          expect(paletteCss()[0]).toContain('--focus-ring-color: #008890;');
        }
      });
    }
  }
});
