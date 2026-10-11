// @vitest-environment happy-dom

/**
 * Tempdoc 811 C-2a (the held half) — the Library's "Other sources" section.
 *
 * Documents ingested from a path under no watched root are tagged with a collection, pill-labelled
 * in results (#372) and removable by `DELETE /api/indexing/collections` (#380) — but the Library
 * listed only watched roots, so the user could neither see them nor reach the removal. These tests
 * pin the surface half end-to-end: the enumeration probe populates rows, reserved corpora never
 * appear, removal confirms before it deletes, and nothing renders when there is nothing to say.
 */

import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import './LibrarySurface.js';
import type { LibrarySurface } from './LibrarySurface.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import {
  __resetAiStateForTest,
  __feedForTest,
  __tickClockForTest,
  type StatusSnapshot,
} from '../state/aiStateStore.js';

interface FetchCall {
  path: string;
  method: string;
  body: unknown;
}

function jsonResponse(payload: unknown): Response {
  return { ok: true, status: 200, json: async () => payload } as unknown as Response;
}

type Probe = {
  collectionFacet?: Record<string, number>;
  facetsTruncated?: boolean;
  confirm?: boolean;
  /** Watched-root rows the substrate endpoint answers with (their `collection` is subtracted). */
  roots?: Array<Record<string, unknown>>;
  calls: FetchCall[];
};

/**
 * The host seam the surface uses for its own endpoints (roots, settings, the collection DELETE).
 * The enumeration probe does NOT come through here — it is issued by `searchState.fetchCollectionFacet`,
 * the ONE `/api/knowledge/collection-facet` site, which goes out through the global fetch (see {@link stubProbe}).
 */
function makeHost(opts: Probe): PluginHostApi {
  return {
    platform: { capabilities: new Set<string>() },
    utilities: { formatRelativeTime: () => 'just now' },
    ui: { showConfirmDialog: vi.fn(async () => opts.confirm !== false) },
    data: {
      fetch: async (path: string, init?: { method?: string; body?: string }) => {
        opts.calls.push({
          path,
          method: init?.method ?? 'GET',
          body: init?.body ? JSON.parse(init.body) : undefined,
        });
        if (path.startsWith('/api/indexing-roots/substrate')) {
          return jsonResponse({ items: opts.roots ?? [] });
        }
        if (path.startsWith('/api/indexing/collections')) {
          return jsonResponse({ status: 'ok', deletedDocs: 12 });
        }
        return jsonResponse({});
      },
    },
  } as unknown as PluginHostApi;
}

/** Answers the enumeration probe with the `facets.collection` map a real search would carry. */
function stubProbe(opts: Probe): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: { method?: string; body?: string }) => {
      opts.calls.push({
        path: String(url),
        method: init?.method ?? 'GET',
        body: init?.body ? JSON.parse(init.body) : undefined,
      });
      return jsonResponse({
        totalHits: 0,
        results: [],
        facets: { collection: opts.collectionFacet ?? {} },
        facetsTruncated: opts.facetsTruncated === true,
      });
    }) as unknown as typeof globalThis.fetch,
  );
}

