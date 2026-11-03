// @vitest-environment happy-dom
/** uiModeState — the app-wide Simple/Detailed authority (tempdoc 557 Q8). */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  enqueueUiModePersistence,
  enqueueUiModeSettings,
  UI_MODE_INTENT_HEADER,
  getUiMode,
  getUiModeRevision,
  isAdvancedMode,
  setUiMode,
  subscribeUiMode,
  __resetUiModeForTest,
} from './uiModeState.js';

describe('uiModeState', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    __resetUiModeForTest();
  });

  it('defaults to simple (hide advanced affordances until known)', () => {
    expect(getUiMode()).toBe('simple');
    expect(isAdvancedMode()).toBe(false);
  });

  it('resolves only "advanced" to advanced; everything else is simple', () => {
    setUiMode('advanced');
    expect(isAdvancedMode()).toBe(true);
    setUiMode('simple');
    expect(isAdvancedMode()).toBe(false);
    setUiMode(undefined);
    expect(getUiMode()).toBe('simple');
    setUiMode('garbage');
    expect(getUiMode()).toBe('simple');
  });

  it('notifies subscribers on change (and once synchronously on subscribe)', () => {
    const seen: string[] = [];
    const unsub = subscribeUiMode((m) => seen.push(m));
    expect(seen).toEqual(['simple']); // sync on subscribe
    setUiMode('advanced');
    expect(seen).toEqual(['simple', 'advanced']);
    setUiMode('advanced'); // no-op (unchanged) → no extra notify
    expect(seen).toEqual(['simple', 'advanced']);
    unsub();
    setUiMode('simple');
    expect(seen).toEqual(['simple', 'advanced']); // unsubscribed
  });

  it('increments its revision only when the shared choice changes', () => {
    expect(getUiModeRevision()).toBe(0);
    setUiMode('simple');
    expect(getUiModeRevision()).toBe(0);
    setUiMode('advanced');
    expect(getUiModeRevision()).toBe(1);
    setUiMode('advanced');
    expect(getUiModeRevision()).toBe(1);
    setUiMode('simple');
    expect(getUiModeRevision()).toBe(2);
  });

  it('serializes persistence submitted by different mode projections', async () => {
    const started: string[] = [];
    const releases: Array<() => void> = [];
    const submit = (owner: string): Promise<void> =>
      enqueueUiModePersistence(
        () => new Promise<void>((resolve) => {
          started.push(owner);
          releases.push(resolve);
        }),
      );

    const brain = submit('brain');
    const topbar = submit('topbar');
    const settings = submit('settings');
    await vi.waitFor(() => expect(started).toEqual(['brain']));

    releases.shift()?.();
    await brain;
    await vi.waitFor(() => expect(started).toEqual(['brain', 'topbar']));

    releases.shift()?.();
    await topbar;
    await vi.waitFor(() => expect(started).toEqual(['brain', 'topbar', 'settings']));

    releases.shift()?.();
    await settings;
  });

  it('aborts a timed-out writer and advances the queue', async () => {
    let firstAborted = false;
    const started: string[] = [];
    const intents: string[] = [];
    const first = enqueueUiModePersistence(
      (signal, intent) => new Promise<void>((_resolve, reject) => {
        started.push('first');
        intents.push(intent);
        signal.addEventListener('abort', () => {
          firstAborted = true;
          reject(signal.reason);
        }, { once: true });
      }),
      { timeoutMs: 5 },
    );
    const second = enqueueUiModePersistence(async (_signal, intent) => {
      started.push('second');
      intents.push(intent);
    });

    await expect(first).rejects.toMatchObject({ name: 'TimeoutError' });
    await expect(second).resolves.toBeUndefined();
    expect(firstAborted).toBe(true);
    expect(started).toEqual(['first', 'second']);
    expect(intents.map((intent) => Number(intent.slice(intent.lastIndexOf(':') + 1))))
      .toEqual([1, 2]);
  });

  it('keeps one monotonic ordering domain across a shell reload', async () => {
    const intents: string[] = [];
    await enqueueUiModePersistence(async (_signal, intent) => {
      intents.push(intent);
    });

    __resetUiModeForTest({ preservePersistence: true });
    await enqueueUiModePersistence(async (_signal, intent) => {
      intents.push(intent);
    });

    expect(intents).toHaveLength(2);
    const [beforeReload, afterReload] = intents.map((intent) => {
      const separator = intent.lastIndexOf(':');
      return {
        clientId: intent.slice(0, separator),
        sequence: Number(intent.slice(separator + 1)),
      };
    });
    expect(afterReload!.clientId).toBe(beforeReload!.clientId);
    expect(afterReload!.sequence).toBe(beforeReload!.sequence + 1);
  });

  it('times out a blocked cross-context sequence lock and advances the queue', async () => {
    let lockRequest = 0;
    const request = vi.fn((
      _name: string,
      options: { signal: AbortSignal },
      callback: () => string,
    ): Promise<string> => {
      lockRequest += 1;
      if (lockRequest > 1) return Promise.resolve(callback());
      return new Promise((_resolve, reject) => {
        options.signal.addEventListener('abort', () => reject(options.signal.reason), { once: true });
      });
    });
    vi.stubGlobal('navigator', { locks: { request } });

    const firstWriter = vi.fn(async () => undefined);
    const secondWriter = vi.fn(async () => undefined);
    const first = enqueueUiModePersistence(firstWriter, { timeoutMs: 5 });
    const second = enqueueUiModePersistence(secondWriter, { timeoutMs: 50 });

    await expect(first).rejects.toMatchObject({ name: 'TimeoutError' });
    await expect(second).resolves.toBeUndefined();
    expect(firstWriter).not.toHaveBeenCalled();
    expect(secondWriter).toHaveBeenCalledOnce();
  });
});

