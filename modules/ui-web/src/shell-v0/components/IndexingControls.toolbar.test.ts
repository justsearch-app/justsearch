// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import './IndexingControls.js';
import { selectIndexingProgress } from '../state/indexingProgress.js';
import type { StatusResponse } from '../../api/generated/schema-types/index.js';

const operation = vi.hoisted(() => ({ invokeWithConsent: vi.fn() }));
vi.mock('../operations/OperationClient.js', () => ({ getOperationClient: () => operation }));
type Control = HTMLElement & { toolbar: boolean; compact: boolean; allowPause: boolean;
  progress: ReturnType<typeof selectIndexingProgress>; updateComplete: Promise<unknown> };
function progress(reasons: string[] = []) {
  return selectIndexingProgress({ worker: { core: { indexState: 'IDLE', indexedDocuments: 1 } },
    operating: { indexing: { state: reasons.length ? 'PAUSED' : 'RUNNING', reasons } } } as unknown as StatusResponse, true, 0, []);
}
async function settle(el: Control) {
  for (let i = 0; i < 10; i++) { await Promise.resolve(); await el.updateComplete; }
}
async function mount(reasons: string[] = []) {
  const el = document.createElement('jf-indexing-controls') as Control;
  Object.assign(el, { toolbar: true, compact: true, allowPause: true, progress: progress(reasons) });
  document.body.append(el); await settle(el); return el;
}
function button(el: Control, label: string) {
  const found = [...el.shadowRoot!.querySelectorAll('button')].find(b => b.textContent?.trim() === label);
  expect(found, label).toBeDefined(); return found!;
}
afterEach(() => { document.body.replaceChildren(); operation.invokeWithConsent.mockReset(); vi.restoreAllMocks(); });

it.each([[0, 'Until I resume'], [900000, '15 minutes'], [3600000, '1 hour']] as const)(
  'pause menu preserves the %s duration and suppresses concurrent requests', async (duration, label) => {
    let complete!: () => void;
    operation.invokeWithConsent.mockImplementation(() => new Promise<void>(resolve => { complete = resolve; }));
    vi.spyOn(Date, 'now').mockReturnValue(1000000);
    const el = await mount(['schedule', 'on_battery']);
    const toggle = button(el, 'Pause indexing'); toggle.click(); await settle(el);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    const choice = button(el, label); choice.click(); choice.click(); await settle(el);
    expect(operation.invokeWithConsent).toHaveBeenCalledTimes(1);
    expect(operation.invokeWithConsent).toHaveBeenCalledWith('core.indexing-pause',
      { args: duration ? { untilEpochMs: 1000000 + duration } : {} }, expect.anything());
    expect(toggle.getAttribute('aria-disabled')).toBe('true');
    complete(); await settle(el);
    el.progress = progress(['user_paused', 'schedule', 'on_battery']); await settle(el);
    expect(el.shadowRoot!.textContent).toContain('Resume indexing');
    expect(el.shadowRoot!.textContent).not.toContain('Change hours');
  });

it('Resume clears manual pause and reports the remaining holds from confirmed status', async () => {
  operation.invokeWithConsent.mockResolvedValue(undefined);
  const el = await mount(['user_paused', 'schedule', 'on_battery']);
  button(el, 'Resume indexing').click(); await settle(el);
  expect(operation.invokeWithConsent).toHaveBeenCalledWith('core.indexing-resume', { args: {} }, expect.anything());
  expect(el.shadowRoot!.textContent).toContain('Waiting for status');
  el.progress = progress(['schedule', 'on_battery']); await settle(el);
  expect(el.shadowRoot!.textContent).toContain('Manual pause cleared');
  expect(el.shadowRoot!.textContent).toContain('indexing hours and battery power');
  expect(el.shadowRoot!.textContent).toContain('Pause indexing');
});

it('Escape closes the keyboard-operable pause menu and restores its trigger', async () => {
  const el = await mount(); const toggle = button(el, 'Pause indexing');
  toggle.click(); await settle(el); button(el, '15 minutes').focus();
  el.shadowRoot!.querySelector('.pause-menu')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
  await settle(el);
  expect(el.shadowRoot!.activeElement).toBe(toggle);
  expect(toggle.getAttribute('aria-expanded')).toBe('false');
  expect(operation.invokeWithConsent).not.toHaveBeenCalled();
});
