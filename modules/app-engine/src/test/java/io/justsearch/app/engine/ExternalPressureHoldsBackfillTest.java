/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.app.api.gpu.GpuMemoryReader;
import io.justsearch.app.api.gpu.GpuMemorySample;
import io.justsearch.app.api.gpu.GpuMemorySample.ProcessMemory;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.services.gpu.GpuResidencySampler;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.scheduling.BulkWorkGate;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.bgem3.BgeM3Encoder;
import io.justsearch.indexerworker.bgem3.BgeM3Output;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.BackfillScheduler;
import io.justsearch.indexerworker.loop.EmbeddingProviderLifecycle;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Spec E E-S7c (S7 low memory, through the bus seam): another application takes the graphics card's
 * memory while a bulk batch runs, and no further bulk GPU batch starts.
 *
 * <p>The seam is production code end to end: the real {@link BackfillScheduler#runIdleCycle()} over
 * the production {@link InProcessWorkerSignalBus} and {@link GpuSchedulingGauge}, and the real
 * {@link GpuResidencySampler} reading a {@link GpuMemoryReader} fake that reports PDH readings with
 * 300 MiB free and 6 GB used by other processes. The sampler runs on a manual scheduler, so each
 * tick is placed exactly: ticks 1-2 before the cycle (cycle entry still admits it), tick 3, the
 * raise, inside the first batch. The profile is P12 (on NO_GPU the gate never holds).
 *
 * <p>Inside a running cycle the only stop that reads the hold is the bus fold, {@code
 * shouldYieldGpuBackfill() || isBulkHeld()} (Spec E section 1.3 item 1); removing just that fold
 * lets batch 2 start, and these checks fail by assertion. On the base before step E3 (with the
 * sampler copied in) the bus takes no gate, every batch starts, and they fail by assertion too.
 */
final class ExternalPressureHoldsBackfillTest {

  private static final long MIB = 1024L * 1024L;
  private static final long GIB = 1024L * MIB;
  private static final long TOTAL = 12 * GIB;
  private static final GpuDevice DEVICE =
      new GpuDevice("luid_0x00000000_0x0000d7a2", OptionalInt.empty());

  // Case A: the raise lands inside the first combined batch; that batch finishes, no second starts
  @Test
  void combinedCycleStartsNoBatchAfterTheRaise() throws Exception {
    var fixture = new Fixture();
    int batchSize = ResolvedConfig.Ai.BackfillPacing.DEFAULTS.embeddingBackfillBatchSize();
    fixture.pending(6 * batchSize);
    SpladeEncoder splade = mock(SpladeEncoder.class);
    NerService ner = mock(NerService.class);
    when(ner.isAvailable()).thenReturn(true);
    AtomicInteger encodes = new AtomicInteger();
    when(splade.encodeBatch(anyList())).thenAnswer(inv -> {
      // Tick 3, from inside the first batch after it has started.
      if (encodes.getAndIncrement() == 0) fixture.tick();
      List<String> contents = inv.getArgument(0);
      List<Map<String, Float>> out = new ArrayList<>();
      for (int i = 0; i < contents.size(); i++) out.add(Map.of("term", 1.5f));
      return out;
    });
    BackfillScheduler scheduler = fixture.scheduler(splade, null, ner);

    fixture.tick();
    fixture.tick();
    assertFalse(fixture.bus.shouldYieldGpuBackfill(), "two low samples do not raise pressure");

    assertTimeoutPreemptively(Duration.ofSeconds(30), scheduler::runIdleCycle,
        "the cycle returns once the hold is raised");

    assertEquals(1, fixture.batchStarts.get(),
        "the in-flight batch finishes and no further combined batch starts after the raise");
    assertTrue(fixture.bus.shouldYieldGpuBackfill(), "the bus yields while external use holds");
    fixture.assertPausedForExternalUse();

    fixture.clearAndResume(scheduler);
  }

  // Case B: the raise lands inside the first BGE-M3 batch; its encode does not start
  @Test
  void bgeM3BatchDoesNotEncodeAfterTheRaise() throws Exception {
    var fixture = new Fixture();
    fixture.pending(ResolvedConfig.Ai.BackfillPacing.DEFAULTS.bgeM3BackfillBatchSize());
    fixture.onFirstBatchStart = fixture::tick;
    BgeM3Encoder bge = mock(BgeM3Encoder.class);
    when(bge.encodeBatch(anyList())).thenAnswer(inv -> {
      List<String> contents = inv.getArgument(0);
      List<BgeM3Output> out = new ArrayList<>();
      for (int i = 0; i < contents.size(); i++) {
        out.add(new BgeM3Output(new float[] {1f}, Map.of("term", 1.5f)));
      }
      return out;
    });
    BackfillScheduler scheduler = fixture.scheduler(null, bge, null);

    fixture.tick();
    fixture.tick();
    assertFalse(fixture.bus.shouldYieldGpuBackfill(), "two low samples do not raise pressure");

    assertTimeoutPreemptively(Duration.ofSeconds(30), scheduler::runIdleCycle,
        "the cycle returns once the hold is raised");

    assertEquals(1, fixture.batchStarts.get(), "one BGE-M3 batch started before the raise");
    assertEquals(0, encodeCalls(bge), "that batch's encode does not start after the raise");
    assertTrue(fixture.bus.shouldYieldGpuBackfill(), "the bus yields while external use holds");
    fixture.assertPausedForExternalUse();

    fixture.clearAndResume(scheduler);
    assertTrue(encodeCalls(bge) > 0, "after the clear the BGE-M3 encode runs");
  }

  private static long encodeCalls(BgeM3Encoder bge) {
    return org.mockito.Mockito.mockingDetails(bge).getInvocations().stream()
        .filter(inv -> inv.getMethod().getName().equals("encodeBatch"))
        .count();
  }

  /** The production bus and sampler over mocked index operations and a fake reader. */
  private static final class Fixture {
    final BulkWorkGate gate = new BulkWorkGate();
    final InProcessWorkerSignalBus bus = busOver(new GpuSchedulingGauge(), gate);
    final AtomicReference<GpuMemorySample> reading = new AtomicReference<>(low());
    final AtomicReference<Runnable> tick = new AtomicReference<>();
    final AtomicInteger batchStarts = new AtomicInteger();
    final Set<String> pending = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
    final DocumentFieldOps documentFieldOps = mock(DocumentFieldOps.class);
    final IndexingCoordinator indexingCoordinator = mock(IndexingCoordinator.class);
    final IndexCountOps indexCountOps = mock(IndexCountOps.class);
    final CommitOps commitOps = mock(CommitOps.class);
    Runnable onFirstBatchStart = () -> {};

    Fixture() {
      gate.setProfile(BulkWorkGate.Profile.P12);
      ScheduledExecutorService manual = mock(ScheduledExecutorService.class);
      when(manual.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any())).thenAnswer(inv -> {
        tick.set(inv.getArgument(0));
        return mock(ScheduledFuture.class);
      });
      GpuMemoryReader reader = new GpuMemoryReader() {
        @Override public GpuMemorySample sampleNow() {
          return reading.get();
        }

        @Override public void close() {}
      };
      new GpuResidencySampler(reader, GpuResidencySampler::engineAndDescendants, gate,
          Clock.systemUTC(), manual).start();
    }

    void tick() {
      tick.get().run();
    }

    void pending(int count) throws Exception {
      for (int i = 0; i < count; i++) pending.add("doc-" + i);
      when(documentFieldOps.queryNonChunkDocIdsByField(
          eq(SchemaFields.SPLADE_STATUS), eq(SchemaFields.SPLADE_STATUS_PENDING), anyInt()))
          .thenAnswer(inv -> {
            int limit = inv.getArgument(2);
            synchronized (pending) {
              return new ArrayList<>(pending).subList(0, Math.min(limit, pending.size()));
            }
          });
      when(documentFieldOps.getDocumentContentBatch(anyList())).thenAnswer(inv -> {
        if (batchStarts.getAndIncrement() == 0) onFirstBatchStart.run();
        List<String> ids = inv.getArgument(0);
        Map<String, String> content = new HashMap<>();
        for (String id : ids) content.put(id, "enrichable content for " + id);
        return content;
      });
      when(documentFieldOps.getDocumentFieldsBatch(anyList(), anySet())).thenAnswer(inv -> {
        List<String> ids = inv.getArgument(0);
        Map<String, Map<String, String>> fields = new HashMap<>();
        for (String id : ids) {
          fields.put(id, Map.of(
              SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING,
              SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_COMPLETED));
        }
        return fields;
      });
      when(indexingCoordinator.updateDocumentsBatch(anyList())).thenAnswer(inv -> {
        List<Map.Entry<String, ?>> batch = inv.getArgument(0);
        for (var entry : batch) pending.remove(entry.getKey());
        return new LuceneRuntimeTypes.BatchUpdateResult(batch.size(), 0);
      });
      when(indexCountOps.countByField(anyString(), anyString())).thenReturn(0);
      doAnswer(inv -> {
        ((Runnable) inv.getArgument(0)).run();
        return null;
      }).when(commitOps).withNrtSuspended(any());
    }

    BackfillScheduler scheduler(SpladeEncoder splade, BgeM3Encoder bge, NerService ner) {
      EmbeddingProvider embedding = mock(EmbeddingProvider.class);
      when(embedding.isAvailable()).thenReturn(false);
      var lifecycle = new EmbeddingProviderLifecycle(
          bus, mock(JobQueue.class), indexCountOps, commitOps);
      lifecycle.setEmbeddingProvider(embedding);
      return new BackfillScheduler(documentFieldOps, indexingCoordinator, indexCountOps, commitOps,
          bus, io.justsearch.indexerworker.loop.pacing.IndexingPacing.unthrottled(), lifecycle,
          new AtomicBoolean(true), Fixture::resolvedConfig, () -> splade, () -> bge, () -> ner,
          () -> null);
    }

    /** Status reads PAUSED [gpu_busy_external]: the gate's verdict is HOLD for that one reason. */
    void assertPausedForExternalUse() {
      assertTrue(gate.held());
      assertEquals(BulkWorkGate.Mode.HOLD, gate.verdict().mode());
      assertEquals(List.of(BulkWorkGate.Reason.GPU_BUSY_EXTERNAL), gate.verdict().reasons());
    }

    /** The other application frees its memory: after the clear, the next cycle starts batches. */
    void clearAndResume(BackfillScheduler scheduler) {
      reading.set(sample(4 * GIB, 6 * GIB));
      for (int i = 0; i < 4; i++) tick();
      assertTrue(bus.shouldYieldGpuBackfill(), "four clear samples are not enough");
      tick();
      assertFalse(gate.held(), "five clear samples clear the pressure");
      assertFalse(bus.shouldYieldGpuBackfill());
      assertEquals(BulkWorkGate.Mode.RUN, gate.verdict().mode());
      int before = batchStarts.get();
      assertTimeoutPreemptively(Duration.ofSeconds(30), scheduler::runIdleCycle);
      assertTrue(batchStarts.get() > before, "after the clear, batches start again");
    }

    private static ResolvedConfig resolvedConfig() {
      ResolvedConfig config = mock(ResolvedConfig.class);
      ResolvedConfig.Rag rag = mock(ResolvedConfig.Rag.class);
      when(rag.chunkVectorsEnabled()).thenReturn(false);
      when(rag.chunkSpladeEnabled()).thenReturn(false);
      when(config.rag()).thenReturn(rag);
      ResolvedConfig.Ai ai = mock(ResolvedConfig.Ai.class);
      ResolvedConfig.Ai.Embedding embedding = mock(ResolvedConfig.Ai.Embedding.class);
      when(embedding.lateChunkingEnabled()).thenReturn(false);
      when(ai.embedding()).thenReturn(embedding);
      when(ai.backfillPacing()).thenReturn(ResolvedConfig.Ai.BackfillPacing.DEFAULTS);
      when(config.ai()).thenReturn(ai);
      return config;
    }
  }

  /**
   * The production bus over the Engine's gate; over the gauge alone where the bus takes no gate (the
   * base before step E3), so this check runs against the before-state's API.
   */
  private static InProcessWorkerSignalBus busOver(GpuSchedulingGauge gauge, BulkWorkGate gate) {
    try {
      return InProcessWorkerSignalBus.class
          .getConstructor(GpuSchedulingGauge.class, java.nio.file.Path.class, BulkWorkGate.class)
          .newInstance(gauge, null, gate);
    } catch (NoSuchMethodException beforeStepE3) {
      return new InProcessWorkerSignalBus(gauge);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 300 MiB free; the Engine holds 5.7 GB of the rest, so other processes use 6 GB. */
  private static GpuMemorySample low() {
    return sample(300 * MIB, 6 * GIB);
  }

  private static GpuMemorySample sample(long free, long external) {
    long used = TOTAL - free;
    return new GpuMemorySample(GpuMemorySource.PDH, Optional.empty(), Optional.of(DEVICE),
        Optional.of("NVIDIA GeForce RTX 4070"), OptionalLong.of(TOTAL), OptionalLong.of(used),
        Map.of(ProcessHandle.current().pid(), new ProcessMemory(used - external, 0L)),
        Instant.now());
  }
}
