/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SqliteSchema;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ImportStoreMixedUpgradeTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void independentStoreMigrationsRecoverEitherMixedUpgradeCut(boolean operationsFirst) throws Exception {
    Path operations = directory.resolve("operations.db");
    Path jobs = directory.resolve("jobs.db");
    try (var _ = new SqliteOperationStore(operations); var queue = new SqliteJobQueue(jobs)) {
      queue.open(); queue.enqueue(List.of(directory.resolve("preserved.txt")));
    }
    downgrade(operations, "operations", List.of("active_approval_binding", "interruption_revision", "interruption_reason"), 6);
    downgrade(jobs, "source_origin", List.of("restart_binding_version", "approval_binding_id", "approval_plan_hash", "predecessor_origin_id"), 27);
    if (operationsFirst) {
      try (var store = new SqliteOperationStore(operations)) { assertTrue(store.recovery().isEmpty()); }
      assertEquals(7, version(operations), "the operations migration must commit independently");
      try (var queue = failingQueue(jobs)) {
        assertThrows(SQLException.class, queue::open, "injected v28 rollback must execute");
      }
      assertEquals(27, version(jobs)); assertEquals(7, version(operations));
    } else {
      try (var queue = new SqliteJobQueue(jobs)) { queue.open(); }
      assertEquals(SqliteSchema.TARGET_VERSION, version(jobs), "the queue migration must commit independently");
      var failure = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> failingOperations(operations));
      assertInstanceOf(java.io.IOException.class, failure.getCause());
      assertEquals(6, version(operations)); assertEquals(SqliteSchema.TARGET_VERSION, version(jobs));
    }
    try (var store = new SqliteOperationStore(operations); var queue = new SqliteJobQueue(jobs)) {
      queue.open(); assertTrue(store.recovery().isEmpty()); assertEquals(1, queue.queueDepth());
      assertEquals(7, version(operations)); assertEquals(SqliteSchema.TARGET_VERSION, version(jobs));
    }
  }

  private static SqliteJobQueue failingQueue(Path path) throws Exception {
    Class<?> hook = Class.forName(SqliteJobQueue.class.getName() + "$MigrationStepHook");
    Object callback = Proxy.newProxyInstance(hook.getClassLoader(), new Class<?>[] {hook}, (proxy, method, args) -> {
      if (args != null && Integer.valueOf(28).equals(args[0])) throw new SQLException("injected mixed queue upgrade cut");
      return null;
    });
    var constructor = SqliteJobQueue.class.getDeclaredConstructor(Path.class, int.class, Runnable.class, hook);
    constructor.setAccessible(true);
    return (SqliteJobQueue) constructor.newInstance(path, 3, null, callback);
  }

  private static void failingOperations(Path path) throws Exception {
    Class<?> hook = Class.forName(SqliteOperationStore.class.getName() + "$OpenStepHook");
    Object callback = Proxy.newProxyInstance(hook.getClassLoader(), new Class<?>[] {hook}, (proxy, method, args) -> {
      if (args != null && "before-schema-commit".equals(args[0])) throw new java.io.IOException("injected mixed operations upgrade cut");
      return null;
    });
    var constructor = SqliteOperationStore.class.getDeclaredConstructor(Path.class, Clock.class, hook);
    constructor.setAccessible(true);
    try (var _ = (SqliteOperationStore) constructor.newInstance(path, Clock.systemUTC(), callback)) {
      fail("injected operations upgrade must refuse open");
    }
  }

  private static void downgrade(Path path, String table, List<String> columns, int version) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement()) {
      var existing = new java.util.HashSet<String>();
      try (var rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) { while (rows.next()) existing.add(rows.getString("name")); }
      for (String column : columns) if (existing.contains(column)) statement.execute("ALTER TABLE " + table + " DROP COLUMN " + column);
      statement.execute("PRAGMA user_version=" + version);
    }
  }

  private static int version(Path path) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA user_version")) {
      assertTrue(rows.next()); return rows.getInt(1);
    }
  }
}
