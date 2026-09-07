/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.grpc;

import io.grpc.stub.StreamObserver;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.ipc.FetchDocumentSliceRequest;
import io.justsearch.ipc.FetchDocumentSliceResponse;
import io.justsearch.ipc.FetchDocumentsRequest;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.ListAllDocumentIdsRequest;
import io.justsearch.ipc.ListAllDocumentIdsResponse;
import io.justsearch.ipc.ListFolderFilesRequest;
import io.justsearch.ipc.ListFolderFilesResponse;
import io.justsearch.ipc.ListFoldersRequest;
import io.justsearch.ipc.ListFoldersResponse;
import io.justsearch.ipc.MatchCitationsRequest;
import io.justsearch.ipc.MatchCitationsResponse;
import io.justsearch.ipc.RerankRequest;
import io.justsearch.ipc.RerankResponse;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchServiceGrpc;
import io.justsearch.ipc.SuggestRequest;
import io.justsearch.ipc.SuggestResponse;
import java.util.Objects;

/**
 * gRPC adapter for {@link WorkerSearchService}, and the seam that enables runtime service
 * swapping.
 *
 * <p>Registered once with the gRPC server. All RPC calls are forwarded to a {@code volatile}
 * delegate that can be swapped without restarting the gRPC server (hot reload, tempdoc 305).
 *
 * <p>Lane F stage A item A3: the delegate is the converted service, which returns its response
 * instead of writing to a {@code StreamObserver}; this wrapper builds the {@link
 * io.justsearch.indexerworker.services.CallContext} from the two worker-core interceptors, does the
 * {@code onNext} / {@code onCompleted}, and maps {@code WorkerServiceException} back onto the
 * identical status code — so the wire behaviour is unchanged. Deleted at item A9.
 *
 * <p>Non-RPC operations (model wiring, GPU lifecycle) are routed through {@code WorkerAppServices},
 * not through this wrapper.
 */
public final class DelegatingSearchService extends SearchServiceGrpc.SearchServiceImplBase {

  private volatile WorkerSearchService delegate;

  public DelegatingSearchService(WorkerSearchService delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  public void setDelegate(WorkerSearchService delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  // ==================== RPC forwards ====================

  @Override
  public void search(SearchRequest req, StreamObserver<SearchResponse> obs) {
    WorkerServiceCalls.unary(obs, () -> delegate.search(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void suggest(SuggestRequest req, StreamObserver<SuggestResponse> obs) {
    WorkerServiceCalls.unary(obs, () -> delegate.suggest(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void fetchDocuments(FetchDocumentsRequest req, StreamObserver<FetchDocumentsResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.fetchDocuments(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void fetchDocumentSlice(
      FetchDocumentSliceRequest req, StreamObserver<FetchDocumentSliceResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.fetchDocumentSlice(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void retrieveContext(
      RetrieveContextRequest req, StreamObserver<RetrieveContextResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.retrieveContext(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void matchCitations(
      MatchCitationsRequest req, StreamObserver<MatchCitationsResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.matchCitations(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void listFolders(ListFoldersRequest req, StreamObserver<ListFoldersResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.listFolders(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void listFolderFiles(
      ListFolderFilesRequest req, StreamObserver<ListFolderFilesResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.listFolderFiles(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void listAllDocumentIds(
      ListAllDocumentIdsRequest req, StreamObserver<ListAllDocumentIdsResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.listAllDocumentIds(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void rerank(RerankRequest req, StreamObserver<RerankResponse> obs) {
    WorkerServiceCalls.unary(obs, () -> delegate.rerank(req, WorkerServiceCalls.callContext(obs)));
  }
}
