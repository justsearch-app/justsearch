// @vitest-environment happy-dom
/**
 * Tempdoc 550 C1 (FE): ActionLedgerClient + unifiedActivity projection.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  ActionLedgerClient,
  unifiedActivity,
  gateFirings,
  openActionLedgerStream,
  startEffectIngest,
  projectBackend,
  type BackendLedgerEntry,
} from './ActionLedgerClient';
// tempdoc 612 §3 — seed the OperationCatalog so projectBackend can grade an operation row's significance.
import {
  __seedForTest as seedOperationCatalog,
  __resetForTest as resetOperationCatalog,
} from '../../api/registry/OperationCatalogClient';
import type { Operation, OperationCatalog } from '../../api/types/registry';
import {
  recordEffect,
  listJournal,
  journalEventId,
  markUndoableOperation,
  __resetJournalForTest,
  type JournalEntry,
} from '../substrates/effects/index';
import { CORE_PROVENANCE } from '../primitives/provenance';

function backendEntry(over: Partial<BackendLedgerEntry>): BackendLedgerEntry {
  return {
    id: 'operation:2026-05-26T00:00:00.000Z:core.test',
    kind: 'operation',
    occurredAt: '2026-05-26T00:00:00.000Z',
    originator: 'agent',
    ...over,
  };
}

beforeEach(() => {
  __resetJournalForTest();
});

describe('unifiedActivity (pure projection)', () => {
  it('merges backend + FE journal entries, sorted oldest-first', () => {
    const backend: BackendLedgerEntry[] = [
      backendEntry({ kind: 'operation', operationId: 'core.reindex', outcome: 'SUCCESS', occurredAt: '2026-05-26T00:00:02.000Z' }),
      backendEntry({ kind: 'navigation', targetSurface: 'core.library', originator: 'user', occurredAt: '2026-05-26T00:00:00.000Z' }),
    ];
    const journal: JournalEntry[] = [
      {
        id: 1,
        effect: { kind: 'navigate', to: '#x' },
        invokedBy: CORE_PROVENANCE,
        invokedAt: '2026-05-26T00:00:01.000Z',
        inverse: null,
        originator: 'agent',
      },
    ];

    const rows = unifiedActivity(backend, journal);

    expect(rows.map(r => r.occurredAt)).toEqual([
      '2026-05-26T00:00:00.000Z',
      '2026-05-26T00:00:01.000Z',
      '2026-05-26T00:00:02.000Z',
    ]);
    expect(rows[0]!).toMatchObject({ source: 'backend', kind: 'navigation', originator: 'user' });
    expect(rows[1]!).toMatchObject({ source: 'fe-effect', kind: 'navigate', originator: 'agent' });
    expect(rows[2]!).toMatchObject({ source: 'backend', kind: 'operation', originator: 'agent' });
    // §2.A / Q7: rows show human labels via the display projector, not raw ids.
    expect(rows[0]!.label).toContain('Library');
    expect(rows[0]!.label).not.toContain('core.library');
    expect(rows[2]!.label).toContain('Reindex');
    expect(rows[2]!.label).not.toContain('core.reindex');
    // tempdoc 558 Deepening 3 — the outcome is now a STRUCTURED field (the row renders it as a glyph +
    // tone), not concatenated into the label. Assert it where it now lives.
    expect(rows[2]!.outcome).toBe('SUCCESS');
    expect(rows[2]!.label).not.toContain('SUCCESS');
  });

  it('marks a vetoed (rejected) pending effect in the label', () => {
    const journal: JournalEntry[] = [
      {
        id: 1,
        effect: { kind: 'invoke-operation', operationId: 'core.danger', args: {} },
        invokedBy: CORE_PROVENANCE,
        invokedAt: '2026-05-26T00:00:00.000Z',
        inverse: null,
        originator: 'agent',
        pendingOutcome: 'rejected',
      },
    ];
    const rows = unifiedActivity([], journal);
    expect(rows[0]!.label).toContain('Danger');
    expect(rows[0]!.label).not.toContain('core.danger');
    expect(rows[0]!.label).toContain('rejected');
  });

  it('collapses an FE invoke-operation effect and its backend operation row into ONE record (G6)', () => {
    // The FE dispatched an invoke-operation effect; the backend ran it and returned exec-42,
    // which the Shell associated with the journal entry via markUndoableOperation. The backend
    // ledger row for that op carries the same executionId. unifiedActivity must show ONE row.
    const entry = recordEffect(
      { kind: 'invoke-operation', operationId: 'core.reindex', args: {} },
      CORE_PROVENANCE,
      { originator: 'agent' },
    );
    markUndoableOperation(entry.id, 'core.reindex', 'exec-42');

    const backend: BackendLedgerEntry[] = [
      backendEntry({
        kind: 'operation',
        operationId: 'core.reindex',
        outcome: 'SUCCESS',
        executionId: 'exec-42',
        originator: 'agent',
        occurredAt: '2026-05-26T00:00:05.000Z',
      }),
    ];

    const rows = unifiedActivity(backend, listJournal());
    // One logical record across the boundary — the FE effect row is collapsed into the backend row.
    expect(rows).toHaveLength(1);
    expect(rows[0]!).toMatchObject({ source: 'backend', kind: 'operation' });
    expect(rows[0]!.label).toContain('Reindex');
  });

  // Tempdoc 577 §2.9 V6 — Effect→Effect collapse: an FE effect INGESTED into the backend log
  // (startEffectIngest posts it under `fe-effect:<journalId>`) must not also render from the
  // local journal — the live-audit Timeline showed every navigation twice at identical timestamps.
  it('collapses an ingested FE effect and its backend kind=effect row into ONE record', () => {
    const entry = recordEffect(
      { kind: 'navigate', to: 'justsearch://surface/core.unified-chat-surface' },
      CORE_PROVENANCE,
      { originator: 'user' },
    );
    const backend: BackendLedgerEntry[] = [
      backendEntry({
        id: journalEventId(entry),
        kind: 'effect',
        effectKind: 'navigate',
        subject: 'justsearch://surface/core.unified-chat-surface',
        originator: 'user',
      }),
    ];
    const rows = unifiedActivity(backend, listJournal());
    expect(rows).toHaveLength(1);
    expect(rows[0]!).toMatchObject({ source: 'backend', kind: 'effect' });
  });

  it('keeps the FE effect row when the backend has not (yet) ingested it', () => {
    recordEffect(
      { kind: 'navigate', to: 'justsearch://surface/core.search-surface' },
      CORE_PROVENANCE,
      { originator: 'user' },
    );
    const rows = unifiedActivity([], listJournal());
    expect(rows).toHaveLength(1);
    expect(rows[0]!).toMatchObject({ source: 'fe-effect', kind: 'navigate' });
  });

  it('keeps the FE effect row when no backend row shares its executionId', () => {
    const entry = recordEffect(
      { kind: 'invoke-operation', operationId: 'core.reindex', args: {} },
      CORE_PROVENANCE,
      { originator: 'agent' },
    );
    markUndoableOperation(entry.id, 'core.reindex', 'exec-99');
    // Backend row for a DIFFERENT execution — no collapse.
    const backend: BackendLedgerEntry[] = [
      backendEntry({ kind: 'operation', operationId: 'core.reindex', executionId: 'exec-other' }),
    ];
    const rows = unifiedActivity(backend, listJournal());
    expect(rows).toHaveLength(2);
  });

  it('collapse is driven by the injected executionIdOf resolver (explicit correlation seam, F2)', () => {
    // unifiedActivity is a pure function of its args: the executionId correlation source is the
    // third parameter, not a hidden global read. Injecting a resolver drives the collapse without
    // touching the journal side-map.
    const entry = recordEffect(
      { kind: 'invoke-operation', operationId: 'core.reindex', args: {} },
      CORE_PROVENANCE,
      { originator: 'agent' },
    );
    const backend: BackendLedgerEntry[] = [
      backendEntry({ kind: 'operation', operationId: 'core.reindex', executionId: 'exec-77', occurredAt: '2026-05-26T00:00:05.000Z' }),
    ];
    // Resolver maps this journal entry to the backend execution id → collapse to one row.
    const collapsed = unifiedActivity(backend, listJournal(), id => (id === entry.id ? 'exec-77' : undefined));
    expect(collapsed).toHaveLength(1);
    // A resolver that returns no correlation → no collapse (two rows).
    const notCollapsed = unifiedActivity(backend, listJournal(), () => undefined);
    expect(notCollapsed).toHaveLength(2);
  });

  it('projects a gate-firing row and exposes it via the trust-audit read-view (gateFirings)', () => {
    const backend: BackendLedgerEntry[] = [
      backendEntry({ kind: 'operation', operationId: 'core.ping', outcome: 'SUCCESS', occurredAt: '2026-05-26T00:00:00.000Z' }),
      backendEntry({
        kind: 'gate',
        operationId: 'core.bulk-reindex',
        disposition: 'GATED',
        gateBehavior: 'TYPED_CONFIRM',
        originator: 'agent',
        occurredAt: '2026-05-26T00:00:01.000Z',
      }),
    ];
    const rows = unifiedActivity(backend, []);
    const gateRow = rows.find(r => r.kind === 'gate')!;
    expect(gateRow.label).toContain('GATED');
    expect(gateRow.label).toContain('Bulk Reindex');
    expect(gateRow.label).not.toContain('core.bulk-reindex');
    expect(gateRow.label).toContain('TYPED_CONFIRM');

    // The 538 trust-firing audit is just a filter over the one ledger.
    const audit = gateFirings(rows);
    expect(audit).toHaveLength(1);
    expect(audit[0]!.kind).toBe('gate');
    expect(audit[0]!.originator).toBe('agent');
  });

  it('projects a kind=index terminal outcome row with a system-attributed label', () => {
    // Tempdoc 550 thesis I: indexing terminal outcomes are the system-operation contributor to the
    // one log; Activity shows them with an originator=system "Indexed · <collection>" label.
    const backend: BackendLedgerEntry[] = [
      backendEntry({
        id: 'index:2026-05-28T00:00:00.000Z:default:abc123:DONE',
        kind: 'index',
        originator: 'system',
        pathHash: 'abc123def456',
        collection: 'default',
        state: 'DONE',
        occurredAt: '2026-05-28T00:00:00.000Z',
      }),
      backendEntry({
        id: 'index:2026-05-28T00:00:01.000Z:help:def456:FAILED',
        kind: 'index',
        originator: 'system',
        pathHash: 'def456abc123',
        collection: 'help',
        state: 'FAILED',
        occurredAt: '2026-05-28T00:00:01.000Z',
      }),
    ];
    const rows = unifiedActivity(backend, []);
    const done = rows.find(r => r.kind === 'index' && r.label.startsWith('Indexed'))!;
    expect(done.originator).toBe('system');
    expect(done.label).toContain('default');
    const failed = rows.find(r => r.label.startsWith('Index failed'))!;
    expect(failed.label).toContain('help');
  });

  it('a RETRY_EXHAUSTED index row is labelled distinctly from a parse failure', () => {
    // Tempdoc 885 item 21b: without its own arm this state falls to the else branch and renders as
    // "Indexed" — reporting a file that was never readable as successfully indexed.
    const rows = unifiedActivity(
      [
        backendEntry({
          id: 'index:2026-09-02T00:00:00.000Z:docs:aaa111:RETRY_EXHAUSTED',
          kind: 'index',
          originator: 'system',
          pathHash: 'aaa111bbb222',
          collection: 'docs',
          state: 'RETRY_EXHAUSTED',
          occurredAt: '2026-09-02T00:00:00.000Z',
        }),
      ],
      [],
    );
    const exhausted = rows.find(r => r.kind === 'index')!;
    expect(exhausted.label).toContain('Index gave up');
    expect(exhausted.label).toContain('docs');
    expect(exhausted.label).not.toContain('Index failed');
  });
});

describe('ActionLedgerClient', () => {
  it('fetchBackendLedger parses the entries array', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(JSON.stringify({ entries: [backendEntry({ operationId: 'core.ping' })] }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    const client = new ActionLedgerClient({ apiBase: 'http://localhost', fetchImpl });
    const entries = await client.fetchBackendLedger();
    expect(entries).toHaveLength(1);
    expect(entries[0]!.operationId).toBe('core.ping');
    expect(String(fetchImpl.mock.calls[0]![0])).toContain('/api/action-ledger');
  });

  it('unifiedActivity() folds the live FE journal into the backend ledger', async () => {
    recordEffect({ kind: 'navigate', to: '#here' }, CORE_PROVENANCE, { originator: 'user' });
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(
        JSON.stringify({
          entries: [backendEntry({ operationId: 'core.reindex', occurredAt: '2000-01-01T00:00:00.000Z' })],
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    );
    const client = new ActionLedgerClient({ apiBase: 'http://localhost', fetchImpl });

    const rows = await client.unifiedActivity();
    // One backend op + one local effect (the recordEffect above).
    expect(rows.some(r => r.source === 'backend' && r.label.includes('Reindex'))).toBe(true);
    expect(rows.some(r => r.source === 'fe-effect' && r.kind === 'navigate')).toBe(true);
    expect(listJournal()).toHaveLength(1);
  });

  it('throws on a non-OK backend response', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(new Response('nope', { status: 500 }));
    const client = new ActionLedgerClient({ apiBase: 'http://localhost', fetchImpl });
    await expect(client.fetchBackendLedger()).rejects.toThrow(/action-ledger fetch failed/);
  });
});

// Minimal EventSource double (mirrors EnvelopeStream's 'frame' listener contract).
class FakeEventSource {
  listeners: Record<string, Array<(e: unknown) => void>> = {};
  closed = false;
  constructor(public url: string) {}
  addEventListener(type: string, fn: (e: unknown) => void): void {
    (this.listeners[type] ??= []).push(fn);
  }
  removeEventListener(type: string, fn: (e: unknown) => void): void {
    this.listeners[type] = (this.listeners[type] ?? []).filter(f => f !== fn);
  }
  close(): void {
    this.closed = true;
  }
  emitFrame(envelope: unknown): void {
    for (const fn of this.listeners['frame'] ?? []) fn({ data: JSON.stringify(envelope) });
  }
}

describe('openActionLedgerStream (tempdoc 550 G3/G4/G5 — live read-view)', () => {
  it('renders the snapshot then folds in a live UPDATE row', () => {
    let es: FakeEventSource | undefined;
    const seen: Array<ReturnType<typeof unifiedActivity>> = [];
    const stop = openActionLedgerStream({
      apiBase: 'http://localhost',
      eventSourceFactory: url => {
        es = new FakeEventSource(url);
        return es as unknown as EventSource;
      },
      onActivity: rows => seen.push(rows),
    });
    expect(es!.url).toContain('/api/action-ledger/stream');

    // Snapshot lifecycle frame with one backend operation row.
    es!.emitFrame({
      streamId: 'action-ledger/v1',
      frameKind: 'LIFECYCLE',
      seq: 1,
      ts: '2026-05-26T00:00:00Z',
      resumeToken: 't1',
      payload: { kind: 'snapshot', entries: [{ kind: 'operation', operationId: 'core.a', occurredAt: '2026-05-26T00:00:00.000Z', originator: 'agent' }] },
    });
    // Live UPDATE: a gate firing.
    es!.emitFrame({
      streamId: 'action-ledger/v1',
      frameKind: 'UPDATE',
      seq: 2,
      ts: '2026-05-26T00:00:01Z',
      resumeToken: 't2',
      payload: { kind: 'gate', operationId: 'core.b', disposition: 'GATED', gateBehavior: 'TYPED_CONFIRM', occurredAt: '2026-05-26T00:00:01.000Z', originator: 'agent' },
    });

    const last = seen.at(-1)!;
    expect(last.map(r => r.kind)).toEqual(['operation', 'gate']);
    expect(gateFirings(last)).toHaveLength(1);

    stop();
    expect(es!.closed).toBe(true);
  });

  it('dedups by explicit event id — a row in both the snapshot and a later UPDATE appears once', () => {
    let es: FakeEventSource | undefined;
    const seen: Array<ReturnType<typeof unifiedActivity>> = [];
    const stop = openActionLedgerStream({
      apiBase: 'http://localhost',
      eventSourceFactory: url => {
        es = new FakeEventSource(url);
        return es as unknown as EventSource;
      },
      onActivity: rows => seen.push(rows),
    });

    const row = {
      id: 'operation:2026-05-26T00:00:00.000Z:core.a',
      kind: 'operation',
      operationId: 'core.a',
      occurredAt: '2026-05-26T00:00:00.000Z',
      originator: 'agent',
    };
    // Snapshot already contains the event...
    es!.emitFrame({
      streamId: 'action-ledger/v1',
      frameKind: 'LIFECYCLE',
      seq: 1,
      ts: '2026-05-26T00:00:00Z',
      resumeToken: 't1',
      payload: { kind: 'snapshot', entries: [row] },
    });
    // ...and a redelivered UPDATE carries the SAME id (e.g. reconnect replay).
    es!.emitFrame({ streamId: 'action-ledger/v1', frameKind: 'UPDATE', seq: 2, ts: '2026-05-26T00:00:01Z', resumeToken: 't2', payload: row });

    const last = seen.at(-1)!;
    expect(last.filter(r => r.id === row.id)).toHaveLength(1);

    stop();
  });

  it('dedups the SNAPSHOT frame by id (F1 — reload re-delivery appears once)', () => {
    let es: FakeEventSource | undefined;
    const seen: Array<ReturnType<typeof unifiedActivity>> = [];
    const stop = openActionLedgerStream({
      apiBase: 'http://localhost',
      eventSourceFactory: url => {
        es = new FakeEventSource(url);
        return es as unknown as EventSource;
      },
      onActivity: rows => seen.push(rows),
    });
    const row = {
      id: 'operation:2026-05-26T00:00:00.000Z:core.a',
      kind: 'operation',
      operationId: 'core.a',
      occurredAt: '2026-05-26T00:00:00.000Z',
      originator: 'agent',
    };
    // A snapshot that already contains the same event twice (e.g. duplicates from prior re-POSTs).
    es!.emitFrame({
      streamId: 'action-ledger/v1',
      frameKind: 'LIFECYCLE',
      seq: 1,
      ts: '2026-05-26T00:00:00Z',
      resumeToken: 't1',
      payload: { kind: 'snapshot', entries: [row, row] },
    });

    const last = seen.at(-1)!;
    expect(last.filter(r => r.id === row.id)).toHaveLength(1);
    stop();
  });
});

describe('startEffectIngest (tempdoc 550 thesis I — FE effects into the ONE log)', () => {
  it('distinct fresh journals cannot alias effects that share local numeric id 1', () => {
    const posted: Array<Record<string, unknown>> = [];
    const fetchImpl = vi.fn<typeof fetch>((_url, init) => {
      posted.push(JSON.parse(String(init?.body)) as Record<string, unknown>);
      return Promise.resolve(new Response('{}', { status: 202 }));
    });
    const first = recordEffect({ kind: 'navigate', to: '#first-client' }, CORE_PROVENANCE);
    startEffectIngest({ fetchImpl })();
    __resetJournalForTest();
    const second = recordEffect({ kind: 'navigate', to: '#second-client' }, CORE_PROVENANCE);
    startEffectIngest({ fetchImpl })();
    expect(first.id).toBe(1);
    expect(second.id).toBe(1);
    expect(posted).toHaveLength(2);
    expect(posted[0]!.id).not.toBe(posted[1]!.id);
  });

  it('posts FE-local effects to the ingest endpoint but NOT invoke-operation effects', () => {
    const posted: Array<Record<string, unknown>> = [];
    const fetchImpl = vi.fn<typeof fetch>((_url, init) => {
      posted.push(JSON.parse(String((init as RequestInit).body)) as Record<string, unknown>);
      return Promise.resolve(new Response('{}', { status: 202 }));
    });
    recordEffect({ kind: 'navigate', to: '#x' }, CORE_PROVENANCE, { originator: 'user' });
    // invoke-operation effects become authoritative backend Operation events — must NOT be posted.
    recordEffect(
      { kind: 'invoke-operation', operationId: 'core.reindex', args: {} },
      CORE_PROVENANCE,
      { originator: 'agent' },
    );

    const stop = startEffectIngest({ apiBase: 'http://localhost', fetchImpl });

    // flush() runs synchronously on start; the navigate is posted, the invoke-operation is skipped.
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(posted[0]!.effectKind).toBe('navigate');
    expect(String(fetchImpl.mock.calls[0]![0])).toContain('/api/action-ledger/events');
    stop();
  });
});

/**
 * Tempdoc 612 §3/§L — projectBackend stamps `isRoutine` so the curated Activity feed can de-flood. An
 * operation row is routine only when its DECLARED OperationCatalog facets grade it insignificant; an
 * ungradable op (registry not loaded / unknown id) fails toward foreground.
 */
