// @vitest-environment happy-dom

/**
 * Tests for slice 474 alpha — Theme Manifest L1 (palette themes).
 *
 * Covers:
 *  - applyTheme adopts a constructed stylesheet with the CSS text
 *  - applyTheme replaces the existing sheet on subsequent calls
 *  - clearActiveTheme removes the sheet + sets state to null
 *  - subscribers receive initial value + change notifications
 *  - loadAndApplyTheme fetches `/themes/<id>.css` and applies it
 *  - loadAndApplyTheme throws on non-2xx response
 */

import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import {
  __resetThemeStateForTest,
  applyTheme,
  applyAppearance,
  restoreAppearanceOnBoot,
  clearActiveTheme,
  getActiveThemeId,
  getSurfaceMode,
  loadAndApplyTheme,
  setSurfaceMode,
  subscribeActiveTheme,
} from './themeState.js';
import { __resetUserStateForTest } from './UserStateDocument.js';

// happy-dom does not retain @layer rules in cssRules; inspect what the real sheet receives.
let replaceSync: MockInstance<CSSStyleSheet['replaceSync']>;
beforeEach(() => { replaceSync = vi.spyOn(CSSStyleSheet.prototype, 'replaceSync'); });
afterEach(() => vi.restoreAllMocks());
// The last palette CSS written, only if the sheet that received it is adopted by the document.
const paletteCss = () => {
  const calls = replaceSync.mock.calls;
  let i = calls.length - 1;
  while (i >= 0 && !calls[i]![0].startsWith('@layer user-theme')) i--;
  return i >= 0 && document.adoptedStyleSheets.includes(replaceSync.mock.contexts[i] as CSSStyleSheet) ? calls[i]![0] : undefined;
};

describe('themeState (slice 474 alpha)', () => {
  beforeEach(() => {
    __resetThemeStateForTest();
  });

  afterEach(() => {
    __resetThemeStateForTest();
  });

  it('applyTheme adopts a constructed stylesheet', () => {
    applyTheme('core.solarized', ':root { --accent-tint: #b58900; }');
    expect(document.adoptedStyleSheets).toContain(replaceSync.mock.contexts.at(-1));
    expect(paletteCss()).toContain('--accent-tint: #b58900');
    expect(getActiveThemeId()).toBe('core.solarized');
  });

  it('applyTheme replaces the existing sheet on subsequent calls (one style at a time)', () => {
    applyTheme('core.nord', ':root { --accent-tint: #88c0d0; }');
    const sheets = [...document.adoptedStyleSheets];
    applyTheme('core.solarized', ':root { --accent-tint: #b58900; }');
    expect(document.adoptedStyleSheets).toEqual(sheets);
    // Exactly one adopted sheet carries a palette.
    const paletteSheets = new Set(replaceSync.mock.calls.flatMap(([css], i) =>
      css.startsWith('@layer user-theme') ? [replaceSync.mock.contexts[i] as CSSStyleSheet] : []));
    expect(document.adoptedStyleSheets.filter((sheet) => paletteSheets.has(sheet))).toHaveLength(1);
    expect(paletteCss()).toContain('#b58900');
    expect(getActiveThemeId()).toBe('core.solarized');
  });

  it('clearActiveTheme removes the sheet and resets state', () => {
    applyTheme('core.nord', ':root { --accent-tint: #88c0d0; }');
    const sheet = replaceSync.mock.contexts.at(-1);
    clearActiveTheme();
    expect(document.adoptedStyleSheets).not.toContain(sheet);
    expect(getActiveThemeId()).toBeNull();
  });

  it('subscribers receive initial state (null) on subscribe', () => {
    const listener = vi.fn();
    const unsub = subscribeActiveTheme(listener);
    expect(listener).toHaveBeenCalledWith(null);
    unsub();
  });

  it('subscribers receive change notifications', () => {
    const listener = vi.fn();
    const unsub = subscribeActiveTheme(listener);
    listener.mockClear();
    applyTheme('core.nord', ':root { --accent-tint: #88c0d0; }');
    expect(listener).toHaveBeenCalledWith('core.nord');
    clearActiveTheme();
    expect(listener).toHaveBeenCalledWith(null);
    unsub();
  });

  it('subscribers do not double-fire when applyTheme called with same id', () => {
    const listener = vi.fn();
    subscribeActiveTheme(listener);
    listener.mockClear();
    applyTheme('core.nord', ':root { --accent-tint: #aaa; }');
    applyTheme('core.nord', ':root { --accent-tint: #bbb; }'); // same id, different css
    // CSS text updates, but the theme id didn't change → listener fires once.
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('loadAndApplyTheme fetches /themes/<id>.css and applies', async () => {
    const fakeFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: () => Promise.resolve(':root { --accent-tint: #88c0d0; }'),
    } as unknown as Response);
    await loadAndApplyTheme('core.nord', fakeFetch as unknown as typeof fetch);
    expect(fakeFetch).toHaveBeenCalledWith('/themes/core.nord.css');
    expect(getActiveThemeId()).toBe('core.nord');
    expect(paletteCss()).toContain(
      '#88c0d0',
    );
  });

  it('loadAndApplyTheme throws on non-2xx response (both .json and .css fail)', async () => {
    // 478 §4.E: loader tries .json first, falls back to .css.
    // Both must fail for the load to throw.
    const fakeFetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      text: () => Promise.resolve(''),
      json: () => Promise.resolve({}),
    } as unknown as Response);
    await expect(
      loadAndApplyTheme('missing.theme', fakeFetch as unknown as typeof fetch),
    ).rejects.toThrow(/neither.*\.json nor.*\.css.*loaded successfully.*404/);
  });

  it('encodes theme id safely in the URL', async () => {
    const fakeFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: () => Promise.resolve(':root {}'),
    } as unknown as Response);
    await loadAndApplyTheme('vendor/foo bar', fakeFetch as unknown as typeof fetch);
    // encodeURIComponent encodes both '/' and ' '
    expect(fakeFetch).toHaveBeenCalledWith('/themes/vendor%2Ffoo%20bar.css');
  });
});

