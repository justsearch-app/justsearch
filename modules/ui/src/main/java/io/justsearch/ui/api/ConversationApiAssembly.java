/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.observability.HeadApiMetricCatalog;
import io.justsearch.telemetry.Telemetry;
import java.util.List;
import java.util.function.Supplier;

/**
 * Tempdoc 583 Stage 2: the ConversationEngine + SPI-registry + agent/chat/MCP assembly, lifted out
 * of {@link LocalApiServer}'s constructor (§B.7 remedy).
 *
 * <p>This ~260-LOC block of dependency-injection wiring grew with every conversation-shape / SPI /
 * MCP-resource slice; isolating it here keeps that growth off the LocalApiServer class-size pin.
 * Behaviour is identical — the block moved verbatim, with the four {@code this.<field>} writes
 * turned into the returned {@link Result} and the cross-references {@code this.telemetry} /
 * {@code this.apiCatalog} / {@code this.registryController != null} / {@code () ->
 * this.knowledgeSearchController} turned into parameters. The late-bind holder semantics (the
 * {@code standalone-capability-stays-stuck} fix — set the workflow runner idempotently on the
 * lazily-resolved agent) are preserved unchanged.
 */
final class ConversationApiAssembly {
  private ConversationApiAssembly() {}

  /** The fields {@link LocalApiServer} assigns from the assembly result. */
  record Result(
      ResolveAddressController resolveAddressController,
      AgentController agentController,
      // Tempdoc 585 §B.5 — the agent controller's read-axis + tools-axis cuts; registered alongside
      // the run/control core by AgentRoutes.register.
      AgentSessionController agentSessionController,
      AgentToolsController agentToolsController,
      ChatController chatController,
      // Tempdoc 834 §1.6 — the POST-managed-SSE run stream family, over the ONE run channel registry
      // the S4 enumeration will also read.
      RunStreamController runStreamController,
      io.justsearch.ui.api.mcp.McpProtocolHandler mcpProtocolHandler,
      // Tempdoc 629 (#7): the AUTHORED conversation store, exposed for the encrypted-backup export.
      io.justsearch.agent.api.conversation.ConversationStore conversationStore) {}

