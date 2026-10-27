package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ExtractionSandboxFactoryTest {
  @TempDir Path tempDir;

  @Test
  void autoFactoryReportsConfiguredContentLimitAndValidatesArtifact() throws Exception {
    assertFactoryPolicy(ExtractionSandboxFactory.Mode.AUTO, contentPolicy());
  }

  @Test
  void autoFactoryReportsConfiguredFileLimitAndValidatesArtifact() throws Exception {
    assertFactoryPolicy(ExtractionSandboxFactory.Mode.AUTO, filePolicy());
  }

  @Test
  void inProcessFactoryReportsConfiguredContentLimitAndValidatesArtifact() throws Exception {
    assertFactoryPolicy(ExtractionSandboxFactory.Mode.IN_PROCESS, contentPolicy());
  }

  @Test
  void inProcessFactoryReportsConfiguredFileLimitAndValidatesArtifact() throws Exception {
    assertFactoryPolicy(ExtractionSandboxFactory.Mode.IN_PROCESS, filePolicy());
  }

  @Test
  void structuredFactoryReportsConfiguredContentLimitAndValidatesArtifact() throws Exception {
    assertStructuredPolicy(contentPolicy());
  }

  @Test
  void structuredFactoryReportsConfiguredFileLimitAndValidatesArtifact() throws Exception {
    assertStructuredPolicy(filePolicy());
  }

  @Test
  void publicConstructorsReportRegistryContentLimitAndValidateArtifact() throws Exception {
    assertPublicConstructorPolicies(contentPolicy());
  }

  @Test
  void publicConstructorsReportRegistryFileLimitAndValidateArtifact() throws Exception {
    assertPublicConstructorPolicies(filePolicy());
  }

  @Test
  void configuredContentLimitTruncatesAndValidatesAgainstReportedPolicy() throws Exception {
    TikaExtractionPolicy policy = contentPolicy();
    String prefix = "kept ".repeat(40);
    Path file = Files.writeString(tempDir.resolve("long.txt"), prefix + "beyondcut".repeat(40));
    try (TimeboxedContentExtractor extractor = factory(ExtractionSandboxFactory.Mode.AUTO, policy)) {
      ExtractionArtifact artifact = assertDoesNotThrow(() -> extractor.extractArtifact(file));
      assertTrue(artifact.truncated());
      assertEquals(ExtractionStatus.SUCCESS_PARTIAL, artifact.status());
      assertTrue(artifact.result().content().length() <= 128);
      assertTrue(artifact.result().content().startsWith("kept "));
      assertFalse(artifact.result().content().contains("beyondcut"));
      assertDoesNotThrow(() -> artifact.validate(extractor.extractionPolicy(), null));
      assertEquals(policy, extractor.extractionPolicy());
    }
  }

  @Test
  void defaultCompositionsReportDefaultPolicyAndValidateArtifacts() throws Exception {
    TikaExtractionPolicy defaults = TikaExtractionPolicy.defaults();
    assertFactoryPolicy(ExtractionSandboxFactory.Mode.AUTO, defaults);
    assertFactoryPolicy(ExtractionSandboxFactory.Mode.IN_PROCESS, defaults);
    assertStructuredPolicy(defaults);
    assertPublicConstructorPolicies(defaults);
  }

  @Test
  void directTikaDelegateReportsConfiguredPolicyAndValidatesArtifact() throws Exception {
    TikaExtractionPolicy policy = contentPolicy();
    try (TimeboxedContentExtractor extractor =
        new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), tika(policy))) {
      assertPolicyAndArtifact(extractor, policy);
    }
  }

  @Test
  void ordinaryProvidersReportDefaultPolicyAndValidateArtifacts() throws Exception {
    ContentExtractorProvider provider = new ContentExtractorProvider() {
      @Override
      public ContentExtractor.ExtractionResult extract(Path file) {
        return new ContentExtractor.ExtractionResult("searchable sentinel", null, "text/plain");
      }

      @Override
      public String detectMimeType(Path file) {
        return "text/plain";
      }
    };
    try (TimeboxedContentExtractor direct =
            new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), provider);
        TimeboxedContentExtractor registry =
            new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
                ExtractorContributionRegistry.withCoreTika(provider))) {
      assertPolicyAndArtifact(direct, TikaExtractionPolicy.defaults());
      assertPolicyAndArtifact(registry, TikaExtractionPolicy.defaults());
    }
  }

  @Test
  void mismatchedArtifactPolicyIsStillRejected() {
    ExtractionArtifact artifact = ExtractionArtifact.full(
        new ContentExtractor.ExtractionResult("searchable sentinel", null, "text/plain"),
        contentPolicy(), "test-parser", false);
    ContentExtractor.ExtractionException failure = assertThrows(
        ContentExtractor.ExtractionException.class,
        () -> artifact.validate(TikaExtractionPolicy.defaults(), null));
    assertEquals("Extraction artifact policy id does not match expected policy", failure.getMessage());
  }

  private static TikaExtractionPolicy contentPolicy() {
    return TikaExtractionPolicy.fromWorkerLimits(new ResolvedConfig.Worker(
        128, TikaExtractionPolicy.DEFAULT_MAX_INPUT_BYTES));
  }

  private static TikaExtractionPolicy filePolicy() {
    return TikaExtractionPolicy.fromWorkerLimits(new ResolvedConfig.Worker(
        TikaExtractionPolicy.DEFAULT_MAX_EXTRACTED_CHARS, 4096));
  }

  private static TimeboxedContentExtractor factory(
      ExtractionSandboxFactory.Mode mode, TikaExtractionPolicy policy) {
    // Text stays in process in AUTO; this command must never be needed by these checks.
    return ExtractionSandboxFactory.create(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(),
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), io.justsearch.indexerworker.TestWorkerExecutorRegistrations.readers(),
        mode, policy, Duration.ofSeconds(5), null, List.of("unused-extraction-child"));
  }

  private void assertFactoryPolicy(ExtractionSandboxFactory.Mode mode, TikaExtractionPolicy policy)
      throws Exception {
    try (TimeboxedContentExtractor extractor = factory(mode, policy)) {
      assertPolicyAndArtifact(extractor, policy);
    }
  }

  private void assertStructuredPolicy(TikaExtractionPolicy policy) throws Exception {
    try (TimeboxedContentExtractor extractor = ExtractionSandboxFactory.inProcessStructured(
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(), io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), null,
        OcrRoutingConfig.disabled(), OcrMetricCatalog.noop(), policy)) {
      assertPolicyAndArtifact(extractor, policy);
    }
  }

  private void assertPublicConstructorPolicies(TikaExtractionPolicy policy) throws Exception {
    try (TimeboxedContentExtractor defaultTimeout = new TimeboxedContentExtractor(
            io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
            ExtractorContributionRegistry.withCoreTika(tika(policy)));
        TimeboxedContentExtractor explicitTimeout = new TimeboxedContentExtractor(
            io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
            ExtractorContributionRegistry.withCoreTika(tika(policy)), Duration.ofSeconds(5), null)) {
      assertPolicyAndArtifact(defaultTimeout, policy);
      assertPolicyAndArtifact(explicitTimeout, policy);
    }
  }

  private static PolicyDrivenTikaExtractor tika(TikaExtractionPolicy policy) {
    return new PolicyDrivenTikaExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocrFactory(), policy);
  }

  private void assertPolicyAndArtifact(TimeboxedContentExtractor extractor, TikaExtractionPolicy policy)
      throws Exception {
    Path file = Files.writeString(tempDir.resolve("small.txt"), "searchable sentinel");
    assertEquals(policy, extractor.extractionPolicy());
    ExtractionArtifact artifact = assertDoesNotThrow(() -> extractor.extractArtifact(file));
    assertTrue(artifact.result().content().contains("searchable sentinel"));
    assertEquals(policy.policyId(), artifact.policyId());
    assertDoesNotThrow(() -> artifact.validate(extractor.extractionPolicy(), null));
  }

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
