// @vitest-environment happy-dom

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

describe('resourceCatalog', () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
    vi.resetModules();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.clearAllMocks();
    if (typeof localStorage !== 'undefined') {
      localStorage.removeItem('justsearch.resourceCatalog.en.body');
      localStorage.removeItem('justsearch.resourceCatalog.en.etag');
    }
  });

  describe('localizeResourceKey', () => {
    it('returns the localized string when the key is in the catalog', async () => {
      const mod = await import('./resourceCatalog');
      mod.__seedForTest({
        'registry-resource.metric.job-queue-depth.label': 'Job queue depth',
      });
      expect(mod.localizeResourceKey('registry-resource.metric.job-queue-depth.label'))
        .toBe('Job queue depth');
    });

    it('returns the raw key as fallback when key is missing', async () => {
      const mod = await import('./resourceCatalog');
      mod.__seedForTest({});
      expect(mod.localizeResourceKey('registry-resource.metric.unknown.label'))
        .toBe('registry-resource.metric.unknown.label');
    });

    it('returns the raw key when the catalog is empty (boot not yet attempted)', async () => {
      const mod = await import('./resourceCatalog');
      mod.__resetForTest();
      expect(mod.localizeResourceKey('any.key')).toBe('any.key');
    });
  });

  describe('bootResourceCatalog', () => {
    it('populates catalog on a 200 response with messages map', async () => {
      const mockResponse = {
        ok: true,
        status: 200,
        json: () => Promise.resolve({
          schemaVersion: '1.0',
          locale: 'en',
          namespace: 'registry-resource',
          messages: {
            'registry-resource.metric.gpu-utilization.label': 'GPU utilization',
          },
        }),
        headers: { get: (name: string) => name === 'ETag' ? '"abc123"' : null },
      };
      globalThis.fetch = vi.fn().mockResolvedValue(mockResponse);

      const mod = await import('./resourceCatalog');
      mod.__resetForTest();
      await mod.bootResourceCatalog('http://localhost:33221');

      expect(globalThis.fetch).toHaveBeenCalledWith(
        'http://localhost:33221/api/messages/registry-resource/en',
        expect.objectContaining({ headers: expect.any(Object) }),
      );
      expect(mod.localizeResourceKey('registry-resource.metric.gpu-utilization.label'))
        .toBe('GPU utilization');
    });

    it('sends If-None-Match header when localStorage has a cached etag', async () => {
      localStorage.setItem(
        'justsearch.resourceCatalog.en.body',
        JSON.stringify({ 'registry-resource.metric.test.label': 'Cached value' }),
      );
      localStorage.setItem('justsearch.resourceCatalog.en.etag', '"cached-etag"');

      let capturedHeaders: Record<string, string> | undefined;
      globalThis.fetch = vi.fn().mockImplementation((_url: string, init: RequestInit) => {
        capturedHeaders = init?.headers as Record<string, string>;
        return Promise.resolve({
          ok: false,
          status: 304,
          json: () => Promise.resolve({}),
          headers: { get: () => null },
        });
      });

      const mod = await import('./resourceCatalog');
      mod.__resetForTest();
      // Re-seed storage after reset wiped it.
      localStorage.setItem(
        'justsearch.resourceCatalog.en.body',
        JSON.stringify({ 'registry-resource.metric.test.label': 'Cached value' }),
      );
      localStorage.setItem('justsearch.resourceCatalog.en.etag', '"cached-etag"');

      await mod.bootResourceCatalog('http://localhost:33221');

      expect(capturedHeaders?.['If-None-Match']).toBe('"cached-etag"');
      // 304 retained the seeded body — lookup resolves the cached value.
      expect(mod.localizeResourceKey('registry-resource.metric.test.label'))
        .toBe('Cached value');
    });

    it('falls back to raw-key passthrough when the fetch fails', async () => {
      globalThis.fetch = vi.fn().mockRejectedValue(new Error('network down'));

      const mod = await import('./resourceCatalog');
      mod.__resetForTest();
      await mod.bootResourceCatalog('http://localhost:33221');

      expect(mod.localizeResourceKey('any.key')).toBe('any.key');
    });

    it('skips the network round-trip when baseUrl is empty', async () => {
      globalThis.fetch = vi.fn();

      const mod = await import('./resourceCatalog');
      mod.__resetForTest();
      await mod.bootResourceCatalog('');

      expect(globalThis.fetch).not.toHaveBeenCalled();
    });

    it('uses a distinct localStorage key from errorCatalog', async () => {
      // Cache pollution guard: errorCatalog uses justsearch.errorCatalog.*;
      // resourceCatalog must use justsearch.resourceCatalog.* so the two
      // catalogs do not overwrite each other's persisted bodies.
      const mockResponse = {
        ok: true,
        status: 200,
        json: () => Promise.resolve({
          messages: { 'registry-resource.metric.foo.label': 'Foo' },
        }),
        headers: { get: (name: string) => name === 'ETag' ? '"resource-etag"' : null },
      };
      globalThis.fetch = vi.fn().mockResolvedValue(mockResponse);

      const mod = await import('./resourceCatalog');
      mod.__resetForTest();
      await mod.bootResourceCatalog('http://localhost:33221');

      expect(localStorage.getItem('justsearch.resourceCatalog.en.etag')).toBe('"resource-etag"');
      // Verify it did NOT overwrite the errorCatalog key.
      expect(localStorage.getItem('justsearch.errorCatalog.en.etag')).toBeNull();
    });
  });

  describe('bootWorkflowCatalog (tempdoc 565 §27.4)', () => {
    it('merges the registry-workflow catalog so workflow labelKeys resolve', async () => {
      const mockResponse = {
        ok: true,
        status: 200,
        json: () => Promise.resolve({
          namespace: 'registry-workflow',
          messages: { 'registry-workflow.research-brief.label': 'Research brief' },
        }),
        headers: { get: () => null },
      };
      globalThis.fetch = vi.fn().mockResolvedValue(mockResponse);

      const mod = await import('./resourceCatalog');
      mod.__resetForTest();
      await mod.bootWorkflowCatalog('http://localhost:33221');

      // 941 F3: the boot forces an end-to-end revalidation so a webview HTTP-cache entry that
      // outlived an over-install cannot answer with the previous version's catalog body.
      expect(globalThis.fetch).toHaveBeenCalledWith(
        'http://localhost:33221/api/messages/registry-workflow/en',
        { cache: 'no-cache' },
      );
      expect(mod.localizeResourceKey('registry-workflow.research-brief.label'))
        .toBe('Research brief');
    });

    it('falls back to raw-key passthrough only after the retry budget is exhausted', async () => {
      globalThis.fetch = vi.fn().mockRejectedValue(new Error('network down'));
      const mod = await import('./resourceCatalog');
      mod.__resetForTest();
      vi.useFakeTimers();
      try {
        const boot = mod.bootWorkflowCatalog('http://localhost:33221');
        await vi.advanceTimersByTimeAsync(60_000);
        await boot;
      } finally {
        vi.useRealTimers();
      }
      // 941 F3: one lost race must not abandon the namespace — the boot retried before giving up.
      expect((globalThis.fetch as unknown as { mock: { calls: unknown[] } }).mock.calls.length)
        .toBeGreaterThan(1);
      // Mirrors present.ts: an unresolved labelKey => humanizeId fallback upstream.
      expect(mod.localizeResourceKey('registry-workflow.demo-compose.label'))
        .toBe('registry-workflow.demo-compose.label');
    });

    it('skips the network round-trip when baseUrl is empty', async () => {
      globalThis.fetch = vi.fn();
      const mod = await import('./resourceCatalog');
      mod.__resetForTest();
      await mod.bootWorkflowCatalog('');
      expect(globalThis.fetch).not.toHaveBeenCalled();
    });

    // Tempdoc 565 §28 — the cold-boot clobber regression. bootResourceCatalog used to REPLACE
    // coreCatalog (`coreCatalog = body.messages`) while every sibling boot merges; co-scheduled in
    // i18n.ts's Promise.all with no ordering, a resource 200 resolving AFTER the workflow boot wiped
    // the just-merged workflow labels → the picker fell back to humanizeId on first launch. The fix
    // makes bootResourceCatalog merge too; this test pins that workflow keys survive a following
    // resource boot (it FAILS under the old replace semantics).
    it('a following bootResourceCatalog (200) does NOT clobber already-merged workflow labels', async () => {
      globalThis.fetch = vi.fn((url: string) => {
        const messages = url.includes('registry-workflow')
          ? { 'registry-workflow.demo-compose.label': 'Compose demo' }
          : { 'registry-resource.health-events.label': 'Health events' };
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({ messages }),
          headers: { get: () => null },
        });
      }) as unknown as typeof fetch;

      const mod = await import('./resourceCatalog');
      mod.__resetForTest();
      // Workflow boot merges first; then the resource boot lands (the clobbering ordering).
      await mod.bootWorkflowCatalog('http://localhost:33221');
      await mod.bootResourceCatalog('http://localhost:33221');

      // Both must resolve — the resource boot must MERGE, not replace.
      expect(mod.localizeResourceKey('registry-workflow.demo-compose.label')).toBe('Compose demo');
      expect(mod.localizeResourceKey('registry-resource.health-events.label')).toBe('Health events');
    });
  });

  /**
   * 478 §4.F — owner-scoped catalog tests. Verify the structural
   * properties: cross-plugin isolation, core-wins-over-plugin
   * lookup, O(1) namespace delete on uninstall.
   */
  describe('owner-scoped plugin catalogs (478 §4.F)', () => {
    beforeEach(async () => {
      const mod = await import('./resourceCatalog');
      mod.__resetForTest();
    });

    afterEach(async () => {
      const mod = await import('./resourceCatalog');
      mod.__resetForTest();
    });

    it('plugin scope keys are isolated per plugin id', async () => {
      const mod = await import('./resourceCatalog');
      mod.registerPluginCatalog('alpha', { 'shared.key': 'alpha-value' });
      mod.registerPluginCatalog('bravo', { 'shared.key': 'bravo-value' });
      // Lookup hits the FIRST registered scope (Map insertion order).
      // Cross-plugin shared keys are an anti-pattern; the test pins
      // current behavior so future changes are intentional.
      expect(mod.localizeResourceKey('shared.key')).toBe('alpha-value');
    });

    it('core (server-fetched) keys win over plugin keys', async () => {
      const mod = await import('./resourceCatalog');
      mod.__seedForTest({ 'core.key': 'authoritative-value' });
      mod.registerPluginCatalog('plugin', {
        'core.key': 'plugin-attempted-override',
      });
      expect(mod.localizeResourceKey('core.key')).toBe('authoritative-value');
    });

    it('unregisterPluginCatalog removes the entire plugin scope in O(1)', async () => {
      const mod = await import('./resourceCatalog');
      mod.registerPluginCatalog('plugin-a', {
        'a.label': 'A label',
        'a.desc': 'A description',
        'a.help': 'A help',
      });
      expect(mod.localizeResourceKey('a.label')).toBe('A label');
      mod.unregisterPluginCatalog('plugin-a');
      // All keys gone with one call.
      expect(mod.localizeResourceKey('a.label')).toBe('a.label');
      expect(mod.localizeResourceKey('a.desc')).toBe('a.desc');
      expect(mod.localizeResourceKey('a.help')).toBe('a.help');
    });

    it('unregisterPluginCatalog on unknown id is a no-op', async () => {
      const mod = await import('./resourceCatalog');
      mod.registerPluginCatalog('alpha', { 'k': 'v' });
      mod.unregisterPluginCatalog('non-existent');
      expect(mod.localizeResourceKey('k')).toBe('v');
    });

    it('uninstalling one plugin does not remove another plugin\'s keys', async () => {
      const mod = await import('./resourceCatalog');
      mod.registerPluginCatalog('alpha', { 'alpha.key': 'A' });
      mod.registerPluginCatalog('bravo', { 'bravo.key': 'B' });
      mod.unregisterPluginCatalog('alpha');
      expect(mod.localizeResourceKey('alpha.key')).toBe('alpha.key');
      expect(mod.localizeResourceKey('bravo.key')).toBe('B');
    });

    it('legacy registerCatalogEntries still works via __legacy__ bucket', async () => {
      const mod = await import('./resourceCatalog');
      mod.registerCatalogEntries({ 'legacy.key': 'legacy-value' });
      expect(mod.localizeResourceKey('legacy.key')).toBe('legacy-value');
      // Removal via legacy API still works.
      mod.unregisterCatalogEntries(['legacy.key']);
      expect(mod.localizeResourceKey('legacy.key')).toBe('legacy.key');
    });
  });

  // Tempdoc 855 fix-round F2 (S2) — the subscription API a mounted consumer (SettingsNav,
  // SettingsSurface) uses to re-render once a late-arriving catalog fetch resolves a key that was
  // still rendering the raw fallback at mount time.
  describe('onCatalogUpdated (855 fix-round F2 S2)', () => {
    it('notifies a subscriber after __seedForTest merges new keys', async () => {
      const mod = await import('./resourceCatalog');
      mod.__resetForTest();
      const calls: number[] = [];
      const unsubscribe = mod.onCatalogUpdated(() => calls.push(1));
      mod.__seedForTest({ 'a.label': 'A' });
      expect(calls.length).toBe(1);
      unsubscribe();
    });

    it('unsubscribe stops further notifications', async () => {
      const mod = await import('./resourceCatalog');
      mod.__resetForTest();
      const calls: number[] = [];
      const unsubscribe = mod.onCatalogUpdated(() => calls.push(1));
      unsubscribe();
      mod.__seedForTest({ 'a.label': 'A' });
      expect(calls.length).toBe(0);
    });

    it('notifies after a bootMessageCatalog-family fetch merges (e.g. bootWorkflowCatalog)', async () => {
      const mod = await import('./resourceCatalog');
      mod.__resetForTest();
      globalThis.fetch = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({ messages: { 'registry-workflow.demo.label': 'Demo' } }),
      }) as unknown as typeof fetch;
      const calls: number[] = [];
      const unsubscribe = mod.onCatalogUpdated(() => calls.push(1));
      await mod.bootWorkflowCatalog('http://localhost:1234');
      expect(calls.length).toBe(1);
      expect(mod.localizeResourceKey('registry-workflow.demo.label')).toBe('Demo');
      unsubscribe();
    });

    it('a failed fetch does not notify', async () => {
      const mod = await import('./resourceCatalog');
      mod.__resetForTest();
      globalThis.fetch = vi.fn().mockResolvedValue({ ok: false, status: 500 }) as unknown as typeof fetch;
      const calls: number[] = [];
      const unsubscribe = mod.onCatalogUpdated(() => calls.push(1));
      vi.useFakeTimers();
      try {
        const boot = mod.bootWorkflowCatalog('http://localhost:1234');
        await vi.advanceTimersByTimeAsync(60_000);
        await boot;
      } finally {
        vi.useRealTimers();
      }
      expect(calls.length).toBe(0);
      unsubscribe();
    });
  });

  /**
   * Tempdoc 941 — a boot that never got an answer stays re-attemptable.
   *
   * The old guard was `bootAttempted && the catalog is non-empty`. Because the boot seeds from
   * localStorage BEFORE fetching, and because every sibling namespace merges into the SAME
   * `coreCatalog`, "non-empty" was true for a `registry-resource` fetch that had never landed —
   * so the guard closed the boot permanently on data from a previous session or a previous
   * VERSION of the app. Success is now recorded only where the backend actually answered.
   */
  describe('941 — re-attemptable boot', () => {
    it('re-fetches after a failed boot, and stops once the backend answers', async () => {
      const mod = await import('./resourceCatalog');
      mod.__resetForTest();
      const ok = {
        ok: true,
        status: 200,
        json: () => Promise.resolve({ messages: { 'registry-resource.late.label': 'Late' } }),
        headers: { get: () => null },
      };
      const fetchMock = vi
        .fn()
        .mockRejectedValueOnce(new Error('backend not up yet'))
        .mockResolvedValue(ok);
      globalThis.fetch = fetchMock as unknown as typeof fetch;

      await mod.bootResourceCatalog('http://localhost:33221');
      expect(mod.localizeResourceKey('registry-resource.late.label'))
        .toBe('registry-resource.late.label');

      // The backend came up: the SAME entry point re-attempts (this is what the shell's
      // backend-ready edge calls).
      await mod.bootResourceCatalog('http://localhost:33221');
      expect(fetchMock.mock.calls.length).toBe(2);
      expect(mod.localizeResourceKey('registry-resource.late.label')).toBe('Late');

      // …and a third call is a no-op now that the fetch has been answered.
      await mod.bootResourceCatalog('http://localhost:33221');
      expect(fetchMock.mock.calls.length).toBe(2);
    });

    it('a stale localStorage body no longer closes the boot (the one-shot trap)', async () => {
      const mod = await import('./resourceCatalog');
      mod.__resetForTest();
      // A previous session cached a body. The boot seeds it, then the fetch fails.
      localStorage.setItem(
        'justsearch.resourceCatalog.en.body',
        JSON.stringify({ 'registry-resource.stale.label': 'From the last session' }),
      );
      localStorage.setItem('justsearch.resourceCatalog.en.etag', '"stale-etag"');
      const fetchMock = vi.fn().mockRejectedValue(new Error('backend not up yet'));
      globalThis.fetch = fetchMock as unknown as typeof fetch;

      await mod.bootResourceCatalog('http://localhost:33221');
      // Pre-941 this left a NON-EMPTY catalog + a set guard = permanently closed.
      expect(mod.localizeResourceKey('registry-resource.stale.label'))
        .toBe('From the last session');

      await mod.bootResourceCatalog('http://localhost:33221');
      expect(fetchMock.mock.calls.length).toBe(2);
    });

    it('re-attempts a namespace catalog that exhausted its retry budget', async () => {
      const mod = await import('./resourceCatalog');
      mod.__resetForTest();
      globalThis.fetch = vi.fn().mockRejectedValue(new Error('down')) as unknown as typeof fetch;
      vi.useFakeTimers();
      try {
        const boot = mod.bootSurfaceCatalog('http://localhost:33221');
        await vi.advanceTimersByTimeAsync(60_000);
        await boot;
      } finally {
        vi.useRealTimers();
      }
      const afterGiveUp = (globalThis.fetch as unknown as { mock: { calls: unknown[] } })
        .mock.calls.length;
      expect(afterGiveUp).toBeGreaterThan(1);

      globalThis.fetch = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ messages: { 'settings.group.general': 'General' } }),
        headers: { get: () => null },
      }) as unknown as typeof fetch;
      await mod.bootSurfaceCatalog('http://localhost:33221');
      // The abandoned namespace resolves on the re-attempt — the `settings.group.general`
      // raw-key defect (941 round-18 F3) closes for a backend that came up LATE, not just slow.
      expect(mod.localizeResourceKey('settings.group.general')).toBe('General');
    });

    it('concurrent boots share one request', async () => {
      const mod = await import('./resourceCatalog');
      mod.__resetForTest();
      const fetchMock = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ messages: { 'registry-resource.a.label': 'A' } }),
        headers: { get: () => null },
      });
      globalThis.fetch = fetchMock as unknown as typeof fetch;
      await Promise.all([
        mod.bootResourceCatalog('http://localhost:33221'),
        mod.bootResourceCatalog('http://localhost:33221'),
        mod.bootResourceCatalog('http://localhost:33221'),
      ]);
      expect(fetchMock.mock.calls.length).toBe(1);
    });
  });
});
