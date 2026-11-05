/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.indexing.SourceOrigin;
import io.justsearch.app.api.operations.OperationAuthorizationBasis;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SourceOriginLookupTest {
  @TempDir Path temporary;

  @Test
  void importsResolveOnlyTheirOperationAndNormalizedRootAfterReopen() throws Exception {
    Path db = temporary.resolve("imports.db");
    Path root = temporary.resolve("Root");
    var one = origin(SourceOrigin.Kind.IMPORT, root, "operation-one", 1);
    var two = origin(SourceOrigin.Kind.IMPORT, root, "operation-two", 2);
    var otherRoot = origin(SourceOrigin.Kind.IMPORT, temporary.resolve("other"), "operation-one", 1);
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.recordSourceOrigins(List.of(one, two, otherRoot));
    }
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertEquals(Optional.of(one.id()), lookup(queue, claim(root.resolve("a.txt"), root.resolve("child/.."), "operation-one", 1L)));
      assertEquals(Optional.of(two.id()), lookup(queue, claim(root.resolve("a.txt"), root, "operation-two", 1L)));
      assertEquals(Optional.empty(), lookup(queue, claim(root.resolve("a.txt"), root, "pre-upgrade", 1L)));
      assertEquals(Optional.empty(), lookup(queue, claim(root.resolve("a.txt"), root, null, null)), "imports are never guessed by root alone");
      // SQLite admissions use this key, including Windows case folding; declarations keep spelling.
      Path queuedRoot = Path.of(io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(root));
      assertEquals(Optional.of(one.id()), lookup(queue, claim(queuedRoot.resolve("a.txt"), queuedRoot, "operation-one", 1L)));
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var query = connection.createStatement()) {
        try (var rows = query.executeQuery("PRAGMA user_version")) { assertTrue(rows.next()); assertEquals(28, rows.getInt(1)); }
        try (var rows = query.executeQuery("SELECT count(*) FROM source_origin")) { assertTrue(rows.next()); assertEquals(3, rows.getInt(1)); }
      }
    }
  }

  @Test
  void watchedAdmissionsUseDeclaredRootAndMissingRecordsStayAbsent() throws Exception {
    Path db = temporary.resolve("watched.db");
    Path root = temporary.resolve("Root");
    var watched = origin(SourceOrigin.Kind.WATCHED, root, "watch-operation", 1);
    var nested = origin(SourceOrigin.Kind.WATCHED, root.resolve("nested"), "nested-operation", 2);
    try (var queue = new SqliteJobQueue(db)) {
      queue.open(); queue.recordSourceOrigins(List.of(watched, nested));
      assertEquals(Optional.of(watched.id()), lookup(queue, claim(root.resolve("nested/a.txt"), root, null, null)));
      assertEquals(Optional.of(nested.id()), lookup(queue, claim(root.resolve("nested/a.txt"), root.resolve("nested"), null, null)));
      Path queuedRoot = Path.of(io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(root));
      assertEquals(Optional.of(watched.id()), lookup(queue, claim(queuedRoot.resolve("a.txt"), queuedRoot, null, null)));
      assertEquals(Optional.empty(), lookup(queue, claim(root.resolve("a.txt"), root.resolve("a.txt"), null, null)), "exact-file admission creates no origin");
      assertEquals(Optional.empty(), lookup(queue, claim(temporary.resolve("legacy/a.txt"), temporary.resolve("legacy"), null, null)));
      assertEquals(Optional.empty(), lookup(queue, claim(root.resolve("a.txt"), root, "pre-upgrade-import", 1L)));
    }
  }

  private static SourceOrigin origin(SourceOrigin.Kind kind, Path root, String operation, long time) {
    var approval = new SourceOrigin.Approval(new OperationAuthorizationBasis.StructuralAuto().encode(),
        "system_internal", "trusted", "CLI", operation, operation, SourceOrigin.ApprovalClass.DURABLE,
        SourceOrigin.EvidenceSource.GATE);
    return SourceOrigin.accepted(kind, SourceOrigin.watchedRoot(root, "documents"), approval, time);
  }

  private static JobQueue.IndexJob claim(Path file, Path root, String operation, Long epoch) {
    return new JobQueue.IndexJob(file, "documents", null, operation, null, epoch, false, null, root);
  }

  private static Object lookup(JobQueue queue, JobQueue.IndexJob claim) {
    return assertDoesNotThrow(() -> JobQueue.class.getMethod("sourceOriginId", JobQueue.IndexJob.class).invoke(queue, claim),
        "publication must resolve the durable origin of an admission");
  }
}
