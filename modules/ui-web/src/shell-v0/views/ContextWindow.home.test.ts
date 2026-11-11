// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import './SettingsSurface.js';
import './BrainSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { NAVIGATE_TO_SURFACE_EVENT } from '../controllers/navigateRequest.js';
import { takeMemberTabIntent } from '../router/memberTabIntent.js';
import { __feedForTest, __feedContactForTest, __tickClockForTest, __resetAiStateForTest, type InferenceSnapshot } from '../state/aiStateStore.js';
import { __seedForTest, __resetForTest } from '../../i18n/resourceCatalog.js';
import { searchRegister } from './settingsRegister.js';

type Surface = HTMLElement & { updateComplete: Promise<unknown>; activeCategory: string; activeTab: string };
let saved: number;
let refuse: number;
let holdSaves: boolean;
let pendingSaves: Array<{ body: { operationKey: string; llm: { contextWindow: number } }; release: (response: Response) => void }>;
const witness = { acceptedRevision: 0, lastCommittedOperationKey: null };
const json = (data: unknown, status = 200) => Response.json(data, { status });
async function settle(el: Surface) {
  await el.updateComplete;
  await new Promise(resolve => setTimeout(resolve, 0));
  await el.updateComplete;
}
async function mount(tag: string) {
  const el = document.createElement(tag) as Surface;
  const host = createMockHostApi();
  host.data.fetch = (path, init) => fetch(path, { ...init, body: typeof init?.body === 'object' ? JSON.stringify(init.body) : init?.body });
  Object.assign(el, { host_: host, apiBase: '', activeCategory: 'agent' });
  document.body.append(el); await settle(el); return el;
}
async function advanced(el: Surface) {
  el.shadowRoot!.querySelector<HTMLButtonElement>('.brain-links button')?.click(); await settle(el);
}
function field(el: Surface) {
  const input = el.shadowRoot!.querySelector<HTMLInputElement>('#brain-contextWindow');
  expect(input, 'Reading memory is available in Answers').not.toBeNull(); return input!;
}
async function activate(el: Surface, label: string) {
  const button = [...el.shadowRoot!.querySelectorAll<HTMLElement & { label: string; onActivate: () => void }>('jf-button')].find(b => b.label === label);
  expect(button, `the ${label} link exists`).toBeDefined(); button!.onActivate(); await settle(el);
}
function engine(tokens: number | null, reason = 'override') {
  __feedForTest({ inference: { mode: tokens == null ? 'offline' : 'online', available: tokens != null,
    llmContextTokens: tokens, contextWindow: tokens == null ? null : { rung: tokens, reason, slots: 2, kvType: 'q8_0' },
  } as unknown as InferenceSnapshot }); __feedContactForTest();
}
beforeEach(() => {
  saved = 4096; refuse = 0; holdSaves = false; pendingSaves = []; __resetAiStateForTest();
  takeMemberTabIntent('core.brain-surface');
  const labels = Object.fromEntries(readFileSync('../app-api/src/main/resources/messages/registry-surface.en.properties', 'utf8')
    .split('\n').filter(line => line.startsWith('settings.')).map(line => { const i = line.indexOf('='); return [line.slice(0, i), line.slice(i + 1).trim()]; }));
  __seedForTest(labels);
  vi.stubGlobal('fetch', vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
    if (String(url).endsWith('/api/settings/v2')) {
      if (init?.method === 'POST') {
        const body = JSON.parse(String(init.body));
        if (holdSaves) return new Promise<Response>(release => pendingSaves.push({ body, release }));
        if (refuse) return json({ error: 'Reading memory was refused', errorCode: refuse === 409 ? 'VERSION_CONFLICT' : 'SAVE_FAILED' }, refuse);
        saved = body.llm.contextWindow;
        return json({ state: 'COMPLETE', operationKey: body.operationKey,
          witness: { acceptedRevision: 1, lastCommittedOperationKey: body.operationKey }, llm: { contextWindow: saved } });
      }
      return json({ ui: {}, llm: { contextWindow: saved }, witness });
    }
    return json({});
  }));
});

