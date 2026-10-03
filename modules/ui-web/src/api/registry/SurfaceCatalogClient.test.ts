/**
 * Slice 449 phase 5 — SurfaceCatalogClient tests.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Surface, SurfaceCatalog } from '../types/surface';
import {
  __resetForTest,
  __seedForTest,
  bootSurfaceRegistry,
  getSurface,
  listSurfaces,
  listSurfacesByPlacement,
  mergePluginSurfaceContributions,
  onSurfaceCatalogChange,
} from './SurfaceCatalogClient';
import type { Provenance } from '../types/registry';

function librarySurface(id: string = 'core.library-surface'): Surface {
  return {
    id,
    presentation: {
      labelKey: 'registry-surface.library-surface.label',
      descriptionKey: 'registry-surface.library-surface.description',
      iconHint: null,
      category: null,
    },
    audience: 'USER',
    placement: 'RAIL',
    consumes: {
      resources: [],
      operations: [
        'core.reindex',
        'core.add-watched-root',
        'core.remove-watched-root',
        'core.preview-excludes',
        'core.apply-excludes',
      ],
      prompts: [],
      diagnosticChannels: [],
    },
    mountTag: 'jf-library-surface',
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
  };
}

function operatorSurface(id: string): Surface {
  return {
    ...librarySurface(id),
    audience: 'OPERATOR',
    placement: 'STAGE',
  };
}

function catalogOf(...entries: Surface[]): SurfaceCatalog {
  return {
    schemaVersion: '1.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'Surface',
    entries,
  };
}

// The RAW WIRE shape served by `RegistryController.handleSurfaces` — what the boot-fetch path parses
// through the generated `surfaceWireSchema` (strict, and precise all the way down since tempdoc 884:
// Surface / SurfaceConsumes / SurfaceStateSchema / StateBinding are `PreciseWire`). The FE `Surface`
// type relaxes `altitude` / `members` / `riskTier` / `stateSchema` / `consumes.conversationShapes` to
// optional for client-constructed literals (plugin contributions, these fixtures); the wire always
// carries them, so a drifting mock now throws at the boundary. Mirrors the wire-faithful builder in
// OperationCatalogClient.test.ts.
function surfaceWireEntry(fe: Surface): unknown {
  return {
    ...fe,
    altitude: fe.altitude ?? 'PRODUCT',
    members: fe.members ?? [],
    riskTier: fe.riskTier ?? 'LOW',
    stateSchema: fe.stateSchema ?? null,
    consumes: {
      ...fe.consumes,
      conversationShapes: fe.consumes.conversationShapes ?? [],
    },
    provenance: {
      tier: fe.provenance.tier,
      contributorId: fe.provenance.contributorId,
      version: fe.provenance.version,
      identity: fe.provenance.identity
        ? {
            verified: fe.provenance.identity.verified,
            signature: fe.provenance.identity.signature ?? null,
          }
        : null,
    },
  };
}

function wireCatalogOf(...entries: Surface[]): unknown {
  return { ...catalogOf(...entries), entries: entries.map(surfaceWireEntry) };
}

describe('mergePluginSurfaceContributions — TRUST + DIAGNOSTIC altitude clamp (tempdoc 571 §4d)', () => {
  beforeEach(() => __resetForTest());
  afterEach(() => __resetForTest());

  function merge(id: string, altitude: 'PRODUCT' | 'DIAGNOSTIC' | 'TRUST' | 'TOOL', prov: Provenance) {
    mergePluginSurfaceContributions([
      {
        pluginId: prov.contributorId,
        contribution: {
          id,
          mountTag: `jf-${id.replace(/\./g, '-')}`,
          labelKey: 'x',
          descriptionKey: 'y',
          audience: 'USER',
          placement: 'RAIL',
          altitude,
        },
        effectiveAudience: 'USER',
        provenance: prov,
      },
    ]);
  }

  // The FE mints CORE surfaces through the plugin path as tier TRUSTED_PLUGIN + contributorId 'core'.
  it('keeps TRUST for a first-party core contribution (TRUSTED_PLUGIN + contributorId core)', () => {
    merge('core.activity-surface', 'TRUST', {
      tier: 'TRUSTED_PLUGIN',
      contributorId: 'core',
      version: '1.0',
    } as Provenance);
    expect(getSurface('core.activity-surface')?.altitude).toBe('TRUST');
  });

  it('preserves consumes.conversationShapes so a jf-chat-shape-mount surface can derive shape-id (560 §28.G)', () => {
    mergePluginSurfaceContributions([
      {
        pluginId: 'vendor.checklist',
        contribution: {
          id: 'vendor.checklist.shape-surface',
          mountTag: 'jf-chat-shape-mount',
          labelKey: 'x',
          descriptionKey: 'y',
          audience: 'USER',
          placement: 'RAIL',
          consumes: { conversationShapes: ['vendor.checklist.demo-shape'] },
        },
        effectiveAudience: 'USER',
        provenance: {
          tier: 'TRUSTED_PLUGIN',
          contributorId: 'vendor.checklist',
          version: '0.1',
        } as Provenance,
      },
    ]);
    expect(getSurface('vendor.checklist.shape-surface')?.consumes.conversationShapes).toEqual([
      'vendor.checklist.demo-shape',
    ]);
  });

  it('keeps TRUST for CORE-tier provenance', () => {
    merge('core.activity-surface', 'TRUST', {
      tier: 'CORE',
      contributorId: 'core',
      version: '1.0',
    } as Provenance);
    expect(getSurface('core.activity-surface')?.altitude).toBe('TRUST');
  });

  it('clamps a genuinely third-party TRUST claim to PRODUCT (forged-trust foreclosure)', () => {
    merge('acme.evil-surface', 'TRUST', {
      tier: 'UNTRUSTED_PLUGIN',
      contributorId: 'acme',
      version: '1.0',
    } as Provenance);
    expect(getSurface('acme.evil-surface')?.altitude).toBe('PRODUCT');
  });

  it('clamps a genuinely third-party DIAGNOSTIC claim to PRODUCT (diagnostic plugin-ineligible until 560 §4a)', () => {
    // tempdoc 571 §4d: a diagnostic surface is plugin-ineligible until backend capability-attenuation
    // (560 §4a) ships — a plugin cannot claim diagnostic altitude / Diagnostics-band homing.
    merge('acme.diag-surface', 'DIAGNOSTIC', {
      tier: 'TRUSTED_PLUGIN',
      contributorId: 'acme',
      version: '1.0',
    } as Provenance);
    expect(getSurface('acme.diag-surface')?.altitude).toBe('PRODUCT');
  });

  it('keeps DIAGNOSTIC for a first-party (core) contribution', () => {
    merge('core.logs-surface', 'DIAGNOSTIC', {
      tier: 'TRUSTED_PLUGIN',
      contributorId: 'core',
      version: '1.0',
    } as Provenance);
    expect(getSurface('core.logs-surface')?.altitude).toBe('DIAGNOSTIC');
  });

  it('does not clamp a product/tool altitude from a third party (PRODUCT/TOOL stay plugin-eligible)', () => {
    merge('acme.dashboard-surface', 'PRODUCT', {
      tier: 'TRUSTED_PLUGIN',
      contributorId: 'acme',
      version: '1.0',
    } as Provenance);
    expect(getSurface('acme.dashboard-surface')?.altitude).toBe('PRODUCT');
  });

  // Single-authority (tempdoc 571 §9 fix): altitude's sole authority is the wire/Java catalog. A core
  // contribution OMITS altitude, so the merge must PRESERVE the wire entry's altitude, not clobber it.
  function mergeNoAltitude(id: string, prov: Provenance) {
    mergePluginSurfaceContributions([
      {
        pluginId: prov.contributorId,
        contribution: {
          id,
          mountTag: `jf-${id.replace(/\./g, '-')}`,
          labelKey: 'x',
          descriptionKey: 'y',
          audience: 'USER',
          placement: 'RAIL',
          // altitude intentionally omitted — CorePlugin no longer declares it.
        },
        effectiveAudience: 'USER',
        provenance: prov,
      },
    ]);
  }

  it('preserves the wire entry altitude when a contribution omits it (single authority)', () => {
    // The wire (Java catalog, /api/registry/surfaces) populated altitude TRUST before the merge.
    __seedForTest(
      catalogOf({ ...librarySurface('core.activity-surface'), altitude: 'TRUST' } as Surface),
    );
    mergeNoAltitude('core.activity-surface', {
      tier: 'TRUSTED_PLUGIN',
      contributorId: 'core',
      version: '1.0',
    } as Provenance);
    // Preserved, NOT clobbered to PRODUCT.
    expect(getSurface('core.activity-surface')?.altitude).toBe('TRUST');
  });

  it('falls back to PRODUCT when neither the wire nor the contribution declares altitude', () => {
    __seedForTest(catalogOf(librarySurface('core.search-surface'))); // wire has no altitude
    mergeNoAltitude('core.search-surface', {
      tier: 'TRUSTED_PLUGIN',
      contributorId: 'core',
      version: '1.0',
    } as Provenance);
    expect(getSurface('core.search-surface')?.altitude ?? 'PRODUCT').toBe('PRODUCT');
  });

  // Tempdoc 571 §11 / 578 — `members` is single-authority (the Java wire). A CORE contribution omits
  // it, so the merge must PRESERVE the wire entry's members (the host/member relationship is the wire's
  // to own). Guards the wire→FE round-trip the whole composition feature depends on (wire-emitter-elision).
  it('preserves the wire entry members when a contribution omits them (single authority)', () => {
    __seedForTest(
      catalogOf({
        ...librarySurface('core.library-surface'),
        members: ['core.browse-surface'],
      } as Surface),
    );
    mergeNoAltitude('core.library-surface', {
      tier: 'TRUSTED_PLUGIN',
      contributorId: 'core',
      version: '1.0',
    } as Provenance);
    expect(getSurface('core.library-surface')?.members).toEqual(['core.browse-surface']);
  });
});

describe('SurfaceCatalogClient', () => {
  beforeEach(() => {
    __resetForTest();
  });
  afterEach(() => {
    __resetForTest();
  });

  describe('lookup API', () => {
    it('returns undefined for unknown id before boot', () => {
      expect(getSurface('core.unknown-surface')).toBeUndefined();
    });

    it('returns the seeded entry by id', () => {
      __seedForTest(catalogOf(librarySurface()));
      const s = getSurface('core.library-surface');
      expect(s?.audience).toBe('USER');
      expect(s?.placement).toBe('RAIL');
      expect(s?.mountTag).toBe('jf-library-surface');
    });

    it('listSurfaces returns all entries', () => {
      __seedForTest(
        catalogOf(librarySurface('core.library-surface'), operatorSurface('core.engine-log-surface')),
      );
      expect(
        listSurfaces()
          .map((s) => s.id)
          .sort(),
      ).toEqual(['core.engine-log-surface', 'core.library-surface']);
    });

    it('listSurfacesByPlacement filters by chrome zone', () => {
      __seedForTest(
        catalogOf(librarySurface('core.library-surface'), operatorSurface('core.engine-log-surface')),
      );
      expect(listSurfacesByPlacement('RAIL').map((s) => s.id)).toEqual([
        'core.library-surface',
      ]);
      expect(listSurfacesByPlacement('STAGE').map((s) => s.id)).toEqual([
        'core.engine-log-surface',
      ]);
      expect(listSurfacesByPlacement('HUD')).toEqual([]);
    });
  });

  describe('boot fetch', () => {
    it('populates the catalog on 200', async () => {
      const catalog = wireCatalogOf(librarySurface());
      const fetchImpl = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        headers: { get: () => '"etag-1"' },
        json: () => Promise.resolve(catalog),
      } as unknown as Response);
      await bootSurfaceRegistry('http://127.0.0.1:33221', fetchImpl);
      expect(getSurface('core.library-surface')).toBeDefined();
    });

    it('swallows fetch errors and retains cached entries', async () => {
      __seedForTest(catalogOf(librarySurface()));
      const fetchImpl = vi.fn().mockRejectedValue(new Error('network down'));
      __resetForTest();
      __seedForTest(catalogOf(librarySurface()));
      await expect(
        bootSurfaceRegistry('http://127.0.0.1:33221', fetchImpl),
      ).resolves.toBeUndefined();
      expect(getSurface('core.library-surface')).toBeDefined();
    });

    it('first-install boot retries on transient failure (observations.md fix)', async () => {
      // No cached catalog; first attempt parse-fails (empty body), second
      // succeeds. Without retry the rail would stay empty until refresh.
      let call = 0;
      const fetchImpl = vi.fn().mockImplementation(() => {
        call++;
        if (call === 1) {
          return Promise.resolve({
            ok: true,
            status: 200,
            headers: { get: () => null },
            json: () => Promise.reject(new SyntaxError('Unexpected end of JSON input')),
          } as unknown as Response);
        }
        return Promise.resolve({
          ok: true,
          status: 200,
          headers: { get: () => '"etag-1"' },
          json: () => Promise.resolve(wireCatalogOf(librarySurface())),
        } as unknown as Response);
      });
      // Speed up the test: stub setTimeout to fire immediately.
      const realSetTimeout = globalThis.setTimeout;
      vi.stubGlobal('setTimeout', ((fn: () => void) => {
        fn();
        return 0 as unknown as ReturnType<typeof setTimeout>;
      }) as typeof setTimeout);
      try {
        await bootSurfaceRegistry('http://127.0.0.1:33221', fetchImpl);
      } finally {
        vi.stubGlobal('setTimeout', realSetTimeout);
      }
      expect(call).toBeGreaterThanOrEqual(2);
      expect(getSurface('core.library-surface')).toBeDefined();
    });

    it('first-install gives up after capped retries when backend stays down', async () => {
      // Drains the full retry budget and exits with empty catalog rather than
      // retrying forever. Asserts the retry budget is bounded.
      const fetchImpl = vi.fn().mockRejectedValue(new Error('transient'));
      const realSetTimeout = globalThis.setTimeout;
      vi.stubGlobal('setTimeout', ((fn: () => void) => {
        fn();
        return 0 as unknown as ReturnType<typeof setTimeout>;
      }) as typeof setTimeout);
      try {
        await bootSurfaceRegistry('http://127.0.0.1:33221', fetchImpl);
      } finally {
        vi.stubGlobal('setTimeout', realSetTimeout);
      }
      // 1 initial + 4 retries = 5 attempts.
      expect(fetchImpl).toHaveBeenCalledTimes(5);
      expect(getSurface('core.library-surface')).toBeUndefined();
    });

    it('returning users (cached catalog in localStorage) do not retry on fetch failure', async () => {
      // Restored regression-guard: returning users with a cached catalog must
      // NOT trigger the first-install retry loop. Pre-populate localStorage with
      // the cache the boot flow expects (body + etag), make fetch fail, and
      // assert exactly one fetch attempt was made.
      const cachedCatalog = catalogOf(librarySurface());
      const store = new Map<string, string>();
      store.set('justsearch.surfaceCatalog.body', JSON.stringify(cachedCatalog));
      store.set('justsearch.surfaceCatalog.etag', '"etag-cached"');
      vi.stubGlobal('localStorage', {
        getItem: (k: string) => store.get(k) ?? null,
        setItem: (k: string, v: string) => {
          store.set(k, v);
        },
        removeItem: (k: string) => {
          store.delete(k);
        },
        clear: () => {
          store.clear();
        },
        key: (i: number) => Array.from(store.keys())[i] ?? null,
        get length() {
          return store.size;
        },
      } as unknown as Storage);

      const fetchImpl = vi.fn().mockRejectedValue(new Error('transient'));
      const realSetTimeout = globalThis.setTimeout;
      vi.stubGlobal('setTimeout', ((fn: () => void) => {
        fn();
        return 0 as unknown as ReturnType<typeof setTimeout>;
      }) as typeof setTimeout);
      try {
        await bootSurfaceRegistry('http://127.0.0.1:33221', fetchImpl);
      } finally {
        vi.stubGlobal('setTimeout', realSetTimeout);
        vi.unstubAllGlobals();
      }
      // Cached catalog rendered from storage; only ONE fetch attempt — no retry.
      expect(fetchImpl).toHaveBeenCalledTimes(1);
      expect(getSurface('core.library-surface')).toBeDefined();
    });
  });

  describe('catalog change listener', () => {
    it('fires on a successful boot', async () => {
      const listener = vi.fn();
      onSurfaceCatalogChange(listener);
      const fetchImpl = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        headers: { get: () => null },
        json: () => Promise.resolve(wireCatalogOf(librarySurface())),
      } as unknown as Response);
      await bootSurfaceRegistry('http://127.0.0.1:33221', fetchImpl);
      expect(listener).toHaveBeenCalledOnce();
    });

    it('unsubscribe stops further notifications (V1.5 hot-reload teardown shape)', () => {
      const listener = vi.fn();
      const off = onSurfaceCatalogChange(listener);
      __seedForTest(catalogOf(librarySurface()));
      expect(listener).toHaveBeenCalledOnce();
      off();
      __seedForTest(catalogOf(operatorSurface('core.engine-log-surface')));
      // Listener still has 1 call after unsubscribe; the second seed should
      // not re-trigger it.
      expect(listener).toHaveBeenCalledOnce();
    });
  });
});
