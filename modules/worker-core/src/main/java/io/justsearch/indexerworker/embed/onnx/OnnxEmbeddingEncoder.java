/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.tokenizers.jni.CharSpan;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.justsearch.indexerworker.inference.LocalSessionAcquisition;
import io.justsearch.indexerworker.metrics.EncoderOrtRunSpans;
import io.justsearch.ort.NativeSessionHandle;
import io.justsearch.ort.OrtCudaStatus;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.ort.SessionHandle;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ONNX Runtime-based embedding encoder for nomic-embed-text-v1.5.
 *
 * <p>Loads an ONNX model and produces L2-normalized dense embeddings. Supports GPU acceleration
 * with lazy initialization and CPU fallback (same pattern as {@code SpladeEncoder} and {@code
 * CrossEncoderReranker}).
 *
 * <p>The ONNX model outputs {@code last_hidden_state} (per-token embeddings). This encoder applies
 * pooling (mean or CLS) and L2 normalization to produce a single unit-length vector per input text.
 * Long texts exceeding {@code maxSeqLen} are chunked with a sliding window.
 *
 * <p>Pooling strategy is auto-detected from a {@code pooling_config.json} file in the model
 * directory (key: {@code "pooling_mode"}). Supported values: {@code "mean"} (default, used by
 * nomic-embed), {@code "cls"} (used by gte-modernbert). If no config file is found, defaults to
 * mean pooling for backward compatibility.
 */
public final class OnnxEmbeddingEncoder implements Closeable {

  private static final Logger log = LoggerFactory.getLogger(OnnxEmbeddingEncoder.class);

  /** Pooling strategy for extracting a single vector from per-token hidden states. */
  public enum PoolingStrategy {
    /** Attention-mask-weighted mean of all token embeddings (nomic-embed, E5, BGE). */
    MEAN_POOL,
    /** First token (CLS) embedding (gte-modernbert, DPR). */
    CLS
  }

  // --- Session management (delegated to SessionHandle — tempdoc 397 §7.4 / §14.5 W1) ---
  private final SessionHandle sessions;

  // --- Tokenizer ---
  private final HuggingFaceTokenizer tokenizer;
  private final int maxSeqLen;
  // Tempdoc 691 Phase 2: single-pass whole-doc VECTOR limit for embedWithSpans, independent of
  // maxSeqLen. Falls back to maxSeqLen when the shape's value is <= 0 (defensive — DISABLED /
  // test paths that don't wire the late-chunking config).
  private final int lateChunkingMaxSeqLen;
  private final boolean needsTokenTypeIds;

  // --- Chunking ---
  private final int chunkSize;
  private final int chunkOverlap;

  // --- Pooling strategy (auto-detected from model config) ---
  private final PoolingStrategy poolingStrategy;

  // --- Embedding dimension (detected from model output) ---
  private volatile int embeddingDimension;
  // Tempdoc 710 Wave 2 Move 1: declared dimension from the capability contract, 0 if undeclared.
  // Cross-check only — the reactive first-inference detection above stays authoritative for
  // embeddingDimension(); a mismatch is WARNed once, not silently overridden either direction.
  private final int declaredEmbeddingDimension;
  private final java.util.concurrent.atomic.AtomicBoolean warnedDimensionMismatch =
      new java.util.concurrent.atomic.AtomicBoolean(false);

  // --- Per-call profiling (356->357: shared accumulator, pull model) ---
  private final io.justsearch.indexerworker.metrics.EncoderProfileAccumulator profiler =
      new io.justsearch.indexerworker.metrics.EncoderProfileAccumulator(
          "tokenize", "tensor", "ort", "extract");
  private static final int PROFILE_LOG_INTERVAL = 50;

  // --- Per-ORT-call span tracer (tempdoc 400 LR2-a) ---
  private static final Tracer ORT_TRACER = EncoderOrtRunSpans.encoderTracer("embed");

  /** Result of embedding a single text (may contain chunk vectors if text was long). */
  public record EmbedResult(float[] vector, List<float[]> chunkVectors, int chunkCount) {}

  /**
   * Tempdoc 397 §14.24 FD-Embedding primary constructor. All construction inputs are pre-built
   * by the composition root (or by {@link #buildAssembly} for dev-mode fallback paths). Encoder
   * performs zero filesystem I/O.
   *
   * @param sessions pre-configured session handle
   * @param shape model-intrinsic facts (max sequence length + pooling + token_type_ids)
   * @param tokenizer pre-loaded DJL HuggingFace tokenizer
   */
  public OnnxEmbeddingEncoder(
      SessionHandle sessions, EmbeddingShape shape, HuggingFaceTokenizer tokenizer) {
    this.sessions = sessions;
    this.maxSeqLen = shape.maxSequenceLength();
    this.lateChunkingMaxSeqLen =
        shape.lateChunkingMaxSequenceLength() > 0 ? shape.lateChunkingMaxSequenceLength() : maxSeqLen;
    this.chunkSize = Math.min(512, maxSeqLen);
    this.chunkOverlap = 128;
    this.needsTokenTypeIds = shape.needsTokenTypeIds();
    this.tokenizer = tokenizer;
    this.poolingStrategy = shape.poolingStrategy();
    this.declaredEmbeddingDimension = shape.declaredEmbeddingDimension();

    log.info(
        "OnnxEmbeddingEncoder initialized: maxSeqLen={}, lateChunkingMaxSeqLen={}, tokenTypeIds={},"
            + " poolingStrategy={}",
        maxSeqLen,
        lateChunkingMaxSeqLen,
        needsTokenTypeIds,
        poolingStrategy);
    io.justsearch.indexerworker.metrics.OperationalMetrics.getInstance()
        .registerEncoder("embed", profiler);
    // Tempdoc 710 Move 2: bind the choke-point recorder so every session.run() invocation
    // through this SessionHandle's leases records itself — call sites (including runHidden's
    // late-chunking path) can no longer forget (the class of gap that shipped as the S-B3
    // runHidden blind spot, closed at the source by Wave 0 and now made structural). Null-guarded
    // because OnnxEmbeddingEncoderPoolingTest constructs this encoder with sessions=null to unit
    // test poolSpan/pool in isolation (tempdoc 691 Wave 0) — those pure functions never touch
    // sessions, so the constructor staying I/O-free for that path is intentional.
    if (sessions != null) {
      sessions.setOrtRunRecorder(profiler::recordOrtCall);
    }
  }

