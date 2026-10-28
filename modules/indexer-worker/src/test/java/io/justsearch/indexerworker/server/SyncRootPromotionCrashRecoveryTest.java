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
import io.justsearch.indexerworker.queue.SwitchBufferDelete;
import io.justsearch.indexerworker.queue.SwitchBufferSyncRoot;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.NoVolumeIdentityExtension;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.ipc.MigrationStartRequest;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real Engine process death after the durable generation pointer moved and before the replayed
 * switch-buffer receipts were acknowledged; the restarted Engine must publish B promptly.
 *
 * <p>One child driver serves every case. Its migration journals B's receipts at SWITCHING and
 * stops at one named cut; a restart publishes, searches and records what B holds. Each case keeps
 * its own cut and its own named assertions.
 */
@Timeout(600)
final class SyncRootPromotionCrashRecoveryTest {
  private static final String OPERATION = "01994180-0000-7000-8000-000000000951";
  private static final String SOURCE = "sync-root-crash-test";
  private static final String TOKEN = "quartzreplaywitness";
  private static final long STARTUP_SECONDS = 30;
  /** Long enough to observe the committed-root settlement deadline (3 minutes) end the boot. */
  private static final long OBSERVE_SECONDS = 210;
  private static final String AFTER_POINTER = "migration-after-pointer-commit";
  private static final String AFTER_PUBLICATION = "migration-after-live-activation";
  /** Every token a restart searches for, by the file that holds it. */
  private static final Map<String, String> TOKENS = Map.ofEntries(
      Map.entry(TOKEN, "watched/replayed.txt"), Map.entry("keeptoken", "watched/keep.txt"),
      Map.entry("controltoken", "other/control.txt"),
      Map.entry("ubeforetoken", "watched/u-before.txt"), Map.entry("uaftertoken", "watched/u-after.txt"),
      Map.entry("dbeforetoken", "watched/d-before.txt"), Map.entry("daftertoken", "watched/d-after.txt"),
      Map.entry("oupserttoken", "other/o-upsert.txt"), Map.entry("odeletetoken", "other/o-delete.txt"),
      Map.entry("addedtoken", "watched/added.txt"), Map.entry("removedtoken", "watched/remove-me.txt"),
      Map.entry("oldchangetoken", "watched/change-me.txt"),
      Map.entry("newchangetoken", "watched/change-me.txt"),
      Map.entry("awaytoken", "watched/while-away.txt"), Map.entry("aftercuttoken", "watched/after-cut.txt"));

  /** S1, with S3 for a force=true receipt: B's only retained receipt is its SYNC_ROOT. */
  @Test
  void nativeBootPublishesReplayedRootAfterPointerCrash(@TempDir Path root) throws Exception {
    migrateAndCut(root, "NATIVE", "root-only", AFTER_POINTER);
    changeRootWhileStopped(root);
    var restart = restart(root, "NATIVE", "root-only", "restart", OBSERVE_SECONDS);
    assertRootReplayRecovered("S1", restart);
    assertAll("S3 force=true: B matches the disk changed while the Engine was stopped",
        () -> assertHits(restart, "addedtoken", "watched/added.txt"),
        () -> assertHits(restart, "newchangetoken", "watched/change-me.txt"),
        () -> assertHits(restart, "oldchangetoken"),
        () -> assertHits(restart, "removedtoken"),
        () -> assertHits(restart, "keeptoken", "watched/keep.txt"));
  }

  /** P1: the recorded owner's PROMOTED boot still acknowledges the same retained state. */
  @Test
  void promotedBootPublishesReplayedRootAfterPointerCrash(@TempDir Path root) throws Exception {
    migrateAndCut(root, "PROMOTED", "root-only", AFTER_POINTER);
    assertRootReplayRecovered("P1", restart(root, "PROMOTED", "root-only", "restart", STARTUP_SECONDS));
  }

