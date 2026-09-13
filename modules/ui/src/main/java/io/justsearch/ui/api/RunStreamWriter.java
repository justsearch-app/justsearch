/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.sse.SseClient;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.observability.stream.SseStreamChannel;
import io.justsearch.app.observability.stream.run.RunChannel;
import io.justsearch.app.observability.stream.run.RunFrame;
import io.justsearch.app.observability.stream.run.RunStateSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The per-connection writer for a RUN stream — sibling to {@link SseEnvelopeWriter}, same
 * managed-{@link SseClient} orchestration, run vocabulary instead of envelope vocabulary (tempdoc
 * 834 §1.6).
 *
 * <p>Frames go out in today's bare {@code event:} / {@code data:} shape, not wrapped in the
 * envelope: migrating run streams to the envelope wire is a real option §1.6 deliberately did not
 * bundle. What IS shared is everything underneath — one channel, one sequence, one ring, one
 * atomic subscribe.
 *
 * <p><strong>The five protocol requirements</strong> (§1.6 / §15.1.2), each of which is a test:
 *
 * <ol>
 *   <li><strong>Absent cursor ⇒ replay from 0</strong>, never snapshot-only. {@code
 *       SseEnvelopeWriter} resumes only when a token is present and otherwise sends a snapshot; for
 *       a run stream that would silently discard everything published before the observer
 *       connected — the exact loss the journal exists to prevent. This also keeps run streams on
 *       the atomic path, since {@code subscribeAndReplay(listener, 0)} always succeeds.
 *   <li><strong>A window miss is handled explicitly.</strong> {@code subscribeAndReplay} returns
 *       empty AND REGISTERS NOTHING, so a caller that ignored the empty would hand the client a
 *       permanently dead stream. This writer emits {@code replay_truncated}, then re-subscribes
 *       from 0 and asserts that succeeded.
 *   <li><strong>{@code ?sinceSeq=<long>}</strong>, not {@code ?since=}: the envelope family's
 *       {@code ?since=} carries a {@code ResumeTokenCodec} token, and reusing the name for a raw
 *       integer would be a silent grammar fork. A {@code ?since=} on a run stream is REFUSED, not
 *       quietly read as 0.
 *   <li><strong>No {@code id:} line</strong> — {@code sendEvent(name, data)} is the two-arg form,
 *       so {@code Last-Event-ID} cannot become a second, unvalidated resume channel.
 *   <li><strong>The heartbeat is mandatory</strong>, not cosmetic (§15.0 D1.1). Probe D1 measured
 *       {@code onClose} at 850–1114 ms and ALWAYS on a tick boundary, never on socket close: a
 *       stream that writes nothing never learns its client left. The heartbeat is the only write a
 *       parked run makes, so it is the sole mechanism by which {@code onClose} fires for a parked
 *       run, and therefore the sole mechanism by which the zero-observer park and {@code
 *       observerCount} become true. 15 s cadence against a 120 s park window is an 8x margin, so
 *       detection cannot lose a race with the park timeout — at the cost of an honest residual:
 *       zero-observer detection is eventually consistent within one heartbeat interval.
 * </ol>
 *
 * <p><strong>Frame order deviates from §15.1.2's sentence, deliberately.</strong> That sentence
 * puts the snapshot AFTER {@code replay_truncated}; this writer emits the snapshot before the
 * subscribe attempt in BOTH branches, because §6.1 is the stronger statement: the primer is pushed
 * before the replay so it is the one frame guaranteed to arrive. Emitting it after a successful
 * replay would put it behind thousands of narrative frames on exactly the reattach that needs it
 * first.
 */
public final class RunStreamWriter {

  private static final Logger log = LoggerFactory.getLogger(RunStreamWriter.class);

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /** The run-identity frame, first on every run stream (§3.2). */
  public static final String RUN_STARTED_EVENT = "run_started";

  /** The act-on-the-run primer; same event name the agent vocabulary already uses. */
  public static final String STATE_SNAPSHOT_EVENT = "state_snapshot";

  /** Emitted when the requested cursor predates the retained window. */
  public static final String REPLAY_TRUNCATED_EVENT = "replay_truncated";

  /** The liveness frame — see requirement 5 above. */
  public static final String HEARTBEAT_EVENT = "heartbeat";

  /** The run-stream cursor parameter. Deliberately NOT {@code since} (requirement 3). */
  public static final String CURSOR_PARAM = "sinceSeq";

