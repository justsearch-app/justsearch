/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.core.context.EngineContext;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.gpl.RerankerService;
import io.justsearch.app.api.knowledge.FolderBrowseRequest;
import io.justsearch.app.api.knowledge.FolderBrowseResponse;
import io.justsearch.app.api.knowledge.FolderFilesRequest;
import io.justsearch.app.api.knowledge.FolderFilesResponse;
import io.justsearch.app.api.knowledge.KnowledgeIngestResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeStatus;
import io.justsearch.ipc.BatchResponse;
import io.justsearch.ipc.FolderEntry;
import io.justsearch.ipc.FolderFileEntry;
import io.justsearch.ipc.ListFolderFilesResponse;
import io.justsearch.ipc.ListFoldersResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Adapter that maps the Knowledge HTTP API contract (app-api DTOs) to/from gRPC proto DTOs.
 *
 * <p>UI controllers should not import proto DTOs directly; this class is the intended boundary.
 *
 * <p>Tempdoc 556 (F-C4.2): the search-execution engine (search/status + retrieval orchestration,
 * reranking, async query understanding, trace + result mapping) lives in {@link KnowledgeSearchEngine} and
 * its collaborators ({@link SearchRequestMapper}, {@link SearchResultMapper}, {@link
 * SearchTraceMapper}, {@link SearchPipelinePresets}, {@link SearchPerSourceExecutor}). This class is
 * a thin facade: it delegates search/status to {@code KnowledgeSearchEngine} and keeps the ingest / scan /
 * folder-browse / suggest pass-throughs that only need the Worker gRPC client.
 */
public final class KnowledgeHttpApiAdapter {

  private final KnowledgeServerBootstrap knowledgeServer;
  private final KnowledgeSearchEngine searchEngine;

  /**
   * Tempdoc 419 / T4: scoped scan-progress registry, bound by the {@code LocalApiServer} composition
   * site via {@link #setScanProgressRegistry}. When unset, {@link #scanRoot} skips registry calls.
   */
  private volatile ScanProgressRegistry scanProgressRegistry;

  /**
   * Tempdoc 812 D2: the scan-rollup aggregator, bound by the same composition site. When unset,
   * {@link #scanRoot} skips rollup calls and only the per-document ledger rows exist.
   */
  private volatile io.justsearch.app.observability.ledger.ScanRollupLedger scanRollupLedger;

  public KnowledgeHttpApiAdapter(KnowledgeServerBootstrap knowledgeServer, SearchPerSourceExecutor perSourceSearch) {
    this(knowledgeServer, perSourceSearch, OnlineAiService.unavailable(), null);
  }

  public KnowledgeHttpApiAdapter(
      KnowledgeServerBootstrap knowledgeServer, SearchPerSourceExecutor perSourceSearch, OnlineAiService onlineAiService) {
    this(knowledgeServer, perSourceSearch, onlineAiService, null);
  }

  public KnowledgeHttpApiAdapter(
      KnowledgeServerBootstrap knowledgeServer, SearchPerSourceExecutor perSourceSearch,
      OnlineAiService onlineAiService,
      RerankerService lambdaMartReranker) {
    this.knowledgeServer = Objects.requireNonNull(knowledgeServer, "knowledgeServer");
    this.searchEngine = new KnowledgeSearchEngine(knowledgeServer, perSourceSearch, onlineAiService, lambdaMartReranker);
  }

  // ========== Search + status (delegated to KnowledgeSearchEngine) ==========

  public KnowledgeSearchResponse search(KnowledgeSearchRequest req, EngineContext engineContext) {
    return searchEngine.search(req, engineContext);
  }

  public KnowledgeStatus status(EngineContext engineContext) {
    return searchEngine.status(engineContext);
  }

  /** Returns the current facet snapshot for filter normalization (366 Phase 6). */
  public String getCachedFacetSnapshot() {
    return searchEngine.getCachedFacetSnapshot();
  }

  public void setWorkerCapability(io.justsearch.app.services.lifecycle.WorkerCapability cap) {
    searchEngine.setWorkerCapability(cap);
  }

  /**
   * 360: Returns whether the reranker config is ready. Used by {@code GplJobCoordinator} to check if
   * remote reranking is available (the Worker hosts the model).
   */
  public boolean isRerankerConfigured() {
    return searchEngine.isRerankerConfigured();
  }

