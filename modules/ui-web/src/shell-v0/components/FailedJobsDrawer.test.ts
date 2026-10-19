// @vitest-environment happy-dom
//
// Tempdoc 599 §16/B1 — the per-folder "failed files" drill-down drawer. A real FAILED indexing job
// could not be manufactured live (read-deny / zero-byte files still extracted cleanly), so this drives
// the drawer's load → render → retry path deterministically: the chip→drawer UI is covered here even
// though the live chip could not be reproduced. Mirrors RetrospectivePanel.test.ts (same right-drawer
// TransientController pattern).

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './FailedJobsDrawer.js';
import type { FailedJobsDrawer } from './FailedJobsDrawer.js';
import {
  openFailedJobs,
  closeFailedJobs,
  isFailedJobsOpen,
  failedJobsFolderPathHash,
  __resetFailedJobsDrawer,
} from '../state/failedJobsDrawer.js';
import { isDevMode } from '../../api/devMode.js';
import { readWireDrift } from '../../api/wireDriftTelemetry.js';

// The posture seam (tempdoc 683, mirrored from api/schemas.test.ts): parseWireContract THROWS in dev
// (the vitest default) but DEGRADES in prod. The prod case has its own describe block at the bottom.
vi.mock('../../api/devMode.js', { spy: true });

afterEach(() => {
  __resetFailedJobsDrawer();
});

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

describe('failedJobsDrawer store', () => {
  it('opens with a folder pathHash and closes', () => {
    expect(isFailedJobsOpen()).toBe(false);
    expect(failedJobsFolderPathHash()).toBeNull();
    openFailedJobs('abc123');
    expect(isFailedJobsOpen()).toBe(true);
    expect(failedJobsFolderPathHash()).toBe('abc123');
    closeFailedJobs();
    expect(isFailedJobsOpen()).toBe(false);
  });
});

describe('FailedJobsDrawer', () => {
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
      const el = document.createElement('jf-failed-jobs-drawer') as FailedJobsDrawer;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      // Drive the REAL open path (subscribe → refresh fetch), not a direct `el.open = true`.
      openFailedJobs('folder-hash');
      await pump(el);

      const rows = el.shadowRoot?.querySelectorAll('.row') ?? [];
      expect(rows.length).toBe(2);
      const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
      expect(text).toContain('parse error: unexpected EOF');
      expect(text).toContain('extraction timed out');
      expect(text).toContain('Retry all'); // the header batch affordance appears when rows>0
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
      const el = document.createElement('jf-failed-jobs-drawer') as FailedJobsDrawer;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      openFailedJobs('empty-folder');
      await pump(el);

      const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
      expect(text).toContain('No failed files in this folder.');
      expect(text).not.toContain('Retry all');
      el.remove();
    } finally {
      globalThis.fetch = origFetch;
    }
  });

  it('a per-row action success (from <jf-row-actions>) drops that row from the list', async () => {
    const origFetch = globalThis.fetch;
    globalThis.fetch = (async (url: unknown) => {
      if (String(url).includes('/api/indexing-jobs/failed/by-prefix')) {
        return new Response(
          byPrefixBody([
            job({ pathHash: 'h-one', errorMessage: 'boom' }),
            job({ pathHash: 'h-two', errorMessage: 'bang' }),
          ]),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch;
    try {
      const el = document.createElement('jf-failed-jobs-drawer') as FailedJobsDrawer;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      openFailedJobs('folder-hash');
      await pump(el);
      expect(el.shadowRoot?.querySelectorAll('.row').length).toBe(2);

      // <jf-row-actions> emits a composed `row-action-success` with the rowKey after a successful
      // Operation; the drawer drops that row. Emit it for h-one from the mounted row-actions element.
      const ra = el.shadowRoot?.querySelector('jf-row-actions[row-key="h-one"]');
      ra?.dispatchEvent(
        new CustomEvent('row-action-success', {
          detail: { operationId: 'core.retry-indexing-job', rowKey: 'h-one' },
          bubbles: true,
          composed: true,
        }),
      );
      await pump(el);

      const remaining = el.shadowRoot?.querySelectorAll('jf-row-actions') ?? [];
      expect(remaining.length).toBe(1);
      expect(remaining[0]?.getAttribute('row-key')).toBe('h-two');
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
      const el = document.createElement('jf-failed-jobs-drawer') as FailedJobsDrawer;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      openFailedJobs('folder-hash');
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
      const el = document.createElement('jf-failed-jobs-drawer') as FailedJobsDrawer;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      openFailedJobs('folder-hash');
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
      const el = document.createElement('jf-failed-jobs-drawer') as FailedJobsDrawer;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      openFailedJobs('folder-hash');
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
      const el = document.createElement('jf-failed-jobs-drawer') as FailedJobsDrawer;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      openFailedJobs('folder-hash');
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
      const el = document.createElement('jf-failed-jobs-drawer') as FailedJobsDrawer;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      openFailedJobs('folder-hash');
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

  it('"Retry all" invokes core.retry-indexing-job for every listed file and clears them', async () => {
    const retried: string[] = [];
    const origFetch = globalThis.fetch;
    globalThis.fetch = (async (url: unknown, init?: RequestInit) => {
      const u = String(url);
      if (u.includes('/api/indexing-jobs/failed/by-prefix')) {
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
      const el = document.createElement('jf-failed-jobs-drawer') as FailedJobsDrawer;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      openFailedJobs('folder-hash');
      await pump(el);
      expect(el.shadowRoot?.querySelectorAll('.row').length).toBe(2);

      // Activate the header "Retry all" (jf-button exposes onActivate).
      const buttons = Array.from(el.shadowRoot?.querySelectorAll('jf-button') ?? []);
      const retryAll = buttons.find((b) => (b.getAttribute('label') ?? '').includes('Retry all')) as
        | (Element & { onActivate?: () => void })
        | undefined;
      retryAll?.onActivate?.();
      await pump(el);

      // The retry Operation was invoked for both files (POST /api/operations/.../invoke), and the
      // list cleared as each succeeded.
      expect(retried.length).toBe(2);
      expect(el.shadowRoot?.querySelectorAll('.row').length).toBe(0);
      el.remove();
    } finally {
      globalThis.fetch = origFetch;
    }
  });
});

describe('FailedJobsDrawer — PROD posture (drift degrades, it does not freeze the drawer)', () => {
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
      const el = document.createElement('jf-failed-jobs-drawer') as FailedJobsDrawer;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      openFailedJobs('folder-hash');
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
      const el = document.createElement('jf-failed-jobs-drawer') as FailedJobsDrawer;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      openFailedJobs('folder-hash');
      await pump(el);

      expect(el.shadowRoot?.querySelectorAll('.row').length).toBe(1);
      expect((el.shadowRoot?.textContent ?? '')).not.toContain("Couldn't load failed files");
      el.remove();
    } finally {
      globalThis.fetch = origFetch;
    }
  });
});
