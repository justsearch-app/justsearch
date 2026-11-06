// @vitest-environment happy-dom
//
// Tempdoc 914 D2 — the failed-files row must give the PATH the width.
//
// Measured live in Chrome against a real 2-failed-file folder, before the fix (widths read through
// the shadow DOM with getBoundingClientRect):
//
//   .row               373px wide
//   jf-row-actions     339px wide   (two non-shrinking full-label buttons)
//   .row-info           22px wide x 585px tall   → one character per line, an 822px-tall row
//
// The row was a two-column flex line inside a 26rem panel: `.row-info { flex: 1; min-inline-size: 0 }`
// shrinks, and a button strip does not. Pinning the intent structurally (the row STACKS, the strip
// WRAPS) is what a headless test can assert; the live re-measure is in the tempdoc's report-back.

import { describe, expect, it } from 'vitest';
import './FailedFilesList.js';
import './RowActions.js';
import type { FailedFilesList } from './FailedFilesList.js';

function job(over: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
  return {
    pathHash: 'h-one',
    state: 'FAILED',
    attempts: 1,
    lastUpdatedMs: 1_700_000_000_000,
    errorMessage: 'ExtractionException: Sandbox parser failed',
    retryAfterMs: 0,
    collection: 'default',
    scanId: '',
    ...over,
  };
}

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

async function pump(el: Element): Promise<void> {
  for (let i = 0; i < 30; i += 1) {
    await Promise.resolve();
    await settle(el);
  }
}

describe('FailedFilesList — the row stacks so the path gets the width (914 D2)', () => {
  it('.row is a COLUMN, so the path block is never squeezed by the action buttons', async () => {
    const origFetch = globalThis.fetch;
    globalThis.fetch = (async (url: unknown) => {
      if (String(url).includes('/api/indexing-jobs/failed/by-prefix')) {
        return new Response(JSON.stringify({ jobs: [job()], count: 1 }), {
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

      const row = el.shadowRoot?.querySelector('.row') as HTMLElement;
      expect(row, 'the drawer must render a row for the failed file').toBeTruthy();
      const rowStyle = getComputedStyle(row);
      expect(rowStyle.display).toBe('flex');
      // The whole defect: as `row` (the pre-fix value) the two buttons take the line and the path
      // block gets what is left — 22px of a 373px row.
      expect(rowStyle.flexDirection).toBe('column');

      // The path block spans the row rather than competing with the buttons for the same line.
      const info = el.shadowRoot?.querySelector('.row-info') as HTMLElement;
      expect(getComputedStyle(info).inlineSize).toBe('100%');

      el.remove();
    } finally {
      globalThis.fetch = origFetch;
    }
  });

  it('jf-row-actions wraps its buttons instead of overflowing a narrow host', async () => {
    // The shared strip is also a table cell in ResourceView; wrapping is the property that makes it
    // safe in ANY narrow host, which is why it belongs on the component, not on the drawer's copy.
    const strip = document.createElement('jf-row-actions');
    document.body.appendChild(strip);
    await (strip as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    const style = getComputedStyle(strip);
    expect(style.display).toBe('inline-flex');
    expect(style.flexWrap).toBe('wrap');
    strip.remove();
  });
});
