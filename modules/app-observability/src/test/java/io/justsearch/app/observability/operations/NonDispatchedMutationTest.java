/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.encryption.DataKeyState;
import io.justsearch.agent.api.encryption.KeyLockedException;
import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lane F/955: a producer depends only on the app-api runner, with no catalog or executor. */
final class NonDispatchedMutationTest {
  @TempDir Path temp;

  @Test
  void eachSealedMutationHasOneCommittedAcceptanceAndExactlyOneTerminalRow() throws Exception {
    try (var store = store()) {
      var cipher = cipher(false);
      var producer = new Producer(new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of()), cipher);
      for (String mutation : java.util.List.of("remember", "correct", "forget", "clear", "import-batch")) {
        var request = request(mutation);
        var result = producer.mutate(request, "private-memory-body");
        var row = result.completion().toCompletableFuture().join();
        assertEquals(OperationState.COMPLETE, row.state());
        assertEquals(OperationKind.MEMORY, row.descriptor().kind());
        assertEquals(EngineContext.Survival.INTERACTIVE, row.context().survival());
        assertEquals(request.key(), row.key());
        assertEquals(1, countEffects(request.key()));
        assertEquals("private-memory-body", cipher.open(effect(request.key())));
        assertTrue(cipher.isSealed(effect(request.key())));
        assertFalse(effect(request.key()).contains("private-memory-body"));
        assertEquals(row.id(), producer.mutate(request, "private-memory-body")
            .completion().toCompletableFuture().join().id());
        assertEquals(1, countEffects(request.key()), "a recorded outcome must not repeat a sealed effect");
      }
      assertEquals(5, scalar("operations.db", "SELECT COUNT(*) FROM operations"));
      assertEquals(5, scalar("effects.db", "SELECT COUNT(*) FROM effects"));
      assertFalse(new String(java.nio.file.Files.readAllBytes(temp.resolve("operations.db")),
          java.nio.charset.StandardCharsets.ISO_8859_1).contains("private-memory-body"));
    }
  }

  @Test
  void acceptanceFailurePreventsEvenEnteringTheSealedStore() throws Exception {
    try (var store = store()) {
      var producer = new Producer(new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of()), cipher(false));
      sql("operations.db", "CREATE TRIGGER refuse_accept BEFORE INSERT ON operations "
          + "BEGIN SELECT RAISE(ABORT, 'fixture acceptance failure'); END");
      assertThrows(OperationStoreException.class, () -> producer.mutate(request("remember"), "private-memory-body"));
      assertEquals(0, producer.storeEntries);
      assertEquals(0, scalar("operations.db", "SELECT COUNT(*) FROM operations"));
      assertEquals(0, scalar("effects.db", "SELECT COUNT(*) FROM effects"));
    }
  }

  @Test
  void sealedStoreFailureFailsTheAcceptedRowAndNeverReturnsSuccessOnRetry() throws Exception {
    try (var store = store()) {
      var producer = new Producer(new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of()), cipher(false));
      sql("effects.db", "CREATE TRIGGER refuse_effect BEFORE INSERT ON effects "
          + "BEGIN SELECT RAISE(ABORT, 'fixture effect failure'); END");
      var request = request("correct");
      assertThrows(IllegalStateException.class, () -> producer.mutate(request, "private-memory-body"));
      var row = store.find(request.key()).orElseThrow();
      assertEquals(OperationState.FAILED, row.state());
      assertEquals("UNCAUGHT_EXCEPTION", row.failureReason());
      assertEquals(0, countEffects(request.key()));
      assertFalse(producer.mutate(request, "private-memory-body").response().success());
      assertEquals(1, producer.storeEntries, "the failed receipt must not retry the effect");
    }
  }

  @Test
  void lockedCipherFailsWithoutPlaintextFallback() throws Exception {
    try (var store = store()) {
      var producer = new Producer(new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of()), cipher(true));
      var request = request("remember");
      assertThrows(KeyLockedException.class, () -> producer.mutate(request, "private-memory-body"));
      assertEquals(OperationState.FAILED, store.find(request.key()).orElseThrow().state());
      assertEquals(0, countEffects(request.key()));
    }
  }

  /** Fixture effect owner. The admission facade has no OperationStore or dispatcher dependency. */
  private final class Producer {
    private final OperationAttemptRunner runner;
    private final StoreCipher cipher;
    private int storeEntries;

    private Producer(OperationAttemptRunner runner, StoreCipher cipher) throws SQLException {
      this.runner = runner;
      this.cipher = cipher;
      sql("effects.db", "CREATE TABLE effects (operation_key TEXT PRIMARY KEY, sealed_value TEXT NOT NULL)");
    }

    private OperationAttemptRunner.Result mutate(OperationAttemptRunner.Request request, String value) {
      var accepted = runner.accept(request);
      return runner.start(accepted, handle -> {
        storeEntries++;
        // A second connection must observe committed RUNNING before the sealed store is touched.
        assertEquals(1, scalar("operations.db", "SELECT COUNT(*) FROM operations WHERE id = "
            + handle.id() + " AND state = 'RUNNING'"));
        String sealed = cipher.seal(value);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("effects.db"));
            var statement = connection.prepareStatement("INSERT INTO effects VALUES (?, ?)")) {
          statement.setString(1, handle.key());
          statement.setString(2, sealed);
          statement.executeUpdate(); // Autocommit returns only after the sealed effect commits.
        } catch (SQLException failure) {
          throw new IllegalStateException("Sealed fixture write failed", failure);
        }
        return OperationExecution.finished(OperationResult.success("Mutation committed"));
      });
    }
  }

  private SqliteOperationStore store() throws Exception {
    return new SqliteOperationStore(temp.resolve("operations.db"), Clock.systemUTC(), step -> {});
  }

  private static OperationAttemptRunner.Request request(String mutation) {
    var context = new EngineContext(EngineContext.ClientKind.INTERNAL, "memory-fixture",
        Optional.empty(), Optional.empty(), "system", "SYSTEM_INTERNAL",
        EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);
    return new OperationAttemptRunner.Request(OperationKeys.generate(Clock.systemUTC()),
        OperationDescriptor.invocation(OperationKind.MEMORY, "fixture." + mutation,
            "{\"value\":\"private-memory-body\"}", false), context, null);
  }

  private static StoreCipher cipher(boolean locked) {
    byte[] key = new byte[32];
    new java.security.SecureRandom().nextBytes(key);
    return new StoreCipher(new DataKeyState() {
      @Override public boolean enabled() { return true; }
      @Override public boolean locked() { return locked; }
      @Override public byte[] dek() {
        if (locked) throw new KeyLockedException();
        return key.clone();
      }
    });
  }

  private int countEffects(String key) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("effects.db"));
        var statement = connection.prepareStatement("SELECT COUNT(*) FROM effects WHERE operation_key = ?")) {
      statement.setString(1, key);
      try (var rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getInt(1); }
    }
  }

  private String effect(String key) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("effects.db"));
        var statement = connection.prepareStatement("SELECT sealed_value FROM effects WHERE operation_key = ?")) {
      statement.setString(1, key);
      try (var rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getString(1); }
    }
  }

  private int scalar(String file, String sql) {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve(file));
        var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getInt(1);
    } catch (SQLException failure) { throw new IllegalStateException(failure); }
  }

  private void sql(String file, String sql) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve(file));
        var statement = connection.createStatement()) { statement.execute(sql); }
  }
}
