package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationRef;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OperationHistoryStore")
final class OperationHistoryStoreTest {

  @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
  private SqliteOperationStore operations;
  private final OperationTestClock clock = new OperationTestClock(Instant.parse("2026-09-13T00:00:00Z").toEpochMilli());

  @org.junit.jupiter.api.BeforeEach
  void open() throws Exception {
    operations = new SqliteOperationStore(directory.resolve("operations.db"), clock, step -> {});
  }

  @org.junit.jupiter.api.AfterEach
  void close() throws Exception { operations.close(); }

  private void complete(int seq) {
    var context = new io.justsearch.core.context.EngineContext(io.justsearch.core.context.EngineContext.ClientKind.INTERNAL,
        "history-test", Optional.empty(), Optional.empty(), "SYSTEM", "SYSTEM_INTERNAL",
        io.justsearch.core.context.EngineContext.Survival.INTERACTIVE, io.justsearch.core.context.EngineContext.Urgency.BACKGROUND);
    var row = operations.accept(io.justsearch.app.api.operations.OperationKeys.generate(clock),
        io.justsearch.app.api.operations.OperationDescriptor.invocation(io.justsearch.agent.api.registry.OperationKind.OPERATION,
            "core.test-" + seq, "{}", false), context, InvocationProvenance.systemInternal(clock.instant()),
        io.justsearch.app.api.operations.OperationHistoryMode.STANDARD).record();
    operations.finish(row.id(), io.justsearch.app.api.operations.OperationState.COMPLETE,
        new io.justsearch.app.api.operations.OperationReceipt("COMPLETE", null));
    clock.setMillis(clock.millis() + 1);
  }

  @Test
  @DisplayName("default capacity is 200")
  void defaultCapacity() {
    assertEquals(200, new OperationHistoryStore(operations).capacity());
  }

  @Test
  @DisplayName("constructor rejects non-positive capacity")
  void rejectsBadCapacity() {
    assertThrows(IllegalArgumentException.class, () -> new OperationHistoryStore(operations, 0));
    assertThrows(IllegalArgumentException.class, () -> new OperationHistoryStore(operations, -5));
  }

  @Test
  @DisplayName("committed terminal rows return in chronological order")
  void committedRowsChronological() {
    OperationHistoryStore store = new OperationHistoryStore(operations, 10);
    complete(1);
    complete(2);
    complete(3);
    List<OperationHistoryEntry> recent = store.recent();
    assertEquals(3, recent.size());
    assertEquals("core.test-1", recent.get(0).operationId().value());
    assertEquals("core.test-3", recent.get(2).operationId().value());
  }

  @Test
  @DisplayName("view bounds retain the full durable source")
  void boundedViewDoesNotEvictDurableRows() {
    OperationHistoryStore store = new OperationHistoryStore(operations, 3);
    for (int i = 1; i <= 5; i++) {
      complete(i);
    }
    List<OperationHistoryEntry> recent = store.recent();
    assertEquals(3, recent.size());
    assertEquals("core.test-3", recent.get(0).operationId().value());
    assertEquals("core.test-5", recent.get(2).operationId().value());
    assertEquals(5, operations.recentHistory(200).size());
  }

  @Test
  @DisplayName("recent(n) returns last n entries")
  void recentN() {
    OperationHistoryStore store = new OperationHistoryStore(operations, 10);
    for (int i = 1; i <= 5; i++) {
      complete(i);
    }
    List<OperationHistoryEntry> last2 = store.recent(2);
    assertEquals(2, last2.size());
    assertEquals("core.test-4", last2.get(0).operationId().value());
    assertEquals("core.test-5", last2.get(1).operationId().value());
  }

  @Test
  @DisplayName("recent(0) returns empty")
  void recentZero() {
    OperationHistoryStore store = new OperationHistoryStore(operations, 10);
    complete(1);
    assertTrue(store.recent(0).isEmpty());
  }

  @Test
  @DisplayName("recent(n) where n >= size returns full set")
  void recentLargeN() {
    OperationHistoryStore store = new OperationHistoryStore(operations, 10);
    complete(1);
    complete(2);
    assertEquals(2, store.recent(99).size());
  }

  @Test
  @DisplayName("recent(-1) rejects")
  void recentNegativeRejects() {
    OperationHistoryStore store = new OperationHistoryStore(operations);
    assertThrows(IllegalArgumentException.class, () -> store.recent(-1));
  }

  @Test
  @DisplayName("OperationHistoryEntry rejects null")
  void entryRejectsNull() {
    assertThrows(
        NullPointerException.class,
        () ->
            new OperationHistoryEntry(
                null,
                "head",
                Instant.now(),
                Instant.now(),
                OperationOutcome.SUCCESS,
                Optional.empty(),
                InvocationProvenance.systemInternal(Instant.now()),
                Optional.empty()));
  }

  @Test
  @DisplayName("OperationHistoryEntry rejects blank actor")
  void entryRejectsBlankActor() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OperationHistoryEntry(
                new OperationRef("core.test"),
                "  ",
                Instant.now(),
                Instant.now(),
                OperationOutcome.SUCCESS,
                Optional.empty(),
                InvocationProvenance.systemInternal(Instant.now()),
                Optional.empty()));
  }

  @Test
  @DisplayName("OperationHistoryEntry rejects null provenance (slice 490 required field)")
  void entryRejectsNullProvenance() {
    assertThrows(
        NullPointerException.class,
        () ->
            new OperationHistoryEntry(
                new OperationRef("core.test"),
                "head",
                Instant.now(),
                Instant.now(),
                OperationOutcome.SUCCESS,
                Optional.empty(),
                null,
                Optional.empty()));
  }
}
