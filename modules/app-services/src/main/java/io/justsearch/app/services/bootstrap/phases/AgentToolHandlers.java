/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.services.gpl.LambdaMartReranker;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 519 §7 / Step 7: agent-tool handler registration helpers extracted from
 * {@code HeadAssembly}.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #registerEager} — constructor-time registration when the KnowledgeServer is
 *       provided up front (typical of test paths). Registers only the non-null tools carried by
 *       the supplied {@link AgentToolFactory.Output} bundle.
 *   <li>{@link #registerLateBound} — connect-time registration after the worker channel comes
 *       up. Delegates composition of the tool bundle to {@link AgentToolFactory} (tempdoc 832: one
 *       construction authority) and registers what it returns. Idempotent PER REF (tempdoc 876
 *       §B.5): a ref already present (typically registered by {@link #registerEager}) is left
 *       alone rather than the whole call short-circuiting on one sentinel ref — the two paths
 *       compose instead of one excluding the other.
 * </ul>
 */
public final class AgentToolHandlers {

  private static final Logger log = LoggerFactory.getLogger(AgentToolHandlers.class);

  private AgentToolHandlers() {}

  /**
   * Registers {@code handler} at {@code ref} and records the ref, so the completion log cannot
   * drift from the fact (tempdoc 877 §2.10) — unless {@code ref} is already registered, in which
   * case this is a no-op and the ref is NOT recorded, because this call did not add it.
   *
   * <p>Tempdoc 876 §B.5: skip-if-present belongs here and only here. {@link
   * HandlerRegistry#register} keeps its throw-on-duplicate contract for every other caller — the
   * eager and late-bound agent-tool paths are the only pair designed to both run against the same
   * registry. Before 876 they did not compose: {@code registerLateBound} returned early whenever
   * SEARCH_INDEX was already present, one ref standing proxy for all of them, which left {@code
   * core.remember} permanently unhandled.
   */
  private static void register(
      HandlerRegistry operationHandlers,
      List<OperationRef> registered,
      OperationRef ref,
      OperationHandler handler) {
    if (operationHandlers.resolve(ref).isPresent()) {
      return;
    }
    operationHandlers.register(ref, handler);
    registered.add(ref);
  }

  /** Eager-path convenience: register if absent, without a ref ledger. */
  private static void registerIfAbsent(
      HandlerRegistry operationHandlers, OperationRef ref, OperationHandler handler) {
    register(operationHandlers, new ArrayList<>(), ref, handler);
  }

  /**
   * True when every ref {@link #registerLateBound} could register already resolves. REMEMBER counts
   * only when a {@code memoryStore} exists to back it — without one there is nothing this path
   * could add for that ref.
   *
   * <p>READ_DOCUMENT counts unconditionally because this check runs BEFORE {@code
   * AgentToolFactory.assemble}, so it cannot yet know whether the factory will return a null read
   * tool. When it does (no {@code DocumentService}), that ref never resolves and this guard returns
   * false on every call — the check is simply inert there, which costs a repeated assemble but
   * never skips a ref that was still registerable.
   */
  private static boolean allLateBoundRefsPresent(
      HandlerRegistry operationHandlers, io.justsearch.agent.api.memory.MemoryStore memoryStore) {
    List<OperationRef> required =
        new ArrayList<>(
            List.of(
                AgentToolsOperationCatalog.SEARCH_INDEX,
                AgentToolsOperationCatalog.READ_DOCUMENT,
                AgentToolsOperationCatalog.BROWSE_FOLDERS,
                AgentToolsOperationCatalog.INGEST_FILES,
                AgentToolsOperationCatalog.FILE_OPERATIONS));
    if (memoryStore != null) {
      required.add(AgentToolsOperationCatalog.REMEMBER);
    }
    for (OperationRef ref : required) {
      if (operationHandlers.resolve(ref).isEmpty()) {
        return false;
      }
    }
    return true;
  }

  /** Eager-path registration: register only the bundle's non-null tools, skipping any ref
   * already registered (idempotent, symmetric with {@link #registerLateBound}). */
  public static void registerEager(
      HandlerRegistry operationHandlers, AgentToolFactory.Output agentTools) {
    if (agentTools.searchTool() != null) {
      registerIfAbsent(
          operationHandlers, AgentToolsOperationCatalog.SEARCH_INDEX, agentTools.searchTool());
    }
    if (agentTools.readDocumentTool() != null) {
      registerIfAbsent(
          operationHandlers,
          AgentToolsOperationCatalog.READ_DOCUMENT,
          agentTools.readDocumentTool());
    }
    if (agentTools.browseTool() != null) {
      registerIfAbsent(
          operationHandlers, AgentToolsOperationCatalog.BROWSE_FOLDERS, agentTools.browseTool());
    }
    if (agentTools.ingestTool() != null) {
      registerIfAbsent(
          operationHandlers, AgentToolsOperationCatalog.INGEST_FILES, agentTools.ingestTool());
    }
    if (agentTools.fileOperationsTool() != null) {
      registerIfAbsent(
          operationHandlers,
          AgentToolsOperationCatalog.FILE_OPERATIONS,
          agentTools.fileOperationsTool());
    }
  }

  /**
   * Late-bound registration: obtain the tool instances from {@link AgentToolFactory#assemble} and
   * register them, skipping any ref already registered by {@link #registerEager} (tempdoc 876
   * §B.5 — the two paths compose, they no longer exclude each other via a single sentinel ref).
   *
   * @return true if the prerequisite guards passed and registration was attempted (refs already
   *     present are left untouched, refs not yet present are newly registered); false if a
   *     prerequisite (worker capability, knowledge server, or data dir) was missing.
   */
  public static boolean registerLateBound(
      io.justsearch.app.services.worker.SearchPerSourceExecutor perSourceSearch,
      HandlerRegistry operationHandlers,
      KnowledgeServerBootstrap knowledgeServer,
      KnowledgeClient knowledgeClient,
      WorkerCapability workerCapability,
      Path dataDir,
      IndexingService indexingService,
      OnlineAiService onlineAiService,
      LambdaMartReranker lambdaMartReranker,
      KnowledgeHttpApiAdapter existingAdapter,
      io.justsearch.agent.tools.FileOperationLog existingFileOperationLog,
      io.justsearch.agent.api.memory.MemoryStore memoryStore,
      io.justsearch.app.services.worker.ScanProgressRegistry scanProgressRegistry,
      io.justsearch.app.observability.ledger.ScanRollupLedger scanRollupLedger,
      DocumentService documentService) {
    if (knowledgeClient == null || !workerCapability.available()) {
      log.warn("registerAgentToolHandlers skipped: knowledgeClient or worker capability unavailable");
      return false;
    }
    if (knowledgeServer == null) {
      log.warn("registerAgentToolHandlers skipped: knowledgeServer is null");
      return false;
    }
    if (dataDir == null) {
      log.warn("registerAgentToolHandlers skipped: dataDir not initialized");
      return false;
    }
    // Tempdoc 876 §B.5: nothing-to-do check over the WHOLE set this path can register, which is a
    // different thing from the sentinel it replaced — that one let SEARCH_INDEX stand proxy for the
    // rest and so permanently suppressed REMEMBER. This one skips only when every ref is already
    // handled, so the two paths still compose. It matters because assemble() is not side-effect
    // free: it builds a KnowledgeHttpApiAdapter and, when the caller passes no journal to reuse, a
    // FileOperationLog whose constructor runs a diagnostic retention prune. (Tempdoc 913 D5: on the
    // normal boot the eager path now hands its journal down via existingFileOperationLog, so that
    // second construction — and its second prune — no longer happens.)
    if (allLateBoundRefsPresent(operationHandlers, memoryStore)) {
      return true;
    }
    // Tempdoc 832: one construction authority. This path used to duplicate AgentToolFactory's
    // assembly, which is how the lane-D scan-observability wiring reached only one of the two
    // copies. Registration is this method's job; composition is the factory's.
    AgentToolFactory.Output tools =
        AgentToolFactory.assemble(
            perSourceSearch,
            dataDir,
            knowledgeServer,
            knowledgeClient,
            indexingService,
            onlineAiService,
            lambdaMartReranker,
            existingAdapter,
            existingFileOperationLog,
            scanProgressRegistry,
            scanRollupLedger,
            documentService);
    // Tempdoc 877 §2.10: the log line is DERIVED from what this method actually registered. It
    // used to hand-list the names, which is a second authority that drifts the moment a
    // conditional registration is skipped (READ_DOCUMENT and REMEMBER both are, below).
    //
    // Tempdoc 876 §B.5 converges with that: register(...) skips a ref already present rather than
    // throwing, because 876 deleted the SEARCH_INDEX sentinel that used to make the whole method
    // return early. 877's helper was safe only BECAUSE of that sentinel — with it gone, a throwing
    // register would blow up the moment the eager path had already claimed a ref. Skipping keeps
    // both facts: the two paths compose, and the log still names exactly what THIS call added.
    List<OperationRef> registered = new ArrayList<>();
    register(
        operationHandlers, registered, AgentToolsOperationCatalog.SEARCH_INDEX, tools.searchTool());
    if (tools.readDocumentTool() != null) {
      register(
          operationHandlers,
          registered,
          AgentToolsOperationCatalog.READ_DOCUMENT,
          tools.readDocumentTool());
    }
    register(
        operationHandlers,
        registered,
        AgentToolsOperationCatalog.BROWSE_FOLDERS,
        tools.browseTool());
    register(
        operationHandlers, registered, AgentToolsOperationCatalog.INGEST_FILES, tools.ingestTool());
    register(
        operationHandlers,
        registered,
        AgentToolsOperationCatalog.FILE_OPERATIONS,
        tools.fileOperationsTool());
    // Tempdoc 561 P-E: the learning producer — core_remember persists durable facts into the shared
    // single-authority MemoryStore (the same instance /api/memory reads).
    if (memoryStore != null) {
      register(
          operationHandlers,
          registered,
          AgentToolsOperationCatalog.REMEMBER,
          new io.justsearch.app.services.registry.operations.handlers.RememberFactHandler(
              memoryStore));
    }
    log.info(
        "AgentTools operation handlers registered: {}",
        registered.stream().map(OperationRef::value).collect(Collectors.joining(", ")));
    return true;
  }

}
