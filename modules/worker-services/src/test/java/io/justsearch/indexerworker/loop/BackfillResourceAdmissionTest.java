/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.bgem3.*;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.disambiguation.DisambiguationService;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.ops.BackfillSnapshotTestSupport;
import io.justsearch.indexerworker.loop.ops.LoopPacingPolicy;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.*;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

final class BackfillResourceAdmissionTest {
  private final DocumentFieldOps fields = mock(DocumentFieldOps.class);
  private final IndexingCoordinator coordinator = mock(IndexingCoordinator.class);
  private final IndexCountOps counts = mock(IndexCountOps.class);
  private final EmbeddingProvider dense = mock(EmbeddingProvider.class);
  private final NerService ner = mock(NerService.class);
  private final SpladeEncoder sparse = mock(SpladeEncoder.class);
  private final BgeM3Encoder bge = mock(BgeM3Encoder.class);
  private final DisambiguationService disambiguation = mock(DisambiguationService.class);
  private final WorkerSignalBus signals = mock(WorkerSignalBus.class);
  private final EmbeddingProviderLifecycle lifecycle = mock(EmbeddingProviderLifecycle.class);
  private final ResolvedConfig config = mock(ResolvedConfig.class, RETURNS_DEEP_STUBS);
  private final AtomicBoolean hold = new AtomicBoolean();
  private final AtomicBoolean main = new AtomicBoolean();
  private final AtomicLong clock = new AtomicLong();
  private final Map<String, Object> state = new HashMap<>();