/** Mount and let the mount-time fetch chain (roots → other sources) settle. */
async function mount(host: PluginHostApi): Promise<LibrarySurface> {
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.host_ = host;
  document.body.appendChild(el);
  for (let i = 0; i < 8; i++) {
    await el.updateComplete;
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  await el.updateComplete;
  return el;
}

/** Section copy with template line-wrapping collapsed, so assertions read as the user sees it. */
function sectionText(el: LibrarySurface): string {
  const section = el.shadowRoot?.querySelector('[data-testid="library-other-sources"]');
  return (section?.textContent ?? '').replace(/\s+/g, ' ').trim();
}

function sourceNames(el: LibrarySurface): string[] {
  return [...(el.shadowRoot?.querySelectorAll('[data-testid="library-other-source-name"]') ?? [])]
    .map((n) => n.textContent?.trim() ?? '');
}

/** Stub both transports (host seam + the probe's global fetch), mount, and settle. */
async function setup(
  opts: Omit<Probe, 'calls'> = {},
): Promise<{ el: LibrarySurface; host: PluginHostApi; calls: FetchCall[] }> {
  const calls: FetchCall[] = [];
  const probe: Probe = { ...opts, calls };
  stubProbe(probe);
  const host = makeHost(probe);
  const el = await mount(host);
  return { el, host, calls };
}

/** The Remove control for a source row, addressed the way a user would: by what it says it does. */
function removeButton(
  el: LibrarySurface,
  collection: string,
): (HTMLElement & { onActivate: () => void }) | null {
  return el.shadowRoot?.querySelector(
    `jf-button[label="Remove ${collection}"]`,
  ) as (HTMLElement & { onActivate: () => void }) | null;
}

describe('LibrarySurface — "Other sources" section (811 C-2a)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    __resetAiStateForTest();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    __resetAiStateForTest();
  });

  it('renders a row per non-root collection with its document count', async () => {
    const { el, calls } = await setup({ collectionFacet: { 'mcp-ingest': 12, notes: 3 } });
    try {
      expect(sourceNames(el)).toEqual(['mcp-ingest', 'notes']);
      const text = sectionText(el);
      expect(text).toContain('Other sources');
      expect(text).toContain('12 documents');
      expect(text).toContain('3 documents');
      // The enumeration went over the wire — the section is not seeded from anything local.
      expect(calls.some((c) => c.path.endsWith('/api/knowledge/collection-facet') && c.method === 'POST')).toBe(
        true,
      );
    } finally {
      el.remove();
    }
  });

  it('never lists the reserved app-internal collections', async () => {
    const { el } = await setup({
      collectionFacet: { 'justsearch-help': 240, 'agent-history': 9, 'mcp-ingest': 1 },
    });
    try {
      expect(sourceNames(el)).toEqual(['mcp-ingest']);
      expect(sectionText(el)).not.toContain('justsearch-help');
      expect(sectionText(el)).not.toContain('agent-history');
      // Singular count, since the one row has one document.
      expect(sectionText(el)).toContain('1 document');
    } finally {
      el.remove();
    }
  });

  it('never lists a collection a watched folder already owns', async () => {
    // The WIRING half of the subtraction: the surface must hand the roots' collections to the
    // derivation. A seam that filters correctly while its one caller passes nothing is still a bug.
    const { el } = await setup({
      collectionFacet: { 'work-notes': 40, 'mcp-ingest': 3 },
      roots: [
        {
          pathHash: 'h1',
          collection: 'work-notes',
          fileCount: 40,
          lastIndexedIsoTime: '2026-08-06T00:00:00Z',
          status: 'indexed',
        },
      ],
    });
    try {
      expect(sourceNames(el)).toEqual(['mcp-ingest']);
    } finally {
      el.remove();
    }
  });

  it('renders NO section at all when there are no non-root collections', async () => {
    const { el } = await setup({ collectionFacet: { default: 900 } });
    try {
      expect(el.shadowRoot?.querySelector('[data-testid="library-other-sources"]')).toBeNull();
      expect(el.shadowRoot?.textContent ?? '').not.toContain('Other sources');
    } finally {
      el.remove();
    }
  });

  it('says the list may be incomplete when the facet scan truncated', async () => {
    // Truncation OMITS collections rather than undercounting them, so silence would reproduce the
    // very invisibility this section exists to end.
    const { el } = await setup({ collectionFacet: {}, facetsTruncated: true });
    try {
      expect(sectionText(el)).toContain('may be missing sources');
    } finally {
      el.remove();
    }
  });

  it('re-probes with a bigger scan once a poll reveals a larger index', async () => {
    // The FIRST probe necessarily runs before the first status poll (roots are fetched on connect),
    // so it is sized at the engine floor. On a corpus larger than that floor the scan omits
    // collections outright — leaving the short list on screen would be the invisibility bug again.
    const { el, calls } = await setup({ collectionFacet: { 'mcp-ingest': 3 } });
    const probes = () =>
      calls
        .filter((c) => c.path.endsWith('/api/knowledge/collection-facet'))
        .map((c) => (c.body as { maxDocsScanned: number }).maxDocsScanned);
    try {
      expect(probes()).toEqual([50_000]);

      __feedForTest({
        status: {
          worker: {
            core: {
              indexedDocuments: 900_000,
              searchableDocuments: 900_000,
              indexState: 'IDLE',
              indexHealthy: true,
            },
          },
          readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } },
        } as unknown as StatusSnapshot,
      });
      __tickClockForTest();
      for (let i = 0; i < 4; i++) {
        await el.updateComplete;
        await new Promise((resolve) => setTimeout(resolve, 0));
      }

      const scans = probes();
      expect(scans.length).toBe(2);
      expect(scans[1]).toBeGreaterThan(900_000);
    } finally {
      el.remove();
    }
  });

  it('confirms first, then issues the DELETE for that collection', async () => {
    const { el, host, calls } = await setup({ collectionFacet: { 'mcp-ingest': 12 } });
    try {
      const remove = removeButton(el, 'mcp-ingest');
      expect(remove).not.toBeNull();
      remove?.onActivate();
      await new Promise((resolve) => setTimeout(resolve, 0));

      const confirm = host.ui.showConfirmDialog as unknown as ReturnType<typeof vi.fn>;
      expect(confirm).toHaveBeenCalledTimes(1);
      const message = confirm.mock.calls[0]?.[0] as string;
      expect(message).toContain('12 documents');
      expect(message).toContain("'mcp-ingest'");
      expect(confirm.mock.calls[0]?.[1]).toMatchObject({ destructive: true });

      const del = calls.find((c) => c.path === '/api/indexing/collections');
      expect(del?.method).toBe('DELETE');
      expect(del?.body).toEqual({ collection: 'mcp-ingest' });
    } finally {
      el.remove();
    }
  });

  it('issues nothing when the confirm is declined', async () => {
    const { el, host, calls } = await setup({ collectionFacet: { 'mcp-ingest': 12 }, confirm: false });
    try {
      removeButton(el, 'mcp-ingest')?.onActivate();
      await new Promise((resolve) => setTimeout(resolve, 0));

      expect(host.ui.showConfirmDialog).toHaveBeenCalledTimes(1);
      expect(calls.some((c) => c.path === '/api/indexing/collections')).toBe(false);
    } finally {
      el.remove();
    }
  });
});
