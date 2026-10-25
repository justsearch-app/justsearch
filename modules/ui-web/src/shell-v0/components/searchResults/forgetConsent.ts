// SPDX-License-Identifier: Apache-2.0
import { html } from 'lit';
import type { ForgetConsent } from '../../../api/domains/forget.js';
import { confirmAsync } from '../ConfirmDialog.js';

/** Existing modal and keyboard contract, with the server's exact frozen target description. */
export async function confirmForget(prompt: ForgetConsent, signal: AbortSignal): Promise<boolean> {
  if (signal.aborted || !prompt.summary) return false;
  const action = prompt.operationId === 'core.withdraw-forget' ? 'Withdraw'
    : prompt.operationId === 'core.retry-forget' ? 'Retry' : 'Forget';
  return confirmAsync({ title: `${action} indexed content?`,
    message: html`<pre>${prompt.summary}</pre>`, confirmLabel: action, cancelLabel: 'Cancel',
    variant: 'danger', typedConfirmWord: prompt.typed ? action.toUpperCase() : undefined, signal });
}
