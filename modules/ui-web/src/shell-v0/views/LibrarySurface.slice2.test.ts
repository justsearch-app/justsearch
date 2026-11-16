// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { requestIndexingRuleFocus, takeIndexingRuleFocus } from '../router/indexingRuleIntent.js';
import { afterEach, expect, it, vi } from 'vitest';
import './LibrarySurface.js';
import './SettingsSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { __feedForTest, __resetAiStateForTest, type StatusSnapshot } from '../state/aiStateStore.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import type { IndexingRulesEditor } from '../components/IndexingRulesEditor.js';
import type { LibrarySurface } from './LibrarySurface.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };
type Button = HTMLElement & { onActivate(): unknown; disabled: boolean };
const initialPolicy = { paused: true, reason: 'SETTINGS_RECOVERY', untilEpochMs: 2000000000000,
  schedule: null as null | { enabled: boolean; days: number[]; startMinute: number; endMinute: number }, pauseOnBattery: false };
const key = (revision: number) => `0194f72c-0000-7000-8000-${String(revision).padStart(12, '0')}`;
async function settle(el: Mounted): Promise<void> {
  for (let i = 0; i < 30; ++i) { await Promise.resolve(); await el.updateComplete; }
  await new Promise<void>(resolve => requestAnimationFrame(() => resolve()));
}
function action(el: Mounted, label: string): Button {
  const button = el.shadowRoot!.querySelector<Button>(`jf-button[label="${label}"]`);
  expect(button, label).not.toBeNull(); return button!;
}
function editor(el: Mounted): IndexingRulesEditor {
  const result = el.shadowRoot!.querySelector<IndexingRulesEditor>('jf-indexing-rules-editor');
  expect(result, 'Library owns the rules editor').not.toBeNull(); return result!;
}
async function editBattery(el: IndexingRulesEditor, checked: boolean): Promise<void> {
  const field = el.shadowRoot!.querySelector<HTMLInputElement>('#indexing-battery')!;
  field.checked = checked; field.dispatchEvent(new Event('change')); await settle(el);
}
/** Slice 6b-2: rules save as they change; a time still being typed saves on submit or leaving its field. */
async function saveRules(el: IndexingRulesEditor): Promise<void> {
  el.shadowRoot!.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await settle(el);
}
/** Slice 6b-2: a skip-rule draft saves when its field is left. */
function leaveSkip(el: LibrarySurface): void {
  const field = el.shadowRoot!.querySelector<HTMLInputElement>('.skip-rule input');
  expect(field, 'a skip-rule field').not.toBeNull(); field!.focus(); field!.blur();
}
async function saveSkip(el: LibrarySurface): Promise<void> { leaveSkip(el); await settle(el); }
function checkBattery(el: IndexingRulesEditor, checked: boolean): void {
  const field = el.shadowRoot!.querySelector<HTMLInputElement>('#indexing-battery')!;
  field.checked = checked; field.dispatchEvent(new Event('change'));
}
const conflictText = (rules: IndexingRulesEditor) => rules.shadowRoot!.querySelector('[aria-label="Saved reading rules"]')?.textContent ?? '';
function fixture(receipt: 'matching' | 'missing' | 'gap' | 'wrong key' = 'matching', schedule: typeof initialPolicy.schedule = null) {
  let revision = 0, lastKey: string | null = null;
  let ui = { excludePatterns: ['*.old'], indexingPause: { ...structuredClone(initialPolicy), schedule } };
  let releaseRule: (() => void) | null = null;
  let ruleWait: Promise<void> | null = null;
  const wireUi = () => {
    const copy = structuredClone(ui);
    if (copy.indexingPause.schedule === null) delete (copy.indexingPause as { schedule?: unknown }).schedule;
    return copy;
  };
  const fetchSettings = vi.fn<PluginHostApi['data']['fetch']>(async (path, init) => {
    if (path === '/api/indexing/excludes') return Response.json({ source: 'settings.json', sourceDetail: null, patterns: ui.excludePatterns });
    if (path.startsWith('/api/indexing/forgets?')) return Response.json({ items: [], complete: true, nextId: null });
    if (path !== '/api/settings/v2') return Response.json({ items: [] });
    if (init?.method === 'POST') {
      const attempt = JSON.parse(String(init.body));
      if (attempt.witness.acceptedRevision !== revision) return Response.json({ state: 'FAILED', errorCode: 'VERSION_CONFLICT' }, { status: 409 });
      ui = { ...ui, excludePatterns: attempt.ui.excludePatterns }; ++revision; lastKey = attempt.operationKey;
      return Response.json({ ui: wireUi(), state: 'COMPLETE', operationKey: lastKey, witness: { acceptedRevision: revision, lastCommittedOperationKey: lastKey } });
    }
    return Response.json({ ui: wireUi(), witness: { acceptedRevision: revision, lastCommittedOperationKey: lastKey } });
  });
  const operations = vi.fn(async (_path: RequestInfo | URL, init?: RequestInit) => {
    const { args, idempotencyKey } = JSON.parse(String(init?.body));
    const operation = String(_path).split('/').at(-2);
    if (ruleWait) await ruleWait;
    // The rule write carries the revision its draft was based on (B6').
    if (operation === 'core.set-indexing-rules' && args.witness && args.witness.acceptedRevision !== revision) {
      return Response.json({ success: false, message: 'Settings changed since rules were prepared', errorClass: 'HANDLER_FAILURE', errorCode: 'VERSION_CONFLICT' });
    }
    if (operation === 'core.set-indexing-rules') ui = { ...ui, indexingPause: { ...ui.indexingPause, schedule: args.schedule, pauseOnBattery: args.pauseOnBattery } };
    else ui = { ...ui, indexingPause: { ...ui.indexingPause, paused: operation === 'core.indexing-pause',
      reason: operation === 'core.indexing-pause' ? 'USER_PAUSED' : null as unknown as string, untilEpochMs: args.untilEpochMs ?? null } };
    lastKey = idempotencyKey ?? key(revision + 1); ++revision;
    return Response.json({ success: true, message: 'Accepted', structuredData: receipt === 'missing' ? {}
      : { witness: { acceptedRevision: receipt === 'gap' ? revision + 1 : revision,
        lastCommittedOperationKey: receipt === 'wrong key' ? key(999) : lastKey } } });
  });
  vi.stubGlobal('fetch', operations);
  const host = createMockHostApi({ data: { fetch: fetchSettings } });
  return { host, fetchSettings, operations,
    externalRules: () => { ui.indexingPause.pauseOnBattery = true; lastKey = key(++revision); },
    externalHours: () => { ui.indexingPause = { ...ui.indexingPause, schedule: { enabled: true, days: [6], startMinute: 60, endMinute: 120 } }; lastKey = key(++revision); },
    unrelatedChange: () => { lastKey = key(++revision); },
    externalChange: () => { ui = { ...ui, excludePatterns: ['*.external'] }; lastKey = key(++revision); },
    read: () => structuredClone(ui),
    holdRules: () => { ruleWait = new Promise<void>(resolve => { releaseRule = resolve; }); },
    releaseRules: () => { releaseRule!(); ruleWait = null; } };
}
async function mount(host: PluginHostApi): Promise<LibrarySurface> {
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.host_ = host;
  Object.assign(el, { refresh: vi.fn().mockResolvedValue(true), loading: false });
  document.body.append(el); await settle(el); await settle(editor(el)); return el;
}
afterEach(async () => {
  document.body.replaceChildren();
  // Slice 6b-2: leaving Library saves a draft still being typed; let that settle on the stubs.
  for (let i = 0; i < 5; i++) await new Promise(resolve => setTimeout(resolve, 0));
  __resetAiStateForTest(); vi.unstubAllGlobals(); vi.restoreAllMocks();
});

