// @vitest-environment happy-dom
//
// Tempdoc 599 §16/B1 — a folder's failed files, inline under the folder. A real FAILED indexing job
// could not be manufactured live (read-deny / zero-byte files still extracted cleanly), so this drives
// the list's load → render → retry path deterministically.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './FailedFilesList.js';
import type { FailedFilesList } from './FailedFilesList.js';
import { EPHEMERAL_TOAST_EVENT, type EphemeralToastSpec } from './advisory/ephemeralToast.js';
import { isDevMode } from '../../api/devMode.js';
import { readWireDrift } from '../../api/wireDriftTelemetry.js';

// The posture seam (tempdoc 683, mirrored from api/schemas.test.ts): parseWireContract THROWS in dev
// (the vitest default) but DEGRADES in prod. The prod case has its own describe block at the bottom.
vi.mock('../../api/devMode.js', { spy: true });

/**
 * Tempdoc 911 (885 UL.9) — every fixture below is SCHEMA-SHAPED: the by-prefix wire is now the
 * generated `FailedIndexingJobsResponse` contract (`SSOT/schemas/failed-indexing-jobs-response.v1.json`,
 * all eight IndexingJobView fields required + non-null), and the drawer parses through it. A
 * hand-trimmed fixture would no longer be a smaller version of the wire — it would be a body the
 * backend cannot send, so a test written on one proves nothing about the real surface.
 */
function job(over: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
  return {
    pathHash: 'h-one',
    state: 'FAILED',
    attempts: 3,
    lastUpdatedMs: 1_700_000_000_000,
    errorMessage: '',
    retryAfterMs: 0,
    collection: 'default',
    scanId: '',
    ...over,
  };
}

function byPrefixBody(jobs: Array<Record<string, unknown>>): string {
  return JSON.stringify({ jobs, count: jobs.length });
}

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

/** Drive the element's open + load path, pumping microtasks so the async refresh() settles. */
async function pump(el: Element): Promise<void> {
  for (let i = 0; i < 30; i += 1) {
    await Promise.resolve();
    await settle(el);
  }
}

