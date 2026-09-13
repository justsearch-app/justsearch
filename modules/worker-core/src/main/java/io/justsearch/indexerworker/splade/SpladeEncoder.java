/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.splade;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.modality.nlp.DefaultVocabulary;
import ai.djl.modality.nlp.Vocabulary;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.justsearch.indexerworker.metrics.EncoderOrtRunSpans;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.justsearch.configuration.resolved.ConfigStore;

import io.justsearch.ort.OrtCudaStatus;
import io.justsearch.ort.NativeSessionHandle;
import io.justsearch.ort.SessionHandle;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPLADE-v3 sparse encoder using ONNX Runtime for inference.
 *
 * <p>Encodes text into a sparse vector of BERT vocabulary tokens weighted by importance. Uses
 * BertForMaskedLM (exported as ONNX), with SPLADE activation: {@code max_over_seq(log1p(ReLU(
 * logits)))}, then filters special tokens.
 *
 * <p>Thread-safe: ORT sessions are shared across threads, tensors are per-request. GPU session is
 * lazily initialized (copying {@link io.justsearch.reranker.CrossEncoderReranker} pattern) to avoid
 * VRAM allocation until actually needed.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * try (SpladeEncoder encoder = new SpladeEncoder(config, () -> !signalBus.isMainGpuActive())) {
 *   Map<String, Float> sparse = encoder.encode("search query");
 *   // Use sparse weights with Lucene FeatureField
 * }
 * }</pre>
 */
public final class SpladeEncoder implements Closeable {

  private static final Logger log = LoggerFactory.getLogger(SpladeEncoder.class);

  private static final int EVIDENCE_WINDOW_OVERLAP_TOKENS = 64;

  private final AtomicBoolean closed = new AtomicBoolean(false);

  /** BERT special token IDs to filter from SPLADE output: [PAD], [UNK], [CLS], [SEP], [MASK]. */
  private static final Set<Integer> SKIP_TOKEN_IDS = Set.of(0, 100, 101, 102, 103);

  /** Output format inferred from the ONNX graph's output names. Package-private for SpladeShape. */
  enum OutputFormat {
    MLM_LOGITS,
    PRESPARSE
  }

  // Session management (delegated to SessionHandle — tempdoc 397 §14.9 PR 4).
  private final SessionHandle sessions;
  private final SpladeConfig config;
  private volatile boolean firstEncodeLogged;

  // --- Tokenizer + vocabulary ---
  private final HuggingFaceTokenizer tokenizer;
  private final Vocabulary vocabulary;
  private final int maxSeqLen;
  private final boolean needsTokenTypeIds;
  private final boolean doubleLogActivation;
  private final boolean reluOnlyActivation;
  private final OutputFormat outputFormat;
  private final Path truncationEvidencePath;
  private final SpladeTruncationEvidence truncationEvidence;

  // --- Pinned output state (accessed only from indexing-loop thread via encodeBatch) ---
  // Query-time encode(String) uses runOnnxInferenceSingle() which bypasses these fields.
  // Do NOT access these from any method reachable by a query-serving caller thread (before lane F
  // stage A item A9 those were the gRPC Netty threads; now they are the Head's request threads).
  private final String outputName;
  private OnnxTensor pinnedOutputTensor;
  private FloatBuffer pinnedOutputBuffer;
  private int pinnedBatchSize;
  private int pinnedSeqLen;
  // Tempdoc 397 §14.9 post-Phase-1 Tier-2 fix: volatile to prevent torn reads across
  // indexing-loop thread vs signal-bus / release-requester thread.
  private volatile boolean pinnedOutputsSupported = true;

  // --- Per-call profiling (356->357: shared accumulator, pull model) ---
  private final io.justsearch.indexerworker.metrics.EncoderProfileAccumulator profiler =
      new io.justsearch.indexerworker.metrics.EncoderProfileAccumulator(
          "tokenize", "ort", "postProcess");
  private static final int PROFILE_LOG_INTERVAL = 20;

  // --- Per-ORT-call span tracer (tempdoc 400 LR2-a) ---
  private static final Tracer ORT_TRACER = EncoderOrtRunSpans.encoderTracer("splade");

  /** Returns the tokenizer for sharing with other encoders (e.g., IDF query encoder). */
  public HuggingFaceTokenizer tokenizer() {
    return tokenizer;
  }

  /** Returns the vocabulary for sharing with other encoders (e.g., IDF query encoder). */
  public Vocabulary vocabulary() {
    return vocabulary;
  }

  /**
   * Tempdoc 397 §14.24 FD-SPLADE primary constructor. All construction inputs are pre-built by
   * the composition root (or by {@link #buildAssembly} for dev-mode fallback paths). Encoder
   * performs zero filesystem I/O.
   *
   * @param sessions pre-configured session handle
   * @param shape model-intrinsic facts (input/output-name detection + max sequence length)
   * @param tokenizer pre-loaded DJL HuggingFace tokenizer
   * @param vocabulary pre-loaded WordPiece vocabulary
   * @param truncationEvidencePath evidence sidecar path; null = disabled
   * @param config SPLADE configuration (for activation flags, model path for evidence sidecar
   *     fingerprinting)
   */
  public SpladeEncoder(
      SessionHandle sessions,
      SpladeShape shape,
      HuggingFaceTokenizer tokenizer,
      Vocabulary vocabulary,
      Path truncationEvidencePath,
      SpladeConfig config) {
    this.sessions = sessions;
    this.config = config;
    this.tokenizer = tokenizer;
    this.vocabulary = vocabulary;
    this.maxSeqLen = shape.maxSequenceLength();
    this.needsTokenTypeIds = shape.needsTokenTypeIds();
    this.outputFormat = shape.outputFormat();
    this.outputName = shape.outputName();
    this.doubleLogActivation = config.isDoubleLogActivation();
    this.reluOnlyActivation = config.isReluOnlyActivation();
    this.truncationEvidencePath = truncationEvidencePath;
    this.truncationEvidence =
        new SpladeTruncationEvidence(maxSeqLen, EVIDENCE_WINDOW_OVERLAP_TOKENS);

    // Register GPU release callback via typed W5 wiring (replaces legacy
    // setOnBeforeGpuRelease(Runnable); tempdoc 397 §14.5 W5).
    sessions.setLifecycleCallback(this::closePinnedOutput);

    log.info(
        "SpladeEncoder initialized: outputFormat={}, vocab={} tokens, maxSeqLen={}",
        outputFormat, vocabulary.size(), maxSeqLen);
    io.justsearch.indexerworker.metrics.OperationalMetrics.getInstance()
        .registerEncoder("splade", profiler);
    // Tempdoc 710 Move 2: bind the choke-point recorder so every session.run()/runPinned()
    // invocation through this SessionHandle's leases records itself, including CPU-retry
    // sub-batches — call sites can no longer forget.
    sessions.setOrtRunRecorder(profiler::recordOrtCall);
  }

