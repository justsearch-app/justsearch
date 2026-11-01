// @vitest-environment happy-dom

/**
 * Render test for LibrarySurface empty-state — tempdoc 595 §4.3 (closes 1.2).
 *
 * A fresh load while the backend is mid-transition (worker restart / rebuild)
 * leaves `roots = []` (the 503 keeps the never-loaded initial value), which used
 * to render the catastrophe-reading "No watched folders". The surface now
 * consults the one Stability axis: while provisional, an empty list renders the
 * transition, not "no folders configured".
 */

import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import './LibrarySurface.js';
import { folderRowLabel, type LibrarySurface } from './LibrarySurface.js';
import { folderStatus } from '../state/folderStatus.js';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import {
  __resetAiStateForTest,
  __feedForTest,
  __tickClockForTest,
  type StatusSnapshot,
} from '../state/aiStateStore.js';

// A host whose fetch always 503s — reproduces the worker-down window (roots stay []).
const stubHost = {
  platform: { capabilities: new Set<string>() },
  data: { fetch: async () => ({ ok: false, status: 503 }) as unknown as Response },
} as unknown as PluginHostApi;

function feedRebuilding(): void {
  __feedForTest({
    status: {
      worker: {
        core: { indexedDocuments: 0, indexState: 'IDLE', indexHealthy: true },
        migration: {
          migrationState: 'MIGRATING',
          activeGenerationId: 'g1',
          buildingGenerationId: 'g2',
          servingSearchGenerationId: 'g1',
          servingIngestGenerationId: 'g1',
        },
      },
      readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } },
    } as unknown as StatusSnapshot,
  });
  __tickClockForTest();
}

async function mount(): Promise<LibrarySurface> {
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.host_ = stubHost;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('LibrarySurface — transition-aware empty state (595 §4.3)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    __resetAiStateForTest();
  });
  afterEach(() => __resetAiStateForTest());

  it('while the backend is rebuilding, an empty roots list renders the transition, NOT "No watched folders"', async () => {
    feedRebuilding();
    const el = await mount();
    try {
      await el.updateComplete;
      const text = el.shadowRoot?.textContent ?? '';
      expect(text).toContain('Rebuilding index');
      expect(text).not.toContain('No watched folders');
    } finally {
      el.remove();
    }
  });

  it('when settled with no folders, it still shows the add-a-folder headline when there are genuinely no watched folders', async () => {
    // settled store (no migration / IDLE)
    __feedForTest({
      status: {
        worker: {
          core: { indexedDocuments: 5, indexState: 'IDLE', indexHealthy: true },
          migration: {
            migrationState: 'IDLE',
            activeGenerationId: 'g1',
            buildingGenerationId: '',
            servingSearchGenerationId: 'g1',
            servingIngestGenerationId: 'g1',
          },
        },
        readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = await mount();
    try {
      await el.updateComplete;
      const text = el.shadowRoot?.textContent ?? '';
      expect(el.shadowRoot?.querySelector('h2')?.textContent).toBe('Add a folder to start searching');
      expect(text).not.toContain('Rebuilding index');
    } finally {
      el.remove();
    }
  });
});

/**
 * Tempdoc 813 remediation F1 — the PRE-POLL applicability sentinel, taken from the production path
 * rather than hand-made. `subscribeAiState` invokes its listener SYNCHRONOUSLY on subscribe, so the
 * very first value every mounted surface sees is the projection of a `null` status snapshot. That
 * value used to be an all-false applicability object, which reads as "no parent stage applies" —
 * and a root whose chunk coverage happened to be complete then rendered "fully searchable" off a
 * snapshot in which nothing had been observed at all. Unknown must stay unknown.
 */
describe('LibrarySurface — applicability before the first poll (813 F1)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    __resetAiStateForTest();
  });
  afterEach(() => __resetAiStateForTest());

  it('the synchronous first callback yields UNKNOWN stages, and no row claims a coverage tier', async () => {
    // No __feedForTest: this is the real pre-poll store state the listener fires with on mount.
    const el = await mount();
    try {
      await el.updateComplete;
      expect(el.enrichmentStages).toBeNull();
      // The surface feeds `folderStatus` BOTH gates (813 §4 coverage + 809 finding 1's index-wide
      // fallback). Pre-poll there is no positive evidence of pending work either, so the fallback
      // arm must not fire off an absent snapshot — unknown stays unknown on both inputs.
      expect(el.enrichmentPending).toBe(false);

      // The same value LibrarySurface hands folderStatus for every row it renders.
      const row = {
        pathHash: 'h',
        collection: 'default',
        fileCount: 312,
        lastIndexedIsoTime: '2026-08-06T00:00:00Z',
        status: 'indexed',
        walkError: '',
        inFlightCount: 0,
        failedCount: 0,
        walkCompleted: true,
        chunkDocsTotal: 100,
        chunkDocsSettled: 100,
      } as IndexedRootView;
      const fs = folderStatus(row, {
        relativeTime: 'just now',
        verifiedRelativeTime: '',
        provisional: false,
        enrichmentStages: el.enrichmentStages,
        enrichmentPending: el.enrichmentPending,
      });
      expect(fs.metaText).not.toContain('fully searchable');
      expect(fs.metaText).not.toContain('semantic search still catching up');
      expect(fs.metaText).not.toContain('%');
    } finally {
      el.remove();
    }
  });
});

