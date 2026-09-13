/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lane F stage A item A18 — {@link DevReloadManager} consumes the on-disk reload trigger.
 *
 * <p><b>Why this test is worth its weight.</b> Hot reload has now died silently twice on this
 * branch, at both ends, and in both cases the whole suite stayed green because nothing tested the
 * end that broke. At A6 the trigger stopped being written; at A11 the JDWP listener stopped being
 * started. The tool went on reporting a successful push into a JVM that was not reconstructing
 * anything. The failure mode is always the same shape — the two halves stop agreeing and nothing
 * fails — so the half with no coverage is the one to cover.
 *
 * <p>{@link InProcessWorkerSignalBus} already has tests for the file protocol itself
 * ({@code InProcessWorkerSignalBusReloadTest}). What had none is the consumer: that
 * {@code DevReloadManager}, when it runs, actually READS and CLEARS that file. A manager that
 * reconstructed services perfectly but never consumed the request would re-trigger on the sentinel's
 * very next poll, one second later, forever.
 *
 * <p><b>On the half-built server.</b> {@code performReload} clears the signal as its first act
 * (step 1, before it touches services) precisely so a compile landing mid-reload gets its own
 * reload. That ordering is what makes this test possible without booting a server: the steps after
 * it fail on a server that was never started, and {@code performReload} catches and logs them
 * (DevReloadManager.java:99-101) rather than propagating. So the call returns, and the question
 * "did it consume the trigger?" is answerable on its own. This is deliberately a test of step 1 and
 * nothing else; it makes no claim about reconstruction, which needs a live stack.
 */
@DisplayName("DevReloadManager — the on-disk reload trigger (item A18)")
final class DevReloadManagerTriggerTest {

  /**
   * {@code WorkerBootFixture.workerConfig} reads {@code ConfigStore.global()}, which THROWS when
   * unset rather than defaulting. Whether some other test in this module happens to have set it
   * first is not a property this test should depend on — that is an ordering coupling that passes
   * until the day the suite is sharded differently. So it establishes one if there is none, and
   * leaves an existing one alone.
   */
  @BeforeAll
  static void ensureGlobalConfig() {
    if (ConfigStore.globalOrNull() == null) {
      ConfigStore.setGlobal(new ConfigStore(ResolvedConfig.builder().contributeEnvRegistry().build()));
    }
  }

  private static InProcessWorkerSignalBus busOver(Path runtimeDir) {
    return new InProcessWorkerSignalBus(new GpuSchedulingGauge(), runtimeDir);
  }

  /** A server object with only the field {@code performReload}'s first step needs. */
  private static KnowledgeServer serverWith(InProcessWorkerSignalBus bus, Path dataDir) {
    KnowledgeServer server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(), WorkerBootFixture.workerConfig(dataDir), bus);
    // start() assigns this from the injected bus; the test does not start the server, so it assigns
    // the same thing directly. Package-private for exactly this kind of access.
    server.signalBus = bus;
    return server;
  }

  @Test
  @DisplayName("a reload request present at entry is consumed, so the sentinel does not re-fire")
  void performReloadConsumesTheRequestFile(@TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path runtimeDir = Files.createDirectories(dataDir.resolve("runtime"));
    Path request = runtimeDir.resolve(InProcessWorkerSignalBus.RELOAD_REQUEST_FILENAME);
    Files.writeString(request, "2026-09-08T00:00:00Z reload requested\n");

    InProcessWorkerSignalBus bus = busOver(runtimeDir);
    assertTrue(
        bus.isReloadRequested(),
        "precondition: the bus must see the request the MCP tool would have written, or this test"
            + " proves nothing about consuming it");

    new DevReloadManager(serverWith(bus, dataDir)).performReload();

    assertFalse(
        Files.exists(request),
        "performReload must delete the request file. Left in place, the sentinel's next poll (one"
            + " second later, KnowledgeServer.java:2014) sees it again and reloads again, forever.");
    assertFalse(
        bus.isReloadRequested(),
        "and the bus must agree — this is the observable the sentinel actually consults");
  }

  @Test
  @DisplayName("no request means no consumption attempt is mistaken for one")
  void withoutARequestNothingIsConsumed(@TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path runtimeDir = Files.createDirectories(dataDir.resolve("runtime"));
    Path unrelated = runtimeDir.resolve("something-else.txt");
    Files.writeString(unrelated, "not a reload request");

    InProcessWorkerSignalBus bus = busOver(runtimeDir);
    assertFalse(bus.isReloadRequested(), "precondition: no request file, so no request");

    new DevReloadManager(serverWith(bus, dataDir)).performReload();

    assertTrue(
        Files.exists(unrelated),
        "the consumption step must delete the request file by name and nothing else — a reload that"
            + " swept the runtime directory would take real state with it");
  }

  /** The MCP server that writes the trigger, relative to this module's project directory. */
  private static final Path MCP_SERVER =
      Path.of("..", "..", "scripts", "dev", "justsearch-dev-mcp", "server.mjs");

  @Test
  @DisplayName("the tool that WRITES the trigger and the code that READS it agree on the path")
  void theWriterAndTheReaderAgreeOnThePath() throws Exception {
    // A18's own complaint about the arrangement it replaced was that the MCP server wrote "byte 29"
    // as a literal rather than importing OFFSET_RELOAD_SIGNAL, so the two halves could drift with
    // nothing to notice. Re-homing the trigger onto a file did not fix that: server.mjs still spells
    // the path out (`path.join(dataDir, 'runtime', 'dev-reload.request')`) because a Node process
    // cannot import a Java constant. The duplication is unavoidable; the SILENCE is not.
    //
    // Renaming RELOAD_REQUEST_FILENAME on the Java side breaks hot reload completely and reds
    // nothing — the tool writes a file nobody reads, reports success, and the Engine never
    // reconstructs. That is the third occurrence of the same failure on this branch. This assertion
    // is what makes the fourth one loud.
    assertTrue(
        Files.isRegularFile(MCP_SERVER),
        "the dev MCP server was not found at " + MCP_SERVER.toAbsolutePath()
            + " — if it moved, this pin must follow it rather than silently checking nothing");
    String js = Files.readString(MCP_SERVER);

    assertTrue(
        js.contains("'" + InProcessWorkerSignalBus.RELOAD_REQUEST_FILENAME + "'"),
        "server.mjs does not mention '" + InProcessWorkerSignalBus.RELOAD_REQUEST_FILENAME
            + "'. Either the Java constant was renamed without updating the writer, or the writer"
            + " stopped naming the file literally. Both leave the MCP reload tool reporting success"
            + " while the Engine never sees a request.");
    assertTrue(
        js.contains("'runtime'"),
        "server.mjs must still place the request inside the runtime directory; the bus resolves it"
            + " against <dataDir>/runtime and looks nowhere else"
            + " (InProcessWorkerSignalBus.java:83).");
  }
}
