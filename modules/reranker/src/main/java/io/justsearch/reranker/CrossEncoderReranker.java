/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import io.justsearch.core.execution.InferenceRequest;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.GpuArenaPlan;
import io.justsearch.ort.GpuCallPacker;
import io.justsearch.ort.OrtCudaStatus;
import io.justsearch.ort.SessionAcquireDeadlineExceededException;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.ort.SessionHandle;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import java.io.Closeable;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import io.justsearch.telemetry.OpenInferenceSpans;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.IntStream;
import net.logstash.logback.marker.Markers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cross-encoder reranker using ONNX Runtime for inference.
 *
 * <p>This class is thread-safe. The ONNX session is shared across threads, while tensors are
 * created per-request.
 *
 * <p>Session lifecycle (CPU session, GPU session, GPU retry, VRAM release) is delegated to {@link
 * SessionHandle}. This class owns only the tokenizer and inference logic.
 *
 * <p>Usage (tempdoc 397 §14.24 FC / §14.28 U1): the composition root
 * {@code InferenceCompositionRoot.compose(...)} produces a {@code RerankerAssembly} whose
 * {@link SessionHandle} is passed to this constructor. Tests + benchmarks route through
 * {@code InferenceCompositionRootTestHelper.sessionFor} in ort-common testFixtures.
 */
