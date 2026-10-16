/**
 * Spec A step 9 — a chat request the Engine refuses because chat is unloaded for another GPU user
 * answers 503 CHAT_UNAVAILABLE with a typed body. The stream error carries that refusal (why, and
 * whether a person may load chat anyway), so the chat window can word it instead of showing the raw
 * HTTP text; any other error body leaves the error as it was.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

vi.mock('../utils/tauriRuntime', () => ({
  isTauriRuntime: vi.fn(() => false),
}));

function answer(status: number, text: string): Response {
  const response = {
    ok: false,
    status,
    headers: new Headers({ 'content-type': 'application/json' }),
    body: null,
    text: async () => text,
    json: async () => JSON.parse(text) as unknown,
    clone: () => response,
  };
  return response as unknown as Response;
}

type RefusalError = Error & {
  status?: number;
  chatRefusal?: { errorCode: string; reason: string; message: string; forceAllowed: boolean };
};

async function failureOf(text: string, status = 503): Promise<RefusalError> {
  globalThis.fetch = vi.fn().mockResolvedValue(answer(status, text));
  const { consumeShapeStream } = await import('./streams');
  try {
    await consumeShapeStream('http://localhost/api/chat/dispatch', {}, () => {});
  } catch (e) {
    return e as RefusalError;
  }
  throw new Error('the stream should have failed');
}

describe('consumeShapeStream — Spec A step 9 chat refusals', () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
    vi.resetModules();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.clearAllMocks();
  });

  it('a 503 CHAT_UNAVAILABLE carries the refusal: reason, words and Load anyway', async () => {
    const err = await failureOf(
      JSON.stringify({
        error: 'Chat is paused: another application needs the graphics memory.',
        errorCode: 'CHAT_UNAVAILABLE',
        retryable: true,
        reason: 'GPU_BUSY',
        forceAllowed: true,
      }),
    );
    expect(err.status).toBe(503);
    expect(err.chatRefusal).toEqual({
      errorCode: 'CHAT_UNAVAILABLE',
      reason: 'GPU_BUSY',
      message: 'Chat is paused: another application needs the graphics memory.',
      forceAllowed: true,
    });
  });

  it('an error body without a refusal stays a plain HTTP error', async () => {
    const err = await failureOf(JSON.stringify({ error: 'boom', errorCode: 'INTERNAL_ERROR' }), 500);
    expect(err.status).toBe(500);
    expect(err.chatRefusal).toBeUndefined();
  });
});
