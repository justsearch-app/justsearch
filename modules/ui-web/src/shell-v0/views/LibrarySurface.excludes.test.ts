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
type Pressable = HTMLElement & { onActivate?: () => unknown };
async function settle(el: Editor) { for (let i = 0; i < 30; i++) { await Promise.resolve(); await el.updateComplete; } }
function control(root: ParentNode | null | undefined, name: string): Pressable {
  const found = [...(root?.querySelectorAll<Pressable>('button, jf-button') ?? [])].find(c =>
    c.textContent?.trim() === name || c.getAttribute('label') === name || c.getAttribute('aria-label') === name);
  expect(found, name).toBeDefined(); return found!;
}
/** Add *.tmp and leave its field: the save asks in place whether to remove matching read files. */
async function addRuleAndAsk(el: Editor): Promise<HTMLElement> {
  el.excludesText = '*.log\n*.tmp'; await settle(el);
  const field = el.shadowRoot!.querySelector<HTMLInputElement>('input[aria-label="Skip rule 2"]');
  expect(field).toBeTruthy(); field!.focus(); field!.blur(); await settle(el);
  const ask = el.shadowRoot!.querySelector<HTMLElement>('[role="region"][aria-label^="Remove files already read"]');
  expect(ask, 'the in-place removal ask').toBeTruthy(); return ask!;
}
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const result = { dryRun: false, patterns: 1, rootsProcessed: 1, deletedByPathJobs: 0,
  deletedById: 2, matchedFiles: 3, perPattern: [{ pattern: '*.log', matches: 3 }],
  capped: false, confirmedRemovals: 2, uncheckedScopes: [] };
function fixture() {
  // Policy observation is independent of the retained settings-attempt transport.
  let savedPatterns: string[] = [];
  let witness: { acceptedRevision: number; lastCommittedOperationKey: string | null } = { acceptedRevision: 0, lastCommittedOperationKey: null };
  const send = vi.fn<PluginHostApi['data']['fetch']>().mockImplementation(async (_, init) => {
    if (init?.body) {
      const attempt = JSON.parse(String(init.body));
      witness = { acceptedRevision: attempt.witness.acceptedRevision + 1, lastCommittedOperationKey: attempt.operationKey };
      return json({ state: 'COMPLETE', operationKey: attempt.operationKey, witness });
    }
    return json({ ui: { excludePatterns: savedPatterns.length ? savedPatterns : ['*.log'] }, witness });
  });
  const fetchPolicy: PluginHostApi['data']['fetch'] = async (path, init) => {
    if (path === '/api/indexing/excludes') return json({ source: 'settings.json', sourceDetail: null, patterns: savedPatterns });
    const response = await send(path, init);
    const body = await response.clone().json();
    if (body.ui?.excludePatterns) savedPatterns = body.ui.excludePatterns;
    if (body.state === 'COMPLETE' && init?.body) savedPatterns = JSON.parse(String(init.body)).ui.excludePatterns;
    return response;
  };
  const invoke = vi.fn().mockResolvedValue({ success: true, structuredData: result });
  const confirm = vi.fn().mockResolvedValue(true);
  const el = document.createElement('jf-library-surface') as Editor;
  el.host_ = createMockHostApi({ data: { fetch: fetchPolicy, invokeOperation: invoke }, ui: { showConfirmDialog: confirm } });
  el.refresh = vi.fn().mockResolvedValue(undefined);
  return { el, send, invoke, confirm };
}
afterEach(() => { document.body.innerHTML = ''; vi.restoreAllMocks(); });