/**
 * Tempdoc 804 §B9 (round-10 F8) — the INDEXED FOLDERS rows rendered `[b5ec60937d1a…]`, an opaque
 * path hash, beside a Remove button: the only action offered on a row the user cannot identify was
 * the destructive one. A row never names itself with a bare hex id.
 */
describe('LibrarySurface — a folder row never names itself with a hash (804 F8)', () => {
  const HASH = 'b5ec60937d1af0c2e4d9aa1177ce33bd';
  const BARE_HEX_ID = /\b[0-9a-f]{12,}\b/;

  beforeEach(() => {
    document.body.innerHTML = '';
    __resetAiStateForTest();
  });
  afterEach(() => __resetAiStateForTest());

  it('renders the folder name with the full path on hover once the hash resolves', () => {
    const { label, title } = folderRowLabel(HASH, 'C:\\Users\\me\\Documents\\seed-corpus');
    expect(label).toBe('seed-corpus');
    expect(label).not.toMatch(BARE_HEX_ID);
    expect(title).toBe('C:\\Users\\me\\Documents\\seed-corpus');
  });

  it('says so in words when the path is unresolved — the hash is never the label', () => {
    const { label, title } = folderRowLabel(HASH, undefined);
    expect(label).not.toMatch(BARE_HEX_ID);
    expect(label).toContain('unavailable');
    // The id stays reachable as diagnostic detail on hover, not as the row's name.
    expect(title).toContain(HASH.slice(0, 12));
  });

  it('renders the resolved name in the card DOM (not the hash)', async () => {
    const el = document.createElement('jf-library-surface') as LibrarySurface;
    // The card renderer projects its meta line through host utilities (folderStatus), so this test
    // needs the fuller host the empty-state tests never reach.
    el.host_ = {
      platform: { capabilities: new Set<string>() },
      data: { fetch: async () => ({ ok: false, status: 503 }) as unknown as Response },
      utilities: { formatRelativeTime: () => 'just now' },
    } as unknown as PluginHostApi;
    document.body.appendChild(el);
    await el.updateComplete;
    try {
      el.roots = [
        {
          pathHash: HASH,
          collection: 'default',
          fileCount: 400,
          lastIndexedIsoTime: '',
          status: 'indexed',
        },
      ];
      el.resolvedPaths = { [HASH]: '/home/me/Documents/seed-corpus' };
      el.requestUpdate();
      await el.updateComplete;

      const name = el.shadowRoot?.querySelector('[data-testid="library-folder-name"]');
      expect(name).not.toBeNull();
      expect(name?.textContent?.trim()).toBe('seed-corpus');
      expect(name?.getAttribute('title')).toBe('/home/me/Documents/seed-corpus');
      el.shadowRoot?.querySelector<HTMLButtonElement>('.folder-toggle')?.click();
      await el.updateComplete;
      // The row still carries a Remove action — it just now names what it would remove.
      expect(el.shadowRoot?.textContent ?? '').toContain('Remove');
      expect(name?.textContent ?? '').not.toMatch(BARE_HEX_ID);
    } finally {
      el.remove();
    }
  });
});

