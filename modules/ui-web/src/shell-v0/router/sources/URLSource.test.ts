/**
 * @vitest-environment happy-dom
 *
 * URLSource tests — slice 492 tier-1 substrate.
 *
 * Covers the boot-read + popstate listener mechanics. The source emits
 * Intent envelopes into the supplied dispatcher; parsing is delegated to
 * the pure `parseUrl` (covered by parser.test.ts).
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createURLSource } from './URLSource.js';
import type { DispatchOptions } from '../intentRouter.js';
import type { Intent } from '../types.js';

interface DispatchCall {
  intent: Intent;
  options?: DispatchOptions;
}

function makeDispatch(): {
  dispatch: (intent: Intent, options?: DispatchOptions) => void;
  calls: DispatchCall[];
} {
  const calls: DispatchCall[] = [];
  const dispatch = (intent: Intent, options?: DispatchOptions) => {
    calls.push({ intent, options });
  };
  return { dispatch, calls };
}

beforeEach(() => {
  window.location.hash = '';
});

afterEach(() => {
  window.location.hash = '';
});

describe('URLSource — boot read', () => {
  it('dispatches the parsed Intent when hash starts with #justsearch://', () => {
    window.location.hash = '#justsearch://surface/core.search-surface?query=hello';
    const source = createURLSource();
    const { dispatch, calls } = makeDispatch();
    source.start(dispatch);
    expect(calls).toHaveLength(1);
    const first = calls[0]!;
    expect(first.intent.transport).toBe('URL_BAR');
    expect(first.intent.address.kind).toBe('navigate');
    if (first.intent.address.kind === 'navigate') {
      expect(first.intent.address.target).toBe('core.search-surface');
      expect(first.intent.address.state).toEqual({ query: 'hello' });
    }
    expect(first.options).toEqual({ pushHistory: true, taskOrigin: 'restore' });
  });

  it('emits nothing when no hash is present', () => {
    const source = createURLSource();
    const { dispatch, calls } = makeDispatch();
    source.start(dispatch);
    expect(calls).toEqual([]);
  });

  it('emits nothing when hash is unrelated (no justsearch:// prefix)', () => {
    window.location.hash = '#some-anchor';
    const source = createURLSource();
    const { dispatch, calls } = makeDispatch();
    source.start(dispatch);
    expect(calls).toEqual([]);
  });

  it('emits nothing on malformed justsearch URLs', () => {
    window.location.hash = '#justsearch://gibberish-with-no-segment';
    const source = createURLSource();
    const { dispatch, calls } = makeDispatch();
    source.start(dispatch);
    expect(calls).toEqual([]);
  });
});

describe('URLSource — popstate', () => {
  it('dispatches with pushHistory: false on popstate (browser already moved history)', () => {
    const source = createURLSource();
    const { dispatch, calls } = makeDispatch();
    const teardown = source.start(dispatch);
    // Now simulate a popstate with a new hash.
    window.location.hash = '#justsearch://surface/core.library-surface';
    window.dispatchEvent(new PopStateEvent('popstate'));
    expect(calls.length).toBeGreaterThan(0);
    const last = calls[calls.length - 1]!;
    expect(last.options).toEqual({ pushHistory: false, taskOrigin: 'restore' });
    expect(last.intent.address.kind).toBe('navigate');
    if (typeof teardown === 'function') teardown();
  });

  it('teardown removes the popstate listener', () => {
    const source = createURLSource();
    const { dispatch, calls } = makeDispatch();
    const teardown = source.start(dispatch);
    expect(typeof teardown).toBe('function');
    if (typeof teardown === 'function') teardown();
    const before = calls.length;
    window.location.hash = '#justsearch://surface/core.library-surface';
    window.dispatchEvent(new PopStateEvent('popstate'));
    expect(calls.length).toBe(before);
  });
});

describe('URLSource — SSR safety', () => {
  it('returns a no-op teardown when no window is available', () => {
    const source = createURLSource({ windowImpl: undefined as unknown as Window });
    const { dispatch, calls } = makeDispatch();
    const teardown = source.start(dispatch);
    expect(calls).toEqual([]);
    expect(typeof teardown).toBe('function');
    if (typeof teardown === 'function') teardown();
  });
});


describe('DS1 native URL provenance is bounded and conservative', () => {
  function nativeSource() {
    const listeners = new Map<string, EventListener>();
    const navigation = {
      addEventListener: (name: string, callback: EventListener) => listeners.set(name, callback),
      removeEventListener: (name: string) => listeners.delete(name),
    };
    const win = {
      location: { hash: '', href: 'https://local.test/' }, navigation,
      addEventListener: (name: string, callback: EventListener) => listeners.set(name, callback),
      removeEventListener: (name: string) => listeners.delete(name),
    } as unknown as Window;
    const { dispatch, calls } = makeDispatch();
    const teardown = createURLSource({ windowImpl: win }).start(dispatch);
    const stop = () => { if (typeof teardown === 'function') teardown(); };
    const url = 'https://local.test/#justsearch://surface/core.search-v3-surface?query=needle';
    const observe = (type = 'push', destination = url, extra = {}) => listeners.get('navigate')?.({
      isTrusted: true, defaultPrevented: false, navigationType: type, signal: new AbortController().signal,
      destination: { url: destination }, ...extra,
    } as unknown as Event);
    const pop = () => {
      win.location.hash = '#justsearch://surface/core.search-v3-surface?query=needle'; win.location.href = url;
      listeners.get('popstate')?.({ isTrusted: true } as unknown as Event);
    };
    return { calls, observe, pop, stop, listeners };
  }
  it('consumes a matching native push once; traversal/reload and missing metadata restore', () => {
    const fixture = nativeSource();
    fixture.observe(); fixture.pop();
    expect(fixture.calls.at(-1)?.options?.taskOrigin).toBe('fresh');
    fixture.pop(); expect(fixture.calls.at(-1)?.options?.taskOrigin).toBe('restore');
    for (const type of ['traverse', 'reload']) {
      fixture.observe(type); fixture.pop(); expect(fixture.calls.at(-1)?.options?.taskOrigin).toBe('restore');
    }
    fixture.stop();
  });
  it('cancellation, stale/unmatched destinations, untrusted events and stop cannot authorize a push', () => {
    const fixture = nativeSource(); const controller = new AbortController();
    fixture.observe('push', undefined, { signal: controller.signal }); controller.abort();
    fixture.pop(); expect(fixture.calls.at(-1)?.options?.taskOrigin).toBe('restore');
    fixture.observe('push', 'https://local.test/#unmatched'); fixture.pop();
    expect(fixture.calls.at(-1)?.options?.taskOrigin).toBe('restore');
    for (const extra of [{ defaultPrevented: true }, { isTrusted: false }, { navigationType: undefined }, { signal: undefined }]) {
      fixture.observe(); fixture.observe('push', undefined, extra); fixture.pop();
      expect(fixture.calls.at(-1)?.options?.taskOrigin).toBe('restore');
    }
    fixture.observe(); fixture.stop(); expect(fixture.listeners.size).toBe(0);
  });
});

it.each(['navigate', 'reload', 'back_forward', undefined, 'unusable'] as const)(
  'DS1 boot NavigationTiming %s distinguishes fresh URL from automatic or unknown restoration', type => {
    const win = {
      location: { hash: '#justsearch://surface/core.search-v3-surface?query=old', href: 'https://local.test/#justsearch://surface/core.search-v3-surface?query=old' },
      performance: { getEntriesByType: () => type === undefined ? [] : [{ type }] },
      addEventListener: () => {}, removeEventListener: () => {},
    } as unknown as Window;
    const { dispatch, calls } = makeDispatch(); const teardown = createURLSource({ windowImpl: win }).start(dispatch);
    expect(calls).toHaveLength(1);
    expect(calls[0]!.options?.taskOrigin ?? 'fresh').toBe(type === 'navigate' ? 'fresh' : 'restore');
    if (typeof teardown === 'function') teardown();
  },
);
