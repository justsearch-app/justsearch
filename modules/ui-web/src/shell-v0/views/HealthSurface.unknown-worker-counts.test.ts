// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * Follow-up B1, FB2b and FB5a, on Health. FB2b: while a status marks its document counts unknown,
 * Health's Size and Queue, Worker counts like Files, are not known either; they never show the
 * placeholder or the number the block carries. FB5a: Files is the person's own count; an Engine that
 * reports none reads Unknown, never the Help-inclusive searchable count, also during an outage.
 * The status goes through the real store; the checks read what Health renders.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './HealthSurface.js';
import type { HealthSurface } from './HealthSurface.js';
import { __feedForTest, __resetAiStateForTest, __tickClockForTest, type StatusSnapshot } from '../state/aiStateStore.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { __resetEngineLossForTest, reportEngineReachability } from '../state/engineLoss.js';

const json = (value: unknown, code = 200) => new Response(JSON.stringify(value), { status: code, headers: { 'Content-Type': 'application/json' } });
function feed(core: Record<string, unknown>): void {
  __feedForTest({ status: {
    status: 'ok',
    worker: { core: { indexHealthy: true, ...core }, migration: { pendingJobsCount: 0, processingJobsCount: 0 } },
    readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] }, aiFeatures: { state: 'READY', reasonCodes: [] } } },
    memoryUsedBytes: 5, memoryMaxBytes: 10,
  } as unknown as StatusSnapshot, inference: { mode: 'online', starting: false, available: true } });
  __tickClockForTest();
}

let el: HealthSurface | undefined;
async function mount(): Promise<HealthSurface> {
  el = document.createElement('jf-health-surface') as HealthSurface;
  el.host_ = createMockHostApi({ data: { fetch: async path => path === '/api/condition-recovery-index'
    ? json({ entries: [] }) : json({ jobs: [] }, path.includes('/stream') ? 404 : 200) } });
  document.body.append(el);
  await el.updateComplete;
  return el;
}
async function settle(): Promise<void> {
  for (let i = 0; i < 20; i++) { await Promise.resolve(); await el!.updateComplete; }
}
/** A value in Health's overview measurements. */
const overview = (label: string) => [...(el!.shadowRoot?.querySelectorAll('.health-metrics > div') ?? [])]
  .find(node => node.querySelector('dt')?.textContent?.trim() === label)?.querySelector('dd')?.textContent?.trim() ?? '';
/** A value on Health's Diagnostics cards. */
const card = (label: string) => [...(el!.shadowRoot?.querySelectorAll('.card') ?? [])]
  .find(node => node.querySelector('.card-label')?.textContent?.trim() === label)
  ?.querySelector('.card-value')?.textContent?.trim() ?? '';
const fact = (label: string) => el!.shadowRoot!.querySelector(`[data-fact="${label}"] .val`)?.textContent?.trim() ?? '';

beforeEach(() => {
  document.body.innerHTML = '';
  __resetAiStateForTest(); __resetEngineLossForTest();
  vi.stubGlobal('fetch', vi.fn(async () => json({})));
});
afterEach(() => { el?.remove(); el = undefined; __resetAiStateForTest(); __resetEngineLossForTest(); vi.unstubAllGlobals(); });

describe('FB2b Health shows no Worker count from a status whose counts are unknown', () => {
  it('Size and Queue are not known, never the placeholder or the carried number', async () => {
    feed({ indexState: 'INDEXING', indexedDocuments: 0, searchableDocuments: 0, pendingJobs: 3, indexSizeBytes: 4096,
      indexedDocumentsUnknown: true });
    await mount(); await settle();
    expect(el!.shadowRoot!.querySelector('.health-metrics'), 'the live overview is on screen').not.toBeNull();
    expect(overview('Memory')).toMatch(/\d/);
    for (const value of [overview('Size'), card('Size')]) {
      expect(value).not.toMatch(/\d/);
    }
    for (const value of [overview('Queue'), card('Queue')]) {
      expect(value).not.toMatch(/\d/);
    }
    expect(overview('Files')).not.toMatch(/\d/);
  });
});

describe('FB5a Health never shows a Help-inclusive count as Files', () => {
  it('during an outage, an Engine that reported no own count reads Unknown, not the searchable count', async () => {
    // 12 searchable documents (the bundled Help included); the Engine did not say how many are the person's.
    feed({ indexState: 'IDLE', indexedDocuments: 12, searchableDocuments: 12, pendingJobs: 0, indexedDocumentsUnknown: false });
    await mount(); await settle();
    reportEngineReachability(true, Date.now()); // the Engine stops answering
    await settle();
    expect(fact('Searchable files')).toMatch(/^Unknown\b/);
    expect(fact('Searchable files')).not.toMatch(/\b12\b/);
  });

  it('during an outage, a reported own count is the one shown', async () => {
    feed({ indexState: 'IDLE', indexedDocuments: 12, searchableDocuments: 12, ownSearchableDocuments: 7, pendingJobs: 0,
      indexedDocumentsUnknown: false });
    await mount(); await settle();
    reportEngineReachability(true, Date.now()); // the Engine stops answering
    await settle();
    expect(fact('Searchable files')).toMatch(/^7 · last seen/);
  });
});