// These transport guarantees moved from the removed Brain page toggle to its owner.
describe('uiModeState witnessed settings writer', () => {
  beforeEach(() => __resetUiModeForTest());
  it('persists the compatible wire value and serializes rapid choices with fresh witnesses', async () => {
    let acceptedRevision = 0;
    let lastCommittedOperationKey: string | null = null;
    const posts: Array<{ body: Record<string, unknown>; intent: string | null }> = [];
    const releases: Array<() => void> = [];
    const send = async (_path: string, init?: RequestInit) => {
      if (init?.method !== 'POST') return new Response(JSON.stringify({ witness: { acceptedRevision, lastCommittedOperationKey } }));
      const body = JSON.parse(String(init.body));
      posts.push({ body, intent: new Headers(init.headers).get(UI_MODE_INTENT_HEADER) });
      await new Promise<void>(resolve => releases.push(resolve));
      acceptedRevision++;
      lastCommittedOperationKey = body.operationKey;
      return new Response(JSON.stringify({ state: 'COMPLETE', operationKey: body.operationKey, witness: { acceptedRevision, lastCommittedOperationKey } }));
    };
    const first = enqueueUiModeSettings(send, { ui: { mode: 'advanced' } });
    const second = enqueueUiModeSettings(send, { ui: { mode: 'simple' } });
    await vi.waitFor(() => expect(posts).toHaveLength(1));
    const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
    expect(posts[0]?.body).toEqual({ ui: { mode: 'advanced' }, witness: { acceptedRevision: 0, lastCommittedOperationKey: null }, operationKey: expect.stringMatching(uuid) });
    expect(posts[0]?.intent).toMatch(/:1$/);
    releases.shift()?.(); await first;
    await vi.waitFor(() => expect(posts).toHaveLength(2));
    expect(posts[1]?.body).toEqual({ ui: { mode: 'simple' }, witness: { acceptedRevision: 1, lastCommittedOperationKey: posts[0]!.body.operationKey }, operationKey: expect.stringMatching(uuid) });
    expect(posts[1]?.intent).toMatch(/:2$/);
    releases.shift()?.(); await second;
  });
  it('aborts a hung settings save and releases later intent', async () => {
    let aborted = false;
    const send = async (_path: string, init?: RequestInit) => {
      if (init?.method !== 'POST') return new Response(JSON.stringify({ witness: { acceptedRevision: 0, lastCommittedOperationKey: null } }));
      return new Promise<Response>((_resolve, reject) => init.signal?.addEventListener('abort', () => {
        aborted = true; reject(init.signal?.reason);
      }, { once: true }));
    };
    const first = enqueueUiModeSettings(send, { ui: { mode: 'advanced' } }, { timeoutMs: 10 });
    const later = vi.fn(async () => undefined);
    const second = enqueueUiModePersistence(later);
    await expect(first).rejects.toMatchObject({ code: 'SETTINGS_COMPLETION_UNKNOWN', message: 'Settings request timed out.' });
    await second;
    expect(aborted).toBe(true); expect(later).toHaveBeenCalledOnce();
  });
});
