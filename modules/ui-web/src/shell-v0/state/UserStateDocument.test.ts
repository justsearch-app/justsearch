// @vitest-environment happy-dom

/**
 * Slice 478 §4.B — UserStateDocument tests.
 *
 * Covers:
 *   - Default shape on first boot (no localStorage data)
 *   - Persistence: mutation writes to consolidated key
 *   - Migration: V1.5 alpha legacy keys → V1.5.1 consolidated doc
 *   - Subscription: full document + projection (referential equality)
 *   - Forward-compat: malformed consolidated doc → fallback to defaults;
 *     legacy keys NOT deleted on migration (revertibility)
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  getDocument,
  subscribeDocument,
  subscribeProjection,
  mutateDocument,
  __resetUserStateForTest,
  __resetInMemoryStateForTest,
  __seedLegacyForTest,
  __DOCUMENT_STORAGE_KEY,
  __LEGACY_USER_CONFIG_KEY,
  __LEGACY_ACTIVE_THEME_KEY,
  getActiveProfileId,
  setActiveProfileId,
  listProfiles,
  getProfile,
  createProfile,
  renameProfile,
  deleteProfile,
  subscribeActiveProfileId,
  subscribeProfileSwitch,
  DEFAULT_PROFILE_ID,
} from './UserStateDocument.js';

describe('UserStateDocument — defaults + persistence', () => {
  beforeEach(() => {
    __resetUserStateForTest();
  });

  afterEach(() => {
    __resetUserStateForTest();
  });

  it('returns default document on first read', () => {
    const doc = getDocument();
    // §11.3 / §13.3 — V2: storage version is 2; userConfig is
    // flattened from the active 'default' profile.
    expect(doc.version).toBe(2);
    expect(doc.activeProfileId).toBe('default');
    expect(doc.userConfig).toEqual({ version: 1 });
    expect(doc.activeThemeId).toBeNull();
  });

  it('mutateDocument writes to consolidated localStorage key', () => {
    mutateDocument((doc) => ({ ...doc, activeThemeId: 'core.foo' }));
    const raw = localStorage.getItem(__DOCUMENT_STORAGE_KEY);
    expect(raw).not.toBeNull();
    const parsed = JSON.parse(raw!);
    // V2 storage: per-profile slices live under profiles[id].
    expect(parsed.version).toBe(2);
    expect(parsed.activeProfileId).toBe('default');
    expect(parsed.profiles.default.activeThemeId).toBe('core.foo');
  });

  it('mutateDocument updates in-memory snapshot', () => {
    mutateDocument((doc) => ({ ...doc, activeThemeId: 'core.bar' }));
    expect(getDocument().activeThemeId).toBe('core.bar');
  });

  it('persists surfaceMode cross-profile (top level) and reloads it from storage (567 §9.4)', () => {
    mutateDocument((doc) => ({ ...doc, surfaceMode: 'solid' }));
    const raw = JSON.parse(localStorage.getItem(__DOCUMENT_STORAGE_KEY)!);
    // Cross-profile: at the document top level, NOT under profiles[id].
    expect(raw.surfaceMode).toBe('solid');
    expect(raw.profiles.default.surfaceMode).toBeUndefined();
    // Drop the in-memory cache (keep localStorage) → the value re-parses on next read.
    __resetInMemoryStateForTest();
    expect(getDocument().surfaceMode).toBe('solid');
  });

  it('drops a malformed surfaceMode on parse (567 §9.4)', () => {
    mutateDocument((doc) => ({ ...doc, activeThemeId: 'x' }));
    const raw = JSON.parse(localStorage.getItem(__DOCUMENT_STORAGE_KEY)!);
    raw.surfaceMode = 'translucent'; // not a valid literal
    localStorage.setItem(__DOCUMENT_STORAGE_KEY, JSON.stringify(raw));
    __resetInMemoryStateForTest();
    expect(getDocument().surfaceMode).toBeUndefined();
  });

  it('mutateDocument with same reference does not fire listeners', () => {
    const listener = vi.fn();
    subscribeDocument(listener);
    listener.mockClear();
    // Producer returns the same doc — no change.
    mutateDocument((doc) => doc);
    expect(listener).not.toHaveBeenCalled();
  });
});

describe('UserStateDocument — V1.5 alpha → V1.5.1 migration', () => {
  beforeEach(() => {
    __resetUserStateForTest();
  });

  afterEach(() => {
    __resetUserStateForTest();
  });

  it('migrates legacy userConfig key into consolidated doc', () => {
    __seedLegacyForTest({
      userConfig: {
        version: 1,
        surfaceVisibility: { 'core.help-surface': false },
      },
    });
    const doc = getDocument();
    expect(doc.userConfig.surfaceVisibility).toEqual({
      'core.help-surface': false,
    });
  });

  it('migrates legacy activeTheme key into consolidated doc', () => {
    __seedLegacyForTest({ activeThemeId: 'core.nord' });
    const doc = getDocument();
    expect(doc.activeThemeId).toBe('core.nord');
  });

  it('migrates BOTH legacy keys when present', () => {
    __seedLegacyForTest({
      userConfig: { version: 1, surfaceOrder: ['a', 'b'] },
      activeThemeId: 'core.sepia',
    });
    const doc = getDocument();
    expect(doc.userConfig.surfaceOrder).toEqual(['a', 'b']);
    expect(doc.activeThemeId).toBe('core.sepia');
  });

  it('legacy keys are LEFT IN PLACE after migration (revertibility)', () => {
    __seedLegacyForTest({
      userConfig: { version: 1, surfaceOrder: ['a'] },
      activeThemeId: 'core.nord',
    });
    getDocument(); // triggers migration
    // V1.5 alpha → V1.5.1 leaves the legacy keys; V1.5.2 cleanup
    // can delete them later. This means a revert to V1.5 alpha
    // reads the legacy keys correctly.
    expect(localStorage.getItem(__LEGACY_USER_CONFIG_KEY)).not.toBeNull();
    expect(localStorage.getItem(__LEGACY_ACTIVE_THEME_KEY)).not.toBeNull();
  });

  it('falls back to defaults when legacy userConfig is malformed', () => {
    localStorage.setItem(__LEGACY_USER_CONFIG_KEY, 'not json');
    const doc = getDocument();
    // Default userConfig.
    expect(doc.userConfig).toEqual({ version: 1 });
  });

  it('falls back to defaults when consolidated doc is malformed', () => {
    localStorage.setItem(__DOCUMENT_STORAGE_KEY, 'not json');
    const doc = getDocument();
    // Falls back to default doc (and migration runs since
    // consolidated parse failed). V2 default.
    expect(doc.version).toBe(2);
  });

  it('rejects consolidated doc with unknown schemaVersion', () => {
    localStorage.setItem(
      __DOCUMENT_STORAGE_KEY,
      JSON.stringify({ version: 99, userConfig: {}, activeThemeId: null }),
    );
    const doc = getDocument();
    // Unknown version → fall back to default + migration. V2.
    expect(doc.version).toBe(2);
  });
});

describe('UserStateDocument — subscription', () => {
  beforeEach(() => {
    __resetUserStateForTest();
  });

  afterEach(() => {
    __resetUserStateForTest();
  });

  it('subscribeDocument fires once on subscribe + on every mutation', () => {
    const listener = vi.fn();
    subscribeDocument(listener);
    expect(listener).toHaveBeenCalledTimes(1);
    mutateDocument((doc) => ({ ...doc, activeThemeId: 'a' }));
    expect(listener).toHaveBeenCalledTimes(2);
    mutateDocument((doc) => ({ ...doc, activeThemeId: 'b' }));
    expect(listener).toHaveBeenCalledTimes(3);
  });

  it('subscribeProjection only fires when selected slice changes', () => {
    const listener = vi.fn();
    subscribeProjection((doc) => doc.activeThemeId, listener);
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenLastCalledWith(null);
    // Unrelated mutation — projection unchanged → no fire.
    mutateDocument((doc) => ({
      ...doc,
      userConfig: { ...doc.userConfig, surfaceOrder: ['x'] },
    }));
    expect(listener).toHaveBeenCalledTimes(1);
    // Related mutation — projection changes → listener fires.
    mutateDocument((doc) => ({ ...doc, activeThemeId: 'a' }));
    expect(listener).toHaveBeenCalledTimes(2);
    expect(listener).toHaveBeenLastCalledWith('a');
  });

  it('unsubscribe stops further notifications', () => {
    const listener = vi.fn();
    const off = subscribeDocument(listener);
    listener.mockClear();
    off();
    mutateDocument((doc) => ({ ...doc, activeThemeId: 'after-unsub' }));
    expect(listener).not.toHaveBeenCalled();
  });
});

// Slice 486 G36 — pinnedSearches slice tests.
describe('UserStateDocument — pinnedSearches (G36)', () => {
  beforeEach(() => {
    __resetUserStateForTest();
  });

  afterEach(() => {
    __resetUserStateForTest();
  });

  it('default document has empty pinnedSearches array', () => {
    expect(getDocument().pinnedSearches).toEqual([]);
  });

  it('round-trips pinnedSearches through localStorage', () => {
    const pin = { id: 'pin-1', query: 'configuration', pinnedAt: 1700000000000, runs: [] };
    mutateDocument((doc) => ({
      ...doc,
      pinnedSearches: [pin],
    }));
    const raw = localStorage.getItem(__DOCUMENT_STORAGE_KEY);
    expect(raw).not.toBeNull();
    const parsed = JSON.parse(raw!);
    // §11.3 — pinnedSearches lives under the active profile in V2.
    expect(parsed.profiles.default.pinnedSearches).toEqual([pin]);
    // Re-read through getDocument after reset to confirm validation
    // path also accepts what we just wrote.
    __resetUserStateForTest();
    localStorage.setItem(__DOCUMENT_STORAGE_KEY, raw!);
    expect(getDocument().pinnedSearches).toEqual([pin]);
  });

  it('drops malformed pin entries while keeping valid ones', () => {
    const validPin = { id: 'good', query: 'q', pinnedAt: 1, runs: [] };
    const malformed = [
      validPin,
      { id: 123, query: 'q', pinnedAt: 1 }, // wrong type id
      { id: 'x', pinnedAt: 1 }, // missing query
      null,
      'not-an-object',
    ];
    localStorage.setItem(
      __DOCUMENT_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: null,
        pinnedSearches: malformed,
      }),
    );
    __resetUserStateForTest();
    // Re-seed without clearing localStorage
    localStorage.setItem(
      __DOCUMENT_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: null,
        pinnedSearches: malformed,
      }),
    );
    expect(getDocument().pinnedSearches).toEqual([validPin]);
  });

  it('falls back to [] when pinnedSearches is not an array', () => {
    localStorage.setItem(
      __DOCUMENT_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: null,
        pinnedSearches: 'not-an-array',
      }),
    );
    __resetUserStateForTest();
    localStorage.setItem(
      __DOCUMENT_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: null,
        pinnedSearches: 'not-an-array',
      }),
    );
    expect(getDocument().pinnedSearches).toEqual([]);
  });

  it('falls back to [] when pinnedSearches is missing entirely', () => {
    localStorage.setItem(
      __DOCUMENT_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: null,
        // pinnedSearches omitted — backward-compat with V1.5.1 alpha
      }),
    );
    __resetUserStateForTest();
    localStorage.setItem(
      __DOCUMENT_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: null,
      }),
    );
    expect(getDocument().pinnedSearches).toEqual([]);
  });

  it('legacy migration seeds empty pinnedSearches array', () => {
    __seedLegacyForTest({
      userConfig: { version: 1 },
      activeThemeId: 'core.nord',
    });
    expect(getDocument().pinnedSearches).toEqual([]);
  });

  // Slice 486 G36-widening (run-history) — pin.runs schema extension.

  it('defaults pin.runs to [] for pins without the field', () => {
    localStorage.setItem(
      __DOCUMENT_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: null,
        pinnedSearches: [{ id: 'a', query: 'q', pinnedAt: 1 }],
      }),
    );
    __resetUserStateForTest();
    localStorage.setItem(
      __DOCUMENT_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: null,
        pinnedSearches: [{ id: 'a', query: 'q', pinnedAt: 1 }],
      }),
    );
    expect(getDocument().pinnedSearches).toEqual([
      { id: 'a', query: 'q', pinnedAt: 1, runs: [] },
    ]);
  });

  it('round-trips pin.runs through localStorage', () => {
    const pin = {
      id: 'pin-2',
      query: 'configuration',
      pinnedAt: 1,
      runs: [
        { ranAt: 1700000001000, totalHits: 17 },
        { ranAt: 1700000002000, totalHits: 3 },
      ],
    };
    mutateDocument((doc) => ({ ...doc, pinnedSearches: [pin] }));
    const raw = localStorage.getItem(__DOCUMENT_STORAGE_KEY)!;
    __resetUserStateForTest();
    localStorage.setItem(__DOCUMENT_STORAGE_KEY, raw);
    expect(getDocument().pinnedSearches).toEqual([pin]);
  });

  it('drops malformed run entries while keeping valid ones', () => {
    const goodRun = { ranAt: 1, totalHits: 5 };
    const pinWithMixedRuns = {
      id: 'a',
      query: 'q',
      pinnedAt: 1,
      runs: [
        goodRun,
        { ranAt: 'not-a-number', totalHits: 5 },
        { totalHits: 5 }, // missing ranAt
        null,
        'not-an-object',
      ],
    };
    localStorage.setItem(
      __DOCUMENT_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: null,
        pinnedSearches: [pinWithMixedRuns],
      }),
    );
    __resetUserStateForTest();
    localStorage.setItem(
      __DOCUMENT_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: null,
        pinnedSearches: [pinWithMixedRuns],
      }),
    );
    expect(getDocument().pinnedSearches).toEqual([
      { id: 'a', query: 'q', pinnedAt: 1, runs: [goodRun] },
    ]);
  });

  // Slice 486 G36-widening (filter-snapshot) — pin.filterSpec extension.

  it('round-trips pin.filterSpec through localStorage', () => {
    const pin = {
      id: 'pin-fs',
      query: 'configuration',
      pinnedAt: 1,
      runs: [],
      filterSpec: { modifiedFromMs: 1700000000000, modifiedToMs: 1800000000000 },
    };
    mutateDocument((doc) => ({ ...doc, pinnedSearches: [pin] }));
    const raw = localStorage.getItem(__DOCUMENT_STORAGE_KEY)!;
    __resetUserStateForTest();
    localStorage.setItem(__DOCUMENT_STORAGE_KEY, raw);
    expect(getDocument().pinnedSearches).toEqual([pin]);
  });

  it('drops malformed filterSpec bounds; if both drop, omits filterSpec', () => {
    const pin = {
      id: 'pin-bad',
      query: 'q',
      pinnedAt: 1,
      runs: [],
      filterSpec: { modifiedFromMs: 'oops', modifiedToMs: 'also-oops' },
    };
    localStorage.setItem(
      __DOCUMENT_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: null,
        pinnedSearches: [pin],
      }),
    );
    __resetUserStateForTest();
    localStorage.setItem(
      __DOCUMENT_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: null,
        pinnedSearches: [pin],
      }),
    );
    expect(getDocument().pinnedSearches).toEqual([
      { id: 'pin-bad', query: 'q', pinnedAt: 1, runs: [] },
    ]);
  });

  it('preserves filterSpec when one bound is malformed (drops only the bad bound)', () => {
    const pin = {
      id: 'pin-half',
      query: 'q',
      pinnedAt: 1,
      runs: [],
      filterSpec: { modifiedFromMs: 1700000000000, modifiedToMs: 'oops' },
    };
    localStorage.setItem(
      __DOCUMENT_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: null,
        pinnedSearches: [pin],
      }),
    );
    __resetUserStateForTest();
    localStorage.setItem(
      __DOCUMENT_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: null,
        pinnedSearches: [pin],
      }),
    );
    expect(getDocument().pinnedSearches).toEqual([
      {
        id: 'pin-half',
        query: 'q',
        pinnedAt: 1,
        runs: [],
        filterSpec: { modifiedFromMs: 1700000000000 },
      },
    ]);
  });

  it('absent filterSpec field is fine (default = pin without filter)', () => {
    localStorage.setItem(
      __DOCUMENT_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: null,
        pinnedSearches: [
          { id: 'p', query: 'q', pinnedAt: 1, runs: [] },
        ],
      }),
    );
    __resetUserStateForTest();
    localStorage.setItem(
      __DOCUMENT_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: null,
        pinnedSearches: [
          { id: 'p', query: 'q', pinnedAt: 1, runs: [] },
        ],
      }),
    );
    expect(getDocument().pinnedSearches[0]).toEqual({
      id: 'p',
      query: 'q',
      pinnedAt: 1,
      runs: [],
    });
    expect(
      (getDocument().pinnedSearches[0] as { filterSpec?: unknown }).filterSpec,
    ).toBeUndefined();
  });

  it('falls back to [] when pin.runs is not an array', () => {
    localStorage.setItem(
      __DOCUMENT_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: null,
        pinnedSearches: [{ id: 'a', query: 'q', pinnedAt: 1, runs: 'oops' }],
      }),
    );
    __resetUserStateForTest();
    localStorage.setItem(
      __DOCUMENT_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: null,
        pinnedSearches: [{ id: 'a', query: 'q', pinnedAt: 1, runs: 'oops' }],
      }),
    );
    expect(getDocument().pinnedSearches).toEqual([
      { id: 'a', query: 'q', pinnedAt: 1, runs: [] },
    ]);
  });
});

describe('UserStateDocument — §12.2 validateV1 restores previously-dropped slices', () => {
  beforeEach(() => {
    __resetUserStateForTest();
  });

  afterEach(() => {
    __resetUserStateForTest();
  });

  function seedDoc(extra: Record<string, unknown>): void {
    localStorage.setItem(
      __DOCUMENT_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: null,
        pinnedSearches: [],
        ...extra,
      }),
    );
    __resetUserStateForTest();
    // After reset, re-seed (reset clears localStorage).
    localStorage.setItem(
      __DOCUMENT_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: null,
        pinnedSearches: [],
        ...extra,
      }),
    );
  }

  it('preserves acknowledgedAdvisories array', () => {
    seedDoc({ acknowledgedAdvisories: ['op-a@123', 'op-b@456'] });
    expect(getDocument().acknowledgedAdvisories).toEqual(['op-a@123', 'op-b@456']);
  });

  it('drops non-string entries in acknowledgedAdvisories', () => {
    seedDoc({ acknowledgedAdvisories: ['valid', 42, null, 'also-valid'] });
    expect(getDocument().acknowledgedAdvisories).toEqual(['valid', 'also-valid']);
  });

  it('preserves pluginSettings nested object', () => {
    seedDoc({
      pluginSettings: { 'plugin-a': { theme: 'dark', size: 12 } },
    });
    expect(getDocument().pluginSettings).toEqual({
      'plugin-a': { theme: 'dark', size: 12 },
    });
  });

  it('drops malformed plugin settings entries but keeps the rest', () => {
    seedDoc({
      pluginSettings: {
        good: { a: 1 },
        bad1: 'not-an-object',
        bad2: null,
        bad3: ['array'],
      },
    });
    expect(getDocument().pluginSettings).toEqual({ good: { a: 1 } });
  });

  it('preserves recentCommandIds', () => {
    seedDoc({ recentCommandIds: ['cmd-a', 'cmd-b', 'cmd-c'] });
    expect(getDocument().recentCommandIds).toEqual(['cmd-a', 'cmd-b', 'cmd-c']);
  });

  it('caps recentCommandIds at 10', () => {
    seedDoc({
      recentCommandIds: Array.from({ length: 15 }, (_, i) => `cmd-${i}`),
    });
    expect(getDocument().recentCommandIds).toHaveLength(10);
    expect(getDocument().recentCommandIds?.[0]).toBe('cmd-0');
  });

  it('preserves keybindingOverrides', () => {
    seedDoc({
      keybindingOverrides: [
        { key: 'ctrl+k', commandId: 'core.palette', source: 'user' },
        { key: 'ctrl+p', commandId: 'core.previous', source: 'user' },
      ],
    });
    const overrides = getDocument().keybindingOverrides;
    expect(overrides).toHaveLength(2);
    expect(overrides?.[0]).toEqual({
      key: 'ctrl+k',
      commandId: 'core.palette',
      source: 'user',
    });
  });

  it('normalizes keybindingOverrides source to "user"', () => {
    seedDoc({
      keybindingOverrides: [
        { key: 'ctrl+x', commandId: 'core.cut', source: 'plugin' },
      ],
    });
    expect(getDocument().keybindingOverrides?.[0]?.source).toBe('user');
  });

  it('drops malformed keybindingOverrides entries', () => {
    seedDoc({
      keybindingOverrides: [
        { key: 'ctrl+k', commandId: 'core.palette' },
        { key: 42, commandId: 'x' },
        null,
        { commandId: 'no-key' },
      ],
    });
    expect(getDocument().keybindingOverrides).toHaveLength(1);
  });

  it('round-trips through mutate + reload', () => {
    seedDoc({
      acknowledgedAdvisories: ['ack-1'],
      pluginSettings: { p: { x: 1 } },
      recentCommandIds: ['c-1'],
      keybindingOverrides: [{ key: 'ctrl+k', commandId: 'core.palette', source: 'user' }],
    });
    const first = getDocument();
    // Trigger a mutation that touches an unrelated slice; the four
    // restored slices must survive serialization + reload.
    mutateDocument((d) => ({ ...d, activeThemeId: 'theme.x' }));
    __resetUserStateForTest();
    // Re-seed from the persisted state.
    localStorage.setItem(
      __DOCUMENT_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: 'theme.x',
        pinnedSearches: [],
        acknowledgedAdvisories: ['ack-1'],
        pluginSettings: { p: { x: 1 } },
        recentCommandIds: ['c-1'],
        keybindingOverrides: [{ key: 'ctrl+k', commandId: 'core.palette', source: 'user' }],
      }),
    );
    const second = getDocument();
    expect(second.acknowledgedAdvisories).toEqual(first.acknowledgedAdvisories);
    expect(second.pluginSettings).toEqual(first.pluginSettings);
    expect(second.recentCommandIds).toEqual(first.recentCommandIds);
    expect(second.keybindingOverrides).toEqual(first.keybindingOverrides);
  });
});

describe('UserStateDocument — §11.3 Profiles V2', () => {
  beforeEach(() => {
    __resetUserStateForTest();
  });

  afterEach(() => {
    __resetUserStateForTest();
  });

  it('starts with built-in default profile active', () => {
    expect(getActiveProfileId()).toBe(DEFAULT_PROFILE_ID);
    const profiles = listProfiles();
    expect(profiles).toHaveLength(1);
    expect(profiles[0]!.id).toBe(DEFAULT_PROFILE_ID);
  });

  it('createProfile adds a new profile based on default', () => {
    createProfile('focus', 'Focus Mode');
    expect(getProfile('focus')).toBeDefined();
    expect(getProfile('focus')!.label).toBe('Focus Mode');
    expect(listProfiles()).toHaveLength(2);
  });

  it('createProfile copies basis slices when basedOn is supplied', () => {
    mutateDocument((doc) => ({ ...doc, activeThemeId: 'core.nord' }));
    createProfile('research', 'Research', DEFAULT_PROFILE_ID);
    expect(getProfile('research')!.activeThemeId).toBe('core.nord');
  });

  it('createProfile throws on duplicate id', () => {
    createProfile('focus', 'Focus Mode');
    expect(() => createProfile('focus', 'Other')).toThrow(/already exists/);
  });

  it('setActiveProfileId switches the active profile atomically', () => {
    mutateDocument((doc) => ({ ...doc, activeThemeId: 'core.solarized' }));
    createProfile('night', 'Night');
    // Night starts as a copy of default → has the same theme.
    setActiveProfileId('night');
    expect(getActiveProfileId()).toBe('night');
    // Mutate night's theme.
    mutateDocument((doc) => ({ ...doc, activeThemeId: 'core.nord' }));
    expect(getDocument().activeThemeId).toBe('core.nord');
    // Switch back; original theme restored.
    setActiveProfileId(DEFAULT_PROFILE_ID);
    expect(getDocument().activeThemeId).toBe('core.solarized');
    // Switch to night again; night's theme.
    setActiveProfileId('night');
    expect(getDocument().activeThemeId).toBe('core.nord');
  });

  it('setActiveProfileId throws on unknown id', () => {
    expect(() => setActiveProfileId('does-not-exist')).toThrow(/does not exist/);
  });

  it('renameProfile updates the label only', () => {
    createProfile('focus', 'Focus');
    renameProfile('focus', 'Focus Mode V2');
    expect(getProfile('focus')!.label).toBe('Focus Mode V2');
    expect(getProfile('focus')!.id).toBe('focus');
  });

  it('deleteProfile removes a non-active profile', () => {
    createProfile('focus', 'Focus');
    deleteProfile('focus');
    expect(getProfile('focus')).toBeUndefined();
    expect(listProfiles()).toHaveLength(1);
  });

  it('deleteProfile refuses to delete the default profile', () => {
    expect(() => deleteProfile(DEFAULT_PROFILE_ID)).toThrow(/cannot delete the 'default'/);
  });

  it('deleteProfile refuses to delete the active profile', () => {
    createProfile('focus', 'Focus');
    setActiveProfileId('focus');
    expect(() => deleteProfile('focus')).toThrow(/cannot delete the active/);
  });

  it('subscribeActiveProfileId fires on switch', () => {
    createProfile('focus', 'Focus');
    const listener = vi.fn();
    subscribeActiveProfileId(listener);
    listener.mockClear();
    setActiveProfileId('focus');
    expect(listener).toHaveBeenCalledWith('focus');
  });

  it('cross-profile slices persist across profile switch', () => {
    mutateDocument((doc) => ({
      ...doc,
      acknowledgedAdvisories: ['advisory-1'],
      recentCommandIds: ['cmd-1'],
    }));
    createProfile('focus', 'Focus');
    setActiveProfileId('focus');
    expect(getDocument().acknowledgedAdvisories).toEqual(['advisory-1']);
    expect(getDocument().recentCommandIds).toEqual(['cmd-1']);
  });

  it('per-profile slices isolated across profiles', () => {
    // Default profile has theme A and a pin.
    mutateDocument((doc) => ({
      ...doc,
      activeThemeId: 'core.alpha',
      pinnedSearches: [{ id: 'pin-a', query: 'alpha', pinnedAt: 1, runs: [] }],
    }));
    createProfile('beta', 'Beta');
    setActiveProfileId('beta');
    // Beta inherited from default — same theme + pin.
    expect(getDocument().activeThemeId).toBe('core.alpha');
    // Mutate beta.
    mutateDocument((doc) => ({
      ...doc,
      activeThemeId: 'core.beta',
      pinnedSearches: [{ id: 'pin-b', query: 'beta', pinnedAt: 2, runs: [] }],
    }));
    // Switch back to default → original state.
    setActiveProfileId(DEFAULT_PROFILE_ID);
    expect(getDocument().activeThemeId).toBe('core.alpha');
    expect(getDocument().pinnedSearches[0]!.query).toBe('alpha');
  });

  it('V1 legacy doc migrates forward into the default profile', () => {
    // Seed a V1 doc directly into localStorage.
    localStorage.setItem(
      'justsearch.userState.v1',
      JSON.stringify({
        version: 1,
        userConfig: { version: 1, surfaceOrder: ['core.search-surface'] },
        activeThemeId: 'core.nord',
        pinnedSearches: [{ id: 'p', query: 'q', pinnedAt: 1, runs: [] }],
      }),
    );
    __resetUserStateForTest();
    // Re-seed since reset cleared. (Test helper resets memory only;
    // localStorage was also cleared, so we re-seed before reading.)
    localStorage.setItem(
      'justsearch.userState.v1',
      JSON.stringify({
        version: 1,
        userConfig: { version: 1, surfaceOrder: ['core.search-surface'] },
        activeThemeId: 'core.nord',
        pinnedSearches: [{ id: 'p', query: 'q', pinnedAt: 1, runs: [] }],
      }),
    );
    const doc = getDocument();
    expect(doc.version).toBe(2);
    expect(doc.activeProfileId).toBe('default');
    expect(doc.userConfig.surfaceOrder).toEqual(['core.search-v3-surface']);
    expect(doc.activeThemeId).toBe('core.nord');
    expect(doc.pinnedSearches).toHaveLength(1);
    // V2 written to v2 key.
    expect(localStorage.getItem('justsearch.userState.v2')).not.toBeNull();
  });

  // Tempdoc 507/508-merge T1.2 — V1 legacy doc carrying slice-501
  // savedViews + 511-followup-A viewerAudience must lift those fields
  // onto the default profile during the V1→V2 migration. Pre-merge,
  // main treated savedViews + viewerAudience as flat V1 fields; the
  // merge moved them onto Profile. This test pins that the migration
  // path doesn't drop them silently when reading a pre-merge V1 doc.
  it('V1 legacy savedViews + viewerAudience lift into the default profile', () => {
    localStorage.setItem(
      'justsearch.userState.v1',
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: null,
        pinnedSearches: [],
        savedViews: [
          {
            id: 'sv1',
            label: 'My Search',
            url: 'justsearch://surface/core.search-surface?query=hi',
            surfaceId: 'core.search-surface',
            savedAt: 123,
          },
        ],
        viewerAudience: 'OPERATOR',
      }),
    );
    __resetUserStateForTest();
    localStorage.setItem(
      'justsearch.userState.v1',
      JSON.stringify({
        version: 1,
        userConfig: { version: 1 },
        activeThemeId: null,
        pinnedSearches: [],
        savedViews: [
          {
            id: 'sv1',
            label: 'My Search',
            url: 'justsearch://surface/core.search-surface?query=hi',
            surfaceId: 'core.search-surface',
            savedAt: 123,
          },
        ],
        viewerAudience: 'OPERATOR',
      }),
    );

    const doc = getDocument();
    expect(doc.version).toBe(2);
    expect(doc.activeProfileId).toBe('default');
    // Per-profile fields lifted onto the active profile's flat slices.
    expect(doc.savedViews).toHaveLength(1);
    expect(doc.savedViews?.[0]?.id).toBe('sv1');
    expect(doc.savedViews?.[0]?.url).toBe(
      'justsearch://surface/core.search-surface?query=hi',
    );
    expect(doc.viewerAudience).toBe('OPERATOR');
    // The Profile record itself also carries them (the V1.5 view is a
    // flat-slice projection of profiles[active]).
    const profile = doc.profiles[doc.activeProfileId];
    expect(profile?.savedViews).toHaveLength(1);
    expect(profile?.viewerAudience).toBe('OPERATOR');
  });

  // Tempdoc 507/508-merge T1.2 — write-then-read round-trip: write a
  // V2 doc that carries per-profile savedViews + viewerAudience, then
  // re-parse it from localStorage. The validateV2 path must preserve
  // both fields per-profile (not collapse them to a root field).
  it('V2 round-trip preserves per-profile savedViews + viewerAudience', () => {
    // Mutate the in-memory doc to set savedViews + viewerAudience on the
    // active profile, then force a re-read from localStorage.
    mutateDocument((d) => ({
      ...d,
      savedViews: [
        {
          id: 'rt1',
          label: 'Round-trip',
          url: 'justsearch://surface/core.search-surface',
          surfaceId: 'core.search-surface',
          savedAt: 456,
        },
      ],
      viewerAudience: 'DEVELOPER',
    }));
    const raw = localStorage.getItem('justsearch.userState.v2');
    expect(raw).not.toBeNull();
    __resetUserStateForTest();
    localStorage.setItem('justsearch.userState.v2', raw!);
    const reloaded = getDocument();
    expect(reloaded.savedViews?.[0]?.id).toBe('rt1');
    expect(reloaded.viewerAudience).toBe('DEVELOPER');
    // The per-profile slice on the Profile record itself must also have them.
    const profile = reloaded.profiles[reloaded.activeProfileId];
    expect(profile?.savedViews?.[0]?.id).toBe('rt1');
    expect(profile?.viewerAudience).toBe('DEVELOPER');
  });
});

describe('UserStateDocument — subscribeProfileSwitch (tempdoc 508-followup §β4)', () => {
  beforeEach(() => {
    __resetUserStateForTest();
  });

  afterEach(() => {
    __resetUserStateForTest();
  });

  it('does NOT fire on subscribe (initial-fire suppressed)', () => {
    const handler = vi.fn();
    const off = subscribeProfileSwitch(handler);
    expect(handler).not.toHaveBeenCalled();
    off();
  });

  it('fires when the active profile changes', () => {
    createProfile('work', 'Work');
    const handler = vi.fn();
    const off = subscribeProfileSwitch(handler);
    setActiveProfileId('work');
    expect(handler).toHaveBeenCalledTimes(1);
    expect(handler).toHaveBeenCalledWith('work');
    off();
  });

  it('does not fire when setActiveProfileId is called with the current id (no-op)', () => {
    createProfile('work', 'Work');
    setActiveProfileId('work');
    const handler = vi.fn();
    const off = subscribeProfileSwitch(handler);
    setActiveProfileId('work');
    expect(handler).not.toHaveBeenCalled();
    off();
  });

  it('does not fire on unrelated document mutations', () => {
    const handler = vi.fn();
    const off = subscribeProfileSwitch(handler);
    mutateDocument((d) => ({ ...d, recentCommandIds: ['x'] }));
    expect(handler).not.toHaveBeenCalled();
    off();
  });

  it('unsubscribe stops further handler calls', () => {
    createProfile('a', 'A');
    createProfile('b', 'B');
    const handler = vi.fn();
    const off = subscribeProfileSwitch(handler);
    setActiveProfileId('a');
    off();
    setActiveProfileId('b');
    expect(handler).toHaveBeenCalledTimes(1);
  });
});
