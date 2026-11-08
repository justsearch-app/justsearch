/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FrozenOperationStoreTest {
  @TempDir Path directory;

  @Test void currentStoreOpensAndKeepsSchema() throws Exception {
    Path path = directory.resolve("operations.db");
    try (var store = new SqliteOperationStore(path)) {}
    try (var mode = frozen(); var store = new SqliteOperationStore(path)) {
      assertTrue(Files.isRegularFile(path));
    }
  }

  @Test void oldAndFutureVersionsRefuseWithoutMigrationOrQuarantine() throws Exception {
    for (int version : new int[] {1, OperationSchema.VERSION + 1}) {
      Path path = directory.resolve("operations-" + version + ".db");
      try (var store = new SqliteOperationStore(path)) {}
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
          var statement = connection.createStatement()) { statement.execute("PRAGMA user_version=" + version); }
      byte[] before = Files.readAllBytes(path);
      try (var mode = frozen()) { assertThrows(Exception.class, () -> new SqliteOperationStore(path)); }
      assertArrayEquals(before, Files.readAllBytes(path));
    }
  }

  @Test void pendingPreservationRefusesBeforeMovingAnyArtifacts() throws Exception {
    Path path = directory.resolve("operations.db");
    try (var store = new SqliteOperationStore(path)) {}
    Path pending = Files.createDirectory(directory.resolve("operations.db.corrupt-1-" + UUID.randomUUID() + ".pending"));
    byte[] before = Files.readAllBytes(path);
    try (var mode = frozen()) { assertThrows(Exception.class, () -> new SqliteOperationStore(path)); }
    assertArrayEquals(before, Files.readAllBytes(path));
    assertTrue(Files.isDirectory(pending));
    try (var children = Files.list(pending)) { assertEquals(0, children.count()); }
  }

  @Test void missingOrIncompleteCurrentSchemaRefusesWithoutCreate() throws Exception {
    Path missing = directory.resolve("missing.db");
    try (var mode = frozen()) { assertThrows(Exception.class, () -> new SqliteOperationStore(missing)); }
    assertFalse(Files.exists(missing));
    Path path = directory.resolve("operations.db");
    try (var store = new SqliteOperationStore(path)) {}
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        var statement = connection.createStatement()) { statement.execute("DROP TABLE operation_preparations"); }
    byte[] before = Files.readAllBytes(path);
    try (var mode = frozen()) { assertThrows(Exception.class, () -> new SqliteOperationStore(path)); }
    assertArrayEquals(before, Files.readAllBytes(path));
  }

  private static AutoCloseable frozen() {
    String eval = System.getProperty("justsearch.eval.mode"), frozen = System.getProperty("justsearch.eval.frozen");
    System.setProperty("justsearch.eval.mode", "true"); System.setProperty("justsearch.eval.frozen", "true");
    return () -> {
      if (eval == null) System.clearProperty("justsearch.eval.mode"); else System.setProperty("justsearch.eval.mode", eval);
      if (frozen == null) System.clearProperty("justsearch.eval.frozen"); else System.setProperty("justsearch.eval.frozen", frozen);
    };
  }
}
