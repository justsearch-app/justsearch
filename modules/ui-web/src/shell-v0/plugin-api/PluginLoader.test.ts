// @vitest-environment happy-dom

/**
 * Tests for slices 465 (Plugin Loader) + 469 (Hot-reload) +
 * 477 H2.1 (V1.5.1 Compartment-Loader integration).
 *
 * H2.1 flipped the loader from native `import()` (host-realm
 * execution) to source-text fetch + Compartment.evaluate (separate
 * realm). The plugin source contract changed to "expression
 * evaluating to manifest object or factory function." These tests
 * cover the new contract end-to-end.
 *
 * Covers:
 *   - loadPluginFromUrl: source fetch → compartment evaluate →
 *     factory invocation → install round-trip
 *   - PluginLoadError stages (fetch / evaluate / shape / install)
 *     with cause preservation
 *   - shape rejection on missing required fields
 *   - install-stage failures (e.g., duplicate id) propagate as
 *     PluginLoadError(stage='install') with cause
 *   - factory pattern: source as `(endowments) => manifest`
 *   - direct manifest pattern: source as `({ ...manifest })`
 *   - reloadPlugin: uninstall + re-install round-trip
 *   - reloadPlugin against a not-installed id is a clean install
 *   - **isolation property**: plugin source can't reach `window`
 *     or other host-realm globals unless endowed
 */

// Slice 477 H2.6 — loader code-splits SES; tests stay synchronous
// by importing SES at the top.
import 'ses';
// Same reason, for the module-mode path: PluginLoader lazily `await import(
// '@endo/module-source')` (PluginLoader.ts:354). Resolving that graph cold costs far more than the
// 5s per-test budget under parallel load, and the cost would land inside whichever module-mode case
// runs first. Importing it here moves it to file-collection time, which has no per-test timeout.
import '@endo/module-source';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  loadPluginFromUrl,
  PluginLoadError,
  reloadPlugin,
  type SourceFetcher,
} from './PluginLoader.js';
import type { TrustChannel } from './TrustChannel.js';
import { PluginRegistry } from './PluginRegistry.js';
import { PLUGIN_CONTRACT_VERSION } from './plugin-types.js';

/**
 * Build a plugin source string that evaluates to a manifest object.
 * The returned string is what a real plugin file would contain
 * (sans imports).
 */
function manifestSource(overrides: {
  id?: string;
  version?: string;
  displayName?: string;
  contractVersion?: string;
  tagNamespace?: string;
} = {}): string {
  const id = overrides.id ?? 'acme.test';
  const version = overrides.version ?? '0.1.0';
  const displayName = overrides.displayName ?? 'Acme Test Plugin';
  const contractVersion =
    overrides.contractVersion ?? PLUGIN_CONTRACT_VERSION;
  const tagNamespace = overrides.tagNamespace ?? id;
  return `
    ({
      id: ${JSON.stringify(id)},
      version: ${JSON.stringify(version)},
      displayName: ${JSON.stringify(displayName)},
      contractVersion: ${JSON.stringify(contractVersion)},
      tagNamespace: ${JSON.stringify(tagNamespace)},
      capabilities: {},
      register: () => {},
      unregister: () => {},
    })
  `;
}

/** Build a factory-shape plugin source. */
function factorySource(opts: { id?: string; closesOver?: string } = {}): string {
  const id = opts.id ?? 'factory.test';
  return `
    (({ console: c }) => {
      ${opts.closesOver ?? ''}
      return {
        id: ${JSON.stringify(id)},
        version: '1.0.0',
        displayName: 'Factory Test',
        contractVersion: '${PLUGIN_CONTRACT_VERSION}',
        tagNamespace: ${JSON.stringify(id)},
        capabilities: {},
        register: () => {},
      };
    })
  `;
}

function fetcherOf(source: string): SourceFetcher {
  return async () => source;
}

/** Build an ES-module plugin source (top-level import/export → module-mode, §4.2). */
function esmModuleSource(id = 'esm.alpha', bareImport = '@kernel/data'): string {
  return `import { data } from ${JSON.stringify(bareImport)};
export default {
  id: ${JSON.stringify(id)},
  version: '1.0.0',
  displayName: 'ESM Test',
  contractVersion: '${PLUGIN_CONTRACT_VERSION}',
  tagNamespace: ${JSON.stringify(id)},
  capabilities: {},
  register: () => ({ kernelDataFetch: typeof data.fetch }),
};`;
}

const ESM_HOST_DEPS = { apiBase: '', registerSurfacePort: () => {} };

