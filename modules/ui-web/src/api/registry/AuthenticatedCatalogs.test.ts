// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({ invoke: vi.fn() }));
vi.mock('../../utils/tauriRuntime', () => ({ isTauriRuntime: () => true }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }));
const base = 'http://127.0.0.1:33221';
afterEach(() => { localStorage.clear(); vi.unstubAllGlobals(); vi.resetModules(); mocks.invoke.mockReset(); });

const calls = [
  ['operation boot', async () => (await import('./OperationCatalogClient.js')).bootOperationRegistry(base)],
  ['operation refresh', async () => (await import('./OperationCatalogClient.js')).refreshOperationCatalog(base)],
  ['resource boot', async () => (await import('./ResourceCatalogClient.js')).bootResourceRegistry(base)],
  ['resource refresh', async () => (await import('./ResourceCatalogClient.js')).refreshResourceCatalog(base)],
  ['surface boot', async () => (await import('./SurfaceCatalogClient.js')).bootSurfaceRegistry(base)],
  ['shape boot', async () => (await import('./ConversationShapeCatalogClient.js')).bootConversationShapeRegistry(base)],
  ['diagnostic boot', async () => (await import('./DiagnosticChannelCatalogClient.js')).bootDiagnosticChannelRegistry(base)],
] as const;

it.each(calls)('authenticates the default %s fetch', async (_name, call) => {
  mocks.invoke.mockResolvedValue({ origin: 'http://127.0.0.1:33221', token: 'owner-boot-token' });
  // Seed conditional-read caches so 304 is a valid response, without first-install backoff.
  for (const [name, primitive] of [['surfaceCatalog', 'Surface'], ['conversationShapeCatalog', 'ConversationShape']]) {
    localStorage.setItem(`justsearch.${name}.body`, JSON.stringify({ schemaVersion: '1.0', catalogVersion: 1, namespace: 'core', primitive, entries: [] }));
    localStorage.setItem(`justsearch.${name}.etag`, 'catalog-fixture');
  }
  const send = vi.fn().mockImplementation(() => Promise.resolve(new Response(null, { status: 304 })));
  vi.stubGlobal('fetch', send);
  await call();
  expect(send).toHaveBeenCalledTimes(1);
  expect(new Headers(send.mock.calls[0]?.[1]?.headers).get('X-JustSearch-Session')).toBe('owner-boot-token');
});

it('authenticates canonical Engine schema reads', async () => {
  mocks.invoke.mockResolvedValue({ origin: 'http://127.0.0.1:33221', token: 'owner-boot-token' });
  const send = vi.fn().mockImplementation(() => Promise.resolve(Response.json({ type: 'object' })));
  vi.stubGlobal('fetch', send);
  const { fetchSchema } = await import('./schemaFetcher.js');
  expect(await fetchSchema('https://ssot.justsearch/v1/schemas/example.v1.json')).toEqual({ type: 'object' });
  expect(new Headers(send.mock.calls[0]?.[1]?.headers).get('X-JustSearch-Session')).toBe('owner-boot-token');
});

it('keeps external schema documents credential-free', async () => {
  mocks.invoke.mockResolvedValue({ origin: 'http://127.0.0.1:33221', token: 'owner-boot-token' });
  const send = vi.fn().mockImplementation(() => Promise.resolve(Response.json({ type: 'object' })));
  vi.stubGlobal('fetch', send);
  const { fetchSchema } = await import('./schemaFetcher.js');
  expect(await fetchSchema('https://example.com/schema.json')).toEqual({ type: 'object' });
  expect(new Headers(send.mock.calls[0]?.[1]?.headers).has('X-JustSearch-Session')).toBe(false);
  expect(mocks.invoke).not.toHaveBeenCalled();
});
