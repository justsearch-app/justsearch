/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.disambiguation;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FrozenEntityClusterStoreTest {
  @TempDir Path directory;

  @Test void frozenQueryLoadsRetainedClustersAndRefusesMutations() throws Exception {
    Path path = directory.resolve("entity-clusters.db");
    try (var store = new EntityClusterStore(path)) {
      store.open(); store.upsert("Witness", "PERSON", "cluster", "Witness", 1.0);
    }
    try (var mode = frozen(); var store = new EntityClusterStore(path)) {
      store.open();
      assertEquals(1, store.loadAll().size());
      assertThrows(Exception.class, () -> store.upsert("Other", "PERSON", "other", "Other", 1.0));
      assertEquals(1, store.loadAll().size());
    }
  }

  @Test void oldFutureMissingAndIncompleteClustersRefuseBeforeSchemaMutation() throws Exception {
    for (int version : new int[] {1, EntityClusterStore.CURRENT_SCHEMA_VERSION + 1}) {
      Path path = directory.resolve("entity-" + version + ".db");
      try (var store = new EntityClusterStore(path)) { store.open(); store.upsert("Witness", "PERSON", "cluster", "Witness", 1.0); }
      try (var db = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = db.createStatement()) {
        statement.execute("PRAGMA user_version=" + version);
      }
      byte[] before = Files.readAllBytes(path);
      try (var mode = frozen(); var store = new EntityClusterStore(path)) {
        assertThrows(Exception.class, store::open);
      }
      assertArrayEquals(before, Files.readAllBytes(path));
    }
    Path absent = directory.resolve("absent.db");
    try (var mode = frozen(); var store = new EntityClusterStore(absent)) { assertThrows(Exception.class, store::open); }
    assertFalse(Files.exists(absent));
    Path incomplete = directory.resolve("incomplete.db");
    try (var store = new EntityClusterStore(incomplete)) { store.open(); }
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + incomplete); var statement = db.createStatement()) {
      statement.execute("DROP TABLE entity_mentions");
    }
    byte[] before = Files.readAllBytes(incomplete);
    try (var mode = frozen(); var store = new EntityClusterStore(incomplete)) { assertThrows(Exception.class, store::open); }
    assertArrayEquals(before, Files.readAllBytes(incomplete));
  }

  static AutoCloseable frozen() {
    String eval = System.getProperty("justsearch.eval.mode"), frozen = System.getProperty("justsearch.eval.frozen");
    System.setProperty("justsearch.eval.mode", "true"); System.setProperty("justsearch.eval.frozen", "true");
    return () -> {
      if (eval == null) System.clearProperty("justsearch.eval.mode"); else System.setProperty("justsearch.eval.mode", eval);
      if (frozen == null) System.clearProperty("justsearch.eval.frozen"); else System.setProperty("justsearch.eval.frozen", frozen);
    };
  }
}