it('B1 edits one rule per line; typing saves nothing until a field is left; removing a rule saves', async () => {
  const f = fixture(); const el = await mount(f.host);
  const first = el.shadowRoot!.querySelector<HTMLInputElement>('.skip-rule input')!;
  first.value = 'node_modules/'; first.dispatchEvent(new Event('input')); await settle(el);
  action(el, 'Add a rule').onActivate(); await settle(el);
  const inputs = el.shadowRoot!.querySelectorAll<HTMLInputElement>('.skip-rule input');
  expect(inputs).toHaveLength(2); expect(el.shadowRoot!.activeElement).toBe(inputs[1]);
  inputs[1]!.value = '*.tmp'; inputs[1]!.dispatchEvent(new Event('input')); await settle(el);
  expect(f.read().excludePatterns).toEqual(['*.old']);
  expect(el.shadowRoot!.querySelector('jf-button[label="Save patterns"]')).toBeNull();
  action(el, 'Remove skip rule 1').onActivate(); await settle(el);
  expect(el.excludesText).toBe('*.tmp');
  expect(f.read().excludePatterns).toEqual(['*.tmp']);
});

it.each(['rules first', 'exclusions first'] as const)('L19 %s saves both editors without a local conflict and keeps edits after reload', async order => {
  const f = fixture(); const el = await mount(f.host); const rules = editor(el);
  el.excludesText = '*.new';
  if (order === 'rules first') {
    await editBattery(rules, true); await saveSkip(el);
    expect(el.excludesText).toBe('*.new');
    expect(el.shadowRoot!.querySelector('#excludes-error')).toBeNull();
    expect(f.read().excludePatterns).toEqual(['*.new']);
  } else {
    await saveSkip(el); await editBattery(rules, true);
  }
  const expected = { excludePatterns: ['*.new'], indexingPause: { ...initialPolicy, pauseOnBattery: true } };
  expect(rules.indexingRulesError).toBe('');
  expect(el.shadowRoot!.querySelector('#excludes-error')).toBeNull();
  expect(f.read()).toEqual(expected);
  // Both editors must adopt each local successor, including when exclusions saved first.
  el.excludesText = '*.again'; await saveSkip(el);
  expect(el.shadowRoot!.querySelector('#excludes-error')).toBeNull();
  expect(f.read().excludePatterns).toEqual(['*.again']);
  el.excludesText = '*.new'; await saveSkip(el);
  el.remove(); const reloaded = await mount(f.host);
  expect(reloaded.excludesText).toBe('*.new');
  expect(editor(reloaded).indexingRules.pauseOnBattery).toBe(true);
  expect(f.read().indexingPause).toEqual(expected.indexingPause);
  // Library hands its own successor revision to the hours editor: no refused round trip either way.
  expect(f.operations).toHaveBeenCalledTimes(1);
  expect(JSON.parse(String(f.operations.mock.calls[0]![1]?.body)).args).toEqual({ schedule: null, pauseOnBattery: true,
    witness: order === 'rules first' ? { acceptedRevision: 0, lastCommittedOperationKey: null }
      : { acceptedRevision: 1, lastCommittedOperationKey: expect.any(String) } });
});

