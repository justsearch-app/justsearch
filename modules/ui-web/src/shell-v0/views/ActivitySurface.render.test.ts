// @vitest-environment happy-dom

/**
 * ActivitySurface render test — 727 F-4.
 *
 * Pre-fix, the operation-history panel was mounted with no `api-base`, so its
 * `core.operation-history` stream fell back to relative URLs — wrong whenever the FE and backend
 * are served from different origins. The one feed now reads that history itself; these tests pin
 * that it reads it, and the ledger, from the `api-base` ActivitySurface was given.
 */

import { describe, expect, it, afterEach, beforeAll, vi } from 'vitest';
import { addressActivitySection, requestActivitySection, takeActivitySection } from '../state/activitySection.js';

/** A pointer's navigation, applied: it lands on the page these Activities are on. */
const landOnPage = (): void => addressActivitySection(() => document.body);
import {
  __resetForTest as resetResourceCatalog,
  __seedForTest as seedResourceCatalog,
} from '../../api/registry/ResourceCatalogClient.js';
import type { Resource } from '../../api/types/registry.js';

// Stub EventSource before ActivitySurface's transitive imports (ActionLedgerView) try to open real
// streams against a non-existent backend.
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

const requested: string[] = [];

beforeAll(() => {
  vi.stubGlobal(
    'EventSource',
    function StubES(url: string | URL) {
      return new NoopEventSource(url) as unknown as EventSource;
    },
  );
  // Tempdoc 804 §B9 (F9): <jf-action-ledger> also READS `GET /api/action-ledger`. Stub every read;
  // this test is about which origin the reads go to, not their content.
  vi.stubGlobal(
    'fetch',
    async (input: RequestInfo | URL) => {
      requested.push(String(input));
      return { ok: true, status: 200, json: async () => ({ entries: [] }), body: null } as unknown as Response;
    },
  );
});

import './ActivitySurface.js';
import type { ActivitySurface } from './ActivitySurface.js';

function operationHistoryResource(): Resource {
  return {
    id: 'core.operation-history',
    presentation: { labelKey: 'registry-resource.operation-history.label',
      descriptionKey: 'registry-resource.operation-history.description', iconHint: null, category: null },
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
    primaryKey: 'operationKey',
    audience: 'USER',
    consumers: [],
    role: 'PRODUCT',
  };
}

