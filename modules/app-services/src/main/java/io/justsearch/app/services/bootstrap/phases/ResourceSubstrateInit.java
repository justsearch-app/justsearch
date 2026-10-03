/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.app.observability.CapabilitiesResourceCatalog;
import io.justsearch.app.observability.ai.AiInstallResourceCatalog;
import io.justsearch.app.observability.ai.AiPackImportResourceCatalog;
import io.justsearch.app.observability.diagnostic.DiagnosticChannelAppender;
import io.justsearch.app.observability.diagnostic.DiagnosticChannelAppenderInstaller;
import io.justsearch.app.observability.diagnostic.DiagnosticChannelStreamRegistry;
import io.justsearch.app.observability.diagnostic.EngineLogDiagnosticChannelCatalog;
import io.justsearch.app.observability.health.ConditionRecoveryIndexCatalog;
import io.justsearch.app.observability.health.ConditionRecoveryIndexChangeRegistry;
import io.justsearch.app.observability.health.HealthResourceCatalog;
import io.justsearch.app.observability.indexing.FailedIndexingJobsResourceCatalog;
import io.justsearch.app.observability.indexing.IndexedRootsResourceCatalog;
import io.justsearch.app.observability.indexing.IndexingJobsChangeRegistry;
import io.justsearch.app.observability.indexing.IndexingJobsResourceCatalog;
import io.justsearch.app.observability.inference.CoreInferenceResourceCatalog;
import io.justsearch.app.observability.runtime.RuntimeContext;
import io.justsearch.app.observability.runtime.RuntimeContextChangeRegistry;
import io.justsearch.app.observability.runtime.RuntimeContextHolder;
import io.justsearch.app.observability.runtime.RuntimeContextResourceCatalog;
import io.justsearch.app.observability.surface.CoreSurfaceCatalog;
import io.justsearch.app.services.observability.runtime.RuntimeContextConfigBridge;
import io.justsearch.configuration.resolved.ConfigStore;

/**
 * Tempdoc 519 §7 / Step 7: static phase function that constructs the bootstrap's 8
 * {@link ResourceCatalog}s + the diagnostic-channel cluster + the runtime-context cluster +
 * the surface catalog. Replaces ~60 LOC of inline {@code new X()} constructions in the
 * bootstrap's main constructor.
 *
 * <p>Side effect: attaches the {@link DiagnosticChannelAppenderInstaller} to the root Logback
 * logger so the SSE diagnostic-channel stream begins receiving log records.
 */
public final class ResourceSubstrateInit {

  private ResourceSubstrateInit() {}

  /** Bundled output — all Resource catalogs + diagnostic channel + runtime context cluster. */
  public record Output(
      ResourceCatalog resourceCatalog,
      RuntimeContextResourceCatalog runtimeContextResourceCatalog,
      RuntimeContextHolder runtimeContextHolder,
      RuntimeContextChangeRegistry runtimeContextChangeRegistry,
      RuntimeContextConfigBridge runtimeContextConfigBridge,
      CapabilitiesResourceCatalog serverCapabilitiesResourceCatalog,
      CoreInferenceResourceCatalog coreInferenceResourceCatalog,
      AiInstallResourceCatalog aiInstallResourceCatalog,
      AiPackImportResourceCatalog aiPackImportResourceCatalog,
      IndexingJobsResourceCatalog indexingJobsResourceCatalog,
      IndexingJobsChangeRegistry indexingJobsChangeRegistry,
      FailedIndexingJobsResourceCatalog failedIndexingJobsResourceCatalog,
      IndexedRootsResourceCatalog indexedRootsResourceCatalog,
      ConditionRecoveryIndexCatalog conditionRecoveryIndexResourceCatalog,
      ConditionRecoveryIndexChangeRegistry conditionRecoveryIndexChangeRegistry,
      EngineLogDiagnosticChannelCatalog engineLogDiagnosticChannelCatalog,
      DiagnosticChannelStreamRegistry diagnosticChannelStreamRegistry,
      DiagnosticChannelAppender diagnosticChannelAppender,
      DiagnosticChannelAppenderInstaller diagnosticChannelAppenderInstaller,
      CoreSurfaceCatalog coreSurfaceCatalog) {}

