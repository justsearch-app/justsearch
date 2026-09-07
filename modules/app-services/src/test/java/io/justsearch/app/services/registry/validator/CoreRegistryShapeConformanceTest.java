package io.justsearch.app.services.registry.validator;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.DiagnosticChannelCatalog;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.app.observability.CapabilitiesResourceCatalog;
import io.justsearch.app.observability.advisory.AdvisoryResourceCatalog;
import io.justsearch.app.observability.diagnostic.EngineLogDiagnosticChannelCatalog;
import io.justsearch.app.observability.health.ConditionRecoveryIndexCatalog;
import io.justsearch.app.observability.health.HealthResourceCatalog;
import io.justsearch.app.observability.indexing.FailedIndexingJobsResourceCatalog;
import io.justsearch.app.observability.indexing.IndexedRootsResourceCatalog;
import io.justsearch.app.observability.indexing.IndexingJobsResourceCatalog;
import io.justsearch.app.observability.inference.CoreInferenceResourceCatalog;
import io.justsearch.app.observability.ledger.ActionLedgerResourceCatalog;
import io.justsearch.app.observability.metrics.DocumentsIndexedRateMetricResourceCatalog;
import io.justsearch.app.observability.metrics.GpuMemoryUtilizationMetricResourceCatalog;
import io.justsearch.app.observability.metrics.GpuUtilizationMetricResourceCatalog;
import io.justsearch.app.observability.metrics.JobQueueDepthMetricResourceCatalog;
import io.justsearch.app.observability.operations.OperationHistoryResourceCatalog;
import io.justsearch.app.observability.runtime.RuntimeContextResourceCatalog;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 575 §13 L2 — the shape-rule conformance gate over the REAL core registry catalogs.
 *
 * <p>The de-risk pass found that {@link ResourceAreaValidator} / {@link DiagnosticChannelAreaValidator}
 * (which encode the §4.2 finer shape rules — Category×SubscriptionMode, HistoryPolicy-required-when,
 * TABULAR-requires-primaryKey, schema/endpoint/presentation shape) were <em>fixture-tested only</em>: no
 * production caller and no conformance test ran them against the real catalogs, so a misclassified real
 * Resource's shape would not fail the build. This test closes that — it runs the validators over the same
 * catalog set the runtime assembles ({@code SubstrateGraphAssembler} via the
 * {@code Resource/Operation/Metric SubstrateInit} phases) and asserts zero findings, mirroring how
 * {@link SurfaceAreaValidatorTest} / {@code ValidatorRunnerTest} pin the surface and operation axes.
 *
 * <p><b>Catalog-list authority.</b> The list below is the production Resource/Channel set assembled in
 * {@code SubstrateGraphAssembler.assemble(...)}. It is maintained by hand here, but completeness is
 * backstopped by the {@code observed-happening} gate's reverse-coverage rule ({@code stream-uncovered}),
 * which fails the build if a catalog stream is neither registered nor declared out-of-family — so a NEW
 * catalog cannot escape governance silently even if it is missed here.
 */
@DisplayName("CoreRegistryShapeConformance (tempdoc 575 §13 L2)")
final class CoreRegistryShapeConformanceTest {

  /** The production Resource catalogs — every {@code implements ResourceCatalog} in core. */
  private static List<ResourceCatalog> coreResourceCatalogs() {
    return List.of(
        new HealthResourceCatalog(),
        new RuntimeContextResourceCatalog(),
        new CapabilitiesResourceCatalog(),
        new CoreInferenceResourceCatalog(),
        // Tempdoc 575 §17 Face C: Brain install/pack OBSERVABLE polled-state Resources.
        new io.justsearch.app.observability.ai.AiInstallResourceCatalog(),
        new io.justsearch.app.observability.ai.AiPackImportResourceCatalog(),
        new IndexingJobsResourceCatalog(),
        new FailedIndexingJobsResourceCatalog(),
        new IndexedRootsResourceCatalog(),
        new ConditionRecoveryIndexCatalog(),
        new OperationHistoryResourceCatalog(),
        new ActionLedgerResourceCatalog(),
        new AdvisoryResourceCatalog(),
        new JobQueueDepthMetricResourceCatalog(),
        new DocumentsIndexedRateMetricResourceCatalog(),
        new GpuUtilizationMetricResourceCatalog(),
        new GpuMemoryUtilizationMetricResourceCatalog());
  }

  /** The production DiagnosticChannel catalogs — every {@code implements DiagnosticChannelCatalog} in core. */
  private static List<DiagnosticChannelCatalog> coreChannelCatalogs() {
    return List.of(new EngineLogDiagnosticChannelCatalog());
  }

  @Test
  @DisplayName("every real Resource catalog conforms to the ResourceAreaValidator shape rules")
  void realResourceCatalogsConformToShapeRules() {
    ResourceAreaValidator validator = new ResourceAreaValidator();
    // Empty operationCatalogs → shape-only (the §4.2 finer rules); cross-reference resolution is a
    // separate concern (slice 445) and not what L2 targets.
    List<ResourceAreaValidator.Finding> findings = new ArrayList<>();
    int validated = 0;
    for (ResourceCatalog catalog : coreResourceCatalogs()) {
      List<Resource> defs = catalog.definitions();
      validated += defs.size();
      findings.addAll(validator.validate(catalog, List.of()));
    }
    // Non-vacuity guard: the test must actually validate the real registry, not pass on an empty list.
    assertTrue(validated >= 5, "non-vacuity: expected many real Resources to be validated, got " + validated);
    assertTrue(
        findings.isEmpty(),
        "Real core Resource catalogs must conform to the ResourceAreaValidator shape rules "
            + "(tempdoc 575 §13 L2); found violations: "
            + findings);
  }

  @Test
  @DisplayName("every real DiagnosticChannel catalog conforms to the DiagnosticChannelAreaValidator rules")
  void realChannelCatalogsConformToShapeRules() {
    DiagnosticChannelAreaValidator validator = new DiagnosticChannelAreaValidator();
    List<DiagnosticChannelAreaValidator.Finding> findings = new ArrayList<>();
    for (DiagnosticChannelCatalog catalog : coreChannelCatalogs()) {
      findings.addAll(validator.validate(catalog));
    }
    assertTrue(
        findings.isEmpty(),
        "Real core DiagnosticChannel catalogs must conform to the DiagnosticChannelAreaValidator rules "
            + "(tempdoc 575 §13 L2); found violations: "
            + findings);
  }
}
