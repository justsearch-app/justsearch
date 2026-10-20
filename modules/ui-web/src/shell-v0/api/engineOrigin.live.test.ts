// @vitest-environment node
import { createServer, type IncomingMessage, type ServerResponse, type Server } from 'node:http';
import { afterEach, describe, expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({ invoke: vi.fn() }));
vi.mock('../../utils/tauriRuntime', () => ({ isTauriRuntime: () => true }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }));
const servers: Server[] = [];
async function listen(handler: (request: IncomingMessage, response: ServerResponse) => void) {
  const server = createServer(handler);
  servers.push(server);
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('Missing listener address');
  return `http://127.0.0.1:${address.port}`;
}
afterEach(async () => {
  for (const server of servers.splice(0)) {
    server.closeAllConnections();
    await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  }
  vi.resetModules();
  mocks.invoke.mockReset();
});

describe('Engine origin on real HTTP connections', () => {
  it('does not send a successor credential back to the impostor returning TOKEN_REQUIRED', async () => {
    const received: (string | string[] | undefined)[] = [];
    const originB = await listen((_request, response) => response.end('{}'));
    const originA = await listen((request, response) => {
      received.push(request.headers['x-justsearch-session']);
      response.writeHead(401, { 'Content-Type': 'application/json' });
      response.end('{"errorCode":"UI_TOKEN_REQUIRED"}');
    });
    mocks.invoke.mockResolvedValueOnce({ origin: originA, token: 'dead-incarnation' })
      .mockResolvedValueOnce({ origin: originB, token: 'live-incarnation' });
    const { authorizedFetch } = await import('./authorizedFetch.js');
    expect((await authorizedFetch(`${originA}/api/search`, { method: 'POST', body: '{}' })).status).toBe(401);
    expect(received).toEqual(['dead-incarnation']);
  });

  it('does not follow a redirect carrying the session header or request body', async () => {
    let redirected = 0;
    const destination = await listen((_request, response) => { redirected++; response.end('{}'); });
    const origin = await listen((_request, response) => {
      response.writeHead(307, { Location: `${destination}/capture` }); response.end();
    });
    mocks.invoke.mockResolvedValue({ origin, token: 'fixture-credential' });
    const { authorizedFetch } = await import('./authorizedFetch.js');
    await expect(authorizedFetch(`${origin}/redirect`, { method: 'POST', body: 'private query' })).rejects.toThrow();
    expect(redirected).toBe(0);
  });
});
