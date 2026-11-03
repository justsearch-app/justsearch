// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

const native = vi.hoisted(() => ({ invoke: vi.fn() }));
vi.mock('../utils/tauriRuntime', () => ({ isTauriRuntime: () => true }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: native.invoke }));

beforeEach(() => { vi.resetModules(); native.invoke.mockReset(); });
afterEach(() => { vi.unstubAllGlobals(); });
const origin = 'http://127.0.0.1:33221';

it('sends the window credential only beside its own origin token and preserves URL provenance', async () => {
  native.invoke.mockResolvedValue({ origin, token: 'session', windowCredential: 'window' });
  const send = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => Response.json({ ok: true }));
  vi.stubGlobal('fetch', send);
  const { fetchWithSessionToken } = await import('./http');
  await fetchWithSessionToken(origin + '/api/operations/core.add-watched-root/invoke', {
    method: 'POST', headers: { 'X-JustSearch-Transport': 'URL_DEEPLINK' }, body: '{}',
  });
  const options = send.mock.calls[0]![1] as RequestInit;
  const headers = new Headers(options.headers);
  expect(headers.get('X-JustSearch-Window')).toBe('window');
  expect(headers.get('X-JustSearch-Session')).toBe('session');
  expect(headers.get('X-JustSearch-Transport')).toBe('URL_DEEPLINK');
  expect(options.redirect).toBe('error');
  await fetchWithSessionToken('http://127.0.0.1:43311/api/status', {
    headers: { 'X-JustSearch-Session': 'session', 'X-JustSearch-Window': 'window' },
  });
  const elsewhere = new Headers((send.mock.calls[1]![1] as RequestInit).headers);
  expect(elsewhere.has('X-JustSearch-Window')).toBe(false);
  expect(elsewhere.has('X-JustSearch-Session')).toBe(false);
});

it('rotates both credentials once on window refusal and never loops', async () => {
  native.invoke.mockResolvedValueOnce({ origin, token: 'same-port-old-session', windowCredential: 'old-window' })
    .mockResolvedValueOnce({ origin, token: 'new-session', windowCredential: 'new-window' });
  const send = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) =>
    Response.json({ errorCode: 'WINDOW_CREDENTIAL_REQUIRED' }, { status: 403 }));
  vi.stubGlobal('fetch', send);
  const { fetchWithSessionToken } = await import('./http');
  const response = await fetchWithSessionToken(origin + '/api/authorizations/approve', { method: 'POST', body: '{}' });
  expect(response.status).toBe(403);
  expect(send).toHaveBeenCalledTimes(2);
  expect(new Headers(send.mock.calls[0]![1]?.headers).get('X-JustSearch-Window')).toBe('old-window');
  expect(new Headers(send.mock.calls[1]![1]?.headers).get('X-JustSearch-Window')).toBe('new-window');
  expect(native.invoke).toHaveBeenCalledTimes(2);
});

it('discards an in-flight credential answer across restart', async () => {
  let finish!: (credential: unknown) => void;
  native.invoke.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  const { resolveSessionTokenFromTauri, invalidateSessionToken, fetchWithSessionToken } = await import('./http');
  const resolving = resolveSessionTokenFromTauri();
  await vi.waitFor(() => expect(native.invoke).toHaveBeenCalledTimes(1));
  invalidateSessionToken();
  finish({ origin, token: 'dead-session', windowCredential: 'dead-window' });
  expect(await resolving).toBeNull();
  native.invoke.mockResolvedValue({ origin, token: 'live-session', windowCredential: 'live-window' });
  const send = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => Response.json({}));
  vi.stubGlobal('fetch', send);
  await fetchWithSessionToken(origin + '/api/status');
  expect(new Headers(send.mock.calls[0]![1]?.headers).get('X-JustSearch-Window')).toBe('live-window');
});

it('a deep-link operation keeps URL_DEEPLINK provenance even with the window credential attached', async () => {
  native.invoke.mockResolvedValue({ origin, token: 'session', windowCredential: 'window' });
  const send = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) =>
    Response.json({ success: true, message: 'done' }));
  vi.stubGlobal('fetch', send);
  const { OperationClient } = await import('../shell-v0/operations/OperationClient');
  const { createInvocationHandler } = await import('../shell-v0/router/invocationHandler');
  // The path the shell's deep-link and single-instance forwarding take (Shell.ts, tauriBridge.ts).
  await createInvocationHandler({ client: new OperationClient({ apiBase: origin }) }).handle(
    { kind: 'invoke', target: 'core.add-watched-root', args: { path: '/corpus' } }, 'URL_DEEPLINK');
  const headers = new Headers((send.mock.calls[0]![1] as RequestInit).headers);
  expect(headers.get('X-JustSearch-Window')).toBe('window');
  expect(headers.get('X-JustSearch-Transport')).toBe('URL_DEEPLINK');
});
