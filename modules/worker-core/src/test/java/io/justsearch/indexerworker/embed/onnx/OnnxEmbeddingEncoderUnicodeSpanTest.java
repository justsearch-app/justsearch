/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed.onnx;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.onnxruntime.OnnxTensor;
import io.justsearch.indexerworker.embed.EmbeddingConfig;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.inference.LocalSessionAcquisition;
import java.nio.file.Path;
import java.util.ArrayList;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Raw hidden-state oracles identify source words by token ID, never by native character offsets. */
final class OnnxEmbeddingEncoderUnicodeSpanTest {
  private static EmbeddingAssembly assembly;
  private static OnnxEmbeddingEncoder encoder;
  private static int blueId;
  private static int greenId;
  private static Path modelDir;
  private static final List<EmbeddingService> services = new ArrayList<>();

  @BeforeAll
  static void setUp() throws Exception {
    var discovery = io.justsearch.ort.testing.ModelDirTestResolver.discover(
        "models/onnx/embedding", "JUSTSEARCH_EMBED_ONNX_MODEL_PATH", "model.onnx", "tokenizer.json");
    assumeTrue(discovery.modelDir() != null, discovery.missDescription());
    modelDir = discovery.modelDir();
    var sessions = io.justsearch.ort.testing.InferenceCompositionRootTestHelper.cpuSessionFor(
        "unicode-span-test", modelDir);
    assembly = OnnxEmbeddingEncoder.buildAssembly(sessions, modelDir, 512, 0, false);
    encoder = new OnnxEmbeddingEncoder(assembly);
    // IDs independently read from these fixtures' tokenizer vocabularies. The tests assert each
    // ID occurs once per source word before using its hidden-state row in the raw ORT oracle.
    boolean cls = assembly.shape().poolingStrategy() == OnnxEmbeddingEncoder.PoolingStrategy.CLS;
    blueId = cls ? 57571 : 2630;
    greenId = cls ? 50997 : 2665;
    System.out.println("Unicode span fixture=" + modelDir + " pooling=" + assembly.shape().poolingStrategy()
        + " provider=CPU (cpuSessionFor), blueId=" + blueId + ", greenId=" + greenId);
  }

  @AfterAll
  static void tearDown() {
    for (var service : services) {
      service.close();
    }
    if (assembly != null) {
      assembly.close();
    }
  }

  @Test
  void supplementaryAndNormalizedTextSelectsKnownTokenSubsets() throws Exception {
    for (String text : List.of("red 😀 blue green", "red 😀😀𠀀 blue green",
        "red é é ﬁ blue green", "😀𠀀 prefix: red blue green")) {
      assertWordSpans(text);
    }
  }

  @Test
  void asciiAndBmpPreserveKnownTokenSubsets() throws Exception {
    for (String text : List.of("red blue green", "red é 中 blue green", "red � blue green")) {
      assertWordSpans(text);
    }
  }

  private static void assertWordSpans(String text) throws Exception {
    var encoding = assembly.tokenizer().encode(text);
    float[][] hidden = rawHidden(encoding);
    int blue = uniqueToken(encoding, blueId);
    int green = uniqueToken(encoding, greenId);
    int start = text.indexOf("blue");
    int end = text.indexOf("green") + "green".length();
    int[][] spans = {{start, start + 4}, {start + 1, start + 3}, {start, end},
        {start, start + 4}, {end - 5, end}, {start + 3, start + 4}};
    var actual = encoder.embedWithSpans(text, spans);
    assertNotNull(actual);
    assertEquals(spans.length, actual.chunkCount());
    assertEquals(spans.length, actual.chunkVectors().size());
    for (int row : new int[] {0, 1, 3, 5}) {
      assertArrayEquals(mean(hidden, blue), actual.chunkVectors().get(row), 1e-4f, text + " row=" + row);
    }
    assertArrayEquals(mean(hidden, blue, green), actual.chunkVectors().get(2), 1e-4f, text);
    assertArrayEquals(mean(hidden, green), actual.chunkVectors().get(4), 1e-4f, text);
    assertArrayEquals(encoder.embed(text).vector(), actual.vector());
    assertArrayEquals(new int[] {start, start + 4}, spans[0]);
    var empty = encoder.embedWithSpans(text, new int[0][]);
    assertNotNull(empty);
    assertEquals(0, empty.chunkCount());
    assertTrue(empty.chunkVectors().isEmpty());
    assertArrayEquals(actual.vector(), empty.vector());
  }