  /**
   * Builds a complete {@link EmbeddingAssembly} from a session handle + model directory + max
   * sequence length. Tempdoc 397 §14.24 FD-Embedding. Shared helper called by
   * {@code InferenceCompositionRoot.composeEmbedAssembly} (variant-driven path) and by test
   * harnesses + the worker-embed service lazy-init path.
   *
   * @param lateChunkingMaxSeqLen single-pass whole-doc token limit for {@link #embedWithSpans}
   *     (tempdoc 691 Phase 2); {@code <= 0} falls back to {@code maxSeqLen}
   * @param capabilityContractStrict when {@code true}, an undeclared/ambiguous model-capability
   *     fact throws instead of degrading with a WARN — {@code
   *     justsearch.models.capability_contract_strict} (tempdoc 710 Wave 2 Move 1)
   * @throws OrtException if session input-name probe fails
   * @throws UncheckedIOException if tokenizer load fails
   * @throws IllegalStateException if {@code capabilityContractStrict} and a capability fact is
   *     undeclared/ambiguous
   */
  public static EmbeddingAssembly buildAssembly(
      SessionHandle sessions,
      Path modelDir,
      int maxSeqLen,
      int lateChunkingMaxSeqLen,
      boolean capabilityContractStrict)
      throws OrtException {
    // Tempdoc 397 §14.24 FD-ProbeDeletion: probe input names via the assembler helper.
    // Tempdoc 374 sandbox round 4 issue H: previously hardcoded model.onnx, which
    // broke when Install AI only downloaded model_fp16.onnx on a CUDA-functional
    // host. resolveExistingModelFile picks whichever declared variant is on disk.
    io.justsearch.ort.ModelManifest manifest = io.justsearch.ort.ModelManifest.loadOrDefault(modelDir);
    Path probeModel = manifest.resolveExistingModelFile(modelDir);
    io.justsearch.ort.OrtSessionAssembler.ProbedNames probed =
        io.justsearch.ort.OrtSessionAssembler.probeModelNames(
            sessions.environment(), probeModel);
    boolean needsTokenTypeIds = probed.inputs().contains("token_type_ids");
    Path tokenizerPath = modelDir.resolve("tokenizer.json");
    HuggingFaceTokenizer tokenizer;
    try {
      tokenizer =
          HuggingFaceTokenizer.newInstance(
              tokenizerPath, Map.of("truncation", "false", "padding", "false"));
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Failed to load embedding tokenizer from " + tokenizerPath, e);
    }
    // Tempdoc 710 Wave 2 Move 1: capabilities resolved ONCE here (the composition choke point),
    // not parsed by this encoder — detectPoolingStrategy's own file read is retired.
    io.justsearch.ort.ModelCapabilities capabilities =
        io.justsearch.ort.ModelCapabilityResolver.resolve(
            "embedding",
            modelDir,
            manifest,
            io.justsearch.ort.CapabilityRequirements.EMBEDDING,
            capabilityContractStrict);
    PoolingStrategy poolingStrategy = toPoolingStrategy(capabilities.poolingMode());
    return new EmbeddingAssembly(
        sessions,
        new EmbeddingShape(
            maxSeqLen,
            needsTokenTypeIds,
            poolingStrategy,
            lateChunkingMaxSeqLen,
            capabilities.embeddingDimension()),
        tokenizer,
        capabilities);
  }

  /**
   * Maps the ort-common capability fact to this encoder's pooling enum. {@code UNKNOWN} (no
   * source declared a pooling mode) falls back to the historical default ({@code MEAN_POOL}) —
   * {@link io.justsearch.ort.ModelCapabilityResolver} already logged a WARN naming the gap at the
   * choke point, so this mapping doesn't re-warn.
   */
  private static PoolingStrategy toPoolingStrategy(io.justsearch.ort.ModelCapabilities.PoolingMode mode) {
    return mode == io.justsearch.ort.ModelCapabilities.PoolingMode.CLS
        ? PoolingStrategy.CLS
        : PoolingStrategy.MEAN_POOL;
  }

  /**
   * Embeds a text, chunking if it exceeds the model's context window.
   *
   * @param text input text to embed
   * @return embedding result with primary vector and optional chunk vectors
   * @throws OrtException if ONNX inference fails
   */
  public EmbedResult embed(String text) throws OrtException {
    return embed(text, LocalSessionAcquisition.foreground());
  }

  private EmbedResult embed(String text, SessionAcquisitionRequest acquisition) throws OrtException {
    Encoding encoding = tokenizer.encode(text);
    long[] ids = encoding.getIds();
    long[] mask = encoding.getAttentionMask();
    long[] typeIds = encoding.getTypeIds();

    if (ids.length <= maxSeqLen) {
      // Short text: single embedding
      float[] vector = embedSingle(ids, mask, typeIds, acquisition);
      return new EmbedResult(vector, List.of(), 1);
    }

    // Long text: chunk and mean-pool
    List<long[][]> chunks = createChunks(ids, mask, typeIds);
    List<float[]> chunkVectors = new ArrayList<>(chunks.size());

    for (long[][] chunk : chunks) {
      chunkVectors.add(embedSingle(chunk[0], chunk[1], chunk[2], acquisition));
    }

    float[] pooled = meanPoolChunks(chunkVectors);
    return new EmbedResult(pooled, chunkVectors, chunks.size());
  }

