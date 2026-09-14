// @vitest-environment happy-dom

/**
 * 569 §19 Seam 4 — the one adaptation/accessibility authority: persist per-profile + project to global
 * DOM state, omitted axes untouched.
 *
 * Tempdoc 855 §17 R1 — the contrast axis is retired here (the canonical store is the backend
 * `UISettings.highContrast`, written by `themeState.applyAppearance`). The contrast assertions this
 * file used to make are PORTED, not deleted: the "projects contrast" ones become the migration
 * contract below, and the "does not fight the legacy appearance contrast" one becomes the stronger
 * claim that this module no longer touches the class at all.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  applyAdaptationProfile,
  getAdaptationProfile,
  migrateLegacyContrastPreference,
} from './adaptationProfile.js';
import {
  __resetUserStateForTest,
  createProfile,
  getDocument,
  mutateDocument,
  setActiveProfileId,
} from './UserStateDocument.js';

beforeEach(() => {
  __resetUserStateForTest();
  document.documentElement.className = '';
});

describe('applyAdaptationProfile', () => {
  it('persists the axes per-profile and projects motion to a global class', () => {
    applyAdaptationProfile({ density: 'compact', motion: 'reduced' });
    expect(getAdaptationProfile()).toEqual({ density: 'compact', motion: 'reduced' });
    expect(getDocument().userConfig.density).toBe('compact'); // density threads via userConfig
    expect(document.documentElement.classList.contains('motion-reduced')).toBe(true);
  });

  it('merges partial updates (omitted axes untouched) and toggles back off', () => {
    applyAdaptationProfile({ density: 'compact' });
    applyAdaptationProfile({ motion: 'reduced' });
    expect(getAdaptationProfile()).toEqual({ density: 'compact', motion: 'reduced' });
    applyAdaptationProfile({ motion: 'full' });
    expect(document.documentElement.classList.contains('motion-reduced')).toBe(false);
    expect(getAdaptationProfile().density).toBe('compact'); // untouched
  });

  it('never writes the high-contrast class (that authority is applyAppearance alone)', () => {
    document.documentElement.classList.add('high-contrast'); // the appearance writer set it
    applyAdaptationProfile({ density: 'spacious', motion: 'reduced' });
    expect(document.documentElement.classList.contains('high-contrast')).toBe(true);
  });
});

/** Seed the retired FE-local axis the way a pre-855 build persisted it. */
function seedLegacyContrast(value: 'normal' | 'high'): void {
  mutateDocument((doc) => ({
    ...doc,
    userConfig: {
      ...doc.userConfig,
      accessibilityProfile: { ...doc.userConfig.accessibilityProfile, contrast: value },
    },
  }));
}

const legacyContrast = (): 'normal' | 'high' | undefined =>
  getDocument().userConfig.accessibilityProfile?.contrast;

const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const ZERO_WITNESS = { acceptedRevision: 0, lastCommittedOperationKey: null };

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function completeResponse(init: RequestInit): Response {
  const request = JSON.parse(String(init.body)) as {
    operationKey: string;
    witness: { acceptedRevision: number };
  };
  return jsonResponse({
    state: 'COMPLETE',
    operationKey: request.operationKey,
    witness: {
      acceptedRevision: request.witness.acceptedRevision + 1,
      lastCommittedOperationKey: request.operationKey,
    },
  });
}

function expectAttempt(init: RequestInit, desired: boolean, acceptedRevision = 0): Record<string, unknown> {
  const body = JSON.parse(String(init.body)) as Record<string, unknown>;
  expect(body).toEqual({
    ui: { highContrast: desired },
    witness: {
      acceptedRevision,
      lastCommittedOperationKey: acceptedRevision === 0 ? null : expect.stringMatching(UUID_V7),
    },
    operationKey: expect.stringMatching(UUID_V7),
  });
  return body;
}

/** A fetch double: GET returns `canonical`, POST records the body and succeeds (unless `postOk`). */
function fetchDouble(canonical: boolean, postOk = true) {
  const posts: RequestInit[] = [];
  const impl = vi.fn((_url: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      posts.push(init);
      return Promise.resolve(postOk
        ? completeResponse(init)
        : jsonResponse({ errorCode: 'VERSION_CONFLICT', retryable: false }, 409));
    }
    return Promise.resolve(jsonResponse({ ui: { highContrast: canonical }, witness: ZERO_WITNESS }));
  });
  return { impl: impl as unknown as typeof fetch, posts, calls: impl };
}

