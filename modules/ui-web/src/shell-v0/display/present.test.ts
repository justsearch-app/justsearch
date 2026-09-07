// @vitest-environment happy-dom
/**
 * present() — the single display projector (tempdoc 557 §2.A).
 *
 * These tests pin the projector's behaviour with NO catalog loaded: every kind
 * falls back to a human-readable label, never a raw id/key/route. That is the
 * Q3/Q6/Q7 leak-class guarantee — a raw identifier must never reach the user.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { present, presentLabel } from './present';
import {
  localizeResourceKey,
  __seedForTest as seedResourceCatalog,
  __resetForTest as resetResourceCatalog,
} from '../../i18n/resourceCatalog';
import { describeEffect } from '../substrates/effects/describe';
import {
  registerStatusBarItem,
  __resetForTest as resetStatusBar,
} from '../commands/StatusBarRegistry';
import { CORE_PROVENANCE } from '../primitives/provenance';

describe('present() — operation', () => {
  it('humanizes a dotted operation id when the catalog has no label', () => {
    expect(present({ kind: 'operation', id: 'core.reindex' }).label).toBe('Reindex');
  });
  it('humanizes a multi-word last segment', () => {
    expect(present({ kind: 'operation', id: 'core.bulk-reindex' }).label).toBe('Bulk Reindex');
  });
  it('never leaks the raw id', () => {
    expect(present({ kind: 'operation', id: 'core.export-diagnostics' }).label).not.toContain('core.');
  });
  // Tempdoc 577 §2.12 Move 4 — an AGENT WIRE NAME (snake_case, the ledger's stamp) humanizes
  // cleanly instead of the raw "Core_search_index" the History tab showed (§2.11 #5).
  it('humanizes an agent wire name (underscores + core_ prefix)', () => {
    expect(present({ kind: 'operation', id: 'core_search_index' }).label).toBe('Search Index');
    expect(present({ kind: 'operation', id: 'core_ingest_files' }).label).toBe('Ingest Files');
    expect(present({ kind: 'operation', id: 'core_search_index' }).label).not.toContain('_');
  });
});

describe('present() — surface / route', () => {
  it('derives a title from a surface id, stripping the -surface suffix', () => {
    expect(present({ kind: 'surface', id: 'core.library-surface' }).label).toBe('Library');
  });
  it('resolves a justsearch://surface/<id> route to the same title', () => {
    expect(present({ kind: 'route', target: 'justsearch://surface/core.health-surface' }).label).toBe(
      'Health',
    );
  });
  it('falls back to the bare target when it is neither a known surface nor a route', () => {
    expect(present({ kind: 'route', target: '#raw' }).label).toBe('#raw');
  });
});

describe('present() — condition', () => {
  it('humanizes a dotted condition id, keeping the namespace', () => {
    expect(present({ kind: 'condition', id: 'schema.reindex-required' }).label).toBe(
      'Schema Reindex Required',
    );
  });
  it('never leaks the raw condition id', () => {
    expect(present({ kind: 'condition', id: 'index.unavailable' }).label).not.toContain('.');
  });
});

/**
 * Tempdoc 941 — the health-events catalog copy the backend authors and the frontend never read.
 *
 * `bootHealthEventsCatalog` merges `health-events.*` into the SAME store `localizeResourceKey`
 * reads, so these tests seed that store directly (the boot path's own fetch is covered in
 * `i18n/resourceCatalog.test.ts`) and assert the resolution ORDER: per-reason message > generic
 * message for the description, authored label > humanized id for the label.
 */
describe('present() — condition reads the authored health-events copy (941)', () => {
  afterEach(() => resetResourceCatalog());

  it('prefers the per-reason message over the generic one', () => {
    seedResourceCatalog({
      'health-events.index.unavailable.label': 'Indexer unavailable',
      'health-events.index.unavailable.message': 'The indexer is unavailable.',
      'health-events.index.unavailable.reason.WorkerStarting.message':
        'The Worker is starting up; the index will be available shortly.',
    });
    const presented = present({
      kind: 'condition',
      id: 'index.unavailable',
      reason: 'WorkerStarting',
    });
    expect(presented.label).toBe('Indexer unavailable');
    expect(presented.description).toBe(
      'The Worker is starting up; the index will be available shortly.',
    );
  });

  it('falls back to the generic message when the reason has no authored override', () => {
    seedResourceCatalog({
      'health-events.index.unavailable.message': 'The indexer is unavailable.',
    });
    // A reason the catalog does not carry copy for must not blank the description.
    expect(
      present({ kind: 'condition', id: 'index.unavailable', reason: 'SomethingElse' }).description,
    ).toBe('The indexer is unavailable.');
    // …and so must a condition asserted with no reason at all.
    expect(present({ kind: 'condition', id: 'index.unavailable' }).description).toBe(
      'The indexer is unavailable.',
    );
  });

  it('leaves the description absent — not a raw key — when the catalog has no entry', () => {
    seedResourceCatalog({ 'health-events.other.thing.message': 'Unrelated.' });
    const presented = present({
      kind: 'condition',
      id: 'schema.reindex-required',
      reason: 'SchemaMismatch',
    });
    expect(presented.description).toBeUndefined();
    // The pre-941 humanized-id fallback is unchanged when nothing is authored.
    expect(presented.label).toBe('Schema Reindex Required');
  });
});

describe('present() — resource / effect delegate to the single resolvers', () => {
  it('resource label === localizeResourceKey(key)', () => {
    const key = 'registry-resource.metric.unknown.label';
    expect(present({ kind: 'resource', key }).label).toBe(localizeResourceKey(key));
  });
  it('non-navigate effect label === describeEffect(effect) (delegates to the pure resolver)', () => {
    const effect = { kind: 'noop' } as const;
    expect(present({ kind: 'effect', effect }).label).toBe(describeEffect(effect));
  });
  it('557 Q7 — a navigate effect humanizes its route, never leaking the raw justsearch:// id', () => {
    // No catalog loaded → id-derived label, but still NOT the raw route.
    const effect = { kind: 'navigate', to: 'justsearch://surface/core.health-surface' } as const;
    const label = present({ kind: 'effect', effect }).label;
    expect(label).toBe('Navigate to Health');
    expect(label).not.toContain('justsearch://');
  });
});

describe('present() — metric (559 Authority V)', () => {
  afterEach(() => resetStatusBar());

  it("projects the registered StatusBarItem's declared accessibleLabel", () => {
    registerStatusBarItem({
      id: 'core.files',
      position: 'left',
      priority: 20,
      source: 'core',
      provenance: CORE_PROVENANCE,
      accessibleLabel: 'Documents indexed',
      render: () => 'core.files',
    });
    expect(present({ kind: 'metric', id: 'core.files' }).label).toBe('Documents indexed');
  });

  it('falls back to a humanized id when the metric is unregistered or has no label', () => {
    // unregistered id → humanized fallback, never the raw dotted id.
    expect(present({ kind: 'metric', id: 'core.queue-depth' }).label).toBe('Queue Depth');
    expect(present({ kind: 'metric', id: 'core.queue-depth' }).label).not.toContain('core.');
  });
});

describe('presentLabel()', () => {
  it('returns the same label as present(ref).label', () => {
    const ref = { kind: 'operation', id: 'core.reindex' } as const;
    expect(presentLabel(ref)).toBe(present(ref).label);
  });
});
