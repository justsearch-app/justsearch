// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './HealthSurface.js';
import type { HealthSurface } from './HealthSurface.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { __feedForTest, __resetAiStateForTest, __tickClockForTest, getAiState,
  type StatusSnapshot } from '../state/aiStateStore.js';
import { projectSv3Degradation } from './search-v3/sv3-degradation.js';

const path = '/data/index/indices/foreign';
const detail = `Search could not start because JustSearch cannot verify that the folder ${path} belongs to it. Nothing in it was changed. If it is your JustSearch index, restore it from a backup; otherwise move it out of the index folder, then restart. Evidence: missing`;

function feedRefusal(): void {
  __feedForTest({ status: { readiness: {
    engineComponents: { index: { state: 'FAILED', reasonCode: 'index.ownership_unproven', evidence: detail } },
    composites: {
      retrieval: { state: 'NOT_READY', reasonCodes: ['index.ownership_unproven'] },
      aiFeatures: { state: 'NOT_READY', reasonCodes: ['index.ownership_unproven'] },
    },
  } } as unknown as StatusSnapshot });
  __tickClockForTest();
}

async function mount(): Promise<HealthSurface> {
  const el = document.createElement('jf-health-surface') as HealthSurface;
  el.host_ = { data: { fetch: vi.fn(async () => new Response('{}')) } } as unknown as PluginHostApi;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('ownership refusal at the search and Health surfaces', () => {
  beforeEach(() => {
    __resetAiStateForTest();
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}')));
    feedRefusal();
  });
  afterEach(() => {
    document.body.replaceChildren();
    __resetAiStateForTest();
    vi.unstubAllGlobals();
  });

  it('S16: the search notice reads the rejected path from status evidence', () => {
    const notice = projectSv3Degradation(getAiState());
    expect(notice?.headline).toBe('Search is unavailable');
    expect(notice?.body).toBe(detail);
    expect(notice?.remedy).toEqual({ target: 'core.health-surface', label: 'Open Health' });
  });

  it('S16: Health shows the folder and manual recovery instruction', async () => {
    const el = await mount();
    expect(el.shadowRoot?.textContent).toContain(path);
    expect(el.shadowRoot?.textContent).toContain('Nothing in it was changed');
    expect(el.shadowRoot?.textContent).toContain('restore it from a backup');
  });

  it('S16: Health never offers Reindex, Force Rebuild or Retry for an unproven folder', async () => {
    const el = await mount();
    el.recommendedActions = new Map([['index.failure|index', { target: 'core.rebuild-index', args: {} }]]);
    await el.updateComplete;
    const ids = [...el.shadowRoot!.querySelectorAll('jf-operation')].map(op => op.getAttribute('operation-id'));
    expect(ids).not.toContain('core.reindex');
    expect(ids).not.toContain('core.rebuild-index');
    expect(ids).not.toContain('core.bulk-reindex');
    expect(ids).not.toContain('core.recover-component');
    expect(ids).toContain('core.export-diagnostics');
  });

  it('S16: Health does not claim keyword search is available during refusal', async () => {
    const el = await mount();
    const map = el.shadowRoot!.querySelector('jf-capability-map') as HTMLElement & { updateComplete: Promise<unknown> };
    await map.updateComplete;
    const row = [...map.shadowRoot!.querySelectorAll('li')].find(li => li.textContent?.includes('Search (keyword)'));
    expect(row).toBeDefined();
    expect(row?.textContent).not.toContain('Available');
    expect(row?.textContent).toContain('cannot verify');
  });

  it('S16: Health suppresses refused-index fetch errors while preserving the folder notice', async () => {
    const el = await mount();
    el.error = 'Failed to fetch';
    await el.updateComplete;
    expect(el.shadowRoot?.textContent).not.toContain('Failed to fetch');
    expect(el.shadowRoot?.textContent).toContain(path);
    expect(el.host_.data.fetch).not.toHaveBeenCalledWith('/api/indexing/failed-jobs?limit=20', expect.anything());
  });
});