  public static Output run(RuntimeContext initialRuntimeContext) {
    ResourceCatalog resourceCatalog = new HealthResourceCatalog();
    RuntimeContextResourceCatalog runtimeContextResourceCatalog =
        new RuntimeContextResourceCatalog();
    RuntimeContextHolder runtimeContextHolder = new RuntimeContextHolder(initialRuntimeContext);
    RuntimeContextChangeRegistry runtimeContextChangeRegistry = new RuntimeContextChangeRegistry();
    ConfigStore configStore = ConfigStore.globalOrNull();
    RuntimeContextConfigBridge runtimeContextConfigBridge =
        configStore == null
            ? null
            : new RuntimeContextConfigBridge(
                runtimeContextHolder, runtimeContextChangeRegistry, configStore);
    CapabilitiesResourceCatalog serverCapabilitiesResourceCatalog =
        new CapabilitiesResourceCatalog();
    // Tempdoc 560 WS7b — the Brain's runtime capability as an OBSERVABLE registry participant.
    CoreInferenceResourceCatalog coreInferenceResourceCatalog = new CoreInferenceResourceCatalog();
    // Tempdoc 575 §17 Face C — Brain install/pack as OBSERVABLE polled-state Resources.
    AiInstallResourceCatalog aiInstallResourceCatalog = new AiInstallResourceCatalog();
    AiPackImportResourceCatalog aiPackImportResourceCatalog = new AiPackImportResourceCatalog();
    IndexingJobsResourceCatalog indexingJobsResourceCatalog = new IndexingJobsResourceCatalog();
    IndexingJobsChangeRegistry indexingJobsChangeRegistry = new IndexingJobsChangeRegistry();
    FailedIndexingJobsResourceCatalog failedIndexingJobsResourceCatalog =
        new FailedIndexingJobsResourceCatalog();
    IndexedRootsResourceCatalog indexedRootsResourceCatalog = new IndexedRootsResourceCatalog();
    ConditionRecoveryIndexCatalog conditionRecoveryIndexResourceCatalog =
        new ConditionRecoveryIndexCatalog();
    ConditionRecoveryIndexChangeRegistry conditionRecoveryIndexChangeRegistry =
        new ConditionRecoveryIndexChangeRegistry();
    EngineLogDiagnosticChannelCatalog engineLogDiagnosticChannelCatalog =
        new EngineLogDiagnosticChannelCatalog();
    DiagnosticChannelStreamRegistry diagnosticChannelStreamRegistry =
        new DiagnosticChannelStreamRegistry(engineLogDiagnosticChannelCatalog);
    DiagnosticChannelAppender diagnosticChannelAppender =
        new DiagnosticChannelAppender(diagnosticChannelStreamRegistry, engineLogDiagnosticChannelCatalog);
    DiagnosticChannelAppenderInstaller diagnosticChannelAppenderInstaller =
        new DiagnosticChannelAppenderInstaller(diagnosticChannelAppender);
    diagnosticChannelAppenderInstaller.attach();
    CoreSurfaceCatalog coreSurfaceCatalog = new CoreSurfaceCatalog();
    return new Output(
        resourceCatalog,
        runtimeContextResourceCatalog,
        runtimeContextHolder,
        runtimeContextChangeRegistry,
        runtimeContextConfigBridge,
        serverCapabilitiesResourceCatalog,
        coreInferenceResourceCatalog,
        aiInstallResourceCatalog,
        aiPackImportResourceCatalog,
        indexingJobsResourceCatalog,
        indexingJobsChangeRegistry,
        failedIndexingJobsResourceCatalog,
        indexedRootsResourceCatalog,
        conditionRecoveryIndexResourceCatalog,
        conditionRecoveryIndexChangeRegistry,
        engineLogDiagnosticChannelCatalog,
        diagnosticChannelStreamRegistry,
        diagnosticChannelAppender,
        diagnosticChannelAppenderInstaller,
        coreSurfaceCatalog);
  }
}