it('S32.1 recovers the real OperationClient 428 after the in-place answer, without a second dialog', async () => {
  const { el, confirm, send } = fixture();
  const transport = vi.fn<typeof fetch>()
    .mockResolvedValueOnce(json({ success: false, errorClass: 'CONFIRMATION_REQUIRED',
      message: 'Typed confirmation required', gateBehavior: 'TYPED_CONFIRM', pendingId: 'pending-excludes' }, 428))
    .mockResolvedValueOnce(json({ capsule: 'approved-capsule' }))
    .mockResolvedValueOnce(json({ success: true, structuredData: result }));
  const client = new OperationClient({ apiBase: '', fetchImpl: transport });
  el.host_.data.invokeOperation = (id, args, opts) => client.invokeWithConsent(id, { args }, opts);
  await el.loadExcludes(); document.body.appendChild(el); await settle(el);
  const ask = await addRuleAndAsk(el);
  control(ask, 'Remove from search').click(); await settle(el);
  expect(el.error, 'S32.1: the 428 must recover through the consented answer').toBeNull();
  expect(confirm, 'the in-place answer is the consent').not.toHaveBeenCalled();
  expect(transport.mock.calls.map(([url]) => url)).toEqual([
    '/api/operations/core.apply-excludes/invoke', '/api/authorizations/approve', '/api/operations/core.apply-excludes/invoke',
  ]);
  expect(send.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
});

it('S32.1 Keep them removes nothing and never saves again', async () => {
  const { el, confirm, invoke, send } = fixture();
  await el.loadExcludes(); document.body.appendChild(el); await settle(el);
  const ask = await addRuleAndAsk(el);
  control(ask, 'Keep them').click(); await settle(el);
  expect(invoke).not.toHaveBeenCalled();
  expect(confirm).not.toHaveBeenCalled();
  expect(send.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
  expect(el.error).toBeNull();
});

it('S32.2 there is no Save, Preview or Apply; a draft is not saved before its field is left', async () => {
  const { el, send, invoke } = fixture();
  await el.loadExcludes();
  el.excludesText = '*.tmp';
  document.body.appendChild(el); await el.updateComplete;
  const root = el.shadowRoot!;
  for (const label of ['Save patterns', 'Preview', 'Apply', 'Reload saved patterns']) expect(root.querySelector(`jf-button[label="${label}"]`)).toBeNull();
  expect(root.textContent).toContain('A rule is saved when you leave its field');
  await el.handlePreviewExcludes(); await el.handleApplyExcludes();
  expect(send.mock.calls.filter(([path, init]) => path === '/api/settings/v2' && init?.signal)).toHaveLength(1);
  expect(invoke).not.toHaveBeenCalled();
});

it('S32.3 WX1 a partial removal says how many were confirmed and names every scope not checked or not removed', async () => {
  const { el, invoke } = fixture();
  const scopes = [
    { path: '/offline', status: 'not_checked', reason: 'Watched folder is offline or missing' },
    { path: '/docs/refused.log', status: 'not_removed', reason: 'Engine refused deletion' },
  ];
  invoke.mockResolvedValue({ success: true, structuredData: { ...result, uncheckedScopes: scopes } });
  await el.loadExcludes(); document.body.appendChild(el); await settle(el);
  control(await addRuleAndAsk(el), 'Remove from search').click(); await settle(el);
  const output = el.shadowRoot?.querySelector('.removal-slot [role="status"]');
  expect(output).toBeTruthy();
  expect(output?.textContent).toContain('Partly done');
  expect(output?.textContent).toContain('2 files removed from search');
  expect(output?.textContent).toContain('Offline or missing folder, not checked: /offline');
  expect(output?.textContent).toContain("Couldn't be removed: /docs/refused.log");
  for (const scope of scopes) expect([...output!.querySelectorAll('li')].some(li => li.getAttribute('title') === scope.reason)).toBe(true);
});

it('S32.3 an old or missing result is not sure rather than a complete removal', async () => {
  const { el, invoke } = fixture();
  invoke.mockResolvedValue({ success: true, structuredData: { deletedById: 3 } });
  await el.loadExcludes(); document.body.appendChild(el); await settle(el);
  control(await addRuleAndAsk(el), 'Remove from search').click(); await settle(el);
  expect(el.shadowRoot?.querySelector('.removal-slot')?.textContent).toContain('Not sure the files were removed');
});

it('WX2 explains buffered removals and keeps them out of the completed count', async () => {
  const { el, invoke } = fixture();
  invoke.mockResolvedValue({ success: true, structuredData: { ...result, confirmedRemovals: 0,
    uncheckedScopes: [{ path: '/docs/pending.log', status: 'not_removed',
      reason: 'Removal completes when the index rebuild finishes' }] } });
  await el.loadExcludes(); document.body.appendChild(el); await settle(el);
  control(await addRuleAndAsk(el), 'Remove from search').click(); await settle(el);
  const text = el.shadowRoot?.querySelector('.removal-slot')?.textContent;
  expect(text).toContain('Partly done');
  expect(text).toContain('0 files removed from search');
  expect(text).toContain('Waits for the rebuild to finish: /docs/pending.log');
  expect(el.shadowRoot?.querySelector('#excludes-help')?.textContent)
    .toContain('During a rebuild of search data, removal may wait');
});

it('S32.6 an incomplete rule keeps the draft editable with an inline announced reason and is not sent', async () => {
  const { el, send } = fixture();
  await el.loadExcludes(); el.excludesText = '[broken';
  await el.handleSaveExcludes?.();
  document.body.appendChild(el); await el.updateComplete;
  const field = el.shadowRoot?.querySelector<HTMLInputElement>('.skip-rule input');
  expect(field?.value).toBe('[broken'); expect(field?.disabled).toBe(false);
  expect(field?.getAttribute('aria-invalid')).toBe('true');
  expect(el.shadowRoot?.querySelector('#rule-problem-0[role="alert"]')?.textContent).toContain('a [ needs a closing ]');
  expect(send.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0);
});

it('S32.5 keeps the conflicting draft, shows the saved rules, and Keep my draft saves on their revision', async () => {
  const { el, send } = fixture();
  await el.loadExcludes(); el.excludesText = 'my draft';
  send.mockResolvedValueOnce(json({ errorCode: 'VERSION_CONFLICT', state: 'FAILED' }, 409))
    .mockResolvedValueOnce(json({ ui: { excludePatterns: ['elsewhere'] },
      witness: { acceptedRevision: 1, lastCommittedOperationKey: '01993ba0-0000-7000-8000-000000000001' } }));
  await el.handleSaveExcludes?.();
  document.body.appendChild(el); await settle(el);
  const conflict = el.shadowRoot?.querySelector('.skip-conflict');
  expect(conflict?.textContent).toContain('Changed elsewhere');
  expect(conflict?.textContent).toContain('elsewhere');
  expect(el.excludesText).toBe('my draft');
  send.mockImplementationOnce(async (_, init) => {
    const attempt = JSON.parse(String(init?.body));
    return json({ state: 'COMPLETE', operationKey: attempt.operationKey,
      witness: { acceptedRevision: 2, lastCommittedOperationKey: attempt.operationKey } });
  });
  control(conflict, 'Keep my draft').click(); await settle(el);
  const writes = send.mock.calls.filter(([, init]) => init?.method === 'POST');
  expect(writes).toHaveLength(2);
  expect(JSON.parse(String(writes[1]?.[1]?.body)).ui.excludePatterns).toEqual(['my draft']);
  expect(JSON.parse(String(writes[1]?.[1]?.body)).witness.acceptedRevision).toBe(1);
  expect(el.excludesText).toBe('my draft');
});

it('S32.7 announces the final outcome once while a removal in flight ignores repeated answers', async () => {
  const { el, invoke, confirm } = fixture();
  await el.loadExcludes(); document.body.appendChild(el); await settle(el);
  const ask = await addRuleAndAsk(el);
  let finish!: (value: unknown) => void;
  invoke.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  control(ask, 'Remove from search').click(); await settle(el);
  try {
    expect(control(ask, 'Remove from search').getAttribute('aria-disabled')).toBe('true');
    control(ask, 'Remove from search').click(); control(ask, 'Keep them').click(); await settle(el);
    expect(invoke).toHaveBeenCalledTimes(1);
    expect(confirm).not.toHaveBeenCalled();
  } finally {
    finish({ success: true, structuredData: result });
  }
  await settle(el);
  const statuses = el.shadowRoot?.querySelectorAll('.removal-slot [role="status"]');
  expect(statuses).toHaveLength(1);
  expect(statuses?.[0]?.textContent).toContain('Done. 2 files removed from search');
  expect(el.shadowRoot?.querySelector('.removal-slot')?.getAttribute('aria-live')).toBe('polite');
});
