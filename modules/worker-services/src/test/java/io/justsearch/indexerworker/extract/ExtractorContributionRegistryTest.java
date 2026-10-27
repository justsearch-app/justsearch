package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.extract.ExtractorContributionRegistry.ExtractorContribution;
import io.justsearch.indexerworker.extract.ExtractorContributionRegistry.ExtractorTrust;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies the Worker-side extractor composer (tempdoc 560 §4.4/§6) — composition, dispatch, lifecycle, trust. */
class ExtractorContributionRegistryTest {
  @TempDir Path tempDir;

  /** A stub provider that tags its result with its own name, so dispatch can be asserted. */
  private static ContentExtractorProvider provider(String tag) {
    return new ContentExtractorProvider() {
      @Override
      public ExtractionResult extract(Path file) {
        return new ExtractionResult(tag, tag, "text/plain");
      }

      @Override
      public String detectMimeType(Path file) {
        return "mime/" + tag;
      }
    };
  }

  @Test
  void withCoreTikaDispatchesEveryFileToTheCatchAll() throws Exception {
    ExtractorContributionRegistry registry = ExtractorContributionRegistry.withCoreTika(provider("tika"));
    assertEquals(java.util.List.of("core.tika"), registry.ids());
    // Behavior preservation: every file routes to the single catch-all.
    assertEquals("tika", registry.extract(Path.of("a.pdf")).content());
    assertEquals("tika", registry.extract(Path.of("b.unknown")).content());
    assertEquals("mime/tika", registry.detectMimeType(Path.of("c.docx")));
  }

  @Test
  void dispatchPicksTheFirstMatchingContributionInInstallOrder() throws Exception {
    ExtractorContributionRegistry registry = new ExtractorContributionRegistry();
    // A specific .md handler installed before the catch-all wins for .md files.
    registry.install(
        new ExtractorContribution(
            "vendor.md",
            ExtractorTrust.TRUSTED,
            f -> f.toString().endsWith(".md"),
            provider("md")));
    registry.install(ExtractorContribution.catchAll("core.tika", ExtractorTrust.CORE, provider("tika")));

    assertEquals("md", registry.extract(Path.of("notes.md")).content());
    assertEquals("tika", registry.extract(Path.of("notes.pdf")).content());
  }

