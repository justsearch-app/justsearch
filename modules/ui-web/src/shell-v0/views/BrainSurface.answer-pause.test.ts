// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { AiState } from '../state/aiStateStore.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
const store = vi.hoisted(() => ({ listeners: new Set<(s: AiState) => void>(), current: {} as AiState }));
vi.mock('../state/aiStateStore.js', async original => ({
  ...await original<typeof import('../state/aiStateStore.js')>(),
  subscribeAiState: (listener: (s: AiState) => void) => {
    store.listeners.add(listener); listener(store.current);
    return () => store.listeners.delete(listener);
  },
}));
import './BrainSurface.js';
interface Brain extends HTMLElement { apiBase: string; host_: unknown; updateComplete: Promise<boolean> }
let enabled: boolean | null;
let chatEnabled: boolean;
let revision: number;
let lastKey: string | null;
let posts: Record<string, unknown>[];
let holdSave: Promise<void> | null;
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
function settings() {
  return { ui: { chatEnabled }, pauseAnswersWhileIndexing: enabled,
    witness: { acceptedRevision: revision, lastCommittedOperationKey: lastKey } };
}
function emit(paused: boolean) {
  store.current = { ...store.current, inference: { mode: 'online', available: true,
    answersPausedWhileIndexing: paused } };
  for (const listener of store.listeners) listener(store.current);
}
async function settle(el: Brain) {
  for (let i = 0; i < 5; i++) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
}
async function mount() {
  const el = document.createElement('jf-brain-surface') as Brain;
  el.apiBase = ''; el.host_ = createMockHostApi();
  document.body.append(el); await settle(el);
  const advanced = [...el.shadowRoot!.querySelectorAll<HTMLButtonElement>('button')]
    .find(b => b.textContent?.includes('Advanced settings'));
  expect(advanced).toBeDefined(); advanced!.click(); await settle(el);
  return el;
}
function toggle(el: Brain) {
  const button = el.shadowRoot!.querySelector<HTMLButtonElement>('[role="switch"][aria-label="Pause answers while indexing"]');
  expect(button).not.toBeNull(); return button!;
}
beforeEach(() => {
  enabled = null; chatEnabled = true; revision = 0; lastKey = null; posts = []; holdSave = null;
  store.current = {
    installObservationLive: true, runtimeObservationLive: true, snapshotLive: true,
    aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
    runtime: { mode: 'online', installed: { known: true, value: true } },
    installStatus: { state: 'completed', installedFully: true }, capabilities: { chat: true },
  } as AiState;
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path.endsWith('/api/settings/v2') && init?.method === 'POST') {
      const body = JSON.parse(String(init.body)); posts.push(body); await holdSave;
      if (body.witness.acceptedRevision !== revision) return json({ errorCode: 'VERSION_CONFLICT' }, 409);
      if (typeof body.pauseAnswersWhileIndexing === 'boolean') enabled = body.pauseAnswersWhileIndexing;
      if (typeof body.ui?.chatEnabled === 'boolean') chatEnabled = body.ui.chatEnabled;
      revision++; lastKey = body.operationKey;
      return json({ ...settings(), state: 'COMPLETE', operationKey: lastKey });
    }
    if (path.endsWith('/api/settings/v2')) return json(settings());
    if (path.endsWith('/plan-preview')) return json({ totalDownloadBytes: 0, components: [] });
    return json({});
  }));
});
afterEach(() => { document.body.innerHTML = ''; store.listeners.clear(); vi.unstubAllGlobals(); });

it('defaults off, saves only the pause policy, and waits for the witnessed save', async () => {
  const el = await mount();
  expect(toggle(el).getAttribute('aria-checked')).toBe('false');
  expect(toggle(el).parentElement!.textContent).toContain('Off');
  let release!: () => void;
  holdSave = new Promise(resolve => { release = resolve; });
  toggle(el).click(); await settle(el);
  expect(posts).toHaveLength(1);
  expect(posts[0]).toMatchObject({ pauseAnswersWhileIndexing: true, witness: { acceptedRevision: 0 } });
  expect(Object.keys(posts[0]!).sort()).toEqual(['operationKey', 'pauseAnswersWhileIndexing', 'witness']);
  expect(toggle(el).disabled).toBe(true);
  expect(toggle(el).parentElement!.textContent).toContain('Changing…');
  release(); await settle(el);
  expect(toggle(el).getAttribute('aria-checked')).toBe('true');
  expect(toggle(el).parentElement!.textContent).toContain('On');
  expect(chatEnabled).toBe(true);
  const reopened = await mount();
  expect(toggle(reopened).getAttribute('aria-checked')).toBe('true');
});

it('uses the Engine pause observation for wording and converges a second window', async () => {
  const first = await mount(); const second = await mount();
  toggle(first).click(); await settle(first);
  emit(true); await settle(first); await settle(second);
  expect(toggle(second).getAttribute('aria-checked')).toBe('true');
  expect(first.shadowRoot!.querySelector('h1')!.textContent).toBe('Answers paused while indexing');
  expect(first.shadowRoot!.querySelector('.brain-sentence')!.textContent).toContain('Answers resume automatically');
  expect(first.shadowRoot!.querySelector('.brain-sentence')!.textContent).toContain('answer model stays loaded');
  toggle(second).click(); await settle(second);
  expect(enabled).toBe(false);
  emit(false); await settle(first); await settle(second);
  expect(toggle(first).getAttribute('aria-checked')).toBe('false');
  expect(first.shadowRoot!.querySelector('h1')!.textContent).toBe('AI is ready');
  expect(chatEnabled).toBe(true);
});
