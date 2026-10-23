/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.conversation.ContextInjector;
import io.justsearch.agent.api.conversation.ExecutionMode;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.conversation.IterationController;
import io.justsearch.agent.api.conversation.IterationDecision;
import io.justsearch.agent.api.conversation.IterationMode;
import io.justsearch.agent.api.conversation.PromptContributor;
import io.justsearch.agent.api.conversation.PromptFragment;
import io.justsearch.agent.api.conversation.SingleHopController;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.conversation.StreamConsumer;
import io.justsearch.agent.api.conversation.StreamConsumerResult;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.ConversationShapeCatalog;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.core.util.TokenEstimation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The conversation substrate runtime.
 *
 * <p>Per tempdoc 491 §5.4: one engine, two execution modes.
 *
 * <ul>
 *   <li>{@link ExecutionMode#SHAPE_DRIVEN} — the engine delegates to a registered
 *       {@link ShapeRunner}, which is responsible for the entire conversation lifecycle.
 *       Used to encapsulate existing implementations (the agent loop is the canonical
 *       example).
 *   <li>{@link ExecutionMode#SUBSTRATE_DRIVEN} — the engine controls the per-iteration loop
 *       and invokes the shape's SPIs in declaration order. Phase C implementation:
 *       (1) assemble system prompt from {@link PromptContributor}s ordered by priority,
 *       (2) run {@link ContextInjector}s in declaration order, prepending injected messages,
 *       (3) call {@link OnlineAiService#streamChat} with the assembled message list,
 *       (4) dispatch streamed chunks + final {@code onDone} to {@link StreamConsumer}s,
 *       collecting their {@link StreamConsumerResult#messageDeltas} for the next iteration,
 *       (5) invoke {@link IterationController#next} to decide whether to loop.
 * </ul>
 *
 * <p>Per §5.4 trust gating: the engine validates the request's invocation audience against
 * the shape's declared {@link Audience} before invoking either path.
 */
public final class ConversationEngine {

  private static final Logger LOG = LoggerFactory.getLogger(ConversationEngine.class);
  // Package-private, not private: the reasoning-budget clamp in ResolvedConfigBuilder mirrors this
  // ceiling (the configuration module cannot depend on app-services) and a test pins the two
  // together — tempdoc 835 §9f.
  static final int DEFAULT_MAX_TOKENS = 1024;
  private static final int MAX_ITERATIONS_HARD_CAP = 20;
  private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();
  private static final long STALL_POLL_MS = 250;

  /**
   * How long a turn may go with no output at all before {@link #streamLlm} gives up on it. Longer
   * than the transport's own idle read deadline (2 minutes), so a stalled <em>body</em> is reported
   * by the transport and this only catches a producer that never called back — a stream task that
   * never started, or one parked outside the read loop. Package-private for tests.
   */
  Duration llmStallDeadline = Duration.ofMinutes(3);

  private final ConversationShapeCatalog catalog;
  private final Map<ConversationShapeRef, ShapeRunner> runnersByShape;
  private final PromptContributorRegistry promptContributors;
  private final ContextInjectorRegistry contextInjectors;
  private final StreamConsumerRegistry streamConsumers;
  private final IterationControllerRegistry iterationControllers;
  private final Supplier<OnlineAiService> onlineAiSupplier;
  private final io.justsearch.agent.api.conversation.ConversationStore conversationStore;
  private final io.justsearch.app.api.EngineAdmissionService admission;

  /** The dispatch body key naming the turn, so the reader's Stop can name the turn it means. */
  public static final String TURN_ID_KEY = "turnId";
  /** The cancellation reason of the reader's own Stop, the one cancellation saved as theirs. */
  public static final String USER_STOP_REASON = "user_stop";
  /** The disposition a stopped turn is saved with; the record reads it as "stopped by you". */
  static final String STOPPED_DISPOSITION = "CANCELLED";

  /** The recording turns a Stop can reach: running now, or stopped before they started. */
  private final TurnStops turnStops = new TurnStops(USER_STOP_REASON);

  /**
   * Constructs the engine. Phase B-compatible overload (registries + LLM source default to
   * empty / unavailable) — used by tests and pre-substrate-driven assembly.
   */
  public ConversationEngine(ConversationShapeCatalog catalog, Iterable<ShapeRunner> shapeRunners) {
    this(
        catalog,
        shapeRunners,
        PromptContributorRegistry.of(List.of()),
        ContextInjectorRegistry.of(List.of()),
        StreamConsumerRegistry.of(List.of()),
        IterationControllerRegistry.of(List.of()),
        OnlineAiService::unavailable,
        io.justsearch.agent.api.conversation.ConversationStore.noop());
  }

  /**
   * Full constructor. Phase C wiring uses this overload to inject the substrate-driven
   * SPI registries, the LLM service supplier, and the conversation store.
   */
  public ConversationEngine(
      ConversationShapeCatalog catalog,
      Iterable<ShapeRunner> shapeRunners,
      PromptContributorRegistry promptContributors,
      ContextInjectorRegistry contextInjectors,
      StreamConsumerRegistry streamConsumers,
      IterationControllerRegistry iterationControllers,
      Supplier<OnlineAiService> onlineAiSupplier) {
    this(catalog, shapeRunners, promptContributors, contextInjectors,
        streamConsumers, iterationControllers, onlineAiSupplier,
        io.justsearch.agent.api.conversation.ConversationStore.noop());
  }

  /**
   * Slice 496 §3.B — constructor with ConversationStore for persistent shapes.
   */
  public ConversationEngine(
      ConversationShapeCatalog catalog,
      Iterable<ShapeRunner> shapeRunners,
      PromptContributorRegistry promptContributors,
      ContextInjectorRegistry contextInjectors,
      StreamConsumerRegistry streamConsumers,
      IterationControllerRegistry iterationControllers,
      Supplier<OnlineAiService> onlineAiSupplier,
      io.justsearch.agent.api.conversation.ConversationStore conversationStore) {
    this(catalog, shapeRunners, promptContributors, contextInjectors, streamConsumers,
        iterationControllers, onlineAiSupplier, conversationStore, null);
  }

  public ConversationEngine(
      ConversationShapeCatalog catalog,
      Iterable<ShapeRunner> shapeRunners,
      PromptContributorRegistry promptContributors,
      ContextInjectorRegistry contextInjectors,
      StreamConsumerRegistry streamConsumers,
      IterationControllerRegistry iterationControllers,
      Supplier<OnlineAiService> onlineAiSupplier,
      io.justsearch.agent.api.conversation.ConversationStore conversationStore,
      io.justsearch.app.api.EngineAdmissionService admission) {
    this.admission = admission;
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    Map<ConversationShapeRef, ShapeRunner> idx = new LinkedHashMap<>();
    for (ShapeRunner r : shapeRunners) {
      Objects.requireNonNull(r, "shapeRunner");
      ShapeRunner existing = idx.putIfAbsent(r.shapeId(), r);
      if (existing != null) {
        throw new IllegalArgumentException(
            "Duplicate ShapeRunner registration for " + r.shapeId().value());
      }
    }
    this.runnersByShape = Map.copyOf(idx);
    this.promptContributors = Objects.requireNonNull(promptContributors, "promptContributors");
    this.contextInjectors = Objects.requireNonNull(contextInjectors, "contextInjectors");
    this.streamConsumers = Objects.requireNonNull(streamConsumers, "streamConsumers");
    this.iterationControllers =
        Objects.requireNonNull(iterationControllers, "iterationControllers");
    this.onlineAiSupplier = Objects.requireNonNull(onlineAiSupplier, "onlineAiSupplier");
    this.conversationStore =
        Objects.requireNonNull(conversationStore, "conversationStore");
  }

  /** Returns the catalog the engine resolves shapes against. */
  public ConversationShapeCatalog catalog() {
    return catalog;
  }

  /**
   * Tempdoc 734 round-14 F4 — TRUE when this shape + body WOULD record its turns to the conversation
   * store, but the store is encrypted and locked, so every append throws and the turn is
   * accepted-and-dropped. Asked by the dispatch controller BEFORE it commits an SSE 200, so the
   * caller gets the same locked answer the history read path already gives (423) instead of silence.
   *
   * <p>Gated on the WRITE KEY, not bluntly on "locked": a dispatch that records nothing (an EPHEMERAL
   * shape that is not {@code recordsToThread}, an OPERATOR/AGENT-audience or shape-driven shape, a
   * request carrying neither {@code sessionId} nor {@code conversationId}, or an internal throwaway
   * conversation) touches no store and stays serviceable while locked. The key is resolved by the
   * SAME two helpers the substrate-driven dispatch resolves it with ({@link #sessionIdFor} /
   * {@link #threadRecordId}), so the gate cannot drift from what actually gets written.
   */
  public boolean wouldDiscardWhileLocked(ConversationShapeRef shapeId, Map<String, Object> body) {
    if (conversationStore == null || !conversationStore.isLocked()) {
      return false;
    }
    Map<String, Object> safeBody = body == null ? Map.of() : body;
    return catalog
        .findById(shapeId)
        .map(shape -> persistenceKey(shape, safeBody) != null)
        .orElse(false);
  }

  /**
   * The conversation-record write key for a request: the PERSISTENT shape's {@code sessionId}, else
   * the recordsToThread {@code conversationId}. {@code null} = this request persists nothing.
   */
  private static String persistenceKey(ConversationShape shape, Map<String, Object> body) {
    String sessionId = sessionIdFor(shape, body);
    return sessionId != null ? sessionId : threadRecordId(shape, sessionId, body);
  }

  /**
   * Run a registered shape. Lookup → audience-validate → dispatch by mode. Blocks until the
   * shape's runner (or substrate-driven loop) returns.
   */
  public void run(
      ConversationShapeRef shapeId,
      Map<String, Object> body,
      Audience audience,
      Consumer<SseEvent> sink, EngineContext engineContext) {
    run(shapeId, body, audience, sink, engineContext, false);
  }

  /**
   * Runs one externally owned conversation turn with an explicit scope opened after admission.
   *
   * <p>Admission may replace the caller's context with its work-id-bearing form. Opening here makes
   * that exact immutable context available to resources used across executor boundaries, while the
   * try-with-resources lifetime covers the complete blocking shape run.
   */
  public void runScoped(
      ConversationShapeRef shapeId,
      Map<String, Object> body,
      Audience audience,
      Consumer<SseEvent> sink,
      EngineContext engineContext,
      OperationScopeFactory scopeFactory) {
    run(shapeId, body, audience, sink, engineContext, false, scopeFactory);
  }

  /** Server-owned non-interactive posture, carried by nested workflow delegation. */
  public void run(ConversationShapeRef shapeId, Map<String, Object> body, Audience audience,
      Consumer<SseEvent> sink, EngineContext engineContext, boolean background) {
    run(shapeId, body, audience, sink, engineContext, background, OperationScopeFactory.none());
  }

  private void run(
      ConversationShapeRef shapeId,
      Map<String, Object> body,
      Audience audience,
      Consumer<SseEvent> sink,
      EngineContext engineContext,
      boolean background,
      OperationScopeFactory scopeFactory) {
    Objects.requireNonNull(scopeFactory, "scopeFactory");
    Map<String, Object> safeBody = body == null ? Map.of() : body;
    String conversationId = catalog.findById(shapeId).map(shape -> persistenceKey(shape, safeBody)).orElse(null);
    if (conversationId == null && safeBody.get("conversationId") instanceof String id && !id.isBlank()) {
      conversationId = id;
    }
    var writers = conversationStore.conversationWork();
    try (var ignoredWriting = writers == null ? null : writers.start(conversationId);
        var work = admission == null ? null : admission.attach(engineContext)) {
      EngineContext context = work == null ? engineContext : work.context();
      if (work != null) work.cancellationReason().ifPresent(reason -> {
        throw new io.justsearch.app.api.EngineWorkCancelledException(reason);
      });
      try (OperationScope ignored = scopeFactory.open(shapeId, context)) {
        runAdmitted(shapeId, body, audience, sink, context, work, background);
      }
    }
  }

  /** What the reader's Stop did to its turn ({@link #stopTurn}). */
  public enum TurnStop {
    /** The running turn was stopped now; it is saved as stopped. */
    STOPPED,
    /** The turn has not started yet; it is stopped, and saved as stopped, when it starts. */
    PENDING,
    /** Nothing changed: the turn's answer was already saved, or it ended for another reason. */
    UNCHANGED
  }

  /**
   * The reader pressed Stop on a turn: its work is cancelled with {@link #USER_STOP_REASON}, and
   * the turn is saved as stopped, with its question, its rewrite note, its sources and whatever had
   * streamed and been thought. A Stop that overtook its dispatch stops the turn the moment it
   * starts ({@link TurnStops}); a turn whose answer is already saved is left alone.
   */
  public TurnStop stopTurn(String conversationId, String turnId) {
    return turnStops.stop(new TurnStops.Turn(conversationId, turnId));
  }

  @FunctionalInterface
  public interface OperationScopeFactory {
    OperationScope open(ConversationShapeRef shapeId, EngineContext engineContext);

    static OperationScopeFactory none() {
      return (shapeId, context) -> () -> {};
    }
  }

  @FunctionalInterface
  public interface OperationScope extends AutoCloseable {
    @Override
    void close();
  }

  private void runAdmitted(
      ConversationShapeRef shapeId,
      Map<String, Object> body,
      Audience audience,
      Consumer<SseEvent> sink, EngineContext engineContext, io.justsearch.app.api.EngineWorkHandle work,
      boolean background) {
    Objects.requireNonNull(shapeId, "shapeId");
    Objects.requireNonNull(audience, "audience");
    Objects.requireNonNull(sink, "sink");
    Map<String, Object> safeBody = body == null ? Map.of() : body;

    ConversationShape shape =
        catalog
            .findById(shapeId)
            .orElseThrow(
                () ->
                    new ShapeNotFoundException(
                        "No ConversationShape registered for id " + shapeId.value()));

    validateAudience(shape, audience);

    switch (shape.executionMode()) {
      case SHAPE_DRIVEN -> dispatchShapeDriven(shape, safeBody, audience, sink, engineContext, work, background);
      case SUBSTRATE_DRIVEN -> dispatchSubstrateDriven(shape, safeBody, audience, sink, engineContext, work);
    }
  }

  private static void validateAudience(ConversationShape shape, Audience requestAudience) {
    if (shape.audience() != requestAudience) {
      boolean adminOverride =
          (requestAudience == Audience.OPERATOR || requestAudience == Audience.DEVELOPER)
              && (shape.audience() == Audience.USER || shape.audience() == Audience.AGENT);
      if (!adminOverride) {
        throw new AudienceDeniedException(
            "Audience "
                + requestAudience
                + " is not permitted to invoke shape "
                + shape.id().value()
                + " (requires "
                + shape.audience()
                + ")");
      }
    }
  }

  /**
   * Tempdoc 863 §4.A.3 (A-1) — the body key the engine stamps onto a shape-driven dispatch that IS
   * recording to the canonical record. {@code ShapeRunner.run} receives no {@link ConversationShape},
   * so the runner cannot ask {@code recordsToThread()} itself; the engine answers the question once,
   * here, and the runner carries the answer into its {@code AgentRequest} (and from there into the
   * run meta, which is where the thread projection reads it). Always written — true AND false — so a
   * client-supplied value in the request body can never be mistaken for the engine's decision.
   */
  public static final String RECORDS_TO_THREAD_KEY = "recordsToThread";
  /** Internal dispatch projection: the engine overwrites caller input on every shape dispatch. */
  static final String BACKGROUND_RUN_KEY = "backgroundRun";

  private void dispatchShapeDriven(
      ConversationShape shape,
      Map<String, Object> body,
      Audience audience,
      Consumer<SseEvent> sink, EngineContext engineContext, io.justsearch.app.api.EngineWorkHandle work,
      boolean background) {
    ShapeRunner runner = runnersByShape.get(shape.id());
    if (runner == null) {
      throw new IllegalStateException(
          "Shape "
              + shape.id().value()
              + " declares SHAPE_DRIVEN execution but no ShapeRunner is registered for it");
    }
    LOG.debug("Dispatching shape-driven {} (audience={})", shape.id().value(), audience);

    // Tempdoc 863 §4.A.2 — a recordsToThread SHAPE_DRIVEN shape (core.agent-run) puts its turns on
    // the canonical record, exactly as the substrate-driven path does. The write key comes from the
    // SAME helper the locked-store refusal is gated on (`persistenceKey`), so the 423 gate and the
    // write cannot drift. The runner stays untouched: there is ONE store-write site (here), the
    // clean user turn goes in before the run starts, and the assistant turn goes in at the terminal
    // `done` observed on the sink the engine already owns.
    // The no-op store is excluded EXPLICITLY, not incidentally. It silently accepts every append, so
    // stamping a run against it would suppress the run plane's own synthesised turns (the stamp's
    // whole job) while nothing had actually been recorded anywhere — one delegate turn would vanish
    // from the thread entirely. Suppression is only ever justified by a store that really kept the
    // turn, so the engine asks whether it has one.
    boolean recordingStore =
        conversationStore != null
            && conversationStore != io.justsearch.agent.api.conversation.ConversationStore.noop();
    String recordKey =
        shape.recordsToThread() && recordingStore ? persistenceKey(shape, body) : null;

    Map<String, Object> dispatchBody = new LinkedHashMap<>(body);
    dispatchBody.put(RECORDS_TO_THREAD_KEY, recordKey != null);
    dispatchBody.put(BACKGROUND_RUN_KEY, background);

    if (recordKey == null) {
      runner.run(dispatchBody, audience, sink, engineContext);
      return;
    }

    conversationStore.createSession(recordKey, shape.id().value());
    conversationStore.appendMessage(recordKey, shape.id().value(), threadUserMessage(body));
    // Tempdoc 863 §4.A.5 (F2) — the run's own id, observed on the sink. It rides onto the persisted
    // answer so the thread projection can suppress the run plane's copy of THIS run's answer only
    // when the record really holds it; see recordShapeDrivenAnswer.
    var runId = new AtomicReference<String>();
    runner.run(
        dispatchBody,
        audience,
        event -> {
          // RECORD FIRST, forward second. The sink is allowed to throw — `AgentSseWriter.writeOrEvict`
          // throws precisely so a run drops a disconnected observer — so forwarding first would make
          // the durable write conditional on someone still watching: a reader who closed the tab
          // during a long run would come back to a conversation whose answer was never recorded,
          // while the run itself completed. The store write is the durable act and does not depend on
          // an audience.
          if ("session_started".equals(event.name())
              && event.payload().get("sessionId") instanceof String s
              && !s.isBlank()) {
            runId.compareAndSet(null, s);
          }
          if ("done".equals(event.name())) {
            checkWork(work);
            recordShapeDrivenAnswer(recordKey, shape, event.payload(), runId.get());
          }
          sink.accept(event);
        }, engineContext);
  }

  /**
   * Tempdoc 863 §4.A.2/§4.A.4 — the shape-driven answer, appended to the canonical record at the
   * terminal {@code done}. The text is the payload's {@code finalResponse} (a shape-driven run
   * accumulates no {@code finalText} of its own in the engine), and the evidence rides the same
   * {@link #persistedAssistant} projection the substrate path uses, so the two planes cannot carry
   * different evidence for the same answer.
   *
   * <p>A store failure here is logged rather than thrown: this runs inside the runner's event
   * callback, and throwing would abort the agent loop's own terminal bookkeeping after the reader
   * already has the answer. The dispatch-time cause — a store already locked — is refused before the
   * stream commits ({@code wouldDiscardWhileLocked}, which answers true for this dispatch precisely
   * because it now has a write key, 863 §4.A.5 A-9). The residual is a store that becomes locked or
   * unwritable MID-RUN, and that is why the answer carries its {@code runId} (F2): the thread
   * projection suppresses the run plane's copy only for a run whose answer the record actually holds,
   * so a failed append here leaves the run-plane answer standing rather than erasing the answer from
   * both planes. The run keeps its {@code recordsToThread} stamp either way — the USER turn really
   * was recorded, so its synthesis stays suppressed and does not double.
   */
  private void recordShapeDrivenAnswer(
      String recordKey, ConversationShape shape, Map<String, Object> donePayload, String runId) {
    Map<String, Object> payload = donePayload == null ? Map.of() : donePayload;
    Object finalResponse = payload.get("finalResponse");
    try {
      Map<String, Object> answer =
          persistedAssistant(
              assistantMessage(finalResponse instanceof String s ? s : ""), payload, null, null);
      if (runId != null && !runId.isBlank()) {
        answer.put("runId", runId);
      }
      conversationStore.appendMessage(recordKey, shape.id().value(), answer);
    } catch (RuntimeException e) {
      LOG.warn("Failed to record the {} answer for conversation {}", shape.id().value(), recordKey, e);
    }
  }

  /**
   * The substrate-driven orchestration. Resolves SPIs from registries, assembles the system
   * prompt, runs injectors on iteration 0, then loops: LLM call → consumer dispatch →
   * controller decision.
   *
   * <p>Per tempdoc 491 §5.1: contributors are priority-sorted (stable). Injectors run once
   * per request (iteration 0 only) — re-running e.g. RAG retrieval every iteration would
   * be wrong; injection is a per-request concern, not a per-iteration concern. Consumers
   * fire on every iteration's {@code onChunk} + {@code onDone}.
   */
  private void dispatchSubstrateDriven(
      ConversationShape shape,
      Map<String, Object> body,
      Audience audience,
      Consumer<SseEvent> sink, EngineContext engineContext, io.justsearch.app.api.EngineWorkHandle work) {
    // Tempdoc 834 §4.3 — the turn-open marker is cleared HERE, in a finally around the whole
    // dispatch body, because that body has EIGHT exits (injector terminated, AI unavailable,
    // LlmStreamException, consumer onDone threw, controller next threw, STOP_SUCCESS, STOP_ERROR,
    // iteration hard cap). Clearing at selected exits would leave every cleanly-errored run marked
    // "interrupted" — the inverse dishonesty. Only a process death leaves the mark set, which is
    // exactly the condition it encodes.
    var openTurnKey = new AtomicReference<String>();
    TurnStops.Turn running = runningTurn(shape, body, work);
    if (running != null) turnStops.started(running, work);
    try {
      dispatchSubstrateDrivenBody(shape, body, audience, sink, openTurnKey, engineContext, work, running);
    } finally {
      if (running != null) turnStops.finished(running, work);
      String key = openTurnKey.get();
      if (key != null && conversationStore != null) {
        try {
          conversationStore.setTurnOpen(key, false);
        } catch (RuntimeException e) {
          LOG.warn("Failed to clear the turn-open marker for conversation {}", key, e);
        }
      }
    }
  }

  /** The turn a Stop can name: one that is recorded, names itself and runs as Engine work. */
  private static TurnStops.Turn runningTurn(
      ConversationShape shape, Map<String, Object> body, io.justsearch.app.api.EngineWorkHandle work) {
    String conversationId = persistenceKey(shape, body);
    return work != null && conversationId != null
            && body.get(TURN_ID_KEY) instanceof String turnId && !turnId.isBlank()
        ? new TurnStops.Turn(conversationId, turnId)
        : null;
  }

  private void dispatchSubstrateDrivenBody(
      ConversationShape shape,
      Map<String, Object> body,
      Audience audience,
      Consumer<SseEvent> sink,
      AtomicReference<String> openTurnKey, EngineContext engineContext, io.justsearch.app.api.EngineWorkHandle work,
      TurnStops.Turn running) {
    LOG.debug("Dispatching substrate-driven {} (audience={})", shape.id().value(), audience);

    // Resolve SPI implementations from registries.
    List<PromptContributor> contributors = resolveContributors(shape);
    List<ContextInjector> injectors = resolveInjectors(shape);
    List<StreamConsumer> consumers = resolveConsumers(shape);
    IterationController controller = resolveController(shape);

    // Slice 496 §3.B: for PERSISTENT shapes, load history from ConversationStore.
    // The prior stub (Phase C) left the message list empty and said "later phase."
    // Phase 496 implements the integration: sessionId from the request body seeds
    // the context with the conversation history. EPHEMERAL shapes still start empty.
    String sessionId = sessionIdFor(shape, body);
    List<Map<String, Object>> initialMessages = new ArrayList<>();
    if (sessionId != null && conversationStore != null) {
      // Tempdoc 610 Phase C — seed the prompt from the EFFECTIVE context (the
      // history trimmed to the session's context floor, if set), NOT the full
      // displayed history. With no floor this is identical to loadHistory. The
      // display path (/history) keeps loadHistory, so the transcript still
      // shows everything above the floor as out-of-context.
      initialMessages.addAll(conversationStore.loadEffectiveContext(sessionId));
    }
    // Tempdoc 561 P-A/P-B — the canonical-record WRITE key, decoupled from the history-LOAD key
    // (sessionId). PERSISTENT shapes already record under sessionId below (and load their history
    // for multi-turn context). A recordsToThread shape that is NOT on the persistent path (e.g. the
    // EPHEMERAL RAG-ask shape — fresh LLM context every turn) records its CLEAN user turn + assistant
    // turn (with citations + producer calibration) under the request's conversationId, so the unified
    // thread / History / Timeline project the grounded answer + its evidence. EPHEMERAL context-load
    // semantics are untouched: threadId only writes, it never seeds initialMessages.
    String threadId = threadRecordId(shape, sessionId, body);
    EngineConversationContext ctx =
        new EngineConversationContext(
            initialMessages, audience, sessionId, shape.id().value(), body, engineContext);

    // Tempdoc 610 §J.3 — seed the conversation's hidden retrieved-source ids (the store is the source
    // of truth, mirroring per-message exclude) so RAGContext can drop them from this turn's retrieval.
    // Keyed by the conversation the FE stamps: sessionId for PERSISTENT shapes, else the threadId
    // (= body.conversationId) for EPHEMERAL recordsToThread shapes like the documents RAG-ask path —
    // which is where retrieved-source exclusion actually applies.
    String excludeKey = sessionId != null ? sessionId : threadId;
    if (excludeKey != null && conversationStore != null) {
      ctx.attributes().put(
          io.justsearch.app.services.conversation.spi.RAGContext.ATTR_EXCLUDED_SOURCES,
          conversationStore.excludedSourceIds(excludeKey));
    }

    int maxTokens = parseMaxTokens(body);
    // Tempdoc 845 — publish the effective completion reserve so context injectors budget the prompt
    // against what this turn ACTUALLY reserves. RAGContext used to assume a flat 1024, which is
    // merely the default: Thorough sends 3072, so its retrieved context over-committed the window
    // by the 2048-token difference and the request 400ed at the server. Seeded from the same
    // variable handed to streamLlm below, so the budgeting reserve cannot drift from the real one.
    ctx.attributes().put(
        io.justsearch.app.services.conversation.spi.RAGContext.ATTR_COMPLETION_RESERVE_TOKENS,
        maxTokens);
    SamplingParams sampling = applySchemaConstraint(parseSamplingParams(body), body);

    // Assemble the system prompt once per request — contributors are stateless and don't
    // re-render between iterations (their content depends on ctx but iteration is the only
    // mutable field they typically care about, and for iteration-stable contributors the
    // re-render would produce identical output).
    String systemPrompt = assembleSystemPrompt(contributors, ctx);

    String retrievalRecordId = sessionId != null ? sessionId : threadId;
    if (stoppedByUser(work)) {
      // Stopped before it began (a Stop that overtook the dispatch): nothing was rewritten,
      // retrieved or answered.
      recordStopped(retrievalRecordId, shape, body, false, Map.of(), List.of(), null, "", null, sink);
      return;
    }

    // Run injectors once per request (iteration 0 only). Each injector may emit SSE events
    // (forwarded to sink) and may declare a terminal error that aborts before the LLM call.
    InjectorRunResult injectorRun = runInjectors(injectors, ctx, sink, work);
    // Read once, after the injectors: the rewrite (if any) happened among them, and every outcome
    // row this turn writes below carries it.
    String standalone = standaloneQuestion(ctx);
    if (stoppedByUser(work)) {
      // Stopped while the question was being rewritten or its documents retrieved: nothing was
      // answered, and the question has not been recorded yet.
      recordStopped(retrievalRecordId, shape, body, false, injectorRun.retrievalMetadata,
          injectorRun.citations, standalone, "", null, sink);
      return;
    }
    if (injectorRun.terminated) {
      // A turn whose retrieval reported what it did, or whose question was rewritten (the reader saw
      // "Interpreted as" live), is saved with its outcome even though it ends here: a retrieval that
      // could not run at all reports no metadata, and the rewrite is then all that says what it was.
      if (retrievalRecordId != null && conversationStore != null
          && (!injectorRun.retrievalMetadata.isEmpty() || standalone != null || "core.extract".equals(shape.id().value()))
          && injectorRun.terminalError != null) {
        conversationStore.createSession(retrievalRecordId, shape.id().value());
        conversationStore.appendMessage(retrievalRecordId, shape.id().value(), threadUserMessage(body));
        persistRetrievalError(retrievalRecordId, shape, injectorRun.retrievalMetadata, standalone,
            String.valueOf(injectorRun.terminalError.payload().getOrDefault("error", "Retrieval failed")),
            injectorRun.terminalError.payload().get("errorCode"));
      }
      return;
    }
    ctx.appendMessages(injectorRun.messages);
    // Slice 496 §3.B: persist injected messages (which include the user's input)
    // for PERSISTENT shapes so the conversation store has the full thread.
    if (sessionId != null && conversationStore != null && !injectorRun.messages.isEmpty()) {
      conversationStore.createSession(sessionId, shape.id().value());
      for (Map<String, Object> msg : injectorRun.messages) {
        conversationStore.appendMessage(sessionId, shape.id().value(), msg);
      }
    } else if (threadId != null && conversationStore != null) {
      // Tempdoc 561 P-A/P-B: record the CLEAN user turn (the user's actual question), NOT the
      // context-augmented injector message — RAGContext injects "Documents:\n<retrieved>\n\nQuestion:
      // <q>" for the LLM, which must never become the thread's user bubble.
      conversationStore.createSession(threadId, shape.id().value());
      conversationStore.appendMessage(threadId, shape.id().value(), threadUserMessage(body));
    }

    // Tempdoc 610 §I.2/§J — per-phase token attribution for the meter breakdown + inspector. The three
    // phases are stable per request: system (contributors), conversation (the floor-trimmed effective
    // context; empty for EPHEMERAL), retrieved (the injected RAG/doc messages). Estimated via
    // TokenEstimation (which over-estimates) — the authoritative total is `promptTokens` from usage, so
    // the FE presents these as the split (scaled/≈), never false precision.
    Map<String, Object> contextBreakdown = new LinkedHashMap<>();
    contextBreakdown.put("system", TokenEstimation.estimateTokens(systemPrompt));
    // The turns a caller forwards in the request body's `context` (an EPHEMERAL ask's follow-up
    // history) are conversation, not retrieved documents, even though an injector supplies them.
    contextBreakdown.put("conversation",
        estimatePhaseTokens(initialMessages) + estimatePhaseTokens(injectorRun.forwarded));
    contextBreakdown.put("retrieved",
        estimatePhaseTokens(injectorRun.messages) - estimatePhaseTokens(injectorRun.forwarded));

    OnlineAiService ai = onlineAiSupplier.get();
    if (ai == null || !ai.isAvailable()) {
      emitError(sink, "AI service unavailable", "AI_OFFLINE");
      persistRetrievalError(retrievalRecordId, shape, injectorRun.retrievalMetadata, standalone,
          "AI service unavailable", "AI_OFFLINE");
      return;
    }

    int hardCap = shape.iterationMode() == IterationMode.ONE_SHOT ? 1 : MAX_ITERATIONS_HARD_CAP;

    // Tempdoc 834 §4.3 — open the turn marker before iteration 0, for ITERATING shapes only. The
    // assistant-turn persistence below sits INSIDE this loop, so a WITHIN_TURN_ITERATION shape
    // writes one assistant turn per iteration and a crash at iteration 3 of 8 leaves three
    // persisted turns reading as a finished answer. A ONE_SHOT ask has no such gap: it persists
    // once, complete, so a dropped ask loses nothing and needs no marker.
    String turnRecordKey = sessionId != null ? sessionId : threadId;
    if (shape.iterationMode() != IterationMode.ONE_SHOT
        && turnRecordKey != null
        && conversationStore != null) {
      try {
        conversationStore.setTurnOpen(turnRecordKey, true);
        // Only after the write succeeded — the store writes meta.json atomically, so a failure
        // leaves nothing to clear, and arming the finally anyway would just log a second failure.
        openTurnKey.set(turnRecordKey);
      } catch (RuntimeException e) {
        // The marker is diagnostic; the answer is the product. Failing to record it must not kill
        // the run (this mirrors the guard on the clearing side).
        LOG.warn("Failed to set the turn-open marker for conversation {}", turnRecordKey, e);
      }
    }

    String accumulatedFinalText = "";
    Map<String, Object> mergedDoneEntries = new LinkedHashMap<>();
    // Whether this iteration's answer is already recorded: a Stop after that changes nothing saved.
    boolean answerRecorded = false;
    // The thinking of the call whose text a late Stop saves.
    List<ReasoningTrace> stoppedReasoning = null;
    try {
      for (int i = 0; i < hardCap; i++) {
        answerRecorded = false;
        List<Map<String, Object>> llmInput = buildLlmInput(systemPrompt, ctx.messages());
        if ("core.extract".equals(shape.id().value())
            && estimatePhaseTokens(llmInput) > io.justsearch.app.services.conversation.spi.RAGContext
                .budgetFor(onlineAiSupplier, ctx).inputBudget()) {
          String reason = "Extraction input exceeds the model context budget; shorten the instruction, schema or inputs.";
          emitError(sink, reason, "INPUT_TOO_LARGE");
          persistRetrievalError(retrievalRecordId, shape, injectorRun.retrievalMetadata, standalone,
              reason, "INPUT_TOO_LARGE");
          return;
        }

        String finalText;
        AtomicReference<OnlineAiService.AiUsage> usageRef = new AtomicReference<>();
        // Tempdoc 848 §2.2 — per-`streamLlm` (therefore per-iteration) by construction: an ITERATING
        // shape's iteration-3 record carries iteration-3's thinking, with no cross-iteration bleed.
        AtomicReference<List<ReasoningTrace>> reasoningRef = new AtomicReference<>();
        try {
          finalText =
              streamLlm(
                  ai, llmInput, maxTokens, sampling, consumers, ctx, usageRef, reasoningRef, sink);
        } catch (LlmStreamException e) {
          if (stoppedByUser(work)) {
            recordStopped(retrievalRecordId, shape, body, true, injectorRun.retrievalMetadata,
                injectorRun.citations, standalone, e.partialText, e.reasoning, sink);
            return;
          }
          emitError(sink, e.getMessage(), e.errorCode);
          persistRetrievalError(retrievalRecordId, shape, injectorRun.retrievalMetadata, standalone,
              e.getMessage(), e.errorCode);
          return;
        }
        accumulatedFinalText = finalText;
        stoppedReasoning = reasoningRef.get();
        checkWork(work);

        // Stream consumers: onDone dispatch. Collect message deltas + done-payload entries.
        List<Map<String, Object>> aggregateDeltas = new ArrayList<>();
        mergedDoneEntries.clear();
        for (StreamConsumer consumer : consumers) {
          checkWork(work);
          StreamConsumerResult result;
          try {
            result = consumer.onDone(finalText, ctx);
          } catch (Exception e) {
            // A consumer cut off by the reader's Stop has not failed: the turn was stopped.
            if (stoppedByUser(work)) checkWork(work);
            LOG.warn("StreamConsumer {} onDone threw; emitting error event", consumer.id(), e);
            emitError(sink, "StreamConsumer " + consumer.id() + " failed: " + e.getMessage(), "CONSUMER_ERROR");
            persistRetrievalError(retrievalRecordId, shape, injectorRun.retrievalMetadata, standalone,
                "StreamConsumer " + consumer.id() + " failed: " + e.getMessage(), "CONSUMER_ERROR");
            return;
          }
          result.events().forEach(sink);
          aggregateDeltas.addAll(result.messageDeltas());
          mergedDoneEntries.putAll(result.donePayloadEntries());
        }
        mergedDoneEntries.putAll(injectorRun.retrievalMetadata);
        if (("core.extract".equals(shape.id().value()) || "core.summarize".equals(shape.id().value()))
            && !injectorRun.citations.isEmpty()) {
          mergedDoneEntries.put("citations", injectorRun.citations);
        }

        // Tempdoc 610 §E.4 / §G — surface the prompt-token occupancy on the done payload so the FE can
        // render a context-budget meter. The model context window (the meter's denominator) is FE-side
        // (aiState). The usage is captured per LLM call; the last iteration's value is what the meter shows.
        OnlineAiService.AiUsage usage = usageRef.get();
        if (usage != null && usage.promptTokens() != null) {
          mergedDoneEntries.put("promptTokens", usage.promptTokens());
          if (usage.totalTokens() != null) {
            mergedDoneEntries.put("totalTokens", usage.totalTokens());
          }
        }
        // Tempdoc 835 §9c.4 — completionTokens was captured on AiUsage and dropped here. It is the
        // denominator for every reasoning-budget decision (reasoning and answer tokens share this
        // number), so a turn's cost is unmeasurable without it.
        if (usage != null && usage.completionTokens() != null) {
          mergedDoneEntries.put("completionTokens", usage.completionTokens());
        }
        // Tempdoc 610 §I.2 — the per-phase split rides the done payload alongside the real total.
        mergedDoneEntries.put("contextBreakdown", contextBreakdown);

        // Keep attempts in this retry context, but persist Extract only after acceptance.
        Map<String, Object> assistantMsg = assistantMessage(finalText);
        // The turn's last answer is saved as complete below, so a Stop from here on changes nothing,
        // and says so; one that landed before carries the turn's work, and the check below sees it.
        if (running != null && i == hardCap - 1) turnStops.completing(running, work);
        checkWork(work);
        ctx.appendMessage(assistantMsg);
        ctx.appendMessages(aggregateDeltas);
        // Iteration decision.
        IterationDecision decision;
        try {
          decision = controller.next(ctx);
        } catch (Exception e) {
          LOG.warn("IterationController {} threw; treating as STOP_ERROR", controller.id(), e);
          emitError(sink, "IterationController " + controller.id() + " failed: " + e.getMessage(), "CONTROLLER_ERROR");
          persistRetrievalError(retrievalRecordId, shape, injectorRun.retrievalMetadata, standalone,
              "IterationController " + controller.id() + " failed: " + e.getMessage(), "CONTROLLER_ERROR");
          return;
        }

        boolean accepted = !"core.extract".equals(shape.id().value())
            || decision == IterationDecision.STOP_SUCCESS;
        // Slice 496 §3.B / tempdoc 561 P-A: persist the assistant message WITH its evidence (citations +
        // producer calibration from the done-payload), so the unified thread surfaces grounding FROM the
        // record, not an FE-side content re-match. Evidence is kept off the LLM-context copy so it never
        // pollutes the next prompt. PERSISTENT shapes record under sessionId; recordsToThread ephemeral
        // shapes (RAG) record under threadId (the request's conversationId).
        if (accepted && sessionId != null && conversationStore != null) {
          conversationStore.appendMessage(
              sessionId,
              shape.id().value(),
              persistedAssistant(assistantMsg, mergedDoneEntries, reasoningRef.get(), standalone));
        } else if (accepted && threadId != null && conversationStore != null) {
          conversationStore.appendMessage(
              threadId,
              shape.id().value(),
              persistedAssistant(assistantMsg, mergedDoneEntries, reasoningRef.get(), standalone));
        }
        answerRecorded = accepted;

        switch (decision) {
          case STOP_SUCCESS -> {
            checkWork(work);
            emitDone(sink, accumulatedFinalText, ctx.iteration() + 1, mergedDoneEntries);
            return;
          }
          case STOP_ERROR -> {
            String reason = String.valueOf(ctx.attributes().getOrDefault("validation.reason", "Conversation terminated with error"));
            emitError(sink, reason, "CONTROLLER_STOP");
            persistRetrievalError(retrievalRecordId, shape, injectorRun.retrievalMetadata, standalone,
                reason, "CONTROLLER_STOP");
            return;
          }
          case CONTINUE -> ctx.incrementIteration();
        }
      }

      // Reached hard cap without STOP_*
      LOG.warn("Substrate-driven shape {} reached iteration hard cap {}", shape.id().value(), hardCap);
      checkWork(work);
      if ("core.extract".equals(shape.id().value())) {
        emitError(sink, "Extraction validation retry limit reached", "CONTROLLER_STOP");
        persistRetrievalError(retrievalRecordId, shape, injectorRun.retrievalMetadata, standalone,
            "Extraction validation retry limit reached", "CONTROLLER_STOP");
      } else {
        emitDone(sink, accumulatedFinalText, hardCap, mergedDoneEntries);
      }
    } catch (io.justsearch.app.api.EngineWorkCancelledException cancelled) {
      // The reader's Stop landed after the text was produced but before the answer was recorded
      // (while its citations were checked, say): the text is saved, as stopped.
      if (answerRecorded || !stoppedByUser(work)) throw cancelled;
      recordStopped(retrievalRecordId, shape, body, true, injectorRun.retrievalMetadata,
          injectorRun.citations, standalone, accumulatedFinalText, stoppedReasoning, sink);
    }
  }

  /** Whether the turn's work was cancelled by the reader's Stop ({@link #stopTurn}), not otherwise. */
  private static boolean stoppedByUser(io.justsearch.app.api.EngineWorkHandle work) {
    return work != null && work.cancellationReason().filter(USER_STOP_REASON::equals).isPresent();
  }

  private static void checkWork(io.justsearch.app.api.EngineWorkHandle work) {
    if (work != null) work.cancellationReason().ifPresent(reason -> {
      throw new io.justsearch.app.api.EngineWorkCancelledException(reason);
    });
  }

  /**
   * Stream the LLM call. Blocks until {@code onComplete} or {@code onError} fires (via
   * CountDownLatch). Returns the full text on success; throws {@link LlmStreamException} on
   * error. Emits {@code chunk} SSE events to the sink for each streamed chunk, and dispatches
   * each chunk to all {@link StreamConsumer}s.
   */
  /** Tempdoc 610 §I.2 — estimated token sum over a phase's messages (the content field). */
  private static int estimatePhaseTokens(List<Map<String, Object>> messages) {
    int sum = 0;
    for (Map<String, Object> m : messages) {
      Object content = m.get("content");
      if (content instanceof String s) {
        sum += TokenEstimation.estimateTokens(s);
      }
    }
    return sum;
  }

  private String streamLlm(
      OnlineAiService ai,
      List<Map<String, Object>> messages,
      int maxTokens,
      SamplingParams sampling,
      List<StreamConsumer> consumers,
      EngineConversationContext ctx,
      AtomicReference<OnlineAiService.AiUsage> usageOut,
      AtomicReference<List<ReasoningTrace>> reasoningOut,
      Consumer<SseEvent> sink)
      throws LlmStreamException {

    try (var work = admission == null ? null : admission.attach(ctx.engineContext())) {
      return streamLlmOwned(ai, messages, maxTokens, sampling, consumers, ctx, usageOut,
          reasoningOut, sink, work);
    }
  }

  private String streamLlmOwned(
      OnlineAiService ai,
      List<Map<String, Object>> messages,
      int maxTokens,
      SamplingParams sampling,
      List<StreamConsumer> consumers,
      EngineConversationContext ctx,
      AtomicReference<OnlineAiService.AiUsage> usageOut,
      AtomicReference<List<ReasoningTrace>> reasoningOut,
      Consumer<SseEvent> sink,
      io.justsearch.app.api.EngineWorkHandle work) throws LlmStreamException {

    CountDownLatch latch = new CountDownLatch(1);
    StringBuilder fullText = new StringBuilder();
    AtomicReference<String> completionText = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime());
    AtomicBoolean abandoned = new AtomicBoolean(false);
    try (var _ = work == null ? null : work.onCancel(reason -> {
      abandoned.set(true);
      error.compareAndSet(null, new io.justsearch.app.api.EngineWorkCancelledException(reason));
    })) {
    // Tempdoc 848 §2.2 — accumulate reasoning exactly as `fullText` accumulates content: reasoning is
    // a property of an LLM CALL, and this is the one place a call happens, so a shape-layer
    // accumulator would need one copy per shape and would drift.
    //
    // ONE block per thinking REGION, not one per call (848 F2): a model that thinks, answers, then
    // thinks again produces two regions, and the live `ReasoningController` already cuts a block at
    // each of them (`endThinking` on the first content token after a region). Accumulating the whole
    // call into a single block would make the record disagree with the live render on block count AND
    // report a duration covering only the first region. §2.1's duration semantic is per region: first
    // reasoning token → first non-reasoning output, i.e. the next content chunk, or stream end.
    List<ReasoningTrace> reasoningBlocks = Collections.synchronizedList(new ArrayList<>());
    StringBuilder reasoningText = new StringBuilder();
    AtomicLong reasoningStartNanos = new AtomicLong(-1);

    ai.stream(
        new OnlineAiService.StreamRequest(messages, maxTokens, null, sampling, true, work),
        new OnlineAiService.StreamSink(
            chunk -> {
              if (abandoned.get()) {
                return;
              }
              lastActivityNanos.set(System.nanoTime());
              // Tempdoc 848 §2.1 — the first content token CLOSES the open thinking region, mirroring
              // `UnifiedChatView.onChunk` calling `ReasoningController.endThinking()`. Clearing the
              // start marker is what lets a later reasoning chunk open a NEW region.
              long regionStart = reasoningStartNanos.getAndSet(-1);
              if (regionStart >= 0) {
                flushReasoningRegion(reasoningBlocks, reasoningText, regionStart, System.nanoTime());
              }
              fullText.append(chunk);
              sink.accept(new SseEvent("chunk", Map.of("text", chunk)));
              for (StreamConsumer consumer : consumers) {
                try {
                  StreamConsumerResult r = consumer.onChunk(chunk, ctx);
                  r.events().forEach(sink);
                } catch (RuntimeException e) {
                  LOG.warn("StreamConsumer {} onChunk threw; continuing stream", consumer.id(), e);
                }
              }
            },
            reasoning -> {
              if (abandoned.get()) {
                return;
              }
              lastActivityNanos.set(System.nanoTime());
              reasoningStartNanos.compareAndSet(-1, System.nanoTime());
              synchronized (reasoningText) {
                reasoningText.append(reasoning);
              }
              sink.accept(new SseEvent("reasoning_chunk", Map.of("text", reasoning)));
            },
            toolDelta -> {},
            usage -> usageOut.set(usage),
            complete -> {
              completionText.set(complete);
              latch.countDown();
            },
            err -> {
              error.compareAndSet(null, err);
              latch.countDown();
            }));

    try {
      // Bounded, idle-based: a stream that is producing keeps its turn for as long as it needs, but
      // a producer that never calls back at all — the wedge shape, where the shared streaming thread
      // is parked and this dispatch is only queued behind it — dies loudly instead of parking this
      // request thread forever. The transport's own read deadline is shorter, so it normally reports
      // first and this stays a backstop.
      while (!latch.await(STALL_POLL_MS, TimeUnit.MILLISECONDS)) {
        if (System.nanoTime() - lastActivityNanos.get() >= llmStallDeadline.toNanos()) {
          abandoned.set(true);
          if (work != null) {
            work.cancel("deadline_exceeded");
            // The producer releases this latch after its active callback exits. Ending the turn
            // here would let an error overtake a chunk still being written by that callback.
            continue;
          }
          LOG.error(
              "LLM stream produced nothing for {} — abandoning the turn (producer never returned)",
              llmStallDeadline);
          throw new LlmStreamException(
              "LLM stream stalled: no output for " + llmStallDeadline.toSeconds() + "s",
              "LLM_TIMEOUT");
        }
      }
    } catch (InterruptedException e) {
      abandoned.set(true);
      if (work != null) work.cancel("caller_interrupted");
      if (work != null) {
        boolean drained = false;
        while (!drained) {
          try { latch.await(); drained = true; }
          catch (InterruptedException repeated) { /* Retain ownership until the transport terminal. */ }
        }
      }
      Thread.currentThread().interrupt();
      throw new LlmStreamException("LLM call interrupted",
          work == null ? "INTERRUPTED" : work.cancellationReason().orElseThrow());
    }

    if (error.get() != null) {
      Throwable err = error.get();
      if (err instanceof io.justsearch.app.api.EngineWorkCancelledException cancelled) {
        // What the model had thought when it was cancelled was streamed to the reader, so a stopped
        // turn keeps it, region by region, as a finished call does below.
        long openRegion = reasoningStartNanos.getAndSet(-1);
        if (openRegion >= 0) {
          flushReasoningRegion(reasoningBlocks, reasoningText, openRegion, System.nanoTime());
        }
        throw new LlmStreamException(cancelled.getMessage(), cancelled.reasonCode(), fullText.toString(),
            reasoningBlocks.isEmpty() ? null : List.copyOf(reasoningBlocks));
      }
      throw new LlmStreamException(err.getMessage() == null ? err.toString() : err.getMessage(), "LLM_ERROR",
          fullText.toString(), null);
    }

    // Tempdoc 848 §2.1 — a region still open at stream end (the turn ended on thinking, or thought
    // without ever answering) closes here; the model really produced it, so it persists like any other.
    long trailingStart = reasoningStartNanos.getAndSet(-1);
    if (trailingStart >= 0) {
      flushReasoningRegion(reasoningBlocks, reasoningText, trailingStart, System.nanoTime());
    }
    if (!reasoningBlocks.isEmpty()) {
      reasoningOut.set(List.copyOf(reasoningBlocks));
    }

    // Always return the chunk-accumulator; `completionText` is the LLM
    // finish_reason, not the response body. See the comment on the onComplete
    // callback above.
    return fullText.toString();
    }
  }

  /**
   * Close one thinking region into a block. A region that produced no text at all is not a block —
   * the persisted array says what the model actually thought, and an empty entry would claim
   * otherwise.
   */
  private static void flushReasoningRegion(
      List<ReasoningTrace> blocks, StringBuilder text, long startNanos, long endNanos) {
    synchronized (text) {
      if (text.isEmpty() || text.toString().isBlank()) {
        text.setLength(0);
        return;
      }
      blocks.add(
          new ReasoningTrace(
              text.toString(), Math.max(0L, (endNanos - startNanos) / 1_000_000L)));
      text.setLength(0);
    }
  }

  /**
   * Tempdoc 848 §2.1 — one reasoning block: one REGION of thinking and the interval it took. The
   * persisted shape is an ARRAY of these, because both frontend models are already lists
   * ({@code ReasoningController.reasoningBlocks}, {@code Sv3Turn.reasoning}) — the record is the FE's
   * existing shape, not a second one — and because one call can contain several regions.
   */
  private record ReasoningTrace(String text, long durationMs) {
    Map<String, Object> asBlock() {
      Map<String, Object> block = new LinkedHashMap<>();
      block.put("text", text);
      block.put("durationMs", durationMs);
      return block;
    }
  }

  /** Resolve all {@link PromptContributor}s referenced by the shape; missing ids throw. */
  private List<PromptContributor> resolveContributors(ConversationShape shape) {
    List<PromptContributor> out = new ArrayList<>(shape.promptContributorIds().size());
    for (String id : shape.promptContributorIds()) {
      PromptContributor c =
          promptContributors
              .findById(id)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "PromptContributor not registered: " + id + " (shape " + shape.id().value() + ")"));
      out.add(c);
    }
    return out;
  }

  private List<ContextInjector> resolveInjectors(ConversationShape shape) {
    List<ContextInjector> out = new ArrayList<>(shape.contextInjectorIds().size());
    for (String id : shape.contextInjectorIds()) {
      ContextInjector inj =
          contextInjectors
              .findById(id)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "ContextInjector not registered: " + id + " (shape " + shape.id().value() + ")"));
      out.add(inj);
    }
    return out;
  }

  private List<StreamConsumer> resolveConsumers(ConversationShape shape) {
    List<StreamConsumer> out = new ArrayList<>(shape.streamConsumerIds().size());
    for (String id : shape.streamConsumerIds()) {
      StreamConsumer sc =
          streamConsumers
              .findById(id)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "StreamConsumer not registered: " + id + " (shape " + shape.id().value() + ")"));
      out.add(sc);
    }
    return out;
  }

  /**
   * Resolve the {@link IterationController}. Defaults to {@link SingleHopController#INSTANCE}
   * for shapes whose manifest leaves {@code iterationControllerId} null (legal only for
   * {@link IterationMode#ONE_SHOT} shapes per {@link ConversationShape}'s compact constructor).
   */
  private IterationController resolveController(ConversationShape shape) {
    String id = shape.iterationControllerId();
    if (id == null) {
      return SingleHopController.INSTANCE;
    }
    return iterationControllers
        .findById(id)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "IterationController not registered: " + id + " (shape " + shape.id().value() + ")"));
  }

  /**
   * Compose the system prompt: collect each contributor's fragment, stable-sort by priority,
   * and join with double-newline. Empty fragments are filtered.
   */
  private static String assembleSystemPrompt(
      List<PromptContributor> contributors, EngineConversationContext ctx) {
    List<PromptFragment> fragments = new ArrayList<>();
    for (PromptContributor c : contributors) {
      Optional<PromptFragment> f;
      try {
        f = c.contribute(ctx);
      } catch (Exception e) {
        LOG.warn("PromptContributor {} threw; skipping fragment", c.id(), e);
        continue;
      }
      f.ifPresent(fragments::add);
    }
    // Stable sort by priority ascending; declaration order ties handled by sort stability.
    fragments.sort(Comparator.comparingInt(PromptFragment::priority));
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < fragments.size(); i++) {
      if (i > 0) {
        sb.append("\n\n");
      }
      sb.append(fragments.get(i).text());
    }
    return sb.toString();
  }

  /**
   * Run each {@link ContextInjector} in declaration order. Per substrate enhancement E2:
   *
   * <ul>
   *   <li>Each injector returns an {@link InjectorResult} carrying messages, events, and
   *       optional terminal error.
   *   <li>Events are forwarded to {@code sink} in the order injectors produce them.
   *   <li>If any injector signals a terminal error, the engine emits that error event and
   *       returns {@code terminated = true}; downstream injectors are skipped and no LLM
   *       call is made.
   *   <li>An Engine refusal terminates with a typed error before any LLM call. Other throwing
   *       injectors are logged and skipped; the conversation continues with the remaining context.
   *   <li>An injector cut off by the reader's Stop sends no error of its own: the turn ends as
   *       stopped, and that is its one terminal.
   * </ul>
   */
  private static InjectorRunResult runInjectors(
      List<ContextInjector> injectors,
      EngineConversationContext ctx,
      Consumer<SseEvent> sink,
      io.justsearch.app.api.EngineWorkHandle work) {
    List<Map<String, Object>> out = new ArrayList<>();
    List<Map<String, Object>> forwarded = new ArrayList<>();
    Map<String, Object> retrievalMetadata = new LinkedHashMap<>();
    List<Map<String, Object>> citations = new ArrayList<>();
    for (ContextInjector inj : injectors) {
      InjectorResult result;
      try {
        result = inj.inject(ctx);
      } catch (Exception e) {
        SseEvent refusal = refusalEvent(e);
        if (refusal != null) {
          if (!stoppedByUser(work)) sink.accept(refusal);
          return new InjectorRunResult(out, forwarded, true, retrievalMetadata, citations, refusal);
        }
        LOG.warn("ContextInjector {} threw; skipping injection", inj.id(), e);
        continue;
      }
      if (result == null) {
        continue;
      }
      for (SseEvent event : result.events()) {
        if ("rag.meta".equals(event.name())) {
          retrievalMetadata.clear();
          copyRetrievalFact(retrievalMetadata, "retrievalMode", event.payload().get("retrieval_mode"));
          copyRetrievalFact(retrievalMetadata, "retrievalModeReason", event.payload().get("retrieval_mode_reason"));
          copyRetrievalFact(retrievalMetadata, "retrievalModeCause", event.payload().get("retrieval_mode_cause"));
        }
        // The sources the reader is shown before any answer; a stopped turn is saved with them.
        if ("rag.citations".equals(event.name()) && event.payload().get("citations") instanceof List<?> shown) {
          citations.clear();
          for (Object citation : shown) {
            if (citation instanceof Map<?, ?> map) citations.add(stringKeyed(map));
          }
        }
        sink.accept(event);
      }
      if (result.terminalError().isPresent()) {
        if (!stoppedByUser(work)) sink.accept(result.terminalError().get());
        return new InjectorRunResult(out, forwarded, true, retrievalMetadata, citations,
            result.terminalError().get());
      }
      out.addAll(result.messages());
      if (io.justsearch.app.services.conversation.spi.ExternalContextInjector.ID.equals(inj.id())) {
        forwarded.addAll(result.messages());
      }
    }
    return new InjectorRunResult(out, forwarded, false, retrievalMetadata, citations, null);
  }

  private static Map<String, Object> stringKeyed(Map<?, ?> map) {
    Map<String, Object> copy = new LinkedHashMap<>();
    map.forEach((key, value) -> copy.put(String.valueOf(key), value));
    return copy;
  }

  /**
   * Output of {@link #runInjectors}: accumulated messages + whether any injector aborted. {@code
   * forwarded} is the subset of {@code messages} that is the caller's forwarded conversation;
   * {@code citations} the sources announced live ({@code rag.citations}).
   */
  private record InjectorRunResult(List<Map<String, Object>> messages,
      List<Map<String, Object>> forwarded, boolean terminated,
      Map<String, Object> retrievalMetadata, List<Map<String, Object>> citations,
      SseEvent terminalError) {}

  private void persistRetrievalError(String recordId, ConversationShape shape,
      Map<String, Object> retrievalMetadata, String standaloneQuestion, String message,
      Object errorCode) {
    if (recordId == null || conversationStore == null
        || (retrievalMetadata.isEmpty() && standaloneQuestion == null && !"core.extract".equals(shape.id().value()))) {
      return;
    }
    var error = new LinkedHashMap<String, Object>();
    error.put("role", "assistant");
    error.put("content", message);
    error.put("terminalError", true);
    if (errorCode instanceof String code && !code.isBlank()) error.put("errorCode", code);
    error.putAll(retrievalMetadata);
    // The "Interpreted as" note belongs to the turn whatever its outcome: a rewrite that was followed
    // by a failed retrieval, a failed generation or a Stop was still shown live, so the record keeps it.
    putIfPresent(error, "standaloneQuestion", standaloneQuestion);
    conversationStore.appendMessage(recordId, shape.id().value(), error);
  }

  /**
   * The reader's Stop, saved as the turn's outcome: the text that had streamed (none, for an early
   * Stop), the thinking and the sources the reader was shown, the rewrite note and the
   * {@link #STOPPED_DISPOSITION} the record reads as "stopped by you". An answer row rather than an
   * error row, so the turn keeps what it said and stays addressable.
   */
  private void recordStopped(String recordId, ConversationShape shape, Map<String, Object> body,
      boolean questionRecorded, Map<String, Object> retrievalMetadata,
      List<Map<String, Object>> citations, String standaloneQuestion, String text,
      List<ReasoningTrace> reasoning, Consumer<SseEvent> sink) {
    if (recordId != null && conversationStore != null) {
      if (!questionRecorded) {
        conversationStore.createSession(recordId, shape.id().value());
        conversationStore.appendMessage(recordId, shape.id().value(), threadUserMessage(body));
      }
      Map<String, Object> outcome = new LinkedHashMap<>(retrievalMetadata);
      outcome.put("citations", citations);
      outcome.put("disposition", STOPPED_DISPOSITION);
      conversationStore.appendMessage(recordId, shape.id().value(),
          persistedAssistant(assistantMessage(text), outcome, reasoning, standaloneQuestion));
    }
    emitError(sink, "Stopped by the user", STOPPED_DISPOSITION);
  }

  /**
   * The standalone question {@code QueryRewriteInjector} retrieved on, when it rewrote this turn's
   * follow-up; null when it did not (first turn, unchanged, rewrite failed, AI down).
   */
  private static String standaloneQuestion(EngineConversationContext ctx) {
    return ctx.attributes().get(
            io.justsearch.app.services.conversation.spi.QueryRewriteInjector.ATTR_STANDALONE_QUESTION)
        instanceof String s && !s.isBlank() ? s : null;
  }

  private static void copyRetrievalFact(Map<String, Object> target, String key, Object value) {
    if (value instanceof String text && !text.isBlank()) target.put(key, text);
  }

  static SseEvent refusalEvent(Throwable failure) {
    RuntimeException refused = io.justsearch.app.services.worker.EngineRefusals.find(failure);
    String code;
    int retryAfter;
    if (refused instanceof io.justsearch.app.api.EngineAdmissionException admission) {
      code = switch (admission.reason()) {
        case CONTEXT_LIMIT -> "ADMISSION_CONTEXT_LIMIT";
        case ENGINE_LIMIT -> "ADMISSION_ENGINE_LIMIT";
        case INFERENCE_BUSY -> "ADMISSION_INFERENCE_BUSY";
        case FROZEN -> "UPGRADE_PREPARING";
        case WORK_FINISHED -> "SERVICE_UNAVAILABLE";
      };
      retryAfter = admission.retryAfterSeconds();
    } else if (refused instanceof io.justsearch.core.execution.EngineExecutorRejectedException executor) {
      code = executor.reason() == io.justsearch.core.execution.EngineExecutorRejectedException.Reason.CLOSED
          ? "SERVICE_UNAVAILABLE" : "ADMISSION_ENGINE_LIMIT";
      retryAfter = executor.retryAfterSeconds();
    } else {
      return null;
    }
    return new SseEvent("error", Map.of(
        "error", refused.getMessage(), "errorCode", code, "i18nKey", "errors." + code,
        "retryAfterSeconds", retryAfter, "retrySafe", false));
  }

  /**
   * The keys the model's message contract actually defines. Everything else a context message
   * carries is persistence bookkeeping and must not reach the server.
   */
  private static final Set<String> LLM_MESSAGE_KEYS =
      Set.of("role", "content", "name", "tool_calls", "tool_call_id");

  /**
   * Build the LLM message list: system prompt as message[0] (omitted if empty), then the
   * accumulated context messages PROJECTED to the keys the model contract defines.
   *
   * <p>Tempdoc 848 §2.2b — the store is a schemaless passthrough ({@code FileConversationStore}
   * returns each persisted line whole, and {@code loadEffectiveContext} only filters by id), so a
   * PERSISTENT shape's next turn re-sends every persistence-only key of every earlier assistant
   * record inside the OpenAI-compat {@code messages} body. That was already true of {@code
   * id}/{@code hash}/{@code ts} — inert, so harmless by luck — and becomes a real defect the moment
   * a payload key is persisted: {@code reasoning} is an array in the server's reasoning namespace
   * and would inflate the prompt in proportion to conversation length. The boundary is the one
   * place a guarantee can hold for every future persisted field, so it PROJECTS to the contract's
   * keys rather than blacklisting today's known extras.
   *
   * <p>System-role context messages (the store's compaction summary, a summary the caller forwards
   * in {@code context}) are FOLDED into message[0], in order, after the system prompt. The served
   * model runs its own chat template ({@code --jinja}), and Qwen3.5's raises "System message must
   * be at the beginning." for any later system message, so the list carries one system message,
   * first. Every other message keeps its order.
   */
  private static List<Map<String, Object>> buildLlmInput(
      String systemPrompt, List<Map<String, Object>> contextMessages) {
    StringBuilder systemText = new StringBuilder(systemPrompt);
    List<Map<String, Object>> rest = new ArrayList<>(contextMessages.size());
    for (Map<String, Object> message : contextMessages) {
      if ("system".equals(message.get("role"))) {
        String text = Objects.toString(message.get("content"), "");
        if (!text.isBlank()) {
          if (!systemText.isEmpty()) systemText.append("\n\n");
          systemText.append(text);
        }
      } else {
        rest.add(projectForLlm(message));
      }
    }
    List<Map<String, Object>> out = new ArrayList<>(rest.size() + 1);
    if (!systemText.isEmpty()) {
      Map<String, Object> system = new LinkedHashMap<>();
      system.put("role", "system");
      system.put("content", systemText.toString());
      out.add(system);
    }
    out.addAll(rest);
    return out;
  }

  private static Map<String, Object> projectForLlm(Map<String, Object> message) {
    Map<String, Object> projected = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : message.entrySet()) {
      if (LLM_MESSAGE_KEYS.contains(entry.getKey())) {
        projected.put(entry.getKey(), entry.getValue());
      }
    }
    return projected;
  }

  private static Map<String, Object> assistantMessage(String text) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("role", "assistant");
    m.put("content", text);
    return m;
  }

  /**
   * The history-LOAD key: the request's {@code sessionId} for PERSISTENT shapes, {@code null} for
   * EPHEMERAL ones (which start from a fresh context every turn). Also the primary write key —
   * a PERSISTENT shape records its turns under it.
   */
  private static String sessionIdFor(ConversationShape shape, Map<String, Object> body) {
    return shape.persistenceMode() == io.justsearch.agent.api.conversation.PersistenceMode.PERSISTENT
        ? (String) body.get("sessionId")
        : null;
  }

  /**
   * Tempdoc 561 P-A/P-B — the canonical-record WRITE key for a recordsToThread shape that is NOT on
   * the persistent (sessionId) path. Returns the request's {@code conversationId} for EPHEMERAL
   * recordsToThread shapes (e.g. RAG ask) so their turns land on the unified thread; returns null for
   * persistent shapes (they record under sessionId), shapes that opt out of the thread, blank ids, and
   * internal throwaway sessions (auto-title generation).
   */
  private static String threadRecordId(
      ConversationShape shape, String sessionId, Map<String, Object> body) {
    if (sessionId != null) {
      return null; // PERSISTENT shapes already record under sessionId
    }
    if (!shape.recordsToThread()) {
      return null;
    }
    Object cid = body.get("conversationId");
    if (!(cid instanceof String s) || s.isBlank()) {
      return null;
    }
    if (s.startsWith(
        io.justsearch.agent.api.conversation.ConversationStore.THROWAWAY_SESSION_PREFIX)) {
      return null;
    }
    return s;
  }

  /**
   * Tempdoc 561 P-A/P-B — the CLEAN user turn for the thread: the user's actual input, taken from the
   * shape's input field, NOT the context-augmented injector message (RAGContext rewrites the user
   * message into a documents+question blob for the LLM, which must never be the thread's user bubble).
   */
  private static Map<String, Object> threadUserMessage(Map<String, Object> body) {
    String text =
        firstNonBlank(
            body.get("question"), body.get("prompt"), body.get("message"), body.get("text"));
    // Tempdoc 863 §4.A.2 — the agent body names its input `messages` (OpenAI shape), not one of the
    // four scalar fields the ask shapes post. Read the last `role:"user"` entry rather than widening
    // the wire contract: the agent dispatch already sends exactly this, and the LAST user entry is
    // the turn being asked (an earlier one is prior history the FE replayed).
    if (text == null) {
      text = lastUserContent(body.get("messages"));
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("role", "user");
    m.put("content", text == null ? "" : text);
    return m;
  }

  /** The content of the last {@code role:"user"} entry of an OpenAI-shaped message list. */
  private static String lastUserContent(Object messages) {
    if (!(messages instanceof List<?> list)) {
      return null;
    }
    for (int i = list.size() - 1; i >= 0; i--) {
      if (list.get(i) instanceof Map<?, ?> msg
          && "user".equals(msg.get("role"))
          && msg.get("content") instanceof String content
          && !content.isBlank()) {
        return content;
      }
    }
    return null;
  }

  /**
   * Tempdoc 561 P-A — the assistant turn persisted to the canonical record, with its evidence
   * (citations + producer-owned calibration) attached from the done-payload. Evidence is on this
   * persisted copy only, never on the LLM-context copy, so it cannot pollute the next prompt.
   */
  private static Map<String, Object> persistedAssistant(
      Map<String, Object> assistantMsg,
      Map<String, Object> mergedDoneEntries,
      List<ReasoningTrace> reasoning,
      String standaloneQuestion) {
    Map<String, Object> persisted = new LinkedHashMap<>(assistantMsg);
    // Tempdoc 863 §4.A.4 (A-3) — "present ⇒ carried, absent ⇒ no key", where EMPTY counts as absent.
    // The agent `done` payload always writes `sources`/`citations` keys (empty lists when nothing
    // matched), so a plain null-check would persist `citations: []` — a claimed ZERO where the honest
    // answer is "never told". `InteractionThreadController.chatTurn` already drops empties at the
    // wire, so this keeps the record saying what the wire says.
    putIfPresent(persisted, "citations", mergedDoneEntries.get("citations"));
    putIfPresent(persisted, "calibration", mergedDoneEntries.get("calibration"));
    // Tempdoc 863 §4.A.4 — the three attributes the run plane carried and the store plane did not.
    // Suppressing the run-plane `done` for a stamped run (A-2) drops them from that plane, so the
    // record has to carry them or a delegate answer would lose its Sources pane, its scorer stamp and
    // its truncation disclosure on reload. `calibration` and `claimMatches` stay honestly ABSENT for
    // an agent payload — the agent `done` does not produce them, and a zero would not be the truth.
    // WARNING (863 §4.A.5 F6) — `sources` is not just an attribute here, it is the FE's PLANE
    // DISCRIMINATOR: `sv3-record.ts`'s recordEvidenceOf keys on `Array.isArray(a.sources)` to decide
    // whether a persisted answer's `citations` are `AgentSentenceCite`s (action plane) or
    // `RetrievalCitation`s (answer plane), and `UnifiedChatView` casts on the same basis. Today only
    // the agent `done` supplies it, so the discriminator holds. The FIRST substrate shape whose
    // consumer emits a `sources` done-entry silently reinterprets every RAG turn's citations as
    // sentence-cites — the 859 §5a class, which produced a confident wrong number rather than an
    // error. Adding such an entry means giving the record an explicit plane tag first.
    putIfPresent(persisted, "sources", mergedDoneEntries.get("sources"));
    putIfPresent(persisted, "citationScorer", mergedDoneEntries.get("citationScorer"));
    putIfPresent(persisted, "disposition", mergedDoneEntries.get("disposition"));
    // Tempdoc 561 P-A (evidence non-divergence): persist the per-claim grounding (sentence->chunk
    // matches the Worker's cross-encoder produced) so a reloaded conversation renders the inline
    // per-claim marks FROM the record, matching the live render (the two paths cannot diverge).
    putIfPresent(persisted, "claimMatches", mergedDoneEntries.get("claimMatches"));
    for (String key : List.of("retrievalMode", "retrievalModeReason", "retrievalModeCause")) {
      putIfPresent(persisted, key, mergedDoneEntries.get(key));
    }
    // Tempdoc 848 §2.1/§2.2 — the turn's thinking, as an ordered array of blocks. Passed as an
    // EXPLICIT argument rather than through `mergedDoneEntries`, because those entries also ship on
    // the `done` SSE payload: routing reasoning through them would re-send on the wire what already
    // streamed chunk-by-chunk. Absent (no key) when the model did not think — "no key" is the honest
    // reading for a non-thinking preset, and an empty array would claim otherwise.
    if (reasoning != null && !reasoning.isEmpty()) {
      persisted.put("reasoning", reasoning.stream().map(ReasoningTrace::asBlock).toList());
    }
    // The follow-up's rewritten question, so the "Interpreted as" note survives a reload. Persisted
    // only — the live reader already got it on `rag.rewrite`, so it stays off the `done` payload.
    putIfPresent(persisted, "standaloneQuestion", standaloneQuestion);
    return persisted;
  }

  /** Carry a done-payload entry onto the record when it says something — empty is not a fact. */
  private static void putIfPresent(Map<String, Object> target, String key, Object value) {
    if (value == null) {
      return;
    }
    if (value instanceof String s && s.isBlank()) {
      return;
    }
    if (value instanceof java.util.Collection<?> c && c.isEmpty()) {
      return;
    }
    if (value instanceof Map<?, ?> m && m.isEmpty()) {
      return;
    }
    target.put(key, value);
  }

  private static String firstNonBlank(Object... values) {
    for (Object v : values) {
      if (v instanceof String s && !s.isBlank()) {
        return s;
      }
    }
    return null;
  }

  private static int parseMaxTokens(Map<String, Object> body) {
    Object raw = body.get("maxTokens");
    if (raw instanceof Number n) {
      int v = n.intValue();
      return v > 0 ? v : DEFAULT_MAX_TOKENS;
    }
    return DEFAULT_MAX_TOKENS;
  }

  private static SamplingParams parseSamplingParams(Map<String, Object> body) {
    Object raw = body.get("enableThinking");
    if (raw instanceof Boolean b) {
      return new SamplingParams(0.8, 0.95, null, null, b);
    }
    return null;
  }

  /**
   * Slice 496 §3.C hardening (tempdoc 569 Phase 5) — when a substrate-driven request declares a
   * JSON {@code schema} (the {@code ExtractShape} contract — its {@code ValidationConsumer} already
   * reads {@code body["schema"]} for the correction prompt), promote that schema to a server-side
   * {@code response_format} constraint so the FIRST iteration is schema-valid by construction
   * (llama-server converts schema→GBNF internally; {@code OnlineModeOps} applies it). This turns the
   * generative-authoring path from "emit-then-validate-retry" into "constrained-emit", with the
   * existing validate-retry loop kept as the safety net for any backend that ignores the constraint.
   *
   * <p>The schema arrives as a JSON STRING (the caller posts {@code schema: JSON.stringify(...)}).
   * If it is absent, blank, or unparseable, the sampling params are returned unchanged — the
   * validate-retry loop still guards correctness, so a bad schema degrades rather than fails.
   *
   * <p>Structured extraction wants near-deterministic, non-thinking output; when the caller supplied
   * no explicit sampling we base the constrained params on {@link SamplingParams#DETERMINISTIC},
   * which carries {@code enable_thinking:false} for every mechanical call (tempdoc 835 §10f).
   */
  private static SamplingParams applySchemaConstraint(
      SamplingParams sampling, Map<String, Object> body) {
    if (!(body.get("schema") instanceof String schemaJson) || schemaJson.isBlank()) {
      return sampling;
    }
    final Map<String, Object> schemaMap;
    try {
      schemaMap = SCHEMA_MAPPER.readValue(schemaJson, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      LOG.warn(
          "Request declared a schema but it did not parse as JSON; falling back to validate-retry"
              + " only (response_format not applied): {}",
          e.getMessage());
      return sampling;
    }
    SamplingParams base = sampling != null ? sampling : SamplingParams.DETERMINISTIC;
    // The codebase-standard schema→GBNF response_format form (QueryUnderstandingService +
    // OnlineModeOps' non-streaming path): a response_format-enforcing llama.cpp build converts this to
    // a server-side grammar so the FIRST emission is schema-valid. On a build that does NOT enforce it,
    // the ExtractShape's validate-retry loop + the FE self-repair remain the correctness safety net
    // (live-verified: every run yields valid, schema-conforming JSON regardless).
    return base.withResponseFormat(Map.of("type", "json_object", "schema", schemaMap));
  }

  /**
   * Emit the substrate-default {@code done} event with consumer-contributed enrichment.
   *
   * <p>Per substrate enhancement E1: {@code consumerEntries} are merged in first (consumers'
   * declaration order is preserved via the caller's LinkedHashMap), then the substrate
   * defaults are written last so they cannot be overridden by consumers.
   */
  private static void emitDone(
      Consumer<SseEvent> sink,
      String finalText,
      int iterationsUsed,
      Map<String, Object> consumerEntries) {
    Map<String, Object> payload = new LinkedHashMap<>();
    if (consumerEntries != null) {
      payload.putAll(consumerEntries);
    }
    payload.put("finalResponse", finalText);
    payload.put("iterationsUsed", iterationsUsed);
    sink.accept(new SseEvent("done", payload));
  }

  private static void emitError(Consumer<SseEvent> sink, String message, String errorCode) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("error", message);
    payload.put("errorCode", errorCode);
    payload.put("i18nKey", "errors." + errorCode);
    sink.accept(new SseEvent("error", payload));
  }

  /** Thrown when {@link #run} is invoked with an unregistered shape id. */
  public static final class ShapeNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ShapeNotFoundException(String message) {
      super(message);
    }
  }

  /** Thrown when the request's audience is insufficient for the shape. */
  public static final class AudienceDeniedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AudienceDeniedException(String message) {
      super(message);
    }
  }

  /** Internal exception used to communicate stream-call failures. */
  private static final class LlmStreamException extends Exception {
    private static final long serialVersionUID = 1L;
    final String errorCode;
    /** The text that had streamed when the call ended; a stopped turn keeps it. */
    final String partialText;
    /** The thinking that had streamed when the call was cancelled, or null; a stopped turn keeps it. */
    final transient List<ReasoningTrace> reasoning;

    LlmStreamException(String message, String errorCode) {
      this(message, errorCode, "", null);
    }

    LlmStreamException(
        String message, String errorCode, String partialText, List<ReasoningTrace> reasoning) {
      super(message);
      this.errorCode = errorCode;
      this.partialText = partialText;
      this.reasoning = reasoning;
    }
  }
}
