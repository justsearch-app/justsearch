/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.core.context.EngineContext;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.gpl.RerankerService;
import io.justsearch.app.api.knowledge.FolderBrowseRequest;
import io.justsearch.app.api.knowledge.FolderBrowseResponse;
import io.justsearch.app.api.knowledge.FolderFilesRequest;
import io.justsearch.app.api.knowledge.FolderFilesResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeStatus;
import io.justsearch.ipc.FolderEntry;
import io.justsearch.ipc.FolderFileEntry;
import io.justsearch.ipc.ListFolderFilesResponse;
import io.justsearch.ipc.ListFoldersResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Adapter that maps the Knowledge HTTP API contract (app-api DTOs) to/from the index-half protocol DTOs.
 *
 * <p>UI controllers should not import proto DTOs directly; this class is the intended boundary.
 *
 * <p>Tempdoc 556 (F-C4.2): the search-execution engine (search/status + retrieval orchestration,
 * reranking, async query understanding, trace + result mapping) lives in {@link KnowledgeSearchEngine} and
 * its collaborators ({@link SearchRequestMapper}, {@link SearchResultMapper}, {@link
 * SearchTraceMapper}, {@link SearchPipelinePresets}, {@link SearchPerSourceExecutor}). This class is
 * a thin facade: it delegates search/status to {@code KnowledgeSearchEngine} and keeps folder-browse and suggest pass-throughs through the Worker ports.
 */
public final class KnowledgeHttpApiAdapter {

  private final KnowledgeServerBootstrap knowledgeServer;
  private final KnowledgeSearchEngine searchEngine;

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
    this(knowledgeServer, perSourceSearch, onlineAiService, lambdaMartReranker, null);
  }

  public KnowledgeHttpApiAdapter(
      KnowledgeServerBootstrap knowledgeServer, SearchPerSourceExecutor perSourceSearch,
      OnlineAiService onlineAiService, RerankerService lambdaMartReranker,
      io.justsearch.configuration.resolved.ConfigStore configStore) {
    this.knowledgeServer = Objects.requireNonNull(knowledgeServer, "knowledgeServer");
    this.searchEngine = new KnowledgeSearchEngine(knowledgeServer, perSourceSearch, onlineAiService, lambdaMartReranker, configStore);
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

  public void setWorkerCapability(io.justsearch.app.api.lifecycle.Capability cap) {
    searchEngine.setWorkerCapability(cap);
  }

  /**
   * 360: Returns whether the reranker config is ready. Used by {@code GplJobCoordinator} to check if
   * remote reranking is available (the Worker hosts the model).
   */
  public boolean isRerankerConfigured() {
    return searchEngine.isRerankerConfigured();
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
