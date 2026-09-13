/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.agent.api.AgentRunQueries;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.agent.AgentBatchSummary;
import io.justsearch.app.api.agent.AgentHistoryResponse;
import io.justsearch.app.api.agent.AgentSessionSummary;
import io.justsearch.app.api.agent.AgentSessionsResponse;
import io.justsearch.app.api.run.LiveRunSummary;
import io.justsearch.app.api.run.LiveRunsResponse;
import io.justsearch.app.api.run.ParkSummary;
import io.justsearch.app.api.run.RunStateSnapshotView;
import io.justsearch.app.observability.stream.run.RunChannel;
import io.justsearch.app.observability.stream.run.RunChannelRegistry;
import io.justsearch.app.observability.stream.run.RunStateSnapshot;
import io.justsearch.telemetry.Telemetry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 585 §B.5 (Hybrid C, the read-axis cut): the agent capability's READ-TIME session/history
 * endpoints, lifted out of {@link AgentController}. These 8 handlers (session snapshots/lists,
 * persisted events, the transcript bundle, operation history/detail, undo) are **pure reads** — each
 * calls exactly one method of the narrow {@link AgentRunQueries} surface that tempdoc 584 segregated
 * out of {@code AgentService}.
 *
 * <p>This controller depends on {@code Supplier<AgentRunQueries>} — **not** the full loop+control
 * {@code AgentService} — realizing on the controller side the consumer-narrowing 584 set up on the
 * service side (and could not apply to {@code InteractionThreadController}, §584 B.3). A new
 * read-projection endpoint attaches here, on the read interface, never on the orchestrator-coupled
 * core. The streaming resume endpoints and {@code cancelSession} are deliberately NOT here — they
 * need {@code isAvailable()} / the control surface / the SSE writer, so they stay on the run/control
 * core (§B.3 boundary wrinkle).
 *
 * <p>Behaviour-preserving: the handler bodies moved verbatim (the late-bound supplier resolves the
 * live agent the same way the parent did).
 */