async function mount(apiBase: string): Promise<ActivitySurface> {
  const el = document.createElement('jf-activity-surface') as ActivitySurface;
  el.apiBase = apiBase;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('ActivitySurface (jf-activity-surface) — api-base forwarding (727 F-4)', () => {
  afterEach(() => {
    document.body.innerHTML = '';
    resetResourceCatalog();
    requested.length = 0;
  });

  it('the one feed reads the ledger and the operation history from its own api-base', async () => {
    seedResourceCatalog({ schemaVersion: '1.0', catalogVersion: 1, namespace: 'core', primitive: 'Resource',
      entries: [operationHistoryResource()] });
    const el = await mount('http://127.0.0.1:33221');
    const ledger = el.shadowRoot?.querySelector('jf-action-ledger');
    expect(ledger?.getAttribute('api-base')).toBe('http://127.0.0.1:33221');
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(requested).toContain('http://127.0.0.1:33221/api/action-ledger');
    expect(requested).toContain('http://127.0.0.1:33221/api/operation-history/stream');
    // One list: the separate operations table is gone, its records are in the feed.
    expect(el.shadowRoot?.querySelector('jf-resource')).toBeNull();
  });
});

describe('Activity holds what the frame drawers held (DP3)', () => {
  const FEED_SCOPE = '[data-testid="feed-scope"] button';

  it('shows background runs and the one feed; the journal and This run are scopes of the feed, not lists of their own', async () => {
    const el = document.createElement('jf-activity-surface') as ActivitySurface;
    document.body.append(el);
    await el.updateComplete;
    expect(el.shadowRoot!.querySelector('jf-run-activity')).not.toBeNull();
    expect(el.shadowRoot!.querySelector('jf-action-ledger')).not.toBeNull();
    expect(el.shadowRoot!.querySelector('jf-effect-audit-log')).toBeNull();
    const runs = el.shadowRoot!.querySelector('jf-run-activity') as HTMLElement & { updateComplete: Promise<unknown> };
    await runs.updateComplete;
    expect(runs.shadowRoot!.querySelector('[data-testid="activity-this-run"]')).toBeNull();
    expect(el.shadowRoot!.querySelector('dialog')).toBeNull();
    el.remove();
  });

  it.each([
    ['background-runs', 'jf-run-activity', '[data-testid="activity-background-runs"]'],
    ['effect-journal', 'jf-action-ledger', FEED_SCOPE],
  ] as const)('a pointer to %s lands on that section, whether Activity is up yet or not', async (section, owner, selector) => {
    requestActivitySection(section);
    landOnPage();
    const el = document.createElement('jf-activity-surface') as ActivitySurface;
    document.body.append(el);
    await el.updateComplete;
    const child = el.shadowRoot!.querySelector(owner) as HTMLElement & { updateComplete: Promise<unknown> };
    await child.updateComplete;
    await new Promise(resolve => setTimeout(resolve, 0));
    await child.updateComplete;
    expect(child.shadowRoot!.querySelector(selector), 'the section the pointer names is there').not.toBeNull();
    expect(child.shadowRoot!.activeElement).toBe(child.shadowRoot!.querySelector(selector));
    expect(takeActivitySection(el)).toBeNull();
    (child.shadowRoot!.activeElement as HTMLElement).blur();
    requestActivitySection(section);
    landOnPage();
    await new Promise(resolve => setTimeout(resolve, 0));
    await child.updateComplete;
    expect(child.shadowRoot!.activeElement).toBe(child.shadowRoot!.querySelector(selector));
    el.remove();
  });

  it('an Activity closing before it renders leaves the request to another Activity, which reveals it', async () => {
    const leaving = document.createElement('jf-activity-surface') as ActivitySurface;
    const staying = document.createElement('jf-activity-surface') as ActivitySurface;
    document.body.append(leaving, staying);
    await leaving.updateComplete;
    await staying.updateComplete;
    requestActivitySection('effect-journal');
    landOnPage();
    leaving.remove();
    const feed = staying.shadowRoot!.querySelector('jf-action-ledger') as HTMLElement & { updateComplete: Promise<unknown> };
    await feed.updateComplete;
    await new Promise(resolve => setTimeout(resolve, 0));
    await feed.updateComplete;
    expect(feed.shadowRoot!.querySelector(FEED_SCOPE), 'the feed is scoped to this window').not.toBeNull();
    expect(feed.shadowRoot!.activeElement).toBe(feed.shadowRoot!.querySelector(FEED_SCOPE));
    expect(takeActivitySection(staying)).toBeNull();
    staying.remove();
  });

  it('arriving at the journal clears a filter that would hide what the pointer named', async () => {
    const el = document.createElement('jf-activity-surface') as ActivitySurface;
    document.body.append(el);
    await el.updateComplete;
    const feed = el.shadowRoot!.querySelector('jf-action-ledger') as HTMLElement & { updateComplete: Promise<unknown>;
      filter: Record<string, unknown> };
    feed.filter = { ...feed.filter, quick: 'problems', originators: ['system'], proposals: ['rejected'], date: 'today' };
    requestActivitySection('effect-journal');
    landOnPage();
    await new Promise(resolve => setTimeout(resolve, 0));
    await feed.updateComplete;
    expect(feed.filter).toEqual({ quick: 'all', originators: [], outcomes: [], proposals: [], routine: 'auto', date: 'all', scope: 'local' });
    el.remove();
  });
});
