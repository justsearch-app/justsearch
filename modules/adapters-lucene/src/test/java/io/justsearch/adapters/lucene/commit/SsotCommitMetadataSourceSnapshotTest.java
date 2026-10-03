/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.commit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class SsotCommitMetadataSourceSnapshotTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void capturedConfigRemainsCoherentWhileNoArgSourceTracksTheGlobalConfig() throws Exception {
    ResolvedConfig globalA = config(16, 100, false, 0.7, 0.2, "{\"title\":1.0}");
    ResolvedConfig suppliedB =
        config(48, 320, true, 1.2, 0.7, "{\"body\":2.0,\"title\":4.0}");
    ResolvedConfig globalC = config(24, 180, false, 1.6, 0.8, "{\"body\":7.0}");
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore installed = new ConfigStore(globalA);
    try {
      ConfigStore.setGlobal(installed);
      var lateBound = new SsotCommitMetadataSource();
      var fixed = new SsotCommitMetadataSource(suppliedB);

      Map<String, Object> lateA = lateBound.build();
      Map<String, Object> fixedB = fixed.build();
      assertMetadata(lateA, 16, 100, "float32");
      assertMetadata(fixedB, 48, 320, "int8_sq");
      assertNotEquals(lateA.get("similarity_fp"), fixedB.get("similarity_fp"));
      assertNotEquals(lateA.get("boosts_fp"), fixedB.get("boosts_fp"));

      installed = new ConfigStore(globalC);
      ConfigStore.setGlobal(installed);
      Map<String, Object> fixedAfterGlobalMutation = fixed.build();
      Map<String, Object> lateC = lateBound.build();

      assertEquals(
          fixedB.get(IndexFingerprint.COMMIT_META_INPUTS_KEY),
          fixedAfterGlobalMutation.get(IndexFingerprint.COMMIT_META_INPUTS_KEY));
      assertEquals(fixedB.get("similarity_fp"), fixedAfterGlobalMutation.get("similarity_fp"));
      assertEquals(fixedB.get("boosts_fp"), fixedAfterGlobalMutation.get("boosts_fp"));
      assertEquals(fixedB.get("vector_format"), fixedAfterGlobalMutation.get("vector_format"));
      assertMetadata(fixedAfterGlobalMutation, 48, 320, "int8_sq");

      assertMetadata(lateC, 24, 180, "float32");
      assertNotEquals(lateA.get(IndexFingerprint.COMMIT_META_INPUTS_KEY),
          lateC.get(IndexFingerprint.COMMIT_META_INPUTS_KEY));
      assertNotEquals(lateA.get("similarity_fp"), lateC.get("similarity_fp"));
      assertNotEquals(lateA.get("boosts_fp"), lateC.get("boosts_fp"));
      assertEquals(new SsotCommitMetadataSource().build(), lateC);
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void capturedConstructorRejectsNull() {
    assertThrows(NullPointerException.class, () -> new SsotCommitMetadataSource(null));
  }

  @Test
  void runtimeBoundSourcesKeepIndependentModelInputsWhileProvidersChange() throws Exception {
    IndexFingerprint.installEffectiveVectorDimension(() -> 768);
    IndexFingerprint.installModelFingerprintProviders(
        () -> IndexFingerprint.ModelFingerprint.present("a".repeat(64)),
        IndexFingerprint.ModelFingerprint::notConfigured,
        IndexFingerprint.ModelFingerprint::notConfigured);
    try {
      ResolvedConfig blueConfig = config(16, 100, false, 0.7, 0.2, "{\"title\":1.0}");
      ResolvedConfig greenConfig = config(48, 320, true, 1.2, 0.7, "{\"body\":2.0}");
      var blue =
          new SsotCommitMetadataSource(
              blueConfig,
              new SsotCommitMetadataSource.RuntimeFingerprintInputs(
                  768,
                  IndexFingerprint.ModelFingerprint.present("a".repeat(64)),
                  IndexFingerprint.ModelFingerprint.notConfigured(),
                  IndexFingerprint.ModelFingerprint.notConfigured()));
      var green =
          new SsotCommitMetadataSource(
              greenConfig,
              new SsotCommitMetadataSource.RuntimeFingerprintInputs(
                  1024,
                  IndexFingerprint.ModelFingerprint.present("b".repeat(64)),
                  IndexFingerprint.ModelFingerprint.present("c".repeat(64)),
                  IndexFingerprint.ModelFingerprint.notConfigured()));

      Map<String, Object> blueBeforeProviderChange = blue.build();
      Map<String, Object> greenBeforeProviderChange = green.build();

      IndexFingerprint.installEffectiveVectorDimension(() -> 384);
      IndexFingerprint.installModelFingerprintProviders(
          () -> IndexFingerprint.ModelFingerprint.present("d".repeat(64)),
          () -> IndexFingerprint.ModelFingerprint.present("e".repeat(64)),
          () -> IndexFingerprint.ModelFingerprint.present("f".repeat(64)));

      Map<String, Object> blueAfterProviderChange = blue.build();
      Map<String, Object> greenAfterProviderChange = green.build();
      assertEquals(blueBeforeProviderChange, blueAfterProviderChange);
      assertEquals(greenBeforeProviderChange, greenAfterProviderChange);
      assertNotEquals(
          blueBeforeProviderChange.get(IndexFingerprint.COMMIT_META_KEY),
          greenBeforeProviderChange.get(IndexFingerprint.COMMIT_META_KEY));

      JsonNode blueInputs =
          MAPPER.readTree(
              (String) blueBeforeProviderChange.get(IndexFingerprint.COMMIT_META_INPUTS_KEY));
      JsonNode greenInputs =
          MAPPER.readTree(
              (String) greenBeforeProviderChange.get(IndexFingerprint.COMMIT_META_INPUTS_KEY));
      assertEquals(768, blueInputs.path("fields").findValue("dimension").asInt());
      assertEquals("a".repeat(64), blueInputs.path("embedding_model_sha256").asText());
      assertEquals(1024, greenInputs.path("fields").findValue("dimension").asInt());
      assertEquals("b".repeat(64), greenInputs.path("embedding_model_sha256").asText());
      assertEquals("c".repeat(64), greenInputs.path("splade_model_sha256").asText());

      JsonNode legacyInputs =
          MAPPER.readTree(
              (String)
                  new SsotCommitMetadataSource(blueConfig)
                      .build()
                      .get(IndexFingerprint.COMMIT_META_INPUTS_KEY));
      assertEquals(384, legacyInputs.path("fields").findValue("dimension").asInt());
      assertEquals("d".repeat(64), legacyInputs.path("embedding_model_sha256").asText());
      assertEquals("e".repeat(64), legacyInputs.path("splade_model_sha256").asText());
      assertEquals("f".repeat(64), legacyInputs.path("ner_model_sha256").asText());
    } finally {
      IndexFingerprint.resetModelFingerprintProviders();
    }
  }

  private static ResolvedConfig config(
      int hnswM,
      int efConstruction,
      boolean quantized,
      double k1,
      double b,
      String boostsJson) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("index.vector.hnsw.m", Integer.toString(hnswM));
    values.put("index.vector.hnsw.ef_construction", Integer.toString(efConstruction));
    values.put("index.vector.quantization.enabled", Boolean.toString(quantized));
    values.put("index.similarity.text.k1", Double.toString(k1));
    values.put("index.similarity.text.b", Double.toString(b));
    values.put("index.boosts", boostsJson);
    var builder = ResolvedConfig.builder();
    values.forEach(builder::putDefault);
    return builder.build();
  }

  private static void assertMetadata(
      Map<String, Object> metadata, int hnswM, int efConstruction, String vectorFormat)
      throws Exception {
    assertEquals(vectorFormat, metadata.get("vector_format"));
    JsonNode inputs = MAPPER.readTree(
        (String) metadata.get(IndexFingerprint.COMMIT_META_INPUTS_KEY));
    assertEquals(vectorFormat, inputs.path("vector_format").asText());
    assertEquals(hnswM, inputs.path("hnsw").path("m").asInt());
    assertEquals(
        efConstruction, inputs.path("hnsw").path("ef_construction").asInt());
  }
}
