// @vitest-environment happy-dom

/**
 * 569 Fix D — one apply writer + one catalog.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  applyPresentation,
  previewPresentation,
  listPresentations,
  getPresentation,
  revertPresentation,
  restoreActivePresentationOnBoot,
} from './presentationState.js';
import { activeBodyFor, __resetPresentationForTest } from './presentationRuntime.js';
import { SETTINGS_INTERFACE_REGION } from '../themes/builtinPresentations.js';
import { saveCustomPresentation } from '../themes/presentationCatalog.js';
import { getDocument, __resetUserStateForTest } from './UserStateDocument.js';

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
afterEach(() => vi.restoreAllMocks());
const markLayer = (sheet: CSSStyleSheet, text: string) => {
  const layer = /^@layer\s+([\w-]+)/.exec(text)?.[1];
  if (layer && !sheetLayer.has(sheet)) sheetLayer.set(sheet, layer);
};
// Current text of every adopted sheet first written with the given layer.
const layerCss = (layer: string) => document.adoptedStyleSheets
  .filter((sheet) => sheetLayer.get(sheet) === layer).map((sheet) => sheetText.get(sheet) ?? '');
const paletteCss = () => layerCss('user-theme');

beforeEach(() => {
  __resetPresentationForTest();
  document.adoptedStyleSheets = [];
});

describe('569 §19 Seam 5/6 — persist active declaration + versioned revert', () => {
  beforeEach(() => {
    __resetUserStateForTest();
    __resetPresentationForTest();
  });

  const A = { schemaVersion: 1 as const, id: 'user.skin-a', displayName: 'Skin A' };
  const B = { schemaVersion: 1 as const, id: 'user.skin-b', displayName: 'Skin B' };

  it('persists the active id + dedup-consecutive history, and reverts to the prior declaration', () => {
    expect(saveCustomPresentation(A).ok).toBe(true);
    expect(saveCustomPresentation(B).ok).toBe(true);
    applyPresentation(A);
    applyPresentation(A); // consecutive same id → not a new history entry
    applyPresentation(B);
    expect(getDocument().activePresentationId).toBe('user.skin-b');
    expect((getDocument().presentationHistory ?? []).map((h) => h.presentationId)).toEqual([
      'user.skin-a',
      'user.skin-b',
    ]);

    const reverted = revertPresentation();
    expect(reverted?.ok).toBe(true);
    expect(getDocument().activePresentationId).toBe('user.skin-a');
    expect((getDocument().presentationHistory ?? []).map((h) => h.presentationId)).toEqual([
      'user.skin-a',
    ]);
  });

  it('revert is a no-op with fewer than two history entries', () => {
    saveCustomPresentation(A);
    applyPresentation(A);
    expect(revertPresentation()).toBeNull();
  });

  it('boot restore re-applies the persisted active declaration (not the built-in default)', () => {
    saveCustomPresentation(A);
    applyPresentation(A);
    __resetPresentationForTest(); // simulate reload: runtime store cleared, the doc persists
    const res = restoreActivePresentationOnBoot();
    expect(res.ok).toBe(true);
    expect(getDocument().activePresentationId).toBe('user.skin-a');
    // the stamped origin is preserved through save → user
    expect(getPresentation('user.skin-a')?.origin?.kind).toBe('user');
  });
});

describe('applyPresentation (the one writer)', () => {
  it('routes the theme tier to the appearance writer AND the body tier to the runtime store', () => {
    const res = applyPresentation({
      schemaVersion: 1,
      id: 'user.combined',
      displayName: 'Combined',
      theme: { tokens: { 'accent-tint': '#3366cc' } },
      body: {
        [SETTINGS_INTERFACE_REGION]: { schema: {}, uischema: { type: 'VerticalLayout', elements: [] } },
      },
    });
    expect(res.ok).toBe(true);
    // body tier applied
    expect(activeBodyFor(SETTINGS_INTERFACE_REGION)).toBeDefined();
    // theme tier applied through the one appearance writer (adopted @layer user-theme sheet)
    expect(paletteCss()).toHaveLength(1);
    expect(paletteCss()[0]).toContain('--accent-tint: #3366cc');
  });

  it('does not apply a hard-invalid declaration', () => {
    const res = applyPresentation({ schemaVersion: 1, id: 'bad', displayName: 'B', cssText: 'x' });
    expect(res.ok).toBe(false);
    expect(activeBodyFor(SETTINGS_INTERFACE_REGION)).toBeUndefined();
  });
});

describe('the one catalog', () => {
  it('lists built-in declarations and resolves by id', () => {
    expect(listPresentations().some((p) => p.id === 'builtin.settings-declared')).toBe(true);
    expect(getPresentation('builtin.settings-declared')?.displayName).toBe('Settings (declared)');
  });
});

describe('previewPresentation', () => {
  it('certifies without applying (no theme injected)', () => {
    const r = previewPresentation({
      schemaVersion: 1,
      id: 'preview.only',
      displayName: 'P',
      theme: { tokens: { 'accent-tint': '#abcdef' } },
    });
    expect(r.verdict.ok).toBe(true);
    // not applied: the appearance writer injected nothing for this candidate
    expect(paletteCss()).toHaveLength(0);
  });
});
