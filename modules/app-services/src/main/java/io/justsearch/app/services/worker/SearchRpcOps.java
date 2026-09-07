/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

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
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.PipelineConfigs;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SuggestRequest;
import io.justsearch.ipc.SuggestResponse;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Search, suggest, fetch, and RAG context RPC operations.
 *
 * <p>Each method builds a proto request and delegates to the {@link SearchRpcExecutor} for circuit
 * breaker, deadline, and transport handling. Extracted from {@link KnowledgeClient}.
 */
final class SearchRpcOps {

    private final SearchRpcExecutor rpc;

    SearchRpcOps(SearchRpcExecutor rpc) {
        this.rpc = Objects.requireNonNull(rpc, "rpc");
    }

    // ========== Search ==========

    /**
     * Performs a search query using the default TEXT pipeline.
     *
     * @param query the query string
     * @param limit maximum results to return
     * @return search response
     */
    SearchResponse search(String query, int limit) {
        return search(query, limit, PipelineConfigs.TEXT);
    }

    /**
     * Performs a search query with the specified pipeline configuration.
     *
     * @param query the query string
     * @param limit maximum results to return
     * @param pipeline the pipeline configuration
     * @return search response
     */
    SearchResponse search(String query, int limit, PipelineConfig pipeline) {
        SearchRequest request = SearchRequest.newBuilder()
                .setQuery(query)
                .setLimit(limit)
                .setPipeline(pipeline)
                .build();
        return search(request);
    }

    /**
     * Performs a search query using a fully-specified gRPC {@link SearchRequest}.
     *
     * <p>This is used by the Head HTTP API to forward structured filters/facets/projection to the
     * Worker.
     */
    SearchResponse search(SearchRequest request) {
        SearchRequest req = request == null ? SearchRequest.newBuilder().build() : request;
        return rpc.execute(
                "search",
                KnowledgeClient.RpcDeadlineCategory.STANDARD,
                stub -> stub.search(req));
    }

    /**
     * Performs a vector search with a pre-computed query vector.
     *
     * @param queryVector the query vector
     * @param limit maximum results to return
     * @return search response
     */
    SearchResponse searchVector(List<Float> queryVector, int limit) {
        SearchRequest request = SearchRequest.newBuilder()
                .addAllVector(queryVector)
                .setLimit(limit)
                .setPipeline(PipelineConfigs.VECTOR)
                .build();
        return rpc.execute(
                "searchVector",
                KnowledgeClient.RpcDeadlineCategory.STANDARD,
                stub -> stub.search(request));
    }

    /**
     * Gets query suggestions.
     *
     * @param query the partial query string
     * @param limit maximum suggestions to return
     * @return suggest response
     */
    SuggestResponse suggest(String query, int limit) {
        SuggestRequest request = SuggestRequest.newBuilder()
                .setQuery(query)
                .setLimit(limit)
                .build();
        return rpc.execute(
                "suggest",
                KnowledgeClient.RpcDeadlineCategory.STANDARD,
                stub -> stub.suggest(request));
    }

    // ========== Document Fetching ==========

    /**
     * Fetches document content from the Worker via gRPC.
     *
     * <p>This method allows the Main process to retrieve document content without directly
     * accessing the Lucene index, avoiding MMapDirectory/write.lock conflicts between the Main and
     * Worker processes.
     *
     * @param docIds list of document IDs to fetch
     * @return response containing document content and metadata
     */
    /**
     * Lists all parent document IDs in the Worker's index (paginated).
     *
     * <p>Used by the GPL coordinator to iterate the full corpus without depending on folder
     * hierarchy. Excludes chunk documents.
     *
     * @param offset zero-based page start
     * @param limit max IDs to return (0 → default 1000 on the Worker)
     * @return response with doc IDs, total count, and timing
     */
    ListAllDocumentIdsResponse listAllDocumentIds(int offset, int limit) {
        return listAllDocumentIds(offset, limit, "");
    }

