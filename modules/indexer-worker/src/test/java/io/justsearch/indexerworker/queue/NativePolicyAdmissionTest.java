/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(20)
final class NativePolicyAdmissionTest {
  @TempDir Path directory;
  static final String CANDIDATE = "g-native-policy";
  static final JobQueue.EnqueueProvenance PROVENANCE =
      new JobQueue.EnqueueProvenance("system", "MIGRATION_ENUMERATOR");
  static final IngestionOutcome SKIP = IngestionOutcome.of(IngestionOutcomeClass.SKIPPED_POLICY,
      "SKIPPED_TEMP_OR_SYSTEM", IngestionRetryPolicy.NONE);

  private record FileAdmission(Path path, SwitchBufferCapableQueue.SwitchBufferOp op,
      Map<String, String> row) {}

  private FileAdmission skipped(SqliteJobQueue queue, Path root, String name, String collection) throws Exception {
    Path path = Files.writeString(root.resolve(name), "current " + name);
    assertTrue(queue.enqueueAndBufferFileForGeneration(CANDIDATE,
        JobQueue.EnqueueEntry.stat(path, PROVENANCE).withinRoot(root), collection));
    var claim = queue.pollPending(1).getFirst();
    assertTrue(queue.markClaimDone(claim, SKIP, null));
    var op = queue.listSwitchBufferOpsStrict().stream()
        .filter(value -> value.key().equals("path:" + PathNormalizer.normalizeKey(path))).findFirst().orElseThrow();
    return new FileAdmission(path, op, queue.watcherReconciliationRow(PathNormalizer.normalizeKey(path)));
  }

  private NativePolicyReadmission replacement(FileAdmission old, Path path, Path root, String collection) throws Exception {
    return new NativePolicyReadmission(old.op(), old.row(),
        new JobQueue.EnqueueEntry(path, Files.size(path), PROVENANCE,
            SourceContentHash.sha256(path), root), collection);
  }

  @Test
  void exactReadPreservesNullableRowsAndRejectsDifferentExpectedPathAndChangedJournal() throws Exception {
    try (var queue = new SqliteJobQueue(directory.resolve("read.db"))) {
      queue.open(); Path root = Files.createDirectory(directory.resolve("root"));
      var p = skipped(queue, root, "p.txt", null); var q = skipped(queue, root, "q.txt", null);
      var journal = queue.listSwitchBufferOpsStrict();
      assertTrue(p.row().containsKey("content_hash")); assertNull(p.row().get("content_hash"));
      assertTrue(queue.tryMatchesNativePolicySkippedAdmission(CANDIDATE, journal, p.op(), p.row()));
      assertFalse(queue.tryMatchesNativePolicySkippedAdmission(CANDIDATE, journal, p.op(), q.row()));
      assertTrue(queue.putSwitchBuffer("later-root", "SYNC", root.toString()));
      assertFalse(queue.tryMatchesNativePolicySkippedAdmission(CANDIDATE, journal, p.op(), p.row()));
    }
  }

  @Test
  void pairingOldPWithIncomingQRefusesTheEntireBatchWithoutChangingEitherFile() throws Exception {
    try (var queue = new SqliteJobQueue(directory.resolve("join.db"))) {
      queue.open(); Path root = Files.createDirectory(directory.resolve("join-root"));
      var p = skipped(queue, root, "p.txt", "documents"); var q = skipped(queue, root, "q.txt", "documents");
      var journal = queue.listSwitchBufferOpsStrict();
      assertTrue(queue.readmitNativePolicySkippedAdmissions(CANDIDATE, journal,
          List.of(replacement(p, q.path(), root, "documents"))).isEmpty());
      assertEquals(journal, queue.listSwitchBufferOpsStrict());
      assertEquals(p.row(), queue.watcherReconciliationRow(PathNormalizer.normalizeKey(p.path())));
      assertEquals(q.row(), queue.watcherReconciliationRow(PathNormalizer.normalizeKey(q.path())));
    }
  }

