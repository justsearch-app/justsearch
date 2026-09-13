/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.sse.SseClient;
import io.javalin.http.sse.SseHandler;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.observability.stream.run.RunChannel;
import io.justsearch.app.observability.stream.run.RunChannelCapacityExceededException;
import io.justsearch.app.observability.stream.run.RunChannelPolicy;
import io.justsearch.app.observability.stream.run.RunChannelRegistry;
import io.justsearch.app.observability.stream.run.RunDescriptor;
import io.justsearch.app.observability.stream.run.RunFrame;
import io.justsearch.app.observability.stream.run.RunId;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * The run-stream endpoint family (tempdoc 834 §1.6): a conversational run is created BY observing
 * it, and can be observed again by anyone else for as long as it lives.
 *
 * <pre>
 * POST /api/chat/runs                  → managed SSE; creates the run AND observes it
 * POST /api/chat/runs/{runId}/observe  → managed SSE; an additional / reattaching observer
 * </pre>
 *
 * <p><strong>Why POST, and why no filter change.</strong> Probe D1 confirmed live that {@code
 * app.post(path, new SseHandler(consumer))} yields a fully managed {@link SseClient} — request body
 * readable from {@code client.ctx()}, incremental streaming, a working {@code onClose} — and that
 * the existing {@code ApiSecurityFilters} token filter rejects an untokened POST with 401 BEFORE
 * the handler runs. A GET run-stream family would have shipped UNAUTHENTICATED (the filter returns
 * early for GET), and the journal carries prompts, answers, retrieved passage text and tool
 * arguments. Loopback-only is not a trust boundary here; the session token exists precisely because
 * other local processes are not trusted.
 *
 * <p><strong>Why the SSE handler is wrapped in a plain {@link Handler}.</strong> Once {@code
 * SseHandler} runs, the response is a committed 200 {@code text/event-stream} — a 404 or a 423 can
 * no longer be stated. So both routes validate FIRST and only then delegate: the run's existence
 * (§1.6's typed 404) and the locked-store gate (tempdoc 734 F4's 423) are answered as real HTTP
 * statuses, not as an SSE frame on a stream that claims success.
 *
 * <p><strong>Execution stays on the initiating thread</strong> (§1.7). The creating call IS the
 * first stream, so {@code engine.run} runs synchronously on the Jetty handler thread exactly as
 * today, and the run survives its client disconnecting because the handler thread keeps running and
 * the channel absorbs a dead observer's write failure. An executor becomes REQUIRED only if
 * "start a run without observing it" is ever wanted.
 */
public final class RunStreamController {

  private static final Logger LOG = LoggerFactory.getLogger(RunStreamController.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final long HEARTBEAT_SECONDS =
      StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS;

  /** How long a terminal run stays observable before its ring is dropped (§2). */
  static final Duration LINGER = Duration.ofSeconds(60);

  private final RunChannelRegistry registry;
  private final ChatController chat;
  private final Clock clock;
  private final EngineExecutorRegistry.Registration heartbeatRegistration;
  private final ScheduledExecutorService heartbeatScheduler;
  private final SseHandler createHandler;
  private final SseHandler observeHandler;

  public RunStreamController(
      EngineExecutorRegistry processExecutors,
      RunChannelRegistry registry, ChatController chat) {
    this(processExecutors, registry, chat, Clock.systemUTC());
  }

  public RunStreamController(
      EngineExecutorRegistry processExecutors,
      RunChannelRegistry registry, ChatController chat, Clock clock) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.chat = Objects.requireNonNull(chat, "chat");
    this.clock = Objects.requireNonNull(clock, "clock");
    SchedulerResources resources =
        openHeartbeatScheduler(
            processExecutors,
            "head.run-stream-heartbeat",
            "run-stream-heartbeat");
    this.heartbeatRegistration = resources.registration();
    this.heartbeatScheduler = resources.scheduler();
    this.createHandler = new SseHandler(this::streamNewRun);
    this.observeHandler = new SseHandler(this::streamExistingRun);
  }

  /** The registry this controller opens runs in — the enumeration (S4) reads the same one. */
  public RunChannelRegistry registry() {
    return registry;
  }

  // ── POST /api/chat/runs ──────────────────────────────────────────────────────────────────────

