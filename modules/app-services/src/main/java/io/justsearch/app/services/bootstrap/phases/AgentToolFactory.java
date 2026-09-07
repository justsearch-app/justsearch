/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.agent.AgentContextBudgets;
import io.justsearch.agent.tools.AgentToolPaths;
import io.justsearch.agent.tools.BrowseTool;
import io.justsearch.agent.tools.FileOperationLog;
import io.justsearch.agent.tools.FileOperationsTool;
import io.justsearch.agent.tools.IngestTool;
import io.justsearch.agent.tools.ReadDocumentTool;
import io.justsearch.agent.tools.SearchTool;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy;
import io.justsearch.app.services.gpl.LambdaMartReranker;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.util.ContextBudget;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Tempdoc 519 §7 / Step 7: agent-tool factory extracted from the bootstrap's main constructor
 * body. Builds the eager-path KnowledgeHttpApiAdapter + 5 tool instances (Search/Browse/Ingest/
 * FileOperations, and ReadDocument since tempdoc 868 §B.2) when both knowledgeClient and
 * indexingService are available at construction time. Returns null fields in {@link Output} when
 * prerequisites are not met, so the caller can skip registration.
 *
 * <p>Tempdoc 832: {@link #assemble} is the SINGLE construction authority for the agent tool
 * bundle. Both entry points route through it — {@link #build} for the eager path and
 * {@code AgentToolHandlers.registerLateBound} for the connect-time path — so a wiring added here
 * reaches both. It previously existed twice; the scan-observability binding (lane D / PR #453) was
 * added to one copy and silently missing from the other until a second fix caught up.
 */
public final class AgentToolFactory {

  private AgentToolFactory() {}

  /** Eager-path agent-tool bundle. Each field may be null when prerequisites are unavailable. */
  public record Output(
      KnowledgeHttpApiAdapter agentSearchAdapter,
      FileOperationLog fileOperationLog,
      FileOperationsTool fileOperationsTool,
      SearchTool searchTool,
      BrowseTool browseTool,
      IngestTool ingestTool,
      /** Tempdoc 868 §B.2 — the content-bearing read; null when no DocumentService was supplied. */
      ReadDocumentTool readDocumentTool) {}

  /**
   * Build the eager-path agent-tool instances. Every TOOL is null when either
   * {@code knowledgeClient} or {@code indexingService} is null — but {@code fileOperationLog} is
   * not, because it does not depend on either (tempdoc 913 D5).
   *
   * <p>D5: the log is a directory reader/writer over {@code dataDir/file-operations}. It used to
   * ride the all-null guard arm, and on the real boot that arm is the one taken —
   * {@code HeadlessApp} constructs {@code HeadAssembly} with a null knowledgeServer and lets the
   * Worker connect asynchronously. So {@code AgentLoopService} value-captured a null log into a
   * final field with no rebind path, {@code AgentRunQueryService.operationHistory} returned
   * {@code List.of()} forever, and {@code GET /api/chat/agent/history} reported no batches while
   * the journals were on disk the whole time. Returning the log here is what makes the null
   * impossible rather than tolerated: a filesystem reader was gated on Worker availability, which
   * is a dependency it never had.
   */
  public static Output build(
      Path dataDir,
      KnowledgeServerBootstrap knowledgeServer,
      KnowledgeClient knowledgeClient,
      IndexingService indexingService,
      OnlineAiService onlineAiService,
      LambdaMartReranker lambdaMartReranker,
      DocumentService documentService) {
    if (knowledgeClient == null || indexingService == null) {
      return new Output(null, fileOperationLog(dataDir), null, null, null, null, null);
    }
    return assemble(
        dataDir,
        knowledgeServer,
        knowledgeClient,
        indexingService,
        onlineAiService,
        lambdaMartReranker,
        null,
        null,
        null,
        null,
        documentService);
  }

  /**
   * The ONE construction rule for the file-operation journal: {@code dataDir/file-operations}.
   * Null only when no data dir is known, which is also the only case in which no journal can be
   * named. Kept as a named method so the path is spelled once — both entry points and the
   * late-bound reuse path go through it.
   */
  private static FileOperationLog fileOperationLog(Path dataDir) {
    return dataDir == null ? null : new FileOperationLog(dataDir.resolve("file-operations"));
  }

  /**
   * Tempdoc 832 — the one place the agent tool bundle is composed. Callers own their own
   * prerequisite checks: this method assumes {@code knowledgeClient}, {@code indexingService} and
   * {@code dataDir} are usable and does not re-guard them (the eager guard lives in {@link #build};
   * the late-bound guards live in {@code AgentToolHandlers.registerLateBound}).
   *
   * @param existingAdapter reuse this adapter when non-null, otherwise build a fresh one. The
   *     late-bound path passes the eager-path adapter when the eager path produced one; on the
   *     normal (async-Worker) boot it is null and the adapter built here is the one
   *     {@code IngestTool} actually drives.
   * @param existingFileOperationLog reuse this journal when non-null, otherwise build a fresh one —
   *     the same reuse contract as {@code existingAdapter}, added by tempdoc 913 D5. On the normal
   *     boot the eager path always produces one now, so the late-bound path receives it and the
   *     process holds exactly ONE instance: the one the read side (agent history / undo) queries is
   *     the one {@code FileOperationsTool} writes through. It also drops the second run of the
   *     constructor's 30-day retention prune that {@code AgentToolHandlers} calls out as the reason
   *     re-assembling is not side-effect free.
   * @param scanProgressRegistry scan-progress SSE registry, bound onto whichever adapter is used;
   *     null on the eager path, where neither collaborator exists yet (see
   *     {@link #bindScanObservability}).
   * @param scanRollupLedger scan-rollup ledger, same lifecycle caveat as the registry.
   * @param documentService the Worker-backed document fetch {@code ReadDocumentTool} pages over
   *     (tempdoc 868 §B.2). Null only where no read capability can be offered; the tool is then
   *     null and its handler is not registered, exactly as the other null-tolerant fields behave.
   */
  static Output assemble(
      Path dataDir,
      KnowledgeServerBootstrap knowledgeServer,
      KnowledgeClient knowledgeClient,
      IndexingService indexingService,
      OnlineAiService onlineAiService,
      LambdaMartReranker lambdaMartReranker,
      KnowledgeHttpApiAdapter existingAdapter,
      FileOperationLog existingFileOperationLog,
      io.justsearch.app.services.worker.ScanProgressRegistry scanProgressRegistry,
      io.justsearch.app.observability.ledger.ScanRollupLedger scanRollupLedger,
      DocumentService documentService) {
    KnowledgeHttpApiAdapter agentSearchAdapter =
        existingAdapter != null
            ? existingAdapter
            : new KnowledgeHttpApiAdapter(knowledgeServer, onlineAiService, lambdaMartReranker);
    bindScanObservability(agentSearchAdapter, scanProgressRegistry, scanRollupLedger);
    FileOperationLog fileOperationLog =
        existingFileOperationLog != null ? existingFileOperationLog : fileOperationLog(dataDir);
    Supplier<List<BrowseTool.RootInfo>> rootsSupplier =
        () ->
            indexingService.getWatchedRoots().stream()
                .map(
                    r ->
                        new BrowseTool.RootInfo(
                            r.path().toAbsolutePath().normalize().toString(),
                            r.path().getFileName().toString()))
                .toList();
    // Tempdoc 877 §2.4: ONE roots view for the whole bundle. Every tool that resolves or validates a
    // path reads the indexed roots through it, so there is one guarded accessor, one degrade-open
    // rule and one relative→absolute algorithm rather than the five near-copies this replaced.
    AgentToolPaths.RootsView rootsView = AgentToolPaths.RootsView.of(rootsSupplier);
    // Tempdoc 877 §2.7: file-operations takes the roots view too — NOT as a second sandbox (that
    // stays `indexingService::getWatchedPaths`, one argument earlier) but so a root-relative path
    // the model echoed back from a browse result resolves instead of failing DEST_NOT_SANDBOXED.
    FileOperationsTool fileOperationsTool =
        new FileOperationsTool(
            indexingService::getWatchedPaths,
            knowledgeClient::updateDocumentPaths,
            fileOperationLog,
            rootsView);
    // Tempdoc 883 decision 3: ONE live context budget for the whole bundle, read per tool call.
    // The tools cannot see the AgentSession (they are OperationHandlers dispatched by id), so the
    // window reaches them as a supplier bound here, where the OnlineAiService already is.
    Supplier<ContextBudget> contextBudget = () -> AgentContextBudgets.forCall(onlineAiService);
    SearchTool searchTool =
        new SearchTool(agentSearchAdapter::search, rootsView, contextBudget);
    BrowseTool browseTool =
        new BrowseTool(
            agentSearchAdapter::listFolders, agentSearchAdapter::listFolderFiles, rootsView);
    IngestTool ingestTool =
        new IngestTool(
            agentSearchAdapter::ingest,
            agentSearchAdapter::scanRoot,
            rootsView,
            () -> rootBindings(indexingService));
    // Tempdoc 868 §B.2: the read tool rides the SAME roots view as search, so `path` validation
    // and `path_prefix` validation share one authority and one degrade-open rule. The fetch is the
    // Worker's FetchDocumentSlice via DocumentService — the Head still never reads document bytes.
    ReadDocumentTool readDocumentTool =
        documentService == null
            ? null
            : new ReadDocumentTool(documentService::fetchSlice, rootsView, contextBudget);
    return new Output(
        agentSearchAdapter,
        fileOperationLog,
        fileOperationsTool,
        searchTool,
        browseTool,
        ingestTool,
        readDocumentTool);
  }

  /**
   * Tempdoc 832 (lane D) — binds the scan-progress registry and scan-rollup ledger onto an
   * agent-owned adapter. Without this, a directory ingest driven through {@code IngestTool} (agent
   * loop / MCP {@code justsearch_ingest}) emitted no scan-progress SSE and left no rollup row, while
   * the byte-identical ingest through {@code /api/knowledge/ingest} did — the setters were only ever
   * called on the controller-owned adapter.
   *
   * <p>{@link #assemble} calls this for the adapter it composes the bundle around. It stays public
   * because the eager path cannot supply the collaborators at composition time — neither exists yet
   * when {@link #build} runs (the ledger is created by the substrate phase and the registry by
   * {@code LocalApiServer}, both strictly after the service phase), so {@code HeadAssembly} binds
   * the eager adapter later, out of band. The agent adapter stays its own instance — sharing the
   * controller's would couple two lifecycles.
   */
  public static void bindScanObservability(
      KnowledgeHttpApiAdapter agentSearchAdapter,
      io.justsearch.app.services.worker.ScanProgressRegistry scanProgressRegistry,
      io.justsearch.app.observability.ledger.ScanRollupLedger scanRollupLedger) {
    if (agentSearchAdapter == null) return;
    if (scanProgressRegistry != null) agentSearchAdapter.setScanProgressRegistry(scanProgressRegistry);
    if (scanRollupLedger != null) agentSearchAdapter.setScanRollupLedger(scanRollupLedger);
  }

  /**
   * Tempdoc 811 (C-2a) — projects the watched-root registry into the ingest-tagging authority so an
   * agent/MCP ingest of an in-root path inherits that root's collection instead of writing an
   * unlabeled document. Best-effort: a Worker-unavailable lookup yields an empty list, which makes
   * every path resolve out-of-root (`mcp-ingest`).
   */
  static List<IngestCollectionPolicy.RootBinding> rootBindings(IndexingService indexingService) {
    try {
      return indexingService.getWatchedRoots().stream()
          .filter(r -> r != null && r.path() != null)
          .map(r -> new IngestCollectionPolicy.RootBinding(r.path(), r.collection()))
          .toList();
    } catch (RuntimeException e) {
      return List.of();
    }
  }
}
