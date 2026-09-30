/**
 * Slice 448 phase 5 — DiagnosticChannelCatalogClient tests.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  DiagnosticChannel,
  DiagnosticChannelCatalog,
} from '../types/diagnostic';
import {
  __resetForTest,
  __seedForTest,
  bootDiagnosticChannelRegistry,
  getDiagnosticChannel,
  listDiagnosticChannels,
  onDiagnosticChannelCatalogChange,
} from './DiagnosticChannelCatalogClient';

function engineLogEntry(id: string = 'core.engine-log'): DiagnosticChannel {
  return {
    id,
    presentation: {
      labelKey: 'registry-diagnostic.engine-log.label',
      descriptionKey: 'registry-diagnostic.engine-log.description',
      iconHint: null,
      category: null,
    },
    dataClasses: ['USER_PATHS', 'CONFIG_VALUES', 'EXCEPTION_BODIES'],
    producer: 'IN_PROCESS_LOGBACK',
    deliveryMode: 'SSE_STREAM',
    selector: {
      prefixMappings: { 'io.justsearch.': 'CORE_DIAGNOSTIC' },
      overrides: {},
      defaultSubCategory: 'LIBRARY_TRACE',
    },
    endpoint: '/api/diagnostic-channels/engine-log/stream',
    consumerPermission: 'OPERATOR_OVERRIDE',
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
    consumers: [],
  };
}

function catalogOf(...entries: DiagnosticChannel[]): DiagnosticChannelCatalog {
  return {
    schemaVersion: '1.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'DiagnosticChannel',
    entries,
  };
}

// The RAW WIRE shape served by RegistryController — what the boot-fetch path parses through the
// generated `diagnosticChannelWireSchema` (tempdoc 560 §4c). It carries the discriminator
// `type:"diagnostic-channel"` and the present-as-null `provenance.identity` that the precise wire
// requires; the FE `DiagnosticChannel` (engineLogEntry) omits both. A mock body lacking them would log
// a spurious `[WireContract]` drift, so the fetch fixtures use this wire-faithful builder.
function engineLogWireEntry(id: string = 'core.engine-log'): unknown {
  const fe = engineLogEntry(id);
  return {
    ...fe,
    type: 'diagnostic-channel',
    provenance: { ...fe.provenance, identity: null },
  };
}

function wireCatalogOf(...entries: unknown[]): unknown {
  return {
    schemaVersion: '1.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'DiagnosticChannel',
    entries,
  };
}

describe('DiagnosticChannelCatalogClient', () => {
  beforeEach(() => {
    __resetForTest();
  });
  afterEach(() => {
    __resetForTest();
  });

  describe('lookup API', () => {
    it('returns undefined for unknown id before boot', () => {
      expect(getDiagnosticChannel('core.unknown')).toBeUndefined();
    });

    it('returns the seeded entry by id', () => {
      __seedForTest(catalogOf(engineLogEntry()));
      const c = getDiagnosticChannel('core.engine-log');
      expect(c?.producer).toBe('IN_PROCESS_LOGBACK');
      expect(c?.consumerPermission).toBe('OPERATOR_OVERRIDE');
      expect(c?.deliveryMode).toBe('SSE_STREAM');
    });

    it('listDiagnosticChannels returns all entries', () => {
      __seedForTest(
        catalogOf(engineLogEntry('core.engine-log'), engineLogEntry('vendor.acme.audit-log')),
      );
      expect(
        listDiagnosticChannels()
          .map((c) => c.id)
          .sort(),
      ).toEqual(['core.engine-log', 'vendor.acme.audit-log']);
    });
  });

  describe('boot fetch', () => {
    it('populates the catalog on 200', async () => {
      const catalog = wireCatalogOf(engineLogWireEntry());
      const fetchImpl = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        headers: { get: () => '"etag-1"' },
        json: () => Promise.resolve(catalog),
      } as unknown as Response);
      await bootDiagnosticChannelRegistry('http://127.0.0.1:33221', fetchImpl);
      expect(getDiagnosticChannel('core.engine-log')).toBeDefined();
    });

    it('no-ops on 304 (cached body retained)', async () => {
      __seedForTest(catalogOf(engineLogEntry()));
      const fetchImpl = vi.fn().mockResolvedValue({
        ok: false,
        status: 304,
        headers: { get: () => null },
        json: () => Promise.resolve({}),
      } as unknown as Response);
      __resetForTest();
      __seedForTest(catalogOf(engineLogEntry()));
      await bootDiagnosticChannelRegistry('http://127.0.0.1:33221', fetchImpl);
      expect(getDiagnosticChannel('core.engine-log')).toBeDefined();
    });

    it('swallows fetch errors and retains cached entries', async () => {
      __seedForTest(catalogOf(engineLogEntry()));
      const fetchImpl = vi.fn().mockRejectedValue(new Error('network down'));
      // Re-seed after reset to simulate cached-from-previous-session state.
      __resetForTest();
      __seedForTest(catalogOf(engineLogEntry()));
      await expect(
        bootDiagnosticChannelRegistry('http://127.0.0.1:33221', fetchImpl),
      ).resolves.toBeUndefined();
      expect(getDiagnosticChannel('core.engine-log')).toBeDefined();
    });
  });

  describe('catalog change listener', () => {
    it('fires on a successful boot', async () => {
      const listener = vi.fn();
      onDiagnosticChannelCatalogChange(listener);
      const fetchImpl = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        headers: { get: () => null },
        json: () => Promise.resolve(wireCatalogOf(engineLogWireEntry())),
      } as unknown as Response);
      await bootDiagnosticChannelRegistry('http://127.0.0.1:33221', fetchImpl);
      expect(listener).toHaveBeenCalledOnce();
    });

    it('unsubscribe stops further notifications', () => {
      const listener = vi.fn();
      const off = onDiagnosticChannelCatalogChange(listener);
      __seedForTest(catalogOf(engineLogEntry()));
      expect(listener).toHaveBeenCalledOnce();
      off();
      __seedForTest(catalogOf(engineLogEntry('vendor.acme.audit-log')));
      // Listener still has 1 call after unsubscribe; the second seed should
      // not re-trigger it.
      expect(listener).toHaveBeenCalledOnce();
    });
  });
});
