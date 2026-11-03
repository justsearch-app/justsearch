/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.bgem3.*;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.*;
import io.justsearch.indexerworker.loop.*;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.*;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.slf4j.LoggerFactory;

/** Unit boundaries use real lifecycle authority; writes are observed at the coordinator interface. */
final class EnrichmentHardStopRepairTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  enum Route {
    DENSE, INDIVIDUAL_DENSE, SINGLE_PASS, WINDOW, CHUNK, INDIVIDUAL_CHUNK,
    SPARSE, SPARSE_CHUNK, INDIVIDUAL_SPARSE, INTERLEAVED_SPARSE,
    INDIVIDUAL_SPARSE_CHUNK, INTERLEAVED_SPARSE_CHUNK, NER, INDIVIDUAL_NER,
    BGE, BGE_CHUNK
  }
  enum Stop { SHUTDOWN, INTERRUPTION, HOLD, SOURCE, TOKEN, UNAVAILABLE }
  enum Result { SUCCESS, FAILURE, CANCELLATION }
  private final DocumentFieldOps fields = mock(DocumentFieldOps.class);
  private final EmbeddingProvider dense = mock(EmbeddingService.class);
  private final SpladeEncoder sparse = mock(SpladeEncoder.class);
  private final NerService ner = mock(NerService.class);
  private final BgeM3Encoder bge = mock(BgeM3Encoder.class);
  private final WorkerSignalBus signals = mock(WorkerSignalBus.class);
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final AtomicBoolean hold = new AtomicBoolean();
  private final AtomicBoolean available = new AtomicBoolean(true);
  private final AtomicLong epoch = new AtomicLong();
  private final AtomicInteger calls = new AtomicInteger();
  private final Map<String, Map<String, Object>> writes = new LinkedHashMap<>();
  private EmbeddingProviderLifecycle lifecycle;
  private IndexingCoordinator coordinator;
  private RunningRuntime runtime;
  private Runnable onFetch = () -> {};
  private Runnable onInference = () -> {};
  private Result result = Result.SUCCESS;
  private int windowCount = 96;
  private int documentCount = 100;

  private void setup(Route route) throws Exception {
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(2)).atPath(directory)
        .withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();
    coordinator = mock(IndexingCoordinator.class,
        withSettings().spiedInstance(runtime.indexingCoordinator()).defaultAnswer(call -> {
          if (call.getMethod().getName().equals("bulkDeleteEpoch")) return epoch.get();
          if (call.getMethod().getName().equals("publishEnrichmentBatch")) {
            List<Map.Entry<String, Map<String, Object>>> updates = call.getArgument(0);
            updates.forEach(entry -> writes.put(entry.getKey(), entry.getValue()));
            return new LuceneRuntimeTypes.EnrichmentPublicationReceipt(
                updates.stream().map(Map.Entry::getKey).toList(), List.of(), null);
          }
          return call.callRealMethod();
        }));
    when(dense.isAvailable()).thenAnswer(call -> available.get());
    when(dense.dimension()).thenReturn(2);
    when(dense.documentWindowCount(anyString())).thenAnswer(call -> route == Route.WINDOW ? windowCount : 1);
    when(dense.embedDocumentBatch(anyList())).thenAnswer(call -> {
      inference();
      List<String> texts = call.getArgument(0);
      return Collections.nCopies(texts.size(), new float[] {1, 0});
    });
    when(dense.embedDocument(anyString(), any())).thenAnswer(call -> { inference(); return new float[] {1, 0}; });
    when(dense.embedWithSpans(anyString(), any())).thenAnswer(call -> {
      inference(); return new EmbeddingService.ChunkedEmbedding(new float[] {1, 0}, List.of(), 1);
    });
    when(dense.embedDocumentWindows(anyString(), anyInt(), anyInt())).thenAnswer(call -> {
      inference();
      int from = call.getArgument(1);
      return new EmbeddingProvider.WindowSlice(
          Collections.nCopies(Math.min(32, windowCount - from), new float[] {1, 0}), from, windowCount);
    });
    when(sparse.encodeBatch(anyList())).thenAnswer(call -> {
      inference(); List<String> texts = call.getArgument(0);
      return Collections.nCopies(texts.size(), Map.of("term", 1f));
    });
    when(sparse.encode(anyString(), any())).thenAnswer(call -> { inference(); return Map.of("term", 1f); });
    when(ner.isAvailable()).thenAnswer(call -> available.get());
    when(ner.extractEntitiesBatch(anyList())).thenAnswer(call -> { inference(); return List.of(NerResult.EMPTY); });
    when(bge.encodeBatch(anyList())).thenAnswer(call -> {
      inference(); List<String> texts = call.getArgument(0);
      return Collections.nCopies(texts.size(), new BgeM3Output(new float[] {1, 0}, Map.of("term", 1f)));
    });
    when(bge.encode(anyString(), any())).thenAnswer(call -> {
      inference(); return new BgeM3Output(new float[] {1, 0}, Map.of("term", 1f));
    });
    lifecycle = new EmbeddingProviderLifecycle(signals, mock(JobQueue.class), runtime.indexCountOps(), runtime.commitOps());
    lifecycle.setEmbeddingProvider(dense);
    new BackfillScheduler(fields, coordinator, runtime.indexCountOps(), runtime.commitOps(), signals,
        IndexingPacing.unthrottled(), lifecycle, running, () -> null, () -> sparse, () -> bge, () -> ner, () -> null);
    boolean chunk = route.name().contains("CHUNK");
    String status = route.name().contains("NER") ? SchemaFields.NER_STATUS
        : route.name().contains("SPARSE") || route.name().contains("BGE") ? SchemaFields.SPLADE_STATUS
        : chunk ? SchemaFields.CHUNK_EMBEDDING_STATUS : SchemaFields.EMBEDDING_STATUS;
    var metadata = new HashMap<String, String>();
    metadata.put(status, "PENDING");
    metadata.put(status.replace("_status", "_retry_count"), "0");
    if (chunk) metadata.put(SchemaFields.IS_CHUNK, "true");
    var ids = java.util.stream.IntStream.range(0, documentCount).mapToObj(i -> "doc-" + i).toList();
    when(fields.queryDocIdsByField(anyString(), anyString(), anyInt())).thenAnswer(call -> {
      String field = call.getArgument(0);
      if (field.equals(SchemaFields.PARENT_DOC_ID)) return route == Route.SINGLE_PASS ? List.of("child") : List.of();
      return field.equals(status) ? ids : List.of();
    });
    when(fields.queryNonChunkDocIdsByField(anyString(), anyString(), anyInt())).thenAnswer(call ->
        call.getArgument(0).equals(status) ? ids : List.of());
    when(fields.getEnrichmentInputs(anyList())).thenAnswer(call -> {
      List<String> selected = call.getArgument(0);
      var inputs = new LinkedHashMap<String, DocumentFieldOps.EnrichmentInput>();
      for (String id : selected) inputs.put(id,
          new DocumentFieldOps.EnrichmentInput("content", Map.copyOf(metadata), null, Map.of()));
      onFetch.run();
      return inputs;
    });
    when(fields.getEnrichmentInputs(anyList(), anyLong())).thenAnswer(call -> fields.getEnrichmentInputs(call.getArgument(0)));
    when(signals.isEnergyReduced()).thenAnswer(call -> hold.get());
    when(signals.shouldYieldGpuBackfill()).thenAnswer(call -> hold.get());
  }

  private void inference() {
    calls.incrementAndGet();
    onInference.run();
    if (result == Result.FAILURE) throw new IllegalArgumentException("inference failure");
    if (result == Result.CANCELLATION) throw new CancellationException("inference cancelled");
  }

  private void stop(Stop stop) {
    switch (stop) {
      case SHUTDOWN -> running.set(false);
      case INTERRUPTION -> Thread.currentThread().interrupt();
      case HOLD -> hold.set(true);
      case SOURCE -> epoch.incrementAndGet();
      case TOKEN -> lifecycle.setEmbeddingProvider(mock(EmbeddingProvider.class));
      case UNAVAILABLE -> available.set(false);
    }
  }

  private EnrichmentAccounting.Summary run(Route route, RunningRuntime owned) {
    var log = LoggerFactory.getLogger(getClass());
    BooleanSupplier active = () -> running.get() && !hold.get();
    return switch (route) {
      case INDIVIDUAL_DENSE, INDIVIDUAL_CHUNK -> {
        var context = new EmbeddingBackfillOps.BackfillContext(fields, coordinator, owned.commitOps(),
            signals, IndexingPacing.unthrottled(), lifecycle::embeddingProvider, active, () -> true, 100, log);
        yield (route == Route.INDIVIDUAL_CHUNK ? EmbeddingBackfillOps.processChunkEmbeddingBackfill(context)
            : EmbeddingBackfillOps.processEmbeddingBackfill(context)).accounting();
      }
      case INDIVIDUAL_SPARSE, INTERLEAVED_SPARSE, INDIVIDUAL_SPARSE_CHUNK, INTERLEAVED_SPARSE_CHUNK ->
          SpladeBackfillOps.processSpladeBackfill(new SpladeBackfillOps.BackfillContext(fields, coordinator,
              owned.commitOps(), IndexingPacing.unthrottled(), () -> available.get() ? sparse : null,
              active, 100, !route.name().startsWith("INTERLEAVED"), route.name().contains("CHUNK"), log)).accounting();
      case INDIVIDUAL_NER -> NerBackfillOps.processNerBackfill(new NerBackfillOps.BackfillContext(fields,
          coordinator, owned.commitOps(), IndexingPacing.unthrottled(), () -> ner, active, 100, log)).accounting();
      case BGE, BGE_CHUNK -> BgeM3BackfillOps.processBgeM3Backfill(new BgeM3BackfillOps.BackfillContext(fields,
          coordinator, owned.commitOps(), signals, () -> available.get() ? bge : null,
          active, 100, false, route == Route.BGE_CHUNK, log)).accounting();
      default -> CombinedEnrichmentBackfillOps.processCombinedBackfill(
          new CombinedEnrichmentBackfillOps.BackfillContext(fields, coordinator, owned.commitOps(),
              IndexingPacing.unthrottled(), lifecycle::embeddingProvider,
              () -> route.name().contains("SPARSE") && available.get() ? sparse : null,
              () -> route == Route.NER ? ner : null, active, () -> true, 100, log,
              route == Route.CHUNK, route == Route.SPARSE_CHUNK, route == Route.SINGLE_PASS, 100,
              new ArrayDeque<>(), new ArrayDeque<>(), new int[] {0}, () -> false, () -> hold.get(),
              () -> false, new WindowedEmbedProgress())).accounting();
    };
  }

  static Stream<Arguments> stopMatrix() {
    return Arrays.stream(Route.values()).flatMap(route -> Arrays.stream(Stop.values()).flatMap(stop ->
        Stream.of(Arguments.of(route, stop, false), Arguments.of(route, stop, true))));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @MethodSource("stopMatrix")
  void everyHardStopBoundsEveryRoute(Route route, Stop stop, boolean afterFirst) throws Exception {
    setup(route);
    try (var owned = runtime) {
      if (afterFirst) onInference = () -> stop(stop);
      else onFetch = () -> stop(stop);
      var accounting = run(route, owned);
      assertEquals(afterFirst ? 1 : 0, calls.get(), "no unit starts after a hard stop");
      if (!afterFirst || stop == Stop.TOKEN || stop == Stop.UNAVAILABLE) assertTrue(writes.isEmpty());
      assertTrue(writes.values().stream().flatMap(update -> update.entrySet().stream()).noneMatch(entry ->
          entry.getKey().endsWith("_retry_count") && !entry.getValue().equals("0")), "stopped rows spend no retry");
      assertTrue(accounting.counters().entrySet().stream().noneMatch(entry ->
          entry.getKey().endsWith("failed_charged") && entry.getValue() > 0));
    } finally { Thread.interrupted(); }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {false, true})
  void individualNerUnavailabilityBoundsEveryDocument(boolean afterFirst) throws Exception {
    documentCount = 3;
    everyHardStopBoundsEveryRoute(Route.INDIVIDUAL_NER, Stop.UNAVAILABLE, afterFirst);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({"BGE,SOURCE", "BGE,INTERRUPTION", "BGE_CHUNK,SOURCE", "BGE_CHUNK,INTERRUPTION"})
  void bgeSourceInvalidationAndInterruptionPreventTheFirstBatch(Route route, Stop stop) throws Exception {
    everyHardStopBoundsEveryRoute(route, stop, false);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @EnumSource(value = Route.class, names = {"SINGLE_PASS", "WINDOW"})
  void successfulFinalDenseCallCannotPublishAfterUnavailability(Route route) throws Exception {
    setup(route);
    try (var owned = runtime) {
      windowCount = 2;
      onInference = () -> stop(Stop.UNAVAILABLE);
      var accounting = run(route, owned);
      assertEquals(1, calls.get());
      assertTrue(writes.isEmpty());
      assertEquals(1L, accounting.counters().getOrDefault("embed.computed", 0L));
      assertEquals(0L, accounting.counters().getOrDefault("embed.accepted", 0L));
      assertEquals(EnrichmentAccounting.Disposition.DEFERRED,
          accounting.tickets().get(new EnrichmentAccounting.Ticket("doc-0", EnrichmentAccounting.Stage.EMBED)));
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @EnumSource(value = Route.class, names = {"INDIVIDUAL_SPARSE", "INTERLEAVED_SPARSE",
      "INDIVIDUAL_SPARSE_CHUNK", "INTERLEAVED_SPARSE_CHUNK"})
  void sparseRetirementWinsOverCancellation(Route route) throws Exception {
    setup(route);
    try (var owned = runtime) {
      result = Result.CANCELLATION;
      onInference = () -> stop(Stop.TOKEN);
      var accounting = run(route, owned);
      assertEquals(1, calls.get());
      assertTrue(writes.isEmpty());
      assertTrue(accounting.tickets().values().stream().allMatch(value -> value == EnrichmentAccounting.Disposition.DEFERRED));
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({"BGE,TOKEN", "BGE,UNAVAILABLE", "BGE_CHUNK,TOKEN", "BGE_CHUNK,UNAVAILABLE"})
  void bgeRetirementPreventsTheFirstBatch(Route route, Stop stop) throws Exception {
    everyHardStopBoundsEveryRoute(route, stop, false);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @EnumSource(Result.class)
  void combinedNerRejectsEveryOutcomeWhenTheServiceBecomesUnavailable(Result outcome) throws Exception {
    retirementDefersSuccessFailureAndCancellationOnEveryRoute(Route.NER, Stop.UNAVAILABLE, outcome);
  }

  static Stream<Arguments> unavailableSparseOutcomes() {
    return Stream.of(Route.SPARSE, Route.SPARSE_CHUNK, Route.INDIVIDUAL_SPARSE,
        Route.INTERLEAVED_SPARSE, Route.INDIVIDUAL_SPARSE_CHUNK, Route.INTERLEAVED_SPARSE_CHUNK)
        .flatMap(route -> Stream.of(Result.SUCCESS, Result.CANCELLATION).map(outcome -> Arguments.of(route, outcome)));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @MethodSource("unavailableSparseOutcomes")
  void sparseUnavailabilityRejectsComputedSuccessAndCancellation(Route route, Result outcome) throws Exception {
    retirementDefersSuccessFailureAndCancellationOnEveryRoute(route, Stop.UNAVAILABLE, outcome);
  }

  static Stream<Arguments> fallbackRetirements() {
    return Stream.of(Route.DENSE, Route.CHUNK, Route.INDIVIDUAL_DENSE, Route.INDIVIDUAL_CHUNK,
        Route.INDIVIDUAL_SPARSE, Route.INTERLEAVED_SPARSE, Route.INDIVIDUAL_SPARSE_CHUNK,
        Route.INTERLEAVED_SPARSE_CHUNK, Route.BGE, Route.BGE_CHUNK).flatMap(route ->
        Stream.of(Stop.TOKEN, Stop.UNAVAILABLE).flatMap(stop -> Arrays.stream(Result.values())
            .map(outcome -> Arguments.of(route, stop, outcome))));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @MethodSource("fallbackRetirements")
  void fallbackRetirementDefersEveryOutcome(Route route, Stop stop, Result outcome) throws Exception {
    setup(route);
    try (var owned = runtime) {
      doReturn(List.of()).when(dense).embedDocumentBatch(anyList());
      doThrow(new IllegalArgumentException("batch needs fallback")).when(sparse).encodeBatch(anyList());
      doThrow(new IllegalArgumentException("batch needs fallback")).when(bge).encodeBatch(anyList());
      result = outcome;
      onInference = () -> stop(stop);
      var accounting = run(route, owned);
      assertEquals(1, calls.get(), "retirement ends per-document fallback too");
      assertTrue(writes.isEmpty());
      assertTrue(accounting.tickets().entrySet().stream().filter(entry ->
          entry.getKey().documentId().equals("doc-0")).allMatch(entry ->
              entry.getValue() == EnrichmentAccounting.Disposition.DEFERRED));
      if (outcome == Result.SUCCESS) assertTrue(accounting.counters().entrySet().stream().anyMatch(entry ->
          entry.getKey().endsWith(".computed") && entry.getValue() > 0));
    } finally { Thread.interrupted(); }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @EnumSource(value = Route.class, names = {"INDIVIDUAL_DENSE", "INDIVIDUAL_CHUNK"})
  void individualDenseFallbackUnavailabilityWinsOverCancellation(Route route) throws Exception {
    fallbackRetirementDefersEveryOutcome(route, Stop.UNAVAILABLE, Result.CANCELLATION);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @EnumSource(value = Route.class, names = {"INDIVIDUAL_DENSE", "INDIVIDUAL_CHUNK"})
  void individualDenseBatchUnavailabilityWinsOverCancellation(Route route) throws Exception {
    retirementDefersSuccessFailureAndCancellationOnEveryRoute(route, Stop.UNAVAILABLE, Result.CANCELLATION);
  }

  static Stream<Arguments> bgeFallbackStops() {
    return Stream.of(Route.BGE, Route.BGE_CHUNK).flatMap(route -> Arrays.stream(Stop.values())
        .map(stop -> Arguments.of(route, stop)));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @MethodSource("bgeFallbackStops")
  void bgeFallbackCannotStartAnotherUnitAfterAHardStop(Route route, Stop stop) throws Exception {
    setup(route);
    try (var owned = runtime) {
      result = Result.FAILURE;
      onInference = () -> stop(stop);
      var accounting = run(route, owned);
      assertEquals(1, calls.get(), "a failed batch does not authorize a fallback through a hard stop");
      assertTrue(writes.isEmpty());
      assertTrue(accounting.counters().entrySet().stream().noneMatch(entry ->
          entry.getKey().endsWith(".accepted") && entry.getValue() > 0));
    } finally { Thread.interrupted(); }
  }

  static Stream<Arguments> retiredFinalCalls() {
    return Arrays.stream(Route.values()).flatMap(route -> Stream.of(Stop.TOKEN, Stop.UNAVAILABLE).flatMap(stop ->
        Arrays.stream(Result.values()).map(result -> Arguments.of(route, stop, result))));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @MethodSource("retiredFinalCalls")
  void retirementDefersSuccessFailureAndCancellationOnEveryRoute(Route route, Stop stop, Result outcome) throws Exception {
    setup(route);
    try (var owned = runtime) {
      result = outcome;
      windowCount = 2;
      onInference = () -> stop(stop);
      var accounting = run(route, owned);
      assertEquals(1, calls.get());
      assertTrue(writes.isEmpty(), "retired results cannot reach publication");
      if (outcome == Result.SUCCESS) {
        assertTrue(accounting.counters().entrySet().stream().anyMatch(entry ->
            entry.getKey().endsWith(".computed") && entry.getValue() > 0),
            "retired successful computations remain counted");
      }
      assertTrue(accounting.counters().entrySet().stream().noneMatch(entry ->
          entry.getKey().endsWith(".accepted") && entry.getValue() > 0));
      for (var entry : accounting.tickets().entrySet()) {
        if (entry.getKey().documentId().equals("doc-0")) {
          assertEquals(EnrichmentAccounting.Disposition.DEFERRED, entry.getValue());
        }
      }
    } finally { Thread.interrupted(); }
  }
}
