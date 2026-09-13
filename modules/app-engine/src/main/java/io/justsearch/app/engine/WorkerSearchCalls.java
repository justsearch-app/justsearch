/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.services.worker.SearchServiceCalls;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerSearchService;

/**
 * Binds {@link SearchServiceCalls} to the converted {@link WorkerSearchService} for one call (lane F stage A item A6).
 *
 * <p>This is the whole of "the ports as direct calls": each method is the same request the wire
 * carried, handed straight to the worker service in this JVM. The {@link CallContext} is per-call —
 * it carries the trace and request ids and the deadline's cancellation signal — so an instance of
 * this class is per-call too, never cached.
 */
final class WorkerSearchCalls implements SearchServiceCalls {

  private final WorkerSearchService service;
  private final CallContext ctx;

  WorkerSearchCalls(WorkerSearchService service, CallContext ctx) {
    this.service = service;
    this.ctx = ctx;
  }

  @Override
  public io.justsearch.ipc.SearchResponse search(io.justsearch.ipc.SearchRequest request) {
    return service.search(request, ctx);
  }

  @Override
  public io.justsearch.ipc.SuggestResponse suggest(io.justsearch.ipc.SuggestRequest request) {
    return service.suggest(request, ctx);
  }

  @Override
  public io.justsearch.ipc.FetchDocumentsResponse fetchDocuments(io.justsearch.ipc.FetchDocumentsRequest request) {
    return service.fetchDocuments(request, ctx);
  }

  @Override
  public io.justsearch.ipc.FetchDocumentSliceResponse fetchDocumentSlice(io.justsearch.ipc.FetchDocumentSliceRequest request) {
    return service.fetchDocumentSlice(request, ctx);
  }

  @Override
  public io.justsearch.ipc.RetrieveContextResponse retrieveContext(io.justsearch.ipc.RetrieveContextRequest request) {
    return service.retrieveContext(request, ctx);
  }

  @Override
  public io.justsearch.ipc.MatchCitationsResponse matchCitations(io.justsearch.ipc.MatchCitationsRequest request) {
    return service.matchCitations(request, ctx);
  }

  @Override
  public io.justsearch.ipc.ListFoldersResponse listFolders(io.justsearch.ipc.ListFoldersRequest request) {
    return service.listFolders(request, ctx);
  }

  @Override
  public io.justsearch.ipc.ListFolderFilesResponse listFolderFiles(io.justsearch.ipc.ListFolderFilesRequest request) {
    return service.listFolderFiles(request, ctx);
  }

  @Override
  public io.justsearch.ipc.ListAllDocumentIdsResponse listAllDocumentIds(io.justsearch.ipc.ListAllDocumentIdsRequest request) {
    return service.listAllDocumentIds(request, ctx);
  }

  @Override
  public io.justsearch.ipc.RerankResponse rerank(io.justsearch.ipc.RerankRequest request) {
    return service.rerank(request, ctx);
  }
}
