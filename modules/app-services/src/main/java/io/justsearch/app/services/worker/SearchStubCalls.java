/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * Adapts a generated SearchService blocking stub to {@link SearchServiceCalls} (lane F stage A item A6).
 *
 * <p>Pure forwarding, one line per RPC. It exists because the generated stub is a class only a
 * {@code Channel} can produce, so it cannot be the type the ops layer is written against once a
 * second, in-process implementation of the same calls has to exist. Deleted at item A10 together
 * with {@link RemoteKnowledgeClient}.
 */
final class SearchStubCalls implements SearchServiceCalls {

  private final io.justsearch.ipc.SearchServiceGrpc.SearchServiceBlockingStub stub;

  SearchStubCalls(io.justsearch.ipc.SearchServiceGrpc.SearchServiceBlockingStub stub) {
    this.stub = stub;
  }

  @Override
  public io.justsearch.ipc.SearchResponse search(io.justsearch.ipc.SearchRequest request) {
    return stub.search(request);
  }

  @Override
  public io.justsearch.ipc.SuggestResponse suggest(io.justsearch.ipc.SuggestRequest request) {
    return stub.suggest(request);
  }

  @Override
  public io.justsearch.ipc.FetchDocumentsResponse fetchDocuments(io.justsearch.ipc.FetchDocumentsRequest request) {
    return stub.fetchDocuments(request);
  }

  @Override
  public io.justsearch.ipc.FetchDocumentSliceResponse fetchDocumentSlice(io.justsearch.ipc.FetchDocumentSliceRequest request) {
    return stub.fetchDocumentSlice(request);
  }

  @Override
  public io.justsearch.ipc.RetrieveContextResponse retrieveContext(io.justsearch.ipc.RetrieveContextRequest request) {
    return stub.retrieveContext(request);
  }

  @Override
  public io.justsearch.ipc.MatchCitationsResponse matchCitations(io.justsearch.ipc.MatchCitationsRequest request) {
    return stub.matchCitations(request);
  }

  @Override
  public io.justsearch.ipc.ListFoldersResponse listFolders(io.justsearch.ipc.ListFoldersRequest request) {
    return stub.listFolders(request);
  }

  @Override
  public io.justsearch.ipc.ListFolderFilesResponse listFolderFiles(io.justsearch.ipc.ListFolderFilesRequest request) {
    return stub.listFolderFiles(request);
  }

  @Override
  public io.justsearch.ipc.ListAllDocumentIdsResponse listAllDocumentIds(io.justsearch.ipc.ListAllDocumentIdsRequest request) {
    return stub.listAllDocumentIds(request);
  }

  @Override
  public io.justsearch.ipc.RerankResponse rerank(io.justsearch.ipc.RerankRequest request) {
    return stub.rerank(request);
  }
}