  /**
   * Embeds a batch of pre-chunked text strings in a single ORT inference call.
   *
   * <p>Each text is tokenized, truncated to maxSeqLen, and padded to the batch's max length. The
   * batch is run through ORT as a single [batchSize, maxLen] tensor. Each result is mean-pooled and
   * L2-normalized independently.
   *
   * @param texts list of text strings to embed (already chunked by caller)
   * @return list of embedding vectors, one per input text
   * @throws OrtException if ONNX inference fails
   */
  /**
   * Maximum ORT inference batch size. batch=16 is the saturation point for
   * per-doc throughput (Batch E probe, tempdoc 390/394), but at the default
   * 3072 MB embed arena with shrinkage-on, batch=16 triggers BFCArena
   * fragmentation OOMs on MatMul / BiasSoftmax activations (51 OOMs observed
   * over 5184-doc scifact run, 2026-04-20). Each OOM triggers per-doc fallback
   * in {@code EmbeddingBackfillOps} which negates the batching gain.
   *
   * <p>Kept at 8 until one of the following lands:
   * <ul>
   *   <li>Item 4 (E6' shrinkage-off config) — validated with 6144 MB arena,
   *       zero OOMs at batch=16;</li>
   *   <li>{@code arena_extend_strategy = kNextPowerOfTwo} — NOT a known fix (tempdoc 710 R-3a
   *       correction, tempdoc 394 Runs B+C 2026-04-20): it is the CUDA EP default, trading
   *       {@code kSameAsRequested}'s external-fragmentation risk for over-reservation risk: as a
   *       *global* setting it was already tried and reverted (regressed SPLADE ortP50 by 65% for
   *       zero net pipeline win). An untried embed-only per-session variant (~8s projected gain,
   *       tempdoc 394 item 4) remains a candidate, but any adoption needs its own A/B with
   *       VRAM-headroom measurement, not a "lands and fixes it" assumption;</li>
   *   <li>Raising this constant itself — distinct from the sub-batch OOM fallback ladder
   *       ({@link #embedPreTokenizedBatch}, tempdoc 710 Move 3) added below, which improves
   *       failure-mode coverage (a sub-batch OOM no longer nulls the whole caller batch) but does
   *       not by itself make batch=16 safe to raise to as the default.</li>
   * </ul>
   *
   * <p>Historical constraint (tempdoc 334 Phase 8): batch=16 also failed at
   * 2048 MB arena and at 4096 MB × 3 concurrent sessions (5× regression from
   * VRAM fragmentation). That scenario is superseded by the shrinkage-on
   * default (4.8 GB observed peak) but the per-encoder fragmentation issue
   * remains.
   */
  private static final int MAX_ORT_BATCH_SIZE = 8;

  public List<float[]> embedBatch(List<String> texts) throws OrtException {
    var acquisition = LocalSessionAcquisition.background();
    if (texts.isEmpty()) {
      return List.of();
    }
    if (texts.size() == 1) {
      // Fast path: avoid batch overhead for single text
      float[] vec = embed(texts.get(0), acquisition).vector();
      return List.of(vec);
    }

    // Sub-batch if input exceeds optimal ORT batch size
    if (texts.size() > MAX_ORT_BATCH_SIZE) {
      List<float[]> allResults = new ArrayList<>(texts.size());
      for (int start = 0; start < texts.size(); start += MAX_ORT_BATCH_SIZE) {
        int end = Math.min(start + MAX_ORT_BATCH_SIZE, texts.size());
        allResults.addAll(embedBatchInternal(texts.subList(start, end), acquisition));
      }
      return allResults;
    }

    return embedBatchInternal(texts, acquisition);
  }

  private List<float[]> embedBatchInternal(
      List<String> texts, SessionAcquisitionRequest acquisition) throws OrtException {
    int batchSize = texts.size();
    long tTok = System.nanoTime();
    List<long[][]> tokenized = new ArrayList<>(batchSize);
    for (int i = 0; i < batchSize; i++) {
      Encoding enc = tokenizer.encode(texts.get(i));
      int seqLen = Math.min(enc.getIds().length, maxSeqLen);
      tokenized.add(
          new long[][] {
            truncate(enc.getIds(), seqLen),
            truncate(enc.getAttentionMask(), seqLen),
            truncate(enc.getTypeIds(), seqLen)
          });
    }
    profiler.addPhaseNs("tokenize", System.nanoTime() - tTok);
    return embedPreTokenizedBatch(tokenized, acquisition);
  }

