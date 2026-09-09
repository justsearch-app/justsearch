/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentErrorClass;
import io.justsearch.agent.api.AgentErrorCode;
import io.justsearch.agent.api.AgentProfile;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.RetryAction;
import io.justsearch.agent.api.RunObservation;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.ToolCallRequest;
import io.justsearch.agent.api.TraceContext;
import io.justsearch.agent.api.interaction.InteractionEvent;
import io.justsearch.agent.api.interaction.InteractionEventKind;
import io.justsearch.agent.api.lifecycle.LifecycleState;
import io.justsearch.agent.api.registry.AgentToolEmitter;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.RecoveryAction;
import io.justsearch.agent.api.registry.ResolutionRecoveryPolicy;
import io.justsearch.agent.api.registry.ResolutionResult;
import io.justsearch.agent.api.registry.BackendIntentRouter;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.tools.FileOperationLog;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.telemetry.Telemetry;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements the agent loop: send prompt with tools to llama-server, parse tool calls, dispatch to
 * tool handlers, and feed results back.
 */
public final class AgentLoopService implements AgentService {
  private static final Logger LOG = LoggerFactory.getLogger(AgentLoopService.class);
  // Lazy lookup via getTracer() instead of static final Tracer — a static field captures
  // the noop tracer at class-load time, before TracingBootstrap registers the real SDK,
  // making agent spans untestable. The per-call lookup cost is negligible (ConcurrentHashMap).
  // Package-private: AgentLlmCaller (tempdoc 240 W4) reuses this scope so chat + tool spans
  // share one tracer name.
  static final String TRACER_SCOPE = "io.justsearch.agent.AgentLoopService";

  /** Reads an int field from ConfigStore if available, falling back to {@code fallback}. */
  private static int resolveFromConfig(ToIntFunction<ResolvedConfig> extractor, int fallback) {
    ConfigStore cs = ConfigStore.globalOrNull();
    return cs != null ? extractor.applyAsInt(cs.get()) : fallback;
  }

  /** Reads a boolean field from ConfigStore if available, falling back to {@code fallback}. */
  private static boolean resolveFromConfig(
      Function<ResolvedConfig, Boolean> extractor, boolean fallback) {
    ConfigStore cs = ConfigStore.globalOrNull();
    return cs != null ? extractor.apply(cs.get()) : fallback;
  }

  /**
   * Direction I: GBNF grammar enforcing Hermes 2 Pro tool-call format on forced-tool-call turns.
   *
   * <p>Applied alongside {@code tool_choice="required"} as a belt-and-suspenders layer. When the
   * server supports both grammar and tools simultaneously (Hermes 2 Pro / Qwen3), this prevents
   * malformed JSON even if the model samples unusual token sequences. When the server does not
   * support grammar+tools (HTTP 400), {@link OnlineModeOps} silently omits the grammar field and
   * the {@code tool_choice} enforcement alone is used.
   */
  static final String TOOL_CALL_GRAMMAR =
      """
      ws          ::= [ \\t\\n\\r]*
      string      ::= "\\"" characters "\\""
      characters  ::= character*
      character   ::= [^"\\\\] | "\\\\" escape_char
      escape_char ::= "\\"" | "\\\\" | "/" | "b" | "f" | "n" | "r" | "t" | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]
      number      ::= "-"? ("0" | [1-9] [0-9]*) ("." [0-9]+)? ([eE] [+-]? [0-9]+)?
      object      ::= "{" ws (pair (ws "," ws pair)*)? ws "}"
      pair        ::= string ws ":" ws value
      array       ::= "[" ws (value (ws "," ws value)*)? ws "]"
      value       ::= string | number | object | array | "true" | "false" | "null"
      root        ::= "<tool_call>" "\\n" call "\\n" "</tool_call>"
      call        ::= "{" ws "\\"name\\"" ws ":" ws string ws "," ws "\\"arguments\\"" ws ":" ws object ws "}"
      """;

  // Tempdoc 584 §B.4: DEFAULT_SYSTEM_PROMPT + the prompt-composition concerns moved to
  // AgentPromptComposer. TOOL_CALL_GRAMMAR (above) stays here — it is shared cross-collaborator
  // infra referenced by AgentLlmCaller / AgentStepRunner, not prompt-composition state.

  private final OnlineAiService onlineAiService;
  private final OperationCatalog operationCatalog;
  private final OperationDispatcher operationExecutor;
  /**
   * Slice 487 Phase 1.7: setter-injected by {@code HeadAssembly} after
   * construction. When non-null, {@link AgentToolDispatcher#dispatchToolCall}
   * (resolved lazily via the router supplier passed to the dispatcher) routes the
   * tool-call dispatch through the {@link BackendIntentRouter} (preserving the
   * trust-lattice's per-step re-validation) rather than calling
   * {@link OperationDispatcher#dispatch} directly. When null, the legacy
   * direct-dispatch path runs — preserved for test wiring that doesn't construct
   * the full intent substrate.
   */
  private volatile BackendIntentRouter backendIntentRouter;
  // Tempdoc 550 C2 step 2: late-bound consent-capsule authority (mirrors
  // backendIntentRouter). Set by AgentLoopWiring after construction; the tool dispatcher
  // resolves it per-call via a supplier and mints a bound capsule for approved tool calls.
  private volatile io.justsearch.agent.api.registry.ConsentCapsuleAuthority consentCapsuleAuthority;
  // Tempdoc 561 P-D1: late-bound read-only window onto the ONE intent-gate authority
  // (IntentGateEvaluator). Set by AgentLoopWiring after construction; the tool dispatcher resolves
  // it per-call via a supplier and stamps the backend GateBehavior onto the pending-approval event.
  private volatile io.justsearch.agent.api.registry.IntentPreviewer intentPreviewer;
  // Tempdoc 834 §1.3 — the observation substrate a run is journaled and observed through. Late-bound
  // by the composition root; NONE until then (see setRunObservation).
  private volatile RunObservation runObservation = RunObservation.NONE;
  /** The shape a native agent run is journaled under. */
  private static final String RUN_SHAPE_ID = "core.agent-run";
  /**
   * Owner decision 2026-08-26 — the durable note {@link #recordStopRequest} writes. The reader's own
   * act, in their own terms: not "cancelled" (which words their decision as a malfunction) and not a
   * verdict on the run, which may well have finished in the same instant.
   */
  private static final String STOP_REQUESTED_NOTE = "You stopped this run";
  private final AgentToolEmitter agentToolEmitter;
  private final FileOperationLog fileOperationLog; // nullable
  // Tempdoc 584 §B.4: prompt assembly (the indexed-root preamble + the slice-447 condition-recovery
  // context) lives in AgentPromptComposer. The loop holds it and delegates buildSystemPrompt() and
  // setConditionContextSupplier().
  private final AgentPromptComposer promptComposer;
  private final AgentRunStore runStore;
  private final AgentTelemetry agentTelemetry;
  private final AgentSessionTerminationObserver terminationObserver;
  private final AgentContextCompressor compressor;
  private final AgentLlmCaller llmCaller;
  private final AgentToolDispatcher toolDispatcher;
  private final AgentSessionFinalizer finalizer;
  private final AgentStepRunner stepRunner;
  // Tempdoc 584 §B.4: the read-time query/projection + resume surface lives in AgentRunQueryService;
  // the loop holds it and delegates the AgentRunQueries methods to it.
  private final AgentRunQueryService queries;
  // Tempdoc 584 §B.4: the live-run session map + per-run control surface lives in
  // AgentSessionRegistry; the loop registers/evicts at run start/end and delegates the control verbs.
  private final AgentSessionRegistry sessionRegistry = new AgentSessionRegistry();

