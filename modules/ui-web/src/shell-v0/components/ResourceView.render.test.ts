// @vitest-environment happy-dom

/**
 * Slice 3a.1.9 §A.6 — `<jf-resource-view>` render tests (smoke).
 *
 * Mounts the element against an in-memory catalog stub. Smoke-only:
 * verifies the element renders the right placeholder for unknown
 * resources + missing strategies, and the renderer dispatch picks
 * the right tag for a TABULAR resource. Full SSE-flow coverage
 * lives in Phase 8's integration test (live-stack smoke against
 * runHeadlessEval).
 *
 * The EnvelopeStream / EventSource path is NOT exercised here; that
 * runs against the real backend in Phase 8 verification.
 */

import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import './ResourceView.js';

// Stub EventSource at module level to a no-op that drops connections
// silently. Avoids unhandled-error noise from happy-dom attempting a
// real network call against /api/indexing-jobs/stream.
class NoopEventSource extends EventTarget {
  static urls: string[] = [];
  readyState = 0;
  url: string;
  CONNECTING = 0;
  OPEN = 1;
  CLOSED = 2;
  withCredentials = false;
  onopen: ((this: EventSource, ev: Event) => unknown) | null = null;
  onmessage: ((this: EventSource, ev: MessageEvent) => unknown) | null = null;
  onerror: ((this: EventSource, ev: Event) => unknown) | null = null;
  constructor(url: string | URL) {
    super();
    this.url = String(url);
    NoopEventSource.urls.push(this.url);
  }
  close(): void {
    this.readyState = this.CLOSED;
  }
}

beforeAll(() => {
  vi.stubGlobal(
    'EventSource',
    function StubES(url: string | URL) {
      return new NoopEventSource(url) as unknown as EventSource;
    },
  );
});
import type { ResourceView } from './ResourceView.js';
import {
  __resetForTest as resetResourceCatalog,
  __seedForTest as seedResourceCatalog,
} from '../../api/registry/ResourceCatalogClient.js';
import {
  __resetForTest as resetSchemaFetcher,
} from '../../api/registry/schemaFetcher.js';
import type { Resource, ResourceCatalog } from '../../api/types/registry.js';

function tabularResource(): Resource {
  return {
    id: 'core.indexing-jobs',
    presentation: {
      labelKey: 'registry-resource.indexing-jobs.label',
      descriptionKey: 'registry-resource.indexing-jobs.description',
      iconHint: null,
      category: null,
    },
    schema: 'https://ssot.justsearch/v1/schemas/indexing-job-view.v1.json',
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
    itemOperations: ['core.cancel-indexing-job', 'core.retry-indexing-job'],
    collectionOperations: [],
    primaryKey: 'pathHash',
    audience: 'USER',
    consumers: [],
    role: 'PRODUCT',
  };
}

function catalogOf(...entries: Resource[]): ResourceCatalog {
  return {
    schemaVersion: '1.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'Resource',
    entries,
  };
}

async function mountIsolated(resourceId: string): Promise<ResourceView> {
  const el = document.createElement('jf-resource-view') as ResourceView;
  el.resourceId = resourceId;
  document.body.appendChild(el);
  // Wait one tick for connectedCallback's async bind() to settle.
  await Promise.resolve();
  await el.updateComplete;
  return el;
}

