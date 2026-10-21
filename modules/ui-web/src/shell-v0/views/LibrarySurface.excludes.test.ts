// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import './LibrarySurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { OperationClient } from '../operations/OperationClient.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';

interface Editor extends HTMLElement {
  host_: PluginHostApi; excludesText: string; error: string | null;
  updateComplete: Promise<unknown>; loadExcludes(): Promise<void>; refresh(): Promise<void>;
  handleSaveExcludes?: () => Promise<void>;
  handlePreviewExcludes(): Promise<void>; handleApplyExcludes(): Promise<void>;
}
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const result = { dryRun: false, patterns: 1, rootsProcessed: 1, deletedByPathJobs: 0,
  deletedById: 2, matchedFiles: 3, perPattern: [{ pattern: '*.log', matches: 3 }],
  capped: false, confirmedRemovals: 2, uncheckedScopes: [] };
function fixture() {
  const send = vi.fn<PluginHostApi['data']['fetch']>().mockImplementation(async (_, init) => {
    if (init?.body) {
      const attempt = JSON.parse(String(init.body));
      return json({ state: 'COMPLETE', operationKey: attempt.operationKey,
        witness: { acceptedRevision: attempt.witness.acceptedRevision + 1, lastCommittedOperationKey: attempt.operationKey } });
    }
    return json({ ui: { excludePatterns: ['*.log'] }, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } });
  });
  const invoke = vi.fn().mockResolvedValue({ success: true, structuredData: result });
  const confirm = vi.fn().mockResolvedValue(true);
  const el = document.createElement('jf-library-surface') as Editor;
  el.host_ = createMockHostApi({ data: { fetch: send, invokeOperation: invoke }, ui: { showConfirmDialog: confirm } });
  el.refresh = vi.fn().mockResolvedValue(undefined);
  return { el, send, invoke, confirm };
}
afterEach(() => { document.body.innerHTML = ''; vi.restoreAllMocks(); });

it('S32.1 recovers the real OperationClient 428 only after typed consent', async () => {
  const { el, confirm, send } = fixture();
  const transport = vi.fn<typeof fetch>()
    .mockResolvedValueOnce(json({ success: false, errorClass: 'CONFIRMATION_REQUIRED',
      message: 'Typed confirmation required', gateBehavior: 'TYPED_CONFIRM', pendingId: 'pending-excludes' }, 428))
    .mockResolvedValueOnce(json({ capsule: 'approved-capsule' }))
    .mockResolvedValueOnce(json({ success: true, structuredData: result }));
  const client = new OperationClient({ apiBase: '', fetchImpl: transport });
  el.host_.data.invokeOperation = (id, args, opts) => client.invokeWithConsent(id, { args }, opts);
  await el.loadExcludes();
  await el.handleApplyExcludes();
  expect(el.error, 'S32.1: the 428 must recover through typed consent').toBeNull();
  expect(confirm).toHaveBeenCalledWith(expect.stringContaining('saved patterns'),
    { confirmLabel: 'Apply', destructive: true, typedConfirmWord: 'core.apply-excludes' });
  expect(transport.mock.calls.map(([url]) => url)).toEqual([
    '/api/operations/core.apply-excludes/invoke', '/api/authorizations/approve', '/api/operations/core.apply-excludes/invoke',
  ]);
  expect(send).toHaveBeenCalledTimes(1);
});

it('S32.1 cancelling typed Apply removes nothing and never saves a draft', async () => {
  const { el, confirm, invoke, send } = fixture();
  confirm.mockResolvedValue(false);
  await el.loadExcludes();
  await el.handleApplyExcludes();
  expect(invoke).not.toHaveBeenCalled();
  expect(send).toHaveBeenCalledTimes(1);
  expect(el.error).toBeNull();
});

it('S32.2 Save is explicit and unsaved drafts disable Preview and Apply', async () => {
  const { el, send, invoke } = fixture();
  await el.loadExcludes();
  el.excludesText = '*.tmp';
  document.body.appendChild(el); await el.updateComplete;
  const root = el.shadowRoot!;
  expect(root.querySelector('jf-button[label="Save patterns"]')).not.toBeNull();
  expect(root.textContent).toContain('Save changes what future indexing skips');
  for (const label of ['Preview', 'Apply']) {
    expect((root.querySelector(`jf-button[label="${label}"]`) as HTMLElement & { disabled: boolean }).disabled).toBe(true);
  }
  await el.handlePreviewExcludes(); await el.handleApplyExcludes();
  expect(send.mock.calls.filter(([path]) => path === '/api/settings/v2')).toHaveLength(1);
  expect(invoke).not.toHaveBeenCalled();
});

it('S32.3 WX1 renders confirmed scope counts and every unchecked scope without claiming documents', async () => {
  const { el, invoke } = fixture();
  const scopes = [
    { path: '/offline', status: 'not_checked', reason: 'Watched folder is offline or missing' },
    { path: '/docs/refused.log', status: 'not_removed', reason: 'Engine refused deletion' },
  ];
  invoke.mockResolvedValue({ success: true, structuredData: { ...result, uncheckedScopes: scopes } });
  await el.loadExcludes(); await el.handleApplyExcludes();
  document.body.appendChild(el); await el.updateComplete;
  const output = el.shadowRoot?.querySelector('[data-testid="excludes-result"]');
  expect(output?.textContent).toContain('Partial Apply');
  expect(output?.textContent).toContain('2 matching files or folders confirmed no longer searchable');
  for (const scope of scopes) { expect(output?.textContent).toContain(scope.path); expect(output?.textContent).toContain(scope.reason); }
  expect(output?.getAttribute('role')).toBe('status');
});

