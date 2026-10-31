// @vitest-environment happy-dom
// Preservation characterization captured at bc827e04b0 before moving declarations.
// The cold/recovery snapshots record existing gaps; they do not establish desired offline behavior.
import wire from './__fixtures__/core-surfaces.before.json';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PluginRegistry } from '../../shell-v0/plugin-api/PluginRegistry.js';
import { createCorePluginManifest } from '../../shell-v0/plugin-api/CorePlugin.js';
import { __resetForTest, bootSurfaceRegistry, listSurfaces } from './SurfaceCatalogClient.js';

const baseUrl = 'http://catalog.test';
const etag = '"baseline-catalog"';

function response(status = 200, body: unknown = wire): Response {
  return { ok: status === 200, status, headers: new Headers({ ETag: etag }),
    json: async () => body } as Response;
}

function installCore() {
  const registry = new PluginRegistry();
  registry.install(createCorePluginManifest(), 'CORE');
  expect(registry.get('core')?.registerError).toBeNull();
  return registry;
}

function snapshotCatalog() {
  return listSurfaces().map(({ factory, provenance, ...surface }) => {
    expect(factory).toBeDefined();
    const { installedAt: _installedAt, ...stableProvenance } = provenance;
    return { ...surface, provenance: stableProvenance };
  });
}

function cacheWire() {
  localStorage.setItem('justsearch.surfaceCatalog.body', JSON.stringify(wire));
  localStorage.setItem('justsearch.surfaceCatalog.etag', etag);
}

beforeEach(() => {
  __resetForTest();
  vi.spyOn(console, 'debug').mockImplementation(() => {});
  vi.spyOn(console, 'error').mockImplementation(() => {});
  // Keep the production retry path, without waiting for wall-clock backoff.
  vi.stubGlobal('setTimeout', ((fn: () => void) => { fn(); return 0; }) as typeof setTimeout);
});
afterEach(() => { __resetForTest(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('ordered effective catalog preservation', () => {
  it('online boot fetches wire before installing frontend contributions and caches only wire', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(response());
    await bootSurfaceRegistry(baseUrl, fetchImpl);
    const beforeInstall = snapshotCatalog();
    installCore();
    expect({ beforeInstall, installed: snapshotCatalog() }).toMatchSnapshot();
    expect(JSON.parse(localStorage.getItem('justsearch.surfaceCatalog.body')!)).toEqual(wire);
  });

  it('cached 304 sends ETag and installs contributions over the restored wire', async () => {
    cacheWire();
    const fetchImpl = vi.fn().mockResolvedValue(response(304));
    await bootSurfaceRegistry(baseUrl, fetchImpl);
    expect(fetchImpl).toHaveBeenCalledExactlyOnceWith(`${baseUrl}/api/registry/surfaces`, {
      headers: { 'If-None-Match': etag },
    });
    installCore();
    expect(snapshotCatalog()).toMatchSnapshot();
    expect(JSON.parse(localStorage.getItem('justsearch.surfaceCatalog.body')!)).toEqual(wire);
  });

  it.each(['network', 'http', 'rejected-body'] as const)('cached %s failure retains wire and contributions', async (failure) => {
    cacheWire();
    const fetchImpl = failure === 'network'
      ? vi.fn().mockRejectedValue(new Error('offline'))
      : vi.fn().mockResolvedValue(failure === 'http' ? response(503) : response(200, { entries: [] }));
    await bootSurfaceRegistry(baseUrl, fetchImpl);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    installCore();
    expect(snapshotCatalog()).toMatchSnapshot();
    expect(JSON.parse(localStorage.getItem('justsearch.surfaceCatalog.body')!)).toEqual(wire);
  });

  it('cold failure retains the existing local fallback after capped retries', async () => {
    const fetchImpl = vi.fn().mockRejectedValue(new Error('offline'));
    await bootSurfaceRegistry(baseUrl, fetchImpl);
    expect(fetchImpl).toHaveBeenCalledTimes(5);
    installCore();
    expect(snapshotCatalog()).toMatchSnapshot();
    expect(localStorage.getItem('justsearch.surfaceCatalog.body')).toBeNull();
  });

  it('failure → install → backend-ready attempt → success preserves the observed recovery sequence', async () => {
    const fetchImpl = vi.fn().mockRejectedValue(new Error('offline'));
    await bootSurfaceRegistry(baseUrl, fetchImpl);
    installCore();
    const installed = snapshotCatalog();
    fetchImpl.mockResolvedValue(response());
    await bootSurfaceRegistry(baseUrl, fetchImpl);
    expect({ installed, recovered: snapshotCatalog() }).toMatchSnapshot();
    expect(JSON.parse(localStorage.getItem('justsearch.surfaceCatalog.body')!)).toEqual(wire);
  });
});