describe('themeState — §2.C / C5 single appearance writer', () => {
  afterEach(() => {
    document.documentElement.removeAttribute('data-theme');
    document.documentElement.classList.remove('high-contrast');
    vi.restoreAllMocks();
    __resetThemeStateForTest();
  });

  /**
   * Stubs `window.matchMedia('(prefers-color-scheme: dark)')` and returns a `fireChange` hook
   * so a test can simulate the OS flipping its preference live, mirroring the
   * `DocumentPane.test.ts` `stubReducedMotion` pattern for a `change`-capable mock.
   */
  function stubOsColorScheme(isDark: boolean): { fireChange: (nowDark: boolean) => void } {
    let changeHandler: ((e: { matches: boolean }) => void) | null = null;
    let matches = isDark;
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (query: string) =>
        ({
          matches,
          media: query,
          addEventListener: (_type: string, handler: (e: { matches: boolean }) => void) => {
            changeHandler = handler;
          },
          removeEventListener: () => {},
        }) as unknown as MediaQueryList,
    );
    return {
      fireChange: (nowDark: boolean) => {
        matches = nowDark;
        changeHandler?.({ matches: nowDark });
      },
    };
  }

  it('applyAppearance sets data-theme=light and toggles high-contrast', () => {
    applyAppearance({ theme: 'light', highContrast: true });
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    expect(document.documentElement.classList.contains('high-contrast')).toBe(true);
  });

  it('applyAppearance resolves system to light on a light-mode OS (tempdoc 727 F-5)', () => {
    stubOsColorScheme(false);
    applyAppearance({ theme: 'light', highContrast: true });
    applyAppearance({ theme: 'system', highContrast: false });
    // Pre-fix this removed the attribute and left tokens.css's hardcoded-dark :root default —
    // "Follow OS" silently meant "app default (dark)" even when the OS was light.
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    expect(document.documentElement.classList.contains('high-contrast')).toBe(false);
  });

  it('applyAppearance resolves system to dark on a dark-mode OS (tempdoc 727 F-5)', () => {
    stubOsColorScheme(true);
    applyAppearance({ theme: 'system' });
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('system live-follows an OS preference change without re-calling applyAppearance (tempdoc 727 F-5)', () => {
    const os = stubOsColorScheme(false);
    applyAppearance({ theme: 'system' });
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');

    // OS flips to dark while "system" is still the active choice — the round observed exactly
    // this happening spontaneously mid-session, with no user action.
    os.fireChange(true);
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('an explicit light/dark choice stops following subsequent OS preference changes', () => {
    const os = stubOsColorScheme(false);
    applyAppearance({ theme: 'system' });
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');

    applyAppearance({ theme: 'dark' }); // explicit choice — must not track the OS anymore
    os.fireChange(true); // OS "changes" to dark too, but that's incidental here
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');

    os.fireChange(false); // OS flips back to light — explicit dark must hold
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('applyAppearance sets data-surface-mode=solid and removes it for glass (§9.4)', () => {
    applyAppearance({ surfaceMode: 'solid' });
    expect(document.documentElement.getAttribute('data-surface-mode')).toBe('solid');
    applyAppearance({ surfaceMode: 'glass' });
    expect(document.documentElement.hasAttribute('data-surface-mode')).toBe(false);
    document.documentElement.removeAttribute('data-surface-mode');
  });

  it('setSurfaceMode applies live AND persists; getSurfaceMode reads it back (default glass) (§9.4)', () => {
    __resetUserStateForTest();
    expect(getSurfaceMode()).toBe('glass'); // default when unset
    setSurfaceMode('solid');
    expect(document.documentElement.getAttribute('data-surface-mode')).toBe('solid');
    expect(getSurfaceMode()).toBe('solid'); // persisted in the user-state document
    setSurfaceMode('glass');
    expect(getSurfaceMode()).toBe('glass');
    document.documentElement.removeAttribute('data-surface-mode');
    __resetUserStateForTest();
  });

  it('restoreAppearanceOnBoot replays the persisted solid surface mode (§9.4)', async () => {
    __resetUserStateForTest();
    setSurfaceMode('solid');
    document.documentElement.removeAttribute('data-surface-mode'); // wipe the live DOM
    const fakeFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ ui: {} }),
    } as unknown as Response);
    await restoreAppearanceOnBoot(fakeFetch as unknown as typeof fetch);
    expect(document.documentElement.getAttribute('data-surface-mode')).toBe('solid');
    document.documentElement.removeAttribute('data-surface-mode');
    __resetUserStateForTest();
  });

  it('restoreAppearanceOnBoot replays the persisted appearance from /api/settings/v2', async () => {
    const fakeFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ ui: { theme: 'light', highContrast: true } }),
    } as unknown as Response);
    await restoreAppearanceOnBoot(fakeFetch as unknown as typeof fetch);
    expect(fakeFetch).toHaveBeenCalledWith('/api/settings/v2');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    expect(document.documentElement.classList.contains('high-contrast')).toBe(true);
  });

  it('restoreAppearanceOnBoot is a no-op (no throw) when settings are unreachable', async () => {
    const fakeFetch = vi.fn().mockRejectedValue(new Error('offline'));
    await expect(
      restoreAppearanceOnBoot(fakeFetch as unknown as typeof fetch),
    ).resolves.toBe(false);
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false);
  });

  it('C1: restoreAppearanceOnBoot replays BOTH layers from ONE call (settings + persisted palette)', async () => {
    // Persist a palette id, then wipe the live DOM so the boot path must re-apply both layers.
    applyTheme('core.testpalette', ':root { --accent-tint: #abc; }');
    document.adoptedStyleSheets = [];
    document.documentElement.removeAttribute('data-theme');

    // Route by URL: settings → ui prefs; palette .json 404 → .css served.
    const fakeFetch = vi.fn(async (url: string) => {
      const u = String(url);
      if (u.includes('/api/settings/v2')) {
        return { ok: true, json: () => Promise.resolve({ ui: { theme: 'light', highContrast: true } }) };
      }
      if (u.endsWith('.json')) return { ok: false, status: 404, json: () => Promise.resolve({}) };
      if (u.endsWith('.css')) {
        return { ok: true, status: 200, text: () => Promise.resolve(':root { --accent-tint: #abc; }') };
      }
      return { ok: false, status: 404, text: () => Promise.resolve('') };
    });

    await restoreAppearanceOnBoot(fakeFetch as unknown as typeof fetch);

    // Layer 1 — data-theme + high-contrast from settings.
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    expect(document.documentElement.classList.contains('high-contrast')).toBe(true);
    // Layer 2 — the persisted palette was re-injected, from the SAME boot call.
    expect(paletteCss()).toContain('--accent-tint: #abc');
    const paletteCall = replaceSync.mock.calls.findIndex(([css]) => css.startsWith('@layer user-theme'));
    expect(document.adoptedStyleSheets).toContain(replaceSync.mock.contexts[paletteCall]);
    expect(getActiveThemeId()).toBe('core.testpalette');
  });
});

