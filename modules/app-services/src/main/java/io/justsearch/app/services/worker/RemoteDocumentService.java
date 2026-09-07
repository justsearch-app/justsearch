/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.DocumentService;
import io.justsearch.ipc.DocumentContent;
import io.justsearch.ipc.ContextChunk;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.MatchCitationsResponse;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.indexing.rag.ContextBudgeter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DocumentService implementation that fetches documents via gRPC from the Worker process.
 *
 * <p>This implementation avoids the index locking issue by routing all document fetches
 * through the Worker process, which owns the Lucene index. The Main process no longer
 * needs direct access to the index files.
 *
 * <p>Benefits:
 * <ul>
 *   <li>No MMapDirectory conflicts between Main and Worker</li>
 *   <li>No write.lock contention</li>
 *   <li>Worker is single source of truth for indexed data</li>
 * </ul>
 *
 * @see KnowledgeClient#fetchDocuments(List)
 */
public final class RemoteDocumentService implements DocumentService {
  private static final Logger log = LoggerFactory.getLogger(RemoteDocumentService.class);

  private final Supplier<KnowledgeClient> clientSupplier;
  private final RagMetricCatalog catalog;

  /**
   * Creates a new RemoteDocumentService without telemetry (backward compatible).
   *
   * @param clientSupplier supplier for the gRPC client; resolves at use-time per §31 supplier-aware
   */
  public RemoteDocumentService(Supplier<KnowledgeClient> clientSupplier) {
    this(clientSupplier, RagMetricCatalog.noop());
  }