  public AgentLoopService(
      OnlineAiService onlineAiService,
      OperationCatalog operationCatalog,
      OperationDispatcher operationExecutor,
      AgentToolEmitter agentToolEmitter,
      FileOperationLog fileOperationLog,
      Function<EngineContext, List<String>> rootPathsSupplier) {
    this(
        onlineAiService,
        operationCatalog,
        operationExecutor,
        agentToolEmitter,
        fileOperationLog,
        rootPathsSupplier,
        null,
        null);
  }

  public AgentLoopService(
      OnlineAiService onlineAiService,
      OperationCatalog operationCatalog,
      OperationDispatcher operationExecutor,
      AgentToolEmitter agentToolEmitter,
      FileOperationLog fileOperationLog,
      Function<EngineContext, List<String>> rootPathsSupplier,
      AgentRunStore runStore,
      Telemetry telemetry) {
    this(
        onlineAiService,
        operationCatalog,
        operationExecutor,
        agentToolEmitter,
        fileOperationLog,
        rootPathsSupplier,
        runStore,
        telemetry,
        // Default supplier: this constructor doesn't get a holder reference, so the
        // agent.session.active_count gauge reports 0. Production wiring uses the
        // longer constructor below to thread through a live supplier.
        () -> 0,
        // Tempdoc 430 Phase 7: this back-compat constructor doesn't wire the
        // health-event observer; callers needing the agent.session.* events use
        // the longer constructor below.
        AgentSessionTerminationObserver.NOOP);
  }

  /**
   * Production constructor (tempdoc 415 + 429 Phase 10): threads through a live
   * {@code activeSessionSupplier} so the {@code agent.session.active_count} gauge reports
   * the running service's session count. {@code HeadAssembly} captures a holder array
   * and passes {@code () -> holder[0].activeSessionCount()} so the supplier resolves once
   * the service is constructed.
   *
   * <p>Tempdoc 430 Phase 7: accepts an optional {@link AgentSessionTerminationObserver}
   * called once per session at terminal disposition (alongside the existing
   * {@link AgentTelemetry#recordSessionEnd} emit). Defaults to
   * {@link AgentSessionTerminationObserver#NOOP} when null.
   */
  public AgentLoopService(
      OnlineAiService onlineAiService,
      OperationCatalog operationCatalog,
      OperationDispatcher operationExecutor,
      AgentToolEmitter agentToolEmitter,
      FileOperationLog fileOperationLog,
      Function<EngineContext, List<String>> rootPathsSupplier,
      AgentRunStore runStore,
      Telemetry telemetry,
      java.util.function.IntSupplier activeSessionSupplier,
      AgentSessionTerminationObserver terminationObserver) {
    this(
        onlineAiService,
        operationCatalog,
        operationExecutor,
        agentToolEmitter,
        fileOperationLog,
        rootPathsSupplier,
        runStore,
        // Tempdoc 417 Phase 2d: AgentTelemetry now wraps typed catalogs. When telemetry is a
        // LocalTelemetry, build catalogs against its registry; otherwise noop.
        telemetry instanceof io.justsearch.telemetry.LocalTelemetry lt
            ? new AgentTelemetry(
                new AgentMetricCatalog(lt.registry(), activeSessionSupplier),
                new GenAiMetricCatalog(lt.registry()))
            : AgentTelemetry.noop(),
        terminationObserver,
        Boolean.TRUE); // marker — selects the AgentTelemetry-taking private constructor
  }

  /**
   * Test factory: constructs an {@link AgentLoopService} with the supplied {@link
   * AgentTelemetry} (typically backed by a {@code TestMetricRegistry}) instead of routing
   * through {@link Telemetry} casting.
   */
  static AgentLoopService forTesting(
      OnlineAiService onlineAiService,
      OperationCatalog operationCatalog,
      OperationDispatcher operationExecutor,
      AgentToolEmitter agentToolEmitter,
      FileOperationLog fileOperationLog,
      Function<EngineContext, List<String>> rootPathsSupplier,
      AgentRunStore runStore,
      AgentTelemetry agentTelemetry) {
    return forTesting(
        onlineAiService,
        operationCatalog,
        operationExecutor,
        agentToolEmitter,
        fileOperationLog,
        rootPathsSupplier,
        runStore,
        agentTelemetry,
        AgentSessionTerminationObserver.NOOP);
  }

  /**
   * Test factory variant accepting an explicit {@link AgentSessionTerminationObserver} for the
   * tempdoc 430 Phase 7 health-event emitter integration tests.
   */
  static AgentLoopService forTesting(
      OnlineAiService onlineAiService,
      OperationCatalog operationCatalog,
      OperationDispatcher operationExecutor,
      AgentToolEmitter agentToolEmitter,
      FileOperationLog fileOperationLog,
      Function<EngineContext, List<String>> rootPathsSupplier,
      AgentRunStore runStore,
      AgentTelemetry agentTelemetry,
      AgentSessionTerminationObserver terminationObserver) {
    return new AgentLoopService(
        onlineAiService,
        operationCatalog,
        operationExecutor,
        agentToolEmitter,
        fileOperationLog,
        rootPathsSupplier,
        runStore,
        agentTelemetry,
        terminationObserver,
        // marker for the private constructor below
        Boolean.TRUE);
  }