describe('ResourceView (jf-resource-view) — placeholders', () => {
  beforeEach(() => {
    resetResourceCatalog();
    resetSchemaFetcher();
    NoopEventSource.urls = [];
  });
  afterEach(() => {
    resetResourceCatalog();
    resetSchemaFetcher();
    document.body.innerHTML = '';
  });

  it('shows a placeholder when resource-id is unknown', async () => {
    // Seed the catalog with an unrelated entry so the empty-catalog
    // wait path (slice 3a.2 §B.B.E.1) doesn't apply — we want to
    // exercise the populated-but-missing-id error path here.
    seedResourceCatalog(catalogOf(tabularResource()));
    const el = await mountIsolated('core.unknown');
    const text = el.shadowRoot?.textContent ?? '';
    expect(text).toContain('Resource not found');
  });

  it('shows a placeholder when resource-id is empty', async () => {
    const el = await mountIsolated('');
    const text = el.shadowRoot?.textContent ?? '';
    expect(text).toContain('No resource-id');
  });

  it('renders header with localized label key when resource is found', async () => {
    seedResourceCatalog(catalogOf(tabularResource()));
    const el = await mountIsolated('core.indexing-jobs');
    // The element starts the stream (real EventSource against
    // /api/indexing-jobs/stream) but we don't drive frames here; the
    // header renders synchronously from the Resource entry.
    const header = el.shadowRoot?.querySelector('h2');
    // localizeResourceKey falls back to raw key when i18n catalog
    // isn't seeded (test mode).
    expect(header?.textContent).toContain('registry-resource.indexing-jobs.label');
    el.remove();
  });

  it('exposes connectionState as reactive property', async () => {
    seedResourceCatalog(catalogOf(tabularResource()));
    const el = await mountIsolated('core.indexing-jobs');
    // Either 'connecting' (initial) or 'error' if EventSource fails;
    // both demonstrate the state is a real lifecycle signal.
    expect(['idle', 'connecting', 'connected', 'error']).toContain(
      el.connectionState,
    );
    el.remove();
  });

  it('prefixes SSE resource endpoints with api-base', async () => {
    seedResourceCatalog(catalogOf(tabularResource()));
    const el = document.createElement('jf-resource-view') as ResourceView;
    el.resourceId = 'core.indexing-jobs';
    el.apiBase = 'http://127.0.0.1:8080';
    document.body.appendChild(el);
    await Promise.resolve();
    await el.updateComplete;

    expect(NoopEventSource.urls).toContain('http://127.0.0.1:8080/api/indexing-jobs/stream');
    el.remove();
  });

  it('waits for catalog arrival when mounted before boot completes (slice 3a.2 §B.B.E.1)', async () => {
    // Catalog is empty at mount time — simulates first-visit boot
    // race where `<jf-resource-view>` mounts before
    // `bootResourceRegistry()` finishes. Pre-fix behavior: the
    // view immediately errored with "Resource not found in catalog";
    // post-fix: the view stays in 'connecting' and re-binds when
    // the catalog arrives.
    const el = await mountIsolated('core.indexing-jobs');
    expect(el.connectionState).toBe('connecting');
    expect(el.shadowRoot?.textContent ?? '').not.toContain('Resource not found');

    // Catalog arrives. The onCatalogChange listener fires and bind()
    // re-runs.
    seedResourceCatalog(catalogOf(tabularResource()));
    await el.updateComplete;
    // bind() is async; wait one more microtask for it to progress
    // past the lookup.
    await Promise.resolve();
    await el.updateComplete;

    // The view has progressed past the empty-catalog wait — either
    // 'connecting' (stream opening) or 'connected' / 'error' from
    // the stub EventSource. The defining post-fix property: it is
    // NOT in the "Resource not found" error state.
    expect(el.connectionState).not.toBe('error');
    expect(el.shadowRoot?.textContent ?? '').not.toContain('Resource not found');
    el.remove();
  });

  it('errors immediately when catalog is populated but id is missing', async () => {
    // Distinct from the empty-catalog wait path: if the catalog has
    // entries and our id isn't among them, the not-found is real.
    // Pre-fix and post-fix behavior should match here.
    seedResourceCatalog(catalogOf(tabularResource()));
    const el = await mountIsolated('core.does-not-exist');
    expect(el.connectionState).toBe('error');
    expect(el.shadowRoot?.textContent ?? '').toContain('Resource not found');
    el.remove();
  });

  it('727 F-4 — opens the api-base-prefixed EventSource for an operation-history-shaped resource', async () => {
    // Mirrors "prefixes SSE resource endpoints with api-base" above, for the exact
    // EVENT_STREAM×SSE_STREAM shape of core.operation-history. This isolated component-level
    // check passed even before the 727 F-4 fix — <jf-resource-view> itself was never buggy;
    // the bug was one layer up (the (Resource, list-item) strategy dropping `apiBase` before
    // it ever reached this element — see resourceListItem.test.ts + ActivitySurface.render.test.ts
    // for the tests that fail on the pre-fix code). This test guards the element's own contract
    // so a future regression at either layer is caught somewhere.
    const opHistory: Resource = {
      id: 'core.operation-history',
      presentation: {
        labelKey: 'registry-resource.operation-history.label',
        descriptionKey: 'registry-resource.operation-history.description',
        iconHint: null,
        category: null,
      },
      schema: 'https://ssot.justsearch/v1/schemas/operation-history-entry.v1.json',
      category: 'EVENT_STREAM',
      subscriptionMode: 'SSE_STREAM',
      endpoint: '/api/operation-history/stream',
      kind: 'operation-history',
      history: null,
      recovery: null,
      provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
      privacy: { pathPolicy: 'NO_PATHS', loopbackOnly: false, resolver: null },
      itemOperations: [],
      collectionOperations: [],
      primaryKey: '',
      audience: 'USER',
      consumers: [],
      role: 'PRODUCT',
    };
    seedResourceCatalog(catalogOf(opHistory));
    const el = document.createElement('jf-resource-view') as ResourceView;
    el.resourceId = 'core.operation-history';
    el.apiBase = 'http://127.0.0.1:33221';
    document.body.appendChild(el);
    await Promise.resolve();
    await el.updateComplete;

    expect(NoopEventSource.urls).toContain(
      'http://127.0.0.1:33221/api/operation-history/stream',
    );
    // 'connecting' here (not 'connected') is expected and correct: NoopEventSource never fires
    // 'open'/'frame'. The property under test is that the stream-open attempt happened at all,
    // against the right URL — 'error' would mean bind() rejected before reaching that point.
    expect(el.connectionState).not.toBe('error');
    el.remove();
  });
});