  /**
   * Builds a complete {@link SpladeAssembly} from a session handle + SPLADE config. Tempdoc
   * 397 §14.24 FD-SPLADE. Shared helper called by
   * {@code InferenceCompositionRoot.composeSpladeAssembly} (variant-driven path) and by test
   * harnesses that need to construct a SPLADE encoder from raw inputs.
   *
   * <p>Performs every SPLADE boot-time I/O: input-name probe, output-name probe + format
   * inference, tokenizer load, vocabulary load, evidence-path resolution.
   *
   * @throws OrtException if session input/output-name probe fails
   * @throws UncheckedIOException if tokenizer load fails
   * @throws IllegalStateException if vocabulary load fails
   */
  public static SpladeAssembly buildAssembly(SessionHandle sessions, SpladeConfig config)
      throws OrtException {
    // Tempdoc 397 §14.24 FD-ProbeDeletion: probe input + output names via the assembler helper.
    // Tempdoc 374 sandbox round 4 issue H: resolve via ModelManifest so the probe
    // hits whichever variant Install AI actually placed on disk (FP32 model.onnx
    // vs FP16 model_fp16.onnx).
    Path probeModel =
        io.justsearch.ort.ModelManifest.loadOrDefault(config.modelPath())
            .resolveExistingModelFile(config.modelPath());
    io.justsearch.ort.OrtSessionAssembler.ProbedNames probed =
        io.justsearch.ort.OrtSessionAssembler.probeModelNames(
            sessions.environment(), probeModel);
    boolean needsTokenTypeIds = probed.inputs().contains("token_type_ids");
    Set<String> outputNames = probed.outputs();
    OutputFormat outputFormat =
        outputNames.contains("output_idx") && outputNames.contains("output_weights")
            ? OutputFormat.PRESPARSE
            : OutputFormat.MLM_LOGITS;
    String outputName =
        outputFormat == OutputFormat.MLM_LOGITS
            ? (outputNames.contains("logits") ? "logits" : outputNames.iterator().next())
            : null;
    SpladeShape shape =
        new SpladeShape(config.maxSequenceLength(), needsTokenTypeIds, outputFormat, outputName);

    Path tokenizerPath = config.modelPath().resolve("tokenizer.json");
    HuggingFaceTokenizer tokenizer;
    try {
      tokenizer =
          HuggingFaceTokenizer.newInstance(
              tokenizerPath, Map.of("truncation", "false", "padding", "false"));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load SPLADE tokenizer from " + tokenizerPath, e);
    }
    Path vocabPath = config.modelPath().resolve("vocab.txt");
    Vocabulary vocabulary;
    try {
      vocabulary =
          DefaultVocabulary.builder()
              .addFromTextFile(vocabPath)
              .optUnknownToken("[UNK]")
              .build();
    } catch (Exception e) {
      tokenizer.close();
      throw new IllegalStateException("Failed to load SPLADE vocabulary from " + vocabPath, e);
    }
    Path evidencePath =
        ConfigStore.globalOrNull() != null
            ? (ConfigStore.global().get().ai().splade().evidencePath() != null
                ? ConfigStore.global().get().ai().splade().evidencePath().toAbsolutePath()
                : null)
            : null;
    return new SpladeAssembly(sessions, shape, tokenizer, vocabulary, evidencePath);
  }

  /**
   * Encodes a single text into a SPLADE sparse vector.
   *
   * @param text input text to encode
   * @return sparse vector mapping BERT tokens to SPLADE weights (only non-zero entries)
   * @throws OrtException if ONNX inference fails
   */
  public Map<String, Float> encode(String text) throws OrtException {
    long tTok = System.nanoTime();
    Encoding encoding = tokenizer.encode(text);
    truncationEvidence.record(encoding.getIds().length);
    truncationEvidence.flushIfNeeded(truncationEvidencePath, config.modelPath());
    int seqLen = Math.min(encoding.getIds().length, maxSeqLen);
    long[] inputIds = truncate(encoding.getIds(), seqLen);
    long[] attentionMask = truncate(encoding.getAttentionMask(), seqLen);
    long[] tokenTypeIds = truncate(encoding.getTypeIds(), seqLen);
    profiler.addPhaseNs("tokenize", System.nanoTime() - tTok);

    return runOnnxInferenceSingle(inputIds, attentionMask, tokenTypeIds);
  }

  /**
   * Returns the full non-truncated tokenizer length for the provided text.
   *
   * <p>This uses the same tokenizer configuration as SPLADE encoding. Tokenizer-level truncation is
   * disabled at construction time, so the result reflects the true parent-document token count.
   */
  public long tokenCount(String text) {
    if (text == null || text.isBlank()) {
      return 0L;
    }
    Encoding encoding = tokenizer.encode(text);
    return encoding.getIds().length;
  }

  /**
   * Encodes a batch of texts into SPLADE sparse vectors.
   *
   * <p>Texts are tokenized, padded to the longest sequence in the batch, and run through ONNX
   * inference in a single forward pass. More efficient than individual {@link #encode} calls when
   * processing multiple texts.
   *
   * @param texts input texts to encode
   * @return list of sparse vectors, one per input text
   * @throws OrtException if ONNX inference fails
   */
  /**
   * Maximum ORT inference batch size for SPLADE. CPU: batch=4 optimal (EXP-5, 2.28x speedup;
   * batch=8 regresses). GPU: batch=8 — pinned output is allocated once at worst-case size
   * (maxBatch × maxSeqLen × vocabSize × 4 bytes ≈ 476MB at batch=8/seq=512) and reused for all
   * inference calls, preventing BFCArena fragmentation from repeated free/realloc cycles.
   */
  private static final int MAX_SPLADE_BATCH_SIZE_CPU = 4;

  // GPU batch cap. Previously 16 (tuned for the MLM_LOGITS pinned-output path).
  // Tempdoc 394 item 2 discovered the PRESPARSE path's `ps_rmax` ReduceMax node
  // needs ~batch × seqLen × vocab × intermediate-factor of contiguous arena
  // memory: batch=16 + seqLen=512 requested 6.94 GB in observed OOM events,
  // exceeding both 4 GB (default) and 6 GB arena caps due to fragmentation.
  // Dropping to 4 is the conservative, guaranteed-safe setting until one of
  // (a) arena_extend_strategy=kNextPowerOfTwo lands, (b) internal sub-batching
  // in runSparseOutputInference is added, or (c) pinned-output is extended
  // safely to the PRESPARSE path (currently blocked by tempdoc 386 constraint).
  private static final int MAX_SPLADE_BATCH_SIZE_GPU = 4;

