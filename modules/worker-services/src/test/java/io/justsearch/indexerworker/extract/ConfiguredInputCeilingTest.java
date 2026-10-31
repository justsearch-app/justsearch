/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class ConfiguredInputCeilingTest {
  @TempDir Path tempDir;

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"structured", "flat", "child"})
  @Timeout(120)
  void configuredCeilingAbove100MiBAppliesToStructuredFlatFallbackAndChild(String mode) throws Exception {
    long size = 100L * 1024 * 1024 + 1;
    Path source = tempDir.resolve("large.txt");
    byte[] block = new byte[8192];
    Arrays.fill(block, (byte) 'x');
    try (var out = Files.newOutputStream(source)) {
      out.write("UniqueLargeText ".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      for (long remaining = size - 16; remaining > 0; remaining -= block.length) {
        out.write(block, 0, (int) Math.min(remaining, block.length));
      }
    }
    assertEquals(size, Files.size(source));
    var policy = TikaExtractionPolicy.fromWorkerLimits(new ResolvedConfig.Worker(128, size));
    if (!mode.equals("child")) {
      boolean flatFallback = mode.equals("flat");
      try (var extractor = new StructuredContentExtractor(128, policy)) {
        if (flatFallback) {
          var parser = org.mockito.Mockito.mock(org.apache.tika.parser.AutoDetectParser.class);
          org.mockito.Mockito.doThrow(new org.xml.sax.SAXException("force flat fallback"))
              .when(parser).parse(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                  org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
          var field = StructuredContentExtractor.class.getDeclaredField("parser");
          field.setAccessible(true);
          field.set(extractor, parser);
        }
        var result = extractor.extractWithStatus(source);
        assertTrue(result.result().content().startsWith("UniqueLargeText "));
        assertTrue(result.result().content().length() <= 128);
        assertTrue(result.truncated());
      }
    }
    if (mode.equals("child")) {
      try (var extractor = ExtractionSandboxFactory.create(
          io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(),
          io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
          io.justsearch.indexerworker.TestWorkerExecutorRegistrations.readers(),
          ExtractionSandboxFactory.Mode.PROCESS, policy, Duration.ofSeconds(60), null,
          PersistentExtractionSandboxTest.javaCommand(ExtractionSandboxChild.class))) {
        var result = extractor.extractArtifact(source);
        assertTrue(result.result().content().startsWith("UniqueLargeText "));
        assertEquals(ExtractionStatus.SUCCESS_PARTIAL, result.status());
      }
    }
    for (var refused : java.util.List.of(TikaExtractionPolicy.defaults(),
        TikaExtractionPolicy.fromWorkerLimits(new ResolvedConfig.Worker(128, size - 1)))) {
      try (var extractor = new StructuredContentExtractor(128, refused)) {
        assertEquals("INPUT_TOO_LARGE", assertThrows(ContentExtractor.BudgetExceededException.class,
            () -> extractor.extract(source)).reasonCode());
      }
    }
  }
}
