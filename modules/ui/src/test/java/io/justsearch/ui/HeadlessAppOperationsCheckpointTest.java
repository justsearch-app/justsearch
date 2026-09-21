/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.engine.ShutdownRequest.Reason;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.ShutdownOutcome;
import io.justsearch.core.context.EngineContext;
import io.justsearch.app.api.EngineProcessResources;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HeadlessAppOperationsCheckpointTest {
  @TempDir Path temp;

  @Test
  void checkpointRunsAfterEarlierFailureAndIndexDrainMayRecordOneMoreUnit() throws Exception {
    Path path = temp.resolve("operations.db");
    String key = OperationKeys.generate(Clock.systemUTC());
    long id;
    try (var real = new SqliteOperationStore(path)) {
      var store = spy(real);
      var context = new EngineContext(EngineContext.ClientKind.INTERNAL, "checkpoint-test",
          Optional.empty(), Optional.empty(), "system", "SYSTEM_INTERNAL",
          EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
      id = store.accept(key, OperationDescriptor.invocation(OperationKind.INGEST,
          "core.ingest", "{}", false), context, null).record().id();
      assertTrue(store.start(id));
      assertTrue(store.checkpoint(id, "unit-1", 1, 0));
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
          var statement = connection.createStatement()) {
        statement.execute("UPDATE operations SET updated_at=1");
      }
      var manifest = mock(RuntimeManifestPublisher.class);
      doThrow(new IllegalStateException("earlier failure")).when(manifest).markShutdownPending("restart");
      var index = mock(KnowledgeServerBootstrap.class);
      long rowId = id;
      when(index.closeForUpgrade()).thenAnswer(ignored -> {
        // Independent connection, before final close: the named step must have committed already.
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            var statement = connection.createStatement();
            var row = statement.executeQuery("SELECT updated_at, checkpoint_cursor FROM operations")) {
          assertTrue(row.next());
          assertTrue(row.getLong(1) > 1);
          assertEquals("unit-1", row.getString(2));
        }
        assertTrue(store.checkpoint(rowId, "unit-2", 2, 0));
        return ShutdownOutcome.GRACEFUL;
      });
      var steps = HeadlessApp.orderedShutdownSteps(null, null, null, index, manifest,
          null, null, null, mock(OperationLeaseService.class), mock(EngineAdmissionService.class),
          mock(EngineProcessResources.class), () -> null, store);
      var names = steps.stream().map(EngineShutdownSequence.Step::name).toList();
      assertEquals(names.indexOf(EngineShutdownSequence.INDEX_HALF_STEP) - 1,
          names.indexOf("durable-operations-checkpoint"));
      var result = new EngineShutdownSequence(temp, steps, ignored -> {}).run(Reason.RESTART);
      assertFalse(result.clean());
      assertEquals(java.util.List.of("runtime-manifest"), result.errors());
      var order = inOrder(store, index);
      order.verify(store).checkpointDurableOperations();
      order.verify(index).closeForUpgrade();
      order.verify(store).close();
    }
    try (var reopened = new SqliteOperationStore(path)) {
      var row = reopened.find(key).orElseThrow();
      assertEquals(id, row.id());
      assertEquals(OperationState.RUNNING, row.state());
      assertEquals("unit-2", row.checkpointCursor());
      assertEquals(2, row.unitsCompleted());
      var runner = new OperationAttemptRunnerImpl(reopened, Clock.systemUTC(), Set.of(OperationKind.INGEST));
      runner.reconcile(OperationKind.INGEST, recovered -> {
        assertEquals(row, recovered);
        return new OperationAttemptRunner.Reconciliation.Resume(handle -> {
          assertEquals(row.id(), handle.id());
          handle.checkpoint("unit-3", 3, 0);
          return OperationExecution.finished(OperationResult.success("resumed"));
        });
      });
      assertEquals(OperationState.COMPLETE, reopened.find(key).orElseThrow().state());
      assertEquals(3, reopened.find(key).orElseThrow().unitsCompleted());
      assertEquals(2, reopened.find(key).orElseThrow().attempts());
    }
  }

  @Test
  void checkpointFailureNamesItsOwnStepAndDoesNotPreventIndexDrain() throws Exception {
    var store = mock(OperationStore.class);
    doThrow(new IllegalStateException("checkpoint unavailable")).when(store).checkpointDurableOperations();
    var index = mock(KnowledgeServerBootstrap.class);
    when(index.closeForUpgrade()).thenReturn(ShutdownOutcome.GRACEFUL);
    var steps = HeadlessApp.orderedShutdownSteps(null, null, null, index, null, null, null, null,
        mock(OperationLeaseService.class), mock(EngineAdmissionService.class),
        mock(EngineProcessResources.class), () -> null, store);
    var result = new EngineShutdownSequence(temp, steps, ignored -> {}).run(Reason.QUIT);
    assertFalse(result.clean());
    assertEquals("GRACEFUL", result.workerOutcome());
    assertEquals(java.util.List.of("durable-operations-checkpoint"), result.errors());
    var order = inOrder(store, index);
    order.verify(store).checkpointDurableOperations();
    order.verify(index).closeForUpgrade();
    order.verify(store).close();
  }
}
