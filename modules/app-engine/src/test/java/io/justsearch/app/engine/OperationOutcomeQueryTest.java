/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.*;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.core.context.EngineContext;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

final class OperationOutcomeQueryTest {
  private static final EngineContext CONTEXT = new EngineContext(EngineContext.ClientKind.INTERNAL,
      "outcome-query", Optional.empty(), Optional.empty(), "system", "SYSTEM_INTERNAL",
      EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND);
  private static final OperationDescriptor DESCRIPTOR = OperationDescriptor.invocation(
      OperationKind.OPERATION, "core.query-fixture", "{\"privateInput\":\"do-not-project\"}", false);
  @TempDir Path temp;

  @Test
  void sixAnswersAgreeWithOperationsAndActualJobsAcrossRestart() throws Exception {
    Path operationsPath = temp.resolve("operations.db");
    String key = OperationKeys.generate(Clock.systemUTC());
    String completed = OperationKeys.generate(Clock.systemUTC());
    String expired = OperationKeys.generate(Clock.systemUTC());
    OperationOutcomeView failedView;
    try (var store = new SqliteOperationStore(operationsPath);
        var jobs = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      jobs.open();
      assertEquals("unknown", store.outcome(key).state().wireValue());
      assertTrue(store.find(key).isEmpty());
      assertEquals(0, jobs.jobStateCounts().doneCount());
      var row = store.accept(key, DESCRIPTOR, CONTEXT, null).record();
      assertEquals("accepted", store.outcome(key).state().wireValue());
      assertEquals(OperationState.ACCEPTED, store.find(key).orElseThrow().state());
      assertTrue(store.start(row.id()));
      assertEquals("running", store.outcome(key).state().wireValue());
      assertEquals(OperationState.RUNNING, store.find(key).orElseThrow().state());

      Path done = Files.writeString(temp.resolve("done.txt"), "done");
      Path failed1 = Files.writeString(temp.resolve("failed1.txt"), "failed");
      Path failed2 = Files.writeString(temp.resolve("failed2.txt"), "failed");
      assertEquals(3, jobs.enqueue(List.of(done, failed1, failed2), "query-fixture"));
      assertEquals(3, jobs.pollPending(3).size());
      jobs.markDone(done);
      var failure = IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED, "IO_FAILED", IngestionRetryPolicy.NONE);
      jobs.markFailed(failed1, failure);
      jobs.markFailed(failed2, failure);
      var counts = jobs.jobStateCounts();
      assertEquals(1, counts.doneCount());
      assertEquals(2, counts.failedCount());
      assertTrue(store.checkpoint(row.id(), "committed-units", counts.doneCount(), counts.failedCount()));
      assertTrue(store.finish(row.id(), OperationState.FAILED, new OperationReceipt("IO_FAILED", null)).isPresent());
      failedView = store.outcome(key);
      assertEquals("failed", failedView.state().wireValue());
      assertEquals("IO_FAILED", failedView.reason());
      assertEquals(counts.doneCount(), failedView.unitsCompleted());
      assertEquals(counts.failedCount(), failedView.unitsFailed());
      assertEquals(store.find(key).orElseThrow().unitsFailed(), failedView.unitsFailed());
      assertNotNull(failedView.completedAt());

      var successful = store.accept(completed, DESCRIPTOR, CONTEXT, null).record();
      assertTrue(store.finish(successful.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", "exec-receipt")).isPresent());
      assertEquals("complete", store.outcome(completed).state().wireValue());
      assertEquals("SUCCESS", store.outcome(completed).result().code());
      assertEquals("exec-receipt", store.outcome(completed).result().executionId());
      assertEquals(OperationState.COMPLETE, store.find(completed).orElseThrow().state());

      var aged = store.accept(expired, DESCRIPTOR, CONTEXT, null).record();
      assertTrue(store.finish(aged.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null)).isPresent());
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + operationsPath);
          var update = connection.prepareStatement("UPDATE operations SET completed_at = ? WHERE id = ?")) {
        update.setLong(1, System.currentTimeMillis() - Duration.ofDays(31).toMillis());
        update.setLong(2, aged.id());
        update.executeUpdate();
      }
      store.pruneHistory();
      assertTrue(store.find(expired).isEmpty());
      assertEquals("expired", store.outcome(expired).state().wireValue(), "an evicted key must not answer unknown");
      assertEquals(store.historySinceMillis(), store.outcome(expired).historySince());
      assertEquals("failed", store.outcome(key).state().wireValue(), "present rows win even below the advanced fence");
      String newKey = OperationKeys.generate(Clock.offset(Clock.systemUTC(), Duration.ofSeconds(1)));
      assertEquals("unknown", store.outcome(newKey).state().wireValue());
      assertTrue(store.find(newKey).isEmpty());
      assertFalse(JsonMapper.builder().build().writeValueAsString(store.outcome(key)).contains("do-not-project"));
    }
    try (var reopened = new SqliteOperationStore(operationsPath);
        var jobs = new SqliteJobQueue(temp.resolve("jobs.db"))) {
      jobs.open();
      var answer = reopened.outcome(key);
      assertEquals("failed", answer.state().wireValue());
      assertEquals(failedView.completedAt(), answer.completedAt());
      assertEquals(jobs.jobStateCounts().doneCount(), answer.unitsCompleted());
      assertEquals(jobs.jobStateCounts().failedCount(), answer.unitsFailed());
      assertEquals("complete", reopened.outcome(completed).state().wireValue());
      assertEquals("expired", reopened.outcome(expired).state().wireValue());
    }
  }

  @Test
  void cancelledAndAwaitingAcceptanceKeepTheSixStateVocabulary() throws Exception {
    Path path = temp.resolve("operations.db");
    try (var store = new SqliteOperationStore(path)) {
      String key = OperationKeys.generate(Clock.systemUTC());
      var row = store.accept(key, DESCRIPTOR, CONTEXT, null).record();
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
          var update = connection.prepareStatement("UPDATE operations SET state='COMPLETE_WITH_GAPS', gaps_json=? WHERE id=?")) {
        update.setString(1, "[{\"unitId\":\"doc-1\",\"reason\":\"IO_FAILED\"}]");
        update.setLong(2, row.id());
        assertEquals(1, update.executeUpdate());
      }
      var awaiting = store.outcome(key);
      assertEquals("running", awaiting.state().wireValue());
      assertEquals("awaiting_acceptance", awaiting.phase());
      assertNull(awaiting.completedAt());
      assertEquals(List.of(new OperationOutcomeView.Gap("doc-1", "IO_FAILED")), awaiting.result().gaps());
      assertTrue(store.finish(row.id(), OperationState.CANCELLED, new OperationReceipt("INTERRUPTED", null)).isPresent());
      var cancelled = store.outcome(key);
      assertEquals("failed", cancelled.state().wireValue());
      assertEquals("cancelled", cancelled.reason());
      assertNull(cancelled.result().gaps(), "a terminal receipt does not reuse pending gap display");
    }
  }

  @Test
  void queryRejectsExecutionIdsMalformedAndFarFutureKeys() throws Exception {
    try (var store = new SqliteOperationStore(temp.resolve("operations.db"))) {
      for (String key : List.of("exec-receipt", java.util.UUID.randomUUID().toString(),
          OperationKeys.generate(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(6))))) {
        assertEquals(OperationStoreException.Code.INVALID_OPERATION_KEY,
            assertThrows(OperationStoreException.class, () -> store.outcome(key)).code());
      }
      assertTrue(store.openRecords().isEmpty(), "queries cannot accept rows");
      assertEquals(0, store.historySinceMillis());
    }
  }
}