  /** Creates a run and streams it. Validates before any SSE status is committed. */
  public void handleCreate(Context ctx) throws Exception {
    Map<String, Object> body;
    try {
      body = readBody(ctx);
    } catch (RuntimeException malformed) {
      ctx.status(400)
          .json(ApiErrorHandler.toResponse(ApiErrorCode.BAD_REQUEST, message(malformed)));
      return;
    }
    Optional<ConversationShapeRef> shapeId = shapeIdOf(body);
    if (shapeId.isEmpty()) {
      ctx.status(400)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.INVALID_REQUEST, "Missing required field: shapeId"));
      return;
    }
    if (chat.wouldDiscardWhileLocked(shapeId.get(), body)) {
      LOG.info("Refusing run of shape {}: conversation store is locked", shapeId.get().value());
      Map<String, Object> locked =
          ApiErrorHandler.toResponse(
              ApiErrorCode.STORE_LOCKED,
              "Your chat history is encrypted and locked - unlock it to send a message.");
      locked.put("locked", true);
      ctx.status(423).json(locked);
      return;
    }
    createHandler.handle(ctx);
  }

  /** Package-private so the run mechanics are testable without a live Jetty async context. */
  void streamNewRun(SseClient client) {
    var front = RequestEngineWork.get(client.ctx());
    try (var work = front == null ? null : front.retain()) {
      streamNewRunOwned(client, work);
    }
  }

  private void streamNewRunOwned(SseClient client, io.justsearch.app.api.EngineWorkHandle work) {
    var engineContext = RequestEngineContext.get(client.ctx());
    var waiting = new java.util.concurrent.atomic.AtomicBoolean(true);
    Map<String, Object> body = readBody(client.ctx());
    ConversationShapeRef shapeId = shapeIdOf(body).orElseThrow();
    RunId runId = RunId.mint();
    RunChannel run;
    try {
      run =
          registry.open(
              runId,
              new RunDescriptor(shapeId.value(), conversationIdOf(body), clock.millis()),
              RunChannelPolicy.conversational());
    } catch (RunChannelCapacityExceededException full) {
      LOG.warn("Refusing a new run: {}", full.getMessage());
      // The stream is already committed here, so this one IS an SSE error rather than a status.
      // TRANSIENT, and honestly so: the cap is on CONCURRENT runs, so retrying once one finishes
      // succeeds. Refusing loudly is the point — evicting a live run to make room would drop
      // someone's in-flight answer silently.
      client.sendEvent(
          "error", serialize(errorBody(full.getMessage(), ApiErrorCode.SERVICE_UNAVAILABLE)));
      return;
    }
    try {
      try {
        if (RunStreamWriter.attach(client, run, heartbeatScheduler, HEARTBEAT_SECONDS, () -> {
          if (work != null && waiting.compareAndSet(true, false)) work.waitingClientGone();
        }).isEmpty()) {
          // The cursor grammar was refused. That is a malformed REQUEST, not a dead client, so
          // there is nothing to run — return before the shape is dispatched.
          return;
        }
      } catch (RuntimeException clientGone) {
        // The creating socket died before or during the attach. The run must NOT die with it
        // (§3.4): a one-shot shape at zero observers keeps going and persists, and the frames it
        // publishes are still journaled for anyone who reattaches. Only the writes the WRITER makes
        // directly (run_started, the primer, the replay) can throw out here — the channel's fan-out
        // absorbs a dead observer by evicting it.
        LOG.info(
            "The creating observer of run {} left before the stream was established; the run "
                + "continues unobserved",
            runId.value());
      }
      chat.runToSink(
          shapeId,
          body,
          ChatController.readAudience(client.ctx()),
          event -> run.publish(new RunFrame(event.name(), event.payload())), engineContext);
    } finally {
      // Server retirement closes the same SSE client. Disarm the waiting-client transition first:
      // normal completion must not masquerade as a client disconnect in the durable work row.
      waiting.set(false);
      // The run is terminal whichever way the body left: refuse further publishes, close the
      // attached connections, and keep the ring readable for the linger so a tab reloading as the
      // answer lands still replays it.
      registry.retire(runId, LINGER);
    }
  }

  // ── POST /api/chat/runs/{runId}/observe ──────────────────────────────────────────────────────

  /**
   * Attaches an additional observer to an existing run. An unknown or long-retired run answers
   * §1.6's typed 404 — NOT a 200 with an empty stream, and not a 200 with {@code replay_truncated}:
   * that would say "you missed part" when the truth is "this run is over, read the record".
   */
  public void handleObserve(Context ctx) throws Exception {
    Optional<RunId> runId = parseRunId(ctx.pathParam("runId"));
    if (runId.isPresent() && registry.find(runId.get()).isPresent()) {
      observeHandler.handle(ctx);
      return;
    }
    String raw = ctx.pathParam("runId");
    RunChannelRegistry.Lookup lookup =
        runId.map(registry::lookup).orElse(RunChannelRegistry.Lookup.UNKNOWN);
    Map<String, Object> notFound = new LinkedHashMap<>();
    notFound.put("runId", raw);
    notFound.put("reason", lookup == RunChannelRegistry.Lookup.RETIRED ? "retired" : "unknown");
    notFound.put("recordHint", recordHint(runId.orElse(null)));
    ctx.status(404).json(notFound);
  }

  private void streamExistingRun(SseClient client) {
    Optional<RunId> runId = parseRunId(client.ctx().pathParam("runId"));
    Optional<RunChannel> run = runId.flatMap(registry::find);
    if (run.isEmpty()) {
      // Retired between the pre-check and here — a real race, and the honest answer on a committed
      // stream is the same sentence the 404 carries.
      client.sendEvent(
          "error", serialize(errorBody("This run is no longer live", ApiErrorCode.NOT_FOUND)));
      return;
    }
    RunStreamWriter.attach(client, run.get(), heartbeatScheduler, HEARTBEAT_SECONDS);
  }

  /**
   * Where the record of a run lives, for a client that arrived too late to observe it. An agent run
   * has a durable event ledger; a conversational run's journal is ephemeral by §4, so the honest
   * answer is the persisted transcript — the client must be TOLD that rather than shown an empty
   * stream.
   */
  private String recordHint(RunId runId) {
    Optional<RunDescriptor> retired =
        runId == null ? Optional.empty() : registry.retiredDescriptor(runId);
    if (retired.isEmpty()) {
      return "";
    }
    String conversationId = retired.get().conversationId();
    if (!conversationId.isBlank()) {
      return "/api/chat/conversations/" + conversationId;
    }
    return "/api/chat/sessions/" + runId.value();
  }

  /** Stops the heartbeat scheduler and retires every open run. Call on shutdown. */
  public void shutdown() {
    heartbeatScheduler.shutdownNow();
    heartbeatRegistration.close();
    registry.clear();
  }

  // ── helpers ──────────────────────────────────────────────────────────────────────────────────

  private static Optional<RunId> parseRunId(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(new RunId(raw.trim()));
    } catch (IllegalArgumentException notARunId) {
      return Optional.empty();
    }
  }

  private static Optional<ConversationShapeRef> shapeIdOf(Map<String, Object> body) {
    Object raw = body.get("shapeId");
    if (raw == null || raw.toString().isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new ConversationShapeRef(raw.toString().trim()));
  }

  private static String conversationIdOf(Map<String, Object> body) {
    Object conversationId = body.get("conversationId");
    if (conversationId != null && !conversationId.toString().isBlank()) {
      return conversationId.toString().trim();
    }
    Object sessionId = body.get("sessionId");
    return sessionId == null ? "" : sessionId.toString().trim();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> readBody(Context ctx) {
    if (ctx == null || ctx.body() == null || ctx.body().isEmpty()) {
      return Map.of();
    }
    return MAPPER.readValue(ctx.body(), Map.class);
  }

  private static Map<String, Object> errorBody(String message, ApiErrorCode code) {
    Map<String, Object> err = new LinkedHashMap<>();
    err.put("error", message);
    err.put("errorCode", code.name());
    err.put("errorClass", code.errorClass().name());
    err.put("retryable", code.isRetryable());
    return err;
  }

  private static String serialize(Map<String, Object> body) {
    return MAPPER.writeValueAsString(body);
  }

  private static String message(Exception e) {
    return e.getMessage() == null ? e.toString() : e.getMessage();
  }
  private static SchedulerResources openHeartbeatScheduler(
      EngineExecutorRegistry processExecutors, String name, String threadName) {
    Objects.requireNonNull(processExecutors, "processExecutors");
    EngineExecutorRegistry.Limits background =
        processExecutors.limits(EngineExecutorSpec.Kind.BACKGROUND);
    EngineExecutorRegistry.Registration registration =
        processExecutors.register(
            new EngineExecutorSpec(
                name,
                EngineExecutorSpec.Kind.BACKGROUND,
                EngineExecutorSpec.Mode.SCHEDULED,
                1,
                background.maxQueue(),
                1));
    try {
      ScheduledExecutorService scheduler =
          registration.openScheduled(
              runnable -> {
                Thread thread = new Thread(runnable, threadName);
                thread.setDaemon(true);
                return thread;
              });
      return new SchedulerResources(registration, scheduler);
    } catch (RuntimeException | Error failure) {
      try {
        registration.close();
      } catch (RuntimeException | Error cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  private record SchedulerResources(
      EngineExecutorRegistry.Registration registration, ScheduledExecutorService scheduler) {}
}
