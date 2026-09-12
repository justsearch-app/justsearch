/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStoreException;
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
import java.util.concurrent.CompletableFuture;
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
final class OperationChildAcceptanceTest {
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-12T09:00:00Z"), ZoneOffset.UTC);
  private static final EngineContext CONTEXT = new EngineContext(EngineContext.ClientKind.INTERNAL,
      "child-test-client", Optional.of("session-17"), Optional.of("grant-9"), "test-tier",
      "AGENT_LOOP", EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND);
  private static final InvocationProvenance PROVENANCE = new InvocationProvenance(
      TransportTag.AGENT_LOOP, ExecutorTag.AGENT, Optional.of("initiator-17"), CLOCK.instant(),
      Optional.of("intent-17"), Optional.of("correlation-17"));

  @TempDir Path temp;

  @Test
  void childInheritsFrozenRootAndAttributionAndParentWaitsForDurableChild() throws Exception {
    try (var store = store()) {
      var runner = newRunner(store);
      RecordedRootPlan plan = plan();
      OperationAttemptRunner.PreparedAttempt parent = runner.accept(parentRequest(plan));
      AtomicReference<OperationRecordHandle> parentHandle = new AtomicReference<>();
      AtomicReference<OperationAttemptRunner.PreparedAttempt> childAttempt = new AtomicReference<>();
      CompletableFuture<OperationResult> parentOwnOutcome = new CompletableFuture<>();
      CompletableFuture<OperationResult> childOutcome = new CompletableFuture<>();
      AtomicInteger childRuns = new AtomicInteger();
      runner.start(parent, handle -> {
        parentHandle.set(handle);
        var child = runner.acceptIngestChild(handle, plan.roots().get(0));
        childAttempt.set(child);
        runner.start(child, childHandle -> {
          childRuns.incrementAndGet();
          return new OperationExecution(OperationResult.success("child-started"), childOutcome);
        });
        var completion = parentOwnOutcome.thenCombine(child.completion(),
            (own, ignored) -> own);
        return new OperationExecution(OperationResult.success("parent-started"), completion);
      });

      OperationAttemptRunner.PreparedAttempt child = childAttempt.get();
      assertNotNull(child);
      assertFalse(child.existing());
      OperationRecord parentRow = store.find(parent.accepted().key()).orElseThrow();
      OperationRecord childRow = store.find(child.accepted().key()).orElseThrow();
      assertEquals(OperationState.RUNNING, childRow.state());
      assertEquals(parentRow.context(), childRow.context());
      assertEquals(parentRow.executor(), childRow.executor());
      assertEquals(parentRow.initiator(), childRow.initiator());
      assertEquals(parentRow.correlationId(), childRow.correlationId());
      assertEquals(OperationKind.INGEST, childRow.descriptor().kind());
      assertNull(childRow.descriptor().operationRef());
      assertEquals(plan.generation(), childRow.descriptor().recordedRootPlan().generation());
      assertEquals(plan.roots().get(0), childRow.descriptor().recordedRootPlan().roots().get(0));

      var identity = tools.jackson.databind.json.JsonMapper.builder().build()
          .readTree(childRow.descriptor().identityJson());
      assertEquals("ingest-child", identity.path("mode").asText());
      assertEquals(parentRow.key(), identity.path("parentOperationKey").asText());
      assertEquals("root-plan.v1", identity.path("preparedInvocation").path("schema").asText());

      parentOwnOutcome.complete(OperationResult.success("parent-effect-committed"));
      assertEquals(OperationState.RUNNING, store.find(parentRow.key()).orElseThrow().state());
      assertFalse(parent.completion().toCompletableFuture().isDone());

      childOutcome.complete(OperationResult.success("child-committed", "child-execution-1"));
      assertEquals(OperationState.COMPLETE, child.completion().toCompletableFuture().join().state());
      var duplicateTerminal = runner.acceptIngestChild(parentHandle.get(), plan.roots().get(0));
      assertTrue(duplicateTerminal.existing());
      assertEquals(child.accepted().key(), duplicateTerminal.accepted().key());
      assertEquals(OperationState.COMPLETE, duplicateTerminal.accepted().state());
      assertEquals(OperationState.COMPLETE, parent.completion().toCompletableFuture().join().state());
      assertEquals(1, childRuns.get());
    }
  }

