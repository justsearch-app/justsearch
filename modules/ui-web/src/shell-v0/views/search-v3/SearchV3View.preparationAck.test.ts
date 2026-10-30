// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/** Preservation of preparation callers at the synchronous storage acknowledgement boundary. */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3Composer } from './Sv3Composer.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { __resetConversationListForTest, restoreActiveConversation } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { resetSearchState } from '../../state/searchState.js';
import type { Sv3Preparation } from './sv3-recovery.js';
import { parkSearchV3Handoff } from '../../utils/compose.js';

const incarnation = '00000000-0000-4000-8000-000000000001';
const key = 'justsearch.draft.attachment.v1.search-v3.main';
let http: ReturnType<typeof vi.fn>;
const composer = (view: SearchV3View): Sv3Composer => view.shadowRoot!.querySelector('jf-sv3-composer')!;
const emit = (view: SearchV3View, name: string, detail: unknown = {}) =>
  composer(view).dispatchEvent(new CustomEvent(name, { detail, bubbles: true, composed: true }));
async function settle(view: SearchV3View): Promise<void> {
  for (let i = 0; i < 8; i++) await new Promise(resolve => setTimeout(resolve, 0));
  await view.updateComplete; await composer(view).updateComplete;
}
beforeEach(() => {
  localStorage.clear(); sessionStorage.clear(); resetSearchState();
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetAiStateForTest();
  http = vi.fn(async () => new Response('{}'));
  vi.stubGlobal('fetch', http);
  __feedForTest({ inference: { available: true } as never, status: {
    inference: { chatEnabledSpec: true }, worker: { core: { indexedDocuments: 42 } },
    operating: { chat: { state: 'READY' } },
    readiness: { composites: { aiFeatures: { state: 'READY', reasonCodes: [] } } },
  } as unknown as StatusSnapshot });
  __feedContactForTest();
});
afterEach(() => {
  document.body.replaceChildren(); resetSearchState(); __resetAiStateForTest();
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});

