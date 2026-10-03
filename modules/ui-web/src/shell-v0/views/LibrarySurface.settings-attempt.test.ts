// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './LibrarySurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';

interface LibrarySettings extends HTMLElement {
  host_: PluginHostApi;
  excludesText: string;
  excludesLoaded: boolean;
  excludesBusy: boolean;
  error: string | null;
  updateComplete: Promise<unknown>;
  refresh(): Promise<void>;
  loadExcludes(): Promise<void>;
  handlePreviewExcludes(): Promise<void>;
  handleApplyExcludes(): Promise<void>;
}
const prior = '01993ba0-0000-7000-8000-000000000001';
const observed = { acceptedRevision: 4, lastCommittedOperationKey: prior };
function json(body: unknown, status = 200): Response { return new Response(JSON.stringify(body), { status }); }
function complete(init?: { body?: unknown }): Response {
  const attempt = JSON.parse(String(init?.body));
  return json({ state: 'COMPLETE', operationKey: attempt.operationKey,
    witness: { acceptedRevision: attempt.witness.acceptedRevision + 1, lastCommittedOperationKey: attempt.operationKey } });
}
function fixture() {
  const send = vi.fn<PluginHostApi['data']['fetch']>();
  const invoke = vi.fn().mockResolvedValue({ success: true });
  const library = document.createElement('jf-library-surface') as LibrarySettings;
  library.host_ = createMockHostApi({ data: { fetch: send, invokeOperation: invoke } });
  library.refresh = vi.fn().mockResolvedValue(undefined);
  return { library, send, invoke };
}
beforeEach(() => vi.useFakeTimers());
afterEach(() => { document.body.innerHTML = ''; vi.useRealTimers(); vi.restoreAllMocks(); });