  @Test
  void ordinaryContributionUsesConfiguredRegistryPolicyAndValidatesArtifact() throws Exception {
    TikaExtractionPolicy policy = configuredPolicy();
    String markdown = "markdown sentinel ".repeat(20);
    try (ExtractorContributionRegistry registry = new ExtractorContributionRegistry()) {
      registry.install(
          new ExtractorContribution(
              "vendor.md",
              ExtractorTrust.TRUSTED,
              file -> file.toString().endsWith(".md"),
              provider(markdown)));
      registry.install(ExtractorContribution.catchAll(
          "core.tika", ExtractorTrust.CORE,
          new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(), policy)));

      assertEquals(policy, registry.policy());
      ExtractionArtifact artifact = registry.extractArtifact(Path.of("notes.md"));
      assertEquals(policy.policyId(), artifact.policyId());
      assertEquals(markdown.substring(0, policy.maxExtractedChars()), artifact.result().content());
      assertTrue(artifact.truncated());
      assertEquals(ExtractionStatus.SUCCESS_PARTIAL, artifact.status());
      assertDoesNotThrow(() -> artifact.validate(registry.policy(), null));
    }
  }

  @Test
  void closeRetainsConfiguredCoreTikaPolicy() {
    TikaExtractionPolicy policy = configuredPolicy();
    ExtractorContributionRegistry registry = ExtractorContributionRegistry.withCoreTika(
        new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(), policy));
    assertEquals(policy, registry.policy());

    registry.close();

    assertTrue(registry.ids().isEmpty());
    assertEquals(policy, registry.policy());
  }

  @Test
  void uninstallCoreTikaRestoresDefaultPolicy() {
    TikaExtractionPolicy policy = configuredPolicy();
    try (PolicyDrivenTikaExtractor tika =
            new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(), policy);
        ExtractorContributionRegistry registry = ExtractorContributionRegistry.withCoreTika(tika)) {
      assertEquals(policy, registry.policy());
      assertTrue(registry.uninstall("core.tika"));
      assertEquals(TikaExtractionPolicy.defaults(), registry.policy());
    }
  }

  @Test
  void installRejectsConfiguredCoreAfterDefaultPolicyDrivenContribution() {
    try (PolicyDrivenTikaExtractor core =
            new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(), configuredPolicy());
        ExtractorContributionRegistry registry = new ExtractorContributionRegistry()) {
      registry.install(ExtractorContribution.catchAll(
          "vendor.tika", ExtractorTrust.TRUSTED,
          new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory())));

      assertThrows(IllegalStateException.class, () -> registry.install(
          ExtractorContribution.catchAll("core.tika", ExtractorTrust.CORE, core)));

      assertEquals(java.util.List.of("vendor.tika"), registry.ids());
      assertEquals(TikaExtractionPolicy.defaults(), registry.policy());
    }
  }

  @Test
  void installRejectsMismatchedPolicyDrivenContributionAndAcceptsMatchingPolicy() throws Exception {
    TikaExtractionPolicy policy = configuredPolicy();
    try (PolicyDrivenTikaExtractor mismatched =
            new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory());
        ExtractorContributionRegistry registry = new ExtractorContributionRegistry()) {
      registry.install(new ExtractorContribution(
          "vendor.first", ExtractorTrust.TRUSTED, file -> false,
          new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(), policy)));

      assertThrows(IllegalStateException.class, () -> registry.install(
          ExtractorContribution.catchAll("vendor.tika", ExtractorTrust.TRUSTED, mismatched)));
      assertEquals(java.util.List.of("vendor.first"), registry.ids());
      assertEquals(policy, registry.policy());

      registry.install(ExtractorContribution.catchAll(
          "vendor.tika", ExtractorTrust.TRUSTED,
          new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(), policy)));
      assertEquals(java.util.List.of("vendor.first", "vendor.tika"), registry.ids());
      Path file = Files.writeString(tempDir.resolve("vendor.txt"), "vendor sentinel");
      ExtractionArtifact artifact = registry.extractArtifact(file);
      assertTrue(artifact.result().content().contains("vendor sentinel"));
      assertEquals(policy.policyId(), artifact.policyId());
      assertDoesNotThrow(() -> artifact.validate(registry.policy(), null));
    }
  }

  @Test
  void collidingCoreInstallRetainsConfiguredPolicy() {
    TikaExtractionPolicy policy = configuredPolicy();
    try (ExtractorContributionRegistry registry = ExtractorContributionRegistry.withCoreTika(
        new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(), policy))) {
      assertThrows(IllegalStateException.class, () -> registry.install(
          ExtractorContribution.catchAll("core.tika", ExtractorTrust.CORE, provider("duplicate"))));

      assertEquals(java.util.List.of("core.tika"), registry.ids());
      assertEquals(policy, registry.policy());
    }
  }

  @Test
  void ordinaryCoreAfterUninstallReportsDefaultPolicy() {
    try (PolicyDrivenTikaExtractor core =
            new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(), configuredPolicy());
        ExtractorContributionRegistry registry = ExtractorContributionRegistry.withCoreTika(core)) {
      assertTrue(registry.uninstall("core.tika"));
      registry.install(ExtractorContribution.catchAll("core.tika", ExtractorTrust.CORE, provider("ordinary")));

      assertEquals(java.util.List.of("core.tika"), registry.ids());
      assertEquals(TikaExtractionPolicy.defaults(), registry.policy());
    }
  }

  @Test
  void ordinaryCoreBeforeConfiguredVendorUsesSharedPolicyAndValidatesArtifact() throws Exception {
    TikaExtractionPolicy policy = configuredPolicy();
    String text = "ordinary sentinel ".repeat(20);
    try (ExtractorContributionRegistry registry = ExtractorContributionRegistry.withCoreTika(provider(text))) {
      registry.install(ExtractorContribution.catchAll(
          "vendor.tika", ExtractorTrust.TRUSTED,
          new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(), policy)));

      assertEquals(policy, registry.policy());
      ExtractionArtifact artifact = registry.extractArtifact(Path.of("ordinary.txt"));
      assertEquals(policy.policyId(), artifact.policyId());
      assertEquals(text.substring(0, policy.maxExtractedChars()), artifact.result().content());
      assertTrue(artifact.truncated());
      assertDoesNotThrow(() -> artifact.validate(registry.policy(), null));
    }
  }

  @Test
  void ordinaryCoreAfterConfiguredVendorUsesSharedPolicyAndValidatesArtifact() throws Exception {
    TikaExtractionPolicy policy = configuredPolicy();
    try (ExtractorContributionRegistry registry = new ExtractorContributionRegistry()) {
      registry.install(new ExtractorContribution(
          "vendor.tika", ExtractorTrust.TRUSTED, file -> false,
          new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(), policy)));
      registry.install(ExtractorContribution.catchAll("core.tika", ExtractorTrust.CORE, provider("ordinary")));

      assertEquals(policy, registry.policy());
      ExtractionArtifact artifact = registry.extractArtifact(Path.of("ordinary.txt"));
      assertEquals("ordinary", artifact.result().content());
      assertEquals(policy.policyId(), artifact.policyId());
      assertDoesNotThrow(() -> artifact.validate(registry.policy(), null));
    }
  }

  @Test
  void uninstallCoreTikaRetainsConfiguredVendorPolicy() {
    TikaExtractionPolicy policy = configuredPolicy();
    try (PolicyDrivenTikaExtractor core =
            new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(), policy);
        ExtractorContributionRegistry registry = ExtractorContributionRegistry.withCoreTika(core)) {
      registry.install(ExtractorContribution.catchAll(
          "vendor.tika", ExtractorTrust.TRUSTED,
          new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(), policy)));

      assertTrue(registry.uninstall("core.tika"));
      assertEquals(java.util.List.of("vendor.tika"), registry.ids());
      assertEquals(policy, registry.policy());
    }
  }

  @Test
  void uninstallLastPolicyDrivenContributionRestoresDefaultPolicy() {
    TikaExtractionPolicy policy = configuredPolicy();
    try (PolicyDrivenTikaExtractor vendor =
            new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(), policy);
        ExtractorContributionRegistry registry = ExtractorContributionRegistry.withCoreTika(provider("ordinary"))) {
      registry.install(ExtractorContribution.catchAll("vendor.tika", ExtractorTrust.TRUSTED, vendor));
      assertEquals(policy, registry.policy());

      assertTrue(registry.uninstall("vendor.tika"));
      assertEquals(java.util.List.of("core.tika"), registry.ids());
      assertEquals(TikaExtractionPolicy.defaults(), registry.policy());
    }
  }

  @Test
  void closeRetainsConfiguredVendorPolicy() {
    TikaExtractionPolicy policy = configuredPolicy();
    try (ExtractorContributionRegistry registry = new ExtractorContributionRegistry()) {
      registry.install(ExtractorContribution.catchAll(
          "vendor.tika", ExtractorTrust.TRUSTED,
          new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(), policy)));
      assertEquals(policy, registry.policy());

      registry.close();

      assertTrue(registry.ids().isEmpty());
      assertEquals(policy, registry.policy());
    }
  }

  private static TikaExtractionPolicy configuredPolicy() {
    return TikaExtractionPolicy.fromWorkerLimits(
        new ResolvedConfig.Worker(128, TikaExtractionPolicy.DEFAULT_MAX_INPUT_BYTES));
  }

  @Test
  void installRejectsIdCollision() {
    ExtractorContributionRegistry registry = ExtractorContributionRegistry.withCoreTika(provider("tika"));
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                registry.install(
                    ExtractorContribution.catchAll("core.tika", ExtractorTrust.CORE, provider("dup"))));
    // Same id == same owner in the shared composer → Lifecycle rejects the re-install.
    assertTrue(ex.getMessage().contains("Already installed"));
  }

  @Test
  void trustSubstrateRejectsNonCoreContributorMintingCoreId() {
    ExtractorContributionRegistry registry = new ExtractorContributionRegistry();
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                registry.install(
                    ExtractorContribution.catchAll(
                        "core.tika", ExtractorTrust.UNTRUSTED, provider("evil"))));
    assertTrue(ex.getMessage().contains("Host owns truth"));
  }

  @Test
  void uninstallRevokesContribution() {
    ExtractorContributionRegistry registry = ExtractorContributionRegistry.withCoreTika(provider("tika"));
    assertTrue(registry.uninstall("core.tika"));
    assertFalse(registry.uninstall("core.tika"));
    // With nothing installed, dispatch has no contribution to route to.
    assertThrows(IllegalStateException.class, () -> registry.extract(Path.of("a.pdf")));
  }
}
