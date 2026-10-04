/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.VariantSelection;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder.EmbedResult;
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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Forensic reproduction test (tempdoc 691 takeover) for the reported live vector-corruption in
 * {@link OnnxEmbeddingEncoder#embedWithSpans} for documents in the 2049-8192-token range —
 * the regime that is {@code > maxSeqLen} (2048, the base batch-path limit) but {@code <=
 * lateChunkingMaxSeqLen} (8192, the raised late-chunking ceiling, tempdoc 691 Phase 2).
 *
 * <p>Production symptom: whole-doc vectors written by {@code embedWithSpans(content, new
 * int[0][])} on an encoder configured with {@code maxSeqLen=2048, lateChunkingMaxSeqLen=8192} are
 * retrieval-garbage. The base {@code embed(content)} path on an encoder configured with {@code
 * maxSeqLen=8192} produces measured-good vectors for the same docs, and the raw ONNX file is
 * independently verified numerically perfect at 8192 tokens against the HF reference. So if the
 * defect is real, it lives in the Java path uniquely reachable by {@code embedWithSpans} when
 * {@code maxSeqLen < ids.length <= lateChunkingMaxSeqLen} — a regime {@link
 * OnnxEmbeddingEncoderLateChunkingTest} does not exercise (it fixes {@code lateChunkingMaxSeqLen}
 * to fall back to its 512-token {@code maxSeqLen}, so "long doc" there means "beyond the
 * lateChunking ceiling", not "beyond maxSeqLen but within it").
 *
 * <p>Model-gated: skipped when the real ONNX model is not found. Discovery mirrors {@link
 * OnnxEmbeddingEncoderLateChunkingTest} (env var {@code JUSTSEARCH_EMBED_ONNX_MODEL_PATH}) plus a
 * parent-walk for {@code models/onnx/gte-multilingual-base} (this repo's real embedding model
 * directory — distinct from the {@code models/onnx/embedding} convention the other test walks).
 *
 * <p>Deliberately loads {@code model.onnx} (FP32) directly rather than going through {@link
 * InferenceCompositionRootTestHelper#cpuSessionFor}: this model directory's {@code
 * model_manifest.json} declares {@code "cpu": "model_fp16.onnx"}, and FP16-on-CPU is documented
 * as catastrophic ({@link ModelSessionPolicyResolver#deriveCpuOptLevel} downgrades it to {@code
 * BASIC_OPT} because {@code EXTENDED_OPT} inserts Cast nodes and can take 30+ minutes). FP32 is
 * native to the CPU EP and is the file the python cosine-vs-HF probe referenced in the takeover
 * brief was almost certainly run against (INT8 is not present in this model directory — only
 * FP32 {@code model.onnx} and FP16 {@code model_fp16.onnx} exist on disk).
 *
 * <p>E8k and E2k share one {@link SessionHandle} (the ONNX graph is shape-agnostic; {@code
 * maxSeqLen} is a Java-side threshold only, not baked into session construction) — this also
 * mirrors production, where exactly one model file backs both the base and late-chunking paths.
 */
@DisplayName("OnnxEmbeddingEncoder long-doc forensic (tempdoc 691 takeover, 2049-8192 token regime)")
final class OnnxEmbeddingEncoderLongDocForensicTest {

  private static Path modelDir;
  private static SessionHandle sharedSessions;
  private static OnnxEmbeddingEncoder e8k; // maxSeqLen=8192, lateChunkingMaxSeqLen=8192
  private static OnnxEmbeddingEncoder e2k; // maxSeqLen=2048, lateChunkingMaxSeqLen=8192
  private static HuggingFaceTokenizer tokenizer8k;
  private static HuggingFaceTokenizer tokenizer2k;

  @BeforeAll
  static void setUp() throws Exception {
    ModelDirTestResolver.Discovery discovery = discoverModelDir();
    assumeTrue(discovery.modelDir() != null, discovery.missDescription());
    modelDir = discovery.modelDir();

    Path modelFile = modelDir.resolve("model.onnx");

    sharedSessions = buildFp32CpuSession("embed-longdoc-forensic", modelFile);

    EmbeddingAssembly assembly8k =
        OnnxEmbeddingEncoder.buildAssembly(sharedSessions, modelDir, 8192, 8192, false);
    e8k = new OnnxEmbeddingEncoder(assembly8k.sessions(), assembly8k.shape(), assembly8k.tokenizer());
    tokenizer8k = assembly8k.tokenizer();

    EmbeddingAssembly assembly2k =
        OnnxEmbeddingEncoder.buildAssembly(sharedSessions, modelDir, 2048, 8192, false);
    e2k = new OnnxEmbeddingEncoder(assembly2k.sessions(), assembly2k.shape(), assembly2k.tokenizer());
    tokenizer2k = assembly2k.tokenizer();
  }

  @AfterAll
  static void tearDown() {
    // e8k/e2k share sharedSessions — do NOT call encoder.close() (it would double-close the
    // shared SessionHandle). Close each encoder's own tokenizer instance, then the session once.
    if (tokenizer8k != null) {
      tokenizer8k.close();
    }
    if (tokenizer2k != null) {
      tokenizer2k.close();
    }
    if (sharedSessions != null) {
      sharedSessions.close();
    }
  }

  // Tempdoc 710 Move 6: shared walker (obs:spladebatchsweeptest) — mirrors {@link
  // OnnxEmbeddingEncoderLateChunkingTest}'s discovery, plus the real model dir.
  private static ModelDirTestResolver.Discovery discoverModelDir() {
    return ModelDirTestResolver.discover(
        "models/onnx/gte-multilingual-base",
        "JUSTSEARCH_EMBED_ONNX_MODEL_PATH",
        "model.onnx",
        "tokenizer.json");
  }

  /**
   * Builds a CPU {@link SessionHandle} pinned to an explicit model file at FP32/EXTENDED_OPT,
   * bypassing {@link InferenceCompositionRootTestHelper#cpuSessionFor} (which would resolve
   * {@code model_fp16.onnx} per this model dir's manifest and hit the FP16-on-CPU BASIC_OPT
   * penalty). Structurally mirrors {@code InferenceCompositionRootTestHelper.sessionFor} with an
   * explicit-file override.
   */
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

  private static String buildText(HuggingFaceTokenizer tokenizer, int targetTokens) {
    String[] sentences = {
      "The field of artificial intelligence has evolved significantly over recent decades.",
      "Quantum computing relies on superposition and entanglement of qubits.",
      "The recipe calls for two cups of flour and a pinch of salt.",
      "Machine learning models encode text into dense vector representations.",
      "Late chunking derives chunk vectors from a single forward pass over the whole document.",
      "The river flowed quietly past the old stone bridge at dawn.",
      "Economic policy debates often center on trade-offs between growth and stability.",
      "The orchestra rehearsed the symphony's final movement late into the evening.",
      "Photosynthesis converts sunlight, water, and carbon dioxide into glucose and oxygen.",
      "The spacecraft's trajectory was recalculated after the mid-course correction burn.",
      "Ancient trade routes connected distant civilizations long before modern transportation.",
      "The committee reviewed the proposal and requested several structural revisions.",
      "Coral reefs support an extraordinary density of marine biodiversity.",
      "The novelist spent a decade researching the historical setting of her latest book.",
      "Distributed systems must tolerate partial failures without losing consistency.",
      "The mountain trail wound steeply upward through dense pine forest.",
      "Central banks adjust interest rates to influence inflation and employment.",
      "The chess grandmaster sacrificed a rook to open a decisive attack.",
      "Renewable energy sources are increasingly cost-competitive with fossil fuels.",
      "The archaeologists uncovered pottery shards dating back several millennia.",
    };
    StringBuilder sb = new StringBuilder();
    int idx = 0;
    while (true) {
      sb.append("Paragraph ").append(idx).append(": ").append(sentences[idx % sentences.length]).append(' ');
      idx++;
      // Only re-tokenize periodically -- tokenizing the whole growing string every sentence is
      // O(n^2); check every 8 sentences, which is precise enough for an approximate target.
      if (idx % 8 == 0) {
        int len = tokenizer.encode(sb.toString()).getIds().length;
        if (len >= targetTokens) {
          return sb.toString();
        }
      }
    }
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

  private static String head(float[] v, int n) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < Math.min(n, v.length); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(v[i]);
    }
    return sb.append(']').toString();
  }

  @Test
  @DisplayName(
      "2049-8192 token doc: embedWithSpans on maxSeqLen=2048 encoder matches embed() on"
          + " maxSeqLen=8192 encoder")
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  void longDocEmbedWithSpansMatchesBaseEmbed() throws Exception {
    String longText = buildText(tokenizer8k, 5000);
    int tokenCount = tokenizer8k.encode(longText).getIds().length;
    assertTrue(
        tokenCount > 2048 && tokenCount <= 8192,
        "test text must land in the 2049-8192 token regime under test, got " + tokenCount);

    EmbedResult base = e8k.embed(longText);
    assertNotNull(base.vector(), "E8k.embed() must not return null for an in-range doc");

    EmbedResult late2k = e2k.embedWithSpans(longText, new int[0][]);
    assertNotNull(
        late2k,
        "E2k.embedWithSpans() must not return null: tokenCount="
            + tokenCount
            + " is within lateChunkingMaxSeqLen=8192");

    EmbedResult late8k = e8k.embedWithSpans(longText, new int[0][]);
    assertNotNull(late8k);

    double cosBaseVsLate2k = cosine(base.vector(), late2k.vector());
    double cosBaseVsLate8k = cosine(base.vector(), late8k.vector());

    String diagnostic =
        String.format(
            "tokenCount=%d%ncos(base, late@E2k)=%.6f%ncos(base, late@E8k)=%.6f%n"
                + "base[0:8]=%s%nlate@E2k[0:8]=%s%nlate@E8k[0:8]=%s",
            tokenCount,
            cosBaseVsLate2k,
            cosBaseVsLate8k,
            head(base.vector(), 8),
            head(late2k.vector(), 8),
            head(late8k.vector(), 8));
    System.out.println("[forensic]\n" + diagnostic);

    assertTrue(
        cosBaseVsLate2k > 0.99,
        "E2k.embedWithSpans() doc vector diverged from E8k.embed() base vector -- "
            + diagnostic);
    assertTrue(
        cosBaseVsLate8k > 0.99,
        "E8k.embedWithSpans() doc vector diverged from E8k.embed() base vector (sanity control"
            + " -- should be ~1.0 identical path modulo pooling formula) -- "
            + diagnostic);
  }

  @Test
  @DisplayName("short doc (~300 tokens) control: embedWithSpans matches embed() near bit-identity")
  void shortDocControlIsBitIdentical() throws Exception {
    String shortText = buildText(tokenizer2k, 300);
    int tokenCount = tokenizer2k.encode(shortText).getIds().length;
    assertTrue(tokenCount <= 2048, "control text must stay under maxSeqLen, got " + tokenCount);

    EmbedResult plain = e2k.embed(shortText);
    EmbedResult withSpans = e2k.embedWithSpans(shortText, new int[0][]);
    assertNotNull(withSpans);

    double cos = cosine(plain.vector(), withSpans.vector());
    System.out.println("[forensic] shortDocControl tokenCount=" + tokenCount + " cos=" + cos);
    assertTrue(
        cos > 0.999999,
        "short-doc control must be ~bit-identical (same tokens, same ORT pass, same pooling) --"
            + " cos="
            + cos
            + " plain[0:8]="
            + head(plain.vector(), 8)
            + " withSpans[0:8]="
            + head(withSpans.vector(), 8));
  }
}
