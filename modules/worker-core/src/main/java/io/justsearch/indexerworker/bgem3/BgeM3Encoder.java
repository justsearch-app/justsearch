/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.bgem3;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.justsearch.indexerworker.inference.LocalSessionAcquisition;
import io.justsearch.ort.OrtCudaStatus;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.ort.SessionHandle;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.LongBuffer;
import java.nio.file.Files;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * BGE-M3 encoder producing both dense (1024-dim) and sparse (per-token) embeddings in a single
 * forward pass. The FP16 fused ONNX model uses MultiHeadAttention ops that dispatch to Flash
 * Attention v2 on CUDA, enabling 8192-token inputs with O(n) memory.
 *
 * <p>Delegates GPU/CPU session lifecycle to the {@link SessionHandle}. Single-tenant GPU policy via
 * the signal bus callback.
 *
 * <p>Sparse output format is {@code Map<String, Float>} — identical to SPLADE's output — so
 * existing FieldMapper/FeatureField infrastructure works without modification.
 */
public class BgeM3Encoder implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(BgeM3Encoder.class);

  /** XLM-RoBERTa special token IDs to skip in sparse output: 0=&lt;s&gt;, 1=&lt;pad&gt;, 2=&lt;/s&gt;, 3=&lt;unk&gt;. */
  private static final Set<Long> SKIP_TOKEN_IDS = Set.of(0L, 1L, 2L, 3L);

  /** Max batch sizes (larger seqs than SPLADE's 512). */
  private static final int MAX_BATCH_SIZE_GPU = 4;
  private static final int MAX_BATCH_SIZE_CPU = 2;

  // ---- Truncation evidence (observability) ----
  private final java.util.concurrent.atomic.LongAdder docsEncoded =
      new java.util.concurrent.atomic.LongAdder();
  private final java.util.concurrent.atomic.LongAdder docsTruncated =
      new java.util.concurrent.atomic.LongAdder();
  private final java.util.concurrent.atomic.AtomicInteger maxObservedTokens =
      new java.util.concurrent.atomic.AtomicInteger();

  // ---- Per-call profiling (tempdoc 400 LR2-a prerequisite) ----
  // Matches the tokenize/tensor/ort/extract phase pattern used by
  // OnnxEmbeddingEncoder + BertNerInference; SpladeEncoder uses the slightly
  // different tokenize/ort/postProcess set because its post-processing is
  // heavier. Registered in OperationalMetrics so /api/status surfaces
  // per-encoder distributions consistent with the other three encoders.
  private final io.justsearch.indexerworker.metrics.EncoderProfileAccumulator profiler =
      new io.justsearch.indexerworker.metrics.EncoderProfileAccumulator(
          "tokenize", "tensor", "ort", "extract");

  // --- Per-ORT-call span tracer (tempdoc 400 LR2-a) ---
  private static final io.opentelemetry.api.trace.Tracer ORT_TRACER =
      io.justsearch.indexerworker.metrics.EncoderOrtRunSpans.encoderTracer("bgem3");

  // ---- Immutable state ----
  // Session management (delegated to SessionHandle — tempdoc 397 §14.8 PR 3).
  private final SessionHandle sessions;
  private final OrtEnvironment env;
  private final BgeM3Config config;
  private final int maxSeqLen;
  private final HuggingFaceTokenizer tokenizer;
  private final String[] vocabulary; // token ID → token string (250K)

  /**
   * Tempdoc 397 §14.24 FD-BgeM3 primary constructor. All construction inputs are pre-built by
   * the composition root (or by {@link #buildAssembly} for dev-mode fallback paths). Encoder
   * performs zero filesystem I/O.
   *
   * @param sessions pre-configured session handle
   * @param shape model-intrinsic facts (max sequence length + vocabulary)
   * @param tokenizer pre-loaded DJL HuggingFace tokenizer
   * @param config BGE-M3 configuration (for model directory, dimension caps)
   */
  public BgeM3Encoder(
      SessionHandle sessions, BgeM3Shape shape, HuggingFaceTokenizer tokenizer, BgeM3Config config) {
    this.sessions = sessions;
    this.env = OrtEnvironment.getEnvironment();
    this.config = config;
    this.maxSeqLen = shape.maxSequenceLength();
    this.tokenizer = tokenizer;
    this.vocabulary = shape.vocabulary();

    log.info(
        "BgeM3Encoder initialized: vocab={} tokens, maxSeqLen={}, gpuConfigured={}",
        vocabulary.length,
        maxSeqLen,
        sessions.status().configured());
    io.justsearch.indexerworker.metrics.OperationalMetrics.getInstance()
        .registerEncoder("bgem3", profiler);
    // Tempdoc 710 Move 2: bind the choke-point recorder so every session.run() invocation
    // through this SessionHandle's leases records itself.
    sessions.setOrtRunRecorder(profiler::recordOrtCall);
  }

  /**
   * Builds a complete {@link BgeM3Assembly} from a session handle + BGE-M3 config. Tempdoc 397
   * §14.24 FD-BgeM3. Shared helper called by
   * {@code InferenceCompositionRoot.composeBgeM3Assembly} and by test harnesses. Parses
   * {@code tokenizer.json} twice — once via DJL's tokenizer constructor, once via the local
   * vocabulary extractor (that's how it was before FD — DJL doesn't expose the ID→string
   * map directly).
   *
   * @throws UncheckedIOException if tokenizer or vocabulary load fails
   */
  public static BgeM3Assembly buildAssembly(SessionHandle sessions, BgeM3Config config) {
    Path tokenizerPath = config.modelPath().resolve("tokenizer.json");
    HuggingFaceTokenizer tokenizer;
    try {
      tokenizer =
          HuggingFaceTokenizer.newInstance(
              tokenizerPath, Map.of("truncation", "false", "padding", "false"));
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Failed to load BGE-M3 tokenizer from " + tokenizerPath, e);
    }
    String[] vocabulary;
    try {
      vocabulary = loadVocabularyFromTokenizerJson(tokenizerPath);
    } catch (IOException e) {
      tokenizer.close();
      throw new UncheckedIOException(
          "Failed to load BGE-M3 vocabulary from " + tokenizerPath, e);
    }
    BgeM3Shape shape = new BgeM3Shape(config.maxSequenceLength(), vocabulary);
    return new BgeM3Assembly(sessions, shape, tokenizer);
  }

  // ==================== Public API ====================

  /**
   * Encodes a single text, producing both dense and sparse embeddings.
   *
   * @param text input text
   * @return dense vector (1024-dim) + sparse weights (token→weight)
   * @throws OrtException if ONNX inference fails
   */
  public BgeM3Output encode(String text) throws OrtException {
    return encodeBatchInternal(List.of(text), LocalSessionAcquisition.foreground()).get(0);
  }

  /**
   * Encodes a batch of texts. Uses token-budget partitioning for large batches.
   *
   * @param texts list of input texts
   * @return list of BgeM3Output, one per input text
   * @throws OrtException if ONNX inference fails
   */
  public List<BgeM3Output> encodeBatch(List<String> texts) throws OrtException {
    var acquisition = LocalSessionAcquisition.background();
    if (texts.size() <= 1) {
      return encodeBatchInternal(texts, acquisition);
    }
    return encodeBatchTokenBudget(texts, acquisition);
  }

  /** Returns the number of tokens the tokenizer produces for the given text (pre-truncation). */
  public long tokenCount(String text) {
    return tokenizer.encode(text).getIds().length;
  }

  /** Returns the underlying tokenizer (for sharing with query encoders). */
  public HuggingFaceTokenizer tokenizer() {
    return tokenizer;
  }

  public boolean isGpuAvailable() {
    return sessions.isGpuAvailable();
  }

  public OrtCudaStatus getOrtCudaStatus() {
    return sessions.status();
  }

  public String resolvedModelPath() {
    return config.modelPath().toString();
  }

  // ==================== Encoding internals ====================

  private List<BgeM3Output> encodeBatchInternal(
      List<String> texts, SessionAcquisitionRequest acquisition) throws OrtException {
    if (texts.isEmpty()) {
      return List.of();
    }

    int batchSize = texts.size();
    long[][] allInputIds = new long[batchSize][];
    long[][] allAttentionMask = new long[batchSize][];
    int maxLen = 0;

    long tTok = System.nanoTime();
    for (int i = 0; i < batchSize; i++) {
      Encoding encoding = tokenizer.encode(texts.get(i));
      int rawTokenCount = encoding.getIds().length;
      docsEncoded.increment();
      if (rawTokenCount > maxSeqLen) {
        docsTruncated.increment();
      }
      maxObservedTokens.getAndUpdate(cur -> Math.max(cur, rawTokenCount));
      int seqLen = Math.min(rawTokenCount, maxSeqLen);
      allInputIds[i] = truncate(encoding.getIds(), seqLen);
      allAttentionMask[i] = truncate(encoding.getAttentionMask(), seqLen);
      maxLen = Math.max(maxLen, seqLen);
    }
    profiler.addPhaseNs("tokenize", System.nanoTime() - tTok);

    return runOnnxInference(allInputIds, allAttentionMask, batchSize, maxLen, acquisition);
  }

  private List<BgeM3Output> encodeBatchTokenBudget(
      List<String> texts, SessionAcquisitionRequest acquisition) throws OrtException {
    int maxBatch = sessions.isGpuAvailable() ? MAX_BATCH_SIZE_GPU : MAX_BATCH_SIZE_CPU;
    int tokenBudget = maxBatch * maxSeqLen;

    // Tokenize all texts
    int n = texts.size();
    Encoding[] encodings = new Encoding[n];
    int[] tokenCounts = new int[n];
    Integer[] sortedIndices = new Integer[n];
    long tTok = System.nanoTime();
    for (int i = 0; i < n; i++) {
      encodings[i] = tokenizer.encode(texts.get(i));
      tokenCounts[i] = Math.min(encodings[i].getIds().length, maxSeqLen);
      sortedIndices[i] = i;
    }
    profiler.addPhaseNs("tokenize", System.nanoTime() - tTok);
    Arrays.sort(sortedIndices, Comparator.comparingInt(i -> tokenCounts[i]));

    // Partition into sub-batches and encode
    List<BgeM3Output> results = new ArrayList<>(Collections.nCopies(n, null));
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
      if (pos == batchStart) {
        pos++;
      }

      int batchSize = pos - batchStart;
      long[][] batchIds = new long[batchSize][];
      long[][] batchMask = new long[batchSize][];
      int maxLen = 0;
      for (int j = 0; j < batchSize; j++) {
        int origIdx = sortedIndices[batchStart + j];
        int seqLen = tokenCounts[origIdx];
        batchIds[j] = truncate(encodings[origIdx].getIds(), seqLen);
        batchMask[j] = truncate(encodings[origIdx].getAttentionMask(), seqLen);
        maxLen = Math.max(maxLen, seqLen);
      }

      List<BgeM3Output> subResults =
          runOnnxInference(batchIds, batchMask, batchSize, maxLen, acquisition);
      for (int j = 0; j < batchSize; j++) {
        results.set(sortedIndices[batchStart + j], subResults.get(j));
      }
    }
    return results;
  }

  private List<BgeM3Output> runOnnxInference(
      long[][] allInputIds,
      long[][] allAttentionMask,
      int batch,
      int maxLen,
      SessionAcquisitionRequest acquisition)
      throws OrtException {
    // Save pre-padded copies for sparse post-processing (avoids dependence on padding value)
    long[][] origInputIds = new long[batch][];
    long[][] origAttentionMask = new long[batch][];
    for (int i = 0; i < batch; i++) {
      origInputIds[i] = allInputIds[i].clone();
      origAttentionMask[i] = allAttentionMask[i].clone();
    }

    // Pad to uniform length for ONNX (0-padded)
    for (int i = 0; i < batch; i++) {
      allInputIds[i] = padRight(allInputIds[i], maxLen);
      allAttentionMask[i] = padRight(allAttentionMask[i], maxLen);
    }

    long[] shape = {batch, maxLen};
    long tTensor = System.nanoTime();
    try (OnnxTensor inputIdsTensor =
            OnnxTensor.createTensor(env, flatten(allInputIds, batch, maxLen), shape);
        OnnxTensor attentionMaskTensor =
            OnnxTensor.createTensor(env, flatten(allAttentionMask, batch, maxLen), shape)) {

      Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
      inputs.put("input_ids", inputIdsTensor);
      inputs.put("attention_mask", attentionMaskTensor);
      profiler.addPhaseNs("tensor", System.nanoTime() - tTensor);

      // Tempdoc 397 §14.5.3 OQ resolution = Path A: align to lease pattern. The legacy
      // selectSession() + runOptionsFor() bypass is gone; the lease provides both the session
      // and the per-session RunOptions in one call, plus the GPU semaphore for serialised
      // inference (matches all four other encoders).
      // Tempdoc 400 LR2-a/LR2-b: span starts before acquire so lease.acquire
      // child-span parents correctly.
      io.opentelemetry.api.trace.Span ortSpan =
          io.justsearch.indexerworker.metrics.EncoderOrtRunSpans.maybeOrtRun(
              ORT_TRACER, "bgem3", batch, maxLen);
      try (io.opentelemetry.context.Scope _ = ortSpan.makeCurrent()) {
        try (var lease = sessions.acquire(acquisition)) {
          ortSpan.setAttribute("encoder.gpu", !lease.isCpu());
          try (OrtSession.Result result = lease.run(inputs)) {
            // ORT-call timing recorded at the Lease choke point (tempdoc 710 Move 2).

            long tExtract = System.nanoTime();
            // Extract dense_vecs: (batch, 1024)
            float[][] denseVecs = (float[][]) result.get("dense_vecs").get().getValue();

            // Extract sparse_vecs: (batch, seq, 1)
            float[][][] sparseVecs = (float[][][]) result.get("sparse_vecs").get().getValue();

            // Post-process using pre-padded arrays (only real tokens, not padding)
            List<BgeM3Output> outputs = new ArrayList<>(batch);
            for (int b = 0; b < batch; b++) {
              float[] dense = denseVecs[b];
              Map<String, Float> sparse =
                  postProcessSparse(origInputIds[b], origAttentionMask[b], sparseVecs[b]);
              outputs.add(new BgeM3Output(dense, sparse));
            }
            profiler.addPhaseNs("extract", System.nanoTime() - tExtract);
            return outputs;
          }
        }
      } finally {
        ortSpan.end();
      }
    }
  }

  // ==================== Sparse post-processing ====================

  /**
   * Converts per-token sparse weights to a vocabulary-keyed sparse map.
   *
   * <p>For each token position: if attention_mask is 1, the token is not special, and the weight is
   * positive, map the token ID to its vocabulary string and keep the maximum weight per unique
   * token (handles repeated subwords).
   */
  static Map<String, Float> postProcessSparse(
      long[] inputIds, long[] attentionMask, float[][] sparseWeights, String[] vocab) {
    Map<String, Float> result = new HashMap<>();
    for (int i = 0; i < inputIds.length; i++) {
      if (attentionMask[i] == 0) continue;
      long tokenId = inputIds[i];
      if (SKIP_TOKEN_IDS.contains(tokenId)) continue;
      float weight = sparseWeights[i][0];
      if (weight <= 0.0f) continue;
      if (tokenId < 0 || tokenId >= vocab.length) continue;
      String token = vocab[(int) tokenId];
      result.merge(token, Math.min(weight, 64.0f), Math::max);
    }
    return result;
  }

  private Map<String, Float> postProcessSparse(
      long[] inputIds, long[] attentionMask, float[][] sparseWeights) {
    return postProcessSparse(inputIds, attentionMask, sparseWeights, vocabulary);
  }

  /** Releases the GPU session to free VRAM (called when Main claims GPU). */
  public void releaseGpuSession() {
    sessions.releaseGpu();
  }

  @Override
  public void close() {
    sessions.close();
    tokenizer.close();
  }

  // ==================== Utility methods ====================

  /**
   * Loads vocabulary (ID→string) from HuggingFace tokenizer.json.
   *
   * <p>XLM-RoBERTa uses a Unigram (SentencePiece) tokenizer where {@code model.vocab} is a list of
   * {@code [token_string, score]} pairs. The token ID is the list index.
   *
   * <p>Also handles BPE tokenizers where {@code model.vocab} is a {@code Map<String, Integer>}.
   */
  static String[] loadVocabularyFromTokenizerJson(Path tokenizerJsonPath) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode root = mapper.readTree(Files.readString(tokenizerJsonPath));
    JsonNode model = root.get("model");
    if (model == null) {
      throw new IOException("tokenizer.json missing 'model' field");
    }
    JsonNode vocabNode = model.get("vocab");
    if (vocabNode == null) {
      throw new IOException("tokenizer.json missing 'model.vocab' field");
    }

    if (vocabNode.isArray()) {
      // Unigram/SentencePiece: vocab is [[token, score], ...] — ID = index
      int size = vocabNode.size();
      String[] vocab = new String[size];
      for (int i = 0; i < size; i++) {
        JsonNode entry = vocabNode.get(i);
        vocab[i] = entry.isArray() ? entry.get(0).asText() : entry.asText();
      }
      return vocab;
    } else if (vocabNode.isObject()) {
      // BPE: vocab is {token: id, ...}
      Map<String, Integer> vocabMap =
          mapper.convertValue(vocabNode, new TypeReference<Map<String, Integer>>() {});
      int maxId = vocabMap.values().stream().mapToInt(Integer::intValue).max().orElse(0);
      String[] vocab = new String[maxId + 1];
      Arrays.fill(vocab, "<unk>");
      for (var entry : vocabMap.entrySet()) {
        vocab[entry.getValue()] = entry.getKey();
      }
      return vocab;
    } else {
      throw new IOException(
          "tokenizer.json model.vocab has unexpected type: " + vocabNode.getNodeType());
    }
  }

  private static long[] truncate(long[] arr, int maxLen) {
    return arr.length <= maxLen ? arr : Arrays.copyOf(arr, maxLen);
  }

  private static long[] padRight(long[] arr, int targetLen) {
    if (arr.length >= targetLen) return arr;
    return Arrays.copyOf(arr, targetLen); // extends with 0s
  }

  private static LongBuffer flatten(long[][] arrays, int batch, int len) {
    long[] flat = new long[batch * len];
    for (int i = 0; i < batch; i++) {
      System.arraycopy(arrays[i], 0, flat, i * len, len);
    }
    return LongBuffer.wrap(flat);
  }
}