it('L12 L19 concurrent saves and tab changes retain one rule request and both outcomes', async () => {
  const f = fixture(); const el = await mount(f.host); const rules = editor(el);
  f.holdRules(); await editBattery(rules, true); await saveRules(rules); await saveRules(rules);
  el.excludesText = '*.new'; await saveSkip(el);
  el.activeTab = 'core.browse-surface'; await settle(el);
  expect(f.operations).toHaveBeenCalledTimes(1); expect(rules.indexingRulesSaving).toBe(true);
  f.releaseRules(); await settle(rules); el.activeTab = 'folders'; await settle(el);
  expect(editor(el)).toBe(rules); expect(rules.shadowRoot!.textContent).toContain('Reading rules saved');
  expect(f.read()).toEqual({ excludePatterns: ['*.new'], indexingPause: { ...initialPolicy, pauseOnBattery: true } });
});

it('L12 reload during a rule save reads its result while a second window keeps an edited draft', async () => {
  const f = fixture(); const first = await mount(f.host); const second = await mount(f.host);
  editor(second).shadowRoot!.querySelector<HTMLInputElement>('input[type=time]')!.value = '21:00';
  editor(second).shadowRoot!.querySelector<HTMLInputElement>('input[type=time]')!.dispatchEvent(new Event('input'));
  await settle(editor(second)); f.holdRules(); await editBattery(editor(first), true); first.remove();
  f.releaseRules(); await settle(editor(first));
  const reloaded = await mount(f.host);
  expect(editor(reloaded).indexingRules.pauseOnBattery).toBe(true);
  expect(editor(second).indexingRules.start).toBe('21:00');
  expect(f.operations).toHaveBeenCalledTimes(1);
});

