// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  invoke: vi.fn(),
  listen: vi.fn(),
  catalog: vi.fn(),
  endpoint: vi.fn(),
}));
vi.mock('ses', () => ({}));
vi.mock('../shell-v0/index.ts', () => ({}));
vi.mock('../utils/logger.ts', async (importOriginal) => ({
  ...await importOriginal(),
  appLog: { error: vi.fn(), warn: vi.fn() },
}));
vi.mock('../utils/tauriRuntime.js', () => ({ isTauriRuntime: () => true }));
vi.mock('../api/http', () => ({ resolveApiEndpoint: mocks.endpoint, invalidateSessionToken: vi.fn() }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }));
vi.mock('@tauri-apps/api/event', () => ({ listen: mocks.listen }));
vi.mock('../api/registry/SurfaceCatalogClient.ts', () => ({ bootSurfaceRegistry: mocks.catalog }));

afterEach(() => {
  document.body.innerHTML = '';
  vi.unstubAllGlobals();
});

it('the production entry point mounts recovery and does not boot API catalogs without a binding', async () => {
  vi.stubGlobal('lockdown', vi.fn());
  mocks.listen.mockResolvedValue(() => {});
  let reachedEndpoint;
  const discoveryObservations = [];
  const endpointReached = new Promise((resolve) => { reachedEndpoint = resolve; });
  mocks.endpoint.mockImplementation(async () => {
    discoveryObservations.push({
      mounted: document.querySelector('jf-engine-recovery') !== null,
      listening: mocks.listen.mock.calls.some(([event]) => event === 'justsearch://supervisor-state'),
    });
    reachedEndpoint();
    return { baseUrl: null };
  });
  mocks.invoke.mockImplementation(async (command) => command === 'supervisor_state' ? {
    schemaVersion: 1, kind: 'engine-supervisor-state.v1', supervisor: 'tauri',
    state: 'exhausted', incarnation: 1, restartCount: 3, maxRestartAttempts: 3,
  } : { state: 'up_to_date', currentVersion: '1.0' });
  document.body.innerHTML = '<div id="root"></div>';
  await import('../main.jsx');
  await endpointReached;
  expect(discoveryObservations[0]).toEqual({ mounted: true, listening: true });
  expect(mocks.endpoint).toHaveBeenCalledOnce();
  const recovery = document.querySelector('jf-engine-recovery');
  await recovery.updateComplete;
  expect(recovery.shadowRoot?.textContent).toContain('JustSearch could not restart');
  expect(mocks.catalog).not.toHaveBeenCalled();
});
