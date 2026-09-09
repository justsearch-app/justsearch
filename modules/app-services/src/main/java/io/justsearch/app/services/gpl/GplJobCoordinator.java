/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpl;

import io.justsearch.core.context.EngineContext;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.app.api.gpl.GplJobStatus;
import io.justsearch.app.api.gpl.GplStatusProvider;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
import java.util.Objects;
import io.justsearch.ipc.DocumentContent;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.ListAllDocumentIdsResponse;

import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import io.justsearch.ipc.RerankResponse;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates the GPL (Generative Pseudo Labels) offline corpus adaptation job.
 *
 * <p>For each indexed document, the coordinator:
 * <ol>
 *   <li>Iterates the full corpus via {@code ListAllDocumentIds} (paged, batch size 50).</li>
 *   <li>Fetches document content via {@code FetchDocuments}.</li>
 *   <li>Calls the LLM to generate 2–3 synthetic search queries per document.</li>
 *   <li>Scores each {@code (query, document)} pair with the cross-encoder reranker.</li>
 *   <li>Runs each synthetic query against the live index ({@link #collectFeaturesAndNegatives})
 *       to capture BM25/QPP feature columns and collect negative examples for LambdaMART
 *       training. The positive triple and all negatives are written via
 *       {@link GplTrainingTripleStore#appendWithFeatures}.</li>
 * </ol>
 *
 * <p>Thread safety: only one job runs at a time; concurrent {@link #runAsync()} calls return
 * {@code false} if a job is already running.
 *
 * <p>The cross-encoder reranker supplier is optional. When it returns {@code null}, all triples
 * receive a default score of {@code 1.0f}.
 */
public final class GplJobCoordinator implements GplStatusProvider {
  private static final EngineContext ENGINE_CONTEXT = io.justsearch.app.services.intent.EngineProvenance.internal(
      "gpl-job-coordinator", EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);


  private static final Logger log = LoggerFactory.getLogger(GplJobCoordinator.class);

  /** Batch size for ListAllDocumentIds pages. */
  private static final int BATCH_SIZE = 50;

  /** Max tokens for the LLM query-generation call. */
  private static final int GPL_MAX_TOKENS = 100;

  /** Default cross-encoder deadline per query-doc pair. */
  private static final long RERANK_DEADLINE_MS = 5_000L;

  /** Timeout for collecting a single streaming LLM call. */
  private static final long STREAM_TIMEOUT_SECONDS = 30L;

  /** Max time to wait for AI to become available during a job (exponential backoff). */
  private static final long AI_WAIT_TIMEOUT_MS = 120_000L; // 2 minutes

  /** Initial backoff delay when AI is unavailable. */
  private static final long AI_WAIT_INITIAL_MS = 2_000L;

  /** Maximum backoff delay (capped). */
  private static final long AI_WAIT_MAX_DELAY_MS = 30_000L;

  /** Maximum characters of document content sent to the LLM prompt. */
  private static final int CONTENT_PREVIEW_CHARS = 4_096;

  /** Number of top search results to fetch during the negative-sampling re-query pass. */
  private static final int NEGATIVE_CANDIDATE_LIMIT = 20;

  /** Stage 3A GPL re-query pipeline: explicit 3-way retrieval with CC fusion and debug scores. */
  private static final io.justsearch.ipc.PipelineConfig GPL_REQUERY_PIPELINE =
      io.justsearch.ipc.PipelineConfig.newBuilder()
          .setSparseEnabled(true)
          .setDenseEnabled(true)
          .setSpladeEnabled(true)
          .setFusionAlgorithm("cc")
          .setLambdamartEnabled(false)
          .setCrossEncoderEnabled(false)
          .setExpansionEnabled(false)
          .setPipelineName("sparse+dense+splade")
          .build();

  /** Maximum negative triples written per synthetic query. */
  private static final int MAX_NEGATIVES_PER_QUERY = 5;

  /**
   * Few-shot prompt for synthetic query generation. Three examples demonstrate natural,
   * informal search behavior (keywords + questions) across document types JustSearch handles.
   * Based on InPars GBQ / Promptagator research: 3 few-shot examples are sufficient for
   * 8B-class models to produce realistic queries instead of formal document titles.
   */
  private static final String GPL_PROMPT_TEMPLATE =
      "Generate 2 search queries a real person would type to find this document.\n"
          + "Write what someone would actually type in a search box: short, informal,\n"
          + "lowercase. Mix styles — keywords, questions, or short phrases.\n"
          + "Do NOT write document titles or formal headings.\n"
          + "Output one query per line, no numbering.\n\n"
          + "Document: \"## Git Rebase vs Merge\\nWhen working with feature branches...\"\n"
          + "Queries:\n"
          + "git rebase vs merge difference\n"
          + "when should I rebase instead of merge\n\n"
          + "Document: \"Invoice #4521 from Acme Corp, dated March 15, total $2,340...\"\n"
          + "Queries:\n"
          + "acme invoice march\n"
          + "how much was the acme invoice\n\n"
          + "Document: \"Meeting Notes - Q4 Planning\\nAttendees: Sarah, Mike...\"\n"
          + "Queries:\n"
          + "q4 planning meeting notes\n"
          + "what did we decide in the q4 meeting\n\n"
          + "Document: \"{CONTENT}\"\n"
          + "Queries:";

  /** Sampling parameters for GPL query generation: focused but not deterministic. */
  private static final SamplingParams GPL_SAMPLING = new SamplingParams(0.4, 0.9);


  /**
   * Coherent run-state snapshot. All four fields are replaced together via a single
   * {@link AtomicReference#set}, so {@link #getStatus()} always returns a point-in-time
   * consistent view (avoids the torn-read risk of five separate volatile fields).
   */
  private record GplRunSnapshot(long processedDocs, long totalDocs, Instant lastRunAt, String lastError) {}

  private final Supplier<KnowledgeClient> knowledgeClientSupplier;
  private final OnlineAiService onlineAiService;
  private final boolean rerankerAvailable;
  private final GplTrainingTripleStore tripleStore;
  /** Consecutive triple-write failures. Trips circuit breaker at 3 to prevent O(N) error logs. */
  private int consecutiveWriteFailures;
  /** Invoked after {@link GplJobStatus.Status#COMPLETED}. May be null (no-op). */
  private final Runnable onJobCompleted; // nullable

  private final AtomicReference<GplJobStatus.Status> status = new AtomicReference<>(GplJobStatus.Status.IDLE);
  private final AtomicReference<GplRunSnapshot> runSnapshot =
      new AtomicReference<>(new GplRunSnapshot(0L, 0L, null, null));
  private final CountDownLatch terminalLatch = new CountDownLatch(1);

  /**
   * Creates a new coordinator.
   *
   * @param knowledgeClient gRPC client for corpus iteration, document fetch, and remote reranking
   * @param onlineAiService LLM service for synthetic query generation
   * @param rerankerAvailable whether the cross-encoder is available in the Worker (360)
   * @param tripleStore persistent NDJSON store for training triples
   */
  public GplJobCoordinator(
      Supplier<KnowledgeClient> knowledgeClientSupplier,
      OnlineAiService onlineAiService,
      boolean rerankerAvailable,
      GplTrainingTripleStore tripleStore) {
    this(knowledgeClientSupplier, onlineAiService, rerankerAvailable, tripleStore, null);
  }

  /**
   * Creates a new coordinator with a post-completion callback.
   *
   * @param knowledgeClient gRPC client for corpus iteration, document fetch, and remote reranking
   * @param onlineAiService LLM service for synthetic query generation
   * @param rerankerAvailable whether the cross-encoder is available in the Worker (360)
   * @param tripleStore persistent NDJSON store for training triples
   * @param onJobCompleted invoked on the job thread after {@link GplJobStatus.Status#COMPLETED}; may be null
   */
  public GplJobCoordinator(
      Supplier<KnowledgeClient> knowledgeClientSupplier,
      OnlineAiService onlineAiService,
      boolean rerankerAvailable,
      GplTrainingTripleStore tripleStore,
      Runnable onJobCompleted) {
    this.knowledgeClientSupplier =
        Objects.requireNonNull(knowledgeClientSupplier, "knowledgeClientSupplier");
    this.onlineAiService = onlineAiService;
    this.rerankerAvailable = rerankerAvailable;
    this.tripleStore = tripleStore;
    this.onJobCompleted = onJobCompleted;
  }

  /**
   * Submits the GPL job to run asynchronously on a virtual thread.
   *
   * @return {@code true} if the job was submitted; {@code false} if it is already running
   */
  public boolean runAsync() {
    GplJobStatus.Status current = status.get();
    if (current == GplJobStatus.Status.RUNNING) {
      log.info("GPL job already running; skipping new submission");
      return false;
    }
    if (!status.compareAndSet(current, GplJobStatus.Status.RUNNING)) {
      log.info("GPL job race: another thread started it first");
      return false;
    }
    runSnapshot.set(new GplRunSnapshot(0, 0L, Instant.now(), null));

    CompletableFuture.runAsync(this::runJob)
        .whenComplete(
            (v, ex) -> {
              if (ex != null) {
                String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                GplRunSnapshot prev = runSnapshot.get();
                runSnapshot.set(new GplRunSnapshot(prev.processedDocs(), prev.totalDocs(), prev.lastRunAt(), msg));
                status.set(GplJobStatus.Status.FAILED);
                terminalLatch.countDown();
                log.error("GPL job failed after processing {} docs", runSnapshot.get().processedDocs(), ex);
              }
            });
    return true;
  }

  /**
   * Returns the path to the NDJSON triple store file. Used by LambdaMartTrainer.
   */
  public Path getTripleStorePath() {
    return tripleStore.storeFile();
  }

  /**
   * Returns a status snapshot.
   *
   * @return current GPL job status
   */
  @Override
  public GplJobStatus getStatus() {
    long triples = tripleStore.count();
    GplRunSnapshot snap = runSnapshot.get();
    return new GplJobStatus(status.get(), snap.processedDocs(), snap.totalDocs(), triples, snap.lastRunAt(), snap.lastError());
  }

  /**
   * Blocks until the job reaches a terminal state ({@link GplJobStatus.Status#COMPLETED} or
   * {@link GplJobStatus.Status#FAILED}), or the timeout elapses. If the job has already reached a terminal
   * state, returns {@code true} immediately without blocking.
   *
   * <p>Note: this latch fires only once per coordinator instance. {@link GplJobCoordinator}
   * does not support re-runs; create a new instance for each job execution.
   *
   * @param timeout maximum time to wait
   * @param unit time unit of the {@code timeout} argument
   * @return {@code true} if a terminal state was reached; {@code false} if the timeout elapsed
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
    return terminalLatch.await(timeout, unit);
  }

  // ========== Core job loop ==========

  private void runJob() {
    // 360: reranker now runs in the Worker via gRPC
    if (!rerankerAvailable) {
      log.warn(
          "GPL job: cross-encoder reranker unavailable — all scores will default to 1.0"
              + " (binary is_negative labels only, no graded relevance)");
    } else {
      log.info("GPL job: cross-encoder reranker ready (remote via Worker)");
    }

    // E2E-6: Clear the triple store at job start to avoid duplicate triples on retry.
    try {
      tripleStore.clear();
    } catch (IOException e) {
      log.warn("GPL job: failed to clear triple store (will append to existing)", e);
    }

    consecutiveWriteFailures = 0;
    log.info("GPL job starting: iterating corpus in batches of {}", BATCH_SIZE);
    long started = System.currentTimeMillis();
    long triplesWritten = 0L;
    int offset = 0;
    // Local counters — pushed to runSnapshot at each batch boundary to avoid
    // per-document AtomicReference churn while still providing progress visibility.
    int localProcessed = 0;
    long localTotal = 0L;
    String snapshotToken = "";
    Instant startedAt = runSnapshot.get().lastRunAt();

    try {
      boolean aiTimedOut = false;
      while (!aiTimedOut) {
        ListAllDocumentIdsResponse page =
            offset == 0
                ? knowledgeClientSupplier.get().listAllDocumentIds(0, BATCH_SIZE, ENGINE_CONTEXT)
                : knowledgeClientSupplier
                    .get()
                    .listAllDocumentIds(offset, BATCH_SIZE, snapshotToken, ENGINE_CONTEXT);
        List<String> docIds = page.getDocIdsList();

        if (localTotal == 0L) {
          localTotal = page.getTotalCount();
          log.info("GPL job: corpus size = {} docs", localTotal);
        }

        if (!page.getSnapshotToken().isEmpty()) {
          snapshotToken = page.getSnapshotToken();
        } else if ((long) offset + docIds.size() < page.getTotalCount()) {
          throw new IllegalStateException(
              "Worker omitted the document ID snapshot token for a paginated corpus");
        }

        if (docIds.isEmpty()) {
          break;
        }

        // Fetch content for this batch. Tempdoc 885 item 6 [R6b]: BATCH_SIZE doc ids x the 200k-char
        // per-document cap can assemble a reply near the 32 MiB transport ceiling, so the batch is
        // paged under a byte budget rather than handed over whole.
        FetchDocumentsResponse fetchResp =
            io.justsearch.app.services.worker.BoundedDocumentFetch.fetchAll(
                ids -> knowledgeClientSupplier.get().fetchDocuments(ids, ENGINE_CONTEXT), docIds);

        for (DocumentContent doc : fetchResp.getDocumentsList()) {
          if (!doc.getFound() || doc.getContent().isBlank()) {
            continue;
          }

          // Gate: AI must be available before we call the LLM.
          // Uses exponential backoff (2s → 30s cap) with a global 2-minute timeout.
          // On timeout, the entire job is aborted (not just this doc skipped) —
          // all triples written so far are preserved for the next trigger cycle.
          if (!onlineAiService.isAvailable() && !waitForAiAvailability()) {
            log.warn(
                "GPL job: AI unavailable for {}s; aborting job at doc {} of {}",
                AI_WAIT_TIMEOUT_MS / 1000,
                localProcessed,
                localTotal);
            aiTimedOut = true;
            break;
          }

          String queriesText = generateSyntheticQueries(doc.getContent());
          if (queriesText == null || queriesText.isBlank()) {
            localProcessed++;
            continue;
          }

          List<String> queries =
              queriesText
                  .lines()
                  .map(String::strip)
                  .filter(q -> !q.isBlank())
                  .limit(5) // safety cap — LLM shouldn't produce more than 5
                  .toList();

          for (int qi = 0; qi < queries.size(); qi++) {
            String query = queries.get(qi);
            String queryId = doc.getDocId() + "#" + qi;
            float positiveScore = scoreQueryDoc(query, doc.getContent());
            triplesWritten += collectFeaturesAndNegatives(
                queryId, doc.getDocId(), query, positiveScore);
          }

          localProcessed++;
        }

        // Publish batch progress as a coherent atomic snapshot
        runSnapshot.set(new GplRunSnapshot(localProcessed, localTotal, startedAt, null));

        offset += docIds.size();
        if (localTotal > 0 && offset >= localTotal) {
          break;
        }
      }

      long elapsedMs = System.currentTimeMillis() - started;
      runSnapshot.set(new GplRunSnapshot(localProcessed, localTotal, startedAt, null));

      // Circuit breaker: abort if disk writes are failing repeatedly.
      if (consecutiveWriteFailures >= 3) {
        status.set(GplJobStatus.Status.FAILED);
        terminalLatch.countDown();
        log.error(
            "GPL job aborted: {} consecutive disk write failures after processing {}/{} documents",
            consecutiveWriteFailures,
            localProcessed,
            localTotal);
        return;
      }

      // E2E-4: Set FAILED on AI timeout instead of cementing partial state as COMPLETED.
      if (aiTimedOut) {
        status.set(GplJobStatus.Status.FAILED);
        terminalLatch.countDown();
        log.warn(
            "GPL job failed: LLM became unavailable after processing {}/{} documents in {}ms."
                + " Job will re-trigger on next poll cycle.",
            localProcessed,
            localTotal,
            elapsedMs);
      } else {
        log.info(
            "GPL job complete: {} docs processed, {} triples written in {}ms",
            localProcessed,
            triplesWritten,
            elapsedMs);
        writeStage3aAnalysisReport();
        writeStage3bBranchFusionReport();
        status.set(GplJobStatus.Status.COMPLETED);
        terminalLatch.countDown();
        if (onJobCompleted != null) {
          try {
            onJobCompleted.run();
          } catch (Exception e) {
            log.warn("GPL job: onJobCompleted callback threw (non-fatal)", e);
          }
        }
      }

    } catch (Exception e) {
      // Rethrow so the CompletableFuture.whenComplete sees it
      throw new RuntimeException("GPL job aborted at offset " + offset, e);
    }
  }

  // ========== Negative sampling + feature re-query pass ==========

  /**
   * Runs the query against the live index to capture Stage 3A whole-doc/chunk calibration
   * features and collect negative examples. Writes one positive triple (source doc) and up to
   * {@link #MAX_NEGATIVES_PER_QUERY} negative triples using the extended
   * {@link GplTrainingTripleStore#appendWithFeatures} schema.
   *
   * <p>If the source document does not appear in the top-{@link #NEGATIVE_CANDIDATE_LIMIT} results,
   * the positive triple is still written with {@code rank_position=0} and zero feature scores; QPP
   * values (query-level) are captured regardless.
   *
   * @return the total number of triples written (1 positive + negatives)
   */
  private int collectFeaturesAndNegatives(
      String queryId, String sourceDocId, String query, float positiveScore) {
    if (consecutiveWriteFailures >= 3) {
      return 0; // circuit broken — disk writes are failing, skip to avoid O(N) error logs
    }

    long timestampMs = Instant.now().toEpochMilli();

    // Run the re-query pass against the live index with explicit 3-way CC search.
    SearchResponse searchResp = null;
    try {
      SearchRequest req =
          SearchRequest.newBuilder()
              .setQuery(query)
              .setLimit(NEGATIVE_CANDIDATE_LIMIT)
              .setPipeline(GPL_REQUERY_PIPELINE)
              // Tempdoc 549 Phase D2: GPL's LTR feature collection needs the numeric detail tier
              // (the per-hit HitStage.detail map). Request it via include_detail (replacing the
              // deprecated debug flag, which the worker still honors as a transitional alias).
              .setIncludeDetail(true)
              .build();
      searchResp = knowledgeClientSupplier.get().search(req, ENGINE_CONTEXT);
    } catch (Exception e) {
      if (isTransientWorkerUnavailable(e)) {
        throw new IllegalStateException(
            "GPL re-query aborted because the worker is temporarily unavailable", e);
      }
      log.warn(
          "GPL re-query search failed for query='{}'; writing positive triple with zero features",
          query,
          e);
    }

    // Extract QPP metrics (query-level, available even without document-level scores).
    // Tempdoc 549 Phase E5: read QPP from the unified trace (flat fields retired).
    io.justsearch.ipc.TraceQpp qppTrace = qppOf(searchResp);
    float qppMaxIdf = qppTrace.getMaxIdf();
    float qppAvgIctf = qppTrace.getAvgIctf();
    float qppQueryScope = qppTrace.getQueryScope();

    // Locate source doc in results and identify negatives.
    boolean sourceDocFound = false;
    int negativeCount = 0;
    int triplesWritten = 0;

    if (searchResp != null) {
      List<SearchResult> results = searchResp.getResultsList();
      for (int rank = 0; rank < results.size(); rank++) {
        SearchResult result = results.get(rank);
        int rankPosition = rank + 1; // 1-indexed
        GplTrainingTripleStore.FeaturePayload payload =
            buildFeaturePayload(searchResp, result, rankPosition, timestampMs);

        if (result.getId().equals(sourceDocId)) {
          // Write positive triple with captured features.
          sourceDocFound = true;
          try {
            tripleStore.appendWithFeatures(
                queryId, sourceDocId, query, positiveScore, false, payload);
            triplesWritten++;
            consecutiveWriteFailures = 0;
          } catch (IOException e) {
            consecutiveWriteFailures++;
            log.error("GPL job: failed to write positive triple for doc {}", sourceDocId, e);
          }
        } else if (negativeCount < MAX_NEGATIVES_PER_QUERY) {
          // Collect negative candidate: fetch content and score with cross-encoder.
          String negDocId = result.getId();
          String negContent = fetchSingleDocContent(negDocId);
          if (negContent != null) {
            float negScore = scoreQueryDoc(query, negContent);
            try {
              tripleStore.appendWithFeatures(
                  queryId, negDocId, query, negScore, true, payload);
              triplesWritten++;
              negativeCount++;
              consecutiveWriteFailures = 0;
            } catch (IOException e) {
              consecutiveWriteFailures++;
              log.error("GPL job: failed to write negative triple for doc {}", negDocId, e);
            }
          }
        }
      }
    }

    // If the source doc was not in the top results, write the positive triple with zero
    // BM25/vector scores (QPP is query-level so it's still available).
    if (!sourceDocFound) {
      try {
        tripleStore.appendWithFeatures(
            queryId,
            sourceDocId,
            query,
            positiveScore,
            false,
            GplTrainingTripleStore.FeaturePayload.builder()
                .sparse(0f)
                .vector(0f)
                .wholeSparse(0f)
                .wholeVector(0f)
                .wholeSplade(0f)
                .wholeCc(0f)
                .chunkSparse(0f)
                .chunkVector(0f)
                .chunkSplade(0f)
                .chunkCc(0f)
                .branchWhole(0f)
                .branchChunk(0f)
                .branchCc(0f)
                .branchPresentWhole(false)
                .branchPresentChunk(false)
                .branchWeightWhole(0f)
                .branchWeightChunk(0f)
                .branchEffectiveWeightWhole(0f)
                .branchEffectiveWeightChunk(0f)
                .branchModifierWhole(0f)
                .branchModifierChunk(0f)
                .qppMaxIdf(qppMaxIdf)
                .qppAvgIctf(qppAvgIctf)
                .qppQueryScope(qppQueryScope)
                .rankPosition(0)
                .timestampMs(timestampMs)
                .build());
        triplesWritten++;
        consecutiveWriteFailures = 0;
      } catch (IOException e) {
        consecutiveWriteFailures++;
        log.error(
            "GPL job: failed to write positive triple (not-ranked) for doc {}",
            sourceDocId,
            e);
      }
    }

    return triplesWritten;
  }

  private static GplTrainingTripleStore.FeaturePayload buildFeaturePayload(
      SearchResponse searchResp, SearchResult result, int rankPosition, long timestampMs) {
    // Tempdoc 549 Phase D: source features from the unified trace's per-hit detail tier (the
    // union of all HitStage.detail), not the legacy debug_scores map. The worker guarantees
    // union(detail) == debug_scores for every hit (SearchResponseBuilder feeds the same effective
    // map to both), so the FeaturePayload is byte-identical — verified by the byte-equivalence
    // test. Falls back to debug_scores for a pre-trace (dual-emit) response.
    Map<String, Float> debugScores = unifiedDetailTier(result);
    float wholeSparse = debugScores.getOrDefault("sparse", 0f);
    float wholeVector = debugScores.getOrDefault("vector", 0f);
    float wholeSplade = debugScores.getOrDefault("splade", 0f);
    float wholeCc = debugScores.getOrDefault("cc", 0f);
    float chunkSparse = debugScores.getOrDefault("chunk_sparse", 0f);
    float chunkVector = debugScores.getOrDefault("chunk_vector", 0f);
    float chunkSplade = debugScores.getOrDefault("chunk_splade", 0f);
    float chunkCc = debugScores.getOrDefault("chunk_cc", 0f);
    float branchWhole = debugScores.getOrDefault("whole_branch", 0f);
    float branchChunk = debugScores.getOrDefault("chunk_branch", 0f);
    float branchCc = debugScores.getOrDefault("branch_merge_cc", 0f);
    boolean branchPresentWhole = debugScores.getOrDefault("whole_branch_rank", 0f) > 0f;
    boolean branchPresentChunk = debugScores.getOrDefault("chunk_branch_rank", 0f) > 0f;
    float branchWeightWhole = debugScores.getOrDefault("branch_merge_cc_weight_whole", 0f);
    float branchWeightChunk = debugScores.getOrDefault("branch_merge_cc_weight_chunk", 0f);
    float branchEffectiveWeightWhole =
        debugScores.getOrDefault("branch_merge_cc_effective_weight_whole", 0f);
    float branchEffectiveWeightChunk =
        debugScores.getOrDefault("branch_merge_cc_effective_weight_chunk", 0f);
    float branchModifierWhole = debugScores.getOrDefault("branch_merge_cc_modifier_whole", 0f);
    float branchModifierChunk = debugScores.getOrDefault("branch_merge_cc_modifier_chunk", 0f);

    return GplTrainingTripleStore.FeaturePayload.builder()
        .sparse(wholeSparse)
        .vector(wholeVector)
        .wholeSparse(wholeSparse)
        .wholeVector(wholeVector)
        .wholeSplade(wholeSplade)
        .wholeCc(wholeCc)
        .chunkSparse(chunkSparse)
        .chunkVector(chunkVector)
        .chunkSplade(chunkSplade)
        .chunkCc(chunkCc)
        .branchWhole(branchWhole)
        .branchChunk(branchChunk)
        .branchCc(branchCc)
        .branchPresentWhole(branchPresentWhole)
        .branchPresentChunk(branchPresentChunk)
        .branchWeightWhole(branchWeightWhole)
        .branchWeightChunk(branchWeightChunk)
        .branchEffectiveWeightWhole(branchEffectiveWeightWhole)
        .branchEffectiveWeightChunk(branchEffectiveWeightChunk)
        .branchModifierWhole(branchModifierWhole)
        .branchModifierChunk(branchModifierChunk)
        .parentTokenCount(parentTokenCount(debugScores))
        .qppMaxIdf(qppOf(searchResp).getMaxIdf())
        .qppAvgIctf(qppOf(searchResp).getAvgIctf())
        .qppQueryScope(qppOf(searchResp).getQueryScope())
        .rankPosition(rankPosition)
        .timestampMs(timestampMs)
        .build();
  }

  /**
   * Tempdoc 549 Phase D/E1: the per-hit numeric detail tier from the unified trace — the union of
   * every {@code HitStage.detail} map on the result. This is the SOLE source now that the legacy
   * {@code debug_scores} wire field is retired (Phase E1); GPL requests it via include_detail so
   * the detail tier is populated. Package-private for the byte-equivalence test.
   */
  /** Tempdoc 549 Phase E5: the query-level QPP from the unified trace (flat fields retired). */
  private static io.justsearch.ipc.TraceQpp qppOf(SearchResponse resp) {
    return resp != null && resp.hasSearchTrace() && resp.getSearchTrace().hasQpp()
        ? resp.getSearchTrace().getQpp()
        : io.justsearch.ipc.TraceQpp.getDefaultInstance();
  }

  static Map<String, Float> unifiedDetailTier(SearchResult result) {
    Map<String, Float> union = new java.util.HashMap<>();
    for (io.justsearch.ipc.HitStage hs : result.getTraceList()) {
      union.putAll(hs.getDetailMap());
    }
    return union;
  }

  private static Long parentTokenCount(Map<String, Float> debugScores) {
    if (debugScores == null || debugScores.isEmpty()) {
      return null;
    }
    Float branch = debugScores.get("branch_merge_parent_token_count");
    if (branch != null && branch > 0f) {
      return Long.valueOf(Math.round(branch));
    }
    Float unprefixed = debugScores.get("parent_token_count");
    if (unprefixed != null && unprefixed > 0f) {
      return Long.valueOf(Math.round(unprefixed));
    }
    Float chunk = debugScores.get("chunk_parent_token_count");
    if (chunk != null && chunk > 0f) {
      return Long.valueOf(Math.round(chunk));
    }
    return null;
  }

  /**
   * Fetches the content of a single document. Returns {@code null} if the document is not found
   * or an error occurs.
   */
  private String fetchSingleDocContent(String docId) {
    try {
      FetchDocumentsResponse resp = knowledgeClientSupplier.get().fetchDocuments(List.of(docId), ENGINE_CONTEXT);
      for (DocumentContent doc : resp.getDocumentsList()) {
        if (doc.getFound() && !doc.getContent().isBlank()) {
          return doc.getContent();
        }
      }
    } catch (Exception e) {
      log.warn("GPL job: failed to fetch content for negative doc {}", docId, e);
    }
    return null;
  }

  // ========== LLM query generation ==========

  private String generateSyntheticQueries(String docContent) {
    String preview =
        docContent.length() > CONTENT_PREVIEW_CHARS
            ? docContent.substring(0, CONTENT_PREVIEW_CHARS)
            : docContent;
    String prompt = GPL_PROMPT_TEMPLATE.replace("{CONTENT}", preview);

    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", prompt));

    StringBuilder sb = new StringBuilder();
    CompletableFuture<String> future = new CompletableFuture<>();

    onlineAiService.streamChat(
        messages,
        GPL_MAX_TOKENS,
        chunk -> sb.append(chunk),
        fr -> future.complete(sb.toString()),
        err -> future.completeExceptionally(err),
        GPL_SAMPLING);

    try {
      return future.get(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (Exception e) {
      log.warn("GPL query generation timed out or failed", e);
      return null;
    }
  }

  // ========== Cross-encoder scoring (360: via remote Worker RPC) ==========

  private float scoreQueryDoc(String query, String docContent) {
    if (!rerankerAvailable) {
      return 1.0f;
    }
    try {
      RerankResponse result =
          knowledgeClientSupplier.get().rerank(query, List.of(docContent), RERANK_DEADLINE_MS, ENGINE_CONTEXT);
      if (!result.getSkipped() && result.getScoresCount() > 0) {
        return result.getScores(0);
      }
      return 1.0f;
    } catch (Exception e) {
      log.warn("Remote cross-encoder scoring failed; using default score 1.0", e);
      return 1.0f;
    }
  }

  private void writeStage3aAnalysisReport() {
    try {
      Path reportPath = GplStage3aAnalysisReport.write(tripleStore.storeFile());
      log.info("GPL Stage 3A analysis report written to {}", reportPath);
    } catch (IOException e) {
      log.warn("Failed to write GPL Stage 3A analysis report", e);
    }
  }

  private void writeStage3bBranchFusionReport() {
    try {
      Path reportPath = GplStage3bBranchFusionReport.write(tripleStore.storeFile());
      log.info("GPL Stage 3B branch fusion report written to {}", reportPath);
    } catch (IOException e) {
      log.warn("Failed to write GPL Stage 3B branch fusion report", e);
    }
  }

  // ========== Utilities ==========

  private static void sleepQuietly(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Whether a failed re-query means "the index half is busy or gone", as opposed to "this query is
   * bad". The distinction is load-bearing: a transient failure aborts the GPL pass
   * (:464-467) so the run is retried later, while any other failure writes the positive triple with
   * zero features — which would poison the training set if it were really a transient outage.
   *
   * <p><b>Retargeted at lane F stage A item A14.</b> This used to walk the cause chain for
   * {@code io.grpc.StatusRuntimeException} / {@code io.grpc.StatusException} and match
   * {@code UNAVAILABLE} / {@code DEADLINE_EXCEEDED}. Since item A6 the client boundary is
   * in-process and {@code EngineKnowledgeClient} translates every worker failure into
   * {@link KnowledgeClientException} (see that class's javadoc), so no gRPC status type can reach
   * this chain any more and the method could only ever return false — the abort branch was
   * unreachable and every transient outage silently wrote a zero-feature triple. The two status
   * constants are 1:1 copies of the gRPC codes this replaced
   * ({@code KnowledgeClientException.Status.UNAVAILABLE} / {@code DEADLINE_EXCEEDED}), so the
   * classification is the same one, reached through the type that actually arrives.
   */
  static boolean isTransientWorkerUnavailable(Throwable t) {
    for (Throwable cur = t; cur != null; cur = cur.getCause()) {
      if (cur instanceof KnowledgeClientException kce
          && (kce.status() == KnowledgeClientException.Status.UNAVAILABLE
              || kce.status() == KnowledgeClientException.Status.DEADLINE_EXCEEDED)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Waits for AI to become available using exponential backoff.
   *
   * <p>Backoff schedule: 2s → 4s → 8s → 16s → 30s → 30s... (capped at
   * {@link #AI_WAIT_MAX_DELAY_MS}). Returns {@code true} if AI became available
   * within {@link #AI_WAIT_TIMEOUT_MS}, {@code false} if the timeout was exceeded.
   */
  private boolean waitForAiAvailability() {
    long waited = 0;
    long delay = AI_WAIT_INITIAL_MS;
    while (!onlineAiService.isAvailable()) {
      if (waited >= AI_WAIT_TIMEOUT_MS || Thread.currentThread().isInterrupted()) {
        return false;
      }
      log.info(
          "GPL job: AI unavailable, waiting {}ms ({}s total elapsed)",
          delay,
          waited / 1000);
      sleepQuietly(delay);
      waited += delay;
      delay = Math.min(delay * 2, AI_WAIT_MAX_DELAY_MS);
    }
    if (waited > 0) {
      log.info("GPL job: AI available after {}ms wait", waited);
    }
    return true;
  }
}