describe('PluginLoader (slice 465 + 477 H2.1)', () => {
  let registry: PluginRegistry;

  beforeEach(() => {
    registry = new PluginRegistry();
  });

  afterEach(() => {
    // No-op; each test's registry is fresh.
  });

  it('loadPluginFromUrl evaluates source and installs the manifest', async () => {
    const installed = await loadPluginFromUrl(registry, 'plugin://test', {
      sourceFetcher: fetcherOf(manifestSource()),
    });
    expect(installed.id).toBe('acme.test');
    expect(registry.has('acme.test')).toBe(true);
  });

  it('factory-shape source: source is a function, called with endowments', async () => {
    const installed = await loadPluginFromUrl(registry, 'plugin://factory', {
      sourceFetcher: fetcherOf(factorySource({ id: 'factory.alpha' })),
    });
    expect(installed.id).toBe('factory.alpha');
    expect(registry.has('factory.alpha')).toBe(true);
  });

  it('module-mode: an ES-import plugin resolves @kernel/data and installs', async () => {
    // A successful install proves `import { data } from '@kernel/data'` resolved through the kernel
    // module map (an unresolved import would throw at module load → PluginLoadError(evaluate)).
    const installed = await loadPluginFromUrl(registry, 'plugin://esm', {
      sourceFetcher: fetcherOf(esmModuleSource('esm.alpha')),
      hostDeps: ESM_HOST_DEPS,
      expectedPluginId: 'esm.alpha',
    });
    expect(installed.id).toBe('esm.alpha');
  });

  it('module-mode: a non-@kernel bare import is denied — the boundary IS the import paths', async () => {
    // The importHook throws for any specifier other than @kernel/* + the entry; the loader wraps that
    // as a failed module-mode import. Either way the load is rejected (the denial fired).
    let caught: unknown;
    await loadPluginFromUrl(registry, 'plugin://esmbad', {
      sourceFetcher: fetcherOf(esmModuleSource('esm.bad', 'node:fs')),
      hostDeps: ESM_HOST_DEPS,
      expectedPluginId: 'esm.bad',
    }).catch((e) => {
      caught = e;
    });
    expect(caught).toBeInstanceOf(PluginLoadError);
    expect((caught as PluginLoadError).stage).toBe('evaluate');
    expect(String((caught as PluginLoadError).cause)).toMatch(/kernel boundary|may not import/);
  });

  it('PluginLoadError(stage=fetch) when sourceFetcher throws', async () => {
    const fetcher: SourceFetcher = async () => {
      throw new Error('network down');
    };
    await expect(
      loadPluginFromUrl(registry, 'plugin://x', { sourceFetcher: fetcher }),
    ).rejects.toMatchObject({
      name: 'PluginLoadError',
      stage: 'fetch',
      url: 'plugin://x',
    });
  });

  it('PluginLoadError(stage=evaluate) when source has syntax errors', async () => {
    await expect(
      loadPluginFromUrl(registry, 'plugin://broken', {
        sourceFetcher: fetcherOf('(this is not valid javascript)'),
      }),
    ).rejects.toMatchObject({
      name: 'PluginLoadError',
      stage: 'evaluate',
    });
  });

  it('PluginLoadError(stage=evaluate) when factory throws', async () => {
    await expect(
      loadPluginFromUrl(registry, 'plugin://throwing-factory', {
        sourceFetcher: fetcherOf(
          '(() => { throw new Error("plugin init failed"); })',
        ),
      }),
    ).rejects.toMatchObject({
      name: 'PluginLoadError',
      stage: 'evaluate',
    });
  });

  it('PluginLoadError(stage=shape) on missing id field', async () => {
    await expect(
      loadPluginFromUrl(registry, 'plugin://noid', {
        sourceFetcher: fetcherOf(
          `({ version: '1.0.0', register: () => {}, contractVersion: '1.1', tagNamespace: 'x' })`,
        ),
      }),
    ).rejects.toMatchObject({
      name: 'PluginLoadError',
      stage: 'shape',
    });
  });

  it('PluginLoadError(stage=shape) on missing register field', async () => {
    await expect(
      loadPluginFromUrl(registry, 'plugin://noreg', {
        sourceFetcher: fetcherOf(
          `({ id: 'x', version: '1', contractVersion: '1.1', tagNamespace: 'x' })`,
        ),
      }),
    ).rejects.toMatchObject({
      name: 'PluginLoadError',
      stage: 'shape',
    });
  });

  it('PluginLoadError(stage=install) when registry rejects (duplicate id)', async () => {
    await loadPluginFromUrl(registry, 'plugin://first', {
      sourceFetcher: fetcherOf(manifestSource({ id: 'dup.test' })),
    });
    await expect(
      loadPluginFromUrl(registry, 'plugin://second', {
        sourceFetcher: fetcherOf(manifestSource({ id: 'dup.test' })),
      }),
    ).rejects.toMatchObject({
      name: 'PluginLoadError',
      stage: 'install',
    });
  });

  it('isolation: plugin source cannot reach host `window`', async () => {
    // The plugin source reads `typeof window`. Inside a Compartment
    // without `window` endowed, this evaluates to 'undefined'.
    // We capture the result via a known-safe channel: the plugin
    // sets manifest.displayName to the captured value.
    const source = `
      (() => ({
        id: 'iso.test',
        version: '1.0.0',
        displayName: typeof window,
        contractVersion: '1.1',
        tagNamespace: 'iso.test',
        capabilities: {},
        register: () => {},
      }))
    `;
    const installed = await loadPluginFromUrl(registry, 'plugin://iso', {
      sourceFetcher: fetcherOf(source),
    });
    // 'undefined' = host's window not visible inside the compartment.
    expect(installed.displayName).toBe('undefined');
  });

  it('isolation: an UNTRUSTED plugin source does NOT see a `document` endowment (560 Fix C)', async () => {
    // Tempdoc 560 Fix C: the UNTRUSTED bundle omits `document` entirely (no window to climb to via
    // defaultView / body.ownerDocument.defaultView). The default trust verdict is UNTRUSTED, so a
    // plugin loaded without a trust channel sees `typeof document === 'undefined'`. This is exactly
    // the "future tighter default" this test's prior form anticipated (it previously pinned the
    // V1.5-alpha compat behavior where document was endowed).
    const source = `
      (() => ({
        id: 'doc.test',
        version: '1.0.0',
        displayName: typeof document,
        contractVersion: '1.1',
        tagNamespace: 'doc.test',
        capabilities: {},
        register: () => {},
      }))
    `;
    const installed = await loadPluginFromUrl(registry, 'plugin://doc', {
      sourceFetcher: fetcherOf(source),
    });
    expect(installed.displayName).toBe('undefined');
  });

  it('endowmentsExtension can splice in additional values', async () => {
    const source = `
      (({ extra }) => ({
        id: 'ext.test',
        version: '1.0.0',
        displayName: extra.greeting,
        contractVersion: '1.1',
        tagNamespace: 'ext.test',
        capabilities: {},
        register: () => {},
      }))
    `;
    const installed = await loadPluginFromUrl(registry, 'plugin://ext', {
      sourceFetcher: fetcherOf(source),
      endowmentsExtension: { extra: { greeting: 'hello' } },
    });
    expect(installed.displayName).toBe('hello');
  });
});

