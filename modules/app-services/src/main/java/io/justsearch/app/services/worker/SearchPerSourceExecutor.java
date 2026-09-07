/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 556 (F-C4.2): 385 per-source (federated) retrieval — extracted verbatim from {@code
 * KnowledgeHttpApiAdapter}. Issues N parallel meta_source-filtered searches through the knowledge
 * port and round-robin interleaves the results, backfilling from unfiltered retrieval when
 * insufficient. Stateless statics.
 *
 * <p>They were gRPC calls until lane F item A6; they are direct calls now, which is why the
 * executor below had to start propagating trace context — see its javadoc.
 */
final class SearchPerSourceExecutor {

  private static final Logger log = LoggerFactory.getLogger(SearchPerSourceExecutor.class);

  /**
   * 385: Virtual-thread executor for the per-source parallel calls.
   *
   * <p>Wrapped by {@code Context.taskWrapping} (lane F review S13), which propagates the calling
   * thread's OpenTelemetry context onto each task. This became load-bearing at item A9. While the
   * calls crossed a wire, the trace context travelled as W3C headers and the server interceptor
   * re-established it on the far side, so it did not matter that a task started on a virtual thread
   * with an empty context. In process there are no headers and no interceptor: {@code
   * EngineKnowledgeClient} reads {@code Span.current()} on the thread that calls the port, which for
   * these tasks is the virtual thread. Without the wrapper every per-source retrieval would produce
   * a root span with no parent, so a federated search — the query shape most in need of a trace —
   * would fan out into N unconnected traces plus the one that asked for them.
   */
  private static final ExecutorService PER_SOURCE_EXECUTOR =
      io.opentelemetry.context.Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor());

  private SearchPerSourceExecutor() {}

  /**
   * Executes per-source retrieval for multi-source queries (#6 + #1). Uses the original query text
   * with a {@code meta_source} hard filter per source (FeB4RAG: the filter does the work, BM25 is
   * robust to source tokens). Falls back to unfiltered retrieval if all per-source calls fail.
   */
  static SearchResponse execute(
      KnowledgeClient client, SearchRequest baseReq, List<String> sources, int totalLimit) {

    int perSourceLimit = Math.max(1, (int) Math.ceil((double) totalLimit / sources.size()));

    // Fire parallel gRPC calls — one per source
    List<CompletableFuture<SearchResponse>> futures = new ArrayList<>();
    for (String source : sources) {
      futures.add(CompletableFuture.supplyAsync(() -> {
        SearchRequest perSourceReq = baseReq.toBuilder()
            .setLimit(perSourceLimit)
            // Proto3: getFilters() returns default instance (never null) — safe to toBuilder()
            .setFilters(baseReq.getFilters().toBuilder()
                .addMetaSource(source.toLowerCase(Locale.ROOT))
                .build())
            .build();
        return client.search(perSourceReq);
      }, PER_SOURCE_EXECUTOR));
    }

    // Collect results
    List<SearchResponse> responses = new ArrayList<>();
    for (var future : futures) {
      try {
        responses.add(future.get(10, TimeUnit.SECONDS));
      } catch (Exception e) {
        log.debug("385: Per-source retrieval failed for one source: {}", e.getMessage());
      }
    }

    if (responses.isEmpty()) {
      // All per-source calls failed — fall back to unfiltered
      log.debug("385: All per-source calls failed, falling back to unfiltered retrieval");
      return client.search(baseReq);
    }

    // Round-robin interleave hits from each source
    return mergeSearchResponses(responses, totalLimit, baseReq, client::search);
  }

  /**
   * 385: Merge N per-source SearchResponses into one via round-robin interleaving. Backfills from
   * unfiltered retrieval if per-source results are insufficient. Package-private static for
   * testability — the backfill function is injected to decouple from the gRPC client.
   */
  static SearchResponse mergeSearchResponses(
      List<SearchResponse> responses,
      int totalLimit,
      SearchRequest backfillReq,
      java.util.function.Function<SearchRequest, SearchResponse> backfillFn) {

    // Collect per-source hit lists
    List<List<SearchResult>> perSourceHits = new ArrayList<>();
    long totalHits = 0;
    // Tempdoc 597: matchCount sums across sources — distinct corpora ⇒ disjoint match sets, so
    // the true matched total of the merged response is the sum of the per-source matched totals
    // (mirrors the totalHits sum directly above it).
    long matchCount = 0;
    long maxTookMs = 0;
    for (SearchResponse r : responses) {
      perSourceHits.add(new ArrayList<>(r.getResultsList()));
      totalHits += r.getTotalHits();
      matchCount += r.getMatchCount();
      maxTookMs = Math.max(maxTookMs, r.getTookMs());
    }

    // Round-robin interleave
    List<SearchResult> interleaved = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    int maxRank = perSourceHits.stream().mapToInt(List::size).max().orElse(0);

    for (int rank = 0; rank < maxRank && interleaved.size() < totalLimit; rank++) {
      for (var hits : perSourceHits) {
        if (rank < hits.size()) {
          SearchResult hit = hits.get(rank);
          if (seen.add(hit.getId())) {
            interleaved.add(hit);
            if (interleaved.size() >= totalLimit) break;
          }
        }
      }
    }

    // Backfill from unfiltered retrieval if per-source results are insufficient
    if (interleaved.size() < totalLimit) {
      log.debug("385: Per-source retrieval returned {} of {} requested, backfilling",
          interleaved.size(), totalLimit);
      try {
        SearchResponse backfill = backfillFn.apply(backfillReq);
        for (SearchResult hit : backfill.getResultsList()) {
          if (interleaved.size() >= totalLimit) break;
          if (seen.add(hit.getId())) {
            interleaved.add(hit);
          }
        }
        totalHits = Math.max(totalHits, backfill.getTotalHits());
        matchCount = Math.max(matchCount, backfill.getMatchCount());
      } catch (Exception e) {
        log.debug("385: Backfill retrieval failed: {}", e.getMessage());
      }
    }

    // Tempdoc 549 Phase E5: OR-merge the per-sub-query trace degradation; QPP is per-query
    // (meaningless merged) so clear it on the merged trace.
    boolean anySpladeExecuted = false;
    boolean anyVectorBlocked = false;
    boolean anyHybridFallback = false;
    String vectorBlockedReason = "";
    String hybridFallbackReason = "";
    String spladeSkipReason = "";
    for (SearchResponse r : responses) {
      if (!r.hasSearchTrace() || !r.getSearchTrace().hasDegradation()) continue;
      io.justsearch.ipc.TraceDegradation d = r.getSearchTrace().getDegradation();
      anySpladeExecuted |= d.getSpladeExecuted();
      if (d.getVectorBlocked()) {
        anyVectorBlocked = true;
        if (!d.getVectorBlockedReason().isBlank()) vectorBlockedReason = d.getVectorBlockedReason();
      }
      if (d.getHybridFallback()) {
        anyHybridFallback = true;
        if (!d.getHybridFallbackReason().isBlank()) hybridFallbackReason = d.getHybridFallbackReason();
      }
      if (!d.getSpladeSkipReason().isBlank()) spladeSkipReason = d.getSpladeSkipReason();
    }

    SearchResponse template = responses.getFirst();
    SearchResponse.Builder merged = template.toBuilder()
        .clearResults()
        .setTotalHits(totalHits)
        .setMatchCount(matchCount)
        .setTookMs(maxTookMs)
        .clearFacets();                           // facets from a filtered sub-query are misleading
    if (template.hasSearchTrace()) {
      io.justsearch.ipc.SearchTrace.Builder mt = template.getSearchTrace().toBuilder();
      mt.setDegradation(
          io.justsearch.ipc.TraceDegradation.newBuilder()
              .setSpladeExecuted(anySpladeExecuted)
              .setVectorBlocked(anyVectorBlocked)
              .setVectorBlockedReason(vectorBlockedReason)
              .setHybridFallback(anyHybridFallback)
              .setHybridFallbackReason(hybridFallbackReason)
              .setSpladeSkipReason(spladeSkipReason)
              .build());
      mt.clearQpp(); // per-query, meaningless when merged across sub-queries
      merged.setSearchTrace(mt.build());
    }
    for (SearchResult hit : interleaved) {
      merged.addResults(hit);
    }

    return merged.build();
  }
}
