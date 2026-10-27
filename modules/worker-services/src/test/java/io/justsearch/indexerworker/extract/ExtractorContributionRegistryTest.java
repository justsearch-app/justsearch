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
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Verifies the Worker-side extractor composer (tempdoc 560 §4.4/§6) — composition, dispatch, lifecycle, trust. */
class ExtractorContributionRegistryTest {

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
