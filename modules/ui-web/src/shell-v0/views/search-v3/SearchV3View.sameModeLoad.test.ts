// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3Composer } from './Sv3Composer.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { resetSearchState } from '../../state/searchState.js';
import { resetInspectorState } from '../../state/inspectorState.js';
import { __resetAgentSessionStore } from '../../state/agentSessionStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { registerCoreStores } from '../../router/bootstrap.js';
import { createNavigationHandler } from '../../router/navigationHandler.js';
import { deactivateProjection } from '../../router/URLProjector.js';
import { takePendingForceShape, takePendingSelection, takePendingAutoRun } from '../../utils/compose.js';

type Activation = 'pointer' | 'keyboard';
const question = 'What is the chosen project code? Answer briefly.';
const schema = '{"type":"object","properties":{"code":{"type":"string"}}}';
let http: ReturnType<typeof vi.fn>;
const dispatches = (): Record<string, unknown>[] => http.mock.calls
  .filter(c => String(c[0]).includes('/api/chat/dispatch'))
  .map(c => JSON.parse((c[1] as RequestInit).body as string) as Record<string, unknown>);
const done = (): Response => new Response('event: done\ndata: {"finalResponse":"Answer"}\n\n',
  { headers: { 'Content-Type': 'text/event-stream' } });
function composer(view: SearchV3View): Sv3Composer { return view.shadowRoot!.querySelector('jf-sv3-composer')!; }
function control<T extends HTMLElement = HTMLButtonElement>(host: HTMLElement, id: string): T {
  return host.shadowRoot!.querySelector<T>(`[data-testid="${id}"]`)!;
}
async function settle(view: SearchV3View): Promise<void> {
  for (let i = 0; i < 8; i++) await new Promise<void>(resolve => setTimeout(resolve, 0));
  await view.updateComplete; await composer(view).updateComplete;
}
async function mount(): Promise<SearchV3View> {
  const view = document.createElement('jf-sv3-window') as SearchV3View;
  document.body.append(view); await settle(view); return view;
}
async function navigate(view: SearchV3View, state: Record<string, string | string[]>): Promise<void> {
  await createNavigationHandler({ isKnownSurface: () => true, setActiveSurface: () => {} })
    .handle({ kind: 'navigate', target: 'core.search-v3-surface', state });
  await settle(view);
}
function activate(button: HTMLButtonElement, action: Activation): void {
  if (action === 'pointer') { button.click(); return; }
  button.focus();
  // happy-dom has no native keyboard default activation; supply its click after the real key event.
  if (button.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true, cancelable: true }))) button.click();
  button.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true, composed: true }));
}
async function chooseMode(view: SearchV3View, tier: string, action: Activation): Promise<void> {
  const c = composer(view);
  activate(control(c, 'sv3-composer-tier'), action); await c.updateComplete;
  const option = c.shadowRoot!.querySelector<HTMLButtonElement>(`[data-testid="sv3-composer-tier-option"][data-tier="${tier}"]`)!;
  expect(option).not.toBeNull();
  activate(option, action); await settle(view);
  expect(control(c, 'sv3-composer-tier-menu')).toBeNull();
}
async function type(view: SearchV3View, id: string, value: string): Promise<void> {
  const field = control<HTMLTextAreaElement>(composer(view), id);
  field.value = value; field.dispatchEvent(new Event('input')); await settle(view);
}
beforeEach(() => {
  registerCoreStores(); resetInspectorState(); sessionStorage.clear(); localStorage.clear();
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetDraftKeptForTest(); __resetAiStateForTest();
  __feedForTest({ inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot });
  __feedContactForTest();
  http = vi.fn(async (url: unknown) => String(url).includes('/api/chat/dispatch') ? done()
    : new Response(JSON.stringify(String(url).includes('/api/knowledge/search')
      ? { results: [{ id: 'chosen', fields: { path: 'chosen.md' } }], total: 1 } : {})));
  vi.stubGlobal('fetch', http);
});
afterEach(async () => {
  deactivateProjection(); takePendingSelection(); takePendingForceShape(); takePendingAutoRun();
  document.body.replaceChildren(); await new Promise<void>(resolve => setTimeout(resolve, 0));
  resetSearchState(); __resetAiStateForTest(); __resetAgentSessionStore();
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});

