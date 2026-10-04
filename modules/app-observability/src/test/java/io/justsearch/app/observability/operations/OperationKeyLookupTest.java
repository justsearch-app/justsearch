/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

final class OperationKeyLookupTest {
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC);
  private static final EngineContext CONTEXT = new EngineContext(EngineContext.ClientKind.INTERNAL,
      "lookup", Optional.empty(), Optional.empty(), "system", "SYSTEM_INTERNAL",
      EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND);
  @TempDir Path temp;

  @ParameterizedTest
  @ValueSource(strings = {"malformed", "future", "expired"})
  void unknownLookupDoesNotAcceptOrMoveHistoryAndInvalidMissingKeysRefuse(String refusedKind) throws Exception {
    try (var store = store()) {
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of());
      assertTrue(runner.lookup(request(OperationKeys.generate(CLOCK), descriptor("{}", false))).isEmpty());
      assertTrue(store.openRecords().isEmpty());
      assertEquals(0, store.historySinceMillis());
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("operations.db"));
          var statement = connection.createStatement()) {
        if (refusedKind.equals("expired")) {
          statement.execute("UPDATE operations_meta SET history_since_ms = " + (CLOCK.millis() + 1));
        }
        try (var rows = statement.executeQuery("SELECT count(*) FROM operations")) {
          assertTrue(rows.next());
          assertEquals(0, rows.getInt(1), "Read-only probes must not create a durable acceptance");
        }
      }
      String key = switch (refusedKind) {
        case "malformed" -> "not-a-key";
        case "future" -> OperationKeys.generate(Clock.offset(CLOCK, Duration.ofMinutes(6)));
        default -> OperationKeys.generate(CLOCK);
      };
      assertCode(refusedKind.equals("expired") ? OperationStoreException.Code.OPERATION_EXPIRED
          : OperationStoreException.Code.INVALID_OPERATION_KEY,
          () -> runner.lookup(request(key, descriptor("{}", false))));
    }
  }

  @ParameterizedTest
  @EnumSource(OperationState.class)
  void matchingLookupReturnsExistingCapabilityButEveryChangedIdentityConflicts(OperationState state) throws Exception {
    try (var store = store()) {
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of());
      String key = OperationKeys.generate(CLOCK);
      var accepted = runner.accept(request(key, descriptor("{\"a\":1,\"b\":2}", false)));
      if (state == OperationState.RUNNING) assertTrue(store.start(accepted.accepted().id()));
      if (state == OperationState.COMPLETE_WITH_GAPS) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("operations.db"));
            var update = connection.prepareStatement("UPDATE operations SET state = 'COMPLETE_WITH_GAPS' WHERE id = ?")) {
          update.setLong(1, accepted.accepted().id());
          assertEquals(1, update.executeUpdate());
        }
      }
      if (state.terminal()) {
        assertTrue(store.finish(accepted.accepted().id(), state, new OperationReceipt("RECORDED", null)).isPresent());
      }
      var found = runner.lookup(request(key, descriptor("{\"b\":2,\"a\":1}", false))).orElseThrow();
      assertTrue(found.existing());
      assertEquals(accepted.accepted().id(), found.accepted().id());
      assertEquals(state, found.accepted().state());
      var result = runner.start(found, ignored -> { fail("Lookup never authorizes another effect"); return null; });
      assertEquals(state, result.record().state());
      assertCode(OperationStoreException.Code.OPERATION_KEY_REUSED,
          () -> runner.lookup(request(key, descriptor("{\"a\":9,\"b\":2}", false))));
      assertCode(OperationStoreException.Code.OPERATION_KEY_REUSED,
          () -> runner.lookup(request(key, descriptor("{\"a\":1,\"b\":2}", true))));
      assertCode(OperationStoreException.Code.OPERATION_KEY_REUSED,
          () -> runner.lookup(request(key, OperationDescriptor.invocation(
              OperationKind.OPERATION, "core.other", "{\"a\":1,\"b\":2}", false))));
      assertCode(OperationStoreException.Code.OPERATION_KEY_REUSED,
          () -> runner.lookup(request(key, OperationDescriptor.invocation(
              OperationKind.NOTE, "core.lookup", "{\"a\":1,\"b\":2}", false))));
    }
  }

  private SqliteOperationStore store() throws Exception {
    return new SqliteOperationStore(temp.resolve("operations.db"), CLOCK, step -> {});
  }

  private static OperationDescriptor descriptor(String arguments, boolean undo) {
    return OperationDescriptor.invocation(OperationKind.OPERATION, "core.lookup", arguments, undo);
  }

  private static OperationAttemptRunner.Request request(String key, OperationDescriptor descriptor) {
    return new OperationAttemptRunner.Request(key, descriptor, CONTEXT, null);
  }

  private static void assertCode(OperationStoreException.Code expected, org.junit.jupiter.api.function.Executable action) {
    assertEquals(expected, assertThrows(OperationStoreException.class, action).code());
  }
}