describe('Q14 — indexing Resource window and queue totals', () => {
  it('names the bounded window and reads totals outside it from status', async () => {
    const { __feedForTest, __resetAiStateForTest } = await import('../state/aiStateStore.js');
    const { formatCount } = await import('../display/format.js');
    resetResourceCatalog();
    seedResourceCatalog(catalogOf(tabularResource()));
    const el = await mountIsolated('core.indexing-jobs');
    try {
      __feedForTest({ status: { worker: {
        core: { indexState: 'INDEXING' },
        migration: { pendingJobsCount: 700000, processingJobsCount: 16 },
        failure: { failedJobs: 1001 },
      } } });
      await Promise.resolve(); await Promise.resolve(); await el.updateComplete;
      expect(el.shadowRoot?.querySelector('[data-testid="indexing-window-notice"]')?.textContent).toContain('window');
      const totals = el.shadowRoot?.querySelector('[data-testid="indexing-queue-totals"]')?.textContent;
      expect(totals).toContain(`${formatCount(700000)} queued`);
      expect(totals).toContain('16 processing');
      expect(totals).toContain(`${formatCount(1001)} failed`);
      __feedForTest({ status: { worker: { core: { indexState: 'UNAVAILABLE' },
        migration: { pendingJobsCount: 0, processingJobsCount: 0 }, failure: { failedJobs: 0 },
      } } });
      await Promise.resolve(); await Promise.resolve(); await el.updateComplete;
      expect(el.shadowRoot?.textContent).toContain('Queue totals unavailable');
      __feedForTest({ status: null });
      await Promise.resolve(); await Promise.resolve(); await el.updateComplete;
      expect(el.shadowRoot?.textContent).toContain('Queue totals unavailable');
    } finally { el.remove(); __resetAiStateForTest(); resetResourceCatalog(); }
  });
});
