package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ExtractionSandboxFactoryTest {

  @Test
  void inProcessFactoryReturnsTimeboxedExtractorWithDefaultPolicy() {
    try (TimeboxedContentExtractor extractor =
        ExtractionSandboxFactory.inProcessStructured(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(), io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), null)) {
      assertNotNull(extractor);
      assertEquals(
          TikaExtractionPolicy.defaults().policyId(), extractor.extractionPolicy().policyId());
    }
  }

  @Test
  void processFactoryBuildsSandboxWithProvidedCommandAndPolicy() {
    TikaExtractionPolicy policy =
        new TikaExtractionPolicy(
            "process-mode-test",
            1024,
            1024,
            1024,
            128,
            128,
            4096,
            0,
            0,
            100.0d,
            true,
            java.util.Set.of(),
            java.util.Set.of());
    List<String> command = List.of("java", "-cp", "ignored", "ExtractionSandboxChild");

    try (TimeboxedContentExtractor extractor =
        ExtractionSandboxFactory.create(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(), io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), io.justsearch.indexerworker.TestWorkerExecutorRegistrations.readers(),
            ExtractionSandboxFactory.Mode.PROCESS,
            policy,
            Duration.ofSeconds(5),
            null,
            command)) {
      assertNotNull(extractor);
      assertEquals("process-mode-test", extractor.extractionPolicy().policyId());
    }
  }
}