  @Test
  void forgedForeignAndOutOfScopeCapabilitiesRefuseBeforeAnyChildEffect() throws Exception {
    try (var store = store()) {
      var runner = newRunner(store);
      RecordedRootPlan plan = plan();
      OperationAttemptRunner.PreparedAttempt parent = runner.accept(parentRequest(plan));
      AtomicReference<OperationRecordHandle> parentHandle = new AtomicReference<>();
      CompletableFuture<OperationResult> parentOutcome = new CompletableFuture<>();
      runner.start(parent, handle -> {
        parentHandle.set(handle);
        return new OperationExecution(OperationResult.success("parent-running"), parentOutcome);
      });

      OperationRecord parentRow = store.find(parent.accepted().key()).orElseThrow();
      OperationRecordHandle forged = new OperationRecordHandle() {
        @Override public long id() { return parentRow.id(); }
        @Override public String key() { return parentRow.key(); }
        @Override public void checkpoint(String cursor, long completed, long failed) {}
      };
      assertChildRefused(() -> runner.acceptIngestChild(forged, plan.roots().get(0)));

      var foreignRunner = newRunner(store);
      assertChildRefused(() -> foreignRunner.acceptIngestChild(parentHandle.get(), plan.roots().get(0)));

      Path outsidePath = temp.resolve("outside");
      RecordedRootPlan.Root outside = root(outsidePath, "docs", false, List.of(), List.of());
      assertChildRefused(() -> runner.acceptIngestChild(parentHandle.get(), outside));
      RecordedRootPlan.Root changedPolicy = root(plan.roots().get(0).path(), "other-collection", false,
          plan.roots().get(0).excludePatterns(), plan.roots().get(0).excludedSubtrees());
      assertChildRefused(() -> runner.acceptIngestChild(parentHandle.get(), changedPolicy));

      assertEquals(1, store.openRecords().size());
      parentOutcome.complete(OperationResult.success("parent-done"));
      assertEquals(OperationState.COMPLETE, parentRowState(store, parentRow.key()));
    }
  }

  @Test
  void childAdmissionRefusalFromParentBodyStoresTypedFailureCode() throws Exception {
    try (var store = store()) {
      var runner = newRunner(store);
      RecordedRootPlan plan = plan();
      OperationAttemptRunner.PreparedAttempt parent = runner.accept(parentRequest(plan));
      assertThrows(OperationStoreException.class, () -> runner.start(parent, handle -> {
        OperationRecordHandle forged = new OperationRecordHandle() {
          @Override public long id() { return handle.id(); }
          @Override public String key() { return handle.key(); }
          @Override public void checkpoint(String cursor, long completed, long failed) {}
        };
        runner.acceptIngestChild(forged, plan.roots().get(0));
        return OperationExecution.finished(OperationResult.success("unreachable"));
      }));
      OperationRecord failed = store.find(parent.accepted().key()).orElseThrow();
      assertEquals(OperationState.FAILED, failed.state());
      assertEquals(OperationStoreException.Code.CHILD_ACCEPTANCE_REFUSED.name(), failed.failureReason());
      assertEquals(OperationStoreException.Code.CHILD_ACCEPTANCE_REFUSED.name(), failed.receipt().code());

      OperationAttemptRunner.PreparedAttempt asyncParent = runner.accept(parentRequest(plan));
      CompletableFuture<OperationResult> asyncFailure = new CompletableFuture<>();
      runner.start(asyncParent, handle -> {
        OperationRecordHandle forged = new OperationRecordHandle() {
          @Override public long id() { return handle.id(); }
          @Override public String key() { return handle.key(); }
          @Override public void checkpoint(String cursor, long completed, long failed) {}
        };
        try {
          runner.acceptIngestChild(forged, plan.roots().get(0));
        } catch (OperationStoreException refusal) {
          asyncFailure.completeExceptionally(refusal);
        }
        return new OperationExecution(OperationResult.success("parent-async"), asyncFailure);
      });
      OperationRecord asyncFailed = store.find(asyncParent.accepted().key()).orElseThrow();
      assertEquals(OperationState.FAILED, asyncFailed.state());
      assertEquals(OperationStoreException.Code.CHILD_ACCEPTANCE_REFUSED.name(), asyncFailed.failureReason());
      assertEquals(OperationStoreException.Code.CHILD_ACCEPTANCE_REFUSED.name(), asyncFailed.receipt().code());
    }
  }

