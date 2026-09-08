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
    // The diagnostics ring is localStorage-backed and therefore outlives `vi.resetModules()`;
    // clear it so one case's rows cannot be counted by the next.
    localStorage.removeItem('jf.stream-handler-telemetry');
  });

  afterEach(() => {
    document.removeEventListener(EPHEMERAL_TOAST_EVENT, listener);
    globalThis.fetch = originalFetch;
    warn.mockRestore();
    localStorage.removeItem('jf.stream-handler-telemetry');
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
    expect(reported[0]?.message).toContain('could not be displayed');
    // A NON-terminal event: the rest really is still arriving, so the notice may say so.
    expect(reported[0]?.message).toContain('still arriving');
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
    // second, distinct broken handler is still its own signal. (Both carry `supersede`, so the
    // toast host shows the later one rather than stacking — this counts EMITS, not overlays.)
    expect(reported).toHaveLength(2);
    expect(warn.mock.calls.length).toBe(4);
  });

  it('a clean stream reports nothing', async () => {
    mockFetchSse(sseStream('event: chunk\ndata: {"text":"hi"}\n\n', 'event: done\ndata: {}\n\n'));
    const { consumeShapeStream } = await import('./streams');
    await consumeShapeStream('http://localhost/test', {}, () => {});
    expect(reported).toHaveLength(0);
  });

  it('a throwing `error` handler reports NOTHING extra — the caller already gets the real error', async () => {
    // `errorFromEvent` is assembled from the payload BEFORE the handler is dispatched and rethrown
    // once the stream drains, so the caller surfaces a real, specific error for this turn. A toast
    // here would be a second notice about one failure — and the vaguer of the two would be the one
    // claiming "the rest of it is still arriving", which is false: `error` ended the stream.
    mockFetchSse(sseStream('event: error\ndata: {"error":"AI_OFFLINE"}\n\n'));
    const { consumeShapeStream } = await import('./streams');
    await expect(
      consumeShapeStream('http://localhost/test', {}, () => {
        throw new Error('error handler bug');
      }),
    ).rejects.toThrow('AI_OFFLINE');
    expect(reported).toHaveLength(0);
    // …but the FE defect still leaves an inspectable trace. Silence to the reader is not silence
    // to whoever is diagnosing.
    const { readStreamHandlerFailures } = await import('./streamHandlerTelemetry');
    expect(readStreamHandlerFailures().map((e) => e.event)).toEqual(['error']);
  });

  it('a throwing `done` handler does not claim the rest is still arriving', async () => {
    // `receivedTerminal` is likewise set before dispatch: when a `done` handler throws, nothing
    // more is coming. `done` is also where a throw costs most — the handler that commits the
    // finished turn is a `done` handler, so its throw drops the whole answer, which is why this
    // one still reports rather than staying silent.
    mockFetchSse(sseStream('event: chunk\ndata: {"text":"hi"}\n\n', 'event: done\ndata: {}\n\n'));
    const { consumeShapeStream } = await import('./streams');
    await consumeShapeStream('http://localhost/test', {}, (event) => {
      if (event === 'done') throw new Error('commit handler bug');
    });
    expect(reported).toHaveLength(1);
    expect(reported[0]?.message).toContain('may be incomplete');
    expect(reported[0]?.message).not.toContain('still arriving');
  });

  it('the reader notice is polite and superseding, not an assertive pile', async () => {
    // severity drives announcement politeness (`presentationForSeverity`): `warning` resolves to
    // `live: 'alert'`, an assertive screen-reader interruption cutting across an answer that is
    // still streaming. `info` is the polite `status` role. `supersede` keeps one bug that breaks
    // several handlers of one turn from stacking overlays over the answer being read.
    mockFetchSse(sseStream('event: chunk\ndata: {"text":"a"}\n\n', 'event: done\ndata: {}\n\n'));
    const { consumeShapeStream } = await import('./streams');
    await consumeShapeStream('http://localhost/test', {}, (event) => {
      if (event === 'chunk') throw new Error('chunk handler bug');
    });
    expect(reported[0]?.classId).toBe('core.stream.partial-failure');
    expect(reported[0]?.severity).toBe('info');
    expect(reported[0]?.supersede).toBe(true);
  });

  it('dedupe is per STREAM, not module-level — a later stream reports its own failures', async () => {
    // The `alreadyReported` set is declared inside `consumeShapeStream`. If it were module-level,
    // the second stream would inherit the first one's silence and a reader who retried after a bad
    // turn would be told nothing at all.
    const { consumeShapeStream } = await import('./streams');
    const throwOnChunk = (event: string): void => {
      if (event === 'chunk') throw new Error('chunk handler bug');
    };

    mockFetchSse(sseStream('event: chunk\ndata: {"text":"a"}\n\n', 'event: done\ndata: {}\n\n'));
    await consumeShapeStream('http://localhost/test', {}, throwOnChunk);
    expect(reported).toHaveLength(1);

    mockFetchSse(sseStream('event: chunk\ndata: {"text":"b"}\n\n', 'event: done\ndata: {}\n\n'));
    await consumeShapeStream('http://localhost/test', {}, throwOnChunk);
    expect(reported).toHaveLength(2);
  });

  it('the diagnostics ring accumulates across streams, one row per stream per event', async () => {
    const { consumeShapeStream } = await import('./streams');
    const { readStreamHandlerFailures, summarizeStreamHandlerFailures } = await import(
      './streamHandlerTelemetry'
    );
    for (let i = 0; i < 2; i++) {
      // Three `chunk` frames per stream: the ring must record ONE row for them, not three.
      mockFetchSse(
        sseStream(
          'event: chunk\ndata: {"text":"a"}\n\n',
          'event: chunk\ndata: {"text":"b"}\n\n',
          'event: chunk\ndata: {"text":"c"}\n\n',
          'event: done\ndata: {}\n\n',
        ),
      );
      await consumeShapeStream('http://localhost/test', {}, (event) => {
        if (event === 'chunk') throw new Error('chunk handler bug');
      });
    }
    expect(readStreamHandlerFailures()).toHaveLength(2);
    // Repetition ACROSS streams is what distinguishes a one-off from a systematic break, so it is
    // the axis the summary counts.
    expect(summarizeStreamHandlerFailures().byEvent).toEqual([{ event: 'chunk', count: 2 }]);
  });
});