  private int getMaxBatchSize() {
    // Check if GPU would be selected. isGpuConfigured() checks config, isGpuAvailable() checks
    // runtime state. If GPU is configured but not yet attempted, use the larger batch size —
    // the actual GPU session creation happens lazily in selectSession().
    if (sessions.status().configured()) {
      return MAX_SPLADE_BATCH_SIZE_GPU;
    }
    return MAX_SPLADE_BATCH_SIZE_CPU;
  }

  /**
   * Upper bound on total input CHARS per native batchEncode call in {@link
   * #encodeBatchTokenBudget}. With truncation disabled, materialized Encoding memory scales with
   * input length (~2 chars/token for OCR-grade text, ~100 bytes/token materialized incl. char
   * spans and token strings), so 512k chars ≈ ~256k tokens ≈ a few tens of MB peak — bounded
   * regardless of caller batch size. A single text longer than the budget forms its own group
   * (~200k chars max via the extraction char cap → ~10-25 MB, safe). Derivation: tempdoc 686
   * full-corpus crash forensics, 2026-07-10.
   */
  private static final long TOKENIZE_GROUP_CHAR_BUDGET = 512_000;

  public List<Map<String, Float>> encodeBatch(List<String> texts) throws OrtException {
    if (texts.size() <= 1) {
      return encodeBatchInternal(texts);
    }
    return encodeBatchTokenBudget(texts);
  }

  /**
   * Token-budget batching: tokenize all texts upfront, sort by token count, partition into
   * sub-batches where total tokens &le; budget, encode each sub-batch (minimal padding waste), then
   * scatter results back to original order.
   *
   * <p>Budget = {@code getMaxBatchSize() * maxSeqLen} tokens per sub-batch, ensuring the output
   * tensor never exceeds current worst-case size. Each sub-batch is also capped at {@code
   * getMaxBatchSize()} documents to bound pinned output tensor dimensions.
   */
  private List<Map<String, Float>> encodeBatchTokenBudget(List<String> texts) throws OrtException {
    int maxBatch = getMaxBatchSize();
    int tokenBudget = maxBatch * maxSeqLen;

    // Phase 1: Tokenize in memory-bounded groups (tempdoc 686 crash fix). The tokenizer runs
    // with truncation DISABLED (truncation-evidence contract), so one full-document text can
    // materialize an Encoding of ~100k+ tokens (ids + tokens + char spans ≈ tens of MB). The
    // previous single batchEncode(texts) call materialized ALL encodings simultaneously —
    // ~100 doc-level contents ≈ up to ~1 GB of short-lived Java allocations, which exhausted
    // the 1g worker heap and killed the JVM natively (JNI allocation failure inside DJL's
    // getTokenCharSpans surfaced as EXCEPTION_UNCAUGHT_CXX_EXCEPTION in tokenizers.dll; three
    // identical hs_err dumps, heap 99.8% full). Grouping by input chars bounds peak
    // materialization to one group; only the truncated (≤ maxSeqLen) arrays are retained.
    int n = texts.size();
    long[][] idsByText = new long[n][];
    long[][] maskByText = new long[n][];
    long[][] typesByText = new long[n][];
    int[] tokenCounts = new int[n];
    long tTok = System.nanoTime();
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
      Encoding[] groupEncodings = tokenizer.batchEncode(texts.subList(groupStart, groupEnd));
      for (int i = 0; i < groupEncodings.length; i++) {
        int idx = groupStart + i;
        Encoding enc = groupEncodings[i];
        truncationEvidence.record(enc.getIds().length);
        int seqLen = Math.min(enc.getIds().length, maxSeqLen);
        tokenCounts[idx] = seqLen;
        idsByText[idx] = truncate(enc.getIds(), seqLen);
        maskByText[idx] = truncate(enc.getAttentionMask(), seqLen);
        typesByText[idx] = truncate(enc.getTypeIds(), seqLen);
      }
      groupStart = groupEnd;
    }
    profiler.addPhaseNs("tokenize", System.nanoTime() - tTok);
    truncationEvidence.flushIfNeeded(truncationEvidencePath, config.modelPath());

    // Phase 2: Build index array sorted by effective token count (ascending)
    Integer[] sortedIndices = new Integer[n];
    for (int i = 0; i < n; i++) {
      sortedIndices[i] = i;
    }
    Arrays.sort(sortedIndices, Comparator.comparingInt(i -> tokenCounts[i]));

    // Phase 3: Partition into token-budget sub-batches and encode each
    List<Map<String, Float>> results = new ArrayList<>(Collections.nCopies(n, null));
    int pos = 0;
    while (pos < n) {
      int batchStart = pos;
      int batchTokens = 0;
      while (pos < n
          && (pos - batchStart) < maxBatch
          && batchTokens + tokenCounts[sortedIndices[pos]] <= tokenBudget) {
        batchTokens += tokenCounts[sortedIndices[pos]];
        pos++;
      }
      // Ensure at least one doc per sub-batch (single long doc may exceed budget alone)
      if (pos == batchStart) {
        pos++;
      }

      // Build pre-tokenized arrays for this sub-batch (already truncated in phase 1)
      int batchSize = pos - batchStart;
      long[][] batchIds = new long[batchSize][];
      long[][] batchMask = new long[batchSize][];
      long[][] batchTypes = new long[batchSize][];
      int maxLen = 0;
      for (int j = 0; j < batchSize; j++) {
        int origIdx = sortedIndices[batchStart + j];
        int seqLen = tokenCounts[origIdx];
        batchIds[j] = idsByText[origIdx];
        batchMask[j] = maskByText[origIdx];
        batchTypes[j] = typesByText[origIdx];
        maxLen = Math.max(maxLen, seqLen);
      }

      // Run ORT inference for this sub-batch
      List<Map<String, Float>> subResults =
          runOnnxInference(batchIds, batchMask, batchTypes, batchSize, maxLen);

      // Scatter results back to original indices
      for (int j = 0; j < batchSize; j++) {
        results.set(sortedIndices[batchStart + j], subResults.get(j));
      }
    }

