// @vitest-environment happy-dom

/**
 * Tempdoc 511-followup-2 Track CC — `<jf-resource>` component-level
 * smoke test. Mirrors the JfOperation.test.ts shape.
 */

import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

// Incidental requests are outside these render/event scenarios. Keep their
// unavailable-backend behavior explicit, without contacting a real server.
const originalFetch = globalThis.fetch;
beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Test backend unavailable')));
});
afterEach(() => { globalThis.fetch = originalFetch; });

// ResourceView's connectedCallback opens an EnvelopeStream backed by
// EventSource. happy-dom has no EventSource; stub it to a no-op so
// the inner <jf-resource-view> mounts without blowing up the test.
// Same pattern as components/ResourceView.render.test.ts.
class NoopEventSource extends EventTarget {
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

import {
  __resetForTest,
  __seedForTest,
} from '../../../api/registry/ResourceCatalogClient';
import type {
  Resource,
  ResourceCatalog,
} from '../../../api/types/registry';
import { bootstrapAggregateSubstrate, __resetBootstrap } from '../bootstrap';
import { __clearAggregateRegistry } from '../aggregateRegistry';
import { setViewerAudience } from '../../state/viewerAudienceState';
import { __resetUserStateForTest } from '../../state/UserStateDocument';
import './JfResource.js';

function res(id: string, overrides: Partial<Resource> = {}): Resource {
  return {
    id,
    presentation: {
      labelKey: `registry-resource.${id}.label`,
      descriptionKey: `registry-resource.${id}.description`,
      iconHint: null,
      category: null,
    },
    schema: `https://ssot.justsearch/v1/schemas/${id}.v1.json`,
    category: 'EVENT_STREAM',
    subscriptionMode: 'SSE_STREAM',
    endpoint: `/api/${id}/stream`,
    kind: 'test',
    history: null,
    recovery: null,
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
    privacy: { pathPolicy: 'NO_PATHS', loopbackOnly: true, resolver: null },
    itemOperations: [],
    collectionOperations: [],
    primaryKey: '',
    audience: 'USER',
    consumers: [],
    role: 'PRODUCT',
    ...overrides,
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

async function mountResource(
  resourceId: string,
  context = 'list-item',
  audience?: string,
): Promise<HTMLElement> {
  const el = document.createElement('jf-resource');
  el.setAttribute('resource-id', resourceId);
  el.setAttribute('context', context);
  if (audience !== undefined) {
    el.setAttribute('viewer-audience', audience);
  }
  document.body.appendChild(el);
  await (el as unknown as { updateComplete: Promise<void> }).updateComplete;
  return el;
}

describe('<jf-resource>', () => {
  beforeEach(() => {
    __resetForTest();
    __clearAggregateRegistry();
    __resetBootstrap();
    __resetUserStateForTest();
    bootstrapAggregateSubstrate();
  });
  afterEach(() => {
    document.body.innerHTML = '';
    __resetForTest();
    __clearAggregateRegistry();
    __resetBootstrap();
    __resetUserStateForTest();
  });

  it('renders inner <jf-resource-view> when the resource is in the catalog', async () => {
    __seedForTest(catalogOf(res('core.test')));
    const el = await mountResource('core.test');
    const inner = el.querySelector('jf-resource-view');
    expect(inner).not.toBeNull();
    expect(inner?.getAttribute('resource-id')).toBe('core.test');
  });

  it('renders nothing when the resource is missing', async () => {
    const el = await mountResource('core.unknown');
    expect(el.querySelector('jf-resource-view')).toBeNull();
  });

  it('audience gate denies USER viewers from OPERATOR resources', async () => {
    __seedForTest(catalogOf(res('core.ops-only', { audience: 'OPERATOR' })));
    // Default viewer audience is USER (from the store).
    const el = await mountResource('core.ops-only');
    expect(el.querySelector('jf-resource-view')).toBeNull();
  });

  it('explicit viewer-audience override beats the store', async () => {
    __seedForTest(catalogOf(res('core.ops-only', { audience: 'OPERATOR' })));
    // Store default USER would deny; explicit OPERATOR override
    // qualifies. Track DD: hasAttribute('viewer-audience') is what
    // distinguishes "explicit" from "absent."
    const el = await mountResource('core.ops-only', 'list-item', 'OPERATOR');
    expect(el.querySelector('jf-resource-view')).not.toBeNull();
  });

  it('reads from store when attribute is absent (Track A)', async () => {
    __seedForTest(catalogOf(res('core.ops-only', { audience: 'OPERATOR' })));
    setViewerAudience('OPERATOR');
    const el = await mountResource('core.ops-only');
    expect(el.querySelector('jf-resource-view')).not.toBeNull();
  });

  it('548 §4.6 — a LIVE document-signal change drives a pinpoint re-render (no manual subscription)', async () => {
    // This is the §4.6 outcome: pinpoint DOM updates fall out of the §4.4 signal
    // graph. `getViewerAudience()` projects the §4.4 `docSig` (a computed over
    // the storage authority); JfResource extends SignalWatcher(LitElement) and
    // reads it in render(). Flipping the store WHILE the element is mounted
    // re-renders it via Lit's pinpoint diff — no subscribeProjection wiring on
    // the component.
    __seedForTest(catalogOf(res('core.ops-only', { audience: 'OPERATOR' })));
    setViewerAudience('USER');
    const el = await mountResource('core.ops-only'); // no explicit attribute → reads the store
    expect(el.querySelector('jf-resource-view')).toBeNull(); // USER denied

    setViewerAudience('OPERATOR'); // mutate the §4.4 document graph live
    await (el as unknown as { updateComplete: Promise<void> }).updateComplete;
    expect(el.querySelector('jf-resource-view')).not.toBeNull(); // now visible — pinpoint
  });
});