describe('themeState — V1.5.1 persistence', () => {
  beforeEach(() => {
    __resetThemeStateForTest();
  });

  afterEach(() => {
    __resetThemeStateForTest();
  });

  // 478 §4.B refactor + 508 §11.3 V2: persistence consolidated
  // under 'justsearch.userState.v2'; activeThemeId lives inside
  // the active profile (profiles[activeProfileId].activeThemeId).
  const DOC_KEY = 'justsearch.userState.v2';
  const readActiveThemeId = (): string | null => {
    const raw = localStorage.getItem(DOC_KEY);
    if (raw === null) return null;
    try {
      const doc = JSON.parse(raw);
      const profile = doc.profiles?.[doc.activeProfileId];
      return profile?.activeThemeId ?? null;
    } catch {
      return null;
    }
  };

  it('applyTheme persists themeId to localStorage (consolidated document)', () => {
    applyTheme('core.solarized', ':root { --accent-tint: #b58900; }');
    expect(readActiveThemeId()).toBe('core.solarized');
  });

  it('clearActiveTheme clears the persisted themeId', () => {
    applyTheme('core.nord', ':root { --accent-tint: #88c0d0; }');
    expect(readActiveThemeId()).toBe('core.nord');
    clearActiveTheme();
    expect(readActiveThemeId()).toBeNull();
  });

  it('__resetThemeStateForTest clears localStorage', () => {
    applyTheme('core.nord', ':root { --accent-tint: #88c0d0; }');
    __resetThemeStateForTest();
    expect(readActiveThemeId()).toBeNull();
  });

  // Note: boot-time restoration via `restoreActiveThemeIfPersisted`
  // is verified via the equivalent live-smoke (apply Nord → reload
  // page → theme reapplied automatically).
});
