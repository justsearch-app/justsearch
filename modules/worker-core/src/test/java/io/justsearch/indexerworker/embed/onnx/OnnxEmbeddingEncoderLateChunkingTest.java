/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed.onnx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.onnxruntime.OnnxTensor;
import io.justsearch.indexerworker.inference.LocalSessionAcquisition;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link OnnxEmbeddingEncoder#embedWithSpans} (late chunking, tempdoc 691 §Phase
 * G/H) using a real ONNX model.
 *
 * <p>Skipped automatically if the model files are not present on disk (mirrors {@code
 * OnnxEmbeddingEncoderIntegrationTest}'s discovery — {@code models/onnx/embedding/model.onnx} +
 * {@code tokenizer.json}, or {@code JUSTSEARCH_EMBED_ONNX_MODEL_PATH}).
 */
@DisplayName("OnnxEmbeddingEncoder.embedWithSpans (late chunking)")
final class OnnxEmbeddingEncoderLateChunkingTest {

  private static final int MAX_SEQ_LEN = 512;
  private static Path modelDir;
  private static EmbeddingAssembly assembly;
  private static OnnxEmbeddingEncoder encoder;

  @BeforeAll
  static void setUp() throws Exception {
    // Tempdoc 710 Move 6: shared walker (obs:spladebatchsweeptest).
    io.justsearch.ort.testing.ModelDirTestResolver.Discovery discovery =
        io.justsearch.ort.testing.ModelDirTestResolver.discover(
            "models/onnx/embedding",
            "JUSTSEARCH_EMBED_ONNX_MODEL_PATH",
            "model.onnx",
            "tokenizer.json");
    assumeTrue(discovery.modelDir() != null, discovery.missDescription());
    modelDir = discovery.modelDir();

    io.justsearch.ort.SessionHandle sessions =
        io.justsearch.ort.testing.InferenceCompositionRootTestHelper.cpuSessionFor(
            "embed-late-chunking-test", modelDir);
    // lateChunkingMaxSeqLen=0 falls back to MAX_SEQ_LEN — these tests exercise embedWithSpans'
    // limit-check semantics at the base maxSeqLen boundary, not the raised Phase-2 ceiling.
    assembly =
        OnnxEmbeddingEncoder.buildAssembly(sessions, modelDir, MAX_SEQ_LEN, 0, false);
    encoder = new OnnxEmbeddingEncoder(assembly.sessions(), assembly.shape(), assembly.tokenizer());
  }

  @AfterAll
  static void tearDown() {
    if (encoder != null) {
      encoder.close();
    }
  }

  @Test
  @DisplayName("embed() sanity: produces a unit-length vector")
  void embedSanity() throws Exception {
    var result = encoder.embed("Late chunking derives chunk vectors from one forward pass.");
    assertNotNull(result.vector());
    double norm = 0.0;
    for (float v : result.vector()) {
      norm += (double) v * v;
    }
    assertEquals(1.0, Math.sqrt(norm), 1e-4);
  }

  @Test
  @DisplayName("doc vector from embedWithSpans is bit-identical to embed()")
  void docVectorIsBitIdenticalToEmbed() throws Exception {
    String text = "The quick brown fox jumps over the lazy dog near the riverbank.";

    var plain = encoder.embed(text);
    var withSpans = encoder.embedWithSpans(text, new int[][] {{0, text.length()}});

    assertNotNull(withSpans, "short text must not return null");
    assertArrayEquals(
        plain.vector(),
        withSpans.vector(),
        "embedWithSpans doc vector must be bit-identical to embed() — same tokens, same ORT"
            + " pass, same pooling formula (runHidden extraction must be output-preserving)");
  }