it('L14 Settings links to the Library editor and recovery stays in Settings after a rule save', async () => {
  const f = fixture();
  __feedForTest({ status: { operating: { indexing: { state: 'PAUSED', reasons: ['settings_recovery'] } },
    worker: { core: { indexState: 'IDLE' } } } as unknown as StatusSnapshot });
  const settings = document.createElement('jf-settings-surface') as Mounted;
  Object.assign(settings, { host_: f.host, activeCategory: 'indexing' });
  document.body.append(settings); await settle(settings);
  expect(settings.shadowRoot!.querySelector('form[aria-label="Reading rules"]')).toBeNull();
  const navigate = vi.fn(); document.addEventListener('jf-navigate-to-surface', navigate);
  try { action(settings, 'Change when to read files').onActivate(); }
  finally { document.removeEventListener('jf-navigate-to-surface', navigate); }
  expect(navigate).toHaveBeenCalledWith(expect.objectContaining({ detail: { surfaceId: 'core.library-surface' } }));
  expect(takeIndexingRuleFocus()).toBe('hours');
  const library = await mount(f.host); await editBattery(editor(library), true); await saveRules(editor(library));
  const controls = library.shadowRoot!.querySelector<Mounted>('jf-indexing-controls:not([toolbar])')!;
  await settle(controls); expect(controls.shadowRoot!.textContent).toContain('Open Settings at Reset to defaults');
  expect(f.read().indexingPause.reason).toBe('SETTINGS_RECOVERY');
});

for (const tab of ['folders', 'core.browse-surface']) {
  it(`L14 consumes a live focus request once from ${tab}, coalescing quick requests`, async () => {
    const f = fixture(); const el = await mount(f.host);
    el.activeTab = tab; await settle(el);
    const focus = vi.spyOn(editor(el), 'focusIndexingRule');
    requestIndexingRuleFocus('hours'); requestIndexingRuleFocus('battery');
    await settle(el);
    expect(focus).toHaveBeenCalledExactlyOnceWith('battery');
    expect(el.activeTab).toBe('folders');
    await vi.waitFor(() => expect(editor(el).shadowRoot!.activeElement?.id).toBe('indexing-battery'));
    expect(takeIndexingRuleFocus()).toBeUndefined();
    el.remove(); document.body.append(el); await settle(el);
    expect(focus).toHaveBeenCalledTimes(1);
  });
}
it('L14 drains only the latest pending request when Library connects', async () => {
  requestIndexingRuleFocus('hours'); requestIndexingRuleFocus('battery');
  const f = fixture(); const el = await mount(f.host);
  await vi.waitFor(() => expect(editor(el).shadowRoot!.activeElement?.id).toBe('indexing-battery'));
  expect(takeIndexingRuleFocus()).toBeUndefined();
  el.remove(); document.body.append(el); await settle(el);
  expect(editor(el).shadowRoot!.activeElement?.id).not.toBe('indexing-battery');
});

it.each(['rules first', 'exclusions first'] as const)('V3 quick successive saves %s share only local witnesses', async order => {
  const f = fixture(); const el = await mount(f.host); const rules = editor(el);
  el.excludesText = '*.quick'; await settle(el);
  if (order === 'rules first') { checkBattery(rules, true); leaveSkip(el); }
  else { leaveSkip(el); checkBattery(rules, true); }
  await settle(el); await settle(rules); await settle(el);
  expect(el.shadowRoot!.querySelector('#excludes-error')).toBeNull();
  expect(rules.indexingRulesError).toBe('');
  expect(f.read()).toEqual({ excludePatterns: ['*.quick'], indexingPause: { ...initialPolicy, pauseOnBattery: true } });
  el.excludesText = '*.after-quick'; await saveSkip(el);
  expect(el.shadowRoot!.querySelector('#excludes-error')).toBeNull();
  expect(f.read().excludePatterns).toEqual(['*.after-quick']);
  el.remove(); const reloaded = await mount(f.host);
  expect(reloaded.excludesText).toBe('*.after-quick');
  expect(editor(reloaded).indexingRules.pauseOnBattery).toBe(true);
});