it.each(['pointer', 'keyboard'] as const)('pending Load anyway survives shown Ask choice by %s', async action => {
  const view = await mount(); await chooseMode(view, 'ask', 'pointer');
  await type(view, 'sv3-composer-input', question);
  let release!: (response: Response) => void;
  http.mockImplementation(async (url: unknown) => {
    if (String(url).includes('/api/chat/load-anyway')) return new Promise<Response>(resolve => { release = resolve; });
    if (String(url).includes('/api/chat/dispatch')) return dispatches().length === 1
      ? new Response(JSON.stringify({ errorCode: 'CHAT_UNAVAILABLE', error: 'Injected pre-admission pressure refusal',
        reason: 'GPU_BUSY', forceAllowed: true, retryAfterMs: 1 }), { status: 503 }) : done();
    return new Response('{}');
  });
  control(composer(view), 'sv3-composer-send').click(); await settle(view);
  expect(dispatches()).toHaveLength(1); expect(dispatches()[0]).toMatchObject({ question, shapeId: 'core.rag-ask' });
  control(view, 'sv3-residency-force').click(); await settle(view);
  expect(http.mock.calls.filter(c => String(c[0]).includes('/api/chat/load-anyway'))).toHaveLength(1);
  const pendingText = control<HTMLTextAreaElement>(composer(view), 'sv3-composer-input').value;
  await chooseMode(view, 'ask', action);
  expect(control<HTMLTextAreaElement>(composer(view), 'sv3-composer-input').value).toBe(pendingText);
  expect(dispatches()).toHaveLength(1);
  release(new Response('{"loading":true}', { status: 202 })); await settle(view);
  expect(dispatches()).toHaveLength(2);
  expect(dispatches()[1]).toMatchObject({ question, shapeId: 'core.rag-ask', docIds: [], allowLoad: true });
  await settle(view); expect(dispatches()).toHaveLength(2);
});

for (const preparation of ['text', 'files', 'schema'] as const) {
  it.each(['pointer', 'keyboard'] as const)(`shown mode preserves ordinary ${preparation} by %s`, async action => {
    const view = await mount();
    if (preparation !== 'text') {
      await type(view, 'sv3-composer-input', 'chosen');
      control(composer(view), 'sv3-composer-send').click(); await settle(view);
      control(view, 'sv3-column').dispatchEvent(new CustomEvent('card-scope-file',
        { detail: { path: 'chosen.md' }, bubbles: true, composed: true })); await settle(view);
    }
    await chooseMode(view, preparation === 'schema' ? 'structured' : 'ask', 'pointer');
    await type(view, 'sv3-composer-input', question);
    if (preparation === 'schema') await type(view, 'sv3-composer-schema', schema);
    const c = composer(view); const text = control<HTMLTextAreaElement>(c, 'sv3-composer-input').value;
    const scope = c.shadowRoot!.textContent;
    await chooseMode(view, preparation === 'schema' ? 'structured' : 'ask', action);
    expect(control<HTMLTextAreaElement>(c, 'sv3-composer-input').value).toBe(text);
    expect(c.shadowRoot!.textContent).toBe(scope);
    if (preparation === 'schema') expect(control<HTMLTextAreaElement>(c, 'sv3-composer-schema').value).toBe(schema);
    expect(dispatches()).toHaveLength(0);
    control(c, 'sv3-composer-send').click(); await settle(view);
    expect(dispatches()).toHaveLength(1);
    if (preparation === 'schema') expect(dispatches()[0]).toMatchObject({ shapeId: 'core.extract', prompt: question, schema });
    else expect(dispatches()[0]).toMatchObject({ shapeId: 'core.rag-ask', question, docIds: preparation === 'files' ? ['chosen.md'] : [] });
  });
}

it.each(['pointer', 'keyboard'] as const)('R3 shown Ask choice still ends retired hand-off block by %s', async action => {
  const view = await mount();
  await navigate(view, { query: question, forceShapeId: 'core.summarize', docIds: ['chosen.md'] });
  view.remove(); await settle(view); document.body.append(view); await settle(view);
  const c = composer(view);
  expect(c.tier).toBe('ask');
  expect(c.shadowRoot!.textContent).toMatch(/files and mode.*not kept/i);
  control<HTMLTextAreaElement>(c, 'sv3-composer-input').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true }));
  control(c, 'sv3-composer-send').click(); await settle(view); expect(dispatches()).toHaveLength(0);
  await chooseMode(view, 'ask', action);
  expect(control<HTMLTextAreaElement>(c, 'sv3-composer-input').value).toBe(question);
  expect(c.shadowRoot!.textContent).not.toMatch(/files and mode.*not kept/i);
  expect(dispatches()).toHaveLength(0);
  control(c, 'sv3-composer-send').click(); await settle(view);
  expect(dispatches()).toHaveLength(1);
  expect(dispatches()[0]).toMatchObject({ shapeId: 'core.rag-ask', question, docIds: [] });
  expect(dispatches()[0]).not.toHaveProperty('selection');
});