  @Test
  void duplicateAndDotAliasPathsRefuseTheWholeBatch() throws Exception {
    try (var queue = new SqliteJobQueue(directory.resolve("duplicates.db"))) {
      queue.open(); Path root = Files.createDirectory(directory.resolve("duplicate-root"));
      var p = skipped(queue, root, "p.txt", null); var journal = queue.listSwitchBufferOpsStrict();
      var entry = replacement(p, p.path(), root, null);
      assertTrue(queue.readmitNativePolicySkippedAdmissions(CANDIDATE, journal, List.of(entry, entry)).isEmpty());
      Files.createDirectory(root.resolve("sub"));
      var alias = replacement(p, root.resolve("sub/../p.txt"), root, null);
      assertTrue(queue.readmitNativePolicySkippedAdmissions(CANDIDATE, journal, List.of(entry, alias)).isEmpty());
      assertEquals(journal, queue.listSwitchBufferOpsStrict());
      assertEquals(p.row(), queue.watcherReconciliationRow(PathNormalizer.normalizeKey(p.path())));
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
  void windowsCaseAliasesRefuseTheEntireBatchBeforeEitherReplacement() throws Exception {
    try (var queue = new SqliteJobQueue(directory.resolve("windows-alias.db"))) {
      queue.open(); Path root = Files.createDirectory(directory.resolve("windows-alias-root"));
      var p = skipped(queue, root, "p.txt", null); var journal = queue.listSwitchBufferOpsStrict();
      var ordinary = replacement(p, p.path(), root, null);
      var caseAlias = replacement(p, root.resolve("P.TXT"), root, null);
      assertEquals(PathNormalizer.normalizeKey(ordinary.entry().path()), PathNormalizer.normalizeKey(caseAlias.entry().path()));
      assertTrue(queue.tryMatchesNativePolicySkippedAdmission(CANDIDATE, journal, p.op(), p.row()));
      assertTrue(queue.readmitNativePolicySkippedAdmissions(CANDIDATE, journal, List.of(ordinary, caseAlias)).isEmpty());
      assertEquals(journal, queue.listSwitchBufferOpsStrict());
      assertEquals(p.row(), queue.watcherReconciliationRow(PathNormalizer.normalizeKey(p.path())));
    }
  }

  @Test
  void oneStaleExpectationOrIssuedClaimPreventsEveryWriteInAMultiFileBatch() throws Exception {
    try (var queue = new SqliteJobQueue(directory.resolve("multi.db"))) {
      queue.open(); Path root = Files.createDirectory(directory.resolve("multi-root"));
      var p = skipped(queue, root, "p.txt", null); var q = skipped(queue, root, "q.txt", null);
      var journal = queue.listSwitchBufferOpsStrict();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(q.path(), PROVENANCE).withinRoot(root)));
      var claim = queue.pollPending(1).getFirst();
      var before = queue.watcherReconciliationRow(PathNormalizer.normalizeKey(p.path()));
      assertTrue(queue.readmitNativePolicySkippedAdmissions(CANDIDATE, journal,
          List.of(replacement(p, p.path(), root, null), replacement(q, q.path(), root, null))).isEmpty());
      assertEquals(before, queue.watcherReconciliationRow(PathNormalizer.normalizeKey(p.path())));
      assertFalse(queue.tryMatchesNativePolicySkippedAdmission(CANDIDATE, journal, q.op(), q.row()));
      queue.returnUnfinishedClaims(List.of(claim));
    }
  }

