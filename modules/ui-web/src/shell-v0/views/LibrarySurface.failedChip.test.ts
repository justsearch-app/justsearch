// @vitest-environment happy-dom
//
// Tempdoc 914 D3 (review S2-2 + delta nit) — the chip rendered by LibrarySurface's OWN card.
//
// `FolderCardRenderer.failedChip.test.ts` covers the declared renderer. This is the hand-authored
// half: `renderFailedChip()` plus the shared `failedChipStyles` fragment, asserted in THIS shadow
// root. Both sites are now fed by one copy authority (`failedChipCopy`) and one style authority
// (`failedChipPresentation.ts`); these two tests are what keeps them from drifting apart again.

import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import './LibrarySurface.js';
import type { LibrarySurface } from './LibrarySurface.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';

const host = {
  platform: { capabilities: new Set<string>() },
  data: { fetch: async () => ({ ok: false, status: 503 }) as unknown as Response },
  utilities: { formatRelativeTime: () => 'just now' },
} as unknown as PluginHostApi;

const row = (over: Partial<IndexedRootView> = {}): IndexedRootView =>
  ({
    pathHash: 'h',
    collection: 'default',
    fileCount: 200,
    lastIndexedIsoTime: '2026-09-02T00:00:00Z',
    status: 'indexed',
    walkError: '',
    inFlightCount: 0,
    failedCount: 2,
    walkCompleted: true,
    ...over,
  }) as IndexedRootView;

/**
 * Mount with rows already in place and a seeded last-known memory, so the chip can be driven into
 * both states without waiting on a live poll. `lastKnownFailed` is the surface's own private field —
 * the same map `rememberFailedCounts` writes in `refresh()`.
 */
async function mount(
  rows: IndexedRootView[],
  lastKnownFailed: Record<string, number> = {},
  provisional = false,
): Promise<LibrarySurface> {
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.host_ = host;
  document.body.appendChild(el);
  await el.updateComplete;
  (el as unknown as { roots: IndexedRootView[] }).roots = rows;
  (el as unknown as { lastKnownFailed: Record<string, number> }).lastKnownFailed = lastKnownFailed;
  (el as unknown as { provisional: boolean }).provisional = provisional;
  el.requestUpdate();
  await el.updateComplete;
  return el;
}

describe("LibrarySurface — the failed chip on the surface's own card (914 D3)", () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    __resetAiStateForTest();
  });
  afterEach(() => __resetAiStateForTest());

  it('a count this poll reported renders plain, in the danger tone', async () => {
    const el = await mount([row()]);
    try {
      const chip = el.shadowRoot?.querySelector('.failed-chip') as HTMLElement;
      expect(chip, 'a folder with failures must offer the drill-down').toBeTruthy();
      const text = chip.textContent?.replace(/\s+/g, ' ').trim() ?? '';
      expect(text).toContain('2 failed');
      expect(text).not.toContain('last known');
      expect(chip.getAttribute('data-last-known')).toBe('false');
      expect(chip.getAttribute('title')).toBeNull();
      expect(getComputedStyle(chip).fontStyle).not.toBe('italic');
    } finally {
      el.remove();
    }
  });

  it('a CARRIED count says "last known" on screen and takes the muted-italic treatment', async () => {
    // The S2-1 shape: the wire reports the failures as in-flight (the retry re-queue), and the shell
    // is provisional — the branch the first cut left unfixed.
    const el = await mount([row({ inFlightCount: 2, failedCount: 0 })], { h: 2 }, true);
    try {
      const chip = el.shadowRoot?.querySelector('.failed-chip') as HTMLElement;
      expect(chip, 'the drill-down must stay reachable through the retry window').toBeTruthy();
      expect(chip.textContent?.replace(/\s+/g, ' ').trim()).toContain('2 failed · last known');
      expect(chip.getAttribute('data-last-known')).toBe('true');
      // The shared `failedChipStyles` fragment is applied in THIS shadow root, not only the
      // renderer's — the assertion the dedupe exists to keep honest.
      expect(getComputedStyle(chip).fontStyle).toBe('italic');
      expect(chip.getAttribute('title')).toContain('failed as of the last settled check');
      // WCAG 2.5.3: the accessible name contains the visible text.
      expect((chip.getAttribute('label') ?? '').startsWith('2 failed · last known')).toBe(true);
      // The row's own state line stays truthful about what the queue is doing.
      const meta = el.shadowRoot?.querySelector('.folder-state')?.textContent ?? '';
      expect(meta).toContain('Rebuilding');
    } finally {
      el.remove();
    }
  });

  it('no chip at all when nothing failed and nothing is carried', async () => {
    const el = await mount([row({ failedCount: 0 })]);
    try {
      expect(el.shadowRoot?.querySelector('.failed-chip')).toBeNull();
    } finally {
      el.remove();
    }
  });
});
