/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.core.component.ComponentRecoveryAction;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ipc.WorkerFatalReasonMarker;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OwnershipRefusalRecoveryTest {
  private static final String CODE = LifecycleReasonCode.INDEX_OWNERSHIP_UNPROVEN.code();

  @Test
  void ownershipRefusalDoesNotAdmitAutomaticOrManualRecovery() {
    for (boolean essential : new boolean[] {true, false}) {
      try (var executors = TestEngineExecutors.awaitingTermination();
          var components = new TestEngineComponents()) {
        var index = components.register(new ComponentSpec("index", essential, Set.of(),
            ComponentSpec.ComposeCapability.IN_PLACE, Duration.ZERO, 2));
        index.transition(ComponentState.FAILED, CODE, "Folder /index/foreign; Evidence: missing");
        var calls = new AtomicInteger();
        var escalations = new AtomicInteger();
        var bootstrap = mock(KnowledgeServerBootstrap.class);
        try (var monitor = new KnowledgeServerHealthMonitor(executors, bootstrap)) {
          monitor.componentRegistry(components);
          monitor.componentRecoveryBindings(Map.of("index", new ComponentRecoveryBinding(index,
              request -> {
                calls.incrementAndGet();
                return ComponentRecoveryAction.Result.REFUSED;
              })), ignored -> escalations.incrementAndGet());
          assertEquals(ComponentRecoveryAuthority.Outcome.NOT_APPLICABLE,
              monitor.requestComponentRecovery("index"));
          monitor.tick();
          assertFalse(monitor.recoveryAttemptRunningForTest());
          assertEquals(0, calls.get());
          assertEquals(0, escalations.get());
          assertEquals(0, index.snapshot().recoveryAttempts());
        }
      }
    }
  }

  @Test
  void ownershipRefusalDoesNotRestartEngineAfterBudgetAndOwnerDeadline() {
    for (boolean initialOwnerPending : new boolean[] {false, true}) {
      try (var executors = TestEngineExecutors.awaitingTermination();
          var components = new TestEngineComponents()) {
        var index = components.register(new ComponentSpec("index", true, Set.of(),
            ComponentSpec.ComposeCapability.IN_PLACE, Duration.ofMinutes(1), 0));
        String detail = "Folder /index/foreign; Evidence: malformed";
        index.transition(ComponentState.FAILED, CODE, detail);
        var now = new AtomicLong(1_000L);
        var escalations = new AtomicInteger();
        var bootstrap = mock(KnowledgeServerBootstrap.class);
        try (var monitor = new KnowledgeServerHealthMonitor(executors, bootstrap, 10_000L,
            now::get, BootRecoveryPolicy.defaults(), () -> now.get() * 1_000_000L)) {
          monitor.componentRegistry(components);
          monitor.componentRecoveryBindings(Map.of("index", new ComponentRecoveryBinding(index,
              request -> { throw new AssertionError("ownership cannot be recovered"); })),
              ignored -> escalations.incrementAndGet());
          if (initialOwnerPending) monitor.observeInitialStartup(new CompletableFuture<>());
          for (int tick = 0; tick < 40; tick++) {
            now.addAndGet(10_000L);
            monitor.tick();
          }
          assertEquals(0, escalations.get(), "the API must remain available after the restart budget");
          var status = components.snapshot().components().getFirst();
          assertEquals(ComponentState.FAILED, status.state());
          assertEquals(CODE, status.reasonCode());
          assertEquals(detail, status.evidence());
          assertEquals(0, status.recoveryAttempts());
        }
      }
    }
  }

  @Test
  void ownershipRefusalStopsStartupRetriesAndKeepsStatusUntilValidatedStart(@TempDir Path dir)
      throws Exception {
    var host = mock(WorkerHost.class);
    var starts = new AtomicInteger();
    String detail = "Folder " + dir.resolve("indices/foreign") + "; Evidence: missing";
    var client = mock(KnowledgeClient.class);
    when(client.isHealthy(any())).thenReturn(true);
    when(host.start(any(), any())).thenAnswer(ignored -> {
      starts.incrementAndGet();
      WorkerFatalReasonMarker.write(dir, WorkerFatalReasonMarker.INDEX_OWNERSHIP_UNPROVEN, detail);
      throw new IOException("index refused");
    });
    try (var fixture = KnowledgeServerBootstrapTestFixture.create(config(dir), null, host, false);
        var executors = TestEngineExecutors.awaitingTermination()) {
      var bootstrap = fixture.bootstrap();
      assertThrows(IOException.class, () -> bootstrap.startWithRetry(3, 0));
      assertEquals(1, starts.get(), "ownership refusal must end the startup retry arc");
      var now = new AtomicLong(1_000L);
      var escalations = new AtomicInteger();
      try (var monitor = new KnowledgeServerHealthMonitor(executors, bootstrap, 10_000L,
          now::get, BootRecoveryPolicy.defaults())) {
        monitor.componentRegistry(fixture.components());
        var bindings = new HashMap<String, ComponentRecoveryBinding>();
        for (var row : fixture.components().snapshot().components()) {
          bindings.put(row.spec().name(), new ComponentRecoveryBinding(
              fixture.components().handle(row.spec().name()), null));
        }
        monitor.componentRecoveryBindings(bindings, ignored -> escalations.incrementAndGet());
        for (int tick = 0; tick < 40; tick++) {
          now.addAndGet(10_000L);
          monitor.tick();
        }
        assertEquals(0, escalations.get());
        assertEquals(CODE, fixture.indexComponent().snapshot().reasonCode());
        assertEquals(detail, fixture.indexComponent().snapshot().evidence());
        assertEquals(detail, bootstrap.indexFatalDetail());
      }
      doReturn(client).when(host).start(any(), any());
      bootstrap.start();
      assertTrue(bootstrap.checkHealth());
      assertNull(bootstrap.indexFatalCode());
      assertNull(bootstrap.indexFatalDetail());
      fixture.publishSamplerReady();
      assertNull(fixture.indexComponent().snapshot().reasonCode());
      assertNull(fixture.indexComponent().snapshot().evidence());
      bootstrap.transitionWorkerDown(LifecycleReasonCode.INDEX_FAILED, "later unrelated failure");
      assertEquals("index.failed", fixture.indexComponent().snapshot().reasonCode());
    }
  }

  @Test
  void ownershipFatalLatchKeepsPathAndEvidenceUntilAValidatedStart(@TempDir Path dir)
      throws Exception {
    var host = mock(WorkerHost.class);
    var client = mock(KnowledgeClient.class);
    when(host.start(any(), any())).thenReturn(client);
    when(client.isHealthy(any())).thenReturn(true);
    try (var fixture = KnowledgeServerBootstrapTestFixture.create(config(dir), null, host, false)) {
      var bootstrap = fixture.bootstrap();
      String detail = "Folder " + dir.resolve("indices/foreign") + "; Evidence: wrong identity";
      WorkerFatalReasonMarker.write(dir, WorkerFatalReasonMarker.INDEX_OWNERSHIP_UNPROVEN, detail);
      bootstrap.transitionWorkerDown(LifecycleReasonCode.INDEX_FAILED, "refused");
      WorkerFatalReasonMarker.write(dir, WorkerFatalReasonMarker.INDEX_SCHEMA_MISMATCH);
      bootstrap.transitionWorkerDown(LifecycleReasonCode.INDEX_FAILED, "another failure");
      assertEquals(LifecycleReasonCode.INDEX_OWNERSHIP_UNPROVEN, bootstrap.indexFatalCode());
      assertEquals(detail, bootstrap.indexFatalDetail());
      assertFalse(bootstrap.checkHealth(), "no client has opened yet");
      assertEquals(detail, bootstrap.indexFatalDetail());
      bootstrap.start();
      assertNull(bootstrap.indexFatalCode());
      assertNull(bootstrap.indexFatalDetail());
    }
  }

  @Test
  void ownershipLatchRefusesPhysicalComponentRecovery(@TempDir Path dir) throws Exception {
    try (var fixture = KnowledgeServerBootstrapTestFixture.create(config(dir))) {
      WorkerFatalReasonMarker.write(dir, WorkerFatalReasonMarker.INDEX_OWNERSHIP_UNPROVEN,
          "Folder /index/foreign; Evidence: link");
      var bootstrap = fixture.bootstrap();
      bootstrap.transitionWorkerDown(LifecycleReasonCode.INDEX_FAILED, "refused");
      var owners = new AtomicInteger();
      assertEquals(ComponentRecoveryAction.Result.REFUSED,
          bootstrap.recomposeForRecovery(mock(ComponentRecoveryAction.Request.class),
              (request, body) -> {
                owners.incrementAndGet();
                return ComponentRecoveryAction.Result.SUPERSEDED;
              }));
      assertEquals(0, owners.get());
    }
  }

  private static KnowledgeServerConfig config(Path dir) {
    return new KnowledgeServerConfig(false, dir, dir, dir,
        5_000L, 2_000L, 3, 2_000L, 1_000L, 300_000L, 100, 0L, 0);
  }
}
