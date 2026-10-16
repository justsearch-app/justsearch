/**
 * Spec A step 9 — the shared stream-error words (the search window's ask uses them) say why a chat
 * request was refused, in the Engine's words, instead of the raw HTTP status and JSON body.
 */
import { describe, it, expect } from 'vitest';
import { friendlyStreamError } from './streamError.js';

function refused(detail: Record<string, unknown>): Error {
  return Object.assign(
    new Error('consumeShapeStream: HTTP 503 from /api/chat/dispatch — {"errorCode":"CHAT_UNAVAILABLE"}'),
    { status: 503, chatRefusal: detail },
  );
}

describe('friendlyStreamError — Spec A step 9 chat refusals', () => {
  it('words a pressure refusal in the Engine words', () => {
    expect(
      friendlyStreamError(
        refused({
          errorCode: 'CHAT_UNAVAILABLE',
          reason: 'GPU_BUSY',
          message: 'Chat is paused: another application needs the graphics memory.',
          forceAllowed: false,
        }),
      ),
    ).toBe('Chat is paused: another application needs the graphics memory.');
  });

  it('adds when to try again when the Engine knows', () => {
    expect(
      friendlyStreamError(
        refused({
          errorCode: 'CHAT_UNAVAILABLE',
          reason: 'RELOAD_BACKOFF',
          message: 'Chat was paused a moment ago to free graphics memory; it can load again shortly.',
          forceAllowed: false,
          retryAfterMs: 45_000,
        }),
      ),
    ).toBe(
      'Chat was paused a moment ago to free graphics memory; it can load again shortly. Try again in about 45 s.',
    );
  });

  it('leaves any other error as it was', () => {
    expect(friendlyStreamError(new Error('boom'))).toBe('boom');
  });
});
