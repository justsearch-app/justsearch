/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.core.context.EngineContext;

import io.javalin.http.Context;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.conversation.BranchesPreventDeletionException;
import io.justsearch.agent.api.conversation.ConversationStore;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.conversation.AgentRunShape;
import io.justsearch.app.services.conversation.ConversationEngine;
import io.justsearch.telemetry.Telemetry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Generic substrate-driven shape handler.
 *
 * <p>Per tempdoc 491 §9 Phase C: each new typed endpoint (e.g., {@code /api/chat/summarize},
 * {@code /api/chat/ask}, {@code /api/chat/summarize/batch}, {@code /api/chat/summarize/hierarchical})
 * binds to a fixed {@link ConversationShapeRef} and delegates to this controller. The
 * controller parses the body as a Map, reads the {@code X-JustSearch-Audience} header
 * (default {@link Audience#USER}), initializes SSE response headers, and invokes
 * {@link ConversationEngine#run} with a sink that writes each emitted
 * {@link io.justsearch.agent.api.conversation.SseEvent} to the wire.
 *
 * <p>The agent shape's controller ({@code AgentController}) handles the agent-specific
 * 503 short-circuit (when {@code AgentService.isAvailable()} is false) before delegating
 * to the engine. Other shapes — like summarize / RAG ask — route through this generic
 * controller; their LLM-unavailable case surfaces as an SSE {@code error} event from
 * the engine's substrate-driven path.
 */
public final class ChatController {

  private static final Logger LOG = LoggerFactory.getLogger(ChatController.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String AUDIENCE_HEADER = "X-JustSearch-Audience";

  /**
   * Tempdoc 734 round-14 F4 — HTTP 423 Locked, the answer this codebase already gives for "an
   * AUTHORED store's data key is locked": the conversation-history read raises it through the global
   * {@code KeyLockedException} mapping ({@code LocalApiServer}) and {@code MemoryController} answers
   * its locked mutations with it. The dispatch write path conforms rather than minting a second
   * status for the one condition.
   */
  private static final int LOCKED_STATUS = 423;

  private final ConversationEngine engine;
  private final SseWriter sseWriter;
  private final Telemetry telemetry;
  private final ConversationStore conversationStore;
  // Tempdoc 610 Phase D — the one-shot summarizer for compaction (reuses the
  // existing OnlineAiService.summarize). Supplied (not held) so the controller
  // tolerates the runtime being unavailable / hot-swapped, mirroring the
  // assembly's onlineAiSupplier.
  private final Supplier<OnlineAiService> onlineAi;
  /**
   * Tempdoc 859 slice C PR-2 — the SECOND record the conversation list reads. A delegate run persists
   * a complete conversation into the agent-run store and no {@code ConversationStore} row at all, so a
   * list that reads one store lists half the conversations. Supplied (not held) for the same reason
   * {@code onlineAi} is: the agent capability is constructed lazily and may be unavailable.
   */
  private final Supplier<AgentService> agentService;

  /**
   * Tempdoc 859 D live-defect D2 — the SAME out-of-band liveness heartbeat {@link AgentController}
   * has carried since 604. This route dispatches Search v3's DELEGATE runs ({@code shapeId=
   * core.agent-run} through {@code host.ai.streamShape}), and a delegate run parks at gates and emits
   * nothing while it waits. Without a beat the FE's 40 s watchdog declared the stream dead, the
   * panel went stale, its arms stopped working, and the run timed out server-side into
   * {@code BUDGET_EDGE_FINALIZE} — reproduced three times on 2026-08-25. Fixed at the producer, not
   * by widening the watchdog, which would have masked genuinely dead connections.
   */
  private final SseHeartbeat heartbeat;

  public ChatController(
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      ConversationEngine engine,
      SseWriter sseWriter,
      Telemetry telemetry,
      ConversationStore conversationStore,
      Supplier<OnlineAiService> onlineAi,
      Supplier<AgentService> agentService) {
    this(executors, engine, sseWriter, telemetry, conversationStore, onlineAi, agentService,
        // A lambda, not a method reference: the reference would bind (and null-check) the writer at
        // construction, and this controller has constructors that legitimately pass nothing useful.
        new SseHeartbeat(
            executors,
            (ctx, event, payload) -> sseWriter.writeEvent(ctx, event, payload),
            "chat-stream-heartbeat"));
  }

  /** Test seam: an {@link SseHeartbeat} whose scheduler and cadence a test can drive. */
  ChatController(
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      ConversationEngine engine,
      SseWriter sseWriter,
      Telemetry telemetry,
      ConversationStore conversationStore,
      Supplier<OnlineAiService> onlineAi,
      Supplier<AgentService> agentService,
      SseHeartbeat heartbeat) {
    this.engine = engine;
    this.sseWriter = sseWriter;
    this.telemetry = telemetry;
    this.conversationStore = conversationStore;
    this.onlineAi = onlineAi;
    this.agentService = agentService;
    this.heartbeat = heartbeat;
  }

  /** Stops the heartbeat scheduler. Call on shutdown (tempdoc 638 PE's asymmetry, not repeated). */
  public void shutdown() {
    heartbeat.shutdown();
  }

  /** Test-only: whether {@link #shutdown()} has stopped the heartbeat scheduler. */
  boolean isHeartbeatSchedulerShutdown() {
    return heartbeat.isShutdown();
  }

  public ChatController(
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      ConversationEngine engine,
      SseWriter sseWriter,
      Telemetry telemetry,
      ConversationStore conversationStore,
      Supplier<OnlineAiService> onlineAi) {
    this(executors, engine, sseWriter, telemetry, conversationStore, onlineAi, AgentService::unavailable);
  }

  public ChatController(
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      ConversationEngine engine,
      SseWriter sseWriter,
      Telemetry telemetry,
      ConversationStore conversationStore) {
    this(executors, engine, sseWriter, telemetry, conversationStore, OnlineAiService::unavailable);
  }

  public ChatController(io.justsearch.core.execution.EngineExecutorRegistry executors, ConversationEngine engine, SseWriter sseWriter, Telemetry telemetry) {
    this(executors, engine, sseWriter, telemetry, ConversationStore.noop());
  }

  /** Returns a handler that runs the supplied shape via the engine. */
  public io.javalin.http.Handler handler(ConversationShapeRef shapeId, String route) {
    return ctx -> dispatch(ctx, shapeId, route);
  }

  /**
   * Returns a handler that reads {@code shapeId} from the JSON body and dispatches dynamically.
   * Used by the unified chat surface ({@code POST /api/chat/dispatch}) so the FE picks the
   * shape per-message via affordance state.
   */
  public io.javalin.http.Handler dynamicHandler(String route) {
    return ctx -> {
      Object rawShapeId = readBody(ctx).get("shapeId");
      if (rawShapeId == null || rawShapeId.toString().isBlank()) {
        sseWriter.initSseHeaders(ctx, route);
        sseError(ctx, "Missing required field: shapeId", ApiErrorCode.INVALID_REQUEST);
        return;
      }
      dispatch(ctx, new ConversationShapeRef(rawShapeId.toString().trim()), route);
    };
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> readBody(Context ctx) {
    return ctx.body() == null || ctx.body().isEmpty()
        ? Map.of()
        : MAPPER.readValue(ctx.body(), Map.class);
  }

  /**
   * The one SSE {@code error} event shape: message + the typed code triple the FE reads. Built as
   * an {@link SseEvent} so the {@code ctx}-writing form below and the SINK-writing form in
   * {@link #runToSink} cannot drift into two error vocabularies.
   */
  private static SseEvent errorEvent(String message, ApiErrorCode code) {
    Map<String, Object> err = new LinkedHashMap<>();
    err.put("error", message);
    err.put("errorCode", code.name());
    err.put("errorClass", code.errorClass().name());
    err.put("retryable", code.isRetryable());
    return new SseEvent("error", err);
  }

  /**
   * Writes an error DIRECTLY to the response. Correct only for the PRE-run failures — a malformed
   * body, a missing {@code shapeId} — where no run exists and therefore no run journal could carry
   * the error. Mid-run failures go through {@link #runToSink} instead (tempdoc 834 §15.1.3).
   */
  private void sseError(Context ctx, String message, ApiErrorCode code) {
    SseEvent event = errorEvent(message, code);
    sseWriter.writeEvent(ctx, event.name(), event.payload());
  }

  private static String message(Exception e) {
    return e.getMessage() == null ? e.toString() : e.getMessage();
  }

  private void dispatch(Context ctx, ConversationShapeRef shapeId, String route) {
    var engineContext = RequestEngineContext.get(ctx);
    // Tempdoc 734 round-14 F4 — the locked gate runs BEFORE the SSE headers commit a 200: with chat
    // persistence encrypted and locked, a turn that would be recorded is accepted-and-dropped (the
    // append throws, nothing reaches disk, and the transcript after unlock holds no trace of it). The
    // read path already answers this condition 423; the write path now gives the same answer instead
    // of an empty success. Parsed here (rather than inside the try below) because the answer depends
    // on the body's write key — a body that will not parse keeps its previous SSE-error behaviour.
    Map<String, Object> parsedBody;
    try {
      parsedBody = readBody(ctx);
    } catch (RuntimeException malformed) {
      sseWriter.initSseHeaders(ctx, route);
      LOG.error("Chat dispatch failed for shape {}", shapeId.value(), malformed);
      sseError(ctx, message(malformed), ApiErrorCode.BAD_REQUEST);
      return;
    }
    if (engine.wouldDiscardWhileLocked(shapeId, parsedBody)) {
      LOG.info("Refusing dispatch of shape {}: conversation store is locked", shapeId.value());
      Map<String, Object> locked =
          ApiErrorHandler.toResponse(
              ApiErrorCode.STORE_LOCKED,
              "Your chat history is encrypted and locked - unlock it to send a message.");
      locked.put("locked", true);
      ctx.status(LOCKED_STATUS).json(locked);
      return;
    }
    sseWriter.initSseHeaders(ctx, route);
    // Tempdoc 859 D live-defect D2 — the heartbeat wraps the BLOCKING run, and only it: the pre-run
    // refusals above answer and return without ever opening a stream to beat on.
    try {
      heartbeat.around(
          ctx,
          () ->
              runToSink(
                  shapeId,
                  parsedBody,
                  readAudience(ctx),
                  sseEvent -> sseWriter.writeEvent(ctx, sseEvent.name(), sseEvent.payload()), engineContext));
    } catch (Exception impossible) {
      // runToSink catches every mid-run failure and reports it ON THE RUN (§15.1.3); the only way
      // out of `around` is therefore a failure of the heartbeat plumbing itself, which must not be
      // swallowed silently nor turned into a second error vocabulary.
      LOG.error("Chat dispatch stream failed outside the run for shape {}", shapeId.value(), impossible);
      sseError(ctx, message(impossible), ApiErrorCode.BAD_REQUEST);
    }
    // Suppress unused-field warning until telemetry is wired into per-shape spans (Phase D).
    if (telemetry == null) {
      LOG.trace("telemetry sink not configured");
    }
  }

  /**
   * Runs a shape against {@code sink} — and reports EVERY mid-run failure through that same sink.
   *
   * <p>Tempdoc 834 §15.1.3. Before this, the three catch arms wrote the error straight to the
   * request's {@code Context}. That is invisible-by-construction under a run journal: the creating
   * client sees the failure, and every OTHER observer — a second tab, a reattacher — sees a stream
   * that simply stops. A run that fails has to fail ON THE RUN, where all its observers are.
   *
   * <p>The pre-run failures (a body that will not parse, a missing {@code shapeId}) keep writing to
   * the response: there is no run yet, so there is no journal to fail on.
   */
  public void runToSink(
      ConversationShapeRef shapeId,
      Map<String, Object> body,
      Audience audience,
      java.util.function.Consumer<SseEvent> sink, EngineContext engineContext) {
    try {
      engine.run(shapeId, body, audience, sink, engineContext);
    } catch (ConversationEngine.AudienceDeniedException denied) {
      LOG.info("Audience denied for shape {}: {}", shapeId.value(), denied.getMessage());
      sink.accept(errorEvent(denied.getMessage(), ApiErrorCode.INVALID_REQUEST));
    } catch (ConversationEngine.ShapeNotFoundException notFound) {
      LOG.error("Shape not registered: {}", shapeId.value());
      sink.accept(errorEvent(notFound.getMessage(), ApiErrorCode.NOT_FOUND));
    } catch (io.justsearch.app.api.EngineWorkCancelledException cancelled) {
      sink.accept(new SseEvent("error", Map.of("message", cancelled.getMessage(),
          "errorCode", ApiErrorCode.SERVICE_UNAVAILABLE.name(), "reasonCode", cancelled.reasonCode())));
    } catch (Exception e) {
      LOG.error("Chat dispatch failed for shape {}", shapeId.value(), e);
      sink.accept(errorEvent(message(e), ApiErrorCode.BAD_REQUEST));
    }
  }

  /**
   * The 423 gate, asked BEFORE any stream commits a 200 (tempdoc 734 round-14 F4). Exposed so the
   * run-stream routes ask the same question through the same authority rather than re-deriving
   * which shapes+bodies would record.
   */
  public boolean wouldDiscardWhileLocked(ConversationShapeRef shapeId, Map<String, Object> body) {
    return engine.wouldDiscardWhileLocked(shapeId, body);
  }


  /**
   * {@code GET /api/chat/conversations} — the conversation list, joined across the TWO records that
   * hold conversations (tempdoc 859 slice C PR-2).
   *
   * <p>Before this join the endpoint read {@code ConversationStore} alone, which lists exactly the
   * conversations whose turns were APPENDED as messages. A delegate run written before tempdoc 863
   * appended none: {@code ConversationEngine.dispatchShapeDriven} did not call {@code appendMessage},
   * so such a conversation has a complete, disk-backed record in the agent-run store, is served in
   * full by {@code GET /api/thread/{id}} (which already merges both planes), and had no index entry
   * anywhere: an intact record with no door to it. That is still exactly true of the runs that will
   * always be run-plane-only — standalone runs and background runs — and of every delegate
   * conversation created before the stamp, which is why this join stays.
   *
   * <p><b>Superseded (tempdoc 863 §4.A.5 A-4).</b> This comment used to record a rejected
   * alternative: <i>"having agent runs mint a {@code ConversationStore} row. The decisive argument is
   * the DOUBLE-RENDER — {@code InteractionThreadController} already merges both planes, so
   * agent-authored store messages would render every delegate turn twice."</i> That argument was
   * correct and unanswered right up until 863 shipped its answer, which is a STAMP rather than a
   * counter-argument: a run the engine recorded to the conversation record persists
   * {@code recordsToThread: true} in its run meta, and {@code AgentRunQueryService.threadEvents}
   * suppresses its own synthesised user turn and terminal assistant message for exactly those runs.
   * One delegate turn therefore renders once through BOTH consumers. A {@code core.agent-run}
   * conversation created since is store-backed, so {@link #hasStoreSession} dedups it here, no row is
   * synthesised for it, {@code storeBacked} is absent (i.e. true) and RENAME becomes available on it.
   *
   * <p>The contract this join commits to:
   *
   * <ul>
   *   <li><b>{@code limit} applies PER STORE</b>, and the merged list is re-sorted by last activity
   *       and re-limited — so the response never exceeds {@code limit} while neither store can starve
   *       the other out of the window.
   *   <li><b>{@code shapeId} filters both sides.</b> The store side filters as it always has; the run
   *       side matches only {@link AgentRunShape#ID}, so a query naming any other shape excludes
   *       synthesized rows ENTIRELY rather than silently including them.
   *   <li><b>A conversation that exists in both records is listed ONCE</b>, as its store row: a mixed
   *       conversation (chat turns plus a delegate run) is store-backed, and its row keeps the full
   *       action set. Membership is resolved against the STORE ({@link #hasStoreSession}), never
   *       against the limited window this method just fetched — see that method for why the window is
   *       the wrong authority.
   *   <li><b>{@code storeBacked: false}</b> on a synthesized row — a conditional key, like
   *       {@code title} / {@code parentSessionId} below, so absence keeps meaning what it always did.
   *       Every per-row action this list's consumers offer except discard (rename, branch,
   *       context-floor, compact, exclude) writes to a {@code ConversationStore} session; the row says
   *       so instead of offering an action that would 404. The conversation id is REAL — only the
   *       capability claim is corrected. In {@code modules/ui-web} this flag gates RENAME only (the
   *       other four are gated on message ids), so a stamped delegate conversation gains rename here
   *       and gains the id-gated acts by having real store message ids (863 §2 P1, §4.A.5 A-6).
   *   <li><b>No {@code messageCount} on a synthesized row</b> — honestly absent. Deriving one means
   *       running the {@code /api/thread} projection per row per request, turning a sidebar refresh
   *       into O(runs x events) and defeating the run store's lazy limit. Absent = not told.
   * </ul>
   *
   * <p>{@link #handleLoadHistory} deliberately does NOT join: Search v3's transcript already comes
   * from {@code GET /api/thread/{id}}, and a second join here would give one transcript two sources.
   */
  public void handleListSessions(Context ctx) {
    String shapeId = ctx.queryParam("shapeId");
    String limitParam = ctx.queryParam("limit");
    int limit = Math.min(limitParam != null ? Integer.parseInt(limitParam) : 20, 100);
    List<ConversationStore.SessionSummary> sessions =
        conversationStore.listSessions(shapeId, limit);
    List<Map<String, Object>> storeRows = sessions.stream().map(s -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("sessionId", s.sessionId());
      m.put("shapeId", s.shapeId());
      m.put("createdAtMs", s.createdAtMs());
      m.put("lastActiveAtMs", s.lastActiveAtMs());
      m.put("messageCount", s.messageCount());
      m.put("firstUserMessage", s.firstUserMessage());
      // Slice 513 — branching: expose parent pointers when present.
      if (s.parentSessionId() != null) m.put("parentSessionId", s.parentSessionId());
      if (s.branchPointMessageId() != null) {
        m.put("branchPointMessageId", s.branchPointMessageId());
      }
      // Tempdoc 838 — the conversation's durable name, and who chose it. Conditional like the parent
      // pointers: absence means "no title", and a sealed title reads absent while the store is locked
      // (the row still lists, with the FE's placeholder — the same answer firstUserMessage gives).
      if (s.title() != null && !s.title().isEmpty()) m.put("title", s.title());
      if (s.titleSource() != null && !s.titleSource().isEmpty()) {
        m.put("titleSource", s.titleSource());
      }
      return m;
    }).toList();
    List<Map<String, Object>> result = new ArrayList<>(storeRows);
    result.addAll(runBackedRows(shapeId, limit, storeRows));
    // One order for both records. The store side already sorts by last activity, so a STABLE sort on
    // the same key leaves it untouched and only decides where the synthesized rows land.
    result.sort((a, b) -> Long.compare(asMillis(b.get("lastActiveAtMs")), asMillis(a.get("lastActiveAtMs"))));
    ctx.json(Map.of("sessions", result.size() <= limit ? result : result.subList(0, limit)));
  }

  /**
   * The run-backed half of {@link #handleListSessions}: conversations that live ONLY in the agent-run
   * record. Empty when {@code shapeId} names a non-agent shape, when the agent capability is
   * unavailable, and when its store is sealed + locked (which lists nothing rather than failing).
   */
  private List<Map<String, Object>> runBackedRows(
      String shapeId, int limit, List<Map<String, Object>> storeRows) {
    String agentShapeId = AgentRunShape.ID.value();
    if (shapeId != null && !shapeId.isBlank() && !agentShapeId.equals(shapeId)) {
      return List.of();
    }
    AgentService agent = agentService.get();
    if (agent == null) {
      return List.of();
    }
    java.util.Set<Object> inThisWindow = new java.util.HashSet<>();
    for (Map<String, Object> row : storeRows) {
      inThisWindow.add(row.get("sessionId"));
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map<String, Object> run : agent.conversationSummaries(limit)) {
      Object conversationId = run.get("conversationId");
      if (!(conversationId instanceof String id) || id.isBlank() || hasStoreSession(id, inThisWindow)) {
        continue;
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("sessionId", id);
      m.put("shapeId", agentShapeId);
      m.put("createdAtMs", asMillis(run.get("createdAtMs")));
      m.put("lastActiveAtMs", asMillis(run.get("lastActiveAtMs")));
      // No messageCount: see the contract on handleListSessions. Absent = not told.
      m.put("firstUserMessage", run.get("firstUserMessage") instanceof String p ? p : "");
      m.put("storeBacked", false);
      rows.add(m);
    }
    return rows;
  }

  /**
   * Does a {@code ConversationStore} session exist for {@code conversationId}? Asked of the STORE,
   * not of the window {@link #handleListSessions} just fetched.
   *
   * <p>The window is the wrong authority and the difference is a real defect: a MIXED conversation —
   * chat turns plus a delegate run — has a store row whose {@code lastActiveAtMs} froze at its last
   * chat turn, so once the store holds more than {@code limit} conversations it can fall outside the
   * window while its runs are the freshest thing on disk. Deduplicating against the window would then
   * miss it, re-synthesize it as {@code storeBacked:false} carrying the RUN's timestamp — which sorts
   * it to the top and past the re-limit — and the FE's known-row adoption would downgrade an open,
   * renameable conversation to one that offers no rename. One direct lookup per candidate (at most
   * {@code limit} of them) is what makes "listed once, as its store row" true for every conversation
   * rather than only for the ones the window happened to include.
   *
   * <p>While the conversation store is sealed + locked this lookup RAISES rather than answering
   * ({@code FileConversationStore.getSessionMeta} propagates {@code KeyLockedException} — the
   * documented asymmetry with {@code listSessions}, which must keep listing). The list must not 423,
   * so a locked store falls back to the window: renaming is impossible while locked anyway, and a
   * degraded dedup is the honest answer when the store will not say what it holds.
   */
  private boolean hasStoreSession(String conversationId, java.util.Set<Object> inThisWindow) {
    if (inThisWindow.contains(conversationId)) {
      return true;
    }
    try {
      return conversationStore.getSessionMeta(conversationId).isPresent();
    } catch (io.justsearch.agent.api.encryption.KeyLockedException locked) {
      return false;
    }
  }

  private static long asMillis(Object value) {
    return value instanceof Number n ? n.longValue() : 0L;
  }

  /**
   * {@code GET /api/chat/conversations/{sessionId}/history} — the store's messages, and deliberately
   * ONLY the store's (tempdoc 859 slice C PR-2).
   *
   * <p>This endpoint does NOT perform the two-store join {@link #handleListSessions} does. The
   * unified transcript already has one authority — {@code GET /api/thread/{id}}, which merges the
   * answer plane and the action plane — and joining the agent runs in here as well would give one
   * transcript two sources that must then be kept in agreement.
   *
   * <p>Narrowed by tempdoc 863 §4.A.5 A-5. This javadoc used to add that "a run-backed conversation
   * therefore answers here with an empty message list, which is the true thing: it has no store
   * messages." That is now true only of a conversation that is RUN-ONLY: a standalone or background
   * run, or a delegate conversation created before the {@code recordsToThread} stamp. Since 863 the
   * engine appends a stamped delegate run's user turn and assistant turn to the conversation record
   * as it dispatches, so this endpoint returns them — which is what ends the legacy window's empty
   * transcript for a delegate conversation. No backfill exists, deliberately: minting store rows for
   * messages that were never store messages would invent addressable fork points that never existed.
   */
  public void handleLoadHistory(Context ctx) {
    String sessionId = ctx.pathParam("sessionId");
    List<Map<String, Object>> messages = conversationStore.loadHistory(sessionId);
    // Slice 513 + 515 FIX-7 — include parent pointers in the response so the
    // FE can mark inherited messages with a branch indicator. Direct
    // getSessionMeta lookup replaces the O(N) listSessions+filter scan.
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("messages", messages);
    Optional<ConversationStore.SessionSummary> meta =
        conversationStore.getSessionMeta(sessionId);
    meta.ifPresent(s -> {
      if (s.parentSessionId() != null) {
        response.put("parentSessionId", s.parentSessionId());
        // Slice 515 FIX-8 — surface the parent's first-message preview so the
        // FE branch indicator can name the parent without a second roundtrip.
        conversationStore.getSessionMeta(s.parentSessionId()).ifPresent(parent -> {
          if (parent.firstUserMessage() != null && !parent.firstUserMessage().isEmpty()) {
            response.put("parentFirstUserMessage", parent.firstUserMessage());
          }
        });
      }
      if (s.branchPointMessageId() != null) {
        response.put("branchPointMessageId", s.branchPointMessageId());
      }
      // Tempdoc 610 Phase C — surface the effective-context floor so the FE can
      // render the floor divider + out-of-context band on reload. Additive,
      // mirrors the parent-pointer fields above.
      if (s.contextFloor() != null) {
        response.put("contextFloor", s.contextFloor());
      }
      // Tempdoc 610 Phase D — surface the compaction summary so the divider can
      // offer "Show summary" (the trust requirement: the user sees what the
      // assistant now sees).
      if (s.contextFloorSummary() != null) {
        response.put("contextFloorSummary", s.contextFloorSummary());
      }
    });
    // Tempdoc 610 §E.3 — surface the per-message excluded set so the FE renders the toggle state +
    // out-of-context treatment on reload (additive, like the floor fields above).
    List<String> excluded = conversationStore.excludedMessageIds(sessionId);
    if (!excluded.isEmpty()) {
      response.put("excludedMessageIds", excluded);
    }
    // Tempdoc 610 §J.3 — surface the per-source excluded set so the FE renders the hidden-source
    // toggle state + dim treatment on reload (additive, like the per-message set above).
    List<String> excludedSources = conversationStore.excludedSourceIds(sessionId);
    if (!excludedSources.isEmpty()) {
      response.put("excludedSourceIds", excludedSources);
    }
    ctx.json(response);
  }

  /**
   * Tempdoc 610 Phase C — POST /api/chat/conversations/{sessionId}/context-floor
   * with body {@code {"floorMessageId": "..."}}. Sets the session's
   * effective-context floor (the next turn's prompt starts here; the transcript
   * still displays everything above it).
   */
  public void handleSetContextFloor(Context ctx) {
    String sessionId = ctx.pathParam("sessionId");
    String floorMessageId = null;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = ctx.bodyAsClass(Map.class);
      Object f = body == null ? null : body.get("floorMessageId");
      if (f instanceof String s) floorMessageId = s;
    } catch (RuntimeException ignored) {
      // empty/invalid body → treated as a clear (floorMessageId stays null).
    }
    if (floorMessageId == null || floorMessageId.isBlank()) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", "Missing required field: floorMessageId");
      err.put("errorCode", ApiErrorCode.INVALID_REQUEST.name());
      ctx.status(400).json(err);
      return;
    }
    conversationStore.setContextFloor(sessionId, floorMessageId);
    ctx.json(Map.of("ok", true, "contextFloor", floorMessageId));
  }

  /**
   * Tempdoc 610 Phase C — DELETE /api/chat/conversations/{sessionId}/context-floor.
   * Clears the floor (restores full context).
   */
  public void handleClearContextFloor(Context ctx) {
    String sessionId = ctx.pathParam("sessionId");
    conversationStore.setContextFloor(sessionId, null);
    ctx.json(Map.of("ok", true));
  }

  /**
   * Tempdoc 838 — POST /api/chat/conversations/{sessionId}/title with body
   * {@code {"title": "...", "source": "user"|"auto"}}. Names the conversation durably: the title
   * becomes a sealed field of the session's meta.json, so it survives a cleared site-data, a second
   * client and a profile change, and it stops sitting in browser plaintext outside the encryption
   * boundary while the conversation it names is sealed.
   *
   * <p>POST-to-set / DELETE-to-clear is the conversation family's house style (context-floor,
   * compact, exclude), so a blank title here is a 400 rather than a second spelling of "clear".
   * A locked store answers 423 through the global {@code KeyLockedException} mapping — the store's
   * own read raises it, so there is no lock arm in this method.
   */
  public void handleSetTitle(Context ctx) {
    String sessionId = ctx.pathParam("sessionId");
    String title = null;
    String source = ConversationStore.TITLE_SOURCE_USER;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = ctx.bodyAsClass(Map.class);
      if (body != null) {
        Object t = body.get("title");
        if (t instanceof String s) title = s;
        Object src = body.get("source");
        if (src instanceof String s) source = s;
      }
    } catch (RuntimeException ignored) {
      // fall through to the 400 below
    }
    if (title == null || title.isBlank()) {
      badRequest(ctx, "Missing required field: title");
      return;
    }
    if (!ConversationStore.TITLE_SOURCE_USER.equals(source)
        && !ConversationStore.TITLE_SOURCE_AUTO.equals(source)) {
      badRequest(ctx, "Unknown title source: " + source);
      return;
    }
    // A rename of a conversation that does not exist must not MINT one — a titled zero-message
    // session is a list row that names nothing. The FE's own retry covers the sub-second race
    // between opening a conversation and its first message landing.
    if (conversationStore.getSessionMeta(sessionId).isEmpty()) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", "No such conversation: " + sessionId);
      err.put("errorCode", ApiErrorCode.NOT_FOUND.name());
      ctx.status(404).json(err);
      return;
    }
    conversationStore.setTitle(sessionId, title, source);
    // Echo what was actually STORED, not what was sent: the store caps the name at 200 characters,
    // and a response that repeated an over-long input would be telling the client something untrue.
    String stored =
        conversationStore
            .getSessionMeta(sessionId)
            .map(ConversationStore.SessionSummary::title)
            .orElse(title.trim());
    ctx.json(Map.of("ok", true, "title", stored, "titleSource", source));
  }

  /**
   * Tempdoc 838 — DELETE /api/chat/conversations/{sessionId}/title. Removes the name (the row falls
   * back to its opening message, then to the FE placeholder). Idempotent: clearing the name of a
   * conversation that has none, or of one that does not exist, is a no-op success.
   */
  public void handleClearTitle(Context ctx) {
    conversationStore.setTitle(ctx.pathParam("sessionId"), null, null);
    ctx.json(Map.of("ok", true));
  }

  private static void badRequest(Context ctx, String message) {
    Map<String, Object> err = new LinkedHashMap<>();
    err.put("error", message);
    err.put("errorCode", ApiErrorCode.INVALID_REQUEST.name());
    ctx.status(400).json(err);
  }

  /**
   * Tempdoc 610 Phase D — POST /api/chat/conversations/{sessionId}/compact with
   * body {@code {"floorMessageId": "..."}}. Summarizes the messages ABOVE the
   * floor (one-shot via OnlineAiService.summarize) and attaches the summary to
   * the floor, so the next turn sees "[summary] + recent turns" while the
   * transcript still displays everything. Returns the generated summary.
   */
  public void handleCompact(Context ctx) {
    String sessionId = ctx.pathParam("sessionId");
    String floorMessageId = null;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = ctx.bodyAsClass(Map.class);
      Object f = body == null ? null : body.get("floorMessageId");
      if (f instanceof String s) floorMessageId = s;
    } catch (RuntimeException ignored) {
      // fall through to the 400 below
    }
    if (floorMessageId == null || floorMessageId.isBlank()) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", "Missing required field: floorMessageId");
      err.put("errorCode", ApiErrorCode.INVALID_REQUEST.name());
      ctx.status(400).json(err);
      return;
    }
    // The prefix to summarize = the resolved history up to (not including) the
    // floor. If the floor is the first message there is nothing to compact.
    List<Map<String, Object>> full = conversationStore.loadHistory(sessionId);
    List<Map<String, Object>> prefix = new ArrayList<>();
    for (Map<String, Object> m : full) {
      if (floorMessageId.equals(m.get("id"))) break;
      prefix.add(m);
    }
    if (prefix.isEmpty()) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", "Nothing to compact before the chosen message");
      err.put("errorCode", ApiErrorCode.INVALID_REQUEST.name());
      ctx.status(400).json(err);
      return;
    }
    StringBuilder transcript = new StringBuilder();
    for (Map<String, Object> m : prefix) {
      transcript
          .append(String.valueOf(m.getOrDefault("role", "user")))
          .append(": ")
          .append(String.valueOf(m.getOrDefault("content", "")))
          .append("\n");
    }
    String summary;
    try {
      summary = onlineAi.get().summarize(transcript.toString(),
          OnlineAiService.DEFAULT_SUMMARY_TOKENS, RequestEngineContext.get(ctx)).get(60, TimeUnit.SECONDS);
    } catch (Exception e) {
      if (ApiErrorHandler.writeExecutorRefusal(ctx, e, null)) return;
      LOG.warn("Compaction summarize failed for {}", sessionId, e);
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", "Summarization unavailable");
      err.put("errorCode", ApiErrorCode.SERVICE_UNAVAILABLE.name());
      ctx.status(503).json(err);
      return;
    }
    if (summary == null || summary.isBlank()) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", "Summarization produced no output");
      err.put("errorCode", ApiErrorCode.SERVICE_UNAVAILABLE.name());
      ctx.status(503).json(err);
      return;
    }
    conversationStore.compactContext(sessionId, floorMessageId, summary);
    ctx.json(Map.of("ok", true, "contextFloor", floorMessageId, "contextFloorSummary", summary));
  }

  /**
   * Tempdoc 610 §E.2 — POST /api/chat/conversations/{sessionId}/context-floor/summary with body
   * {@code {"summaryText": "..."}}. Replaces the stored compaction summary IN PLACE so the user can
   * correct what the summarizer got wrong (the §E.1 "no write barriers" answer). The floor is
   * unchanged and there is NO re-summarization — it reuses {@code compactContext} with the session's
   * current floor. 400 if the conversation has no compaction summary to edit.
   */
  public void handleEditContextFloorSummary(Context ctx) {
    String sessionId = ctx.pathParam("sessionId");
    String summaryText = null;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = ctx.bodyAsClass(Map.class);
      Object s = body == null ? null : body.get("summaryText");
      if (s instanceof String str) summaryText = str;
    } catch (RuntimeException ignored) {
      // fall through to the 400 below
    }
    if (summaryText == null || summaryText.isBlank()) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", "Missing required field: summaryText");
      err.put("errorCode", ApiErrorCode.INVALID_REQUEST.name());
      ctx.status(400).json(err);
      return;
    }
    Optional<ConversationStore.SessionSummary> meta = conversationStore.getSessionMeta(sessionId);
    String floorId = meta.map(ConversationStore.SessionSummary::contextFloor).orElse(null);
    String existing = meta.map(ConversationStore.SessionSummary::contextFloorSummary).orElse(null);
    if (floorId == null || existing == null) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", "No compaction summary to edit for this conversation");
      err.put("errorCode", ApiErrorCode.INVALID_REQUEST.name());
      ctx.status(400).json(err);
      return;
    }
    conversationStore.compactContext(sessionId, floorId, summaryText);
    ctx.json(Map.of("ok", true, "contextFloor", floorId, "contextFloorSummary", summaryText));
  }

  /**
   * Tempdoc 610 §E.3 — POST /api/chat/conversations/{sessionId}/messages/{messageId}/exclude with
   * body {@code {"excluded": true|false}}. Toggles whether a single message is dropped from the
   * effective context. The transcript still displays it (loadHistory is unaffected); only
   * loadEffectiveContext filters it.
   */
  public void handleToggleMessageExcluded(Context ctx) {
    String sessionId = ctx.pathParam("sessionId");
    String messageId = ctx.pathParam("messageId");
    boolean excluded = true;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = ctx.bodyAsClass(Map.class);
      Object e = body == null ? null : body.get("excluded");
      if (e instanceof Boolean b) excluded = b;
    } catch (RuntimeException ignored) {
      // default to excluded=true
    }
    conversationStore.excludeMessage(sessionId, messageId, excluded);
    ctx.json(Map.of("ok", true, "messageId", messageId, "excluded", excluded));
  }

  /**
   * Tempdoc 610 §J.3 — POST /api/chat/conversations/{sessionId}/sources/exclude with body
   * {@code {"sourceId": "...", "excluded": true|false}}. The sourceId (a unit-separator-joined
   * parentDocId+chunkIndex id with slashes/colons) rides in the body, not the path. Toggles whether
   * a retrieved source is hidden from this conversation's retrieval — the Worker drops the matching
   * chunk pre-search on subsequent turns; past transcript turns are unaffected.
   */
  public void handleToggleSourceExcluded(Context ctx) {
    String sessionId = ctx.pathParam("sessionId");
    String sourceId = "";
    boolean excluded = true;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = ctx.bodyAsClass(Map.class);
      if (body != null) {
        Object s = body.get("sourceId");
        if (s instanceof String str) sourceId = str;
        Object e = body.get("excluded");
        if (e instanceof Boolean b) excluded = b;
      }
    } catch (RuntimeException ignored) {
      // default to excluded=true / empty sourceId (no-op below)
    }
    if (sourceId.isBlank()) {
      ctx.status(400).json(Map.of("ok", false, "error", "sourceId required"));
      return;
    }
    conversationStore.excludeSource(sessionId, sourceId, excluded);
    ctx.json(Map.of("ok", true, "sourceId", sourceId, "excluded", excluded));
  }

  /**
   * {@code DELETE /api/chat/conversations/{sessionId}} — deletes the conversation from BOTH records
   * it can live in (tempdoc 859 slice C PR-2).
   *
   * <p>The store session goes as it always did; the conversation's agent runs go with it. Without the
   * second half a run-backed row would be listable (the join above) and undeletable, so the sidebar
   * would only ever grow — the completing capability for the row's action set, not a shim: the run
   * store owns those directories.
   *
   * <p>Order matters: the store deletion runs FIRST, so a branches-prevent-deletion refusal aborts
   * before any run is destroyed. Runs are deleted for a conversation with no store session at all,
   * which is exactly the synthesized-row case.
   */
  public void handleDeleteConversation(Context ctx) {
    String sessionId = ctx.pathParam("sessionId");
    try {
      conversationStore.deleteSession(sessionId);
      AgentService agent = agentService.get();
      int runsDeleted = agent == null ? 0 : agent.deleteConversationRuns(sessionId);
      ctx.json(Map.of("ok", true, "agentRunsDeleted", runsDeleted));
    } catch (BranchesPreventDeletionException blocked) {
      // Slice 515 FIX-3 — 409 Conflict with the child session ids so the
      // FE can offer a cascade-delete UX (out of scope here).
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", blocked.getMessage());
      err.put("errorCode", "BRANCHES_PREVENT_DELETION");
      err.put("childSessionIds", blocked.childSessionIds());
      ctx.status(409).json(err);
    }
  }

  /**
   * Slice 513 — POST /api/chat/conversations/{sessionId}/branch?fromMsgId=...
   *
   * <p>Creates a new session that branches from {@code sessionId} at
   * {@code fromMsgId}. The new session carries no messages of its own; the
   * store's loadHistory resolves the parent prefix on each call. Returns the
   * new sessionId for the FE to navigate into.
   */
  public void handleBranchConversation(Context ctx) {
    String parentSessionId = ctx.pathParam("sessionId");
    String fromMsgId = ctx.queryParam("fromMsgId");
    if (fromMsgId == null || fromMsgId.isBlank()) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", "Missing required query parameter: fromMsgId");
      err.put("errorCode", ApiErrorCode.INVALID_REQUEST.name());
      ctx.status(400).json(err);
      return;
    }
    String newSessionId = "uc-" + UUID.randomUUID();
    try {
      conversationStore.branchFrom(parentSessionId, fromMsgId, newSessionId);
    } catch (IllegalArgumentException invalid) {
      // Slice 515 FIX-2 — non-existent parent or branch-point id → 400 with
      // structured error. The store no longer silently falls back to a
      // full-parent prefix walk.
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("error", invalid.getMessage());
      err.put("errorCode", ApiErrorCode.INVALID_REQUEST.name());
      ctx.status(400).json(err);
      return;
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sessionId", newSessionId);
    result.put("parentSessionId", parentSessionId);
    result.put("branchPointMessageId", fromMsgId);
    ctx.json(result);
  }

  public static Audience readAudience(Context ctx) {
    String raw = ctx.header(AUDIENCE_HEADER);
    if (raw == null || raw.isBlank()) {
      return Audience.USER;
    }
    try {
      return Audience.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      LOG.debug("Unrecognized {} header value {}; defaulting to USER", AUDIENCE_HEADER, raw);
      return Audience.USER;
    }
  }
}
