/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.activate;
import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.key;
import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.search;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.app.api.indexing.ProjectionSeedSource;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real Engine process death after the durable generation pointer moved and before the replayed
 * switch-buffer receipts were acknowledged; the restarted Engine must publish B promptly.
 *
 * <p>One child driver serves every case. Its migration journals B's receipts at SWITCHING and
 * stops at one named cut, or completes and acknowledges them as an uninterrupted migration does;
 * a restart publishes, searches and records what B holds. Each case keeps its own named
 * assertions. Parity is judged against a control in the same harness: an uninterrupted migration
 * with the same receipts, the same offline changes and an ordinary restart.
 */
@Timeout(600)
final class SyncRootPromotionCrashRecoveryTest {
  private static final String OPERATION = "01994180-0000-7000-8000-000000000951";
  private static final String SOURCE = "sync-root-crash-test";
  private static final String TOKEN = "quartzreplaywitness";
  private static final long STARTUP_SECONDS = 30;
  private static final String AFTER_POINTER = "migration-after-pointer-commit";
  private static final String AFTER_PUBLICATION = "migration-after-live-activation";
  /** The control's cut: no stop; the migration completes and acknowledges its receipts. */
  private static final String UNINTERRUPTED = "complete";
  private static final List<String> WATCHED = List.of("watched", "other", "away", "gone", "guarded", "dropped");
  private static final List<String> COLLECTIONS =
      List.of("archive", "shelf", "loose", "projected", "cleared");
  /** Every token a restart searches for, by the file that holds it. */
  private static final Map<String, String> TOKENS = Map.ofEntries(
      Map.entry(TOKEN, "watched/replayed.txt"), Map.entry("keeptoken", "watched/keep.txt"),
      Map.entry("controltoken", "other/control.txt"),
      Map.entry("addedtoken", "watched/added.txt"), Map.entry("removedtoken", "watched/remove-me.txt"),
      Map.entry("oldchangetoken", "watched/change-me.txt"), Map.entry("newchangetoken", "watched/change-me.txt"),
      Map.entry("archivekeeptoken", "archive/keep.txt"), Map.entry("archiveaddedtoken", "archive/added.txt"),
      Map.entry("archiveremovedtoken", "archive/remove-me.txt"),
      Map.entry("archiveoldtoken", "archive/change-me.txt"), Map.entry("archivenewtoken", "archive/change-me.txt"),
      Map.entry("awaykeeptoken", "away/keep.txt"), Map.entry("awaytoken", "away/while-away.txt"),
      Map.entry("gonetoken", "gone/gone.txt"), Map.entry("backtoken", "gone/back.txt"),
      Map.entry("shelftoken", "shelf/shelf.txt"),
      Map.entry("droppedtoken", "dropped/dropped.txt"), Map.entry("loosetoken", "loose/loose.txt"),
      Map.entry("guardedopentoken", "guarded/open.txt"), Map.entry("guardedsecrettoken", "guarded/locked/secret.txt"),
      Map.entry("ubeforetoken", "watched/u-before.txt"), Map.entry("uaftertoken", "watched/u-after.txt"),
      Map.entry("dbeforetoken", "watched/d-before.txt"), Map.entry("daftertoken", "watched/d-after.txt"),
      Map.entry("oupserttoken", "other/o-upsert.txt"), Map.entry("odeletetoken", "other/o-delete.txt"),
      Map.entry("arestoredtoken", "archive/a-restored.txt"),
      Map.entry("lateoldtoken", "shelf/late.txt"), Map.entry("latenewtoken", "shelf/late.txt"),
      Map.entry("projectedfiletoken", "projected/file.txt"), Map.entry("clearedtoken", "cleared/cleared.txt"),
      Map.entry("shelfrestoredtoken", "shelf/restored.txt"),
      Map.entry("forgottentoken", "watched/forgotten.txt"), Map.entry("excludedtoken", "watched/excluded.txt"),
      Map.entry("earlyprojectiontoken", "-"), Map.entry("laterprojectiontoken", "-"));
  private static final String PROJECTION_SOURCE = "memory";
  /** Under the watched root: a file forgotten (its exact-path policy entry) and one excluded by pattern. */
  private static final String FORGOTTEN = "watched/forgotten.txt";
  private static final String EXCLUDED = "watched/excluded.txt";

  /**
   * S1, S3 and S5 against the control. B retains only SYNC_ROOTs: for a watched root and a
   * configured collection root that change while stopped (S3), a watched root whose volume is
   * away (S5a), a collection root deleted from disk (S5b), and a watched root and a collection
   * root no longer declared (S5c). {@link #deletedAndUnreadableWatchedRootsLetBootPublishAsTheControl}
   * covers a deleted watched root and S8. These children run with the Engine's native policy owner:
   * the watched root also holds a forgotten and an excluded file, both saved again while stopped;
   * boot neither indexes nor requeues them.
   */
  @Test
  void retainedRootSyncsRestartAsAnUninterruptedMigrationDoes(@TempDir Path temporary) throws Exception {
    Path crash = Files.createDirectories(temporary.resolve("crash"));
    Path control = Files.createDirectories(temporary.resolve("control"));
    all(() -> migrateAndCut(crash, "NATIVE", "roots", AFTER_POINTER),
        () -> migrateAndCut(control, "NATIVE", "roots", UNINTERRUPTED));
    for (Path root : List.of(crash, control)) changeRootsWhileStopped(root);
    var restarts = all(() -> restart(crash, "NATIVE", "roots", "restart"),
        () -> restart(control, "NATIVE", "roots", "restart"));
    var restart = restarts.get(0);
    var ordinary = restarts.get(1);
    assertTrue(ordinary.published(), "the control's ordinary restart must publish");
    assertRootSyncsSettled("S1", restart);
    assertAll("S1: the configured collection root's files reach B only through its committed sync",
        () -> assertHits(restart, "archivekeeptoken", "archive/keep.txt"));
    assertAll("S3: a watched root matches its current disk, as ordinary boot reconciles it",
        () -> assertHits(restart, "addedtoken", "watched/added.txt"),
        () -> assertHits(restart, "newchangetoken", "watched/change-me.txt"),
        () -> assertHits(restart, "oldchangetoken"),
        () -> assertHits(restart, "removedtoken"),
        () -> assertHits(restart, "keeptoken", "watched/keep.txt"));
    assertAll("S3: a configured collection root keeps B's committed state; restarts do not reconcile it",
        () -> assertHits(restart, "archiveaddedtoken"),
        () -> assertHits(restart, "archivenewtoken"),
        () -> assertHits(restart, "archiveoldtoken", "archive/change-me.txt"),
        () -> assertHits(restart, "archiveremovedtoken", "archive/remove-me.txt"));
    assertAll("S5: other roots serve while a root is away, deleted or undeclared",
        () -> assertHits(restart, "controltoken", "other/control.txt"));
    assertAll("Forget: a forgotten and an excluded file under the retained SYNC_ROOT's root stay out",
        () -> assertTrue(restart.published(), "boot must publish B"),
        () -> assertHits(restart, "forgottentoken"),
        () -> assertHits(restart, "excludedtoken"),
        () -> assertEquals(restart.hits().getProperty("@kept-out-jobs-before"),
            restart.hits().getProperty("@kept-out-jobs"), "boot requeues neither file"));
    for (String token : List.of("awaykeeptoken", "awaytoken", "shelftoken",
        "droppedtoken", "loosetoken")) {
      assertEquals(relative(ordinary, ordinary.hits(), token), relative(restart, restart.hits(), token),
          () -> "S5: " + token + " follows the ordinary-restart rule");
    }
    assertEquals(parity(ordinary), parity(restart),
        "S3/S5 parity: search results and durable state equal the control's");
  }