  /**
   * Private constructor used by {@link #forTesting} — distinct signature (extra unused {@code
   * Boolean marker}) so it doesn't clash with the public {@code (... Telemetry)} overload.
   */
  private AgentLoopService(
      OnlineAiService onlineAiService,
      OperationCatalog operationCatalog,
      OperationDispatcher operationExecutor,
      AgentToolEmitter agentToolEmitter,
      FileOperationLog fileOperationLog,
      Function<EngineContext, List<String>> rootPathsSupplier,
      AgentRunStore runStore,
      AgentTelemetry agentTelemetry,
      AgentSessionTerminationObserver terminationObserver,
      @SuppressWarnings("unused") Boolean marker) {
    this.onlineAiService = onlineAiService;
    this.operationCatalog = Objects.requireNonNull(operationCatalog, "operationCatalog");
    this.operationExecutor = Objects.requireNonNull(operationExecutor, "operationExecutor");
    this.agentToolEmitter = Objects.requireNonNull(agentToolEmitter, "agentToolEmitter");
    this.fileOperationLog = fileOperationLog;
    this.promptComposer = new AgentPromptComposer(rootPathsSupplier);
    this.runStore = runStore != null ? runStore : AgentRunStore.noop();
    this.agentTelemetry = agentTelemetry != null ? agentTelemetry : AgentTelemetry.noop();
    this.terminationObserver =
        terminationObserver != null ? terminationObserver : AgentSessionTerminationObserver.NOOP;
    this.compressor =
        new AgentContextCompressor(
            resolveFromConfig(rc -> rc.agent().contextCompressionEnabled(), true),
            Math.max(1, resolveFromConfig(rc -> rc.agent().contextCompressionMinChars(), 200)),
            Math.max(0, resolveFromConfig(rc -> rc.agent().contextCompressionKeepLastResults(), 0)),
            // Tempdoc 883 decision 3 — the Layer-2 cap is read from the LIVE window per truncation,
            // not frozen at class-init. The supplier, not a value, is what makes that true.
            () -> AgentContextBudgets.forCall(this.onlineAiService));
    this.llmCaller = new AgentLlmCaller(this.onlineAiService, this.agentTelemetry, this.compressor);
    this.toolDispatcher =
        new AgentToolDispatcher(
            this.operationExecutor,
            this.agentTelemetry,
            () -> this.backendIntentRouter,
            () -> this.consentCapsuleAuthority,
            () -> this.intentPreviewer);
    this.finalizer =
        new AgentSessionFinalizer(this.agentTelemetry, this.terminationObserver, this.runStore);
    this.stepRunner =
        new AgentStepRunner(
            this.onlineAiService,
            this.operationCatalog,
            this.agentToolEmitter,
            this.agentTelemetry,
            this.runStore,
            this.compressor,
            this.llmCaller,
            this.toolDispatcher,
            this::checkpoint,
            this::emitError,
            this::swapSystemPrompt);
    this.queries =
        new AgentRunQueryService(
            this.runStore,
            this.operationCatalog,
            this.operationExecutor,
            this.fileOperationLog,
            this::emitError,
            (req, sink, engineContext) -> runAgent(req, sink, engineContext));
  }

  /**
   * Tempdoc 834 §1.3 — installs the run observation substrate. Idempotent and late-bindable: the
   * agent is constructed lazily when AI activates, so the composition root sets this on whichever
   * instance it resolves rather than once at boot (the {@code standalone-capability-stays-stuck}
   * trap). Until it is set, {@link RunObservation#NONE} applies and a run is UNOBSERVABLE but not
   * mis-reported as unwatched.
   */
  @Override
  public void setRunObservation(RunObservation observation) {
    this.runObservation = observation == null ? RunObservation.NONE : observation;
  }

  /**
   * Slice 447 §X.11.5 Phase 5: wires the agent retrospection consumer onto the prompt composer.
   * The supplier is called at prompt-construction time; its returned String is appended to the
   * system prompt. Pass {@code null} to disable. Tempdoc 584 §B.4: delegates to
   * {@link AgentPromptComposer}.
   */
  public void setConditionContextSupplier(Supplier<String> supplier) {
    promptComposer.setConditionContextSupplier(supplier);
  }

  /**
   * Slice 487 Phase 1.7: wire the backend intent router. Called by
   * {@code HeadAssembly} after construction. When non-null, the agent's
   * tool-call dispatch flows through the intent layer (trust lattice, audit,
   * unified observability) rather than calling {@code OperationDispatcher} directly.
   */
  /** Tempdoc 550 C2 step 2: late-bind the shared consent-capsule authority (mint side). */
  public void setConsentCapsuleAuthority(
      io.justsearch.agent.api.registry.ConsentCapsuleAuthority authority) {
    this.consentCapsuleAuthority = authority;
  }

  public void setBackendIntentRouter(BackendIntentRouter router) {
    this.backendIntentRouter = router;
  }

  /**
   * Tempdoc 561 P-D1: late-bind the intent-gate previewer (backed by IntentGateEvaluator) so the
   * pending-approval event carries the backend's authoritative GateBehavior.
   */
  public void setIntentPreviewer(io.justsearch.agent.api.registry.IntentPreviewer previewer) {
    this.intentPreviewer = previewer;
  }


  /** Creates handoff tool descriptors for all agent profiles except the active one. */

  /** Swaps the first system message for the new agent's system prompt. */
  private void swapSystemPrompt(AgentSession session, AgentProfile toProfile) {
    String basePrompt = toProfile.systemPrompt() != null
        ? toProfile.systemPrompt()
        : promptComposer.buildSystemPrompt(session.engineContext());
    String newPrompt = basePrompt;
    List<Map<String, Object>> messages = session.messages();
    if (!messages.isEmpty() && "system".equals(messages.get(0).get("role"))) {
      messages.set(0, Map.of("role", "system", "content", newPrompt));
    } else {
      messages.add(0, Map.of("role", "system", "content", newPrompt));
    }
  }


