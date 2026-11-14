// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { SearchV3View } from '../SearchV3View.js';
import { resetSearchState } from '../../../state/searchState.js';
import { __resetConversationListForTest } from '../../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../../controllers/draftPersistence.js';
beforeEach(() => {
  localStorage.clear(); sessionStorage.clear(); __resetConversationListForTest(); __resetDraftProvidersForTest();
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ results: [], matchCount: 0 }))));
});
afterEach(() => { document.body.replaceChildren(); resetSearchState(); vi.unstubAllGlobals(); });
it('top field precedes filters and results in DOM without replacing the editor on Search and Ask moves', async () => {
  const view = new SearchV3View(); document.body.append(view); await view.updateComplete;
  const composer = view.shadowRoot!.querySelector('jf-sv3-composer')!; await composer.updateComplete;
  const field = composer.shadowRoot!.querySelector<HTMLTextAreaElement>('textarea')!;
  field.value = 'keep this'; field.dispatchEvent(new Event('input')); await composer.updateComplete;
  field.selectionStart = 2; field.selectionEnd = 4;
  field.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
  for (let n = 0; n < 50; n++) await Promise.resolve(); await view.updateComplete;
  expect(view.hasAttribute('search-results')).toBe(true);
  const dock = view.shadowRoot!.querySelector('.dock')!;
  const main = view.shadowRoot!.querySelector('jf-sv3-main')!;
  expect(dock.compareDocumentPosition(main) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  field.focus();
  expect(view.shadowRoot!.querySelector('jf-sv3-composer')).toBe(composer);
  expect(composer.shadowRoot!.querySelector('textarea')).toBe(field);
  expect(field.value).toBe('keep this'); expect(field.selectionStart).toBe(2); expect(field.selectionEnd).toBe(4);
  field.dispatchEvent(new CompositionEvent('compositionstart'));
  (view as unknown as { tier: string }).tier = 'ask'; await view.updateComplete;
  expect(view.hasAttribute('search-results')).toBe(false);
  expect(composer.shadowRoot!.querySelector('textarea')).toBe(field);
  expect(field.value).toBe('keep this');
  expect(composer.shadowRoot!.activeElement).toBe(field);
  (view as unknown as { tier: string }).tier = 'search'; await view.updateComplete;
  expect(view.shadowRoot!.querySelector('jf-sv3-composer')).toBe(composer);
  expect(composer.shadowRoot!.activeElement).toBe(field);
});
