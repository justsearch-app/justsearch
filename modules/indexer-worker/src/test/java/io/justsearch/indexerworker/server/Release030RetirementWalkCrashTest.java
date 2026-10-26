/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.ipc.DeleteByPathRequest;
import io.justsearch.ipc.SyncDirectoryRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Kill after later walk admission/checkpoint, while its real writer remains in retry backoff. */
@Timeout(600)
@org.junit.jupiter.api.extension.ExtendWith(io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030RetirementWalkCrashTest {
  @ParameterizedTest(name = "{0} completed walk with pending writer")
  @ValueSource(strings = {"migrating", "switching"})
  void completedWalkSurvivesAnActualKillAndRestart(String fixture, @TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack(fixture, temporary);
    var original = state(root);
    Path file = Files.writeString(root.resolve("roots/alpha/walk-crash.txt"), "walkcrashviolet");
    Path args = root.resolve("child.args");
    Files.writeString(args, "-cp\n\"" + System.getProperty("java.class.path")
        .replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n");
    var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xmx512m", "--enable-native-access=ALL-UNNAMED",
        "-Djustsearch.test.noVolumeIdentity=" + Boolean.getBoolean("justsearch.test.noVolumeIdentity"),
        "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"), "@" + args,
        Release030RetirementWalkCrashTest.class.getName(), root.toString()));
    var builder = new ProcessBuilder(command).redirectErrorStream(true)
        .redirectOutput(root.resolve("walk-child.log").toFile());
    builder.environment().remove("JAVA_TOOL_OPTIONS");
    builder.environment().put("JUSTSEARCH_HOME", root.resolve("home").toString());
    builder.environment().put("JUSTSEARCH_DATA_DIR", root.resolve("data").toString());
    Process child = builder.start();
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150);
      while (child.isAlive() && !Files.exists(root.resolve("walk-cut"))
          && System.nanoTime() < deadline) Thread.sleep(20);
      assertTrue(Files.exists(root.resolve("walk-cut")), () -> readLog(root));
      System.out.println("WALK_KILL child=" + child.pid() + " checkpoint=" + Files.readString(root.resolve("walk-cut")));
      child.destroyForcibly();
      assertTrue(child.waitFor(15, TimeUnit.SECONDS));
    } finally {
      if (child.isAlive()) { child.destroyForcibly(); child.waitFor(15, TimeUnit.SECONDS); }
      System.out.println("WALK_CHILD_STDOUT_BEGIN " + fixture + "\n" + readLog(root)
          + "\nWALK_CHILD_STDOUT_END " + fixture);
    }
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try (var models = new ReleasedModelIdentities(root)) {
      for (int restart = 0; restart < 2; restart++) {
        try (var ex = new TestEngineExecutors(); var server = boot(root, ex)) {
          activate(server);
          await(() -> state(root).previous_generation() == null,
              "later completed walk survives a kill with pending writer work", 150);
          assertTrue(search(server, "walkcrashviolet").contains(key(file)),
              "the older prefix delete cannot erase the later walk's durable admissions");
          assertTrue(search(server, "nebula").contains(key(root.resolve("roots/beta/file-02.txt"))));
          assertEquals(original.active_generation(), state(root).active_generation());
          Release030RetirementSafetyTest.assertRetired(root, original.building_generation());
        }
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  private static String readLog(Path root) {
    try { return Files.readString(root.resolve("walk-child.log")); }
    catch (java.io.IOException failure) { return failure.toString(); }
  }

  private static boolean checkpointWalked(Path root,
      io.justsearch.indexerworker.queue.SwitchBufferCapableQueue.SwitchBufferOp op) {
    try { return io.justsearch.indexerworker.queue.Release030RecoveryState.load(root.resolve("data/jobs.db")).rootWalked(op); }
    catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
  }

  public static void main(String[] args) throws Exception {
    io.justsearch.indexerworker.services.NoVolumeIdentityExtension.installVolumeLookupIfAbsent();
    Path root = Path.of(args[0]);
    Path alpha = root.resolve("roots/alpha");
    Path file = alpha.resolve("walk-crash.txt");
    configure(root);
    var arrived = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var once = new AtomicBoolean();
    var injected = new AtomicBoolean();
    var serverRef = new AtomicReference<KnowledgeServer>();
    try (var models = new ReleasedModelIdentities(root); var ex = new TestEngineExecutors();
        var server = Release030RetirementOrderingTest.bootWithBarrier(root, ex, transition -> {
          if (transition.point().equals("release030-before-replay") && once.compareAndSet(false, true)) {
            arrived.countDown(); assertTrue(release.await(30, TimeUnit.SECONDS));
          }
          if (transition.point().equals("release030-root-walked")) {
            var queue = Release030RetirementWalkOrderTest.queue(serverRef.get());
            // The public forced-sync receipt is last; observe its newly admitted queue row.
            if (queue.hasNonterminalJobsByPathPrefixStrict(key(alpha))
                && queue.listSwitchBufferOpsStrict().stream().anyMatch(op ->
                    op.op().equals("SYNC_ROOT") && !op.key().startsWith("release030-root:")
                        && Path.of(io.justsearch.indexerworker.queue.SwitchBufferSyncRoot.decode(op.payload()).rootPath()).equals(alpha)
                        && checkpointWalked(root, op)) && injected.compareAndSet(false, true)) {
              queue.markFailed(file, IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED,
                  IngestionReasonCodes.SOURCE_UNREADABLE, IngestionRetryPolicy.RETRY_UNTIL_AVAILABLE,
                  "walk crash writer retry"));
              var target = queue.listSwitchBufferOpsStrict().stream().filter(op ->
                  op.op().equals("SYNC_ROOT") && !op.key().startsWith("release030-root:")
                      && Path.of(io.justsearch.indexerworker.queue.SwitchBufferSyncRoot.decode(op.payload()).rootPath()).equals(alpha))
                  .findFirst().orElseThrow();
              Release030RetirementWalkOrderTest.observeRetry(root, file, target, 1);
              if (queue.jobStateCountsStrict().pendingBackoffCount() > 0) {
                Files.writeString(root.resolve("walk-cut"), "pid=" + ProcessHandle.current().pid()
                    + " state=" + queue.jobStateCountsStrict());
                new CountDownLatch(1).await();
              }
            }
          }
        })) {
      serverRef.set(server); activate(server); assertTrue(arrived.await(30, TimeUnit.SECONDS));
      var producer = (DefaultWorkerAppServices) server.appServices();
      assertTrue(producer.pauseProducerForCutover(10000));
      try {
        assertTrue(server.appServices().ingestService().deleteByPath(DeleteByPathRequest.newBuilder()
            .setPath(alpha.toString()).build(), CallContext.none()).getError().isBlank());
        assertTrue(server.appServices().ingestService().syncDirectory(SyncDirectoryRequest.newBuilder()
            .setRootPath(alpha.toString()).setForce(true).build(), CallContext.none()).getError().isBlank());
      } finally { release.countDown(); }
      new CountDownLatch(1).await();
    } finally { release.countDown(); }
  }
}