  @BeforeEach void wire() throws Exception {
    BackfillSnapshotTestSupport.installReceipts(coordinator);
    BackfillSnapshotTestSupport.installSelection(fields);
    when(signals.isBulkHeld()).thenAnswer(call -> hold.get());
    when(signals.isMainGpuActive()).thenAnswer(call -> main.get());
    when(lifecycle.embeddingProvider()).thenReturn(dense);
    when(lifecycle.allowEmbeddingWrites()).thenReturn(true);
    when(config.ai().backfillPacing()).thenReturn(ResolvedConfig.Ai.BackfillPacing.DEFAULTS);
    when(fields.queryDocIdsByField(anyString(), anyString(), anyInt())).thenAnswer(call ->
        call.getArgument(1).equals(state.get(call.getArgument(0))) ? List.of("doc") : List.of());
    when(fields.queryNonChunkDocIdsByField(anyString(), anyString(), anyInt())).thenAnswer(call ->
        fields.queryDocIdsByField(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
    when(fields.getEnrichmentInputs(anyList(), anyLong())).thenAnswer(call -> {
      List<String> ids = call.getArgument(0);
      if (ids.isEmpty()) return Map.of();
      Map<String, String> metadata = new HashMap<>(); state.forEach((key, value) -> metadata.put(key, value.toString()));
      return Map.of("doc", new DocumentFieldOps.EnrichmentInput("document", metadata, null, Map.of()));
    });
    when(coordinator.updateDocumentsBatchIf(anyList(), anyMap())).thenAnswer(call -> {
      List<Map.Entry<String, Map<String, Object>>> updates = call.getArgument(0);
      updates.forEach(entry -> state.putAll(entry.getValue()));
      return new LuceneRuntimeTypes.BatchUpdateResult(updates.size(), 0);
    });
    when(dense.embedDocumentBatch(anyList())).thenReturn(List.of(new float[] {1, 0}));
    when(ner.isAvailable()).thenReturn(true);
    when(ner.extractEntitiesBatch(anyList())).thenReturn(List.of(NerResult.EMPTY));
    when(sparse.encodeBatch(anyList())).thenReturn(List.of(Map.of("term", 1f)));
    when(bge.encodeBatch(anyList())).thenReturn(List.of(new BgeM3Output(new float[] {1, 0}, Map.of("term", 1f))));
  }

  private BackfillScheduler scheduler(boolean denseEnabled, boolean nerEnabled, boolean sparseEnabled,
      boolean unified, boolean disambiguationEnabled) {
    when(dense.isAvailable()).thenReturn(denseEnabled);
    return new BackfillScheduler(fields, coordinator, counts, mock(CommitOps.class), signals,
        IndexingPacing.unthrottled(), lifecycle, new AtomicBoolean(true), () -> config,
        () -> sparseEnabled ? sparse : null, () -> unified ? bge : null,
        () -> nerEnabled ? ner : null, () -> disambiguationEnabled ? disambiguation : null, clock::get);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({"bulk,dense", "main,dense", "bulk,sparse", "main,sparse", "bulk,ner", "main,ner"})
  void aHeldGpuStageStillAdmitsTheCpuStageInTheSameCycle(String reason, String gpuStage) {
    hold.set(reason.equals("bulk")); main.set(reason.equals("main"));
    boolean denseGpu = gpuStage.equals("dense"), sparseGpu = gpuStage.equals("sparse"), nerGpu = gpuStage.equals("ner");
    when(dense.isUsingGpu()).thenReturn(denseGpu);
    when(sparse.isGpuAvailable()).thenReturn(sparseGpu);
    when(ner.isGpuAvailable()).thenReturn(nerGpu);
    state.put(SchemaFields.EMBEDDING_STATUS, "PENDING"); state.put(SchemaFields.NER_STATUS, "PENDING");
    state.put(SchemaFields.SPLADE_STATUS, "PENDING");
    assertTrue(scheduler(true, true, true, false, false).runIdleCycle());
    assertEquals(denseGpu ? "PENDING" : "COMPLETED", state.get(SchemaFields.EMBEDDING_STATUS));
    assertEquals(nerGpu ? "PENDING" : "COMPLETED_EMPTY", state.get(SchemaFields.NER_STATUS));
    assertEquals(sparseGpu ? "PENDING" : "COMPLETED", state.get(SchemaFields.SPLADE_STATUS));
    assertDoesNotThrow(() -> verify(dense, times(denseGpu ? 0 : 1)).embedDocumentBatch(anyList()));
    assertDoesNotThrow(() -> verify(ner, times(nerGpu ? 0 : 1)).extractEntitiesBatch(anyList()));
    assertDoesNotThrow(() -> verify(sparse, times(sparseGpu ? 0 : 1)).encodeBatch(anyList()));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({"bulk,false,false", "bulk,false,true", "main,true,false", "main,true,true"})
  void interleavedAdmissionUsesTheSparseUnitResource(String reason, boolean gpu, boolean unified) {
    hold.set(reason.equals("bulk")); main.set(reason.equals("main"));
    when(sparse.isGpuAvailable()).thenReturn(gpu); when(bge.isGpuAvailable()).thenReturn(gpu);
    state.put(SchemaFields.SPLADE_STATUS, "PENDING");
    scheduler(false, false, !unified, unified, false).runInterleavedSplade(Long.MAX_VALUE);
    assertEquals(gpu ? "PENDING" : "COMPLETED", state.get(SchemaFields.SPLADE_STATUS));
    assertDoesNotThrow(() -> verify(sparse, times(!gpu && !unified ? 1 : 0)).encodeBatch(anyList()));
    assertDoesNotThrow(() -> verify(bge, times(!gpu && unified ? 1 : 0)).encodeBatch(anyList()));
  }

  @Test void gpuHoldAssertedBetweenStagesLeavesTheNextGpuUnitPending() throws Exception {
    state.put(SchemaFields.EMBEDDING_STATUS, "PENDING"); state.put(SchemaFields.SPLADE_STATUS, "PENDING");
    when(sparse.isGpuAvailable()).thenReturn(true);
    when(dense.embedDocumentBatch(anyList())).thenAnswer(call -> { hold.set(true); return List.of(new float[] {1, 0}); });
    assertTrue(scheduler(true, false, true, false, false).runIdleCycle());
    assertEquals("COMPLETED", state.get(SchemaFields.EMBEDDING_STATUS));
    assertEquals("PENDING", state.get(SchemaFields.SPLADE_STATUS));
    assertDoesNotThrow(() -> verify(sparse, never()).encodeBatch(anyList()));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"parent", "ner", "disambiguation"})
  void acknowledgedIndividualWorkSelectsActiveSleep(String stage) throws Exception {
    if (stage.equals("parent")) state.put(SchemaFields.EMBEDDING_STATUS, "PENDING");
    if (stage.equals("ner")) state.put(SchemaFields.NER_STATUS, "PENDING");
    if (stage.equals("disambiguation")) {
      when(disambiguation.isAvailable()).thenReturn(true);
      Object reader = new Object();
      var revision = new DocumentFieldOps.EntityCorpusRevision(fields, 0, 1, 0);
      when(fields.entityCorpusState()).thenReturn(new DocumentFieldOps.EntityCorpusState(revision, reader, 0));
      when(disambiguation.prepareReconciliation(revision, reader)).thenReturn(true);
      when(fields.entityPage(any(), anyInt(), anyInt())).thenReturn(new DocumentFieldOps.EntityPage(List.of(), 7, true));
      when(coordinator.withEnrichmentAuthority(any(), any())).thenAnswer(call -> {
        ((java.util.concurrent.Callable<?>) call.getArgument(1)).call(); return true;
      });
    }
    boolean active = scheduler(stage.equals("parent"), stage.equals("ner"), false, false, stage.equals("disambiguation")).runIdleCycle();
    assertEquals(LoopPacingPolicy.activeIdleSleepMs(), LoopPacingPolicy.idleSleepMs(active));
    if (stage.equals("disambiguation")) assertDoesNotThrow(() -> verify(disambiguation).acknowledgeReconciliation(7, true));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"ner", "sparse", "bge"})
  void individualGpuStagesWaitForMainGpuOwnership(String stage) throws Exception {
    main.set(true);
    when(ner.isGpuAvailable()).thenReturn(true); when(sparse.isGpuAvailable()).thenReturn(true); when(bge.isGpuAvailable()).thenReturn(true);
    String status = stage.equals("ner") ? SchemaFields.NER_STATUS : SchemaFields.SPLADE_STATUS;
    state.put(status, "PENDING");
    assertFalse(scheduler(false, stage.equals("ner"), stage.equals("sparse"), stage.equals("bge"), false).runIdleCycle());
    assertEquals("PENDING", state.get(status));
    assertDoesNotThrow(() -> verify(ner, never()).extractEntitiesBatch(anyList()));
    assertDoesNotThrow(() -> verify(sparse, never()).encodeBatch(anyList()));
    assertDoesNotThrow(() -> verify(bge, never()).encodeBatch(anyList()));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"parent", "ner", "sparse", "bge"})
  void cpuOnlyIndividualStagesAdvanceUnderGpuPressure(String stage) {
    hold.set(true); main.set(true);
    String status = stage.equals("parent") ? SchemaFields.EMBEDDING_STATUS
        : stage.equals("ner") ? SchemaFields.NER_STATUS : SchemaFields.SPLADE_STATUS;
    state.put(status, "PENDING");
    scheduler(stage.equals("parent"), stage.equals("ner"), stage.equals("sparse"), stage.equals("bge"), false).runIdleCycle();
    assertEquals(stage.equals("ner") ? "COMPLETED_EMPTY" : "COMPLETED", state.get(status));
  }

  @Test void disambiguationInvocationWithoutAcknowledgementStaysIdle() throws Exception {
    when(disambiguation.isAvailable()).thenReturn(true);
    Object reader = new Object();
    var revision = new DocumentFieldOps.EntityCorpusRevision(fields, 0, 1, 0);
    when(fields.entityCorpusState()).thenReturn(new DocumentFieldOps.EntityCorpusState(revision, reader, 0));
    when(disambiguation.prepareReconciliation(revision, reader)).thenReturn(true);
    when(fields.entityPage(any(), anyInt(), anyInt())).thenReturn(new DocumentFieldOps.EntityPage(List.of(), 7, true));
    when(coordinator.withEnrichmentAuthority(any(), any())).thenAnswer(call -> {
      ((java.util.concurrent.Callable<?>) call.getArgument(1)).call(); return true;
    });
    doThrow(new java.sql.SQLException("snapshot publication failed")).when(disambiguation).acknowledgeReconciliation(7, true);
    assertFalse(scheduler(false, false, false, false, true).runIdleCycle());
    assertDoesNotThrow(() -> verify(disambiguation).replaceSources(anyMap(), any()));
    assertDoesNotThrow(() -> verify(disambiguation).acknowledgeReconciliation(7, true));
  }

  @Test void individualDeadlineFinishesTheCurrentNerDocumentAndDefersTheNext() throws Exception {
    when(fields.queryDocIdsByField(anyString(), anyString(), anyInt())).thenAnswer(call ->
        SchemaFields.NER_STATUS.equals(call.getArgument(0)) ? List.of("a", "b") : List.of());
    when(fields.getEnrichmentInputs(anyList(), anyLong())).thenAnswer(call -> {
      List<String> ids = call.getArgument(0);
      Map<String, DocumentFieldOps.EnrichmentInput> inputs = new LinkedHashMap<>();
      for (String id : ids) inputs.put(id, new DocumentFieldOps.EnrichmentInput("document " + id,
          Map.of(SchemaFields.NER_STATUS, "PENDING", SchemaFields.NER_RETRY_COUNT, "2"), null, Map.of()));
      return inputs;
    });
    var nativeCalls = new AtomicInteger(); var accepted = new ArrayList<String>();
    when(ner.extractEntitiesBatch(anyList())).thenAnswer(call -> {
      for (int i = 0; i < 2; i++) {
        io.justsearch.indexerworker.inference.LocalSessionAcquisition.background().remainingNanos();
        nativeCalls.incrementAndGet(); clock.set(5_000_000_000L);
      }
      return List.of(NerResult.EMPTY);
    });
    when(coordinator.updateDocumentsBatchIf(anyList(), anyMap())).thenAnswer(call -> {
      List<Map.Entry<String, Map<String, Object>>> updates = call.getArgument(0);
      updates.forEach(entry -> accepted.add(entry.getKey()));
      return new LuceneRuntimeTypes.BatchUpdateResult(updates.size(), 0);
    });
    scheduler(false, true, false, false, false).runIdleCycle();
    assertEquals(2, nativeCalls.get(), "the current document finishes after deadline expiry; no next document starts");
    assertEquals(List.of("a"), accepted);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({"bulk,ner", "main,ner", "bulk,sparse", "main,sparse", "bulk,bge", "main,bge"})
  void aColdConfiguredGpuUnitWaitsBeforeItCanInitializeTheGpu(String reason, String stage) throws Exception {
    hold.set(reason.equals("bulk")); main.set(reason.equals("main"));
    var cold = io.justsearch.ort.OrtCudaStatus.pending("fixture", java.nio.file.Path.of("fixture"));
    when(ner.getOrtCudaStatus()).thenReturn(cold);
    when(sparse.getOrtCudaStatus()).thenReturn(cold);
    when(bge.getOrtCudaStatus()).thenReturn(cold);
    String status = stage.equals("ner") ? SchemaFields.NER_STATUS : SchemaFields.SPLADE_STATUS;
    state.put(status, "PENDING");
    scheduler(false, stage.equals("ner"), stage.equals("sparse"), stage.equals("bge"), false).runIdleCycle();
    assertEquals("PENDING", state.get(status), "unavailable is not CPU-only: bulk acquisition may initialize a configured GPU");
    assertDoesNotThrow(() -> verify(ner, never()).extractEntitiesBatch(anyList()));
    assertDoesNotThrow(() -> verify(sparse, never()).encodeBatch(anyList()));
    assertDoesNotThrow(() -> verify(bge, never()).encodeBatch(anyList()));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {false, true})
  void aBgePlanThatForcesCpuAdvancesDespiteGpuPressure(boolean interleaved) throws Exception {
    hold.set(true);
    when(bge.isGpuAvailable()).thenReturn(true);
    when(bge.isBulkOnCpu()).thenReturn(true);
    state.put(SchemaFields.SPLADE_STATUS, "PENDING");
    var scheduler = scheduler(false, false, false, true, false);
    if (interleaved) scheduler.runInterleavedSplade(Long.MAX_VALUE);
    else scheduler.runIdleCycle();
    assertEquals("COMPLETED", state.get(SchemaFields.SPLADE_STATUS));
    assertDoesNotThrow(() -> verify(bge).encodeBatch(anyList()));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {false, true})
  void bgeAndNerStillServeTheirCpuOwnerWhileTheOtherGpuOwnerIsHeld(boolean bgeGpu) throws Exception {
    hold.set(true);
    when(bge.isGpuAvailable()).thenReturn(bgeGpu); when(ner.isGpuAvailable()).thenReturn(!bgeGpu);
    state.put(SchemaFields.NER_STATUS, "PENDING"); state.put(SchemaFields.SPLADE_STATUS, "PENDING");
    scheduler(false, true, false, true, false).runIdleCycle();
    assertEquals(bgeGpu ? "COMPLETED_EMPTY" : "PENDING", state.get(SchemaFields.NER_STATUS));
    assertEquals(bgeGpu ? "PENDING" : "COMPLETED", state.get(SchemaFields.SPLADE_STATUS));
    assertDoesNotThrow(() -> verify(ner, times(bgeGpu ? 1 : 0)).extractEntitiesBatch(anyList()));
    assertDoesNotThrow(() -> verify(bge, times(bgeGpu ? 0 : 1)).encodeBatch(anyList()));
    assertDoesNotThrow(() -> verify(dense, never()).embedDocumentBatch(anyList()));
  }

  @Test void chunkOnlyWorkStillPublishesBesideTheUnifiedBgeOwner() throws Exception {
    when(config.rag().chunkVectorsEnabled()).thenReturn(true);
    state.put(SchemaFields.IS_CHUNK, "true"); state.put(SchemaFields.CHUNK_EMBEDDING_STATUS, "PENDING");
    assertTrue(scheduler(true, false, false, true, false).runIdleCycle());
    assertEquals("COMPLETED", state.get(SchemaFields.CHUNK_EMBEDDING_STATUS));
    assertDoesNotThrow(() -> verify(dense).embedDocumentBatch(anyList()));
    assertDoesNotThrow(() -> verify(bge, never()).encodeBatch(anyList()));
  }

  @Test void aTurnWithoutAdvancementSelectsIdleSleep() {
    assertFalse(scheduler(false, false, false, false, false).runIdleCycle());
  }

  @Test void energyReductionStillBlocksCpuEnrichment() throws Exception {
    when(signals.isEnergyReduced()).thenReturn(true);
    state.put(SchemaFields.NER_STATUS, "PENDING");
    assertFalse(scheduler(false, true, false, false, false).runIdleCycle());
    assertDoesNotThrow(() -> verify(ner, never()).extractEntitiesBatch(anyList()));
  }
}
