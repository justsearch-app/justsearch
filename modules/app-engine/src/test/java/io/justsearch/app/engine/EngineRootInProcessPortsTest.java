/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.services.WorkerServiceException;
import io.justsearch.ipc.BatchResponse;
import io.justsearch.ipc.SearchResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lane F stage A item A6 — the ports as direct calls, exercised end to end in one JVM.
 *
 * <p>This is the test the item's acceptance names, and it is deliberately a <b>boot</b> test rather
 * than a unit test of the adapter: the property A6 claims is that the Head can reach the index half
 * with no process, no port and no channel, and every part of that is invisible to a test that
 * stubs {@code WorkerAppServices}. What it asserts, in order:
 *
 * <ol>
 *   <li>{@link EngineRoot} composes a real {@link KnowledgeServer} inside this JVM and hands back a
 *       {@link KnowledgeClient};
 *   <li>a document submitted through the {@code IndexingService} side of that client becomes
 *       findable through the {@code SearchPort} side — the whole ingest-to-search loop, in process;
 *   <li>the foreground gauge moves during a search, which is what item A4's gate replaced the
 *       deleted gRPC interceptor with, and the gauge it moves is <em>the</em> gauge the indexing
 *       loop paces off (identity, not equality — a copy would throttle nothing);
 *   <li>a call whose deadline elapses surfaces as {@code DEADLINE_EXCEEDED} rather than returning
 *       quietly. Design §6: a bound that vanished with the channel is a lost operation contract.
 * </ol>
 */
@Timeout(180)
final class EngineRootInProcessPortsTest {

  private EngineRoot root;

  @AfterEach
  void tearDown() {
    if (root != null) {
      root.close();
      root = null;
    }
  }

  private static void publishConfig(Path dataDir, Path indexBase) throws Exception {
    Files.createDirectories(dataDir);
    Files.createDirectories(indexBase);
    ConfigStore.setGlobal(
        new ConfigStore(
            new ResolvedConfigBuilder()
                .contributeBaseSources()
                .putDefault("justsearch.data.dir", dataDir.toAbsolutePath().toString())
                .putDefault("justsearch.index.base_path", indexBase.toAbsolutePath().toString())
                .build()));
  }

  @Test
  @DisplayName("ingest through IndexingService, find through SearchPort, with no process boundary")
  void ingestAndSearchThroughTheInProcessPorts(@TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path indexBase = dataDir.resolve("index");
    publishConfig(dataDir, indexBase);

    Path doc = tempDir.resolve("engine-root-probe.txt");
    Files.writeString(doc, "quokka telemetry probe for the in-process engine port test");

    GpuSchedulingGauge gauge = new GpuSchedulingGauge();
    KnowledgeServer[] built = new KnowledgeServer[1];
    root =
        new EngineRoot(
            g -> {
              built[0] = new KnowledgeServer(WorkerConfig.load(), new InProcessWorkerSignalBus(g));
              return built[0];
            },
            30_000L,
            5_000);
    KnowledgeClient client = root.start(gauge, IpcTelemetry.noop());
    assertNotNull(client, "the root must hand back a client");

    // (3a) The gate feeds THE gauge, not a copy of it. Asserted before any call so a failure here
    // is legible as a wiring defect rather than as a search that happened not to move a counter.
    assertSame(
        built[0].foregroundLoad(),
        built[0].appServices().indexingPacing().foregroundLoad(),
        "the gauge the gate feeds must be the one the indexing loop paces off");

    // (2) Ingest through the IndexingService side of the port.
    BatchResponse submitted = client.submitBatch(List.of(doc));
    assertTrue(submitted.getAcceptedCount() > 0, "the batch must be accepted: " + submitted);

    // The indexing loop drains asynchronously, exactly as it did across the wire.
    SearchResponse found = null;
    long deadline = System.currentTimeMillis() + 120_000;
    while (System.currentTimeMillis() < deadline) {
      SearchResponse response = client.search("quokka", 10);
      if (response.getResultsCount() > 0) {
        found = response;
        break;
      }
      Thread.sleep(250);
    }
    assertNotNull(found, "the submitted document must become findable through the search port");
    assertTrue(
        found.getResultsList().stream()
            .anyMatch(r -> r.getId().contains("engine-root-probe")),
        "the found result must be the document we submitted: " + found.getResultsList());

    // (3b) The gauge moved during a search and came back to rest afterwards.
    int[] observedInFlight = {0};
    ForegroundLoadGate probe = new ForegroundLoadGate(built[0].foregroundLoad());
    probe.run("Search", () -> observedInFlight[0] = built[0].foregroundLoad().inFlight());
    assertEquals(1, observedInFlight[0], "a foreground call must be counted while it runs");
    assertEquals(
        0, built[0].foregroundLoad().inFlight(), "the gauge must balance after the call returns");
  }

  @Test
  @DisplayName("a call whose deadline elapses surfaces as DEADLINE_EXCEEDED, not as a quiet return")
  void anElapsedDeadlineIsReportedRatherThanLost(@TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path indexBase = dataDir.resolve("index");
    publishConfig(dataDir, indexBase);

    GpuSchedulingGauge gauge = new GpuSchedulingGauge();
    // A one-millisecond base deadline: every category multiplies it, and every category is still
    // shorter than the work. The point is not the number — it is that SOMETHING enforces it now
    // that no transport does.
    root =
        new EngineRoot(
            g -> new KnowledgeServer(WorkerConfig.load(), new InProcessWorkerSignalBus(g)),
            1L,
            5_000);
    KnowledgeClient client = root.start(gauge, IpcTelemetry.noop());

    WorkerServiceException raised =
        assertThrows(
            WorkerServiceException.class,
            () -> client.search("anything at all", 10),
            "an elapsed budget must be reported");
    assertEquals(
        WorkerServiceException.Status.DEADLINE_EXCEEDED,
        raised.status(),
        "the failure must carry the deadline status, not be re-labelled INTERNAL");
  }
}