  static Result assemble(
      LocalApiServer.Builder b,
      Telemetry telemetry,
      HeadApiMetricCatalog apiCatalog,
      boolean registryPresent,
      Supplier<KnowledgeSearchController> knowledgeSearchControllerSupplier) {
    // Tempdoc 374 alpha.25 U14-B: pass a Supplier<AgentService> so the controller always
    // reads the current instance from HeadAssembly. Needed because agentService is now
    // lazily constructed when AI is activated post-boot via the alpha.23 R13-B clickable
    // badge — pre-fix the controller captured a final reference at construction time and
    // stayed permanently "unavailable" even after activation succeeded.
    // Tempdoc 491 Phase B: assemble the ConversationEngine + agent shape runner. The agent
    // loop's body is encapsulated unchanged; the engine routes /api/agent/run/stream through
    // the runner, preserving the SSE wire vocabulary exactly.
    // Tempdoc 491 Phase C0: wire SPI registries for substrate-driven shapes. Phase C0 ships
    // the engine with empty registries; phases C1+ register the four core SPI implementations
    // (SummarizationStyle, DocAccess, RAGContext, CitationMatcher, MultiPassSynthesis, ...).
    // Tempdoc 519 §5 / Step F1: late-resolving suppliers route through typed records
    // (core/workers/inference) instead of bootstrap locator methods. The bootstrap may be
    // null at builder time + the inner service references can transition (Worker reconnect),
    // so each lambda resolves at supplier-invocation time.
    // Tempdoc 560 WS5 — the streaming workflow-as-tool runner, late-bound to the LAZILY-CONSTRUCTED
    // agent. The agent is built only when AI activates (post-boot) and is re-resolved per request via
    // the supplier below, so a one-shot set at boot misses it entirely (the standalone-capability-stays-
    // stuck trap — caught by live validation). Instead we set the runner IDEMPOTENTLY on whatever agent
    // the supplier resolves. WorkflowShapeRunner is constructed further down, so the runner reads it
    // lazily through a holder (live-only: a workflow tool is never invoked before the agent runs).
    final io.justsearch.app.services.conversation.WorkflowShapeRunner[] wfShapeRunnerHolder =
        new io.justsearch.app.services.conversation.WorkflowShapeRunner[1];
    final io.justsearch.agent.api.registry.WorkflowToolRunner wfToolRunner =
        new io.justsearch.app.services.conversation.WorkflowToolRunnerImpl(
            io.justsearch.app.services.conversation.CoreWorkflowCatalog.catalog(),
            (body, aud, sink, engineContext) -> {
              io.justsearch.app.services.conversation.WorkflowShapeRunner r = wfShapeRunnerHolder[0];
              if (r == null) {
                throw new IllegalStateException("WorkflowShapeRunner not yet wired");
              }
              r.run(body, aud, sink, engineContext);
            });
    Supplier<io.justsearch.agent.api.AgentService> rawAgentSupplier =
        b.agentService != null
            ? () -> b.agentService
            : (b.HeadAssembly != null
                ? () -> b.HeadAssembly.core().agent()
                : io.justsearch.agent.api.AgentService::unavailable);
    // Tempdoc 834 §1.3 — THE run channel registry. One instance, shared by the agent loop (which
    // journals through it) and the run-stream endpoints (which observe and, in S4, enumerate it):
    // a second registry would be a second answer to "what is running right now".
    final io.justsearch.app.observability.stream.run.RunChannelRegistry runChannelRegistry =
        new io.justsearch.app.observability.stream.run.RunChannelRegistry();
    // The projector keeps the journal carrying what the WIRE carries: the base payload plus the one
    // wire-only overlay (the tool_batch_proposed gate prediction). Journaling the base alone would
    // silently ship a gate-hint-less plan preview to every observer.
    final io.justsearch.agent.api.RunObservation runObservation =
        new io.justsearch.app.observability.stream.run.RunChannelObservation(
            runChannelRegistry,
            event -> {
              java.util.Map<String, io.justsearch.agent.api.registry.Operation> opsByToolName;
              try {
                opsByToolName =
                    io.justsearch.app.services.conversation.ProposedBatchProjection.indexByToolName(
                        rawAgentSupplier.get().availableOperations());
              } catch (RuntimeException engineOffline) {
                opsByToolName = java.util.Map.of(); // degrade, exactly as AgentSseWriter does
              }
              var translated =
                  io.justsearch.app.services.conversation.AgentEventSseTranslator.translate(
                      event,
                      b.HeadAssembly != null
                          ? b.HeadAssembly.substrate().conversation().intentGateEvaluator()
                          : null,
                      opsByToolName);
              return new io.justsearch.agent.api.RunObservation.WireFrame(
                  translated.name(), translated.payload());
            },
            java.time.Clock.systemUTC());
    Supplier<io.justsearch.agent.api.AgentService> agentSupplier =
        () -> {
          io.justsearch.agent.api.AgentService a = rawAgentSupplier.get();
          if (a != null) {
            // Idempotent: forwards to the step runner (no-op on the unavailable null-object).
            a.setWorkflowToolRunner(wfToolRunner);
            // Same late-bind reason (tempdoc 834 §1.3): the agent is constructed lazily when AI
            // activates, so a one-shot set at boot would miss the instance that actually runs.
            a.setRunObservation(runObservation);
          }
          return a;
        };
    Supplier<OnlineAiService> onlineAiSupplier =
        b.onlineAiService != null
            ? () -> b.onlineAiService
            : (b.HeadAssembly != null
                ? () -> b.HeadAssembly.inference().onlineAi()
                : OnlineAiService::unavailable);
    Supplier<DocumentService> docsSupplier =
        b.documentService != null
            ? () -> b.documentService
            : (b.HeadAssembly != null
                ? () -> b.HeadAssembly.workers().documents()
                : DocumentService::unavailable);
    DocumentService docs =
        new io.justsearch.app.services.conversation.LazyDocumentService(docsSupplier);
    // Tempdoc 526 §4.2 — typed DocumentAddress → canonical resolver.
    ResolveAddressController resolveAddressController = new ResolveAddressController(docs);
    // Slice 487 SPI contributors require HeadAssembly (live operation catalog +
    // surface catalog + backend intent router). Integration tests instantiate
    // LocalApiServer without a bootstrap, so guard the slice-487 registrations.
    List<io.justsearch.agent.api.conversation.PromptContributor> promptContributors =
        new java.util.ArrayList<>();
    promptContributors.add(io.justsearch.app.services.conversation.spi.SummarizationStyle.INSTANCE);
    promptContributors.add(io.justsearch.app.services.conversation.spi.RAGQAStyle.INSTANCE);
    // Tempdoc 822 §1.3 (S6) — answer-shape guidance for the ask tier. Must be registered here
    // or ConversationEngine.resolveContributors throws for core.rag-ask.
    promptContributors.add(
        io.justsearch.app.services.conversation.spi.AnswerShapeGrammar.INSTANCE);
    List<io.justsearch.agent.api.conversation.StreamConsumer> streamConsumers =
        new java.util.ArrayList<>();
    streamConsumers.add(io.justsearch.app.services.conversation.spi.SummaryDoneEnricher.INSTANCE);
    streamConsumers.add(
        io.justsearch.app.services.conversation.spi.BatchSummaryDoneEnricher.INSTANCE);
    // Slice 493 Phase 6: CitationMatcher superseded by StreamingCitationMatcher (same
    // onDone behavior + streaming onChunk). Old class retained in source for one release.
    // Tempdoc 799 N.2: the citation cutoff is now read from config. Tempdoc 565 15.A made this
    // the ONE cutoff shared with the agent path, so AgentLoopWiring must read the same key —
    // wiring only one side would reintroduce the RAG/agent divergence 565 removed.
    var ragCfgForCitations = resolvedRag();
    streamConsumers.add(
        ragCfgForCitations == null
            ? new io.justsearch.app.services.conversation.spi.StreamingCitationMatcher(docs)
            : new io.justsearch.app.services.conversation.spi.StreamingCitationMatcher(
                docs, ragCfgForCitations.citationMatchThreshold()));
    streamConsumers.add(io.justsearch.app.services.conversation.spi.RAGDoneEnricher.INSTANCE);
    // Slice 491 §9.D Phase E (C4 + F1) — same URLExtractor instance is registered in the
    // substrate-driven streamConsumers list AND consumed by ToolIteratingShapeRunner via
    // its StreamConsumerRegistry (AgentRunShape declares core.url-extractor in its
    // streamConsumerIds; the runner resolves it from the same registry).
    io.justsearch.app.services.conversation.spi.URLExtractor sharedUrlExtractor;
    if (b.HeadAssembly != null) {
      // URLEmissionCapability's PromptContributor — renders the URL grammar + live
      // catalog descriptor block; consumed by NavigateChatShape (and any future shape
      // that adopts the capability — composability is the design's payoff).
      promptContributors.add(
          new io.justsearch.app.services.conversation.spi.URLEmissionGrammar(
              b.HeadAssembly.substrate().operations().operations(),
              b.HeadAssembly.substrate().conversation().coreSurfaces()));
      // URLEmissionCapability's StreamConsumer — parses justsearch:// URLs from
      // onDone(fullText), dispatches each via BackendIntentRouter, emits namespaced
      // navigate.url_extracted / navigate.url_dispatched / navigate.url_rejected
      // SSE events.
      sharedUrlExtractor =
          new io.justsearch.app.services.conversation.spi.URLExtractor(
              b.HeadAssembly.substrate().intent().router());
      streamConsumers.add(sharedUrlExtractor);
    }
    // Slice 496 §3.C: ValidationConsumer for the Extract shape.
    streamConsumers.add(new io.justsearch.app.services.conversation.spi.ValidationConsumer());
    // Tempdoc 561 P-E: the passive (answer-plane) learning producer — chat / RAG-ask turns persist
    // explicit user facts/preferences into the SAME single-authority MemoryStore the agent tool writes.
    // Registered only when a HeadAssembly (and thus the shared store) is present; builder paths without
    // one (integration tests) don't run the chat shapes that resolve this consumer.
    if (b.HeadAssembly != null) {
      streamConsumers.add(
          new io.justsearch.app.services.conversation.spi.MemoryExtractionConsumer(
              b.HeadAssembly.memoryStore()));
    }
    // F1: share the same StreamConsumerRegistry between the agent-shape runner and the
    // substrate-driven engine so AgentRunShape's declared streamConsumerIds resolve via
    // the same registry the substrate path uses.
    io.justsearch.app.services.conversation.StreamConsumerRegistry streamConsumerRegistry =
        io.justsearch.app.services.conversation.StreamConsumerRegistry.of(streamConsumers);

    // Slice 491 G4 (Pass-8 follow-up) — wire the tier-compatibility validator into
    // catalog construction. Today's CORE-only catalog passes trivially (all SPIs
    // default to all tiers + all shapes are CORE); the wiring is the load-bearing
    // piece — the gate fires on every boot, so when a future plugin slice registers
    // a non-CORE shape that composes a restricted SPI, the violation surfaces at
    // boot instead of at runtime.
    io.justsearch.app.services.conversation.PromptContributorRegistry promptContributorRegistry =
        io.justsearch.app.services.conversation.PromptContributorRegistry.of(promptContributors);
    io.justsearch.app.services.conversation.ContextInjectorRegistry contextInjectorRegistry =
        io.justsearch.app.services.conversation.ContextInjectorRegistry.of(
            List.of(
                new io.justsearch.app.services.conversation.spi.DocAccess(docs),
                new io.justsearch.app.services.conversation.spi.BatchDocAccess(docs),
                // Tempdoc 845 — the same onlineAiSupplier the engine uses, so the RAG token budget
                // is computed against the window the running llama-server actually has (observed
                // n_ctx, else the configured launch window) instead of a hardcoded 8192.
                ragCfgForCitations == null
                    ? new io.justsearch.app.services.conversation.spi.RAGContext(
                        docs,
                        io.justsearch.app.services.conversation.spi.RAGContext.DEFAULT_TOP_K,
                        onlineAiSupplier)
                    : new io.justsearch.app.services.conversation.spi.RAGContext(
                        docs, ragCfgForCitations.ragTopK(), onlineAiSupplier),
                io.justsearch.app.services.conversation.spi.UserPromptInjector.INSTANCE,
                // Tempdoc 883 decision 3 — the same onlineAiSupplier RAGContext gets, so the
                // history cap is a fraction of the window the running server actually has.
                new io.justsearch.app.services.conversation.spi.ExternalContextInjector(
                    onlineAiSupplier),
                // Tempdoc 603 C2 — conversation-aware query decontextualization before RAG retrieval.
                new io.justsearch.app.services.conversation.spi.QueryRewriteInjector(onlineAiSupplier),
                // tempdoc 526 §12.4 — typed selection injector (core.selection).
                new io.justsearch.app.services.conversation.spi.SelectionContextInjector(
                    docs, onlineAiSupplier)));
    io.justsearch.app.services.conversation.IterationControllerRegistry iterationControllerRegistry =
        io.justsearch.app.services.conversation.IterationControllerRegistry.of(List.of(
            // SingleHopController for all ONE_SHOT shapes (including FreeChat).
            io.justsearch.agent.api.conversation.SingleHopController.INSTANCE,
            // Slice 496 §3.C: ValidatingController for the Extract shape.
            new io.justsearch.app.services.conversation.spi.ValidatingController()));
    io.justsearch.app.services.conversation.ShapeTierCompatibilityValidator tierValidator =
        new io.justsearch.app.services.conversation.ShapeTierCompatibilityValidator(
            promptContributorRegistry,
            contextInjectorRegistry,
            streamConsumerRegistry,
            iterationControllerRegistry);
    tierValidator.enforceOrThrow(
        io.justsearch.app.services.conversation.CoreConversationShapeCatalog.catalog()
            .definitions());
    // Tempdoc 629 (LAYER): seal conversation content with the data key when encryption is enabled.
    // 629 (#1): the cipher is a projection of the catalog's class (not a hardcoded literal).
    var conversationsPath = b.indexBasePath.resolveSibling("conversations");
    var conversationStore = new io.justsearch.app.services.conversation.FileConversationStore(
        conversationsPath,
        b.HeadAssembly != null
            ? b.HeadAssembly.storeCipher(
                io.justsearch.agent.api.encryption.StoreCatalog.CONVERSATIONS.recoverability())
            : io.justsearch.agent.api.encryption.StoreCipher.disabled());
    // 629 (#1): register the conversation store into HeadAssembly's authoritative AUTHORED list (it is
    // built here, in modules/ui, not in HeadAssembly) so the backup export reads ONE list for all 3 stores.
    if (b.HeadAssembly != null) {
      b.HeadAssembly.registerAuthoredStore(
          new io.justsearch.agent.api.encryption.StoreDescriptor(
              io.justsearch.agent.api.encryption.StoreCatalog.CONVERSATIONS,
              conversationsPath,
              () -> {
                var out = new java.util.ArrayList<java.util.Map<String, Object>>();
                for (var s : conversationStore.listSessions(null, 100_000)) {
                  var entry = new java.util.LinkedHashMap<String, Object>();
                  entry.put("sessionId", s.sessionId());
                  entry.put("shapeId", s.shapeId());
                  entry.put("firstUserMessage", s.firstUserMessage());
                  entry.put("messages", conversationStore.loadHistory(s.sessionId()));
                  out.add(entry);
                }
                return out;
              },
              // Import sink (629 #E): recreate each conversation (skip-existing) by replaying its
              // messages through appendMessage, which re-seals them under the LOCAL data key.
              entries -> {
                var existing = new java.util.HashSet<String>();
                for (var s : conversationStore.listSessions(null, 100_000)) {
                  existing.add(s.sessionId());
                }
                int restored = 0;
                for (var c : entries) {
                  Object sid = c.get("sessionId");
                  if (sid == null || existing.contains(sid.toString())) {
                    continue;
                  }
                  String shapeId = c.get("shapeId") == null ? null : c.get("shapeId").toString();
                  if (c.get("messages") instanceof List<?> list) {
                    for (var msg : list) {
                      if (msg instanceof java.util.Map<?, ?> raw) {
                        var typed = new java.util.LinkedHashMap<String, Object>();
                        for (var e : raw.entrySet()) {
                          typed.put(String.valueOf(e.getKey()), e.getValue());
                        }
                        conversationStore.appendMessage(sid.toString(), shapeId, typed);
                      }
                    }
                    restored++;
                  }
                }
                return restored;
              }));
    }
    var toolIteratingShapeRunner =
        new io.justsearch.app.services.conversation.ToolIteratingShapeRunner(
            agentSupplier, streamConsumerRegistry);
    // Tempdoc 550 F6: share the one intent-gate evaluator so the AgentRun conversation-shape batch
    // reads the SAME predicted verdict the /api/agent path does (no drift between projections).
    if (b.HeadAssembly != null) {
      toolIteratingShapeRunner.setIntentGateEvaluator(
          b.HeadAssembly.substrate().conversation().intentGateEvaluator());
    }
    // Tempdoc 560 Phase 2 — the workflow runner makes the Workflow Manifest type executable.
    // Late-bound to the engine (holder) because a workflow's LlmStep delegates back into the
    // engine, which is constructed below with this runner in its list.
    final io.justsearch.app.services.conversation.ConversationEngine[] engineHolder =
        new io.justsearch.app.services.conversation.ConversationEngine[1];
    var workflowGateRegistry =
        new io.justsearch.app.services.conversation.WorkflowGateRegistry();
    io.justsearch.app.services.conversation.WorkflowShapeRunner workflowShapeRunner = null;
    if (b.HeadAssembly != null) {
      var gatedExecutor =
          new io.justsearch.agent.api.registry.GatedOperationExecutor(
              () -> b.HeadAssembly.substrate().intent().router(),
              () -> b.HeadAssembly.substrate().conversation().consentCapsuleService(),
              // Tempdoc 560 Fix B: the workflow runner stamps WORKFLOW transport (vs the agent loop's
              // AGENT_LOOP) so its tool-calls are correctly attributed in the lattice + audit ledger.
              io.justsearch.agent.api.registry.TransportTag.WORKFLOW);
      // Tempdoc 565 §15.C — share the agent run's shape-agnostic RunEventStore so the workflow run
      // persists + indexes in the one run-event space the unified thread projects.
      var sharedRunEvents =
          b.HeadAssembly.agentRunStore() != null
              ? b.HeadAssembly.agentRunStore().runEvents()
              : io.justsearch.agent.RunEventStore.noop();
      workflowShapeRunner =
          new io.justsearch.app.services.conversation.WorkflowShapeRunner(
              () -> engineHolder[0],
              io.justsearch.app.services.conversation.CoreWorkflowCatalog.catalog(),
              () -> b.HeadAssembly.substrate().operations().agentTools(),
              () -> b.HeadAssembly.substrate().operations().operations(),
              gatedExecutor,
              workflowGateRegistry,
              sharedRunEvents);
      // Tempdoc 560 WS5 — publish the runner into the holder the workflow-tool bridge reads lazily.
      wfShapeRunnerHolder[0] = workflowShapeRunner;
    }
    List<io.justsearch.app.services.conversation.ShapeRunner> shapeRunners =
        new java.util.ArrayList<>();
    shapeRunners.add(toolIteratingShapeRunner);
    shapeRunners.add(
        new io.justsearch.app.services.conversation.HierarchicalShapeRunner(
            onlineAiSupplier, docsSupplier));
    if (workflowShapeRunner != null) {
      shapeRunners.add(workflowShapeRunner);
    }
    io.justsearch.app.services.conversation.ConversationEngine conversationEngine =
        new io.justsearch.app.services.conversation.ConversationEngine(
            io.justsearch.app.services.conversation.CoreConversationShapeCatalog.catalog(),
            shapeRunners,
            promptContributorRegistry,
            // Same ContextInjectorRegistry the tier validator saw — the injector list
            // includes UserPromptInjector.INSTANCE for NavigateChatShape's user-prompt
            // injection path (slice 487 Phase 2.3).
            contextInjectorRegistry,
            streamConsumerRegistry,
            iterationControllerRegistry,
            onlineAiSupplier,
            // Slice 496 §3.B: file-backed ConversationStore for PERSISTENT shapes.
            // Store conversations alongside the index data (same parent directory).
            conversationStore);
    // Tempdoc 560 Phase 2 — late-bind the engine into the workflow runner (LlmStep delegation).
    engineHolder[0] = conversationEngine;
    // Tempdoc 560 WS5 — the streaming workflow-as-tool runner is late-bound through the agentSupplier
    // wrapper above (set idempotently on the lazily-constructed agent each time it is resolved), and the
    // WorkflowShapeRunner it bridges to was published into wfShapeRunnerHolder at its construction. No
    // one-shot boot set here — that missed the not-yet-constructed agent (the bug live validation caught).
    // Tempdoc 508 §11.5 / §13.5 — FE virtual-operations sidecar.
    // Single global store (V1 deployment is one FE per backend).
    var virtualOperationStore =
        new io.justsearch.app.services.registry.emitter.VirtualOperationStore();
    // Tempdoc 585 §B.5 (Hybrid C) — the agent-control endpoint family is split into a run/control
    // core + the read-axis (AgentSessionController, narrowed to AgentRunQueries) + the tools-axis
    // (AgentToolsController), over one shared AgentSseWriter (the AgentEvent→SSE vocabulary keystone).
    AgentSseWriter agentSseWriter =
        new AgentSseWriter(
            new SseWriter(apiCatalog),
            agentSupplier,
            // Tempdoc 550 thesis III: the plan-preview reads the one shared intent-gate evaluator.
            b.HeadAssembly != null
                ? b.HeadAssembly.substrate().conversation().intentGateEvaluator()
                : null);
    AgentController agentController =
        new AgentController(agentSupplier, conversationEngine, agentSseWriter, telemetry);
    // Tempdoc 560 Phase 2 — the workflow approve/reject endpoints complete the runner's gates.
    agentController.setWorkflowGateRegistry(workflowGateRegistry);
    // Tempdoc 584/585 — the read sub-controller depends on the NARROW AgentRunQueries surface
    // (agentSupplier::get adapts Supplier<AgentService> to Supplier<AgentRunQueries>).
    AgentSessionController agentSessionController =
        new AgentSessionController(agentSupplier::get, telemetry, runChannelRegistry);
    AgentToolsController agentToolsController =
        new AgentToolsController(agentSupplier, virtualOperationStore);
    ChatController chatController =
        new ChatController(
            conversationEngine,
            new SseWriter(apiCatalog),
            telemetry,
            conversationStore,
            // Tempdoc 610 Phase D — the one-shot summarizer for compaction.
            onlineAiSupplier,
            // Tempdoc 859 slice C PR-2 — the SECOND conversation record: a delegate run persists a
            // whole conversation here and no ConversationStore row, so the list joins both.
            agentSupplier);
    // Tempdoc 834 §1.6 / §15.1.3 — the run-stream family. It dispatches THROUGH ChatController's
    // sink-taking entry point rather than around it, so a mid-run failure reaches every observer of
    // the run instead of only the socket that started it.
    RunStreamController runStreamController =
        new RunStreamController(runChannelRegistry, chatController);
    io.justsearch.ui.api.mcp.McpProtocolHandler mcpProtocolHandler = null;
    if (registryPresent) {
      // Tempdoc 501 Phase 15: thread the runtime-manifest publisher through so the
      // justsearch_runtime_manifest MCP tool can serve the redacted manifest.
      final io.justsearch.ui.runtime.RuntimeManifestPublisher manifestPubForMcp =
          b.runtimeManifestPublisher;
      var mcpSurface = new io.justsearch.ui.api.mcp.McpToolSurface(
          List.of(
              b.HeadAssembly.substrate().operations().operations(),
              b.HeadAssembly.substrate().operations().agentTools()),
          b.HeadAssembly.substrate().operations().executor(),
          knowledgeSearchControllerSupplier,
          () -> b.HeadAssembly,
          java.time.Clock.systemUTC(),
          () -> manifestPubForMcp,
          // Tempdoc 655: the SAME pending-authorization store/broadcast the REST gate path uses
          // (ResourceApiModule), so an MCP-originated gate firing is visible to the same approve
          // endpoint and the same live shell signal — not a second, parallel mechanism.
          b.HeadAssembly.substrate().conversation().pendingAuthorizationStore(),
          b.HeadAssembly.substrate().conversation().pendingAuthorizationChanges());
      mcpProtocolHandler =
          new io.justsearch.ui.api.mcp.McpProtocolHandler(
              mcpSurface,
              List.of(
                  b.HeadAssembly.substrate().resources().resources(),
                  b.HeadAssembly.substrate().resources().runtimeContext(),
                  b.HeadAssembly.substrate().resources().serverCapabilities(),
                  b.HeadAssembly.substrate().resources().operationHistory(),
                  b.HeadAssembly.substrate().advisory().resources(),
                  b.HeadAssembly.substrate().resources().indexingJobs(),
                  b.HeadAssembly.substrate().resources().failedIndexingJobs(),
                  b.HeadAssembly.substrate().resources().indexedRoots(),
                  b.HeadAssembly.substrate().resources().conditionRecoveryIndex(),
                  // Tempdoc 571 §4c: the action-ledger Resource — sibling of operation-history,
                  // exposed to the agent's resources/list like its sibling.
                  b.HeadAssembly.substrate().resources().actionLedger(),
                  b.HeadAssembly.substrate().metrics().jobQueueDepthCatalog(),
                  b.HeadAssembly.substrate().metrics().documentsIndexedRateCatalog(),
                  b.HeadAssembly.substrate().metrics().gpuUtilizationCatalog(),
                  b.HeadAssembly.substrate().metrics().gpuMemoryUtilizationCatalog()));
    }
    return new Result(
        resolveAddressController,
        agentController,
        agentSessionController,
        agentToolsController,
        chatController,
        runStreamController,
        mcpProtocolHandler,
        conversationStore);
  }

  /**
   * Resolved RAG settings, read once at this composition root (tempdoc 799 N.2).
   *
   * <p>Both settings resolved correctly and were read by nothing before this: {@code
   * justsearch.rag.top_k} lost to a hardcoded 5, and {@code justsearch.citation.match_threshold}
   * was typed String, never parsed, and lost to a hardcoded 0.5.
   *
   * <p>Reading config HERE rather than inside the SPI classes keeps them constructor-injected and
   * unit-testable. Falls back to the compiled defaults when no ConfigStore is installed (tests,
   * early boot), mirroring {@code DefaultWorkerAppServices.resolvedOcrConfig()}.
   */
  private static io.justsearch.configuration.resolved.ResolvedConfig.Rag resolvedRag() {
    io.justsearch.configuration.resolved.ConfigStore store =
        io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    return store == null || store.get() == null ? null : store.get().rag();
  }
}
