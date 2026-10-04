/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.agent.api.AgentService;
import io.justsearch.app.api.BrainInstallService;
import io.justsearch.app.api.BrainRuntimeService;
import io.justsearch.app.api.CoreServices;
import io.justsearch.app.api.DiagnosticsService;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.ExcludesService;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.InferenceServices;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.PackImportService;
import io.justsearch.app.api.PolicyService;
import io.justsearch.app.api.RuntimeVariantService;
import io.justsearch.app.api.SearchService;
import io.justsearch.app.api.ServiceGraph;
import io.justsearch.app.api.SettingsService;
import io.justsearch.app.api.WorkerServices;

/**
 * §31 Phase 3: all 7 previously-late-bound controller-services + ExcludesService now flow as
 * direct parameters (constructed by ServicePhase). LateBoundServices.java has been deleted —
 * services no longer late-bind via a separate registration step.
 *
 * <p>Per §31 Phase 5: the bootstrap's runtime-variant + brainInstall + remaining inference fields
 * still flow through this assembler. The {@code agent} field is set later (OrchestrationPhase
 * wires AgentLoopService) and the rebuild path projects it from the previously-held graph.
 */
public final class ServiceGraphAssembler {

  private ServiceGraphAssembler() {}

  public static ServiceGraph assemble(
      AgentService agent,
      OnlineAiService onlineAi,
      SearchService search,
      IndexingService indexing,
      DocumentService documents,
      ExcludesService excludes,
      SettingsService settings,
      PolicyService policy,
      DiagnosticsService diagnostics,
      BrainRuntimeService brainRuntime,
      RuntimeVariantService runtimeVariant,
      PackImportService packImport,
      BrainInstallService brainInstall) {
    CoreServices coreRecord = new CoreServices(settings, policy, diagnostics, agent);
    WorkerServices workerRecord = new WorkerServices(indexing, documents, excludes, search);
    InferenceServices inferenceRecord =
        new InferenceServices(onlineAi, brainRuntime, runtimeVariant, packImport, brainInstall);
    return new ServiceGraph(coreRecord, workerRecord, inferenceRecord);
  }
}
