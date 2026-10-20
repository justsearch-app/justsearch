// @vitest-environment happy-dom

/**
 * Slice 3a-1-8e (ship-option a, 2026-05-07) — tests for the SSE-to-
 * contractEvents bridge. Uses the same FakeEventSource pattern as
 * `shell-v0/handshake/CapabilitiesHandshake.test.ts`.
 *
 * Verifies that incoming UPDATE frames carrying ContractEventPayload
 * shapes are decoded + dispatched; that legacy CapabilityChangeEvent
 * payloads on the same channel are filtered out (structural
 * discrimination); that catalog-membership-changed events trigger
 * the registered refresh path.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// Incidental requests are outside these render/event scenarios. Keep their
// unavailable-backend behavior explicit, without contacting a real server.
const originalFetch = globalThis.fetch;
beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Test backend unavailable')));
});
afterEach(() => { globalThis.fetch = originalFetch; });
import {
  bootContractEventBridge,
  stopContractEventBridge,
  __isRunningForTest,
} from './contractEventsBridge.js';
import { __resetForTest, subscribe } from './contractEvents.js';
import type { SseEnvelope } from '../../shell-v0/streaming/envelope-types.js';

class FakeEventSource extends EventTarget {
  url: string;
  closed = false;
  constructor(url: string) {
    super();
    this.url = url;
  }
  emitFrame(envelope: SseEnvelope): void {
    this.dispatchEvent(
      new MessageEvent('frame', { data: JSON.stringify(envelope) }),
    );
  }
  close(): void {
    this.closed = true;
  }
}

function updateFrame(seq: number, payload: unknown): SseEnvelope {
  return {
    streamId: 'registry:capabilities',
    frameKind: 'UPDATE',
    seq,
    ts: '2026-05-07T00:00:00Z',
    payload: payload as Record<string, unknown>,
    resumeToken: `rt-${seq}`,
  };
}

describe('contractEventsBridge', () => {
  let fakeEs: FakeEventSource;

  beforeEach(() => {
    __resetForTest();
    fakeEs = new FakeEventSource('http://test/infra/capabilities/stream');
  });

  afterEach(() => {
    stopContractEventBridge();
    __resetForTest();
  });

  it('dispatches capability-registered events to subscribers', () => {
    const listener = vi.fn();
    subscribe('capability-registered', listener);

    bootContractEventBridge('http://test', {
      eventSourceFactory: () => fakeEs as unknown as EventSource,
    });

    fakeEs.emitFrame(
      updateFrame(1, {
        kind: 'capability-registered',
        capabilityId: 'core.library',
        capabilityType: 'resource',
      }),
    );

    expect(listener).toHaveBeenCalledOnce();
    expect(listener.mock.calls[0]?.[0].capabilityId).toBe('core.library');
  });

  it('dispatches catalog-membership-changed events filtered by category', () => {
    const resourceListener = vi.fn();
    const operationListener = vi.fn();
    subscribe('catalog-membership-changed', resourceListener, {
      category: 'resource',
    });
    subscribe('catalog-membership-changed', operationListener, {
      category: 'operation',
    });

    bootContractEventBridge('http://test', {
      eventSourceFactory: () => fakeEs as unknown as EventSource,
    });

    fakeEs.emitFrame(
      updateFrame(1, {
        kind: 'catalog-membership-changed',
        category: 'operation',
        added: ['core.library.reindex'],
      }),
    );

    expect(resourceListener).not.toHaveBeenCalled();
    expect(operationListener).toHaveBeenCalledOnce();
  });

  it('filters out legacy CapabilityChangeEvent payloads (structural discrimination)', () => {
    const capListener = vi.fn();
    const catalogListener = vi.fn();
    const reactionListener = vi.fn();
    subscribe('capability-registered', capListener);
    subscribe('catalog-membership-changed', catalogListener);
    subscribe('reaction-outcome', reactionListener);

    bootContractEventBridge('http://test', {
      eventSourceFactory: () => fakeEs as unknown as EventSource,
    });

    // Legacy CapabilityChangeEvent shape (kind: "added" / "modified" / etc.
    // + detail). The bridge's structural discriminator
    // (isContractEvent) requires capabilityId | category | consumerId;
    // this payload has none, so it must NOT dispatch.
    fakeEs.emitFrame(
      updateFrame(1, {
        kind: 'added',
        detail: 'plugin.foo',
      }),
    );

    expect(capListener).not.toHaveBeenCalled();
    expect(catalogListener).not.toHaveBeenCalled();
    expect(reactionListener).not.toHaveBeenCalled();
  });

  it('ignores LIFECYCLE frames (initial snapshot, heartbeat, reset)', () => {
    const listener = vi.fn();
    subscribe('capability-registered', listener);

    bootContractEventBridge('http://test', {
      eventSourceFactory: () => fakeEs as unknown as EventSource,
    });

    fakeEs.emitFrame({
      streamId: 'registry:capabilities',
      frameKind: 'LIFECYCLE',
      seq: 1,
      ts: '2026-05-07T00:00:00Z',
      payload: { kind: 'snapshot', detail: 'initial' },
      resumeToken: 'rt-1',
    });

    expect(listener).not.toHaveBeenCalled();
  });

  it('idempotent boot — second boot is a no-op while already running', () => {
    bootContractEventBridge('http://test', {
      eventSourceFactory: () => fakeEs as unknown as EventSource,
    });
    expect(__isRunningForTest()).toBe(true);

    let secondFactoryCalled = false;
    bootContractEventBridge('http://test', {
      eventSourceFactory: () => {
        secondFactoryCalled = true;
        return fakeEs as unknown as EventSource;
      },
    });

    expect(secondFactoryCalled).toBe(false);
  });

  it('stop() unsubscribes and closes the EventSource', () => {
    bootContractEventBridge('http://test', {
      eventSourceFactory: () => fakeEs as unknown as EventSource,
    });
    expect(__isRunningForTest()).toBe(true);

    stopContractEventBridge();
    expect(__isRunningForTest()).toBe(false);
    expect(fakeEs.closed).toBe(true);

    const listener = vi.fn();
    subscribe('capability-registered', listener);
    fakeEs.emitFrame(
      updateFrame(1, {
        kind: 'capability-registered',
        capabilityId: 'x',
        capabilityType: 'resource',
      }),
    );
    expect(listener).not.toHaveBeenCalled();
  });

  it('catalog-membership-changed triggers refreshResourceCatalog', async () => {
    const fetchSpy = vi.fn(async () => ({
      ok: true,
      status: 200,
      statusText: 'OK',
      headers: new Headers(),
      json: async () => ({ entries: [] }),
    })) as unknown as typeof fetch;
    vi.stubGlobal('fetch', fetchSpy);

    bootContractEventBridge('http://test', {
      eventSourceFactory: () => fakeEs as unknown as EventSource,
    });

    fakeEs.emitFrame(
      updateFrame(1, {
        kind: 'catalog-membership-changed',
        category: 'resource',
        added: ['core.new'],
      }),
    );

    // Wait one microtask cycle for the async refresh to call fetch.
    await Promise.resolve();
    await Promise.resolve();

    expect(fetchSpy).toHaveBeenCalled();
    const url = (fetchSpy as ReturnType<typeof vi.fn>).mock.calls[0]?.[0];
    expect(url).toBe('http://test/api/registry/resources');

    vi.unstubAllGlobals();
  });

  it('catalog-membership-changed { category: "operation" } routes to operation refresh', async () => {
    const fetchSpy = vi.fn(async () => ({
      ok: true,
      status: 200,
      statusText: 'OK',
      headers: new Headers(),
      json: async () => ({ entries: [] }),
    })) as unknown as typeof fetch;
    vi.stubGlobal('fetch', fetchSpy);

    bootContractEventBridge('http://test', {
      eventSourceFactory: () => fakeEs as unknown as EventSource,
    });

    fakeEs.emitFrame(
      updateFrame(1, {
        kind: 'catalog-membership-changed',
        category: 'operation',
        modified: ['core.lib.reindex'],
      }),
    );

    await Promise.resolve();
    await Promise.resolve();

    expect(fetchSpy).toHaveBeenCalled();
    const url = (fetchSpy as ReturnType<typeof vi.fn>).mock.calls[0]?.[0];
    expect(url).toBe('http://test/api/registry/operations');

    vi.unstubAllGlobals();
  });
});