  /**
   * S5b and S8 against the control, in one set of children. S5b: a watched root whose SYNC_ROOT B
   * retains is deleted while stopped. S8: B built another watched root's subtree, which was
   * unreadable when that root's SYNC_ROOT walked, so the walk could not verify deletes. Boot
   * publishes within normal startup; the deleted root's file follows the ordinary-restart rule and
   * B keeps the unreadable subtree's file. Once the root returns, permissions return and the Head's
   * periodic sync walks both, B equals the control. The PROMOTED boot of the same retained state
   * publishes too; its recorded build never read the locked file, so it is compared with the
   * control on the deleted root at restart and on both once they are back.
   */
  @Test
  void deletedAndUnreadableWatchedRootsLetBootPublishAsTheControl(@TempDir Path temporary) throws Exception {
    try {
      var restarts = crashesAndControl(temporary, "withheld", root -> deleteTree(root.resolve("gone")));
      var restart = restarts.get(0);
      var ordinary = restarts.get(1);
      var promoted = restarts.get(2);
      assertAll("S8 control: an ordinary restart keeps the file its walk could not read",
          () -> assertTrue(ordinary.published(), "the control's ordinary restart must publish"),
          () -> assertHits(ordinary, "guardedsecrettoken", "guarded/locked/secret.txt"),
          () -> assertEquals(key(ordinary.root().resolve("guarded/locked/secret.txt")),
              ordinary.returned().getProperty("guardedsecrettoken"), "after permissions return"));
      assertRootSyncsSettled("S5b and S8", restart);
      assertAll("S5b: a deleted watched root follows the ordinary-restart rule",
          () -> assertEquals(relative(ordinary, ordinary.hits(), "gonetoken"),
              relative(restart, restart.hits(), "gonetoken"), "gonetoken at restart"),
          () -> assertEquals(relative(ordinary, ordinary.returned(), "backtoken"),
              relative(restart, restart.returned(), "backtoken"), "the returned root's added file"));
      assertAll("S8: a sync whose walk could not verify deletes loses nothing B holds",
          () -> assertHits(restart, "guardedopentoken", "guarded/open.txt"),
          () -> assertHits(restart, "guardedsecrettoken", "guarded/locked/secret.txt"),
          () -> assertEquals(key(restart.root().resolve("guarded/locked/secret.txt")),
              restart.returned().getProperty("guardedsecrettoken"), "after permissions return"));
      assertAll("S5b and S8 parity",
          () -> assertEquals(parity(ordinary), parity(restart), "at restart B equals the control"),
          () -> assertEquals(relative(ordinary, ordinary.returned()), relative(restart, restart.returned()),
              "once the root and permissions return B equals the control, its predecessor retired"));
      assertRootSyncsSettled("S5b and S8 PROMOTED", promoted);
      var promotedReturn = relative(ordinary, ordinary.returned());
      promotedReturn.remove("@previous-retired"); // this harness's recorded owner retires nothing
      assertAll("S5b and S8 PROMOTED",
          () -> assertHits(promoted, "guardedopentoken", "guarded/open.txt"),
          () -> assertEquals(relative(ordinary, ordinary.hits(), "gonetoken"),
              relative(promoted, promoted.hits(), "gonetoken"), "gonetoken at restart"),
          () -> assertEquals(promotedReturn, relative(promoted, promoted.returned()),
              "once the root and permissions return B equals the control"));
    } finally {
      for (String name : RESTARTS) unlock(temporary.resolve(name).resolve("guarded/locked"));
    }
  }

  /** P1: the recorded owner's PROMOTED boot still acknowledges the same retained state. */
  @Test
  void promotedBootPublishesReplayedRootAfterPointerCrash(@TempDir Path root) throws Exception {
    migrateAndCut(root, "PROMOTED", "publication", AFTER_POINTER);
    assertRootSyncsSettled("P1", restart(root, "PROMOTED", "publication", "restart"));
  }

  /**
   * S2: SYNC_ROOTs among exact file receipts inside and outside their roots, accepted before and
   * after them. Inside the configured collection root, a DELETE is followed by the force=true sync
   * that restores its file. (An UPSERT followed by a force=true sync of its file never drains at
   * the cutover, so no pointer commits it; CommittedNativeSyncRootTest covers that supersession.)
   */
  @Test
  void mixedFileReceiptsAroundRootSyncsSettleInAcceptedOrder(@TempDir Path root) throws Exception {
    migrateAndCut(root, "NATIVE", "mixed", AFTER_POINTER);
    var restart = restart(root, "NATIVE", "mixed", "restart");
    assertRootSyncsSettled("S2", restart);
    assertAll("S2: B keeps the accepted-order result its commit holds",
        () -> assertHits(restart, "ubeforetoken", "watched/u-before.txt"),
        () -> assertHits(restart, "uaftertoken", "watched/u-after.txt"),
        () -> assertHits(restart, "dbeforetoken", "watched/d-before.txt"),
        () -> assertHits(restart, "daftertoken"),
        () -> assertHits(restart, "oupserttoken", "other/o-upsert.txt"),
        () -> assertHits(restart, "odeletetoken"),
        () -> assertHits(restart, "controltoken", "other/control.txt"));
    assertAll("S2: a superseded DELETE inside the collection root is not replayed over the restored file",
        () -> assertHits(restart, "arestoredtoken", "archive/a-restored.txt"));
  }

