/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.*;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ImportApprovalStatePersistenceTest {
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC);
  @TempDir Path directory;

  @Test
  void activeSelectionAndInterruptionRoundTripWithoutReinterpretingRowState() throws Exception {
    Path db = directory.resolve("operations.db");
    String key = OperationKeys.generate(CLOCK);
    long id;
    var binding = new ImportApprovalBinding(1, UUID.randomUUID().toString(), key,
        UUID.randomUUID().toString(), "a".repeat(64), 3);
    try (var store = new SqliteOperationStore(db, CLOCK, ignored -> {})) {
      var context = new EngineContext(EngineContext.ClientKind.INTERNAL, "selection-test", Optional.empty(),
          Optional.of("jsa1:auto"), "TRUSTED", "SYSTEM_INTERNAL", EngineContext.Survival.DURABLE,
          EngineContext.Urgency.BACKGROUND);
      id = store.accept(key, OperationDescriptor.invocation(OperationKind.INGEST, "core.ingest-files", "{}", false), context, null).record().id();
      assertEquals(new ImportApprovalState(null, 0, null), store.importApprovalState(id).orElseThrow());
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
          var update = connection.prepareStatement("UPDATE operations SET active_approval_binding=?,interruption_revision=3,interruption_reason='revoked' WHERE id=?")) {
        update.setString(1, binding.encode()); update.setLong(2, id); assertEquals(1, update.executeUpdate());
      }
    }
    try (var store = new SqliteOperationStore(db, CLOCK, ignored -> {})) {
      assertEquals(new ImportApprovalState(binding, 3, "revoked"), store.importApprovalState(id).orElseThrow());
      assertEquals(OperationState.ACCEPTED, store.find(key).orElseThrow().state());
      assertTrue(store.start(id)); assertTrue(store.checkpoint(id, "same-operation", 1, 0));
      assertEquals(new ImportApprovalState(binding, 3, "revoked"), store.importApprovalState(id).orElseThrow());
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
        statement.executeUpdate("UPDATE operations SET active_approval_binding='{}'");
      }
      assertThrows(RuntimeException.class, () -> store.importApprovalState(id), "malformed is never legacy absence");
    }
  }
}
