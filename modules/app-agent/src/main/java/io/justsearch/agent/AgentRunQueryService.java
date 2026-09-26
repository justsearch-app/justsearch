/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.AgentErrorClass;
import io.justsearch.agent.api.AgentErrorCode;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentProfile;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.RetryAction;
import io.justsearch.agent.api.interaction.InteractionEvent;
import io.justsearch.agent.api.interaction.InteractionEventKind;
import io.justsearch.agent.api.interaction.ThreadProjection;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.tools.FileOperationLog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Tempdoc 584 §B.4 — the agent's READ-TIME query/projection surface, extracted from
 * {@code AgentLoopService} (the single largest non-loop cluster, ~25% of the pre-584 file). Every
 * method here is a pure read over {@code AgentRunStore} / {@code OperationCatalog} /
 * {@code FileOperationLog} (durable-record projections, operation history, the unified-thread and
 * lifecycle/presence projections) plus the resume bridge — none touch live agent-loop state.
 *
 * <p>This is the §B.2 *breadth-axis* cut: the read surface is exactly what regrew across 415 / 561
 * (session history, threadEvents/lifecycles/presence). Relocating it here means a new read-projection
 * feature attaches to this collaborator (and, via the {@code AgentRunQueries} interface it backs, to
 * the {@code InteractionThreadController} consumer) instead of growing the orchestrator.
 *
 * <p>The two cross-concern callbacks — {@link AgentStepRunner.ErrorEmitter} (reused so the resume
 * paths emit the exact same typed error + telemetry as the loop) and {@link SessionRunner} (the
 * loop's {@code runAgent}, so a resumed snapshot re-enters the live loop) — keep this class free of a
 * back-reference to {@code AgentLoopService}.
 */
final class AgentRunQueryService implements io.justsearch.agent.api.AgentRunQueries {

  /** The loop entry point a resumed snapshot re-enters (bound to {@code AgentLoopService::runAgent}). */
  @FunctionalInterface
  interface SessionRunner {
    void run(AgentRequest request, Consumer<AgentEvent> eventConsumer, EngineContext engineContext);
  }

  private final AgentRunStore runStore;
  private final OperationCatalog operationCatalog;
  private final OperationDispatcher operationExecutor;
  private final FileOperationLog fileOperationLog; // nullable
  private final AgentStepRunner.ErrorEmitter errorEmitter;
  private final SessionRunner sessionRunner;

  AgentRunQueryService(
      AgentRunStore runStore,
      OperationCatalog operationCatalog,
      OperationDispatcher operationExecutor,
      FileOperationLog fileOperationLog,
      AgentStepRunner.ErrorEmitter errorEmitter,
      SessionRunner sessionRunner) {
    this.runStore = runStore;
    this.operationCatalog = operationCatalog;
    this.operationExecutor = operationExecutor;
    this.fileOperationLog = fileOperationLog;
    this.errorEmitter = errorEmitter;
    this.sessionRunner = sessionRunner;
  }

  /**
   * Per tempdoc 429 §E.4 + Phase 10: returns the live substrate operation catalog. After Phase 10 the
   * agent loop is driven by {@link OperationCatalog} + {@code OperationExecutor}; the legacy
   * {@code availableTools()} parallel surface (returning {@code List<ToolDefinition>}) was deleted
   * along with {@code ToolDefinition} itself.
   */
  @Override
  public List<Operation> availableOperations() {
    return List.copyOf(operationCatalog.definitions());
  }

  /**
   * Tempdoc 876 §B.1 — this collaborator holds the catalog but NOT the {@code AgentToolEmitter},
   * and the offering is the emitter's to decide. {@code AgentLoopService} (which holds the emitter)
   * overrides {@code offeredOperations()} directly instead of delegating here, so this body is
   * never the answer any caller receives; returning empty rather than the raw catalog keeps it from
   * becoming a second, unfiltered authority on the offering if that ever changes.
   */
  @Override
  public List<Operation> offeredOperations() {
    return List.of();
  }

  @Override
  public OperationResult undoOperation(String toolName, String executionId, EngineContext engineContext) {
    var op = operationCatalog.findByWireName(toolName).orElse(null);
    if (op == null) {
      return OperationResult.failure("Unknown tool: " + toolName);
    }
    // Tempdoc 875 §C.7: undo meets the trust lattice, so it must carry the transport it really
    // arrived on. This surface is the agent-history "undo the AI" affordance, which is the
    // AGENT_LOOP ingress — declaring it (rather than defaulting to SYSTEM_INTERNAL) is what
    // makes the gate evaluate the true source tier. No token: a reversal that needs one gets a
    // ConfirmationRequiredException, which the route surfaces.
    return operationExecutor.undo(
        op,
        executionId,
        io.justsearch.agent.api.registry.InvocationProvenance.fromEngineContext(engineContext,
            io.justsearch.agent.api.registry.ExecutorTag.AGENT, java.time.Instant.now(), java.util.Optional.empty()),
        java.util.Optional.empty(), engineContext);
  }

  @Override
  public List<Map<String, Object>> operationHistory(int limit) {
    if (fileOperationLog == null) {
      return List.of();
    }
    return fileOperationLog.listBatches(limit).stream()
        .map(AgentRunQueryService::toBatchSummary)
        .toList();
  }

  @Override
  public Map<String, Object> operationDetail(String batchId) {
    if (fileOperationLog == null) {
      return null;
    }
    return fileOperationLog.readBatch(batchId);
  }

  @Override
  public Map<String, Object> lastSessionSnapshot() {
    return runStore.readLastSnapshot();
  }

  @Override
  public List<Map<String, Object>> listSessions(int limit) {
    return runStore.listSessions(Math.max(1, Math.min(limit, 100)));
  }

  @Override
  public List<Map<String, Object>> conversationSummaries(int limit) {
    return runStore.listConversations(Math.max(1, Math.min(limit, 100)));
  }

  @Override
  public Map<String, Object> sessionSnapshot(String sessionId) {
    return runStore.readSnapshot(sessionId);
  }

  @Override
  public void resumeLastSession(Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
    Map<String, Object> snapshot = runStore.readLastSnapshot();
    if (snapshot == null) {
      errorEmitter.emitError(
          eventConsumer,
          "No persisted session snapshot was found.",
          AgentErrorCode.UNSUPPORTED_RESUME_STATE,
          AgentErrorClass.PERMANENT,
          RetryAction.ABORT,
          null);
      return;
    }
    resumeFromSnapshot(snapshot, eventConsumer, engineContext);
  }

  @Override
  public void resumeSession(String sessionId, Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
    Map<String, Object> snapshot = runStore.readSnapshot(sessionId);
    if (snapshot == null) {
      errorEmitter.emitError(
          eventConsumer,
          "No persisted snapshot found for session: " + sessionId,
          AgentErrorCode.UNSUPPORTED_RESUME_STATE,
          AgentErrorClass.PERMANENT,
          RetryAction.ABORT,
          null);
      return;
    }
    resumeFromSnapshot(snapshot, eventConsumer, engineContext);
  }

  /**
   * Shared resume implementation. Tempdoc 415 follow-up (C20). Extracted from
   * {@link #resumeLastSession(Consumer, EngineContext)} so {@link #resumeSession(String, Consumer, EngineContext)} inherits the
   * same state-gate semantics (only WAITING_APPROVAL / READY_FOR_LLM / AFTER_TOOL_RESULT) without
   * duplication.
   */
  private void resumeFromSnapshot(
      Map<String, Object> snapshot, Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
    String state = String.valueOf(snapshot.getOrDefault("state", "UNKNOWN"));
    if (!"WAITING_APPROVAL".equals(state)
        && !"READY_FOR_LLM".equals(state)
        && !"AFTER_TOOL_RESULT".equals(state)) {
      errorEmitter.emitError(
          eventConsumer,
          "Unsupported resume state: " + state
              + ". Resume is only supported for WAITING_APPROVAL, READY_FOR_LLM, and"
              + " AFTER_TOOL_RESULT. Please start a fresh run.",
          AgentErrorCode.UNSUPPORTED_RESUME_STATE,
          AgentErrorClass.PERMANENT,
          RetryAction.ABORT,
          null);
      return;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> messages =
        snapshot.get("messages") instanceof List<?>
            ? (List<Map<String, Object>>) snapshot.get("messages")
            : List.of();
    @SuppressWarnings("unchecked")
    List<String> selectedTools =
        snapshot.get("selectedToolNames") instanceof List<?>
            ? ((List<?>) snapshot.get("selectedToolNames")).stream().map(String::valueOf).toList()
            : List.of();

    int maxIterations = 10;
    Object maxIterationsObj = snapshot.get("maxIterations");
    if (maxIterationsObj instanceof Number n) {
      maxIterations = n.intValue();
    } else if (maxIterationsObj != null) {
      try {
        maxIterations = Integer.parseInt(String.valueOf(maxIterationsObj));
      } catch (NumberFormatException ignored) {
        // Keep default.
      }
    }
    maxIterations = Math.max(1, maxIterations);

    var resumeMessages = new ArrayList<Map<String, Object>>(messages);
    if ("WAITING_APPROVAL".equals(state)) {
      resumeMessages.add(
          Map.of(
              "role",
              "system",
              "content",
              "Resume note: previous pending approvals must be re-requested after restart before"
                  + " any write or destructive action."));
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rawProfiles =
        snapshot.get("agentProfiles") instanceof List<?> l
            ? (List<Map<String, Object>>) l : List.of();
    List<AgentProfile> agentProfiles = rawProfiles.stream()
        .map(AgentProfile::fromMap)
        .toList();
    // Resume from the agent that was active at interruption, not the original initialAgentId
    String resumeAgentId = snapshot.get("activeAgentId") instanceof String s ? s : null;

    sessionRunner.run(
        resumedRequest(snapshot, resumeMessages, selectedTools, maxIterations, agentProfiles,
            resumeAgentId),
        eventConsumer, engineContext);
  }

  /**
   * Tempdoc 859 §D §2.1 — rebuild an {@link AgentRequest} from a persisted snapshot, carrying the
   * EFFORT rung the run was dispatched with.
   *
   * <p>Both reconstruction paths (resume and fork) go through here, because the defect this closes is
   * one of OMISSION and the two sites drifted apart exactly by one caller forgetting a field. A
   * Thorough run resumed through the short constructor silently became Standard — 15x to 5x — and
   * nothing said so, because an absent rung IS Standard by design.
   *
   * <p>The other three fields stay at the short constructor's values (no conversationId, no
   * autonomy level, unscoped docIds): that is today's resume behaviour, unchanged and out of this
   * slice's scope. The rung moves, and so does the sampling pin (lane F PR 0b) — for the same
   * reason and against the same omission: a resumed capture turn that silently reverted to the
   * agent preset would report a pinned run while sampling freely.
   */
  private static AgentRequest resumedRequest(
      Map<String, Object> snapshot,
      List<Map<String, Object>> messages,
      List<String> selectedTools,
      int maxIterations,
      List<AgentProfile> agentProfiles,
      String agentId) {
    // Absent on a pre-859 record, which reads as Standard — the documented fallback, not a guess.
    String effort = snapshot.get("effort") instanceof String s && !s.isBlank() ? s : null;
    return new AgentRequest(
        messages, selectedTools, maxIterations, agentProfiles, agentId, null, null, null,
        List.of(), effort, false, resumedSampling(snapshot));
  }

  /**
   * Rebuilds the run's sampling pin from its snapshot (lane F PR 0b). Absent on a pre-PR-0b record,
   * which reads as "no override" — the documented default.
   *
   * <p>Reads defensively: the snapshot is JSON that has round-tripped through a store, so a number
   * may come back as any {@link Number} subtype, and a value the writer never wrote must not become
   * a pin the caller did not ask for. Anything unreadable is dropped rather than guessed — the
   * run then samples exactly as an unpinned run does, which is the honest fallback.
   */
  private static AgentRequest.SamplingOverride resumedSampling(Map<String, Object> snapshot) {
    if (!(snapshot.get("sampling") instanceof Map<?, ?> map)) {
      return null;
    }
    Double temperature = snapshotDouble(map.get("temperature"));
    Double topP = snapshotDouble(map.get("top_p"));
    Long seed =
        map.get("seed") instanceof Number n && !(map.get("seed") instanceof Boolean)
            ? n.longValue()
            : null;
    if (temperature == null && topP == null && seed == null) {
      return null;
    }
    return new AgentRequest.SamplingOverride(temperature, topP, seed);
  }

  private static Double snapshotDouble(Object value) {
    if (!(value instanceof Number n) || value instanceof Boolean) {
      return null;
    }
    double d = n.doubleValue();
    return Double.isFinite(d) ? d : null;
  }

  /**
   * Tempdoc 585 §D Phase 3 (C2) — time-travel FORK: branch a NEW run from a finished one by rewinding
   * to its last user turn, optionally editing that question, and re-running with the full prior
   * context. Unlike {@link #resumeSession} this has NO state gate (a fork is meaningful precisely for
   * a DONE/ERRORED run) — it constructs a fresh {@link AgentRequest} directly, reusing the snapshot's
   * tool selection / iteration budget / agent profiles. The truncation lands on a clean conversation
   * boundary (it ends at the user message, dropping the prior run's response), so the LLM never sees
   * a half-finished turn.
   */
  @Override
  public void forkSession(String sessionId, String editedMessage, Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
    Map<String, Object> snapshot = runStore.readSnapshot(sessionId);
    if (snapshot == null || snapshot.isEmpty()) {
      errorEmitter.emitError(
          eventConsumer,
          "No such session to fork: " + sessionId,
          AgentErrorCode.UNSUPPORTED_RESUME_STATE,
          AgentErrorClass.PERMANENT,
          RetryAction.ABORT,
          null);
      return;
    }
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> messages =
        snapshot.get("messages") instanceof List<?>
            ? (List<Map<String, Object>>) snapshot.get("messages")
            : List.of();
    List<Map<String, Object>> forked = forkMessages(messages, editedMessage);
    if (forked.isEmpty()) {
      errorEmitter.emitError(
          eventConsumer,
          "Cannot fork a run with no user turn to branch from.",
          AgentErrorCode.UNSUPPORTED_RESUME_STATE,
          AgentErrorClass.PERMANENT,
          RetryAction.ABORT,
          null);
      return;
    }
    @SuppressWarnings("unchecked")
    List<String> selectedTools =
        snapshot.get("selectedToolNames") instanceof List<?>
            ? ((List<?>) snapshot.get("selectedToolNames")).stream().map(String::valueOf).toList()
            : List.of();
    int maxIterations = 10;
    if (snapshot.get("maxIterations") instanceof Number n) {
      maxIterations = Math.max(1, n.intValue());
    }
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rawProfiles =
        snapshot.get("agentProfiles") instanceof List<?> l
            ? (List<Map<String, Object>>) l : List.of();
    List<AgentProfile> agentProfiles = rawProfiles.stream().map(AgentProfile::fromMap).toList();
    String agentId = snapshot.get("activeAgentId") instanceof String s ? s : null;
    sessionRunner.run(
        resumedRequest(snapshot, forked, selectedTools, maxIterations, agentProfiles, agentId),
        eventConsumer, engineContext);
  }

  /**
   * Tempdoc 585 §D Phase 3 (C2) — truncate a conversation at its last user turn (a clean boundary)
   * and replace that question with {@code editedMessage} (or keep the original when blank, a re-roll).
   * Everything BEFORE the last user turn is preserved as context; the prior run's response is dropped.
   * Returns empty when there is no user turn to fork from.
   */
  static List<Map<String, Object>> forkMessages(
      List<Map<String, Object>> messages, String editedMessage) {
    int lastUser = -1;
    for (int i = messages.size() - 1; i >= 0; i--) {
      if ("user".equals(messages.get(i).get("role"))) {
        lastUser = i;
        break;
      }
    }
    if (lastUser < 0) {
      return List.of();
    }
    var forked = new ArrayList<Map<String, Object>>(messages.subList(0, lastUser));
    String content =
        editedMessage != null && !editedMessage.isBlank()
            ? editedMessage
            : String.valueOf(messages.get(lastUser).getOrDefault("content", ""));
    forked.add(Map.of("role", "user", "content", content));
    return forked;
  }

  @Override
  public List<Map<String, Object>> sessionEvents(String sessionId) {
    return runStore.readEvents(sessionId);
  }

  /**
   * Tempdoc 561 P-A/P-B (correction): the agent plane's contribution to the unified thread — a
   * READ-TIME projection over {@code AgentRunStore} for every run of {@code conversationId}. Each
   * run yields its user prompt (from the run meta) + its thread-worthy events (from events.ndjson via
   * {@link AgentInteractionMapper}). No second store is written; the thread reads the durable record.
   */
  @Override
  public List<InteractionEvent> threadEvents(String conversationId) {
    return threadEvents(conversationId, java.util.Set.of());
  }

  @Override
  public List<InteractionEvent> threadEvents(
      String conversationId, java.util.Set<String> answeredRunIds) {
    return threadProjection(conversationId, answeredRunIds).events();
  }

  @Override
  public ThreadProjection threadProjection(
      String conversationId, java.util.Set<String> answeredRunIds) {
    if (conversationId == null || conversationId.isBlank()) {
      return ThreadProjection.of(List.of());
    }
    java.util.Set<String> answered = answeredRunIds == null ? java.util.Set.of() : answeredRunIds;
    List<InteractionEvent> out = new ArrayList<>();
    // Tempdoc 871 §3b — the trailing blocks of every run whose terminal answer is suppressed below,
    // handed to the caller (the ONE place both planes are visible) rather than re-homed backwards.
    Map<String, List<Map<String, Object>>> trailingReasoning = new LinkedHashMap<>();
    for (String runId : runStore.listRunIdsByConversation(conversationId)) {
      Map<String, Object> meta = runStore.readSnapshot(runId);
      // Tempdoc 565 §26.D — a BACKGROUND run (ran "while you were away") that carries this
      // conversationId renders as a `background` run-segment in the thread: wrap its events in
      // origin=background boundary markers the FE assignRunSegments brackets (the same §26.A
      // machinery as workflow nodes). An interactive run stays ungrouped.
      boolean background = meta != null && Boolean.TRUE.equals(meta.get("background"));
      java.time.Instant startedAt =
          meta != null
              ? AgentInteractionMapper.parseTs(meta.get("startedAt"))
              : java.time.Instant.EPOCH;
      if (background) {
        out.add(backgroundBoundary(runId, conversationId, startedAt, "start"));
      }
      // Tempdoc 863 §4.A.3 (A-2) — a STAMPED run is one whose turns the CONVERSATION record already
      // holds, because the engine appended them there as it dispatched (863 §4.A.2). Both of this
      // projection's read-time syntheses are therefore duplicates for it: the `<runId>:user` turn
      // below and the mapper's terminal `ASSISTANT_MESSAGE`. `InteractionThreadController` merges the
      // two planes with no dedup and `projectSv3RecordTurns` opens a turn on EVERY user item, so
      // without this one delegate turn would render twice — the exact objection `ChatController`
      // recorded against this design, answered here rather than argued with.
      //
      // The suppression is NARROWING, not deletion: a run with no stamp (every run written before
      // this shipped, every standalone run, every background run) keeps both syntheses and renders
      // byte-identically to before, because for those runs the syntheses really are the only record.
      boolean stamped = meta != null && Boolean.TRUE.equals(meta.get("recordsToThread"));
      if (meta != null && !stamped) {
        String userContent = firstUserMessage(meta);
        if (userContent != null) {
          out.add(
              InteractionEvent.message(
                  runId + ":user",
                  conversationId,
                  startedAt,
                  InteractionEventKind.USER_MESSAGE,
                  "user",
                  userContent));
        }
      }
      // Tempdoc 848 §2.4 — the whole run at once, not record-by-record: the reasoning fold is
      // stateful (chunks coalesce into blocks that attach to the turn they belong to), and its
      // terminal-attachment rule needs the run's boundary.
      List<InteractionEvent> runEvents =
          AgentInteractionMapper.fromRunEvents(runStore.readEvents(runId), conversationId);
      // Tempdoc 863 §4.A.5 (F2) — the ANSWER is suppressed only when the record demonstrably holds
      // it. The stamp is written at startRun and the answer is appended at the terminal `done`, so a
      // store locked mid-run leaves a stamped run whose answer reached neither plane; keying on the
      // duplicate's existence makes that impossible, and self-heals runs that already failed so.
      // The USER synthesis above stays keyed on the stamp alone, because its append happens BEFORE
      // the run starts — a stamped run's user turn is on the record by construction.
      boolean answerOnRecord = stamped && answered.contains(runId);
      if (answerOnRecord) {
        List<Map<String, Object>> orphaned = new ArrayList<>();
        out.addAll(withoutTerminalAnswer(runEvents, conversationId, orphaned));
        if (!orphaned.isEmpty()) {
          trailingReasoning.put(runId, List.copyOf(orphaned));
        }
      } else {
        out.addAll(runEvents);
      }
      if (background) {
        // +1ms after the run's last update so the closing marker sorts AFTER every event of the run.
        java.time.Instant endAt =
            (meta != null ? AgentInteractionMapper.parseTs(meta.get("updatedAt")) : startedAt)
                .plusMillis(1);
        out.add(backgroundBoundary(runId, conversationId, endAt, "end"));
      }
    }
    return new ThreadProjection(out, trailingReasoning);
  }

  /**
   * Tempdoc 863 §4.A.3 (A-2) — drop the AGENT run's terminal {@code ASSISTANT_MESSAGE}, which for a
   * stamped run is a second copy of an answer the conversation record already holds. Keyed on the
   * mint ({@link AgentInteractionMapper#isTerminalAnswer}), never on position: a workflow run's
   * per-node answers are {@code ASSISTANT_MESSAGE}s too, and a "drop the last assistant event" rule
   * would have eaten the last node of every one of them.
   *
   * <p>The dropped event can be carrying the run's TRAILING thinking — the reasoning fold attaches a
   * block to the next event that projects, and on an agent run the terminal answer is usually that
   * event (848 §2.4). Tempdoc 871 §3b closes 863 §8.4's open question about where those blocks then
   * go: they are collected into {@code orphanedOut} for the caller to place, NOT re-attached to the
   * run's last surviving event. That re-home was 863's own "KNOWN INVERSION" and it is a chronology
   * defect, not a cosmetic one — the last surviving event is normally the run's final
   * {@code TOOL_ACTIVITY}, which happened BEFORE the thinking, and the FE draws a carrier's blocks
   * ABOVE the carrier. A reader saw "planned → analysed the results → [the search that produced
   * them]". The blocks' real carrier is the answer plane's copy of this same answer, which only the
   * thread controller can see; the cross-plane merge this comment used to defer is now made there.
   */
  private static List<InteractionEvent> withoutTerminalAnswer(
      List<InteractionEvent> runEvents,
      String conversationId,
      List<Map<String, Object>> orphanedOut) {
    List<InteractionEvent> kept = new ArrayList<>(runEvents.size());
    for (InteractionEvent event : runEvents) {
      if (AgentInteractionMapper.isTerminalAnswer(event, conversationId)) {
        if (event.attributes().get("reasoning") instanceof List<?> blocks) {
          for (Object block : blocks) {
            if (block instanceof Map<?, ?> m) {
              orphanedOut.add(AgentInteractionMapper.castMap(m));
            }
          }
        }
        continue;
      }
      kept.add(event);
    }
    return kept;
  }

  /**
   * Tempdoc 565 §26.D — a background-run segment boundary (a PROGRESS marker carrying
   * {@code originKind=background} + the run id as the segment key). The id sorts before the run's
   * {@code :user} turn (start) / after its events (end, +1ms) so the FE brackets the whole run.
   */
  private static InteractionEvent backgroundBoundary(
      String runId, String conversationId, java.time.Instant at, String boundary) {
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("nodeBoundary", boundary);
    attrs.put("originKind", "background");
    attrs.put("nodeId", runId);
    attrs.put("label", "Background activity");
    return new InteractionEvent(
        runId + ":bg-" + boundary,
        conversationId,
        at,
        InteractionEventKind.PROGRESS,
        "agent",
        "",
        attrs);
  }

  /**
   * Tempdoc 561 P-A / P-A2: the typed loop objects for a conversation's agent runs, projected from
   * the durable AgentRunStore record (the consumer that makes the Session ⊃ Turn ⊃ Iteration model
   * first-class, not unconsumed substrate).
   */
  @Override
  public List<io.justsearch.agent.api.lifecycle.AgentLifecycle> lifecycles(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return List.of();
    }
    List<io.justsearch.agent.api.lifecycle.AgentLifecycle> out = new ArrayList<>();
    for (String runId : runStore.listRunIdsByConversation(conversationId)) {
      Map<String, Object> meta = runStore.readSnapshot(runId);
      if (meta != null) {
        out.add(AgentLifecycleProjection.fromRun(meta));
      }
    }
    return out;
  }

  /**
   * Tempdoc 561 P-D2: the presence axis — the typed lifecycles of BACKGROUND runs completed since
   * {@code since}, projected from the durable AgentRunStore record (the render-on-return inbox source).
   */
  @Override
  public List<io.justsearch.agent.api.lifecycle.AgentLifecycle> presenceSince(
      java.time.Instant since) {
    List<io.justsearch.agent.api.lifecycle.AgentLifecycle> out = new ArrayList<>();
    for (Map<String, Object> meta : runStore.presenceRunsSince(since)) {
      out.add(AgentLifecycleProjection.fromRun(meta));
    }
    return out;
  }

  /** The first user-role message in a run's persisted meta (the run's prompt). */
  private static String firstUserMessage(Map<String, Object> meta) {
    if (meta.get("messages") instanceof List<?> messages) {
      for (Object m : messages) {
        if (m instanceof Map<?, ?> msg && "user".equals(msg.get("role"))) {
          Object content = msg.get("content");
          return content instanceof String c ? c : "";
        }
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> toBatchSummary(Map<String, Object> batch) {
    List<?> ops = (List<?>) batch.get("operations");
    List<Map<String, Object>> executed =
        (List<Map<String, Object>>) batch.get("executed");

    long successCount =
        executed == null
            ? 0
            : executed.stream()
                .filter(
                    e -> "OK".equals(e.get("status")) || "OK_RENAMED".equals(e.get("status")))
                .count();
    long failedCount =
        executed == null
            ? 0
            : executed.stream().filter(e -> "FAILED".equals(e.get("status"))).count();
    long skippedCount =
        executed == null
            ? 0
            : executed.stream().filter(e -> "SKIPPED".equals(e.get("status"))).count();

    return Map.of(
        "batchId", batch.getOrDefault("batchId", ""),
        "timestamp", batch.getOrDefault("timestamp", ""),
        "explanation", batch.getOrDefault("explanation", ""),
        "operationCount", ops != null ? ops.size() : 0,
        "successCount", successCount,
        "failedCount", failedCount,
        "skippedCount", skippedCount,
        "finalized", batch.containsKey("finalized"));
  }
}
