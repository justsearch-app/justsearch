// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * FA4 — the composer's "Searching N files" counts only the person's own files (the Engine's
 * `ownSearchableDocuments`), never the bundled Help; with no own files it says so in words; when
 * the own count is unknown (before a poll, or an older Engine that does not report it) it says so,
 * never a number. Fed through the real status store into the real Search window.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { resetSearchState } from '../../state/searchState.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

const HELP = 250;

/** A settled status with `own` of the person's files beside the bundled Help; `own: undefined` is an Engine that does not report it. */
function observe(own: number | undefined): void {
  const searchable = (own ?? 0) + HELP;
  __feedForTest({
    status: { worker: { core: { indexedDocuments: searchable, searchableDocuments: searchable,
      ...(own === undefined ? {} : { ownSearchableDocuments: own }) } } } as unknown as StatusSnapshot,
  });
  __feedContactForTest();
}

async function corpusLine(): Promise<{ kind: string | null; text: string; remedy: boolean }> {
  const el = document.createElement('jf-sv3-window') as Mounted;
  el.setAttribute('api-base', 'http://127.0.0.1:9999');
  document.body.append(el);
  await el.updateComplete;
  const composer = el.shadowRoot!.querySelector('jf-sv3-composer') as Mounted;
  await composer.updateComplete;
  const line = composer.shadowRoot!.querySelector<HTMLElement>('[data-testid="sv3-composer-corpus"]');
  return { kind: line?.dataset.kind ?? null, text: (line?.textContent ?? '').replace(/\s+/g, ' ').trim(),
    remedy: composer.shadowRoot!.querySelector('[data-testid="sv3-composer-corpus-remedy"]') !== null };
}

beforeEach(() => {
  sessionStorage.clear(); localStorage.clear();
  __resetConversationListForTest(); __resetDraftProvidersForTest();
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, body: null, json: async () => ({}) }));
});
afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  resetSearchState(); __resetAiStateForTest(); vi.unstubAllGlobals(); vi.restoreAllMocks();
});

describe('S4 returning Search does not repeat the Library corpus fact', () => {
  for (const own of [12, 1, 0, undefined]) it(`omits the corpus line with own count ${own}, regardless of bundled Help`, async () => {
    observe(own);
    expect(await corpusLine()).toEqual({ kind: null, text: '', remedy: false });
  });
  it('unknown and subsequently reported counts do not resurrect corpus chrome', async () => {
    expect(await corpusLine()).toEqual({ kind: null, text: '', remedy: false });
    observe(7);
    expect(await corpusLine()).toEqual({ kind: null, text: '', remedy: false });
  });
});