  /**
   * Tempdoc 419 / T4 — wires the scoped scan-progress registry. Composition (LocalApiServer
   * bootstrap) calls this once after constructing the registry so subsequent {@link #scanRoot}
   * invocations bridge to SSE subscribers.
   */
  public void setScanProgressRegistry(ScanProgressRegistry registry) {
    this.scanProgressRegistry = registry;
  }

  /**
   * Tempdoc 812 D2 — wires the scan-rollup aggregator so a directory scan leaves ONE durable
   * audit record instead of only its per-document ephemera. Same composition site as
   * {@link #setScanProgressRegistry}: the Head-side {@code scanRoot} is the one place that knows a
   * scan's id, root, collection and admitted count together.
   */
  public void setScanRollupLedger(io.justsearch.app.observability.ledger.ScanRollupLedger ledger) {
    this.scanRollupLedger = ledger;
  }

  // ========== Ingest / scan / browse / suggest (direct Worker client pass-throughs) ==========

  public KnowledgeIngestResponse ingest(List<Path> files, EngineContext engineContext) {
    return ingest(files, null, engineContext);
  }

  /**
   * Tempdoc 811 (C-2a) — single-file ingest with an explicit collection tag. The tag rides
   * {@code BatchRequest.target_collection} to {@code WorkerIngestService.submitBatch}, which passes it
   * to {@code JobQueue.enqueue} and from there to {@code IndexingDocumentOps}'s {@code collection}
   * field write. A {@code null}/blank collection preserves the pre-811 untagged behaviour.
   */
  public KnowledgeIngestResponse ingest(List<Path> files, String collection, EngineContext engineContext) {
    KnowledgeClient client = knowledgeServer.client();
    BatchResponse r = client.submitBatch(files, false, collection, engineContext);
    return new KnowledgeIngestResponse(r.getAcceptedCount(), r.getErrorMessage());
  }

  /**
   * Tempdoc 418 Phase B — Worker-side directory scan via a server-streaming RPC that walks the root
   * inside the Worker (so {@code WorkerIngestionAuthority} admission applies before any path crosses
   * into indexing). The {@code accepted} field carries the Worker-reported {@code files_admitted};
   * the {@code error} field carries the {@code terminal_reason_code} when the walk did not finish
   * cleanly (empty otherwise).
   */
  public KnowledgeIngestResponse scanRoot(
      String rootPath, String collection, List<String> excludeGlobs, EngineContext engineContext) {
    KnowledgeClient client = knowledgeServer.client();
    // Tempdoc 419 / T4: hold the worker-allocated scanId from the first event so the SSE endpoint at
    // GET /api/scans/{scanId}/progress can subscribe via the registry. The registry handles late
    // subscribers via replay so the UI can open the SSE connection AFTER reading the scanId.
    final ScanProgressRegistry registry = scanProgressRegistry;
    // Tempdoc 812 D2: the rollup aggregator opens the scan on the SAME first-frame signal the
    // progress registry uses, so the audit record and the SSE stream key on one id.
    final io.justsearch.app.observability.ledger.ScanRollupLedger rollup = scanRollupLedger;
    final CancelToken cancelToken = registry == null ? null : new CancelToken();
    final String[] scanIdHolder = {null};
    java.util.function.Consumer<io.justsearch.ipc.ScanRootProgress> progressConsumer =
        progress -> {
          if (scanIdHolder[0] == null && !progress.getScanId().isBlank()) {
            scanIdHolder[0] = progress.getScanId();
            if (registry != null) {
              registry.register(scanIdHolder[0], cancelToken);
            }
            if (rollup != null) {
              rollup.scanStarted(scanIdHolder[0], collection, rootPath);
            }
          }
          if (registry != null && scanIdHolder[0] != null) {
            registry.record(scanIdHolder[0], toScanProgressEvent(progress));
          }
        };
    io.justsearch.ipc.ScanRootProgress terminal;
    try {
      terminal =
          client.scanRoot(
              rootPath,
              collection,
              io.justsearch.ipc.ScanMode.SCAN_MODE_INITIAL,
              excludeGlobs == null ? List.of() : excludeGlobs,
              cancelToken,
              progressConsumer, engineContext);
    } catch (RuntimeException e) {
      // Synthesize a terminal so SSE subscribers always see a clean signal even on RPC failure.
      if (registry != null && scanIdHolder[0] != null) {
        registry.markComplete(
            scanIdHolder[0],
            io.justsearch.app.api.scan.ScanProgressEvent.terminal(scanIdHolder[0], "RPC_FAILED"));
      }
      // The walk ended with no trustworthy admitted count; the rollup closes out on quiescence
      // with whatever the already-enqueued documents actually did.
      if (rollup != null && scanIdHolder[0] != null) {
        rollup.scanEnumerated(
            scanIdHolder[0],
            io.justsearch.app.observability.ledger.ScanRollupLedger.ADMITTED_UNKNOWN);
      }
      throw e;
    }
    long admitted = terminal == null ? 0L : terminal.getFilesAdmitted();
    String reason = terminal == null ? "" : terminal.getTerminalReasonCode();
    String scanId = scanIdHolder[0] != null ? scanIdHolder[0] : "";
    if (rollup != null && !scanId.isEmpty()) {
      rollup.scanEnumerated(scanId, (int) Math.min(admitted, Integer.MAX_VALUE));
    }
    return new KnowledgeIngestResponse(
        (int) Math.min(admitted, Integer.MAX_VALUE), reason, scanId);
  }