  @Override
  public void runAgent(AgentRequest request, Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
    runAgent(request, eventConsumer, false, engineContext);
  }

  @Override
  public void runAgent(AgentRequest request, Consumer<AgentEvent> eventConsumer, boolean background,
      EngineContext engineContext) {
    try (var work = engineAdmission == null ? null : engineAdmission.attach(engineContext)) {
      runAgentOwned(request, eventConsumer, background,
          work == null ? engineContext : work.context(), work);
    }
  }

  private volatile io.justsearch.app.api.EngineAdmissionService engineAdmission;

  /** Composition supplies the Engine's one admission owner before exposing this lazy service. */
  public void setEngineAdmission(io.justsearch.app.api.EngineAdmissionService admission) {
    this.engineAdmission = admission;
  }

  private void runAgentOwned(AgentRequest request, Consumer<AgentEvent> eventConsumer, boolean background,
      EngineContext engineContext, io.justsearch.app.api.EngineWorkHandle work) {
    String sessionId = UUID.randomUUID().toString();

    // Calculate initial token budget
    int contextWindow =
        onlineAiService.llmContextTokens() != null
            ? onlineAiService.llmContextTokens()
            : onlineAiService.configuredContextTokens();
    // Tempdoc 859 §D §2.1 — the run's ECONOMIC budget is the context window times the EFFORT rung's
    // multiplier, not one context window. The former `safetyMargin = 256` is gone: it was standing in
    // for "leave room for the response", a job the between-step gate already does properly by
    // projecting the real next prompt against the remaining budget (AgentStepRunner).
    int initialBudget =
        AgentBudgetPolicy.initialBudget(request.effort(), contextWindow, background);

    // Resolve effective initial agent ID: explicit > first profile > "primary"
    String effectiveAgentId = request.initialAgentId();
    if (effectiveAgentId == null && !request.agentProfiles().isEmpty()) {
      effectiveAgentId = request.agentProfiles().get(0).agentId();
    }
    var session = new AgentSession(request.messages(), initialBudget, effectiveAgentId,
        new EngineContext(engineContext.clientKind(), engineContext.clientId(),
            java.util.Optional.of(sessionId), engineContext.grantReference(), engineContext.sourceTier(),
            engineContext.transport(), engineContext.survival(), engineContext.urgency(), engineContext.workId()), work);
    var cancellation = work == null ? null : work.onCancel(reason -> session.cancelFromWork());
    try {
    // Tempdoc 577 §2.14 Root II (#14) — carry the model's context window (n_ctx) onto the session so
    // each budget event can report cognitive headroom (promptTokens ÷ n_ctx) beside the economic budget.
    session.contextWindow(contextWindow);
    // Tempdoc 561 P-D: the user's autonomy dial rides the request; the ONE backend issuance policy
    // (IntentGateEvaluator.agentGate) reads it so the FE obeys the verdict instead of re-deriving.
    session.setAutonomyLevel(
        io.justsearch.agent.api.registry.AutonomyLevel.fromWire(request.autonomyLevel()));
    // Tempdoc S7 — the FE's scope-chip selection rides the request; AgentToolDispatcher.scopeToolCall
    // reads it off the session to filter every search-tool call in this run. Empty = unscoped
    // (unchanged behavior).
    session.setDocIdsScope(request.docIds());
    // Lane F PR 0b — the run's optional sampling override rides the request; every site that builds
    // agent sampling reads it off the session. Null = SamplingParams.AGENT unchanged.
    session.setSamplingOverride(request.sampling());
    if (background) {
      session.markBackground(); // Tempdoc 561 P-D: safe-by-default safety gate for unwatched runs
    }
    sessionRegistry.register(sessionId, session);
    agentTelemetry.recordSessionStart(); // tempdoc 415
    var traceSequencerRef = new AtomicReference<>(
        new AgentEventTracing.Sequencer(sessionId, session.activeAgentId()));
    Consumer<AgentEvent> sink = wrapEventConsumer(sessionId, session, traceSequencerRef);
    // Tempdoc 834 §1.3 — open the run's observation channel. The session-local hub is gone; the
    // substrate owns sequencing, the bounded replay ring, fan-out and evict-on-throw.
    session.observeThrough(runObservation.open(sessionId, RUN_SHAPE_ID, request.conversationId()));
    // Tempdoc 834 §6.1 — the act-on-the-run primer, pushed before every replay. All EIGHT
    // components ride: dropping the three S1 added (pendingApprovals, autonomyLevel, park) would
    // silently undo S1's whole point — a reattacher would see a stopped run with no gate to answer.
    session.observation()
        .setSnapshotSupplier(
            () ->
                new AgentEvent.StateSnapshot(
                    session.iterationsUsed(),
                    session.budgetRemaining(),
                    session.toolCallsExecuted(),
                    session.messages().size(),
                    session.activeAgentId(),
                    session.pendingApprovals(),
                    session.autonomyLevel().name(),
                    session.parkSnapshot(),
                    TraceContext.none()));
    // Tempdoc 577 §2.14 Root I (#13) — the initiating SSE writer is an OBSERVER of the run, not a
    // privileged owner: the run survives it leaving, and it is counted (and evicted on a dead
    // socket) exactly like a reattaching tab. It is delivered to directly because it takes typed
    // AgentEvents while the journal carries the wire projection (§1.3.2).
    session.attachInitiatingObserver(eventConsumer);

    // Prepend default system prompt if the conversation doesn't already have one
    if (session.messages().isEmpty()
        || !"system".equals(session.messages().get(0).get("role"))) {
      session.messages().add(0, Map.of("role", "system", "content", promptComposer.buildSystemPrompt(session.engineContext())));
    }
    // For multi-agent: override system prompt with the initial profile's prompt
    if (!request.agentProfiles().isEmpty()) {
      AgentProfile initialProfile = AgentHandoff.findProfile(request.agentProfiles(), session.activeAgentId());
      if (initialProfile != null) {
        swapSystemPrompt(session, initialProfile);
      }
    }
    runStore.startRun(sessionId, request, session.messages(), initialBudget);
    if (session.isBackground()) {
      // Tempdoc 561 P-D2: stamp the durable record so the presence projection (presenceSince) surfaces
      // this run in the render-on-return inbox as work done while the user was away.
      runStore.markBackground(sessionId);
    }
    checkpoint(sessionId, session, LifecycleState.READY_FOR_LLM.name(), "");

    Span agentSpan = GlobalOpenTelemetry.getTracer(TRACER_SCOPE).spanBuilder("invoke_agent primary")
        .setSpanKind(SpanKind.INTERNAL)
        .setAttribute("gen_ai.operation.name", "invoke_agent")
        .setAttribute("gen_ai.agent.id", "primary")
        .setAttribute("gen_ai.agent.name", "justsearch-agent")
        .setAttribute("gen_ai.conversation.id", sessionId)
        .startSpan();

    boolean agentSuccess = false;
    try (Scope ignored = agentSpan.makeCurrent()) {
      // Lane F PR 0b — echo the sampling this run will ACTUALLY use, resolved through the one
      // site that applies the request's optional override. A capture can then prove the pin it
      // requested is the pin the backend used, instead of recording its own request back.
      SamplingParams appliedSampling = AgentLlmCaller.agentBaseSampling(session);
      sink.accept(
          new AgentEvent.SessionStarted(
              sessionId,
              appliedSampling.temperature(),
              appliedSampling.topP(),
              appliedSampling.seed(),
              TraceContext.none()));
      sink.accept(
          new AgentEvent.AgentProgress("init", "Starting agent session", 0, // 561 #4: no raw UUID
              request.maxIterations()));

      List<Map<String, Object>> baseTools =
          agentToolEmitter.emit(operationCatalog, request.selectedToolNames());
      // Tempdoc 868 §C: the ONE record of what the model was offered this run. Verifying a tool
      // "exists" against a controller's catalog view proved nothing about this list (a tool the
      // controller advertised was absent here for a whole live campaign), so the emitted names are
      // logged at the seam rather than inferred from a neighbouring surface.
      LOG.info(
          "Agent tools offered (session={}, selected={}): {}",
          sessionId,
          request.selectedToolNames(),
          baseTools.stream()
              .map(t -> t.get("function") instanceof Map<?, ?> f ? String.valueOf(f.get("name")) : "?")
              .toList());

      if (baseTools.isEmpty() && request.agentProfiles().isEmpty()) {
        String noToolsMessage = noToolsMessage(operationCatalog, request.selectedToolNames());
        emitError(
            sink,
            noToolsMessage,
            AgentErrorCode.NO_TOOLS,
            AgentErrorClass.PERMANENT,
            RetryAction.ABORT,
            null);
        // Tempdoc 415 F1: state-first, durability-second.
        session.markTerminated(TerminalDisposition.ERRORED, AgentErrorCode.NO_TOOLS, null);
        checkpoint(sessionId, session, LifecycleState.ERROR.name(), noToolsMessage);
        return;
      }

      for (int iteration = 0; iteration < request.maxIterations(); iteration++) {
        // Tempdoc 876 §B.2b — the offering re-evaluates WITHIN a run, monotonically. The emit above
        // is a t=0 sample and a ten-iteration run easily outlives a Worker recovery, so without this
        // a tool that came back mid-run could never reach the model. Iteration 0 runs on the emit
        // taken immediately above (same instant, nothing to re-evaluate); every later iteration
        // re-asks the emitter and adopts the answer only when its NAME SET is a strict superset of
        // what the model already has. The asymmetry is the point: offering fails open, execution
        // fails closed. An offered-but-broken tool returns an error the model can read and adapt to,
        // whereas a withheld tool is not experienced as a missing capability at all — it is
        // experienced as a reason to improvise (868 §C.3 recorded the model inventing
        // core_file_operations {"operations":[{"operation":"read"}]} while core_read_document was
        // withheld). So growth is adopted and shrinkage is not; on this path nothing the model has already been
        // shown — and possibly already called — vanishes underneath it.
        //
        // SCOPE (876 C.6): this governs the SINGLE-AGENT path, which is the only one that consumes
        // baseTools. With agentProfiles present, AgentStepRunner.buildIterationTools discards
        // baseTools and re-emits per ACTIVE PROFILE, because a profile switch is meant to change
        // the tool set; re-emitting here would burn an emit per iteration for a list nobody reads.
        // That path's own availability-driven shrink is an open item, not something this guard
        // silently covers — see the tempdoc.
        if (iteration > 0 && request.agentProfiles().isEmpty()) {
          baseTools = adoptGrownOffering(sessionId, request, baseTools);
        }
        IterationOutcome outcome =
            stepRunner.executeIteration(
                iteration, request, session, sessionId, baseTools, traceSequencerRef, sink);
        if (outcome.terminated()) {
          agentSuccess = outcome.success();
          return;
        }
      }

      // Max iterations reached
      agentSuccess = true;
      // Tempdoc 878 §D.1 — the OTHER truncating terminal, and the SECOND involuntary one. Like the
      // budget wall it now gets one no-tools synthesis attempt before it stops, so the work the run
      // already paid for reaches the reader; unlike the budget wall it names the STEP limit in the
      // instruction (859 D5: the two limits have different remedies and must not borrow each
      // other's sentence). The disposition is stamped regardless of what the model produced, which
      // is what keeps the truncation disclosed even when the answer is empty.
      //
      // The event is built by the runner because AgentGroundingSeamAuditTest reserves
      // grounding-carrying AgentDone construction to AgentStepRunner.groundedDone; this loop keeps
      // the state transition it owns for every other terminal.
      sink.accept(stepRunner.finalizeAtIterationCeiling(request, session, sink));
      // F1: state-first, durability-second.
      session.markTerminated(TerminalDisposition.MAX_ITERATIONS, null, null);
      checkpoint(sessionId, session, LifecycleState.DONE.name(), "Max iterations reached");

    } catch (io.justsearch.app.api.EngineWorkCancelledException cancelled) {
      sink.accept(session.cancellationEvent());
      session.markTerminated(TerminalDisposition.CANCELLED, null, session.cancellationTrigger());
      checkpoint(sessionId, session, LifecycleState.CANCELLED.name(), cancelled.reasonCode());
    } catch (Exception e) {
      LOG.error("Agent loop error", e);
      agentSpan.recordException(e);
      agentSpan.setStatus(StatusCode.ERROR, "agent-loop-error");
      emitError(
          sink,
          e.getMessage(),
          AgentErrorCode.INTERNAL_ERROR,
          AgentErrorClass.PERMANENT,
          RetryAction.ABORT,
          null);
      // F1: state-first, durability-second.
      session.markTerminated(TerminalDisposition.ERRORED, AgentErrorCode.INTERNAL_ERROR, null);
      checkpoint(sessionId, session, LifecycleState.ERROR.name(), e.getMessage());
    } finally {
      // Tempdoc 565 §33 — end-of-run drain: a steering directive queued AFTER the last step boundary
      // (during the final answer, or a single-LLM-call run with no second boundary) would otherwise be
      // silently dropped. Drain any still-pending directive once and emit DirectiveAcknowledged so the
      // human's direction is ALWAYS recorded as a run event — even when it landed too late to change the
      // output. The session is still in the map and the SSE sink open until this handler returns.
      String lateDirective = session.drainInterject();
      if (lateDirective != null && !lateDirective.isBlank()) {
        sink.accept(new AgentEvent.DirectiveAcknowledged(lateDirective));
      }
      if (agentSuccess) {
        agentSpan.setStatus(StatusCode.OK);
      } else {
        // Covers both: catch-path (setStatus(ERROR) already called — second call is no-op)
        // and error-return paths (emitError + return without exception)
        agentSpan.setStatus(StatusCode.ERROR, "agent-error");
      }
      agentSpan.end();
      // Tempdoc 415 / 430 Phase 7: centralized session-end emit (metric family + health-event
      // Occurrence + runStore typed reason). Each return site marks the session via
      // session.markTerminated(...) before returning; the finalizer reads the typed reason,
      // defaults a missing mark to ERRORED/INTERNAL_ERROR (F6, loud ERROR), and fans out.
      finalizer.emitSessionEnd(sessionId, session);
      sessionRegistry.remove(sessionId);
      // Tempdoc 577 §2.14 Root I (#13) / 834 §2 — the run is terminal. ONE call owns the whole
      // transition: refuse late publishes, close attached observers, keep the replay readable for
      // the linger window, then drop it. It used to be two sites that disagreed — remove-then-close
      // here, with attach refusing an already-removed session — so a reattach arriving in the gap
      // saw "gone" for a run whose answer had just landed.
      session.observation().retire();
    }
    } finally {
      if (cancellation != null) cancellation.close();
    }
  }


