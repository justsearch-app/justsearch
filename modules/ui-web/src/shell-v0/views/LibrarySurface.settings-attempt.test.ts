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
  excludesError: string | null;
  updateComplete: Promise<unknown>;
  refresh(): Promise<void>;
  loadExcludes(): Promise<void>;
  handleSaveExcludes(): Promise<void>;
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
  // Policy observation is independent of the retained settings-attempt transport.
  let savedPatterns: string[] = [];
  const fetchPolicy: PluginHostApi['data']['fetch'] = async (path, init) => {
    if (path.startsWith('/api/indexing/forgets?')) return json({ items: [], complete: true, nextId: null });
    if (path === '/api/indexing/excludes') return json({ source: 'settings.json', sourceDetail: null, patterns: savedPatterns });
    const response = await send(path, init);
    const body = await response.clone().json();
    if (body.ui?.excludePatterns) savedPatterns = body.ui.excludePatterns;
    if (body.state === 'COMPLETE' && init?.body) savedPatterns = JSON.parse(String(init.body)).ui.excludePatterns;
    return response;
  };
  const invoke = vi.fn().mockResolvedValue({ success: true });
  const library = document.createElement('jf-library-surface') as LibrarySettings;
  library.host_ = createMockHostApi({ data: { fetch: fetchPolicy, invokeOperation: invoke }, ui: { showConfirmDialog: vi.fn().mockResolvedValue(true) } });
  library.refresh = vi.fn().mockResolvedValue(undefined);
  return { library, send, invoke };
}
beforeEach(() => vi.useFakeTimers());
afterEach(() => { document.body.innerHTML = ''; vi.useRealTimers(); vi.restoreAllMocks(); });