    ListAllDocumentIdsResponse listAllDocumentIds(
            int offset, int limit, String snapshotToken) {
        ListAllDocumentIdsRequest request =
                ListAllDocumentIdsRequest.newBuilder()
                        .setOffset(offset)
                        .setLimit(limit)
                        .setSnapshotToken(snapshotToken == null ? "" : snapshotToken)
                        .build();
        return rpc.execute(
                "listAllDocumentIds",
                KnowledgeClient.RpcDeadlineCategory.STANDARD,
                stub -> stub.listAllDocumentIds(request));
    }

    FetchDocumentsResponse fetchDocuments(List<String> docIds) {
        FetchDocumentsRequest request = FetchDocumentsRequest.newBuilder()
                .addAllDocIds(docIds)
                .build();
        return rpc.execute(
                "fetchDocuments",
                KnowledgeClient.RpcDeadlineCategory.CONTENT_FETCH,
                stub -> stub.fetchDocuments(request));
    }

    /**
     * Fetches a slice of extracted/indexed document text from the Worker via gRPC.
     *
     * <p>Unlike {@link #fetchDocuments(List)}, this does not apply a fixed-size trim; instead it
     * pages by (offsetChars, maxChars) with a server-side cap.
     */
    FetchDocumentSliceResponse fetchDocumentSlice(String docId, int offsetChars, int maxChars) {
        FetchDocumentSliceRequest request = FetchDocumentSliceRequest.newBuilder()
                .setDocId(docId == null ? "" : docId)
                .setOffsetChars(Math.max(0, offsetChars))
                .setMaxChars(maxChars)
                .build();
        return rpc.execute(
                "fetchDocumentSlice",
                KnowledgeClient.RpcDeadlineCategory.CONTENT_FETCH,
                stub -> stub.fetchDocumentSlice(request));
    }

    // ========== RAG Context ==========

    /**
     * Retrieves relevant context for Q&amp;A using RAG (chunk search) via gRPC.
     *
     * <p>The Worker searches for relevant chunks using BM25 on the chunk_content field, falling
     * back to full document search if no chunks are indexed.
     *
     * @param question the question/query text for BM25 ranking
     * @param docIds set of document IDs to search within
     * @param topK number of chunks/docs to retrieve
     * @return response containing formatted context and metadata
     */
    RetrieveContextResponse retrieveContext(String question, Set<String> docIds, int topK) {
        return retrieveContext(question, docIds, topK, 0);
    }

    /**
     * Retrieves relevant context for Q&amp;A using RAG (chunk search) via gRPC with token budget.
     *
     * <p>The Worker searches for relevant chunks using BM25 on the chunk_content field, falling
     * back to full document search if no chunks are indexed.
     *
     * <p>Phase 6 (Gap 6): When maxContextTokens &gt; 0, the Worker uses token-aware budgeting to
     * avoid over-fetching context that would be truncated by the Head. This eliminates the
     * double-truncation problem where Worker returns 200K chars that Head truncates to 3K tokens.
     *
     * @param question the question/query text for BM25 ranking
     * @param docIds set of document IDs to search within
     * @param topK number of chunks/docs to retrieve
     * @param maxContextTokens token budget (0 = use character budget fallback)
     * @return response containing formatted context and metadata
     */
    RetrieveContextResponse retrieveContext(
            String question, Set<String> docIds, int topK, int maxContextTokens) {
        RetrieveContextRequest.Builder builder = RetrieveContextRequest.newBuilder()
                .setQuestion(question)
                .addAllDocIds(docIds)
                .setTopK(topK);
        if (maxContextTokens > 0) {
            builder.setMaxContextTokens(maxContextTokens);
        }
        RetrieveContextRequest request = builder.build();
        return rpc.execute(
                "retrieveContext",
                // Tempdoc 806 B.2 (round-12): this RPC runs a cross-encoder rerank inside the Worker
                // (RagContextOps.executeRetrieval), so CONTENT_FETCH (2x base = 10s) budgeted it below
                // the cost the RERANK category was created for ("20 docs x 2048 seq on CPU takes ~42s").
                // Round 12's cold-reranker Document Q&A answered in ~9.5s -- under the 10s ceiling by
                // 0.5s. The caller's own 20s budget (RAGContext.DEFAULT_TIMEOUT) is the real ceiling now.
                KnowledgeClient.RpcDeadlineCategory.RERANK,
                stub -> stub.retrieveContext(request));
    }