  @Test
  void aDoneRowWithAnActuallyIssuedHiddenClaimRefusesTheWholeBatch() throws Exception {
    try (var queue = new SqliteJobQueue(directory.resolve("hidden-claim.db"))) {
      queue.open(); Path root = Files.createDirectory(directory.resolve("hidden-claim-root"));
      var p = skipped(queue, root, "p.txt", null); var oldQ = skipped(queue, root, "q.txt", null);
      assertTrue(queue.enqueueAndBufferFileForGeneration(CANDIDATE,
          JobQueue.EnqueueEntry.stat(oldQ.path(), PROVENANCE).withinRoot(root), null));
      var claim = queue.pollPending(1).getFirst();
      assertEquals(oldQ.path(), claim.path());
      // The supported legacy completion writes DONE but does not surrender this issued claim.
      queue.markDone(claim.path(), SKIP, null);
      var journal = queue.listSwitchBufferOpsStrict();
      var qOp = journal.stream().filter(op -> op.key().equals(oldQ.op().key())).findFirst().orElseThrow();
      var q = new FileAdmission(oldQ.path(), qOp, queue.watcherReconciliationRow(PathNormalizer.normalizeKey(oldQ.path())));
      assertEquals("DONE", q.row().get("state"));
      assertEquals(SwitchBufferUpsert.decode(qOp.payload()).unitRevision(), q.row().get("unit_revision"));
      assertTrue(queue.tryMatchesNativePolicySkippedAdmission(CANDIDATE, journal, p.op(), p.row()));
      assertFalse(queue.tryMatchesNativePolicySkippedAdmission(CANDIDATE, journal, q.op(), q.row()));
      try {
        assertTrue(queue.readmitNativePolicySkippedAdmissions(CANDIDATE, journal,
            List.of(replacement(p, p.path(), root, null), replacement(q, q.path(), root, null))).isEmpty());
        assertEquals(journal, queue.listSwitchBufferOpsStrict());
        assertEquals(p.row(), queue.watcherReconciliationRow(PathNormalizer.normalizeKey(p.path())));
        assertEquals(q.row(), queue.watcherReconciliationRow(PathNormalizer.normalizeKey(q.path())));
      } finally { queue.returnUnfinishedClaims(List.of(claim)); }
    }
  }

  @Test
  void successfulBatchReturnsActualNewRowsAndReceiptsIncludingNullOwner() throws Exception {
    try (var queue = new SqliteJobQueue(directory.resolve("success.db"))) {
      queue.open(); Path root = Files.createDirectory(directory.resolve("success-root"));
      var p = skipped(queue, root, "p.txt", null); var q = skipped(queue, root, "q.txt", "documents");
      var journal = queue.listSwitchBufferOpsStrict();
      Files.writeString(p.path(), "new bytes");
      var actual = queue.readmitNativePolicySkippedAdmissions(CANDIDATE, journal,
          List.of(replacement(p, p.path(), root, null), replacement(q, q.path(), root, "documents")));
      assertEquals(2, actual.size()); assertEquals(actual, queue.listSwitchBufferOpsStrict());
      for (var op : actual) {
        var payload = SwitchBufferUpsert.decode(op.payload());
        var row = queue.watcherReconciliationRow(payload.path());
        assertEquals("PENDING", row.get("state")); assertEquals(payload.unitRevision(), row.get("unit_revision"));
        assertEquals(payload.sourceSha256(), row.get("planned_source_sha256"));
        assertEquals(payload.sourceSha256(), SourceContentHash.sha256(Path.of(payload.path())));
        assertEquals(payload.collection(), row.get("collection")); assertEquals(root, payload.ingestionRoot());
        assertEquals(PROVENANCE, payload.provenance()); assertTrue(op.acceptedOrder() > q.op().acceptedOrder());
        assertNotEquals(p.op().revision(), op.revision()); assertNotEquals(q.op().revision(), op.revision());
      }
      assertNull(SwitchBufferUpsert.decode(actual.getFirst().payload()).collection());
      assertEquals(0, queue.removeReplayedSwitchBufferOps(journal), "old cleanup cannot erase replacements");
      assertTrue(queue.readmitNativePolicySkippedAdmissions(CANDIDATE, journal,
          List.of(replacement(p, p.path(), root, null))).isEmpty(), "the old batch cannot reset current work");
    }
  }

  @Test
  void laterExactDeletePreventsReadmissionEvenWithTheCompleteCurrentSnapshot() throws Exception {
    try (var queue = new SqliteJobQueue(directory.resolve("later.db"))) {
      queue.open(); Path root = Files.createDirectory(directory.resolve("later-root"));
      var p = skipped(queue, root, "p.txt", null);
      assertTrue(queue.putSwitchBuffer(p.op().key(), "DELETE",
          new SwitchBufferDelete(PathNormalizer.normalizeKey(p.path()), SwitchBufferDelete.Origin.EXPLICIT, null).encode()));
      var journal = queue.listSwitchBufferOpsStrict();
      assertTrue(journal.contains(p.op()), "the candidate UPSERT remains an exact current member");
      assertTrue(queue.tryMatchesNativePolicySkippedAdmission(CANDIDATE, journal, p.op(), p.row()),
          "the ordinary skip predicate matches; accepted-order survival must refuse the mutable operation");
      assertTrue(queue.readmitNativePolicySkippedAdmissions(CANDIDATE, journal,
          List.of(replacement(p, p.path(), root, null))).isEmpty());
      assertEquals(journal, queue.listSwitchBufferOpsStrict());
    }
  }