  /**
   * Tempdoc 876 §B.2b — re-emit the offering and adopt it only when its name set is a STRICT
   * SUPERSET of what the run is already offering. Equal set ⇒ keep the current list (a needless
   * replacement churns the prompt and invalidates caching for no gain); subset or disjoint ⇒ keep it
   * as well, because a tool may APPEAR mid-run but must never DISAPPEAR. Adoption replaces the list
   * wholesale rather than appending the new entries, which preserves the emitter's catalog ordering
   * — {@code AgentOperationEmitter}'s javadoc calls that stability load-bearing, since a model may
   * weight earlier tools differently.
   *
   * <p>The emit is a few object allocations over ~10 operations, i.e. nothing against an LLM
   * round-trip, so it is deliberately neither cached nor throttled.
   */
  private List<Map<String, Object>> adoptGrownOffering(
      String sessionId, AgentRequest request, List<Map<String, Object>> current) {
    List<Map<String, Object>> refreshed =
        agentToolEmitter.emit(operationCatalog, request.selectedToolNames());
    Set<String> currentNames = toolNames(current);
    Set<String> refreshedNames = toolNames(refreshed);
    if (refreshedNames.size() <= currentNames.size() || !refreshedNames.containsAll(currentNames)) {
      return current;
    }
    Set<String> appeared = new LinkedHashSet<>(refreshedNames);
    appeared.removeAll(currentNames);
    LOG.info("Agent tools grew mid-run (session={}): +{}", sessionId, appeared);
    return refreshed;
  }

