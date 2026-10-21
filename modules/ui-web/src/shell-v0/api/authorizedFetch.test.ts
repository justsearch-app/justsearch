// @vitest-environment happy-dom

/**
 * Tempdoc 804 B3/D3 — the authorized-request seam.
 *
 * The packaged shell boots the backend with `prod=true`, where ApiSecurityFilters
 * requires `X-JustSearch-Session` on POST/PUT/DELETE (GET/OPTIONS exempt, no path
 * exemptions). These tests pin the three things that were broken or missing:
 *   (a) a non-GET through `performFetch` (the covering seam behind every surface's
 *       `doFetch`) carries the token when one resolves, and omits it when none does;
 *   (b) the same through `OperationClient`'s DEFAULT fetch path (host.data.invokeOperation);
 *   (c) a Tauri-runtime null does not poison the resolver cache — the shell's
 *       `session_token` wait can elapse on a slow cold start, and the old code cached
 *       that null forever, 401-ing every mutating call for the app's lifetime.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const mocks = vi.hoisted(() => ({
  isTauriRuntime: vi.fn(() => false),
  invoke: vi.fn(),
}));

vi.mock('../../utils/tauriRuntime', () => ({ isTauriRuntime: mocks.isTauriRuntime }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }));

const SESSION_HEADER = 'X-JustSearch-Session';
const apiBase = 'http://127.0.0.1:43210';

let originalFetch: typeof fetch;
let fetchSpy: ReturnType<typeof vi.fn>;

function ok(body: unknown = { success: true, message: 'ok' }): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

function headerOf(call: unknown[] | undefined): string | undefined {
  const init = call?.[1] as RequestInit | undefined;
  const headers = init?.headers;
  if (!headers) return undefined;
  if (headers instanceof Headers) return headers.get(SESSION_HEADER) ?? undefined;
  return (headers as Record<string, string>)[SESSION_HEADER];
}

beforeEach(() => {
  originalFetch = globalThis.fetch;
  fetchSpy = vi.fn().mockResolvedValue(ok());
  globalThis.fetch = fetchSpy as unknown as typeof fetch;
  mocks.isTauriRuntime.mockReturnValue(false);
  mocks.invoke.mockReset();
  vi.resetModules(); // http.ts caches the token in module state — start each test clean.
});

afterEach(() => {
  globalThis.fetch = originalFetch;
  vi.clearAllMocks();
});

describe('authorizedFetch', () => {
  it('attaches the session token to a POST when one resolves', async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    mocks.invoke.mockResolvedValue({ origin: 'http://127.0.0.1:43210', token: 'tok-abc' });

    const { authorizedFetch } = await import('./authorizedFetch.js');
    await authorizedFetch(`${apiBase}/api/x`, { method: 'POST', body: '{}' });

    expect(headerOf(fetchSpy.mock.calls[0])).toBe('tok-abc');
  });

  it('attaches the resolved session token to GET requests', async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    mocks.invoke.mockResolvedValue({ origin: 'http://127.0.0.1:43210', token: 'tok-abc' });

    const { authorizedFetch } = await import('./authorizedFetch.js');
    await authorizedFetch(`${apiBase}/api/x`);

    expect(headerOf(fetchSpy.mock.calls[0])).toBe('tok-abc');
    expect(mocks.invoke).toHaveBeenCalled();
  });

  it("preserves the caller's explicit session header", async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    mocks.invoke.mockResolvedValue({ origin: 'http://127.0.0.1:43210', token: 'tok-abc' });

    const { authorizedFetch } = await import('./authorizedFetch.js');
    await authorizedFetch(`${apiBase}/api/x`, {
      method: 'POST',
      headers: { [SESSION_HEADER]: 'caller-wins' },
    });

    expect(headerOf(fetchSpy.mock.calls[0])).toBe('caller-wins');
  });

  it('keeps other headers when it adds the token', async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    mocks.invoke.mockResolvedValue({ origin: 'http://127.0.0.1:43210', token: 'tok-abc' });

    const { authorizedFetch } = await import('./authorizedFetch.js');
    await authorizedFetch(`${apiBase}/api/x`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
    });

    const headers = fetchSpy.mock.calls[0]![1].headers as Record<string, string>;
    expect(headers['Content-Type']).toBe('application/json');
    expect(headers[SESSION_HEADER]).toBe('tok-abc');
  });
});

describe('performFetch (host.data.fetch — the covering doFetch seam)', () => {
  it('carries the session header on a non-GET when a token resolves', async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    mocks.invoke.mockResolvedValue({ origin: 'http://127.0.0.1:43210', token: 'tok-data' });

    const { createDataApi } = await import('../plugin-api/capabilities/data.js');
    await createDataApi('TRUSTED_PLUGIN', apiBase).fetch('/api/conversations/encryption/export', {
      method: 'POST',
    });

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    expect(String(fetchSpy.mock.calls[0]![0])).toBe(
      `${apiBase}/api/conversations/encryption/export`,
    );
    expect(headerOf(fetchSpy.mock.calls[0])).toBe('tok-data');
  });

  it('omits the session header when no token is available (browser / dev)', async () => {
    mocks.isTauriRuntime.mockReturnValue(false);

    const { createDataApi } = await import('../plugin-api/capabilities/data.js');
    await createDataApi('TRUSTED_PLUGIN', apiBase).fetch('/api/x', {
      method: 'POST',
      body: { a: 1 },
    });

    expect(headerOf(fetchSpy.mock.calls[0])).toBeUndefined();
    // The body path's Content-Type is still applied.
    const headers = fetchSpy.mock.calls[0]![1].headers as Record<string, string>;
    expect(headers['Content-Type']).toBe('application/json');
  });
});

describe('OperationClient default fetch path', () => {
  it('carries the session header on the invoke POST', async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    mocks.invoke.mockResolvedValue({ origin: 'http://127.0.0.1:43210', token: 'tok-op' });

    const { OperationClient } = await import('../operations/OperationClient.js');
    await new OperationClient({ apiBase }).invoke('demo.op');

    expect(headerOf(fetchSpy.mock.calls[0])).toBe('tok-op');
  });

  it('omits the session header when no token is available', async () => {
    mocks.isTauriRuntime.mockReturnValue(false);

    const { OperationClient } = await import('../operations/OperationClient.js');
    await new OperationClient({ apiBase }).invoke('demo.op');

    expect(headerOf(fetchSpy.mock.calls[0])).toBeUndefined();
  });

  it('still honours an explicitly injected fetchImpl (testability preserved)', async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    mocks.invoke.mockResolvedValue({ origin: 'http://127.0.0.1:43210', token: 'tok-op' });
    const injected = vi.fn().mockResolvedValue(ok());

    const { OperationClient } = await import('../operations/OperationClient.js');
    await new OperationClient({ apiBase, fetchImpl: injected as unknown as typeof fetch }).invoke(
      'demo.op',
    );

    expect(injected).toHaveBeenCalledTimes(1);
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});

describe('session-token resolver caching (804 D3 hazard)', () => {
  it('does NOT cache a Tauri-runtime null — a later call re-attempts and picks the token up', async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    mocks.invoke.mockResolvedValueOnce(null).mockResolvedValueOnce({ origin: 'http://127.0.0.1:43210', token: 'late-token' });

    const { resolveSessionTokenFromTauri, getSessionToken } = await import('../../api/http.js');

    expect(await resolveSessionTokenFromTauri()).toBeNull();
    expect(getSessionToken()).toBeNull();

    expect(await resolveSessionTokenFromTauri()).toBe('late-token');
    expect(getSessionToken()).toBe('late-token');
    expect(mocks.invoke).toHaveBeenCalledTimes(2);
  });

  it('a POST issued before the token exists does not doom later POSTs', async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    mocks.invoke.mockResolvedValueOnce(null).mockResolvedValueOnce({ origin: 'http://127.0.0.1:43210', token: 'late-token' });

    const { authorizedFetch } = await import('./authorizedFetch.js');
    await authorizedFetch(`${apiBase}/api/x`, { method: 'POST' });
    await authorizedFetch(`${apiBase}/api/x`, { method: 'POST' });

    expect(headerOf(fetchSpy.mock.calls[0])).toBeUndefined();
    expect(headerOf(fetchSpy.mock.calls[1])).toBe('late-token');
  });

  it('caches a resolved token (one invoke for many calls)', async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    mocks.invoke.mockResolvedValue({ origin: 'http://127.0.0.1:43210', token: 'tok-once' });

    const { authorizedFetch } = await import('./authorizedFetch.js');
    await authorizedFetch(`${apiBase}/api/x`, { method: 'POST' });
    await authorizedFetch(`${apiBase}/api/x`, { method: 'POST' });

    expect(mocks.invoke).toHaveBeenCalledTimes(1);
    expect(headerOf(fetchSpy.mock.calls[1])).toBe('tok-once');
  });

  it('single-flights concurrent resolutions on the retryable path', async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    let release!: (v: unknown) => void;
    const pending = new Promise((resolve) => {
      release = resolve;
    });
    mocks.invoke.mockReturnValue(pending);

    const { resolveSessionTokenFromTauri } = await import('../../api/http.js');
    const a = resolveSessionTokenFromTauri();
    const b = resolveSessionTokenFromTauri();
    release(null);

    expect(await a).toBeNull();
    expect(await b).toBeNull();
    expect(mocks.invoke).toHaveBeenCalledTimes(1);
  });

  it('caches the non-Tauri verdict permanently (no invoke, no retry cost)', async () => {
    mocks.isTauriRuntime.mockReturnValue(false);

    const { resolveSessionTokenFromTauri } = await import('../../api/http.js');
    expect(await resolveSessionTokenFromTauri()).toBeNull();
    expect(await resolveSessionTokenFromTauri()).toBeNull();

    expect(mocks.invoke).not.toHaveBeenCalled();
  });

  it('invalidateSessionToken clears the cache so the next resolve re-invokes', async () => {
    // Tempdoc 805 G.1 — the token is a per-boot fact. Without this the binding latches for the
    // app's lifetime (R11-F2).
    mocks.isTauriRuntime.mockReturnValue(true);
    mocks.invoke.mockResolvedValueOnce({ origin: 'http://127.0.0.1:43210', token: 'tok-boot-1' }).mockResolvedValueOnce({ origin: 'http://127.0.0.1:43210', token: 'tok-boot-2' });

    const { resolveSessionTokenFromTauri, getSessionToken, invalidateSessionToken } = await import(
      '../../api/http.js'
    );

    expect(await resolveSessionTokenFromTauri()).toBe('tok-boot-1');
    expect(await resolveSessionTokenFromTauri()).toBe('tok-boot-1');
    expect(mocks.invoke).toHaveBeenCalledTimes(1);

    invalidateSessionToken();
    expect(getSessionToken()).toBeNull();

    expect(await resolveSessionTokenFromTauri()).toBe('tok-boot-2');
    expect(getSessionToken()).toBe('tok-boot-2');
    expect(mocks.invoke).toHaveBeenCalledTimes(2);
  });
});

describe('one-shot 401 heal (805 G.1 — a restart the shell has not announced yet)', () => {
  function unauthorized(errorCode = 'UI_TOKEN_REQUIRED'): Response {
    return new Response(JSON.stringify({ errorCode, message: 'nope' }), {
      status: 401,
      headers: { 'Content-Type': 'application/json' },
    });
  }

  it('re-resolves and retries exactly once, then returns the second 401', async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    mocks.invoke.mockResolvedValueOnce({ origin: 'http://127.0.0.1:43210', token: 'tok-dead' }).mockResolvedValueOnce({ origin: 'http://127.0.0.1:43210', token: 'tok-live' });
    fetchSpy.mockResolvedValueOnce(unauthorized()).mockResolvedValueOnce(unauthorized());

    const { authorizedFetch } = await import('./authorizedFetch.js');
    const res = await authorizedFetch(`${apiBase}/api/x`, { method: 'POST', body: '{}' });

    expect(res.status).toBe(401);
    expect(fetchSpy).toHaveBeenCalledTimes(2);
    expect(mocks.invoke).toHaveBeenCalledTimes(2);
    expect(headerOf(fetchSpy.mock.calls[0])).toBe('tok-dead');
    expect(headerOf(fetchSpy.mock.calls[1])).toBe('tok-live');
  });

  it('heals a single stale-token 401 and returns the retry response', async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    mocks.invoke.mockResolvedValueOnce({ origin: 'http://127.0.0.1:43210', token: 'tok-dead' }).mockResolvedValueOnce({ origin: 'http://127.0.0.1:43210', token: 'tok-live' });
    fetchSpy.mockResolvedValueOnce(unauthorized()).mockResolvedValueOnce(ok());

    const { authorizedFetch } = await import('./authorizedFetch.js');
    const res = await authorizedFetch(`${apiBase}/api/x`, { method: 'POST', body: '{}' });

    expect(res.status).toBe(200);
    expect(fetchSpy).toHaveBeenCalledTimes(2);
    expect(headerOf(fetchSpy.mock.calls[1])).toBe('tok-live');
  });

  it('does NOT retry a 401 carrying a different errorCode', async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    mocks.invoke.mockResolvedValue({ origin: 'http://127.0.0.1:43210', token: 'tok-abc' });
    fetchSpy.mockResolvedValueOnce(unauthorized('AUTH_FORBIDDEN'));

    const { authorizedFetch } = await import('./authorizedFetch.js');
    const res = await authorizedFetch(`${apiBase}/api/x`, { method: 'POST' });

    expect(res.status).toBe(401);
    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });

  it('does NOT retry a non-401 response', async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    mocks.invoke.mockResolvedValue({ origin: 'http://127.0.0.1:43210', token: 'tok-abc' });
    fetchSpy.mockResolvedValueOnce(
      new Response(JSON.stringify({ errorCode: 'UI_TOKEN_REQUIRED' }), { status: 500 }),
    );

    const { authorizedFetch } = await import('./authorizedFetch.js');
    const res = await authorizedFetch(`${apiBase}/api/x`, { method: 'POST' });

    expect(res.status).toBe(500);
    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });

  it('leaves a probed response body readable by the caller', async () => {
    mocks.isTauriRuntime.mockReturnValue(true);
    mocks.invoke.mockResolvedValue({ origin: 'http://127.0.0.1:43210', token: 'tok-abc' });
    fetchSpy.mockResolvedValueOnce(unauthorized('AUTH_FORBIDDEN'));

    const { authorizedFetch } = await import('./authorizedFetch.js');
    const res = await authorizedFetch(`${apiBase}/api/x`, { method: 'POST' });

    // The rejection probe reads a CLONE, so the caller's body is still unconsumed.
    await expect(res.json()).resolves.toMatchObject({ errorCode: 'AUTH_FORBIDDEN' });
  });
});
