/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.ipc.FetchDocumentSliceRequest;
import io.justsearch.ipc.FetchDocumentSliceResponse;
import io.justsearch.ipc.FetchDocumentsRequest;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.IndexingJobsFrame;
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
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.ScanRootRequest;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SuggestRequest;
import io.justsearch.ipc.SuggestResponse;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A {@link KnowledgeClient} whose transport seams are supplied by the test.
 *
 * <p>Lane F stage A item A10 deleted {@code RemoteKnowledgeClient} and with it the harness several
 * Head-side tests used: a real Netty (or in-process) gRPC server plus a memory-mapped
 * {@code MainSignalBus} whose port field was poked through reflection. Every one of those tests was
 * asking the same question — <em>what request does the Head's own mapping produce, and what does it
 * do with the answer?</em> — and none was asking anything about a socket. This double answers that
 * question directly: the request arrives at {@link SearchCalls} / {@link IngestCalls} exactly as
 * {@code KnowledgeClient} built it, which is the layer the assertions were always about.
 *
 * <p>Deliberately NOT a Mockito mock: the tests below assert on the <em>content</em> of a proto the
 * production code assembles across several helper hops, and a mock that merely records the call
 * would let a mapping regression through if the recording happened above the hop that drops the
 * field.
 */
class TestKnowledgeClient extends KnowledgeClient {

  private final SearchServiceCalls search;
  private final IngestServiceCalls ingest;
  private final Consumer<ScanRootRequest> onScanRoot;

  TestKnowledgeClient(SearchServiceCalls search) {
    this(search, null, null);
  }

  TestKnowledgeClient(
      SearchServiceCalls search, IngestServiceCalls ingest, Consumer<ScanRootRequest> onScanRoot) {
    super(/*deadlineMs=*/ 5000, DEFAULT_BATCH_SIZE, IpcTelemetry.noop());
    this.search = search;
    this.ingest = ingest;
    this.onScanRoot = onScanRoot;
  }

  @Override
  protected <T> T executeSearchRpc(
      String operation, RpcDeadlineCategory category, Function<SearchServiceCalls, T> rpc) {
    if (search == null) {
      throw new UnsupportedOperationException("no SearchServiceCalls wired for " + operation);
    }
    return rpc.apply(search);
  }

  @Override
  protected <T> T executeIngestRpc(
      String operation, RpcDeadlineCategory category, Function<IngestServiceCalls, T> rpc) {
    if (ingest == null) {
      throw new UnsupportedOperationException("no IngestServiceCalls wired for " + operation);
    }
    return rpc.apply(ingest);
  }

  @Override
  protected <T> T executeHealthRpc(
      String operation, long callDeadlineMs, Function<HealthServiceCalls, T> rpc) {
    throw new UnsupportedOperationException("no HealthServiceCalls wired for " + operation);
  }

  @Override
  protected ScanRootProgress executeScanRoot(
      ScanRootRequest request, CancelToken cancelToken, Consumer<ScanRootProgress> progress) {
    if (onScanRoot != null) {
      onScanRoot.accept(request);
    }
    ScanRootProgress terminal = ScanRootProgress.newBuilder().setComplete(true).build();
    progress.accept(terminal);
    return terminal;
  }

  @Override
  public IndexingJobsStream subscribeIndexingJobs(
      Consumer<IndexingJobsFrame> onFrame, Consumer<Throwable> onError, Runnable onCompleted) {
    throw new UnsupportedOperationException("no job stream wired");
  }

  @Override
  protected void closeTransport() {
    // Nothing held.
  }

  /**
   * {@link SearchServiceCalls} with every call refused. Tests override only the calls their
   * scenario reaches, so an unexpected extra call is a loud failure rather than a silent default —
   * which is the property the old fake gRPC services had (an unimplemented method returned
   * UNIMPLEMENTED) and the reason they were not Mockito mocks either.
   */
  abstract static class SearchCalls implements SearchServiceCalls {

    @Override
    public SearchResponse search(SearchRequest request) {
      throw new UnsupportedOperationException("search");
    }

    @Override
    public SuggestResponse suggest(SuggestRequest request) {
      throw new UnsupportedOperationException("suggest");
    }

    @Override
    public FetchDocumentsResponse fetchDocuments(FetchDocumentsRequest request) {
      throw new UnsupportedOperationException("fetchDocuments");
    }

    @Override
    public FetchDocumentSliceResponse fetchDocumentSlice(FetchDocumentSliceRequest request) {
      throw new UnsupportedOperationException("fetchDocumentSlice");
    }

    @Override
    public RetrieveContextResponse retrieveContext(RetrieveContextRequest request) {
      throw new UnsupportedOperationException("retrieveContext");
    }

    @Override
    public MatchCitationsResponse matchCitations(MatchCitationsRequest request) {
      throw new UnsupportedOperationException("matchCitations");
    }

    @Override
    public ListFoldersResponse listFolders(ListFoldersRequest request) {
      throw new UnsupportedOperationException("listFolders");
    }

    @Override
    public ListFolderFilesResponse listFolderFiles(ListFolderFilesRequest request) {
      throw new UnsupportedOperationException("listFolderFiles");
    }

    @Override
    public ListAllDocumentIdsResponse listAllDocumentIds(ListAllDocumentIdsRequest request) {
      throw new UnsupportedOperationException("listAllDocumentIds");
    }

    @Override
    public RerankResponse rerank(RerankRequest request) {
      throw new UnsupportedOperationException("rerank");
    }
  }
}
