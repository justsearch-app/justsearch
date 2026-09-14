/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.operations.OperationAttemptRunner;
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
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
final class OperationIngestChildAcceptanceTest {
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-14T09:00:00Z"),
      ZoneOffset.UTC);
  private static final EngineContext CONTEXT = new EngineContext(EngineContext.ClientKind.INTERNAL,
      "child-test-client", Optional.of("session-17"), Optional.of("grant-9"), "test-tier",
      "AGENT_LOOP", EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
  private static final InvocationProvenance PROVENANCE = new InvocationProvenance(
      TransportTag.AGENT_LOOP, ExecutorTag.AGENT, Optional.of("initiator-17"), CLOCK.instant(),
      Optional.of("intent-17"), Optional.of("correlation-17"));

  @TempDir Path temp;

  @Test
  void childInheritsFrozenRootAndAttributionAndParentWaitsForDurableChild() throws Exception {
    try (var store = store()) {
      var plan = plan();
      var runner = newRunner(store);
      var parent = acceptParent(runner, plan);
      var parentHandle = new AtomicReference<OperationRecordHandle>();
      var childAttempt = new AtomicReference<OperationAttemptRunner.PreparedAttempt>();
      var parentOutcome = new CompletableFuture<OperationResult>();
      var childOutcome = new CompletableFuture<OperationResult>();
      var childRuns = new AtomicInteger();

      runner.start(parent.attempt(), handle -> {
        parentHandle.set(handle);
        var child = runner.acceptIngestChild(handle, plan.roots().get(0));
        childAttempt.set(child);
        runner.start(child, childHandle -> {
          childRuns.incrementAndGet();
          return new OperationExecution(OperationResult.success("child-started"), childOutcome);
        });
        return new OperationExecution(OperationResult.success("parent-started"),
            parentOutcome.thenCombine(child.completion(), (own, ignored) -> own));
      });

      OperationAttemptRunner.PreparedAttempt child = childAttempt.get();
      assertNotNull(child);
      assertFalse(child.existing());
      OperationRecord parentRow = store.find(parent.attempt().accepted().key()).orElseThrow();
      OperationRecord childRow = store.find(child.accepted().key()).orElseThrow();
      assertEquals(OperationState.RUNNING, childRow.state());
      assertEquals(parentRow.context(), childRow.context());
      assertEquals(parentRow.executor(), childRow.executor());
      assertEquals(parentRow.initiator(), childRow.initiator());
      assertEquals(parentRow.correlationId(), childRow.correlationId());
      assertEquals(parentRow.provenanceOccurredAt(), childRow.provenanceOccurredAt());
      assertEquals(OperationKind.INGEST, childRow.descriptor().kind());
      assertNull(childRow.descriptor().operationRef());
      assertEquals(OperationHistoryMode.NONE, childRow.historyMode());
      assertFalse(childRow.descriptor().identityJson().contains(plan.roots().get(0).path().toString()));
      var childPreparation = store.acceptedPreparation(childRow.id()).orElseThrow();
      assertEquals(new RecordedIngestChild(parentRow.key(),
          new RecordedRootPlan(plan.generation(), List.of(plan.roots().get(0)))),
          RecordedIngestChild.from(childRow.descriptor(), childPreparation.payload()));

      parentOutcome.complete(OperationResult.success("parent-effect-committed"));
      assertEquals(OperationState.RUNNING, store.find(parentRow.key()).orElseThrow().state());
      assertFalse(parent.attempt().completion().toCompletableFuture().isDone());

      childOutcome.complete(OperationResult.success("child-committed", "child-execution-1"));
      assertEquals(OperationState.COMPLETE, child.completion().toCompletableFuture().join().state());
      var duplicateTerminal = runner.acceptIngestChild(parentHandle.get(), plan.roots().get(0));
      assertTrue(duplicateTerminal.existing());
      assertEquals(child.accepted().key(), duplicateTerminal.accepted().key());
      assertEquals(OperationState.COMPLETE, duplicateTerminal.accepted().state());
      assertEquals(OperationState.COMPLETE, parent.attempt().completion().toCompletableFuture().join().state());
      assertEquals(1, childRuns.get());
    }
  }

  @Test
  void forgedForeignAndOutOfScopeCapabilitiesRefuseBeforeAnyChildEffect() throws Exception {
    try (var store = store()) {
      var plan = plan();
      var runner = newRunner(store);
      var parent = acceptParent(runner, plan);
      var parentHandle = new AtomicReference<OperationRecordHandle>();
      var parentOutcome = new CompletableFuture<OperationResult>();
      runner.start(parent.attempt(), handle -> {
        parentHandle.set(handle);
        return new OperationExecution(OperationResult.success("parent-running"), parentOutcome);
      });

      OperationRecord parentRow = store.find(parent.attempt().accepted().key()).orElseThrow();
      OperationRecordHandle forged = new OperationRecordHandle() {
        @Override public long id() { return parentRow.id(); }
        @Override public String key() { return parentRow.key(); }
        @Override public void checkpoint(String cursor, long completed, long failed) {}
      };
      assertChildRefused(() -> runner.acceptIngestChild(forged, plan.roots().get(0)));

      var foreignRunner = newRunner(store);
      assertChildRefused(() -> foreignRunner.acceptIngestChild(parentHandle.get(), plan.roots().get(0)));

      var outside = new RecordedRootPlan.Root(temp.resolve("outside"), "docs", false, false,
          List.of("*.tmp"), List.of());
      assertChildRefused(() -> runner.acceptIngestChild(parentHandle.get(), outside));
      var changedPolicy = new RecordedRootPlan.Root(plan.roots().get(0).path(), "other-collection",
          plan.roots().get(0).force(), plan.roots().get(0).singleFile(),
          plan.roots().get(0).excludePatterns(), plan.roots().get(0).excludedSubtrees());
      assertChildRefused(() -> runner.acceptIngestChild(parentHandle.get(), changedPolicy));

      parentOutcome.complete(OperationResult.success("parent-done"));
      assertEquals(OperationState.COMPLETE, store.find(parentRow.key()).orElseThrow().state());
    }
  }

  @Test
  void preparationScopeAndLostParentCapabilityRefuseChildAcceptance() throws Exception {
    try (var store = store()) {
      var plan = plan();
      var runner = newRunner(store);
      var parent = acceptParent(runner, plan);
      var parentHandle = new AtomicReference<OperationRecordHandle>();
      var parentOutcome = new CompletableFuture<OperationResult>();
      assertThrows(IllegalStateException.class, () -> runner.start(parent.attempt(), handle -> {
        parentHandle.set(handle);
        runner.withPreparation(parent.request(), scope -> {
          runner.acceptIngestChild(handle, plan.roots().get(0));
          return null;
        });
        return new OperationExecution(OperationResult.success("unreachable"), parentOutcome);
      }));
      assertEquals(OperationState.FAILED, store.find(parent.attempt().accepted().key()).orElseThrow().state());

      var second = acceptParent(runner, plan);
      var secondHandle = new AtomicReference<OperationRecordHandle>();
      var secondOutcome = new CompletableFuture<OperationResult>();
      runner.start(second.attempt(), handle -> {
        secondHandle.set(handle);
        return new OperationExecution(OperationResult.success("parent-running"), secondOutcome);
      });
      execute("CREATE TRIGGER refuse_parent_complete BEFORE UPDATE ON operations WHEN OLD.id = "
          + second.attempt().accepted().id() + " AND NEW.state = 'COMPLETE' "
          + "BEGIN SELECT RAISE(ABORT, 'fixture'); END");
      secondOutcome.complete(OperationResult.success("parent-complete"));
      assertThrows(CompletionException.class, () -> second.attempt().completion().toCompletableFuture().join());
      assertChildRefused(() -> runner.acceptIngestChild(secondHandle.get(), plan.roots().get(0)));
    }
  }

  @Test
  void directStoreRequiresRunningParentAndExactPreparationWitness() throws Exception {
    try (var store = store()) {
      var plan = plan();
      var unstarted = acceptDirectParent(store, plan);
      assertChildRefused(() -> store.acceptIngestChild(unstarted.record().key(), OperationKeys.generate(CLOCK),
          unstarted.preparation(), plan));

      var terminal = acceptDirectParent(store, plan);
      assertTrue(store.finish(terminal.record().id(), OperationState.COMPLETE,
          new OperationReceipt("SUCCESS", null)).isPresent());
      assertChildRefused(() -> store.acceptIngestChild(terminal.record().key(), OperationKeys.generate(CLOCK),
          terminal.preparation(), plan));

      var running = acceptDirectParent(store, plan);
      assertTrue(store.start(running.record().id()));
      var child = store.acceptIngestChild(running.record().key(), OperationKeys.generate(CLOCK),
          running.preparation(), plan);
      assertTrue(child.created());
      OperationRecord childRow = child.record();
      assertEquals(running.record().context(), childRow.context());
      assertEquals(running.record().executor(), childRow.executor());
      assertEquals(running.record().initiator(), childRow.initiator());
      assertEquals(running.record().correlationId(), childRow.correlationId());

      var stale = acceptDirectParent(store, plan);
      assertTrue(store.start(stale.record().id()));
      updatePreparationPayload(stale.record().key(), plan(ROOT_PATH.resolve("changed"), "docs", false)
          .toReplayPayload());
      assertChildRefused(() -> store.acceptIngestChild(stale.record().key(), OperationKeys.generate(CLOCK),
          stale.preparation(), plan));
    }
  }

  @Test
  void concurrentRunnerAcceptanceFindsOneChildAndOneEffect() throws Exception {
    try (var store = store(); var pool = Executors.newFixedThreadPool(8)) {
      var plan = plan();
      var runner = newRunner(store);
      var parent = acceptParent(runner, plan);
      var parentHandle = new AtomicReference<OperationRecordHandle>();
      var parentOutcome = new CompletableFuture<OperationResult>();
      runner.start(parent.attempt(), handle -> {
        parentHandle.set(handle);
        return new OperationExecution(OperationResult.success("parent-running"), parentOutcome);
      });
      var gate = new CountDownLatch(1);
      var futures = new java.util.ArrayList<Future<OperationAttemptRunner.PreparedAttempt>>();
      for (int i = 0; i < 8; i++) {
        futures.add(pool.submit(() -> {
          assertTrue(gate.await(5, TimeUnit.SECONDS));
          return runner.acceptIngestChild(parentHandle.get(), plan.roots().get(0));
        }));
      }
      gate.countDown();
      var attempts = new java.util.ArrayList<OperationAttemptRunner.PreparedAttempt>();
      for (var future : futures) attempts.add(future.get(5, TimeUnit.SECONDS));
      Set<String> keys = attempts.stream().map(attempt -> attempt.accepted().key())
          .collect(java.util.stream.Collectors.toSet());
      assertEquals(1, keys.size());
      assertEquals(1, attempts.stream().filter(attempt -> !attempt.existing()).count());

      var childRuns = new AtomicInteger();
      for (var attempt : attempts) {
        runner.start(attempt, handle -> {
          childRuns.incrementAndGet();
          return OperationExecution.finished(OperationResult.success("one-child-body"));
        });
      }
      assertEquals(1, childRuns.get());
      assertEquals(1, store.find(keys.iterator().next()).orElseThrow().attempts());
      parentOutcome.complete(OperationResult.success("parent-done"));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(value = OperationState.class,
      names = {"RUNNING", "COMPLETE_WITH_GAPS"})
  void terminalChildSurvivesClockAdvancePruneAndParentReopenRetry(OperationState parentState) throws Exception {
    OperationTestClock clock = new OperationTestClock(CLOCK.millis());
    Path db = temp.resolve("retained-child.db");
    var plan = plan();
    String parentKey;
    OperationStore.Preparation parentPreparation;
    String childKey;
    try (var store = new SqliteOperationStore(db, clock, step -> {})) {
      var parent = acceptDirectParent(store, plan);
      parentKey = parent.record().key();
      parentPreparation = parent.preparation();
      assertTrue(store.start(parent.record().id()));
      childKey = OperationKeys.generate(clock);
      var child = store.acceptIngestChild(parentKey, childKey, parentPreparation, plan).record();
      assertTrue(store.start(child.id()));
      assertTrue(store.finish(child.id(), OperationState.COMPLETE,
          new OperationReceipt("SUCCESS", "child-effect")).isPresent());
      if (parentState == OperationState.COMPLETE_WITH_GAPS) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
            var update = connection.prepareStatement("UPDATE operations SET state = ? WHERE operation_key = ?")) {
          update.setString(1, parentState.name()); update.setString(2, parentKey);
          assertEquals(1, update.executeUpdate());
        }
      }
      clock.setMillis(clock.millis() + OperationStore.HISTORY_RETENTION.toMillis() + 1);
      store.pruneHistory();
      assertTrue(store.find(childKey).isPresent(), "an open parent retains its completed child");
    }

    try (var reopened = new SqliteOperationStore(db, clock, step -> {})) {
      assertTrue(reopened.find(parentKey).isPresent());
      assertTrue(reopened.find(childKey).isPresent());
      var retry = reopened.acceptIngestChild(parentKey, OperationKeys.generate(clock),
          parentPreparation, plan);
      assertFalse(retry.created());
      assertEquals(childKey, retry.record().key());
      assertEquals(OperationState.COMPLETE, retry.record().state());
      assertTrue(reopened.finish(reopened.find(parentKey).orElseThrow().id(), OperationState.COMPLETE,
          new OperationReceipt("SUCCESS", null)).isPresent());
      reopened.pruneHistory();
      assertTrue(reopened.find(childKey).isEmpty(), "terminal parent releases aged child retention");
    }
  }

  private SqliteOperationStore store() throws Exception {
    return new SqliteOperationStore(temp.resolve("operations.db"), CLOCK, step -> {});
  }

  private static OperationAttemptRunnerImpl newRunner(SqliteOperationStore store) {
    return new OperationAttemptRunnerImpl(store, CLOCK,
        Set.of(OperationKind.REINDEX, OperationKind.INGEST), null,
        (parent, preparation) -> RecordedRootPlan.fromReplayPayload(preparation.payload().value()));
  }

  private ParentFixture acceptParent(OperationAttemptRunnerImpl runner, RecordedRootPlan plan) {
    String key = OperationKeys.generate(CLOCK);
    var descriptor = OperationDescriptor.invocation(OperationKind.REINDEX, "core.recorded-child",
        "{}", false);
    var request = new OperationAttemptRunner.Request(key, descriptor, CONTEXT, PROVENANCE);
    var preparation = new OperationStore.Preparation(UUID.randomUUID(),
        new OperationPreparedPayload(false, plan.toReplayPayload()));
    assertTrue(runner.savePreparation(request, preparation).isPresent());
    return new ParentFixture(request, preparation, runner.acceptPrepared(request, preparation.nonce()));
  }

  private DirectParent acceptDirectParent(SqliteOperationStore store, RecordedRootPlan plan)
      throws Exception {
    String key = OperationKeys.generate(CLOCK);
    var descriptor = OperationDescriptor.invocation(OperationKind.REINDEX, "core.recorded-child",
        "{}", false);
    var preparation = new OperationStore.Preparation(UUID.randomUUID(),
        new OperationPreparedPayload(false, plan.toReplayPayload()));
    assertTrue(store.savePreparation(key, descriptor, preparation).isPresent());
    OperationRecord record = store.acceptPrepared(key, descriptor, CONTEXT, PROVENANCE,
        preparation.nonce()).record();
    return new DirectParent(record, preparation);
  }

  private static RecordedRootPlan plan() {
    return plan(Path.of("child-acceptance-root").toAbsolutePath().normalize(), "docs", false);
  }

  private static RecordedRootPlan plan(Path path, String collection, boolean singleFile) {
    return new RecordedRootPlan("generation-child-17", List.of(new RecordedRootPlan.Root(path,
        collection, true, singleFile, List.of("*.tmp", "*.partial"), List.of())));
  }

  private static final Path ROOT_PATH = Path.of("child-acceptance-root").toAbsolutePath().normalize();

  private void updatePreparationPayload(String key, String payload) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("operations.db"));
        var update = connection.prepareStatement(
            "UPDATE operations SET preparation_payload = ? WHERE operation_key = ?")) {
      update.setString(1, payload);
      update.setString(2, key);
      assertEquals(1, update.executeUpdate());
    }
  }

  private void execute(String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("operations.db"));
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static void assertChildRefused(org.junit.jupiter.api.function.Executable operation) {
    var failure = assertThrows(OperationStoreException.class, operation);
    assertEquals(OperationStoreException.Code.CHILD_ACCEPTANCE_REFUSED, failure.code());
  }

  private record ParentFixture(OperationAttemptRunner.Request request,
      OperationStore.Preparation preparation, OperationAttemptRunner.PreparedAttempt attempt) {}

  private record DirectParent(OperationRecord record, OperationStore.Preparation preparation) {}
}
