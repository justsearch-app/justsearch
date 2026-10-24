// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import './ConfirmDialog.js';
import { ConfirmDialog } from './ConfirmDialog.js';

afterEach(async () => {
  document.body.replaceChildren();
  await vi.runOnlyPendingTimersAsync();
  vi.useRealTimers();
});

async function open(typed = false) {
  vi.useFakeTimers();
  const host = document.createElement('jf-confirm-dialog') as ConfirmDialog;
  host.title = 'Delete'; host.message = 'A named item'; host.open = true;
  if (typed) { host.typedConfirmWord = 'DELETE'; host.typedInput = 'DELETE'; }
  document.body.append(host);
  await host.updateComplete;
  await vi.runOnlyPendingTimersAsync();
  const confirmed = vi.fn();
  host.addEventListener('confirm', confirmed);
  return { host, confirmed };
}

function key(target: Element, type: string, value = 'Enter', repeat = false) {
  const event = new KeyboardEvent(type, { key: value, repeat, bubbles: true, composed: true, cancelable: true });
  target.dispatchEvent(event);
  return event;
}

it.each([false, true])('F1 never confirms a held Enter, including its release (typed=%s)', async typed => {
  const { host, confirmed } = await open(typed);
  const target = host.shadowRoot!.querySelector<HTMLElement>(typed ? 'input' : 'dialog')!;
  target.tabIndex = -1; target.focus();
  key(target, 'keydown');
  for (let i = 0; i < 10; ++i) key(target, 'keydown', 'Enter', true);
  key(target, 'keyup');
  expect(confirmed).not.toHaveBeenCalled();
});

it.each(['Enter', ' '])('F1 blocks repeat activation of the native confirm button (%s)', async value => {
  const { host, confirmed } = await open();
  const atom = host.shadowRoot!.querySelector('jf-button.confirm')!;
  await (atom as ConfirmDialog).updateComplete;
  const control = atom.shadowRoot!.querySelector('jf-control')!;
  await (control as ConfirmDialog).updateComplete;
  const button = control.shadowRoot!.querySelector('button')!;
  button.focus();
  const repeated = key(button, 'keydown', value, true);
  // happy-dom has no native keyboard-click default action; a browser runs it only if uncancelled.
  if (!repeated.defaultPrevented && value === 'Enter') button.click();
  const release = key(button, 'keyup', value);
  if (!release.defaultPrevented && value === ' ') button.click();
  expect(repeated.defaultPrevented).toBe(true);
  expect(confirmed).not.toHaveBeenCalled();
});

it('F1 requires release of a fresh Enter before confirming', async () => {
  const { host, confirmed } = await open();
  const target = host.shadowRoot!.querySelector('dialog')!;
  target.tabIndex = -1; target.focus();
  key(target, 'keydown');
  expect(confirmed).not.toHaveBeenCalled();
  key(target, 'keyup');
  expect(confirmed).toHaveBeenCalledOnce();
});

it.each([{ key: 'Enter', typed: false }, { key: ' ', typed: false }, { key: 'Enter', typed: true }, { key: ' ', typed: true }])('preserves one native confirm activation with a fresh $key press (typed=$typed)', async ({ key: value, typed }) => {
  const { host, confirmed } = await open(typed);
  const atom = host.shadowRoot!.querySelector('jf-button.confirm')!;
  await (atom as ConfirmDialog).updateComplete;
  const control = atom.shadowRoot!.querySelector('jf-control')!;
  await (control as ConfirmDialog).updateComplete;
  const button = control.shadowRoot!.querySelector('button')!;
  button.focus();
  const down = key(button, 'keydown', value);
  if (!down.defaultPrevented && value === 'Enter') button.click();
  const up = key(button, 'keyup', value);
  if (!up.defaultPrevented && value === ' ') button.click();
  expect(confirmed).toHaveBeenCalledOnce();
});
