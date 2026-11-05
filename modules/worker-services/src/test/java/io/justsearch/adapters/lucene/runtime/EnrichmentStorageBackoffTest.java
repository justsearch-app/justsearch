/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.bgem3.*;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.disambiguation.DisambiguationService;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.*;
import io.justsearch.indexerworker.loop.ops.*;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.ner.*;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.*;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Owner intent: E3c-b E3c-4/E3c-6; faults cross the actual ordered Lucene mutation loop. */
class EnrichmentStorageBackoffTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  private String previousMode;
  private final Map<Map<String, LongAdder>, Map<String, LongAdder>> previousMetrics = new IdentityHashMap<>();

  @BeforeEach @SuppressWarnings("unchecked")
  void saveMetrics() throws Exception {
    var metrics = OperationalMetrics.getInstance();
    previousMode = metrics.getBackfillMode();
    for (String name : List.of("enrichmentCompleted", "batchTimingMs", "batchTimingCount", "enrichmentOutcomes")) {
      var field = OperationalMetrics.class.getDeclaredField(name);
      field.setAccessible(true);
      var counters = (Map<String, LongAdder>) field.get(metrics);
      var saved = new HashMap<String, LongAdder>();
      counters.forEach((key, value) -> { var copy = new LongAdder(); copy.add(value.sum()); saved.put(key, copy); });
      previousMetrics.put(counters, saved);
    }
  }

  @AfterEach void restoreMetrics() {
    previousMetrics.forEach((counters, saved) -> { counters.clear(); counters.putAll(saved); });
    OperationalMetrics.getInstance().recordBackfillMode(previousMode);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"embed", "chunk", "ner"})
  void individualPublicationFailureHoldsItsStageAcrossServicePathsAndRecovers(String stage) throws Exception {
    try (var h = new Harness(stage)) {
      h.enable(stage);
      h.seedThree(stage);
      h.failSecondMutation();
      h.turn(stage);
      // E4b: NER units publish one document each, so the accepted prefix spans the receipts.
      var receipt = h.failedReceipt();
      assertEquals(1, h.accepted().size());
      assertEquals(List.of(), receipt.rejectedIds());
      assertTrue(receipt.storageFailures().isEmpty());
      assertNotNull(receipt.failure());
      assertThrows(RuntimeException.class, receipt::result);
      h.refresh();
      for (String id : List.of("a", "b", "c")) {
        if (h.accepted().contains(id)) continue;
        assertEquals("PENDING", h.value(id, status(stage)));
        assertEquals("2", h.value(id, retry(stage)), "unaccepted rows are not charged or reset");
      }
      int calls = h.calls.get();
      h.fail.set(false);
      // A different eligible stage can advance while the failed stage is held.
      if (!stage.equals("ner")) {
        h.nerEnabled = true;
        h.seed("other", Map.of(SchemaFields.NER_STATUS, "PENDING", SchemaFields.NER_RETRY_COUNT, "2"));
      } else {
        h.denseEnabled = true;
        h.seed("other", Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING", SchemaFields.EMBEDDING_RETRY_COUNT, "2"));
      }
      h.scheduler.runIdleCycle();
      h.scheduler.runInterleavedEnrichment(System.currentTimeMillis());
      h.refresh();
      assertEquals(calls, h.calls.get(), "no failed-stage unit may start through idle or busy service");
      assertEquals(stage.equals("ner") ? "COMPLETED" : "COMPLETED_EMPTY",
          h.value("other", stage.equals("ner") ? SchemaFields.EMBEDDING_STATUS : SchemaFields.NER_STATUS));
      h.clock.set(2 * LoopPacingPolicy.idleSleepMs() * 1_000_000L - 1);
      h.turn(stage);
      assertEquals(calls, h.calls.get(), "the stage stays held until the controlled deadline");
      h.clock.incrementAndGet();
      for (int cycle = 0; cycle < 12; cycle++) h.turn(stage);
      h.refresh();
      assertTrue(h.calls.get() > calls, "expiry revisits the unaccepted suffix");
      for (String id : List.of("a", "b", "c")) {
        assertEquals(stage.equals("ner") ? "COMPLETED_EMPTY" : "COMPLETED", h.value(id, status(stage)));
      }
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"embed", "chunk", "ner", "sparse", "bge"})
  void providerReplacementResetsOnlyThatStagesHold(String stage) throws Exception {
    try (var h = new Harness(stage)) {
      h.enable(stage); h.seedThree(stage); h.failSecondMutation(); h.turn(stage);
      int calls = h.calls.get();
      h.fail.set(false);
      h.turn(stage);
      assertEquals(calls, h.calls.get(), "the original provider is held before replacement");
      h.replace(stage);
      for (int cycle = 0; cycle < 6; cycle++) h.turn(stage);
      assertTrue(h.calls.get() > calls, "replacement provider must not inherit storage backoff");
      h.refresh();
      assertEquals(stage.equals("ner") ? "COMPLETED_EMPTY" : "COMPLETED", h.value("c", status(stage)));
    }
  }

  /**
   * E4b transfer of the combined owner-release case: dense windowed units publish their own
   * documents, so the accepted owner releases its slot, the complete unaccepted owner is retained
   * and recovers without recomputing its windows, only dense is held, and NER keeps advancing.
   */
  @Test void denseLaneFailureReleasesAcceptedOwnersAndRecoversCompleteOwnersWithoutRecomputing() throws Exception {
    try (var h = new Harness("combined")) {
      h.denseEnabled = true; h.nerEnabled = true;
      for (String id : List.of("a", "b", "c")) h.seed(id, Map.of(
          SchemaFields.EMBEDDING_STATUS, "PENDING", SchemaFields.EMBEDDING_RETRY_COUNT, "2",
          SchemaFields.NER_STATUS, "COMPLETED_EMPTY"));
      when(h.dense.documentWindowCount(anyString())).thenReturn(2);
      when(h.dense.embedDocumentWindows(anyString(), anyInt(), anyInt())).thenAnswer(call -> {
        h.calls.incrementAndGet();
        return new EmbeddingProvider.WindowSlice(List.of(vector(), vector()), 0, 2);
      });
      h.failSecondMutation(); h.scheduler.runIdleCycle();
      assertEquals(List.of("a"), h.accepted());
      assertNotNull(h.failedReceipt().failure());
      var progress = h.progress();
      assertEquals(List.of("b"), progress.owners(), "the accepted owner released its slot");
      assertTrue(progress.isComplete("b"));
      h.refresh();
      for (String id : List.of("b", "c")) assertEquals("2", h.value(id, SchemaFields.EMBEDDING_RETRY_COUNT));
      int denseCalls = h.calls.get();
      h.fail.set(false);
      for (int cycle = 0; cycle < 5; cycle++) {
        String id = "ner-" + cycle;
        h.seed(id, Map.of(SchemaFields.NER_STATUS, "PENDING", SchemaFields.NER_RETRY_COUNT, "2"));
        h.scheduler.runIdleCycle(); h.scheduler.runInterleavedEnrichment(System.currentTimeMillis()); h.refresh();
        assertEquals("COMPLETED_EMPTY", h.value(id, SchemaFields.NER_STATUS));
        assertEquals(denseCalls, h.calls.get());
        assertEquals(1, progress.owners().size(), "backoff must not grow retained state");
      }
      // Replacing a peer provider does not reset dense: each provider owns only its own backoff.
      h.replace("ner"); h.scheduler.runInterleavedEnrichment(System.currentTimeMillis());
      assertEquals(denseCalls, h.calls.get());
      h.clock.addAndGet(120_000_000_000L);
      for (int cycle = 0; cycle < 12; cycle++) h.scheduler.runIdleCycle();
      h.refresh();
      for (String id : List.of("a", "b", "c")) assertEquals("COMPLETED", h.value(id, SchemaFields.EMBEDDING_STATUS));
      assertTrue(progress.owners().isEmpty(), "all accepted complete owners release retained state");
      assertEquals(denseCalls + 1, h.calls.get(),
          "the complete unaccepted owner recovers without recomputing; only never-computed c runs");
      // The recovered stage still charges exactly one retry for a later genuine row failure.
      h.seed("row-error", Map.of(SchemaFields.NER_STATUS, "PENDING", SchemaFields.NER_RETRY_COUNT, "1"));
      when(h.ner.extractEntitiesBatch(anyList())).thenThrow(new IllegalArgumentException("bad row"));
      h.scheduler.runIdleCycle(); h.refresh();
      assertEquals("2", h.value("row-error", SchemaFields.NER_RETRY_COUNT));
    }
  }

  /**
   * E4b: every lane publishes its own stage (BGE-M3 its dense and sparse pair together), so a
   * failed write holds all and only the stages it carried in its unaccepted rows, while the other
   * lanes keep advancing the same rows. Supersedes the bundled combined write.
   */
  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"dense-sparse", "dense-ner", "ner-sparse", "all", "bge-ner"})
  void lanePublicationHoldsAllAndOnlyStagesInItsUnacceptedRows(String stages) throws Exception {
    try (var h = new Harness("attribution")) {
      h.bgeEnabled = stages.equals("bge-ner");
      h.denseEnabled = !stages.equals("ner-sparse") && !h.bgeEnabled;
      h.sparseEnabled = !stages.equals("dense-ner") && !h.bgeEnabled;
      h.nerEnabled = !stages.equals("dense-sparse");
      var statuses = new ArrayList<String>();
      if (h.denseEnabled || h.bgeEnabled) statuses.add(SchemaFields.EMBEDDING_STATUS);
      if (h.sparseEnabled || h.bgeEnabled) statuses.add(SchemaFields.SPLADE_STATUS);
      if (h.nerEnabled) statuses.add(SchemaFields.NER_STATUS);
      for (String id : List.of("a", "b", "c")) {
        var fields = new HashMap<String, Object>();
        for (String status : statuses) { fields.put(status, "PENDING"); fields.put(retryOf(status), "2"); }
        if (!h.nerEnabled) { fields.put(SchemaFields.NER_STATUS, "COMPLETED_EMPTY"); fields.put(SchemaFields.NER_RETRY_COUNT, "2"); }
        h.seed(id, fields);
      }
      h.failSecondMutation(); h.scheduler.runIdleCycle(); h.refresh();
      var failed = h.failedReceipt();
      assertEquals(List.of("a"), failed.acceptedIds());
      Set<String> held = h.statusesOf(failed);
      assertEquals(h.bgeEnabled ? Set.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.SPLADE_STATUS) : Set.of(statuses.getFirst()),
          held, "a lane write carries only the stages that lane owns");
      for (String status : statuses) {
        for (String id : List.of("b", "c")) {
          if (held.contains(status)) {
            assertEquals("PENDING", h.value(id, status), id + " " + status);
            assertEquals("2", h.value(id, retryOf(status)), "unaccepted rows are not charged");
          } else {
            assertEquals(done(status), h.value(id, status), "independent lanes keep advancing the same rows");
          }
        }
      }
      int denseCalls = h.calls.get(), sparseCalls = h.sparseCalls.get(), nerCalls = h.nerCalls.get();
      h.fail.set(false);
      h.seed("later", Map.of(statuses.getLast(), "PENDING"));
      h.scheduler.runIdleCycle(); h.scheduler.runInterleavedEnrichment(System.currentTimeMillis()); h.refresh();
      if (held.contains(SchemaFields.EMBEDDING_STATUS)) assertEquals(denseCalls, h.calls.get(), "held dense stage waits");
      if (held.contains(SchemaFields.SPLADE_STATUS) && !h.bgeEnabled) assertEquals(sparseCalls, h.sparseCalls.get(), "held sparse stage waits");
      if (held.contains(SchemaFields.NER_STATUS)) assertEquals(nerCalls, h.nerCalls.get(), "held NER stage waits");
      if (!held.contains(statuses.getLast())) assertEquals(done(statuses.getLast()), h.value("later", statuses.getLast()));
      h.clock.addAndGet(120_000_000_000L);
      for (int cycle = 0; cycle < 8; cycle++) h.scheduler.runIdleCycle();
      h.refresh();
      for (String status : statuses) assertEquals(done(status), h.value("c", status), status);
    }
  }

  @Test void aStageRepresentedOnlyInTheAcceptedPrefixRemainsEligible() throws Exception {
    try (var h = new Harness("prefix-attribution")) {
      h.denseEnabled = true; h.sparseEnabled = true;
      h.seed("a", Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING", SchemaFields.EMBEDDING_RETRY_COUNT, "2"));
      for (String id : List.of("b", "c")) h.seed(id, Map.of(SchemaFields.SPLADE_STATUS, "PENDING", SchemaFields.SPLADE_RETRY_COUNT, "2"));
      h.failSecondMutation(); h.scheduler.runIdleCycle();
      assertEquals(List.of("a"), h.receipts.getFirst().acceptedIds());
      int sparseCalls = h.sparseCalls.get();
      h.fail.set(false);
      h.seed("new-dense", Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING", SchemaFields.EMBEDDING_RETRY_COUNT, "2"));
      h.scheduler.runIdleCycle(); h.scheduler.runInterleavedEnrichment(System.currentTimeMillis()); h.refresh();
      assertEquals("COMPLETED", h.value("new-dense", SchemaFields.EMBEDDING_STATUS), "accepted-only stage stays eligible");
      assertEquals(sparseCalls, h.sparseCalls.get(), "unaccepted sparse stage waits for expiry");
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"embed", "chunk", "sparse", "bge"})
  void systemicPublicationInPerDocumentFallbackStopsBeforeAnotherNativeCall(String stage) throws Exception {
    try (var h = new Harness("fallback")) {
      h.enable(stage); h.seedThree(stage);
      if (stage.equals("embed") || stage.equals("chunk")) {
        when(h.dense.embedDocumentBatch(anyList())).thenReturn(null);
        when(h.dense.embedDocument(anyString(), any())).thenAnswer(call -> { h.calls.incrementAndGet(); return vector(); });
      } else if (stage.equals("sparse")) {
        when(h.sparse.encodeBatch(anyList())).thenThrow(new IllegalArgumentException("batch fallback"));
        when(h.sparse.encode(anyString(), any())).thenAnswer(call -> { h.calls.incrementAndGet(); return Map.of("term", 1f); });
      } else {
        when(h.bge.encodeBatch(anyList())).thenThrow(new IllegalArgumentException("batch fallback"));
        when(h.bge.encode(anyString(), any())).thenAnswer(call -> { h.calls.incrementAndGet(); return new BgeM3Output(vector(), Map.of("term", 1f)); });
      }
      h.calls.set(0);
      h.failSecondMutation(); h.turn(stage); h.refresh();
      assertEquals(2, h.calls.get(), "storage failure must escape per-document inference fallback");
      assertEquals(2, h.receipts.size(), "there is no retry write after a systemic publication failure");
      assertEquals("2", h.value("b", retry(stage))); assertEquals("2", h.value("c", retry(stage)));
      h.fail.set(false); h.scheduler.runIdleCycle(); h.scheduler.runInterleavedEnrichment(System.currentTimeMillis());
      assertEquals(2, h.calls.get(), "later entry points share the fallback write's backoff");
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"sparse", "bge", "interleaved", "bge-interleaved"})
  void individualSparseStorageBackoffAlreadyHoldsOnTheBase(String stage) throws Exception {
    try (var h = new Harness("sparse-preservation")) {
      h.enable(stage); h.seedThree(stage); h.failSecondMutation(); h.turn(stage);
      assertEquals(List.of("a"), h.receipts.getFirst().acceptedIds());
      assertNotNull(h.receipts.getFirst().failure()); h.refresh();
      for (String id : List.of("b", "c")) assertEquals("2", h.value(id, SchemaFields.SPLADE_RETRY_COUNT));
      int calls = h.calls.get(); h.fail.set(false);
      h.scheduler.runInterleavedEnrichment(System.currentTimeMillis()); h.scheduler.runIdleCycle();
      assertEquals(calls, h.calls.get(), "existing individual sparse backoff is shared with busy service");
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {false, true})
  void rowFailurePublishesHealthyPeersChargesOnceAndSetsNoBackoff(boolean combined) throws Exception {
    try (var h = new Harness("row-local")) {
      h.nerEnabled = true; h.denseEnabled = combined;
      for (String id : List.of("a", "b", "c")) h.seed(id, Map.of(SchemaFields.NER_STATUS, "PENDING", SchemaFields.NER_RETRY_COUNT, "1"));
      when(h.ner.extractEntitiesBatch(anyList())).thenAnswer(call -> {
        if (((List<?>) call.getArgument(0)).contains("body b")) throw new IllegalArgumentException("one bad row");
        return List.of(NerResult.EMPTY);
      });
      h.scheduler.runIdleCycle(); h.refresh();
      assertEquals("COMPLETED_EMPTY", h.value("a", SchemaFields.NER_STATUS));
      assertEquals("COMPLETED_EMPTY", h.value("c", SchemaFields.NER_STATUS));
      assertEquals("PENDING", h.value("b", SchemaFields.NER_STATUS));
      assertEquals("2", h.value("b", SchemaFields.NER_RETRY_COUNT));
      assertTrue(h.receipts.stream().allMatch(receipt -> receipt.failure() == null));
      when(h.ner.extractEntitiesBatch(anyList())).thenReturn(List.of(NerResult.EMPTY));
      h.seed("d", Map.of(SchemaFields.NER_STATUS, "PENDING", SchemaFields.NER_RETRY_COUNT, "2"));
      for (int cycle = 0; cycle < 3; cycle++) h.scheduler.runIdleCycle();
      h.refresh();
      assertEquals("COMPLETED_EMPTY", h.value("d", SchemaFields.NER_STATUS), "row-local failure leaves NER eligible on the same clock");
    }
  }

  @Test void expiredStageEventuallyWrapsItsCursorWhileTheIndependentStageKeepsAdvancing() throws Exception {
    try (var h = new Harness("cursor-recovery")) {
      h.denseEnabled = true; h.nerEnabled = true; h.batchSize = 2;
      for (int i = 0; i < 6; i++) {
        h.seed("dense-" + i, Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING", SchemaFields.EMBEDDING_RETRY_COUNT, "2",
            SchemaFields.NER_STATUS, "COMPLETED_EMPTY"));
      }
      h.failSecondMutation(); h.scheduler.runIdleCycle();
      int calls = h.calls.get(); h.fail.set(false);
      for (int i = 0; i < 6; i++) {
        h.seed("ner-" + i, Map.of(SchemaFields.NER_STATUS, "PENDING", SchemaFields.NER_RETRY_COUNT, "2"));
      }
      for (int cycle = 0; cycle < 8; cycle++) h.scheduler.runIdleCycle();
      h.refresh();
      assertEquals(calls, h.calls.get(), "the failed stage's cursor may not admit more work during backoff");
      for (int i = 0; i < 6; i++) assertEquals("COMPLETED_EMPTY", h.value("ner-" + i, SchemaFields.NER_STATUS));
      assertEquals("2", h.value("dense-1", SchemaFields.EMBEDDING_RETRY_COUNT));
      h.clock.set(2 * LoopPacingPolicy.idleSleepMs() * 1_000_000L);
      for (int cycle = 0; cycle < 12; cycle++) h.scheduler.runIdleCycle();
      h.refresh();
      for (int i = 0; i < 6; i++) assertEquals("COMPLETED", h.value("dense-" + i, SchemaFields.EMBEDDING_STATUS),
          "the unaccepted row behind the cursor is eventually revisited");
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"sparse", "bge", "interleaved", "bge-interleaved"})
  void publicationCancellationNeverBacksOffOrChargesTheUnacceptedSuffix(String stage) throws Exception {
    try (var h = new Harness("publication-cancellation")) {
      h.enable(stage); h.seedThree(stage);
      h.publicationFailure = new java.util.concurrent.CancellationException("cancelled publication");
      h.failSecondMutation(); h.turn(stage); h.refresh();
      assertEquals(List.of("a"), h.receipts.getFirst().acceptedIds());
      assertInstanceOf(java.util.concurrent.CancellationException.class, h.receipts.getFirst().failure());
      for (String id : List.of("b", "c")) {
        assertEquals("PENDING", h.value(id, SchemaFields.SPLADE_STATUS));
        assertEquals("2", h.value(id, SchemaFields.SPLADE_RETRY_COUNT));
      }
      int calls = h.calls.get(); h.fail.set(false);
      h.turn(stage); h.refresh();
      assertTrue(h.calls.get() > calls, "cancellation is never a systemic failure or a retry hold");
      assertEquals("COMPLETED", h.value("c", SchemaFields.SPLADE_STATUS));
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"embed", "chunk", "ner", "sparse", "bge", "interleaved", "bge-interleaved"})
  void interruptedWriterCancellationDoesNotBecomeStorageBackoff(String stage) throws Exception {
    boolean interrupted = Thread.currentThread().isInterrupted();
    try (var h = new Harness("interrupted-writer")) {
      h.enable(stage); h.seedThree(stage);
      h.beforePublicationFailure = () -> Thread.currentThread().interrupt();
      h.publicationFailure = new java.nio.channels.ClosedByInterruptException();
      h.failSecondMutation();
      try { h.turn(stage); } finally { Thread.interrupted(); }
      assertCancelledSuffixRecovers(h, stage);
    } finally {
      Thread.interrupted();
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  static java.util.stream.Stream<Arguments> interruptedIoFailures() {
    return java.util.stream.Stream.of("embed", "chunk", "ner", "sparse", "bge", "interleaved", "bge-interleaved")
        .flatMap(stage -> java.util.stream.Stream.of("interrupted-io", "closed-by-interrupt", "file-lock")
            .map(failure -> Arguments.of(stage, failure)));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @MethodSource("interruptedIoFailures")
  void wrappedInterruptedIoWithoutThreadFlagNeverBacksOff(String stage, String failure) throws Exception {
    try (var h = new Harness("interrupted-io")) {
      h.enable(stage); h.seedThree(stage);
      IOException cause = switch (failure) {
        case "interrupted-io" -> new java.io.InterruptedIOException();
        case "closed-by-interrupt" -> new java.nio.channels.ClosedByInterruptException();
        default -> new java.nio.channels.FileLockInterruptionException();
      };
      h.publicationFailure = new RuntimeException("wrapped interruption", cause);
      h.failSecondMutation(); h.turn(stage);
      assertCancelledSuffixRecovers(h, stage);
    }
  }

  static java.util.stream.Stream<Arguments> publicationHardStops() {
    return java.util.stream.Stream.of("embed", "chunk", "ner", "sparse", "bge", "interleaved", "bge-interleaved")
        .flatMap(stage -> java.util.stream.Stream.of("shutdown", "hold", "source", "provider", "authority")
            .map(stop -> Arguments.of(stage, stop)));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @MethodSource("publicationHardStops")
  void publicationFailureDuringHardStopNeverBacksOff(String stage, String stop) throws Exception {
    try (var h = new Harness("stopped-writer")) {
      h.enable(stage); h.seedThree(stage);
      h.beforePublicationFailure = switch (stop) {
        case "shutdown" -> () -> h.running.set(false);
        case "hold" -> () -> h.held.set(true);
        case "source" -> () -> h.runtime.session().bulkDeleteEpoch.incrementAndGet();
        case "authority" -> () -> h.runtime.indexingCoordinator().transitionEnrichmentAuthority(() -> {});
        default -> () -> {
          if (stage.equals("embed") || stage.equals("chunk")) h.denseEnabled = false;
          else if (stage.equals("ner")) when(h.ner.isAvailable()).thenReturn(false);
          else if (stage.startsWith("bge")) h.bgeEnabled = false;
          else h.sparseEnabled = false;
        };
      };
      h.failSecondMutation(); h.turn(stage);
      h.running.set(true); h.held.set(false); h.enable(stage);
      when(h.ner.isAvailable()).thenReturn(true);
      assertCancelledSuffixRecovers(h, stage);
    }
  }

  private void assertCancelledSuffixRecovers(Harness h, String stage) throws Exception {
    h.refresh();
    assertEquals(List.of("a"), h.accepted());
    assertNotNull(h.failedReceipt().failure());
    assertEquals(stage.equals("ner") ? "COMPLETED_EMPTY" : "COMPLETED", h.value("a", status(stage)));
    for (String id : List.of("b", "c")) {
      assertEquals("PENDING", h.value(id, status(stage)));
      assertEquals("2", h.value(id, retry(stage)));
    }
    h.fail.set(false);
    for (int cycle = 0; cycle < 6; cycle++) h.turn(stage);
    h.refresh();
    for (String id : List.of("b", "c")) {
      assertEquals(stage.equals("ner") ? "COMPLETED_EMPTY" : "COMPLETED", h.value(id, status(stage)),
          "cancellation leaves the suffix eligible on the same clock");
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"embed", "chunk", "ner", "sparse", "bge", "interleaved", "bge-interleaved"})
  void closedWriterWithoutHardStopRemainsSystemic(String stage) throws Exception {
    try (var h = new Harness("closed-writer")) {
      h.enable(stage); h.seedThree(stage);
      h.publicationFailure = new org.apache.lucene.store.AlreadyClosedException("writer closed");
      h.failSecondMutation(); h.turn(stage); h.refresh();
      assertEquals(List.of("a"), h.receipts.getFirst().acceptedIds());
      int calls = h.calls.get(); h.fail.set(false);
      for (int cycle = 0; cycle < 6; cycle++) h.turn(stage);
      h.refresh();
      assertEquals(calls, h.calls.get(), "closed writer without cancellation starts systemic backoff");
      for (String id : List.of("b", "c")) {
        assertEquals("PENDING", h.value(id, status(stage)));
        assertEquals("2", h.value(id, retry(stage)));
      }
      h.clock.addAndGet(120_000_000_000L);
      for (int cycle = 0; cycle < 6; cycle++) h.turn(stage);
      h.refresh();
      assertEquals(stage.equals("ner") ? "COMPLETED_EMPTY" : "COMPLETED", h.value("c", status(stage)));
    }
  }

  @Test void acceptedPrefixReceiptsAlreadyHoldOnTheBase() throws Exception {
    try (var h = new Harness("receipt")) {
      h.enable("ner"); h.seedThree("ner"); h.failSecondMutation(); h.scheduler.runIdleCycle();
      var receipt = h.failedReceipt();
      assertEquals(List.of("a"), h.accepted()); assertTrue(receipt.rejectedIds().isEmpty());
      assertNotNull(receipt.failure()); h.refresh();
      assertEquals("COMPLETED_EMPTY", h.value("a", SchemaFields.NER_STATUS));
      for (String id : List.of("b", "c")) {
        assertEquals("PENDING", h.value(id, SchemaFields.NER_STATUS));
        assertEquals("2", h.value(id, SchemaFields.NER_RETRY_COUNT));
      }
    }
  }

  private static String status(String stage) {
    return stage.equals("ner") ? SchemaFields.NER_STATUS : stage.equals("chunk") ? SchemaFields.CHUNK_EMBEDDING_STATUS
        : stage.equals("embed") ? SchemaFields.EMBEDDING_STATUS : SchemaFields.SPLADE_STATUS;
  }
  private static String retry(String stage) {
    return stage.equals("ner") ? SchemaFields.NER_RETRY_COUNT : stage.equals("chunk") ? SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT
        : stage.equals("embed") ? SchemaFields.EMBEDDING_RETRY_COUNT : SchemaFields.SPLADE_RETRY_COUNT;
  }
  private static float[] vector() { return new float[] {1, 0, 0, 0}; }
  private static String retryOf(String status) {
    return status.equals(SchemaFields.NER_STATUS) ? SchemaFields.NER_RETRY_COUNT
        : status.equals(SchemaFields.SPLADE_STATUS) ? SchemaFields.SPLADE_RETRY_COUNT : SchemaFields.EMBEDDING_RETRY_COUNT;
  }
  private static String done(String status) {
    return status.equals(SchemaFields.NER_STATUS) ? "COMPLETED_EMPTY" : "COMPLETED";
  }

  private final class Harness implements AutoCloseable {
    final RunningRuntime runtime;
    final AtomicLong clock = new AtomicLong();
    final AtomicBoolean fail = new AtomicBoolean();
    final AtomicBoolean running = new AtomicBoolean(true), held = new AtomicBoolean();
    Runnable beforePublicationFailure = () -> {};
    Throwable publicationFailure = new IOException("injected systemic storage outage");
    final AtomicInteger writes = new AtomicInteger(), calls = new AtomicInteger(), sparseCalls = new AtomicInteger(), nerCalls = new AtomicInteger();
    final List<LuceneRuntimeTypes.EnrichmentPublicationReceipt> receipts = new ArrayList<>();
    final Map<LuceneRuntimeTypes.EnrichmentPublicationReceipt, Set<String>> receiptFields = new IdentityHashMap<>();
    EmbeddingProvider dense;
    SpladeEncoder sparse;
    NerService ner;
    BgeM3Encoder bge;
    boolean denseEnabled, sparseEnabled, nerEnabled, bgeEnabled;
    final BackfillScheduler scheduler;
    final LifecycleSnapshot original;
    String measured;
    int batchSize = 100;

    Harness(String name) throws Exception {
      var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(4).fields());
      for (String field : List.of(SchemaFields.SOURCE_SHA256, SchemaFields.EMBEDDING_RETRY_COUNT,
          SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_RETRY_COUNT, SchemaFields.NER_STATUS, SchemaFields.NER_RETRY_COUNT)) {
        if (fields.stream().noneMatch(existing -> existing.id().equals(field))) fields.add(new FieldDef(field,
            field.endsWith("_count") ? "long" : "keyword", true, true, List.of("filter"), null, null, false));
      }
      fields.add(new FieldDef(SchemaFields.SPLADE, "splade", false, false, List.of(), null, null, false, "reset-status:splade_status"));
      runtime = IndexSchema.fromCatalog(new FieldCatalogDef("storage-backoff", fields))
          .atPath(directory.resolve(name)).withExecutorRegistrations(testLuceneExecutors()).open();
      runtime.commitOps().stopCommitTimer(); runtime.commitOps().suspendNrtRefresh();
      original = runtime.session().snapshot;
      var writer = mock(IndexWriter.class, org.mockito.AdditionalAnswers.delegatesTo(original.writer()));
      doAnswer(call -> {
        if (fail.get() && writes.incrementAndGet() == 2) {
          beforePublicationFailure.run();
          throw publicationFailure;
        }
        return original.writer().updateDocument(call.getArgument(0), call.getArgument(1));
      }).when(writer).updateDocument(any(Term.class), any());
      runtime.session().snapshot = new LifecycleSnapshot(original.directory(), writer, original.searcherManager(),
          original.indexPath(), original.ephemeralPath(), original.indexAnalyzer());
      replace("embed"); replace("sparse"); replace("ner"); replace("bge");
      var realCoordinator = runtime.indexingCoordinator();
      var coordinator = mock(IndexingCoordinator.class, org.mockito.AdditionalAnswers.delegatesTo(realCoordinator));
      doAnswer(call -> {
        List<Map.Entry<String, Map<String, Object>>> updates = call.getArgument(0);
        var receipt = realCoordinator.publishEnrichmentBatch(updates, call.getArgument(1), call.getArgument(2));
        receipts.add(receipt);
        receiptFields.put(receipt, updates.stream().flatMap(update -> update.getValue().keySet().stream())
            .collect(java.util.stream.Collectors.toSet()));
        return receipt;
      }).when(coordinator).publishEnrichmentBatch(anyList(), anyMap(), any());
      var signals = mock(WorkerSignalBus.class);
      when(signals.isIndexingHeld()).thenAnswer(call -> held.get());
      var lifecycle = mock(EmbeddingProviderLifecycle.class);
      when(lifecycle.embeddingProvider()).thenAnswer(call -> dense);
      when(lifecycle.allowEmbeddingWrites()).thenReturn(true);
      ResolvedConfig config = mock(ResolvedConfig.class, RETURNS_DEEP_STUBS);
      when(config.ai().backfillPacing()).thenAnswer(call -> {
        var d = ResolvedConfig.Ai.BackfillPacing.DEFAULTS;
        return new ResolvedConfig.Ai.BackfillPacing(d.pollBatchSize(), batchSize, batchSize,
            d.disambiguationBackfillBatchSize(), batchSize, d.enrichmentInterleaveBatchSize(),
            0L, d.commitIntervalMs(), d.maxDocsBeforeCommit(),
            d.chunkSlotsPerBatch(), d.bgeM3BackfillBatchSize(), d.bgeM3InterleaveBatchSize(),
            d.foregroundDutyPct(), d.foregroundCooldownMs());
      });
      when(config.rag().chunkVectorsEnabled()).thenReturn(true);
      when(config.rag().chunkSpladeEnabled()).thenReturn(true);
      var ctor = BackfillScheduler.class.getDeclaredConstructor(DocumentFieldOps.class, IndexingCoordinator.class,
          IndexCountOps.class, CommitOps.class, WorkerSignalBus.class, IndexingPacing.class, EmbeddingProviderLifecycle.class,
          AtomicBoolean.class, Supplier.class, Supplier.class, Supplier.class, Supplier.class, Supplier.class, LongSupplier.class);
      ctor.setAccessible(true);
      scheduler = ctor.newInstance(runtime.documentFieldOps(), coordinator, runtime.indexCountOps(), runtime.commitOps(),
          signals, IndexingPacing.unthrottled(), lifecycle, running, (Supplier<ResolvedConfig>) () -> config,
          (Supplier<SpladeEncoder>) () -> sparseEnabled ? sparse : null,
          (Supplier<BgeM3Encoder>) () -> bgeEnabled ? bge : null,
          (Supplier<NerService>) () -> nerEnabled ? ner : null,
          (Supplier<DisambiguationService>) () -> null, (LongSupplier) clock::get);
    }

    void enable(String stage) {
      measured = stage;
      denseEnabled = stage.equals("embed") || stage.equals("chunk"); nerEnabled = stage.equals("ner");
      sparseEnabled = stage.equals("sparse") || stage.equals("interleaved"); bgeEnabled = stage.startsWith("bge");
    }
    void replace(String stage) throws Exception {
      if (stage.equals("embed") || stage.equals("chunk")) {
        dense = mock(EmbeddingProvider.class);
        when(dense.isAvailable()).thenAnswer(call -> denseEnabled);
        when(dense.dimension()).thenReturn(4); when(dense.documentWindowCount(anyString())).thenReturn(1);
        when(dense.embedDocumentBatch(anyList())).thenAnswer(call -> {
          if (measured == null || measured.equals("embed") || measured.equals("chunk")) calls.incrementAndGet();
          return Collections.nCopies(((List<?>) call.getArgument(0)).size(), vector());
        });
      } else if (stage.equals("sparse")) {
        sparse = mock(SpladeEncoder.class);
        when(sparse.encodeBatch(anyList())).thenAnswer(call -> {
          sparseCalls.incrementAndGet();
          if (measured != null && (measured.equals("sparse") || measured.equals("interleaved"))) calls.incrementAndGet();
          return Collections.nCopies(((List<?>) call.getArgument(0)).size(), Map.of("term", 1f));
        });
      } else if (stage.equals("ner")) {
        ner = mock(NerService.class); when(ner.isAvailable()).thenReturn(true);
        when(ner.extractEntitiesBatch(anyList())).thenAnswer(call -> {
          nerCalls.incrementAndGet();
          if ("ner".equals(measured)) calls.incrementAndGet();
          return Collections.nCopies(((List<?>) call.getArgument(0)).size(), NerResult.EMPTY);
        });
      } else {
        bge = mock(BgeM3Encoder.class);
        when(bge.encodeBatch(anyList())).thenAnswer(call -> {
          calls.incrementAndGet(); return Collections.nCopies(((List<?>) call.getArgument(0)).size(), new BgeM3Output(vector(), Map.of("term", 1f)));
        });
      }
    }
    void turn(String stage) { if (stage.contains("interleaved")) scheduler.runInterleavedEnrichment(System.currentTimeMillis()); else scheduler.runIdleCycle(); }
    void failSecondMutation() { writes.set(0); fail.set(true); }
    /** Rows accepted across the turn's receipts (NER units publish one document each). */
    List<String> accepted() { return receipts.stream().flatMap(receipt -> receipt.acceptedIds().stream()).toList(); }
    /** The one failed publication, which ends its lane's service. */
    LuceneRuntimeTypes.EnrichmentPublicationReceipt failedReceipt() {
      var failed = receipts.stream().filter(receipt -> receipt.failure() != null).toList();
      assertEquals(1, failed.size(), "exactly one publication failed: " + receipts);
      return failed.getFirst();
    }
    Set<String> statusesOf(LuceneRuntimeTypes.EnrichmentPublicationReceipt receipt) {
      var statuses = new HashSet<>(receiptFields.get(receipt));
      statuses.retainAll(Set.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.CHUNK_EMBEDDING_STATUS,
          SchemaFields.SPLADE_STATUS, SchemaFields.NER_STATUS));
      return statuses;
    }
    void refresh() throws IOException { runtime.commitOps().maybeRefreshBlocking(); }
    String value(String id, String field) { return runtime.documentFieldOps().getDocumentField(id, field); }
    void seedThree(String stage) throws Exception {
      if (stage.equals("chunk")) seed("parent", Map.of());
      for (String id : List.of("a", "b", "c")) {
        Map<String, Object> fields = new HashMap<>(); fields.put(status(stage), "PENDING"); fields.put(retry(stage), "2");
        if (stage.equals("chunk")) {
          fields.put(SchemaFields.IS_CHUNK, "true"); fields.put(SchemaFields.PARENT_DOC_ID, "parent");
          fields.put(SchemaFields.CHUNK_START_CHAR, "0"); fields.put(SchemaFields.CHUNK_END_CHAR, "11");
          fields.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex("body parent"));
        }
        seed(id, fields);
      }
      receipts.clear();
    }
    void seed(String id, Map<String, Object> overrides) throws Exception {
      var fields = new HashMap<String, Object>();
      fields.put(SchemaFields.DOC_ID, id); fields.put(SchemaFields.DOC_UID, "uid-" + id); fields.put(SchemaFields.CONTENT, "body " + id);
      fields.put(SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex("body " + id));
      fields.put(SchemaFields.SOURCE_SHA256, "source-" + id); fields.putAll(overrides);
      runtime.indexingCoordinator().indexSingle(new IndexDocument(fields)); refresh();
    }
    WindowedEmbedProgress progress() throws Exception {
      var field = BackfillScheduler.class.getDeclaredField("windowedEmbedProgress"); field.setAccessible(true);
      return (WindowedEmbedProgress) field.get(scheduler);
    }
    @Override public void close() { runtime.session().snapshot = original; runtime.close(); }
  }
}
