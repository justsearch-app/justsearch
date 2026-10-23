// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { ConfirmDialog } from './ConfirmDialog.js';
import './ConfirmDialog.js';
import { deepActiveElement } from '../utils/keyboardHandler.js';

beforeEach(() => vi.useFakeTimers());
afterEach(async () => {
  document.body.replaceChildren();
  await vi.runOnlyPendingTimersAsync();
  vi.useRealTimers();
});

async function open(typed = false, matched = true) {
  const host = document.createElement('jf-confirm-dialog') as ConfirmDialog;
  if (typed) host.typedConfirmWord = 'core.apply-excludes';
  host.open = true;
  document.body.append(host);
  await host.updateComplete;
  await vi.runOnlyPendingTimersAsync();
  if (typed && matched) {
    host.typedInput = 'core.apply-excludes';
    await host.updateComplete;
  }
  const confirm = vi.fn();
  const cancel = vi.fn();
  host.addEventListener('confirm', confirm);
  host.addEventListener('cancel', cancel);
  return { host, confirm, cancel };
}

async function button(host: ConfirmDialog, name: string) {
  const atom = host.shadowRoot!.querySelector(`jf-button.${name}`)!;
  await (atom as ConfirmDialog).updateComplete;
  const control = atom.shadowRoot!.querySelector('jf-control')!;
  await (control as ConfirmDialog).updateComplete;
  return control.shadowRoot!.querySelector('button')!;
}

function enter(target: Element) {
  const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true, cancelable: true });
  target.dispatchEvent(event);
  return event;
}

it.each([true, false])('CE1 leaves Enter on Cancel to native activation (typed=%s)', async typed => {
  const { host, confirm, cancel } = await open(typed);
  const control = await button(host, 'cancel');
  control.focus();
  expect(deepActiveElement()).toBe(control);
  const event = enter(control);
  expect(confirm).not.toHaveBeenCalled();
  expect(event.defaultPrevented).toBe(false);
  // happy-dom does not synthesize a native keyboard click; verify the remaining native action.
  control.click();
  expect(cancel).toHaveBeenCalledOnce();
  expect(confirm).not.toHaveBeenCalled();
});

it('CE1 leaves Enter on Close to native activation', async () => {
  const { host, confirm, cancel } = await open();
  const control = await button(host, 'close');
  control.focus();
  const event = enter(control);
  expect(confirm).not.toHaveBeenCalled();
  expect(event.defaultPrevented).toBe(false);
  control.click();
  expect(cancel).toHaveBeenCalledOnce();
});

it.each(['a', 'select'])('CE1 leaves Enter on another focused control (%s)', async tag => {
  const { host, confirm } = await open();
  const control = document.createElement(tag);
  if (control instanceof HTMLAnchorElement) control.href = '#native-link';
  host.shadowRoot!.querySelector('.card')!.append(control);
  control.focus();
  expect(deepActiveElement()).toBe(control);
  const event = enter(control);
  expect(confirm).not.toHaveBeenCalled();
  expect(event.defaultPrevented).toBe(false);
});

it('CE1 preserves Enter confirmation on the dialog itself', async () => {
  const { host, confirm } = await open();
  const dialog = host.shadowRoot!.querySelector('dialog')!;
  dialog.tabIndex = -1;
  dialog.focus();
  enter(dialog);
  expect(confirm).toHaveBeenCalledOnce();
});

it('CE1 preserves exactly one Enter confirmation in the matching phrase field', async () => {
  const { host, confirm } = await open(true);
  const input = host.shadowRoot!.querySelector('input')!;
  input.focus();
  enter(input);
  expect(confirm).toHaveBeenCalledOnce();
});

it.each(['cancel', 'close', 'confirm', 'input', 'dialog'])('CE1 preserves no confirmation for an unmatched phrase with %s focused', async name => {
  const { host, confirm } = await open(true, false);
  const target = ['input', 'dialog'].includes(name)
    ? host.shadowRoot!.querySelector<HTMLElement>(name)!
    : await button(host, name);
  target.tabIndex = 0;
  target.focus();
  enter(target);
  expect(confirm).not.toHaveBeenCalled();
});
