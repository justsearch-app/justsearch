// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ invoke: vi.fn() }));
vi.mock('../../utils/tauriRuntime', () => ({ isTauriRuntime: () => true }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }));
const originA = 'http://127.0.0.1:43111';
const originB = 'http://127.0.0.1:43112';
const credential = (origin: string, token: string) => ({ origin, token });
const sessionHeader = 'X-JustSearch-Session';
const ok = () => new Response('{}', { status: 200 });
const denied = () => new Response('{"errorCode":"UI_TOKEN_REQUIRED"}', { status: 401 });
const originalFetch = globalThis.fetch;
let send: ReturnType<typeof vi.fn>;
const header = (call: unknown[]) => new Headers((call[1] as RequestInit)?.headers
  ?? (call[0] instanceof Request ? call[0].headers : undefined)).get(sessionHeader);

beforeEach(() => {
  vi.resetModules();
  mocks.invoke.mockReset().mockResolvedValue(credential(originA, 'boot-a'));
  send = vi.fn().mockImplementation(async () => ok());
  globalThis.fetch = send as typeof fetch;
});
afterEach(() => { globalThis.fetch = originalFetch; });

describe('Engine credential origin', () => {
  it('never gives port A the new token when its rejection races a binding move to B', async () => {
    mocks.invoke.mockResolvedValueOnce(credential(originA, 'boot-a'))
      .mockResolvedValueOnce(credential(originB, 'boot-b'));
    send.mockResolvedValueOnce(denied());
    const { authorizedFetch } = await import('./authorizedFetch.js');
    expect((await authorizedFetch(`${originA}/api/search`, { method: 'POST', body: 'private query' })).status).toBe(401);
    expect(send).toHaveBeenCalledTimes(1);
    expect(header(send.mock.calls[0]!)).toBe('boot-a');
  });

  it.each([
    'http://localhost:43111/api/search',
    `${originB}/api/search`,
    'https://127.0.0.1:43111/api/search',
  ])('does not attach a token to override or other-host URL %s', async (url) => {
    const { authorizedFetch } = await import('./authorizedFetch.js');
    await authorizedFetch(url);
    expect(header(send.mock.calls[0]!)).toBeNull();
  });

  it('strips a session header carried by a foreign Request input', async () => {
    const { authorizedFetch } = await import('./authorizedFetch.js');
    await authorizedFetch(new Request(`${originB}/api/search`, { headers: { [sessionHeader]: 'boot-a' } }));
    expect(header(send.mock.calls[0]!)).toBeNull();
  });

  it('prevents redirects for token-bearing string and Request inputs', async () => {
    const { authorizedFetch } = await import('./authorizedFetch.js');
    for (const input of [`${originA}/api/search`, new Request(`${originA}/api/search`, { redirect: 'follow' })]) {
      await authorizedFetch(input, { redirect: 'follow' });
    }
    for (const call of send.mock.calls) {
      expect(header(call)).toBe('boot-a');
      expect(call[1].redirect).toBe('error');
    }
  });

  it('drops an in-flight resolution invalidated by a restart', async () => {
    let release!: (value: unknown) => void;
    mocks.invoke.mockImplementationOnce(() => new Promise((resolve) => { release = resolve; }));
    const { authorizedFetch } = await import('./authorizedFetch.js');
    const { invalidateSessionToken } = await import('../../api/http.js');
    const pending = authorizedFetch(`${originA}/api/search`);
    await vi.waitFor(() => expect(release).toBeDefined());
    invalidateSessionToken();
    release(credential(originA, 'predecessor'));
    await pending;
    expect(header(send.mock.calls[0]!)).toBeNull();
  });

  it('applies the binding and redirect rule to the general HTTP client and both stream paths', async () => {
    const { request } = await import('../../api/http.js');
    await request(originB, '/api/search', { retries: 1 });
    expect(header(send.mock.calls[0]!)).toBeNull();
    const { streamRequest, consumeShapeStream } = await import('../../api/streams.js');
    send.mockImplementation(async () => new Response('event: done\ndata: {}\n\n', {
      headers: { 'content-type': 'text/event-stream' },
    }));
    await streamRequest(`${originB}/api/search`, {}, {});
    await consumeShapeStream(`${originB}/api/search`, {}, () => {});
    for (const call of send.mock.calls) expect(header(call)).toBeNull();
    await consumeShapeStream(`${originA}/api/search`, {}, () => {});
    expect(header(send.mock.calls.at(-1)!)).toBe('boot-a');
    expect(send.mock.calls.at(-1)![1].redirect).toBe('error');
  });
});
