/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.VariantSelection;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder.EmbedResult;
import io.justsearch.indexerworker.inference.BoundedTokenizeGroups;
import io.justsearch.ort.Composition;
import io.justsearch.ort.ModelArtifacts;
import io.justsearch.ort.ModelSessionPolicy;
import io.justsearch.ort.ModelSessionPolicyResolver;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.RuntimePolicy;
import io.justsearch.ort.SessionHandle;
import io.justsearch.ort.testing.InferenceCompositionRootTestHelper;
import io.justsearch.ort.testing.ModelDirTestResolver;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression for the tempdoc 710 Move 3 port of the tempdoc 686 SPLADE crash fix to the embed
 * lane: {@code embedBatchWithChunking}'s Phase 1 used to tokenize every caller text upfront with
 * truncation disabled ({@link #buildFp32CpuSession} loads the same tokenizer config as
 * production, which sets {@code "truncation": "false"}). The fix groups Phase 1 tokenization into
 * character-budgeted {@code batchEncode} calls.
 *
 * <p>Like {@code SpladeEncoderBoundedTokenizeTest}, the memory bound itself is not assertable
 * in-JVM; this test pins the OBSERVABLE contract instead — grouping must not reorder, drop, or
 * cross-wire results relative to embedding each text alone. Unlike SPLADE (which truncates every
 * text to one {@code maxSeqLen}-bounded inference regardless of raw length, embed's chunking
 * makes per-text compute scale with raw length. A small injected budget exercises multiple
 * groups while keeping every result cheap enough to compare with singleton inference.
 */
@DisplayName("710: OnnxEmbeddingEncoder bounded-group tokenization preserves per-text results")
final class OnnxEmbeddingEncoderBoundedTokenizeTest {

  private static final int MAX_SEQ_LEN = 512;

  private static Path modelDir;
  private static SessionHandle sharedSessions;
  private static OnnxEmbeddingEncoder encoder;
  private static HuggingFaceTokenizer tokenizer;

  @BeforeAll
  static void setUp() throws Exception {
    ModelDirTestResolver.Discovery discovery = discoverModelDir();
    assumeTrue(discovery.modelDir() != null, discovery.missDescription());
    modelDir = discovery.modelDir();

    Path modelFile = modelDir.resolve("model.onnx");

    // Deliberately loads model.onnx (FP32) directly rather than InferenceCompositionRootTestHelper
    // .cpuSessionFor: this model dir's manifest declares "cpu": "model_fp16.onnx", and FP16-on-CPU
    // is documented as catastrophic (30+ min graph optimization) — see
    // OnnxEmbeddingEncoderLongDocForensicTest for the same precaution.
    sharedSessions = buildFp32CpuSession("embed-bounded-tokenize-test", modelFile);
    EmbeddingAssembly assembly =
        OnnxEmbeddingEncoder.buildAssembly(sharedSessions, modelDir, MAX_SEQ_LEN, MAX_SEQ_LEN, false);
    encoder = new OnnxEmbeddingEncoder(assembly.sessions(), assembly.shape(), assembly.tokenizer());
    tokenizer = assembly.tokenizer();
  }

  @AfterAll
  static void tearDown() {
    if (encoder != null) {
      encoder.close();
    }
  }

  // Tempdoc 710 Move 6: shared walker (obs:spladebatchsweeptest).
  private static ModelDirTestResolver.Discovery discoverModelDir() {
    return ModelDirTestResolver.discover(
        "models/onnx/gte-multilingual-base",
        "JUSTSEARCH_EMBED_ONNX_MODEL_PATH",
        "model.onnx",
        "tokenizer.json");
  }

  private static SessionHandle buildFp32CpuSession(String consumerName, Path modelFile)
      throws OrtException {
    VariantSelection variant =
        InferenceCompositionRootTestHelper.cpuVariant(modelFile, ModelPrecision.FP32);
    OptLevel cpuOptLevel =
        ModelSessionPolicyResolver.deriveCpuOptLevel(variant.precision(), ExecutionProvider.CPU);
    ModelSessionPolicy policy =
        ModelSessionPolicy.forFallback(
            /* gpuConfig= */ null,
            cpuOptLevel,
            /* deferCpuSession= */ false,
            /* gpuRetryEnabled= */ false,
            /* gpuRetryIntervalMs= */ 60_000L);
    Composition comp =
        new Composition(
            RuntimePolicy.defaults(), policy, new ModelArtifacts(variant.modelFile(), variant.modelFile()));
    return OrtSessionAssembler.buildManager(consumerName, comp, () -> false);
  }

  private static double cosine(float[] a, float[] b) {
    assertEquals(a.length, b.length, "vector dimension mismatch");
    double dot = 0.0;
    double normA = 0.0;
    double normB = 0.0;
    for (int i = 0; i < a.length; i++) {
      dot += (double) a[i] * b[i];
      normA += (double) a[i] * a[i];
      normB += (double) b[i] * b[i];
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }

  /** Multi-chunk long document, unique per index — just over MAX_SEQ_LEN tokens. */
  private static String longDocText(int index) {
    StringBuilder sb = new StringBuilder();
    sb.append("LONGDOC-").append(index).append(": ");
    for (int i = 0; i < 260; i++) {
      sb.append("paragraph ").append(index).append(" segment ").append(i)
          .append(" discusses topic ").append((index * 7 + i) % 13).append(". ");
    }
    return sb.toString();
  }

  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  void pooledOnlyEncodingMatchesFullResultsWithoutReturningUnusedChunks() throws Exception {
    List<String> texts = List.of("short introduction", longDocText(1), "brief conclusion", longDocText(2));
    List<EmbedResult> full = encoder.embedBatchWithChunking(texts);
    List<EmbedResult> pooled = encoder.embedBatchPooled(texts);
    assertEquals(full.size(), pooled.size());
    for (int i = 0; i < full.size(); i++) {
      assertEquals(full.get(i).chunkCount(), pooled.get(i).chunkCount());
      assertTrue(cosine(full.get(i).vector(), pooled.get(i).vector()) > 0.999999);
      assertTrue(pooled.get(i).chunkVectors().isEmpty());
    }
    EmbedResult single = encoder.embedBatchPooled(List.of(texts.get(1))).get(0);
    assertTrue(cosine(encoder.embed(texts.get(1)).vector(), single.vector()) > 0.999999);
    assertTrue(single.chunkCount() > 1);
    assertTrue(single.chunkVectors().isEmpty());
  }

  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  @DisplayName("multi-group batch: every position == singleton embed(), order preserved")
  void groupBoundariesPreserveResults() throws Exception {
    List<String> batch = List.of("short introduction", "another topic", longDocText(0),
        "brief conclusion", "different subject");
    long budget = 32;
    assertTrue(BoundedTokenizeGroups.ranges(batch, budget).size() >= 3);
    List<EmbedResult> batched = encoder.embedBatchWithChunking(batch, budget);
    assertEquals(batch.size(), batched.size());
    int tokenCount = tokenizer.encode(batch.get(2)).getIds().length;
    assertTrue(tokenCount > MAX_SEQ_LEN, "long doc must exceed maxSeqLen, got " + tokenCount);
    assertTrue(batched.get(2).chunkCount() > 1, "long doc must produce multiple chunks");
    for (int pos = 0; pos < batch.size(); pos++) {
      EmbedResult singleton = encoder.embed(batch.get(pos));
      double cos = cosine(batched.get(pos).vector(), singleton.vector());
      assertTrue(
          cos > 0.999,
          "position "
              + pos
              + ": batched vector diverged from singleton embed() (cos="
              + cos
              + ", text="
              + (batch.get(pos).length() > 40 ? batch.get(pos).substring(0, 40) + "..." : batch.get(pos))
              + ")");
      assertEquals(
          singleton.chunkCount(),
          batched.get(pos).chunkCount(),
          "position " + pos + ": chunk count mismatch between batched and singleton paths");
    }
  }
}