  /**
   * S2, with S3 for a force=false receipt: SYNC_ROOT among exact file receipts inside and outside
   * its root, accepted before and after it.
   */
  @Test
  void mixedFileReceiptsAroundTheRootSettleInAcceptedOrder(@TempDir Path root) throws Exception {
    migrateAndCut(root, "NATIVE", "mixed", AFTER_POINTER);
    changeRootWhileStopped(root);
    var restart = restart(root, "NATIVE", "mixed", "restart", STARTUP_SECONDS);
    assertRootReplayRecovered("S2", restart);
    assertAll("S2: accepted order and current disk",
        () -> assertHits(restart, "ubeforetoken", "watched/u-before.txt"),
        () -> assertHits(restart, "uaftertoken", "watched/u-after.txt"),
        () -> assertHits(restart, "dbeforetoken", "watched/d-before.txt"),
        () -> assertHits(restart, "daftertoken"),
        () -> assertHits(restart, "oupserttoken", "other/o-upsert.txt"),
        () -> assertHits(restart, "odeletetoken"),
        () -> assertHits(restart, "controltoken", "other/control.txt"));
    assertAll("S3 force=false: B matches the disk changed while the Engine was stopped",
        () -> assertHits(restart, "addedtoken", "watched/added.txt"),
        () -> assertHits(restart, "newchangetoken", "watched/change-me.txt"),
        () -> assertHits(restart, "oldchangetoken"),
        () -> assertHits(restart, "removedtoken"));
  }

  /** S4a: a second abrupt stop once the new settlement acknowledged B's receipts. */
  @Test
  void secondStopAfterReceiptAcknowledgementStillReconcilesOnTheNextBoot(@TempDir Path root)
      throws Exception {
    migrateAndCut(root, "NATIVE", "root-only", AFTER_POINTER);
    Process interrupted = child(root, "NATIVE", "root-only", "restart-cut");
    try {
      awaitMarker(interrupted, root.resolve("replay-settled"), STARTUP_SECONDS);
    } finally {
      kill(interrupted);
    }
    System.out.println("CRASH-RECOVERY NATIVE restart-cut evidence:\n" + evidence(log(root, "restart-cut")));
    assertTrue(Files.exists(root.resolve("replay-settled")),
        "S4a: the first restart must settle B's receipts before root reconciliation");
    var state = new IndexGenerationManager(root.resolve("data/index")).readStateBestEffort();
    assertAll("S4a: retryable evidence after the second stop",
        () -> assertEquals(Files.readString(root.resolve("building")), state.active_generation()),
        () -> assertNotNull(state.previous_generation(), "the predecessor keeps the next boot committed"),
        () -> assertEquals(List.of(), retained(root, state.active_generation()), "receipts were acknowledged"));
    // Only the retained predecessor can make the next boot reconcile this change.
    Files.writeString(root.resolve("watched/after-cut.txt"), "aftercuttoken");
    var restart = restart(root, "NATIVE", "root-only", "restart", STARTUP_SECONDS);
    assertRootReplayRecovered("S4a", restart);
    assertHits(restart, "aftercuttoken", "watched/after-cut.txt");
  }

  /** S5: the SYNC_ROOT's root is on another volume at restart and comes back while running. */
  @Test
  void unavailableRootIsDeferredAndReconciledWhenItsVolumeReturns(@TempDir Path root) throws Exception {
    migrateAndCut(root, "NATIVE", "root-only", AFTER_POINTER);
    Files.writeString(root.resolve("watched/while-away.txt"), "awaytoken");
    var restart = restart(root, "NATIVE", "unavailable", "restart", STARTUP_SECONDS);
    assertAll("S5: boot serves while the root is away",
        () -> assertTrue(restart.published(), "boot must publish within " + STARTUP_SECONDS + "s"),
        () -> assertTrue(restart.settled(), "B's receipts must settle"),
        () -> assertHits(restart, "controltoken", "other/control.txt"),
        () -> assertHits(restart, "keeptoken", "watched/keep.txt"),
        () -> assertHits(restart, TOKEN, "watched/replayed.txt"),
        () -> assertHits(restart, "awaytoken"),
        () -> assertEquals("true", restart.deferred().getProperty("previousKept"),
            "the predecessor stays retained while the root is away"));
    var returned = restart.returned();
    assertAll("S5: the returned volume reconciles without another restart",
        () -> assertEquals("true", returned.getProperty("previousRetired")),
        () -> assertEquals(key(root.resolve("watched/while-away.txt")), returned.getProperty("awaytoken")),
        () -> assertEquals(key(root.resolve("watched/keep.txt")), returned.getProperty("keeptoken")));
  }