describe('Library retained-base exclusions', () => {
  it('RF55 exact rows visibly distinguish trailing whitespace, control characters and slash pairs', async () => {
    const { library, send } = fixture();
    const paths = ['/docs/name', '/docs/name ', '/docs/back\\name', '/docs/back/name', '/docs/tab\t',
      '/docs/name\u00a0', '/docs/name\u200b'];
    const entries = paths.map((path, index) => ({ literal: { version: 1,
      id: `aa10bcec-4102-47f8-a26d-d02346c8a21${index}`, path, scope: 'FILE' } }));
    send.mockResolvedValueOnce(json({ ui: { excludePatterns: entries }, witness: observed }))
      .mockResolvedValue(json({}));
    await library.loadExcludes();
    expect(library.excludesLoaded).toBe(true);
    document.body.appendChild(library);
    await library.updateComplete;
    const names = Array.from(library.shadowRoot?.querySelectorAll('section[aria-label="Exact paths kept out"] code') ?? [],
      (node) => node.textContent);
    expect(names).toEqual(['"/docs/name"', '"/docs/name " (1 trailing whitespace character)',
      '"/docs/back\\\\name"', '"/docs/back/name"', '"/docs/tab\\t" (1 trailing whitespace character)',
      '"/docs/name\\u00a0" (1 trailing whitespace character)', '"/docs/name\\u200b"']);
    expect(new Set(names).size).toBe(paths.length);
    expect(library.excludesText).toBe('');
    library.remove();
    await vi.advanceTimersByTimeAsync(0);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('RF55 keeps exact path objects out of the glob editor and retains their bytes on a glob save', async () => {
    const { library, send } = fixture();
    const literal = { literal: { version: 1, id: 'aa10bcec-4102-47f8-a26d-d02346c8a210',
      path: '/docs/report\\name[1].txt ', scope: 'FILE' } };
    send.mockResolvedValueOnce(json({ ui: { excludePatterns: ['*.log', literal] }, witness: observed }))
      .mockImplementation(async (_, init) => init?.method === 'POST' ? complete(init) : json({}));
    await library.loadExcludes();
    expect(library.excludesLoaded, 'typed exact entries must load as a policy').toBe(true);
    document.body.appendChild(library);
    await library.updateComplete;
    expect(library.excludesText).toBe('*.log');
    expect(library.shadowRoot?.querySelector('section[aria-label="Exact paths kept out"] code')?.textContent)
      .toBe('"/docs/report\\\\name[1].txt " (1 trailing whitespace character)');
    library.excludesText = '*.log\n*.tmp';
    await library.handleSaveExcludes();
    const write = send.mock.calls.find(([, init]) => init?.method === 'POST');
    expect(JSON.parse(String(write?.[1]?.body)).ui.excludePatterns)
      .toEqual(['*.log', '*.tmp', literal]);
    library.remove();
    await vi.advanceTimersByTimeAsync(0);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('RF55 removes a precise keep-out only through the witnessed save without editing glob text', async () => {
    const { library, send } = fixture();
    const literal = { literal: { version: 1, id: 'aa10bcec-4102-47f8-a26d-d02346c8a211',
      path: '/docs/folder ', scope: 'FOLDER' } };
    send.mockResolvedValueOnce(json({ ui: { excludePatterns: ['*.log', literal] }, witness: observed }))
      .mockImplementation(async (_, init) => init?.method === 'POST' ? complete(init) : json({}));
    await library.loadExcludes();
    expect(library.excludesLoaded).toBe(true);
    document.body.appendChild(library);
    await library.updateComplete;
    const remove = library.shadowRoot?.querySelector('section[aria-label="Exact paths kept out"] jf-button') as
      HTMLElement & { onActivate: () => void };
    expect(remove).toBeTruthy();
    remove.onActivate();
    expect(send.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0);
    await library.handleSaveExcludes();
    const write = send.mock.calls.find(([, init]) => init?.method === 'POST');
    expect(JSON.parse(String(write?.[1]?.body)).ui.excludePatterns).toEqual(['*.log']);
    expect(library.excludesText).toBe('*.log');
    library.remove();
    await vi.advanceTimersByTimeAsync(0);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('AX1 editor preserves the saved list and names every literal fallback warning', async () => {
    const { library, send } = fixture();
    const patterns = ['[legacy', 'private', '{broken'];
    send.mockResolvedValueOnce(json({ ui: { excludePatterns: patterns,
      invalidExcludePatterns: ['[legacy', '{broken'] }, witness: observed }));
    await library.loadExcludes();
    document.body.appendChild(library);
    await library.updateComplete;
    expect(Array.from(library.shadowRoot?.querySelectorAll<HTMLInputElement>('.skip-rule input') ?? [], field => field.value)).toEqual(patterns);
    const warnings = library.shadowRoot?.querySelectorAll('[role="status"] li');
    expect(Array.from(warnings ?? [], (node) => node.textContent)).toEqual([
      '“[legacy” has invalid pattern syntax and is matched as a literal name. Correct or remove it.',
      '“{broken” has invalid pattern syntax and is matched as a literal name. Correct or remove it.',
    ]);
    library.remove();
    await vi.advanceTimersByTimeAsync(0);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('AX1 unchanged legacy list can be previewed and applied without replacing the saved policy', async () => {
    const { library, send, invoke } = fixture();
    send.mockResolvedValueOnce(json({ ui: { excludePatterns: ['[legacy', 'private'],
      invalidExcludePatterns: ['[legacy'] }, witness: observed }));
    await library.loadExcludes();
    await library.handlePreviewExcludes();
    await library.handleApplyExcludes();
    expect(send).toHaveBeenCalledTimes(1);
    expect(invoke).toHaveBeenNthCalledWith(1, 'core.preview-excludes', {});
    expect(invoke).toHaveBeenNthCalledWith(2, 'core.apply-excludes', {}, { consented: true });
    expect(library.excludesText).toBe('[legacy\nprivate');
    expect(vi.getTimerCount()).toBe(0);
  });

  it('AX2 pattern help explains character classes and alternatives for previously saved lists', async () => {
    const { library, send } = fixture();
    send.mockResolvedValueOnce(json({ ui: { excludePatterns: ['report[1].txt'] }, witness: observed }));
    await library.loadExcludes();
    document.body.appendChild(library);
    await library.updateComplete;
    const help = library.shadowRoot?.textContent?.replace(/\s+/g, ' ');
    expect(help).toContain('[ab] matches one listed character');
    expect(help).toContain('[a-z] matches a range');
    expect(help).toContain('[!ab] matches a character not listed');
    expect(help).toContain('{draft,final} matches either alternative');
    expect(help).toContain('These meanings also apply to patterns saved by earlier versions.');
    library.remove();
    await vi.advanceTimersByTimeAsync(0);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('keeps the witness read with the draft and never refreshes it before saving', async () => {
    const { library, send, invoke } = fixture();
    send.mockResolvedValueOnce(json({ ui: { excludePatterns: ['old'] }, witness: observed }))
      .mockImplementationOnce(async (_, init) => complete(init))
      .mockImplementationOnce(async (_, init) => complete(init));
    await library.loadExcludes();
    library.excludesText = 'old\nnew';
    await library.handleSaveExcludes();
    const first = JSON.parse(String(send.mock.calls[1]?.[1]?.body));
    expect(first.witness).toEqual(observed);
    expect(first.ui.excludePatterns).toEqual(['old', 'new']);
    expect(invoke).not.toHaveBeenCalled();
    library.excludesText = 'third';
    await library.handleSaveExcludes();
    const second = JSON.parse(String(send.mock.calls[2]?.[1]?.body));
    expect(second.witness).toEqual({ acceptedRevision: 5, lastCommittedOperationKey: first.operationKey });
    expect(second.operationKey).not.toBe(first.operationKey);
    expect(send.mock.calls.filter(([, init]) => init?.method !== 'POST')).toHaveLength(1);
  });

  it.each([503, 200])('failed or unwitnessed load (%i) keeps editing/save disabled and never posts empty patterns', async (status) => {
    const { library, send, invoke } = fixture();
    send.mockResolvedValue(json({ ui: { excludePatterns: ['untrusted'] } }, status));
    await library.loadExcludes();
    await library.handleSaveExcludes();
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
    await library.handleSaveExcludes();
    expect(invoke).not.toHaveBeenCalled();
    expect(library.excludesText).toBe('my draft');
    expect(library.excludesLoaded).toBe(false);
    expect(library.excludesError).toContain('Reload and review');
    await library.handleSaveExcludes();
    expect(send).toHaveBeenCalledTimes(2);
    expect(invoke).not.toHaveBeenCalled();
    await library.loadExcludes();
    expect(library.excludesText).toBe('elsewhere');
    library.excludesText += '\nreviewed';
    await library.handleSaveExcludes();
    expect(invoke).not.toHaveBeenCalled();
    const oldAttempt = JSON.parse(String(send.mock.calls[1]?.[1]?.body));
    const freshAttempt = JSON.parse(String(send.mock.calls[3]?.[1]?.body));
    expect(freshAttempt.operationKey).not.toBe(oldAttempt.operationKey);
    expect(freshAttempt.witness.acceptedRevision).toBe(6);
  });

  it('waits through open202 during Save and rejects overlapping clicks', async () => {
    const { library, send, invoke } = fixture();
    send.mockResolvedValueOnce(json({ ui: { excludePatterns: [] }, witness: observed }))
      .mockImplementationOnce(async (_, init) => json({ state: 'RUNNING', operationKey: JSON.parse(String(init?.body)).operationKey }, 202))
      .mockImplementationOnce(async (_, init) => complete(init));
    await library.loadExcludes();
    library.excludesText = 'one';
    const result = library.handleSaveExcludes();
    await vi.advanceTimersByTimeAsync(249);
    expect(library.excludesBusy).toBe(true);
    expect(invoke).not.toHaveBeenCalled();
    await library.handleSaveExcludes();
    await vi.advanceTimersByTimeAsync(1);
    await result;
    expect(invoke).not.toHaveBeenCalled();
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
    const first = library.handleSaveExcludes();
    await vi.advanceTimersByTimeAsync(10_000);
    await first;
    expect(library.excludesError).toContain('timed out');
    expect(invoke).not.toHaveBeenCalled();
    await library.loadExcludes();
    expect(send).toHaveBeenCalledTimes(2);
    await library.handleSaveExcludes();
    expect(send.mock.calls[2]?.[1]?.body).toBe(send.mock.calls[1]?.[1]?.body);
    expect(invoke).not.toHaveBeenCalled();
    expect(send.mock.calls[1]?.[1]?.signal?.aborted).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('renders disabled controls after failed load and retains a conflicting draft across remount', async () => {
    const { library, send } = fixture();
    send.mockResolvedValueOnce(json({ ui: { excludePatterns: ['base'] }, witness: observed }))
      .mockResolvedValueOnce(json({ errorCode: 'VERSION_CONFLICT', state: 'FAILED' }, 409));
    await library.loadExcludes();
    library.excludesText = 'my unsaved draft';
    await library.handleSaveExcludes();
    document.body.appendChild(library);
    await library.updateComplete;
    const textarea = library.shadowRoot?.querySelector<HTMLInputElement>('.skip-rule input');
    expect(textarea?.disabled).toBe(true);
    expect(textarea?.value).toBe('my unsaved draft');
    expect((library.shadowRoot?.querySelector('jf-button[label="Apply"]') as HTMLElement & { disabled: boolean }).disabled).toBe(true);
    expect((library.shadowRoot?.querySelector('jf-button[label="Reload saved patterns"]') as HTMLElement & { disabled: boolean }).disabled).toBe(false);
    expect(send.mock.calls.filter(([path, init]) => path === '/api/settings/v2' && init?.signal)).toHaveLength(2);
    library.remove();
    document.body.appendChild(library);
    await library.updateComplete;
    expect(library.excludesText).toBe('my unsaved draft');
    expect(send.mock.calls.filter(([path, init]) => path === '/api/settings/v2' && init?.signal)).toHaveLength(2);
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

  for (const [kind, count, makePattern, message] of [
    ['input', 513, (i: number) => `file${i}.txt`, 'Invalid settings request'],
    ['expanded', 257, (i: number) => `directory${i}`, 'Invalid settings request'],
  ] as const) {
    it(`EX4 editor reports ${kind} limit refusal without applying or discarding the draft`, async () => {
      const { library, send, invoke } = fixture();
      send.mockResolvedValueOnce(json({ ui: { excludePatterns: ['saved'] }, witness: observed }))
        .mockResolvedValueOnce(json({ errorCode: 'INVALID_REQUEST', error: message }, 400));
      await library.loadExcludes();
      const patterns = Array.from({ length: count }, (_, index) => makePattern(index));
      const draft = patterns.join('\n');
      library.excludesText = draft;
      await library.handleSaveExcludes();
      expect(JSON.parse(String(send.mock.calls[1]?.[1]?.body)).ui.excludePatterns).toEqual(patterns);
      expect(invoke).not.toHaveBeenCalled();
      expect(library.excludesText).toBe(draft);
      expect(library.excludesError).toBe(message);
      expect(library.excludesBusy).toBe(false);
      document.body.appendChild(library);
      await library.updateComplete;
      expect(library.shadowRoot?.querySelector('#excludes-error[role="alert"]')?.textContent).toContain(message);
      library.remove();
      await vi.advanceTimersByTimeAsync(0);
      expect(vi.getTimerCount()).toBe(0);
    });
  }

});
