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
        const style = document.getElementById('jf-active-theme');
        expect(style?.dataset.themeId).toBe(id);
        expect(style?.textContent).toContain('@layer user-theme');
        expect(style?.textContent).toContain(':root');
        const css = assets.get(`/themes/${id}.css`);
        if (css !== undefined) expect(style?.textContent).toContain(css);
        if (id === 'core.high-vis') {
          expect(style?.textContent).toContain('--focus-ring-color: #008890;');
        }
      });
    }
  }
});