  /** S6: as S1, with the stop after live publication rather than right after the pointer. */
  @Test
  void nativeBootPublishesReplayedRootAfterPublicationCrash(@TempDir Path root) throws Exception {
    migrateAndCut(root, "NATIVE", "root-only", AFTER_PUBLICATION);
    assertRootReplayRecovered("S6", restart(root, "NATIVE", "root-only", "restart", STARTUP_SECONDS));
  }

  // ---- parent side ----

  private record Restart(Path root, boolean published, boolean settled, long seconds,
      Properties hits, Properties deferred, Properties returned) {}

  private static void migrateAndCut(Path root, String mode, String scenario, String cut) throws Exception {
    Files.writeString(root.resolve("child.args"), "-cp\n\"" + System.getProperty("java.class.path")
        .replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n");
    Files.writeString(root.resolve("cut-point"), cut);
    Process migrating = child(root, mode, scenario, "migrate");
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
    var retained = retained(root, committed.active_generation());
    System.out.println("CRASH-RECOVERY " + mode + " " + scenario + " retained after " + cut + ": " + retained);
    assertTrue(retained.contains("SYNC_ROOT"), "the crash must retain the scoped receipt");
  }

  private static List<String> retained(Path root, String generation) throws Exception {
    try (var queue = new SqliteJobQueue(root.resolve("data/jobs.db"))) {
      queue.open();
      return queue.listSwitchBufferOpsStrict().stream().filter(op -> generation.equals(op.generation()))
          .map(op -> op.op()).toList();
    }
  }

  /** S3: one file added, one removed and one changed while no Engine runs. */
  private static void changeRootWhileStopped(Path root) throws IOException {
    Files.writeString(root.resolve("watched/added.txt"), "addedtoken");
    Files.delete(root.resolve("watched/remove-me.txt"));
    Files.writeString(root.resolve("watched/change-me.txt"), "newchangetoken");
  }

  private static Restart restart(Path root, String mode, String scenario, String phase, long observe)
      throws Exception {
    Process restarted = child(root, mode, scenario, phase);
    long started = System.nanoTime();
    boolean published;
    try {
      awaitMarker(restarted, root.resolve("published"), STARTUP_SECONDS);
      published = Files.exists(root.resolve("published"));
      // Keep the child until it reports its outcome, so a held boot shows how it ends.
      awaitMarker(restarted, root.resolve(published ? "verified" : "boot-failed"),
          published ? 120 : observe);
    } finally {
      kill(restarted);
    }
    long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
    for (String logged : List.of("migrate", phase)) {
      System.out.println("CRASH-RECOVERY " + mode + " " + scenario + " " + logged + " evidence:\n"
          + evidence(log(root, logged)));
    }
    System.out.println("CRASH-RECOVERY " + mode + " " + scenario + " restart observed for " + seconds
        + "s; published=" + published + " markers=" + List.of("published", "searched", "settled",
            "verified", "boot-failed").stream().filter(name -> Files.exists(root.resolve(name))).toList());
    var restart = new Restart(root, published, Files.exists(root.resolve("settled")), seconds,
        properties(root.resolve("hits.properties")), properties(root.resolve("deferred.properties")),
        properties(root.resolve("returned.properties")));
    // A further restart of the same root writes its own markers.
    for (String marker : List.of("published", "searched", "settled", "verified", "boot-failed")) {
      Files.deleteIfExists(root.resolve(marker));
    }
    return restart;
  }

  private static void assertRootReplayRecovered(String scenario, Restart restart) {
    assertAll(scenario + " crash recovery",
        () -> assertTrue(restart.published(), "boot must publish promoted generation within "
            + STARTUP_SECONDS + "s (took " + restart.seconds() + "s)"),
        () -> assertHits(restart, TOKEN, "watched/replayed.txt"),
        () -> assertTrue(restart.settled(), "the retained generation-scoped receipts must settle"));
  }

  /** Exactly these files hold {@code token}: nothing lost, nothing duplicated. */
  private static void assertHits(Restart restart, String token, String... files) {
    List<String> expected = java.util.Arrays.stream(files).map(file -> key(restart.root().resolve(file))).toList();
    String found = restart.hits().getProperty(token);
    assertEquals(expected, found == null || found.isEmpty() ? List.of() : List.of(found.split("\n")),
        "search hits for " + token);
  }

  private static Properties properties(Path file) throws IOException {
    var properties = new Properties();
    if (Files.exists(file)) {
      try (Reader reader = Files.newBufferedReader(file)) { properties.load(reader); }
    }
    return properties;
  }

  private static Process child(Path root, String mode, String scenario, String phase) throws IOException {
    String scratch = System.getenv("TMPDIR");
    Path jvm = Files.createDirectories(scratch == null ? Path.of(System.getProperty("java.io.tmpdir"))
        : Path.of(scratch, "builder", "jvm"));
    var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xmx512m", "--enable-native-access=ALL-UNNAMED", "-Dnet.bytebuddy.experimental=true",
        "-Djava.io.tmpdir=" + jvm, "@" + root.resolve("child.args"),
        SyncRootPromotionCrashRecoveryTest.class.getName(), root.toString(), mode, scenario, phase)
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
        || line.contains("Committed boot deferred")
        || line.contains("Committed native mutation witnesses remain unresolved")
        || line.contains("Committed native file witnesses remain unresolved")
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

  // ---- child side ----

  /** Same child-process pattern as Release030RetirementCrashTest; no close or shutdown hook at a cut. */
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    String mode = args[1];
    String scenario = args[2];
    String phase = args[3];
    Path dataDir = root.resolve("data");
    Path indexBase = dataDir.resolve("index");
    Path watched = root.resolve("watched");
    Path other = root.resolve("other");
    if (phase.equals("migrate")) seedRoots(root, scenario.equals("mixed"));
    if (scenario.equals("unavailable") && !phase.equals("migrate")) {
      NoVolumeIdentityExtension.installVolumeLookupWithFolderAway(watched,
          () -> !Files.exists(root.resolve("volume-returned")));
    }
    NoVolumeIdentityExtension.installVolumeLookupIfAbsent();
    Files.createDirectories(dataDir);
    WorkerBootFixture.publishConfig(dataDir, indexBase, "BLUE_GREEN_MIGRATE", Map.of(
        "justsearch.ai.disabled", "true",
        "justsearch.ai.embed.enabled", "false",
        "justsearch.splade.enabled", "false",
        "justsearch.ner.enabled", "false",
        "justsearch.extraction.sandbox.mode", "IN_PROCESS"));
    Files.writeString(dataDir.resolve("watched_roots.json"), new tools.jackson.databind.ObjectMapper()
        .writeValueAsString(Map.of("schemaVersion", 1, "roots", List.of(
            Map.of("path", watched.toString()), Map.of("path", other.toString())))));
    var executors = new TestEngineExecutors();
    var server = org.mockito.Mockito.spy(new KnowledgeServer(executors,
        WorkerBootFixture.workerConfig(dataDir), null, ManagedChildRegistry.noop(), lifecycle(root)));
    org.mockito.Mockito.doNothing().when(server)
        .startDeferredModelInitialization(org.mockito.ArgumentMatchers.any());
    server.bindBootRootBindings(List.of(new RootBinding(watched, null), new RootBinding(other, null)));
    server.bindExclusionRoots(() -> List.of(watched, other));
    if (phase.equals("migrate")) {
      migrate(root, mode, scenario, server, watched, other);
    } else {
      restart(root, mode, phase, server);
    }
  }