public final class CrossEncoderReranker implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(CrossEncoderReranker.class);

  // Tempdoc 400 §22 Issue D / LR2-e.2 (Phase 6 / 6.8): every rerank
  // invocation — chunk-rerank, search-rerank, document-rerank —
  // emits a `search/rerank` span with `search.ce.scored` attr. Moving
  // the span into the reranker (vs wrapping at each caller) centralizes
  // the instrumentation so adding a new caller Just Works.
  private static final Tracer tracer =
      GlobalOpenTelemetry.getTracer("io.justsearch.reranker");

  /** Batch-size buckets to limit ORT plan recompilation to a few shapes (D4). */
  private static final int[] BATCH_SIZE_BUCKETS = {4, 8, 16, 24, 32, 48, 64};

  // Session management (delegated to SessionHandle — tempdoc 397 §14.5 PR 2).
  private final SessionHandle sessions;
  private final RerankerTokenizer tokenizer;
  private final boolean needsTokenTypeIds;
  private final int maxSequenceLength;
  private final java.util.concurrent.atomic.AtomicBoolean truncationReported =
      new java.util.concurrent.atomic.AtomicBoolean();

  /**
   * Tempdoc 397 §14.24 FD primary constructor. All construction inputs are pre-built by the
   * composition root (or by {@link #buildAssembly} for dev-mode fallback paths). Encoder
   * performs zero filesystem I/O.
   *
   * @param sessions pre-configured session handle
   * @param shape model-intrinsic facts ({@code maxSeqLen} + {@code needsTokenTypeIds})
   * @param tokenizer pre-loaded reranker tokenizer
   */
  public CrossEncoderReranker(
      SessionHandle sessions, RerankerShape shape, RerankerTokenizer tokenizer) {
    this.sessions = sessions;
    this.tokenizer = tokenizer;
    this.needsTokenTypeIds = shape.needsTokenTypeIds();
    this.maxSequenceLength = shape.maxSequenceLength();
    log.info(
        "CrossEncoderReranker initialized: maxSeqLen={}, tokenTypeIds={}",
        maxSequenceLength,
        needsTokenTypeIds);
  }

  /**
   * Builds a complete {@link RerankerAssembly} from a session handle + tokenizer path + max
   * sequence length. Tempdoc 397 §14.24 FD-Reranker. Shared helper called by
   * {@code InferenceCompositionRoot.composeRerankAssembly} (variant-driven path) and by test
   * harnesses that need to construct a reranker from raw paths.
   *
   * @throws OrtException if session input-name probe fails
   */
  public static RerankerAssembly buildAssembly(
      SessionHandle sessions, Path tokenizerPath, int maxSequenceLength) throws OrtException {
    // Tempdoc 397 §14.24 FD-ProbeDeletion: probe input names via the assembler helper.
    // Tempdoc 374 sandbox round 4 issue H: resolve via ModelManifest so the probe
    // hits whichever variant Install AI placed on disk (FP32 model.onnx vs FP16
    // model_fp16.onnx).
    Path modelDir = tokenizerPath.getParent();
    Path modelPath =
        io.justsearch.ort.ModelManifest.loadOrDefault(modelDir).resolveExistingModelFile(modelDir);
    return buildAssembly(sessions, tokenizerPath, modelPath, maxSequenceLength);
  }

  /** Uses the exact witnessed model for both the session and its input-name probe. */
  public static RerankerAssembly buildAssembly(
      SessionHandle sessions, Path tokenizerPath, Path modelPath, int maxSequenceLength)
      throws OrtException {
    RerankerTokenizer tokenizer = new RerankerTokenizer(tokenizerPath, maxSequenceLength);
    io.justsearch.ort.OrtSessionAssembler.ProbedNames probed =
        io.justsearch.ort.OrtSessionAssembler.probeModelNames(sessions.environment(), modelPath);
    boolean needsTokenTypeIds = probed.inputs().contains("token_type_ids");
    return new RerankerAssembly(
        sessions, new RerankerShape(maxSequenceLength, needsTokenTypeIds), tokenizer);
  }

  /**
   * Releases the GPU session to free VRAM.
   *
   * <p>Called when Main claims GPU and Worker needs to yield. The session will be lazily recreated
   * on next use when GPU becomes available again.
   *
   * <p>Thread-safe: concurrent rerank() calls will gracefully fall back to CPU during release.
   */
  public void releaseGpuSession() {
    sessions.releaseGpu();
  }

  /**
   * Returns true if GPU is currently available and not being released.
   *
   * <p>Used by callers to make adaptive decisions (e.g., rerank order) based on GPU availability.
   * Note: This reflects the current state; GPU may become unavailable if Main claims it.
   *
   * @return true if GPU session is available for inference
   */
  public boolean isGpuAvailable() {
    return sessions.isGpuAvailable();
  }

  /**
   * F1: Returns the ORT CUDA status for observability.
   *
   * <p>Exposes structured status information that can be surfaced via /api/status to help users
   * understand why GPU reranking may not be working.
   *
   * @return current ORT CUDA status (never null)
   */
  public OrtCudaStatus getOrtCudaStatus() {
    return sessions.status();
  }

  /**
   * Reranks documents based on relevance to query.
   *
   * @param query the search query
   * @param documents list of document texts to rerank
   * @param deadlineMs best-effort time budget in milliseconds (used as a pre-check; not a hard
   *     timeout)
   * @return reranking result with sorted indices, or original order if reranking was skipped or
   *     errored
   */
  public RerankedResult rerank(String query, List<String> documents, long deadlineMs) {
    return rerank(query, documents, deadlineMs, InferenceRequest.within(
        InferenceRequest.Urgency.FOREGROUND, java.time.Duration.ofSeconds(30), () -> false));
  }

  /**
   * Reranks with the admitted caller's native waiting and cancellation authority.
   *
   * <p>When {@code request} asks to be released at its deadline (a search's rerank, Spec D §1.5:
   * its semantic budget is not {@code NONE}), the native run is handed to one of the reranker's own
   * run threads and the rerank answers by {@link #answerBy} that deadline whether or not the run
   * has returned: a run that has not returned continues there, its result dropped, and the rerank
   * is skipped with {@link RerankSkipCause#RUN_DEADLINE_EXCEEDED}. When every reranker run thread
   * already holds such a run, the rerank is skipped the same way at once, without queueing. The
   * caller's thread, and everything the caller holds while it runs, is never held by an abandoned
   * native run.
   */
  public RerankedResult rerank(String query, List<String> documents, long deadlineMs,
      InferenceRequest request) {
    var authority = SessionAcquisitionRequest.from(request);
    long startNanos = System.nanoTime();
    authority.remainingNanos();
    long localDeadline = startNanos + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
        Math.max(1L, Math.min(deadlineMs, 30_000L)));
    var acquisition = new SessionAcquisitionRequest(authority.urgency(),
        startNanos + Math.min(authority.deadlineNanos() - startNanos, localDeadline - startNanos),
        authority.cancellationRequested());
    // Whether the rerank's own budget, not the caller's deadline, bounds the wait for a session.
    boolean rerankBudgetBounds = localDeadline - authority.deadlineNanos() <= 0;
    OptionalLong releaseAt =
        authority.releaseCallerAtDeadline()
            ? OptionalLong.of(answerBy(authority.deadlineNanos()))
            : OptionalLong.empty();

    if (documents.isEmpty()) {
      return new RerankedResult(List.of(), List.of(), RerankSkipCause.NONE, 0);
    }

    // Tempdoc 400 §22 Issue D / LR2-e.2 (Phase 6 / 6.8): wrap the full
    // rerank invocation in a `search/rerank` span with `search.ce.scored`
    // = document count. Covers chunk, search, and document rerank call
    // paths uniformly (previously only RagContextOps.chunkRerank had a
    // span).
    Span rerankSpan =
        tracer
            .spanBuilder("search/rerank")
            .setAttribute("search.ce.scored", documents.size())
            .startSpan();
    try {
      RerankedResult result =
          rerankInSpan(
              query, documents, deadlineMs, startNanos, acquisition, rerankBudgetBounds, releaseAt);
      // Tempdoc 553 Phase D (head): OpenInference RERANKER projection of the CE-scored output (the
      // reranked candidate texts + scores, in the cross-encoder's chosen order). Same shared
      // OpenInferenceSpans projector the worker + head spans use — no per-module vocabulary fork.
      rerankSpan.setAllAttributes(
          OpenInferenceSpans.reranker(
              "cross-encoder", documents.size(), rerankOutputDocs(documents, result)));
      return result;
    } finally {
      rerankSpan.end();
    }
  }

  /** How long before its caller's deadline a rerank that releases its caller answers: 100 ms. */
  private static final long ANSWER_MARGIN_NANOS =
      java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100);

  /**
   * When a rerank that releases its caller answers: 100 ms before the caller's {@code
   * deadlineNanos} (at most half of what is left of it), so that the answer, and the end of the
   * call it runs in, reach the caller before the caller stops waiting.
   */
  public static long answerBy(long deadlineNanos) {
    long remaining = Math.max(0L, deadlineNanos - System.nanoTime());
    return deadlineNanos - Math.min(ANSWER_MARGIN_NANOS, remaining / 2);
  }

  /** Map the reranked output (sorted order + per-doc scores) to the shared OpenInference Doc list. */
  private static List<OpenInferenceSpans.Doc> rerankOutputDocs(
      List<String> documents, RerankedResult result) {
    List<OpenInferenceSpans.Doc> docs = new ArrayList<>();
    List<Float> scores = result.scores();
    for (int idx : result.sortedIndices()) {
      if (docs.size() >= OpenInferenceSpans.MAX_DOCUMENTS) {
        break;
      }
      if (idx < 0 || idx >= documents.size()) {
        continue;
      }
      double score = (scores != null && idx < scores.size()) ? scores.get(idx) : 0.0;
      // Content-only documents (the reranker receives texts, not ids): id is null.
      docs.add(new OpenInferenceSpans.Doc(null, score, documents.get(idx)));
    }
    return docs;
  }

  /**
   * Makes the tokenizer's own truncation visible (tempdoc 836 §1.3(b), §9 Q6). A default-sized
   * indexed chunk already overflows the 512-token pair window on real prose, so this fires on
   * ordinary traffic — it is reported once at WARN and thereafter at DEBUG, because the fix
   * (chunk-side headroom, or windowing at scoring time) is a separate change with its own owner,
   * and a per-query WARN would bury every other line in the log.
   */
  private void reportTruncation(RerankerTokenizer.EncodedBatch batch) {
    if (batch.truncatedPairs() == 0) {
      return;
    }
    if (truncationReported.compareAndSet(false, true)) {
      log.warn(
          "Reranker pair truncation: {} of {} pairs exceed maxSeqLen={} (longest {} tokens) — the"
              + " tail of those documents is not seen by the model",
          batch.truncatedPairs(),
          batch.batchSize(),
          batch.seqLength(),
          batch.longestPairTokens());
    } else {
      log.debug(
          "Reranker pair truncation: {} of {} pairs exceed maxSeqLen={}",
          batch.truncatedPairs(),
          batch.batchSize(),
          batch.seqLength());
    }
  }

  private RerankedResult rerankInSpan(
      String query, List<String> documents, long deadlineMs, long startNanos,
      SessionAcquisitionRequest acquisition, boolean rerankBudgetBounds, OptionalLong releaseAt) {

    // Track which session was used so D9 teardown only fires for CPU failures (tempdoc 397 §14.5
    // W3: the raw-session-identity compare moved to Lease.isCpu()).
    boolean wasCpu = false;
    try {
      // Tokenize query-document pairs
      RerankerTokenizer.EncodedBatch batch =
          tokenizer.encodePairs(query, documents.toArray(new String[0]));
      reportTruncation(batch);

      long tokenizeMs = (System.nanoTime() - startNanos) / 1_000_000;
      if (tokenizeMs > deadlineMs * 0.5) {
        log.debug(
            Markers.append("reason_code", "rerank_skipped_tokenize_budget")
                .and(Markers.append("latency_ms", tokenizeMs))
                .and(Markers.append("budget_ms", deadlineMs)),
            "Rerank skipped: tokenization took {}ms (budget={}ms)",
            tokenizeMs,
            deadlineMs);
        return new RerankedResult(
            originalOrder(documents.size()),
            List.of(),
            RerankSkipCause.TOKENIZE_BUDGET_EXCEEDED,
            tokenizeMs);
      }

      // Prepare ONNX tensors — pad batch to bucket boundary to limit ORT plan recompilation (D4)
      int actualBatchSize = batch.batchSize();
      int paddedBatchSize = bucketBatchSize(actualBatchSize);
      int seqLength = batch.seqLength();

      // Spec E §2.2: the device-memory plan read for this rerank may split it into several calls.
      // Without a plan (and whenever one call fits) it is one call, as before.
      if (callItems(actualBatchSize, seqLength) < actualBatchSize) {
        boolean[] usedCpu = {false};
        try {
          return rerankSplit(
              batch, deadlineMs, startNanos, acquisition, rerankBudgetBounds, releaseAt, usedCpu);
        } finally {
          wasCpu = usedCpu[0];
        }
      }

      long[][] inputIds = padBatch(batch.inputIds(), paddedBatchSize, seqLength);
      long[][] attentionMask = padAttentionMask(batch.attentionMask(), paddedBatchSize, seqLength);
      long[][] tokenTypeIds = padBatch(batch.tokenTypeIds(), paddedBatchSize, seqLength);
      long[] shape = new long[] {paddedBatchSize, seqLength};

      try (OnnxTensor inputIdsTensor =
              OnnxTensor.createTensor(sessions.environment(), flatten(inputIds), shape);
          OnnxTensor attentionMaskTensor =
              OnnxTensor.createTensor(sessions.environment(), flatten(attentionMask), shape);
          OnnxTensor tokenTypeIdsTensor =
              needsTokenTypeIds
                  ? OnnxTensor.createTensor(sessions.environment(), flatten(tokenTypeIds), shape)
                  : null) {

        Map<String, OnnxTensor> inputs = new HashMap<>();
        inputs.put("input_ids", inputIdsTensor);
        inputs.put("attention_mask", attentionMaskTensor);
        if (tokenTypeIdsTensor != null) {
          inputs.put("token_type_ids", tokenTypeIdsTensor);
        }

        // Check deadline before inference
        long preInferenceMs = (System.nanoTime() - startNanos) / 1_000_000;
        if (preInferenceMs > deadlineMs * 0.7) {
          log.debug(
              Markers.append("reason_code", "rerank_skipped_prep_budget")
                  .and(Markers.append("latency_ms", preInferenceMs))
                  .and(Markers.append("budget_ms", deadlineMs)),
              "Rerank skipped: prep took {}ms, insufficient budget for inference",
              preInferenceMs);
          return new RerankedResult(
              originalOrder(documents.size()),
              List.of(),
              RerankSkipCause.PREP_BUDGET_EXCEEDED,
              preInferenceMs);
        }

        // Run inference (select GPU or CPU session based on availability and arbitration)
        // Bind native waiting to this rerank's existing request budget, with a finite ceiling
        // even if a caller supplied an unusually large budget.
        SessionHandle.Lease acquired;
        try {
          acquired = GpuCallPacker.acquireForCall(
              sessions, acquisition, EncoderRole.RERANKER, paddedBatchSize, seqLength);
        } catch (RuntimeException notAcquired) {
          if (!(notAcquired instanceof InferenceRequest.DeadlineExceeded)) throw notAcquired;
          // No session within the acquisition deadline (the device was busy, or a session was
          // still being created): a budget skip, not a failure of the rerank call.
          long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
          RerankSkipCause cause =
              rerankBudgetBounds
                  ? RerankSkipCause.ACQUIRE_BUDGET_EXCEEDED
                  : RerankSkipCause.RUN_DEADLINE_EXCEEDED;
          log.debug(
              Markers.append("reason_code", "rerank_skipped_acquire_budget")
                  .and(Markers.append("latency_ms", elapsedMs))
                  .and(Markers.append("budget_ms", deadlineMs))
                  .and(Markers.append("cause", cause.name())),
              "Rerank skipped: no session within its acquisition deadline: {}",
              notAcquired.getMessage());
          return new RerankedResult(originalOrder(documents.size()), List.of(), cause, elapsedMs);
        }
        try (var lease = acquired) {
          wasCpu = lease.isCpu();
          // Tempdoc 710 Move 2: lease.run() is the ORT choke point — records elapsed time via
          // the recorder bound by the composition root (reranker/citation have no worker-core
          // dependency, so registration + binding happens in InferenceCompositionRoot).
          OrtSession.Result ran;
          if (releaseAt.isEmpty()) {
            ran = lease.run(inputs);
          } else {
            // The lease's own deadline is checked here, as lease.run does, so the catch below
            // sees only the run thread's answer: not returned in time, or not started.
            acquisition.remainingNanos();
            try {
              ran = lease.runWithin(inputs, releaseAt.getAsLong());
            } catch (SessionAcquireDeadlineExceededException notInTime) {
              long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
              log.debug(
                  Markers.append("reason_code", "rerank_skipped_run_deadline")
                      .and(Markers.append("latency_ms", elapsedMs))
                      .and(Markers.append("phase", notInTime.phase().name())),
                  "Rerank skipped: the native run could not finish by the caller's deadline: {}",
                  notInTime.getMessage());
              return new RerankedResult(
                  originalOrder(documents.size()),
                  List.of(),
                  RerankSkipCause.RUN_DEADLINE_EXCEEDED,
                  elapsedMs);
            }
          }
          try (OrtSession.Result result = ran) {
            // Extract scores — only for actual documents, not padding rows
            List<Float> scores = extractScores(result, actualBatchSize);

            // Sort by score descending
            List<Integer> sortedIndices =
                IntStream.range(0, scores.size())
                    .boxed()
                    .sorted(Comparator.comparingDouble(scores::get).reversed())
                    .toList();

            long totalMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.debug("Rerank completed: {} docs in {}ms", actualBatchSize, totalMs);
            return new RerankedResult(sortedIndices, scores, RerankSkipCause.NONE, totalMs);
          }
        }
      }
    } catch (OrtException e) {
      boolean arenaExhausted = io.justsearch.ort.NativeSessionHandle.isBfcArenaFailure(e);
      // Register F-054: an arena exhaustion has one actionable remedy and the campaign that found
      // it had to discover the knob by hand — name it at the failure site, where the ORT message
      // that proves the diagnosis is still in hand.
      log.error(
          Markers.append("reason_code", "rerank_inference_failed")
              .and(Markers.append("arena_exhausted", arenaExhausted)),
          inferenceFailureMessage(arenaExhausted),
          e);
      // D9: report CPU session failure so the manager recreates it on next call,
      // releasing dead BFCArena allocations. Only fire when the CPU session was used —
      // GPU inference failures don't corrupt the CPU session (tempdoc 397 §14.5 W6).
      // Tempdoc 414 A3: classify the cause so ort.session.recovery_total{cause} is meaningful.
      if (wasCpu) {
        io.justsearch.ort.telemetry.CpuRecreateCause cause =
            arenaExhausted
                ? io.justsearch.ort.telemetry.CpuRecreateCause.BFC_ARENA_FAILURE
                : io.justsearch.ort.telemetry.CpuRecreateCause.REPORTED_FAILURE;
        sessions.reportCpuSessionFailure(cause);
      }
      long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
      return new RerankedResult(
          originalOrder(documents.size()), List.of(), RerankSkipCause.INFERENCE_FAILED, elapsedMs);
    }
  }

  /**
   * The pairs, of {@code remaining}, the next call of a rerank takes: all of them without a plan,
   * otherwise as many as the plan's packing budget admits at this sequence length and padded pair
   * count (Spec E §2.2).
   */
  private int callItems(int remaining, int seqLength) {
    return callItems(sessions.arenaPlan(), remaining, seqLength);
  }

  private static int callItems(Optional<GpuArenaPlan> packingPlan, int remaining, int seqLength) {
    return GpuCallPacker.itemsPerCall(
        packingPlan,
        EncoderRole.RERANKER,
        Integer.MAX_VALUE,
        remaining,
        i -> seqLength,
        length -> length,
        CrossEncoderReranker::bucketBatchSize);
  }

  /**
   * A rerank the plan splits into several calls (Spec E invariant 7). One lease, and so one device
   * seat, is held for all of the calls, so aging cannot seat bulk work between them. The scores are
   * used only when every call returned: a deadline miss on any call returns every document in its
   * original order, and a failed call is the caller's {@code INFERENCE_FAILED}. Scored and unscored
   * documents are never mixed.
   *
   * @param usedCpu set to whether the lease was a CPU lease, for the caller's failure report
   */
  private RerankedResult rerankSplit(
      RerankerTokenizer.EncodedBatch batch,
      long deadlineMs,
      long startNanos,
      SessionAcquisitionRequest acquisition,
      boolean rerankBudgetBounds,
      OptionalLong releaseAt,
      boolean[] usedCpu)
      throws OrtException {
    int total = batch.batchSize();
    int seqLength = batch.seqLength();
    long preInferenceMs = (System.nanoTime() - startNanos) / 1_000_000;
    if (preInferenceMs > deadlineMs * 0.7) {
      log.debug(
          Markers.append("reason_code", "rerank_skipped_prep_budget")
              .and(Markers.append("latency_ms", preInferenceMs))
              .and(Markers.append("budget_ms", deadlineMs)),
          "Rerank skipped: prep took {}ms, insufficient budget for inference",
          preInferenceMs);
      return new RerankedResult(
          originalOrder(total), List.of(), RerankSkipCause.PREP_BUDGET_EXCEEDED, preInferenceMs);
    }
    Optional<GpuArenaPlan> packingPlan = sessions.arenaPlan();
    SessionHandle.Lease acquired;
    try {
      acquired = GpuCallPacker.acquireForCall(
          sessions, acquisition, EncoderRole.RERANKER,
          bucketBatchSize(callItems(packingPlan, total, seqLength)), seqLength, packingPlan);
    } catch (RuntimeException notAcquired) {
      if (!(notAcquired instanceof InferenceRequest.DeadlineExceeded)) throw notAcquired;
      long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
      RerankSkipCause cause =
          rerankBudgetBounds
              ? RerankSkipCause.ACQUIRE_BUDGET_EXCEEDED
              : RerankSkipCause.RUN_DEADLINE_EXCEEDED;
      log.debug(
          Markers.append("reason_code", "rerank_skipped_acquire_budget")
              .and(Markers.append("latency_ms", elapsedMs))
              .and(Markers.append("budget_ms", deadlineMs))
              .and(Markers.append("cause", cause.name())),
          "Rerank skipped: no session within its acquisition deadline: {}",
          notAcquired.getMessage());
      return new RerankedResult(originalOrder(total), List.of(), cause, elapsedMs);
    }
    List<Float> scores = new ArrayList<>(total);
    int calls = 0;
    try (var lease = acquired) {
      usedCpu[0] = lease.isCpu();
      int start = 0;
      while (start < total) {
        int items = lease.isCpu() ? total - start : callItems(packingPlan, total - start, seqLength);
        int end = start + items;
        int padded = bucketBatchSize(items);
        long[][] inputIds =
            padBatch(Arrays.copyOfRange(batch.inputIds(), start, end), padded, seqLength);
        long[][] attentionMask =
            padAttentionMask(
                Arrays.copyOfRange(batch.attentionMask(), start, end), padded, seqLength);
        long[][] tokenTypeIds =
            padBatch(Arrays.copyOfRange(batch.tokenTypeIds(), start, end), padded, seqLength);
        long[] shape = new long[] {padded, seqLength};
        try (OnnxTensor inputIdsTensor =
                OnnxTensor.createTensor(sessions.environment(), flatten(inputIds), shape);
            OnnxTensor attentionMaskTensor =
                OnnxTensor.createTensor(sessions.environment(), flatten(attentionMask), shape);
            OnnxTensor tokenTypeIdsTensor =
                needsTokenTypeIds
                    ? OnnxTensor.createTensor(sessions.environment(), flatten(tokenTypeIds), shape)
                    : null) {
          Map<String, OnnxTensor> inputs = new HashMap<>();
          inputs.put("input_ids", inputIdsTensor);
          inputs.put("attention_mask", attentionMaskTensor);
          if (tokenTypeIdsTensor != null) {
            inputs.put("token_type_ids", tokenTypeIdsTensor);
          }
          OrtSession.Result ran;
          try {
            if (releaseAt.isEmpty()) {
              ran = lease.run(inputs);
            } else {
              acquisition.remainingNanos();
              ran = lease.runWithin(inputs, releaseAt.getAsLong());
            }
          } catch (RuntimeException notInTime) {
            if (!(notInTime instanceof InferenceRequest.DeadlineExceeded)) throw notInTime;
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.debug(
                Markers.append("reason_code", "rerank_skipped_run_deadline")
                    .and(Markers.append("latency_ms", elapsedMs))
                    .and(Markers.append("calls_returned", calls)),
                "Rerank skipped: call {} of a split rerank could not finish by the deadline: {}",
                calls + 1,
                notInTime.getMessage());
            return new RerankedResult(
                originalOrder(total), List.of(), RerankSkipCause.RUN_DEADLINE_EXCEEDED, elapsedMs);
          }
          try (OrtSession.Result result = ran) {
            scores.addAll(extractScores(result, items));
          }
        }
        calls++;
        start = end;
      }
    }
    List<Integer> sortedIndices =
        IntStream.range(0, scores.size())
            .boxed()
            .sorted(Comparator.comparingDouble(scores::get).reversed())
            .toList();
    long totalMs = (System.nanoTime() - startNanos) / 1_000_000;
    log.debug("Rerank completed: {} docs in {} calls, {}ms", total, calls, totalMs);
    return new RerankedResult(sortedIndices, scores, RerankSkipCause.NONE, totalMs);
  }

  /**
   * The log line for an inference failure. Register F-054: an ONNX Runtime arena exhaustion is the
   * one inference failure with a single known remedy, and it is invisible from the deadline-shaped
   * signals the caller sees — so the knob is named here, at the only place that holds the ORT
   * message proving the diagnosis. Package-private and pure so the wording is pinned by a test
   * rather than by reading the log.
   */
  static String inferenceFailureMessage(boolean arenaExhausted) {
    return arenaExhausted
        ? "Rerank inference failed: the ONNX Runtime memory arena could not satisfy an allocation."
            + " This is NOT a deadline miss — no rerank deadline value fixes it. Raise the arena"
            + " size via JUSTSEARCH_RERANK_GPU_MEM_MB (justsearch.rerank.gpu_mem_mb)."
        : "Rerank inference failed";
  }

  private List<Float> extractScores(OrtSession.Result result, int actualBatchSize)
      throws OrtException {
    // Get first output (logits)
    Object output = result.get(0).getValue();

    if (output instanceof float[][] logits2d) {
      // Shape [paddedBatch, num_labels] - take relevance class, only for actual documents
      return IntStream.range(0, actualBatchSize)
          .mapToObj(i -> logits2d[i].length > 1 ? logits2d[i][1] : logits2d[i][0])
          .toList();
    } else if (output instanceof float[] logits1d) {
      // Shape [paddedBatch] - direct scores, only for actual documents
      return IntStream.range(0, actualBatchSize).mapToObj(i -> logits1d[i]).toList();
    }

    throw new OrtException("Unexpected output shape: " + output.getClass());
  }

  private static LongBuffer flatten(long[][] array) {
    int rows = array.length;
    int cols = array[0].length;
    long[] flat = new long[rows * cols];
    for (int i = 0; i < rows; i++) {
      System.arraycopy(array[i], 0, flat, i * cols, cols);
    }
    return LongBuffer.wrap(flat);
  }

  private static List<Integer> originalOrder(int size) {
    return IntStream.range(0, size).boxed().toList();
  }

  /**
   * Rounds batch size up to the next bucket boundary. Limits the number of distinct tensor shapes
   * to {@code BATCH_SIZE_BUCKETS.length}, preventing ORT execution plan recompilation on every
   * search request with a different result count (D4).
   */
  static int bucketBatchSize(int batchSize) {
    for (int bucket : BATCH_SIZE_BUCKETS) {
      if (batchSize <= bucket) return bucket;
    }
    return batchSize; // beyond largest bucket — use exact size
  }

  /**
   * Pads a 2D array to the target row count. Padding rows are zero-filled (Java default). For
   * inputIds (0=[PAD]) and tokenTypeIds (0=segment A) this is correct. For attentionMask, callers
   * MUST set at least one position to 1 per padding row — models with global attention (e.g.,
   * ModernBERT) compute softmax across all rows, and an all-zero mask causes NaN propagation.
   */
  private static long[][] padBatch(long[][] original, int targetRows, int cols) {
    if (original.length >= targetRows) return original;
    long[][] padded = new long[targetRows][cols];
    System.arraycopy(original, 0, padded, 0, original.length);
    return padded;
  }

  /**
   * Pads attention mask and sets a non-zero anchor at position 0 for each padding row. Models with
   * global attention (e.g., ModernBERT) compute softmax across the entire batch tensor. All-zero
   * mask rows cause a zero-denominator softmax → NaN that propagates to ALL rows.
   *
   * <p>The actual batch size is derived from {@code original.length} — no separate parameter needed.
   */
  static long[][] padAttentionMask(long[][] original, int targetRows, int cols) {
    int actualBatchSize = original.length;
    long[][] padded = padBatch(original, targetRows, cols);
    for (int row = actualBatchSize; row < targetRows; row++) {
      padded[row][0] = 1;
    }
    return padded;
  }

  @Override
  public void close() {
    sessions.close();
    tokenizer.close();
    log.info("CrossEncoderReranker closed");
  }

  /**
   * Result of reranking operation.
   *
   * <p>Register F-054: {@code skipped} is derived from {@code skipCause}, not stored beside it —
   * one authority for "did the cross-encoder score these documents", so a caller can never read a
   * skip whose cause disagrees with it.
   *
   * @param sortedIndices indices sorted by relevance score (highest first)
   * @param scores relevance scores for each document (original order)
   * @param skipCause why the cross-encoder did not score (never null; {@link RerankSkipCause#NONE}
   *     when it did)
   * @param latencyMs time taken in milliseconds
   */
  public record RerankedResult(
      List<Integer> sortedIndices, List<Float> scores, RerankSkipCause skipCause, long latencyMs) {

    public RerankedResult {
      if (skipCause == null) {
        throw new IllegalArgumentException("skipCause must not be null (use RerankSkipCause.NONE)");
      }
    }

    /** True if reranking was skipped — a budget pre-check or an inference failure. */
    public boolean skipped() {
      return skipCause.isSkip();
    }
  }
}