  @Test
  void repeatedWordsUseTheirOwnContextualTokenRows() throws Exception {
    String text = "red 😀 blue green blue";
    var encoding = assembly.tokenizer().encode(text);
    float[][] hidden = rawHidden(encoding);
    List<Integer> blues = tokenRows(encoding, blueId);
    assertEquals(2, blues.size());
    int first = text.indexOf("blue");
    int second = text.lastIndexOf("blue");
    var actual = encoder.embedWithSpans(text,
        new int[][] {{second, second + 4}, {first, first + 4}, {second, second + 4}});
    assertNotNull(actual);
    assertEquals(3, actual.chunkCount());
    assertArrayEquals(mean(hidden, blues.get(1)), actual.chunkVectors().get(0), 1e-4f);
    assertArrayEquals(mean(hidden, blues.get(0)), actual.chunkVectors().get(1), 1e-4f);
    assertArrayEquals(actual.chunkVectors().get(0), actual.chunkVectors().get(2));
  }

  @Test
  void emptyAndWhitespaceSpansUseExactSubstringFallback() throws Exception {
    String text = "red 😀 blue";
    int inside = text.indexOf("blue") + 2;
    int[][] spans = {{0, 0}, {inside, inside}, {text.length(), text.length()}, {3, 4}};
    var actual = encoder.embedWithSpans(text, spans);
    assertNotNull(actual);
    assertEquals(spans.length, actual.chunkVectors().size());
    for (int i = 0; i < spans.length; i++) {
      assertArrayEquals(encoder.embed(text.substring(spans[i][0], spans[i][1])).vector(),
          actual.chunkVectors().get(i), 1e-4f, "exact substring fallback row=" + i);
    }
    // Few tokens, many source characters: conversion must not allocate a full-text offset map.
    String whitespaceHeavy = "blue" + " ".repeat(100_000) + "green";
    var whitespace = encoder.embedWithSpans(whitespaceHeavy, new int[][] {{50_000, 50_001}});
    assertNotNull(whitespace);
    assertArrayEquals(encoder.embed(" ").vector(), whitespace.chunkVectors().get(0), 1e-4f);
    var emptyContent = encoder.embedWithSpans("", new int[][] {{0, 0}});
    assertNotNull(emptyContent);
    assertArrayEquals(encoder.embed("").vector(), emptyContent.chunkVectors().get(0), 1e-4f);
  }

  @Test
  void actualServicePrefixesKeepSpanOracleAndUnprefixedFallback() throws Exception {
    String content = "red 😀 blue green";
    int start = content.indexOf("blue");
    var config = new EmbeddingConfig(true, modelDir, "onnx", false, 0, 0, 512, true, 512);
    for (String prefix : List.of("", "search_document: ", "😀𠀀 ")) {
      // All facades share this test's assembly; retire them together after the last inference.
      var service = EmbeddingService.createWithBackend(
          new OnnxEmbeddingBackend(new OnnxEmbeddingEncoder(assembly), 0, 512), config, null, prefix, "");
      services.add(service);
      {
        int[][] spans = {{start, start + 4}, {start + 2, start + 2}};
        var actual = service.embedWithSpans(content, spans);
        assertNotNull(actual);
        var encoding = assembly.tokenizer().encode(prefix + content);
        assertArrayEquals(mean(rawHidden(encoding), uniqueToken(encoding, blueId)),
            actual.chunkVectors().get(0), 1e-4f, prefix);
        assertArrayEquals(encoder.embed("").vector(), actual.chunkVectors().get(1), 1e-4f, prefix);
        assertArrayEquals(encoder.embed(prefix + content).vector(), actual.primaryVector());
        assertArrayEquals(new int[] {start, start + 4}, spans[0]);
      }
    }
  }

  @Test
  void malformedOriginalAndPrefixRejectRequestedSpansBeforeNativeCoordinateWork() throws Exception {
    assertAll(
        () -> assertMalformedSpanRequests((char) 0xd83d),
        () -> assertMalformedSpanRequests((char) 0xde00),
        () -> {
          // Direct input has no prefix provenance. The joined text is well formed, while the
          // service must refuse the malformed original even though its prefix completes a pair.
          String original = String.valueOf((char) 0xde00) + " blue green";
          var service = service(String.valueOf((char) 0xd83d));
          assertAll(
              () -> assertNull(service.embedWithSpans(original, new int[][] {{2, 6}})),
              () -> assertNotNull(encoder.embedWithSpans("😀 blue green", new int[][] {{3, 7}})));
        });
  }

