package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexerworker.ingest.IngestionSkipPolicy;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 410 Slice G.3 (M1) — exercises the env-to-policy chain that
 * {@link DefaultWorkerAppServices} wires at Worker boot. Pre-G.3 the chain
 * ({@code JUSTSEARCH_INGESTION_SKIP_* → parseCsvSet → IngestionSkipPolicy.installResolved})
 * had no test; only the leaf {@link IngestionSkipPolicy} singleton-install was covered. A
 * typo in {@code parseCsvSet} or {@code buildSkipPolicy} would have shipped silently.
 */
final class DefaultWorkerAppServicesSkipPolicyTest {

  private static final String PATTERNS_PROP = "justsearch.ingestion.skip.patterns";
  private static final String EXTENSIONS_PROP = "justsearch.ingestion.skip.extensions";
  private static final String DIRECTORIES_PROP = "justsearch.ingestion.skip.directory_names";

  @BeforeEach
  void clearProps() {
    System.clearProperty(PATTERNS_PROP);
    System.clearProperty(EXTENSIONS_PROP);
    System.clearProperty(DIRECTORIES_PROP);
  }

  @AfterEach
  void resetState() {
    System.clearProperty(PATTERNS_PROP);
    System.clearProperty(EXTENSIONS_PROP);
    System.clearProperty(DIRECTORIES_PROP);
    IngestionSkipPolicy.resetToDefaults();
  }

  @Test
  void parseCsvSetReturnsNullForNullInput() {
    assertNull(DefaultWorkerAppServices.parseCsvSet(null));
  }

  @Test
  void parseCsvSetReturnsNullForBlankInput() {
    // EnvRegistry.get() collapses unset and blank-set sysprop into Optional.empty(), so
    // parseCsvSet receives null in both cases. The blank-handling here is a defensive
    // belt-and-suspenders — the upstream collapse is the real gate. See the env-vars doc
    // for the operator-visible semantic.
    assertNull(DefaultWorkerAppServices.parseCsvSet(""));
    assertNull(DefaultWorkerAppServices.parseCsvSet("   "));
  }

  @Test
  void parseCsvSetSplitsOnComma() {
    Set<String> result = DefaultWorkerAppServices.parseCsvSet("foo,bar,baz");
    assertEquals(Set.of("foo", "bar", "baz"), result);
  }

  @Test
  void parseCsvSetTrimsTokensAndDropsEmpties() {
    Set<String> result = DefaultWorkerAppServices.parseCsvSet("  foo  , , bar,, baz  ");
    assertEquals(Set.of("foo", "bar", "baz"), result);
  }

  @Test
  void parseCsvSetReturnsNullWhenAllTokensAreEmpty() {
    assertNull(DefaultWorkerAppServices.parseCsvSet(",,,"));
    assertNull(DefaultWorkerAppServices.parseCsvSet(" , , "));
  }

  @Test
  void buildSkipPolicyUsesDefaultsWhenNoSyspropsSet() {
    IngestionSkipPolicy policy = DefaultWorkerAppServices.buildSkipPolicy();
    // Default skip-extension set includes "pyc"; default skip-directory-name set includes
    // "node_modules" (verified in IngestionSkipPolicyTest).
    assertTrue(
        policy.skipExtensions().contains("pyc"),
        "Default skip extensions should still apply when env is unset");
    assertTrue(
        policy.skipDirectoryNames().contains("node_modules"),
        "Default skip directory names should still apply when env is unset");
  }

  @Test
  void buildSkipPolicyHonorsSyspropOverrideForExtensions() {
    System.setProperty(EXTENSIONS_PROP, "foo,bar");
    IngestionSkipPolicy policy = DefaultWorkerAppServices.buildSkipPolicy();
    assertEquals(Set.of("foo", "bar"), policy.skipExtensions(),
        "Sysprop override must replace the default extension set wholesale");
    assertTrue(
        policy.skipDirectoryNames().contains("node_modules"),
        "Other categories must still use defaults when only one sysprop is set");
  }

  @Test
  void buildSkipPolicyHonorsSyspropOverrideForDirectoryNames() {
    System.setProperty(DIRECTORIES_PROP, "vendor,third_party");
    IngestionSkipPolicy policy = DefaultWorkerAppServices.buildSkipPolicy();
    assertEquals(Set.of("vendor", "third_party"), policy.skipDirectoryNames());
    assertTrue(
        policy.skipExtensions().contains("pyc"),
        "Other categories must still use defaults when only one sysprop is set");
  }

  @Test
  void buildSkipPolicyHonorsSyspropOverrideForPatterns() {
    System.setProperty(PATTERNS_PROP, "secret-marker");
    IngestionSkipPolicy policy = DefaultWorkerAppServices.buildSkipPolicy();
    assertEquals(Set.of("secret-marker"), policy.skipPatterns());
  }

  @Test
  void buildSkipPolicyTrimsAndLowercasesSyspropTokens() {
    System.setProperty(EXTENSIONS_PROP, "  FOO , Bar , BAZ  ");
    IngestionSkipPolicy policy = DefaultWorkerAppServices.buildSkipPolicy();
    // Both parseCsvSet and IngestionSkipPolicy.normalise lowercase + trim; the chain produces
    // a case-folded set.
    assertEquals(Set.of("foo", "bar", "baz"), policy.skipExtensions());
  }

  @Test
  void explicitSnapshotDoesNotFollowLaterGlobalPropertyChanges() {
    System.setProperty(EXTENSIONS_PROP, "captured");
    ResolvedConfig captured =
        new ResolvedConfigBuilder().contributeEnvRegistry().build();
    System.setProperty(EXTENSIONS_PROP, "replacement");

    IngestionSkipPolicy policy = DefaultWorkerAppServices.buildSkipPolicy(captured);

    assertEquals(Set.of("captured"), policy.skipExtensions());
  }
}
