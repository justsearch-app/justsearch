package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.worker.IpcTags.WorkerRestartTags;
import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.TestMetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 417 Phase 2e: assertions go through {@link TestMetricRegistry} typed query API instead
 * of a custom Telemetry mock.
 */
class IpcTelemetryTest {

  private TestMetricRegistry registry;
  private IpcMetricCatalog catalog;
  private IpcTelemetry ipc;

  @BeforeEach
  void setUp() {
    registry = new TestMetricRegistry(IpcMetricCatalog.DEFINITIONS);
    catalog = new IpcMetricCatalog(registry);
    ipc = new IpcTelemetry(catalog);
  }

  @AfterEach
  void tearDown() {
    if (registry != null) registry.close();
  }

  @Test
  void noop_doesNotThrow() {
    IpcTelemetry noop = IpcTelemetry.noop();
    assertDoesNotThrow(() -> {
      noop.recordPortDiscoveryTimeout();
      noop.recordRestartSuccess();
      noop.recordRestartFailed();
      noop.recordRestartLimitExceeded();
      noop.recordPidMismatch();
      noop.recordShutdownTimeout();
      noop.recordForcibleKill();
      var sample = noop.startPortDiscovery();
      try (sample) {
        // timer sample closes without error
      }
    });
  }

  @Test
  void noop_timerSampleCanBeUsedInTryWithResources() {
    IpcTelemetry noop = IpcTelemetry.noop();
    assertDoesNotThrow(() -> {
      var sample = noop.startPortDiscovery();
      try (sample) {
        Thread.sleep(1);
      }
    });
  }

  @Test
  void countersIncrementCorrectly() {
    ipc.recordRestartSuccess();
    ipc.recordRestartSuccess();
    ipc.recordRestartFailed();

    assertEquals(
        2L,
        registry.counterValue(
            IpcMetricCatalog.WORKER_RESTART, new WorkerRestartTags(WorkerRestartOutcome.SUCCESS)));
    assertEquals(
        1L,
        registry.counterValue(
            IpcMetricCatalog.WORKER_RESTART, new WorkerRestartTags(WorkerRestartOutcome.FAILED)));
  }

  @Test
  void allCountersAreRecorded() {
    ipc.recordPortDiscoveryTimeout();
    ipc.recordRestartLimitExceeded();
    ipc.recordPidMismatch();
    ipc.recordShutdownTimeout();
    ipc.recordForcibleKill();

    assertEquals(
        1L, registry.counterValue(IpcMetricCatalog.PORT_DISCOVERY_TIMEOUT, EmptyTags.INSTANCE));
    assertEquals(
        1L,
        registry.counterValue(IpcMetricCatalog.WORKER_RESTART_LIMIT_EXCEEDED, EmptyTags.INSTANCE));
    assertEquals(
        1L, registry.counterValue(IpcMetricCatalog.WORKER_PID_MISMATCH, EmptyTags.INSTANCE));
    assertEquals(1L, registry.counterValue(IpcMetricCatalog.SHUTDOWN_TIMEOUT, EmptyTags.INSTANCE));
    assertEquals(
        1L, registry.counterValue(IpcMetricCatalog.SHUTDOWN_FORCIBLE_KILL, EmptyTags.INSTANCE));
  }

  @Test
  void timerSampleRecordsLatency() {
    var sample = ipc.startPortDiscovery();
    try (sample) {
      Thread.sleep(10);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    assertTrue(
        registry.histogramCount(IpcMetricCatalog.PORT_DISCOVERY_MS, EmptyTags.INSTANCE) >= 1);
  }
}