    /**
     * Retrieves relevant context using the rich parameter set (entity/temporal/content filters).
     */
    RetrieveContextResponse retrieveContext(io.justsearch.app.api.RetrieveContextParams params) {
        RetrieveContextRequest.Builder builder = RetrieveContextRequest.newBuilder()
                .setQuestion(params.question())
                .addAllDocIds(params.docIds())
                .setTopK(params.topK())
                .setAutoEntityExtract(params.autoEntityExtract());

        if (params.maxContextTokens() > 0) {
            builder.setMaxContextTokens(params.maxContextTokens());
        }
        if (!params.entityPersons().isEmpty()) {
            builder.addAllEntityPersons(params.entityPersons());
        }
        if (!params.entityOrganizations().isEmpty()) {
            builder.addAllEntityOrganizations(params.entityOrganizations());
        }
        if (!params.entityLocations().isEmpty()) {
            builder.addAllEntityLocations(params.entityLocations());
        }
        if (params.modifiedAt().isSet()) {
            builder.setModifiedAt(io.justsearch.ipc.TimeRangeMs.newBuilder()
                    .setFromMs(params.modifiedAt().fromMs())
                    .setToMs(params.modifiedAt().toMs())
                    .build());
        }
        if (params.freshnessEnabled()) {
            builder.setFreshnessEnabled(true);
        }
        if (!params.pathPrefix().isEmpty()) {
            builder.setPathPrefix(params.pathPrefix());
        }
        if (!params.fileKind().isEmpty()) {
            builder.addAllFileKind(params.fileKind());
        }
        // Tempdoc 821 §3-C2 — the collection scope. Only set when non-empty: an empty repeated field
        // on the wire IS the default scope, so the absent case stays byte-identical to pre-821.
        if (!params.collection().isEmpty()) {
            builder.addAllCollection(params.collection());
        }

        // Metadata filters
        if (!params.metaSource().isEmpty()) {
            builder.addAllMetaSource(params.metaSource());
        }
        if (!params.metaAuthor().isEmpty()) {
            builder.addAllMetaAuthor(params.metaAuthor());
        }
        if (!params.metaCategory().isEmpty()) {
            builder.addAllMetaCategory(params.metaCategory());
        }
        if (params.metaPublishedAt().isSet()) {
            builder.setMetaPublishedAt(io.justsearch.ipc.TimeRangeMs.newBuilder()
                    .setFromMs(params.metaPublishedAt().fromMs())
                    .setToMs(params.metaPublishedAt().toMs())
                    .build());
        }

        // Map context format enum
        builder.setContextFormat(switch (params.contextFormat()) {
            case XML -> io.justsearch.ipc.ContextFormat.CONTEXT_FORMAT_XML;
            case PLAIN -> io.justsearch.ipc.ContextFormat.CONTEXT_FORMAT_PLAIN;
            default -> io.justsearch.ipc.ContextFormat.CONTEXT_FORMAT_LABELED;
        });

        if (params.returnFullDocuments()) {
            builder.setReturnFullDocuments(true);
        }

        // Tempdoc 610 §J.3 — map the hidden-source ids to structured ChunkRefs so the Worker drops them
        // pre-search. The id is unit-separator-joined (parentDocId may contain colons), split here.
        for (String id : params.excludedSourceIds()) {
            io.justsearch.ipc.ChunkRef ref = parseChunkRef(id);
            if (ref != null) {
                builder.addExcludedChunks(ref);
            }
        }

        RetrieveContextRequest request = builder.build();
        return rpc.execute(
                "retrieveContext",
                // Tempdoc 806 B.2 — see the sibling overload above: this RPC reranks, so it takes the
                // RERANK budget rather than CONTENT_FETCH's 10s.
                KnowledgeClient.RpcDeadlineCategory.RERANK,
                stub -> stub.retrieveContext(request));
    }