  private static void assertMalformedSpanRequests(char surrogate) throws Exception {
    String text = "red " + surrogate + " blue green";
    int blue = text.indexOf("blue");
    var originalService = service("");
    var prefixedService = service("red " + surrogate + " ");
    // Execute every control on the baseline too; a first rejection failure must not hide the
    // other surrogate, the zero-width row, or the independently malformed prefix.
    assertAll("surrogate=" + Integer.toHexString(surrogate),
        () -> assertThrows(IllegalArgumentException.class,
            () -> encoder.embedWithSpans(text, new int[][] {{blue, blue + 4}})),
        () -> assertThrows(IllegalArgumentException.class,
            () -> encoder.embedWithSpans(text, new int[][] {{blue, blue}})),
        () -> assertNull(assertDoesNotThrow(
            () -> originalService.embedWithSpans(text, new int[][] {{blue, blue + 4}}))),
        () -> assertNull(assertDoesNotThrow(
            () -> prefixedService.embedWithSpans("blue green", new int[][] {{0, 4}}))));
  }

  @Test
  void malformedVectorOnlyRetainsDocumentResultsBeforeAndAfterRepair() throws Exception {
    for (char surrogate : new char[] {(char) 0xd83d, (char) 0xde00}) {
      String text = "red " + surrogate + " blue green";
      var actual = encoder.embedWithSpans(text, new int[0][]);
      assertNotNull(actual);
      assertArrayEquals(encoder.embed(text).vector(), actual.vector());
      assertEquals(0, actual.chunkCount());
      assertTrue(actual.chunkVectors().isEmpty());
      System.out.println("VECTOR_ONLY direct surrogate=" + Integer.toHexString(surrogate)
          + " sha256=" + vectorDigest(actual.vector()));
      for (String prefix : List.of("", "red " + surrogate + " ")) {
        String content = prefix.isEmpty() ? text : "blue green";
        var fromService = service(prefix).embedWithSpans(content, new int[0][]);
        assertNotNull(fromService);
        assertArrayEquals(encoder.embed(prefix + content).vector(), fromService.primaryVector());
        // ChunkedEmbedding retains one logical document even when no chunk vectors were requested.
        assertEquals(1, fromService.chunkCount());
        assertTrue(fromService.chunkVectors().isEmpty());
        System.out.println("VECTOR_ONLY service surrogate=" + Integer.toHexString(surrogate)
            + " malformedPrefix=" + !prefix.isEmpty()
            + " sha256=" + vectorDigest(fromService.primaryVector()));
      }
    }
  }

  private static String vectorDigest(float[] vector) throws Exception {
    ByteBuffer bytes = ByteBuffer.allocate(vector.length * Float.BYTES);
    for (float value : vector) {
      bytes.putFloat(value);
    }
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
  }

  private static EmbeddingService service(String prefix) {
    var config = new EmbeddingConfig(true, modelDir, "onnx", false, 0, 0, 512, true, 512);
    var service = EmbeddingService.createWithBackend(
        new OnnxEmbeddingBackend(new OnnxEmbeddingEncoder(assembly), 0, 512), config, null, prefix, "");
    services.add(service);
    return service;
  }

  private static List<Integer> tokenRows(Encoding encoding, int id) {
    List<Integer> rows = new ArrayList<>();
    long[] ids = encoding.getIds();
    for (int i = 0; i < ids.length; i++) {
      if (ids[i] == id) {
        rows.add(i);
      }
    }
    return rows;
  }

  private static int uniqueToken(Encoding encoding, int id) {
    var rows = tokenRows(encoding, id);
    assertEquals(1, rows.size(), "fixture must uniquely identify token ID " + id);
    return rows.getFirst();
  }

  private static float[][] rawHidden(Encoding encoding) throws Exception {
    var sessions = assembly.sessions();
    try (var ids = OnnxTensor.createTensor(sessions.environment(), new long[][] {encoding.getIds()});
        var mask = OnnxTensor.createTensor(sessions.environment(), new long[][] {encoding.getAttentionMask()});
        var types = assembly.shape().needsTokenTypeIds()
            ? OnnxTensor.createTensor(sessions.environment(), new long[][] {encoding.getTypeIds()}) : null;
        var lease = sessions.acquireCpu(LocalSessionAcquisition.background())) {
      assertTrue(lease.isCpu());
      var inputs = new HashMap<String, OnnxTensor>();
      inputs.put("input_ids", ids);
      inputs.put("attention_mask", mask);
      if (types != null) {
        inputs.put("token_type_ids", types);
      }
      try (var result = lease.run(inputs)) {
        return ((float[][][]) result.get(0).getValue())[0];
      }
    }
  }

  private static float[] mean(float[][] hidden, int... tokens) {
    double[] values = new double[hidden[0].length];
    for (int token : tokens) {
      for (int d = 0; d < values.length; d++) {
        values[d] += hidden[token][d] / (double) tokens.length;
      }
    }
    double squared = 0;
    for (double value : values) {
      squared += value * value;
    }
    assertTrue(squared > 0);
    float[] normalized = new float[values.length];
    for (int d = 0; d < values.length; d++) {
      normalized[d] = (float) (values[d] / Math.sqrt(squared));
    }
    return normalized;
  }
}