  /**
   * Tempdoc 419 / T4 — proto → domain record adapter. {@code ui.api} can't import {@code
   * ipc.ScanRootProgress}, so the conversion happens here at the producer boundary before publishing
   * into the {@link ScanProgressRegistry}.
   */
  private static io.justsearch.app.api.scan.ScanProgressEvent toScanProgressEvent(
      io.justsearch.ipc.ScanRootProgress proto) {
    return new io.justsearch.app.api.scan.ScanProgressEvent(
        proto.getScanId(),
        proto.getFilesWalked(),
        proto.getFilesAdmitted(),
        proto.getFilesSkipped(),
        proto.getBytesWalked(),
        proto.getCurrentDirectory(),
        proto.getComplete(),
        proto.getTerminalReasonCode());
  }

  public List<String> suggest(String query, int limit, EngineContext engineContext) {
    KnowledgeClient client = knowledgeServer.client();
    return client.suggest(query, limit, engineContext).getSuggestionsList();
  }

  public FolderBrowseResponse listFolders(FolderBrowseRequest req, EngineContext engineContext) {
    Objects.requireNonNull(req, "req");
    KnowledgeClient client = knowledgeServer.client();
    int maxFolders = req.maxFolders() == null ? 0 : req.maxFolders();
    ListFoldersResponse proto = client.listFolders(req.parentPath(), maxFolders, engineContext);

    List<FolderBrowseResponse.Folder> folders = new ArrayList<>();
    for (FolderEntry entry : proto.getFoldersList()) {
      folders.add(new FolderBrowseResponse.Folder(
          entry.getPath(),
          entry.getName(),
          entry.getFileCount(),
          entry.getTotalSizeBytes(),
          entry.getLastIndexedAt()));
    }
    return new FolderBrowseResponse(folders, proto.getTookMs(), proto.getTruncated());
  }

  public FolderFilesResponse listFolderFiles(FolderFilesRequest req, EngineContext engineContext) {
    Objects.requireNonNull(req, "req");
    KnowledgeClient client = knowledgeServer.client();
    int limit = req.limit() == null ? 0 : req.limit();
    ListFolderFilesResponse proto = client.listFolderFiles(
        req.folderPath(), limit, req.projection(), engineContext);

    List<FolderFilesResponse.FileEntry> files = new ArrayList<>();
    for (FolderFileEntry entry : proto.getFilesList()) {
      files.add(new FolderFilesResponse.FileEntry(entry.getDocId(), entry.getFieldsMap()));
    }
    return new FolderFilesResponse(files, proto.getTotalCount(), proto.getTookMs());
  }

  /**
   * 360: No-op — reranker lifecycle moved to Worker process. Retained for API compatibility with
   * HeadAssembly shutdown sequence.
   */
  public void closeReranker() {
    // No-op: reranker now lives in the Worker process and is closed by KnowledgeServer
  }
}