/**
 * 809 finding 1 — the WIRING half of the coverage gate. `folderStatus` decides the claim, but only if
 * the surface actually hands it the coverage fact: a seam that is correct while its one caller passes
 * a constant would leave the defect exactly where it was. This drives the real store (a status frame
 * with a passage-level backfill in flight) through to the rendered row.
 */
describe('LibrarySurface — the folder row consults enrichment coverage (809 finding 1)', () => {
  const HASH = 'c7dd41a900bb2f4e8a1c33ee55aa7719';

  const settledWorker = {
    core: { indexedDocuments: 400, indexState: 'IDLE', indexHealthy: true },
    migration: {
      migrationState: 'IDLE',
      activeGenerationId: 'g1',
      buildingGenerationId: '',
      servingSearchGenerationId: 'g1',
      servingIngestGenerationId: 'g1',
    },
  };

  function feedEnrichment(enrichment: Record<string, unknown>): void {
    __feedForTest({
      status: {
        worker: { ...settledWorker, enrichment },
        readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
  }

  const DRAINED = {
    embeddingEnabled: true,
    spladeEnabled: true,
    nerEnabled: true,
    embeddingPendingCount: 0,
    spladePendingCount: 0,
    pendingNerCount: 0,
    chunk: { chunkEmbeddingPendingCount: 0, chunkVectorsReady: true },
  };

  beforeEach(() => {
    document.body.innerHTML = '';
    __resetAiStateForTest();
  });
  afterEach(() => __resetAiStateForTest());

  async function mountWithRow(): Promise<LibrarySurface> {
    const el = document.createElement('jf-library-surface') as LibrarySurface;
    el.host_ = {
      platform: { capabilities: new Set<string>() },
      data: { fetch: async () => ({ ok: false, status: 503 }) as unknown as Response },
      utilities: { formatRelativeTime: () => 'just now' },
    } as unknown as PluginHostApi;
    document.body.appendChild(el);
    await el.updateComplete;
    el.roots = [
      {
        pathHash: HASH,
        collection: 'default',
        fileCount: 400,
        lastIndexedIsoTime: '2026-08-06T00:00:00Z',
        status: 'indexed',
        inFlightCount: 0,
        failedCount: 0,
        walkCompleted: true,
      },
    ];
    el.resolvedPaths = { [HASH]: '/home/me/Documents/seed-corpus' };
    el.requestUpdate();
    await el.updateComplete;
    el.shadowRoot?.querySelector<HTMLButtonElement>('.folder-toggle')?.click();
    await el.updateComplete;
    return el;
  }

  it('a drained folder does NOT claim completion while the passage backfill is running', async () => {
    // 809 finding 9's trap shape: the doc-level counters are clean, the passage tier is not.
    feedEnrichment({
      ...DRAINED,
      embeddingCoveragePercent: 100,
      chunk: { chunkEmbeddingPendingCount: 1554, chunkVectorsReady: false },
    });
    const el = await mountWithRow();
    try {
      const text = el.shadowRoot?.textContent ?? '';
      expect(el.shadowRoot?.querySelector('.folder-details')?.textContent).toMatch(/Searchable by words\s*Now/);
      expect(text).toContain('Meaning processing');
      expect(el.shadowRoot?.querySelector('.folder-state')?.textContent).not.toContain('Up to date');
    } finally {
      el.remove();
    }
  });

  it('ANTI-REGRESSION: with the backfill drained the row makes its terminal claim again', async () => {
    feedEnrichment(DRAINED);
    const el = await mountWithRow();
    try {
      const text = el.shadowRoot?.textContent ?? '';
      expect(el.shadowRoot?.querySelector('.folder-state')?.textContent).toContain('Up to date');
      expect(el.shadowRoot?.querySelector('.folder-details')?.textContent).toMatch(/Indexed\s*just now/);
      expect(text).not.toContain('semantic search still catching up');
    } finally {
      el.remove();
    }
  });

  /**
   * Round-15 F1b, at the WIRING altitude the seam test cannot reach: the row's blocked caveat only
   * appears if this surface actually projects `selectIndexingProgress(...).phase === 'blocked'` into
   * the seam. Enrichment block verbatim from the round's captured `/api/status`
   * (`evidence/api-history/20260807-011454/api-api-status.json`).
   */
  it('F1b: with no embedding model the row is qualified rather than plainly "Verified"', async () => {
    feedEnrichment({
      embeddingEnabled: false,
      spladeEnabled: false,
      nerEnabled: false,
      embeddingDocCount: 5,
      embeddingPendingCount: 5,
      embeddingCoveragePercent: 0,
      spladeDocCount: 5,
      spladePendingCount: 5,
      spladeCoveragePercent: 0,
      pendingNerCount: 5,
      completedNerCount: 0,
      chunk: { chunkDocCount: 2, chunkEmbeddingPendingCount: 2, chunkVectorsReady: false },
    });
    const el = await mountWithRow();
    try {
      expect(el.enrichmentBlocked).toBe(true);
      const text = el.shadowRoot?.textContent ?? '';
      expect(text).toContain('semantic search waiting for AI install');
      expect(text).not.toContain('fully searchable');
    } finally {
      el.remove();
    }
  });

  it('F1-repro BOUNDARY: model gone but vectors persisted ⟹ no caveat is added', async () => {
    feedEnrichment({
      embeddingEnabled: false,
      spladeEnabled: false,
      nerEnabled: false,
      embeddingDocCount: 400,
      embeddingPendingCount: 0,
      embeddingCoveragePercent: 100,
      spladeDocCount: 400,
      spladePendingCount: 0,
      completedNerCount: 400,
      pendingNerCount: 0,
      chunk: { chunkDocCount: 120, chunkEmbeddingPendingCount: 0, chunkVectorsReady: true },
    });
    const el = await mountWithRow();
    try {
      expect(el.enrichmentBlocked).toBe(false);
      const text = el.shadowRoot?.textContent ?? '';
      expect(el.shadowRoot?.querySelector('.folder-state')?.textContent).toContain('Up to date');
      expect(el.shadowRoot?.querySelector('.folder-details')?.textContent).toMatch(/Indexed\s*just now/);
      expect(text).not.toContain('semantic search waiting for AI install');
    } finally {
      el.remove();
    }
  });
});

// Upgrade U15/U16: the root row consumes the same retry-wait verdict as the status deck.
it('library rows describe retry waiting without claiming active indexing', async () => {
  __resetAiStateForTest();
  const retry = Date.now() + 60_000;
  __feedForTest({ status: { worker: {
    core: { indexState: 'WAITING', pendingJobs: 8, indexedDocuments: 37, indexHealthy: true },
    failure: { nextRetryAtMs: retry },
  } } as unknown as StatusSnapshot });
  __tickClockForTest();
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.host_ = { ...stubHost, utilities: { formatRelativeTime: () => 'just now' } } as unknown as PluginHostApi;
  document.body.appendChild(el);
  await el.updateComplete;
  try {
    const root = { pathHash: 'wait-root', collection: 'notes', fileCount: 37,
      indexed: true, inFlightCount: 8, failedCount: 0 } as IndexedRootView;
    el.roots = [root];
    await el.updateComplete;
    const text = el.shadowRoot?.querySelector('.folder-state')?.textContent ?? '';
    expect(text).toContain('Waiting to retry');
    expect(text).toContain('8 remaining');
    expect(text).toContain(new Date(retry).toLocaleString());
    expect(text).not.toContain('Indexing');
  } finally {
    el.remove();
    __resetAiStateForTest();
  }
});