describe('855 §17 R1 — one-time contrast migration', () => {
  it('a disagreeing legacy value wins once: written through, projected, then cleared', async () => {
    seedLegacyContrast('high');
    const f = fetchDouble(false);
    await migrateLegacyContrastPreference(f.impl);
    expect(f.posts).toHaveLength(1);
    expectAttempt(f.posts[0]!, true);
    expect(document.documentElement.classList.contains('high-contrast')).toBe(true);
    expect(legacyContrast()).toBeUndefined();
  });

  it('migrates a legacy OFF over a canonical ON just as faithfully', async () => {
    seedLegacyContrast('normal');
    document.documentElement.classList.add('high-contrast'); // restoreAppearanceOnBoot applied canonical
    const f = fetchDouble(true);
    await migrateLegacyContrastPreference(f.impl);
    expect(f.posts).toHaveLength(1);
    expectAttempt(f.posts[0]!, false);
    expect(document.documentElement.classList.contains('high-contrast')).toBe(false);
    expect(legacyContrast()).toBeUndefined();
  });

  it('is idempotent — a second boot makes no request at all', async () => {
    seedLegacyContrast('high');
    const first = fetchDouble(false);
    await migrateLegacyContrastPreference(first.impl);
    const second = fetchDouble(false);
    await migrateLegacyContrastPreference(second.impl);
    expect(second.calls).not.toHaveBeenCalled();
    expect(second.posts).toEqual([]);
  });

  it('agreeing values write nothing (and still retire the axis)', async () => {
    seedLegacyContrast('high');
    const f = fetchDouble(true);
    await migrateLegacyContrastPreference(f.impl);
    expect(f.posts).toEqual([]);
    expect(legacyContrast()).toBeUndefined();
  });

  it('an unset legacy axis is a no-op with no network call', async () => {
    const f = fetchDouble(true);
    await migrateLegacyContrastPreference(f.impl);
    expect(f.calls).not.toHaveBeenCalled();
    expect(legacyContrast()).toBeUndefined();
  });

  it('keeps the legacy value when the write-through fails, so nothing is silently lost', async () => {
    seedLegacyContrast('high');
    const f = fetchDouble(false, /* postOk */ false);
    await migrateLegacyContrastPreference(f.impl);
    expect(f.posts).toHaveLength(1);
    expectAttempt(f.posts[0]!, true);
    expect(legacyContrast()).toBe('high'); // retried on the next boot
  });

  it('keeps the legacy value when the settings read fails (never guesses the canonical value)', async () => {
    seedLegacyContrast('high');
    const impl = vi.fn(() => Promise.reject(new Error('offline'))) as unknown as typeof fetch;
    await migrateLegacyContrastPreference(impl);
    expect(legacyContrast()).toBe('high');
  });

  it('requires a valid witness from the canonical SettingsV2 GET before posting', async () => {
    seedLegacyContrast('high');
    const impl = vi.fn((_url: string, init?: RequestInit) => Promise.resolve(
      init?.method === 'POST'
        ? completeResponse(init)
        : jsonResponse({ ui: { highContrast: false }, witness: { acceptedRevision: 1, lastCommittedOperationKey: null } }),
    )) as unknown as typeof fetch;

    await migrateLegacyContrastPreference(impl);

    expect(impl).toHaveBeenCalledTimes(1);
    expect(legacyContrast()).toBe('high');
  });

  it('keeps the captured marker on conflict and does not refresh with a second GET', async () => {
    seedLegacyContrast('high');
    const f = fetchDouble(false, /* postOk */ false);
    await migrateLegacyContrastPreference(f.impl);

    expect(f.calls).toHaveBeenCalledTimes(2);
    expect(f.calls.mock.calls.filter(([, init]) => init?.method !== 'POST')).toHaveLength(1);
    expectAttempt(f.posts[0]!, true);
    expect(legacyContrast()).toBe('high');
  });

  it('keeps the captured marker when the COMPLETE receipt times out', async () => {
    seedLegacyContrast('high');
    let postSignal!: AbortSignal;
    const calls = vi.fn((_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        postSignal = init.signal!;
        return new Promise<Response>((_resolve, reject) => {
          postSignal.addEventListener('abort', () => reject(postSignal.reason), { once: true });
        });
      }
      return Promise.resolve(jsonResponse({ ui: { highContrast: false }, witness: ZERO_WITNESS }));
    });
    const impl = calls as unknown as typeof fetch;

    vi.useFakeTimers();
    try {
      const migration = migrateLegacyContrastPreference(impl);
      await Promise.resolve();
      await Promise.resolve();
      await Promise.resolve();
      await vi.advanceTimersByTimeAsync(10_000);
      await migration;
    } finally {
      vi.useRealTimers();
    }

    expect(postSignal.aborted).toBe(true);
    expect(calls).toHaveBeenCalledTimes(2);
    expect(legacyContrast()).toBe('high');
  });

  it('replays one exact open attempt and clears only after COMPLETE', async () => {
    seedLegacyContrast('high');
    const posts: RequestInit[] = [];
    let gets = 0;
    let first = true;
    const impl = vi.fn((_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        posts.push(init);
        const request = JSON.parse(String(init.body)) as { operationKey: string };
        if (first) {
          first = false;
          return Promise.resolve(jsonResponse({ state: 'RUNNING', operationKey: request.operationKey }, 202));
        }
        return Promise.resolve(completeResponse(init));
      }
      gets += 1;
      return Promise.resolve(jsonResponse({ ui: { highContrast: false }, witness: ZERO_WITNESS }));
    }) as unknown as typeof fetch;

    const migration = migrateLegacyContrastPreference(impl);
    await new Promise((resolve) => setTimeout(resolve, 25));
    expect(posts).toHaveLength(1);
    expect(legacyContrast()).toBe('high');
    await new Promise((resolve) => setTimeout(resolve, 300));
    await migration;

    expect(posts).toHaveLength(2);
    expect(gets).toBe(1);
    expect(posts[1]!.body).toBe(posts[0]!.body);
    expect(legacyContrast()).toBeUndefined();
  });

  it('does not post when the active profile or captured value drifts before admission', async () => {
    createProfile('other', 'Other');
    seedLegacyContrast('high');
    let resolveGet!: (response: Response) => void;
    const calls = vi.fn((_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') return Promise.resolve(completeResponse(init));
      return new Promise<Response>((resolve) => { resolveGet = resolve; });
    });
    const impl = calls as unknown as typeof fetch;

    const migration = migrateLegacyContrastPreference(impl);
    await Promise.resolve();
    setActiveProfileId('other');
    resolveGet(jsonResponse({ ui: { highContrast: false }, witness: ZERO_WITNESS }));
    await migration;
    expect(calls.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0);
    expect(legacyContrast()).toBeUndefined();
    setActiveProfileId('default');
    expect(legacyContrast()).toBe('high');
  });

  it('does not clear another profile or a changed value after COMPLETE', async () => {
    createProfile('other', 'Other');
    seedLegacyContrast('high');
    let resolvePost!: (response: Response) => void;
    const posts: RequestInit[] = [];
    const impl = vi.fn((_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        posts.push(init);
        return new Promise<Response>((resolve) => { resolvePost = resolve; });
      }
      return Promise.resolve(jsonResponse({ ui: { highContrast: false }, witness: ZERO_WITNESS }));
    }) as unknown as typeof fetch;

    const migration = migrateLegacyContrastPreference(impl);
    await new Promise((resolve) => setTimeout(resolve, 25));
    expect(posts).toHaveLength(1);
    setActiveProfileId('other');
    mutateDocument((doc) => ({
      ...doc,
      userConfig: {
        ...doc.userConfig,
        accessibilityProfile: { ...doc.userConfig.accessibilityProfile, contrast: 'normal' },
      },
    }));
    resolvePost(completeResponse(posts[0]!));
    await migration;

    expect(legacyContrast()).toBe('normal');
    setActiveProfileId('default');
    expect(legacyContrast()).toBe('high');
  });

  it.each(['GET', 'POST'])('preserves a changed legacy value within the same profile during %s', async (phase) => {
    seedLegacyContrast('high');
    let release!: (response: Response) => void;
    let notify!: () => void;
    const reached = new Promise<void>((resolve) => { notify = resolve; });
    const posts: RequestInit[] = [];
    const calls = vi.fn((_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') posts.push(init);
      if ((init?.method ?? 'GET') === phase) {
        return new Promise<Response>((resolve) => { release = resolve; notify(); });
      }
      return Promise.resolve(jsonResponse({ ui: { highContrast: false }, witness: ZERO_WITNESS }));
    });
    const migration = migrateLegacyContrastPreference(calls as unknown as typeof fetch);
    await reached;
    seedLegacyContrast('normal');
    release(phase === 'GET'
      ? jsonResponse({ ui: { highContrast: false }, witness: ZERO_WITNESS })
      : completeResponse(posts[0]!));
    await migration;
    expect(legacyContrast()).toBe('normal');
    expect(posts).toHaveLength(phase === 'GET' ? 0 : 1);
    expect(document.documentElement.classList.contains('high-contrast')).toBe(false);
  });
});
