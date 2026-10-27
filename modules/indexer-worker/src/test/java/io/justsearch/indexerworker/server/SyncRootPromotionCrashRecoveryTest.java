/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.activate;
import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.key;
import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.search;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferSyncRoot;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.NoVolumeIdentityExtension;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.ipc.MigrationStartRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real Engine process death after the durable generation pointer moved and before the replayed
 * switch-buffer receipts were acknowledged; the restarted Engine must publish B promptly.
 */
@Timeout(600)
final class SyncRootPromotionCrashRecoveryTest {
  private static final String OPERATION = "01994180-0000-7000-8000-000000000951";
  private static final String SOURCE = "sync-root-crash-test";
  private static final String TOKEN = "quartzreplaywitness";
  private static final long STARTUP_SECONDS = 30;
  /** Long enough to observe the committed-root settlement deadline (3 minutes) end the boot. */
  private static final long OBSERVE_SECONDS = 210;

  @RepeatedTest(3)
  void nativeBootPublishesReplayedRootAfterPointerCrash(@TempDir Path temporary) throws Exception {
    crashAndRestart(temporary, "NATIVE");
  }

  @RepeatedTest(3)
  void promotedBootPublishesReplayedRootAfterPointerCrash(@TempDir Path temporary) throws Exception {
    crashAndRestart(temporary, "PROMOTED");
  }

