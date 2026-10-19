// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 497 — User-friendly error messages for streaming failures.
 *
 * Maps technical error codes from consumeShapeStream to user-facing messages.
 */
import { chatRefusalOf, retryWording } from '../state/chatResidency.js';

export function friendlyStreamError(err: unknown): string {
  // Spec A step 9 — a refused chat request says why in the Engine's words (e.g. chat is paused for
  // another GPU user), never as the raw HTTP status and JSON body.
  const refusal = chatRefusalOf(err);
  if (refusal) return [refusal.message, retryWording(refusal)].filter((part) => part !== '').join(' ');
  if (err instanceof Error) {
    const code = (err as Error & { code?: string }).code;
    if (code === 'STREAM_INCOMPLETE') {
      return 'Connection lost — the response was interrupted.';
    }
    return err.message;
  }
  return String(err);
}