  /**
   * S4: a revision of an UPSERT receipt replaces the cutover's after its snapshot. The first
   * restart's first SYNC_ROOT removal (S4b) and the removal of an exact DELETE that the later
   * force=true sync of its collection root superseded (S4c) are each ignored once; the restart
   * stops abruptly again once the receipts are acknowledged, before publication (S4a). The next
   * restart publishes and settles.
   */
  @Test
  void interruptedSettlementKeepsRetryableEvidenceAndSettlesOnRetry(@TempDir Path root)
      throws Exception {
    migrateAndCut(root, "NATIVE", "replacement", AFTER_POINTER);
    String generation = Files.readString(root.resolve("building"));
    String rootSync = "sync_root:" + PathNormalizer.normalizePathPrefix(root.resolve("watched").toString());
    String supersededDelete = "path:" + key(root.resolve("archive/a-restored.txt"));
    Files.write(root.resolve("refuse-removals"), List.of(rootSync, supersededDelete));
    var interrupted = restart(root, "NATIVE", "replacement", "restart-halt");
    Files.delete(root.resolve("refuse-removals"));
    var refused = Files.exists(root.resolve("refused")) ? Files.readAllLines(root.resolve("refused")) : List.of();
    var state = new IndexGenerationManager(root.resolve("data/index")).readStateBestEffort();
    assertAll("S4: the interrupted settlement leaves retryable evidence",
        () -> assertTrue(refused.contains(rootSync), "S4b: one receipt removal was refused"),
        () -> assertTrue(refused.contains(supersededDelete), "S4c: the superseded DELETE's removal was refused"),
        () -> assertTrue(interrupted.halted(), "S4a: the restart stopped at publication"),
        () -> assertFalse(interrupted.published(), "S4a: nothing was published"),
        () -> assertEquals(generation, state.active_generation()),
        () -> assertNotNull(state.previous_generation(), "the predecessor keeps the next boot committed"),
        () -> assertEquals(List.of(), retained(root, generation), "S4b/S4c: the refused removal was retried"));
    Files.writeString(root.resolve("await-retirement"), "yes");
    var restart = restart(root, "NATIVE", "replacement", "restart");
    assertRootSyncsSettled("S4", restart);
    assertAll("S4: the replacement revision's effect reached B; nothing lost or duplicated",
        () -> assertHits(restart, "latenewtoken", "shelf/late.txt"),
        () -> assertHits(restart, "lateoldtoken"),
        () -> assertHits(restart, "archivekeeptoken", "archive/keep.txt"),
        () -> assertEquals("true", restart.hits().getProperty("@previous-retired"),
            "the predecessor is retired once B settled"));
    assertAll("S4c: the retry acknowledged the whole snapshot; the superseded DELETE was not replayed",
        () -> assertHits(restart, "arestoredtoken", "archive/a-restored.txt"));
  }

  /**
   * S6: as S1, with the stop after live publication rather than right after the pointer. The
   * configured collection root's receipt was journaled by an agent over MCP; as after an ordinary
   * restart, a file added to it while stopped is not queued and the root gets no watcher.
   */
  @Test
  void nativeBootPublishesReplayedRootAfterPublicationCrash(@TempDir Path root) throws Exception {
    migrateAndCut(root, "NATIVE", "publication", AFTER_PUBLICATION);
    Files.writeString(root.resolve("archive/added.txt"), "archiveaddedtoken");
    var restart = restart(root, "NATIVE", "publication", "restart");
    assertRootSyncsSettled("S6", restart);
    assertAll("S6: acknowledging the collection root's sync admits nothing new",
        () -> assertHits(restart, "archivekeeptoken", "archive/keep.txt"),
        () -> assertHits(restart, "archiveaddedtoken"),
        () -> assertEquals(List.of(), jobAttribution(root, "archive/added.txt"),
            "P6: no fresh admission is queued for the collection root"),
        () -> assertEquals("false", restart.hits().getProperty("@archive-watched"),
            "an ordinary restart registers no watcher for a configured collection root"),
        () -> assertEquals("true", restart.hits().getProperty("@watched-watched"),
            "the declared watched root keeps its watcher"));
  }

  /**
   * S7a and S7b in reverse order, against the control: a projection accepted after a SYNC_ROOT and
   * inside its root stays searchable; a sync followed by a prefix or a collection delete over its
   * files leaves B as the control; the projection both broad deletes cover stays deleted. Beside
   * them (F1), inside a configured collection root, an explicit DELETE is followed by the
   * force=true sync that restores its file, before that projection is accepted.
   */
  @Test
  void syncRootBesideProjectionAndBroadDeletesPublishesCertifiedContent(@TempDir Path temporary)
      throws Exception {
    Path root = Files.createDirectories(temporary.resolve("crash"));
    Path control = Files.createDirectories(temporary.resolve("control"));
    all(() -> migrateAndCut(root, "NATIVE", "projection", AFTER_POINTER),
        () -> migrateAndCut(control, "NATIVE", "projection", UNINTERRUPTED));
    var retained = retained(root, Files.readString(root.resolve("building")));
    assertTrue(retained.containsAll(List.of("DELETE_PREFIX", "DELETE_COLLECTION", "PROJECTION")), retained::toString);
    var restarts = all(() -> restart(root, "NATIVE", "projection", "restart"),
        () -> restart(control, "NATIVE", "projection", "restart"));
    var restart = restarts.get(0);
    assertAll("F1: a DELETE a later sync restores does not hold boot beside a later projection",
        () -> assertTrue(restart.published(), "boot must publish B within " + STARTUP_SECONDS
            + "s (observed " + restart.seconds() + "s)"),
        () -> assertHits(restart, "shelfrestoredtoken", "shelf/restored.txt"));
    assertRootSyncsSettled("S7", restart);
    assertAll("S7a: a projection accepted after the sync and inside its root survives",
        () -> assertEquals(restart.hits().getProperty("@later-id"),
            restart.hits().getProperty("laterprojectiontoken")),
        () -> assertHits(restart, "projectedfiletoken", "projected/file.txt"));
    assertAll("S7b: a prefix delete accepted after a sync keeps its files deleted",
        () -> assertHits(restart, "clearedtoken"),
        () -> assertEquals("", restart.hits().getProperty("earlyprojectiontoken"),
            "the projection deleted by both broad deletes stays deleted"));
    assertEquals(parity(restarts.get(1)), parity(restart),
        "S7 parity, including the collection deleted after its sync: B equals the control");
  }

  // ---- parent side ----

  private record Restart(Path root, boolean published, boolean settled, boolean halted, long seconds,
      Properties hits, Properties returned) {}

  @SafeVarargs
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // unwraps the first failure of any
  private static <T> List<T> all(Callable<T>... calls) throws Exception {
    var futures = java.util.Arrays.stream(calls).map(call -> CompletableFuture.supplyAsync(() -> {
      try {
        return call.call();
      } catch (Exception failure) {
        throw new java.util.concurrent.CompletionException(failure);
      }
    })).toList();
    List<T> results = new ArrayList<>();
    for (var future : futures) {
      try {
        results.add(future.join());
      } catch (java.util.concurrent.CompletionException failure) {
        if (failure.getCause() instanceof Exception cause) throw cause;
        throw failure;
      }
    }
    return results;
  }

  /** The restarts {@link #crashesAndControl} makes, in the order it returns them. */
  private static final List<String> RESTARTS = List.of("crash", "control", "promoted");

  private interface OfflineChange {
    void apply(Path root) throws IOException;
  }

