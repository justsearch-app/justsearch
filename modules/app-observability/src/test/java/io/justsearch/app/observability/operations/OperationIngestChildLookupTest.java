/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationHistoryMode;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationPreparedPayload;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.app.api.operations.RecordedIngestChild;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite proof for exact recorded-child lookup and terminal-parent fencing. */
@Timeout(30)
final class OperationIngestChildLookupTest {
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-14T09:00:00Z"),
      ZoneOffset.UTC);
  private static final EngineContext CONTEXT = new EngineContext(EngineContext.ClientKind.INTERNAL,
      "child-lookup-client", Optional.of("session-lookup"), Optional.of("grant-lookup"),
      "test-tier", "AGENT_LOOP", EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
  private static final InvocationProvenance PROVENANCE = new InvocationProvenance(
      TransportTag.AGENT_LOOP, ExecutorTag.AGENT, Optional.of("lookup-initiator"), CLOCK.instant(),
      Optional.of("lookup-intent"), Optional.of("lookup-correlation"));

  @TempDir Path temp;

  @Test
  void absentChildLookupIsReadOnlyAndMissingParentRefuses() throws Exception {
    Path db = temp.resolve("absent.db");
    var plan = plan();
    try (var store = store(db)) {
      var parent = acceptParent(store, plan);
      assertTrue(store.start(parent.record().id()), "parent starts for lookup fixture");
      long rowsBefore = count(db, "SELECT count(*) FROM operations");
      long preparationsBefore = count(db, "SELECT count(*) FROM operation_preparations");
      OperationRecord before = store.find(parent.record().key()).orElseThrow();

      assertTrue(store.findIngestChild(parent.record().key(), plan).isEmpty(),
          "absent child lookup must not synthesize a row");

      OperationRecord after = store.find(parent.record().key()).orElseThrow();
      assertEquals(rowsBefore, count(db, "SELECT count(*) FROM operations"),
          "absent lookup does not create an operation");
      assertEquals(preparationsBefore, count(db, "SELECT count(*) FROM operation_preparations"),
          "absent lookup does not create preparation state");
      assertEquals(before.attempts(), after.attempts(), "lookup does not advance attempts");
      assertEquals(before.unitsCompleted(), after.unitsCompleted(), "lookup does not advance completion");
      assertEquals(before.unitsFailed(), after.unitsFailed(), "lookup does not advance failures");

      String missingParent = OperationKeys.generate(CLOCK);
      assertChildRefused(() -> store.findIngestChild(missingParent, plan));
      assertEquals(rowsBefore, count(db, "SELECT count(*) FROM operations"),
          "missing parent lookup remains read-only");
    }
  }

  @Test
  void terminalChildLookupSurvivesParentCompletionAndStoreReopen() throws Exception {
    Path db = temp.resolve("terminal-reopen.db");
    var plan = plan();
    String parentKey;
    String childKey;
    try (var store = store(db)) {
      var parent = acceptParent(store, plan);
      parentKey = parent.record().key();
      assertTrue(store.start(parent.record().id()), "parent starts for child fixture");
      var child = store.acceptIngestChild(parentKey, OperationKeys.generate(CLOCK),
          parent.preparation(), plan).record();
      childKey = child.key();
      assertTrue(store.start(child.id()), "child starts for terminal fixture");
      assertTrue(store.finish(child.id(), OperationState.COMPLETE,
          new OperationReceipt("SUCCESS", "child-effect")).isPresent());
      assertTrue(store.finish(parent.record().id(), OperationState.COMPLETE,
          new OperationReceipt("SUCCESS", null)).isPresent());
    }

    try (var reopened = store(db)) {
      OperationRecord found = reopened.findIngestChild(parentKey, plan).orElseThrow();
      assertEquals(childKey, found.key(), "lookup returns the exact terminal child");
      assertEquals(OperationState.COMPLETE, found.state(), "terminal child is repair-readable");
      assertNotNull(found.receipt(), "terminal child retains its receipt");
    }
  }

  @Test
  void existingChildAttributionHistoryAndPayloadMismatchesRefuseLookup() throws Exception {
    for (String mismatch : List.of("attribution", "history", "payload")) {
      Path db = temp.resolve("mismatch-" + mismatch + ".db");
      var plan = plan();
      try (var store = store(db)) {
        var parent = acceptParent(store, plan);
        assertTrue(store.start(parent.record().id()), "parent starts for " + mismatch);
        var child = store.acceptIngestChild(parent.record().key(), OperationKeys.generate(CLOCK),
            parent.preparation(), plan).record();
        corrupt(db, child.id(), mismatch, plan);
        assertChildRefused(() -> store.findIngestChild(parent.record().key(), plan));
        assertEquals(child.key(), store.find(child.key()).orElseThrow().key(),
            mismatch + " mismatch does not replace the child row");
      }
    }
  }

  @Test
  void openChildUnderTerminalParentIsObservableButCannotBeAccepted() throws Exception {
    Path db = temp.resolve("open-under-terminal-parent.db");
    var plan = plan();
    try (var store = store(db)) {
      var parent = acceptParent(store, plan);
      assertTrue(store.start(parent.record().id()), "parent starts for open-child fixture");
      var child = store.acceptIngestChild(parent.record().key(), OperationKeys.generate(CLOCK),
          parent.preparation(), plan).record();
      assertEquals(OperationState.ACCEPTED, child.state(), "child remains open and unstarted");
      assertTrue(store.finish(parent.record().id(), OperationState.FAILED,
          new OperationReceipt("AUTHORIZATION_REFUSED", null)).isPresent());

      OperationRecord observed = store.findIngestChild(parent.record().key(), plan).orElseThrow();
      assertEquals(child.key(), observed.key(), "bookkeeping lookup sees the open child");
      assertChildRefused(() -> store.acceptIngestChild(parent.record().key(),
          OperationKeys.generate(CLOCK), parent.preparation(), plan));
      assertEquals(OperationState.ACCEPTED, store.find(child.key()).orElseThrow().state(),
          "refused acceptance does not terminalize or rerun the open child");
    }
  }

  private SqliteOperationStore store(Path db) throws Exception {
    return new SqliteOperationStore(db, CLOCK, step -> {});
  }

  private static ParentFixture acceptParent(SqliteOperationStore store, RecordedRootPlan plan)
      throws Exception {
    String key = OperationKeys.generate(CLOCK);
    var descriptor = OperationDescriptor.invocation(OperationKind.REINDEX, "core.recorded-child",
        "{}", false);
    var preparation = new OperationStore.Preparation(UUID.randomUUID(),
        new OperationPreparedPayload(false, plan.toReplayPayload()));
    assertTrue(store.savePreparation(key, descriptor, preparation).isPresent());
    OperationRecord record = store.acceptPrepared(key, descriptor, CONTEXT, PROVENANCE,
        preparation.nonce()).record();
    return new ParentFixture(record, preparation);
  }

  private static RecordedRootPlan plan() {
    Path root = Path.of("child-lookup-root").toAbsolutePath().normalize();
    return new RecordedRootPlan("generation-child-lookup", List.of(new RecordedRootPlan.Root(root,
        "docs", true, false, List.of("*.tmp"), List.of())));
  }

  private static void corrupt(Path db, long childId, String mismatch, RecordedRootPlan plan)
      throws Exception {
    String sql = switch (mismatch) {
      case "attribution" -> "UPDATE operations SET client_id = ? WHERE id = ?";
      case "history" -> "UPDATE operations SET history_mode = ? WHERE id = ?";
      case "payload" -> "UPDATE operations SET preparation_payload = ? WHERE id = ?";
      default -> throw new IllegalArgumentException("Unknown mismatch: " + mismatch);
    };
    String value = switch (mismatch) {
      case "attribution" -> "different-client";
      case "history" -> OperationHistoryMode.STANDARD.name();
      case "payload" -> new RecordedIngestChild("01994180-0000-7000-8000-000000000999",
          new RecordedRootPlan("different-generation", plan.roots())).payload().value();
      default -> throw new IllegalArgumentException("Unknown mismatch: " + mismatch);
    };
    try (Connection connection = connection(db); var update = connection.prepareStatement(sql)) {
      update.setString(1, value);
      update.setLong(2, childId);
      assertEquals(1, update.executeUpdate(), mismatch + " corruption fixture row");
    }
  }

  private static void assertChildRefused(org.junit.jupiter.api.function.Executable operation) {
    var failure = assertThrows(OperationStoreException.class, operation);
    assertEquals(OperationStoreException.Code.CHILD_ACCEPTANCE_REFUSED, failure.code());
  }

  private static long count(Path db, String sql) throws Exception {
    try (Connection connection = connection(db); var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next(), "expected count row");
      return rows.getLong(1);
    }
  }

  private static Connection connection(Path db) throws Exception {
    return DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
  }

  private record ParentFixture(OperationRecord record, OperationStore.Preparation preparation) {}
}
