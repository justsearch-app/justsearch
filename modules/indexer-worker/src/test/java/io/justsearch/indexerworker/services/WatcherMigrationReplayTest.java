/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.WatcherRootReconciliation;
import io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** Strict production replay with real SQLite receipts, held filesystem witnesses and Lucene. */
@org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
final class WatcherMigrationReplayTest extends LuceneExecutorTestBase {
  @TempDir Path temporary;

  @Test
  void reconciliationReceiptIsAppliedInsteadOfRejectedAsUnsupported() throws Exception {
    try (var f = new Fixture()) {
      Path kept = f.file("kept.txt", "kept");
      f.index(kept, "notes");
      f.debt();
      var prepared = f.prepare();
      assertTrue(prepared.isPresent(), "fresh watcher reconciliation must permit replay");
      var replay = prepared.orElseThrow();
      assertEquals("kept", f.content(kept));
      assertEquals("notes", f.collection(kept));
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(f.queue, replay));
      assertTrue(f.queue.listSwitchBufferOpsStrict().isEmpty());
    }
  }

  @Test
  void missingEarlierUpsertWaitsForCommittedPerPathReconciliation() throws Exception {
    try (var f = new Fixture()) {
      Path gone = f.file("gone.txt", "gone");
      f.admit(gone, "notes");
      f.index(gone, "notes");
      f.debt();
      Files.delete(gone);
      f.runtime.indexingCoordinator().deleteByIdAndChunks(gone.toString());
      f.runtime.commitOps().maybeRefreshBlocking();
      assertTrue(f.prepare().isPresent(), "fresh committed absence settles the earlier admission");
      assertNull(f.content(gone));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"recreated", "modified", "same-stamp"})
  void debtAloneConvergesCurrentBytesWithoutACompanionDelete(String change) throws Exception {
    try (var f = new Fixture()) {
      Path file = f.file("changed.txt", "old bytes");
      f.admit(file, "notes");
      f.index(file, "notes");
      f.debt();
      FileTime modified = Files.getLastModifiedTime(file);
      if (change.equals("recreated")) Files.delete(file);
      Files.writeString(file, "new bytes");
      if (change.equals("same-stamp")) Files.setLastModifiedTime(file, modified);
      assertTrue(f.prepare().isPresent());
      assertEquals("new bytes", f.content(file));
      assertEquals(SourceContentHash.sha256(file), f.runtime.documentFieldOps()
          .getDocumentFieldOrThrow(file.toString(), SchemaFields.SOURCE_SHA256));
      assertEquals("notes", f.collection(file));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"offline", "unreadable", "incomplete", "volume", "identity", "generation", "foreign-generation", "key", "membership", "collection"})
  void uncertainEvidenceKeepsEveryReceiptAndCannotDelete(String invalid) throws Exception {
    try (var f = new Fixture()) {
      Path gone = f.file("gone.txt", "retained");
      f.index(gone, "notes");
      var debt = f.debtValue();
      if (invalid.equals("identity")) debt = new WatcherRootReconciliation(debt.root(), "another root", "notes");
      String key = invalid.equals("key") ? "watcher-reconcile:other" : debt.key();
      assertTrue(invalid.equals("generation") || invalid.equals("foreign-generation")
          ? f.queue.putSwitchBufferForGeneration(invalid.equals("generation") ? "green" : "another", key,
              WatcherRootReconciliation.OPERATION, debt.encode())
          : f.queue.putSwitchBuffer(key, WatcherRootReconciliation.OPERATION, debt.encode()));
      var before = f.queue.listSwitchBufferOpsStrict();
      Files.delete(gone);
      Path displaced = temporary.resolve("displaced");
      if (invalid.equals("offline")) Files.move(f.root, displaced);
      if (invalid.equals("incomplete")) Files.createSymbolicLink(f.root.resolve("unresolved"), temporary.resolve("absent"));
      if (invalid.equals("unreadable")) Files.setPosixFilePermissions(f.root, java.util.Set.of());
      if (invalid.equals("volume")) RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.set(true);
      if (invalid.equals("membership")) f.roots = List.of();
      if (invalid.equals("collection")) f.roots = List.of(new ResolvedConfig.FileSource(f.root, "another-owner"));
      try {
        assertTrue(f.prepare().isEmpty());
        assertEquals(before, f.queue.listSwitchBufferOpsStrict());
        assertEquals("retained", f.content(gone));
      } finally {
        RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.set(false);
        if (invalid.equals("unreadable")) Files.setPosixFilePermissions(f.root,
            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"unchanged", "same-stamp"})
  void implicitDefaultDeclarationKeepsTheLiveWatchersOwnershipAndCurrentContent(String change)
      throws Exception {
    try (var f = new Fixture()) {
      Path file = f.file("default.txt", "old bytes");
      f.admit(file, null);
      f.index(file, null);
      assertNull(f.collection(file));
      f.roots = List.of(new ResolvedConfig.FileSource(f.root, null));
      var registry = new RootWatcherRegistry();
      assertTrue(registry.watch(f.root.toString(), "default").watching());
      f.usePublisher(registry);
      var debt = new WatcherRootReconciliation(
          f.root.toString(), RootIdentity.capture(f.root).toString(), "default");
      assertTrue(f.queue.putSwitchBuffer(debt.key(), WatcherRootReconciliation.OPERATION, debt.encode()));
      if (change.equals("same-stamp")) {
        var stamp = Files.getLastModifiedTime(file);
        Files.writeString(file, "new bytes");
        Files.setLastModifiedTime(file, stamp);
      }
      var replay = f.prepare();
      assertTrue(replay.isPresent(), "the persisted default and live default name own the same root");
      assertEquals(change.equals("same-stamp") ? "new bytes" : "old bytes", f.content(file));
      assertNull(f.collection(file), "the index default has no stored collection term");
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(f.queue, replay.orElseThrow()));
    }
  }

  @Test
  void exactRevisionRefusalIsVisibleAndKeepsTheReadableProjection() throws Exception {
    try (var f = new Fixture()) {
      Path kept = f.file("kept.txt", "readable while fenced");
      f.admit(kept, "notes");
      var receipts = f.queue.listSwitchBufferOpsStrict();
      String accepted = f.revision(kept);
      assertEquals(1, f.queue.enqueueEntriesWithExactCollection(
          List.of(JobQueue.EnqueueEntry.stat(kept)), "notes"));
      assertNotEquals(accepted, f.revision(kept));
      f.usePublisher(new RootWatcherRegistry());
      assertTrue(f.prepare().isEmpty(), "a replaced queue revision cannot satisfy the old receipt");
      assertEquals(receipts, f.queue.listSwitchBufferOpsStrict());
      assertEquals("readable while fenced", f.content(kept));
      String reason = f.publisher.indexStatus(io.justsearch.ipc.StatusRequest.getDefaultInstance(),
          CallContext.none()).getMigration().getPauseReason();
      assertTrue(reason.contains("Cutover fenced:") && reason.contains(kept.toString()), reason);
    }
  }

  @Test
  void laterDefaultAdmissionKeepsItsExactRevisionAcrossReconciliation() throws Exception {
    try (var f = new Fixture()) {
      Path kept = f.file("kept.txt", "kept after restart");
      f.roots = List.of(new ResolvedConfig.FileSource(f.root, null));
      var debt = new WatcherRootReconciliation(
          f.root.toString(), RootIdentity.capture(f.root).toString(), "default");
      assertTrue(f.queue.putSwitchBuffer(debt.key(), WatcherRootReconciliation.OPERATION, debt.encode()));
      f.admit(kept, null); // Resumed enumeration follows the retained root receipt.
      var receipt = f.queue.listSwitchBufferOpsStrict().stream()
          .filter(op -> "UPSERT".equals(op.op())).findFirst().orElseThrow();
      var admission = io.justsearch.indexerworker.queue.SwitchBufferUpsert.decode(receipt.payload());
      String before = f.revision(kept);
      var prepared = f.prepare();
      String after = f.revision(kept);
      System.out.println("kept.txt reconciliation receipt=" + admission.unitRevision()
          + " queue-before=" + before + " queue-after=" + after);
      assertEquals(admission.unitRevision(), before);
      assertEquals(before, after, "reconciliation must preserve a later accepted revision");
      assertTrue(prepared.isPresent());
      assertNull(f.collection(kept));
      assertTrue(f.queue.matchesAcceptedFileProjection(kept.toString(), admission.unitRevision(),
          admission.sourceSha256(), SourceContentHash.sha256(kept)));
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(f.queue, prepared.orElseThrow()));
    }
  }

  @Test
  void returnedWatcherStartsBeforeItsFreshFilesystemSnapshot() throws Exception {
    try (var f = new Fixture()) {
      var registry = org.mockito.Mockito.spy(new RootWatcherRegistry());
      assertTrue(registry.watch(f.root.toString(), "notes").watching());
      Path born = f.root.resolve("born-while-restoring.txt");
      org.mockito.Mockito.doAnswer(call -> {
        Files.writeString(born, "created as observation resumes");
        return call.callRealMethod();
      }).when(registry).registeredIdentity(f.root);
      f.usePublisher(registry);
      f.debt();
      assertTrue(f.prepare().isPresent());
      assertEquals("created as observation resumes", f.content(born));
      assertEquals("notes", f.collection(born));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"unavailable", "removed-readded", "reassigned"})
  void frozenNestedOwnershipNeverDeletesOrRelabelsTheChild(String change) throws Exception {
    try (var f = new Fixture()) {
      Path child = Files.createDirectory(f.root.resolve("child"));
      Path nested = Files.writeString(child.resolve("nested.txt"), "child content");
      f.index(nested, "child");
      f.roots = List.of(new ResolvedConfig.FileSource(f.root, "notes"),
          new ResolvedConfig.FileSource(child, "child"));
      var liveRegistry = new RootWatcherRegistry();
      assertTrue(liveRegistry.watch(f.root.toString(), "notes").watching());
      assertTrue(liveRegistry.watch(child.toString(), "child").watching());
      f.usePublisher(liveRegistry);
      f.debt();
      if (change.equals("unavailable")) Files.move(child, temporary.resolve("offline-child"));
      else {
        Files.delete(nested);
        if (change.equals("removed-readded")) {
          assertTrue(liveRegistry.unwatch(child.toString()));
          Files.delete(child);
          Files.createDirectory(child);
          assertTrue(liveRegistry.watch(child.toString(), "child").watching());
        }
        Files.writeString(nested, "changed child content");
        if (change.equals("reassigned")) {
          assertTrue(liveRegistry.watch(child.toString(), "other-owner").watching());
          assertEquals("other-owner", liveRegistry.subscription(child).collection());
        }
      }
      Files.writeString(temporary.resolve("watched_roots.json"), new ObjectMapper().writeValueAsString(
          Map.of("schemaVersion", 1, "roots", List.of(
              Map.of("path", f.root.toString(), "collection", "notes"),
              Map.of("path", child.toString(), "collection", change.equals("reassigned") ? "other-owner" : "child")))));
      assertTrue(f.prepare().isPresent());
      assertEquals("child content", f.content(nested));
      assertEquals("child", f.collection(nested));
    }
  }

  @Test
  void replacementDebtSurvivesAcknowledgementOfTheOlderExactVersion() throws Exception {
    try (var f = new Fixture()) {
      f.debt();
      var result = f.prepare();
      assertTrue(result.isPresent());
      var prepared = result.orElseThrow();
      f.debt();
      var newer = f.queue.listSwitchBufferOpsStrict();
      assertFalse(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(f.queue, prepared));
      assertEquals(newer, f.queue.listSwitchBufferOpsStrict());
      assertTrue(f.prepare().isPresent());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"prefix-before", "collection-before", "prefix-after", "collection-after"})
  void broadDeletesAndRootOutcomesKeepTheirAcceptedOrder(String order) throws Exception {
    try (var f = new Fixture()) {
      Path file = f.file("ordered.txt", "current bytes");
      f.admit(file, "notes");
      f.index(file, "notes");
      boolean prefix = order.startsWith("prefix");
      String payload = prefix ? io.justsearch.adapters.lucene.runtime.QueryFilterBuilder
          .normalizePathPrefix(f.root.toString()) : "notes";
      if (order.endsWith("after")) f.debt();
      assertTrue(f.queue.putSwitchBufferForGeneration(f.generation,
          (prefix ? "prefix:" : "collection:") + payload,
          prefix ? "DELETE_PREFIX" : "DELETE_COLLECTION", payload));
      if (order.endsWith("before")) f.debt();
      var replay = f.prepare();
      assertTrue(replay.isPresent(), "each root outcome must respect the broad receipt's position");
      assertEquals(order.endsWith("before") ? "current bytes" : null, f.content(file));
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(f.queue, replay.orElseThrow()));
      assertTrue(f.queue.listSwitchBufferOpsStrict().isEmpty());
    }
  }

  @Test
  void committedPointerCannotBlindlyAcknowledgeRetainedRootDebt() throws Exception {
    try (var f = new Fixture()) {
      Path file = f.file("returned.txt", "current bytes");
      f.index(file, "notes");
      f.debt();
      var retained = f.queue.listSwitchBufferOpsStrict();
      assertFalse(KnowledgeServerMigrationOps.finishCommittedBootSwitchReplay(f.queue, f.generation));
      assertEquals(retained, f.queue.listSwitchBufferOpsStrict());
      assertTrue(KnowledgeServerMigrationOps.drainSwitchBufferStrict(f.context()));
      assertTrue(f.queue.listSwitchBufferOpsStrict().isEmpty());
      assertEquals("current bytes", f.content(file));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void onlyRootDebtMayRecoverAfterTheSwitchingBudgetExpires(boolean rootDebt) throws Exception {
    Path root = Files.createDirectory(temporary.resolve("deadline-root"));
    var manager = new IndexGenerationManager(temporary.resolve("deadline-generations"));
    String blue = manager.initializeOrLoad().state().active_generation();
    String green = manager.startMigration("watcher-deadline").building_generation();
    manager.updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);
    try (var f = new Fixture(root, temporary.resolve("deadline.db"),
        manager.resolveGenerationPathStrict(green), green)) {
      if (rootDebt) f.debt();
      var replayContext = f.context();
      Thread.sleep(10); // Exceed this invocation's real, one-millisecond cutover budget.
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      var attempted = new AtomicBoolean();
      KnowledgeServerMigrationOps.runMigrationCutoverLoop(
          new KnowledgeServerMigrationOps.CutoverContext(manager, f.queue,
              () -> System.nanoTime() < until, () -> true, () -> null, 0, 1, 0,
              () -> f.runtime, () -> true, () -> {},
              LoggerFactory.getLogger(WatcherMigrationReplayTest.class), ignored -> {}, () -> {
                attempted.set(true);
                var replay = KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(replayContext);
                assertTrue(replay.isPresent(), "returned root evidence must settle before promotion");
                f.runtime.commitOps().commitWithBuildState(
                    io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.BuildState.COMPLETE,
                    io.justsearch.adapters.lucene.runtime.CommitReason.MIGRATION_CUTOVER);
                IndexGenerationManager.State promoted;
                try (var pointer = manager.beginNativePromotion(blue, green)) {
                  promoted = pointer.promote();
                }
                assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(
                    f.queue, replay.orElseThrow()));
                return promoted;
              }));
      var state = manager.readStateBestEffort();
      assertEquals(rootDebt, attempted.get());
      assertEquals(rootDebt ? "IDLE" : "FAILED", state.migration_state());
      assertEquals(rootDebt ? green : blue, state.active_generation());
      assertTrue(f.queue.listSwitchBufferOpsStrict().isEmpty());
    }
  }

  @Test
  void aRetryReprobesAReappearedSourceBeforeDestructiveReplay() throws Exception {
    try (var f = new Fixture()) {
      Path file = f.file("return.txt", "old");
      f.admit(file, "notes");
      f.index(file, "notes");
      f.debt();
      Files.delete(file);
      assertTrue(f.prepare().isPresent()); // target committed; pointer/ack not performed
      assertNull(f.content(file));
      Files.writeString(file, "returned before retry");
      assertTrue(f.prepare().isPresent());
      assertEquals("returned before retry", f.content(file));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"before-target-commit", "after-target-commit", "after-pointer"})
  void processDeathRetainsDebtAndReprobesBeforeDestructiveRetry(String cut) throws Exception {
    Path root = Files.createDirectory(temporary.resolve("root"));
    Path base = temporary.resolve("generations");
    var manager = new IndexGenerationManager(base);
    String blue = manager.initializeOrLoad().state().active_generation();
    String green = manager.startMigration("watcher-crash").building_generation();
    manager.updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);
    Path target = manager.resolveGenerationPathStrict(green);
    Path database = temporary.resolve("crash.db");
    Path source = root.resolve("crash.txt");
    try (var f = new Fixture(root, database, target, green)) {
      Files.writeString(source, "original bytes");
      f.admit(source, "notes");
      f.index(source, "notes");
      f.debt();
      Files.delete(source);
    }
    Path arguments = temporary.resolve("crash.args");
    Files.writeString(arguments, "-cp\n\"" + System.getProperty("java.class.path")
        .replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n");
    Path output = temporary.resolve("crash-" + cut + ".log");
    Path home = Files.createDirectory(temporary.resolve("child-home"));
    var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "--enable-native-access=ALL-UNNAMED", "-Duser.home=" + home, "@" + arguments,
        CrashWorker.class.getName(), cut, root.toString(), database.toString(), base.toString(),
        target.toString(), blue, green).redirectErrorStream(true).redirectOutput(output.toFile());
    process.environment().put("HOME", home.toString());
    process.environment().put("USERPROFILE", home.toString());
    Process child = process.start();
    try {
      assertTrue(child.waitFor(40, TimeUnit.SECONDS), "the owned crash worker must finish");
      assertEquals(71, child.exitValue(), () -> {
        try { return Files.readString(output); }
        catch (Exception unavailable) { return unavailable.toString(); }
      });
    } finally {
      if (child.isAlive()) { child.destroyForcibly(); assertTrue(child.waitFor(10, TimeUnit.SECONDS)); }
    }
    Files.writeString(source, "returned after process death");
    try (var f = new Fixture(root, database, target, green)) {
      assertFalse(f.queue.listSwitchBufferOpsStrict().isEmpty(), "every cut precedes exact acknowledgement");
      if (cut.equals("after-pointer")) {
        assertEquals(green, manager.readStateBestEffort().active_generation());
        assertNull(f.content(source));
        assertTrue(KnowledgeServerMigrationOps.drainSwitchBufferStrict(f.context()),
            "committed native boot must freshly reconcile retained root debt");
        assertEquals("returned after process death", f.content(source));
        assertEquals("notes", f.collection(source));
      } else {
        assertEquals(blue, manager.readStateBestEffort().active_generation());
        var prepared = f.prepare();
        assertTrue(prepared.isPresent(), "fresh watcher reconciliation must permit replay");
        var replay = prepared.orElseThrow();
        assertEquals("returned after process death", f.content(source));
        assertEquals("notes", f.collection(source));
        f.runtime.commitOps().commitWithBuildState(
            io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.BuildState.COMPLETE,
            io.justsearch.adapters.lucene.runtime.CommitReason.MIGRATION_CUTOVER);
        try (var promotion = manager.beginNativePromotion(blue, green)) { promotion.promote(); }
        assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(f.queue, replay));
      }
      assertTrue(f.queue.listSwitchBufferOpsStrict().isEmpty());
    }
  }

  private static FieldCatalogDef catalog() {
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(4).fields());
    fields.add(new FieldCatalogDef.FieldDef(SchemaFields.SOURCE_SHA256, "keyword", true,
        false, List.of(), null, null, false));
    return new FieldCatalogDef("watcher-replay", fields);
  }

  public static final class CrashWorker {
    public static void main(String[] args) throws Exception {
      String cut = args[0];
      Path root = Path.of(args[1]), database = Path.of(args[2]), base = Path.of(args[3]);
      Path target = Path.of(args[4]);
      try (var executors = new TestEngineExecutors();
          var lucene = new LuceneExecutorRegistrations(executors);
          var queue = new SqliteJobQueue(database);
          var real = IndexSchema.fromCatalog(catalog(), () -> Map.of(), ignored -> {}).atPath(target)
              .withExecutorRegistrations(lucene).open()) {
        queue.open();
        real.commitOps().stopCommitTimer();
        var runtime = org.mockito.Mockito.spy(real);
        var commits = org.mockito.Mockito.spy(real.commitOps());
        org.mockito.Mockito.doReturn(commits).when(runtime).commitOps();
        org.mockito.Mockito.doAnswer(call -> {
          if (cut.equals("before-target-commit")) Runtime.getRuntime().halt(71);
          call.callRealMethod();
          if (cut.equals("after-target-commit")) Runtime.getRuntime().halt(71);
          return null;
        }).when(commits).commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.SWITCH_BUFFER_REPLAY);
        var context = new KnowledgeServerMigrationOps.DrainSwitchBufferContext(queue, runtime, null,
            IndexingPacing.unthrottled(), base, target, new ObjectMapper(), () -> false,
            () -> true, LoggerFactory.getLogger(CrashWorker.class), Long.MAX_VALUE, args[6], ignored -> true);
        try {
          context = (KnowledgeServerMigrationOps.DrainSwitchBufferContext) context.getClass()
              .getMethod("withMigrationRoots", List.class).invoke(context,
                  List.of(new ResolvedConfig.FileSource(root, "notes")));
        } catch (NoSuchMethodException baseline) { /* reach the baseline's real unsupported replay */ }
        var replay = KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(context);
        if (replay.isPresent() && cut.equals("after-pointer")) {
          real.commitOps().commitWithBuildState(
              io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.BuildState.COMPLETE,
              io.justsearch.adapters.lucene.runtime.CommitReason.MIGRATION_CUTOVER);
          try (var promotion = new IndexGenerationManager(base).beginNativePromotion(args[5], args[6])) {
            promotion.promote();
          }
          Runtime.getRuntime().halt(71);
        }
      }
    }
  }

  private final class Fixture implements AutoCloseable {
    final Path root;
    final SqliteJobQueue queue;
    final String generation;
    WorkerIngestService publisher;
    final RunningRuntime runtime;
    List<ResolvedConfig.FileSource> roots;
    final AtomicBoolean writing = new AtomicBoolean(true);
    final java.util.concurrent.ExecutorService writer = Executors.newSingleThreadExecutor();
    final java.util.concurrent.Future<?> writeResult;

    Fixture() throws Exception {
      this(Files.createDirectory(temporary.resolve("root")), temporary.resolve("queue.db"),
          temporary.resolve("target"), "green");
    }
    Fixture(Path root, Path database, Path targetPath, String generation) throws Exception {
      this.root = root;
      this.generation = generation;
      roots = List.of(new ResolvedConfig.FileSource(root, "notes"));
      queue = new SqliteJobQueue(database);
      queue.open();
      runtime = withTestExecutors(IndexSchema.fromCatalog(catalog(), () -> Map.of(), ignored -> {}).atPath(targetPath)).open();
      runtime.commitOps().stopCommitTimer();
      writeResult = writer.submit(() -> {
        while (writing.get()) {
          for (var job : queue.pollPending(8)) {
            try {
              assertTrue(Files.isRegularFile(job.path()), "the fixture writer requires a present source");
              index(job.path(), job.collection());
              runtime.commitOps().commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.DRAIN);
              assertTrue(queue.markClaimDone(job, io.justsearch.indexerworker.ingest.IngestionOutcome.of(
                  io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_FULL, "INDEXED",
                  io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE), null));
            } catch (Exception failure) { throw new IllegalStateException(failure); }
          }
          try { Thread.sleep(5); }
          catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        }
      });
    }

    Path file(String name, String content) throws Exception { return Files.writeString(root.resolve(name), content); }
    void usePublisher(RootWatcherRegistry registry) {
      publisher = new WorkerIngestService(queue, null,
          org.mockito.Mockito.mock(io.justsearch.indexerworker.coordination.WorkerSignalBus.class), IndexingPacing.unthrottled(),
          temporary, temporary, runtime, runtime, null, 0L);
      publisher.setRootWatcherRegistry(registry);
      publisher.setDeclaredRootsDirectory(temporary);
    }
    void admit(Path path, String collection) throws Exception {
      assertTrue(queue.enqueueAndBufferFileForGeneration(generation, JobQueue.EnqueueEntry.stat(path), collection));
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (queue.jobStateCountsStrict().pendingCount() + queue.jobStateCountsStrict().processingCount() > 0
          && System.nanoTime() < until) Thread.sleep(5);
    }
    WatcherRootReconciliation debtValue() throws Exception {
      return new WatcherRootReconciliation(root.toString(), RootIdentity.capture(root).toString(), "notes");
    }
    void debt() throws Exception {
      var debt = debtValue();
      assertTrue(queue.putSwitchBuffer(debt.key(), WatcherRootReconciliation.OPERATION, debt.encode()));
    }
    void index(Path file, String collection) throws Exception {
      var fields = new java.util.HashMap<String, Object>(Map.of(
          SchemaFields.DOC_ID, file.toString(), SchemaFields.DOC_UID, file.toString(),
          SchemaFields.PATH, file.toString(), SchemaFields.FILENAME, file.getFileName().toString(),
          SchemaFields.CONTENT, Files.readString(file),
          SchemaFields.SOURCE_SHA256, SourceContentHash.sha256(file), SchemaFields.IS_CHUNK, "false"));
      if (collection != null && !collection.isBlank()) fields.put(SchemaFields.COLLECTION, collection);
      runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
      runtime.commitOps().maybeRefreshBlocking();
    }
    String content(Path path) { return runtime.documentFieldOps().getDocumentField(path.toString(), SchemaFields.CONTENT); }
    String collection(Path path) { return runtime.documentFieldOps().getDocumentField(path.toString(), SchemaFields.COLLECTION); }
    String revision(Path path) throws Exception {
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + temporary.resolve("queue.db"));
           var query = connection.prepareStatement("SELECT unit_revision FROM jobs WHERE path = ?")) {
        query.setString(1, path.toString());
        try (var row = query.executeQuery()) { assertTrue(row.next()); return row.getString(1); }
      }
    }
    java.util.Optional<KnowledgeServerMigrationOps.StrictReplay> prepare() throws Exception {
      long drained = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (queue.jobStateCountsStrict().pendingCount() + queue.jobStateCountsStrict().processingCount() > 0
          && System.nanoTime() < drained) Thread.sleep(5);
      return KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(context());
    }
    KnowledgeServerMigrationOps.DrainSwitchBufferContext context() throws Exception {
      var context = new KnowledgeServerMigrationOps.DrainSwitchBufferContext(queue, runtime, null,
          IndexingPacing.unthrottled(), temporary, temporary, new ObjectMapper(), () -> false,
          () -> true, LoggerFactory.getLogger(WatcherMigrationReplayTest.class),
          System.nanoTime() + TimeUnit.SECONDS.toNanos(5), generation, ignored -> true)
          .withPublisher(publisher);
      // The baseline must reach its real unsupported-operation / UPSERT verifier, not fail compilation.
      try {
        context = (KnowledgeServerMigrationOps.DrainSwitchBufferContext) context.getClass()
            .getMethod("withMigrationRoots", List.class).invoke(context, roots);
      } catch (NoSuchMethodException baseline) { /* c29fcddc1e has no frozen replay snapshot seam. */ }
      return context;
    }
    @Override public void close() throws Exception {
      writing.set(false);
      writer.shutdown();
      assertTrue(writer.awaitTermination(10, TimeUnit.SECONDS));
      try { writeResult.get(); }
      finally { runtime.close(); queue.close(); }
    }
  }
}