  /**
   * The wire names in an emitted tool list, in emission order. An envelope carrying no name
   * contributes nothing rather than a shared sentinel: two nameless entries collapsing to one set
   * element would make the strict-superset comparison reason about the wrong cardinality. Nothing
   * emits one today (the OpenAI projection always names its tool and {@code
   * VirtualOperationStore.withoutCollisions} drops nameless virtual entries), which is exactly why
   * a sentinel here would be a silent landmine rather than a visible bug.
   */
  private static Set<String> toolNames(List<Map<String, Object>> tools) {
    Set<String> names = new LinkedHashSet<>();
    for (Map<String, Object> tool : tools) {
      if (tool.get("function") instanceof Map<?, ?> function && function.get("name") != null) {
        names.add(function.get("name").toString());
      }
    }
    return names;
  }

  // Tempdoc 584 §B.4 — the live-run control surface delegates to AgentSessionRegistry. Bodies (the
  // uniform get-session-then-mutate shape) moved there; new per-run control verbs attach to that
  // collaborator. This is the cluster that re-pinned the file via 561 / 565 / 577.

  @Override
  public void approveToolCall(String sessionId, String callId) {
    sessionRegistry.approveToolCall(sessionId, callId);
  }

  @Override
  public void rejectToolCall(String sessionId, String callId, String reason) {
    sessionRegistry.rejectToolCall(sessionId, callId, reason);
  }

  @Override
  public boolean tryApproveToolCall(String sessionId, String callId) {
    return sessionRegistry.tryApproveToolCall(sessionId, callId);
  }

  @Override
  public boolean tryRejectToolCall(String sessionId, String callId, String reason) {
    return sessionRegistry.tryRejectToolCall(sessionId, callId, reason);
  }

  @Override
  public void cancelSession(String sessionId) {
    sessionRegistry.cancelSession(sessionId);
    recordStopRequest(sessionId);
  }