  @Test
  void directStoreRequiresRunningParentAndRejectsAbsentTerminalOrAuditMismatchedChild() throws Exception {
    try (var store = store()) {
      RecordedRootPlan.Root root = plan().roots().get(0);
      String absent = OperationKeys.generate(CLOCK);
      assertChildRefused(() -> store.acceptIngestChild(absent, OperationKeys.generate(CLOCK), root));

      String unstartedKey = OperationKeys.generate(CLOCK);
      OperationRecord unstarted = store.accept(unstartedKey, parentDescriptor(plan()), CONTEXT, PROVENANCE).record();
      assertChildRefused(() -> store.acceptIngestChild(unstarted.key(), OperationKeys.generate(CLOCK), root));

      String terminalKey = OperationKeys.generate(CLOCK);
      OperationRecord terminal = store.accept(terminalKey, parentDescriptor(plan()), CONTEXT, PROVENANCE).record();
      assertTrue(store.finish(terminal.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null)).isPresent());
      assertChildRefused(() -> store.acceptIngestChild(terminal.key(), OperationKeys.generate(CLOCK), root));

      String runningKey = OperationKeys.generate(CLOCK);
      OperationRecord running = store.accept(runningKey, parentDescriptor(plan()), CONTEXT, PROVENANCE).record();
      assertTrue(store.start(running.id()));
      String childKey = OperationKeys.generate(CLOCK);
      store.acceptIngestChild(running.key(), childKey, root);
      assertEquals(childKey, store.find(childKey).orElseThrow().key());
      execute("UPDATE operations SET client_id = 'tampered-client' WHERE operation_key = '" + childKey + "'");
      assertChildRefused(() -> store.acceptIngestChild(running.key(), childKey, root));
    }
  }

  @Test
  void parallelDuplicateAcceptanceCreatesOneChildAndExecutesOneBody() throws Exception {
    try (var store = store(); var pool = Executors.newFixedThreadPool(8)) {
      var runner = newRunner(store);
      RecordedRootPlan plan = plan();
      OperationAttemptRunner.PreparedAttempt parent = runner.accept(parentRequest(plan));
      AtomicReference<OperationRecordHandle> parentHandle = new AtomicReference<>();
      CompletableFuture<OperationResult> parentOutcome = new CompletableFuture<>();
      runner.start(parent, handle -> {
        parentHandle.set(handle);
        return new OperationExecution(OperationResult.success("parent-running"), parentOutcome);
      });
      CountDownLatch gate = new CountDownLatch(1);
      var futures = new java.util.ArrayList<Future<OperationAttemptRunner.PreparedAttempt>>();
      for (int i = 0; i < 16; i++) {
        futures.add(pool.submit(() -> {
          assertTrue(gate.await(5, TimeUnit.SECONDS));
          return runner.acceptIngestChild(parentHandle.get(), plan.roots().get(0));
        }));
      }
      gate.countDown();
      var attempts = new java.util.ArrayList<OperationAttemptRunner.PreparedAttempt>();
      for (var future : futures) attempts.add(future.get(5, TimeUnit.SECONDS));
      Set<String> keys = attempts.stream().map(attempt -> attempt.accepted().key()).collect(java.util.stream.Collectors.toSet());
      assertEquals(1, keys.size());
      assertEquals(1, attempts.stream().filter(attempt -> !attempt.existing()).count());

      AtomicInteger bodies = new AtomicInteger();
      for (var attempt : attempts) {
        runner.start(attempt, handle -> {
          bodies.incrementAndGet();
          return OperationExecution.finished(OperationResult.success("one-child-body"));
        });
      }
      assertEquals(1, bodies.get());
      assertEquals(1, store.find(keys.iterator().next()).orElseThrow().attempts());
      parentOutcome.complete(OperationResult.success("parent-done"));
    }
  }

  @Test
  void storageRefusalLeavesParentRunningAndDoesNotReachChildBody() throws Exception {
    try (var store = store()) {
      var runner = newRunner(store);
      RecordedRootPlan plan = plan();
      OperationAttemptRunner.PreparedAttempt parent = runner.accept(parentRequest(plan));
      AtomicReference<OperationRecordHandle> parentHandle = new AtomicReference<>();
      CompletableFuture<OperationResult> parentOutcome = new CompletableFuture<>();
      runner.start(parent, handle -> {
        parentHandle.set(handle);
        return new OperationExecution(OperationResult.success("parent-running"), parentOutcome);
      });
      execute("CREATE TRIGGER refuse_ingest_child BEFORE INSERT ON operations "
          + "WHEN NEW.kind = 'ingest' BEGIN SELECT RAISE(ABORT, 'fixture'); END");

      assertEquals(OperationStoreException.Code.STORAGE_FAILED,
          assertThrows(OperationStoreException.class,
              () -> runner.acceptIngestChild(parentHandle.get(), plan.roots().get(0))).code());
      assertEquals(OperationState.RUNNING, store.find(parent.accepted().key()).orElseThrow().state());
      assertEquals(1, store.openRecords().size());
      parentOutcome.complete(OperationResult.success("parent-done"));
    }
  }

  @Test
  void reopenReconcilesParentFirstWithoutRerunningExistingChild() throws Exception {
    reopenAndReconcile(true);
  }

  @Test
  void childStorageRefusalInParentBodyRetainsTypedDurableFailure() throws Exception {
    childStorageFailureReceipt(false);
  }

  @Test
  void childStorageRefusalInParentCompletionRetainsTypedDurableFailure() throws Exception {
    childStorageFailureReceipt(true);
  }

  private void childStorageFailureReceipt(boolean asynchronous) throws Exception {
    try (var store = store()) {
      var runner = newRunner(store);
      RecordedRootPlan plan = plan();
      var parent = runner.accept(parentRequest(plan));
      execute("CREATE TRIGGER refuse_ingest_child BEFORE INSERT ON operations "
          + "WHEN NEW.kind = 'ingest' BEGIN SELECT RAISE(ABORT, 'fixture'); END");
      if (asynchronous) {
        CompletableFuture<OperationResult> completion = new CompletableFuture<>();
        AtomicReference<OperationRecordHandle> parentHandle = new AtomicReference<>();
        runner.start(parent, handle -> {
          parentHandle.set(handle);
          return new OperationExecution(OperationResult.success("parent-running"), completion);
        });
        var refusal = assertThrows(OperationStoreException.class,
            () -> runner.acceptIngestChild(parentHandle.get(), plan.roots().get(0)));
        completion.completeExceptionally(refusal);
      } else {
        assertThrows(OperationStoreException.class, () -> runner.start(parent, handle -> {
          runner.acceptIngestChild(handle, plan.roots().get(0));
          throw new AssertionError("a refused acceptance must not reach child execution");
        }));
      }
      OperationRecord failed = parent.completion().toCompletableFuture().join();
      assertEquals(OperationState.FAILED, failed.state());
      assertEquals(OperationStoreException.Code.STORAGE_FAILED.name(), failed.failureReason());
      assertEquals(OperationStoreException.Code.STORAGE_FAILED.name(), failed.receipt().code());
      assertTrue(store.openRecords().isEmpty());
    }
  }

  @Test
  void reopenReconcilesIngestFirstWithoutRerunningExistingChild() throws Exception {
    reopenAndReconcile(false);
  }

  @Test
  void childMissingAtRunnerBootGetsNormalStartAndIsAbsentFromLaterReconciliation() throws Exception {
    Path db = temp.resolve("missing-child.db");
    RecordedRootPlan plan = plan();
    String parentKey = OperationKeys.generate(CLOCK);
    try (var store = new SqliteOperationStore(db, CLOCK, step -> {})) {
      OperationRecord parent = store.accept(parentKey, parentDescriptor(plan), CONTEXT, PROVENANCE).record();
      assertTrue(store.start(parent.id()));
    }

    try (var store = new SqliteOperationStore(db, CLOCK, step -> {})) {
      var runner = newRunner(store);
      AtomicReference<OperationAttemptRunner.PreparedAttempt> createdChild = new AtomicReference<>();
      AtomicInteger childRuns = new AtomicInteger();
      runner.reconcile(OperationKind.REINDEX, row -> new OperationAttemptRunner.Reconciliation.Resume(handle -> {
        var child = runner.acceptIngestChild(handle, plan.roots().get(0));
        assertFalse(child.existing());
        createdChild.set(child);
        runner.start(child, childHandle -> {
          childRuns.incrementAndGet();
          return OperationExecution.finished(OperationResult.success("new-child"));
        });
        return OperationExecution.finished(OperationResult.success("resumed-parent"));
      }));

      assertTrue(createdChild.get() != null);
      assertEquals(OperationState.COMPLETE, createdChild.get().completion().toCompletableFuture().join().state());
      assertEquals(1, childRuns.get());
      assertEquals(OperationState.COMPLETE, store.find(parentKey).orElseThrow().state());
      runner.reconcile(OperationKind.INGEST, row -> {
        throw new AssertionError("a child created after runner boot must not be reconciled again");
      });
      assertEquals(1, childRuns.get());
    }
  }

  @Test
  void lostParentCapabilityRefusesChildAfterParentCompletionPersistenceFailure() throws Exception {
    try (var store = store()) {
      var runner = newRunner(store);
      RecordedRootPlan plan = plan();
      OperationAttemptRunner.PreparedAttempt parent = runner.accept(parentRequest(plan));
      AtomicReference<OperationRecordHandle> parentHandle = new AtomicReference<>();
      CompletableFuture<OperationResult> parentOutcome = new CompletableFuture<>();
      runner.start(parent, handle -> {
        parentHandle.set(handle);
        return new OperationExecution(OperationResult.success("parent-running"), parentOutcome);
      });
      long parentId = parent.accepted().id();
      execute("CREATE TRIGGER refuse_parent_complete BEFORE UPDATE ON operations "
          + "WHEN OLD.id = " + parentId + " AND NEW.state = 'COMPLETE' "
          + "BEGIN SELECT RAISE(ABORT, 'fixture'); END");

      parentOutcome.complete(OperationResult.success("parent-complete"));
      assertThrows(java.util.concurrent.CompletionException.class,
          () -> parent.completion().toCompletableFuture().join());
      assertEquals(OperationState.RUNNING, store.find(parent.accepted().key()).orElseThrow().state());
      assertChildRefused(() -> runner.acceptIngestChild(parentHandle.get(), plan.roots().get(0)));
      assertEquals(1, store.openRecords().size());
    }
  }

  private void reopenAndReconcile(boolean parentFirst) throws Exception {
    Path db = temp.resolve(parentFirst ? "parent-first.db" : "child-first.db");
    RecordedRootPlan plan = plan();
    String parentKey;
    String childKey;
    try (var store = new SqliteOperationStore(db, CLOCK, step -> {})) {
      parentKey = OperationKeys.generate(CLOCK);
      OperationRecord parent = store.accept(parentKey, parentDescriptor(plan()), CONTEXT, PROVENANCE).record();
      assertTrue(store.start(parent.id()));
      childKey = OperationKeys.generate(CLOCK);
      store.acceptIngestChild(parentKey, childKey, plan.roots().get(0));
    }

    try (var store = new SqliteOperationStore(db, CLOCK, step -> {})) {
      var runner = newRunner(store);
      OperationRecord persistedChild = store.find(childKey).orElseThrow();
      AtomicInteger parentRuns = new AtomicInteger();
      AtomicInteger childRuns = new AtomicInteger();
      AtomicReference<OperationAttemptRunner.PreparedAttempt> resumedChild = new AtomicReference<>();
      CompletableFuture<OperationResult> parentOutcome = new CompletableFuture<>();
      OperationAttemptRunner.PreparedAttempt duplicateChild = runner.accept(
          new OperationAttemptRunner.Request(childKey, persistedChild.descriptor(), persistedChild.context(), null));
      runner.start(duplicateChild, handle -> {
        throw new AssertionError("a preexisting child must not run through normal start");
      });

      Runnable reconcileParent = () -> runner.reconcile(OperationKind.REINDEX,
          row -> new OperationAttemptRunner.Reconciliation.Resume(handle -> {
            parentRuns.incrementAndGet();
            var child = runner.acceptIngestChild(handle,
                persistedChild.descriptor().recordedRootPlan().roots().get(0));
            assertTrue(child.existing());
            assertEquals(childKey, child.accepted().key());
            resumedChild.set(child);
            var completion = parentOutcome.thenCombine(child.completion(), (own, ignored) -> own);
            return new OperationExecution(OperationResult.success("resumed-parent"), completion);
          }));
      Runnable reconcileChild = () -> runner.reconcile(OperationKind.INGEST,
          row -> new OperationAttemptRunner.Reconciliation.Resume(handle -> {
            childRuns.incrementAndGet();
            return OperationExecution.finished(OperationResult.success("resumed-child"));
          }));

      if (parentFirst) {
        reconcileParent.run();
        assertEquals(OperationState.RUNNING, store.find(parentKey).orElseThrow().state());
        reconcileChild.run();
      } else {
        reconcileChild.run();
        reconcileParent.run();
      }
      assertNotNull(resumedChild.get());
      assertEquals(1, childRuns.get());
      assertEquals(OperationState.COMPLETE, store.find(childKey).orElseThrow().state());
      parentOutcome.complete(OperationResult.success("parent-after-child"));
      assertEquals(OperationState.COMPLETE, store.find(parentKey).orElseThrow().state());
      assertEquals(1, parentRuns.get());
      reconcileChild.run();
      reconcileParent.run();
      assertEquals(1, childRuns.get());
      assertEquals(1, parentRuns.get());
    }
  }

  private SqliteOperationStore store() throws Exception {
    return new SqliteOperationStore(temp.resolve("operations.db"), CLOCK, step -> {});
  }

  private static OperationAttemptRunnerImpl newRunner(SqliteOperationStore store) {
    return new OperationAttemptRunnerImpl(store, CLOCK, Set.of(OperationKind.REINDEX, OperationKind.INGEST));
  }

  private static OperationAttemptRunner.Request parentRequest(RecordedRootPlan plan) {
    return new OperationAttemptRunner.Request(OperationKeys.generate(CLOCK), parentDescriptor(plan), CONTEXT, PROVENANCE);
  }

  private static OperationDescriptor parentDescriptor(RecordedRootPlan plan) {
    return OperationDescriptor.preparedInvocation(OperationKind.REINDEX, "core.child-fixture", "{}",
        RecordedRootPlan.SCHEMA, plan.toReplayPayload());
  }

  private static RecordedRootPlan plan() {
    Path base = Path.of("child-acceptance-root").toAbsolutePath().normalize();
    return new RecordedRootPlan("generation-child-17", List.of(root(base, "docs", false,
        List.of("*.tmp", "*.partial"), List.of(base.resolve("ignored")))));
  }

  private static RecordedRootPlan.Root root(Path path, String collection, boolean singleFile,
      List<String> patterns, List<Path> exclusions) {
    return new RecordedRootPlan.Root(path, collection, true, singleFile, patterns, exclusions);
  }

  private static OperationState parentRowState(SqliteOperationStore store, String key) {
    return store.find(key).orElseThrow().state();
  }

  private static void assertChildRefused(org.junit.jupiter.api.function.Executable operation) {
    var failure = assertThrows(OperationStoreException.class, operation);
    assertEquals(OperationStoreException.Code.CHILD_ACCEPTANCE_REFUSED, failure.code());
  }

  private void execute(String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("operations.db"));
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
