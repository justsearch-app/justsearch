// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchWithAdmissionWait } from './admissionFetch.js';
import { EPHEMERAL_TOAST_EVENT, type EphemeralToastSpec } from '../shell-v0/components/advisory/ephemeralToast.js';

function refused(code: string, retrySafe = true, preparationId: string | null = code === 'UPGRADE_PREPARING' ? 'prep-1' : null): Response {
  // The upgrade refusal (ApiSecurityFilters) names its preparation; an engine that is closing
  // answers the same code without one (RequestEngineWork.writeRefusal).
  return new Response(JSON.stringify({ errorCode: code, retrySafe, errorClass: 'TRANSIENT', retryable: true,
    ...(preparationId ? { preparationId } : {}) }), {
    status: code === 'UPGRADE_PREPARING' ? 503 : 429,
    headers: { 'Retry-After': '2', 'Content-Type': 'application/json' },
  });
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe('pre-execution admission waits', () => {
  it.each(['ADMISSION_CONTEXT_LIMIT', 'ADMISSION_ENGINE_LIMIT', 'UPGRADE_PREPARING'])(
    '%s waits the named delay and emits an informational notice without failing the request', async (code) => {
      const send = vi.fn().mockResolvedValueOnce(refused(code)).mockResolvedValueOnce(new Response('{}'));
      const notice = new Promise<EphemeralToastSpec>((resolve) => {
        document.addEventListener(EPHEMERAL_TOAST_EVENT,
          (event) => resolve((event as CustomEvent<EphemeralToastSpec>).detail), { once: true });
      });
      const result = fetchWithAdmissionWait(send, 'http://localhost/api/knowledge/search', {
        method: 'POST', body: '{"query":"probe"}',
      });
      expect((await notice).severity).toBe('info');
      await vi.advanceTimersByTimeAsync(1_999);
      expect(send).toHaveBeenCalledTimes(1);
      await vi.advanceTimersByTimeAsync(1);
      expect((await result).status).toBe(200);
      expect(send).toHaveBeenCalledTimes(2);
      expect(send.mock.calls[1]?.[1]?.body).toBe('{"query":"probe"}');
    });

  it('L4a a closing engine (no upgrade preparation) waits and retries without announcing an update', async () => {
    const send = vi.fn().mockResolvedValueOnce(refused('UPGRADE_PREPARING', true, null)).mockResolvedValueOnce(new Response('{}'));
    const notices: EphemeralToastSpec[] = [];
    const listener = (event: Event) => notices.push((event as CustomEvent<EphemeralToastSpec>).detail);
    document.addEventListener(EPHEMERAL_TOAST_EVENT, listener);
    try {
      const result = fetchWithAdmissionWait(send, 'http://localhost/api/knowledge/search', { method: 'POST', body: '{}' });
      await vi.advanceTimersByTimeAsync(2_000);
      expect((await result).status).toBe(200);
      expect(send).toHaveBeenCalledTimes(2);
      expect(notices.map(n => n.message)).toEqual([]);
    } finally {
      document.removeEventListener(EPHEMERAL_TOAST_EVENT, listener);
    }
  });

  it('L4a only a refusal naming an upgrade preparation says an update is being prepared', async () => {
    const send = vi.fn().mockResolvedValueOnce(refused('UPGRADE_PREPARING')).mockResolvedValueOnce(new Response('{}'));
    const notice = new Promise<EphemeralToastSpec>((resolve) => {
      document.addEventListener(EPHEMERAL_TOAST_EVENT,
        (event) => resolve((event as CustomEvent<EphemeralToastSpec>).detail), { once: true });
    });
    const result = fetchWithAdmissionWait(send, 'http://localhost/api/x', { method: 'POST', body: '{}' });
    expect((await notice).message).toBe('JustSearch is preparing an update. Waiting to reconnect.');
    await vi.advanceTimersByTimeAsync(2_000);
    expect((await result).status).toBe(200);
  });

  it('aborting the wait clears its timer and never resubmits the action', async () => {
    const abort = new AbortController();
    const send = vi.fn().mockResolvedValue(refused('ADMISSION_CONTEXT_LIMIT'));
    const notice = new Promise<void>((resolve) => {
      document.addEventListener(EPHEMERAL_TOAST_EVENT, () => resolve(), { once: true });
    });
    const result = fetchWithAdmissionWait(send, 'http://localhost/api/x', { signal: abort.signal });
    const rejected = expect(result).rejects.toMatchObject({ name: 'AbortError' });
    await notice;
    abort.abort();
    await rejected;
    await vi.advanceTimersByTimeAsync(5_000);
    expect(send).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('does not replay an action that lacks a pre-execution refusal guarantee', async () => {
    const response = refused('ADMISSION_ENGINE_LIMIT', false);
    const send = vi.fn().mockResolvedValue(response);
    expect(await fetchWithAdmissionWait(send, 'http://localhost/api/x', { method: 'POST', body: '{}' }))
      .toBe(response);
    expect(send).toHaveBeenCalledTimes(1);
  });

  // Task t-20261005-b6d536, FP11: full inference threads and queue are a capacity refusal too.
  it('ADMISSION_INFERENCE_BUSY refused before execution waits the named delay and retries', async () => {
    const send = vi.fn().mockResolvedValueOnce(refused('ADMISSION_INFERENCE_BUSY'))
      .mockResolvedValueOnce(new Response('{}'));
    const result = fetchWithAdmissionWait(send, 'http://localhost/api/knowledge/search', {
      method: 'POST', body: '{"query":"probe"}',
    });
    await vi.advanceTimersByTimeAsync(2_000);
    expect(send).toHaveBeenCalledTimes(2);
    expect((await result).status).toBe(200);
  });

  it('never replays ADMISSION_INFERENCE_BUSY raised mid-request', async () => {
    const response = refused('ADMISSION_INFERENCE_BUSY', false);
    const send = vi.fn().mockResolvedValue(response);
    expect(await fetchWithAdmissionWait(send, 'http://localhost/api/knowledge/search', {
      method: 'POST', body: '{"query":"probe"}',
    })).toBe(response);
    expect(send).toHaveBeenCalledTimes(1);
  });

  it('does not turn an unrelated 429 into an admission retry', async () => {
    const response = refused('RATE_LIMITED');
    const send = vi.fn().mockResolvedValue(response);
    expect(await fetchWithAdmissionWait(send, 'http://localhost/api/x')).toBe(response);
    expect(send).toHaveBeenCalledTimes(1);
  });

  it('does not replay a single-use ReadableStream body after a safe-looking refusal', async () => {
    const response = refused('ADMISSION_CONTEXT_LIMIT');
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode('{}'));
        controller.close();
      },
    });
    const send = vi.fn().mockResolvedValue(response);

    expect(await fetchWithAdmissionWait(send, 'http://localhost/api/x', {
      method: 'POST',
      body,
    })).toBe(response);
    expect(send).toHaveBeenCalledTimes(1);
    expect(send.mock.calls[0]?.[1]?.body).toBe(body);
  });
});