describe('Reading memory save ownership', () => {
  async function change(brain: Surface, value: number) {
    field(brain).value = String(value);
    field(brain).dispatchEvent(new Event('input'));
    field(brain).dispatchEvent(new Event('change'));
    await settle(brain);
  }
  async function finish(brain: Surface, status = 200) {
    await vi.waitFor(() => expect(pendingSaves).toHaveLength(1));
    const request = pendingSaves.shift()!;
    if (status === 200) {
      saved = request.body.llm.contextWindow;
      request.release(json({ state: 'COMPLETE', operationKey: request.body.operationKey,
        witness: { acceptedRevision: 1, lastCommittedOperationKey: request.body.operationKey }, llm: { contextWindow: saved } }));
    } else request.release(json({ error: 'Reading memory was refused', errorCode: status === 409 ? 'VERSION_CONFLICT' : 'SAVE_FAILED' }, status));
    await settle(brain);
  }
  async function poll(brain: Surface) {
    const reads = () => vi.mocked(fetch).mock.calls.filter(([url, init]) => String(url).endsWith('/api/settings/v2') && init?.method !== 'POST').length;
    const before = reads();
    window.dispatchEvent(new Event('focus')); await settle(brain);
    expect(reads()).toBeGreaterThan(before);
  }
  function shown(brain: Surface, value: number, pending = false) {
    expect(field(brain).value).toBe(String(value));
    const row = field(brain).closest('.advanced-row')!;
    expect(row.querySelector('output')?.textContent).toContain(value === 0 ? 'Automatic' : `${new Intl.NumberFormat().format(value)} tokens`);
    if (pending) expect(row.textContent).toMatch(/not yet saved/i);
    else expect(row.textContent).not.toMatch(/not yet saved/i);
  }
  function error(brain: Surface, status: number) {
    expect(brain.shadowRoot!.querySelector('jf-error-alert')?.textContent).toMatch(status === 409 ? /Reload and review/ : /refused/);
  }
  it.each([500, 409])('one pending change then refusal (%i) restores saved state and accepts the next poll', async status => {
    const brain = await mount('jf-brain-surface'); await advanced(brain); holdSaves = true;
    await change(brain, 4097);
    await finish(brain, status); shown(brain, 4096); error(brain, status);
    saved = 8192; await poll(brain); shown(brain, 8192); error(brain, status);
  });
  it.each([500, 409])('queued changes refused first (%i) never remain displayed or override a poll', async status => {
    const brain = await mount('jf-brain-surface'); await advanced(brain); holdSaves = true;
    await change(brain, 4097); await change(brain, 4098); await change(brain, 4099);
    await finish(brain, status); shown(brain, 4096); error(brain, status);
    expect(pendingSaves).toHaveLength(0);
    expect(vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
    saved = 8192; await poll(brain); shown(brain, 8192); error(brain, status);
  });
  it.each([500, 409])('queued changes refused last (%i) restore the last successful save', async status => {
    const brain = await mount('jf-brain-surface'); await advanced(brain); holdSaves = true;
    await change(brain, 4097); await change(brain, 4098); await change(brain, 4099);
    await finish(brain); expect(saved).toBe(4097);
    await finish(brain, status); shown(brain, 4097); error(brain, status);
    saved = 8192; await poll(brain); shown(brain, 8192); error(brain, status);
  });
  it('all saves refused, including an explicit retry, leave no queued value behind', async () => {
    const brain = await mount('jf-brain-surface'); await advanced(brain); holdSaves = true;
    for (const value of [4097, 8192]) {
      await change(brain, value); await change(brain, value + 1);
      await finish(brain, 500); shown(brain, 4096); error(brain, 500);
      await poll(brain); shown(brain, 4096); error(brain, 500);
    }
  });
  it('a change queued during a successful save stays explicitly unsaved until its own completion', async () => {
    const brain = await mount('jf-brain-surface'); await advanced(brain); holdSaves = true;
    await change(brain, 4097); await change(brain, 4098); await change(brain, 4099);
    shown(brain, 4099, true);
    await finish(brain); expect(saved).toBe(4097); shown(brain, 4099, true);
    await poll(brain); shown(brain, 4099, true);
    await finish(brain); expect(saved).toBe(4099); shown(brain, 4099);
    await poll(brain); shown(brain, 4099);
  });
  it.each([200, 500])('Automatic during a pending save (%i) owns the latest choice without retaining a numeric draft', async status => {
    const brain = await mount('jf-brain-surface'); await advanced(brain); holdSaves = true;
    await change(brain, 8192);
    const automatic = [...brain.shadowRoot!.querySelectorAll<HTMLElement & { label: string; disabled: boolean }>('jf-button')].find(b => b.label === 'Automatic reading memory')!;
    expect(automatic.disabled).toBe(false);
    await activate(brain, 'Automatic reading memory'); shown(brain, 0, true);
    await finish(brain, status);
    if (status === 200) { shown(brain, 0, true); await finish(brain); shown(brain, 0); }
    else { shown(brain, 4096); error(brain, status); }
    await poll(brain); shown(brain, saved);
  });
  it('a late refusal returns to the newer saved value already observed by a poll', async () => {
    engine(4096);
    const brain = await mount('jf-brain-surface'); await advanced(brain); holdSaves = true;
    await change(brain, 4097); await change(brain, 4098);
    saved = 16384; await poll(brain);
    expect(field(brain).closest('.advanced-row')!.textContent).toContain('Saved: 16,384 tokens.');
    await finish(brain, 409); shown(brain, 16384); error(brain, 409);
    await poll(brain); shown(brain, 16384); error(brain, 409);
  });
});
afterEach(() => { document.body.replaceChildren(); takeMemberTabIntent('core.brain-surface'); __resetAiStateForTest(); __resetForTest(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('Context window has one home', () => {
  it('Settings links to AI Brain and has no second context control', async () => {
    const el = await mount('jf-settings-surface');
    // The former Agent page is split: Agent autonomy lives in Security & privacy, the link in General.
    el.activeCategory = 'security-privacy'; await settle(el);
    expect(el.shadowRoot!.querySelector('[data-settings-anchor="agent-autonomy"]')).not.toBeNull();
    el.activeCategory = 'general'; await settle(el);
    expect(el.shadowRoot!.querySelector('#context-window-override')).toBeNull();
    const navigations: unknown[] = [];
    const listener = (event: Event) => navigations.push((event as CustomEvent).detail);
    document.addEventListener(NAVIGATE_TO_SURFACE_EVENT, listener);
    try { await activate(el, 'Reading memory (context window)'); }
    finally { document.removeEventListener(NAVIGATE_TO_SURFACE_EVENT, listener); }
    expect(navigations).toEqual([{ surfaceId: 'core.brain-surface', state: { tab: 'runtime' } }]);
    await mount('jf-brain-surface'); // Consume this test's cold arrival before the next case.
  });
  it.each(['context window', 'reading memory'])('Settings search finds %s at the link', async query => {
    const hit = searchRegister(query).find(hit => hit.section?.key === 'context-window');
    expect(hit, 'the register finds the context link').toBeDefined();
    const el = await mount('jf-settings-surface');
    el.shadowRoot!.querySelector('jf-settings-nav')!.dispatchEvent(new CustomEvent('search-select', { detail: { categoryId: hit!.category.id, sectionKey: hit!.section!.key } }));
    await settle(el);
    expect(el.shadowRoot!.querySelector('[data-settings-anchor="context-window"]')?.textContent).toContain('Reading memory (context window)');
  });
  it.each(['cold', 'mounted'])('arrival opens Answers and focuses Reading memory when %s', async timing => {
    let brain = timing === 'mounted' ? await mount('jf-brain-surface') : null;
    if (brain) brain.activeTab = 'memory';
    const settings = await mount('jf-settings-surface');
    await activate(settings, 'Reading memory (context window)');
    brain ??= await mount('jf-brain-surface'); await settle(brain);
    expect(brain.activeTab).toBe('runtime');
    const arrived = brain;
    await vi.waitFor(() => expect(arrived.shadowRoot!.activeElement).toBe(field(arrived)));
  });
  it.each([0, 512, 196608])('preserves the saved value %i in AI Brain', async value => {
    saved = value;
    const brain = await mount('jf-brain-surface'); await advanced(brain);
    expect(field(brain).value).toBe(String(value));
    expect(field(brain).getAttribute('aria-valuetext')).toBe(value === 0 ? 'Automatic' : `${new Intl.NumberFormat().format(value)} tokens`);
    expect(vi.mocked(fetch).mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false);
  });
  it('floors a positive save at 512 and writes only the narrow wire field', async () => {
    const brain = await mount('jf-brain-surface'); await advanced(brain);
    field(brain).value = '100'; field(brain).dispatchEvent(new Event('input')); field(brain).dispatchEvent(new Event('change')); await settle(brain);
    expect(saved).toBe(512); expect(field(brain).value).toBe('512');
    const posts = vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'POST');
    expect(posts).toHaveLength(1); expect(JSON.parse(String(posts[0]![1]!.body)).llm).toEqual({ contextWindow: 512 });
  });
  it('shows the running value and explains a different saved choice beside Reading memory', async () => {
    saved = 16384; engine(8192);
    const brain = await mount('jf-brain-surface'); await advanced(brain);
    const row = field(brain).closest('.advanced-row')!;
    expect(row.textContent).toContain('Running now: 8,192 tokens (override, 2 slots, q8_0)');
    expect(row.textContent).toContain('Saved: 16,384 tokens. Takes effect the next time the answer model starts.');
  });
  it('a saved Automatic choice explains an explicit window still running', async () => {
    saved = 0; engine(8192);
    const brain = await mount('jf-brain-surface'); await advanced(brain);
    expect(field(brain).closest('.advanced-row')!.textContent).toContain('Saved: Automatic. Takes effect the next time the answer model starts.');
  });
  it('Automatic shows the derived running window without claiming a pending change', async () => {
    saved = 0; engine(32768, 'top-rung');
    const brain = await mount('jf-brain-surface'); await advanced(brain);
    const row = field(brain).closest('.advanced-row')!;
    expect(row.textContent).toContain('Running now: 32,768 tokens (top-rung, 2 slots, q8_0)');
    expect(row.textContent).not.toContain('Takes effect');
  });
  it.each(['unknown', 'offline', 'stale'])('describes %s running memory without claiming it is current', async state => {
    if (state !== 'unknown') engine(state === 'offline' ? null : 8192);
    if (state === 'stale') { vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 60000); __tickClockForTest(); }
    const brain = await mount('jf-brain-surface'); await advanced(brain);
    const row = field(brain).closest('.advanced-row')!;
    expect(row.textContent).toContain(state === 'unknown' ? 'Checking running reading memory' : state === 'offline' ? 'Reading memory is set when the answer model starts' : 'Last observed: 8,192 tokens');
  });
  it.each([500, 409])('a refused save (%i) shows an error and the previous saved value', async status => {
    const brain = await mount('jf-brain-surface'); await advanced(brain); refuse = status;
    field(brain).value = '16384'; field(brain).dispatchEvent(new Event('input')); field(brain).dispatchEvent(new Event('change')); await settle(brain);
    expect(saved).toBe(4096); expect(field(brain).value).toBe('4096');
    expect(brain.shadowRoot!.querySelector('jf-error-alert')?.textContent).toMatch(status === 409 ? /Reload and review/ : /refused|500/);
  });
});