  /**
   * Mixed roots hold files before the migration, so B also journals the build's own file
   * receipts. Root-only roots start empty: their files reach B through the SYNC_ROOT alone.
   */
  private static void seedRoots(Path root, boolean files) throws IOException {
    Path watched = Files.createDirectories(root.resolve("watched"));
    Path other = Files.createDirectories(root.resolve("other"));
    if (!files) return;
    seedRootFiles(watched, other);
    Files.writeString(watched.resolve("d-before.txt"), "dbeforetoken");
    Files.writeString(watched.resolve("d-after.txt"), "daftertoken");
    Files.writeString(other.resolve("o-delete.txt"), "odeletetoken");
  }

  private static void seedRootFiles(Path watched, Path other) throws IOException {
    Files.writeString(watched.resolve("keep.txt"), "keeptoken");
    Files.writeString(watched.resolve("change-me.txt"), "oldchangetoken");
    Files.writeString(watched.resolve("remove-me.txt"), "removedtoken");
    Files.writeString(other.resolve("control.txt"), "controltoken");
  }

  private static void migrate(Path root, String mode, String scenario, KnowledgeServer server,
      Path watched, Path other) throws Exception {
    String cut = Files.readString(root.resolve("cut-point"));
    server.installMigrationBarrierForTests(transition -> {
      if (transition.point().equals("migration-switching-entered")) {
        journal(scenario, (SqliteJobQueue) server.jobQueueForTests(), transition.buildingGeneration(),
            watched, other);
      }
      if (transition.point().equals(cut)) {
        var queue = (SqliteJobQueue) server.jobQueueForTests();
        System.out.println("CRASH-RECOVERY cut=" + cut + " active="
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

  /**
   * B's receipts, accepted at SWITCHING in this order, as production journals them. The root's
   * enumeration already finished, so only the replayed walk can introduce replayed.txt into B.
   */
  private static void journal(String scenario, SqliteJobQueue queue, String building, Path watched,
      Path other) throws IOException {
    boolean mixed = scenario.equals("mixed");
    if (mixed) {
      upsert(queue, building, watched, write(watched.resolve("u-before.txt"), "ubeforetoken"));
      upsert(queue, building, other, write(other.resolve("o-upsert.txt"), "oupserttoken"));
      delete(queue, building, watched.resolve("d-before.txt")); // stays on disk: the walk restores it
    }
    if (!mixed) seedRootFiles(watched, other); // without receipts: only reconciliation sees other/
    Files.writeString(watched.resolve("replayed.txt"), TOKEN);
    // IngestSwitchBufferOps.bufferSyncDirectoryDuringSwitching
    String normalized = PathNormalizer.normalizePathPrefix(watched.toString());
    assertTrue(queue.putSyncRootForGeneration(building, "sync_root:" + normalized,
        new SwitchBufferSyncRoot(normalized, !mixed, null)));
    if (mixed) {
      upsert(queue, building, watched, write(watched.resolve("u-after.txt"), "uaftertoken"));
      Files.delete(watched.resolve("d-after.txt"));
      delete(queue, building, watched.resolve("d-after.txt"));
      Files.delete(other.resolve("o-delete.txt"));
      delete(queue, building, other.resolve("o-delete.txt"));
    }
    System.out.println("CRASH-RECOVERY journaled generation=" + building + " receipts="
        + queue.listSwitchBufferOpsStrict().stream().map(op -> op.op() + " " + op.key()).toList());
  }

  private static Path write(Path file, String content) throws IOException {
    return Files.writeString(file, content);
  }

  /** WorkerIngestService.acceptWatcherUpsert's candidate admission. */
  private static void upsert(SqliteJobQueue queue, String building, Path root, Path file) {
    assertTrue(queue.enqueueAndBufferFileForGeneration(building,
        JobQueue.EnqueueEntry.stat(file).withinRoot(root), null));
  }

  /** IngestSwitchBufferOps.journalDeleteForGeneration. */
  private static void delete(SqliteJobQueue queue, String building, Path file) {
    String id = key(file);
    assertTrue(queue.putSwitchBufferForGeneration(building, "path:" + id, "DELETE",
        new SwitchBufferDelete(id, SwitchBufferDelete.Origin.EXPLICIT, null).encode()));
  }

  @SuppressWarnings("PMD.DoNotTerminateVM") // The isolated child ends without close or shutdown hooks.
  private static void restart(Path root, String mode, String phase, KnowledgeServer server)
      throws Exception {
    long started = System.nanoTime();
    if (phase.equals("restart-cut")) {
      server.installMigrationBarrierForTests(transition -> {
        if (!transition.point().equals("committed-boot-replay-settled")) return;
        System.out.println("CRASH-RECOVERY " + mode + " stop at " + transition.point() + " active="
            + transition.sourceGeneration());
        mark(root, "replay-settled");
        while (true) Thread.sleep(1_000);
      });
    }
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
    var queue = (SqliteJobQueue) server.jobQueueForTests();
    String replayed = key(root.resolve("watched/replayed.txt"));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while ((!search(server, TOKEN).contains(replayed) || busy(queue, root)) && System.nanoTime() < deadline) {
      Thread.sleep(100);
    }
    System.out.println("CRASH-RECOVERY " + mode + " search " + TOKEN + " -> " + search(server, TOKEN));
    if (search(server, TOKEN).contains(replayed)) mark(root, "searched");
    while (queue.listSwitchBufferOpsStrict().stream().anyMatch(op -> active.equals(op.generation()))
        && System.nanoTime() < deadline) Thread.sleep(100);
    var remaining = queue.listSwitchBufferOpsStrict();
    System.out.println("CRASH-RECOVERY " + mode + " remaining receipts="
        + remaining.stream().map(op -> op.op() + " " + op.generation() + " " + op.key()).toList());
    if (remaining.stream().noneMatch(op -> active.equals(op.generation()))) mark(root, "settled");
    store(root.resolve("hits.properties"), hits(server));
    if (Files.exists(root.resolve("watched/while-away.txt"))) awaitReturnedVolume(root, server, manager);
    mark(root, "verified");
    Runtime.getRuntime().halt(0);
  }

  /** S5: evidence while the root is away, then after its volume returns to the running Engine. */
  private static void awaitReturnedVolume(Path root, KnowledgeServer server, IndexGenerationManager manager)
      throws Exception {
    var away = manager.readStateBestEffort();
    var deferred = new Properties();
    deferred.setProperty("previousKept", Boolean.toString(away.previous_generation() != null
        && Files.isDirectory(manager.resolveGenerationPathStrict(away.previous_generation()))));
    store(root.resolve("deferred.properties"), deferred);
    mark(root, "volume-returned");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
    String awayFile = key(root.resolve("watched/while-away.txt"));
    while ((manager.readStateBestEffort().previous_generation() != null
        || !search(server, "awaytoken").contains(awayFile)) && System.nanoTime() < deadline) {
      Thread.sleep(250);
    }
    var returned = hits(server);
    returned.setProperty("previousRetired",
        Boolean.toString(manager.readStateBestEffort().previous_generation() == null));
    store(root.resolve("returned.properties"), returned);
  }

  private static boolean busy(SqliteJobQueue queue, Path root) {
    return queue.hasNonterminalJobsByPathPrefixStrict(PathNormalizer.normalizePathPrefix(root.toString()));
  }

  private static Properties hits(KnowledgeServer server) {
    var hits = new Properties();
    for (String token : TOKENS.keySet()) hits.setProperty(token, String.join("\n", search(server, token)));
    return hits;
  }

  private static void store(Path file, Properties properties) throws IOException {
    try (Writer writer = Files.newBufferedWriter(file)) { properties.store(writer, null); }
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