  /**
   * Creates a new RemoteDocumentService with observability catalog.
   *
   * @param clientSupplier supplier for the gRPC client
   * @param catalog RAG metric catalog (use {@link RagMetricCatalog#noop()} when not wired)
   */
  public RemoteDocumentService(
      Supplier<KnowledgeClient> clientSupplier, RagMetricCatalog catalog) {
    this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  private void recordRagSuccess() {
    catalog.retrievalTotal.increment(RagRetrievalTags.of(RagRetrievalMode.RAG));
  }

  private void recordRagFallback() {
    catalog.retrievalTotal.increment(RagRetrievalTags.of(RagRetrievalMode.FALLBACK));
  }

  private void recordRagError() {
    catalog.retrievalTotal.increment(RagRetrievalTags.of(RagRetrievalMode.ERROR));
  }

  @Override
  public CompletionStage<DocumentRecord> fetch(String docId) {
    return fetchBatch(List.of(docId))
        .thenApply(map -> map.getOrDefault(docId,
            new DocumentRecord(docId, "", Map.of("error", "Not found"))));
  }

  @Override
  public CompletionStage<Map<String, DocumentRecord>> fetchBatch(List<String> docIds) {
    if (docIds == null || docIds.isEmpty()) {
      return CompletableFuture.completedFuture(Map.of());
    }

    return CompletableFuture.supplyAsync(() -> {
      try {
        log.debug("Fetching {} documents via gRPC", docIds.size());
        // Tempdoc 885 item 6 [R6b]: this list is caller-supplied (a search result set, a citation
        // set), so nothing here bounds it. Paged under the byte budget so the reply can never reach
        // the transport ceiling regardless of how many ids arrive.
        FetchDocumentsResponse response =
            BoundedDocumentFetch.fetchAll(ids -> clientSupplier.get().fetchDocuments(ids), docIds);

        Map<String, DocumentRecord> results = new LinkedHashMap<>();
        for (DocumentContent doc : response.getDocumentsList()) {
          Map<String, Object> metadata = new HashMap<>(doc.getMetadataMap());
          metadata.put("found", doc.getFound());

          if (!doc.getFound()) {
            metadata.put("error", doc.getError());
            metadata.put("contentSource", "none");
          } else {
            metadata.put("contentSource", "grpc");
            metadata.put("contentLength", doc.getContent().length());
          }

          results.put(doc.getDocId(),
              new DocumentRecord(doc.getDocId(), doc.getContent(), metadata));
        }

        log.debug("Fetched {} documents via gRPC", results.size());
        return results;

      } catch (Exception e) {
        log.error("Failed to fetch documents via gRPC", e);
        throw new UnavailableException("Failed to fetch documents via Worker: " + e.getMessage(), e);
      }
    });
  }

  @Override
  public CompletionStage<DocumentSlice> fetchSlice(String docId, int offsetChars, int maxChars) {
    if (docId == null || docId.isBlank()) {
      return CompletableFuture.completedFuture(
          new DocumentSlice("", "", Map.of(), false, false, 0, 0, "missing_doc_id"));
    }
    int offset = Math.max(0, offsetChars);
    int max = maxChars <= 0 ? 20_000 : maxChars;

    return CompletableFuture.supplyAsync(
        () -> {
          try {
            var response = clientSupplier.get().fetchDocumentSlice(docId, offset, max);

            Map<String, Object> metadata = new HashMap<>(response.getMetadataMap());
            metadata.put("found", response.getFound());
            if (!response.getFound()) {
              metadata.put("error", response.getError());
              metadata.put("contentSource", "none");
            } else {
              metadata.put("contentSource", "grpc_slice");
              metadata.put("contentLength", response.getContent().length());
            }

            String error = response.getError();
            if (error != null && error.isBlank()) {
              error = null;
            }

            return new DocumentSlice(
                response.getDocId(),
                response.getContent(),
                metadata,
                response.getFound(),
                response.getTruncated(),
                response.getNextOffsetChars(),
                response.getTotalChars(),
                response.getExtractionStatus(),
                response.hasContentTruncated() ? response.getContentTruncated() : null,
                response.getExtractionPolicyId(),
                response.getExtractionParserId(),
                response.getSourceSha256(),
                error);

          } catch (Exception e) {
            log.error("Failed to fetch document slice via gRPC", e);
            throw new UnavailableException("Failed to fetch document slice via Worker: " + e.getMessage(), e);
          }
        });
  }

  @Override
  public CompletionStage<DocumentIdPage> listAllDocumentIds(int offset, int limit) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            var response = clientSupplier.get().listAllDocumentIds(offset, limit);
            return new DocumentIdPage(
                response.getDocIdsList(), response.getTotalCount(), response.getTookMs());
          } catch (Exception e) {
            log.error("Failed to list document IDs via gRPC", e);
            throw new UnavailableException(
                "Failed to list document IDs via Worker: " + e.getMessage(), e);
          }
        });
  }

  /**
   * Retrieves relevant context for Q&A using RAG (chunk search) via gRPC,
   * with metadata about chunk usage.
   *
   * <p>Overrides the default implementation to use the Worker's BM25 chunk search
   * instead of just concatenating full documents. This provides better context
   * relevance for Q&A by returning the most relevant chunks/passages.
   *
   * @param question the user's question
   * @param docIds set of document IDs to search within
   * @param topK number of chunks to retrieve
   * @return result containing context and chunk usage metadata
   */
  @Override
  public CompletionStage<ContextResult> retrieveContextWithMeta(String question, Set<String> docIds, int topK) {
    return retrieveContextWithMeta(question, docIds, topK, 0);
  }

  /**
   * Retrieves relevant context with token budget for Q&A using RAG.
   *
   * <p>Phase 6 (Gap 6): When maxContextTokens > 0, the Worker uses token-aware budgeting
   * to avoid over-fetching context that would be truncated by the Head.
   *
   * @param question the user's question
   * @param docIds set of document IDs to search within
   * @param topK number of chunks to retrieve
   * @param maxContextTokens token budget (0 = use character budget fallback)
   * @return result containing context and chunk usage metadata
   */
  @Override
  public CompletionStage<ContextResult> retrieveContextWithMeta(String question, Set<String> docIds, int topK, int maxContextTokens) {
    if (question == null || question.isBlank() || docIds == null || docIds.isEmpty()) {
      return CompletableFuture.completedFuture(new ContextResult("", 0, 0, 0, List.of(),
          "", "EMPTY_REQUEST", false, List.of()));
    }

    return CompletableFuture.supplyAsync(() -> {
      try {
        log.debug("Retrieving RAG context via gRPC: question='{}', docIds={}, topK={}, maxTokens={}",
            question, docIds.size(), topK, maxContextTokens);

        RetrieveContextResponse response = clientSupplier.get().retrieveContext(question, docIds, topK, maxContextTokens);

        // Semantics:
        // - chunksFound = total hits found (may exceed returned chunks list)
        // - chunksUsed  = number of chunks included in the returned context (0 = full-doc fallback)
        int chunksFound = response.getChunksFound();
        int chunksUsed = response.getUsedChunks() ? response.getChunksCount() : 0;
        List<ContextCitation> citations =
            response.getUsedChunks()
                ? response.getChunksList().stream().map(RemoteDocumentService::toCitation).toList()
                : List.of();

        // Phase 4: Map proto sections to Java for citation filtering on truncation
        List<DocumentService.ContextSection> sections = response.getSectionsList().stream()
            .map(s -> new DocumentService.ContextSection(
                s.getSourceLabel(),
                s.getContent(),
                s.getTruncated(),
                s.getSectionIndex(),
                s.getChunkIndex()))
            .toList();

        log.debug("RAG context retrieved: {} chars, usedChunks={}, chunksFound={}, sections={}, mode={}, reason={}, truncated={}",
            response.getContext().length(), chunksUsed, chunksFound, sections.size(),
            response.getRetrievalMode(), response.getRetrievalModeReason(), response.getContextTruncated());

        // Record success counter for RAG retrieval
        recordRagSuccess();

        // Return with proper metadata from Worker including degradation signals and sections
        return new ContextResult(
            response.getContext(),
            chunksUsed,
            chunksFound,
            0,  // docsUsed=0 when chunks were used
            citations,
            response.getRetrievalMode(),
            response.getRetrievalModeReason(),
            response.getContextTruncated(),
            sections);

      } catch (Exception e) {
        log.error("Failed to retrieve context via gRPC, falling back to default", e);
        // Record fallback counter
        recordRagFallback();
        // Fall back to default implementation (concatenate full docs)
        return retrieveContextFallback(docIds);
      }
    });
  }

  @Override
  public CompletionStage<ContextResult> retrieveContext(
      io.justsearch.app.api.RetrieveContextParams params) {
    if (params.question() == null || params.question().isBlank()) {
      return CompletableFuture.completedFuture(new ContextResult("", 0, 0, 0, List.of(),
          "", "EMPTY_REQUEST", false, List.of()));
    }

    return CompletableFuture.supplyAsync(() -> {
      try {
        // When doc_ids are empty, do a pre-search to find relevant documents.
        // This is necessary because entity/path/date filters are indexed on parent
        // documents, not on chunks. The pre-search finds matching parent docs,
        // then the RAG pipeline searches chunks within those docs.
        var effectiveParams = params;
        if (params.docIds().isEmpty()) {
          Set<String> discoveredDocIds = preSearchForDocIds(params, params.topK() * 2);
          if (!discoveredDocIds.isEmpty()) {
            effectiveParams = new io.justsearch.app.api.RetrieveContextParams(
                params.question(), discoveredDocIds, params.topK(), params.maxContextTokens(),
                params.entityPersons(), params.entityOrganizations(), params.entityLocations(),
                params.modifiedAt(), params.freshnessEnabled(), params.pathPrefix(),
                params.fileKind(), params.autoEntityExtract(), params.contextFormat(),
                params.metaSource(), params.metaAuthor(), params.metaCategory(),
                params.metaPublishedAt(), params.returnFullDocuments(),
                params.excludedSourceIds(), params.collection());
          }
        }

        log.debug("Retrieving RAG context via gRPC (rich params): question='{}', topK={}, "
            + "docIds={}, autoEntityExtract={}, format={}",
            effectiveParams.question(), effectiveParams.topK(), effectiveParams.docIds().size(),
            effectiveParams.autoEntityExtract(), effectiveParams.contextFormat());

        RetrieveContextResponse response = clientSupplier.get().retrieveContext(effectiveParams);
        return mapRetrieveContextResponse(response);
      } catch (Exception e) {
        log.error("Failed to retrieve context via gRPC (rich params), falling back", e);
        recordRagFallback();
        return retrieveContextFallback(params.docIds());
      }
    });
  }

  /**
   * Pre-search to discover relevant document IDs for open retrieval.
   * Uses the existing gRPC search with optional filters to find top-matching documents.
   */
  private Set<String> preSearchForDocIds(
      io.justsearch.app.api.RetrieveContextParams params, int limit) {
    try {
      // Build a search request with the same filters as the RAG request
      var searchBuilder = io.justsearch.ipc.SearchRequest.newBuilder()
          .setQuery(params.question())
          .setLimit(Math.min(limit, 20));

      // Tempdoc 731 I1: a bare request (no pipeline/mode) previously hit the Worker's
      // deprecated-mode fallback and resolved to a sparse-only+expansion+LambdaMART pipeline
      // (SearchPlanner's `deprecated_mode_fallback` WARN, modeToDefaultPipeline default branch) —
      // a different leg set than justsearch_search's default hybrid preset (sparse+dense RRF).
      // That divergence forked the evidence pack's document universe from the doc-ranking surface
      // `justsearch_search` exposes. Tempdoc 735 G5: single-sourced via
      // SearchPipelinePresets#defaultHybridProtoConfig (the shared factory both this call site and
      // KnowledgeSearchEngine#doSearch's default-hybrid resolution are anchored to), rather than
      // each call site re-deriving the hybrid+denseAuto=false combination independently.
      searchBuilder.setPipeline(SearchPipelinePresets.defaultHybridProtoConfig());

      // Apply filters from the RAG params
      boolean hasFilters = !params.pathPrefix().isEmpty()
          || !params.fileKind().isEmpty()
          || !params.entityPersons().isEmpty()
          || !params.entityOrganizations().isEmpty()
          || !params.entityLocations().isEmpty()
          || !params.metaSource().isEmpty()
          || !params.metaAuthor().isEmpty()
          || !params.metaCategory().isEmpty()
          // Tempdoc 821 §3-C2: the pre-search discovers the doc universe the RAG request is then
          // scoped to, so it must carry the same collection scope. Without it an explicit
          // agent-history ASK pre-searched under the DEFAULT scope, which excludes agent-history,
          // and discovered zero parents.
          || !params.collection().isEmpty();

      if (hasFilters || params.modifiedAt().isSet() || params.metaPublishedAt().isSet()) {
        var filtersBuilder = io.justsearch.ipc.SearchFilters.newBuilder();
        if (!params.collection().isEmpty()) {
          filtersBuilder.addAllCollection(params.collection());
        }
        if (!params.pathPrefix().isEmpty()) {
          filtersBuilder.setPathPrefix(params.pathPrefix());
        }
        if (!params.fileKind().isEmpty()) {
          filtersBuilder.addAllFileKind(params.fileKind());
        }
        if (!params.entityPersons().isEmpty()) {
          filtersBuilder.addAllEntityPersons(params.entityPersons());
        }
        if (!params.entityOrganizations().isEmpty()) {
          filtersBuilder.addAllEntityOrganizations(params.entityOrganizations());
        }
        if (!params.entityLocations().isEmpty()) {
          filtersBuilder.addAllEntityLocations(params.entityLocations());
        }
        if (params.modifiedAt().isSet()) {
          filtersBuilder.setModifiedAt(io.justsearch.ipc.TimeRangeMs.newBuilder()
              .setFromMs(params.modifiedAt().fromMs())
              .setToMs(params.modifiedAt().toMs())
              .build());
        }
        if (!params.metaSource().isEmpty()) {
          filtersBuilder.addAllMetaSource(params.metaSource());
        }
        if (!params.metaAuthor().isEmpty()) {
          filtersBuilder.addAllMetaAuthor(params.metaAuthor());
        }
        if (!params.metaCategory().isEmpty()) {
          filtersBuilder.addAllMetaCategory(params.metaCategory());
        }
        if (params.metaPublishedAt().isSet()) {
          filtersBuilder.setMetaPublishedAt(io.justsearch.ipc.TimeRangeMs.newBuilder()
              .setFromMs(params.metaPublishedAt().fromMs())
              .setToMs(params.metaPublishedAt().toMs())
              .build());
        }
        searchBuilder.setFilters(filtersBuilder.build());
      }

      var searchResponse = clientSupplier.get().search(searchBuilder.build());
      // Tempdoc 731 I1: preserve rank order (LinkedHashSet, not HashSet) so the discovered doc
      // universe forwarded to the downstream RetrieveContextRequest reflects the pipeline's
      // ranking, not an arbitrary hash order.
      Set<String> docIds = new LinkedHashSet<>();
      for (var result : searchResponse.getResultsList()) {
        String path = result.getFieldsMap().get("path");
        if (path != null && !path.isEmpty()) {
          docIds.add(path);
        }
      }
      log.debug("Pre-search for open retrieval: found {} documents (filters={})",
          docIds.size(), hasFilters);
      return docIds;
    } catch (Exception e) {
      log.warn("Pre-search for open retrieval failed", e);
      return Set.of();
    }
  }

  /** Maps a gRPC RetrieveContextResponse to the Head-side ContextResult. */
  private ContextResult mapRetrieveContextResponse(RetrieveContextResponse response) {
    int chunksFound = response.getChunksFound();
    int chunksUsed = response.getUsedChunks() ? response.getChunksCount() : 0;
    List<ContextCitation> citations =
        response.getUsedChunks()
            ? response.getChunksList().stream().map(RemoteDocumentService::toCitation).toList()
            : List.of();

    List<DocumentService.ContextSection> sections = response.getSectionsList().stream()
        .map(s -> new DocumentService.ContextSection(
            s.getSourceLabel(), s.getContent(), s.getTruncated(),
            s.getSectionIndex(), s.getChunkIndex()))
        .toList();

    // Map quality signals from proto
    var protoQuality = response.getQuality();
    var quality = new DocumentService.QualitySignals(
        protoQuality.getBestChunkScore(),
        protoQuality.getScoreGap(),
        protoQuality.getRetrievalCoverage(),
        protoQuality.getChunksConsidered(),
        protoQuality.getChunksIncluded());

    recordRagSuccess();

    return new ContextResult(
        response.getContext(),
        chunksUsed, chunksFound, 0, citations,
        response.getRetrievalMode(),
        response.getRetrievalModeReason(),
        response.getContextTruncated(),
        sections, quality);
  }

  private static ContextCitation toCitation(ContextChunk c) {
    if (c == null) {
      return new ContextCitation(
          "", 0, 1, 0, 0, 0.0f, "", 0, 0, "", 0,
          DocumentService.ContextInclusion.ABSENT);
    }
    return new ContextCitation(
        c.getParentDocId(),
        c.getChunkIndex(),
        c.getChunkTotal(),
        c.getStartChar(),
        c.getEndChar(),
        c.getScore(),
        c.getExcerpt(),
        // F8 Tier 2: In-document navigation
        c.getStartLine(),
        c.getEndLine(),
        c.getHeadingText(),
        c.getHeadingLevel(),
        // Tempdoc 849 §5.3 — the Worker cannot know inclusion: the head's cut has not run yet.
        DocumentService.ContextInclusion.ABSENT);
  }

  /**
   * Fallback context retrieval when gRPC fails.
   * Uses fetchBatch and concatenates documents.
   *
   * @return ContextResult with chunksUsed=0 to indicate fallback was used
   */
  private ContextResult retrieveContextFallback(Set<String> docIds) {
    try {
      Map<String, DocumentRecord> docs =
          BoundedDocumentFetch.fetchAll(
                  ids -> clientSupplier.get().fetchDocuments(ids), List.copyOf(docIds))
          .getDocumentsList().stream()
          .collect(java.util.stream.Collectors.toMap(
              DocumentContent::getDocId,
              doc -> new DocumentRecord(doc.getDocId(), doc.getContent(), Map.of()),
              (a, b) -> a,
              LinkedHashMap::new));

      int maxChars = 200_000;
      ContextBudgeter budgeter = new ContextBudgeter(maxChars);
      int docsUsed = 0;
      for (var entry : docs.entrySet()) {
        if (entry.getValue() != null && !entry.getValue().content().isBlank()) {
          ContextBudgeter.AppendResult r =
              budgeter.appendSection(extractFilename(entry.getKey()), entry.getValue().content());
          if (r == ContextBudgeter.AppendResult.APPENDED
              || r == ContextBudgeter.AppendResult.APPENDED_TRUNCATED) {
            docsUsed++;
          }
          if (r == ContextBudgeter.AppendResult.STOPPED_BUDGET
              || r == ContextBudgeter.AppendResult.APPENDED_TRUNCATED) {
            break;
          }
        }
      }
      // chunksUsed=0 indicates fallback to full docs
      // Map budgeter sections to Java ContextSection for consistency
      List<DocumentService.ContextSection> fallbackSections = budgeter.sections().stream()
          .map(s -> new DocumentService.ContextSection(
              s.sourceLabel(), s.content(), s.truncated(), s.sectionIndex(), s.sectionIndex()))
          .toList();
      return new ContextResult(budgeter.build(), 0, 0, docsUsed, List.of(),
          "FULLTEXT_FALLBACK", "GRPC_FAILED", false, fallbackSections);
    } catch (Exception e) {
      log.error("Fallback context retrieval also failed", e);
      recordRagError();
      return new ContextResult("", 0, 0, 0, List.of(),
          "", "FALLBACK_FAILED", false, List.of());
    }
  }

  /**
   * The one site that unpacks {@link VerificationSource} into the wire's positional arrays
   * (tempdoc 836 §1.1). The three arrays are built in a single loop from one source list, so they
   * cannot desync; a length the Worker does not expect is rejected there with INVALID_ARGUMENT
   * rather than absorbed.
   */
  @Override
  public CompletionStage<CitationMatchResult> matchCitationsAgainst(
      String answerText, List<VerificationSource> sources, double threshold) {
    return CompletableFuture.supplyAsync(() -> {
      if (answerText == null || answerText.isBlank() || sources == null || sources.isEmpty()) {
        return new CitationMatchResult(List.of(), 0, 0, 0, 0, ScorerKind.NONE, List.of());
      }
      try {
        List<String> chunkDocIds = new ArrayList<>(sources.size());
        List<Integer> chunkIndices = new ArrayList<>(sources.size());
        List<String> passageTexts = new ArrayList<>(sources.size());
        boolean anyText = false;
        for (VerificationSource s : sources) {
          ContextCitation c = s.citation();
          chunkDocIds.add(c.parentDocId());
          chunkIndices.add(c.chunkIndex());
          passageTexts.add(s.literalText());
          anyText |= s.suppliesText();
        }
        MatchCitationsResponse resp =
            clientSupplier.get().matchCitations(
                answerText,
                chunkDocIds,
                chunkIndices,
                anyText ? passageTexts : List.of(),
                threshold);
        List<CitationMatchEntry> entries = new ArrayList<>(resp.getMatchesCount());
        for (var m : resp.getMatchesList()) {
          entries.add(new CitationMatchEntry(
              m.getSentenceIndex(),
              m.getSentenceText(),
              m.getSourceIndex(),
              m.getSimilarity(),
              m.getParentDocId(),
              TextSource.fromWire(m.getTextSource())));
        }
        // Tempdoc 836 S2S3-A.1 — the per-source examination facts ride through verbatim. Nothing is
        // aggregated here: the response-level ratio is derived by whoever needs it, so one fact
        // never acquires a second producer on the way to the browser.
        List<DocumentService.SourceCoverage> coverage =
            resp.getSourceCoverageList().stream()
                .map(
                    c ->
                        new DocumentService.SourceCoverage(
                            c.getSourceIndex(), c.getWindowsConsidered(), c.getWindowsScored()))
                .toList();
        return new CitationMatchResult(
            entries,
            resp.getSentencesTotal(),
            resp.getSentencesMatched(),
            resp.getTookMs(),
            resp.getSentencesScored(),
            ScorerKind.fromWire(resp.getScorer()),
            coverage);
      } catch (Exception e) {
        log.warn("Citation matching via gRPC failed", e);
        return new CitationMatchResult(List.of(), 0, 0, 0, 0, ScorerKind.NONE, List.of());
      }
    });
  }

  private static String extractFilename(String path) {
    if (path == null) return "unknown";
    int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
  }
}