describe('projectBackend — 612 routine curation', () => {
  function op(id: string, overrides: Partial<Operation> = {}): Operation {
    return {
      id,
      presentation: { labelKey: `ops.${id}.label`, descriptionKey: `ops.${id}.description`, iconHint: null, category: null },
      intf: { errors: [], inputs: {}, result: {}, uiHints: {} },
      policy: { risk: 'LOW', confirm: { kind: 'NONE' }, audit: 'METADATA_ONLY', undoSupported: false },
      availability: {},
      lineage: { affects: [], supersedes: [] },
      provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
      executors: ['UI'],
      audience: 'USER',
      consumers: [],
      ...overrides,
    };
  }
  function catalogOf(...entries: Operation[]): OperationCatalog {
    return { schemaVersion: '1.0', catalogVersion: 1, namespace: 'core', primitive: 'Operation', entries };
  }

  beforeEach(() => resetOperationCatalog());
  afterEach(() => resetOperationCatalog());

  it('marks an insignificant user operation routine (LOW · no-confirm · no-affects)', () => {
    seedOperationCatalog(catalogOf(op('core.resolve-path-hash')));
    const row = projectBackend(backendEntry({ kind: 'operation', operationId: 'core.resolve-path-hash', originator: 'user', outcome: 'SUCCESS' }));
    expect(row.isRoutine).toBe(true);
  });

  it('keeps a significant user operation foreground (destructive / mutates a Resource)', () => {
    seedOperationCatalog(
      catalogOf(op('core.index-gc', { policy: { risk: 'HIGH', confirm: { kind: 'TYPED' }, audit: 'METADATA_ONLY', undoSupported: false }, lineage: { affects: ['core.index'], supersedes: [] } })),
    );
    const row = projectBackend(backendEntry({ kind: 'operation', operationId: 'core.index-gc', originator: 'user', outcome: 'SUCCESS' }));
    expect(row.isRoutine).toBeUndefined();
  });

  it('never marks a FAILED user operation routine, even if otherwise insignificant', () => {
    seedOperationCatalog(catalogOf(op('core.resolve-path-hash')));
    const row = projectBackend(backendEntry({ kind: 'operation', operationId: 'core.resolve-path-hash', originator: 'user', outcome: 'FAILURE' }));
    expect(row.isRoutine).toBeUndefined();
  });

  it('fails toward foreground for an ungradable operation (empty registry / unknown id)', () => {
    // No seed → getOperation returns undefined.
    const row = projectBackend(backendEntry({ kind: 'operation', operationId: 'core.resolve-path-hash', originator: 'user', outcome: 'SUCCESS' }));
    expect(row.isRoutine).toBeUndefined();
  });

  it('never marks an agent operation routine (originator guard)', () => {
    seedOperationCatalog(catalogOf(op('core.resolve-path-hash')));
    const row = projectBackend(backendEntry({ kind: 'operation', operationId: 'core.resolve-path-hash', originator: 'agent', outcome: 'SUCCESS' }));
    expect(row.isRoutine).toBeUndefined();
  });

  it('marks a witnessed user preference effect routine (set-appearance) without any catalog', () => {
    const row = projectBackend(backendEntry({ kind: 'effect', effectKind: 'set-appearance', originator: 'user', occurredAt: '2026-05-26T00:00:00.000Z', id: 'fe-effect:1' }));
    expect(row.isRoutine).toBe(true);
  });
});

