/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class OcrDefaultLanguageTest {
  @TempDir Path tempDir;

  @AfterEach
  void clear() {
    System.clearProperty("justsearch.tesseract.path");
    System.clearProperty("justsearch.tessdata.path");
    TikaOcrRuntime.resetLanguageCacheForTests();
  }

  @Test
  void defaultOrderIsGermanThenEnglishIncludingAfterSandboxSerializationAndWorkerLimit() throws Exception {
    var defaults = OcrRoutingConfig.from(new io.justsearch.configuration.resolved.ResolvedConfig.Ocr(
        null, null, null, null, null, null, null, null)).withWorkerLimit(1);
    var mapper = JsonMapper.builder().build();
    var restored = mapper.readValue(mapper.writeValueAsBytes(defaults), OcrRoutingConfig.class);
    assertEquals("deu+eng", restored.tikaLanguage());
    fakeRuntime();
    assertEquals("", TikaOcrRuntime.blockedReason(restored), "implicit defaults survive the sandbox wire");
  }

  @Test
  void missingDefaultGermanAndOrientationStillOcrWithEnglishAndPersistEvidence() throws Exception {
    fakeRuntime();
    var defaults = OcrRoutingConfig.defaults();
    assertEquals("", TikaOcrRuntime.blockedReason(defaults));
    Path image = tempDir.resolve("scan.png");
    var pixels = new java.awt.image.BufferedImage(120, 60, java.awt.image.BufferedImage.TYPE_INT_RGB);
    javax.imageio.ImageIO.write(pixels, "png", image.toFile());
    try (var extractor = new PolicyDrivenTikaExtractor(Executors::newFixedThreadPool,
        TikaExtractionPolicy.defaults(), defaults)) {
      var result = extractor.extractArtifact(image);
      assertTrue(result.result().content().contains("Readable German fallback"), result.toString());
      var evidence = JsonMapper.builder().build().readTree(result.visualExtractionEvidenceJson());
      assertEquals("eng", evidence.get("ocrLanguage").asString());
      assertEquals("deu", evidence.get("ocrMissingDefaultLanguages").get(0).asString());
      assertFalse(evidence.get("ocrOrientationDetection").asBoolean());
    }
  }

  @Test
  void explicitMissingGermanStillBlocksIncludingAfterSandboxSerialization() throws Exception {
    fakeRuntime();
    var explicit = new OcrRoutingConfig(true, List.of("deu", "eng"), 5_000, 1, 4096, 40_000_000, 72, 1);
    var mapper = JsonMapper.builder().build();
    var restored = mapper.readValue(mapper.writeValueAsBytes(explicit.withWorkerLimit(1)), OcrRoutingConfig.class);
    assertEquals(TikaOcrRuntime.REASON_LANGUAGE_MISSING, TikaOcrRuntime.blockedReason(restored));
    assertEquals("", TikaOcrRuntime.blockedReason(new OcrRoutingConfig(true, List.of("eng"), 5_000, 1, 4096, 40_000_000, 72, 1)));
    assertEquals(TikaOcrRuntime.REASON_DISABLED, TikaOcrRuntime.blockedReason(OcrRoutingConfig.disabled()));
  }

  private void fakeRuntime() throws Exception {
    Path data = Files.createDirectories(tempDir.resolve("runtime/tessdata"));
    Files.writeString(data.resolve("eng.traineddata"), "fixture");
    Path executable = NativeTesseractFixture.write(data.getParent(), "Readable German fallback evidence text " .repeat(8), true);
    System.setProperty("justsearch.tesseract.path", executable.toString());
    System.setProperty("justsearch.tessdata.path", data.toString());
    TikaOcrRuntime.resetLanguageCacheForTests();
  }
}