it.each(['tier', 'schema', 'card scope', 'scope clear'].flatMap(action =>
  ['edit', 'place'].map(change => ({ action, change }))))(
  '$action attachment readback: edits reject preparation; refused departures keep their place and apply preparation ($change)', async ({ action, change }) => {
    const view = document.createElement('jf-sv3-window') as SearchV3View;
    document.body.append(view); await settle(view);
    composer(view).draft = 'companion C';
    parkSearchV3Handoff({ query: 'original A', mode: 'ask', docIds: ['/original.txt'] });
    await settle(view);
    if (action === 'schema') {
      emit(view, 'sv3-tier-change', { tier: 'structured' });
      emit(view, 'sv3-schema-change', { text: '{"type":"object"}' });
      await settle(view);
    }
    const originalTier = composer(view).tier;
    const originalSchema = composer(view).schemaDraft;
    const originalScope = composer(view).scopeLabel;
    const originalPlace = view.sessions.activeId;
    const storage = sessionStorage;
    const get = storage.getItem.bind(storage), set = storage.setItem.bind(storage);
    vi.spyOn(crypto, 'randomUUID').mockReturnValue(incarnation);
    let written = false, edits = 0;
    const writes: string[] = [];
    const getDescriptor = Object.getOwnPropertyDescriptor(storage, 'getItem')
      ?? { configurable: true, writable: true, value: get };
    const setDescriptor = Object.getOwnPropertyDescriptor(storage, 'setItem')
      ?? { configurable: true, writable: true, value: set };
    Object.defineProperty(storage, 'setItem', { ...setDescriptor, value: (candidate: string, value: string) => {
      set(candidate, value); if (candidate === key) { written = true; writes.push(value); }
    } });
    Object.defineProperty(storage, 'getItem', { ...getDescriptor, value: (candidate: string) => {
      if (candidate === key && written && edits === 0) {
        edits++;
        if (change === 'edit') composer(view).draft = 'newer B';
        else restoreActiveConversation('moved-conversation');
      }
      return get(candidate);
    } });
    try {
      if (action === 'tier') emit(view, 'sv3-tier-change', { tier: 'delegate' });
      if (action === 'schema') emit(view, 'sv3-schema-change', { text: '{"type":"array"}' });
      if (action === 'card scope') emit(view, 'card-scope-file', { path: '/replacement.txt' });
      if (action === 'scope clear') emit(view, 'sv3-composer-scope-clear');
      await settle(view);
      expect(edits).toBe(1);
      const preparation: Sv3Preparation = action === 'tier' ? { kind: 'delegate' }
        : action === 'schema' ? { kind: 'structured', schema: '{"type":"array"}', scope: { kind: 'typed' } }
        : action === 'card scope' ? { kind: 'ask', scope: { kind: 'files', paths: ['/replacement.txt'] } }
        : { kind: 'ask', scope: { kind: 'all' } };
      const payload = { version: 2, task: null, drafts: [
        { place: originalPlace, text: 'original A', preparation },
        { place: originalPlace, text: 'companion C', preparation: { kind: 'search' } },
      ] };
      // The ACK saved A's requested preparation even when reentrant input refuses its live application.
      expect(JSON.parse(get(key)!).payload).toEqual(payload);
      expect(writes).toEqual([JSON.stringify({ version: 1, incarnation, payload })]);
      expect(composer(view).draft).toBe(change === 'edit' ? 'newer B' : 'original A');
      if (change === 'edit') {
        expect(composer(view).tier).toBe(originalTier);
        expect(composer(view).schemaDraft).toBe(originalSchema);
        expect(composer(view).scopeLabel).toEqual(originalScope);
        expect(view.shadowRoot!.textContent).toContain('changed while recovery was being saved');
      } else {
        expect(view.shadowRoot!.querySelector('[data-testid=sv3-departure-notice]')).not.toBeNull();
        expect(view.sessions.activeId).toBe(originalPlace);
        expect(composer(view).tier).toBe(action === 'tier' ? 'delegate' : originalTier);
        expect(composer(view).schemaDraft).toBe(action === 'schema' ? '{"type":"array"}' : originalSchema);
        expect(composer(view).scopeLabel).toBe(action === 'card scope' ? 'replacement.txt'
          : action === 'scope clear' || action === 'tier' ? '' : originalScope);
      }
      expect(http.mock.calls.filter(call => String(call[0]).includes('/dispatch'))).toHaveLength(0);
    } finally {
      Object.defineProperty(storage, 'getItem', getDescriptor);
      Object.defineProperty(storage, 'setItem', setDescriptor);
    }
  });

it('an ordinary preparation change writes exactly one attachment with the current and companion drafts', async () => {
  const view = document.createElement('jf-sv3-window') as SearchV3View;
  document.body.append(view); await settle(view);
  composer(view).draft = 'companion C';
  parkSearchV3Handoff({ query: 'original A', mode: 'ask', docIds: ['/original.txt'] });
  await settle(view);
  vi.spyOn(crypto, 'randomUUID').mockReturnValue(incarnation);
  const storage = sessionStorage, set = storage.setItem.bind(storage);
  const descriptor = Object.getOwnPropertyDescriptor(storage, 'setItem')
    ?? { configurable: true, writable: true, value: set };
  const writes: string[] = [];
  Object.defineProperty(storage, 'setItem', { ...descriptor, value: (candidate: string, value: string) => {
    set(candidate, value); if (candidate === key) writes.push(value);
  } });
  try {
    emit(view, 'sv3-tier-change', { tier: 'delegate' });
    await settle(view);
    const payload = { version: 2, task: null, drafts: [
      { place: null, text: 'original A', preparation: { kind: 'delegate' } },
      { place: null, text: 'companion C', preparation: { kind: 'search' } },
    ] };
    const bytes = JSON.stringify({ version: 1, incarnation, payload });
    expect(writes).toEqual([bytes]);
    expect(storage.getItem(key)).toBe(bytes);
    expect(composer(view).tier).toBe('delegate');
  } finally {
    Object.defineProperty(storage, 'setItem', descriptor);
  }
});