describe('FailedFilesList', () => {
  it('loads the folder-scoped failed jobs and renders one row per file with its error + Retry', async () => {
    const origFetch = globalThis.fetch;
    globalThis.fetch = (async (url: unknown) => {
      const u = String(url);
      if (u.includes('/api/indexing-jobs/failed/by-prefix')) {
        return new Response(
          byPrefixBody([
            job({ pathHash: 'h-one', errorMessage: 'parse error: unexpected EOF' }),
            job({ pathHash: 'h-two', errorMessage: 'extraction timed out' }),
          ]),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch;
    try {
      const el = document.createElement('jf-failed-files-list') as FailedFilesList;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      el.folderPathHash = 'folder-hash';
      await pump(el);

      const rows = el.shadowRoot?.querySelectorAll('.row') ?? [];
      expect(rows.length).toBe(2);
      const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
      expect(text).toContain('parse error: unexpected EOF');
      expect(text).toContain('extraction timed out');
      expect(text).toContain('Try again'); // the batch affordance appears when rows>0
      // Tempdoc 599 §16.1 Move 1 / §17.2 — per-row actions REUSE the shared <jf-row-actions> over the
      // failed-jobs Resource (not a hand-rolled button), keyed by the row's pathHash.
      const rowActions = el.shadowRoot?.querySelectorAll('jf-row-actions') ?? [];
      expect(rowActions.length).toBe(2);
      expect(rowActions[0]?.getAttribute('resource-id')).toBe('core.failed-indexing-jobs');
      expect(rowActions[0]?.getAttribute('row-key')).toBe('h-one');
      el.remove();
    } finally {
      globalThis.fetch = origFetch;
    }
  });

  it('shows the empty state when the folder has no failed files', async () => {
    const origFetch = globalThis.fetch;
    globalThis.fetch = (async (url: unknown) => {
      if (String(url).includes('/api/indexing-jobs/failed/by-prefix')) {
        return new Response(byPrefixBody([]), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch;
    try {
      const el = document.createElement('jf-failed-files-list') as FailedFilesList;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      el.folderPathHash = 'empty-folder';
      await pump(el);

      const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
      expect(text).toContain('No failed files in this folder.');
      expect(text).not.toContain('Retry all');
      el.remove();
    } finally {
      globalThis.fetch = origFetch;
    }
  });


  it('a RETRY_EXHAUSTED row reads as "gave up", a FAILED row does not (885 item 21b)', async () => {
    // The two terminal states arrive on the SAME listing and were previously indistinguishable:
    // the drawer read only `errorMessage`, so "we retried for a week and never got to read it"
    // rendered as whichever transient error happened last — i.e. as a verdict about the file.
    const origFetch = globalThis.fetch;
    globalThis.fetch = (async (url: unknown) => {
      if (String(url).includes('/api/indexing-jobs/failed/by-prefix')) {
        return new Response(
          byPrefixBody([
            job({
              pathHash: 'h-bad',
              state: 'FAILED',
              errorMessage: 'parse error: unexpected EOF',
            }),
            job({
              pathHash: 'h-gone',
              state: 'RETRY_EXHAUSTED',
              errorMessage: 'extraction timed out',
              attempts: 41,
            }),
          ]),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch;
    try {
      const el = document.createElement('jf-failed-files-list') as FailedFilesList;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      el.folderPathHash = 'folder-hash';
      await pump(el);

      const rows = Array.from(el.shadowRoot?.querySelectorAll('.row') ?? []);
      expect(rows.length).toBe(2);
      const failedRow = rows[0]!;
      const exhaustedRow = rows[1]!;
      // Keyed on the row that carries the state, not on document order alone.
      expect(failedRow.getAttribute('data-state')).toBe('FAILED');
      expect(exhaustedRow.getAttribute('data-state')).toBe('RETRY_EXHAUSTED');

      const gaveUp = exhaustedRow.querySelector('[data-testid="failed-job-exhausted"]');
      expect(gaveUp, 'an exhausted row must say what happened').toBeTruthy();
      const gaveUpText = (gaveUp!.textContent ?? '').replace(/\s+/g, ' ').trim();
      expect(gaveUpText).toContain('Gave up after 7 days');
      // …and what makes the queue try again — the state is reset by anything that re-enqueues the
      // path, so the remedy is a rescan or an edit, not just pressing Retry harder.
      expect(gaveUpText).toContain('rescan');
      // The underlying error stays visible as DETAIL beneath it, not as the explanation.
      expect((exhaustedRow.textContent ?? '')).toContain('extraction timed out');

      // The parse failure is untouched: no "gave up" line, because the file really is unreadable.
      expect(failedRow.querySelector('[data-testid="failed-job-exhausted"]')).toBeNull();
      expect((failedRow.textContent ?? '')).toContain('parse error: unexpected EOF');
      el.remove();
    } finally {
      globalThis.fetch = origFetch;
    }
  });

  it('an indexed file whose source cannot be read reads as "indexed, source unreadable", not missing (t-20261013-ef3c41 S7)', async () => {
    // A locked or access-denied file keeps its indexed content and is retried without end. The
    // listing carries the reason as the row state; the row must say the content is still
    // searchable and must not read as a broken, missing or abandoned file.
    const origFetch = globalThis.fetch;
    globalThis.fetch = (async (url: unknown) => {
      if (String(url).includes('/api/indexing-jobs/failed/by-prefix')) {
        return new Response(
          byPrefixBody([
            job({
              pathHash: 'h-locked',
              state: 'INDEXED_SOURCE_UNREADABLE',
              errorMessage: 'The process cannot access the file because it is being used by another process',
              attempts: 4,
            }),
            job({ pathHash: 'h-new-locked', state: 'SOURCE_UNREADABLE', attempts: 2 }),
          ]),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch;
    try {
      const el = document.createElement('jf-failed-files-list') as FailedFilesList;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      el.folderPathHash = 'folder-hash';
      await pump(el);

      const rows = Array.from(el.shadowRoot?.querySelectorAll('.row') ?? []);
      expect(rows.length).toBe(2);
      const indexedRow = rows[0]!;
      const notYetIndexedRow = rows[1]!;
      expect(indexedRow.getAttribute('data-state')).toBe('INDEXED_SOURCE_UNREADABLE');

      const line = indexedRow.querySelector('[data-testid="failed-job-source-unreadable"]');
      expect(line, 'an unreadable indexed source must say so').toBeTruthy();
      const text = (line!.textContent ?? '').replace(/\s+/g, ' ').trim();
      expect(text).toContain('Indexed, source unreadable');
      expect(text).toContain('stays searchable');
      expect(text).not.toMatch(/missing|deleted/i);
      expect(indexedRow.querySelector('[data-testid="failed-job-exhausted"]')).toBeNull();

      const other = notYetIndexedRow.querySelector('[data-testid="failed-job-source-unreadable"]');
      expect(other, 'a never-indexed unreadable source is unreadable too').toBeTruthy();
      const otherText = (other!.textContent ?? '').replace(/\s+/g, ' ').trim();
      expect(otherText).toContain('Source unreadable');
      expect(otherText).not.toContain('stays searchable');
      el.remove();
    } finally {
      globalThis.fetch = origFetch;
    }
  });

  it('renders the owning scan id per row, and omits the line for a row with no scan (941 F2)', async () => {
    // Tempdoc 911 plumbed `scanId` all the way to this wire; the drawer never rendered it, so a
    // failed row could not be tied to the scan that produced it. `""` is the contract's declared
    // "no owning scan" (single-file ingest / watcher / pre-scan row) — that row must render NO scan
    // line rather than an empty or invented one, which is the half this test exists to pin.
    const origFetch = globalThis.fetch;
    globalThis.fetch = (async (url: unknown) => {
      if (String(url).includes('/api/indexing-jobs/failed/by-prefix')) {
        return new Response(
          byPrefixBody([
            job({ pathHash: 'h-scan', scanId: '959dd932-4c1e-4a77-9f0e-2b3c4d5e6f70' }),
            job({ pathHash: 'h-noscan', scanId: '' }),
          ]),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch;
    try {
      const el = document.createElement('jf-failed-files-list') as FailedFilesList;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      el.folderPathHash = 'folder-hash';
      await pump(el);

      const rows = Array.from(el.shadowRoot?.querySelectorAll('.row') ?? []);
      expect(rows.length).toBe(2);
      const scanLine = rows[0]!.querySelector('[data-testid="failed-job-scan"]');
      expect(scanLine, 'a row carrying a scanId must show it').toBeTruthy();
      expect((scanLine!.textContent ?? '').replace(/\s+/g, ' ').trim()).toBe('Scan 959dd932…');
      // Truncated for the row, but the full id stays recoverable without another round trip.
      expect(scanLine!.getAttribute('title')).toBe('959dd932-4c1e-4a77-9f0e-2b3c4d5e6f70');

      expect(rows[1]!.querySelector('[data-testid="failed-job-scan"]')).toBeNull();
      expect((rows[1]!.textContent ?? '')).not.toContain('Scan');
      el.remove();
    } finally {
      globalThis.fetch = origFetch;
    }
  });

  it('a non-exhausted state renders WITHOUT the "gave up" line (only RETRY_EXHAUSTED opts in)', async () => {
    // The gave-up arm keys on one exact spelling. Any other state — including one this drawer has
    // never heard of — must fall through to the plain rendering rather than be treated as the
    // newer, softer state.
    const origFetch = globalThis.fetch;
    globalThis.fetch = (async (url: unknown) => {
      if (String(url).includes('/api/indexing-jobs/failed/by-prefix')) {
        return new Response(byPrefixBody([job({ pathHash: 'h-odd', state: 'PENDING' })]), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch;
    try {
      const el = document.createElement('jf-failed-files-list') as FailedFilesList;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      el.folderPathHash = 'folder-hash';
      await pump(el);

      const row = el.shadowRoot?.querySelector('.row');
      expect(row?.getAttribute('data-state')).toBe('PENDING');
      expect(row?.querySelector('[data-testid="failed-job-exhausted"]')).toBeNull();
      el.remove();
    } finally {
      globalThis.fetch = origFetch;
    }
  });

  it('refuses a by-prefix body missing the required `state` field instead of rendering it', async () => {
    // Tempdoc 911 (885 UL.9) — the parse boundary. `state` is the RETRY_EXHAUSTED discriminator and
    // the wire contract declares it required; before this, the drawer did `String(j['state'] ?? '')`
    // and a backend that dropped or renamed the field produced a silently plausible screen (every
    // row rendered as a plain parse failure) with nothing anywhere saying the contract had broken.
    // Under the dev posture parseWireContract THROWS, so the drawer reports a load failure.
    const origFetch = globalThis.fetch;
    globalThis.fetch = (async (url: unknown) => {
      if (String(url).includes('/api/indexing-jobs/failed/by-prefix')) {
        const { state: _dropped, ...noState } = job({ pathHash: 'h-drift' });
        return new Response(byPrefixBody([noState]), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch;
    try {
      const el = document.createElement('jf-failed-files-list') as FailedFilesList;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      el.folderPathHash = 'folder-hash';
      await pump(el);

      // No row is rendered, and the failure is surfaced — not swallowed into an empty list, which
      // would read as "this folder has no failed files".
      expect(el.shadowRoot?.querySelectorAll('.row').length).toBe(0);
      const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
      expect(text).toContain("Couldn't load failed files");
      expect(text).toContain('WireContract');
      expect(text).not.toContain('No failed files in this folder.');
      el.remove();
    } finally {
      globalThis.fetch = origFetch;
    }
  });

  it('"Try again" invokes core.retry-indexing-job for every listed file and reads the list again', async () => {
    const retried: string[] = [];
    let listings = 0;
    const origFetch = globalThis.fetch;
    globalThis.fetch = (async (url: unknown, init?: RequestInit) => {
      const u = String(url);
      if (u.includes('/api/indexing-jobs/failed/by-prefix')) {
        listings += 1;
        return new Response(
          byPrefixBody([
            job({ pathHash: 'h-one', errorMessage: 'boom' }),
            job({ pathHash: 'h-two', errorMessage: 'bang' }),
          ]),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      if (u.includes('/api/operations/') && u.includes('/invoke') && init?.method === 'POST') {
        retried.push(typeof init?.body === 'string' ? init.body : '');
        return new Response(JSON.stringify({ success: true }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch;
    try {
      const el = document.createElement('jf-failed-files-list') as FailedFilesList;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      el.folderPathHash = 'folder-hash';
      await pump(el);
      expect(el.shadowRoot?.querySelectorAll('.row').length).toBe(2);

      const reads = listings;
      inlineRetry(el).onActivate();
      await pump(el);

      // The retry Operation was invoked for both files (POST /api/operations/.../invoke), and the
      // list was read again rather than assumed.
      expect(retried.length).toBe(2);
      expect(listings).toBe(reads + 1);
      el.remove();
    } finally {
      globalThis.fetch = origFetch;
    }
  });
});

describe('FailedFilesList — PROD posture (drift degrades, it does not freeze the list)', () => {
  // Tempdoc 911 review S2-1. In prod `parseWireContract` logs, records the drift, and RETURNS THE
  // RAW BODY — so the typed contract buys nothing at render time. Dereferencing `state`/`pathHash`
  // on a drifted row would throw inside Lit's `performUpdate`, which is NOT inside `refresh()`'s
  // try/catch: `this.error` would stay null and the user would get a frozen drawer, strictly worse
  // than the pre-contract behaviour that rendered the rows. This pins the degrade.
  let consoleErrorSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    vi.mocked(isDevMode).mockReturnValue(false);
    consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    localStorage.clear();
  });

  afterEach(() => {
    consoleErrorSpy.mockRestore();
    vi.mocked(isDevMode).mockRestore();
  });

  it('renders a state-less row instead of throwing, and records the drift', async () => {
    const origFetch = globalThis.fetch;
    globalThis.fetch = (async (url: unknown) => {
      if (String(url).includes('/api/indexing-jobs/failed/by-prefix')) {
        const { state: _dropped, ...noState } = job({
          pathHash: 'h-drift',
          errorMessage: 'parse error: unexpected EOF',
        });
        return new Response(byPrefixBody([noState]), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch;
    try {
      const el = document.createElement('jf-failed-files-list') as FailedFilesList;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      el.folderPathHash = 'folder-hash';
      await pump(el);

      // The row RENDERS (pre-PR behaviour preserved) — no TypeError escaped into performUpdate.
      const rows = el.shadowRoot?.querySelectorAll('.row') ?? [];
      expect(rows.length).toBe(1);
      expect((rows[0]?.textContent ?? '')).toContain('parse error: unexpected EOF');
      // An absent state is NOT the exhausted state: the missing discriminator must not be guessed.
      expect(rows[0]?.querySelector('[data-testid="failed-job-exhausted"]')).toBeNull();
      // …and the drawer is not stuck in the loading or error arm.
      const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
      expect(text).not.toContain("Couldn't load failed files");
      expect(text).not.toContain('Loading');

      // The degrade leaves a TRACE rather than being swallowed: the `[WireContract]` console line
      // plus an entry in the drift ring. Nothing displays that ring — HealthSurface.ts:1459 and
      // HelpSurface.ts:422 pass `summarizeWireDrift()` as `.args` to `core.export-diagnostics`, so
      // it reaches a human only through a diagnostics export.
      expect(consoleErrorSpy).toHaveBeenCalled();
      expect(readWireDrift().map((e) => e.context)).toContain(
        'GET /api/indexing-jobs/failed/by-prefix',
      );
      el.remove();
    } finally {
      globalThis.fetch = origFetch;
    }
  });

  it('survives a jobs array of nulls (the worst degrade the raw body can carry)', async () => {
    const origFetch = globalThis.fetch;
    globalThis.fetch = (async (url: unknown) => {
      if (String(url).includes('/api/indexing-jobs/failed/by-prefix')) {
        return new Response(JSON.stringify({ jobs: [null], count: 1 }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch;
    try {
      const el = document.createElement('jf-failed-files-list') as FailedFilesList;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      el.folderPathHash = 'folder-hash';
      await pump(el);

      expect(el.shadowRoot?.querySelectorAll('.row').length).toBe(1);
      expect((el.shadowRoot?.textContent ?? '')).not.toContain("Couldn't load failed files");
      el.remove();
    } finally {
      globalThis.fetch = origFetch;
    }
  });
});

// L13: replacing a visible target must replace both evidence and retry identities.
it('switches a list from A to B and ignores A arriving late', async () => {
  let answerA!: (response: Response) => void;
  const oldFetch = globalThis.fetch;
  globalThis.fetch = vi.fn(async (url: unknown) => {
    const path = String(url);
    if (path.includes('by-prefix?pathHash=A')) return new Promise<Response>(resolve => { answerA = resolve; });
    if (path.includes('by-prefix?pathHash=B')) return new Response(byPrefixBody([job({ pathHash: 'B-file', errorMessage: 'B reason' })]));
    return new Response('{}');
  }) as typeof fetch;
  const el = document.createElement('jf-failed-files-list') as FailedFilesList;
  try {
    document.body.append(el); await pump(el);
    el.folderPathHash = 'A'; await pump(el);
    el.folderPathHash = 'B'; await pump(el);
    expect(el.rows.map(row => row.pathHash)).toEqual(['B-file']);
    expect(el.shadowRoot?.textContent).toContain('B reason');
    expect(el.shadowRoot?.querySelector('jf-row-actions')?.getAttribute('row-key')).toBe('B-file');
    answerA(new Response(byPrefixBody([job({ pathHash: 'A-file', errorMessage: 'obsolete A' })])));
    await pump(el);
    expect(el.rows.map(row => row.pathHash)).toEqual(['B-file']);
    expect(el.shadowRoot?.textContent).not.toContain('obsolete A');
    el.remove(); document.body.append(el); await pump(el);
    expect(el.rows.map(row => row.pathHash)).toEqual(['B-file']);
  } finally { el.remove(); globalThis.fetch = oldFetch; }
});

it('loads its folder again after it is remounted', async () => {
  const oldFetch = globalThis.fetch;
  globalThis.fetch = vi.fn(async () => new Response(byPrefixBody([job({ pathHash: 'current-file' })]))) as typeof fetch;
  const el = document.createElement('jf-failed-files-list') as FailedFilesList;
  el.folderPathHash = 'current';
  try {
    document.body.append(el); await pump(el);
    expect(el.rows.map(row => row.pathHash)).toEqual(['current-file']);
    el.remove(); document.body.append(el); await pump(el);
    expect(el.rows.map(row => row.pathHash)).toEqual(['current-file']);
  } finally { el.remove(); globalThis.fetch = oldFetch; }
});

function inlineRetry(el: FailedFilesList) {
  return el.shadowRoot!.querySelector<HTMLElement & { onActivate(): void; disabled: boolean }>('jf-button[label="Try again"]')!;
}

it.each(['empty', 'partial'])('R3d live %s refresh replaces the list during retry without dropping unattempted files', async response => {
  let release!: (response: Response) => void;
  let listing = [job({ pathHash: 'first' }), job({ pathHash: 'remaining', state: 'SOURCE_UNREADABLE', errorMessage: 'Permission denied' })];
  let reads = 0;
  const retried: string[] = []; const oldFetch = globalThis.fetch;
  globalThis.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
    if (String(url).includes('by-prefix')) { ++reads; return new Response(byPrefixBody(listing)); }
    if (String(url).includes('/invoke')) {
      retried.push(JSON.parse(String(init?.body)).args.pathHash);
      if (retried.length === 1) return new Promise<Response>(resolve => { release = resolve; });
      return Response.json({ success: true });
    }
    return Response.json({});
  }) as typeof fetch;
  const el = document.createElement('jf-failed-files-list') as FailedFilesList;
  el.folderPathHash = 'folder'; el.observedCount = 2;
  try {
    document.body.append(el); await pump(el);
    inlineRetry(el).onActivate(); await pump(el);
    listing = response === 'empty' ? [] : [job({ pathHash: 'new-failure' })];
    el.observedCount = listing.length; await pump(el);
    expect(reads).toBe(2);
    expect(inlineRetry(el).disabled).toBe(false);
    expect(el.rows.map(row => row.pathHash)).toEqual(response === 'empty' ? ['first', 'remaining'] : ['new-failure']);
    if (response === 'empty') {
      expect(el.shadowRoot!.textContent).toContain('Permission denied');
      expect(el.shadowRoot!.querySelector('[data-state="SOURCE_UNREADABLE"]')).not.toBeNull();
    }
    release(Response.json({ success: true })); await pump(el);
    expect(retried).toEqual(['first', 'remaining']);
    inlineRetry(el).onActivate(); await pump(el);
    expect(retried).toEqual(['first', 'remaining', ...(response === 'partial' ? ['new-failure'] : ['first', 'remaining'])]);
  } finally { el.remove(); globalThis.fetch = oldFetch; }
});

it('R3d a silent retry followed by a silent listing keeps Try again enabled and rows visible', async () => {
  let reads = 0;
  const oldFetch = globalThis.fetch;
  globalThis.fetch = vi.fn(async (url: unknown) => {
    if (String(url).includes('by-prefix') && ++reads === 1) {
      return new Response(byPrefixBody([job({ pathHash: 'silent', state: 'INDEXED_SOURCE_UNREADABLE', errorMessage: 'Access denied' }),
        job({ pathHash: 'remaining', state: 'RETRY_EXHAUSTED', errorMessage: 'Drive offline' })]));
    }
    if (String(url).includes('by-prefix') || String(url).includes('/invoke')) return new Promise<Response>(() => {});
    return Response.json({});
  }) as typeof fetch;
  const el = document.createElement('jf-failed-files-list') as FailedFilesList;
  el.folderPathHash = 'folder'; el.observedCount = 2;
  try {
    document.body.append(el); await pump(el);
    inlineRetry(el).onActivate(); await pump(el);
    el.observedCount = 0; await pump(el);
    expect(inlineRetry(el).disabled).toBe(false);
    expect(reads).toBe(2);
    expect(el.shadowRoot!.querySelectorAll('.row')).toHaveLength(2);
    expect(el.shadowRoot!.textContent).toContain('Access denied');
    expect(el.shadowRoot!.textContent).toContain('Drive offline');
    expect(el.shadowRoot!.querySelector('[data-state="INDEXED_SOURCE_UNREADABLE"]')).not.toBeNull();
    expect(el.shadowRoot!.querySelector('[data-state="RETRY_EXHAUSTED"]')).not.toBeNull();
  } finally { el.remove(); globalThis.fetch = oldFetch; }
});

it('R3d a second click while retry is pending retries every listed file again', async () => {
  const releases: Array<(response: Response) => void> = [];
  const retried: string[] = []; const oldFetch = globalThis.fetch;
  globalThis.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
    if (String(url).includes('by-prefix')) return new Response(byPrefixBody([job({ pathHash: 'A' }), job({ pathHash: 'B' })]));
    if (String(url).includes('/invoke')) {
      const hash = JSON.parse(String(init?.body)).args.pathHash;
      retried.push(hash);
      if (hash === 'A') return new Promise<Response>(resolve => releases.push(resolve));
      return Response.json({ success: true });
    }
    return Response.json({});
  }) as typeof fetch;
  const el = document.createElement('jf-failed-files-list') as FailedFilesList;
  el.folderPathHash = 'folder';
  try {
    document.body.append(el); await pump(el);
    inlineRetry(el).onActivate(); await pump(el);
    inlineRetry(el).onActivate(); await pump(el);
    expect(retried).toEqual(['A', 'A']);
    releases[1]!(Response.json({ success: true })); await pump(el);
    releases[0]!(Response.json({ success: true })); await pump(el);
    expect(retried).toEqual(['A', 'A', 'B', 'B']);
    expect(inlineRetry(el).disabled).toBe(false);
  } finally { el.remove(); globalThis.fetch = oldFetch; }
});

it('R3d a fresh failure list retries A again along with refused B and newly failed C', async () => {
  let listing = [job({ pathHash: 'A' }), job({ pathHash: 'B', state: 'SOURCE_UNREADABLE' })];
  const retried: string[] = []; const oldFetch = globalThis.fetch;
  globalThis.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
    if (String(url).includes('by-prefix')) return new Response(byPrefixBody(listing));
    if (String(url).includes('/invoke')) {
      const hash = JSON.parse(String(init?.body)).args.pathHash;
      retried.push(hash);
      return hash === 'B' ? Response.json({ success: false, message: 'Retry refused' }, { status: 409 })
        : Response.json({ success: true });
    }
    return Response.json({});
  }) as typeof fetch;
  const el = document.createElement('jf-failed-files-list') as FailedFilesList;
  el.folderPathHash = 'folder'; el.observedCount = 2;
  try {
    document.body.append(el); await pump(el);
    inlineRetry(el).onActivate(); await pump(el);
    expect(retried).toEqual(['A', 'B']);
    listing = [...listing, job({ pathHash: 'C' })];
    el.observedCount = 3; await pump(el);
    expect(el.rows.map(row => row.pathHash)).toEqual(['A', 'B', 'C']);
    inlineRetry(el).onActivate(); await pump(el);
    expect(retried).toEqual(['A', 'B', 'A', 'B', 'C']);
    expect(inlineRetry(el).disabled).toBe(false);
  } finally { el.remove(); globalThis.fetch = oldFetch; }
});

it('R3d late retry results cannot touch a switched target or remounted element', async () => {
  const releases = new Map<string, (response: Response) => void>();
  const retried: string[] = []; const reads: string[] = []; const oldFetch = globalThis.fetch;
  globalThis.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
    if (String(url).includes('by-prefix')) {
      const folder = new URL(String(url), 'http://test').searchParams.get('pathHash');
      reads.push(folder!);
      return new Response(byPrefixBody([job({ pathHash: `${folder}-file` }), job({ pathHash: `${folder}-next` })]));
    }
    if (String(url).includes('/invoke')) {
      const hash = JSON.parse(String(init?.body)).args.pathHash;
      retried.push(hash); return new Promise<Response>(resolve => releases.set(hash, resolve));
    }
    return Response.json({});
  }) as typeof fetch;
  const el = document.createElement('jf-failed-files-list') as FailedFilesList;
  el.folderPathHash = 'A';
  const retry = () => el.shadowRoot!.querySelector<HTMLElement & { onActivate(): void }>('jf-button[label="Try again"]')!.onActivate();
  try {
    document.body.append(el); await pump(el);
    retry(); await pump(el);
    el.folderPathHash = 'B';
    await pump(el); retry(); await pump(el);
    const readsBefore = reads.length;
    releases.get('A-file')!(Response.json({ success: true })); await pump(el);
    expect(reads).toHaveLength(readsBefore);
    expect(el.rows.map(row => row.pathHash)).toEqual(['B-file', 'B-next']);
    el.remove(); document.body.append(el); await pump(el);
    const remountedReads = reads.length;
    releases.get('B-file')!(Response.json({ success: true })); await pump(el);
    expect(reads).toHaveLength(remountedReads);
    expect(el.rows.map(row => row.pathHash)).toEqual(['B-file', 'B-next']);
    expect(retried).toEqual(['A-file', 'B-file']);
  } finally { el.remove(); globalThis.fetch = oldFetch; }
});

it('R3d a second window and a reload or tab remount use fresh lists while an old retry is pending', async () => {
  let answerOld!: (response: Response) => void;
  let listing = [job({ pathHash: 'old', errorMessage: 'Old failure' })];
  const retried: string[] = []; const oldFetch = globalThis.fetch;
  globalThis.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
    if (String(url).includes('by-prefix')) return new Response(byPrefixBody(listing));
    if (String(url).includes('/invoke')) {
      retried.push(JSON.parse(String(init?.body)).args.pathHash);
      if (retried.length === 1) return new Promise<Response>(resolve => { answerOld = resolve; });
      return Response.json({ success: true });
    }
    return Response.json({});
  }) as typeof fetch;
  const first = document.createElement('jf-failed-files-list') as FailedFilesList;
  const second = document.createElement('jf-failed-files-list') as FailedFilesList;
  const reloaded = document.createElement('jf-failed-files-list') as FailedFilesList;
  for (const el of [first, second, reloaded]) { el.folderPathHash = 'folder'; }
  try {
    document.body.append(first); await pump(first); inlineRetry(first).onActivate(); await pump(first);
    listing = [job({ pathHash: 'new', errorMessage: 'New failure' })];
    document.body.append(second); await pump(second);
    inlineRetry(second).onActivate(); await pump(second);
    first.remove(); document.body.append(first, reloaded); await pump(first); await pump(reloaded);
    answerOld(Response.json({ success: false }, { status: 409 })); await pump(first); await pump(second);
    for (const el of [first, second, reloaded]) {
      expect(el.rows.map(row => row.pathHash)).toEqual(['new']);
      expect(el.shadowRoot!.textContent).toContain('New failure');
      expect(inlineRetry(el).disabled).toBe(false);
    }
    expect(retried).toEqual(['old', 'new']);
  } finally { first.remove(); second.remove(); reloaded.remove(); globalThis.fetch = oldFetch; }
});

it('R3d a late listing cannot overwrite a newer count refresh', async () => {
  let answerOld!: (response: Response) => void;
  let reads = 0;
  const oldFetch = globalThis.fetch;
  globalThis.fetch = vi.fn(async (url: unknown) => {
    if (String(url).includes('by-prefix')) {
      if (++reads === 2) return new Promise<Response>(resolve => { answerOld = resolve; });
      return new Response(byPrefixBody([job({ pathHash: reads === 1 ? 'initial' : 'newest' })]));
    }
    return Response.json({});
  }) as typeof fetch;
  const el = document.createElement('jf-failed-files-list') as FailedFilesList;
  el.folderPathHash = 'folder'; el.observedCount = 1;
  try {
    document.body.append(el); await pump(el);
    el.observedCount = 2; await pump(el);
    expect(el.shadowRoot!.querySelectorAll('.row')).toHaveLength(1);
    el.observedCount = 3; await pump(el);
    answerOld(new Response(byPrefixBody([job({ pathHash: 'obsolete' })]))); await pump(el);
    expect(el.rows.map(row => row.pathHash)).toEqual(['newest']);
  } finally { el.remove(); globalThis.fetch = oldFetch; }
});

it('R3d a failed refresh keeps the last-known inline rows visible beside the error', async () => {
  let reads = 0;
  const oldFetch = globalThis.fetch;
  globalThis.fetch = vi.fn(async (url: unknown) => {
    if (String(url).includes('by-prefix')) {
      if (++reads === 1) return new Response(byPrefixBody([job({ pathHash: 'kept', state: 'SOURCE_UNREADABLE', errorMessage: 'Permission denied' })]));
      return reads === 2 ? new Response('', { status: 503 }) : Promise.reject(new TypeError('network down'));
    }
    return Response.json({});
  }) as typeof fetch;
  const el = document.createElement('jf-failed-files-list') as FailedFilesList;
  el.folderPathHash = 'folder'; el.observedCount = 1; el.lastKnown = false;
  try {
    document.body.append(el); await pump(el);
    for (const count of [2, 3]) {
      el.observedCount = count; await pump(el);
      expect(el.shadowRoot!.querySelectorAll('.row')).toHaveLength(1);
      expect(el.shadowRoot!.querySelector('[data-state="SOURCE_UNREADABLE"]')).not.toBeNull();
      expect(el.shadowRoot!.textContent).toContain('Permission denied');
      expect(el.shadowRoot!.textContent).toContain("Couldn't refresh failed files");
      expect(inlineRetry(el).disabled).toBe(false);
    }
  } finally { el.remove(); globalThis.fetch = oldFetch; }
});


it('R3d the real Try again button stays operable while a retry never answers', async () => {
  const retried: string[] = []; const oldFetch = globalThis.fetch;
  globalThis.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
    if (String(url).includes('by-prefix')) return new Response(byPrefixBody([job({ pathHash: 'A' })]));
    if (String(url).includes('/invoke')) { retried.push(JSON.parse(String(init?.body)).args.pathHash); return new Promise<Response>(() => {}); }
    return Response.json({});
  }) as typeof fetch;
  const el = document.createElement('jf-failed-files-list') as FailedFilesList;
  el.folderPathHash = 'folder'; el.observedCount = 1;
  try {
    document.body.append(el); await pump(el);
    const atom = inlineRetry(el); await settle(atom);
    const control = atom.shadowRoot!.querySelector('jf-control')! as Element & { busy: boolean };
    await settle(control);
    const button = () => control.shadowRoot!.querySelector('button')!;
    button().click(); await pump(el); await new Promise(resolve => setTimeout(resolve, 400)); await settle(control);
    expect(control.busy).toBe(false);
    expect(button().getAttribute('aria-disabled')).not.toBe('true');
    button().click(); await pump(el);
    expect(retried).toEqual(['A', 'A']);
  } finally { el.remove(); globalThis.fetch = oldFetch; }
});

/** Bulk retries retain failed rows and report through the existing local message channel. */
describe('FailedFilesList — retry failure feedback', () => {
  let el: FailedFilesList;
  let notices: EphemeralToastSpec[];
  const onNotice = (event: Event) => notices.push((event as CustomEvent).detail);
  let invoke: () => Promise<Response>;

  beforeEach(async () => {
    notices = [];
    document.addEventListener(EPHEMERAL_TOAST_EVENT, onNotice);
    invoke = async () => new Response(JSON.stringify({ success: true }));
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes('/invoke')) return invoke();
      if (String(input).includes('/failed/by-prefix')) {
        return new Response(byPrefixBody([job({ pathHash: 'h-one', errorMessage: 'Original parse error' })]));
      }
      return new Response('{}');
    }));
    el = document.createElement('jf-failed-files-list') as FailedFilesList;
    el.apiBase = 'http://retry-test';
    document.body.append(el);
    el.folderPathHash = 'retry-folder';
    await pump(el);
  });
  afterEach(() => {
    el.remove();
    document.removeEventListener(EPHEMERAL_TOAST_EVENT, onNotice);
    vi.unstubAllGlobals();
  });
  async function retry(): Promise<void> {
    const button = el.shadowRoot!.querySelector('jf-button[label="Try again"]')!;
    await settle(button);
    const control = button.shadowRoot!.querySelector('jf-control')!;
    await settle(control);
    control.shadowRoot!.querySelector('button')!.click();
  }

  it.each(['refused', 'network', 'unknown'])('reports a %s retry failure and keeps the row error', async failure => {
    invoke = async () => {
      if (failure === 'network') throw new Error('Connection lost');
      return new Response(JSON.stringify({ success: false,
        ...(failure === 'refused' ? { message: 'Retry denied by policy', errorClass: 'TRUST_DENIED' } : {}),
      }), { status: 403 });
    };
    await retry();
    await vi.waitFor(() => expect(notices).toHaveLength(1));
    expect(notices[0]).toMatchObject({ severity: 'error', classId: 'core.ephemeral' });
    expect(notices[0]!.message).toContain('Could not retry');
    if (failure !== 'unknown') expect(notices[0]!.message).toContain(failure === 'refused' ? 'Retry denied by policy' : 'Connection lost');
    await pump(el);
    expect(el.shadowRoot!.querySelector('.row')!.textContent).toContain('Original parse error');
    expect(el.shadowRoot!.querySelector('jf-button[label="Try again"]')).not.toBeNull();
  });

  it.each(['switch folder', 'leave', 'remount'])('a late retry failure after %s does not affect the new target', async navigation => {
    let reject!: (reason: Error) => void;
    invoke = () => new Promise<Response>((_resolve, fail) => { reject = fail; });
    await retry();
    if (navigation === 'switch folder') el.folderPathHash = 'another-folder';
    else if (navigation === 'leave') el.remove();
    else {
      el.remove();
      el = document.createElement('jf-failed-files-list') as FailedFilesList;
      el.apiBase = 'http://retry-test';
      el.folderPathHash = 'retry-folder';
      document.body.append(el);
    }
    await pump(el);
    reject(new Error('Old request failed'));
    await pump(el);
    expect(notices).toHaveLength(0);
    if (navigation !== 'leave') expect(el.shadowRoot!.querySelector('.row')!.textContent).toContain('Original parse error');
  });
});
