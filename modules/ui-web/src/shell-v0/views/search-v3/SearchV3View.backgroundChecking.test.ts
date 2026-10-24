// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import { loadHighlighter } from '../../components/markdown/markdownHighlight.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { resetSearchState } from '../../state/searchState.js';
import { registerCoreStores } from '../../router/bootstrap.js';
import { createNavigationHandler } from '../../router/navigationHandler.js';
import { deactivateProjection } from '../../router/URLProjector.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';

type Region = HTMLElement & { updateComplete: Promise<unknown> };
const q = (host: HTMLElement, id: string): HTMLElement | null => host.shadowRoot?.querySelector(`[data-testid="${id}"]`) ?? null;
async function drain(view: Region): Promise<void> {
  for (let i = 0; i < 6; i++) await new Promise<void>(resolve => setTimeout(resolve, 0));
  await view.updateComplete;
}
beforeEach(async () => {
  await loadHighlighter();
  registerCoreStores(); sessionStorage.clear(); localStorage.clear();
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetDraftKeptForTest(); __resetAiStateForTest();
  __feedForTest({ inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot });
  __feedContactForTest();
});
afterEach(async () => {
  deactivateProjection(); document.body.replaceChildren();
  await new Promise<void>(resolve => setTimeout(resolve, 0));
  resetSearchState(); __resetAiStateForTest(); vi.unstubAllGlobals(); vi.restoreAllMocks();
});

it('keeps background Ask checking with its conversation and announces the attempt only once on return', async () => {
  let stream!: ReadableStreamDefaultController<Uint8Array>;
  const fetchMock = vi.fn(async (url: unknown) => String(url).includes('/api/chat/dispatch')
    ? new Response(new ReadableStream<Uint8Array>({ start(controller) { stream = controller; } }))
    : { ok: true, status: 200, json: async () => ({}) });
  vi.stubGlobal('fetch', fetchMock);
  const view = document.createElement('jf-sv3-window') as SearchV3View;
  document.body.append(view); (view as unknown as { tier: string }).tier = 'ask'; await drain(view);
  const composer = view.shadowRoot!.querySelector('jf-sv3-composer') as Region;
  await composer.updateComplete;
  const input = q(composer, 'sv3-composer-input') as HTMLTextAreaElement;
  input.value = 'Who owns Cobalt?'; input.dispatchEvent(new Event('input')); await composer.updateComplete;
  q(composer, 'sv3-composer-send')!.click(); await drain(view);
  const id = view.sessions.activeId!;
  const emit = (event: string, data: unknown): void => stream.enqueue(new TextEncoder().encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
  const away = async (): Promise<void> => {
    await createNavigationHandler({ isKnownSurface: () => true, setActiveSurface: () => {} }).handle({
      kind: 'navigate', target: 'core.search-v3-surface', state: { query: 'Separate task', forceShapeId: 'core.summarize', docIds: ['chosen.md'] },
    }); await drain(view);
  };
  const back = async (): Promise<Region> => {
    view.shadowRoot!.querySelector('jf-sv3-sidebar')!.dispatchEvent(new CustomEvent('sv3-session-select', {
      bubbles: true, composed: true, detail: { id },
    })); await drain(view);
    const main = view.shadowRoot!.querySelector('jf-sv3-main') as Region; await main.updateComplete; return main;
  };
  try {
    emit('chunk', { text: 'Ada owns Cobalt.' }); await drain(view);
    await away();
    emit('rag.citation_checking', {}); await drain(view);
    expect(view.askCheckingTurnId).toBeNull();
    const main = await back();
    expect(q(main, 'sv3-citation-checking')?.textContent ?? '').toContain('Checking the answer against your sources');
    expect(q(main, 'sv3-checking-announcement')?.textContent ?? '').toContain('Checking the answer');
    await away(); expect(view.askCheckingTurnId).toBeNull();
    await back();
    expect(q(main, 'sv3-citation-checking')).not.toBeNull();
    expect(q(main, 'sv3-checking-announcement')?.textContent?.trim()).toBe('');
    await away(); emit('rag.citation_matches', { matches: [] }); await drain(view); await back();
    expect(q(main, 'sv3-citation-checking')).toBeNull(); expect(view.askCheckingTurnId).toBeNull();
    expect(fetchMock.mock.calls.filter(call => String(call[0]).includes('/api/chat/dispatch'))).toHaveLength(1);
  } finally {
    emit('done', {}); stream.close(); await drain(view);
  }
});