it('S32.3 an old or missing result is unconfirmed rather than a complete Apply', async () => {
  const { el, invoke } = fixture();
  invoke.mockResolvedValue({ success: true, structuredData: { deletedById: 3 } });
  await el.loadExcludes(); await el.handleApplyExcludes();
  document.body.appendChild(el); await el.updateComplete;
  expect(el.shadowRoot?.querySelector('[data-testid="excludes-result"]')?.textContent).toContain('unconfirmed');
});

it('WX2 explains buffered removals and keeps them out of the completed count', async () => {
  const { el, invoke } = fixture();
  invoke.mockResolvedValue({ success: true, structuredData: { ...result, confirmedRemovals: 0,
    uncheckedScopes: [{ path: '/docs/pending.log', status: 'not_removed',
      reason: 'Removal completes when the index rebuild finishes' }] } });
  await el.loadExcludes(); await el.handleApplyExcludes();
  document.body.appendChild(el); await el.updateComplete;
  const text = el.shadowRoot?.querySelector('[data-testid="excludes-result"]')?.textContent;
  expect(text).toContain('Partial Apply');
  expect(text).toContain('0 matching files or folders confirmed no longer searchable');
  expect(text).toContain('/docs/pending.log');
  expect(text).toContain('Removal completes when the index rebuild finishes');
  expect(el.shadowRoot?.querySelector('#excludes-help')?.textContent)
    .toContain('During an index rebuild, removal may wait until the rebuild finishes.');
});

it('S32.6 invalid saves keep the draft editable with an inline announced error', async () => {
  const { el, send } = fixture();
  await el.loadExcludes(); el.excludesText = '[broken';
  send.mockResolvedValueOnce(json({ errorCode: 'INVALID_REQUEST', error: 'Invalid settings request' }, 400));
  expect(el.handleSaveExcludes).toBeTypeOf('function');
  await el.handleSaveExcludes?.();
  document.body.appendChild(el); await el.updateComplete;
  const field = el.shadowRoot?.querySelector('textarea');
  expect(field?.value).toBe('[broken'); expect(field?.disabled).toBe(false);
  expect(field?.getAttribute('aria-invalid')).toBe('true');
  expect(el.shadowRoot?.querySelector('#excludes-error[role="alert"]')?.textContent).toContain('Invalid settings request');
});

it('S32.5 keeps the conflicting draft while reviewing latest settings before a fresh Save', async () => {
  const { el, send } = fixture();
  await el.loadExcludes(); el.excludesText = 'my draft';
  send.mockResolvedValueOnce(json({ errorCode: 'VERSION_CONFLICT', state: 'FAILED' }, 409));
  expect(el.handleSaveExcludes).toBeTypeOf('function');
  await el.handleSaveExcludes?.();
  document.body.appendChild(el); await el.updateComplete;
  const review = el.shadowRoot?.querySelector('jf-button[label="Review latest settings, keep draft"]') as HTMLElement & { onActivate?: () => void };
  expect(review).toBeTruthy();
  send.mockResolvedValueOnce(json({ ui: { excludePatterns: ['elsewhere'] },
    witness: { acceptedRevision: 1, lastCommittedOperationKey: '01993ba0-0000-7000-8000-000000000001' } }));
  review.onActivate?.();
  for (let i = 0; i < 12; i++) { await Promise.resolve(); await el.updateComplete; }
  expect(el.excludesText).toBe('my draft');
  expect(el.shadowRoot?.querySelector('details')?.textContent).toContain('elsewhere');
  send.mockImplementationOnce(async (_, init) => {
    const attempt = JSON.parse(String(init?.body));
    return json({ state: 'COMPLETE', operationKey: attempt.operationKey,
      witness: { acceptedRevision: 2, lastCommittedOperationKey: attempt.operationKey } });
  });
  await el.handleSaveExcludes?.();
  const writes = send.mock.calls.filter(([, init]) => init?.method === 'POST');
  expect(writes).toHaveLength(2);
  expect(JSON.parse(String(writes[1]?.[1]?.body)).ui.excludePatterns).toEqual(['my draft']);
  expect(JSON.parse(String(writes[1]?.[1]?.body)).witness.acceptedRevision).toBe(1);
  expect(el.excludesText).toBe('my draft');
});

it('S32.7 announces busy and the final result once while Apply blocks overlapping actions', async () => {
  const { el, invoke, confirm } = fixture();
  await el.loadExcludes(); document.body.appendChild(el); await el.updateComplete;
  let finish!: (value: unknown) => void;
  invoke.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  const applying = el.handleApplyExcludes();
  for (let i = 0; i < 12; i++) { await Promise.resolve(); await el.updateComplete; }
  try {
    const statuses = el.shadowRoot?.querySelectorAll('[data-testid="excludes-result"][role="status"]');
    expect(statuses).toHaveLength(1);
    expect(statuses?.[0]?.textContent).toContain('Working on exclusions');
    for (const label of ['Save patterns', 'Preview', 'Apply', 'Reload saved patterns']) {
      expect((el.shadowRoot?.querySelector(`jf-button[label="${label}"]`) as HTMLElement & { disabled: boolean }).disabled).toBe(true);
    }
    await el.handleApplyExcludes(); await el.handlePreviewExcludes();
    expect(confirm).toHaveBeenCalledTimes(1); expect(invoke).toHaveBeenCalledTimes(1);
  } finally {
    finish({ success: true, structuredData: result });
    await applying;
  }
  const statuses = el.shadowRoot?.querySelectorAll('[data-testid="excludes-result"][role="status"]');
  await el.updateComplete;
  expect(statuses?.[0]?.textContent).toContain('Apply complete');
  expect(statuses?.[0]?.getAttribute('aria-atomic')).toBe('true');
});
