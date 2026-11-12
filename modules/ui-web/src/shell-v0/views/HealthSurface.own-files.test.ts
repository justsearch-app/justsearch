// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * Follow-up B1, FB5: Health's Files count is the person's own files, as the composer, Library and AI
 * Brain count them. The bundled Help is searchable but is not one of the person's files, so it is not
 * counted; a count the Engine does not know is said to be unknown, never shown as the whole index or 0.
 * The status goes through the real store; the checks read what Health renders.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './HealthSurface.js';
import type { HealthSurface } from './HealthSurface.js';
import { __feedForTest, __resetAiStateForTest, getAiState, type StatusSnapshot } from '../state/aiStateStore.js';
import { projectSv3Corpus } from './search-v3/sv3-honesty.js';
import { formatCount } from '../display/format.js';

function feed(core: Record<string, unknown>): void {
  __feedForTest({ status: {
    worker: { core: { indexState: 'IDLE', indexHealthy: true, pendingJobs: 0, ...core } },
    readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] }, aiFeatures: { state: 'READY', reasonCodes: [] } } },
  } as unknown as StatusSnapshot });
}

let el: HealthSurface | undefined;
async function mount(): Promise<HealthSurface> {
  el = document.createElement('jf-health-surface') as HealthSurface;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}
/** The Files value in Health's overview measurements. */
const overviewFiles = () => [...(el!.shadowRoot?.querySelectorAll('.health-metrics > div') ?? [])]
  .find(node => node.querySelector('dt')?.textContent?.trim() === 'Files')?.querySelector('dd')?.textContent?.trim() ?? '';
/** The Files value on Health's Diagnostics card. */
const cardFiles = () => [...(el!.shadowRoot?.querySelectorAll('.card') ?? [])]
  .find(card => card.querySelector('.card-label')?.textContent?.trim() === 'Files')
  ?.querySelector('.card-value')?.textContent?.trim() ?? '';

beforeEach(() => {
  document.body.innerHTML = '';
  __resetAiStateForTest();
  vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockImplementation(async () => new Response('{}', { headers: { 'Content-Type': 'application/json' } })));
});
afterEach(() => { el?.remove(); el = undefined; __resetAiStateForTest(); vi.unstubAllGlobals(); });

describe('FB5 Health counts the person\'s own files', () => {
  it('Files leaves the bundled Help out and agrees with the composer, Library and AI Brain', async () => {
    // 12 searchable documents, 5 of them the bundled Help: the person has 7 files.
    feed({ indexedDocuments: 12, searchableDocuments: 12, ownSearchableDocuments: 7, indexedDocumentsUnknown: false });
    await mount();
    const own = getAiState().index.ownDocumentCount; // what Library and AI Brain read
    expect(own).toEqual({ known: true, value: 7 });
    expect(projectSv3Corpus(getAiState())).toEqual({ kind: 'documents', count: 7 }); // the composer
    expect(overviewFiles()).toMatch(new RegExp(`^${formatCount(7)}\\b`));
    expect(cardFiles()).toBe(formatCount(7));
  });

  it('Files says unknown when the Engine does not report the person\'s own count, never the whole index or 0', async () => {
    feed({ indexedDocuments: 12, searchableDocuments: 12, indexedDocumentsUnknown: false });
    await mount();
    expect(getAiState().index.ownDocumentCount.known).toBe(false);
    expect(overviewFiles()).toMatch(/^Unknown\b/);
    expect(cardFiles()).toBe('Unknown');
  });
});