    /**
     * Tempdoc 610 §J.3 — parse a unit-separator-joined {@code parentDocId<US>chunkIndex} hidden-source
     * id into a {@link io.justsearch.ipc.ChunkRef}. Returns {@code null} for malformed ids (skipped).
     */
    private static io.justsearch.ipc.ChunkRef parseChunkRef(String id) {
        if (id == null) {
            return null;
        }
        int sep = id.lastIndexOf((char) 0x1F);
        if (sep <= 0 || sep >= id.length() - 1) {
            return null;
        }
        try {
            long chunkIndex = Long.parseLong(id.substring(sep + 1));
            return io.justsearch.ipc.ChunkRef.newBuilder()
                    .setParentDocId(id.substring(0, sep))
                    .setChunkIndex(chunkIndex)
                    .build();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ========== Folder Browse ==========

    ListFoldersResponse listFolders(String parentPath, int maxFolders) {
        ListFoldersRequest request = ListFoldersRequest.newBuilder()
                .setParentPath(parentPath == null ? "" : parentPath)
                .setMaxFolders(maxFolders)
                .build();
        return rpc.execute(
                "listFolders",
                KnowledgeClient.RpcDeadlineCategory.STANDARD,
                stub -> stub.listFolders(request));
    }

    ListFolderFilesResponse listFolderFiles(String folderPath, int limit, List<String> projection) {
        ListFolderFilesRequest.Builder builder = ListFolderFilesRequest.newBuilder()
                .setFolderPath(folderPath == null ? "" : folderPath)
                .setLimit(limit);
        if (projection != null) {
            builder.addAllProjection(projection);
        }
        return rpc.execute(
                "listFolderFiles",
                KnowledgeClient.RpcDeadlineCategory.STANDARD,
                stub -> stub.listFolderFiles(builder.build()));
    }

    // ========== Reranking (360) ==========

    /**
     * Cross-encoder reranking via Worker's GPU-capable model.
     *
     * @param query the search query
     * @param documentTexts pre-built document texts (title + snippet)
     * @param deadlineMs budget for inference (0 = server default)
     * @return rerank response with sorted indices and scores
     */
    RerankResponse rerank(String query, List<String> documentTexts, long deadlineMs) {
        RerankRequest request = RerankRequest.newBuilder()
                .setQuery(query)
                .addAllDocumentTexts(documentTexts)
                .setDeadlineMs(deadlineMs)
                .build();
        return rpc.execute(
                "rerank",
                KnowledgeClient.RpcDeadlineCategory.RERANK,
                stub -> stub.rerank(request));
    }

    // ========== Citation Matching ==========

    /**
     * Post-hoc citation matching: embed answer sentences, compare against chunk embeddings.
     *
     * @param answerText the full LLM answer text
     * @param chunkDocIds parent doc IDs of chunks used as context
     * @param chunkIndices chunk indices within their parent docs
     * @param passageTexts literal passage text per source (tempdoc 836 §1.4) — either empty, or
     *     exactly as long as {@code chunkDocIds}; a blank entry means "look this one up"
     * @param threshold minimum cosine similarity threshold
     * @return response containing matched citations
     */
    MatchCitationsResponse matchCitations(
            String answerText,
            List<String> chunkDocIds,
            List<Integer> chunkIndices,
            List<String> passageTexts,
            double threshold) {
        MatchCitationsRequest request = MatchCitationsRequest.newBuilder()
                .setAnswerText(answerText)
                .addAllChunkDocIds(chunkDocIds)
                .addAllChunkIndices(chunkIndices)
                .addAllPassageTexts(passageTexts)
                .setSimilarityThreshold(threshold)
                .build();
        return rpc.execute(
                "matchCitations",
                KnowledgeClient.RpcDeadlineCategory.CONTENT_FETCH,
                stub -> stub.matchCitations(request));
    }
}