  /**
   * Embeds a batch of pre-tokenized chunks in a single ORT inference call.
   *
   * <p>Each element is {@code {ids, mask, typeIds}}. All chunks are padded to the batch's max
   * length. Sub-batches at {@link #MAX_ORT_BATCH_SIZE} to avoid memory pressure.
   *
   * @param tokenizedChunks list of token arrays, each {@code long[3][seqLen]}
   * @return one L2-normalized mean-pooled vector per input chunk
   */
  private List<float[]> embedPreTokenizedBatch(
      List<long[][]> tokenizedChunks, SessionAcquisitionRequest acquisition) throws OrtException {
    if (tokenizedChunks.isEmpty()) {
      return List.of();
    }
    if (tokenizedChunks.size() > MAX_ORT_BATCH_SIZE) {
      List<float[]> allResults = new ArrayList<>(tokenizedChunks.size());
      for (int start = 0; start < tokenizedChunks.size(); start += MAX_ORT_BATCH_SIZE) {
        int end = Math.min(start + MAX_ORT_BATCH_SIZE, tokenizedChunks.size());
        allResults.addAll(embedPreTokenizedBatch(tokenizedChunks.subList(start, end), acquisition));
      }
      return allResults;
    }

    int batchSize = tokenizedChunks.size();
    long[][] allIds = new long[batchSize][];
    long[][] allMask = new long[batchSize][];
    long[][] allTypeIds = new long[batchSize][];
    int maxLen = 0;

    for (int i = 0; i < batchSize; i++) {
      long[][] chunk = tokenizedChunks.get(i);
      allIds[i] = chunk[0];
      allMask[i] = chunk[1];
      allTypeIds[i] = chunk[2];
      maxLen = Math.max(maxLen, chunk[0].length);
    }

    // Pad all to uniform length
    for (int i = 0; i < batchSize; i++) {
      allIds[i] = padRight(allIds[i], maxLen);
      allMask[i] = padRight(allMask[i], maxLen);
      allTypeIds[i] = padRight(allTypeIds[i], maxLen);
    }

    long[] shape = {batchSize, maxLen};

    long tTensor = System.nanoTime();
    OrtEnvironment env = sessions.environment();
    try (OnnxTensor inputIdsTensor =
            OnnxTensor.createTensor(env, flatten(allIds, batchSize, maxLen), shape);
        OnnxTensor attentionMaskTensor =
            OnnxTensor.createTensor(env, flatten(allMask, batchSize, maxLen), shape);
        OnnxTensor tokenTypeIdsTensor =
            needsTokenTypeIds
                ? OnnxTensor.createTensor(env, flatten(allTypeIds, batchSize, maxLen), shape)
                : null) {

      Map<String, OnnxTensor> inputs = new HashMap<>();
      inputs.put("input_ids", inputIdsTensor);
      inputs.put("attention_mask", attentionMaskTensor);
      if (tokenTypeIdsTensor != null) {
        inputs.put("token_type_ids", tokenTypeIdsTensor);
      }

      long tOrt = System.nanoTime();
      profiler.addPhaseNs("tensor", tOrt - tTensor);

      // Tempdoc 400 LR2-a/LR2-b: encoder.ort_run starts before sessions.acquire()
      // so the lease.acquire child span emitted inside NativeSessionHandle parents
      // under it naturally. encoder.gpu is set post-acquire when lease.isCpu()
      // becomes knowable.
      Span ortSpan = EncoderOrtRunSpans.maybeOrtRun(ORT_TRACER, "embed", batchSize, maxLen);
      try (Scope _ = ortSpan.makeCurrent()) {
        try (var lease = sessions.acquire(acquisition)) {
          ortSpan.setAttribute("encoder.gpu", !lease.isCpu());

          long tExtract;
          float[][][] hidden;
          try (OrtSession.Result result = lease.run(inputs)) {
            tExtract = System.nanoTime();
            // ORT-call timing recorded at the Lease choke point (tempdoc 710 Move 2).
            // last_hidden_state: [batchSize, maxLen, dim]
            hidden = (float[][][]) result.get(0).getValue();
          } catch (OrtException e) {
            // Tempdoc 710 Move 3: without this, an OOM here propagates as OrtException ->
            // BackendException (OnnxEmbeddingBackend.embedBatch) -> null for the WHOLE caller
            // batch (EmbeddingService.embedDocumentBatch), even though only this ONE sub-batch
            // OOM'd; EmbeddingBackfillOps then re-embeds every doc in the caller batch one at a
            // time. Fallback ladder (mirrors SpladeEncoder's GPU->CPU pattern, but batch-1-on-GPU
            // first): (a) retry each doc in the failed sub-batch as its own batch=1 run on the
            // SAME GPU session/lease — batch=1 fits where the larger batch fragmented (measured
            // §J-4/tempdoc 691); (b) only if a doc ALSO arena-OOMs at batch=1, fall back to
            // sessions.acquireCpu() for that one doc; (c) non-arena OrtExceptions propagate
            // unchanged.
            if (lease.isCpu() || !NativeSessionHandle.isBfcArenaFailure(e)) {
              throw e;
            }
            log.info(
                "Embed GPU arena allocation failed for batch (batchSize={}, seqLen={}), falling"
                    + " back to batch-1 singles on GPU: {}",
                batchSize,
                maxLen,
                e.getMessage());
            final int fallbackSeqLen = maxLen;
            hidden =
                runOomFallbackLadder(
                    batchSize,
                    i ->
                        runSingleHidden(
                            lease, allIds[i], allMask[i], allTypeIds[i], fallbackSeqLen),
                    i -> {
                      try (var cpuLease = sessions.acquireCpu(acquisition)) {
                        return runSingleHidden(
                            cpuLease, allIds[i], allMask[i], allTypeIds[i], fallbackSeqLen);
                      }
                    });
            // Tempdoc 710 Move 2: each per-doc GPU/CPU retry in the ladder above is its own
            // lease.run() call and records itself individually at the choke point — no outer
            // recordOrtCall here (that would double-count against the per-doc recordings).
            tExtract = System.nanoTime();
          }

          int dim = hidden[0][0].length;
          recordDetectedDimension(dim);

          List<float[]> vectors = new ArrayList<>(batchSize);
          for (int b = 0; b < batchSize; b++) {
            vectors.add(l2Normalize(pool(hidden[b], allMask[b], dim)));
          }
          profiler.addPhaseNs("extract", System.nanoTime() - tExtract);
          // callCount() is approximate — concurrent threads may skip or double-fire
          // at interval boundaries. Acceptable for periodic diagnostic logging.
          long calls = profiler.callCount();
          if (calls % PROFILE_LOG_INTERVAL == 0) {
            var snap = profiler.snapshot();
            if (snap != null) {
              log.info(
                  "Embed per-call profile ({}calls): {}, ort=[{}], batch={}, seqLen={}",
                  calls, snap.formatAvgPhases(calls), snap.formatOrtDist(), batchSize, maxLen);
            }
          }
          return vectors;
        }
      } finally {
        ortSpan.end();
      }
    }
  }

  /** Functional seam for the per-doc GPU/CPU fallback runner used by {@link
   * #runOomFallbackLadder}. Package-private for tests (tempdoc 710 Move 3). */
  @FunctionalInterface
  interface SingleDocRunner {
    float[][] run(int index) throws OrtException;
  }

  /**
   * Orchestrates the per-doc GPU-batch1-then-CPU fallback ladder for a sub-batch's BFCArena OOM.
   * Isolated from ORT tensor/session construction (tempdoc 710 Move 3) so the retry-order /
   * exception-routing logic is unit-testable with fake runners that throw synthetic {@link
   * OrtException}s — a real GPU OOM cannot be reproduced in a unit test.
   *
   * @param batchSize number of docs in the failed sub-batch
   * @param gpuSingleRunner runs doc index i as a batch=1 forward pass on the GPU session that
   *     just OOM'd at the larger batch size
   * @param cpuSingleRunner runs doc index i as a batch=1 forward pass on the CPU session;
   *     invoked only when {@code gpuSingleRunner} ALSO throws a BFC-arena failure for that doc
   * @return per-doc hidden states, ordered to match the caller's sub-batch order
   * @throws OrtException the first non-arena-OOM exception from either runner, unmodified
   */
  static float[][][] runOomFallbackLadder(
      int batchSize, SingleDocRunner gpuSingleRunner, SingleDocRunner cpuSingleRunner)
      throws OrtException {
    float[][][] hidden = new float[batchSize][][];
    for (int i = 0; i < batchSize; i++) {
      try {
        hidden[i] = gpuSingleRunner.run(i);
      } catch (OrtException single) {
        if (!NativeSessionHandle.isBfcArenaFailure(single)) {
          throw single;
        }
        log.warn(
            "Embed GPU arena allocation failed for single doc (index={}) even at batch=1,"
                + " falling back to CPU session: {}",
            i,
            single.getMessage());
        EncoderOrtRunSpans.emitCpuFallbackEvent("gpu_bfc_arena", "embed");
        hidden[i] = cpuSingleRunner.run(i);
      }
    }
    return hidden;
  }

