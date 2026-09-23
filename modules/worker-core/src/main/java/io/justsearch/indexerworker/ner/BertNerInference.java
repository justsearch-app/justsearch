/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ner;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.justsearch.indexerworker.inference.LocalSessionAcquisition;
import io.justsearch.indexerworker.metrics.EncoderOrtRunSpans;
import io.justsearch.ort.ModelManifest;
import io.justsearch.ort.OrtCudaStatus;
import io.justsearch.ort.SessionHandle;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ONNX Runtime wrapper for NER inference with optional GPU acceleration.
 *
 * <p>Supports any BERT/DistilBERT token-classification model (e.g.,
 * Davlan/distilbert-base-multilingual-cased-ner-hrl). Auto-detects whether the model expects
 * token_type_ids by inspecting the ONNX graph inputs. Thread-safe — ONNX sessions are shared,
 * tensors are per-request.
 *
 * <p>Session lifecycle (CPU session, GPU session, GPU arbitration, teardown) is delegated to a
 * {@link SessionHandle}. NER uses {@code gpuRetryEnabled(false)} — permanent CPU fallback on GPU
 * failure.
 *
 * <p>Tempdoc 397 §14.24 FD-NER: encoder is a pure inference transformer. Constructor accepts a
 * pre-built {@link NerShape} (input-schema facts) and {@link HuggingFaceTokenizer} (pre-loaded
 * by the composition root). Zero filesystem I/O in the body. The {@link #buildAssembly} factory
 * exposes the metadata-loading logic for dev-mode fallback paths that don't go through the
 * composition root.
 */
public final class BertNerInference implements Closeable {

  private static final Logger log = LoggerFactory.getLogger(BertNerInference.class);

  // --- Session management (delegated to SessionHandle — tempdoc 397 §7.4) ---
  private final SessionHandle sessions;
  private final HuggingFaceTokenizer tokenizer;
  private final int maxSequenceLength;
  private final boolean useTokenTypeIds;

  /** Raw inference output suitable for {@link BioTagDecoder#decode}. */
  public record InferenceOutput(float[][] logits, String[] tokens, long[] wordIds) {}

  // --- Per-call profiling (356->357: shared accumulator, pull model) ---
  private final io.justsearch.indexerworker.metrics.EncoderProfileAccumulator profiler =
      new io.justsearch.indexerworker.metrics.EncoderProfileAccumulator(
          "tokenize", "tensor", "ort", "extract");
  private static final int PROFILE_LOG_INTERVAL = 100;

  // --- Per-ORT-call span tracer (tempdoc 400 LR2-a) ---
  private static final Tracer ORT_TRACER = EncoderOrtRunSpans.encoderTracer("ner");

  /**
   * Tempdoc 397 §14.24 FD-NER primary constructor. All construction inputs are pre-built by
   * the composition root (or by {@link #buildAssembly} for dev-mode fallback paths). Encoder
   * performs zero filesystem I/O.
   *
   * @param sessions pre-configured session handle
   * @param shape model-intrinsic facts ({@code maxSeqLen} + {@code needsTokenTypeIds})
   * @param tokenizer pre-loaded DJL HuggingFace tokenizer (caller owns lifecycle)
   */
  public BertNerInference(
      SessionHandle sessions, NerShape shape, HuggingFaceTokenizer tokenizer) {
    this.sessions = sessions;
    this.tokenizer = tokenizer;
    this.maxSequenceLength = shape.maxSequenceLength();
    this.useTokenTypeIds = shape.needsTokenTypeIds();
    log.info(
        "BertNerInference initialized: maxSeqLen={}, tokenTypeIds={}",
        maxSequenceLength,
        useTokenTypeIds);
    io.justsearch.indexerworker.metrics.OperationalMetrics.getInstance()
        .registerEncoder("ner", profiler);
    // Tempdoc 710 Move 2: bind the choke-point recorder so every session.run() invocation
    // through this SessionHandle's leases records itself — call sites can no longer forget
    // (the class of gap that shipped as tempdoc 691 B-5).
    sessions.setOrtRunRecorder(profiler::recordOrtCall);
  }

  /**
   * Builds a complete {@link NerAssembly} from a session handle + model directory + max
   * sequence length. Tempdoc 397 §14.24 FD-NER: shared helper called by
   * {@code InferenceCompositionRoot.composeNer} (variant-driven path) and by
   * {@link NerService} fallback (dev-mode path). Loads manifest, tokenizer, and label mapping
   * eagerly; reads input names from the session to populate {@link NerShape}.
   *
   * @param capabilityContractStrict when {@code true}, an undeclared/ambiguous model-capability
   *     fact throws instead of degrading with a WARN — {@code
   *     justsearch.models.capability_contract_strict} (tempdoc 710 Wave 2 Move 1)
   * @throws OrtException if session input-name probe fails
   * @throws UncheckedIOException if tokenizer load fails
   * @throws IllegalStateException if {@code capabilityContractStrict} and a capability fact is
   *     undeclared/ambiguous
   */
  public static NerAssembly buildAssembly(
      SessionHandle sessions, Path modelDir, int maxSequenceLength, boolean capabilityContractStrict)
      throws OrtException {
    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);
    Path tokenizerPath = modelDir.resolve(manifest.tokenizer());
    HuggingFaceTokenizer tokenizer;
    try {
      // Tempdoc 710 Move 3: explicit truncation=false/padding=false (matches SpladeEncoder /
      // OnnxEmbeddingEncoder's tokenizer construction). Without this, DJL's batchEncode(List)
      // applies its own default padding-to-batch-max regardless of this model's tokenizer.json
      // declaring "padding": null — discovered via BertNerInferenceBoundedTokenizeTest failing
      // with a short text's tokens padded from 11 to 512 once inferBatch's Phase 1 started using
      // batchEncode (previously only single-text encode() was ever called, which already behaved
      // unpadded/untruncated, so the inconsistency was latent). Explicit false on both keeps
      // batchEncode's per-text output equivalent to encode()'s, and Java-side truncation (below)
      // remains the sole truncation point, as already documented for maxSequenceLength.
      tokenizer =
          HuggingFaceTokenizer.newInstance(
              tokenizerPath, Map.of("truncation", "false", "padding", "false"));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load NER tokenizer from " + tokenizerPath, e);
    }
    // Tempdoc 397 §14.24 FD-ProbeDeletion: probe input names via the assembler helper;
    // SessionHandle no longer exposes inputNames().
    // Tempdoc 374 alpha.22 Bug S: route through resolveExistingModelFile so the probe
    // works on GPU_FULL where Install AI downloads only model_fp16.onnx (no model.onnx).
    // Pre-alpha.22 this called resolveModelPath(_, false) which unconditionally returns
    // manifest.cpu() = "model.onnx" — file doesn't exist on GPU_FULL → ORT_NO_SUCHFILE
    // → composeNerRole catches → NER unavailable. Round 12 evidence: every alpha 13-21
    // shipped this regression but rounds 7-11 used pre-staged models (which have
    // model.onnx) so it stayed hidden. Round 12 first true-fresh-install caught it.
    // This is the 7th encoder site to be migrated through resolveExistingModelFile —
    // matches alpha.16 Bug C (EmbeddingFingerprint), alpha.19 Bug J-2 (OnnxModelDiscovery
    // fp16 fallback), alpha.20 Bug L (EmbeddingFingerprint cold-restart). Migration now
    // complete across all known encoder sites.
    Path probePath = manifest.resolveExistingModelFile(modelDir);
    io.justsearch.ort.OrtSessionAssembler.ProbedNames probed =
        io.justsearch.ort.OrtSessionAssembler.probeModelNames(sessions.environment(), probePath);
    boolean needsTokenTypeIds = probed.inputs().contains("token_type_ids");
    NerShape shape = new NerShape(maxSequenceLength, needsTokenTypeIds);
    // Tempdoc 710 Wave 2 Move 1: capabilities resolved ONCE here (the composition choke point) —
    // label mapping is no longer parsed independently by this encoder; ModelCapabilityResolver
    // already read the manifest-declared label config file's id2label and WARNed if absent.
    io.justsearch.ort.ModelCapabilities capabilities =
        io.justsearch.ort.ModelCapabilityResolver.resolve(
            "ner",
            modelDir,
            manifest,
            io.justsearch.ort.CapabilityRequirements.NER,
            capabilityContractStrict);
    BioTagDecoder.LabelMapping labelMapping = toLabelMapping(capabilities.labelMapping());
    return new NerAssembly(sessions, shape, tokenizer, labelMapping, capabilities);
  }

  /**
   * Projects the ort-common capability contract's raw {@code id -> label} map into this module's
   * {@link BioTagDecoder.LabelMapping} type. Empty (undeclared — {@link
   * ModelCapabilityResolver} already logged a WARN naming the gap at the choke point) falls back
   * to the legacy dslim/bert-base-NER default, loud at WARN here too since this is the last
   * fallback actually taken (was silent {@code log.debug} pre-Wave-2 — tempdoc 710 orphan #6).
   */
  private static BioTagDecoder.LabelMapping toLabelMapping(Map<String, String> id2label) {
    if (id2label == null || id2label.isEmpty()) {
      log.warn("NER label mapping undeclared — using legacy dslim/bert-base-NER default mapping");
      return BioTagDecoder.LabelMapping.bertBaseNer();
    }
    BioTagDecoder.LabelMapping result = BioTagDecoder.LabelMapping.fromId2Label(id2label);
    log.info("NER label mapping resolved: {} labels", result.id2label().length);
    return result;
  }

  /**
   * Runs NER inference on a text chunk.
   *
   * @param text input text (should be pre-chunked to fit within maxSequenceLength)
   * @return raw logits, token strings, and word IDs for BioTagDecoder
   * @throws OrtException if inference fails
   */
  public InferenceOutput infer(String text) throws OrtException {
    long t0 = System.nanoTime();
    Encoding encoding = tokenizer.encode(text);
    long[] inputIds = encoding.getIds();
    long[] attentionMask = encoding.getAttentionMask();
    String[] tokens = encoding.getTokens();
    long[] wordIds = encoding.getWordIds();

    // Truncate to maxSequenceLength if needed
    int seqLen = Math.min(inputIds.length, maxSequenceLength);
    if (seqLen < inputIds.length) {
      inputIds = truncate(inputIds, seqLen);
      attentionMask = truncate(attentionMask, seqLen);
      tokens = truncateStr(tokens, seqLen);
      wordIds = truncate(wordIds, seqLen);
    }
    long t1 = System.nanoTime();

    long[] shape = {1, seqLen};

    try (var lease = sessions.acquire(LocalSessionAcquisition.foreground())) {
      try (OnnxTensor inputIdsTensor =
              OnnxTensor.createTensor(sessions.environment(), LongBuffer.wrap(inputIds), shape);
          OnnxTensor attentionMaskTensor =
              OnnxTensor.createTensor(
                  sessions.environment(), LongBuffer.wrap(attentionMask), shape)) {

        Map<String, OnnxTensor> inputs;
        OnnxTensor tokenTypeIdsTensor = null;
        try {
          if (useTokenTypeIds) {
            long[] tokenTypeIds = new long[seqLen];
            tokenTypeIdsTensor =
                OnnxTensor.createTensor(
                    sessions.environment(), LongBuffer.wrap(tokenTypeIds), shape);
            inputs =
                Map.of(
                    "input_ids", inputIdsTensor,
                    "attention_mask", attentionMaskTensor,
                    "token_type_ids", tokenTypeIdsTensor);
          } else {
            inputs = Map.of("input_ids", inputIdsTensor, "attention_mask", attentionMaskTensor);
          }
          long t2 = System.nanoTime();

          // Tempdoc 400 LR2-a: per-ORT-call span. Single-doc NER path, batch=1.
          // LR2-b: lease is already acquired at the outer try — the
          // lease.acquire span emitted by NativeSessionHandle is parented under
          // whatever is current at acquire time (typically enrichment.batch
          // in backfill, or no parent elsewhere). This NER single-doc path
          // keeps encoder.ort_run as a sibling of lease.acquire; batched path
          // below + OnnxEmbed/SPLADE/BgeM3 achieve LR2-b's child-parent
          // pattern via pre-acquire span start.
          Span ortSpan = EncoderOrtRunSpans.maybeOrtRun(ORT_TRACER, "ner", 1, seqLen);
          ortSpan.setAttribute("encoder.gpu", !lease.isCpu());
          try {
            try (Scope _ = ortSpan.makeCurrent();
                 OrtSession.Result result = lease.run(inputs)) {
            long t3 = System.nanoTime();
            // NER output shape: [1, seqLen, numLabels]
            float[][][] output3d = (float[][][]) result.get(0).getValue();
            float[][] logits = output3d[0]; // Remove batch dimension
            long t4 = System.nanoTime();

            // Aggregate profiling. ORT-call timing is now recorded at the Lease choke point
            // (tempdoc 710 Move 2) via the recorder bound in the constructor; t2/t3 stay only
            // as the extract-phase boundary.
            profiler.addPhaseNs("tokenize", t1 - t0);
            profiler.addPhaseNs("tensor", t2 - t1);
            profiler.addPhaseNs("extract", t4 - t3);
            // callCount() is approximate — concurrent threads may skip or double-fire
            // at interval boundaries. Acceptable for periodic diagnostic logging.
            long calls = profiler.callCount();
            if (calls % PROFILE_LOG_INTERVAL == 0) {
              var snap = profiler.snapshot();
              if (snap != null) {
                log.info(
                    "NER per-call profile ({}calls): {}, ort=[{}], seqLen={}",
                    calls, snap.formatAvgPhases(calls), snap.formatOrtDist(), seqLen);
              }
            }

            return new InferenceOutput(logits, tokens, wordIds);
            }
          } finally {
            ortSpan.end();
          }
        } finally {
          if (tokenTypeIdsTensor != null) {
            tokenTypeIdsTensor.close();
          }
        }
      }
    }
  }

  /**
   * Maximum NER batch size for GPU inference. The output tensor is tiny (batch × seqLen × 9
   * labels × 4 bytes = 288 KB at batch=16/seq=512), but the attention intermediates are not:
   * they scale O(batch × heads × seq²) — ~200 MB at batch=16/seq=512 in fp16 — so batched NER
   * IS arena-limited. At the old 512 MB arena default this OOMed continuously and silently
   * degraded every batch to the per-doc fallback (~10× per-item cost); the arena default is
   * 2048 MB since tempdoc 691 (measured: zero OOM, 7.7 GB total VRAM peak on a 12 GB GPU).
   */
  private static final int MAX_NER_BATCH_SIZE = 16;

  /** SeqLen buckets for consistent tensor shapes — avoids ORT dynamic shape cache invalidation. */
  private static final int[] NER_SEQ_LEN_BUCKETS = {64, 128, 256, 512};

  private static int bucketSeqLen(int seqLen) {
    for (int bucket : NER_SEQ_LEN_BUCKETS) {
      if (seqLen <= bucket) return bucket;
    }
    return seqLen;
  }

  /**
   * Upper bound on total input CHARS per native {@code batchEncode} call in {@link #inferBatch}'s
   * tokenize phase. Mirrors {@code SpladeEncoder.TOKENIZE_GROUP_CHAR_BUDGET} (tempdoc 686 crash
   * fix; ported here by tempdoc 710 Move 3). NER inputs are typically pre-chunked to a few hundred
   * tokens by the caller, but (a) this tokenizer's {@code tokenizer.json} has {@code truncation:
   * null} — no tokenizer-level cap — so one mis-chunked/oversized text still produces an unbounded
   * native encoding, and (b) the caller LIST itself is unbounded, so tokenizing it upfront in one
   * unbounded scan before any inference sub-batching held the same class of landmine SPLADE hit.
   * Grouping by input chars bounds peak per-call native materialization to one group regardless
   * of caller list size, while preserving exact per-text tokenization results.
   */
  private static final long TOKENIZE_GROUP_CHAR_BUDGET = 512_000;

  /**
   * Runs batched NER inference on multiple text chunks. Sorts by token count, groups into
   * sub-batches by sequence length bucket, pads to bucket boundary (not max-in-batch) to minimize
   * padding waste while keeping consistent tensor shapes for ORT caching.
   *
   * @param texts list of text chunks (each should be pre-chunked to fit within maxSequenceLength)
   * @return list of inference outputs, one per input text (original order preserved)
   * @throws OrtException if inference fails
   */
  public List<InferenceOutput> inferBatch(List<String> texts) throws OrtException {
    if (texts.isEmpty()) {
      return List.of();
    }
    if (texts.size() == 1) {
      return List.of(infer(texts.get(0)));
    }

    var acquisition = LocalSessionAcquisition.background();

    // Tokenize in memory-bounded groups (tempdoc 686/710 crash-fix port — see
    // TOKENIZE_GROUP_CHAR_BUDGET). Groups are processed in original order and written into the
    // same per-index arrays a single upfront scan would have produced, so grouping changes only
    // native-call granularity, not output order or values.
    int n = texts.size();
    long[][] allInputIds = new long[n][];
    long[][] allAttentionMask = new long[n][];
    String[][] allTokens = new String[n][];
    long[][] allWordIds = new long[n][];
    int[] tokenCounts = new int[n];

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
      for (int j = 0; j < groupEncodings.length; j++) {
        int idx = groupStart + j;
        Encoding enc = groupEncodings[j];
        int seqLen = Math.min(enc.getIds().length, maxSequenceLength);
        allInputIds[idx] = truncate(enc.getIds(), seqLen);
        allAttentionMask[idx] = truncate(enc.getAttentionMask(), seqLen);
        allTokens[idx] = truncateStr(enc.getTokens(), seqLen);
        allWordIds[idx] = truncate(enc.getWordIds(), seqLen);
        tokenCounts[idx] = seqLen;
      }
      groupStart = groupEnd;
    }

    // Sort by token count (ascending) to group similar-length sequences
    Integer[] sortedIndices = new Integer[n];
    for (int i = 0; i < n; i++) sortedIndices[i] = i;
    java.util.Arrays.sort(sortedIndices, java.util.Comparator.comparingInt(i -> tokenCounts[i]));

    // Process in sub-batches, grouped by bucket
    InferenceOutput[] resultsByIdx = new InferenceOutput[n];
    int pos = 0;
    while (pos < n) {
      int batchStart = pos;
      int batchBucket = bucketSeqLen(tokenCounts[sortedIndices[pos]]);

      // Collect docs that fit in this bucket (up to MAX_NER_BATCH_SIZE)
      while (pos < n
          && (pos - batchStart) < MAX_NER_BATCH_SIZE
          && bucketSeqLen(tokenCounts[sortedIndices[pos]]) == batchBucket) {
        pos++;
      }
      int batchSize = pos - batchStart;
      int padLen = batchBucket;

      // Flatten into batched tensors padded to bucket boundary
      long[] flatIds = new long[batchSize * padLen];
      long[] flatMask = new long[batchSize * padLen];
      long[] flatTypes = useTokenTypeIds ? new long[batchSize * padLen] : null;
      for (int j = 0; j < batchSize; j++) {
        int origIdx = sortedIndices[batchStart + j];
        int srcLen = allInputIds[origIdx].length;
        System.arraycopy(allInputIds[origIdx], 0, flatIds, j * padLen, srcLen);
        System.arraycopy(allAttentionMask[origIdx], 0, flatMask, j * padLen, srcLen);
      }

      long[] shape = {batchSize, padLen};

      try (var lease = sessions.acquire(acquisition)) {
        try (OnnxTensor inputIdsTensor =
                OnnxTensor.createTensor(sessions.environment(), LongBuffer.wrap(flatIds), shape);
            OnnxTensor attentionMaskTensor =
                OnnxTensor.createTensor(
                    sessions.environment(), LongBuffer.wrap(flatMask), shape)) {

          Map<String, OnnxTensor> inputs = new HashMap<>();
          inputs.put("input_ids", inputIdsTensor);
          inputs.put("attention_mask", attentionMaskTensor);
          OnnxTensor tokenTypeIdsTensor = null;
          try {
            if (useTokenTypeIds && flatTypes != null) {
              tokenTypeIdsTensor =
                  OnnxTensor.createTensor(
                      sessions.environment(), LongBuffer.wrap(flatTypes), shape);
              inputs.put("token_type_ids", tokenTypeIdsTensor);
            }

            // Tempdoc 400 LR2-a/LR2-b: per-ORT-call span for batched NER path.
            // Same sibling-to-lease note as single-doc path above — NER path
            // has the lease as an outer try-with-resources that predates this
            // site's refactoring.
            Span ortSpan = EncoderOrtRunSpans.maybeOrtRun(
                ORT_TRACER, "ner", batchSize, padLen);
            ortSpan.setAttribute("encoder.gpu", !lease.isCpu());
            try {
              // Tempdoc 710 Move 2: ORT-call timing is recorded at the Lease choke point
              // (lease.run below) via the recorder bound in the constructor — no per-call-site
              // recordOrtCall needed here (this is the exact NER batched-path gap tempdoc 691
              // B-5 shipped under the old per-call-site regime; the choke point makes it
              // structurally impossible to omit again).
              try (Scope _ = ortSpan.makeCurrent();
                   OrtSession.Result result = lease.run(inputs)) {
                float[][][] output3d = (float[][][]) result.get(0).getValue();
                for (int j = 0; j < batchSize; j++) {
                  int origIdx = sortedIndices[batchStart + j];
                  int srcLen = allInputIds[origIdx].length;
                  float[][] logits = new float[srcLen][];
                  System.arraycopy(output3d[j], 0, logits, 0, srcLen);
                  resultsByIdx[origIdx] =
                      new InferenceOutput(logits, allTokens[origIdx], allWordIds[origIdx]);
                }
              }
            } finally {
              ortSpan.end();
            }
          } finally {
            if (tokenTypeIdsTensor != null) {
              tokenTypeIdsTensor.close();
            }
          }
        }
      }
    }

    return java.util.Arrays.asList(resultsByIdx);
  }

  /** Returns true if GPU is configured and available (for callers to decide batch vs per-doc). */
  public boolean isGpuAvailable() {
    return sessions.isGpuAvailable();
  }

  // ---------------------------------------------------------------------------
  // Observability (delegated to SessionHandle)
  // ---------------------------------------------------------------------------

  /** Returns the current ORT CUDA status for observability. */
  public OrtCudaStatus getOrtCudaStatus() {
    return sessions.status();
  }

  private static long[] truncate(long[] arr, int len) {
    long[] result = new long[len];
    System.arraycopy(arr, 0, result, 0, len);
    return result;
  }

  private static String[] truncateStr(String[] arr, int len) {
    String[] result = new String[len];
    System.arraycopy(arr, 0, result, 0, len);
    return result;
  }

  @Override
  public void close() {
    sessions.close();
    tokenizer.close();
  }
}
