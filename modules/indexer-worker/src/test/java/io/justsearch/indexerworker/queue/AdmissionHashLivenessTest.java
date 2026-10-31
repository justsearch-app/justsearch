/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.indexerworker.loop.SourceContentHash;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class AdmissionHashLivenessTest {
  @TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(strings = {"enqueue", "maintenance", "retry", "reappeared", "recorded"})
  void blockedSourceReadDoesNotReserveClaimsOrCompletions(String route) throws Exception {
    Path completed = Files.writeString(temporary.resolve("completed.txt"), "complete");
    Path pending = Files.writeString(temporary.resolve("pending.txt"), "claim");
    Path slow = Files.writeString(temporary.resolve("slow.txt"), "slow source");
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var queue = new SqliteJobQueue(temporary.resolve("queue.db"));
        var executor = Executors.newFixedThreadPool(2)) {
      queue.open();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(completed).withinRoot(temporary)));
      var claim = queue.pollPending(1).getFirst();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(pending).withinRoot(temporary)));
      String walkKey = "01900000-0000-7000-8000-000000000062";
      var walk = queue.beginRecordedWalk(walkKey, "a".repeat(64), true);
      var admission = executor.submit(() -> {
        try (var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
          hashes.when(() -> SourceContentHash.sha256(eq(slow), any())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS), "test must release its source read");
            return call.callRealMethod();
          });
          var entry = JobQueue.EnqueueEntry.stat(slow).withinRoot(temporary);
          return switch (route) {
            case "recorded" -> queue.enqueueRecordedEntries(
                walkKey, walk.enumerationEpoch(), List.of(entry), null);
            case "enqueue" -> queue.enqueueEntries(List.of(entry));
            case "maintenance" -> queue.enqueueMaintenanceScanEntries(List.of(entry), null, null);
            case "retry" -> queue.reenqueue(entry).accepted();
            case "reappeared" -> queue.enqueueReappearedFile(entry, null) ? 1 : 0;
            default -> throw new AssertionError(route);
          };
        }
      });
      try {
        assertTrue(entered.await(10, TimeUnit.SECONDS), "admission must reach the real source read");
        var progress = executor.submit(() -> {
          assertTrue(queue.markClaimDone(claim, null, null));
          assertEquals(pending, queue.pollPending(1).getFirst().path());
        });
        assertDoesNotThrow(() -> progress.get(2, TimeUnit.SECONDS),
            "a blocked admission hash must not block completion or claiming on the same queue");
      } finally {
        release.countDown();
      }
      assertEquals(1, admission.get(10, TimeUnit.SECONDS));
    }
  }

  @ParameterizedTest
  @CsvSource({"recorded,hash", "recorded,lock", "maintenance,hash", "maintenance,lock",
      "enqueue,hash", "enqueue,lock", "enumeration,hash", "enumeration,lock",
      "watcher,hash", "watcher,lock", "enumeration-planned,lock", "watcher-planned,lock"})
  void equalMetadataRewriteCannotCertifyAnEarlierHash(String route, String interval) throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("unix"));
    Path file = Files.writeString(temporary.resolve("rewritten.txt"), "before");
    var stamp = Files.getLastModifiedTime(file);
    Path db = temporary.resolve("rewrite.db");
    boolean candidate = route.startsWith("enumeration") || route.startsWith("watcher");
    try (var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW);
        var executor = Executors.newSingleThreadExecutor()) {
      queue.open();
      var entry = route.endsWith("-planned")
          ? new JobQueue.EnqueueEntry(file, Files.size(file), null, SourceContentHash.sha256(file), temporary)
          : JobQueue.EnqueueEntry.stat(file).withinRoot(temporary);
      if (candidate) {
        assertEquals(1, queue.enqueueEnumeratedFilesForGeneration("green", List.of(entry), null));
        assertTrue(queue.markClaimDone(queue.pollPending(1).getFirst(), null, null));
      } else if (route.equals("maintenance")) {
        assertEquals(1, queue.enqueueEntries(List.of(entry)));
        execute(db, "UPDATE jobs SET state='FAILED',attempts=5");
      }
      String priorRevision = value(db, "unit_revision");
      var walk = queue.beginRecordedWalk("01900000-0000-7000-8000-000000000064", "a".repeat(64), true);
      var field = SqliteJobQueue.class.getDeclaredField("lock");
      field.setAccessible(true);
      var lock = (ReentrantLock) field.get(queue);
      var admissionThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
      lock.lock();
      java.util.concurrent.Future<Integer> admission;
      try {
        admission = executor.submit(() -> {
          admissionThread.set(Thread.currentThread());
          try (var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
            if (interval.equals("hash")) {
              if (candidate) {
                hashes.when(() -> SourceContentHash.sha256(file)).thenAnswer(call -> {
                  String hash = (String) call.callRealMethod();
                  rewrite(file, stamp);
                  return hash;
                });
              } else {
                hashes.when(() -> SourceContentHash.admissionIdentity(file, temporary)).thenAnswer(call -> {
                  String hash = (String) call.callRealMethod();
                  rewrite(file, stamp);
                  return hash;
                });
              }
            }
            return switch (route) {
              case "recorded" -> queue.enqueueRecordedEntries("01900000-0000-7000-8000-000000000064", walk.enumerationEpoch(), List.of(entry), null);
              case "maintenance" -> queue.enqueueMaintenanceScanEntries(List.of(entry), null, null);
              case "enqueue" -> queue.enqueueEntries(List.of(entry));
              case "enumeration", "enumeration-planned" -> queue.enqueueEnumeratedFilesForGeneration("green", List.of(entry), null);
              case "watcher", "watcher-planned" -> queue.enqueueMaintenanceFileForGeneration("green", entry, null) ? 1 : 0;
              default -> throw new AssertionError(route);
            };
          }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((admissionThread.get() == null || !lock.hasQueuedThread(admissionThread.get()))
            && !admission.isDone() && System.nanoTime() < deadline) Thread.sleep(5);
        assertFalse(admission.isDone(), "admission must reach the held queue lock");
        assertTrue(lock.hasQueuedThread(admissionThread.get()), "hashing must finish before admission locks");
        if (interval.equals("lock")) rewrite(file, stamp);
      } finally {
        lock.unlock();
      }
      assertEquals(1, admission.get(10, TimeUnit.SECONDS), "uncertain identity must leave retryable work");
      assertEquals("PENDING", value(db, "state"));
      assertNotEquals(priorRevision, value(db, "unit_revision"), "stale coverage must not preserve old work");
      assertNull(value(db, "admitted_source_sha256"), "invalidated bytes cannot become source proof");
      assertFalse(queue.pollPending(1).isEmpty(), "the changed file must remain claimable");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void unavailableChangeTimeStillChecksWitnessAroundHashing(boolean mutate) throws Exception {
    Path file = Files.writeString(temporary.resolve("portable.txt"), "before");
    Path db = temporary.resolve("portable.db");
    // Initialize the hashing classes before mocking the filesystem's optional attribute.
    assertNotNull(SourceContentHash.admissionIdentity(file, temporary));
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      try (var files = mockStatic(Files.class, CALLS_REAL_METHODS);
          var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
        files.when(() -> Files.getAttribute(file, "unix:ctime", java.nio.file.LinkOption.NOFOLLOW_LINKS))
            .thenThrow(new UnsupportedOperationException("filesystem has no unix attribute view"));
        if (mutate) {
          hashes.when(() -> SourceContentHash.admissionIdentity(file, temporary)).thenAnswer(call -> {
            String hash = (String) call.callRealMethod();
            Files.writeString(file, "changed size after hashing");
            return hash;
          });
        }
        assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(temporary))));
        if (mutate) assertNull(value(db, "admitted_source_sha256"));
        else assertEquals(SourceContentHash.sha256(file), value(db, "admitted_source_sha256"));
      }
    }
  }

  private static void rewrite(Path file, java.nio.file.attribute.FileTime stamp) throws Exception {
    Files.writeString(file, "after!");
    Files.setLastModifiedTime(file, stamp);
  }

  private static void execute(Path db, String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement()) { statement.executeUpdate(sql); }
  }

  private static String value(Path db, String column) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var query = connection.createStatement(); var row = query.executeQuery("SELECT " + column + " FROM jobs")) {
      return row.next() ? row.getString(1) : null;
    }
  }

  @Test
  void recordedAdmissionRejectsSourceProofInvalidatedBeforeItsTransaction() throws Exception {
    Path file = Files.writeString(temporary.resolve("changing.txt"), "before");
    Path db = temporary.resolve("changing.db");
    try (var queue = new SqliteJobQueue(db);
        var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
      queue.open();
      String key = "01900000-0000-7000-8000-000000000063";
      var walk = queue.beginRecordedWalk(key, "a".repeat(64), true);
      hashes.when(() -> SourceContentHash.admissionIdentity(file, temporary)).thenAnswer(call -> {
        String observed = (String) call.callRealMethod();
        assertNotNull(observed);
        Files.writeString(file, "changed after the source observation");
        return observed;
      });
      assertEquals(1, queue.enqueueRecordedEntries(key, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(temporary)), null));
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
          var query = connection.createStatement();
          var row = query.executeQuery("SELECT admitted_source_sha256 FROM jobs")) {
        assertTrue(row.next());
        assertNull(row.getString(1), "changed metadata must invalidate the earlier source observation");
      }
    }
  }
}