  /**
   * A NATIVE crash after the pointer commit, its control and a PROMOTED crash after the pointer
   * commit, each changed by {@code offline} while stopped and then restarted, at once. (This
   * harness's recorded owner retires nothing without a restart, so a PROMOTED control never
   * settles.)
   */
  private static List<Restart> crashesAndControl(Path temporary, String scenario, OfflineChange offline)
      throws Exception {
    List<Path> roots = new ArrayList<>();
    for (String name : RESTARTS) roots.add(Files.createDirectories(temporary.resolve(name)));
    all(() -> migrateAndCut(roots.get(0), "NATIVE", scenario, AFTER_POINTER),
        () -> migrateAndCut(roots.get(1), "NATIVE", scenario, UNINTERRUPTED),
        () -> migrateAndCut(roots.get(2), "PROMOTED", scenario, AFTER_POINTER));
    for (Path root : roots) offline.apply(root);
    return all(() -> restart(roots.get(0), "NATIVE", scenario, "restart"),
        () -> restart(roots.get(1), "NATIVE", scenario, "restart"),
        () -> restart(roots.get(2), "PROMOTED", scenario, "restart"));
  }

  private static Void migrateAndCut(Path root, String mode, String scenario, String cut) throws Exception {
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
    var retained = retained(root, committed.active_generation());
    System.out.println("CRASH-RECOVERY " + mode + " " + scenario + " retained after " + cut + ": " + retained);
    if (cut.equals(UNINTERRUPTED)) {
      assertNull(committed.previous_generation(), "the uninterrupted migration retired its predecessor");
      assertEquals(List.of(), retained, "the uninterrupted migration acknowledged its receipts");
    } else {
      assertNotNull(committed.previous_generation(), "the crash must precede predecessor retirement");
      assertTrue(retained.contains("SYNC_ROOT"), "the crash must retain the scoped receipt");
    }
    return null;
  }