  /** The envelope family's cursor parameter, refused here so the two grammars cannot merge. */
  public static final String ENVELOPE_CURSOR_PARAM = "since";

  private final SseClient client;
  private final RunChannel run;
  private final Runnable onClientClose;
  private final java.util.concurrent.CompletableFuture<Void> connectionClosed = new java.util.concurrent.CompletableFuture<>();
  private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
  private final java.util.concurrent.atomic.AtomicReference<SseStreamChannel.Subscription> subscription =
      new java.util.concurrent.atomic.AtomicReference<>();
  private final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>> heartbeat =
      new java.util.concurrent.atomic.AtomicReference<>();

  private RunStreamWriter(SseClient client, RunChannel run, Runnable onClientClose) {
    this.client = Objects.requireNonNull(client, "client");
    this.run = Objects.requireNonNull(run, "run");
    this.onClientClose = Objects.requireNonNull(onClientClose, "onClientClose");
  }

  /**
   * Attaches {@code client} as an observer of {@code run}.
   *
   * @return the writer, or empty when the request's cursor grammar was refused (requirement 3) — in
   *     which case an {@code error} frame was sent and no listener was registered
   */
  public static Optional<RunStreamWriter> attach(
      SseClient client,
      RunChannel run,
      ScheduledExecutorService heartbeatScheduler,
      long heartbeatSeconds) {
    return attach(client, run, heartbeatScheduler, heartbeatSeconds, () -> {});
  }

  /** The creating run supplies its waiting-client transition; observers supply cleanup only. */
  public static Optional<RunStreamWriter> attach(
      SseClient client,
      RunChannel run,
      ScheduledExecutorService heartbeatScheduler,
      long heartbeatSeconds,
      Runnable onClientClose) {
    Objects.requireNonNull(run, "run");
    Objects.requireNonNull(heartbeatScheduler, "heartbeatScheduler");
    SseEnvelopeWriter.forceSseHeaders(client);
    RunStreamWriter writer = new RunStreamWriter(client, run, onClientClose);
    try {
    OptionalCursor cursor = writer.readCursor();
    if (cursor.rejected()) {
      // No operation has started. A malformed cursor's error is not a lost waiting client.
      writer.sendError(cursor.rejection(), ApiErrorCode.INVALID_REQUEST);
      return Optional.empty();
    }

    // Javalin 6.7 keepAlive creates its future after registering it and close is one-shot.
    // Own the future before installing the callback, so even a close before future registration
    // supplies an already-completed future instead of resurrecting a dead connection.
    client.onClose(writer::clientClosed);
    if (client.terminated()) {
      writer.clientClosed();
      return Optional.of(writer);
    }
    client.ctx().future(() -> writer.connectionClosed);

    writer.sendRunStarted();
    if (writer.closed.get()) return Optional.of(writer);
    run.snapshot().ifPresent(writer::sendSnapshot);
    if (writer.closed.get()) return Optional.of(writer);

    writer.subscription.set(writer.subscribeFrom(cursor.sinceSeq()));
    if (writer.closed.get()) {
      writer.releaseResources();
      return Optional.of(writer);
    }

    writer.heartbeat.set(
        heartbeatScheduler.scheduleAtFixedRate(
            writer::sendHeartbeat, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS));

    if (writer.closed.get()) {
      writer.releaseResources();
      return Optional.of(writer);
    }
    // The run being over is not the client's decision to notice: without this the connection would
    // hang open on a retired run until the socket happened to fail a write.
    run.onRetire(client::close);

    return Optional.of(writer);
    } catch (RuntimeException | Error failure) {
      try { writer.clientClosed(); }
      finally { client.close(); }
      throw failure;
    }
  }

  private void clientClosed() {
    if (!closed.compareAndSet(false, true)) return;
    try { onClientClose.run(); }
    finally {
      try { releaseResources(); }
      finally { connectionClosed.complete(null); }
    }
  }

  private void releaseResources() {
    var activeSubscription = subscription.getAndSet(null);
    try {
      if (activeSubscription != null) activeSubscription.unsubscribe();
    } finally {
      var activeHeartbeat = heartbeat.getAndSet(null);
      if (activeHeartbeat != null) activeHeartbeat.cancel(false);
    }
  }