describe('PluginLoader.PluginLoadError', () => {
  it('preserves cause through fetch failure', async () => {
    const cause = new TypeError('connection refused');
    const fetcher: SourceFetcher = async () => {
      throw cause;
    };
    try {
      await loadPluginFromUrl(new PluginRegistry(), 'plugin://x', {
        sourceFetcher: fetcher,
      });
      throw new Error('should have thrown');
    } catch (err) {
      expect(err).toBeInstanceOf(PluginLoadError);
      expect((err as PluginLoadError).cause).toBe(cause);
    }
  });
});

describe('PluginLoader — H2.4 tier attenuation', () => {
  let registry: PluginRegistry;

  beforeEach(() => {
    registry = new PluginRegistry();
    // Clean up any leaked plugin localStorage keys between tests.
    if (typeof localStorage !== 'undefined') {
      const toRemove: string[] = [];
      for (let i = 0; i < localStorage.length; i++) {
        const k = localStorage.key(i);
        if (k && k.startsWith('plugin:')) toRemove.push(k);
      }
      for (const k of toRemove) localStorage.removeItem(k);
    }
  });

  it('UNTRUSTED tier: plugin localStorage writes are namespace-scoped', async () => {
    const source = `
      (({ localStorage }) => {
        localStorage.setItem('greeting', 'hello-from-plugin');
        return {
          id: 'untrusted.test',
          version: '1.0.0',
          displayName: 'Untrusted',
          contractVersion: '1.1',
          tagNamespace: 'untrusted.test',
          capabilities: {},
          register: () => {},
        };
      })
    `;
    await loadPluginFromUrl(registry, 'plugin://u', {
      sourceFetcher: fetcherOf(source),
      tier: 'UNTRUSTED_PLUGIN',
      expectedPluginId: 'untrusted.test',
    });
    // Plugin saw its own scoped storage but real localStorage has
    // the prefixed key.
    expect(localStorage.getItem('greeting')).toBeNull();
    expect(localStorage.getItem('plugin:untrusted.test:greeting')).toBe(
      'hello-from-plugin',
    );
  });

  it('TRUSTED tier (via TrustChannel): plugin localStorage writes are unscoped (raw access)', async () => {
    const source = `
      (({ localStorage }) => {
        localStorage.setItem('trusted-key', 'raw-value');
        return {
          id: 'trusted.test',
          version: '1.0.0',
          displayName: 'Trusted',
          contractVersion: '1.1',
          tagNamespace: 'trusted.test',
          capabilities: {},
          register: () => {},
        };
      })
    `;
    // 477 H2.3 — TrustChannel is the mint-site for trust verdicts.
    // Explicit `tier: TRUSTED_PLUGIN` no longer overrides; the
    // channel must produce the verdict. Tests use a mock channel.
    const mockTrusted: TrustChannel = {
      verify: async () => ({
        tier: 'TRUSTED_PLUGIN' as const,
        explanation: 'mock trust channel: caller is test',
        identity: 'test',
      }),
    };
    await loadPluginFromUrl(registry, 'plugin://t', {
      sourceFetcher: fetcherOf(source),
      trustChannel: mockTrusted,
      expectedPluginId: 'trusted.test',
    });
    // Trusted: raw key written, no prefix.
    expect(localStorage.getItem('trusted-key')).toBe('raw-value');
    localStorage.removeItem('trusted-key');
  });

  it('UNTRUSTED tier: customElements.define rejects out-of-namespace tags', async () => {
    const source = `
      (({ customElements, HTMLElement }) => {
        try {
          customElements.define('not-the-namespace', class extends HTMLElement {});
          return { id: 'badns', version: '1.0.0', contractVersion: '1.1', tagNamespace: 'badns', displayName: 'Bad', capabilities: {}, register: () => {}, didDefine: true };
        } catch (e) {
          return { id: 'badns', version: '1.0.0', contractVersion: '1.1', tagNamespace: 'badns', displayName: e.message, capabilities: {}, register: () => {} };
        }
      })
    `;
    const m = await loadPluginFromUrl(registry, 'plugin://bad', {
      sourceFetcher: fetcherOf(source),
      tier: 'UNTRUSTED_PLUGIN',
      expectedPluginId: 'badns',
    });
    // The proxy should have thrown when 'not-the-namespace' was
    // attempted; manifest.displayName captured the full error.
    expect(m.displayName).toContain('badns');
    expect(m.displayName.toLowerCase()).toContain('namespace');
    expect(m.displayName).toContain('not-the-namespace');
  });

  it('UNTRUSTED tier: rejects when manifest.id mismatches expectedPluginId', async () => {
    await expect(
      loadPluginFromUrl(registry, 'plugin://m', {
        sourceFetcher: fetcherOf(manifestSource({ id: 'actual.id' })),
        tier: 'UNTRUSTED_PLUGIN',
        expectedPluginId: 'wrong.id',
      }),
    ).rejects.toMatchObject({
      name: 'PluginLoadError',
      stage: 'shape',
    });
  });

  it('default tier is UNTRUSTED_PLUGIN (least authority)', async () => {
    // Without `tier` passed, attenuation should still apply.
    const source = `
      (({ localStorage }) => {
        localStorage.setItem('default-tier-key', 'value');
        return {
          id: 'default.test',
          version: '1.0.0',
          displayName: 'Default',
          contractVersion: '1.1',
          tagNamespace: 'default.test',
          capabilities: {},
          register: () => {},
        };
      })
    `;
    await loadPluginFromUrl(registry, 'plugin://d', {
      sourceFetcher: fetcherOf(source),
      expectedPluginId: 'default.test',
      // tier omitted — defaults to UNTRUSTED_PLUGIN
    });
    expect(localStorage.getItem('default-tier-key')).toBeNull();
    expect(localStorage.getItem('plugin:default.test:default-tier-key')).toBe(
      'value',
    );
  });
});

describe('reloadPlugin (slice 469)', () => {
  let registry: PluginRegistry;

  beforeEach(() => {
    registry = new PluginRegistry();
  });

  it('reloads an existing plugin (uninstall + reinstall)', async () => {
    await loadPluginFromUrl(registry, 'plugin://v1', {
      sourceFetcher: fetcherOf(manifestSource({ id: 'reload.test' })),
    });
    expect(registry.get('reload.test')?.manifest.version).toBe('0.1.0');

    await reloadPlugin(registry, 'reload.test', 'plugin://v2', {
      sourceFetcher: fetcherOf(
        manifestSource({ id: 'reload.test', version: '0.2.0' }),
      ),
    });
    expect(registry.get('reload.test')?.manifest.version).toBe('0.2.0');
  });

  it('reloadPlugin on not-installed id = clean install', async () => {
    expect(registry.has('fresh.test')).toBe(false);
    await reloadPlugin(registry, 'fresh.test', 'plugin://fresh', {
      sourceFetcher: fetcherOf(manifestSource({ id: 'fresh.test' })),
    });
    expect(registry.has('fresh.test')).toBe(true);
  });
});
