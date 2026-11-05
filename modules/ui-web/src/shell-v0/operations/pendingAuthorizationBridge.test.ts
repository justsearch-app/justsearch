// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0

/**
 * Tempdoc 655 — verifies the out-of-band (e.g. MCP-originated) pending-authorization bridge.
 * Since the fix pass, the broadcast carries routing info only (no decision content); the bridge
 * must fetch the content by id (`peekPending`) before presenting the SAME ceremony a live 428
 * would, and on approval asks the backend to complete the dispatch itself (since the frontend
 * never held the original arguments to replay).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const requestAuthorizationMock = vi.fn();
const approveAndExecutePendingMock = vi.fn();
const peekPendingMock = vi.fn();
const emitEphemeralToastMock = vi.fn();

vi.mock('./authorizationBroker.js', async (importOriginal) => ({
  ...await importOriginal<typeof import('./authorizationBroker.js')>(),
  requestAuthorization: (...args: unknown[]) => requestAuthorizationMock(...args),
}));

vi.mock('./OperationClient.js', async (importOriginal) => ({
  ...await importOriginal<typeof import('./OperationClient.js')>(),
  getOperationClient: () => ({
    approveAndExecutePending: (...args: unknown[]) => approveAndExecutePendingMock(...args),
    peekPending: (...args: unknown[]) => peekPendingMock(...args),
  }),
}));

// Fix pass: the bridge reports an approved-but-failed pending through the single client-originated
// message channel (559 Authority III) — mock it directly rather than asserting on the DOM
// CustomEvent it dispatches, mirroring how the sibling deps above are mocked.
vi.mock('../components/advisory/ephemeralToast.js', () => ({
  emitEphemeralToast: (...args: unknown[]) => emitEphemeralToastMock(...args),
}));

// The shared status cadence is driven separately in the XA1 seam checks.
vi.mock('../utils/statusPoll.js', () => ({ subscribeStatus: () => () => {} }));

import { startPendingAuthorizationBridge } from './pendingAuthorizationBridge.js';
import type { SseEnvelope } from '../streaming/envelope-types.js';
import { MultiplexedStream } from '../streaming/MultiplexedStream.js';
import { SHELL_EVENT_STREAM_IDS } from '../streaming/shellEventStreamIds.js';

class FakeEventSource extends EventTarget {
  url: string;
  closed = false;
  readyState = 0;
  constructor(url: string) {
    super();
    this.url = url;
  }
  emitFrame(envelope: SseEnvelope): void {
    this.dispatchEvent(new MessageEvent('frame', { data: JSON.stringify(envelope) }));
  }
  emitOpen(): void {
    this.readyState = 1;
    this.dispatchEvent(new Event('open'));
  }
  close(): void {
    this.closed = true;
  }
}

function multiplexOn(fakeEs: FakeEventSource): MultiplexedStream {
  const mux = new MultiplexedStream({
    url: 'http://test/api/shell-events/stream',
    eventSourceFactory: () => fakeEs as unknown as EventSource,
  });
  mux.start();
  fakeEs.emitOpen();
  return mux;
}

// The slimmed broadcast payload — routing info only, no argsSummary/rationale (tempdoc 655 fix pass).
function pendingFrame(
  seq: number,
  pendingId: string,
  overrides: Record<string, unknown> = {},
): SseEnvelope {
  return {
    streamId: SHELL_EVENT_STREAM_IDS.PENDING_AUTHORIZATIONS,
    frameKind: 'UPDATE',
    seq,
    ts: '2026-07-02T10:00:00Z',
    payload: {
      pendingId,
      operationId: 'core.ingest-files',
      sourceTier: 'UNTRUSTED',
      riskTier: 'MEDIUM',
      gateBehavior: 'TYPED_CONFIRM',
      ...overrides,
    },
    resumeToken: `rt-${seq}`,
  };
}

function detailFor(pendingId: string, overrides: Record<string, unknown> = {}) {
  return {
    pendingId,
    operationId: 'core.ingest-files',
    argsSummary: '{"paths":["C:/tmp"]}',
    sourceTier: 'UNTRUSTED',
    riskTier: 'MEDIUM',
    gateBehavior: 'TYPED_CONFIRM',
    rationale: 'Confirmation required for operation core.ingest-files',
    ...overrides,
  };
}

// A microtask flush — presentAndExecute awaits peekPending, then requestAuthorization, then (fix
// pass) a re-peek, then the client call. Generous tick count so the extra re-peek await hop has
// headroom (each await can cost more than one microtask tick through the mock promise chain).
async function flush(): Promise<void> {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve();
  }
}

describe('pendingAuthorizationBridge (tempdoc 655)', () => {
  let fakeEs: FakeEventSource;

  beforeEach(() => {
    fakeEs = new FakeEventSource('http://test/api/shell-events/stream');
    requestAuthorizationMock.mockReset();
    approveAndExecutePendingMock.mockReset();
    peekPendingMock.mockReset();
    emitEphemeralToastMock.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('B2 after a reload or in a second window, requests still waiting in the Engine rejoin the ceremony queue once', async () => {
    const future = new Date(Date.now() + 240_000).toISOString();
    const record = (pendingId: string, expiresAt = future) => ({ event: { classId: 'authorization.pending',
      classExtras: { pendingId, operationId: 'core.ingest-files', riskTier: 'MEDIUM', gateBehavior: 'TYPED_CONFIRM', expiresAt } } });
    let snapshot = { advisories: [record('waiting'), record('expired', new Date(Date.now() - 1000).toISOString()), record('answered'),
      { event: { classId: 'operation.completed', classExtras: { pendingId: 'not-a-request', operationId: 'core.x' } } }] };
    const listeners = new Set<(s: typeof snapshot) => void>();
    const retained = { subscribe: (cb: (s: typeof snapshot) => void) => { listeners.add(cb); cb(snapshot); return () => listeners.delete(cb); } };
    peekPendingMock.mockImplementation(async (id: string) => id === 'answered' ? null : detailFor(id, { expiresAt: future }));
    requestAuthorizationMock.mockReturnValue(new Promise(() => {}));
    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs), retained });
    await flush();
    expect(peekPendingMock).not.toHaveBeenCalledWith('expired');
    expect(peekPendingMock).not.toHaveBeenCalledWith('not-a-request');
    expect(requestAuthorizationMock).toHaveBeenCalledTimes(1);
    expect(requestAuthorizationMock.mock.calls[0]?.[0]).toMatchObject({ pendingId: 'waiting', operationId: 'core.ingest-files',
      gateBehavior: 'TYPED_CONFIRM', expiresAt: future });
    // The live announcement and later snapshots of the same request never present it again.
    fakeEs.emitFrame(pendingFrame(1, 'waiting'));
    listeners.forEach(cb => cb(snapshot)); await flush();
    expect(requestAuthorizationMock).toHaveBeenCalledTimes(1);
    // A failed read is neither expiry nor an answer: the next snapshot tries again.
    peekPendingMock.mockRejectedValueOnce(new TypeError('offline'));
    snapshot = { advisories: [...snapshot.advisories, record('later')] };
    listeners.forEach(cb => cb(snapshot)); await flush();
    expect(requestAuthorizationMock).toHaveBeenCalledTimes(1);
    listeners.forEach(cb => cb(snapshot)); await flush();
    expect(requestAuthorizationMock).toHaveBeenCalledTimes(2);
    expect(requestAuthorizationMock.mock.calls[1]?.[0]).toMatchObject({ pendingId: 'later' });
    stop();
    expect(listeners.size).toBe(0);
  });

  it('fetches detail, presents the ceremony, and executes on approval', async () => {
    peekPendingMock.mockResolvedValue(detailFor('pa-001'));
    requestAuthorizationMock.mockResolvedValue({ approved: true, allowAlways: false });
    approveAndExecutePendingMock.mockResolvedValue({ executed: true, executeSuccess: true });

    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, 'pa-001'));
    await flush();

    expect(peekPendingMock).toHaveBeenCalledWith('pa-001');
    expect(requestAuthorizationMock).toHaveBeenCalledTimes(1);
    expect(requestAuthorizationMock.mock.calls[0]?.[0]).toMatchObject({
      pendingId: 'pa-001',
      operationId: 'core.ingest-files',
      gateBehavior: 'TYPED_CONFIRM',
      argsSummary: '{"paths":["C:/tmp"]}',
    });
    expect(approveAndExecutePendingMock).toHaveBeenCalledWith('pa-001', false);

    stop();
  });

  it('does not present the ceremony when peekPending returns null (already expired/consumed)', async () => {
    peekPendingMock.mockResolvedValue(null);

    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, 'pa-gone'));
    await flush();

    expect(peekPendingMock).toHaveBeenCalledWith('pa-gone');
    expect(requestAuthorizationMock).not.toHaveBeenCalled();
    expect(approveAndExecutePendingMock).not.toHaveBeenCalled();

    stop();
  });

  it('does not present the ceremony when peekPending throws (network error)', async () => {
    peekPendingMock.mockRejectedValue(new Error('network error'));

    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, 'pa-neterr'));
    await flush();

    expect(requestAuthorizationMock).not.toHaveBeenCalled();

    stop();
  });

  it('does not call approveAndExecutePending when the human denies', async () => {
    peekPendingMock.mockResolvedValue(detailFor('pa-002'));
    requestAuthorizationMock.mockResolvedValue({ approved: false, allowAlways: false });

    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, 'pa-002'));
    await flush();

    expect(requestAuthorizationMock).toHaveBeenCalledTimes(1);
    expect(approveAndExecutePendingMock).not.toHaveBeenCalled();

    stop();
  });

  it('passes allowAlways through to the execute call', async () => {
    peekPendingMock.mockResolvedValue(detailFor('pa-003'));
    requestAuthorizationMock.mockResolvedValue({ approved: true, allowAlways: true });
    approveAndExecutePendingMock.mockResolvedValue({ executed: true, executeSuccess: true });

    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, 'pa-003'));
    await flush();

    expect(approveAndExecutePendingMock).toHaveBeenCalledWith('pa-003', true);

    stop();
  });

  it('dedups a replayed pendingId (e.g. ring-buffer replay on reconnect)', async () => {
    peekPendingMock.mockResolvedValue(detailFor('pa-dup'));
    requestAuthorizationMock.mockResolvedValue({ approved: true, allowAlways: false });
    approveAndExecutePendingMock.mockResolvedValue({ executed: true, executeSuccess: true });

    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, 'pa-dup'));
    fakeEs.emitFrame(pendingFrame(2, 'pa-dup'));
    await flush();

    expect(requestAuthorizationMock).toHaveBeenCalledTimes(1);

    stop();
  });

  it('ignores a payload missing pendingId or operationId', async () => {
    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, '', { operationId: undefined }));
    await flush();

    expect(peekPendingMock).not.toHaveBeenCalled();
    expect(requestAuthorizationMock).not.toHaveBeenCalled();

    stop();
  });

  it('is a no-op with no multiplex supplied', () => {
    const stop = startPendingAuthorizationBridge('http://test', {});
    expect(() => stop()).not.toThrow();
    expect(requestAuthorizationMock).not.toHaveBeenCalled();
  });

  // ── Fix pass: a validation round typed the confirm phrase on an already-dead pending, clicked
  // Approve, and got no grant + no error message — the backend answered 410 Gone and the bridge's
  // catch reduced it to a console.warn nobody sees. These tests pin the fix: the failure reaches
  // the user via the same client-originated toast channel Shell.ts's sibling gated-dispatch path
  // (`invoke-operation`) already uses, and a re-peek immediately before the POST catches a pending
  // that died in the (arbitrarily long) window while the human was reading/typing the ceremony.

  it('reports the failure to the user (not just console) when approveAndExecutePending rejects with a 410-shaped error', async () => {
    peekPendingMock.mockResolvedValue(detailFor('pa-410'));
    requestAuthorizationMock.mockResolvedValue({ approved: true, allowAlways: false });
    const err410 = Object.assign(new Error('Failed to approve pending pa-410 (HTTP 410)'), {
      errorClass: 'CAPSULE_MINT_FAILED',
      httpStatus: 410,
    });
    approveAndExecutePendingMock.mockRejectedValue(err410);

    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, 'pa-410'));
    await flush();

    // Re-peek happened right before the POST (once before presenting, once before approving).
    expect(peekPendingMock).toHaveBeenCalledTimes(2);
    expect(approveAndExecutePendingMock).toHaveBeenCalledWith('pa-410', false);

    // The core defect: the failure must reach the user, not just a console.warn.
    expect(emitEphemeralToastMock).toHaveBeenCalledTimes(1);
    const toast = emitEphemeralToastMock.mock.calls[0]?.[0] as {
      message: string;
      severity: string;
    };
    expect(toast.severity).toBe('error');
    expect(toast.message).toContain('core.ingest-files');
    expect(toast.message).toContain('HTTP 410');

    stop();
  });

  it('reports the failure when approveAndExecutePending resolves 200 OK but executed:false (server declined/failed silently)', async () => {
    peekPendingMock.mockResolvedValue(detailFor('pa-soft-fail'));
    requestAuthorizationMock.mockResolvedValue({ approved: true, allowAlways: false });
    approveAndExecutePendingMock.mockResolvedValue({
      executed: false,
      executeMessage: 'Operation not available: core.ingest-files',
    });

    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, 'pa-soft-fail'));
    await flush();

    expect(approveAndExecutePendingMock).toHaveBeenCalledWith('pa-soft-fail', false);
    expect(emitEphemeralToastMock).toHaveBeenCalledTimes(1);
    const toast = emitEphemeralToastMock.mock.calls[0]?.[0] as {
      message: string;
      severity: string;
    };
    expect(toast.severity).toBe('error');
    expect(toast.message).toContain('Operation not available: core.ingest-files');

    stop();
  });

  it('reports the failure when approveAndExecutePending resolves executed:true, executeSuccess:false', async () => {
    peekPendingMock.mockResolvedValue(detailFor('pa-exec-fail'));
    requestAuthorizationMock.mockResolvedValue({ approved: true, allowAlways: false });
    approveAndExecutePendingMock.mockResolvedValue({
      executed: true,
      executeSuccess: false,
      executeMessage: 'Approved, but execution failed: disk full',
    });

    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, 'pa-exec-fail'));
    await flush();

    expect(emitEphemeralToastMock).toHaveBeenCalledTimes(1);
    const toast = emitEphemeralToastMock.mock.calls[0]?.[0] as { message: string };
    expect(toast.message).toContain('disk full');

    stop();
  });

  it('does NOT report a failure on a genuinely successful approve+execute (no false positives)', async () => {
    peekPendingMock.mockResolvedValue(detailFor('pa-ok'));
    requestAuthorizationMock.mockResolvedValue({ approved: true, allowAlways: false });
    approveAndExecutePendingMock.mockResolvedValue({ executed: true, executeSuccess: true });

    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, 'pa-ok'));
    await flush();

    expect(emitEphemeralToastMock).not.toHaveBeenCalled();

    stop();
  });

  // ── Tempdoc 807 item 4: sandbox round 13's residual. A ceremony that ends WITHOUT a human
  // decision — no host mounted, or a mounted host torn down mid-ceremony — resolves `approved:
  // false`, the same shape an explicit deny has. The bridge returned silently for both, so the
  // round saw the modal dismiss with nothing dispatched and nothing said. `failedClosed` separates
  // the two; only the failure is reported.

  it('reports a fail-closed ceremony (no host / host torn down) through the same channel', async () => {
    peekPendingMock.mockResolvedValue(detailFor('pa-failclosed'));
    requestAuthorizationMock.mockResolvedValue({
      approved: false,
      allowAlways: false,
      failedClosed: true,
    });

    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, 'pa-failclosed'));
    await flush();

    expect(approveAndExecutePendingMock).not.toHaveBeenCalled();
    expect(emitEphemeralToastMock).toHaveBeenCalledTimes(1);
    const toast = emitEphemeralToastMock.mock.calls[0]?.[0] as {
      message: string;
      severity: string;
    };
    expect(toast.severity).toBe('error');
    expect(toast.message).toContain('core.ingest-files');
    expect(toast.message).toContain('closed before the decision was sent');

    stop();
  });

  it('stays silent on an explicit human deny (a decision is not a failure)', async () => {
    peekPendingMock.mockResolvedValue(detailFor('pa-denied'));
    requestAuthorizationMock.mockResolvedValue({ approved: false, allowAlways: false });

    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, 'pa-denied'));
    await flush();

    expect(approveAndExecutePendingMock).not.toHaveBeenCalled();
    expect(emitEphemeralToastMock).not.toHaveBeenCalled();

    stop();
  });

  it('re-peeks immediately before POSTing and skips the doomed POST when the pending died during the ceremony', async () => {
    // Alive when first fetched (peek-before-present) — the human takes a while to decide — then
    // gone by the time the re-peek runs right before the POST.
    peekPendingMock.mockResolvedValueOnce(detailFor('pa-died-midflight'));
    peekPendingMock.mockResolvedValueOnce(null);
    requestAuthorizationMock.mockResolvedValue({ approved: true, allowAlways: false });

    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, 'pa-died-midflight'));
    await flush();

    expect(peekPendingMock).toHaveBeenCalledTimes(2);
    // The POST is never attempted once the re-peek confirms the pending is gone.
    expect(approveAndExecutePendingMock).not.toHaveBeenCalled();
    expect(emitEphemeralToastMock).toHaveBeenCalledTimes(1);
    const toast = emitEphemeralToastMock.mock.calls[0]?.[0] as { message: string };
    expect(toast.message.toLowerCase()).toContain('expired');

    stop();
  });

  it('proceeds with the POST when the re-peek itself fails transiently (network blip), instead of blocking the approval', async () => {
    peekPendingMock.mockResolvedValueOnce(detailFor('pa-flaky-repeek'));
    peekPendingMock.mockRejectedValueOnce(new Error('network blip'));
    requestAuthorizationMock.mockResolvedValue({ approved: true, allowAlways: false });
    approveAndExecutePendingMock.mockResolvedValue({ executed: true, executeSuccess: true });

    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, 'pa-flaky-repeek'));
    await flush();

    expect(approveAndExecutePendingMock).toHaveBeenCalledWith('pa-flaky-repeek', false);
    expect(emitEphemeralToastMock).not.toHaveBeenCalled();

    stop();
  });

  it.each(['MCP', 'AGENT_LOOP', 'WORKFLOW', 'LLM_EMISSION'])('DE3 a %s request waits in Needs you, announced as a live arrival', async (transport) => {
    peekPendingMock.mockResolvedValue(detailFor('agent', { requestedBy: 'Claude Desktop' }));
    requestAuthorizationMock.mockReturnValue(new Promise(() => {}));
    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, 'agent', { transport, createdAt: '2026-07-02T09:59:00Z' }));
    await flush();
    expect(requestAuthorizationMock.mock.calls[0]?.[0]).toMatchObject({ pendingId: 'agent', arrival: 'needs-you', live: true,
      askedAt: '2026-07-02T09:59:00Z', requestedBy: 'Claude Desktop' });
    stop();
  });

  it.each(['BUTTON', 'PALETTE', undefined])('DE3 the person\'s own %s request keeps its dialog on arrival', async (transport) => {
    peekPendingMock.mockResolvedValue(detailFor('own'));
    requestAuthorizationMock.mockReturnValue(new Promise(() => {}));
    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, 'own', transport ? { transport } : {}));
    await flush();
    const prompt = requestAuthorizationMock.mock.calls[0]?.[0];
    expect(prompt).toMatchObject({ pendingId: 'own' });
    expect(prompt.arrival).toBeUndefined();
    stop();
  });

  it('DE3 a request rediscovered after a reload waits in Needs you without counting as a live arrival', async () => {
    const future = new Date(Date.now() + 240_000).toISOString();
    const snapshot = { advisories: [{ event: { classId: 'authorization.pending', occurredAt: '2026-07-02T09:58:00Z',
      classExtras: { pendingId: 'kept', operationId: 'core.ingest-files', riskTier: 'MEDIUM', gateBehavior: 'INLINE_CONFIRM', expiresAt: future } } }] };
    const retained = { subscribe: (cb: (s: typeof snapshot) => void) => { cb(snapshot); return () => {}; } };
    peekPendingMock.mockResolvedValue(detailFor('kept', { expiresAt: future, gateBehavior: 'INLINE_CONFIRM' }));
    requestAuthorizationMock.mockReturnValue(new Promise(() => {}));
    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs), retained });
    await flush();
    expect(requestAuthorizationMock.mock.calls[0]?.[0]).toMatchObject({ pendingId: 'kept', arrival: 'needs-you', live: false,
      askedAt: '2026-07-02T09:58:00Z' });
    stop();
  });

  it('DE3 reviewing reads the live details again: fresh fields, null when gone, a failure when unreadable', async () => {
    peekPendingMock.mockResolvedValueOnce(detailFor('fresh'));
    requestAuthorizationMock.mockReturnValue(new Promise(() => {}));
    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, 'fresh', { transport: 'MCP' }));
    await flush();
    const refresh = requestAuthorizationMock.mock.calls[0]?.[0].refresh as () => Promise<unknown>;
    expect(refresh, 'the review can read the live details').toBeTypeOf('function');
    peekPendingMock.mockResolvedValueOnce(detailFor('fresh', { argsSummary: '/frozen/target', expiresAt: '2026-07-02T10:05:00Z' }));
    await expect(refresh()).resolves.toMatchObject({ argsSummary: '/frozen/target', expiresAt: '2026-07-02T10:05:00Z',
      operationId: 'core.ingest-files', gateBehavior: 'TYPED_CONFIRM', riskTier: 'MEDIUM' });
    peekPendingMock.mockResolvedValueOnce(null);
    await expect(refresh()).resolves.toBeNull();
    peekPendingMock.mockRejectedValueOnce(new TypeError('offline'));
    await expect(refresh()).rejects.toThrow('offline');
    stop();
  });
});
