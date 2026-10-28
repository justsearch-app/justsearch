// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import { confirmAsync, type ConfirmDialog } from './ConfirmDialog.js';

afterEach(async () => {
  document.body.replaceChildren();
  await vi.runOnlyPendingTimersAsync();
  vi.useRealTimers();
});

it('S56 an already departed owner never opens or approves a forget dialog', async () => {
  vi.useFakeTimers();
  const owner = new AbortController(); owner.abort();
  const options = { title: 'Forget exact file?', message: '"/docs/file "', signal: owner.signal };
  const answer = confirmAsync(options);
  expect(document.querySelector('jf-confirm-dialog'), 'a departed owner cannot leave a stale approval visible').toBeNull();
  await expect(answer).resolves.toBe(false);
});

it('S56 a placement departure closes its own dialog and a late confirm cannot authorize it', async () => {
  vi.useFakeTimers();
  const owner = new AbortController();
  const answer = confirmAsync({ title: 'Forget exact file?', message: '"/docs/file "', signal: owner.signal });
  const dialog = document.querySelector('jf-confirm-dialog') as ConfirmDialog;
  await dialog.updateComplete;
  expect(dialog.open).toBe(true);
  owner.abort();
  expect(dialog.open).toBe(false);
  dialog.dispatchEvent(new CustomEvent('confirm'));
  await expect(answer).resolves.toBe(false);
  await vi.advanceTimersByTimeAsync(250);
  expect(document.querySelector('jf-confirm-dialog')).toBeNull();
});

it('S56 aborted modal and its delayed autofocus do not steal the new placement focus', async () => {
  vi.useFakeTimers();
  const old = document.createElement('button'); document.body.append(old); old.focus();
  const owner = new AbortController();
  const answer = confirmAsync({ title: 'Forget exact file?', message: '"/docs/file "', signal: owner.signal });
  const dialog = document.querySelector('jf-confirm-dialog') as ConfirmDialog;
  await dialog.updateComplete;
  const current = document.createElement('input'); document.body.append(current);
  owner.abort(); current.focus();
  await vi.runOnlyPendingTimersAsync();
  await expect(answer).resolves.toBe(false);
  expect(document.activeElement).toBe(current);
});