  @Test
  void readOnlyPredicateDoesNotWaitForTheQueueOwner() throws Exception {
    try (var queue = new SqliteJobQueue(directory.resolve("contention.db"))) {
      queue.open(); Path root = Files.createDirectory(directory.resolve("contention-root"));
      var p = skipped(queue, root, "p.txt", null); var journal = queue.listSwitchBufferOpsStrict();
      var field = SqliteJobQueue.class.getDeclaredField("lock"); field.setAccessible(true);
      var lock = (ReentrantLock) field.get(queue); var acquired = new CountDownLatch(1); var release = new CountDownLatch(1);
      var owner = CompletableFuture.runAsync(() -> { lock.lock(); try { acquired.countDown(); release.await(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        finally { lock.unlock(); } });
      assertTrue(acquired.await(2, TimeUnit.SECONDS));
      try { assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () ->
          assertFalse(queue.tryMatchesNativePolicySkippedAdmission(CANDIDATE, journal, p.op(), p.row()))); }
      finally { release.countDown(); owner.get(2, TimeUnit.SECONDS); }
    }
  }

  @Test
  void confirmedCommitWithRestoreFailureThrowsAndReloadFindsReplacements() throws Exception {
    Path db = directory.resolve("restore.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open(); Path root = Files.createDirectory(directory.resolve("restore-root"));
      var p = skipped(queue, root, "p.txt", null); var journal = queue.listSwitchBufferOpsStrict();
      var incoming = replacement(p, p.path(), root, null);
      var field = SqliteJobQueue.class.getDeclaredField("connection"); field.setAccessible(true);
      var original = (Connection) field.get(queue);
      var intercepted = org.mockito.Mockito.mock(Connection.class, org.mockito.AdditionalAnswers.delegatesTo(original));
      org.mockito.Mockito.doThrow(new java.sql.SQLException("owned restore fixture")).when(intercepted).setAutoCommit(true);
      field.set(queue, intercepted);
      assertThrows(IllegalStateException.class, () -> queue.readmitNativePolicySkippedAdmissions(
          CANDIDATE, journal, List.of(incoming)));
      org.mockito.Mockito.verify(intercepted).commit();
      org.mockito.Mockito.verify(intercepted, org.mockito.Mockito.never()).rollback();
      queue.close(); queue.open();
      assertNotEquals(journal, queue.listSwitchBufferOpsStrict());
      assertEquals("PENDING", queue.watcherReconciliationRow(PathNormalizer.normalizeKey(p.path())).get("state"));
      assertFalse(queue.tryMatchesNativePolicySkippedAdmission(CANDIDATE, journal, p.op(), p.row()));
    }
  }
  @org.junit.jupiter.params.ParameterizedTest(
      name = "postcommitFailureIsUnconfirmedAndAuthoritativeReloadKeepsTheReplacement({0})")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"witness", "projection", "feed"})
  void postcommitFailureIsUnconfirmedAndAuthoritativeReloadKeepsTheReplacement(String cut) throws Exception {
    Path db = directory.resolve("postcommit-" + cut + ".db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open(); Path root = Files.createDirectory(directory.resolve("postcommit-root"));
      var p = skipped(queue, root, "p.txt", null); var journal = queue.listSwitchBufferOpsStrict();
      var incoming = replacement(p, p.path(), root, null);
      var witnessField = SqliteJobQueue.class.getDeclaredField("switchBufferOps"); witnessField.setAccessible(true);
      var feedField = SqliteJobQueue.class.getDeclaredField("changeStream"); feedField.setAccessible(true);
      var witness = (SqliteQueueSwitchBufferOps) witnessField.get(queue);
      var feed = (IndexingJobsChangeStream) feedField.get(queue);
      AutoCloseable subscription = () -> {};
      var failure = new IllegalStateException("owned postcommit " + cut + " fixture");
      var witnessFailure = new java.sql.SQLException("owned witness fixture");
      var feedFailure = new AssertionError("owned fatal feed fixture");
      if (cut.equals("witness")) {
        var intercepted = org.mockito.Mockito.mock(SqliteQueueSwitchBufferOps.class,
            org.mockito.AdditionalAnswers.delegatesTo(witness));
        org.mockito.Mockito.doThrow(witnessFailure).when(intercepted).acknowledgeAdmissions();
        witnessField.set(queue, intercepted);
      } else if (cut.equals("projection")) {
        var intercepted = org.mockito.Mockito.mock(IndexingJobsChangeStream.class,
            org.mockito.AdditionalAnswers.delegatesTo(feed));
        org.mockito.Mockito.doThrow(failure).when(intercepted).commitSucceeded();
        feedField.set(queue, intercepted);
      } else {
        subscription = feed.subscribe(delta -> { throw feedFailure; });
      }
      try {
        if (cut.equals("witness")) {
          var thrown = assertThrows(IllegalStateException.class,
              () -> queue.readmitNativePolicySkippedAdmissions(CANDIDATE, journal, List.of(incoming)));
          assertSame(witnessFailure, thrown.getCause());
        } else if (cut.equals("projection")) {
          assertSame(failure, assertThrows(IllegalStateException.class,
              () -> queue.readmitNativePolicySkippedAdmissions(CANDIDATE, journal, List.of(incoming))));
        } else {
          assertSame(feedFailure, assertThrows(AssertionError.class,
              () -> queue.readmitNativePolicySkippedAdmissions(CANDIDATE, journal, List.of(incoming))));
        }
      } finally {
        witnessField.set(queue, witness); feedField.set(queue, feed); subscription.close();
      }
      queue.close(); queue.open();
      var actual = queue.listSwitchBufferOpsStrict().getFirst();
      var row = queue.watcherReconciliationRow(PathNormalizer.normalizeKey(p.path()));
      assertEquals("PENDING", row.get("state"));
      assertEquals(CANDIDATE, actual.generation());
      assertTrue(actual.acceptedOrder() > p.op().acceptedOrder());
      assertNotEquals(p.op().revision(), actual.revision());
      assertEquals(row.get("unit_revision"), SwitchBufferUpsert.decode(actual.payload()).unitRevision());
      assertFalse(queue.tryMatchesNativePolicySkippedAdmission(CANDIDATE, journal, p.op(), p.row()));
      assertTrue(queue.readmitNativePolicySkippedAdmissions(CANDIDATE, journal, List.of(incoming)).isEmpty());
      assertEquals(List.of(actual), queue.listSwitchBufferOpsStrict());
    }
  }

  @Test
  void encodedWatcherPredecessorHasOnlyItsRealRootReceiptAuthority() throws Exception {
    try (var queue = new SqliteJobQueue(directory.resolve("watcher-lineage.db"))) {
      queue.open(); Path root = Files.createDirectory(directory.resolve("watcher-lineage-root"));
      var p = skipped(queue, root, "p.txt", null);
      var debt = new WatcherRootReconciliation(root.toString(), "owned directory incarnation", "default");
      assertTrue(queue.putSwitchBuffer(debt.key(), WatcherRootReconciliation.OPERATION, debt.encode()));
      var journal = queue.listSwitchBufferOpsStrict();
      var rootOp = journal.stream().filter(op -> debt.key().equals(op.key())).findFirst().orElseThrow();
      var original = new WatcherReconciliationFile(CANDIDATE, journal, rootOp,
          PathNormalizer.normalizeKey(p.path()), SourceContentHash.sha256(p.path()), Files.size(p.path()),
          true, false, p.row());
      // Exercise the existing ordinary DONE/missing-target branch: it mints the encoded revision.
      assertTrue(queue.reconcileWatcherFile(original, () -> {}, () -> fail("present source must not be deleted")));
      var claim = queue.pollPending(1).getFirst();
      assertTrue(claim.unitRevision().startsWith("watcher-reconcile-v1:"));
      assertTrue(queue.markClaimDone(claim, SKIP, null));
      var row = queue.watcherReconciliationRow(PathNormalizer.normalizeKey(p.path()));
      assertNotEquals(SwitchBufferUpsert.decode(p.op().payload()).unitRevision(), row.get("unit_revision"));
      assertFalse(queue.tryMatchesNativePolicySkippedAdmission(CANDIDATE, journal, p.op(), row));
      assertTrue(queue.readmitNativePolicySkippedAdmissions(CANDIDATE, journal,
          List.of(new NativePolicyReadmission(p.op(), row,
              JobQueue.EnqueueEntry.stat(p.path(), PROVENANCE).withinRoot(root), null))).isEmpty());
      var omitted = new WatcherReconciliationFile(CANDIDATE, journal, rootOp,
          PathNormalizer.normalizeKey(p.path()), null, 0, true, true, row, true);
      var observed = new java.util.concurrent.atomic.AtomicInteger();
      assertTrue(queue.reconcileWatcherFile(omitted, observed::incrementAndGet,
          () -> fail("omission must not mutate index")));
      assertEquals(1, observed.get());
      assertEquals(row, queue.watcherReconciliationRow(PathNormalizer.normalizeKey(p.path())));
      assertEquals(journal, queue.listSwitchBufferOpsStrict());
      assertTrue(queue.putSwitchBuffer(debt.key(), WatcherRootReconciliation.OPERATION, debt.encode()));
      assertFalse(queue.reconcileWatcherFile(omitted, () -> fail("changed receipt must refuse first"), () -> fail()));
    }
  }

  @Test
  void rootOmissionCannotBorrowAMissingUpsertButCanProveItsConditionalDeleteLineage() throws Exception {
    try (var queue = new SqliteJobQueue(directory.resolve("missing-root.db"))) {
      queue.open(); Path root = Files.createDirectory(directory.resolve("missing-root"));
      var p = skipped(queue, root, "p.txt", "documents");
      assertEquals(1, queue.deleteByExactPath(PathNormalizer.normalizeKey(p.path())));
      var debt = new WatcherRootReconciliation(root.toString(), "same owned directory incarnation", "documents");
      assertTrue(queue.putSwitchBuffer(debt.key(), WatcherRootReconciliation.OPERATION, debt.encode()));
      var journal = queue.listSwitchBufferOpsStrict();
      var receipt = journal.stream().filter(op -> debt.key().equals(op.key())).findFirst().orElseThrow();
      var missing = new WatcherReconciliationFile(CANDIDATE, journal, receipt,
          PathNormalizer.normalizeKey(p.path()), null, 0, true, true, null, true);
      assertFalse(queue.reconcileWatcherFile(missing, () -> fail("missing UPSERT is unknown"), () -> fail()));
      var deletion = new SwitchBufferDelete(PathNormalizer.normalizeKey(p.path()), SwitchBufferDelete.Origin.WATCHER,
          root.toString(), debt.rootIdentity(), "documents");
      assertTrue(queue.putSwitchBufferForGeneration(CANDIDATE, p.op().key(), "DELETE", deletion.encode()));
      assertTrue(queue.putSwitchBuffer(debt.key(), WatcherRootReconciliation.OPERATION, debt.encode()));
      var witnessed = queue.listSwitchBufferOpsStrict();
      var currentRoot = witnessed.stream().filter(op -> debt.key().equals(op.key())).findFirst().orElseThrow();
      var absent = new WatcherReconciliationFile(CANDIDATE, witnessed, currentRoot,
          PathNormalizer.normalizeKey(p.path()), null, 0, true, true, null, true);
      assertTrue(queue.reconcileWatcherFile(absent, () -> {}, () -> fail("omission never deletes")));
      assertEquals(witnessed, queue.listSwitchBufferOpsStrict());
      assertNull(queue.watcherReconciliationRow(PathNormalizer.normalizeKey(p.path())));
      // A new admitted row cannot be ignored using the earlier absent-row expectation.
      assertTrue(queue.enqueueAndBufferFileForGeneration(CANDIDATE,
          JobQueue.EnqueueEntry.stat(p.path(), PROVENANCE).withinRoot(root), "documents"));
      assertFalse(queue.reconcileWatcherFile(absent, () -> fail("replacement must refuse first"), () -> fail()));
    }
  }

}
