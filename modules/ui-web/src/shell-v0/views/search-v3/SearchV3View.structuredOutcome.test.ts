// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { resetSearchState } from '../../state/searchState.js';
import { EPHEMERAL_TOAST_EVENT, type EphemeralToastSpec } from '../../components/advisory/ephemeralToast.js';
import { AdvisoryStore } from '../../components/advisory/AdvisoryStore.js';
import '../../components/advisory/AdvisoryToastHost.js';
import type { AdvisoryToastHost } from '../../components/advisory/AdvisoryToastHost.js';

vi.mock('../../../utils/windowFocus.js', () => ({ isWindowFocused: async () => true }));
vi.mock('../../../utils/notify.js', () => ({ sendDesktopNotification: async () => {} }));
import type { StatusSnapshot } from '../../utils/statusPoll.js';

type View = HTMLElement & { updateComplete: Promise<unknown> };
let finish: ((frame: { done: boolean; value?: Uint8Array }) => void) | undefined;
const schema = '{"type":"object","required":["city"]}';
const q = <T extends Element = HTMLElement>(host: View, id: string): T =>
  host.shadowRoot!.querySelector<T>(`[data-testid="${id}"]`)!;
async function drain(view: View): Promise<void> {
  for (let i = 0; i < 6; i++) await new Promise<void>(resolve => setTimeout(resolve, 0));
  await view.updateComplete;
}
async function mount(): Promise<{ view: View; composer: View; box: HTMLTextAreaElement }> {
  const view = document.createElement('jf-sv3-window') as View;
  document.body.append(view); await drain(view);
  const composer = view.shadowRoot!.querySelector('jf-sv3-composer') as View;
  await composer.updateComplete;
  q<HTMLButtonElement>(composer, 'sv3-composer-tier').click(); await composer.updateComplete;
  composer.shadowRoot!.querySelector<HTMLButtonElement>('[data-tier="structured"]')!.click();
  await view.updateComplete; await composer.updateComplete;
  const box = q<HTMLTextAreaElement>(composer, 'sv3-composer-schema');
  box.value = schema; box.dispatchEvent(new Event('input'));
  q<HTMLTextAreaElement>(composer, 'sv3-composer-input').value = 'extract city';
  q(composer, 'sv3-composer-input').dispatchEvent(new Event('input'));
  await view.updateComplete; await composer.updateComplete;
  return { view, composer, box };
}
beforeEach(() => {
  sessionStorage.clear(); localStorage.clear();
  __resetConversationListForTest(); __resetDraftProvidersForTest(); __resetDraftKeptForTest();
  __resetAiStateForTest();
  __feedForTest({ inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot });
  __feedContactForTest();
  vi.stubGlobal('fetch', vi.fn(async (url: unknown) => {
    if (!String(url).includes('/api/chat/dispatch')) return { ok: true, status: 200, json: async () => ({}) };
    let ended = false;
    return { ok: true, status: 200, body: { getReader: () => ({
      read: async () => {
        if (ended) return { done: true };
        ended = true;
        return new Promise<{ done: boolean; value?: Uint8Array }>(resolve => { finish = resolve; });
      }, releaseLock: () => {}, cancel: () => { finish?.({ done: true }); finish = undefined; },
    }) } };
  }));
});
afterEach(async () => {
  finish?.({ done: true }); finish = undefined;
  document.body.replaceChildren();
  await new Promise<void>(resolve => setTimeout(resolve, 0));
  resetSearchState(); __resetAiStateForTest();
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});

it.each(['done', 'error'] as const)('XD2 announces Structured %s exactly once politely without taking focus', async terminal => {
  const { view, composer, box } = await mount();
  const store = new AdvisoryStore();
  store.start();
  const host = document.createElement('jf-advisory-toast-host') as AdvisoryToastHost;
  host.store = store; document.body.append(host); await host.updateComplete;
  const messages: EphemeralToastSpec[] = [];
  const listen = (event: Event): void => { messages.push((event as CustomEvent<EphemeralToastSpec>).detail); };
  document.addEventListener(EPHEMERAL_TOAST_EVENT, listen);
  try {
    // Two identical outcomes must each be announced; record refresh/rerender must not repeat either.
    for (let run = 0; run < 2; run++) {
      if (terminal === 'error' && run === 1) {
        // RR15: an untyped error has no delivery proof. Review it before a fresh submission.
        expect(view.shadowRoot!.textContent).toContain('Delivery unknown');
        const discard = view.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid=sv3-residency-discard]');
        expect(discard).not.toBeNull(); discard!.click(); await drain(view);
      }
      const input = q<HTMLTextAreaElement>(composer, 'sv3-composer-input');
      input.value = 'extract city'; input.dispatchEvent(new Event('input'));
      await view.updateComplete; await composer.updateComplete;
      q<HTMLButtonElement>(composer, 'sv3-composer-send').click(); await drain(view);
      box.focus();
      const payload = terminal === 'done' ? { finalResponse: '{"city":"Paris"}' } : { error: 'Cannot apply extraction schema: required must be an array' };
      expect(finish).toBeTypeOf('function');
      finish!({ done: false, value: new TextEncoder().encode(`event: ${terminal}\ndata: ${JSON.stringify(payload)}\n\n`) });
      finish = undefined;
      await drain(view);
      expect(messages, 'one announcement per terminal').toHaveLength(run + 1);
      expect(messages[run]!.message).toMatch(terminal === 'done' ? /extract.*complet/i : /extract.*fail/i);
      if (terminal === 'error') expect(messages[run]!.message).toContain('required must be an array');
      await host.updateComplete;
      const notices = [...host.shadowRoot!.querySelectorAll<View>('jf-system-notice')];
      await Promise.all(notices.map(notice => notice.updateComplete));
      expect(notices).toHaveLength(run + 1);
      expect(notices[run]!.getAttribute('role')).toBe('status');
      expect(notices[run]!.getAttribute('aria-live')).toBe('polite');
      expect(composer.shadowRoot!.activeElement, 'terminal must not move focus').toBe(box);
      view.setAttribute('theme', run === 0 ? 'light' : 'dark');
      await drain(view);
      expect(messages).toHaveLength(run + 1);
    }
  } finally {
    document.removeEventListener(EPHEMERAL_TOAST_EVENT, listen);
    host.remove(); store.stop();
  }
});
