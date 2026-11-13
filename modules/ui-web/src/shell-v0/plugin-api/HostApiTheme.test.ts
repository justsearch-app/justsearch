// @vitest-environment happy-dom

/**
 * Tempdoc 560 §23/§24 — the plugin `theme` capability.
 *
 * Reads (`getTokens`) are available at every tier. The WRITES (`previewTokens`, `selectTheme`) are
 * trust-attenuated: UNTRUSTED structurally omits them (createThemeApi returns the read-only subset),
 * so an untrusted plugin cannot restyle the app — §4.4. `previewTokens` injects a per-plugin scoped
 * constructed stylesheet owned by the plugin from {name→value} pairs; every name is
 * allowlisted against KNOWN_TOKEN_NAMES AND every value is sanitized (rejecting brace/angle-bracket
 * breakout characters) — the plugin can never inject a raw CSS rule.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createHostApi } from './HostApiImpl.js';
import type { PluginTrustTier } from './plugin-types.js';
import { KNOWN_TOKEN_NAMES } from '../themes/designTokenTree.js';
import { __resetCatalogForTest } from '../themes/themesCatalog.js';
import { __resetUserStateForTest } from '../state/UserStateDocument.js';

import { clearTokenPreview } from '../themes/tokenIntrospection.js';

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

const PLUGIN_ID = 'token-editor';
const PREVIEW_ID = `jf-plugin-token-preview--${PLUGIN_ID}`;

function theme(tier: PluginTrustTier = 'TRUSTED_PLUGIN') {
  return createHostApi(PLUGIN_ID, tier, {
    apiBase: 'http://test.local',
    registerSurfacePort: () => {},
  }).theme;
}

function knownToken(): string {
  return [...KNOWN_TOKEN_NAMES][0]!;
}

afterEach(() => {
  clearTokenPreview(PREVIEW_ID);
});

describe('host.theme.getTokens — read, every tier (560 §23)', () => {
  it('returns one entry per KNOWN_TOKEN_NAMES with name + category + widgetType', () => {
    const tokens = theme('UNTRUSTED_PLUGIN').getTokens();
    expect(tokens.length).toBe(KNOWN_TOKEN_NAMES.size);
    const sample = tokens[0]!;
    expect(typeof sample.name).toBe('string');
    expect(['color', 'number', 'angle', 'duration', 'text']).toContain(sample.widgetType);
    for (const t of tokens) expect(KNOWN_TOKEN_NAMES.has(t.name)).toBe(true);
  });
});

describe('host.theme write attenuation by tier (560 §24)', () => {
  it('UNTRUSTED structurally omits previewTokens AND selectTheme', () => {
    const t = theme('UNTRUSTED_PLUGIN');
    expect(typeof t.getTokens).toBe('function');
    expect(t.previewTokens).toBeUndefined();
    expect(t.selectTheme).toBeUndefined();
  });

  it('TRUSTED_PLUGIN exposes the writes', () => {
    const t = theme('TRUSTED_PLUGIN');
    expect(typeof t.previewTokens).toBe('function');
    expect(typeof t.selectTheme).toBe('function');
  });

  it('CORE exposes the writes', () => {
    expect(typeof theme('CORE').previewTokens).toBe('function');
  });
});

describe('host.theme.previewTokens — value-only, sanitized (560 §23/§24)', () => {
  it('injects a host-generated @layer user-theme sheet (per-plugin owner) for a known token', () => {
    const name = knownToken();
    theme().previewTokens!(new Map([[name, 'rgb(1, 2, 3)']]));
    expect(paletteCss()).toHaveLength(1);
    const css = paletteCss()[0];
    expect(css).toContain('@layer user-theme');
    expect(css).toContain(`--${name}: rgb(1, 2, 3);`);
  });

  it('throws on an unknown token and writes nothing (allowlist closure)', () => {
    expect(() => theme().previewTokens!(new Map([['definitely-not-a-token', 'red']]))).toThrow(
      /unknown token/,
    );
    expect(paletteCss()).toHaveLength(0);
  });

  it('throws on a brace/angle-bracket value and writes NOTHING — no CSS-rule injection', () => {
    const name = knownToken();
    expect(() =>
      theme().previewTokens!(new Map([[name, 'red} html{display:none']])),
    ).toThrow(/break out of the :root rule|brace/);
    // the malicious value never reached the DOM
    expect(paletteCss()).toHaveLength(0);
  });

  it('writes nothing if ANY value is unsafe (validate-before-write)', () => {
    const name = knownToken();
    expect(() =>
      theme().previewTokens!(
        new Map([
          [name, 'red'],
          [[...KNOWN_TOKEN_NAMES][1]!, '<script>'],
        ]),
      ),
    ).toThrow();
    expect(paletteCss()).toHaveLength(0);
  });

  it('clears the preview on an empty map', () => {
    const name = knownToken();
    theme().previewTokens!(new Map([[name, 'green']]));
    expect(paletteCss()).toHaveLength(1);
    theme().previewTokens!(new Map());
    expect(paletteCss()).toHaveLength(0);
  });
});

describe('host.theme custom-theme lifecycle — save/list/delete (567)', () => {
  beforeEach(() => {
    __resetUserStateForTest();
    __resetCatalogForTest();
  });

  const seed = () => [...KNOWN_TOKEN_NAMES].find((n) => n.startsWith('h-'))!;

  it('UNTRUSTED has listThemes (read) but structurally omits saveTheme/deleteTheme (writes)', () => {
    const t = theme('UNTRUSTED_PLUGIN');
    expect(typeof t.listThemes).toBe('function');
    expect(t.saveTheme).toBeUndefined();
    expect(t.deleteTheme).toBeUndefined();
  });

  it('TRUSTED saveTheme persists + appears in listThemes as custom; deleteTheme removes it', () => {
    const t = theme('TRUSTED_PLUGIN');
    expect(t.listThemes().some((x) => x.id === 'custom.my')).toBe(false);
    t.saveTheme!({ id: 'custom.my', displayName: 'My Theme', tokens: { [seed()]: '270' } });
    const listed = t.listThemes().find((x) => x.id === 'custom.my');
    expect(listed).toBeDefined();
    expect(listed!.isCustom).toBe(true);
    expect(listed!.displayName).toBe('My Theme');
    t.deleteTheme!('custom.my');
    expect(t.listThemes().some((x) => x.id === 'custom.my')).toBe(false);
  });

  it('saveTheme rejects an invalid theme (unknown token name)', () => {
    const t = theme('TRUSTED_PLUGIN');
    expect(() =>
      t.saveTheme!({ id: 'custom.bad', displayName: 'Bad', tokens: { 'definitely-not-a-token': 'x' } }),
    ).toThrow();
    expect(t.listThemes().some((x) => x.id === 'custom.bad')).toBe(false);
  });

  it('save replaces an existing theme by id (no duplicate)', () => {
    const t = theme('TRUSTED_PLUGIN');
    t.saveTheme!({ id: 'custom.x', displayName: 'X v1', tokens: { [seed()]: '100' } });
    t.saveTheme!({ id: 'custom.x', displayName: 'X v2', tokens: { [seed()]: '200' } });
    const matches = t.listThemes().filter((x) => x.id === 'custom.x');
    expect(matches.length).toBe(1);
    expect(matches[0]!.displayName).toBe('X v2');
  });
});
