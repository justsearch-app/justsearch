// @vitest-environment happy-dom

/**
 * Slice 490 §4.D — AdvisoryInboxDrawer component tests.
 *
 * Covers list rendering (newest-first), item-click ack flow,
 * Mark-all-read action, drawer open/close.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import './AdvisoryInboxDrawer.js';
import type { AdvisoryInboxDrawer } from './AdvisoryInboxDrawer.js';
import type {
  AdvisoryListener,
  AdvisoryRecord,
  AdvisorySnapshot,
  AdvisoryStore,
} from './AdvisoryStore.js';
import {
  __resetForTest as resetResourceCatalog,
  __seedForTest as seedResourceCatalog,
} from '../../../i18n/resourceCatalog.js';

class StubAdvisoryStore {
  private listeners = new Set<AdvisoryListener>();
  private snap: AdvisorySnapshot = {
    advisories: [],
    unreadCount: 0,
    isConnected: true,
    lastFrameKind: 'initial',
  };
  acknowledge = vi.fn();
  acknowledgeAll = vi.fn();

  subscribe(listener: AdvisoryListener): () => void {
    this.listeners.add(listener);
    listener(this.snap);
    return () => this.listeners.delete(listener);
  }
  push(snapshot: Partial<AdvisorySnapshot>): void {
    this.snap = { ...this.snap, ...snapshot };
    for (const l of this.listeners) l(this.snap);
  }
}

function rec(
  operationId: string,
  occurredAt: string,
  acknowledged: boolean,
  outcome: 'SUCCESS' | 'FAILURE' = 'SUCCESS',
  sourceRenderHint: 'EPHEMERAL' | 'PERSISTED' | 'REQUIRES_ACK' = 'PERSISTED',
): AdvisoryRecord {
  return {
    key: `operation.completed:${operationId}:${outcome}`,
    event: {
      classId: 'operation.completed',
      id: `operation.completed:${operationId}:${outcome}`,
      occurredAt,
      renderHint: sourceRenderHint,
      diagnosticsLink: null,
      provenance: {
        transport: 'BUTTON',
        executor: 'UI',
        initiator: null,
        occurredAt,
      },
      primaryAction: null,
      bodyI18nKey: `advisory.operation-completed.${outcome.toLowerCase()}`,
      classExtras: { operationId, outcome },
    },
    acknowledged,
    sourceRenderHint,
    origin: "stream",
  };
}

function make(store: AdvisoryStore | null): AdvisoryInboxDrawer {
  const el = document.createElement('jf-advisory-inbox-drawer') as AdvisoryInboxDrawer;
  el.store = store;
  document.body.appendChild(el);
  return el;
}

/** 574 B — Mark-all-read + close are <jf-button>; activate the native button in its control. */
async function activateJfButton(el: Element | null | undefined): Promise<void> {
  if (!el) throw new Error('activateJfButton: element not found');
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  const control = el.shadowRoot!.querySelector('jf-control')!;
  await (control as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  (control.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
}

describe('AdvisoryInboxDrawer', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    resetResourceCatalog();
  });

  it('renders empty state when no advisories', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.empty')?.textContent).toContain(
      'No advisories yet',
    );
  });

  it('renders advisories newest-first', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({
      advisories: [
        rec('core.older', '2026-05-12T09:00:00Z', false),
        rec('core.middle', '2026-05-12T09:05:00Z', false),
        rec('core.newest', '2026-05-12T09:10:00Z', false),
      ],
      unreadCount: 3,
    });
    await el.updateComplete;
    const items = el.shadowRoot?.querySelectorAll('.item') ?? [];
    expect(items.length).toBe(3);
    // The store passes advisories oldest-first; the drawer renders newest-first.
    // Titles are humanized through present() (559 ADV-1) — the operationId
    // `core.newest` renders as "Newest", `core.older` as "Older" — never raw.
    expect(items[0]?.textContent).toContain('Newest');
    expect(items[2]?.textContent).toContain('Older');
  });

  it('marks unread items with the unread CSS class', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({
      advisories: [
        rec('core.a', '2026-05-12T09:00:00Z', false),
        rec('core.b', '2026-05-12T09:05:00Z', true),
      ],
      unreadCount: 1,
    });
    await el.updateComplete;
    const items = el.shadowRoot?.querySelectorAll('.item') ?? [];
    // newest-first: index 0 is core.b (acknowledged), index 1 is core.a (unread)
    expect(items[0]?.classList.contains('unread')).toBe(false);
    expect(items[1]?.classList.contains('unread')).toBe(true);
  });

  it('clicking an unread item calls store.acknowledge with its key', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    const r = rec('core.click-me', '2026-05-12T09:00:00Z', false);
    store.push({ advisories: [r], unreadCount: 1 });
    await el.updateComplete;
    const item = el.shadowRoot?.querySelector('.item') as HTMLElement;
    item.click();
    expect(store.acknowledge).toHaveBeenCalledWith(r.key);
  });

  it('clicking an already-acknowledged item does not call acknowledge', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({
      advisories: [rec('core.read', '2026-05-12T09:00:00Z', true)],
      unreadCount: 0,
    });
    await el.updateComplete;
    const item = el.shadowRoot?.querySelector('.item') as HTMLElement;
    item.click();
    expect(store.acknowledge).not.toHaveBeenCalled();
  });

  it('Mark all read is soft-unavailable (aria-disabled, reachable reason) when unreadCount=0', async () => {
    // Tempdoc 596 — the empty-state gate is now TYPED availability (aria-disabled + a reachable reason),
    // not a hard native `disabled` with a suppressed title. The intent (non-actionable when nothing to
    // mark) is unchanged; the mechanism is the reachable one.
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ unreadCount: 0 });
    await el.updateComplete;
    const btn = el.shadowRoot?.querySelector('jf-button[label="Mark all read"]') as HTMLElement & {
      updateComplete: Promise<unknown>;
    };
    await btn.updateComplete;
    const control = btn.shadowRoot!.querySelector('jf-control') as HTMLElement & {
      updateComplete: Promise<unknown>;
    };
    await control.updateComplete;
    const inner = control.shadowRoot!.querySelector('button') as HTMLButtonElement;
    expect(inner.getAttribute('aria-disabled')).toBe('true');
    expect(inner.disabled).toBe(false); // soft, so it stays focusable + the reason is reachable
  });

  it('Mark all read invokes store.acknowledgeAll', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({
      advisories: [rec('core.x', '2026-05-12T09:00:00Z', false)],
      unreadCount: 1,
    });
    await el.updateComplete;
    await activateJfButton(el.shadowRoot?.querySelector('jf-button[label="Mark all read"]'));
    expect(store.acknowledgeAll).toHaveBeenCalled();
  });

  it('toggle() flips the open property', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    await el.updateComplete;
    expect(el.open).toBe(false);
    el.toggle();
    expect(el.open).toBe(true);
    el.toggle();
    expect(el.open).toBe(false);
  });

  // Slice 490 Pass-8 follow-up — diagnosticsLink rendering + renderHint dispatch.

  it('renders diagnosticsLink as an anchor when the value is an absolute URL', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    const r: AdvisoryRecord = {
      key: 'operation.completed:core.diag:FAILURE',
      event: {
        classId: 'operation.completed',
        id: 'operation.completed:core.diag:FAILURE',
        occurredAt: '2026-05-12T09:00:00Z',
        renderHint: 'PERSISTED',
        diagnosticsLink: 'https://example.test/diag/abc',
        provenance: {
          transport: 'BUTTON',
          executor: 'UI',
          initiator: null,
          occurredAt: '2026-05-12T09:00:00Z',
        },
        primaryAction: null,
        bodyI18nKey: 'advisory.operation-completed.failure',
        classExtras: { operationId: 'core.diag', outcome: 'FAILURE' },
      },
      acknowledged: false,
      sourceRenderHint: 'PERSISTED',
      origin: "stream",
    };
    store.push({ advisories: [r], unreadCount: 1 });
    await el.updateComplete;
    const link = el.shadowRoot?.querySelector('.item-diagnostics a') as HTMLAnchorElement;
    expect(link).not.toBeNull();
    expect(link.href).toBe('https://example.test/diag/abc');
    expect(link.target).toBe('_blank');
  });

  it('renders diagnosticsLink as plain text when the value is an i18n key', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({
      advisories: [
        {
          ...rec('core.diag', '2026-05-12T09:00:00Z', false, 'FAILURE'),
          event: {
            ...rec('core.diag', '2026-05-12T09:00:00Z', false, 'FAILURE').event,
            diagnosticsLink: 'health-events.worker-restart',
          },
        },
      ],
      unreadCount: 1,
    });
    await el.updateComplete;
    const span = el.shadowRoot?.querySelector('.item-diagnostics span');
    expect(span?.textContent).toContain('health-events.worker-restart');
    expect(el.shadowRoot?.querySelector('.item-diagnostics a')).toBeNull();
  });

  it('Substrate-completion — EPHEMERAL records hidden from drawer entirely', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({
      advisories: [rec('core.ephemeral', '2026-05-12T09:00:00Z', false, 'SUCCESS', 'EPHEMERAL')],
      unreadCount: 1,
    });
    await el.updateComplete;
    expect(el.advisories.length).toBe(0);
    expect(el.unreadCount).toBe(0);
    expect(el.shadowRoot?.querySelector('.empty')).not.toBeNull();
  });

  it('Substrate-completion — PERSISTED records render normally', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({
      advisories: [rec('core.persisted', '2026-05-12T09:00:00Z', false, 'SUCCESS', 'PERSISTED')],
      unreadCount: 1,
    });
    await el.updateComplete;
    expect(el.advisories.length).toBe(1);
  });

  it('Substrate-completion — REQUIRES_ACK records render normally (acks mandatory in drawer)', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({
      advisories: [
        rec('core.req-ack', '2026-05-12T09:00:00Z', false, 'SUCCESS', 'REQUIRES_ACK'),
      ],
      unreadCount: 1,
    });
    await el.updateComplete;
    expect(el.advisories.length).toBe(1);
  });

  it('close button sets open=false', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    el.open = true;
    await el.updateComplete;
    await activateJfButton(el.shadowRoot?.querySelector('jf-button[title="Close"]'));
    expect(el.open).toBe(false);
  });

  it('slice 494 §10.2 — unknown classId renders with default chrome', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    el.open = true;
    const unknownRecord: import('./AdvisoryStore.js').AdvisoryRecord = {
      key: 'synthetic.never-registered.test:key1',
      event: {
        classId: 'synthetic.never-registered.test',
        id: 'synthetic.never-registered.test:key1',
        occurredAt: '2026-05-15T10:00:00Z',
        renderHint: 'PERSISTED',
        diagnosticsLink: null,
        provenance: null,
        primaryAction: null,
        bodyI18nKey: null,
        classExtras: { foo: 'bar' },
      },
      acknowledged: false,
      sourceRenderHint: 'PERSISTED',
      origin: "stream",
    };
    store.push({ advisories: [unknownRecord], unreadCount: 1 });
    await el.updateComplete;
    const items = el.shadowRoot?.querySelectorAll('.item');
    expect(items?.length).toBe(1);
    const title = el.shadowRoot?.querySelector('.item-title');
    expect(title?.textContent).toContain('Advisory');
    expect(title?.textContent).toContain('ℹ');
    // tempdoc 558 Deepening 2 — the exact defect the audit found was the RAW dotted classId leaking into
    // the title ("schema.reindex-required (worker.schema)"). Pin that an unmapped class degrades to the
    // generic chrome label, never the raw machine key — the no-raw-leak property, regression-guarded.
    expect(title?.textContent).not.toContain('synthetic.never-registered');
  });

  it('humanizes a health.recoverable condition id — no raw dotted key in the title (tempdoc 558 Deepening 2)', async () => {
    // The audit's HEADLINE D2 defect was the drawer title rendering the RAW condition id —
    // "schema.reindex-required (worker.schema)". deriveTitle now routes the conditionId through
    // present({kind:'condition'}) (559 ADV-1), which humanizes it. Pin both halves: the humanized
    // form shows AND the raw dotted condition key does not — the exact regression that shipped past 557.
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    el.open = true;
    const condRecord: AdvisoryRecord = {
      key: 'health.recoverable:schema.reindex-required',
      event: {
        classId: 'health.recoverable',
        id: 'health.recoverable:schema.reindex-required',
        occurredAt: '2026-05-15T10:00:00Z',
        renderHint: 'PERSISTED',
        diagnosticsLink: null,
        provenance: null,
        primaryAction: null,
        bodyI18nKey: null,
        classExtras: { conditionId: 'schema.reindex-required', subject: 'worker.schema' },
      },
      acknowledged: false,
      sourceRenderHint: 'PERSISTED',
      origin: 'stream',
    };
    store.push({ advisories: [condRecord], unreadCount: 1 });
    await el.updateComplete;
    const title = el.shadowRoot?.querySelector('.item-title');
    expect(title?.textContent).toContain('Schema Reindex Required');
    expect(title?.textContent).not.toContain('schema.reindex-required');
  });

  it('humanizes the fallback reason subtitle — no raw reason token at the user altitude (tempdoc 613)', async () => {
    // §11 defect: the meta line printed the RAW reason enum verbatim ("• ReindexRequired"). The
    // subtitle now humanizes the token (camelCase → Title Case words), so the advisory feed cannot
    // leak a machine code beneath the already-humanized title.
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    el.open = true;
    const condRecord: AdvisoryRecord = {
      key: 'health.recoverable:schema.reindex-required',
      event: {
        classId: 'health.recoverable',
        id: 'health.recoverable:schema.reindex-required',
        occurredAt: '2026-05-15T10:00:00Z',
        renderHint: 'PERSISTED',
        diagnosticsLink: null,
        provenance: null, // ⇒ the fallback-subtitle path renders
        primaryAction: null,
        bodyI18nKey: null,
        classExtras: {
          conditionId: 'schema.reindex-required',
          subject: 'worker.schema',
          reason: 'ReindexRequired',
        },
      },
      acknowledged: false,
      sourceRenderHint: 'PERSISTED',
      origin: 'stream',
    };
    store.push({ advisories: [condRecord], unreadCount: 1 });
    await el.updateComplete;
    const meta = el.shadowRoot?.querySelector('.item-meta');
    // Raw camelCase token gone; humanized form present.
    expect(meta?.textContent).not.toContain('ReindexRequired');
    expect(meta?.textContent).toContain('Reindex Required');
  });

  // Slice 496 — filter chip tests

  function healthRec(conditionId: string, acknowledged = false): AdvisoryRecord {
    return {
      key: `health.recoverable:${conditionId}`,
      event: {
        classId: 'health.recoverable',
        id: `health.recoverable:${conditionId}`,
        occurredAt: '2026-05-15T10:00:00Z',
        renderHint: 'PERSISTED',
        diagnosticsLink: null,
        provenance: null,
        primaryAction: { target: 'core.reindex', defaultArgsJson: '{}' },
        bodyI18nKey: 'health-events.test',
        classExtras: { conditionId, severity: 'WARNING' },
      },
      acknowledged,
      sourceRenderHint: 'PERSISTED',
      origin: "stream",
    };
  }

  it('slice 496 — filter chips render when multiple classes exist', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    el.open = true;
    store.push({
      advisories: [
        rec('core.ping', '2026-05-15T10:00:00Z', false),
        healthRec('test.condition'),
      ],
      unreadCount: 2,
    });
    await el.updateComplete;
    const chips = el.shadowRoot?.querySelectorAll('jf-filter-chip');
    expect(chips?.length).toBeGreaterThanOrEqual(2);
  });

  it('slice 496 — clicking class chip narrows the list', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    el.open = true;
    store.push({
      advisories: [
        rec('core.ping', '2026-05-15T10:00:00Z', false),
        healthRec('test.condition'),
      ],
      unreadCount: 2,
    });
    await el.updateComplete;
    const chips = el.shadowRoot?.querySelectorAll('jf-filter-chip') as NodeListOf<HTMLButtonElement>;
    const healthChip = [...chips].find((c) => c.textContent?.includes('Recoverable'));
    healthChip?.click();
    await el.updateComplete;
    const items = el.shadowRoot?.querySelectorAll('.item');
    expect(items?.length).toBe(1);
  });

  it('slice 496 — unread-only toggle filters acknowledged items', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    el.open = true;
    store.push({
      advisories: [
        rec('core.read', '2026-05-15T10:00:00Z', true),
        rec('core.unread', '2026-05-15T10:01:00Z', false),
      ],
      unreadCount: 1,
    });
    await el.updateComplete;
    const unreadChip = [...(el.shadowRoot?.querySelectorAll('jf-filter-chip') ?? [])].find(
      (c) => c.textContent?.includes('Unread'),
    ) as HTMLButtonElement | undefined;
    unreadChip?.click();
    await el.updateComplete;
    const items = el.shadowRoot?.querySelectorAll('.item');
    expect(items?.length).toBe(1);
  });

  it('slice 496 — chips hidden when only one class exists', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    el.open = true;
    store.push({
      advisories: [rec('core.a', '2026-05-15T10:00:00Z', false)],
      unreadCount: 1,
    });
    await el.updateComplete;
    const classChips = [...(el.shadowRoot?.querySelectorAll('jf-filter-chip') ?? [])].filter(
      (c) => c.textContent?.includes('Operation') || c.textContent?.includes('Recoverable'),
    );
    expect(classChips.length).toBe(0);
  });

  it('slice 496 — filters reset on drawer close', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    el.open = true;
    store.push({
      advisories: [
        rec('core.ping', '2026-05-15T10:00:00Z', false),
        healthRec('test.condition'),
      ],
      unreadCount: 2,
    });
    await el.updateComplete;
    const chips = el.shadowRoot?.querySelectorAll('jf-filter-chip') as NodeListOf<HTMLButtonElement>;
    const healthChip = [...chips].find((c) => c.textContent?.includes('Recoverable'));
    healthChip?.click();
    await el.updateComplete;
    expect(el.shadowRoot?.querySelectorAll('.item')?.length).toBe(1);
    el.open = false;
    await el.updateComplete;
    el.open = true;
    await el.updateComplete;
    expect(el.shadowRoot?.querySelectorAll('.item')?.length).toBe(2);
  });

  /**
   * Tempdoc 941 — the per-reason sentence the backend authors reaches the reader.
   *
   * Every health emitter builds `bodyI18nKey` as `health-events.<id>.message`, so before this the
   * expanded detail could only ever show the GENERIC sentence — "the indexer is unavailable" —
   * even when the condition's reason (`classExtras.reason`, stamped by
   * `HealthRecoveryProjector.projectCondition`) had its own authored copy saying the Worker is
   * merely starting. The alarm and the status read identically.
   */
  it('941 — the expanded detail prefers the per-reason authored sentence over the generic one', async () => {
    seedResourceCatalog({
      'health-events.index.unavailable.message': 'The indexer is unavailable.',
      'health-events.index.unavailable.reason.WorkerStarting.message':
        'The Worker is starting up; the index will be available shortly.',
    });
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    el.open = true;
    const condRecord: AdvisoryRecord = {
      key: 'health.recoverable:index.unavailable',
      event: {
        classId: 'health.recoverable',
        id: 'health.recoverable:index.unavailable',
        occurredAt: '2026-05-15T10:00:00Z',
        renderHint: 'PERSISTED',
        diagnosticsLink: null,
        provenance: null,
        primaryAction: null,
        bodyI18nKey: 'health-events.index.unavailable.message',
        classExtras: {
          conditionId: 'index.unavailable',
          subject: 'worker.index',
          reason: 'WorkerStarting',
        },
      },
      acknowledged: false,
      sourceRenderHint: 'PERSISTED',
      origin: 'stream',
    };
    store.push({ advisories: [condRecord], unreadCount: 1 });
    await el.updateComplete;
    (el.shadowRoot?.querySelector('.item') as HTMLElement).click();
    await el.updateComplete;
    const detail = el.shadowRoot?.querySelector('.item-detail');
    expect(detail?.textContent).toContain('The Worker is starting up');
    // Precision: this must pass because the REASON key was read, not because both strings are
    // present. The generic sentence is the one it replaced.
    expect(detail?.textContent).not.toContain('The indexer is unavailable.');
  });

  it('941 — falls back to the generic bodyI18nKey sentence when the reason has no authored copy', async () => {
    seedResourceCatalog({
      'health-events.index.unavailable.message': 'The indexer is unavailable.',
    });
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    el.open = true;
    const condRecord: AdvisoryRecord = {
      key: 'health.recoverable:index.unavailable',
      event: {
        classId: 'health.recoverable',
        id: 'health.recoverable:index.unavailable',
        occurredAt: '2026-05-15T10:00:00Z',
        renderHint: 'PERSISTED',
        diagnosticsLink: null,
        provenance: null,
        primaryAction: null,
        bodyI18nKey: 'health-events.index.unavailable.message',
        classExtras: {
          conditionId: 'index.unavailable',
          subject: 'worker.index',
          reason: 'NoAuthoredCopyForThis',
        },
      },
      acknowledged: false,
      sourceRenderHint: 'PERSISTED',
      origin: 'stream',
    };
    store.push({ advisories: [condRecord], unreadCount: 1 });
    await el.updateComplete;
    (el.shadowRoot?.querySelector('.item') as HTMLElement).click();
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.item-detail')?.textContent).toContain(
      'The indexer is unavailable.',
    );
  });

  it('slice 496 — chip count reflects advisory count per value', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    el.open = true;
    store.push({
      advisories: [
        rec('core.a', '2026-05-15T10:00:00Z', false),
        rec('core.b', '2026-05-15T10:01:00Z', false),
        healthRec('test.condition'),
      ],
      unreadCount: 3,
    });
    await el.updateComplete;
    const chips = el.shadowRoot?.querySelectorAll('jf-filter-chip');
    const opChip = [...(chips ?? [])].find((c) => c.textContent?.includes('Operation'));
    expect(opChip?.textContent).toContain('(2)');
    const healthChip = [...(chips ?? [])].find((c) => c.textContent?.includes('Recoverable'));
    expect(healthChip?.textContent).toContain('(1)');
  });
});