it.each(['rules', 'exclusions'])('V3 genuine external change conflicts with %s and retains both drafts', async changed => {
  const f = fixture(); const el = await mount(f.host); const rules = editor(el);
  el.excludesText = '*.mine'; await settle(el);
  if (changed === 'rules') f.externalHours(); else f.externalChange();
  if (changed === 'rules') {
    await editBattery(rules, true);
    expect(conflictText(rules)).toContain('Changed elsewhere');
    expect(f.operations, 'one write, refused on its stale revision').toHaveBeenCalledTimes(1);
  } else {
    await saveSkip(el);
    expect(el.shadowRoot!.querySelector('.skip-conflict')?.textContent).toContain('Changed elsewhere');
    await editBattery(rules, true);
  }
  expect(f.read().excludePatterns).toEqual(changed === 'exclusions' ? ['*.external'] : ['*.old']);
  expect(f.read().indexingPause.pauseOnBattery).toBe(changed === 'exclusions');
  expect(f.read().indexingPause.schedule?.days ?? null).toEqual(changed === 'rules' ? [6] : null);
  expect(el.excludesText).toBe('*.mine'); expect(rules.indexingRules.pauseOnBattery).toBe(true);
});

async function manualPause(el: LibrarySurface, paused: boolean): Promise<void> {
  const control = el.shadowRoot!.querySelector<Mounted>('jf-indexing-controls[toolbar]')!;
  __feedForTest({ status: {
    worker: { core: { indexState: 'IDLE', indexHealthy: true } },
    operating: { indexing: { state: paused ? 'IDLE' : 'PAUSED', reasons: paused ? [] : ['user_paused'] } },
  } as unknown as StatusSnapshot });
  await settle(el); await settle(control);
  control.shadowRoot!.querySelector<HTMLButtonElement>('#pause-toggle')!.click(); await settle(control);
  if (paused) { control.shadowRoot!.querySelector<HTMLButtonElement>('.pause-options button')!.click(); await settle(control); }
}

it('B own Pause then Resume then time save, and time save then Pause then skip save, chain committed witnesses', async () => {
  const f = fixture(); const el = await mount(f.host); const rules = editor(el);
  el.excludesText = '*.after';
  await manualPause(el, true); await manualPause(el, false);
  await editBattery(rules, true); expect(rules.indexingRulesError).toBe('');
  await manualPause(el, true);
  await saveSkip(el);
  expect(el.shadowRoot!.querySelector('#excludes-error')).toBeNull();
  expect(f.operations.mock.calls.map(([url]) => String(url).split('/').at(-2))).toEqual([
    'core.indexing-pause', 'core.indexing-resume', 'core.set-indexing-rules', 'core.indexing-pause',
  ]);
  el.remove(); const reloaded = await mount(f.host);
  expect(reloaded.excludesText).toBe('*.after');
  expect(editor(reloaded).indexingRules.pauseOnBattery).toBe(true);
});

it('B an unrelated external write is not a conflict for either editor and loses no draft', async () => {
  const f = fixture(); const el = await mount(f.host); const rules = editor(el);
  el.excludesText = '*.mine'; await settle(el); f.unrelatedChange();
  await saveSkip(el);
  expect(el.shadowRoot!.querySelector('.skip-conflict')).toBeNull();
  expect(el.excludesText).toBe('*.mine');
  expect(f.read().excludePatterns).toEqual(['*.mine']);
  f.unrelatedChange();
  await editBattery(rules, true); expect(rules.indexingRulesError).toBe('');
  expect(conflictText(rules)).toBe('');
  expect(f.read().indexingPause).toEqual({ ...initialPolicy, pauseOnBattery: true });
  // Each refused write was based on the draft's revision and sent again only after the saved value was read.
  const posts = f.fetchSettings.mock.calls.filter(([path, init]) => path === '/api/settings/v2' && init?.method === 'POST');
  expect(posts.map(([, init]) => JSON.parse(String(init?.body)).witness.acceptedRevision)).toEqual([0, 1]);
});

