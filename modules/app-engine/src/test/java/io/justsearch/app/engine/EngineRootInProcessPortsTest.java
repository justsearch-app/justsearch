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
import io.justsearch.app.api.knowledge.KnowledgeClientException;
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

    // (3b) The gauge moves across a REAL search through the client, and balances afterwards.
    //
    // Review S10: the first cut built a fresh ForegroundLoadGate and ran a lambda through it, which
    // proved the gate works and said nothing about whether the production search path goes through
    // one. startedTotal is the assertion that distinguishes the two — it can only advance if the
    // client's own executor gated the call.
    long startedBefore = built[0].foregroundLoad().startedTotal();
    client.search("quokka", 10);
    assertEquals(
        startedBefore + 1,
        built[0].foregroundLoad().startedTotal(),
        "a search through the port must pass the foreground gate exactly once — if this is +0 the"
            + " production path is not gated at all; if it is +2 a nested call is double-counting");
    assertEquals(
        0,
        built[0].foregroundLoad().inFlight(),
        "and the gauge must be back at rest once the call returns");
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
    KnowledgeServer[] built = new KnowledgeServer[1];
    root =
        new EngineRoot(
            g -> {
              built[0] = new KnowledgeServer(WorkerConfig.load(), new InProcessWorkerSignalBus(g));
              return built[0];
            },
            1L,
            5_000);
    KnowledgeClient client = root.start(gauge, IpcTelemetry.noop());

    long startedAtMs = System.currentTimeMillis();
    KnowledgeClientException raised =
        assertThrows(
            KnowledgeClientException.class,
            () -> client.search("anything at all", 10),
            "an elapsed budget must be reported");
    long elapsedMs = System.currentTimeMillis() - startedAtMs;

    assertEquals(
        KnowledgeClientException.Status.DEADLINE_EXCEEDED,
        raised.status(),
        "the failure must carry the deadline status, not be re-labelled INTERNAL");

    // Review B3: the caller must be released AT the budget, not after the work finishes. The first
    // cut armed a cancel and then checked after the fact, so a slow search ran to completion and
    // only THEN reported the deadline — which is not what the deadline meant on the wire. A search
    // takes far longer than the 1 ms budget here, so returning quickly is the property; the bound
    // is generous because it is measuring "released early", not measuring latency.
    assertTrue(
        elapsedMs < 5_000,
        "the caller must be released at its budget, not when the work finishes; took "
            + elapsedMs + "ms");

    // And the gauge must come back to rest once the worker unwinds — a timed-out search that left
    // the gate held would un-throttle indexing at exactly the wrong moment (review B3).
    long deadline = System.currentTimeMillis() + 60_000;
    while (built[0].foregroundLoad().inFlight() > 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    assertEquals(
        0,
        built[0].foregroundLoad().inFlight(),
        "the foreground gauge must return to zero once the timed-out call unwinds");
  }
}