  @Test
  @DisplayName("whole-content span matches normalized content-token mean from raw ORT output")
  void singleSpanCoveringWholeDocMatchesRawOrtContentMean() throws Exception {
    String text = "Machine learning models encode text into dense vector representations.";

    var encoding = assembly.tokenizer().encode(text);
    var offsets = encoding.getCharTokenSpans();
    long[] mask = encoding.getAttentionMask();
    var sessions = assembly.sessions();
    // Read the real model output directly. Document pooling may use CLS or include specials;
    // the span reference independently averages only content tokens with intersecting offsets.
    float[] expected;
    try (var ids = OnnxTensor.createTensor(sessions.environment(), new long[][] {encoding.getIds()});
        var attention = OnnxTensor.createTensor(sessions.environment(), new long[][] {mask});
        var types = assembly.shape().needsTokenTypeIds()
            ? OnnxTensor.createTensor(sessions.environment(), new long[][] {encoding.getTypeIds()})
            : null;
        var lease = sessions.acquireCpu(LocalSessionAcquisition.background())) {
      var inputs = new HashMap<String, OnnxTensor>();
      inputs.put("input_ids", ids);
      inputs.put("attention_mask", attention);
      if (types != null) {
        inputs.put("token_type_ids", types);
      }
      try (var result = lease.run(inputs)) {
        float[][] hidden = ((float[][][]) result.get(0).getValue())[0];
        assertEquals(mask.length, hidden.length);
        assertEquals(mask.length, offsets.length);
        double[] mean = new double[hidden[0].length];
        int count = 0;
        for (int token = 0; token < offsets.length; token++) {
          var offset = offsets[token];
          if (mask[token] == 1 && offset != null && offset.getEnd() > offset.getStart()
              && offset.getEnd() > 0 && offset.getStart() < text.length()) {
            count++;
            for (int d = 0; d < mean.length; d++) {
              mean[d] += hidden[token][d];
            }
          }
        }
        assertTrue(count > 0, "reference must select content tokens");
        assertTrue(count < hidden.length, "fixture must exclude special tokens");
        double normSquared = 0;
        for (int d = 0; d < mean.length; d++) {
          mean[d] /= count;
          normSquared += mean[d] * mean[d];
        }
        assertTrue(normSquared > 0, "reference mean must be nonzero");
        double norm = Math.sqrt(normSquared);
        expected = new float[mean.length];
        for (int d = 0; d < mean.length; d++) {
          expected[d] = (float) (mean[d] / norm);
        }
      }
    }
    var withSpans = encoder.embedWithSpans(text, new int[][] {{0, text.length()}});

    assertNotNull(withSpans);
    assertEquals(1, withSpans.chunkVectors().size());
    assertArrayEquals(
        expected,
        withSpans.chunkVectors().get(0),
        1e-4f,
        "whole-content span must match the normalized mean of raw ORT content-token states");
  }

  @Test
  @DisplayName("multi-span: each chunk vector is unit-length and chunks differ")
  void multiSpanChunkVectorsAreUnitLengthAndDistinct() throws Exception {
    String partA = "Quantum computing relies on superposition and entanglement of qubits.";
    String partB = " The recipe calls for two cups of flour and a pinch of salt.";
    String text = partA + partB;

    var withSpans =
        encoder.embedWithSpans(
            text, new int[][] {{0, partA.length()}, {partA.length(), text.length()}});

    assertNotNull(withSpans);
    assertEquals(2, withSpans.chunkVectors().size());

    for (float[] chunkVector : withSpans.chunkVectors()) {
      double norm = 0.0;
      for (float v : chunkVector) {
        norm += (double) v * v;
      }
      assertEquals(1.0, Math.sqrt(norm), 1e-4, "each chunk vector must be L2-normalized");
    }

    float[] chunk0 = withSpans.chunkVectors().get(0);
    float[] chunk1 = withSpans.chunkVectors().get(1);
    double dot = 0.0;
    for (int i = 0; i < chunk0.length; i++) {
      dot += (double) chunk0[i] * chunk1[i];
    }
    assertNotEquals(
        1.0, dot, 1e-6, "distinct-topic chunks should not collapse to identical vectors");
    assertTrue(
        dot < 0.999, "distinct-topic chunk vectors should differ, cosine=" + dot);
  }

  @Test
  @DisplayName("long doc (> maxSeqLen tokens) returns null")
  void longDocReturnsNull() throws Exception {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 400; i++) {
      sb.append("The field of artificial intelligence has evolved significantly over decades. ");
    }
    String longText = sb.toString();

    var result = encoder.embedWithSpans(longText, new int[][] {{0, longText.length()}});

    assertNull(result, "documents exceeding maxSeqLen tokens must return null (Phase-1 scope)");
  }
}