    return results;
  }

  private List<Map<String, Float>> encodeBatchInternal(List<String> texts) throws OrtException {
    if (texts.isEmpty()) {
      return List.of();
    }

    int batchSize = texts.size();
    long[][] allInputIds = new long[batchSize][];
    long[][] allAttentionMask = new long[batchSize][];
    long[][] allTokenTypeIds = new long[batchSize][];
    int maxLen = 0;

    long tTok = System.nanoTime();
    for (int i = 0; i < batchSize; i++) {
      Encoding encoding = tokenizer.encode(texts.get(i));
      truncationEvidence.record(encoding.getIds().length);
      int seqLen = Math.min(encoding.getIds().length, maxSeqLen);
      allInputIds[i] = truncate(encoding.getIds(), seqLen);
      allAttentionMask[i] = truncate(encoding.getAttentionMask(), seqLen);
      allTokenTypeIds[i] = truncate(encoding.getTypeIds(), seqLen);
      maxLen = Math.max(maxLen, seqLen);
    }
    profiler.addPhaseNs("tokenize", System.nanoTime() - tTok);
    truncationEvidence.flushIfNeeded(truncationEvidencePath, config.modelPath());

    return runOnnxInference(allInputIds, allAttentionMask, allTokenTypeIds, batchSize, maxLen);
  }

  /** SeqLen buckets to limit pinned output reallocation to at most a few shapes. */
  private static final int[] SEQ_LEN_BUCKETS = {128, 256, 384, 512};

  /**
   * Rounds seqLen up to the next bucket boundary. Limits the number of distinct pinned output
   * tensor shapes to {@code SEQ_LEN_BUCKETS.length}, preventing BFCArena fragmentation from
   * hundreds of free/realloc cycles with varying sizes.
   */
  private static int bucketSeqLen(int seqLen) {
    for (int bucket : SEQ_LEN_BUCKETS) {
      if (seqLen <= bucket) return bucket;
    }
    return seqLen; // beyond largest bucket — use exact size
  }

  /**
   * Ensures the pinned output tensor matches the required shape. Only reallocates when the bucketed
   * shape changes — with 4 buckets this means at most 4 allocations per session lifetime instead of
   * hundreds, preventing BFCArena fragmentation. The tensor is backed by a direct FloatBuffer
   * (off-heap) to avoid heap pressure.
   */
  private void ensurePinnedOutput(int batch, int seqLen, int vocabSize) throws OrtException {
    int bucketedSeqLen = bucketSeqLen(seqLen);
    if (pinnedOutputTensor != null
        && pinnedBatchSize == batch
        && pinnedSeqLen == bucketedSeqLen) {
      pinnedOutputBuffer.clear();
      return;
    }
    closePinnedOutput();
    long elements = (long) batch * bucketedSeqLen * vocabSize;
    long bytes = elements * Float.BYTES;
    if (bytes > Integer.MAX_VALUE) {
      throw new OrtException(
          "Pinned output too large: "
              + batch
              + "x"
              + bucketedSeqLen
              + "x"
              + vocabSize
              + " = "
              + (bytes / 1024 / 1024)
              + " MB exceeds DirectByteBuffer limit");
    }
    pinnedOutputBuffer =
        ByteBuffer.allocateDirect((int) bytes).order(ByteOrder.nativeOrder()).asFloatBuffer();
    pinnedOutputTensor =
        OnnxTensor.createTensor(
            sessions.environment(), pinnedOutputBuffer, new long[] {batch, bucketedSeqLen, vocabSize});
    pinnedBatchSize = batch;
    pinnedSeqLen = bucketedSeqLen;
    log.info(
        "Allocated pinned output tensor: shape=[{}, {}, {}], size={}MB (bucketed from seqLen={})",
        batch,
        bucketedSeqLen,
        vocabSize,
        bytes / 1024 / 1024,
        seqLen);
  }

  /**
   * Runs ONNX inference for pre-prepared input arrays and returns post-processed sparse vectors.
   */
  private List<Map<String, Float>> runOnnxInference(
      long[][] allInputIds, long[][] allAttentionMask, long[][] allTokenTypeIds, int batch, int len)
      throws OrtException {
    try (var lease = sessions.acquire()) {
      if (!firstEncodeLogged) {
        firstEncodeLogged = true;
        log.info(
            "SPLADE first encode: using {} session (gpuConfigured={}, gpuAvailable={}, outputFormat={})",
            sessions.isGpuAvailable() ? "GPU" : "CPU",
            sessions.status().configured(),
            sessions.isGpuAvailable(),
            outputFormat);
      }

      if (outputFormat == OutputFormat.PRESPARSE) {
        return runSparseOutputInference(
            lease, allInputIds, allAttentionMask, allTokenTypeIds, batch);
      }

      // When using pinned outputs, pad inputs to the bucketed seqLen so model output shape matches
      // the pinned tensor shape. The attention mask is 0 for extra padding, so results are identical.
      int inferLen = pinnedOutputsSupported ? bucketSeqLen(len) : len;
      // Pad all to uniform length
      for (int i = 0; i < batch; i++) {
        allInputIds[i] = padRight(allInputIds[i], inferLen);
        allAttentionMask[i] = padRight(allAttentionMask[i], inferLen);
        allTokenTypeIds[i] = padRight(allTokenTypeIds[i], inferLen);
      }
      long[] shape = {batch, inferLen};
      OrtEnvironment env = sessions.environment();
      try (OnnxTensor inputIdsTensor =
              OnnxTensor.createTensor(env, flatten(allInputIds, batch, inferLen), shape);
          OnnxTensor attentionMaskTensor =
              OnnxTensor.createTensor(env, flatten(allAttentionMask, batch, inferLen), shape);
          OnnxTensor tokenTypeIdsTensor =
              needsTokenTypeIds
                  ? OnnxTensor.createTensor(env, flatten(allTokenTypeIds, batch, inferLen), shape)
                  : null) {
        Map<String, OnnxTensor> inputs = new HashMap<>();
        inputs.put("input_ids", inputIdsTensor);
        inputs.put("attention_mask", attentionMaskTensor);
        if (tokenTypeIdsTensor != null) {
          inputs.put("token_type_ids", tokenTypeIdsTensor);
        }
        int vocabSize = (int) vocabulary.size();
        long t0 = System.nanoTime();
        FloatBuffer buf;

        // Tempdoc 400 LR2-a: per-ORT-call span for SPLADE pinned/heap primary path.
        // Covers the primary run + any in-line GPU-OOM->CPU fallback as one logical
        // inference call (LR2-c will emit a cpu_fallback.triggered event on this
        // span if the fallback fires).
        Span ortSpan = EncoderOrtRunSpans.maybeOrtRun(
            ORT_TRACER, "splade", batch, inferLen);
        ortSpan.setAttribute("encoder.gpu", !lease.isCpu());
        try (Scope _ = ortSpan.makeCurrent()) {
        if (pinnedOutputsSupported) {
          try {
            // BertForMaskedLM output seqLen == input seqLen (padded to bucketed length)
            ensurePinnedOutput(batch, inferLen, vocabSize);
            Map<String, OnnxValue> pinnedOutputs = Map.of(outputName, pinnedOutputTensor);
            // Run into the pinned tensor (caller-managed; not closed here).
            lease.runPinned(inputs, pinnedOutputs);
            pinnedOutputBuffer.clear();
            buf = pinnedOutputBuffer;
          } catch (OrtException e) {
            // Tempdoc 397 §14.5 W3 + W4: lease.isCpu() replaces session-identity compare;
            // acquireCpu() replaces the bare peekCpuSession() reach for explicit CPU fallback.
            if (!lease.isCpu() && NativeSessionHandle.isBfcArenaFailure(e)) {
              log.info(
                  "SPLADE GPU arena allocation failed (batch={}, seqLen={}), using CPU fallback",
                  batch,
                  inferLen);
              // Tempdoc 400 LR2-c: emit cpu_fallback.triggered event on the
              // active encoder.ort_run span.
              EncoderOrtRunSpans.emitCpuFallbackEvent("gpu_bfc_arena", "splade");
              try (var cpuLease = sessions.acquireCpu()) {
                buf = runHeapFallback(cpuLease, inputs, batch, inferLen);
              }
            } else {
              log.warn(
                  "Pinned output inference failed, falling back to heap copy: {}", e.getMessage());
              pinnedOutputsSupported = false;
              buf = runHeapFallback(lease, inputs, batch, inferLen);
            }
          }
        } else {
          try {
            buf = runHeapFallback(lease, inputs, batch, inferLen);
          } catch (OrtException e) {
            if (!lease.isCpu() && NativeSessionHandle.isBfcArenaFailure(e)) {
              log.info(
                  "SPLADE GPU arena allocation failed (batch={}, seqLen={}), using CPU fallback",
                  batch,
                  inferLen);
              // Tempdoc 400 LR2-c: emit cpu_fallback.triggered event on the
              // active encoder.ort_run span.
              EncoderOrtRunSpans.emitCpuFallbackEvent("gpu_bfc_arena", "splade");
              try (var cpuLease = sessions.acquireCpu()) {
                buf = runHeapFallback(cpuLease, inputs, batch, inferLen);
              }
            } else {
              throw e;
            }
          }
        }
        } finally {
          ortSpan.end();
        }

      long t1 = System.nanoTime();
      // ortElapsed spans the primary attempt + any CPU-retry fallback (diagnostic log only,
      // below) — the choke point (tempdoc 710 Move 2) already recorded each actual ORT call
      // (lease.run/runPinned inside runHeapFallback / the pinned branch above) individually.
      long ortElapsed = t1 - t0;

      List<Map<String, Float>> processed =
          postProcessBuffer(buf, batch, inferLen, vocabSize, allAttentionMask);
      long t2 = System.nanoTime();
      profiler.addPhaseNs("postProcess", t2 - t1);
      // callCount() is approximate — concurrent threads may skip or double-fire
      // at interval boundaries. Acceptable for periodic diagnostic logging.
      long calls = profiler.callCount();
      if (calls % PROFILE_LOG_INTERVAL == 0) {
        var snap = profiler.snapshot();
        if (snap != null) {
          log.info(
              "SPLADE per-call profile ({}calls): {}, ort=[{}], seqLen={}",
              calls, snap.formatAvgPhases(calls), snap.formatOrtDist(), inferLen);
        }
      }
      log.debug(
          "SPLADE timing: batch={}, seqLen={} (bucketed={}), pinned={}, ort={}ms, postProcess={}ms, total={}ms",
          batch,
          len,
          inferLen,
          pinnedOutputsSupported,
          ortElapsed / 1_000_000,
          (t2 - t1) / 1_000_000,
          (t2 - t0) / 1_000_000);
        return processed;
      }
    }
  }

  /**
   * Runs single-text ONNX inference using heap-allocated output only. Thread-safe: no pinned output
   * state is touched. Used by {@link #encode(String)} for query-time SPLADE, which runs on whichever
   * caller thread entered the search port (the gRPC Netty threads, before item A9).
   *
   * <p>This method exists to avoid the data race on pinned output fields ({@code pinnedOutputTensor},
   * {@code pinnedOutputBuffer}, etc.) which are only safe for single-threaded access from the
   * indexing-loop. Query-time paths (batch=1) don't benefit from pinned output reuse, so the heap
   * path has no performance penalty.
   */
  private Map<String, Float> runOnnxInferenceSingle(
      long[] inputIds, long[] attentionMask, long[] tokenTypeIds) throws OrtException {
    try (var lease = sessions.acquire()) {
      if (!firstEncodeLogged) {
        firstEncodeLogged = true;
        log.info(
            "SPLADE first encode: using {} session (gpuConfigured={}, gpuAvailable={}, outputFormat={})",
            sessions.isGpuAvailable() ? "GPU" : "CPU",
            sessions.status().configured(),
            sessions.isGpuAvailable(),
            outputFormat);
      }

      // PRESPARSE format: delegate to existing single-doc sparse path
      if (outputFormat == OutputFormat.PRESPARSE) {
        return runSingleSparseInference(lease, inputIds, attentionMask, tokenTypeIds);
      }

      // MLM_LOGITS format: heap-only path (no pinned output)
      int seqLen = inputIds.length;
      long[] shape = {1, seqLen};
      OrtEnvironment env = sessions.environment();
      try (OnnxTensor inputIdsTensor =
              OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape);
          OnnxTensor attentionMaskTensor =
              OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape);
          OnnxTensor tokenTypeIdsTensor =
              needsTokenTypeIds
                  ? OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypeIds), shape)
                  : null) {
        Map<String, OnnxTensor> inputs = new HashMap<>();
        inputs.put("input_ids", inputIdsTensor);
        inputs.put("attention_mask", attentionMaskTensor);
        if (tokenTypeIdsTensor != null) {
          inputs.put("token_type_ids", tokenTypeIdsTensor);
        }
        FloatBuffer buf;
        try {
          buf = runHeapFallback(lease, inputs, 1, seqLen);
        } catch (OrtException e) {
          if (!lease.isCpu() && NativeSessionHandle.isBfcArenaFailure(e)) {
            log.info("SPLADE GPU arena allocation failed (single, seqLen={}), using CPU fallback",
                seqLen);
            // Tempdoc 400 LR2-c.
            EncoderOrtRunSpans.emitCpuFallbackEvent("gpu_bfc_arena", "splade");
            try (var cpuLease = sessions.acquireCpu()) {
              buf = runHeapFallback(cpuLease, inputs, 1, seqLen);
            }
          } else {
            throw e;
          }
        }
        // ORT-call timing recorded at the Lease choke point (tempdoc 710 Move 2).
        long t1 = System.nanoTime();

        int vocabSize = (int) vocabulary.size();
        long[][] singleMask = {attentionMask};
        List<Map<String, Float>> processed = postProcessBuffer(buf, 1, seqLen, vocabSize, singleMask);
        long t2 = System.nanoTime();
        profiler.addPhaseNs("postProcess", t2 - t1);
        long calls = profiler.callCount();
        if (calls % PROFILE_LOG_INTERVAL == 0) {
          var snap = profiler.snapshot();
          if (snap != null) {
            log.info(
                "SPLADE per-call profile ({}calls): {}, ort=[{}], seqLen={}",
                calls, snap.formatAvgPhases(calls), snap.formatOrtDist(), seqLen);
          }
        }
        return processed.get(0);
      }
    }
  }

  /**
   * Batched inference path for sparse-output ONNX exports (token ids and weights directly rather
   * than MLM logits). The model's {@code output_idx} and {@code output_weights} tensors have a
   * batch dimension ({@code [batch, topK]}), so all inputs are run in a single {@code
   * session.run()} and each output row is decoded independently (tempdoc 394 item 2).
   *
   * <p><b>Heap-only contract (tempdoc 386).</b> This path uses fresh {@link
   * OnnxTensor#createTensor} per call with {@link LongBuffer#wrap} backings and never touches the
   * pinned-output fields. The indexing-loop's exclusive ownership of those fields remains intact;
   * query-time threads calling {@link #encode(String)} use the analogous heap-only
   * {@code runOnnxInferenceSingle} path.
   *
   * <p>On a BFC-arena failure on GPU, the whole batch falls back to CPU via a recursive call —
   * matching the MLM_LOGITS path's batched-CPU-fallback strategy rather than the prior per-doc
   * fallback.
   */
  private List<Map<String, Float>> runSparseOutputInference(
      SessionHandle.Lease lease,
      long[][] allInputIds,
      long[][] allAttentionMask,
      long[][] allTokenTypeIds,
      int batch)
      throws OrtException {
    if (batch == 0) {
      return new ArrayList<>(0);
    }

    // Find max sequence length in batch for uniform padding. Inputs from
    // encodeBatchTokenBudget are already grouped by similar length, so padding
    // waste is bounded by the token-budget partition granularity.
    int maxLen = 0;
    for (int i = 0; i < batch; i++) {
      maxLen = Math.max(maxLen, allInputIds[i].length);
    }

    // Pad each row to maxLen. padRight zero-fills; attention-mask zeros keep
    // padding positions from contributing to the sparse output.
    long[][] paddedIds = new long[batch][];
    long[][] paddedMask = new long[batch][];
    long[][] paddedTypes = new long[batch][];
    for (int i = 0; i < batch; i++) {
      paddedIds[i] = padRight(allInputIds[i], maxLen);
      paddedMask[i] = padRight(allAttentionMask[i], maxLen);
      paddedTypes[i] = padRight(allTokenTypeIds[i], maxLen);
    }

    long[] shape = {batch, maxLen};
    OrtEnvironment env = sessions.environment();

    try (OnnxTensor inputIdsTensor =
            OnnxTensor.createTensor(env, flatten(paddedIds, batch, maxLen), shape);
        OnnxTensor attentionMaskTensor =
            OnnxTensor.createTensor(env, flatten(paddedMask, batch, maxLen), shape);
        OnnxTensor tokenTypeIdsTensor =
            needsTokenTypeIds
                ? OnnxTensor.createTensor(env, flatten(paddedTypes, batch, maxLen), shape)
                : null) {
      Map<String, OnnxTensor> inputs = new HashMap<>();
      inputs.put("input_ids", inputIdsTensor);
      inputs.put("attention_mask", attentionMaskTensor);
      if (tokenTypeIdsTensor != null) {
        inputs.put("token_type_ids", tokenTypeIdsTensor);
      }

      // Tempdoc 400 LR2-a: per-ORT-call span (sparse-output batched path).
      Span ortSpan = EncoderOrtRunSpans.maybeOrtRun(
          ORT_TRACER, "splade", batch, maxLen);
      ortSpan.setAttribute("encoder.gpu", !lease.isCpu());
      try (Scope _ = ortSpan.makeCurrent()) {
      try (OrtSession.Result result = lease.run(inputs)) {
        long tPost = System.nanoTime();
        // ORT-call timing recorded at the Lease choke point (tempdoc 710 Move 2).

        OnnxTensor indexTensor =
            (OnnxTensor)
                result
                    .get("output_idx")
                    .orElseThrow(
                        () -> new OrtException("Sparse-output SPLADE model missing output_idx"));
        OnnxTensor weightTensor =
            (OnnxTensor)
                result
                    .get("output_weights")
                    .orElseThrow(
                        () ->
                            new OrtException(
                                "Sparse-output SPLADE model missing output_weights"));

        // Output shape is [batch, topK]. copyLongBuffer / copyFloatBuffer return
        // row-major flat arrays of size batch * topK.
        long[] allTokenIds = copyLongBuffer(indexTensor.getLongBuffer());
        float[] allWeights = copyFloatBuffer(weightTensor.getFloatBuffer());

        if (allTokenIds.length != allWeights.length || allTokenIds.length % batch != 0) {
          throw new OrtException(
              "Sparse-output shape mismatch: tokens="
                  + allTokenIds.length
                  + " weights="
                  + allWeights.length
                  + " batch="
                  + batch);
        }
        int rowSize = allTokenIds.length / batch;

        List<Map<String, Float>> results = new ArrayList<>(batch);
        for (int i = 0; i < batch; i++) {
          long[] rowTokenIds = Arrays.copyOfRange(allTokenIds, i * rowSize, (i + 1) * rowSize);
          float[] rowWeights = Arrays.copyOfRange(allWeights, i * rowSize, (i + 1) * rowSize);
          results.add(decodeSparseOutput(rowTokenIds, rowWeights, vocabulary));
        }

        profiler.addPhaseNs("postProcess", System.nanoTime() - tPost);
        long calls = profiler.callCount();
        if (calls % PROFILE_LOG_INTERVAL == 0) {
          var snap = profiler.snapshot();
          if (snap != null) {
            log.info(
                "SPLADE per-call profile ({}calls): {}, ort=[{}], batch={}, seqLen={}",
                calls, snap.formatAvgPhases(calls), snap.formatOrtDist(), batch, maxLen);
          }
        }
        return results;
      }
      } finally {
        ortSpan.end();
      }
    } catch (OrtException e) {
      // GPU BFC arena failure → retry the whole batch on the CPU session.
      // Matches the MLM_LOGITS path's batched-CPU-fallback strategy at lines ~537-548.
      // Tempdoc 397 §14.9: the "was this GPU?" check uses lease.isCpu() (§14.5 W3); acquireCpu()
      // provides the fallback lease.
      boolean wasGpu = !lease.isCpu();
      if (wasGpu && NativeSessionHandle.isBfcArenaFailure(e)) {
        log.info(
            "SPLADE GPU arena allocation failed for batched sparse output (batch={}, seqLen={}),"
                + " falling back to CPU: {}",
            batch, maxLen, e.getMessage());
        // Tempdoc 400 LR2-c.
        EncoderOrtRunSpans.emitCpuFallbackEvent("gpu_bfc_arena", "splade");
        try (var cpuLease = sessions.acquireCpu()) {
          return runSparseOutputInference(
              cpuLease, allInputIds, allAttentionMask, allTokenTypeIds, batch);
        }
      }
      throw e;
    }
  }

  /** Runs a single sparse-output inference on the given lease. */
  private Map<String, Float> runSingleSparseInference(
      SessionHandle.Lease lease, long[] inputIds, long[] attentionMask, long[] tokenTypeIds)
      throws OrtException {
    int seqLen = inputIds.length;
    long[] shape = {1, seqLen};
    OrtEnvironment env = sessions.environment();
    try (OnnxTensor inputIdsTensor =
            OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape);
        OnnxTensor attentionMaskTensor =
            OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape);
        OnnxTensor tokenTypeIdsTensor =
            needsTokenTypeIds
                ? OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypeIds), shape)
                : null) {
      Map<String, OnnxTensor> inputs = new HashMap<>();
      inputs.put("input_ids", inputIdsTensor);
      inputs.put("attention_mask", attentionMaskTensor);
      if (tokenTypeIdsTensor != null) {
        inputs.put("token_type_ids", tokenTypeIdsTensor);
      }
      // Tempdoc 400 LR2-a: per-ORT-call span (single sparse-output path, batch=1).
      Span ortSpan = EncoderOrtRunSpans.maybeOrtRun(
          ORT_TRACER, "splade", 1, seqLen);
      ortSpan.setAttribute("encoder.gpu", !lease.isCpu());
      try (Scope _ = ortSpan.makeCurrent()) {
      try (OrtSession.Result result = lease.run(inputs)) {
        long tPost = System.nanoTime();
        // ORT-call timing recorded at the Lease choke point (tempdoc 710 Move 2).
        OnnxTensor indexTensor =
            (OnnxTensor)
                result
                    .get("output_idx")
                    .orElseThrow(
                        () -> new OrtException("Sparse-output SPLADE model missing output_idx"));
        OnnxTensor weightTensor =
            (OnnxTensor)
                result
                    .get("output_weights")
                    .orElseThrow(
                        () ->
                            new OrtException(
                                "Sparse-output SPLADE model missing output_weights"));
        long[] tokenIdsBuf = copyLongBuffer(indexTensor.getLongBuffer());
        float[] weights = copyFloatBuffer(weightTensor.getFloatBuffer());
        Map<String, Float> decoded = decodeSparseOutput(tokenIdsBuf, weights, vocabulary);
        profiler.addPhaseNs("postProcess", System.nanoTime() - tPost);
        // callCount() is approximate — concurrent threads may skip or double-fire
        // at interval boundaries. Acceptable for periodic diagnostic logging.
        long calls = profiler.callCount();
        if (calls % PROFILE_LOG_INTERVAL == 0) {
          var snap = profiler.snapshot();
          if (snap != null) {
            log.info(
                "SPLADE per-call profile ({}calls): {}, ort=[{}], seqLen={}",
                calls, snap.formatAvgPhases(calls), snap.formatOrtDist(), seqLen);
          }
        }
        return decoded;
      }
      } finally {
        ortSpan.end();
      }
    }
  }

  /** Fallback: run inference with heap-backed getFloatBuffer() (pre-pinned-outputs path). */
  @SuppressWarnings("PMD.UnusedFormalParameter") // batch/len kept for API consistency with pinned-output path
  private FloatBuffer runHeapFallback(
      SessionHandle.Lease lease, Map<String, OnnxTensor> inputs, int batch, int len)
      throws OrtException {
    try (OrtSession.Result result = lease.run(inputs)) {
      OnnxTensor outputTensor = (OnnxTensor) result.get(0);
      long[] outShape = outputTensor.getInfo().getShape();
      if (outShape.length != 3) {
        throw new OrtException(
            "Unexpected SPLADE output rank: " + outShape.length + ", expected 3");
      }
      long estimatedBytes = outputTensor.getInfo().getNumElements() * Float.BYTES;
      if (estimatedBytes > 2_000_000_000L) {
        throw new OrtException(
            "SPLADE output tensor too large: "
                + Arrays.toString(outShape)
                + " = "
                + (estimatedBytes / 1024 / 1024)
                + " MB");
      }
      return outputTensor.getFloatBuffer();
    }
  }

  /**
   * SPLADE post-processing: ReLU + log1p + max-pool over sequence positions.
   *
   * <p>For each vocabulary position v, computes: {@code max_t(log(1 + max(0, logits[t][v])))} where
   * t ranges over sequence positions with attention_mask[t]==1. Filters BERT special tokens.
   *
   * <p>When {@link SpladeConfig#isDoubleLogActivation()} is true, applies double-log activation:
   * {@code max_t(log(1 + log(1 + max(0, logits[t][v]))))} — used by OpenSearch doc-v3 models.
   *
   * @param logits shape [batch, seq, vocabSize] from BertForMaskedLM
   * @param attentionMask shape [batch, seq] (1 = real token, 0 = padding)
   * @return list of sparse vectors, one per batch item
   */
  @SuppressWarnings("unused") // Package-private for testing (SpladePostProcessTest)
  List<Map<String, Float>> postProcess(float[][][] logits, long[][] attentionMask) {
    int batchSize = logits.length;
    List<Map<String, Float>> results = new ArrayList<>(batchSize);

    for (int b = 0; b < batchSize; b++) {
      float[][] seqLogits = logits[b]; // [seq, vocabSize]
      long[] mask = attentionMask[b];
      int seqLen = seqLogits.length;
      int vocabSize = seqLogits[0].length;

      // Cache-friendly: iterate seq (outer) × vocab (inner) to match [seq][vocab] layout.
      // Accumulate max activated values per vocab position.
      float[] maxActivated = new float[vocabSize];

      for (int t = 0; t < seqLen; t++) {
        if (mask[t] == 0) {
          continue;
        }
        float[] row = seqLogits[t]; // contiguous vocab-sized array
        for (int v = 0; v < vocabSize; v++) {
          float val = row[v];
          if (val > 0.0f) {
            float activated =
                reluOnlyActivation
                    ? val
                    : doubleLogActivation
                        ? (float) Math.log1p(Math.log1p(val))
                        : (float) Math.log1p(val);
            if (activated > maxActivated[v]) {
              maxActivated[v] = activated;
            }
          }
        }
      }

      // Collect non-zero entries, skipping special tokens
      Map<String, Float> sparseVec = new LinkedHashMap<>();
      for (int v = 0; v < vocabSize; v++) {
        if (maxActivated[v] > 0.0f && !SKIP_TOKEN_IDS.contains(v)) {
          String token = vocabulary.getToken(v);
          if (token != null) {
            sparseVec.put(token, maxActivated[v]);
          }
        }
      }

      results.add(sparseVec);
    }

    return results;
  }

  static Map<String, Float> decodeSparseOutput(
      long[] tokenIds, float[] weights, Vocabulary vocabulary) {
    if (tokenIds.length != weights.length) {
      throw new IllegalArgumentException(
          "Sparse SPLADE output length mismatch: tokenIds="
              + tokenIds.length
              + ", weights="
              + weights.length);
    }
    Map<String, Float> sparseVec = new LinkedHashMap<>();
    for (int i = 0; i < tokenIds.length; i++) {
      int tokenId = (int) tokenIds[i];
      if (tokenId < 0 || tokenId >= vocabulary.size() || SKIP_TOKEN_IDS.contains(tokenId)) {
        continue;
      }
      float weight = weights[i];
      if (!(weight > 0.0f) || Float.isNaN(weight)) {
        continue;
      }
      String token = vocabulary.getToken(tokenId);
      if (token != null) {
        sparseVec.merge(token, weight, Math::max);
      }
    }
    return sparseVec;
  }

  /**
   * Zero-copy variant of {@link #postProcess} that reads directly from a flat {@link FloatBuffer}
   * backed by ORT native memory. Avoids materializing the full {@code [batch, seq, vocab]} tensor
   * on the Java heap — critical for GPU batches where the tensor can exceed 900 MB.
   *
   * @param buf flat float buffer in row-major order [batch × seq × vocabSize]
   * @param batchSize number of items in the batch
   * @param seqLen sequence length (including padding)
   * @param vocabSize vocabulary size (typically 30522 for BERT)
   * @param attentionMask shape [batch, seq] (1 = real token, 0 = padding)
   * @return list of sparse vectors, one per batch item
   */
  // Package-private for testing
  List<Map<String, Float>> postProcessBuffer(
      FloatBuffer buf, int batchSize, int seqLen, int vocabSize, long[][] attentionMask) {
    List<Map<String, Float>> results = new ArrayList<>(batchSize);
    // Heap row buffer for bulk reads — avoids per-element JNI calls on the DirectByteBuffer.
    // FloatBuffer.get(float[]) is a single native memcpy vs 30K individual get() calls.
    float[] row = new float[vocabSize];

    for (int b = 0; b < batchSize; b++) {
      long[] mask = attentionMask[b];
      float[] maxActivated = new float[vocabSize];

      for (int t = 0; t < seqLen; t++) {
        if (mask[t] == 0) {
          // Skip padding — advance buffer position past this row
          buf.position(buf.position() + vocabSize);
          continue;
        }
        // Bulk read entire vocab row into heap array (single native memcpy)
        buf.get(row);
        // Process on heap — JIT can auto-vectorize this loop
        for (int v = 0; v < vocabSize; v++) {
          float val = row[v];
          if (val > 0.0f) {
            float activated =
                reluOnlyActivation
                    ? val
                    : doubleLogActivation
                        ? (float) Math.log1p(Math.log1p(val))
                        : (float) Math.log1p(val);
            if (activated > maxActivated[v]) {
              maxActivated[v] = activated;
            }
          }
        }
      }

      // Collect non-zero entries, skipping special tokens
      Map<String, Float> sparseVec = new LinkedHashMap<>();
      for (int v = 0; v < vocabSize; v++) {
        if (maxActivated[v] > 0.0f && !SKIP_TOKEN_IDS.contains(v)) {
          String token = vocabulary.getToken(v);
          if (token != null) {
            sparseVec.put(token, maxActivated[v]);
          }
        }
      }

      results.add(sparseVec);
    }

    return results;
  }

  /**
   * Prunes the lowest-weight fraction of SPLADE query terms (beta pruning). Reduces query latency
   * by reducing the number of FeatureField SHOULD clauses with negligible quality impact (BMP
   * paper, SIGIR 2024).
   *
   * <p>This should only be applied at query time, not at index time. Index-time sparse vectors
   * should retain all terms for maximum recall.
   *
   * @param sparseVec the sparse vector from {@link #encode}
   * @param beta fraction of terms to keep (0.5 = keep top 50%)
   * @return new map with only the top-beta fraction of terms by weight
   */
  public static Map<String, Float> pruneByBeta(Map<String, Float> sparseVec, float beta) {
    if (sparseVec.size() <= 1 || beta >= 1.0f) {
      return sparseVec;
    }
    int keepCount = Math.max(1, Math.round(sparseVec.size() * beta));
    return sparseVec.entrySet().stream()
        .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
        .limit(keepCount)
        .collect(
            Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
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

  private static long[] copyLongBuffer(LongBuffer buffer) {
    LongBuffer copy = buffer.duplicate();
    long[] values = new long[copy.remaining()];
    copy.get(values);
    return values;
  }

  private static float[] copyFloatBuffer(FloatBuffer buffer) {
    FloatBuffer copy = buffer.duplicate();
    float[] values = new float[copy.remaining()];
    copy.get(values);
    return values;
  }

  /** Returns the ORT CUDA status for observability. */
  public OrtCudaStatus getOrtCudaStatus() {
    return sessions.status();
  }

  /** Returns the resolved SPLADE model directory path for diagnostics. */
  public String resolvedModelPath() {
    return config.modelPath().toString();
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
    // Remaining elements are 0 (pad token / no attention)
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
    if (!closed.compareAndSet(false, true)) {
      return; // Already closed — prevent double-close of ORT sessions.
    }
    try {
      truncationEvidence.write(truncationEvidencePath, config.modelPath());
    } catch (UncheckedIOException e) {
      log.warn("Failed to write SPLADE truncation evidence: {}", e.getMessage());
    }
    closePinnedOutput();
    sessions.close();
    tokenizer.close();
    log.info("SpladeEncoder closed");
  }

  private void closePinnedOutput() {
    if (pinnedOutputTensor != null) {
      try {
        pinnedOutputTensor.close();
      } catch (Throwable t) {
        log.warn("Failed to close pinned output tensor", t);
      }
      pinnedOutputTensor = null;
      pinnedOutputBuffer = null;
      pinnedBatchSize = 0;
      pinnedSeqLen = 0;
    }
  }

}