  /**
   * Runs a batch=1 forward pass on the given lease and returns per-token hidden states.
   *
   * <p>Takes the {@link SessionHandle.Lease} itself (tempdoc 710 Move 2), not an unpacked
   * session/RunOptions pair, so the run goes through {@link SessionHandle.Lease#run} — the choke
   * point every ORT call must pass through — regardless of whether the caller passes the
   * already-acquired GPU-batch lease (OOM ladder's GPU-batch1 retry) or a fresh CPU lease (the
   * ladder's CPU fallback).
   */
  private float[][] runSingleHidden(
      SessionHandle.Lease lease, long[] ids, long[] mask, long[] typeIds, int seqLen)
      throws OrtException {
    long[] shape1 = {1, seqLen};
    OrtEnvironment env = sessions.environment();
    try (OnnxTensor idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape1);
        OnnxTensor maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape1);
        OnnxTensor typesTensor =
            needsTokenTypeIds
                ? OnnxTensor.createTensor(env, LongBuffer.wrap(typeIds), shape1)
                : null) {
      Map<String, OnnxTensor> singleInputs = new HashMap<>();
      singleInputs.put("input_ids", idsTensor);
      singleInputs.put("attention_mask", maskTensor);
      if (typesTensor != null) {
        singleInputs.put("token_type_ids", typesTensor);
      }
      try (OrtSession.Result result = lease.run(singleInputs)) {
        float[][][] out = (float[][][]) result.get(0).getValue();
        return out[0];
      }
    }
  }

  /**
   * Upper bound on total input CHARS per native {@code batchEncode} call in {@link
   * #embedBatchWithChunking}'s Phase 1. Mirrors {@code SpladeEncoder.TOKENIZE_GROUP_CHAR_BUDGET}
   * (tempdoc 686 crash fix; ported here by tempdoc 710 Move 3): the tokenizer runs with
   * truncation disabled (see {@link #buildAssembly}'s {@code "truncation": "false"}), so a single
   * native call over the caller's FULL text list can materialize arbitrarily large encodings
   * simultaneously — the same landmine SPLADE hit for full-document text with an unbounded
   * caller list. Grouping by input chars bounds peak per-call native materialization to one
   * group regardless of caller batch size, while preserving exact tokenization results (batching
   * granularity only — same tokenizer, same per-text output).
   */
  private static final long TOKENIZE_GROUP_CHAR_BUDGET = 512_000;

  /**
   * Batch-embeds texts with chunking support for long documents.
   *
   * <p>Short texts (≤ {@code maxSeqLen} tokens) are embedded directly. Long texts are split into
   * overlapping chunks (same windowing as {@link #embed}), and chunk vectors are mean-pooled per
   * document. Windows are streamed in document order through bounded ORT batches; callers of
   * this method retain the resulting chunk vectors explicitly.
   *
   * @param texts list of text strings to embed
   * @return one {@link EmbedResult} per input text, with chunk vectors for long texts
   * @throws OrtException if ONNX inference fails
   */
  public List<EmbedResult> embedBatchWithChunking(List<String> texts) throws OrtException {
    var acquisition = LocalSessionAcquisition.background();
    if (texts.isEmpty()) {
      return List.of();
    }
    if (texts.size() == 1) {
      return List.of(embed(texts.get(0), acquisition));
    }

    return encodeWindowBatches(texts, true, acquisition);
  }

  /** Parent-vector path: pool windows as they finish without retaining unused chunk vectors. */
  public List<EmbedResult> embedBatchPooled(List<String> texts) throws OrtException {
    return encodeWindowBatches(texts, false, LocalSessionAcquisition.background());
  }

  private List<EmbedResult> encodeWindowBatches(
      List<String> texts, boolean retainChunks, SessionAcquisitionRequest acquisition)
      throws OrtException {
    // Preserve the singleton path's one-window inference grouping and the batch path's
    // global groups of eight, including partial groups across tokenization boundaries.
    WindowBatch batch = new WindowBatch(retainChunks,
        texts.size() == 1
            ? 1
            : MAX_ORT_BATCH_SIZE,
        windows -> embedPreTokenizedBatch(windows, acquisition));
    int n = texts.size();
    int groupStart = 0;
    while (groupStart < n) {
      int groupEnd = groupStart;
      long groupChars = 0;
      while (groupEnd < n
          && (groupEnd == groupStart
              || groupChars + texts.get(groupEnd).length() <= TOKENIZE_GROUP_CHAR_BUDGET)) {
        groupChars += texts.get(groupEnd).length();
        groupEnd++;
      }
      long tTok = System.nanoTime();
      Encoding[] groupEncodings = texts.size() == 1
          ? new Encoding[] {tokenizer.encode(texts.get(0))}
          : tokenizer.batchEncode(texts.subList(groupStart, groupEnd));
      profiler.addPhaseNs("tokenize", System.nanoTime() - tTok);
      for (int j = 0; j < groupEncodings.length; j++) {
        Encoding enc = groupEncodings[j];
        long[] ids = enc.getIds();
        long[] mask = enc.getAttentionMask();
        long[] typeIds = enc.getTypeIds();

        List<long[][]> windows = ids.length <= maxSeqLen
            ? java.util.Collections.singletonList(new long[][] {ids, mask, typeIds})
            : createChunks(ids, mask, typeIds);
        batch.addDocument(windows);
        groupEncodings[j] = null;
      }
      groupStart = groupEnd;
    }
    return batch.finish();
  }

  @FunctionalInterface
  interface WindowRunner {
    List<float[]> run(List<long[][]> windows) throws OrtException;
  }

  /** Bounded materialization seam shared by full and pooled-only document encoding. */
  static final class WindowBatch {
    private final boolean retainChunks;
    private final int batchSize;
    private final WindowRunner runner;
    private final List<long[][]> pending = new ArrayList<>();
    private final List<WindowPool> owners = new ArrayList<>();
    private final List<WindowPool> documents = new ArrayList<>();

    WindowBatch(boolean retainChunks, int batchSize, WindowRunner runner) {
      if (batchSize < 1 || batchSize > MAX_ORT_BATCH_SIZE) {
        throw new IllegalArgumentException("Window batch size must be between one and eight");
      }
      this.retainChunks = retainChunks;
      this.batchSize = batchSize;
      this.runner = runner;
    }

    void addDocument(List<long[][]> windows) throws OrtException {
      WindowPool pool = new WindowPool(retainChunks);
      documents.add(pool);
      for (long[][] window : windows) {
        pending.add(window);
        owners.add(pool);
        if (pending.size() == batchSize) flush();
      }
    }

    private void flush() throws OrtException {
      if (pending.isEmpty()) return;
      List<float[]> vectors = runner.run(pending);
      if (vectors.size() != pending.size()) {
        throw new IllegalStateException("Window inference result count does not match input");
      }
      for (int i = 0; i < vectors.size(); i++) owners.get(i).add(vectors.get(i));
      pending.clear();
      owners.clear();
    }

    List<EmbedResult> finish() throws OrtException {
      flush();
      List<EmbedResult> results = new ArrayList<>(documents.size());
      for (WindowPool pool : documents) results.add(pool.finish());
      return results;
    }
  }

  private static final class WindowPool {
    private final List<float[]> retained;
    private double[] sum;
    private float[] first;
    private int count;

    WindowPool(boolean retainChunks) {
      retained = retainChunks ? new ArrayList<>() : null;
    }

    void add(float[] vector) {
      if (count == 0) {
        first = vector;
        sum = new double[vector.length];
      }
      for (int i = 0; i < Math.min(vector.length, sum.length); i++) sum[i] += vector[i];
      if (retained != null) retained.add(vector);
      count++;
    }

    EmbedResult finish() {
      if (count == 0) throw new IllegalStateException("Document has no embedding windows");
      float[] vector = first;
      if (count > 1) {
        vector = new float[sum.length];
        for (int i = 0; i < vector.length; i++) vector[i] = (float) (sum[i] / count);
        vector = l2Normalize(vector);
      }
      return new EmbedResult(vector,
          retained != null && count > 1 ? retained : List.of(), count);
    }
  }

  /**
   * How many sliding windows {@code text} needs (round-15 post-round finding). Tokenize-only — no
   * inference — so a caller can partition a batch into "one forward pass" and "needs resuming"
   * lanes before spending any GPU.
   *
   * <p>The {@code text.length() <= maxSeqLen} short-circuit is exact, not a heuristic: the
   * tokenizer never emits more tokens than the input has characters, so a text that short cannot
   * exceed the context window. It keeps the ordinary short-document batch path free of a second
   * tokenization.
   */
  public int windowCount(String text) {
    if (text == null || text.isEmpty() || text.length() <= maxSeqLen) {
      return 1;
    }
    Encoding encoding = tokenizer.encode(text);
    long[] ids = encoding.getIds();
    if (ids.length <= maxSeqLen) {
      return 1;
    }
    return TokenWindows.count(ids.length, chunkSize, chunkOverlap, maxSeqLen);
  }

  /**
   * Embeds windows {@code [fromWindow, fromWindow + maxWindows)} of {@code text} (round-15
   * post-round finding), returning the RAW per-window vectors — the caller pools them.
   *
   * <p>Windowing is the same {@link #createChunks} sliding window {@link #embed} and {@link
   * #embedBatchWithChunking} use, so resuming a document window-by-window produces the same window
   * set (and therefore the same pooled vector) a single whole-document call would have produced.
   *
   * @return one vector per embedded window, in window order; empty when {@code fromWindow} is past
   *     the end
   */
  public WindowSliceResult embedWindows(String text, int fromWindow, int maxWindows)
      throws OrtException {
    List<long[][]> windows = windowsOf(text);
    if (fromWindow >= windows.size() || maxWindows <= 0) {
      return new WindowSliceResult(List.of(), windows.size());
    }
    int end = Math.min(fromWindow + maxWindows, windows.size());
    return new WindowSliceResult(
        embedPreTokenizedBatch(
            windows.subList(fromWindow, end), LocalSessionAcquisition.background()),
        windows.size());
  }

  /**
   * One {@link #embedWindows} slice plus the document's total window count — returned together so a
   * resuming caller learns its bound from the same tokenization that produced the vectors, instead
   * of paying a second full tokenization per slice on a multi-hundred-KB document.
   */
  public record WindowSliceResult(List<float[]> vectors, int totalWindows) {}

  private List<long[][]> windowsOf(String text) {
    Encoding encoding = tokenizer.encode(text);
    long[] ids = encoding.getIds();
    long[] mask = encoding.getAttentionMask();
    long[] typeIds = encoding.getTypeIds();
    if (ids.length <= maxSeqLen) {
      List<long[][]> single = new ArrayList<>(1);
      single.add(new long[][] {ids, mask, typeIds});
      return single;
    }
    return createChunks(ids, mask, typeIds);
  }

  /** Returns the embedding dimension (detected from first inference, or 0 if not yet known). */
  public int embeddingDimension() {
    return embeddingDimension;
  }

  /**
   * Records the dimension observed from an ORT output tensor on (at most) the first call, and
   * cross-checks it against {@link #declaredEmbeddingDimension} (tempdoc 710 Wave 2 Move 1). A
   * mismatch is WARNed once — the reactive detection stays authoritative; the declared value is a
   * sanity check, not an override (mirrors {@link
   * io.justsearch.ort.ModelCapabilityResolver}'s precision sanity check).
   */
  private void recordDetectedDimension(int dim) {
    if (embeddingDimension == 0) {
      embeddingDimension = dim;
    }
    if (declaredEmbeddingDimension > 0
        && dim != declaredEmbeddingDimension
        && warnedDimensionMismatch.compareAndSet(false, true)) {
      log.warn(
          "Embedding dimension mismatch: declared capability={} but first inference observed={}"
              + " — reactive detection kept authoritative",
          declaredEmbeddingDimension,
          dim);
    }
  }

  // ---------------------------------------------------------------------------
  // Single-chunk embedding
  // ---------------------------------------------------------------------------

  /**
   * Embeds a single chunk of tokens and returns the L2-normalized mean-pooled vector.
   *
   * <p>The ONNX model outputs {@code last_hidden_state} with shape {@code [1, seqLen, dim]}. We
   * apply attention-mask-aware mean pooling and L2 normalization.
   */
  private float[] embedSingle(
      long[] ids,
      long[] mask,
      long[] typeIds,
      SessionAcquisitionRequest acquisition)
      throws OrtException {
    int seqLen = Math.min(ids.length, maxSeqLen);

    // Truncate if needed
    long[] truncIds = truncate(ids, seqLen);
    long[] truncMask = truncate(mask, seqLen);
    long[] truncTypeIds = truncate(typeIds, seqLen);

    float[][] hidden = runHidden(truncIds, truncMask, truncTypeIds, seqLen, acquisition);
    int dim = hidden[0].length;
    return l2Normalize(pool(hidden, truncMask, dim));
  }

  /**
   * Runs a single-document [1, seqLen] ORT forward pass and returns the per-token hidden states
   * ({@code last_hidden_state[0]}, shape {@code [seqLen, dim]}) without pooling.
   *
   * <p>Extracted from {@link #embedSingle} (tempdoc 691 §Phase G) so that late-chunking ({@link
   * #embedWithSpans}) and the plain single-vector path share the exact same ORT-run + tensor-build
   * logic — the whole-doc vector each derives is therefore bit-identical by construction. Callers
   * are responsible for truncating {@code ids}/{@code mask}/{@code typeIds} to {@code seqLen}
   * before calling.
   */
  private float[][] runHidden(
      long[] ids,
      long[] mask,
      long[] typeIds,
      int seqLen,
      SessionAcquisitionRequest acquisition)
      throws OrtException {
    long[] shape = {1, seqLen};

    OrtEnvironment env = sessions.environment();
    try (OnnxTensor inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape);
        OnnxTensor attentionMaskTensor =
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape);
        OnnxTensor tokenTypeIdsTensor =
            needsTokenTypeIds
                ? OnnxTensor.createTensor(env, LongBuffer.wrap(typeIds), shape)
                : null) {

      Map<String, OnnxTensor> inputs = new HashMap<>();
      inputs.put("input_ids", inputIdsTensor);
      inputs.put("attention_mask", attentionMaskTensor);
      if (tokenTypeIdsTensor != null) {
        inputs.put("token_type_ids", tokenTypeIdsTensor);
      }

      // Tempdoc 400 LR2-a/LR2-b: span starts before acquire; see batched path above.
      Span ortSpan = EncoderOrtRunSpans.maybeOrtRun(ORT_TRACER, "embed", 1, mask.length);
      try (Scope _ = ortSpan.makeCurrent()) {
        try (var lease = sessions.acquire(acquisition)) {
          ortSpan.setAttribute("encoder.gpu", !lease.isCpu());
          try (OrtSession.Result result = lease.run(inputs)) {
            // Tempdoc 710 Move 2: ORT-call timing is now recorded at the Lease choke point
            // (structural — was tempdoc 691 Wave 0's per-call-site fix for the B-5 blind spot;
            // the choke point makes the whole class of gap impossible rather than patched).
            // last_hidden_state: [1, seqLen, dim]
            float[][][] hidden = (float[][][]) result.get(0).getValue();
            int dim = hidden[0][0].length;
            recordDetectedDimension(dim);

            return hidden[0];
          }
        }
      } finally {
        ortSpan.end();
      }
    }
  }

  /**
   * Late chunking (tempdoc 691 §Phase G/H, arXiv:2409.04701): embeds {@code content} once and
   * derives both the whole-document vector and one vector per character span from the same
   * token-level forward pass, instead of running a separate ORT pass per chunk.
   *
   * <p>Returns {@code null} if {@code content} exceeds {@link #lateChunkingMaxSeqLen} tokens — the
   * raised single-pass ceiling for this path (tempdoc 691 Phase 2; independent of {@link
   * #maxSeqLen}, the base batch path's limit). Docs beyond it fall back to the existing per-chunk
   * {@link #embed} path. The length check runs before any per-token character-offset
   * materialization: {@code getCharTokenSpans()} on an unbounded {@link Encoding} previously
   * caused a native OOM crash for very large documents (tempdoc 686, {@code SpladeEncoder}), so
   * this primitive only touches it once the token count is confirmed small.
   *
   * @param content the full document text
   * @param charSpans {@code [startCharInclusive, endCharExclusive)} ranges into {@code content}
   *     (e.g. from {@code CHUNK_START_CHAR}/{@code CHUNK_END_CHAR}); one output vector per span.
   *     An empty array embeds the whole doc in one pass and returns zero chunk vectors (tempdoc
   *     691 Phase 2 VECTOR-only mode).
   * @return the doc vector plus one chunk vector per span, or {@code null} if {@code content}
   *     exceeds {@link #lateChunkingMaxSeqLen} tokens
   * @throws OrtException if ONNX inference fails
   */
  public EmbedResult embedWithSpans(String content, int[][] charSpans) throws OrtException {
    var acquisition = LocalSessionAcquisition.background();
    Encoding encoding = tokenizer.encode(content);
    long[] ids = encoding.getIds();

    if (ids.length > lateChunkingMaxSeqLen) {
      return null;
    }

    long[] mask = encoding.getAttentionMask();
    long[] typeIds = encoding.getTypeIds();
    CharSpan[] tokSpans = encoding.getCharTokenSpans();

    float[][] hidden = runHidden(ids, mask, typeIds, ids.length, acquisition);
    int dim = hidden[0].length;
    float[] docVector = l2Normalize(pool(hidden, mask, dim));

    List<float[]> chunkVectors = new ArrayList<>(charSpans.length);
    for (int[] span : charSpans) {
      float[] chunkVector = poolSpan(hidden, mask, tokSpans, span[0], span[1], dim);
      if (chunkVector == null) {
        // Defensive fallback: no token intersected this span. Embed the substring in
        // isolation so a chunk never surfaces a null/zero vector to the caller.
        Encoding subEncoding = tokenizer.encode(content.substring(span[0], span[1]));
        chunkVector =
            embedSingle(
                subEncoding.getIds(),
                subEncoding.getAttentionMask(),
                subEncoding.getTypeIds(),
                acquisition);
      }
      chunkVectors.add(chunkVector);
    }

    return new EmbedResult(docVector, chunkVectors, charSpans.length);
  }

  /**
   * Masked-mean pooling restricted to the tokens whose char span intersects {@code [startChar,
   * endChar)} — {@link #pool}'s formula, scoped to one chunk's token subset. Tokens with a null or
   * zero-width char span (CLS/SEP/PAD and other special tokens — the DJL/tokenizers-rust JNI
   * binding reports these as a {@code (0, 0)} sentinel rather than a real offset) are excluded, as
   * are masked-out (padding) tokens.
   *
   * @return the L2-normalized pooled vector, or {@code null} if no token intersects the span
   */
  // package-private for tests (tempdoc 691 Wave 0)
  float[] poolSpan(
      float[][] hidden, long[] mask, CharSpan[] tokSpans, int startChar, int endChar, int dim) {
    float[] pooled = new float[dim];
    float count = 0.0f;
    int len = Math.min(hidden.length, tokSpans.length);
    for (int t = 0; t < len; t++) {
      if (mask[t] != 1) {
        continue;
      }
      CharSpan tokSpan = tokSpans[t];
      if (tokSpan == null) {
        continue;
      }
      int tokStart = tokSpan.getStart();
      int tokEnd = tokSpan.getEnd();
      if (tokStart == tokEnd) {
        continue; // zero-width sentinel: token has no source-text offset
      }
      if (tokEnd <= startChar || tokStart >= endChar) {
        continue; // no intersection with [startChar, endChar)
      }
      count += 1.0f;
      for (int d = 0; d < dim; d++) {
        pooled[d] += hidden[t][d];
      }
    }
    if (count == 0.0f) {
      return null;
    }
    for (int d = 0; d < dim; d++) {
      pooled[d] /= count;
    }
    return l2Normalize(pooled);
  }

  // ---------------------------------------------------------------------------
  // Chunking (replicates EmbeddingActor.createChunks)
  // ---------------------------------------------------------------------------

  /**
   * Creates overlapping chunks from token arrays using a sliding window.
   *
   * @return list of chunks, each containing [ids, mask, typeIds] arrays
   */
  private List<long[][]> createChunks(long[] ids, long[] mask, long[] typeIds) {
    return new TokenWindows(ids, mask, typeIds, chunkSize, chunkOverlap, maxSeqLen);
  }

  // ---------------------------------------------------------------------------
  // Mean pooling across chunks (replicates EmbeddingActor.meanPool)
  // ---------------------------------------------------------------------------

  private float[] meanPoolChunks(List<float[]> vectors) {
    if (vectors.isEmpty()) {
      return new float[embeddingDimension > 0 ? embeddingDimension : 768];
    }
    if (vectors.size() == 1) {
      return vectors.get(0);
    }

    int dim = vectors.get(0).length;
    double[] sum = new double[dim];
    for (float[] vec : vectors) {
      for (int i = 0; i < Math.min(vec.length, dim); i++) {
        sum[i] += vec[i];
      }
    }

    int count = vectors.size();
    float[] result = new float[dim];
    for (int i = 0; i < dim; i++) {
      result[i] = (float) (sum[i] / count);
    }

    return l2Normalize(result);
  }

  // ---------------------------------------------------------------------------
  // GPU session lifecycle (delegated to SessionHandle)
  // ---------------------------------------------------------------------------

  /** Releases the GPU session to free VRAM (called when Main claims GPU). */
  public void releaseGpuSession() {
    sessions.releaseGpu();
  }

  /** Returns true if GPU is currently available for inference. */
  public boolean isGpuAvailable() {
    return sessions.isGpuAvailable();
  }

  /** Returns the ORT CUDA status for observability. */
  public OrtCudaStatus getOrtCudaStatus() {
    return sessions.status();
  }


  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Applies the configured pooling strategy to extract a single vector from token-level hidden
   * states.
   */
  // package-private for tests (tempdoc 691 Wave 0)
  float[] pool(float[][] tokenHiddenStates, long[] attentionMask, int dim) {
    if (poolingStrategy == PoolingStrategy.CLS) {
      // CLS pooling: take the first token's hidden state
      return tokenHiddenStates[0].clone();
    }
    // Mean pooling: attention-mask-weighted average of all token embeddings
    float[] pooled = new float[dim];
    float maskSum = 0.0f;
    for (int t = 0; t < tokenHiddenStates.length; t++) {
      if (attentionMask[t] == 1) {
        maskSum += 1.0f;
        for (int d = 0; d < dim; d++) {
          pooled[d] += tokenHiddenStates[t][d];
        }
      }
    }
    if (maskSum > 0.0f) {
      for (int d = 0; d < dim; d++) {
        pooled[d] /= maskSum;
      }
    }
    return pooled;
  }

  private static float[] l2Normalize(float[] vec) {
    double norm = 0.0;
    for (float v : vec) {
      norm += (double) v * v;
    }
    double magnitude = Math.sqrt(norm);
    if (magnitude == 0.0) {
      return vec;
    }
    float[] result = new float[vec.length];
    for (int i = 0; i < vec.length; i++) {
      result[i] = (float) (vec[i] / magnitude);
    }
    return result;
  }

  private static long[] truncate(long[] arr, int len) {
    if (arr.length == len) {
      return arr;
    }
    long[] result = new long[len];
    System.arraycopy(arr, 0, result, 0, len);
    return result;
  }

  private static long[] padRight(long[] arr, int targetLen) {
    if (arr.length == targetLen) {
      return arr;
    }
    long[] result = new long[targetLen];
    System.arraycopy(arr, 0, result, 0, arr.length);
    return result;
  }

  private static LongBuffer flatten(long[][] array, int rows, int cols) {
    long[] flat = new long[rows * cols];
    for (int i = 0; i < rows; i++) {
      System.arraycopy(array[i], 0, flat, i * cols, cols);
    }
    return LongBuffer.wrap(flat);
  }

  @Override
  public void close() {
    sessions.close();
    tokenizer.close();
    log.info("OnnxEmbeddingEncoder closed");
  }

}
