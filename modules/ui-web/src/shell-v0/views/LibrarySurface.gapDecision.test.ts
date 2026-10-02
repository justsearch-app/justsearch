// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './LibrarySurface.js';
import type { LibrarySurface } from './LibrarySurface.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { __feedForTest, __resetAiStateForTest, type StatusSnapshot } from '../state/aiStateStore.js';

const key = '0194f72c-0000-7000-8000-000000000001';
const hash = 'a'.repeat(64);
const gap = { unitId: 'document-7', reason: 'PARSER_FAILED' };

async function pump(el: LibrarySurface): Promise<void> {
  for (let i = 0; i < 12; i += 1) {
    await Promise.resolve();
    await el.updateComplete;
  }
}

describe('Library recorded migration gap decision', () => {
  beforeEach(() => { document.body.innerHTML = ''; __resetAiStateForTest(); });
  afterEach(() => { document.body.innerHTML = ''; __resetAiStateForTest(); vi.restoreAllMocks(); });

  it('offers exact-key cancellation during a rebuild only after destructive confirmation', async () => {
    const invoke = vi.fn().mockResolvedValue({ success: true });
    const confirm = vi.fn().mockResolvedValueOnce(false).mockResolvedValueOnce(true);
    const host = { platform: { capabilities: new Set<string>() },
      data: { fetch: vi.fn().mockResolvedValue(new Response('{}', { status: 503 })),
        invokeOperation: invoke }, ui: { showConfirmDialog: confirm } } as unknown as PluginHostApi;
    __feedForTest({ status: { worker: { migration: {
      migrationState: 'MIGRATING', buildingGenerationId: `g-${key}`,
    } } } as unknown as StatusSnapshot });
    const el = document.createElement('jf-library-surface') as LibrarySurface;
    el.host_ = host;
    document.body.appendChild(el);
    await pump(el);

    const cancel = Array.from(el.shadowRoot?.querySelectorAll('jf-button') ?? [])
      .find((button) => button.getAttribute('label') === 'Cancel rebuild') as (Element & { onActivate?: () => void }) | undefined;
    expect(cancel).toBeDefined();
    cancel?.onActivate?.();
    await pump(el);
    expect(invoke).not.toHaveBeenCalled();
    cancel?.onActivate?.();
    await pump(el);

    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('current index stays available'),
      { confirmLabel: 'Cancel rebuild', destructive: true });
    expect(invoke).toHaveBeenCalledTimes(1);
    expect(invoke).toHaveBeenCalledWith('core.cancel-reindex',
      { reindexKey: key }, { consented: true });
    expect(el.shadowRoot?.textContent).toContain('Rebuild cancellation recorded');
    el.remove();
  });

  it('shows the exact operation gap list and invokes one approved keyed decision', async () => {
    let accepted = false;
    const invoke = vi.fn().mockImplementation(async () => { accepted = true; return { success: true }; });
    const fetch = vi.fn().mockImplementation(async (path: string) => {
      if (path === `/api/operation-history/${key}`) {
        return new Response(JSON.stringify(accepted
          ? { state: 'running', historySince: 0, phase: 'settled' }
          : { state: 'running', historySince: 0, phase: 'awaiting_acceptance',
            result: { gapListHash: hash, gaps: [gap] } }), { status: 200 });
      }
      return new Response('{}', { status: 503 });
    });
    const host = { platform: { capabilities: new Set<string>() }, data: { fetch, invokeOperation: invoke } } as unknown as PluginHostApi;
    __feedForTest({ status: { worker: { migration: {
      migrationState: 'AWAITING_ACCEPTANCE', buildingGenerationId: `g-${key}`,
    } } } as unknown as StatusSnapshot });
    const el = document.createElement('jf-library-surface') as LibrarySurface;
    el.host_ = host;
    document.body.appendChild(el);
    await pump(el);

    expect(el.shadowRoot?.textContent).toContain('document-7: PARSER_FAILED');
    const buttons = Array.from(el.shadowRoot?.querySelectorAll('jf-button') ?? []);
    const accept = buttons.find((button) => button.getAttribute('label') === 'Accept gaps and activate') as (Element & { onActivate?: () => void }) | undefined;
    expect(accept).toBeDefined();
    accept?.onActivate?.();
    await pump(el);

    expect(invoke).toHaveBeenCalledTimes(1);
    expect(invoke).toHaveBeenCalledWith('core.accept-gaps',
      { reindexKey: key, gapListHash: hash }, { consented: true });
    expect(el.shadowRoot?.textContent).not.toContain('document-7: PARSER_FAILED');
    el.remove();
  });

  it.each(['before', 'during'])('releases an invalidated gap read that finishes %s the next migration read', async (completion) => {
    const nextKey = '0194f72c-0000-7000-8000-000000000002';
    let finishOld!: (response: Response) => void;
    let finishNext!: (response: Response) => void;
    const oldRead = new Promise<Response>((resolve) => { finishOld = resolve; });
    const nextRead = new Promise<Response>((resolve) => { finishNext = resolve; });
    const fetch = vi.fn().mockImplementation(async (path: string) => {
      if (path === `/api/operation-history/${key}`) return oldRead;
      if (path === `/api/operation-history/${nextKey}`) return nextRead;
      return new Response('{}', { status: 503 });
    });
    const host = { platform: { capabilities: new Set<string>() },
      data: { fetch, invokeOperation: vi.fn() } } as unknown as PluginHostApi;
    const feed = (migrationState: string, operationKey: string) => __feedForTest({
      status: { worker: { migration: {
        migrationState, buildingGenerationId: `g-${operationKey}`,
      } } } as unknown as StatusSnapshot,
    });
    const outcome = (unitId: string) => new Response(JSON.stringify({
      state: 'running', historySince: 0, phase: 'awaiting_acceptance',
      result: { gapListHash: hash, gaps: [{ unitId, reason: 'PARSER_FAILED' }] },
    }));
    let now = 10_000;
    vi.spyOn(Date, 'now').mockImplementation(() => now);
    feed('AWAITING_ACCEPTANCE', key);
    const el = document.createElement('jf-library-surface') as LibrarySurface;
    el.host_ = host;
    document.body.appendChild(el);
    await pump(el);
    expect(fetch.mock.calls.filter(([path]) => path === `/api/operation-history/${key}`)).toHaveLength(1);

    feed('MIGRATING', key);
    await pump(el);
    if (completion === 'before') {
      finishOld(outcome('obsolete-gap'));
      await pump(el);
    }
    feed('AWAITING_ACCEPTANCE', nextKey);
    await pump(el);
    expect(fetch.mock.calls.filter(([path]) => path === `/api/operation-history/${nextKey}`)).toHaveLength(1);
    if (completion === 'during') {
      finishOld(outcome('obsolete-gap'));
      await pump(el);
    }
    expect(el.gapDecision).toBeNull();
    now += 5_000;
    feed('AWAITING_ACCEPTANCE', nextKey);
    await pump(el);
    expect(fetch.mock.calls.filter(([path]) => path === `/api/operation-history/${nextKey}`)).toHaveLength(1);
    finishNext(outcome('current-gap'));
    await pump(el);
    expect(el.gapDecision?.reindexKey).toBe(nextKey);
    expect(el.shadowRoot?.textContent).toContain('current-gap: PARSER_FAILED');
    expect(el.shadowRoot?.textContent).not.toContain('obsolete-gap');
    expect(Array.from(el.shadowRoot?.querySelectorAll('jf-button') ?? [])
      .some((button) => button.getAttribute('label') === 'Accept gaps and activate')).toBe(true);
    el.remove();
  });

  it('does not retain an operation error that completes after navigation', async () => {
    const rejectDecisions: Array<(reason: Error) => void> = [];
    const invoke = vi.fn().mockImplementation(() => new Promise<{ success: boolean }>((_resolve, reject) => {
      rejectDecisions.push(reject);
    }));
    const fetch = vi.fn().mockImplementation(async () => new Response(JSON.stringify({
      state: 'running', historySince: 0, phase: 'awaiting_acceptance',
      result: { gapListHash: hash, gaps: [gap] },
    }), { status: 200 }));
    const host = { platform: { capabilities: new Set<string>() }, data: { fetch, invokeOperation: invoke } } as unknown as PluginHostApi;
    __feedForTest({ status: { worker: { migration: {
      migrationState: 'AWAITING_ACCEPTANCE', buildingGenerationId: `g-${key}`,
    } } } as unknown as StatusSnapshot });
    const el = document.createElement('jf-library-surface') as LibrarySurface;
    el.host_ = host;
    document.body.appendChild(el);
    await pump(el);

    const accept = Array.from(el.shadowRoot?.querySelectorAll('jf-button') ?? [])
      .find((button) => button.getAttribute('label') === 'Accept gaps and activate') as (Element & { onActivate?: () => void }) | undefined;
    expect(accept).toBeDefined();
    accept?.onActivate?.();
    await pump(el);
    expect(rejectDecisions).toHaveLength(1);
    el.remove();
    document.body.appendChild(el);
    await pump(el);
    expect(el.gapDecisionBusy).toBe(false);
    accept?.onActivate?.();
    await pump(el);
    expect(rejectDecisions).toHaveLength(2);
    expect(el.gapDecisionBusy).toBe(true);
    rejectDecisions[0]!(new Error('obsolete surface error'));
    await pump(el);
    expect(el.gapDecisionError).toBeNull();
    expect(el.gapDecisionBusy).toBe(true);
    expect(el.shadowRoot?.textContent).not.toContain('obsolete surface error');
    rejectDecisions[1]!(new Error('current surface error'));
    await pump(el);
    expect(el.gapDecisionError).toBe('current surface error');
    expect(el.gapDecisionBusy).toBe(false);
    el.remove();
  });
});
