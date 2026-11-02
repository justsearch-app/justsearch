// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import './IndexingRulesEditor.js';
import type { IndexingRulesEditor } from './IndexingRulesEditor.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';

async function settle(el: IndexingRulesEditor) {
  for (let i = 0; i < 20; i++) { await Promise.resolve(); await el.updateComplete; }
  await vi.advanceTimersByTimeAsync(0);
}
afterEach(() => { document.body.replaceChildren(); vi.useRealTimers(); vi.restoreAllMocks(); });

it('L14 waits for loaded rules, keeps the destination in view while layout settles', async () => {
  vi.useFakeTimers();
  let loaded!: (value: Response) => void;
  const host = createMockHostApi();
  host.data.fetch = vi.fn(() => new Promise<Response>(resolve => { loaded = resolve; }));
  const el = document.createElement('jf-indexing-rules-editor') as IndexingRulesEditor;
  el.host_ = host; document.body.append(el);
  await el.focusIndexingRule('hours');
  expect(el.shadowRoot!.activeElement).toBeNull();
  loaded(Response.json({ ui: { indexingPause: { pauseOnBattery: false } } }));
  await settle(el);
  const target = el.shadowRoot!.querySelector<HTMLInputElement>('#indexing-hours')!;
  expect(el.shadowRoot!.activeElement).toBe(target);
  let top = 792;
  vi.spyOn(target, 'getBoundingClientRect').mockImplementation(() => ({ top }) as DOMRect);
  const scroll = vi.spyOn(target, 'scrollIntoView').mockImplementation(() => { top = 200; });
  await vi.advanceTimersByTimeAsync(50);
  expect(scroll).toHaveBeenCalledWith({ block: 'center', behavior: 'instant' });
  scroll.mockClear(); top = 900;
  await vi.advanceTimersByTimeAsync(50);
  expect(scroll).toHaveBeenCalledTimes(1);
});

it('L14 a later user focus move cancels arrival scrolling', async () => {
  vi.useFakeTimers();
  const host = createMockHostApi();
  host.data.fetch = vi.fn(async () => Response.json({ ui: {} }));
  const el = document.createElement('jf-indexing-rules-editor') as IndexingRulesEditor;
  el.host_ = host; document.body.append(el); await settle(el);
  void el.focusIndexingRule('hours'); await settle(el);
  const target = el.shadowRoot!.querySelector<HTMLInputElement>('#indexing-hours')!;
  let top = 200;
  vi.spyOn(target, 'getBoundingClientRect').mockImplementation(() => ({ top }) as DOMRect);
  const scroll = vi.spyOn(target, 'scrollIntoView').mockImplementation(() => { top = 200; });
  await vi.advanceTimersByTimeAsync(50);
  expect(scroll).toHaveBeenCalled();
  scroll.mockClear();
  el.shadowRoot!.querySelector<HTMLInputElement>('#indexing-battery')!.focus();
  top = 900; // Movement inside the arrival window would scroll without cancellation.
  await vi.advanceTimersByTimeAsync(200);
  expect(scroll).not.toHaveBeenCalled();
});
