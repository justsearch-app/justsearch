/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationHistoryMode;
import io.justsearch.app.api.operations.OperationHistoryRow;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.observability.ledger.ActionEvent;
import io.justsearch.app.observability.ledger.ActionEventJournal;
import io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry;
import io.justsearch.app.observability.ledger.ActionLedgerProjection;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRegistry;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * L1 — a completed folder removal keeps the folder it removed in its durable receipt, and the action
 * ledger (live and journaled) carries it, so every window — and a later session — can offer
 * "Add <folder> again". The metadata-only history entry never carries it. The field is additive:
 * receipts and journal rows written before it existed still read, without a folder.
 */
final class RemovedFolderRecordTest {
  @TempDir Path directory;
  private final OperationTestClock clock = new OperationTestClock(Instant.parse("2026-11-05T09:00:00Z").toEpochMilli());
  private static final EngineContext CONTEXT = new EngineContext(EngineContext.ClientKind.INTERNAL,
      "removed-folder", Optional.empty(), Optional.empty(), "SYSTEM", "SYSTEM_INTERNAL",
      EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND);
  private static final InvocationProvenance PROVENANCE = InvocationProvenance.fromEngineContext(
      CONTEXT, ExecutorTag.UI, Instant.parse("2026-11-05T08:59:59Z"), Optional.empty());
  private static final OperationReceipt.RemovedFolder NOTES =
      new OperationReceipt.RemovedFolder("C:\\Users\\Sam\\Notes", "reports");

  private SqliteOperationStore open() throws Exception {
    return new SqliteOperationStore(directory.resolve("operations.db"), clock, step -> {});
  }

  private OperationAttemptRunner.Request removal() {
    return new OperationAttemptRunner.Request(OperationKeys.generate(clock), OperationDescriptor.invocation(
        OperationKind.OPERATION, "core.remove-watched-root", "{\"path\":\"C:\\\\Users\\\\Sam\\\\Notes\"}", false),
        CONTEXT, PROVENANCE, OperationHistoryMode.STANDARD);
  }

  /** The projector's own scheduler, driven by hand. */
  private static final class Timer {
    final EngineExecutorRegistry executors = mock(EngineExecutorRegistry.class);
    private Runnable tick;

    Timer() throws Exception {
      var registration = mock(EngineExecutorRegistry.Registration.class);
      var scheduler = mock(ScheduledExecutorService.class);
      when(executors.register(any())).thenReturn(registration);
      when(registration.openScheduled(any())).thenReturn(scheduler);
      when(scheduler.awaitTermination(anyLong(), any())).thenReturn(true);
      doAnswer(call -> { tick = call.getArgument(0); return mock(ScheduledFuture.class); })
          .when(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(1L), eq(TimeUnit.SECONDS));
    }

    void tick() { tick.run(); }
  }

  private static Optional<OperationReceipt.RemovedFolder> folderOf(List<ActionEvent> events) {
    return events.stream().filter(ActionEvent.Operation.class::isInstance).map(ActionEvent.Operation.class::cast)
        .filter(op -> op.operationId().equals("core.remove-watched-root")).findFirst().orElseThrow().removedFolder();
  }

  @Test
  void aCompletedRemovalReachesTheLedgerWithItsFolderAndTheJournalKeepsItAcrossARestart() throws Exception {
    var audit = directory.resolve("audit");
    try (var store = open()) {
      var runner = new OperationAttemptRunnerImpl(store, clock, Set.of());
      var history = new OperationHistoryStore(store);
      var ledger = new ActionLedgerChangeRegistry(ActionEventJournal.at(audit));
      var timer = new Timer();
      var projector = new OperationHistoryProjector(store, history, new OperationHistoryChangeRegistry(), ledger, timer.executors);
      try (projector) {
        runner.start(runner.accept(removal()), handle -> OperationExecution.finished(
            OperationResult.success("Removed", Map.of("path", NOTES.path(), "deletedJobs", 3,
                OperationReceipt.REMOVED_FOLDER_KEY, NOTES)))).completion().toCompletableFuture().join();
        timer.tick();
        assertEquals(Optional.of(NOTES), folderOf(ledger.store().recent()));
        Map<String, Object> wire = ActionLedgerProjection.toWireRow(ledger.store().recent().get(0));
        assertEquals(Map.of("path", "C:\\Users\\Sam\\Notes", "collection", "reports"), wire.get("removedFolder"));
        // The metadata-only operation-history entry never carries the path.
        String historyJson = JsonMapper.builder().build().writeValueAsString(history.recent());
        assertFalse(historyJson.contains("Notes"), historyJson);
      }
    }
    // A later session reads the journaled ledger row, folder included.
    assertEquals(Optional.of(NOTES), folderOf(ActionEventJournal.at(audit).tail(ActionEventJournal.TAIL_CAPACITY)));
  }

