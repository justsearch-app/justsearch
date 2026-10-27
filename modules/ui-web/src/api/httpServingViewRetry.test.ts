import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

vi.mock('../utils/tauriRuntime', () => ({
  isTauriRuntime: () => false,
}));

// The body the Engine sends when a search arrives while an index reload has no serving view.
function servingViewUnavailable(): Response {
  return new Response(JSON.stringify({
    error: 'Index serving view is unavailable',
    errorCode: 'SERVICE_UNAVAILABLE',
    errorClass: 'TRANSIENT',
    retryable: true,
    i18nKey: 'errors.SERVICE_UNAVAILABLE',
    retrySafe: true,
  }), {
    status: 503,
    headers: { 'Content-Type': 'application/json', 'Retry-After': '1' },
  });
}

function success(): Response {
  return new Response(JSON.stringify({ results: [{ id: 'doc-1' }] }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('request during an index reload window', () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
    vi.useFakeTimers();
    vi.resetModules();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  it('gives the caller the success that follows a transient serving-view 503', async () => {
    globalThis.fetch = vi.fn()
      .mockResolvedValueOnce(servingViewUnavailable())
      .mockResolvedValueOnce(success());

    const { request } = await import('./http');
    const pending = request('http://localhost:3000', '/api/knowledge/search', {
      method: 'POST',
      body: { query: 'quarterly report' },
    });
    await vi.runAllTimersAsync();

    await expect(pending).resolves.toEqual({ results: [{ id: 'doc-1' }] });
    expect(globalThis.fetch).toHaveBeenCalledTimes(2);
  });

  it('exhausts the existing retry budget on a persistent serving-view 503', async () => {
    globalThis.fetch = vi.fn().mockImplementation(() => Promise.resolve(servingViewUnavailable()));

    const { request } = await import('./http');
    const pending = request('http://localhost:3000', '/api/knowledge/search', {
      method: 'POST',
      body: { query: 'quarterly report' },
    });
    const settled = expect(pending).rejects.toMatchObject({
      message: 'Index serving view is unavailable',
      code: 'SERVICE_UNAVAILABLE',
      status: 503,
      retryable: true,
      retrySafe: true,
    });
    await vi.runAllTimersAsync();

    await settled;
    expect(globalThis.fetch).toHaveBeenCalledTimes(3);
  });

  it('sends nothing further once the caller cancels during the retry wait', async () => {
    const controller = new AbortController();
    globalThis.fetch = vi.fn().mockImplementation(() => Promise.resolve(servingViewUnavailable()));

    const { request } = await import('./http');
    const pending = request('http://localhost:3000', '/api/knowledge/search', {
      method: 'POST',
      body: { query: 'quarterly report' },
      signal: controller.signal,
    });
    const settled = expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    await vi.advanceTimersByTimeAsync(0);
    expect(globalThis.fetch).toHaveBeenCalledTimes(1);
    controller.abort();
    await vi.runAllTimersAsync();

    await settled;
    expect(globalThis.fetch).toHaveBeenCalledTimes(1);
  });
});
