// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './ContextMenu.js';
import type { ContextMenu } from './ContextMenu.js';
import type { ConfirmDialog } from './ConfirmDialog.js';
import { deepActiveElement } from '../utils/keyboardHandler.js';
const seen: KeyboardEvent[] = [];
const earlierCapture = (e: KeyboardEvent) => seen.push(e);
beforeEach(() => { vi.useFakeTimers(); seen.length = 0; document.addEventListener('keydown', earlierCapture, true); });
afterEach(async () => {
  for (const dialog of document.querySelectorAll('jf-confirm-dialog')) dialog.dispatchEvent(new CustomEvent('cancel'));
  await vi.runOnlyPendingTimersAsync();
  document.body.replaceChildren();
  document.removeEventListener('keydown', earlierCapture, true);
  vi.useRealTimers();
});
async function open() {
  const menu = document.createElement('jf-context-menu') as ContextMenu;
  menu.actions = [{ id: 'confirmed', label: 'Confirmed action', category: 'file', enabled: true,
    requiresConfirmation: { title: 'Confirm fixture action?', message: 'A named item' } }];
  menu.anchor = { x: 10, y: 10 };
  const action = vi.fn(); menu.addEventListener('context-action', action);
  document.body.append(menu); await menu.updateComplete;
  menu.shadowRoot!.querySelector<HTMLButtonElement>('button.item')!.click();
  for (let i = 0; i < 20; i++) await Promise.resolve();
  const host = document.querySelector('jf-confirm-dialog') as ConfirmDialog;
  await host.updateComplete; await vi.runOnlyPendingTimersAsync();
  const atom = host.shadowRoot!.querySelector('jf-button.confirm') as ConfirmDialog;
  await atom.updateComplete;
  const control = atom.shadowRoot!.querySelector('jf-control') as ConfirmDialog;
  await control.updateComplete;
  const button = control.shadowRoot!.querySelector('button')!; button.focus();
  return { action, button };
}
function key(target: Element, type: string, value: string, repeat = false) {
  const e = new KeyboardEvent(type, { key: value, repeat, bubbles: true, composed: true, cancelable: true });
  target.dispatchEvent(e); return e;
}
it('R2 Enter belongs to the confirmation before the earlier ContextMenu capture listener', async () => {
  const { action, button } = await open();
  key(button, 'keydown', 'Enter');
  await vi.runOnlyPendingTimersAsync();
  const openCount = [...document.querySelectorAll<ConfirmDialog>('jf-confirm-dialog')].filter(el => el.open).length;
  key(deepActiveElement()!, 'keyup', 'Enter'); await vi.runOnlyPendingTimersAsync();
  expect(openCount).toBe(1);
  expect(action).toHaveBeenCalledOnce();
  expect(seen).toHaveLength(0);
});
it.each([' ', 'Tab', 'Escape', 'ArrowDown'])('R2 an open confirmation owns %s before earlier document capture', async value => {
  const { button } = await open(); key(button, 'keydown', value);
  expect(seen).toHaveLength(0);
});
it('preserves a held Enter never confirming the ContextMenu action', async () => {
  const { action, button } = await open(); key(button, 'keydown', 'Enter');
  for (let i = 0; i < 10; i++) key(button, 'keydown', 'Enter', true);
  await vi.runOnlyPendingTimersAsync(); key(deepActiveElement()!, 'keyup', 'Enter');
  expect(action).not.toHaveBeenCalled();
});
