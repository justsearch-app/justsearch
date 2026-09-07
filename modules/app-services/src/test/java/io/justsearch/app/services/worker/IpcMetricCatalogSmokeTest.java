package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.worker.IpcTags.WorkerRestartTags;
import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.TestMetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tempdoc 417 F4: smoke test for {@link IpcMetricCatalog}. */
final class IpcMetricCatalogSmokeTest {

  private TestMetricRegistry registry;
  private IpcMetricCatalog catalog;

  @BeforeEach
  void setUp() {
    registry = new TestMetricRegistry(IpcMetricCatalog.DEFINITIONS);
    catalog = new IpcMetricCatalog(registry);
  }

  @AfterEach
  void tearDown() {
    if (registry != null) registry.close();
  }

  @Test
  void constructsAndEmits() {
    assertNotNull(catalog.portDiscoveryMs);
    assertNotNull(catalog.workerRestart);

    catalog.portDiscoveryMs.record(123L, EmptyTags.INSTANCE);
    catalog.workerRestart.increment(new WorkerRestartTags(WorkerRestartOutcome.SUCCESS));

    assertEquals(1L, registry.histogramCount(IpcMetricCatalog.PORT_DISCOVERY_MS, EmptyTags.INSTANCE));
    assertEquals(
        1L,
        registry.counterValue(
            IpcMetricCatalog.WORKER_RESTART, new WorkerRestartTags(WorkerRestartOutcome.SUCCESS)));
  }

  @Test
  void noopCatalogDoesNotThrow() {
    var noop = IpcMetricCatalog.noop();
    assertDoesNotThrow(() -> noop.portDiscoveryTimeout.increment(EmptyTags.INSTANCE));
  }

  @Test
  void noCatalogEntryOutlivesItsProducer() {
    // Lane F stage A item A10 deleted the wire client stack, and with it the only emitters of
    // ipc.grpc.reconnect and the two ipc.circuit_breaker.* counters. This replaces the old
    // "ipc.grpc.reconnect is archived to RRD" pin: the archive assertion was about a metric that
    // no longer has a producer, and asserting on its retention would have kept a dead name alive.
    // What is pinned instead is that the three names are gone from the catalog contract entirely,
    // so a dashboard cannot read "always zero" where the honest answer is "not measured".
    for (String retired :
        java.util.List.of(
            "ipc.grpc.reconnect", "ipc.circuit_breaker.rejected", "ipc.circuit_breaker.state_change")) {
      assertTrue(
          IpcMetricCatalog.DEFINITIONS.stream().noneMatch(d -> d.name().equals(retired)),
          retired + " still declared, but nothing emits it since the wire was deleted");
    }
  }
}
