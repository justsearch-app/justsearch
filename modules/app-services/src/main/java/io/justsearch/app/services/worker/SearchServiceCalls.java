/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * The unary SearchService calls, as a plain Java interface (lane F stage A item A6).
 *
 * <p>Ten calls: the retrieval surface the Head asks the index half for.
 *
 * <p><b>Why this interface exists.</b> Until A6 the executor seam
 * ({@link SearchRpcExecutor}, {@link IngestRpcExecutor}) was typed on the <em>generated gRPC
 * blocking stub</em>, a final-ish class that only a {@code Channel} can produce. That made the
 * whole ops layer ({@code SearchRpcOps}, {@code MigrationOps}, {@code VduOps}, {@code SyncOps},
 * {@code RootLifecycleOps}) reachable only over the wire, even though none of its logic is about
 * a network. This interface is the same call surface with the same method names and signatures,
 * so every existing {@code stub -> stub.foo(request)} lambda compiles verbatim; what changes is
 * that a second implementation is now possible — the in-process one in
 * {@code io.justsearch.app.engine}, which calls the converted worker service directly.
 *
 * <p>Proto DTOs at the signature are transitional (design §6): without a wire they carry its cost
 * and none of its benefit, and a named follow-up replaces them with the {@code app-api} records.
 */
public interface SearchServiceCalls {

  /** {@code SearchService/Search}. */
  io.justsearch.ipc.SearchResponse search(io.justsearch.ipc.SearchRequest request);

  /** {@code SearchService/Suggest}. */
  io.justsearch.ipc.SuggestResponse suggest(io.justsearch.ipc.SuggestRequest request);

  /** {@code SearchService/FetchDocuments}. */
  io.justsearch.ipc.FetchDocumentsResponse fetchDocuments(io.justsearch.ipc.FetchDocumentsRequest request);

  /** {@code SearchService/FetchDocumentSlice}. */
  io.justsearch.ipc.FetchDocumentSliceResponse fetchDocumentSlice(io.justsearch.ipc.FetchDocumentSliceRequest request);

  /** {@code SearchService/RetrieveContext}. */
  io.justsearch.ipc.RetrieveContextResponse retrieveContext(io.justsearch.ipc.RetrieveContextRequest request);

  /** {@code SearchService/MatchCitations}. */
  io.justsearch.ipc.MatchCitationsResponse matchCitations(io.justsearch.ipc.MatchCitationsRequest request);

  /** {@code SearchService/ListFolders}. */
  io.justsearch.ipc.ListFoldersResponse listFolders(io.justsearch.ipc.ListFoldersRequest request);

  /** {@code SearchService/ListFolderFiles}. */
  io.justsearch.ipc.ListFolderFilesResponse listFolderFiles(io.justsearch.ipc.ListFolderFilesRequest request);

  /** {@code SearchService/ListAllDocumentIds}. */
  io.justsearch.ipc.ListAllDocumentIdsResponse listAllDocumentIds(io.justsearch.ipc.ListAllDocumentIdsRequest request);

  /** {@code SearchService/Rerank}. */
  io.justsearch.ipc.RerankResponse rerank(io.justsearch.ipc.RerankRequest request);
}