  /**
   * Owner decision 2026-08-26 (late cancel) — write the reader's Stop into the run's own journal,
   * whether or not the loop was still in a position to act on it.
   *
   * <p>UNCONDITIONAL, deliberately. The registry's cancel is a no-op once the run has been evicted,
   * and even while it is registered the loop only reads the flag at its next step boundary — so a
   * stop that arrives after the terminal `done` leaves the run COMPLETED with the reader's act
   * recorded nowhere. Gating this on "did the cancel actually stop anything" would reintroduce
   * exactly that race, because there is no instant at which the answer to that question is stable.
   * The run that WAS stopped therefore records the fact twice — here and as its {@code CANCELLED}
   * error entry — which is two statements of one true thing, not two stories: the record projection
   * reads either as the same halt.
   *
   * <p>The snapshot guard keeps this addressed to runs that exist; an unknown id writes nothing
   * rather than minting a journal for it.
   *
   * <p>ONCE PER RUN, though (review F2). "The reader stopped this run" does not become truer for
   * being said twice, and a second note is a second row in the reloaded feed — which is what a stop
   * from a second window, or a stop re-sent against an already-stopped run, would have produced. The
   * latch is the JOURNAL rather than a flag on the session, because the case this whole fix exists
   * for is the one where the session is already gone: a session-scoped latch would be absent exactly
   * when the duplicate arrives.
   */
  private void recordStopRequest(String sessionId) {
    if (sessionId == null || sessionId.isBlank() || runStore.readSnapshot(sessionId) == null) {
      return;
    }
    if (hasStopRequestNote(sessionId)) {
      return;
    }
    runStore.appendEvent(
        sessionId,
        new AgentEvent.AgentProgress(
            AgentEvent.AgentProgress.PHASE_STOP_REQUESTED, STOP_REQUESTED_NOTE, 0, 0));
  }

