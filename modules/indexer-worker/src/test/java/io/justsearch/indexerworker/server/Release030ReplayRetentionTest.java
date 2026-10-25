/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** C4: recorded authority, settled native effects and retention of unfinished recovery evidence. */
@Timeout(180)
@org.junit.jupiter.api.extension.ExtendWith(
    io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030ReplayRetentionTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void recordedVolumeOutranksLegacyExactAuthority(boolean watchedRoot, @TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    Path file = root.resolve("explicit.txt"), held = root.resolve("held-explicit.txt");
    Files.move(file, held);
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          stopRecovery(server);
          assertTrue(queue(server).isRelease030ExactFile(file));
          assertTrue(search(server, "apricot").contains(key(file)));
          // Valid persisted evidence for a different volume, as in the existing U11 checks.
          // Holding the watch prevents a fresh discovery from replacing the original admission.
          Path ledger = root.resolve("data/index/default/root-volumes.properties");
          var volumes = new Properties();
          try (var in = Files.newInputStream(ledger)) { volumes.load(in); }
          volumes.setProperty((watchedRoot ? "" : "explicit-file-folder|") + key(root), "windows-volume:123abc");
          try (var out = Files.newOutputStream(ledger)) { volumes.store(out, "known volume evidence"); }
          if (watchedRoot) holdWatch(server, root);
        }
        Files.writeString(file, "replacedvolumecobalt");
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          stopRecovery(server);
          if (watchedRoot) holdWatch(server, root);
          assertTrue(queue(server).isRelease030ExactFile(file), "original admission has not been renewed");
          assertFalse(server.appServices().ingestService().rootCheck().admitsFile(root),
              "the known volume evidence refuses these bytes");
          activate(server);
          await(() -> count(root, "path='" + key(file) + "' AND (state='DONE' OR retry_after>0)") == 1,
              "the queued file reaches its admission decision", 20);
          assertTrue(search(server, "apricot").contains(key(file)),
              "C4-1: legacy authority cannot replace good content against recorded volume evidence");
          assertTrue(search(server, "replacedvolumecobalt").isEmpty());
          assertEquals(0, count(root, "path='" + key(file) + "' AND state='DONE'"));
        }
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  @ParameterizedTest
  @CsvSource({"migrating,false,false", "switching,false,false", "migrating,true,false", "switching,true,false", "migrating,false,true"})
  void completedLegacyEffectIsSettledThroughNativeReplay(String fixture, boolean restart, boolean retention,
      @TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpackNativeMigration(fixture, temporary);
    Path file = Files.writeString(root.resolve("native-receipt.txt"), "settlednativecobalt");
    rawAdmission(root, file, "PENDING");
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root, true);
      try (models) {
        String revision;
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          stopMonitor(server); // Hold only the monitor, never change the fixture's saved state.
          Release030ExactFileRecoveryTest.activateNative(server);
          resume(server);
          await(() -> count(root, "path='" + key(file) + "' AND state='DONE'") == 1,
              "the original legacy admission finishes on Green", 30);
          var green = (RunningRuntime) field(server, "ingestLifecycle");
          green.commitOps().commitAndTrack(CommitReason.DRAIN);
          green.commitOps().maybeRefreshBlocking();
          assertEquals(text(root, file, "content_hash"),
              green.documentFieldOps().getDocumentFieldOrThrow(key(file), SchemaFields.SOURCE_SHA256),
              "the completed receipt proves this exact Green effect");
          revision = text(root, file, "unit_revision");
          assertTrue(revision.startsWith("release030/"));
          assertEquals(1, bufferCount(root, file), "the original raw UPSERT awaits final replay");
          if (retention) {
            long old = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(40);
            sql(root, "UPDATE jobs SET last_updated=" + old + " WHERE path='" + key(file) + "'");
            queue(server).cleanupOldJobs(30);
            assertEquals(revision, text(root, file, "unit_revision"),
                "C4-3: ordinary completed original admissions remain evidence until native replay settles");
          }
          if (!restart) finishNative(server, root, file, revision);
        }
        if (restart) {
          try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
            Release030ExactFileRecoveryTest.activateNative(server);
            resume(server);
            assertEquals(revision, text(root, file, "unit_revision"),
                "C4-2: boot replay cannot renew an already-applied original admission");
            finishNative(server, root, file, revision);
          }
        }
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  @Test
  void routineRetentionKeepsFailedCutoverReceiptUntilRecoveryCompletes(@TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpackNativeMigration("switching", temporary);
    Path file = root.resolve("retained-debt.txt"), ordinary = root.resolve("ordinary-retention.txt");
    rawAdmission(root, file, "RETRY_EXHAUSTED");
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root, true);
      try (models) {
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          stopMonitor(server);
          Release030ExactFileRecoveryTest.activateNative(server);
          resume(server);
          await(() -> count(root, "path='" + key(file) + "' AND retry_after>0") == 1,
              "the missing original exhausted file retains debt", 20);
          expireRealCutover(server);
          assertEquals("FAILED", state(root).migration_state());
          Files.writeString(file, "retainedreceiptcobalt");
          await(() -> count(root, "path='" + key(file) + "' AND state='DONE'") == 1,
              "the returned debt finishes on retained Green while FAILED", 90);
          assertTrue(text(root, file, "unit_revision").startsWith("release030/debt/"));
          assertTrue(Files.exists(checkpoint(root)));
          queue(server).enqueue(java.util.List.of(ordinary));
          queue(server).markDone(ordinary);
          long old = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(40);
          sql(root, "UPDATE jobs SET last_updated=" + old + " WHERE path IN ('" + key(file) + "','" + key(ordinary) + "')");
          queue(server).cleanupOldJobs(30); // The same operation the daily Engine cleanup invokes.
          assertEquals(1, count(root, "path='" + key(file) + "' AND state='DONE'"),
              "C4-3: normal retention must keep the receipt required by unfinished recovery");
          assertEquals(0, count(root, "path='" + key(ordinary) + "'"), "ordinary retention still runs");
        }
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          Release030ExactFileRecoveryTest.activateNative(server);
          resume(server);
          await(() -> "IDLE".equals(state(root).migration_state())
                  && search(server, "retainedreceiptcobalt").contains(key(file)),
              "retained evidence automatically recovers the real failed native migration", 30);
          await(() -> !Files.exists(checkpoint(root)), "root recovery and exact debt finish", 30);
          sql(root, "UPDATE jobs SET last_updated=0 WHERE path='" + key(file) + "'");
          queue(server).cleanupOldJobs(30);
          assertEquals(0, count(root, "path='" + key(file) + "'"), "completed recovery releases retention");
          assertTrue(search(server, "retainedreceiptcobalt").contains(key(file)), "retention removes no indexed content");
        }
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  @ParameterizedTest
  @ValueSource(strings = {"same", "inherited-collection", "inherited-collection-unchanged", "non-normal-path", "different-hash", "different-id", "different-path", "different-collection", "missing", "unreadable", "policy-applied", "policy-unapplied", "unchanged", "unchanged-different-time", "unchanged-missing-evidence", "unchanged-stale-receipt"})
  void unscopedReplaySettlesOnlyAProvenEffect(String projection, @TempDir Path temporary) throws Exception {
    Path database = temporary.resolve("jobs.db"), file = temporary.resolve("ordinary.txt");
    String hash = "a".repeat(64), id = key(file);
    try (var queue = new SqliteJobQueue(database)) {
      queue.open();
      queue.enqueueEntries(java.util.List.of(
          io.justsearch.indexerworker.queue.JobQueue.EnqueueEntry.stat(file, null).withinRoot(file)), projection.startsWith("inherited-") ? "notes" : null);
      // The durable request precedes its completed effect, as in historical native replay.
      assertTrue(queue.putSwitchBuffer("path:" + id, "UPSERT",
          projection.equals("non-normal-path") ? temporary.resolve("unused/../ordinary.txt").toString() : id));
      boolean policy = projection.startsWith("policy-");
      if (projection.startsWith("unchanged") || projection.equals("inherited-collection-unchanged")) {
        var claim = queue.pollPending(1).getFirst();
        var outcome = io.justsearch.indexerworker.ingest.IngestionOutcome.of(
            io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SKIPPED_POLICY,
            io.justsearch.indexerworker.ingest.IngestionReasonCodes.UNCHANGED,
            io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE, "Index already current");
        queue.markClaimDone(claim, outcome,
            projection.equals("unchanged-missing-evidence") ? null
                : new io.justsearch.indexerworker.queue.JobQueue.IngestionLedgerEntry(
                    null, null, 1L, 123L, "FILE", null, null, null));
        if (projection.equals("unchanged-stale-receipt")) {
          queue.enqueueEntries(java.util.List.of(
              io.justsearch.indexerworker.queue.JobQueue.EnqueueEntry.stat(file, null).withinRoot(file)), null);
          // Reuse the exact outcome timestamp: an older receipt cannot supply missing evidence
          // to a later admission, even when two completions occur in the same millisecond.
          queue.markClaimDone(queue.pollPending(1).getFirst(), outcome, null);
        }
      } else if (policy) {
        queue.markDone(file, io.justsearch.indexerworker.ingest.IngestionOutcome.of(
            io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SKIPPED_POLICY,
            io.justsearch.indexerworker.ingest.IngestionReasonCodes.SKIPPED_TEMP_OR_SYSTEM,
            io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE, "Current policy excludes this source"));
      } else {
        queue.markDone(file);
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + database); var s = c.createStatement()) {
          s.executeUpdate("UPDATE jobs SET content_hash='" + hash + "'");
        }
      }
      var runtime = org.mockito.Mockito.mock(RunningRuntime.class);
      var fields = org.mockito.Mockito.mock(io.justsearch.adapters.lucene.runtime.DocumentFieldOps.class);
      org.mockito.Mockito.when(runtime.documentFieldOps()).thenReturn(fields);
      org.mockito.Mockito.when(runtime.commitOps()).thenReturn(
          org.mockito.Mockito.mock(io.justsearch.adapters.lucene.runtime.CommitOps.class));
      var counts = org.mockito.Mockito.mock(io.justsearch.adapters.lucene.runtime.IndexCountOps.class);
      org.mockito.Mockito.when(runtime.indexCountOps()).thenReturn(counts);
      org.mockito.Mockito.when(counts.countByIdAndChunksStrict(id))
          .thenReturn(projection.equals("policy-unapplied") ? 1 : 0);
      org.mockito.Mockito.when(fields.getDocumentFieldOrThrow(id, SchemaFields.MODIFIED_AT))
          .thenReturn(projection.equals("unchanged-different-time") ? "124" : "123");
      if (projection.equals("unreadable")) {
        org.mockito.Mockito.when(fields.getDocumentFieldOrThrow(id, SchemaFields.SOURCE_SHA256))
            .thenThrow(new java.io.IOException("reader unavailable"));
      } else if (!projection.equals("missing") && !projection.equals("policy-applied")) {
        org.mockito.Mockito.when(fields.getDocumentFieldOrThrow(id, SchemaFields.SOURCE_SHA256))
            .thenReturn(projection.equals("different-hash") ? "b".repeat(64) : hash);
        org.mockito.Mockito.when(fields.getDocumentFieldOrThrow(id, SchemaFields.DOC_ID))
            .thenReturn(projection.equals("different-id") ? id + ".other" : id);
        org.mockito.Mockito.when(fields.getDocumentFieldOrThrow(id, SchemaFields.PATH))
            .thenReturn(projection.equals("different-path") ? id + ".other" : id);
        org.mockito.Mockito.when(fields.getDocumentFieldOrThrow(id, SchemaFields.COLLECTION))
            .thenReturn(projection.startsWith("inherited-") ? "notes" : projection.equals("different-collection") ? "other" : null);
      }
      KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
          queue, runtime, null, io.justsearch.indexerworker.loop.pacing.IndexingPacing.unthrottled(),
          temporary, temporary, new tools.jackson.databind.ObjectMapper(), () -> false, () -> true,
          org.slf4j.LoggerFactory.getLogger(getClass()), Long.MAX_VALUE, "g-native"));
      boolean settled = projection.startsWith("inherited-") || projection.equals("same") || projection.equals("non-normal-path")
          || projection.equals("unreadable") || projection.equals("policy-applied") || projection.equals("unchanged");
      try (var c = DriverManager.getConnection("jdbc:sqlite:" + database); var s = c.createStatement();
          var row = s.executeQuery("SELECT state FROM jobs WHERE path='" + id + "'")) {
        assertTrue(row.next());
        assertEquals(settled ? "DONE" : "PENDING", row.getString(1),
            "C4-2: a settled effect is not re-admitted; a missing or different effect is not settled");
      }
      try (var c = DriverManager.getConnection("jdbc:sqlite:" + database); var s = c.createStatement();
          var row = s.executeQuery("SELECT count(*) FROM jobs WHERE state='PENDING'")) {
        assertTrue(row.next());
        assertEquals(settled ? 0 : 1, row.getInt(1), "C4-2: no duplicate alias admission is created");
      }
      assertEquals(1, queue.listSwitchBufferOpsStrict().size(), "best-effort replay retains its durable version");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void committedNativeFileRecoveredAsUnchangedKeepsItsAdmission(boolean retention, @TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpackNativeMigration("migrating", temporary);
    Path file = Files.writeString(root.resolve("unchanged-native.txt"), "unchangednativecobalt");
    rawAdmission(root, file, "PENDING");
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root, true);
      try (models) {
        String revision;
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          stopMonitor(server);
          var queue = queue(server);
          var connection = SqliteJobQueue.class.getDeclaredField("connection"); connection.setAccessible(true);
          var original = (java.sql.Connection) connection.get(queue);
          var blocked = new AtomicInteger();
          var proxy = java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),
              new Class<?>[] {java.sql.Connection.class}, (ignored, method, arguments) -> {
                if (method.getName().equals("prepareStatement") && arguments[0] instanceof String sql) {
                  String compact = sql.replaceAll("\\s+", "");
                  if (compact.contains("state='DONE'") && compact.contains("content_hash=?")) {
                    blocked.incrementAndGet(); throw new java.sql.SQLException("Queue completion unavailable after index commit");
                  }
                }
                try { return method.invoke(original, arguments); }
                catch (java.lang.reflect.InvocationTargetException failed) { throw failed.getCause(); }
              });
          connection.set(queue, proxy);
          Release030ExactFileRecoveryTest.activateNative(server);
          resume(server);
          var green = (RunningRuntime) field(server, "ingestLifecycle");
          await(() -> blocked.get() > 0 && hasSourceHash(green, file), "the real index commit precedes failed queue completion", 30);
          assertEquals(1, count(root, "path='" + key(file) + "' AND state IN ('PENDING','PROCESSING')"));
          assertEquals(null, text(root, file, "content_hash"));
          revision = text(root, file, "unit_revision");
          // Keep the completion fault installed through shutdown, leaving the committed index
          // and unfinished admission for the real next boot to reconcile. No state-file edits.
        }
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          stopMonitor(server);
          Release030ExactFileRecoveryTest.activateNative(server);
          resume(server);
          await(() -> count(root, "path='" + key(file) + "' AND state='DONE' AND last_reason_code='UNCHANGED'") == 1,
              "recovery uses the unchanged fast path against the real committed Green", 30);
          assertEquals(null, text(root, file, "content_hash"), "UNCHANGED is a freshness receipt, not another write");
          if (retention) {
            assertEquals(1, bufferCount(root, file), "the actual native replay still needs this receipt");
            assertTrue(Files.exists(checkpoint(root)));
            long observed = Long.parseLong(text(root, file, "last_outcome_at"));
            long old = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(40);
            sql(root, "UPDATE ingestion_ledger SET observed_at=" + old + " WHERE path_hash='" + pathHash(file) + "' AND observed_at=" + observed);
            sql(root, "UPDATE jobs SET last_updated=" + old + ",last_outcome_at=" + old + " WHERE path='" + key(file) + "'");
            Path control = root.resolve("unrelated-audit.txt");
            queue(server).recordIngestionEvent(control, io.justsearch.indexerworker.ingest.IngestionOutcome.of(
                io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SKIPPED_POLICY,
                io.justsearch.indexerworker.ingest.IngestionReasonCodes.SKIPPED_TEMP_OR_SYSTEM,
                io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE, "Unrelated audit control"), null);
            sql(root, "UPDATE ingestion_ledger SET observed_at=" + (old - 1) + " WHERE path_hash='" + pathHash(control) + "'");
            queue(server).cleanupOldJobs(30);
            assertEquals(revision, text(root, file, "unit_revision"), "C4-3: retain the original unchanged admission");
            queue(server).cleanupOldLedgerEvents(30);
            assertEquals(1, ledgerCount(root, file, old), "C4-3: retain the atomic unchanged proof until replay settles");
            assertEquals(0, ledgerCount(root, control, old - 1), "unrelated audit retention still runs");
          }
          invoke(server, "startMigrationCutoverMonitorBestEffort");
          await(() -> "IDLE".equals(state(root).migration_state()) || !revision.equals(text(root, file, "unit_revision")),
              "final replay judges the unchanged receipt", 30);
          assertEquals(revision, text(root, file, "unit_revision"), "C4-2: an applied UNCHANGED admission is not renewed");
          await(() -> "IDLE".equals(state(root).migration_state())
                  && search(server, "unchangednativecobalt").contains(key(file)), "the real unchanged native effect stays searchable", 30);
          if (retention) {
            await(() -> !Files.exists(checkpoint(root)), "native recovery releases retained receipts", 30);
            long observed = Long.parseLong(text(root, file, "last_outcome_at"));
            queue(server).cleanupOldLedgerEvents(30);
            assertEquals(0, ledgerCount(root, file, observed), "settled audit evidence returns to normal retention");
            queue(server).cleanupOldJobs(30);
            assertNull(text(root, file, "unit_revision"), "settled original jobs return to normal retention");
            assertTrue(search(server, "unchangednativecobalt").contains(key(file)));
          }
        }
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  private static String pathHash(Path file) throws Exception {
    return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(key(file).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  private static long ledgerCount(Path root, Path file, long observed) throws Exception {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("data/jobs.db"));
        var q = c.prepareStatement("SELECT count(*) FROM ingestion_ledger WHERE path_hash=? AND observed_at=?")) {
      q.setString(1, pathHash(file)); q.setLong(2, observed);
      try (var row = q.executeQuery()) { row.next(); return row.getLong(1); }
    }
  }

  private static boolean hasSourceHash(RunningRuntime runtime, Path file) {
    try { return runtime.documentFieldOps().getDocumentFieldOrThrow(key(file), SchemaFields.SOURCE_SHA256) != null; }
    catch (java.io.IOException unavailable) { return false; }
  }

  private static void finishNative(KnowledgeServer server, Path root, Path file, String revision) throws Exception {
    invoke(server, "startMigrationCutoverMonitorBestEffort");
    await(() -> "IDLE".equals(state(root).migration_state())
            || !revision.equals(text(root, file, "unit_revision")), "native final replay runs", 30);
    assertEquals(revision, text(root, file, "unit_revision"),
        "C4-2: final replay must settle the applied effect instead of revoking its admission");
    await(() -> "IDLE".equals(state(root).migration_state())
            && search(server, "settlednativecobalt").contains(key(file)),
        "native promotion finishes with the already-applied content searchable", 30);
    assertEquals(0, bufferCount(root, file));
  }

  private static void expireRealCutover(KnowledgeServer server) throws Exception {
    var manager = (IndexGenerationManager) field(server, "indexGenerationManager");
    var expired = new AtomicInteger();
    Thread.sleep(20);
    KnowledgeServerMigrationOps.runMigrationCutoverLoop(new KnowledgeServerMigrationOps.CutoverContext(
        manager, queue(server), () -> true, () -> true, () -> null, Long.MAX_VALUE, 0L, 0,
        () -> (io.justsearch.adapters.lucene.runtime.LuceneRuntime) uncheckedField(server, "ingestLifecycle"),
        () -> true, () -> { expired.incrementAndGet(); invoke(server, "drainSwitchBufferBestEffort"); },
        org.slf4j.LoggerFactory.getLogger(Release030ReplayRetentionTest.class), observed -> {
          try {
            var method = KnowledgeServer.class.getDeclaredMethod("enterSwitchingWithMutationAdmission", IndexGenerationManager.State.class);
            method.setAccessible(true); method.invoke(server, observed);
          } catch (ReflectiveOperationException failure) { throw new java.io.IOException(failure); }
        }, () -> { throw new AssertionError("expired cutover cannot promote"); }));
    assertEquals(1, expired.get(), "the real native deadline branch ran");
  }

  private static void rawAdmission(Path root, Path file, String state) throws Exception {
    sql(root, "INSERT INTO jobs(path,state,attempts,last_updated) VALUES ('" + key(file) + "','" + state + "'," + (state.equals("PENDING") ? 0 : 6) + ",0)");
    sql(root, "INSERT INTO switch_buffer(key,op,payload,last_updated) VALUES ('path:" + key(file) + "','UPSERT','" + key(file) + "',0)");
  }
  private static String text(Path root, Path file, String column) {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("data/jobs.db"));
        var q = c.prepareStatement("SELECT " + column + " FROM jobs WHERE path=?")) {
      q.setString(1, key(file)); try (var r = q.executeQuery()) { return r.next() ? r.getString(1) : null; }
    } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
  }
  private static long bufferCount(Path root, Path file) throws Exception {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("data/jobs.db"));
        var q = c.prepareStatement("SELECT count(*) FROM switch_buffer WHERE key=?")) {
      q.setString(1, "path:" + key(file)); try (var r = q.executeQuery()) { r.next(); return r.getLong(1); }
    }
  }
  private static void holdWatch(KnowledgeServer server, Path root) {
    assertFalse(server.appServices().ingestService().watchRoot(io.justsearch.ipc.WatchRootRequest.newBuilder()
        .setRootPath(root.toString()).build(), CallContext.none()).getWatching());
    assertTrue(server.appServices().ingestService().refusesRootVolume(root.toString(), CallContext.none()));
  }
  private static void resume(KnowledgeServer server) {
    var response = server.appServices().ingestService().resumeMigration(
        io.justsearch.ipc.MigrationResumeRequest.getDefaultInstance(), CallContext.none());
    assertTrue(response.getAccepted(), response.getError());
  }
  private static void stopMonitor(KnowledgeServer server) throws Exception {
    if (field(server, "migrationCutoverThread") instanceof Thread t) {
      t.interrupt(); t.join(10_000); assertFalse(t.isAlive());
    }
  }
  private static void stopRecovery(KnowledgeServer server) throws Exception {
    if (field(server, "release030RecoveryTask") instanceof java.util.concurrent.Future<?> task) task.cancel(false);
  }
  private static Path checkpoint(Path root) { return root.resolve("data/upgrade-030-recovery.properties"); }
  private static SqliteJobQueue queue(KnowledgeServer server) throws Exception { return (SqliteJobQueue) field(server, "jobQueue"); }
  private static Object field(KnowledgeServer server, String name) throws Exception {
    var f = KnowledgeServer.class.getDeclaredField(name); f.setAccessible(true); return f.get(server);
  }
  private static Object uncheckedField(KnowledgeServer server, String name) {
    try { return field(server, name); } catch (Exception failure) { throw new IllegalStateException(failure); }
  }
  private static void invoke(KnowledgeServer server, String name) {
    try { var m = KnowledgeServer.class.getDeclaredMethod(name); m.setAccessible(true); m.invoke(server); }
    catch (Exception failure) { throw new IllegalStateException(failure); }
  }
}