  @Test
  void aFailedRemovalRecordsNoFolderToAddBack() throws Exception {
    try (var store = open()) {
      var runner = new OperationAttemptRunnerImpl(store, clock, Set.of());
      var request = removal();
      runner.start(runner.accept(request), handle -> OperationExecution.finished(
          OperationResult.failure("Remove watched root incomplete", "HANDLER_FAILED",
              Map.of(OperationReceipt.REMOVED_FOLDER_KEY, NOTES), true))).completion().toCompletableFuture().join();
      var row = OperationHistoryRow.from(store.find(request.key()).orElseThrow());
      assertEquals(OperationState.FAILED, row.state());
      assertTrue(OperationHistoryProjection.removedFolder(row).isEmpty());
      var event = ActionLedgerProjection.projectOperation(OperationHistoryProjection.entry(row),
          OperationHistoryProjection.removedFolder(row));
      assertFalse(ActionLedgerProjection.toWireRow(event).containsKey("removedFolder"));
    }
  }

  @Test
  void aReceiptWrittenBeforeTheFieldExistedStillReadsAndOffersNoReAdd() throws Exception {
    var request = removal();
    try (var store = open()) {
      var runner = new OperationAttemptRunnerImpl(store, clock, Set.of());
      runner.start(runner.accept(request), handle -> OperationExecution.finished(
          OperationResult.success("Removed", Map.of(OperationReceipt.REMOVED_FOLDER_KEY, NOTES))))
          .completion().toCompletableFuture().join();
    }
    // Rewrite the stored receipt in its earlier shape (code only), as older engines wrote it.
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("operations.db"));
        var statement = db.createStatement()) {
      statement.execute("UPDATE operations SET result_json='{\"code\":\"SUCCESS\"}'");
    }
    try (var reopened = open()) {
      var record = reopened.find(request.key()).orElseThrow();
      assertEquals("SUCCESS", record.receipt().code());
      assertNull(record.receipt().removedFolder());
      var row = OperationHistoryRow.from(record);
      assertTrue(OperationHistoryProjection.removedFolder(row).isEmpty());
      var event = ActionLedgerProjection.projectOperation(OperationHistoryProjection.entry(row),
          OperationHistoryProjection.removedFolder(row));
      assertFalse(ActionLedgerProjection.toWireRow(event).containsKey("removedFolder"));
    }
  }

  @Test
  void theJournalRoundTripsTheFolderAndReadsOlderOrUnreadableRowsWithoutOne() {
    var event = new ActionEvent.Operation("operation:k", Instant.parse("2026-11-05T09:00:00Z"), "user", "BUTTON",
        "core.remove-watched-root", "SUCCESS", Optional.empty(), Optional.empty(),
        Optional.of(new OperationReceipt.RemovedFolder("/home/sam/Notes", null)));
    Map<String, Object> wire = ActionLedgerProjection.toWireRow(event);
    assertEquals(Map.of("path", "/home/sam/Notes"), wire.get("removedFolder"));
    assertEquals(Optional.of(event), ActionLedgerProjection.fromWireRow(wire));

    Map<String, Object> older = new LinkedHashMap<>(wire);
    older.remove("removedFolder");
    var read = (ActionEvent.Operation) ActionLedgerProjection.fromWireRow(older).orElseThrow();
    assertTrue(read.removedFolder().isEmpty());

    Map<String, Object> unreadable = new LinkedHashMap<>(wire);
    unreadable.put("removedFolder", Map.of("path", "bad\u0000path"));
    var skipped = (ActionEvent.Operation) ActionLedgerProjection.fromWireRow(unreadable).orElseThrow();
    assertTrue(skipped.removedFolder().isEmpty());
  }

  @Test
  void aFolderThatCannotBeRecordedNeverFailsTheRemoval() {
    assertTrue(OperationReceipt.RemovedFolder.of("", "default").isEmpty());
    assertTrue(OperationReceipt.RemovedFolder.of("x".repeat(5000), null).isEmpty());
    assertEquals(Optional.of(new OperationReceipt.RemovedFolder("/notes", null)),
        OperationReceipt.RemovedFolder.of("/notes", "default"));
  }
}