  /** Does this run's journal already carry the reader's stop? (see {@link #recordStopRequest}) */
  private boolean hasStopRequestNote(String sessionId) {
    for (Map<String, Object> record : runStore.readEvents(sessionId)) {
      if ("progress".equals(record.get("eventType"))
          && record.get("payload") instanceof Map<?, ?> payload
          && AgentEvent.AgentProgress.PHASE_STOP_REQUESTED.equals(payload.get("phase"))) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void setSessionAutonomy(String sessionId, String autonomyLevelWire) {
    sessionRegistry.setSessionAutonomy(sessionId, autonomyLevelWire);
  }

  @Override
  public boolean injectSteeringDirective(String sessionId, String text) {
    return sessionRegistry.injectSteeringDirective(sessionId, text);
  }

  @Override
  public boolean raiseSessionBudget(String sessionId, int addTokens) {
    return sessionRegistry.raiseSessionBudget(sessionId, addTokens);
  }

  @Override
  public boolean resolveBudgetGate(String sessionId, String decision) {
    return sessionRegistry.resolveBudgetGate(sessionId, decision);
  }

  @Override
  public boolean resolveContextGate(String sessionId, String decision) {
    return sessionRegistry.resolveContextGate(sessionId, decision);
  }

  @Override
  public boolean attachToRun(String sessionId, Consumer<RunObservation.WireFrame> observer) {
    return sessionRegistry.attachToRun(sessionId, 0L, observer);
  }

  @Override
  public boolean attachToRun(
      String sessionId, long sinceSeq, Consumer<RunObservation.WireFrame> observer) {
    return sessionRegistry.attachToRun(sessionId, sinceSeq, observer);
  }

  @Override
  public boolean completeVirtualToolCall(
      String sessionId, String callId, boolean success, String output, String errorDetail) {
    return sessionRegistry.completeVirtualToolCall(
        sessionId, callId, success, output, errorDetail);
  }

  @Override
  public int activeSessionCount() {
    return sessionRegistry.activeSessionCount();
  }

  // Tempdoc 584 §B.4 — the AgentRunQueries read/projection/resume surface delegates to
  // AgentRunQueryService. Bodies (and the toBatchSummary / backgroundBoundary / firstUserMessage
  // helpers + resumeFromSnapshot) moved there; new read features attach to that collaborator.

  @Override
  public List<Operation> availableOperations() {
    return queries.availableOperations();
  }

  /**
   * Tempdoc 876 §B.1 — deliberately NOT delegated to {@code queries}: the offering is the emitter's
   * to decide, and the emitter is held here (the read collaborator has only the catalog). Same call
   * the run loop makes at {@code runAgent}, minus a run's tool selection, so the trust panel and the
   * model read one authority.
   */
  @Override
  public List<Operation> offeredOperations() {
    return agentToolEmitter.offer(operationCatalog, List.of());
  }

  @Override
  public OperationResult undoOperation(String toolName, String executionId, EngineContext engineContext) {
    return queries.undoOperation(toolName, executionId, engineContext);
  }

  @Override
  public List<Map<String, Object>> operationHistory(int limit) {
    return queries.operationHistory(limit);
  }

  @Override
  public Map<String, Object> operationDetail(String batchId) {
    return queries.operationDetail(batchId);
  }

  @Override
  public Map<String, Object> lastSessionSnapshot() {
    return queries.lastSessionSnapshot();
  }

  @Override
  public List<Map<String, Object>> listSessions(int limit) {
    return queries.listSessions(limit);
  }

  @Override
  public List<Map<String, Object>> conversationSummaries(int limit) {
    return queries.conversationSummaries(limit);
  }

  @Override
  public Map<String, Object> sessionSnapshot(String sessionId) {
    return queries.sessionSnapshot(sessionId);
  }

  @Override
  public void resumeLastSession(Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
    queries.resumeLastSession(eventConsumer, engineContext);
  }

  @Override
  public void resumeSession(String sessionId, Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
    queries.resumeSession(sessionId, eventConsumer, engineContext);
  }

  @Override
  public void forkSession(String sessionId, String editedMessage, Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {
    queries.forkSession(sessionId, editedMessage, eventConsumer, engineContext);
  }

  @Override
  public List<Map<String, Object>> sessionEvents(String sessionId) {
    return queries.sessionEvents(sessionId);
  }

  @Override
  public List<InteractionEvent> threadEvents(String conversationId) {
    return queries.threadEvents(conversationId);
  }

  @Override
  public List<InteractionEvent> threadEvents(
      String conversationId, Set<String> answeredRunIds) {
    return queries.threadEvents(conversationId, answeredRunIds);
  }

  @Override
  public io.justsearch.agent.api.interaction.ThreadProjection threadProjection(
      String conversationId, Set<String> answeredRunIds) {
    return queries.threadProjection(conversationId, answeredRunIds);
  }

  @Override
  public List<io.justsearch.agent.api.lifecycle.AgentLifecycle> lifecycles(String conversationId) {
    return queries.lifecycles(conversationId);
  }

  @Override
  public List<io.justsearch.agent.api.lifecycle.AgentLifecycle> presenceSince(
      java.time.Instant since) {
    return queries.presenceSince(since);
  }

  @Override
  public String appendSearchEvent(String conversationId, Map<String, Object> attributes) {
    return runStore.appendSearchEvent(conversationId, attributes);
  }

  @Override
  public int deleteConversationRuns(String conversationId) {
    return runStore.deleteConversationRuns(conversationId);
  }

  @Override
  public boolean isAvailable() {
    return !operationCatalog.definitions().isEmpty();
  }

  /** Tempdoc 560 WS5 — forward the streaming workflow-as-tool runner to the step runner. */
  @Override
  public void setWorkflowToolRunner(
      io.justsearch.agent.api.registry.WorkflowToolRunner runner) {
    stepRunner.setWorkflowToolRunner(runner);
  }

  /**
   * Tempdoc 565 §3.A — late-bind the answer↔source matcher from a {@link DocumentService} (available
   * only at bootstrap). Builds the resolver here (keeping it package-private to app-agent) and
   * forwards it to the step runner so the terminal answer carries inline citations. A null service
   * leaves answers citing their grounding sources without per-sentence marks.
   */
  public void setCitationDocumentService(io.justsearch.app.api.DocumentService documentService) {
    setCitationDocumentService(
        documentService, io.justsearch.app.api.DocumentService.DEFAULT_CITATION_SIMILARITY_THRESHOLD);
  }

  /**
   * Same, with the configured cosine cutoff (tempdoc 799 N.2 — {@code
   * justsearch.citation.match_threshold}). Kept as an overload so callers without config resolved
   * keep the compiled default.
   */
  public void setCitationDocumentService(
      io.justsearch.app.api.DocumentService documentService, double similarityThreshold) {
    stepRunner.setCitationResolver(
        documentService == null
            ? null
            : new AgentCitationResolver(documentService, similarityThreshold));
  }

  // --- Internal ---

  private Consumer<AgentEvent> wrapEventConsumer(
      String sessionId, AgentSession session,
      AtomicReference<AgentEventTracing.Sequencer> traceSequencerRef) {
    return event -> {
      AgentEvent enriched = event;
      TraceContext trace = event.trace();
      if (trace == null || !trace.hasIdentity()) {
        enriched = AgentEventTracing.withTrace(event,
            traceSequencerRef.get().next(event, session.iterationsUsed()));
      }
      // Tempdoc 577 §2.14 Root I (#13) / 834 §1.3 — deliver to the run's observers (the journal,
      // which fans out to every reattacher, plus the initiating writer) instead of writing the SSE
      // socket directly. A broken observer's exception is SWALLOWED and the observer evicted — so a
      // closed/dropped socket can no longer kill the loop, and the run survives the socket close
      // (the §2.15 V3 root cause: a synchronous write to a dead socket aborted the loop).
      session.deliverToObservers(enriched);
      runStore.appendEvent(sessionId, enriched);
      // Tempdoc 585 §D Phase 1 (A1): per-event-type observability from the ONE publish chokepoint.
      agentTelemetry.recordEventEmitted(io.justsearch.agent.api.AgentEventPayloads.name(enriched));
    };
  }


  /**
   * Persist a run checkpoint. Collapses the 7-arg {@code runStore.updateCheckpoint}
   * call whose five session-snapshot arguments are always the same
   * (tempdoc 240 W0). All 24 call sites pass {@code (sessionId, session, state, note)}.
   */
  private void checkpoint(String sessionId, AgentSession session, String state, String resumeNote) {
    runStore.updateCheckpoint(
        sessionId,
        state,
        session.messages(),
        session.iterationsUsed(),
        session.toolCallsExecuted(),
        session.totalTokens(),
        resumeNote);
  }

  private void emitError(
      Consumer<AgentEvent> eventConsumer,
      String message,
      AgentErrorCode code,
      AgentErrorClass errorClass,
      RetryAction retryAction,
      Integer retryAttempt) {
    agentTelemetry.recordError(code, errorClass);
    eventConsumer.accept(
        new AgentEvent.AgentError(message, code, errorClass, retryAction, retryAttempt));
  }

  /**
   * What to tell the caller when the run's tool offering is empty, from the selection it actually
   * sent rather than a single fixed sentence.
   *
   * <p>"No tools available" was true of the no-selection case and misleading of every other one:
   * a request whose {@code tools} selection named real operations that the availability filter
   * withheld ({@code core_search_index} while {@code index.unavailable} was asserted — the live
   * 868 §C.3 / §D.4 report) got the same words as a request that named nothing that exists, and
   * neither said which. Same discipline as {@link AgentStepRunner#emptyResponseMessage}: say only
   * what the evidence licenses, because a wrong cause is acted on and an unknown one is not.
   *
   * <p>The selection is matched against the catalog in BOTH accepted identifier spaces — the wire
   * name ({@code core_search_index}) and the raw {@code OperationRef} ({@code core.search-index}) —
   * exactly as {@code AgentOperationEmitter.matchesSelection} does, so "the name is unknown" here
   * means the same thing it means there.
   */
  static String noToolsMessage(OperationCatalog catalog, List<String> selectedToolNames) {
    if (selectedToolNames == null || selectedToolNames.isEmpty()) {
      return "No tools available";
    }
    List<String> known = new ArrayList<>();
    List<String> unknown = new ArrayList<>();
    for (String selected : selectedToolNames) {
      boolean resolves =
          catalog.definitions().stream()
              .anyMatch(
                  op ->
                      OperationCatalog.toWireName(op.id()).equals(selected)
                          || op.id().value().equals(selected));
      (resolves ? known : unknown).add(selected);
    }
    if (known.isEmpty()) {
      return "None of the selected tools exist: "
          + String.join(", ", unknown)
          + ". Use the names from GET /api/chat/agent/tools (e.g. core_search_index), or send no"
          + " tools selection to offer the model everything available.";
    }
    return "The selected tools are not available right now: "
        + String.join(", ", known)
        + ". Tools backed by the index are withheld while it is unavailable (a restarting or"
        + " starting Worker) — retry shortly, or send no tools selection to offer the model"
        + " whatever is available.";
  }


}
