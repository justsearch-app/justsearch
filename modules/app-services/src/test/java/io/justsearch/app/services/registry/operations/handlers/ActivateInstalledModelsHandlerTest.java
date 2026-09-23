/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.AiInstallService.InstalledGenerationCandidate;
import io.justsearch.app.api.BrainInstallService;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.app.api.operations.RecordedIngestionService;
import io.justsearch.app.api.operations.RecordedInstallerGenerationPlan;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class ActivateInstalledModelsHandlerTest {
  private static final EngineContext CONTEXT = TestEngineContexts.internal();
  private static final InvocationProvenance PROVENANCE = EngineProvenance.invocation(
      CONTEXT, ExecutorTag.UI, Instant.parse("2026-09-23T00:00:00Z"), Optional.empty());
  private static final String SOURCE = "installer_model_activation";
  private static final String OPERATION_KEY = "0194f72c-0000-7000-8000-000000000001";
  private static final String HASH = "0".repeat(64);

  @Test
  void freezesCandidateWitnessScopeAndWorkerTargetWithoutStartingIngestion() throws Exception {
    BrainInstallService install = mock(BrainInstallService.class);
    IndexingService indexing = mock(IndexingService.class);
    RecordedIngestionService ingestion = mock(RecordedIngestionService.class);
    InstalledGenerationCandidate candidate = candidate(true);
    when(install.prepareInstalledGenerationCandidate()).thenReturn(Optional.of(candidate));
    when(indexing.captureServingGeneration(CONTEXT)).thenReturn("serving-a");
    IndexTargetSnapshot target = target();
    when(indexing.captureCandidateIndexTarget(any(), any())).thenReturn(target);

    ActivateInstalledModelsHandler handler = handler(install, indexing, ingestion);
    OperationPreparation prepared = handler.prepare(args(), PROVENANCE, CONTEXT);
    var plan = RecordedInstallerGenerationPlan.fromReplayPayload(prepared.replayPayloadJson());

    assertEquals("serving-a", plan.sourceGeneration());
    assertEquals(candidate.settingsWitness(), plan.settingsWitness());
    assertEquals(candidate.models(), plan.models());
    assertEquals(candidate.assets(), plan.assets());
    assertEquals(target, plan.target());
    assertEquals(1, plan.scope().roots().size());
    verify(ingestion, org.mockito.Mockito.never()).execute(any(), any());
  }

  @Test
  void queryOnlyCandidateRemainsWithOrdinarySettingsOwner() {
    BrainInstallService install = mock(BrainInstallService.class);
    IndexingService indexing = mock(IndexingService.class);
    when(install.prepareInstalledGenerationCandidate()).thenReturn(Optional.of(candidate(false)));

    ActivateInstalledModelsHandler handler = handler(install, indexing, mock(RecordedIngestionService.class));

    OperationPreparationRefused refused = assertThrows(OperationPreparationRefused.class,
        () -> handler.prepare(args(), PROVENANCE, CONTEXT));
    assertEquals("GENERATION_REINDEX_NOT_REQUIRED", refused.refusal().errorCode().orElseThrow());
  }

  @Test
  void acceptedPreparedCandidateUsesRecordedOwner() {
    BrainInstallService install = mock(BrainInstallService.class);
    IndexingService indexing = mock(IndexingService.class);
    RecordedIngestionService ingestion = mock(RecordedIngestionService.class);
    when(install.prepareInstalledGenerationCandidate()).thenReturn(Optional.of(candidate(true)));
    when(indexing.captureServingGeneration(CONTEXT)).thenReturn("serving-a");
    when(indexing.captureCandidateIndexTarget(any(), any())).thenReturn(target());
    OperationExecution expected = new OperationExecution(OperationResult.success("accepted"),
        new CompletableFuture<OperationResult>());
    when(ingestion.execute(any(), any())).thenReturn(expected);

    ActivateInstalledModelsHandler handler = handler(install, indexing, ingestion);
    OperationPreparation prepared = handler.prepare(args(), PROVENANCE, CONTEXT);
    OperationRecordHandle record = mock(OperationRecordHandle.class);

    assertSame(expected, handler.executePrepared(prepared, PROVENANCE, CONTEXT, record));
    verify(ingestion).execute(record, CONTEXT);
  }

  @Test
  void changedSettingsWitnessAfterApprovalRequiresFreshPreviewBeforeIngestion() {
    BrainInstallService install = mock(BrainInstallService.class);
    IndexingService indexing = mock(IndexingService.class);
    RecordedIngestionService ingestion = mock(RecordedIngestionService.class);
    InstalledGenerationCandidate first = candidate(true);
    InstalledGenerationCandidate changed = new InstalledGenerationCandidate(
        first.candidateSettings(), new SettingsWitness(5, OPERATION_KEY),
        first.encodedSettings(), first.hotKeys(), first.componentKeys(),
        first.generationBoundKeys(), first.restartRequiredKeys(), first.models(),
        first.assets(), first.provenance());
    when(install.prepareInstalledGenerationCandidate()).thenReturn(
        Optional.of(first), Optional.of(changed));
    when(indexing.captureServingGeneration(CONTEXT)).thenReturn("serving-a");
    when(indexing.captureCandidateIndexTarget(any(), any())).thenReturn(target());
    ActivateInstalledModelsHandler handler = handler(install, indexing, ingestion);
    OperationPreparation approved = handler.prepare(args(), PROVENANCE, CONTEXT);

    OperationExecution result = handler.executePrepared(
        approved, PROVENANCE, CONTEXT, mock(OperationRecordHandle.class));

    assertEquals("ACTIVATION_PREVIEW_STALE", result.response().errorCode().orElseThrow());
    verify(ingestion, org.mockito.Mockito.never()).execute(any(), any());
  }

  @Test
  void changedWorkerTargetAfterApprovalRequiresFreshPreviewBeforeIngestion() {
    BrainInstallService install = mock(BrainInstallService.class);
    IndexingService indexing = mock(IndexingService.class);
    RecordedIngestionService ingestion = mock(RecordedIngestionService.class);
    when(install.prepareInstalledGenerationCandidate()).thenReturn(Optional.of(candidate(true)));
    when(indexing.captureServingGeneration(CONTEXT)).thenReturn("serving-a");
    when(indexing.captureCandidateIndexTarget(any(), any())).thenReturn(target(),
        new IndexTargetSnapshot(
            "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb", "a"));
    ActivateInstalledModelsHandler handler = handler(install, indexing, ingestion);
    OperationPreparation approved = handler.prepare(args(), PROVENANCE, CONTEXT);

    OperationExecution result = handler.executePrepared(
        approved, PROVENANCE, CONTEXT, mock(OperationRecordHandle.class));

    assertEquals("ACTIVATION_PREVIEW_STALE", result.response().errorCode().orElseThrow());
    verify(ingestion, org.mockito.Mockito.never()).execute(any(), any());
  }

  private static ActivateInstalledModelsHandler handler(
      BrainInstallService install, IndexingService indexing, RecordedIngestionService ingestion) {
    return new ActivateInstalledModelsHandler(() -> install, ingestion,
        ignored -> List.of(new RootBinding(Path.of("activation-root").toAbsolutePath(), "documents")),
        () -> indexing, () -> List.of("*.tmp"));
  }

  private static String args() {
    return "{\"source\":\"" + SOURCE + "\"}";
  }

  private static InstalledGenerationCandidate candidate(boolean generationBound) {
    UiSettings settings = new UiSettings();
    String json = HandlerJson.MAPPER.writeValueAsString(settings);
    var provenance = new RecordedInstallerGenerationPlan.AcquisitionProvenance(
        RecordedInstallerGenerationPlan.AcquisitionProvenance.Kind.REGISTRY, "registry", HASH);
    var model = new RecordedInstallerGenerationPlan.ModelIdentity(
        "embedding", "fp32", Path.of("activation-model.onnx").toAbsolutePath(), HASH, 0, provenance);
    var asset = new RecordedInstallerGenerationPlan.AssetIdentity(
        "embedding/tokenizer.json", Path.of("activation-tokenizer.json").toAbsolutePath(), HASH, 0,
        provenance);
    return new InstalledGenerationCandidate(settings, new SettingsWitness(4, OPERATION_KEY),
        RecordedInstallerGenerationPlan.CandidateSettings.fromJson(json), Set.of(),
        Map.of("index", Set.of("justsearch.embed.onnx.model_path")),
        generationBound ? Set.of("justsearch.embed.onnx.model_path") : Set.of(), Set.of(),
        List.of(model), List.of(asset), provenance);
  }

  private static IndexTargetSnapshot target() {
    return new IndexTargetSnapshot(
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "");
  }
}
