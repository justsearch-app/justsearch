// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import { ConfirmDialog } from './ConfirmDialog.js';
import './ConfirmDialog.js';

afterEach(() => {
  document.body.replaceChildren();
  vi.useRealTimers();
});

it.each(['core.apply-excludes', 'DELETE'])('names the shared typed input with the word %s (S32.7, XA1)', async word => {
  vi.useFakeTimers();
  const dialog = document.createElement('jf-confirm-dialog') as ConfirmDialog;
  dialog.typedConfirmWord = word;
  dialog.open = true;
  document.body.append(dialog);
  await dialog.updateComplete;
  const input = dialog.shadowRoot!.querySelector('input')!;
  const label = [...dialog.shadowRoot!.querySelectorAll('label')].find(candidate => candidate.control === input);
  expect(label, 'typed confirmation input needs an associated label').toBeDefined();
  expect(label!.textContent).toContain(word);
  await vi.runOnlyPendingTimersAsync();
});
