/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.services.observability.HeadApiMetricCatalog;
import io.justsearch.app.services.observability.HeadApiTags.ApiStreamTags;
import io.justsearch.app.services.observability.HttpMethod;
import io.justsearch.app.services.observability.StreamTransport;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * SSE event writing with per-context synchronization and TTFT recording.
 *
 * <p>Extracted from SummaryController to serve as a shared utility for all streaming endpoints.
 * Multiple threads (chunk emitter, completion handler, timeout handler) may call write methods
 * concurrently for the same SSE connection; synchronization prevents interleaved/malformed events.
 */
class SseWriter {
  private static final Logger log = LoggerFactory.getLogger(SseWriter.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String ATTR_TTFT_RECORDER = "justsearch.api.ttft_recorder_v1";
  private static final String ATTR_SSE_LOCK = "justsearch.api.sse_write_lock";

  private final HeadApiMetricCatalog apiCatalog;

  SseWriter(HeadApiMetricCatalog apiCatalog) {
    this.apiCatalog = apiCatalog;
  }

  void beginTtft(Context ctx, String route) {
    if (apiCatalog == null || ctx == null || route == null || route.isBlank()) {
      return;
    }
    ctx.attribute(ATTR_TTFT_RECORDER, new TtftRecorder(apiCatalog, route));
  }

  /**
   * The outcome of an SSE write, distinguishing a genuine client-disconnect (the stream is gone — a
   * run observer should be EVICTED) from a serialization failure (THIS event is unwritable but the
   * connection is fine — skip the event, KEEP the observer). Tempdoc 577 §2.14 Root I: the
   * zero-observer eviction must fire only on a real disconnect, never on a bad payload — otherwise a
   * non-serializable event (e.g. a tool's {@code structuredData}) would evict a live observer AND,
   * sitting in the replay ring, re-poison every reattach.
   *
   * <p>Tempdoc 834 §7-S3b keeps this enum after the hub deletion: {@code AgentSseWriter.writeOrEvict}
   * is still the ONLY disconnect signal the raw {@code Context}-based attach routes have (the
   * managed run streams get {@code onClose} instead), and {@code SseWriterTest} pins the
   * serialization-vs-disconnect distinction itself.
   */
  enum SseWriteOutcome {
    WRITTEN,
    SERIALIZATION_FAILED,
    CLIENT_GONE
  }

  /**
   * Best-effort SSE writer with per-context synchronization.
   *
   * @return true if the event was written/flushed successfully, false on ANY failure (serialization
   *     or disconnect). Façade over {@link #writeResult} for callers that only need "did it go out"
   *     (including heartbeat delivery); run-observer callers
   *     use {@link #writeResult} to distinguish the failure modes.
   */
  boolean writeEvent(Context ctx, String event, Map<String, ?> payload) {
    return writeResult(ctx, event, payload) == SseWriteOutcome.WRITTEN;
  }

  /**
   * SSE write that REPORTS its failure mode (per-context synchronized like {@link #writeEvent}).
   * Serialization is attempted first, outside the write lock (it touches no connection state); a
   * failure there is {@link SseWriteOutcome#SERIALIZATION_FAILED} — the socket is fine. Only an
   * {@code IOException} (incl. Jetty {@code EofException}) or other error from the actual
   * {@code write}/{@code flushBuffer} is {@link SseWriteOutcome#CLIENT_GONE}.
   */
  SseWriteOutcome writeResult(Context ctx, String event, Map<String, ?> payload) {
    return writeResult(ctx, null, event, payload);
  }

  /**
   * Tempdoc 585 §D Phase 2 (B1) — SSE write that additionally stamps the WHATWG {@code id:} field
   * (the {@code Last-Event-ID} a reconnecting client echoes back) when {@code id != null}. Per the
   * spec the {@code id:} line precedes {@code event:}/{@code data:} in the field block. A {@code null}
   * id writes the plain block (the {@link #writeResult(Context, String, Map)} default), so
   * non-agent streams are unaffected.
   */
  SseWriteOutcome writeResult(Context ctx, Long id, String event, Map<String, ?> payload) {
    String data;
    try {
      String json = MAPPER.writeValueAsString(payload);
      String idLine = id == null ? "" : "id: " + id + "\n";
      data =
          (event == null || event.isBlank())
              ? idLine + "data: " + json + "\n\n"
              : idLine + "event: " + event + "\ndata: " + json + "\n\n";
    } catch (RuntimeException serializationError) {
      // Jackson 3 serialization failures are unchecked. The CONNECTION is fine — drop THIS event;
      // do NOT treat it as a disconnect (which would evict a live observer + re-poison reattaches).
      log.warn("Failed to serialize SSE event {} (skipping; connection unaffected)", event,
          serializationError);
      return SseWriteOutcome.SERIALIZATION_FAILED;
    }
    Object lock = ctx.attributeOrCompute(ATTR_SSE_LOCK, k -> new Object());
    synchronized (lock) {
      try {
        ctx.res().getWriter().write(data);
        ctx.res().flushBuffer();
        try {
          Object rec = ctx.attribute(ATTR_TTFT_RECORDER);
          if (rec instanceof TtftRecorder r) {
            r.onFirstFlush();
          }
        } catch (Exception ignored) {
          // best-effort
        }
        return SseWriteOutcome.WRITTEN;
      } catch (IOException | RuntimeException clientGone) {
        // IOException (incl. Jetty EofException) = socket closed; a RuntimeException here (e.g.
        // response already committed) likewise means the stream is unusable ⇒ the client is gone.
        log.warn("Failed to write SSE event {} (client gone)", event, clientGone);
        return SseWriteOutcome.CLIENT_GONE;
      }
    }
  }

  /** Configures standard SSE response headers on a Javalin context. */
  void initSseHeaders(Context ctx, String route) {
    ctx.contentType("text/event-stream; charset=utf-8");
    ctx.header("Cache-Control", "no-cache");
    ctx.header("Connection", "keep-alive");
    ctx.header("X-Accel-Buffering", "no");
    beginTtft(ctx, route);
  }

  private static final class TtftRecorder {
    private final HeadApiMetricCatalog apiCatalog;
    private final long startNs;
    private final ApiStreamTags tags;
    private final java.util.concurrent.atomic.AtomicBoolean fired =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    TtftRecorder(HeadApiMetricCatalog apiCatalog, String route) {
      this.apiCatalog = apiCatalog;
      this.startNs = System.nanoTime();
      this.tags = new ApiStreamTags(route, HttpMethod.POST, StreamTransport.SSE);
    }

    void onFirstFlush() {
      if (apiCatalog == null) {
        return;
      }
      if (!fired.compareAndSet(false, true)) {
        return;
      }
      long durMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
      apiCatalog.streamTtftMs.record(Math.max(0, durMs), tags);
    }
  }
}