/**
 * Tempdoc 812 D2/D4 — the scan rollup is the DURABLE record of an indexing run; the per-document
 * index rows are the routine detail behind it, keyed to their scan.
 */
describe('scan rollup + index tiering (tempdoc 812)', () => {
  const rollup = (over: Partial<BackendLedgerEntry> = {}) =>
    projectBackend(
      backendEntry({
        kind: 'operation',
        operationId: 'core.scan-root',
        originator: 'system',
        outcome: 'COMPLETED',
        scanId: 'scan-1',
        collection: 'scifact',
        docsDone: 5184,
        docsFailed: 0,
        docsAdmitted: 5184,
        durationMs: 372_000,
        ...over,
      }),
    );

  it('projects one legible summary row and never routes it routine', () => {
    const row = rollup();
    expect(row.isScanRollup).toBe(true);
    expect(row.scanId).toBe('scan-1');
    expect(row.label).toBe('Indexed 5184 documents · scifact · 6m 12s');
    expect(row.isRoutine).toBeUndefined(); // the audit record is never de-flooded
  });

  it('states failures and incompleteness rather than rounding them away', () => {
    expect(rollup({ docsFailed: 3 }).label).toContain('3 failed');
    expect(rollup({ outcome: 'PARTIAL', docsDone: 12 }).label).toContain('(incomplete)');
    expect(rollup({ outcome: 'STARTED' }).label).toBe('Indexing · scifact…');
    expect(rollup({ durationMs: 45_000 }).label).toContain('45s');
    expect(rollup({ durationMs: 3_780_000 }).label).toContain('1h 03m');
  });

  it('a per-document index row is routine and carries its scan key as the group key', () => {
    const row = projectBackend(
      backendEntry({
        kind: 'index',
        originator: 'system',
        collection: 'scifact',
        state: 'DONE',
        pathHash: 'f7e852aa',
        scanId: 'scan-1',
      }),
    );
    expect(row.isRoutine).toBe(true);
    expect(row.groupKey).toBe('scan-1'); // exact grouping, not render-time adjacency
    expect(row.scanId).toBe('scan-1');
    expect(row.pathHash).toBe('f7e852aa');
    expect(row.collection).toBe('scifact');
  });

  it('a keyless index row falls back to the collection group key (pre-812 / single-file ingest)', () => {
    const row = projectBackend(
      backendEntry({ kind: 'index', originator: 'system', collection: 'default', state: 'DONE', pathHash: 'f7e852aa' }),
    );
    expect(row.groupKey).toBe('default');
    expect(row.scanId).toBeUndefined();
    expect(row.isRoutine).toBe(true);
  });

  it('grant and gate rows stay foreground on every originator (the tier must not hide them)', () => {
    for (const originator of ['user', 'agent', 'system'] as const) {
      expect(
        projectBackend(backendEntry({ kind: 'grant', originator, action: 'ISSUED', grantId: 'g', subject: 'core.search-index' })).isRoutine,
      ).toBeUndefined();
      expect(
        projectBackend(backendEntry({ kind: 'gate', originator, operationId: 'core.reindex', disposition: 'GATED', gateBehavior: 'TYPED_CONFIRM' })).isRoutine,
      ).toBeUndefined();
    }
  });
});
