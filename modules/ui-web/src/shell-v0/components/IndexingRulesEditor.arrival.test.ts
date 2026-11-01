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

it('L14 waits for loaded rules, follows later layout movement, and stops after one second', async () => {
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
  await vi.advanceTimersByTimeAsync(1000);
  scroll.mockClear(); top = 900;
  await vi.advanceTimersByTimeAsync(100);
  expect(scroll).not.toHaveBeenCalled();
});

it('L14 a later user focus move cancels arrival scrolling', async () => {
  vi.useFakeTimers();
  const host = createMockHostApi();
  host.data.fetch = vi.fn(async () => Response.json({ ui: {} }));
  const el = document.createElement('jf-indexing-rules-editor') as IndexingRulesEditor;
  el.host_ = host; document.body.append(el); await settle(el);
  void el.focusIndexingRule('hours'); await settle(el);
  const target = el.shadowRoot!.querySelector<HTMLInputElement>('#indexing-hours')!;
  const scroll = vi.spyOn(target, 'scrollIntoView');
  el.shadowRoot!.querySelector<HTMLInputElement>('#indexing-battery')!.focus();
  await vi.advanceTimersByTimeAsync(200);
  expect(scroll).not.toHaveBeenCalled();
});