  private static void crashAndRestart(Path root, String mode) throws Exception {
    Files.writeString(root.resolve("child.args"), "-cp\n\"" + System.getProperty("java.class.path")
        .replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n");
    Process migrating = child(root, mode, "migrate");
    try {
      awaitMarker(migrating, root.resolve("cut"), 120);
      assertTrue(Files.exists(root.resolve("cut")), () -> "child must reach the cut:\n" + log(root, "migrate"));
    } finally {
      kill(migrating);
    }
    var committed = new IndexGenerationManager(root.resolve("data/index")).readStateBestEffort();
    assertNotNull(committed);
    assertEquals(Files.readString(root.resolve("building")), committed.active_generation(),
        "the pointer must already name B");
    assertNull(committed.building_generation());
    assertNotNull(committed.previous_generation(), "the crash must precede predecessor retirement");
    try (var queue = new SqliteJobQueue(root.resolve("data/jobs.db"))) {
      queue.open();
      var retained = queue.listSwitchBufferOpsStrict().stream()
          .filter(op -> committed.active_generation().equals(op.generation())).toList();
      System.out.println("CRASH-RECOVERY " + mode + " retained after cut: " + retained.stream()
          .map(op -> op.op() + " " + op.key()).toList());
      assertTrue(retained.stream().anyMatch(op -> "SYNC_ROOT".equals(op.op())),
          "the crash must retain the scoped receipt");
    }
    Process restarted = child(root, mode, "restart");
    long started = System.nanoTime();
    boolean published;
    try {
      awaitMarker(restarted, root.resolve("published"), STARTUP_SECONDS);
      published = Files.exists(root.resolve("published"));
      // Keep the child until it reports its outcome, so a held boot shows how it ends.
      awaitMarker(restarted, root.resolve(published ? "verified" : "boot-failed"),
          published ? 60 : OBSERVE_SECONDS);
    } finally {
      kill(restarted);
    }
    long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
    for (String phase : List.of("migrate", "restart")) {
      System.out.println("CRASH-RECOVERY " + mode + " " + phase + " evidence:\n" + evidence(log(root, phase)));
    }
    System.out.println("CRASH-RECOVERY " + mode + " restart observed for " + seconds + "s; published="
        + published + " markers=" + List.of("published", "searched", "settled", "verified", "boot-failed")
            .stream().filter(name -> Files.exists(root.resolve(name))).toList());
    assertAll(mode + " crash recovery",
        () -> assertTrue(published, "boot must publish promoted generation within " + STARTUP_SECONDS + "s"),
        () -> assertTrue(Files.exists(root.resolve("searched")),
            "published search must return the content introduced by root replay"),
        () -> assertTrue(Files.exists(root.resolve("settled")),
            "the retained generation-scoped receipt must settle"));
  }

  private static Process child(Path root, String mode, String phase) throws IOException {
    String scratch = System.getenv("TMPDIR");
    Path jvm = Files.createDirectories(scratch == null ? Path.of(System.getProperty("java.io.tmpdir"))
        : Path.of(scratch, "builder", "jvm"));
    var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xmx512m", "--enable-native-access=ALL-UNNAMED", "-Dnet.bytebuddy.experimental=true",
        "-Djava.io.tmpdir=" + jvm, "@" + root.resolve("child.args"),
        SyncRootPromotionCrashRecoveryTest.class.getName(), root.toString(), mode, phase)
        .redirectErrorStream(true).redirectOutput(root.resolve(phase + ".log").toFile());
    builder.environment().keySet().removeIf(name -> name.startsWith("JUSTSEARCH_")
        || List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS", "_JAVA_OPTIONS").contains(name));
    builder.environment().put("JUSTSEARCH_HOME", root.resolve("home").toString());
    builder.environment().put("JUSTSEARCH_DATA_DIR", root.resolve("data").toString());
    return builder.start();
  }

  private static void awaitMarker(Process child, Path marker, long seconds) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (child.isAlive() && !Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(20);
  }

  private static void kill(Process child) throws InterruptedException {
    if (child.isAlive()) child.destroyForcibly();
    assertTrue(child.waitFor(15, TimeUnit.SECONDS), "the owned child process must exit");
  }

  private static String log(Path root, String phase) {
    try { return Files.readString(root.resolve(phase + ".log")); }
    catch (IOException unavailable) { return unavailable.toString(); }
  }

  private static String evidence(String log) {
    return log.lines().filter(line -> line.contains("CRASH-RECOVERY")
        || line.contains("SYNC_ROOT")
        || line.contains("Committed boot roots settled")
        || line.contains("Committed native mutation witnesses remain unresolved")
        || line.contains("Unsupported committed mutation")
        || line.contains("Committed native switch replay")
        || line.contains("Promoted generation switch replay")
        || line.contains("Exception"))
        // Collapse a retried warning into its first occurrence and its count.
        .collect(java.util.stream.Collectors.groupingBy(
            line -> line.replaceFirst("^\\d\\d:\\d\\d:\\d\\d\\.\\d{3} ", ""),
            java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()))
        .values().stream().limit(24)
        .map(lines -> lines.get(0) + (lines.size() > 1 ? "  (x" + lines.size() + ", last "
            + lines.get(lines.size() - 1).split(" ", 2)[0] + ")" : ""))
        .reduce("", (a, b) -> a + b + "\n");
  }

  private static void mark(Path root, String name) throws IOException {
    Files.writeString(root.resolve(name), "yes");
  }

  /** Same child-process pattern as Release030RetirementCrashTest; no close or shutdown hook at the cut. */
  public static void main(String[] args) throws Exception {
    NoVolumeIdentityExtension.installVolumeLookupIfAbsent();
    Path root = Path.of(args[0]);
    String mode = args[1];
    boolean restart = args[2].equals("restart");
    Path dataDir = root.resolve("data");
    Path indexBase = dataDir.resolve("index");
    Path watched = Files.createDirectories(root.resolve("watched"));
    Files.createDirectories(dataDir);
    WorkerBootFixture.publishConfig(dataDir, indexBase, "BLUE_GREEN_MIGRATE", Map.of(
        "justsearch.ai.disabled", "true",
        "justsearch.ai.embed.enabled", "false",
        "justsearch.splade.enabled", "false",
        "justsearch.ner.enabled", "false",
        "justsearch.extraction.sandbox.mode", "IN_PROCESS"));
    var executors = new TestEngineExecutors();
    var server = org.mockito.Mockito.spy(new KnowledgeServer(executors,
        WorkerBootFixture.workerConfig(dataDir), null, ManagedChildRegistry.noop(), lifecycle(root)));
    org.mockito.Mockito.doNothing().when(server)
        .startDeferredModelInitialization(org.mockito.ArgumentMatchers.any());
    server.bindBootRootBindings(List.of(new RootBinding(watched, null)));
    server.bindExclusionRoots(() -> List.of(watched));
    if (restart) {
      restart(root, mode, server, watched);
    } else {
      migrate(root, mode, server, watched);
    }
  }

  private static void migrate(Path root, String mode, KnowledgeServer server, Path watched)
      throws Exception {
    server.installMigrationBarrierForTests(transition -> {
      if (transition.point().equals("migration-switching-entered")) {
        // Content that only the root replay can introduce into B: the enumerator already finished.
        Files.writeString(watched.resolve("replayed.txt"), TOKEN);
        // The exact receipt IngestSwitchBufferOps.bufferSyncDirectoryDuringSwitching journals.
        String normalized = PathNormalizer.normalizeKey(watched);
        var queue = (SqliteJobQueue) server.jobQueueForTests();
        assertTrue(queue.putSyncRootForGeneration(transition.buildingGeneration(), "sync_root:" + normalized,
            new SwitchBufferSyncRoot(normalized, true, null)));
        System.out.println("CRASH-RECOVERY retained SYNC_ROOT generation=" + transition.buildingGeneration()
            + " receipts=" + queue.listSwitchBufferOpsStrict().stream().map(op -> op.op() + " " + op.key()).toList());
      }
      if (transition.point().equals("migration-after-pointer-commit")) {
        var queue = (SqliteJobQueue) server.jobQueueForTests();
        System.out.println("CRASH-RECOVERY cut=migration-after-pointer-commit active="
            + server.indexGenerationManagerForTests().readStateBestEffort().active_generation()
            + " retained=" + queue.listSwitchBufferOpsStrict().stream().map(op -> op.op() + " " + op.key()).toList());
        mark(root, "cut");
        while (true) Thread.sleep(1_000);
      }
    });
    server.start();
    activate(server);
    var manager = server.indexGenerationManagerForTests();
    var expected = KnowledgeServer.class.getDeclaredMethod("expectedIndexFingerprintOrNull");
    expected.setAccessible(true);
    String fingerprint = (String) expected.invoke(server);
    assertNotNull(fingerprint);
    Files.writeString(root.resolve("fingerprint"), fingerprint);
    String building;
    if (mode.equals("PROMOTED")) {
      String blue = manager.readStateBestEffort().active_generation();
      Files.writeString(root.resolve("recorded-source"), blue);
      building = manager.startRecordedMigration(OPERATION, SOURCE, fingerprint, blue, List.of())
          .building_generation();
      Files.writeString(root.resolve("building"), building);
      assertTrue(server.beginRecordedBuildingLiveAsync(OPERATION,
          () -> { throw new AssertionError("live recorded migration fell back"); }).get(30, TimeUnit.SECONDS));
    } else {
      var started = server.appServices().ingestService().startMigration(
          MigrationStartRequest.newBuilder().setReason("manual").setProjectionSourceIdsPresent(true).build(),
          CallContext.none());
      assertTrue(started.getAccepted(), started::getError);
      building = started.getBuildingGenerationId();
      Files.writeString(root.resolve("building"), building);
      assertTrue(server.beginUnrecordedBuildingLiveAsync(building,
          () -> { throw new AssertionError("live native migration fell back"); }).get(30, TimeUnit.SECONDS));
    }
    System.out.println("CRASH-RECOVERY " + mode + " migration live building=" + building);
    while (true) Thread.sleep(1_000);
  }

  private static void restart(Path root, String mode, KnowledgeServer server, Path watched)
      throws Exception {
    long started = System.nanoTime();
    var disposition = KnowledgeServer.class.getDeclaredField("generationBootDisposition");
    disposition.setAccessible(true);
    var observer = new Thread(() -> {
      try {
        Object observed;
        while ((observed = disposition.get(server)) == null) Thread.sleep(10);
        System.out.println("CRASH-RECOVERY " + mode + " boot disposition=" + observed);
      } catch (ReflectiveOperationException | InterruptedException ignored) {
        // Evidence only.
      }
    }, "disposition-observer");
    observer.setDaemon(true);
    observer.start();
    try {
      server.start();
      activate(server);
    } catch (Exception | Error failure) {
      System.out.println("CRASH-RECOVERY " + mode + " boot failed after "
          + TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) + "s: " + failure);
      failure.printStackTrace(System.out);
      mark(root, "boot-failed");
      Runtime.getRuntime().halt(3);
    }
    var manager = server.indexGenerationManagerForTests();
    String active = manager.readStateBestEffort().active_generation();
    try (var view = server.captureServingView()) {
      assertEquals(active, view.searchRuntime().openedIndexPath().getFileName().toString());
    }
    System.out.println("CRASH-RECOVERY " + mode + " published active=" + active + " after "
        + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + "ms");
    mark(root, "published");
    String replayed = key(watched.resolve("replayed.txt"));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!search(server, TOKEN).contains(replayed) && System.nanoTime() < deadline) Thread.sleep(100);
    System.out.println("CRASH-RECOVERY " + mode + " search " + TOKEN + " -> " + search(server, TOKEN));
    if (search(server, TOKEN).contains(replayed)) mark(root, "searched");
    var queue = (SqliteJobQueue) server.jobQueueForTests();
    while (queue.listSwitchBufferOpsStrict().stream().anyMatch(op -> active.equals(op.generation()))
        && System.nanoTime() < deadline) Thread.sleep(100);
    var remaining = queue.listSwitchBufferOpsStrict();
    System.out.println("CRASH-RECOVERY " + mode + " remaining receipts="
        + remaining.stream().map(op -> op.op() + " " + op.generation() + " " + op.key()).toList());
    if (remaining.stream().noneMatch(op -> active.equals(op.generation()))) mark(root, "settled");
    mark(root, "verified");
    Runtime.getRuntime().halt(0);
  }

  /** Existing recorded-ingestion fixture contract: the test owns and authorizes one exact plan. */
  private static RecordedIngestionLifecycle lifecycle(Path root) {
    return new RecordedIngestionLifecycle() {
      @Override public IndexGenerationManager.BootOwnership bootOwnership(JobQueue queue) throws IOException {
        if (!Files.exists(root.resolve("recorded-source"))) return new IndexGenerationManager.BootOwnership.Native();
        return new IndexGenerationManager.BootOwnership.Recorded(OPERATION,
            Files.readString(root.resolve("recorded-source")), SOURCE,
            Files.readString(root.resolve("fingerprint")), true, true, List.of());
      }
      @Override public boolean recordedCutoverReady(String operationKey) { return OPERATION.equals(operationKey); }
      @Override public boolean beforeRecordedPromotion(String operationKey, JobQueue queue) {
        return OPERATION.equals(operationKey);
      }
      @Override public IndexGenerationManager.State promoteRecordedGeneration(String key, JobQueue queue,
          CheckedPromotion promotion) throws IOException {
        assertEquals(OPERATION, key);
        return promotion.promote();
      }
      @Override public JobQueue.RecordedClaimDecision recordedClaimDecision(String key) {
        return JobQueue.RecordedClaimDecision.ALLOW;
      }
      @Override public Attachment attach(JobQueue queue, CheckedServingGeneration generation,
          java.util.function.BooleanSupplier online) { return () -> {}; }
    };
  }
}
