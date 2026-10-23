// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import './LibrarySurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';

interface Editor extends HTMLElement {
  host_: PluginHostApi; excludesText: string; updateComplete: Promise<unknown>;
  loadExcludes(): Promise<void>; refresh(): Promise<void>; handleSaveExcludes(): Promise<void>;
  handlePreviewExcludes(): Promise<void>; handleApplyExcludes(): Promise<void>;
}
type Policy = { source: string; sourceDetail: string | null; patterns: string[] };
const json = (body: unknown) => new Response(JSON.stringify(body));
function fixture(policy: Policy) {
  let saved = ['saved-only.txt'];
  const send = vi.fn<PluginHostApi['data']['fetch']>().mockImplementation(async (path, init) => {
    if (path === '/api/indexing/excludes') return json(policy);
    if (init?.body) {
      const attempt = JSON.parse(String(init.body)); saved = attempt.ui.excludePatterns;
      if (policy.source === 'settings.json') policy.patterns = saved;
      return json({ state: 'COMPLETE', operationKey: attempt.operationKey,
        witness: { acceptedRevision: 1, lastCommittedOperationKey: attempt.operationKey } });
    }
    return json({ ui: { excludePatterns: saved }, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } });
  });
  const invoke = vi.fn().mockImplementation(async (id) => ({ success: true, structuredData: {
    dryRun: id === 'core.preview-excludes', matchedFiles: 0, confirmedRemovals: 0,
    capped: false, perPattern: [], uncheckedScopes: [], effectivePolicy: policy,
  } }));
  const confirm = vi.fn().mockResolvedValue(true);
  const el = document.createElement('jf-library-surface') as Editor;
  el.host_ = createMockHostApi({ data: { fetch: send, invokeOperation: invoke }, ui: { showConfirmDialog: confirm } });
  el.refresh = vi.fn().mockResolvedValue(undefined);
  return { el, send, invoke, confirm, policy };
}
afterEach(() => { document.body.innerHTML = ''; vi.restoreAllMocks(); });

for (const [source, sourceDetail, pattern, sourceName] of [
  ['env_var', 'JUSTSEARCH_UI_EXCLUDE_PATTERNS', 'env-only.txt', 'environment variable'],
  ['jvm_arg', 'justsearch.ui.exclude_patterns', 'system-only.txt', 'system property'],
] as const) {
  it(`OV1 names the ${source} policy before actions and in Preview, Apply and confirmation`, async () => {
    const { el, confirm } = fixture({ source, sourceDetail, patterns: [pattern] });
    await el.loadExcludes(); document.body.appendChild(el); await el.updateComplete;
    const notice = el.shadowRoot?.querySelector('[data-testid="excludes-override"]');
    expect(notice, 'OV1: override policy must be visible before Preview or Apply').not.toBeNull();
    expect(notice?.textContent).toContain(sourceName); expect(notice?.textContent).toContain(sourceDetail);
    expect(notice?.textContent).toContain(pattern);
    expect(notice?.getAttribute('tabindex')).toBe('0');
    expect(notice?.getAttribute('aria-label'), 'OV1: the focusable notice needs a screen-reader name').not.toBeNull();
    expect(notice?.getAttribute('aria-label')).toContain(sourceDetail);
    expect(notice?.getAttribute('aria-label')).toContain(pattern);
    expect(el.shadowRoot?.querySelector('textarea')?.getAttribute('aria-describedby')).toContain('excludes-override');
    expect(el.excludesText).toBe('saved-only.txt');
    expect(el.shadowRoot?.querySelector('#excludes-help')?.textContent).not.toContain('using the saved list');
    await el.handlePreviewExcludes(); await el.updateComplete;
    expect(el.shadowRoot?.querySelector('[data-testid="excludes-result"]')?.textContent).toContain(pattern);
    await el.handleApplyExcludes(); await el.updateComplete;
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining(pattern), expect.objectContaining({ typedConfirmWord: 'core.apply-excludes' }));
    expect(confirm.mock.calls[0]?.[0]).toContain(sourceDetail);
    expect(el.shadowRoot?.querySelector('[data-testid="excludes-result"]')?.textContent).toContain(pattern);
  });
}
it('OV1 Save retains the override and explains when the saved list takes effect', async () => {
  const { el, send } = fixture({ source: 'env_var', sourceDetail: 'JUSTSEARCH_UI_EXCLUDE_PATTERNS', patterns: ['env-only.txt'] });
  await el.loadExcludes(); document.body.appendChild(el); await el.updateComplete;
  el.excludesText = 'next-saved.txt'; await el.handleSaveExcludes(); await el.updateComplete;
  const writes = send.mock.calls.filter(([, init]) => init?.method === 'POST');
  expect(writes).toHaveLength(1);
  expect(JSON.parse(String(writes[0]?.[1]?.body)).ui.excludePatterns).toEqual(['next-saved.txt']);
  const text = el.shadowRoot?.querySelector('[data-testid="excludes-result"]')?.textContent;
  expect(text).toContain('override'); expect(text).toContain('removed');
  expect(text).not.toContain('Future indexing skips these matches');
  expect(el.shadowRoot?.querySelector('[data-testid="excludes-override"]')?.textContent).toContain('env-only.txt');
});
it('OV1 without an override, including after restart, keeps the saved-list behavior', async () => {
  const { el, confirm } = fixture({ source: 'settings.json', sourceDetail: null, patterns: ['saved-only.txt'] });
  await el.loadExcludes(); document.body.appendChild(el); await el.updateComplete;
  expect(el.shadowRoot?.querySelector('[data-testid="excludes-override"]')).toBeNull();
  expect(el.shadowRoot?.querySelector('#excludes-help')?.textContent).toContain('using the saved list');
  await el.handleApplyExcludes();
  expect(confirm).toHaveBeenCalledWith(expect.stringContaining('Apply saved patterns'), expect.anything());
});
it('OV1 an empty override is still named and is not replaced by the saved list', async () => {
  const { el, confirm } = fixture({ source: 'env_var', sourceDetail: 'JUSTSEARCH_UI_EXCLUDE_PATTERNS', patterns: [] });
  await el.loadExcludes(); document.body.appendChild(el); await el.updateComplete;
  const notice = el.shadowRoot?.querySelector('[data-testid="excludes-override"]');
  expect(notice, 'OV1: even an empty override must be shown before acting').not.toBeNull();
  expect(notice?.textContent).toContain('(none)');
  await el.handleApplyExcludes();
  expect(confirm.mock.calls[0]?.[0]).toContain('(none)');
});

it('OV1 refuses Preview and Apply when the effective policy cannot be read', async () => {
  const { el, send, invoke, confirm } = fixture({ source: 'env_var', sourceDetail: 'JUSTSEARCH_UI_EXCLUDE_PATTERNS', patterns: ['env-only.txt'] });
  const original = send.getMockImplementation()!;
  send.mockImplementation((path, init) => path === '/api/indexing/excludes'
    ? Promise.resolve(new Response('unavailable', { status: 503 })) : original(path, init));
  await el.loadExcludes(); document.body.appendChild(el); await el.updateComplete;
  expect((el.shadowRoot?.querySelector('jf-button[label="Apply"]') as HTMLElement & { disabled: boolean }).disabled).toBe(true);
  await el.handleApplyExcludes(); await el.handlePreviewExcludes();
  expect(invoke).not.toHaveBeenCalled(); expect(confirm).not.toHaveBeenCalled();
  expect(el.shadowRoot?.querySelector('[role="alert"]')?.textContent).toContain('policy');
});
