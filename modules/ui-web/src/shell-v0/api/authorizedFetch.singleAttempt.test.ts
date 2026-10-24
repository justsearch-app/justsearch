// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({ invoke: vi.fn() }));
vi.mock('../../utils/tauriRuntime', () => ({ isTauriRuntime: () => true }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }));
beforeEach(() => { vi.resetModules(); mocks.invoke.mockReset(); });
afterEach(() => { vi.unstubAllGlobals(); });
it('R1 a single-attempt authenticated mutation cannot replay after a stale-token refusal', async () => {
  const origin = 'http://127.0.0.1:43210';
  mocks.invoke.mockResolvedValueOnce({ origin, token: 'old-token' }).mockResolvedValue({ origin, token: 'fresh-token' });
  const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ errorCode: 'UI_TOKEN_REQUIRED' }), { status: 401 }))
    .mockResolvedValue(new Response('{}'));
  vi.stubGlobal('fetch', fetch);
  const { authorizedFetch } = await import('./authorizedFetch.js');
  const response = await authorizedFetch(origin + '/api/memory/one', { method: 'DELETE' }, { retry: false });
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(response.status).toBe(401);
  expect(new Headers(fetch.mock.calls[0]?.[1].headers).get('X-JustSearch-Session')).toBe('old-token');
  expect(fetch.mock.calls[0]?.[1].redirect).toBe('error');
});