it.each(['missing', 'gap', 'wrong key'] as const)('B %s operation witness never advances a skip-rule draft; its refused save reads before sending again', async receipt => {
  const f = fixture(receipt); const el = await mount(f.host); const rules = editor(el);
  el.excludesText = '*.mine'; await editBattery(rules, true);
  const reads = f.fetchSettings.mock.calls.filter(([path, init]) => path === '/api/settings/v2' && init?.method !== 'POST').length;
  await saveSkip(el);
  const posts = f.fetchSettings.mock.calls.filter(([path, init]) => path === '/api/settings/v2' && init?.method === 'POST')
    .map(([, init]) => JSON.parse(String(init?.body)));
  expect(posts[0]?.witness, 'the unverified receipt is not used').toEqual({ acceptedRevision: 0, lastCommittedOperationKey: null });
  expect(f.fetchSettings.mock.calls.filter(([path, init]) => path === '/api/settings/v2' && init?.method !== 'POST')).toHaveLength(reads + 1);
  expect(posts[1]?.witness.acceptedRevision).toBe(1);
  expect(el.shadowRoot!.querySelector('.skip-conflict')).toBeNull();
  expect(el.excludesText).toBe('*.mine');
  expect(f.read().excludePatterns).toEqual(['*.mine']);
});

it('L19 configured disabled schedule survives successive rule saves and skip save', async () => {
  const schedule = { enabled: false, days: [1, 7], startMinute: 1320, endMinute: 420 };
  const f = fixture('matching', schedule); const el = await mount(f.host); const rules = editor(el);
  await editBattery(rules, true); await saveRules(rules);
  expect(rules.indexingRulesError).toBe('');
  await editBattery(rules, false); await saveRules(rules);
  expect(rules.indexingRulesError).toBe('');
  el.excludesText = '*.configured'; await saveSkip(el);
  expect(el.shadowRoot!.querySelector('#excludes-error')).toBeNull();
  el.remove(); const reloaded = await mount(f.host);
  expect(reloaded.excludesText).toBe('*.configured');
  expect(editor(reloaded).indexingRules).toEqual({ enabled: false, days: [1, 7], start: '22:00', end: '07:00', pauseOnBattery: false });
  expect(f.read().indexingPause).toEqual({ ...initialPolicy, schedule });
});

function recoveryButton(rules: IndexingRulesEditor, label: string): HTMLButtonElement {
  const result = [...rules.shadowRoot!.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.trim() === label);
  expect(result, label).toBeDefined(); return result!;
}
async function editStart(rules: IndexingRulesEditor, value: string): Promise<void> {
  const input = rules.shadowRoot!.querySelector<HTMLInputElement>('input[type=time]')!;
  input.value = value; input.dispatchEvent(new Event('input')); await settle(rules);
}

it.each(['Keep my draft', 'Use the saved rules'])('R2 another window changes saved rules; %s recovers and later saves work', async choice => {
  const schedule = { enabled: false, days: [2, 4], startMinute: 1200, endMinute: 360 };
  const f = fixture('matching', schedule); const el = await mount(f.host); const other = await mount(f.host);
  const rules = editor(el); await editStart(rules, '21:00');
  await editBattery(editor(other), true); await saveRules(editor(other));
  await saveRules(rules);
  expect(rules.indexingRules.start).toBe('21:00');
  expect(conflictText(rules)).toContain('Changed elsewhere');
  const conflict = rules.shadowRoot!.querySelector('[aria-label="Saved reading rules"]');
  expect(conflict?.textContent).toContain('Any time');
  expect(conflict?.textContent).toContain('Tuesday, Thursday');
  expect(conflict?.textContent).toContain('20:00'); expect(conflict?.textContent).toContain('06:00');
  expect(conflict?.textContent).toContain('Pause while on battery: on');
  recoveryButton(rules, choice).click(); await settle(rules);
  expect(rules.indexingRulesError).toBe('');
  expect(rules.indexingRules.start).toBe(choice === 'Keep my draft' ? '21:00' : '20:00');
  expect(f.read().indexingPause).toEqual({ ...initialPolicy, schedule: { ...schedule, startMinute: choice === 'Keep my draft' ? 1260 : 1200 }, pauseOnBattery: choice === 'Use the saved rules' });
  await editBattery(rules, false); await saveRules(rules);
  expect(rules.indexingRulesError).toBe('');
  expect(f.read().indexingPause.pauseOnBattery).toBe(false);
  el.remove(); const loaded = await mount(f.host);
  expect(editor(loaded).indexingRules.start).toBe(choice === 'Keep my draft' ? '21:00' : '20:00');
});

