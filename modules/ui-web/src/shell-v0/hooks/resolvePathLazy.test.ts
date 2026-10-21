/**
 * Slice 3a.1.9 §A.7 — resolvePathLazy tests.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  __resetForTest as resetResolveCache,
  __seedForTest as seedResolveCache,
  resolvePathLazy,
} from './resolvePathLazy';
import {
  __resetForTest as resetResourceCatalog,
  __seedForTest as seedResourceCatalog,
} from '../../api/registry/ResourceCatalogClient';
import type { Resource, ResourceCatalog } from '../../api/types/registry';

function tabularResource(): Resource {
  return {
    id: 'core.indexing-jobs',
    presentation: {
      labelKey: 'k.label',
      descriptionKey: 'k.desc',
      iconHint: null,
      category: null,
    },
    schema: '',
    category: 'TABULAR',
    subscriptionMode: 'SSE_STREAM',
    endpoint: '/api/indexing-jobs/stream',
    kind: 'indexing-jobs-table',
    history: null,
    recovery: null,
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
    privacy: {
      pathPolicy: 'HASHED_REQUIRES_RESOLVER',
      loopbackOnly: true,
      resolver: 'core.resolve-path-hash',
    },
    itemOperations: [],
    collectionOperations: [],
    primaryKey: 'pathHash',
    audience: 'USER',
    consumers: [],
    role: 'PRODUCT',
  };
}

function catalogOf(r: Resource): ResourceCatalog {
  return {
    schemaVersion: '1.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'Resource',
    entries: [r],
  };
}

describe('resolvePathLazy', () => {
  beforeEach(() => {
    resetResolveCache();
    resetResourceCatalog();
  });
  afterEach(() => {
    resetResolveCache();
    resetResourceCatalog();
  });

  it('returns null for unknown resource', async () => {
    const r = await resolvePathLazy('core.does-not-exist', 'h1');
    expect(r).toBeNull();
  });

  it('returns null for resource with NO_PATHS privacy', async () => {
    const r = tabularResource();
    r.privacy = { pathPolicy: 'NO_PATHS', loopbackOnly: true, resolver: null };
    seedResourceCatalog(catalogOf(r));
    const result = await resolvePathLazy('core.indexing-jobs', 'h1');
    expect(result).toBeNull();
  });

  it('returns null for resource missing resolver', async () => {
    const r = tabularResource();
    r.privacy = {
      pathPolicy: 'HASHED_REQUIRES_RESOLVER',
      loopbackOnly: true,
      resolver: null,
    };
    seedResourceCatalog(catalogOf(r));
    const result = await resolvePathLazy('core.indexing-jobs', 'h1');
    expect(result).toBeNull();
  });

  it('memoizes seeded resolutions without HTTP', async () => {
    seedResolveCache('core.indexing-jobs', 'h1', '/abs/path.txt');
    const r = await resolvePathLazy('core.indexing-jobs', 'h1');
    expect(r).toBe('/abs/path.txt');
  });

  it('hydrates a core label through the observation read with the scoped pathHash', async () => {
    seedResourceCatalog(catalogOf(tabularResource()));
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () =>
        Promise.resolve({
          found: true, path: '/x/y.txt',
        }),
      headers: new Headers(),
    });
    const result = await resolvePathLazy('core.indexing-jobs', 'h2', {
      apiBase: 'http://localhost',
      fetchImpl: fetchImpl as unknown as typeof fetch,
    });
    expect(result).toBe('/x/y.txt');
    expect(fetchImpl).toHaveBeenCalled();
    const call = fetchImpl.mock.calls[0];
    const url = call?.[0] as string;
    const init = call?.[1] as RequestInit;
    expect(url).toContain('/api/library/path-label');
    expect(init?.body).toBeDefined();
    const body = JSON.parse(init.body as string);
    // Arg name uses primaryKey (pathHash); rowKey is the value.
    expect(body).toEqual({ pathHash: 'h2' });
  });

  it('returns null on resolver "found: false"', async () => {
    seedResourceCatalog(catalogOf(tabularResource()));
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () =>
        Promise.resolve({
          found: false,
        }),
      headers: new Headers(),
    });
    const result = await resolvePathLazy('core.indexing-jobs', 'h3', {
      apiBase: 'http://localhost',
      fetchImpl: fetchImpl as unknown as typeof fetch,
    });
    expect(result).toBeNull();
  });

  it('memoizes after first successful resolution', async () => {
    seedResourceCatalog(catalogOf(tabularResource()));
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () =>
        Promise.resolve({
          found: true, path: '/z.txt',
        }),
      headers: new Headers(),
    });
    await resolvePathLazy('core.indexing-jobs', 'h4', {
      apiBase: 'http://localhost',
      fetchImpl: fetchImpl as unknown as typeof fetch,
    });
    await resolvePathLazy('core.indexing-jobs', 'h4', {
      apiBase: 'http://localhost',
      fetchImpl: fetchImpl as unknown as typeof fetch,
    });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  // Tempdoc 804 §B9 (round-10 F8): a FAILED invocation must stay retryable. It used to be memoized
  // as a permanent null, so one 401 (or any transport hiccup) left every Library folder row showing
  // its path hash for the rest of the session, even after the cause cleared.
  it('does NOT memoize a failed invocation — a later call retries and can succeed', async () => {
    seedResourceCatalog(catalogOf(tabularResource()));
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce({
        ok: false,
        status: 401,
        json: () => Promise.resolve({ error: 'unauthorized' }),
        headers: new Headers(),
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: () =>
          Promise.resolve({ found: true, path: '/recovered.txt' }),
        headers: new Headers(),
      });

    const first = await resolvePathLazy('core.indexing-jobs', 'h5', {
      apiBase: 'http://localhost',
      fetchImpl: fetchImpl as unknown as typeof fetch,
    });
    expect(first).toBeNull();

    const second = await resolvePathLazy('core.indexing-jobs', 'h5', {
      apiBase: 'http://localhost',
      fetchImpl: fetchImpl as unknown as typeof fetch,
    });
    expect(second).toBe('/recovered.txt');
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  // The other half of the same rule: a DEFINITIVE `found: false` is still memoized (no retry storm).
  it('memoizes a definitive "found: false"', async () => {
    seedResourceCatalog(catalogOf(tabularResource()));
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ found: false }),
      headers: new Headers(),
    });
    await resolvePathLazy('core.indexing-jobs', 'h6', {
      apiBase: 'http://localhost',
      fetchImpl: fetchImpl as unknown as typeof fetch,
    });
    await resolvePathLazy('core.indexing-jobs', 'h6', {
      apiBase: 'http://localhost',
      fetchImpl: fetchImpl as unknown as typeof fetch,
    });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });
});

it('bounds the label cache over a long run, retaining recent Windows and UNC identities', async () => {
  resetResolveCache();
  for (let i = 0; i < 2000; i++) seedResolveCache('core.indexing-jobs', `hash-${i}`, `C:\\docs\\${i}.txt`);
  seedResolveCache('core.indexing-jobs', 'unc', '\\\\server\\share\\file.txt');
  expect(await resolvePathLazy('core.indexing-jobs', 'hash-1999')).toBe('C:\\docs\\1999.txt');
  expect(await resolvePathLazy('core.indexing-jobs', 'unc')).toBe('\\\\server\\share\\file.txt');
  // Oldest entries must have been evicted, so a definitive miss replaces the old path.
  resetResourceCatalog();
  expect(await resolvePathLazy('core.indexing-jobs', 'hash-0')).toBeNull();
});
