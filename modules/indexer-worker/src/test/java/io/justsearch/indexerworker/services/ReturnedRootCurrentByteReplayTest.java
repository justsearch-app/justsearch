/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.ContentExtractorProvider;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.IndexingLoopOptions;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.WatcherRootReconciliation;
import io.justsearch.indexerworker.server.WorkerExecutorRegistrations;
import io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** Real claim, planned-hash rejection, extraction, file fence, writer, commit and replay. */
@org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
final class ReturnedRootCurrentByteReplayTest extends LuceneExecutorTestBase {
  @TempDir Path temporary;

  @ParameterizedTest(name = "unavailablePredecessorConvergesThroughTheProductionExtractor({arguments})")
  @ValueSource(strings = {"modified", "same-stamp", "pristine"})
  void unavailablePredecessorConvergesThroughTheProductionExtractor(String change) throws Exception {
    try (var f = new Fixture()) {
      Path file = f.admit("old bytes");
      var stamp = Files.getLastModifiedTime(file);
      var receipts = f.queue.listSwitchBufferOpsStrict();
      if (change.equals("pristine")) {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.database);
             var reset = connection.createStatement()) {
          reset.executeUpdate("UPDATE jobs SET attempts=0,retry_after=NULL,last_reason_code=NULL,last_retry_policy=NULL");
        }
      }
      Files.writeString(file, "new bytes");
      if (change.equals("same-stamp")) Files.setLastModifiedTime(file, stamp);
      f.loop.start();
      var replay = f.prepare();
      assertTrue(replay.isPresent(), "fresh reconciliation must settle H1 admission against current H2");
      assertEquals(receipts, f.queue.listSwitchBufferOpsStrict(), "preparation preserves accepted receipt positions and contents");
      assertEquals(0, f.queue.jobStateCountsStrict().pendingCount());
      assertEquals(SourceContentHash.sha256(file), f.hash(file));
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(f.queue, replay.orElseThrow()));
      assertTrue(f.queue.listSwitchBufferOpsStrict().isEmpty());
    }
  }

  @Test
  void provenAbsentUnavailablePredecessorWakesWithoutARegularSourceHash() throws Exception {
    try (var f = new Fixture()) {
      Path file = f.admit("old bytes");
      Files.delete(file);
      f.loop.start();
      var replay = f.prepare();
      assertTrue(replay.isPresent(), "proven absence must retain the retry-only wake path");
      assertNull(f.hash(file));
      assertEquals(0, f.queue.jobStateCountsStrict().pendingCount());
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(f.queue, replay.orElseThrow()));
    }
  }

  @ParameterizedTest(name = "absentPreparationPreservesDoneFailedAndIssuedWaits({arguments})")
  @ValueSource(strings = {"DONE", "FAILED", "PROCESSING"})
  void absentPreparationPreservesDoneFailedAndIssuedWaits(String state) throws Exception {
    try (var f = new Fixture()) {
      Path file = f.admit("old bytes");
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.database);
           var update = connection.prepareStatement("UPDATE jobs SET state=? WHERE path=?")) {
        update.setString(1, state.equals("PROCESSING") ? "PENDING" : state);
        update.setString(2, file.toString()); assertEquals(1, update.executeUpdate());
      }
      if (state.equals("PROCESSING")) {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.database);
             var update = connection.createStatement()) { update.executeUpdate("UPDATE jobs SET retry_after=NULL"); }
        assertEquals(file, f.queue.pollPending(1).getFirst().path());
      }
      Files.delete(file);
      var before = f.row(file);
      var receipts = f.queue.listSwitchBufferOpsStrict();
      try (var root = rootPreflight(f, receipts)) { prepareRoot(root, receipts); }
      assertEquals(before, f.row(file), "preparation must preserve non-pending/issued waits even for proven absence");
      assertEquals(receipts, f.queue.listSwitchBufferOpsStrict());
    }
  }

  @Test
  void laterIndependentPendingRevisionAndItsWaitRemainUntouched() throws Exception {
    try (var f = new Fixture()) {
      Path file = f.admit("old bytes");
      Files.writeString(file, "new bytes");
      f.queue.enqueueEntriesWithExactCollection(List.of(JobQueue.EnqueueEntry.stat(file)), "notes");
      f.backoff(file);
      var before = f.row(file);
      var receipts = f.queue.listSwitchBufferOpsStrict();
      f.loop.start();
      // The unrelated pending row never settles, so this refusal spends its whole budget.
      assertTrue(f.prepare(System.nanoTime() + TimeUnit.SECONDS.toNanos(8)).isEmpty(),
          "an unrelated replacement cannot be borrowed by root replay");
      assertEquals(before, f.row(file));
      assertEquals(receipts, f.queue.listSwitchBufferOpsStrict());
    }
  }

  @Test
  void committedOwnedH2RepairsH3AfterIdenticalDebtReplacementAndQueueRestart() throws Exception {
    committedOwnedH2RepairsH3AfterRestart(false);
  }

  @Test
  void ordinaryScanPendingH2RepairsH3AndPreservesScanAttribution() throws Exception {
    try (var f = new Fixture()) {
      Path file = f.admit("old bytes", true);
      String scan = f.row(file).get("scan_id");
      Files.writeString(file, "new bytes");
      var receipts = f.queue.listSwitchBufferOpsStrict();
      try (var root = rootPreflight(f, receipts)) { prepareRoot(root, receipts); }
      String h2 = f.row(file).get("unit_revision");
      f.backoff(file); // The successor has the settled eligible failure/policy, not an arbitrary pending wait.
      assertEquals(scan, f.row(file).get("scan_id"));
      var stamp = Files.getLastModifiedTime(file);
      Files.writeString(file, "end bytes");
      Files.setLastModifiedTime(file, stamp);
      try (var root = rootPreflight(f, receipts)) { prepareRoot(root, receipts); }
      assertNotEquals(h2, f.row(file).get("unit_revision"), "owned ordinary scan H2 must admit current H3");
      assertEquals(scan, f.row(file).get("scan_id"));
      assertEquals(SourceContentHash.sha256(file), f.row(file).get("planned_source_sha256"));
      assertEquals(receipts, f.queue.listSwitchBufferOpsStrict());
      f.loop.start();
      var replay = f.prepare();
      assertTrue(replay.isPresent());
      assertEquals(scan, f.row(file).get("scan_id"));
      assertEquals(SourceContentHash.sha256(file), f.hash(file));
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(f.queue, replay.orElseThrow()));
    }
  }

  @Test
  void reconciliationExcludesTheActualLoopWriterUntilConditionalAdmissionFinishes() throws Exception {
    try (var f = new Fixture()) {
      Path file = f.admit("old bytes");
      Files.writeString(file, "new bytes");
      var receipts = f.queue.listSwitchBufferOpsStrict();
      var entered = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      var competing = new java.util.concurrent.CountDownLatch(1);
      var connectionField = SqliteJobQueue.class.getDeclaredField("connection");
      connectionField.setAccessible(true);
      var connection = (java.sql.Connection) connectionField.get(f.queue);
      org.sqlite.Function.create(connection, "hold_reconciliation", new org.sqlite.Function() {
        @Override protected void xFunc() throws java.sql.SQLException {
          entered.countDown();
          try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.sql.SQLException("writer barrier not released"); }
          catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.sql.SQLException(failure); }
          result();
        }
      });
      try (var statement = connection.createStatement()) {
        statement.execute("CREATE TEMP TRIGGER hold_reconciliation AFTER UPDATE OF unit_revision ON jobs "
            + "WHEN OLD.unit_revision IS NOT NEW.unit_revision BEGIN SELECT hold_reconciliation(); END");
      }
      var replay = java.util.concurrent.CompletableFuture.runAsync(() -> {
        try (var root = rootPreflight(f, receipts)) { prepareRoot(root, receipts); }
        catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
      });
      java.util.concurrent.CompletableFuture<?> writer = null;
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var started = new java.util.concurrent.CountDownLatch(1);
        writer = java.util.concurrent.CompletableFuture.runAsync(() -> {
          started.countDown();
          f.loop.withFileMutationFence(() -> {
            competing.countDown();
            f.runtime.indexingCoordinator().indexSingle(new io.justsearch.indexing.api.IndexDocument(java.util.Map.of(
                SchemaFields.DOC_ID, "competing-writer", SchemaFields.DOC_UID, "competing-writer",
                SchemaFields.PATH, "competing-writer", SchemaFields.CONTENT, "actual fenced writer")));
            return true;
          });
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertFalse(competing.await(100, TimeUnit.MILLISECONDS), "the actual loop writer must wait behind reconciliation");
      } finally {
        release.countDown();
        replay.get(5, TimeUnit.SECONDS);
        if (writer != null) writer.get(5, TimeUnit.SECONDS);
        try (var statement = connection.createStatement()) { statement.execute("DROP TRIGGER hold_reconciliation"); }
        org.sqlite.Function.destroy(connection, "hold_reconciliation");
      }
      assertEquals(0, competing.getCount());
      assertEquals(receipts, f.queue.listSwitchBufferOpsStrict());
    }
  }

  @Test
  void ordinaryScanOwnedH2RepairsH3AfterIdenticalDebtReplacementAndQueueRestart() throws Exception {
    committedOwnedH2RepairsH3AfterRestart(true);
  }

  private void committedOwnedH2RepairsH3AfterRestart(boolean ordinaryScan) throws Exception {
    Path file;
    String h2Revision;
    String scan;
    try (var f = new Fixture()) {
      file = f.admit("old bytes", ordinaryScan);
      scan = f.row(file).get("scan_id");
      if (ordinaryScan) assertNotNull(scan);
      Files.writeString(file, "new bytes");
      f.loop.start();
      assertTrue(f.prepare().isPresent(), "first attempt must commit owned H2 without acknowledging debt");
      h2Revision = f.row(file).get("unit_revision");
      assertEquals(SourceContentHash.sha256(file), f.hash(file));
    }
    try (var f = new Fixture()) {
      assertEquals(h2Revision, f.row(file).get("unit_revision"));
      assertEquals(scan, f.row(file).get("scan_id"), "ordinary attribution survives the unacknowledged restart");
      var debt = new WatcherRootReconciliation(f.root.toString(), RootIdentity.capture(f.root).toString(), "notes");
      assertTrue(f.queue.putSwitchBuffer(debt.key(), WatcherRootReconciliation.OPERATION, debt.encode()));
      var stamp = Files.getLastModifiedTime(file);
      Files.writeString(file, "end bytes");
      Files.setLastModifiedTime(file, stamp);
      Path cursor = f.advanceCursor();
      var unrelated = f.row(cursor);
      var receipts = f.queue.listSwitchBufferOpsStrict();
      try (var root = rootPreflight(f, receipts)) {
        prepareRoot(root, receipts);
        assertEquals(h2Revision, f.row(file).get("unit_revision"), "preparation must leave DONE H2 intact");
        root.apply();
        var claim = f.queue.pollPending(1).getFirst();
        assertEquals(file, claim.path(), "a readmission behind the advanced cursor must become claimable");
        assertEquals(SourceContentHash.sha256(file), claim.plannedSourceSha256());
        f.queue.returnUnfinishedClaims(List.of(claim));
      }
      f.loop.start();
      var replay = f.prepare();
      assertTrue(replay.isPresent(), "fresh exact-debt handoff must repair DONE H2 against H3 after restart");
      assertEquals(unrelated, f.row(cursor));
      assertNotEquals(h2Revision, f.row(file).get("unit_revision"));
      assertEquals(scan, f.row(file).get("scan_id"));
      assertEquals(SourceContentHash.sha256(file), f.hash(file));
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(f.queue, replay.orElseThrow()));
    }
  }

  @ParameterizedTest(name = "preparedCurrentBytesKeepTheOriginalBroadDeleteOrder({arguments})")
  @ValueSource(strings = {"prefix-before", "prefix-after", "collection-before", "collection-after"})
  void preparedCurrentBytesKeepTheOriginalBroadDeleteOrder(String order) throws Exception {
    try (var f = new Fixture()) {
      Path file = f.admit("old bytes");
      Files.writeString(file, "new bytes");
      // Replace the debt only to select its accepted position relative to this explicit delete.
      var rootReceipt = f.queue.listSwitchBufferOpsStrict().getLast();
      if (order.endsWith("before")) {
        assertEquals(1, f.queue.removeReplayedSwitchBufferOps(List.of(rootReceipt)));
      }
      boolean prefix = order.startsWith("prefix");
      String prefixPath = io.justsearch.adapters.lucene.runtime.QueryFilterBuilder.normalizePathPrefix(f.root.toString());
      String payload = prefix ? new io.justsearch.adapters.lucene.runtime.PathPrefixScope(prefixPath, List.of()).encode() : "notes";
      assertTrue(f.queue.putSwitchBufferForGeneration("green", prefix ? "prefix:" + prefixPath : "collection:notes",
          prefix ? "DELETE_PREFIX" : "DELETE_COLLECTION", payload));
      if (order.endsWith("before")) {
        assertTrue(f.queue.putSwitchBuffer(rootReceipt.key(), rootReceipt.op(), rootReceipt.payload()));
      }
      var receipts = f.queue.listSwitchBufferOpsStrict();
      f.loop.start();
      long started = System.nanoTime();
      var replay = f.prepare();
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      assertTrue(replay.isPresent(), "current-byte prep must not move an admission after a final delete: "
          + order + " (prep took " + elapsedMs + " ms)");
      assertEquals(order.endsWith("before") ? SourceContentHash.sha256(file) : null, f.hash(file));
      assertEquals(receipts, f.queue.listSwitchBufferOpsStrict());
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(f.queue, replay.orElseThrow()));
    }
  }

  @ParameterizedTest(name = "preparationPreservesDoneIssuedUnknownAndProtectedRows({arguments})")
  @ValueSource(strings = {"DONE", "FAILED", "PROCESSING", "foreign", "release030", "recorded", "parser-wait"})
  void preparationPreservesDoneIssuedUnknownAndProtectedRows(String control) throws Exception {
    try (var f = new Fixture()) {
      Path file = f.admit("old bytes");
      Files.writeString(file, "new bytes");
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.database);
           var update = connection.prepareStatement(switch (control) {
             case "foreign" -> "UPDATE jobs SET unit_revision='independent-admission' WHERE path=?";
             case "release030" -> "UPDATE jobs SET unit_revision='release030-unscoped/independent' WHERE path=?";
             case "recorded" -> "UPDATE jobs SET walk_seen_epoch=1 WHERE path=?";
             case "parser-wait" -> "UPDATE jobs SET last_reason_code='PARSER_TIMEOUT',last_retry_policy='RETRY_WITH_BACKOFF' WHERE path=?";
             case "PROCESSING" -> "UPDATE jobs SET retry_after=NULL WHERE path=?";
             default -> "UPDATE jobs SET state='" + control + "' WHERE path=?";
           })) {
        update.setString(1, file.toString()); assertEquals(1, update.executeUpdate());
      }
      if (control.equals("PROCESSING")) assertEquals(file, f.queue.pollPending(1).getFirst().path());
      var before = f.row(file);
      var receipts = f.queue.listSwitchBufferOpsStrict();
      try (var root = new WatcherReplay(WatcherRootReconciliation.decode(receipts.getLast().payload()),
          f.runtime, f.queue, f.publisher, IndexingPacing.unthrottled(),
          List.of(new ResolvedConfig.FileSource(f.root, "notes")), List.of(file.toString()), () -> false)) {
        try {
          WatcherReplay.class.getMethod("prepare", String.class, List.class,
              io.justsearch.indexerworker.queue.SwitchBufferCapableQueue.SwitchBufferOp.class)
              .invoke(root, "green", receipts, receipts.getLast());
        } catch (NoSuchMethodException beforeA3) {
          // d726's preflight only wakes its exact ROOT_UNAVAILABLE pending revisions.
          if (!control.equals("parser-wait") && !control.equals("recorded")) {
            WatcherReplay.class.getMethod("wakeReturnedRootAdmissions", List.class).invoke(root, List.of());
          }
        }
      }
      assertEquals(before, f.row(file), "preparation cannot borrow " + control + " work");
      assertEquals(receipts, f.queue.listSwitchBufferOpsStrict());
    }
  }

  @Test
  void freshRootFileIsInsertedOnlyByOrderedApply() throws Exception {
    try (var f = new Fixture()) {
      assertTrue(f.publisher.watchRoot(io.justsearch.ipc.WatchRootRequest.newBuilder()
          .setRootPath(f.root.toString()).setCollection("notes").setRootAdded(true).build(), CallContext.none()).getWatching());
      Path fresh = Files.writeString(f.root.resolve("fresh.txt"), "fresh bytes");
      var debt = new WatcherRootReconciliation(f.root.toString(), RootIdentity.capture(f.root).toString(), "notes");
      assertTrue(f.queue.putSwitchBuffer(debt.key(), WatcherRootReconciliation.OPERATION, debt.encode()));
      var receipts = f.queue.listSwitchBufferOpsStrict();
      try (var root = rootPreflight(f, receipts)) {
        prepareRoot(root, receipts);
        assertNull(f.row(fresh), "preparation cannot insert a missing row");
      }
      f.loop.start();
      var replay = f.prepare();
      assertTrue(replay.isPresent());
      assertEquals(SourceContentHash.sha256(fresh), f.hash(fresh));
      assertEquals(receipts, f.queue.listSwitchBufferOpsStrict());
    }
  }

  @ParameterizedTest(name = "staleFrozenExpectationsRefuseBeforeAnyConditionalMutation({arguments})")
  @ValueSource(strings = {"journal", "full-row"})
  void staleFrozenExpectationsRefuseBeforeAnyConditionalMutation(String changed) throws Exception {
    try (var f = new Fixture()) {
      Path file = f.admit("old bytes");
      Files.writeString(file, "new bytes");
      var receipts = f.queue.listSwitchBufferOpsStrict();
      var expectedRow = f.row(file);
      try (var root = rootPreflight(f, receipts)) {
        if (changed.equals("journal")) {
          var debt = receipts.getLast();
          assertTrue(f.queue.putSwitchBuffer(debt.key(), debt.op(), debt.payload()));
        } else {
          try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.database);
               var update = connection.prepareStatement("UPDATE jobs SET error_message='changed after snapshot' WHERE path=?")) {
            update.setString(1, file.toString()); assertEquals(1, update.executeUpdate());
          }
        }
        var afterRace = f.row(file);
        var afterJournal = f.queue.listSwitchBufferOpsStrict();
        try {
          Class<?> requestType = Class.forName("io.justsearch.indexerworker.queue.WatcherReconciliationFile");
          Object request = requestType.getConstructors()[0].newInstance("green", receipts, receipts.getLast(),
              file.toString(), SourceContentHash.sha256(file), Files.size(file), false, false, expectedRow);
          Runnable forbidden = () -> { throw new AssertionError("changed expectations must refuse before fresh proof/effects"); };
          try {
            Object decision = f.publisher.underFileMutationFence(() -> {
              try {
                return SqliteJobQueue.class.getMethod("reconcileWatcherFile", requestType, Runnable.class, Runnable.class)
                    .invoke(f.queue, request, forbidden, forbidden);
              } catch (ReflectiveOperationException failure) {
                if (failure instanceof java.lang.reflect.InvocationTargetException invocation
                    && invocation.getCause() instanceof IllegalStateException refused) return refused;
                throw new AssertionError(failure);
              }
            });
            assertTrue(Boolean.FALSE.equals(decision) || decision instanceof IllegalStateException,
                "changed expectations must refuse the conditional transition");
          } catch (IllegalStateException refused) { /* A changed journal invalidates this attempt. */ }
        } catch (ClassNotFoundException beforeA3) {
          root.apply(); // Old unguarded reconciliation must execute, not fail compilation.
        }
        assertEquals(afterRace, f.row(file), "the complete expected row/journal must still match before reset");
        assertEquals(afterJournal, f.queue.listSwitchBufferOpsStrict());
      }
    }
  }

  @Test
  void failedPostMutationRootProofRollsBackSqlAndRetainsDebtForRetry() throws Exception {
    try (var f = new Fixture()) {
      Path file = f.admit("old bytes");
      Files.writeString(file, "new bytes");
      var before = f.row(file);
      var receipts = f.queue.listSwitchBufferOpsStrict();
      var connectionField = SqliteJobQueue.class.getDeclaredField("connection");
      connectionField.setAccessible(true);
      var connection = (java.sql.Connection) connectionField.get(f.queue);
      org.sqlite.Function.create(connection, "lose_reconciliation_volume", new org.sqlite.Function() {
        @Override protected void xFunc() throws java.sql.SQLException {
          RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.set(true);
          result();
        }
      });
      try (var root = rootPreflight(f, receipts); var statement = connection.createStatement()) {
        statement.execute("CREATE TEMP TRIGGER lose_volume AFTER UPDATE OF unit_revision ON jobs "
            + "WHEN OLD.unit_revision IS NOT NEW.unit_revision BEGIN SELECT lose_reconciliation_volume(); END");
        assertThrows(java.io.IOException.class, () -> prepareRoot(root, receipts));
      } finally {
        RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.set(false);
        try (var statement = connection.createStatement()) { statement.execute("DROP TRIGGER IF EXISTS lose_volume"); }
        org.sqlite.Function.destroy(connection, "lose_reconciliation_volume");
      }
      assertEquals(before, f.row(file), "fresh proof refusal after reset must roll back the entire SQL transition");
      assertEquals(receipts, f.queue.listSwitchBufferOpsStrict());
      f.loop.start();
      assertTrue(f.prepare().isPresent(), "the retained exact debt retries after fresh evidence returns");
      assertEquals(SourceContentHash.sha256(file), f.hash(file));
    }
  }

  @ParameterizedTest(name = "unknownOrConflictingDebtLineageCannotReadmitOwnedDoneWork({arguments})")
  @ValueSource(strings = {"reverse", "conflict", "payload", "candidate", "malformed"})
  void unknownOrConflictingDebtLineageCannotReadmitOwnedDoneWork(String changed) throws Exception {
    try (var f = new Fixture()) {
      Path file = f.admit("old bytes");
      Files.writeString(file, "new bytes");
      f.loop.start();
      assertTrue(f.prepare().isPresent());
      String h2 = f.hash(file);
      var root = f.queue.listSwitchBufferOpsStrict().stream()
          .filter(op -> WatcherRootReconciliation.OPERATION.equals(op.op())).findFirst().orElseThrow();
      if (changed.equals("conflict")) {
        var replacement = new io.justsearch.indexerworker.queue.SwitchBufferUpsert(file.toString(), "notes", null,
            "a".repeat(32), SourceContentHash.sha256(file), f.root);
        assertTrue(f.queue.putSwitchBufferForGeneration("green", "path:" + file, "UPSERT", replacement.encode()));
      }
      assertTrue(f.queue.putSwitchBuffer(root.key(), root.op(), root.payload() + (changed.equals("payload") ? " " : "")));
      if (changed.equals("reverse")) {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.database);
             var update = connection.prepareStatement("UPDATE switch_buffer SET accepted_order=2 WHERE key=?")) {
          update.setString(1, root.key()); assertEquals(1, update.executeUpdate());
        }
      }
      if (changed.equals("malformed")) {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.database);
             var update = connection.prepareStatement("UPDATE jobs SET unit_revision='watcher-reconcile-v1:unknown' WHERE path=?")) {
          update.setString(1, file.toString()); assertEquals(1, update.executeUpdate());
        }
      }
      if (changed.equals("candidate")) f.candidate = "another-candidate";
      Files.writeString(file, "end bytes");
      var before = f.row(file);
      var receipts = f.queue.listSwitchBufferOpsStrict();
      assertTrue(f.prepare().isEmpty(), "only strict forward unchanged-debt lineage can hand off owned work: " + changed);
      assertEquals(before, f.row(file));
      assertEquals(receipts, f.queue.listSwitchBufferOpsStrict());
      assertEquals(h2, f.hash(file));
    }
  }

  @ParameterizedTest(name = "changedBytesDuringIssuedWorkWaitForPhysicalCompletionThenRepairH3({arguments})")
  @ValueSource(strings = {"extraction", "commit"})
  void changedBytesDuringIssuedWorkWaitForPhysicalCompletionThenRepairH3(String cut) throws Exception {
    try (var f = new Fixture()) {
      Path file = f.admit("old bytes");
      Files.writeString(file, "new bytes");
      var entered = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      Runnable hold = () -> {
        entered.countDown();
        try { assertTrue(release.await(10, TimeUnit.SECONDS), "release the owned writer barrier"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
      };
      var listenerField = io.justsearch.adapters.lucene.runtime.CommitOps.class.getDeclaredField("commitCompletedListener");
      listenerField.setAccessible(true);
      var original = (io.justsearch.adapters.lucene.runtime.CommitOps.CommitCompletedListener) listenerField.get(f.runtime.commitOps());
      if (cut.equals("extraction")) f.extractionHook.set(hold);
      else {
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        f.runtime.commitOps().setCommitCompletedListener(reason -> {
          if (original != null) original.onCommitCompleted(reason);
          if (once.compareAndSet(false, true)) hold.run();
        });
      }
      java.util.concurrent.CompletableFuture<java.util.Optional<KnowledgeServerMigrationOps.StrictReplay>> replay = null;
      try {
        f.loop.start();
        replay = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
          try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            java.util.Optional<KnowledgeServerMigrationOps.StrictReplay> result;
            do { result = f.prepare(deadline); }
            while (result.isEmpty() && System.nanoTime() < deadline);
            return result;
          } catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var issued = f.row(file);
        assertEquals("PROCESSING", issued.get("state"));
        // An external writer does not cooperate with the Engine fence. A fresh attempt must
        // re-probe after a transient refusal without acquiring a new promotion allowance.
        var stamp = Files.getLastModifiedTime(file);
        Files.writeString(file, "end bytes");
        Files.setLastModifiedTime(file, stamp);
        assertEquals(issued.get("unit_revision"), f.row(file).get("unit_revision"), "issued work retains its physical owner");
        release.countDown();
        assertTrue(replay.get(10, TimeUnit.SECONDS).isPresent());
        assertEquals(SourceContentHash.sha256(file), f.hash(file));
      } finally {
        release.countDown();
        if (replay != null) replay.get(10, TimeUnit.SECONDS);
        f.runtime.commitOps().setCommitCompletedListener(original);
      }
    }
  }

  @Test
  void freshFileSuccessorCannotHandOffAcrossAnInterveningPathReceipt() throws Exception {
    try (var f = new Fixture()) {
      assertTrue(f.publisher.watchRoot(io.justsearch.ipc.WatchRootRequest.newBuilder()
          .setRootPath(f.root.toString()).setCollection("notes").setRootAdded(true).build(), CallContext.none()).getWatching());
      Path file = Files.writeString(f.root.resolve("file.txt"), "new bytes");
      var debt = new WatcherRootReconciliation(f.root.toString(), RootIdentity.capture(f.root).toString(), "notes");
      assertTrue(f.queue.putSwitchBuffer(debt.key(), WatcherRootReconciliation.OPERATION, debt.encode()));
      f.loop.start();
      assertTrue(f.prepare().isPresent());
      assertTrue(f.queue.putSwitchBuffer("path:" + file, "DELETE",
          new io.justsearch.indexerworker.queue.SwitchBufferDelete(file.toString(),
              io.justsearch.indexerworker.queue.SwitchBufferDelete.Origin.WATCHER,
              debt.root(), debt.rootIdentity(), "notes").encode()));
      assertTrue(f.queue.putSwitchBuffer(debt.key(), WatcherRootReconciliation.OPERATION, debt.encode()));
      Files.writeString(file, "end bytes");
      f.backoff(file);
      var before = f.row(file);
      var receipts = f.queue.listSwitchBufferOpsStrict();
      try (var root = rootPreflight(f, receipts)) { prepareRoot(root, receipts); }
      assertEquals(before, f.row(file), "a no-predecessor lineage cannot borrow an intervening path receipt");
      assertEquals(receipts, f.queue.listSwitchBufferOpsStrict());
    }
  }

  private WatcherReplay rootPreflight(Fixture f,
      List<io.justsearch.indexerworker.queue.SwitchBufferCapableQueue.SwitchBufferOp> receipts) throws Exception {
    var debt = receipts.stream().filter(op -> WatcherRootReconciliation.OPERATION.equals(op.op())).findFirst().orElseThrow();
    return new WatcherReplay(WatcherRootReconciliation.decode(debt.payload()), f.runtime, f.queue, f.publisher,
        IndexingPacing.unthrottled(), List.of(new ResolvedConfig.FileSource(f.root, "notes")),
        List.of(f.root.resolve("file.txt").toString()), () -> false);
  }

  private static void prepareRoot(WatcherReplay root,
      List<io.justsearch.indexerworker.queue.SwitchBufferCapableQueue.SwitchBufferOp> receipts) throws Exception {
    var debt = receipts.stream().filter(op -> WatcherRootReconciliation.OPERATION.equals(op.op())).findFirst().orElseThrow();
    try {
      WatcherReplay.class.getMethod("prepare", String.class, List.class,
          io.justsearch.indexerworker.queue.SwitchBufferCapableQueue.SwitchBufferOp.class)
          .invoke(root, "green", receipts, debt);
    } catch (NoSuchMethodException beforeA3) {
      root.apply();
    } catch (java.lang.reflect.InvocationTargetException failure) {
      if (failure.getCause() instanceof Exception original) throw original;
      if (failure.getCause() instanceof Error original) throw original;
      throw new AssertionError(failure.getCause());
    }
  }

  private final class Fixture implements AutoCloseable {
    final Path root = Files.createDirectories(temporary.resolve("root"));
    final Path database = temporary.resolve("jobs.db");
    final SqliteJobQueue queue = new SqliteJobQueue(database);
    final TestEngineExecutors executors = new TestEngineExecutors();
    final WorkerExecutorRegistrations registrations = new WorkerExecutorRegistrations(executors);
    final RunningRuntime runtime;
    final SqliteDocumentIdentityStore identities;
    final IndexingLoop loop;
    final WorkerIngestService publisher;
    final java.util.concurrent.atomic.AtomicReference<Runnable> extractionHook =
        new java.util.concurrent.atomic.AtomicReference<>(() -> {});
    String candidate = "green";

    Fixture() throws Exception {
      queue.open();
      identities = new SqliteDocumentIdentityStore(database);
      runtime = withTestExecutors(IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
          .atPath(temporary.resolve("target"))).open();
      runtime.commitOps().stopCommitTimer();
      var provider = new ContentExtractorProvider() {
        @Override public ContentExtractor.ExtractionResult extract(Path path) throws java.io.IOException {
          extractionHook.getAndSet(() -> {}).run();
          return new ContentExtractor.ExtractionResult(Files.readString(path), null, "text/plain");
        }
        @Override public String detectMimeType(Path path) { return "text/plain"; }
      };
      var content = new TimeboxedContentExtractor(registrations.extractionTimebox(), provider);
      var configuration = ResolvedConfig.builder()
          .putDefault("justsearch.data.dir", temporary.toString())
          .putDefault("justsearch.ssot.path", temporary.resolve("SSOT").toString())
          .putDefault("justsearch.index.base_path", temporary.resolve("index").toString()).build();
      loop = new IndexingLoop(registrations.pdfOcr(), registrations.extractionTimebox(), queue,
          runtime.indexingCoordinator(), runtime.commitOps(), runtime.documentFieldOps(), runtime.indexCountOps(),
          () -> configuration, mock(io.justsearch.indexerworker.coordination.WorkerSignalBus.class), IndexingPacing.unthrottled(),
          null, null, null, null, content, null,
          new IndexingLoopOptions(false, null, identities, null, null, null));
      publisher = new WorkerIngestService(queue, loop, null, IndexingPacing.unthrottled(), temporary,
          temporary.resolve("target"), runtime, runtime, null, 0);
      var registry = new RootWatcherRegistry();
      assertTrue(registry.watch(root.toString(), "notes").watching());
      publisher.setMutationAdmission(new WorkerMutationAdmission(this), this);
      publisher.setRootWatcherRegistry(registry);
      publisher.setDeclaredRootsDirectory(temporary);
      loop.useRootCheck(publisher.rootCheck());
    }

    Path admit(String text) throws Exception { return admit(text, false); }

    Path admit(String text, boolean ordinaryScan) throws Exception {
      Path file = Files.writeString(root.resolve("file.txt"), text);
      assertTrue(publisher.watchRoot(io.justsearch.ipc.WatchRootRequest.newBuilder()
          .setRootPath(root.toString()).setCollection("notes").setRootAdded(true).build(), CallContext.none()).getWatching());
      if (ordinaryScan) {
        // WorkerScanOps uses this native admission for ordinary (non-recorded) scan batches.
        String scan = java.util.UUID.randomUUID().toString();
        assertEquals(1, queue.enqueueAndBufferFilesForGeneration("green",
            List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)), "notes", scan));
        assertEquals(scan, row(file).get("scan_id"));
      } else {
        assertTrue(queue.enqueueAndBufferFileForGeneration("green", JobQueue.EnqueueEntry.stat(file).withinRoot(root), "notes"));
      }
      backoff(file);
      var debt = new WatcherRootReconciliation(root.toString(), RootIdentity.capture(root).toString(), "notes");
      assertTrue(queue.putSwitchBuffer(debt.key(), WatcherRootReconciliation.OPERATION, debt.encode()));
      return file;
    }
    void backoff(Path file) throws Exception {
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
           var update = connection.prepareStatement("UPDATE jobs SET state='PENDING', attempts=2, retry_after=4000000000000,"
               + "last_reason_code='ROOT_UNAVAILABLE',last_outcome_class='IO_FAILED',"
               + "last_retry_policy='RETRY_UNTIL_AVAILABLE' WHERE path=?")) {
        update.setString(1, file.toString()); assertEquals(1, update.executeUpdate());
      }
    }
    Path advanceCursor() throws Exception {
      Path cursor = Files.writeString(temporary.resolve("cursor.txt"), "unrelated cursor fixture");
      queue.enqueue(List.of(cursor));
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
           var update = connection.prepareStatement("UPDATE jobs SET last_updated=? WHERE path=?")) {
        update.setLong(1, System.currentTimeMillis() + 600_000);
        update.setString(2, cursor.toString()); assertEquals(1, update.executeUpdate());
      }
      var claim = queue.pollPending(1).getFirst();
      assertEquals(cursor, claim.path());
      assertTrue(queue.markClaimDone(claim, io.justsearch.indexerworker.ingest.IngestionOutcome.of(
          io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_FULL, "CURSOR_FIXTURE",
          io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE), null));
      return cursor;
    }
    java.util.Map<String, String> row(Path file) throws Exception {
      // Full-row observation works on d726 too; a missing candidate API is never fail-before evidence.
      var result = new java.util.LinkedHashMap<String, String>();
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
           var query = connection.prepareStatement("SELECT * FROM jobs WHERE path=?")) {
        query.setString(1, file.toString());
        try (var rows = query.executeQuery()) {
          if (!rows.next()) return null;
          for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
            result.put(rows.getMetaData().getColumnName(i), rows.getString(i));
          }
        }
      }
      return result;
    }
    String hash(Path file) throws Exception {
      runtime.commitOps().maybeRefreshBlocking();
      return runtime.documentFieldOps().getDocumentFieldOrThrow(file.toString(), SchemaFields.SOURCE_SHA256);
    }
    java.util.Optional<KnowledgeServerMigrationOps.StrictReplay> prepare() throws Exception {
      // Preparation waits for the started loop to settle and returns as soon as it has; the budget
      // only bounds a regression, inside JUnit's 30 s default. 8 s expired under load (2.5 s idle).
      return prepare(System.nanoTime() + TimeUnit.SECONDS.toNanos(20));
    }
    java.util.Optional<KnowledgeServerMigrationOps.StrictReplay> prepare(long deadline) throws Exception {
      return KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(
          new KnowledgeServerMigrationOps.DrainSwitchBufferContext(queue, runtime, null,
              IndexingPacing.unthrottled(), temporary, temporary.resolve("target"), new ObjectMapper(),
              () -> false, () -> true, LoggerFactory.getLogger(getClass()),
              deadline, candidate, ignored -> true)
              .withPublisher(publisher).withMigrationRoots(List.of(new ResolvedConfig.FileSource(root, "notes"))));
    }
    @Override public void close() throws Exception {
      try { loop.close(); }
      finally {
        try { runtime.close(); }
        finally {
          try { identities.close(); queue.close(); }
          finally { registrations.close(); executors.close(); }
        }
      }
    }
  }
}