final class AgentSessionController {
  private static final Logger LOG = LoggerFactory.getLogger(AgentSessionController.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Supplier<AgentRunQueries> queriesSupplier;
  private final Telemetry telemetry;
  private final RunChannelRegistry runs;

  AgentSessionController(Supplier<AgentRunQueries> queriesSupplier, Telemetry telemetry) {
    this(queriesSupplier, telemetry, null);
  }

  /**
   * @param runs the ONE run-channel registry (tempdoc 834 §5.1). A read of what is executing right
   *     now sits on the read axis beside the reads of what has already executed — the two compose
   *     into the recovery view, since a run is in exactly one of them. Null-tolerant so the
   *     persisted-session handlers stay constructible without the run substrate.
   */
  AgentSessionController(
      Supplier<AgentRunQueries> queriesSupplier, Telemetry telemetry, RunChannelRegistry runs) {
    this.queriesSupplier = queriesSupplier;
    this.telemetry = telemetry;
    this.runs = runs;
  }

  /** Resolves the live agent read surface. Always re-fetches so late-bound updates surface. */
  private AgentRunQueries queries() {
    return queriesSupplier.get();
  }

  /** GET /api/chat/sessions/last - Return last persisted agent session snapshot. */
  void handleSessionLast(Context ctx) {
    var snapshot = queries().lastSessionSnapshot();
    if (snapshot == null || snapshot.isEmpty()) {
      ctx.status(404).json(ApiErrorHandler.toResponse(ApiErrorCode.NOT_FOUND, "No persisted agent session found", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }
    ctx.json(snapshot);
  }

  /** GET /api/chat/sessions/{id}/events - Replay persisted session events for debug. */
  void handleSessionEvents(Context ctx) {
    String sessionId = ctx.pathParam("id");
    List<Map<String, Object>> events = queries().sessionEvents(sessionId);
    if (events == null || events.isEmpty()) {
      ctx.status(404).json(ApiErrorHandler.toResponse(ApiErrorCode.NOT_FOUND, "No events found for session " + sessionId, telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }
    ctx.json(Map.of("sessionId", sessionId, "events", events));
  }

  /**
   * GET /api/chat/sessions - List recent persisted agent sessions (newest first).
   * Tempdoc 415 follow-up (C20).
   */
  void handleListSessions(Context ctx) {
    String limitParam = ctx.queryParam("limit");
    int limit = 20;
    if (limitParam != null) {
      try {
        limit = Integer.parseInt(limitParam);
      } catch (NumberFormatException ignored) {
        // keep default
      }
    }
    limit = Math.max(1, Math.min(limit, 100));
    // Tempdoc 564 Phase 3: project the agent layer's untyped Maps to the typed wire record at the
    // controller boundary (app-api can't depend back on app-agent-api). Jackson converts each Map to
    // the record by component name — identical JSON, now schema-generated + FE-validated.
    List<AgentSessionSummary> sessions =
        queries().listSessions(limit).stream()
            .map(m -> MAPPER.convertValue(m, AgentSessionSummary.class))
            .toList();
    ctx.json(new AgentSessionsResponse(sessions));
  }

  /**
   * GET /api/chat/runs/live - every run executing RIGHT NOW (tempdoc 834 §5.1).
   *
   * <p>The FE's run-discovery authority, replacing the {@code localStorage} pointer the shell used
   * to reattach from (§15.3): a backend enumeration cannot go stale the way a pointer can, and it
   * sees runs this browser never started.
   *
   * <p>Optional {@code conversationId} / {@code shapeId} filters. The result is a LIST and is never
   * collapsed by conversation (§3.5) — nothing serializes two dispatches on one {@code
   * conversationId}, so N &gt; 1 rows for one conversation is a legitimate answer, and turn-taking is
   * the FE composer's decision to make rather than the substrate's to hide.
   *
   * <p>Interruption is deliberately absent here: an interrupted run is a PERSISTED run, so it
   * surfaces on {@link #handleListSessions} via {@code AgentSessionSummary.interruptedAt} (§5.3).
   * The two reads compose — a run appears in exactly one of them.
   *
   * <p>Unlike every other GET on this controller, this route demands the session token — see {@link
   * ApiSecurityFilters#requiresSessionToken}.
   */
  void handleListLiveRuns(Context ctx) {
    if (runs == null) {
      ctx.json(new LiveRunsResponse(List.of()));
      return;
    }
    String conversationId = ctx.queryParam("conversationId");
    String shapeId = ctx.queryParam("shapeId");
    List<LiveRunSummary> live =
        runs.live().stream()
            .filter(run -> matches(conversationId, run.descriptor().conversationId()))
            .filter(run -> matches(shapeId, run.descriptor().shapeId()))
            .map(AgentSessionController::toSummary)
            .toList();
    ctx.json(new LiveRunsResponse(live));
  }

  /** An absent or blank filter matches everything; a present one matches exactly. */
  private static boolean matches(String filter, String actual) {
    return filter == null || filter.isBlank() || filter.equals(actual);
  }

  /**
   * Projects one channel onto the wire record. Tempdoc 564 Phase 3's rule, applied to the run axis:
   * the substrate's already-projected field map converts to the typed record by component name at
   * the controller boundary, so the FE validates a generated schema instead of fail-open hand-Zod.
   */
  private static LiveRunSummary toSummary(RunChannel run) {
    // ORDER IS LOAD-BEARING. `park()` is refreshed as a side effect of taking the snapshot — that is
    // deliberate one layer down (taking the snapshot is the one moment fresh session state is in
    // hand, and two setters for one fact is how they drift), and it is recorded as §16.6 residual 2.
    // Reading park() first would therefore report the value from the PREVIOUS take: on the first
    // enumeration of a parked run, none at all — so a parked run would be published as `running`.
    RunStateSnapshotView snapshot = snapshotView(run);
    ParkSummary park =
        run.park()
            .map(p -> new ParkSummary(p.kind().wire(), p.sinceEpochMs(), p.detail()))
            .orElse(null);
    return new LiveRunSummary(
        run.id().value(),
        run.descriptor().shapeId(),
        run.descriptor().conversationId(),
        park == null ? LiveRunSummary.STATE_RUNNING : LiveRunSummary.STATE_PARKED,
        park,
        run.descriptor().startedAtEpochMs(),
        run.updatedAtEpochMs(),
        run.observerCount(),
        snapshot);
  }

  /**
   * The primer, or null when this run has none or its shape has drifted from the view record.
   *
   * <p>Degrading rather than throwing is the same posture {@code SteppedRunChannelImpl.snapshot()}
   * already takes one layer down, and for the same reason: this endpoint is the path a user takes to
   * RECOVER a run they can no longer see, so a projection mismatch must cost that run its primer,
   * never the whole enumeration. The projection-fidelity test is what turns a drift into a loud
   * failure at build time instead.
   */
  private static RunStateSnapshotView snapshotView(RunChannel run) {
    Optional<RunStateSnapshot> snapshot = run.snapshot();
    if (snapshot.isEmpty()) {
      return null;
    }
    try {
      return MAPPER.convertValue(snapshot.get().fields(), RunStateSnapshotView.class);
    } catch (RuntimeException drifted) {
      LOG.warn(
          "Run {} carries a state snapshot that does not project onto the wire record: {}",
          run.id().value(),
          drifted.toString());
      return null;
    }
  }

  /**
   * GET /api/chat/sessions/{id} - Full persisted snapshot for a specific session.
   * Tempdoc 415 follow-up (C20).
   */
  void handleSessionDetail(Context ctx) {
    String sessionId = ctx.pathParam("id");
    var snapshot = queries().sessionSnapshot(sessionId);
    if (snapshot == null || snapshot.isEmpty()) {
      ctx.status(404)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.NOT_FOUND,
                  "Session not found: " + sessionId,
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
      return;
    }
    ctx.json(snapshot);
  }

  /**
   * GET /api/chat/sessions/{id}/transcript - Bundled meta + events for download.
   * Tempdoc 415 follow-up (C33). {@code Content-Disposition: attachment} triggers a browser
   * download instead of inline render.
   */
  void handleSessionTranscript(Context ctx) {
    String sessionId = ctx.pathParam("id");
    var meta = queries().sessionSnapshot(sessionId);
    if (meta == null || meta.isEmpty()) {
      ctx.status(404)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.NOT_FOUND,
                  "Session not found: " + sessionId,
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
      return;
    }
    var events = queries().sessionEvents(sessionId);
    ctx.header(
        "Content-Disposition", "attachment; filename=\"agent-session-" + sessionId + ".json\"");
    ctx.json(Map.of("meta", meta, "events", events != null ? events : List.of()));
  }

  /** POST /api/chat/agent/undo — Undo a previous tool execution. */
  void handleUndo(Context ctx) {
    try {
      var body = MAPPER.readTree(ctx.body());
      String toolName = body.path("toolName").asText();
      String executionId = body.path("executionId").asText();
      if (toolName.isEmpty() || executionId.isEmpty()) {
        ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, "toolName and executionId are required", telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }
      var result = queries().undoOperation(toolName, executionId, RequestEngineContext.agent(ctx));
      ctx.json(Map.of(
          "success", result.success(),
          "output", result.message(),
          "executionId", result.executionId().orElse("")));
    } catch (io.justsearch.agent.api.registry.ConfirmationRequiredException e) {
      // Tempdoc 875 §C.7: the reversal now meets the same lattice its forward form did. This
      // surface carries no confirmation token, so a gated undo is a typed 428 the caller can
      // act on — not an opaque 500.
      ctx.status(428).json(ApiErrorHandler.toResponse(ApiErrorCode.CONFIRMATION_REQUIRED,
          e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (io.justsearch.agent.api.registry.TrustGateDeniedException e) {
      ctx.status(403).json(ApiErrorHandler.toResponse(ApiErrorCode.TRUST_DENIED,
          e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      LOG.error("Undo failed", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(ApiErrorCode.INTERNAL_ERROR, e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /** GET /api/chat/agent/history — List recent operation batches. */
  void handleHistory(Context ctx) {
    String limitParam = ctx.queryParam("limit");
    int limit = 20;
    if (limitParam != null) {
      try {
        limit = Integer.parseInt(limitParam);
      } catch (NumberFormatException ignored) {
        // keep default
      }
    }
    limit = Math.max(1, Math.min(limit, 100));
    // Tempdoc 564 Phase 3: project the untyped batch Maps to the typed wire record (see handleListSessions).
    List<AgentBatchSummary> batches =
        queries().operationHistory(limit).stream()
            .map(m -> MAPPER.convertValue(m, AgentBatchSummary.class))
            .toList();
    ctx.json(new AgentHistoryResponse(batches));
  }

  /** GET /api/chat/agent/history/{batchId} — Get full batch detail. */
  void handleHistoryDetail(Context ctx) {
    String batchId = ctx.pathParam("batchId");
    var detail = queries().operationDetail(batchId);
    if (detail == null) {
      ctx.status(404).json(ApiErrorHandler.toResponse(ApiErrorCode.NOT_FOUND, "Batch not found: " + batchId, telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }
    ctx.json(detail);
  }
}