  /** The forgotten and the excluded file's job rows, column by column; empty when none exists. */
  private static String keptOutJobs(Path root) throws java.sql.SQLException {
    var rows = new ArrayList<String>();
    try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + root.resolve("data/jobs.db"));
        var query = connection.prepareStatement("SELECT * FROM jobs WHERE path IN (?, ?) ORDER BY path")) {
      query.setString(1, key(root.resolve(FORGOTTEN)));
      query.setString(2, key(root.resolve(EXCLUDED)));
      try (var row = query.executeQuery()) {
        while (row.next()) {
          var columns = new ArrayList<String>();
          for (int i = 1; i <= row.getMetaData().getColumnCount(); i++) {
            columns.add(row.getMetaData().getColumnName(i) + "=" + row.getString(i));
          }
          rows.add(String.join(",", columns).replace(key(root), "<root>"));
        }
      }
    }
    return rows.toString();
  }

  private static List<String> retained(Path root, String generation) throws Exception {
    try (var queue = new SqliteJobQueue(root.resolve("data/jobs.db"))) {
      queue.open();
      return queue.listSwitchBufferOpsStrict().stream().filter(op -> generation.equals(op.generation()))
          .map(op -> op.op()).toList();
    }
  }

  /** The originator and transport the queue holds for {@code file}'s job, once the child stopped. */
  private static List<String> jobAttribution(Path root, String file) throws Exception {
    try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + root.resolve("data/jobs.db"));
        var query = connection.prepareStatement("SELECT originator, transport FROM jobs WHERE path = ?")) {
      query.setString(1, key(root.resolve(file)));
      try (var row = query.executeQuery()) {
        return row.next() ? java.util.Arrays.asList(row.getString("originator"), row.getString("transport"))
            : List.of();
      }
    }
  }

  /**
   * S3: in the watched root and in the configured collection root, one file added, one removed
   * and one changed while no Engine runs; each change is saved anew, so its size and mtime change.
   * S5: a file added to the root whose volume is away; a collection root deleted;
   * a watched and a collection root no longer declared. The forgotten and the excluded file are
   * saved again.
   */
  private static Void changeRootsWhileStopped(Path root) throws IOException {
    Files.writeString(root.resolve("watched/added.txt"), "addedtoken");
    Files.delete(root.resolve("watched/remove-me.txt"));
    Files.writeString(root.resolve("watched/change-me.txt"), "newchangetoken");
    Files.writeString(root.resolve("archive/added.txt"), "archiveaddedtoken");
    Files.delete(root.resolve("archive/remove-me.txt"));
    Path changed = root.resolve("archive/change-me.txt");
    var modified = Files.getLastModifiedTime(changed);
    Files.writeString(changed, "archivenewtoken saved");
    assertNotEquals(modified, Files.getLastModifiedTime(changed), "the edit must change the mtime");
    Files.writeString(root.resolve("away/while-away.txt"), "awaytoken");
    Files.writeString(root.resolve(FORGOTTEN), "forgottentoken saved again");
    Files.writeString(root.resolve(EXCLUDED), "excludedtoken saved again");
    deleteTree(root.resolve("shelf"));
    Files.writeString(root.resolve("undeclare"), "dropped loose");
    return null;
  }

  private static void deleteTree(Path folder) throws IOException {
    try (var tree = Files.walk(folder)) {
      for (Path path : tree.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }

  private static void unlock(Path folder) throws IOException {
    if (Files.exists(folder)) Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
  }

  private static Restart restart(Path root, String mode, String scenario, String phase) throws Exception {
    if (scenario.equals("roots")) Files.writeString(root.resolve("kept-out-jobs"), keptOutJobs(root));
    Process restarted = child(root, mode, scenario, phase);
    long started = System.nanoTime();
    boolean published;
    try {
      awaitMarker(restarted, root.resolve("published"), STARTUP_SECONDS);
      published = Files.exists(root.resolve("published"));
      // Keep a published child until it reports its outcome.
      if (published) awaitMarker(restarted, root.resolve("verified"), 120);
    } finally {
      kill(restarted);
    }
    long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
    for (String logged : List.of("migrate", phase)) {
      System.out.println("CRASH-RECOVERY " + mode + " " + scenario + " " + logged + " evidence:\n"
          + evidence(log(root, logged)));
    }
    System.out.println("CRASH-RECOVERY " + mode + " " + scenario + " " + phase + " observed for " + seconds
        + "s; published=" + published + " markers=" + List.of("published", "searched", "settled",
            "verified", "halted", "boot-failed").stream().filter(name -> Files.exists(root.resolve(name))).toList());
    var restart = new Restart(root, published, Files.exists(root.resolve("settled")),
        Files.exists(root.resolve("halted")), seconds,
        properties(root.resolve("hits.properties")), properties(root.resolve("returned.properties")));
    // A further restart of the same root writes its own markers.
    for (String marker : List.of("published", "searched", "settled", "verified", "halted", "boot-failed",
        "hits.properties", "returned.properties", "kept-out-jobs")) {
      Files.deleteIfExists(root.resolve(marker));
    }
    return restart;
  }

  private static void assertRootSyncsSettled(String scenario, Restart restart) {
    assertAll(scenario + " crash recovery",
        () -> assertTrue(restart.published(), "boot must publish B within " + STARTUP_SECONDS
            + "s (observed " + restart.seconds() + "s)"),
        () -> assertHits(restart, TOKEN, "watched/replayed.txt"),
        () -> assertTrue(restart.settled(), "no B-scoped receipt may remain"));
  }

  /** Exactly these files hold {@code token}: nothing lost, nothing duplicated. */
  private static void assertHits(Restart restart, String token, String... files) {
    List<String> expected = java.util.Arrays.stream(files).map(file -> key(restart.root().resolve(file))).toList();
    String found = restart.hits().getProperty(token);
    assertEquals(expected, found == null || found.isEmpty() ? List.of() : List.of(found.split("\n")),
        "search hits for " + token);
  }

  /** {@code properties} with paths relative to the restart's root, so two fixtures compare. */
  private static Map<String, String> relative(Restart restart, Properties properties) {
    var relative = new TreeMap<String, String>();
    for (String name : properties.stringPropertyNames()) relative.put(name, relative(restart, properties, name));
    return relative;
  }

  /**
   * The restart's search results and durable state. An ordinary restart registers a watched
   * root's watcher after publication, so whether it is registered yet is not compared.
   */
  private static Map<String, String> parity(Restart restart) {
    var parity = relative(restart, restart.hits());
    parity.remove("@watched-watched");
    // Job rows carry this run's revisions and times; each restart compares its own before and after.
    parity.remove("@kept-out-jobs-before");
    parity.remove("@kept-out-jobs");
    return parity;
  }

  private static String relative(Restart restart, Properties properties, String name) {
    String value = properties.getProperty(name);
    return value == null ? null : value.replace(key(restart.root()), "<root>");
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
    if (phase.equals("migrate")) seedRoots(root, scenario);
    if (scenario.equals("roots") && !phase.equals("migrate")) {
      NoVolumeIdentityExtension.installVolumeLookupWithFolderAway(root.resolve("away"), () -> true);
    }
    NoVolumeIdentityExtension.installVolumeLookupIfAbsent();
    // S5c: the roots the person no longer declares are absent from both declarations.
    String undeclared = Files.exists(root.resolve("undeclare")) ? Files.readString(root.resolve("undeclare")) : "";
    List<Path> watched = WATCHED.stream().filter(name -> !undeclared.contains(name)).map(root::resolve).toList();
    String collections = COLLECTIONS.stream().filter(name -> !undeclared.contains(name))
        .map(name -> "{\"name\":\"" + name + "\",\"roots\":[\""
            + root.resolve(name).toString().replace("\\", "\\\\") + "\"]}")
        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    Files.createDirectories(dataDir);
    var settings = new java.util.HashMap<>(Map.of(
        "justsearch.ai.disabled", "true",
        "justsearch.ai.embed.enabled", "false",
        "justsearch.splade.enabled", "false",
        "justsearch.ner.enabled", "false",
        "justsearch.extraction.sandbox.mode", "IN_PROCESS",
        "index.collections", collections));
    if (scenario.equals("roots")) {
      // Forget's exact-path entry for one file, and an ordinary pattern for another.
      settings.put("justsearch.ui.exclude_patterns", new tools.jackson.databind.ObjectMapper().writeValueAsString(
          List.of(Map.of("literal", Map.of("version", 1, "id", "0f3a5c1e-6b2d-4e8f-9a7c-1d2e3f405162",
              "path", root.resolve(FORGOTTEN).toAbsolutePath().normalize().toString(), "scope", "FILE")),
              Path.of(EXCLUDED).getFileName().toString())));
    }
    WorkerBootFixture.publishConfig(dataDir, indexBase, "BLUE_GREEN_MIGRATE", settings);
    Files.writeString(dataDir.resolve("watched_roots.json"), new tools.jackson.databind.ObjectMapper()
        .writeValueAsString(Map.of("schemaVersion", 1, "roots",
            watched.stream().map(path -> Map.of("path", path.toString())).toList())));
    var executors = phase.equals("migrate") ? new TestEngineExecutors()
        : compressedMaintenance(new TestEngineExecutors());
    // "roots" composes the server as the Engine does, with the native policy owner Forget shares.
    var configuration = io.justsearch.configuration.resolved.ConfigStore.global();
    var server = org.mockito.Mockito.spy(scenario.equals("roots")
        ? new KnowledgeServer(executors, WorkerBootFixture.workerConfig(dataDir), null,
            io.justsearch.app.api.runtime.child.DefaultChildControl.unrecorded(ManagedChildRegistry.noop()),
            lifecycle(root), null, null, null, null, configuration.publicationLock(),
            () -> new io.justsearch.core.component.DeviceMemoryLine(null, null), configuration)
        : new KnowledgeServer(executors,
            WorkerBootFixture.workerConfig(dataDir), null, ManagedChildRegistry.noop(), lifecycle(root)));
    org.mockito.Mockito.doNothing().when(server)
        .startDeferredModelInitialization(org.mockito.ArgumentMatchers.any());
    if (Files.exists(root.resolve("refuse-removals"))) {
      var refusing = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
      refusing.addAll(Files.readAllLines(root.resolve("refuse-removals")));
      // The queue is open and boot has not settled B's receipts yet.
      org.mockito.Mockito.doAnswer(reaper -> {
        refuseRemovalsOnce(root, (SqliteJobQueue) reaper.getArgument(0), refusing);
        return reaper.callRealMethod();
      }).when(server).startStuckJobReaper(org.mockito.ArgumentMatchers.any());
    }
    var bindings = watched.stream().map(path -> new RootBinding(path, null)).toList();
    if (scenario.equals("roots")) {
      server.bindNativePolicyRootBindings(new KnowledgeServer.NativePolicyRootBindings() {
        @Override public <T> T withBindings(java.util.function.Function<List<RootBinding>, T> action) {
          return action.apply(bindings);
        }
      });
    }
    server.bindBootRootBindings(bindings);
    server.bindExclusionRoots(() -> watched);
    if (scenario.equals("projection")) server.installProjectionSeedSources(List.of(new ProjectionSeedSource() {
      @Override public String sourceId() { return PROJECTION_SOURCE; }
      @Override public void enumerate(java.util.function.Consumer<AcceptedProjection> sink) {
        sink.accept(projection(root.resolve("removed-root"), "early", 1, "earlyprojectiontoken"));
      }
    }));
    if (phase.equals("migrate")) {
      migrate(root, mode, scenario, server);
    } else {
      restart(root, mode, scenario, phase, server, watched);
    }
  }

  /**
   * S4b and S4c: the first removal of each named receipt is ignored, as a row that vanished would
   * be, and recorded in {@code refused}. The refusal lives in this process, so rolling the removal
   * back does not arm it again.
   */
  private static void refuseRemovalsOnce(Path root, SqliteJobQueue queue, java.util.Set<String> keys)
      throws ReflectiveOperationException, java.sql.SQLException {
    var connectionField = SqliteJobQueue.class.getDeclaredField("connection");
    var lockField = SqliteJobQueue.class.getDeclaredField("lock");
    connectionField.setAccessible(true);
    lockField.setAccessible(true);
    var lock = (java.util.concurrent.locks.ReentrantLock) lockField.get(queue);
    lock.lock();
    try {
      var connection = (java.sql.Connection) connectionField.get(queue);
      org.sqlite.Function.create(connection, "refuse_removal", new org.sqlite.Function() {
        @Override protected void xFunc() throws java.sql.SQLException {
          String key = value_text(0);
          boolean refused = keys.remove(key);
          if (refused) {
            try {
              Files.writeString(root.resolve("refused"), key + "\n", java.nio.file.StandardOpenOption.CREATE,
                  java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException failure) {
              throw new java.sql.SQLException(failure);
            }
          }
          result(refused ? 1 : 0);
        }
      });
      try (var statement = connection.createStatement()) {
        statement.execute("CREATE TEMP TRIGGER refuse_removal BEFORE DELETE ON main.switch_buffer"
            + " WHEN refuse_removal(OLD.key) BEGIN SELECT RAISE(IGNORE); END");
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * The test registry whose server maintenance scheduler (stuck-job reaper and deferred-root check)
   * runs each fixed-delay task at a tenth of its delay, so a restart sees the deferred-root check
   * retry without waiting out its 10 s production cadence.
   */
  private static EngineExecutorRegistry compressedMaintenance(EngineExecutorRegistry executors) {
    return delegating(EngineExecutorRegistry.class, (method, args) -> {
      Object registration = method.invoke(executors, args);
      if (!method.getName().equals("register")
          || !WorkerExecutorRegistrations.STUCK_JOB_REAPER.equals(((EngineExecutorSpec) args[0]).name())) {
        return registration;
      }
      return delegating(EngineExecutorRegistry.Registration.class, (open, openArgs) -> {
        Object scheduler = open.invoke(registration, openArgs);
        if (!open.getName().equals("openScheduled")) return scheduler;
        return delegating(ScheduledExecutorService.class, (schedule, scheduleArgs) -> {
          if (schedule.getName().equals("scheduleWithFixedDelay")) {
            scheduleArgs[1] = (long) scheduleArgs[1] / 10;
            scheduleArgs[2] = (long) scheduleArgs[2] / 10;
          }
          return schedule.invoke(scheduler, scheduleArgs);
        });
      });
    });
  }

  private interface Call {
    Object invoke(java.lang.reflect.Method method, Object[] args) throws ReflectiveOperationException;
  }

  private static <T> T delegating(Class<T> type, Call call) {
    return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
        (proxy, method, args) -> {
          try {
            return call.invoke(method, args);
          } catch (java.lang.reflect.InvocationTargetException thrown) {
            throw thrown.getCause();
          }
        }));
  }

  /**
   * Every root folder exists before the migration. Files present here reach B through its build
   * enumeration; files {@link #journal} writes at SWITCHING reach B only through a receipt.
   */
  private static void seedRoots(Path root, String scenario) throws IOException {
    for (String folder : List.of("watched", "other", "away", "gone", "guarded", "dropped", "archive", "shelf",
        "loose", "projected", "cleared")) {
      Files.createDirectories(root.resolve(folder));
    }
    if (scenario.equals("withheld")) {
      Files.writeString(root.resolve("guarded/open.txt"), "guardedopentoken");
      Files.writeString(Files.createDirectories(root.resolve("guarded/locked")).resolve("secret.txt"),
          "guardedsecrettoken");
    }
    if (scenario.equals("mixed")) {
      write(root, "watched/keep.txt", "keeptoken");
      write(root, "watched/d-before.txt", "dbeforetoken");
      write(root, "watched/d-after.txt", "daftertoken");
      write(root, "other/control.txt", "controltoken");
      write(root, "other/o-delete.txt", "odeletetoken");
      write(root, "archive/a-restored.txt", "arestoredtoken");
    }
  }

  private static void migrate(Path root, String mode, String scenario, KnowledgeServer server) throws Exception {
    String cut = Files.readString(root.resolve("cut-point"));
    server.installMigrationBarrierForTests(transition -> {
      var queue = (SqliteJobQueue) server.jobQueueForTests();
      if (transition.point().equals("migration-switching-entered")) {
        journal(root, scenario, queue, transition.buildingGeneration());
      }
      if (transition.point().equals(cut)) {
        if (scenario.equals("replacement")) {
          // S4: a revision accepted after the cutover's snapshot replaces the UPSERT receipt it replayed.
          upsert(queue, Files.readString(root.resolve("building")), root,
              write(root, "shelf/late.txt", "latenewtoken"));
        }
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
      var request = MigrationStartRequest.newBuilder().setReason("manual").setProjectionSourceIdsPresent(true);
      if (scenario.equals("projection")) request.addProjectionSourceIds(PROJECTION_SOURCE);
      var started = server.appServices().ingestService().startMigration(request.build(), CallContext.none());
      assertTrue(started.getAccepted(), started::getError);
      building = started.getBuildingGenerationId();
      Files.writeString(root.resolve("building"), building);
      assertTrue(server.beginUnrecordedBuildingLiveAsync(building,
          () -> { throw new AssertionError("live native migration fell back"); }).get(30, TimeUnit.SECONDS));
    }
    System.out.println("CRASH-RECOVERY " + mode + " migration live building=" + building);
    if (cut.equals(UNINTERRUPTED)) {
      // The control: the migration completes, acknowledges B's receipts and retires A.
      var queue = (SqliteJobQueue) server.jobQueueForTests();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
      while (System.nanoTime() < deadline) {
        var state = manager.readStateBestEffort();
        if (building.equals(state.active_generation()) && state.previous_generation() == null
            && queue.listSwitchBufferOpsStrictForGeneration(building).isEmpty()) {
          System.out.println("CRASH-RECOVERY uninterrupted migration settled active=" + building);
          mark(root, "cut");
          break;
        }
        Thread.sleep(100);
      }
    }
    while (true) Thread.sleep(1_000);
  }

  /** B's receipts, accepted at SWITCHING in this order, as production journals them. */
  private static void journal(Path root, String scenario, SqliteJobQueue queue, String building)
      throws IOException, InterruptedException {
    write(root, "watched/replayed.txt", TOKEN);
    switch (scenario) {
      case "roots" -> {
        write(root, "watched/keep.txt", "keeptoken");
        write(root, "watched/change-me.txt", "oldchangetoken");
        write(root, "watched/remove-me.txt", "removedtoken");
        write(root, "other/control.txt", "controltoken");
        write(root, "archive/keep.txt", "archivekeeptoken");
        write(root, "archive/change-me.txt", "archiveoldtoken");
        write(root, "archive/remove-me.txt", "archiveremovedtoken");
        write(root, "away/keep.txt", "awaykeeptoken");
        write(root, "shelf/shelf.txt", "shelftoken");
        write(root, "dropped/dropped.txt", "droppedtoken");
        write(root, "loose/loose.txt", "loosetoken");
        // Only the watched root's sync walks these; no file receipt names them.
        write(root, FORGOTTEN, "forgottentoken");
        write(root, EXCLUDED, "excludedtoken");
        for (String synced : List.of("watched", "archive", "away", "shelf", "dropped", "loose")) {
          syncRoot(queue, building, root.resolve(synced), true, null);
        }
      }
      case "withheld" -> {
        // S8: B built the guarded subtree; it is unreadable when the sync walks it.
        awaitIndexed(queue, root.resolve("guarded"));
        Files.setPosixFilePermissions(root.resolve("guarded/locked"), PosixFilePermissions.fromString("---------"));
        // S5b: the gone root is deleted while stopped.
        write(root, "gone/gone.txt", "gonetoken");
        syncRoot(queue, building, root.resolve("watched"), true, null);
        // force=false: the build's own UPSERT receipts for the guarded files precede this sync.
        syncRoot(queue, building, root.resolve("guarded"), false, null);
        syncRoot(queue, building, root.resolve("gone"), true, null);
      }
      case "publication" -> {
        write(root, "archive/keep.txt", "archivekeeptoken");
        syncRoot(queue, building, root.resolve("watched"), true, null);
        syncRoot(queue, building, root.resolve("archive"), true, new JobQueue.EnqueueProvenance("agent", "MCP"));
      }
      case "replacement" -> {
        write(root, "archive/keep.txt", "archivekeeptoken");
        syncRoot(queue, building, root.resolve("watched"), true, null);
        // S4c: inside the collection root, the force=true sync after it supersedes this receipt.
        delete(queue, building, write(root, "archive/a-restored.txt", "arestoredtoken")); // stays on disk
        syncRoot(queue, building, root.resolve("archive"), true, null);
        // A collection root no sync covers: only this receipt brings late.txt into B.
        upsert(queue, building, root, write(root, "shelf/late.txt", "lateoldtoken"));
      }
      case "mixed" -> {
        upsert(queue, building, root, write(root, "watched/u-before.txt", "ubeforetoken"));
        upsert(queue, building, root, write(root, "other/o-upsert.txt", "oupserttoken"));
        delete(queue, building, root.resolve("watched/d-before.txt")); // stays on disk: the walk restores it
        // Inside the collection root, the force=true sync after it supersedes this receipt.
        delete(queue, building, root.resolve("archive/a-restored.txt")); // stays on disk
        syncRoot(queue, building, root.resolve("watched"), false, null);
        syncRoot(queue, building, root.resolve("archive"), true, null);
        upsert(queue, building, root, write(root, "watched/u-after.txt", "uaftertoken"));
        Files.delete(root.resolve("watched/d-after.txt"));
        delete(queue, building, root.resolve("watched/d-after.txt"));
        Files.delete(root.resolve("other/o-delete.txt"));
        delete(queue, building, root.resolve("other/o-delete.txt"));
      }
      case "projection" -> {
        // Both broad deletes cover "early", accepted before them; "later" is accepted after
        // them, inside the projected root synced before it.
        deletePrefix(queue, building, root.resolve("removed-root"));
        syncRoot(queue, building, root.resolve("watched"), true, null);
        write(root, "projected/file.txt", "projectedfiletoken");
        syncRoot(queue, building, root.resolve("projected"), true, null);
        assertTrue(queue.putSwitchBufferForGeneration(building, "collection:scratch", "DELETE_COLLECTION", "scratch"));
        write(root, "shelf/restored.txt", "shelfrestoredtoken");
        delete(queue, building, root.resolve("shelf/restored.txt")); // stays on disk: the walk restores it
        syncRoot(queue, building, root.resolve("shelf"), true, null);
        queue.admitProjectionForGeneration(building,
            projection(root.resolve("projected"), "later", 1, "laterprojectiontoken").encode());
        write(root, "cleared/cleared.txt", "clearedtoken");
        syncRoot(queue, building, root.resolve("cleared"), true, null);
        deletePrefix(queue, building, root.resolve("cleared"));
        write(root, "archive/keep.txt", "archivekeeptoken");
        syncRoot(queue, building, root.resolve("archive"), true, null);
        assertTrue(queue.putSwitchBufferForGeneration(building, "collection:archive", "DELETE_COLLECTION", "archive"));
      }
      default -> throw new IllegalArgumentException(scenario);
    }
    System.out.println("CRASH-RECOVERY journaled generation=" + building + " receipts="
        + queue.listSwitchBufferOpsStrict().stream().map(op -> op.op() + " " + op.key()).toList());
  }

  /** Green's loop has finished every job admitted under {@code folder}. */
  private static void awaitIndexed(SqliteJobQueue queue, Path folder) throws InterruptedException {
    String prefix = PathNormalizer.normalizePathPrefix(folder.toString());
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (queue.hasNonterminalJobsByPathPrefixStrict(prefix) && System.nanoTime() < deadline) Thread.sleep(50);
  }

  /** IngestSwitchBufferOps.bufferSyncDirectoryDuringSwitching. */
  private static void syncRoot(SqliteJobQueue queue, String building, Path root, boolean force,
      JobQueue.EnqueueProvenance provenance) {
    String normalized = PathNormalizer.normalizePathPrefix(root.toString());
    assertTrue(queue.putSyncRootForGeneration(building, "sync_root:" + normalized,
        new SwitchBufferSyncRoot(normalized, force, provenance)));
  }

  /** IngestSwitchBufferOps.bufferDeleteByPathDuringSwitching. */
  private static void deletePrefix(SqliteJobQueue queue, String building, Path folder) {
    String prefix = PathNormalizer.normalizePathPrefix(folder.toString());
    assertTrue(queue.putSwitchBufferForGeneration(building, "prefix:" + prefix, "DELETE_PREFIX",
        new io.justsearch.adapters.lucene.runtime.PathPrefixScope(prefix, List.of()).encode()));
  }

  /** A projection in the deletable "scratch" collection, under {@code folder}. */
  private static AcceptedProjection projection(Path folder, String id, long revision, String token) {
    String path = folder.resolve(id + ".md").toString();
    return new AcceptedProjection(PROJECTION_SOURCE, id, revision, AcceptedProjection.Kind.UPSERT,
        "{\"collection\":\"scratch\",\"content\":\"" + token + "\",\"path\":\""
            + path.replace("\\", "\\\\") + "\"}");
  }

  private static Path write(Path root, String file, String content) throws IOException {
    return Files.writeString(root.resolve(file), content);
  }

  /** WorkerIngestService's candidate admission, in the file's own root and collection. */
  private static void upsert(SqliteJobQueue queue, String building, Path root, Path file) {
    String folder = root.relativize(file).getName(0).toString();
    assertTrue(queue.enqueueAndBufferFileForGeneration(building,
        JobQueue.EnqueueEntry.stat(file).withinRoot(root.resolve(folder)),
        COLLECTIONS.contains(folder) ? folder : null));
  }

  /** IngestSwitchBufferOps.journalDeleteForGeneration. */
  private static void delete(SqliteJobQueue queue, String building, Path file) {
    String id = key(file);
    assertTrue(queue.putSwitchBufferForGeneration(building, "path:" + id, "DELETE",
        new SwitchBufferDelete(id, SwitchBufferDelete.Origin.EXPLICIT, null).encode()));
  }

  @SuppressWarnings("PMD.DoNotTerminateVM") // The isolated child ends without close or shutdown hooks.
  private static void restart(Path root, String mode, String scenario, String phase, KnowledgeServer server,
      List<Path> watched) throws Exception {
    long started = System.nanoTime();
    if (phase.equals("restart-halt")) {
      // S4a: the second abrupt stop, once boot settled B's receipts and before it publishes.
      org.mockito.Mockito.doAnswer(publication -> {
        System.out.println("CRASH-RECOVERY " + mode + " stop before publication");
        mark(root, "halted");
        Runtime.getRuntime().halt(0);
        return null;
      }).when(server).publishServingView(org.mockito.ArgumentMatchers.any());
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
    // What boot itself admitted, before the Head's walks below.
    String keptOutJobs = scenario.equals("roots") ? keptOutJobs(root) : null;
    mark(root, "published");
    // The Head after every restart: it watches each persisted root and walks it
    // (RootLifecycleOps.reindexPersistedRoots), and its periodic sync reconciles it (SyncOps).
    var ingest = server.appServices().ingestService();
    for (Path folder : watched) {
      ingest.watchRoot(io.justsearch.ipc.WatchRootRequest.newBuilder().setRootPath(folder.toString()).build(),
          CallContext.none());
      if (!Files.exists(folder)) continue;
      ingest.scanRoot(io.justsearch.ipc.ScanRootRequest.newBuilder().setRootPath(folder.toString())
          .setMode(io.justsearch.ipc.ScanMode.SCAN_MODE_INITIAL).build(), progress -> {}, CallContext.none());
      ingest.syncDirectory(io.justsearch.ipc.SyncDirectoryRequest.newBuilder().setRootPath(folder.toString())
          .setForce(false).build(), CallContext.none());
    }
    var queue = (SqliteJobQueue) server.jobQueueForTests();
    var hits = settledHits(server, queue, root);
    if (hits.getProperty(TOKEN).contains(key(root.resolve("watched/replayed.txt")))) mark(root, "searched");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    boolean retirement = Files.exists(root.resolve("await-retirement"));
    while ((!retained(queue, active).isEmpty()
        || retirement && manager.readStateBestEffort().previous_generation() != null)
        && System.nanoTime() < deadline) Thread.sleep(100);
    System.out.println("CRASH-RECOVERY " + mode + " remaining receipts=" + queue.listSwitchBufferOpsStrict().stream()
        .map(op -> op.op() + " " + op.generation() + " " + op.key()).toList());
    if (retained(queue, active).isEmpty()) mark(root, "settled");
    hits.setProperty("@receipts", retained(queue, active).toString());
    try (var view = server.captureServingView()) {
      hits.setProperty("@documents", Long.toString(view.searchRuntime().indexCountOps().docCount()));
    }
    if (retirement) {
      hits.setProperty("@previous-retired",
          Boolean.toString(manager.readStateBestEffort().previous_generation() == null));
    }
    hits.setProperty("@later-id", projection(root.resolve("projected"), "later", 1, "").indexId());
    hits.setProperty("@archive-watched", Boolean.toString(watching(server, root.resolve("archive"))));
    hits.setProperty("@watched-watched", Boolean.toString(watching(server, root.resolve("watched"))));
    if (keptOutJobs != null) {
      hits.setProperty("@kept-out-jobs", keptOutJobs);
      hits.setProperty("@kept-out-jobs-before", Files.readString(root.resolve("kept-out-jobs")));
    }
    store(root.resolve("hits.properties"), hits);
    if (scenario.equals("withheld")) {
      // S8: permissions return while the Engine runs. S5b: the root returns with its file and one
      // more. The Head's periodic sync then walks both (SyncOps).
      unlock(root.resolve("guarded/locked"));
      Files.createDirectories(root.resolve("gone"));
      write(root, "gone/gone.txt", "gonetoken");
      write(root, "gone/back.txt", "backtoken");
      for (String returned : List.of("guarded", "gone")) {
        ingest.syncDirectory(io.justsearch.ipc.SyncDirectoryRequest.newBuilder()
            .setRootPath(root.resolve(returned).toString()).setForce(false).build(), CallContext.none());
      }
      var returned = settledHits(server, queue, root);
      if (mode.equals("NATIVE")) {
        // A root the boot deferred is reconciled now, so the predecessor is retired as the control's was.
        long retiring = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (manager.readStateBestEffort().previous_generation() != null && System.nanoTime() < retiring) {
          Thread.sleep(100);
        }
        returned.setProperty("@previous-retired",
            Boolean.toString(manager.readStateBestEffort().previous_generation() == null));
      }
      store(root.resolve("returned.properties"), returned);
    }
    mark(root, "verified");
    Runtime.getRuntime().halt(0);
  }

  /** Every token's hits once no job under the roots is pending and three looks agree. */
  private static Properties settledHits(KnowledgeServer server, SqliteJobQueue queue, Path root)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    Properties last = null;
    int agreeing = 0;
    while (System.nanoTime() < deadline && agreeing < 3) {
      var hits = hits(server);
      boolean idle = !queue.hasNonterminalJobsByPathPrefixStrict(PathNormalizer.normalizePathPrefix(root.toString()));
      agreeing = idle && hits.equals(last) ? agreeing + 1 : 0;
      last = hits;
      Thread.sleep(250);
    }
    return last;
  }

  private static List<String> retained(SqliteJobQueue queue, String generation) {
    return queue.listSwitchBufferOpsStrictForGeneration(generation).stream()
        .map(op -> op.op() + " " + op.key().substring(op.key().indexOf(':') + 1)).toList();
  }

  /** Whether the live Worker holds a watch registration for {@code folder}. */
  private static boolean watching(KnowledgeServer server, Path folder) throws ReflectiveOperationException {
    var field = io.justsearch.indexerworker.services.WorkerIngestService.class
        .getDeclaredField("rootWatcherRegistry");
    field.setAccessible(true);
    Object registry = field.get(server.appServices().ingestService());
    var declared = registry.getClass().getDeclaredMethod("declaredRoots");
    declared.setAccessible(true);
    return ((java.util.Set<?>) declared.invoke(registry)).contains(folder.toAbsolutePath().normalize());
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
