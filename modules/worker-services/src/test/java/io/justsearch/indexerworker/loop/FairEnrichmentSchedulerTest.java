/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.bgem3.*;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.*;
import io.justsearch.indexerworker.inference.LocalSessionAcquisition;
import io.justsearch.indexerworker.loop.ops.*;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.ner.*;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.ort.SessionTemporarilyUnavailableException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * E4b scheduler scenarios through real Lucene publication, deterministic elapsed time (fake
 * encoders advance the scheduler clock) and no sleeps. Scheduler-only APIs are reached
 * reflectively so the before state compiles and fails by assertion.
 */
class FairEnrichmentSchedulerTest extends LuceneExecutorTestBase {
  private static final long SECOND = 1_000_000_000L;
  /** Keeps the base's wall-clock interval anchor from hiding a due busy call. */
  private static final long NOW = 1_000_000_000_000_000L;

  @TempDir Path directory;
  private String previousMode;
  private final Map<Map<String, LongAdder>, Map<String, LongAdder>> previousMetrics =
      new IdentityHashMap<>();
  private final Logger schedulerLog = (Logger) LoggerFactory.getLogger(BackfillScheduler.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private Level previousLevel;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void saveProcessState() throws Exception {
    var metrics = OperationalMetrics.getInstance();
    previousMode = metrics.getBackfillMode();
    for (String name :
        List.of("enrichmentCompleted", "batchTimingMs", "batchTimingCount", "enrichmentOutcomes")) {
      var field = OperationalMetrics.class.getDeclaredField(name);
      field.setAccessible(true);
      var counters = (Map<String, LongAdder>) field.get(metrics);
      var saved = new HashMap<String, LongAdder>();
      counters.forEach((key, value) -> {
        var copy = new LongAdder();
        copy.add(value.sum());
        saved.put(key, copy);
      });
      previousMetrics.put(counters, saved);
    }
    previousLevel = schedulerLog.getLevel();
    schedulerLog.setLevel(Level.DEBUG);
    logs.start();
    schedulerLog.addAppender(logs);
  }

  @AfterEach
  void restoreProcessState() {
    schedulerLog.detachAppender(logs);
    logs.stop();
    schedulerLog.setLevel(previousLevel);
    previousMetrics.forEach((counters, saved) -> {
      counters.clear();
      counters.putAll(saved);
    });
    OperationalMetrics.getInstance().recordBackfillMode(previousMode);
  }

  // ==================== D1 ====================

  @ParameterizedTest
  @ValueSource(strings = {"separate", "dense", "ner", "bge"})
  void D1_continuingIngestionServesEveryContinuouslyEligibleLane(String configuration) throws Exception {
    try (var h = new Harness()) {
      h.denseEnabled = configuration.equals("separate") || configuration.equals("dense");
      h.sparseEnabled = configuration.equals("separate");
      h.nerEnabled = !configuration.equals("dense");
      h.bgeEnabled = configuration.equals("bge");
      h.pendingIngest.set(true);
      backlog(h, 40);
      var turns = h.observe();
      for (int call = 1; call <= 12; call++) busy(h, call);
      if (h.denseEnabled) {
        assertEquals("COMPLETED", h.value("parent-000", SchemaFields.EMBEDDING_STATUS),
            "busy calls must admit dense parents while ingestion stays pending");
        assertEquals("COMPLETED", h.value("chunk-000", SchemaFields.CHUNK_EMBEDDING_STATUS),
            "busy calls must admit dense chunks while parents remain pending");
      }
      if (h.nerEnabled) {
        assertEquals("COMPLETED_EMPTY", h.value("parent-000", SchemaFields.NER_STATUS),
            "busy calls must admit NER while ingestion stays pending");
      }
      if (h.sparseEnabled || h.bgeEnabled) {
        assertEquals("COMPLETED", h.value("parent-000", SchemaFields.SPLADE_STATUS));
        assertEquals("COMPLETED", h.value("chunk-000", SchemaFields.SPLADE_STATUS));
      }
      if (h.bgeEnabled) {
        assertEquals("COMPLETED", h.value("parent-000", SchemaFields.EMBEDDING_STATUS),
            "BGE-M3 writes the parent's dense vector with its sparse postings");
      }
      var expected = new TreeSet<String>();
      if (h.denseEnabled) expected.addAll(List.of("DENSE_PARENT", "DENSE_CHUNK"));
      if (h.sparseEnabled) expected.addAll(List.of("SPARSE_PARENT", "SPARSE_CHUNK"));
      if (h.nerEnabled) expected.add("NER");
      if (h.bgeEnabled) expected.add("BGE_M3");
      var counts = new TreeMap<String, Integer>();
      for (var turn : turns) counts.merge(lane(turn), 1, Integer::sum);
      assertEquals(expected, counts.keySet(), "every continuously eligible lane is admitted: " + counts);
      int floor = 12 / expected.size() - 1;
      counts.forEach((lane, count) -> assertTrue(count >= floor,
          lane + " received " + count + " of 12 turns: opportunities are bounded per lane"));
      assertTrue(h.pendingIngest.get());
      assertTrue(h.runtime.indexCountOps().countByField(SchemaFields.NER_STATUS, "PENDING") > 0
          || h.runtime.indexCountOps().countByField(SchemaFields.EMBEDDING_STATUS, "PENDING") > 0,
          "service is promised per lane, not completion of the queue");
    }
  }

  // ==================== D2 ====================

  @Test
  void D2_pendingIngestionLetsOneAdmittedUnitFinishThenStopsFurtherIdleUnits() throws Exception {
    try (var h = new Harness()) {
      h.denseEnabled = true; h.sparseEnabled = true;
      for (int i = 0; i < 20; i++) {
        h.seed("parent-%03d".formatted(i), "body " + i, Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING",
            SchemaFields.EMBEDDING_RETRY_COUNT, "1", SchemaFields.SPLADE_STATUS, "PENDING"));
      }
      h.refresh();
      h.pendingIngest.set(true);
      assertTrue(h.scheduler.runIdleCycle(), "pending ingestion must permit one admitted unit");
      h.refresh();
      verify(h.dense, times(1)).embedDocumentBatch(anyList());
      verify(h.sparse, never()).encodeBatch(anyList());
      for (int i = 0; i < 8; i++) {
        assertEquals("COMPLETED", h.value("parent-%03d".formatted(i), SchemaFields.EMBEDDING_STATUS));
      }
      assertEquals("PENDING", h.value("parent-008", SchemaFields.EMBEDDING_STATUS));
      assertEquals("1", h.value("parent-008", SchemaFields.EMBEDDING_RETRY_COUNT),
          "ingestion pressure alone charges no retry");
      assertEquals(0L, OperationalMetrics.getInstance().getEnrichmentOutcomes()
          .getOrDefault("embed.cancelled", 0L), "ingestion pressure is not a cancellation");
    }
  }