describe('Library retained-base exclusions', () => {
  it('keeps the witness read with the draft and never refreshes it before saving', async () => {
    const { library, send, invoke } = fixture();
    send.mockResolvedValueOnce(json({ ui: { excludePatterns: ['old'] }, witness: observed }))
      .mockImplementationOnce(async (_, init) => complete(init))
      .mockImplementationOnce(async (_, init) => complete(init));
    await library.loadExcludes();
    library.excludesText = 'old\nnew';
    await library.handlePreviewExcludes();
    const first = JSON.parse(String(send.mock.calls[1]?.[1]?.body));
    expect(first.witness).toEqual(observed);
    expect(first.ui.excludePatterns).toEqual(['old', 'new']);
    expect(invoke).toHaveBeenCalledWith('core.preview-excludes', {});
    library.excludesText = 'third';
    await library.handleApplyExcludes();
    const second = JSON.parse(String(send.mock.calls[2]?.[1]?.body));
    expect(second.witness).toEqual({ acceptedRevision: 5, lastCommittedOperationKey: first.operationKey });
    expect(second.operationKey).not.toBe(first.operationKey);
    expect(send.mock.calls.filter(([, init]) => init?.method !== 'POST')).toHaveLength(1);
  });

  it.each([503, 200])('failed or unwitnessed load (%i) keeps editing/save disabled and never posts empty patterns', async (status) => {
    const { library, send, invoke } = fixture();
    send.mockResolvedValue(json({ ui: { excludePatterns: ['untrusted'] } }, status));
    await library.loadExcludes();
    await library.handleApplyExcludes();
    expect(library.excludesLoaded).toBe(false);
    expect(library.excludesText).toBe('');
    expect(library.error).toBeTruthy();
    expect(send).toHaveBeenCalledTimes(1);
    expect(invoke).not.toHaveBeenCalled();
  });

  it('preserves a conflicting draft and blocks effects until explicit reload and a new attempt', async () => {
    const { library, send, invoke } = fixture();
    send.mockResolvedValueOnce(json({ ui: { excludePatterns: ['old'] }, witness: observed }))
      .mockResolvedValueOnce(json({ errorCode: 'VERSION_CONFLICT', state: 'FAILED' }, 409))
      .mockResolvedValueOnce(json({ ui: { excludePatterns: ['elsewhere'] }, witness: { acceptedRevision: 6, lastCommittedOperationKey: prior } }))
      .mockImplementationOnce(async (_, init) => complete(init));
    await library.loadExcludes();
    library.excludesText = 'my draft';
    await library.handleApplyExcludes();
    expect(invoke).not.toHaveBeenCalled();
    expect(library.excludesText).toBe('my draft');
    expect(library.excludesLoaded).toBe(false);
    expect(library.error).toContain('Reload and review');
    await library.handlePreviewExcludes();
    expect(send).toHaveBeenCalledTimes(2);
    expect(invoke).not.toHaveBeenCalled();
    await library.loadExcludes();
    expect(library.excludesText).toBe('elsewhere');
    library.excludesText += '\nreviewed';
    await library.handleApplyExcludes();
    expect(invoke).toHaveBeenCalledTimes(1);
    const oldAttempt = JSON.parse(String(send.mock.calls[1]?.[1]?.body));
    const freshAttempt = JSON.parse(String(send.mock.calls[3]?.[1]?.body));
    expect(freshAttempt.operationKey).not.toBe(oldAttempt.operationKey);
    expect(freshAttempt.witness.acceptedRevision).toBe(6);
  });

  it('waits through open202 before Preview and rejects overlapping clicks', async () => {
    const { library, send, invoke } = fixture();
    send.mockResolvedValueOnce(json({ ui: { excludePatterns: [] }, witness: observed }))
      .mockImplementationOnce(async (_, init) => json({ state: 'RUNNING', operationKey: JSON.parse(String(init?.body)).operationKey }, 202))
      .mockImplementationOnce(async (_, init) => complete(init));
    await library.loadExcludes();
    library.excludesText = 'one';
    const result = library.handlePreviewExcludes();
    await vi.advanceTimersByTimeAsync(249);
    expect(library.excludesBusy).toBe(true);
    expect(invoke).not.toHaveBeenCalled();
    await library.handleApplyExcludes();
    await vi.advanceTimersByTimeAsync(1);
    await result;
    expect(invoke).toHaveBeenCalledTimes(1);
    expect(invoke).toHaveBeenCalledWith('core.preview-excludes', {});
    expect(send.mock.calls[1]?.[1]?.body).toBe(send.mock.calls[2]?.[1]?.body);
    expect(library.excludesBusy).toBe(false);
  });

  it('retains uncertain bytes across timeout and a later check instead of minting a new mutation', async () => {
    const { library, send, invoke } = fixture();
    send.mockResolvedValueOnce(json({ ui: { excludePatterns: ['old'] }, witness: observed }))
      .mockImplementationOnce(() => new Promise(() => {}))
      .mockImplementationOnce(async (_, init) => complete(init));
    await library.loadExcludes();
    library.excludesText = 'pending';
    const first = library.handleApplyExcludes();
    await vi.advanceTimersByTimeAsync(10_000);
    await first;
    expect(library.error).toContain('timed out');
    expect(invoke).not.toHaveBeenCalled();
    await library.loadExcludes();
    expect(send).toHaveBeenCalledTimes(2);
    await library.handleApplyExcludes();
    expect(send.mock.calls[2]?.[1]?.body).toBe(send.mock.calls[1]?.[1]?.body);
    expect(invoke).toHaveBeenCalledTimes(1);
    expect(send.mock.calls[1]?.[1]?.signal?.aborted).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('renders disabled controls after failed load and retains a conflicting draft across remount', async () => {
    const { library, send } = fixture();
    send.mockResolvedValueOnce(json({ ui: { excludePatterns: ['base'] }, witness: observed }))
      .mockResolvedValueOnce(json({ errorCode: 'VERSION_CONFLICT', state: 'FAILED' }, 409));
    await library.loadExcludes();
    library.excludesText = 'my unsaved draft';
    await library.handleApplyExcludes();
    document.body.appendChild(library);
    await library.updateComplete;
    const textarea = library.shadowRoot?.querySelector('textarea');
    expect(textarea?.disabled).toBe(true);
    expect(textarea?.value).toBe('my unsaved draft');
    expect((library.shadowRoot?.querySelector('jf-button[label="Apply"]') as HTMLElement & { disabled: boolean }).disabled).toBe(true);
    expect((library.shadowRoot?.querySelector('jf-button[label="Reload saved patterns"]') as HTMLElement & { disabled: boolean }).disabled).toBe(false);
    expect(send.mock.calls.filter(([path]) => path === '/api/settings/v2')).toHaveLength(2);
    library.remove();
    document.body.appendChild(library);
    await library.updateComplete;
    expect(library.excludesText).toBe('my unsaved draft');
    expect(send.mock.calls.filter(([path]) => path === '/api/settings/v2')).toHaveLength(2);
  });

  it('unblocks reload after a stalled GET and ignores its late result after a newer observation', async () => {
    const { library, send } = fixture();
    let release!: (response: Response) => void;
    send.mockImplementationOnce(() => new Promise((resolve) => { release = resolve; }))
      .mockResolvedValueOnce(json({ ui: { excludePatterns: ['fresh'] }, witness: observed }));
    const first = library.loadExcludes();
    await vi.advanceTimersByTimeAsync(10_000);
    await first;
    expect(library.excludesBusy).toBe(false);
    expect(library.excludesLoaded).toBe(false);
    expect(send.mock.calls[0]?.[1]?.signal?.aborted).toBe(true);
    await library.loadExcludes();
    expect(library.excludesText).toBe('fresh');
    release(json({ ui: { excludePatterns: ['late-old'] }, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } }));
    await vi.advanceTimersByTimeAsync(0);
    expect(library.excludesText).toBe('fresh');
    expect(library.excludesLoaded).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
  });
});
