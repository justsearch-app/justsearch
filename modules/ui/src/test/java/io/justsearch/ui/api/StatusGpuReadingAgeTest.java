/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.app.api.gpu.GpuMemoryReader;
import io.justsearch.app.api.gpu.GpuMemorySample;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.MigrationGenerationView;
import io.justsearch.app.api.status.MigrationGenerationViewBuilder;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.app.api.status.operating.OperatingGpuView;
import io.justsearch.app.services.lifecycle.RegistryBackedCapability;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongUnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * b82 L1 on the real status path: {@code operating.gpu} is stale from the age of the GPU reading it
 * publishes, measured against the gpu-memory lane's effective cadence, which counts that reading's
 * age at publish. Measured live under bulk indexing, the reading reaches status through three 2 s
 * delays in a row: the Engine's GPU sampler ticks every 2 s, the status projection caches a reading
 * for 2 s, and the status sampler publishes every 2 s while busy. The reading is about 2.1 s old
 * when it is published and about 6.1 s old just before the next one replaces it.
 * {@link StatusGpuStaleSourceTest} keeps the bound: a reading older than the allowance is stale.
 */
final class StatusGpuReadingAgeTest {
  static final long PERIOD_MS = StatusLifecycleHandler.SAMPLER_BUSY_PERIOD_MS;
  /** The GPU sampler's latest tick when the projection reads it: one 2 s tick plus its duration. */
  private static final long READING_AGE_AT_READ_MS = 2_100L;
  /** The projection's cache window, then one busy status period until the next publication. */
  static final long CACHE_WINDOW_MS = 2_000L;

  @TempDir Path indexBase;

  @Test
  @Timeout(60)
  void aPublishedGpuReadingAgedByTheThreeStackedTwoSecondDelaysIsNotStale() throws Exception {
    var clock = new AtomicLong(System.currentTimeMillis());
    var handler = busyHandler(indexBase, clock, now -> now - READING_AGE_AT_READ_MS);
    handler.sampleAndBuildStatusSnapshot();
    long publishedAt = clock.get();

    // The same reading is served from the cache window, and the next pass is one period later.
    clock.set(publishedAt + CACHE_WINDOW_MS + PERIOD_MS);
    OperatingGpuView gpu = handler.buildStatusSnapshot().operating().gpu();

    assertEquals(READING_AGE_AT_READ_MS + CACHE_WINDOW_MS + PERIOD_MS, gpu.ageMs(),
        "the reading aged by the sampler tick, the cache window and one status period");
    assertFalse(gpu.stale(), "a GPU reading aged only by the three stacked 2 s delays ("
        + gpu.ageMs() + " ms) is current while it keeps arriving on that cadence");
  }

  /**
   * A handler on the busy period whose GPU reader returns a PDH reading stamped
   * {@code sampledAt.applyAsLong(now)}, after its first sample has reached status.
   */
  static StatusLifecycleHandler busyHandler(Path indexBase, AtomicLong clock,
      LongUnaryOperator sampledAt) throws InterruptedException {
    var components = TestEngineComponents.fourComponents();
    components.handle("api").transition(ComponentState.READY, null, null);
    components.handle("index").transition(ComponentState.STARTING, "index.starting", null);
    var capability = new RegistryBackedCapability(components, "index", "worker");
    var client = mock(KnowledgeClient.class);
    when(client.getWorkerOperationalView(any())).thenReturn(indexing());
    var server = mock(KnowledgeServerBootstrap.class);
    when(server.hasClient()).thenReturn(true);
    BootstrapLeaseFixtures.bind(server, client);
    var handler = new StatusLifecycleHandler(
        mock(OnlineAiService.class), mock(io.justsearch.agent.api.AgentService.class), () -> null,
        server, null, indexBase, Instant.now(), () -> "OK", null, null, null,
        capability, new RegistryBackedCapability(components, "generative", "inference"));
    handler.setIndexComponent(components, components.handle("index"));
    handler.setClockForTesting(clock::get);
    long self = ProcessHandle.current().pid();
    GpuMemoryReader reader = new GpuMemoryReader() {
      @Override
      public GpuMemorySample sampleNow() {
        return new GpuMemorySample(GpuMemorySource.PDH, Optional.empty(),
            Optional.of(new GpuDevice("luid_0x00000000_0x0000d7a2", OptionalInt.empty())),
            Optional.of("RTX"), OptionalLong.of(12_000L), OptionalLong.of(9_000L),
            Map.of(self, new GpuMemorySample.ProcessMemory(4_000L, 50L)),
            Instant.ofEpochMilli(sampledAt.applyAsLong(clock.get())));
      }

      @Override
      public void close() {}
    };
    handler.setGpuMemoryReader(reader);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    OperatingGpuView gpu = handler.sampleAndBuildStatusSnapshot().operating().gpu();
    while (gpu.source() != GpuMemorySource.PDH && System.nanoTime() < deadline) {
      Thread.sleep(10);
      gpu = handler.sampleAndBuildStatusSnapshot().operating().gpu();
    }
    assertEquals(GpuMemorySource.PDH, gpu.source(), "the first GPU reading reached status");
    assertEquals(PERIOD_MS, handler.samplingPeriodMs(), "indexing keeps the sampler busy");
    return handler;
  }

  private static WorkerOperationalView indexing() {
    var serving = WorkerOperationalViewBuilder.from(WorkerOperationalView.fallback("SERVING"))
        .withCore(new CoreIndexView(true, 10, 1, "INDEXING", 0, 0));
    var indexing = MigrationGenerationViewBuilder.from(MigrationGenerationView.empty())
        .withProcessingJobsCount(1L);
    return WorkerOperationalViewBuilder.from(serving).withMigration(indexing);
  }
}