  @Test
  void D2_ingestionArrivingDuringAMultiCallDocumentKeepsTheUnitsRetainedProgress() throws Exception {
    try (var h = new Harness()) {
      h.denseEnabled = true;
      h.seed("long", "long body", Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING",
          SchemaFields.EMBEDDING_RETRY_COUNT, "1"));
      h.refresh();
      var offsets = new ArrayList<Integer>();
      when(h.dense.documentWindowCount(anyString())).thenReturn(96);
      when(h.dense.embedDocumentWindows(anyString(), anyInt(), anyInt())).thenAnswer(call -> {
        int from = call.getArgument(1);
        offsets.add(from);
        h.pendingIngest.set(true);
        // A native call observes cancellation here; ingestion pressure must not be one.
        LocalSessionAcquisition.checkCancellation();
        return new EmbeddingProvider.WindowSlice(Collections.nCopies(32, vector()), from, 96);
      });
      h.scheduler.runIdleCycle();
      assertEquals(List.of(0), offsets, "the started unit finishes; no second idle unit starts");
      assertEquals(32, h.progress().nextWindow("long", "long body"),
          "the finished unit's windows are retained, not cancelled");
      busy(h, 1);
      assertEquals(List.of(0, 32), offsets, "a due busy call still admits one turn");
      h.refresh();
      assertEquals("1", h.value("long", SchemaFields.EMBEDDING_RETRY_COUNT));
      assertEquals("PENDING", h.value("long", SchemaFields.EMBEDDING_STATUS));
    }
  }

  // ==================== D3 ====================

  @Test
  void D3_anOverrunningEarlyLaneCannotReclaimTheFirstSlotAcrossCyclesAndCallers() throws Exception {
    try (var h = new Harness()) {
      h.denseEnabled = true; h.sparseEnabled = true; h.nerEnabled = true;
      when(h.config.rag().chunkVectorsEnabled()).thenReturn(false);
      when(h.config.rag().chunkSpladeEnabled()).thenReturn(false);
      for (int i = 0; i < 30; i++) {
        h.seed("parent-%03d".formatted(i), "body " + i, Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING",
            SchemaFields.SPLADE_STATUS, "PENDING", SchemaFields.NER_STATUS, "PENDING"));
      }
      h.refresh();
      when(h.dense.embedDocumentBatch(anyList())).thenAnswer(call -> {
        h.clock.addAndGet(6 * SECOND); // every dense unit overruns the 5 s cycle
        return Collections.nCopies(((List<?>) call.getArgument(0)).size(), vector());
      });
      var turns = h.observe();
      h.scheduler.runIdleCycle(); h.refresh();
      busy(h, 1);
      h.scheduler.runIdleCycle(); h.refresh();
      assertEquals(30, h.runtime.indexCountOps().countByField(SchemaFields.NER_STATUS, "COMPLETED_EMPTY"),
          "lanes owed service run after the dense overrun instead of starving behind it");
      assertEquals(30, h.runtime.indexCountOps().countByField(SchemaFields.SPLADE_STATUS, "COMPLETED"));
      verify(h.dense, times(2)).embedDocumentBatch(anyList());
      var lanes = turns.stream().map(FairEnrichmentSchedulerTest::lane).toList();
      assertEquals("DENSE_PARENT", lanes.getFirst());
      assertNotEquals("DENSE_PARENT", lanes.get(1), "the busy caller serves owed lanes next");
      assertEquals("DENSE_PARENT", lanes.getLast(),
          "dense returns only after the lanes it overran have caught up: " + lanes);
      assertEquals(2, Collections.frequency(lanes, "DENSE_PARENT"));
    }
  }

  @Test
  void D3_zeroTimeOutcomesAndTiesRotateWithoutMonopolising() throws Exception {
    try (var h = new Harness()) {
      h.sparseEnabled = true; h.nerEnabled = true;
      when(h.config.rag().chunkSpladeEnabled()).thenReturn(false);
      seedSparseAndNer(h, 40);
      var turns = h.observe();
      for (int call = 1; call <= 6; call++) busy(h, call);
      assertEquals(List.of("SPARSE_PARENT", "NER", "SPARSE_PARENT", "NER", "SPARSE_PARENT", "NER"),
          turns.stream().map(FairEnrichmentSchedulerTest::lane).toList(),
          "zero-time turns are charged 10 ms, and ties advance past the chosen lane");
      turns.forEach(turn -> assertEquals(10_000_000L, charge(turn)));
    }
  }

  @Test
  void D3_unequalCostsShareChargedServiceNotTurnCounts() throws Exception {
    try (var h = new Harness()) {
      h.sparseEnabled = true; h.nerEnabled = true;
      when(h.config.rag().chunkSpladeEnabled()).thenReturn(false);
      seedSparseAndNer(h, 60);
      when(h.sparse.encodeBatch(anyList())).thenAnswer(call -> {
        h.clock.addAndGet(30_000_000L);
        return Collections.nCopies(((List<?>) call.getArgument(0)).size(), Map.of("term", 1f));
      });
      var turns = h.observe();
      for (int call = 1; call <= 9; call++) busy(h, call);
      assertEquals(List.of("SPARSE_PARENT", "NER", "NER", "NER", "SPARSE_PARENT", "NER", "NER", "NER",
          "SPARSE_PARENT"), turns.stream().map(FairEnrichmentSchedulerTest::lane).toList());
    }
  }

  @Test
  void D3_reentryKeepsDebtAndNeverBurstsAfterAbsenceOrProviderReplacement() throws Exception {
    try (var h = new Harness()) {
      h.sparseEnabled = true; h.nerEnabled = true;
      when(h.config.rag().chunkSpladeEnabled()).thenReturn(false);
      seedSparseAndNer(h, 240);
      when(h.sparse.encodeBatch(anyList())).thenAnswer(call -> {
        h.clock.addAndGet(100_000_000L); // one 100 ms overrun of debt
        return Collections.nCopies(((List<?>) call.getArgument(0)).size(), Map.of("term", 1f));
      });
      var turns = h.observe();
      busy(h, 1); // sparse: 100 ms
      h.sparseEnabled = false; // long absence of the indebted lane
      for (int call = 2; call <= 4; call++) busy(h, call); // NER: 30 ms
      h.replace("sparse"); // provider replacement does not forgive the debt
      h.sparseEnabled = true;
      for (int call = 5; call <= 14; call++) busy(h, call);
      var lanes = turns.stream().map(FairEnrichmentSchedulerTest::lane).toList();
      assertEquals(List.of("SPARSE_PARENT", "NER", "NER", "NER", "NER", "NER", "NER", "NER", "NER", "NER",
          "NER", "SPARSE_PARENT", "NER", "SPARSE_PARENT"), lanes,
          "outstanding debt is kept on re-entry: NER is served until it has caught up, then they alternate");

      // An absent lane with no debt enters at the current minimum: no catch-up burst.
      turns.clear();
      h.nerEnabled = false;
      for (int call = 15; call <= 20; call++) busy(h, call);
      h.nerEnabled = true;
      for (int call = 21; call <= 26; call++) busy(h, call);
      lanes = turns.stream().map(FairEnrichmentSchedulerTest::lane).toList();
      assertEquals(List.of("NER", "SPARSE_PARENT", "NER", "SPARSE_PARENT", "NER", "SPARSE_PARENT"),
          lanes.subList(6, 12), "a returning lane alternates instead of reclaiming its absence");
    }
  }

  // ==================== D4b ====================

  @Test
  void D4b_bothSparseLanesGetOpportunitiesUnderContinuingParentArrivals() throws Exception {
    try (var h = new Harness()) {
      h.sparseEnabled = true;
      when(h.config.rag().chunkSpladeEnabled()).thenReturn(false);
      h.seed("zz-parent", "body parent", Map.of(SchemaFields.SPLADE_STATUS, "COMPLETED_EMPTY"));
      for (int i = 0; i < 3; i++) {
        h.chunk("chunk-" + i, "zz-parent", "body parent", Map.of(SchemaFields.SPLADE_STATUS, "PENDING"));
      }
      int call = 0;
      for (; call < 3; call++) { arrivals(h, call); busy(h, call + 1); }
      assertEquals("PENDING", h.value("chunk-0", SchemaFields.SPLADE_STATUS),
          "chunk sparse work is not selected while chunk SPLADE is disabled");
      when(h.config.rag().chunkSpladeEnabled()).thenReturn(true);
      for (; call < 7; call++) { arrivals(h, call); busy(h, call + 1); }
      assertEquals("COMPLETED", h.value("chunk-0", SchemaFields.SPLADE_STATUS),
          "continuing lower-ID parent arrivals cannot take the chunk lane's opportunity");
      assertEquals("COMPLETED", h.value("a-05-9", SchemaFields.SPLADE_STATUS),
          "the parent lane keeps its own opportunities too");
      assertTrue(h.runtime.indexCountOps().countByField(SchemaFields.SPLADE_STATUS, "PENDING") > 0);
    }
  }

  // ==================== D5b ====================

  @ParameterizedTest
  @ValueSource(strings = {"dense", "bge"})
  void D5b_oneProviderHoldCoversEveryLaneItServesThroughBothCallers(String provider) throws Exception {
    try (var h = new Harness()) {
      h.enable(provider);
      h.nerEnabled = true;
      String status = provider.equals("bge") ? SchemaFields.SPLADE_STATUS : SchemaFields.EMBEDDING_STATUS;
      String retry = provider.equals("bge") ? SchemaFields.SPLADE_RETRY_COUNT : SchemaFields.EMBEDDING_RETRY_COUNT;
      h.seed("parent", "body parent", Map.of(status, "PENDING", retry, "2",
          SchemaFields.NER_STATUS, "COMPLETED_EMPTY"));
      h.chunk("chunk", "parent", "body parent", provider.equals("bge")
          ? Map.of(SchemaFields.SPLADE_STATUS, "PENDING", SchemaFields.SPLADE_RETRY_COUNT, "2")
          : Map.of(SchemaFields.CHUNK_EMBEDDING_STATUS, "PENDING", SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "2"));
      h.refresh();
      h.unavailable.set(true);
      busy(h, 1);
      assertEquals(1, h.failedCalls.get());
      for (int call = 2; call <= 7; call++) {
        h.seed("ner-" + call, "peer " + call, Map.of(SchemaFields.NER_STATUS, "PENDING"));
        h.refresh();
        if (call % 2 == 0) h.scheduler.runIdleCycle(); else busy(h, call);
        h.refresh();
        assertEquals("COMPLETED_EMPTY", h.value("ner-" + call, SchemaFields.NER_STATUS), "peers continue");
      }
      assertEquals(1, h.failedCalls.get(),
          "one acquisition failure holds every lane of that provider through both callers");
      assertEquals("2", h.value("parent", retry), "acquisition failure charges no row retry");
      assertEquals("2", h.value("chunk", provider.equals("bge")
          ? SchemaFields.SPLADE_RETRY_COUNT : SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));
      if (provider.equals("bge")) verify(h.bge, never()).encode(anyString(), any());
      else verify(h.dense, never()).embedDocument(anyString(), any());
      h.unavailable.set(false);
      h.clock.addAndGet(600 * SECOND);
      for (int call = 8; call <= 12; call++) busy(h, call);
      assertEquals("COMPLETED", h.value("parent", status), "expiry restores the provider for busy service");
      assertEquals("COMPLETED", h.value("chunk", provider.equals("bge")
          ? SchemaFields.SPLADE_STATUS : SchemaFields.CHUNK_EMBEDDING_STATUS));
    }
  }

  // ==================== D7 ====================

  @Test
  void D7_retainedClassificationAndWindowsResumeThroughEitherCaller() throws Exception {
    try (var h = new Harness()) {
      h.denseEnabled = true;
      var offsets = longDocument(h, "long body");
      var turns = h.observe();
      h.scheduler.runIdleCycle();
      busy(h, 1);
      h.scheduler.runIdleCycle();
      busy(h, 2);
      h.refresh();
      assertEquals(List.of(0, 32, 64), offsets, "busy and idle turns share the retained window offsets");
      verify(h.dense, times(1)).embedWithSpans(anyString(), any(int[][].class));
      assertEquals("COMPLETED", h.value("parent", SchemaFields.EMBEDDING_STATUS));
      assertEquals(List.of("idle", "busy", "idle", "busy"), turns.stream().map(FairEnrichmentSchedulerTest::caller).toList());
      assertTrue(h.progress().owners().isEmpty());
    }
  }

  @Test
  void D7_replacedContentInvalidatesRetainedWorkForBothCallers() throws Exception {
    try (var h = new Harness()) {
      h.denseEnabled = true;
      h.sparseEnabled = true; // no sparse work; keeps the base on its single-pass dense path
      var offsets = longDocument(h, "long body");
      h.scheduler.runIdleCycle();
      busy(h, 1);
      assertEquals(List.of(0), offsets);
      h.seed("parent", "replaced long body", Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING",
          SchemaFields.EMBEDDING_RETRY_COUNT, "2"));
      h.refresh();
      h.scheduler.runIdleCycle();
      busy(h, 2);
      assertEquals(List.of(0, 0), offsets, "the replacement restarts instead of resuming stale windows");
      verify(h.dense, times(2)).embedWithSpans(anyString(), any(int[][].class));
      assertEquals(0, h.progress().nextWindow("parent", "long body"));
    }
  }

  // ==================== D8 ====================

  @Test
  void D8_advancingRetainedWindowsOverManyCyclesAreNotAStall() throws Exception {
    try (var h = new Harness()) {
      h.denseEnabled = true; h.sparseEnabled = true;
      h.seed("long", "long body", Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING",
          SchemaFields.SPLADE_STATUS, "COMPLETED_EMPTY", SchemaFields.NER_STATUS, "COMPLETED_EMPTY"));
      h.refresh();
      when(h.dense.documentWindowCount(anyString())).thenReturn(320);
      when(h.dense.embedDocumentWindows(anyString(), anyInt(), anyInt())).thenAnswer(call -> {
        h.clock.addAndGet(6 * SECOND);
        int from = call.getArgument(1);
        return new EmbeddingProvider.WindowSlice(Collections.nCopies(32, vector()), from, 320);
      });
      for (int cycle = 0; cycle < 7; cycle++) { h.scheduler.runIdleCycle(); h.refresh(); }
      assertEquals(7 * 32, h.progress().nextWindow("long", "long body"));
      assertEquals("PENDING", h.value("long", SchemaFields.EMBEDDING_STATUS));
      assertEquals(List.of(), warnings(), "retained advancement over more than five cycles is not a stall");
      assertTrue(OperationalMetrics.getInstance().getEnrichmentOutcomes()
          .getOrDefault("embed.window_advances", 0L) >= 7, "advancement stays visible without completion");
    }
  }

  @Test
  void D8_holdsAndBackoffAreNotUnsuccessfulService() throws Exception {
    try (var h = new Harness()) {
      h.enable("ner");
      h.seed("doc", "body", Map.of(SchemaFields.NER_STATUS, "PENDING"));
      h.refresh();
      h.unavailable.set(true);
      for (int cycle = 0; cycle < 8; cycle++) {
        h.scheduler.runIdleCycle();
        h.clock.addAndGet(600 * SECOND); // every hold expires and fails again
      }
      assertTrue(h.failedCalls.get() >= 6);
      assertEquals(List.of(), warnings());
    }
  }

  // ==================== D9 ====================

  @Test
  void D9_smallTurnsUseTheThresholdsRetryAFailedCommitAndDrainAtIdle() throws Exception {
    try (var h = new Harness(true)) {
      h.denseEnabled = true;
      for (int i = 0; i < 17; i++) {
        h.seed("parent-%02d".formatted(i), "body " + i, Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING"));
      }
      h.refresh();
      h.runtime.commitOps().commitAndTrack();
      var commits = new ArrayList<CommitReason>();
      h.commitOps.setCommitCompletedListener(commits::add);
      var d = ResolvedConfig.Ai.BackfillPacing.DEFAULTS;
      when(h.config.ai().backfillPacing()).thenReturn(new ResolvedConfig.Ai.BackfillPacing(
          d.pollBatchSize(), d.embeddingBackfillBatchSize(), d.nerBackfillBatchSize(),
          d.disambiguationBackfillBatchSize(), d.spladeBackfillBatchSize(), 10, 5_000L, 600_000L, 16,
          d.chunkSlotsPerBatch(), d.bgeM3BackfillBatchSize(), d.bgeM3InterleaveBatchSize(),
          d.foregroundDutyPct(), d.foregroundCooldownMs()));
      doThrow(new IllegalStateException("commit failed once")).doCallRealMethod()
          .when(h.commitOps).commitAndTrack(CommitReason.BACKFILL_COMBINED);
      busy(h, 1);
      assertEquals("COMPLETED", h.value("parent-00", SchemaFields.EMBEDDING_STATUS), "a busy dense turn publishes");
      assertEquals(List.of(), commits, "a small turn below the thresholds does not commit");
      busy(h, 2);
      assertEquals(List.of(), commits, "the failed threshold commit keeps its dirty documents");
      busy(h, 3);
      assertEquals(List.of(CommitReason.BACKFILL_COMBINED), commits, "the next check retries the commit");
      assertDurable(h, "parent-16", "COMPLETED");
      h.seed("late", "late body", Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING"));
      h.refresh();
      busy(h, 4);
      assertEquals(1, commits.size());
      h.scheduler.runIdleCycle();
      assertEquals(List.of(CommitReason.BACKFILL_COMBINED, CommitReason.BACKFILL_COMBINED_FINAL), commits,
          "the idle drain commits the remaining dirty document with the timer off");
      assertDurable(h, "late", "COMPLETED");
    }
  }

  // ==================== D10 ====================

  @Test
  void D10_bgeIsOneUnifiedOwnerChargedOnceBesideSeparateNer() throws Exception {
    try (var h = new Harness()) {
      h.bgeEnabled = true; h.denseEnabled = true; h.nerEnabled = true;
      for (int i = 0; i < 3; i++) {
        h.seed("parent-" + i, "parent body " + i, Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING",
            SchemaFields.SPLADE_STATUS, "PENDING", SchemaFields.NER_STATUS, "PENDING"));
      }
      h.chunk("chunk-sparse", "parent-0", "parent body 0", Map.of(SchemaFields.SPLADE_STATUS, "PENDING"));
      // Asymmetric pending state: dense-only chunk work keeps today's separate dense chunk lane.
      h.chunk("chunk-dense", "parent-1", "parent body 1", Map.of(SchemaFields.SPLADE_STATUS, "COMPLETED_EMPTY",
          SchemaFields.CHUNK_EMBEDDING_STATUS, "PENDING"));
      h.refresh();
      var turns = h.observe();
      for (int call = 1; call <= 6; call++) busy(h, call);
      h.scheduler.runIdleCycle();
      h.refresh();
      for (int i = 0; i < 3; i++) {
        assertEquals("COMPLETED_EMPTY", h.value("parent-" + i, SchemaFields.NER_STATUS),
            "NER keeps its own lane beside the unified owner");
        assertEquals("COMPLETED", h.value("parent-" + i, SchemaFields.EMBEDDING_STATUS));
        assertEquals("COMPLETED", h.value("parent-" + i, SchemaFields.SPLADE_STATUS));
      }
      assertEquals("COMPLETED", h.value("chunk-sparse", SchemaFields.SPLADE_STATUS));
      assertEquals("COMPLETED", h.value("chunk-sparse", SchemaFields.CHUNK_EMBEDDING_STATUS));
      assertEquals("COMPLETED", h.value("chunk-dense", SchemaFields.CHUNK_EMBEDDING_STATUS));
      verify(h.dense, never()).embedDocumentBatch(argThat(texts -> texts.stream()
          .anyMatch(text -> text.startsWith("parent body") && !text.equals("parent body 1"))));
      long bgeTurns = turns.stream().filter(turn -> lane(turn).equals("BGE_M3")).count();
      assertTrue(bgeTurns > 0);
      verify(h.bge, times((int) bgeTurns)).encodeBatch(anyList());
      assertTrue(turns.stream().noneMatch(turn -> Set.of("DENSE_PARENT", "SPARSE_PARENT", "SPARSE_CHUNK")
          .contains(lane(turn))), "no second selectable alias of the unified owner");
    }
  }

  // ==================== D11 ====================

  @Test
  void D11_activityGaugeAndPerTurnRecordsDistinguishWindowAdvancementFromCompletion() throws Exception {
    try (var h = new Harness()) {
      h.enable("dense");
      h.seed("long", "long body", Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING"));
      h.refresh();
      when(h.dense.documentWindowCount(anyString())).thenReturn(96);
      when(h.dense.embedDocumentWindows(anyString(), anyInt(), anyInt())).thenAnswer(call -> {
        h.acquire("dense");
        return new EmbeddingProvider.WindowSlice(Collections.nCopies(32, vector()), call.getArgument(1), 96);
      });
      var turns = h.observe();
      long completedBefore = OperationalMetrics.getInstance().getEnrichmentCompleted().getOrDefault("embed", 0L);
      busy(h, 1);
      assertEquals("active", OperationalMetrics.getInstance().getBackfillMode(),
          "a busy call that admitted a lane turn reports active");
      assertEquals(1, turns.size());
      Map<String, Long> counters = counters(turns.getFirst());
      assertTrue(advancement(turns.getFirst()));
      assertEquals(1L, counters.get("embed.window_advances"));
      assertNull(counters.get("embed.published"), "window advancement is not published completion");
      assertEquals(completedBefore, OperationalMetrics.getInstance().getEnrichmentCompleted().getOrDefault("embed", 0L));
      assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == Level.DEBUG
          && event.getFormattedMessage().startsWith("Enrichment turn lane=DENSE_PARENT caller=busy")),
          "one DEBUG line per turn names lane and caller");
      h.unavailable.set(true);
      h.scheduler.runIdleCycle();
      assertEquals("active", OperationalMetrics.getInstance().getBackfillMode(), "a held unit was admitted");
      h.scheduler.runIdleCycle();
      assertEquals("idle", OperationalMetrics.getInstance().getBackfillMode(),
          "a call whose only lane is held admits nothing and reports idle");
      h.unavailable.set(false);
      h.clock.addAndGet(600 * SECOND);
      busy(h, 2);
      busy(h, 3);
      assertEquals("COMPLETED", h.value("long", SchemaFields.EMBEDDING_STATUS));
      h.scheduler.runIdleCycle();
      assertEquals("idle", OperationalMetrics.getInstance().getBackfillMode(), "nothing pending is idle");
    }
  }

  // ==================== #1095 through the scheduler ====================

  /**
   * #1095 transferred to the surviving path, under the original fixture costs (classification 80
   * or 4,900 ms, window slice 270 ms, chunk batch 70 ms, 5 s cycles): 100 late-chunked long
   * parents and 50 pending chunks. Chunks publish at least once in every three consecutive cycles
   * while parents are pending, every cycle advances parents, retries are never charged, and a
   * fallback classification is always followed by its retained windows before another one.
   */
  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"80,false", "4900,false", "4900,true"})
  void P1095_chunksAdvanceWithinThreeCyclesUnderTheOriginalLongDocumentCosts(
      long probeMs, boolean successful) throws Exception {
    try (var h = new Harness()) {
      h.denseEnabled = true;
      h.sparseEnabled = true; // no sparse work; the base runs its #1095 combined path
      when(h.config.ai().embedding().lateChunkingEnabled()).thenReturn(true);
      var calls = new ArrayList<String>();
      for (int i = 0; i < 100; i++) {
        String id = "long-%02d".formatted(i);
        String content = (successful ? "SHORT:" : "LONG:") + id;
        h.seed(id, content, Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING"));
        h.seed(id + "-chunk", "", Map.of(SchemaFields.IS_CHUNK, "true", SchemaFields.PARENT_DOC_ID, id,
            SchemaFields.CHUNK_START_CHAR, "0", SchemaFields.CHUNK_END_CHAR, "" + content.length(),
            SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(content)));
        if (i < 50) {
          int start = content.indexOf(':') + 1;
          h.seed("chunk-%02d".formatted(i), "", Map.of(SchemaFields.IS_CHUNK, "true",
              SchemaFields.PARENT_DOC_ID, id, SchemaFields.CHUNK_START_CHAR, "" + start,
              SchemaFields.CHUNK_END_CHAR, "" + content.length(),
              SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(content),
              SchemaFields.CHUNK_EMBEDDING_STATUS, "PENDING"));
        }
      }
      h.refresh();
      when(h.dense.documentWindowCount(anyString())).thenAnswer(call ->
          ((String) call.getArgument(0)).startsWith("LONG:") ? 42 : 1);
      when(h.dense.embedWithSpans(anyString(), any(int[][].class))).thenAnswer(call -> {
        String text = call.getArgument(0);
        calls.add("single:" + text);
        h.clock.addAndGet(probeMs * 1_000_000L);
        return successful ? new EmbeddingService.ChunkedEmbedding(vector(), List.of(), 0) : null;
      });
      when(h.dense.embedDocumentWindows(anyString(), anyInt(), anyInt())).thenAnswer(call -> {
        String text = call.getArgument(0);
        int from = call.getArgument(1), max = call.getArgument(2);
        calls.add("window:" + text + "@" + from);
        h.clock.addAndGet(270_000_000L);
        return new EmbeddingProvider.WindowSlice(
            Collections.nCopies(Math.max(0, Math.min(max, 42 - from)), vector()), from, 42);
      });
      when(h.dense.embedDocumentBatch(anyList())).thenAnswer(call -> {
        List<String> texts = call.getArgument(0);
        calls.add("batch:" + texts.size());
        h.clock.addAndGet(70_000_000L);
        return Collections.nCopies(texts.size(), vector());
      });
      var chunkCompletions = new ArrayList<>(List.of(0));
      for (int cycle = 0; cycle < 6; cycle++) {
        int callsBefore = calls.size();
        int parentsBefore = h.runtime.indexCountOps().countByField(SchemaFields.EMBEDDING_STATUS, "COMPLETED");
        h.scheduler.runIdleCycle();
        h.refresh();
        int parentsAfter = h.runtime.indexCountOps().countByField(SchemaFields.EMBEDDING_STATUS, "COMPLETED");
        // The original S2 condition: cheap classifications leave every cycle parent progress.
        if (probeMs < 5_000 - 270 * 2 - 70) {
          assertTrue(parentsAfter > parentsBefore || calls.subList(callsBefore, calls.size()).stream()
              .anyMatch(call -> call.startsWith("window:")), "each cycle publishes or advances parents");
        }
        chunkCompletions.add(h.runtime.indexCountOps().countByField(SchemaFields.CHUNK_EMBEDDING_STATUS, "COMPLETED"));
        int c = chunkCompletions.size() - 1;
        if (c >= 3 && chunkCompletions.get(c - 3) < 50) {
          assertTrue(chunkCompletions.get(c) > chunkCompletions.get(c - 3),
              "chunks publish at least once in every three consecutive cycles: " + chunkCompletions);
        }
      }
      for (int i = 0; i < 100; i++) {
        String retries = h.value("long-%02d".formatted(i), SchemaFields.EMBEDDING_RETRY_COUNT);
        assertTrue(retries == null || retries.equals("0"), "null fallback and deferral never charge retries");
      }
      assertTrue(h.runtime.indexCountOps().countByField(SchemaFields.EMBEDDING_STATUS, "PENDING") > 0,
          "the fixture keeps a parent backlog");
      if (!successful) {
        String classified = null;
        for (String call : calls) {
          if (call.startsWith("single:")) {
            assertNull(classified, "a classification follows the previous one's retained windows: " + calls);
            classified = call.substring("single:".length());
          } else if (classified != null && call.startsWith("window:" + classified + "@")) {
            classified = null;
          }
        }
      }
    }
  }

  // ==================== Architectural deletion ====================

  @Test
  void architecturalDeletionLeavesNoModeRoutingProbeOrDrainGates() throws Exception {
    String scheduler = Files.readString(Path.of(
        "src/main/java/io/justsearch/indexerworker/loop/BackfillScheduler.java"));
    for (String retired : List.of("useCombined", "availCount", "processCombinedBackfill",
        "recordBackfillMode(\"combined\")", "recordBackfillMode(\"individual\")", "noteWorkSet",
        "workSetSignature", "EMBED_BUDGET_SHARE", "runIndividualBackfills", "pendingDocEmbeddings",
        "embeddingsReady", "runInterleavedSplade", "withNrtSuspended")) {
      assertFalse(scheduler.contains(retired), "retired scheduler machinery remains: " + retired);
    }
    String combined = Files.readString(Path.of(
        "src/main/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOps.java"));
    assertFalse(combined.contains("batchesSinceCommit()[0] >= 5"),
        "no probe-specific five-writing-batch publication commit");
  }

  // ==================== Helpers ====================

  private static void busy(Harness h, long call) {
    long now = NOW + call * 10_000_000L;
    try {
      BackfillScheduler.class.getMethod("runInterleavedEnrichment", long.class).invoke(h.scheduler, now);
    } catch (NoSuchMethodException before) {
      try {
        BackfillScheduler.class.getMethod("runInterleavedSplade", long.class).invoke(h.scheduler, now);
      } catch (ReflectiveOperationException failure) {
        throw new AssertionError(failure);
      }
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
    try {
      h.refresh();
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static Object component(Object turn, String name) {
    try {
      var accessor = turn.getClass().getDeclaredMethod(name);
      accessor.setAccessible(true);
      return accessor.invoke(turn);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static String lane(Object turn) { return component(turn, "lane").toString(); }
  private static String caller(Object turn) { return (String) component(turn, "caller"); }
  private static long charge(Object turn) { return (Long) component(turn, "chargeNanos"); }
  private static boolean advancement(Object turn) { return (Boolean) component(turn, "advancement"); }

  private static Map<String, Long> counters(Object turn) {
    return ((StageOutcome) component(turn, "outcome")).accounting().counters();
  }

  private List<String> warnings() {
    return logs.list.stream().filter(event -> event.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .filter(message -> message.contains("IDENTICAL work-set")
            || message.contains("consecutive eligible turns without advancing"))
        .toList();
  }

  private static void backlog(Harness h, int count) throws Exception {
    for (int i = 0; i < count; i++) {
      String id = "parent-%03d".formatted(i);
      h.seed(id, "body " + i, Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING",
          SchemaFields.SPLADE_STATUS, "PENDING", SchemaFields.NER_STATUS, "PENDING"));
      h.chunk("chunk-%03d".formatted(i), id, "body " + i,
          Map.of(SchemaFields.CHUNK_EMBEDDING_STATUS, "PENDING", SchemaFields.SPLADE_STATUS, "PENDING"));
    }
    h.refresh();
  }

  private static void seedSparseAndNer(Harness h, int count) throws Exception {
    for (int i = 0; i < count; i++) {
      h.seed("parent-%03d".formatted(i), "body " + i, Map.of(SchemaFields.SPLADE_STATUS, "PENDING",
          SchemaFields.NER_STATUS, "PENDING"));
    }
    h.refresh();
  }

  private static void arrivals(Harness h, int call) throws Exception {
    for (int j = 0; j < 10; j++) {
      h.seed("a-%02d-%d".formatted(call, j), "arrival " + call + " " + j,
          Map.of(SchemaFields.SPLADE_STATUS, "PENDING"));
    }
    h.refresh();
  }

  /** A late-chunked parent whose classification and windows each cost more than a cycle. */
  private static List<Integer> longDocument(Harness h, String content) throws Exception {
    when(h.config.ai().embedding().lateChunkingEnabled()).thenReturn(true);
    h.seed("parent", content, Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING",
        SchemaFields.EMBEDDING_RETRY_COUNT, "2"));
    h.chunk("chunk", "parent", content, Map.of());
    h.refresh();
    var offsets = new ArrayList<Integer>();
    when(h.dense.documentWindowCount(anyString())).thenReturn(96);
    when(h.dense.embedWithSpans(anyString(), any(int[][].class))).thenAnswer(call -> {
      h.clock.addAndGet(6 * SECOND);
      return null;
    });
    when(h.dense.embedDocumentWindows(anyString(), anyInt(), anyInt())).thenAnswer(call -> {
      h.clock.addAndGet(6 * SECOND);
      int from = call.getArgument(1);
      offsets.add(from);
      return new EmbeddingProvider.WindowSlice(Collections.nCopies(32, vector()), from, 96);
    });
    return offsets;
  }

  private static void assertDurable(Harness h, String id, String status) throws Exception {
    try (var fs = org.apache.lucene.store.FSDirectory.open(h.path);
        var reader = org.apache.lucene.index.DirectoryReader.open(fs)) {
      var searcher = new org.apache.lucene.search.IndexSearcher(reader);
      var hits = searcher.search(new org.apache.lucene.search.BooleanQuery.Builder()
          .add(new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term(SchemaFields.DOC_ID, id)),
              org.apache.lucene.search.BooleanClause.Occur.FILTER)
          .add(new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term(SchemaFields.EMBEDDING_STATUS, status)),
              org.apache.lucene.search.BooleanClause.Occur.FILTER).build(), 1);
      assertEquals(1, hits.totalHits.value(), id + " is durably " + status);
    }
  }

  private static float[] vector() {
    return new float[] {1, 0, 0, 0};
  }

  private final class Harness implements AutoCloseable {
    final RunningRuntime runtime;
    final Path path = directory.resolve(UUID.randomUUID().toString());
    final ResolvedConfig config = mock(ResolvedConfig.class, RETURNS_DEEP_STUBS);
    final AtomicLong clock = new AtomicLong();
    final AtomicBoolean unavailable = new AtomicBoolean();
    final AtomicBoolean pendingIngest = new AtomicBoolean();
    final AtomicInteger failedCalls = new AtomicInteger();
    boolean denseEnabled, nerEnabled, sparseEnabled, bgeEnabled;
    String measured;
    EmbeddingProvider dense;
    NerService ner;
    SpladeEncoder sparse;
    BgeM3Encoder bge;
    final CommitOps commitOps;
    final BackfillScheduler scheduler;

    Harness() throws Exception {
      this(false);
    }

    Harness(boolean spyCommits) throws Exception {
      var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(4).fields());
      for (String name : List.of(SchemaFields.SOURCE_SHA256, SchemaFields.EMBEDDING_RETRY_COUNT,
          SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_RETRY_COUNT, SchemaFields.NER_STATUS,
          SchemaFields.NER_RETRY_COUNT)) {
        if (fields.stream().noneMatch(field -> field.id().equals(name))) {
          fields.add(new FieldDef(name, name.endsWith("_count") ? "long" : "keyword", true, true,
              List.of("filter"), null, null, false));
        }
      }
      fields.add(new FieldDef(SchemaFields.SPLADE, "splade", false, false, List.of(), null, null,
          false, "reset-status:splade_status"));
      runtime = IndexSchema.fromCatalog(new FieldCatalogDef("e4b-scheduler", fields)).atPath(path)
          .withExecutorRegistrations(testLuceneExecutors()).open();
      runtime.commitOps().stopCommitTimer();
      runtime.commitOps().suspendNrtRefresh();
      commitOps = spyCommits ? spy(runtime.commitOps()) : runtime.commitOps();
      for (String lane : List.of("dense", "ner", "sparse", "bge")) replace(lane);
      var signals = mock(WorkerSignalBus.class);
      when(signals.hasPendingIngest()).thenAnswer(call -> pendingIngest.get());
      var lifecycle = mock(EmbeddingProviderLifecycle.class);
      when(lifecycle.embeddingProvider()).thenAnswer(call -> dense);
      when(lifecycle.allowEmbeddingWrites()).thenReturn(true);
      when(config.ai().backfillPacing()).thenReturn(ResolvedConfig.Ai.BackfillPacing.DEFAULTS);
      when(config.ai().embedding().lateChunkingEnabled()).thenReturn(false);
      when(config.rag().chunkVectorsEnabled()).thenReturn(true);
      when(config.rag().chunkSpladeEnabled()).thenReturn(true);
      var ctor = BackfillScheduler.class.getDeclaredConstructor(DocumentFieldOps.class,
          IndexingCoordinator.class, IndexCountOps.class, CommitOps.class, WorkerSignalBus.class,
          IndexingPacing.class, EmbeddingProviderLifecycle.class, AtomicBoolean.class,
          java.util.function.Supplier.class, java.util.function.Supplier.class,
          java.util.function.Supplier.class, java.util.function.Supplier.class,
          java.util.function.Supplier.class, java.util.function.LongSupplier.class);
      ctor.setAccessible(true);
      scheduler = ctor.newInstance(runtime.documentFieldOps(), runtime.indexingCoordinator(),
          runtime.indexCountOps(), commitOps, signals, IndexingPacing.unthrottled(), lifecycle,
          new AtomicBoolean(true), (java.util.function.Supplier<ResolvedConfig>) () -> config,
          (java.util.function.Supplier<SpladeEncoder>) () -> sparseEnabled ? sparse : null,
          (java.util.function.Supplier<BgeM3Encoder>) () -> bgeEnabled ? bge : null,
          (java.util.function.Supplier<NerService>) () -> nerEnabled ? ner : null,
          (java.util.function.Supplier<Object>) () -> null,
          (java.util.function.LongSupplier) clock::get);
    }

    /** Admitted turns, through the scheduler's private observer; empty where none exists. */
    List<Object> observe() {
      var turns = new ArrayList<Object>();
      try {
        var observer = BackfillScheduler.class.getDeclaredField("turnObserver");
        observer.setAccessible(true);
        observer.set(scheduler, (java.util.function.Consumer<Object>) turns::add);
      } catch (NoSuchFieldException before) {
        // The before state has no per-turn record; assertions on turns fail by assertion.
      } catch (ReflectiveOperationException failure) {
        throw new AssertionError(failure);
      }
      return turns;
    }

    WindowedEmbedProgress progress() throws Exception {
      var field = BackfillScheduler.class.getDeclaredField("windowedEmbedProgress");
      field.setAccessible(true);
      return (WindowedEmbedProgress) field.get(scheduler);
    }

    void enable(String lane) {
      measured = lane;
      denseEnabled = lane.equals("dense");
      nerEnabled = lane.equals("ner");
      bgeEnabled = lane.equals("bge");
    }

    void acquire(String lane) {
      if (!lane.equals(measured) || !unavailable.get()) return;
      failedCalls.incrementAndGet();
      throw new SessionTemporarilyUnavailableException("provider still current, lease unavailable");
    }

    void replace(String lane) throws Exception {
      switch (lane) {
        case "dense" -> {
          dense = mock(EmbeddingService.class);
          when(dense.isAvailable()).thenAnswer(call -> denseEnabled);
          when(dense.dimension()).thenReturn(4);
          when(dense.documentWindowCount(anyString())).thenReturn(1);
          when(dense.embedDocumentBatch(anyList())).thenAnswer(call -> {
            acquire("dense");
            return Collections.nCopies(((List<?>) call.getArgument(0)).size(), vector());
          });
          when(dense.embedDocument(anyString(), any())).thenAnswer(call -> {
            acquire("dense");
            return vector();
          });
        }
        case "ner" -> {
          ner = mock(NerService.class);
          when(ner.isAvailable()).thenReturn(true);
          when(ner.extractEntitiesBatch(anyList())).thenAnswer(call -> {
            acquire("ner");
            return Collections.nCopies(((List<?>) call.getArgument(0)).size(), NerResult.EMPTY);
          });
        }
        case "sparse" -> {
          sparse = mock(SpladeEncoder.class);
          when(sparse.encodeBatch(anyList())).thenAnswer(call ->
              Collections.nCopies(((List<?>) call.getArgument(0)).size(), Map.of("term", 1f)));
        }
        default -> {
          bge = mock(BgeM3Encoder.class);
          when(bge.encodeBatch(anyList())).thenAnswer(call -> {
            acquire("bge");
            return Collections.nCopies(((List<?>) call.getArgument(0)).size(),
                new BgeM3Output(vector(), Map.of("term", 1f)));
          });
          when(bge.encode(anyString(), any())).thenAnswer(call -> {
            acquire("bge");
            return new BgeM3Output(vector(), Map.of("term", 1f));
          });
        }
      }
    }

    void chunk(String id, String parent, String parentContent, Map<String, Object> overrides) {
      var fields = new HashMap<String, Object>(overrides);
      fields.put(SchemaFields.IS_CHUNK, "true");
      fields.put(SchemaFields.PARENT_DOC_ID, parent);
      fields.put(SchemaFields.CHUNK_START_CHAR, "0");
      fields.put(SchemaFields.CHUNK_END_CHAR, "" + parentContent.length());
      fields.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(parentContent));
      seed(id, "", fields);
    }

    void seed(String id, String content, Map<String, Object> overrides) {
      var fields = new HashMap<String, Object>();
      fields.put(SchemaFields.DOC_ID, id);
      fields.put(SchemaFields.DOC_UID, "uid-" + id);
      fields.put(SchemaFields.CONTENT, content);
      fields.put(SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex(content));
      fields.put(SchemaFields.SOURCE_SHA256, "source-" + id + "-" + content.hashCode());
      fields.putAll(overrides);
      runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    }

    void refresh() throws Exception {
      runtime.commitOps().maybeRefreshBlocking();
    }

    String value(String id, String field) {
      return runtime.documentFieldOps().getDocumentField(id, field);
    }

    @Override
    public void close() {
      runtime.close();
    }
  }
}