  /**
   * Requirement 2, in one place: subscribe atomically from {@code sinceSeq}; on a window miss say
   * so and fall back to the path that always succeeds.
   */
  private SseStreamChannel.Subscription subscribeFrom(long sinceSeq) {
    Optional<SseStreamChannel.Subscription> resumed = run.observe(this::sendEnvelope, sinceSeq);
    if (resumed.isPresent()) {
      return resumed.get();
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sinceSeq", sinceSeq);
    body.put("oldestRetainedSeq", run.channel().oldestRetainedSeq());
    sendLifecycle(new RunFrame(REPLAY_TRUNCATED_EVENT, body));
    return run.observe(this::sendEnvelope, 0)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "subscribeAndReplay(listener, 0) must always succeed — the resume window "
                        + "rejects only a cursor ahead of the stream or behind the retained "
                        + "window, and 0 is neither"));
  }

  private OptionalCursor readCursor() {
    if (client.ctx() == null) {
      return OptionalCursor.at(0L);
    }
    String envelopeToken = client.ctx().queryParam(ENVELOPE_CURSOR_PARAM);
    if (envelopeToken != null && !envelopeToken.isBlank()) {
      return OptionalCursor.reject(
          "Run streams resume with ?"
              + CURSOR_PARAM
              + "=<seq>, not ?"
              + ENVELOPE_CURSOR_PARAM
              + "=<token>. One resume input, one grammar.");
    }
    String raw = client.ctx().queryParam(CURSOR_PARAM);
    if (raw == null || raw.isBlank()) {
      return OptionalCursor.at(0L);
    }
    try {
      long sinceSeq = Long.parseLong(raw.trim());
      if (sinceSeq < 0) {
        return OptionalCursor.reject("?" + CURSOR_PARAM + " must be >= 0, got: " + raw);
      }
      return OptionalCursor.at(sinceSeq);
    } catch (NumberFormatException notANumber) {
      return OptionalCursor.reject("?" + CURSOR_PARAM + " must be a whole number, got: " + raw);
    }
  }

  private void sendRunStarted() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("runId", run.id().value());
    body.put("shapeId", run.descriptor().shapeId());
    body.put("conversationId", run.descriptor().conversationId());
    sendLifecycle(new RunFrame(RUN_STARTED_EVENT, body));
  }

  private void sendSnapshot(RunStateSnapshot snapshot) {
    sendLifecycle(snapshot.asFrame(STATE_SNAPSHOT_EVENT));
  }

  /** Package-private for the heartbeat schedule; also the seam the cadence test drives. */
  void sendHeartbeat() {
    sendLifecycle(RunFrame.of(HEARTBEAT_EVENT));
  }

  /** The one SSE {@code error} shape, matching what the chat surface already emits. */
  private void sendError(String message, ApiErrorCode code) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", message);
    body.put("errorCode", code.name());
    body.put("errorClass", code.errorClass().name());
    body.put("retryable", code.isRetryable());
    sendLifecycle(new RunFrame("error", body));
  }

  /**
   * Sends a SEQUENCED-but-NOT-RETAINED frame: it consumes a seq so the wire stays monotonic, but it
   * never occupies a ring slot (§3.2). This is what lets a 15 s heartbeat run for the whole life of
   * a parked run without evicting narrative.
   */
  private void sendLifecycle(RunFrame frame) {
    sendEnvelope(run.lifecycle(frame));
  }

  private void sendEnvelope(SseEnvelope envelope) {
    if (closed.get()) return;
    RunFrame frame =
        RunFrame.from(envelope)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "A run channel carried a non-run frame: " + envelope.payload()));
    try {
      // The TWO-arg sendEvent, deliberately: the three-arg form writes an `id:` line, which would
      // re-open Last-Event-ID as a second, unvalidated resume channel beside ?sinceSeq (§1.6).
      client.sendEvent(frame.event(), MAPPER.writeValueAsString(frame.data()));
    } catch (RuntimeException e) {
      log.debug("Run stream frame send failed; the observer will be evicted on this throw", e);
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize run stream frame", e);
    }
  }

  /** A parsed cursor, or the reason the request's cursor grammar was refused. */
  private record OptionalCursor(long sinceSeq, String rejection) {

    private static OptionalCursor at(long sinceSeq) {
      return new OptionalCursor(sinceSeq, null);
    }

    private static OptionalCursor reject(String reason) {
      return new OptionalCursor(0L, reason);
    }

    private boolean rejected() {
      return rejection != null;
    }
  }
}
