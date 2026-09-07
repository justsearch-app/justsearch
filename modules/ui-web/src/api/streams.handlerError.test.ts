// @vitest-environment happy-dom

/**
 * Tempdoc 941 — a throwing per-event handler is REPORTED, and the stream keeps delivering.
 *
 * Both halves are load-bearing and they pull against each other. Rethrowing would abort a stream
 * whose remaining events are still useful — one broken consumer must not cost the reader the rest
 * of an answer. But swallowing it into a `console.warn`, which is what the code did, means the app
 * learns nothing: the user-visible result of a throwing evidence handler is indistinguishable from
 * a backend that sent nothing at all (847 F-12 — a turn rendering no citation marks). Nobody has
 * devtools open.
 *
 * The report now goes out through the app's one client-originated message channel
 * (`emitEphemeralToast` → the `jf-advisory-ephemeral` document event, 559 Authority III), which is
 * what these tests listen for. A separate file from `streams.test.ts` because that suite runs in
 * the `node` environment and this needs a `document` to carry the channel.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { EPHEMERAL_TOAST_EVENT } from '../shell-v0/components/advisory/ephemeralToast.js';
import type { EphemeralToastSpec } from '../shell-v0/components/advisory/ephemeralToast.js';

vi.mock('../utils/tauriRuntime', () => ({ isTauriRuntime: vi.fn(() => false) }));

function sseStream(...chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  let i = 0;
  return new ReadableStream({
    pull(controller) {
      if (i < chunks.length) controller.enqueue(encoder.encode(chunks[i++]!));
      else controller.close();
    },
  });
}

function mockFetchSse(body: ReadableStream<Uint8Array>): void {
  globalThis.fetch = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    headers: new Headers({ 'content-type': 'text/event-stream' }),
    body,
  } as unknown as Response);
}

describe('consumeShapeStream — a handler throw is reported, not swallowed (941)', () => {
  let originalFetch: typeof fetch;
  let reported: EphemeralToastSpec[];
  let listener: (e: Event) => void;
  let warn: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
    vi.resetModules();
    reported = [];
    listener = (e: Event) => reported.push((e as CustomEvent<EphemeralToastSpec>).detail);
    document.addEventListener(EPHEMERAL_TOAST_EVENT, listener);
    warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
  });

  afterEach(() => {
    document.removeEventListener(EPHEMERAL_TOAST_EVENT, listener);
    globalThis.fetch = originalFetch;
    warn.mockRestore();
  });

  it('reports the failure AND still delivers the following events', async () => {
    mockFetchSse(
      sseStream(
        'event: citations\ndata: {"citations":[]}\n\n',
        'event: chunk\ndata: {"text":"still arriving"}\n\n',
        'event: done\ndata: {}\n\n',
      ),
    );
    const { consumeShapeStream } = await import('./streams');

    const seen: string[] = [];
    await consumeShapeStream('http://localhost/test', {}, (event) => {
      seen.push(event);
      if (event === 'citations') throw new Error('citations renderer blew up');
    });

    // The rest of the stream arrived — the throw did not abort the reader.
    expect(seen).toEqual(['citations', 'chunk', 'done']);
    // …and the app was told, through the one message channel.
    expect(reported).toHaveLength(1);
    expect(reported[0]?.severity).toBe('warning');
    expect(reported[0]?.message).toContain('could not be displayed');
    // The console line stays for whoever can act on the exception itself.
    expect(warn).toHaveBeenCalled();
  });

  it('reports once per event name — a systematic failure is one signal, not hundreds', async () => {
    mockFetchSse(
      sseStream(
        'event: chunk\ndata: {"text":"a"}\n\n',
        'event: chunk\ndata: {"text":"b"}\n\n',
        'event: chunk\ndata: {"text":"c"}\n\n',
        'event: citations\ndata: {}\n\n',
        'event: done\ndata: {}\n\n',
      ),
    );
    const { consumeShapeStream } = await import('./streams');

    await consumeShapeStream('http://localhost/test', {}, (event) => {
      if (event === 'chunk' || event === 'citations') throw new Error(`${event} handler bug`);
    });

    // A handler that throws on `chunk` throws on every token; the dedup is per EVENT NAME, so a
    // second, distinct broken handler is still its own signal.
    expect(reported).toHaveLength(2);
    expect(warn.mock.calls.length).toBe(4);
  });

  it('a clean stream reports nothing', async () => {
    mockFetchSse(sseStream('event: chunk\ndata: {"text":"hi"}\n\n', 'event: done\ndata: {}\n\n'));
    const { consumeShapeStream } = await import('./streams');
    await consumeShapeStream('http://localhost/test', {}, () => {});
    expect(reported).toHaveLength(0);
  });

  it('a handler throw does not swallow the stream’s own terminal error', async () => {
    // Precision: the report must not become a substitute for the transport/error-event path. An
    // `error` event still throws to the caller even though its handler blew up on the way.
    mockFetchSse(sseStream('event: error\ndata: {"error":"AI_OFFLINE"}\n\n'));
    const { consumeShapeStream } = await import('./streams');
    await expect(
      consumeShapeStream('http://localhost/test', {}, () => {
        throw new Error('error handler bug');
      }),
    ).rejects.toThrow('AI_OFFLINE');
    expect(reported).toHaveLength(1);
  });
});