it.each(['rules first', 'exclusions first'] as const)('R2 recovered draft and quick skip save (%s) retain both through reload', async order => {
  const f = fixture(); const el = await mount(f.host); const rules = editor(el);
  await editStart(rules, '22:00'); f.externalRules(); await saveRules(rules);
  expect(conflictText(rules)).toContain('Changed elsewhere');
  el.excludesText = '*.recovered'; await settle(el);
  f.holdRules();
  if (order === 'rules first') { recoveryButton(rules, 'Keep my draft').click(); leaveSkip(el); }
  else { leaveSkip(el); recoveryButton(rules, 'Keep my draft').click(); }
  await settle(el); await settle(rules);
  el.activeTab = 'core.browse-surface'; await settle(el);
  f.releaseRules(); await settle(rules); await settle(el);
  expect(rules.indexingRulesError).toBe(''); expect(el.shadowRoot!.querySelector('#excludes-error')).toBeNull();
  expect(f.read()).toEqual({ excludePatterns: ['*.recovered'], indexingPause: { ...initialPolicy,
    schedule: { enabled: false, days: [1, 2, 3, 4, 5, 6, 7], startMinute: 1320, endMinute: 1020 } } });
  el.remove(); const reloaded = await mount(f.host);
  expect(reloaded.excludesText).toBe('*.recovered'); expect(editor(reloaded).indexingRules.start).toBe('22:00');
});

it.each(['Keep my draft', 'Use the saved rules'])('R2b %s never overwrites a later second-window save it did not show', async choice => {
  const f = fixture(); const el = await mount(f.host); const other = await mount(f.host);
  const rules = editor(el), otherRules = editor(other);
  await editStart(rules, '21:00');
  await editBattery(otherRules, true); await saveRules(otherRules); // B
  await saveRules(rules);
  const review = () => rules.shadowRoot!.querySelector('[aria-label="Saved reading rules"]')?.textContent;
  expect(review()).toContain('Pause while on battery: on');
  await editStart(otherRules, '23:00'); await saveRules(otherRules); // C, after B was displayed
  const before = f.read(); const writes = f.operations.mock.calls.length;
  recoveryButton(rules, choice).click(); recoveryButton(rules, choice).click(); await settle(rules);
  // Keep my draft writes on the revision shown (B), which C replaced: refused, so C is not overwritten.
  expect(f.operations).toHaveBeenCalledTimes(choice === 'Keep my draft' ? writes + 1 : writes);
  expect(f.read()).toEqual(before);
  if (choice === 'Keep my draft') {
    expect(rules.indexingRules.start).toBe('21:00');
    expect(review()).toContain('23:00');
    expect(review()).toContain('Changed elsewhere');
    recoveryButton(rules, choice).click(); await settle(rules);
    expect(f.operations).toHaveBeenCalledTimes(writes + 2);
    expect(f.read().indexingPause).toEqual({ ...initialPolicy, schedule: {
      enabled: false, days: [1, 2, 3, 4, 5, 6, 7], startMinute: 1260, endMinute: 1020,
    } });
  } else {
    expect(rules.indexingRules.start).toBe('23:00');
    expect(rules.indexingRules.pauseOnBattery).toBe(true);
  }
  expect(rules.indexingRulesError).toBe('');
  el.remove(); const reloaded = await mount(f.host);
  expect(editor(reloaded).indexingRules.start).toBe(choice === 'Keep my draft' ? '21:00' : '23:00');
  expect(f.read().indexingPause.reason).toBe('SETTINGS_RECOVERY');
});

it('R2 reconnect keeps an hours draft still being typed and offers the current saved rules', async () => {
  const f = fixture(); const el = await mount(f.host); const rules = editor(el);
  await editStart(rules, '21:00'); f.externalRules();
  // Leaving Library leaves the time field: its save finds the change made elsewhere.
  el.remove(); await settle(rules); document.body.append(el); await settle(el); await settle(rules);
  expect(rules.indexingRules.start).toBe('21:00');
  expect(conflictText(rules)).toContain('Pause while on battery: on');
  expect(f.read().indexingPause.pauseOnBattery, 'nothing overwritten').toBe(true);
  recoveryButton(rules, 'Keep my draft').click(); await settle(rules);
  expect(rules.indexingRulesError).toBe('');
  expect(f.read().indexingPause).toEqual({ ...initialPolicy,
    schedule: { enabled: false, days: [1, 2, 3, 4, 5, 6, 7], startMinute: 1260, endMinute: 1020 } });
});
