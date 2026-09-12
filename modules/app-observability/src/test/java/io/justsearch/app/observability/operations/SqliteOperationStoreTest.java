/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqliteOperationStoreTest {
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-12T09:00:00Z"), ZoneOffset.UTC);
  @TempDir Path temp;

  private Connection connect(Path path) throws Exception {
    return DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
  }

  @Test
  void freshStoreHasIndependentVersionAndSingletonFenceAndReopens() throws Exception {
    Path path = temp.resolve("operations.db");
    try (var store = new SqliteOperationStore(path, CLOCK, step -> {});
        Connection db = connect(path);
        Statement statement = db.createStatement()) {
      assertTrue(store.recovery().isEmpty());
      assertEquals(1, scalar(statement, "PRAGMA user_version"));
      assertEquals(0, scalar(statement, "SELECT history_since_ms FROM operations_meta"));
      assertEquals(1, scalar(statement, "SELECT count(*) FROM operations_meta"));
      try (ResultSet result = statement.executeQuery("PRAGMA table_info(operations)")) {
        Set<String> columns = new HashSet<>();
        while (result.next()) columns.add(result.getString("name"));
        assertTrue(columns.containsAll(Set.of("operation_key", "identity_json", "grant_ref",
            "accepted_settings_revision", "building_generation_id", "gaps_list_hash",
            "journal_replayed_entries", "superseded_from", "urgency_detached_at")));
        assertFalse(columns.contains("history_since"));
      }
      statement.execute("INSERT INTO operations(operation_key, kind, survival, urgency, state,"
          + "identity_json, accepted_at, updated_at) VALUES ('key', 'operation', 'interactive',"
          + "'foreground', 'ACCEPTED', '{}', 1, 1)");
    }
    try (var reopened = new SqliteOperationStore(path);
        Connection db = connect(path); Statement statement = db.createStatement()) {
      assertTrue(reopened.recovery().isEmpty());
      assertEquals(1, scalar(statement, "SELECT count(*) FROM operations"));
      assertEquals(0, scalar(statement, "SELECT history_since_ms FROM operations_meta"));
    }
  }

  @Test
  void futureSchemaIsRefusedWithoutChangingDatabaseBytes() throws Exception {
    Path path = temp.resolve("future.db");
    try (Connection db = connect(path); Statement statement = db.createStatement()) {
      statement.execute("CREATE TABLE future_owned(value TEXT)");
      statement.execute("INSERT INTO future_owned VALUES ('preserve')");
      statement.execute("PRAGMA user_version = 2");
    }
    byte[] before = Files.readAllBytes(path);
    assertThrows(UnsupportedStoreVersionException.class, () -> new SqliteOperationStore(path));
    assertArrayEquals(before, Files.readAllBytes(path));
    try (var siblings = Files.list(temp)) {
      assertFalse(siblings.anyMatch(p -> p.getFileName().toString().contains(".corrupt-")));
    }
  }

  @Test
  void futureVersionInUncheckpointedWalIsAlsoRefused() throws Exception {
    Path path = temp.resolve("wal-future.db");
    try (Connection db = connect(path); Statement statement = db.createStatement()) {
      statement.execute("PRAGMA journal_mode = WAL");
      statement.execute("PRAGMA wal_autocheckpoint = 0");
      statement.execute("CREATE TABLE future_owned(value TEXT)");
      statement.execute("PRAGMA user_version = 2");
      byte[] before = Files.readAllBytes(path);
      Path wal = path.resolveSibling(path.getFileName() + "-wal");
      byte[] beforeWal = Files.readAllBytes(wal);
      Path shm = path.resolveSibling(path.getFileName() + "-shm");
      byte[] beforeShm = Files.readAllBytes(shm);
      assertThrows(UnsupportedStoreVersionException.class, () -> new SqliteOperationStore(path));
      assertArrayEquals(before, Files.readAllBytes(path));
      assertArrayEquals(beforeWal, Files.readAllBytes(wal));
      assertArrayEquals(beforeShm, Files.readAllBytes(shm));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"quarantine-directory", "quarantine-operations.db",
      "quarantine-operations.db-wal", "quarantine-operations.db-shm", "quarantine-finalized", "before-schema-commit"})
  void interruptedQuarantineCannotResetTheHistoryFence(String interruptedStep) throws Exception {
    Path path = temp.resolve("operations.db");
    byte[] corrupt = "this is a corrupt SQLite file".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(path, corrupt);
    Files.writeString(temp.resolve("operations.db-wal"), "original WAL");
    Files.writeString(temp.resolve("operations.db-shm"), "original SHM");
    assertThrows(IOException.class, () -> new SqliteOperationStore(path, CLOCK, step -> {
      if (step.equals(interruptedStep)) throw new IOException("injected interruption");
    }));
    try (var reopened = new SqliteOperationStore(path, CLOCK, step -> {});
        Connection db = connect(path); Statement statement = db.createStatement()) {
      assertEquals(CLOCK.millis() + 300_001,
          scalar(statement, "SELECT history_since_ms FROM operations_meta"));
      assertEquals(1, scalar(statement, "PRAGMA user_version"));
      Path preserved = reopened.recovery().orElseThrow().preservedDirectory();
      assertArrayEquals(corrupt, Files.readAllBytes(preserved.resolve("operations.db")));
      assertEquals("original WAL", Files.readString(preserved.resolve("operations.db-wal")));
      assertEquals("original SHM", Files.readString(preserved.resolve("operations.db-shm")));
    }
    try (var cleanReopen = new SqliteOperationStore(path, CLOCK, step -> {})) {
      assertTrue(cleanReopen.recovery().isEmpty(), "old quarantine must not re-announce each boot");
    }
    boolean foundOriginal = false;
    try (var preserved = Files.walk(temp)) {
      for (Path candidate : preserved.filter(Files::isRegularFile).toList()) {
        if (!candidate.equals(path) && Arrays.equals(corrupt, Files.readAllBytes(candidate))) {
          foundOriginal = true;
        }
      }
    }
    assertTrue(foundOriginal, "quarantine must retain the exact original bytes");
  }

  @Test
  void orphanedWalCannotBeAppliedToFreshStoreAfterQuarantineInterruption() throws Exception {
    Path path = temp.resolve("operations.db");
    Path priorRecovery = temp.resolve("operations.db.corrupt-" + CLOCK.millis()
        + "-00000000-0000-0000-0000-000000000000");
    Files.createDirectory(priorRecovery);
    Files.writeString(priorRecovery.resolve("operations.db"), "original corrupt main file");
    Path wal = temp.resolve("operations.db-wal");
    byte[] originalWal = "orphaned WAL bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(wal, originalWal);
    try (var store = new SqliteOperationStore(path, CLOCK, step -> {});
        Connection db = connect(path); Statement statement = db.createStatement()) {
      assertTrue(store.recovery().isPresent());
      assertEquals(0, scalar(statement, "SELECT count(*) FROM operations"));
      assertEquals(CLOCK.millis() + 300_001,
          scalar(statement, "SELECT history_since_ms FROM operations_meta"));
    }
    try (var preserved = Files.walk(temp)) {
      assertTrue(preserved.filter(p -> p.getFileName().toString().equals("operations.db-wal"))
          .anyMatch(p -> {
            try { return Arrays.equals(originalWal, Files.readAllBytes(p)); }
            catch (IOException error) { throw new java.io.UncheckedIOException(error); }
          }));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"quarantine-directory", "quarantine-operations.db",
      "quarantine-operations.db-wal", "quarantine-operations.db-shm", "quarantine-finalized", "before-schema-commit"})
  void abruptProcessExitPreservesAllArtifactsAndFence(String crashStep) throws Exception {
    Path path = temp.resolve("operations.db");
    Files.writeString(path, "corrupt main");
    Files.writeString(temp.resolve("operations.db-wal"), "original WAL");
    Files.writeString(temp.resolve("operations.db-shm"), "original SHM");
    Set<String> classpath = new java.util.LinkedHashSet<>();
    for (Class<?> type : java.util.List.of(OperationStoreCrashChild.class, SqliteOperationStore.class,
        io.justsearch.app.api.operations.OperationStore.class,
        io.justsearch.configuration.persistence.SqliteStoreSnapshot.class,
        org.slf4j.Logger.class, Class.forName("org.sqlite.JDBC"),
        tools.jackson.databind.ObjectMapper.class, tools.jackson.core.JsonParser.class,
        com.fasterxml.jackson.annotation.JsonProperty.class,
        io.justsearch.core.context.EngineContext.class,
        io.justsearch.agent.api.registry.InvocationProvenance.class)) {
      classpath.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
    }
    String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
    Path output = temp.resolve("crash-child.txt");
    Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
        "-cp", String.join(java.io.File.pathSeparator, classpath), OperationStoreCrashChild.class.getName(),
        path.toString(), crashStep, Long.toString(CLOCK.millis()))
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertTrue(child.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), "crash child timed out");
      assertEquals(71, child.exitValue(), Files.readString(output));
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        assertTrue(child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
      }
    }
    try (var recovered = new SqliteOperationStore(path, CLOCK, step -> {});
        Connection db = connect(path); Statement statement = db.createStatement()) {
      var recovery = recovered.recovery().orElseThrow();
      assertEquals(CLOCK.millis() + 300_001, recovery.historySinceMillis());
      assertEquals(recovery.historySinceMillis(), scalar(statement,
          "SELECT history_since_ms FROM operations_meta WHERE singleton = 1"));
      assertEquals("corrupt main", Files.readString(recovery.preservedDirectory().resolve("operations.db")));
      assertEquals("original WAL", Files.readString(recovery.preservedDirectory().resolve("operations.db-wal")));
      assertEquals("original SHM", Files.readString(recovery.preservedDirectory().resolve("operations.db-shm")));
    }
  }

  private long scalar(Statement statement, String sql) throws Exception {
    try (ResultSet result = statement.executeQuery(sql)) {
      assertTrue(result.next());
      return result.getLong(1);
    }
  }
}
