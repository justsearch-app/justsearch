/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.bootstrap.BootstrapLateBindings;
import io.justsearch.app.services.bootstrap.SubstrateGraph;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeServerHealthMonitor;
import io.justsearch.core.component.TestEngineComponents;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.ui.api.LocalApiServer;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;

final class HeadlessAppHealthMonitorStartupTest {
  @Test
  void bindingFailureRetiresAllocatedMonitorBeforeTimerInstallation() throws Exception {
    var api = mock(LocalApiServer.class);
    var lateBindings = mock(BootstrapLateBindings.class);
    var failure = new IllegalArgumentException("incomplete physical owner bindings");
    try (var construction = mockConstruction(KnowledgeServerHealthMonitor.class, (monitor, context) -> {
      doThrow(failure).when(monitor).componentRecoveryBindings(any(), any());
    })) {
      var thrown = assertThrows(InvocationTargetException.class, () -> start(api, lateBindings));
      assertSame(failure, thrown.getCause());
      var monitor = construction.constructed().getFirst();
      verify(monitor).close();
      verify(monitor, never()).start();
      verify(lateBindings, never()).setComponentRecoveryAuthority(any());
    }
  }

  @Test
  void startupFailureRetiresMonitorWithoutPublishingRecoveryAuthority() throws Exception {
    var api = mock(LocalApiServer.class);
    var lateBindings = mock(BootstrapLateBindings.class);
    var failure = new EngineExecutorRejectedException(
        EngineExecutorRejectedException.Reason.TIMER_LIMIT, "health", 2);
    var cleanup = new IllegalStateException("cleanup failure");
    try (var construction = mockConstruction(KnowledgeServerHealthMonitor.class, (monitor, context) -> {
      doThrow(failure).when(monitor).start();
      doThrow(cleanup).when(monitor).close();
    })) {
      var thrown = assertThrows(InvocationTargetException.class, () -> start(api, lateBindings));
      assertSame(failure, thrown.getCause());
      assertSame(cleanup, failure.getSuppressed()[0]);
      verify(construction.constructed().getFirst()).close();
      verify(lateBindings, never()).setComponentRecoveryAuthority(any());
    }
  }

  @Test
  void recoveryAuthorityIsPublishedOnlyAfterTimerInstallation() throws Exception {
    var api = mock(LocalApiServer.class);
    var lateBindings = mock(BootstrapLateBindings.class);
    try (var construction = mockConstruction(KnowledgeServerHealthMonitor.class)) {
      var result = start(api, lateBindings);
      var monitor = construction.constructed().getFirst();
      assertSame(monitor, result);
      var order = inOrder(monitor, lateBindings);
      order.verify(monitor).componentRecoveryBindings(any(), any());
      order.verify(monitor).start();
      order.verify(lateBindings).setComponentRecoveryAuthority(monitor);
      verify(monitor, never()).close();
    }
  }

  @Test
  void recoveredIndexPreparesSurfacesBeforeReadyAndActivatesBridgeAfterPublication()
      throws Exception {
    var head = mock(HeadAssembly.class);
    var api = mock(LocalApiServer.class);
    var substrate = mock(SubstrateGraph.class);
    var health = mock(SubstrateGraph.HealthSubstrate.class);
    var readiness = mock(
        io.justsearch.app.services.observability.health.ReadinessReconciliationTrigger.class);
    var lateBindings = mock(BootstrapLateBindings.class);
    var knowledgeServer = mock(KnowledgeServerBootstrap.class);
    var index = mock(io.justsearch.core.component.ComponentHandle.class);
    var encoders = mock(io.justsearch.core.component.ComponentHandle.class);
    org.mockito.Mockito.when(head.substrate()).thenReturn(substrate);
    org.mockito.Mockito.when(head.lateBindings()).thenReturn(lateBindings);
    org.mockito.Mockito.when(substrate.health()).thenReturn(health);
    org.mockito.Mockito.when(health.readinessReconciliationTrigger()).thenReturn(readiness);
    org.mockito.Mockito.when(head.generativeComponent())
        .thenReturn(mock(io.justsearch.core.component.ComponentHandle.class));
    org.mockito.Mockito.when(api.apiComponent())
        .thenReturn(mock(io.justsearch.core.component.ComponentHandle.class));
    org.mockito.Mockito.when(knowledgeServer.indexComponent()).thenReturn(index);

    try (var construction = mockConstruction(KnowledgeServerHealthMonitor.class)) {
      start(head, api, knowledgeServer, encoders);
      var monitor = construction.constructed().getFirst();
      @SuppressWarnings("unchecked")
      var bindings = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
      verify(monitor).componentRecoveryBindings(bindings.capture(), any());
      var request = mock(io.justsearch.core.component.ComponentRecoveryAction.Request.class);
      org.mockito.Mockito.when(knowledgeServer.recoverIndex(
              org.mockito.ArgumentMatchers.same(request), any(Runnable.class)))
          .thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return io.justsearch.core.component.ComponentRecoveryAction.Result.REFUSED;
          });

      ((io.justsearch.app.services.worker.ComponentRecoveryBinding)
          bindings.getValue().get("index")).action().recover(request);

      var preparation = inOrder(head, api);
      preparation.verify(head).prepareKnowledgeServerBinding(knowledgeServer);
      preparation.verify(api).lateBindKnowledgeServer(knowledgeServer, null);
      verify(head, never()).activateKnowledgeServerBinding();

      @SuppressWarnings("unchecked")
      var published = org.mockito.ArgumentCaptor.forClass(java.util.function.Consumer.class);
      verify(monitor).onRecoveryPublished(published.capture());
      published.getValue().accept(knowledgeServer);

      var activation = inOrder(head, readiness);
      activation.verify(head).activateKnowledgeServerBinding();
      activation.verify(readiness).request();
    }
  }

  @Test
  void failedStartBindsTheRetainedBootstrapToStatusBeforeRecoveryStarts() throws Exception {
    try (var components = TestEngineComponents.fourComponents()) {
      var head = mock(HeadAssembly.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
      var api = mock(LocalApiServer.class);
      var substrate = mock(SubstrateGraph.class);
      var health = mock(SubstrateGraph.HealthSubstrate.class);
      var bootstrap = mock(KnowledgeServerBootstrap.class);
      var index = components.handle("index");
      var encoders = components.handle("encoders");
      var generative = components.handle("generative");
      org.mockito.Mockito.when(head.substrate()).thenReturn(substrate);
      org.mockito.Mockito.when(substrate.health()).thenReturn(health);
      org.mockito.Mockito.when(head.generativeComponent()).thenReturn(generative);
      org.mockito.Mockito.when(api.apiComponent()).thenReturn(components.handle("api"));
      org.mockito.Mockito.when(bootstrap.indexComponent()).thenReturn(index);
      org.mockito.Mockito.when(bootstrap.hasClient()).thenReturn(false);
      String failure = "initial index open refused";
      var startResultType = Class.forName("io.justsearch.ui.HeadlessApp$KnowledgeServerStartResult");
      var startResultConstructor = startResultType.getDeclaredConstructor(
          KnowledgeServerBootstrap.class, String.class);
      startResultConstructor.setAccessible(true);
      Object startResult = startResultConstructor.newInstance(bootstrap, failure);
      var worker = java.util.concurrent.CompletableFuture.completedFuture(startResult);
      var constructed = java.util.concurrent.CompletableFuture.completedFuture(bootstrap);
      var apiPhase = new HeadlessApp.ApiPhaseResult(head, api, 0, null);
      var method = HeadlessApp.class.getDeclaredMethod("connectWorker",
          HeadlessApp.ApiPhaseResult.class,
          java.util.concurrent.CompletableFuture.class,
          java.util.concurrent.CompletableFuture.class,
          io.justsearch.core.component.ComponentHandle.class,
          io.justsearch.core.component.EngineComponentRegistry.class,
          io.justsearch.core.component.ComponentHandle.class,
          Runnable.class);
      method.setAccessible(true);

      try (var construction = mockConstruction(KnowledgeServerHealthMonitor.class)) {
        method.invoke(null, apiPhase, worker, constructed, index, components, encoders,
            (Runnable) () -> {});

        var monitor = construction.constructed().getFirst();
        var order = inOrder(api, monitor);
        order.verify(api).bindPendingKnowledgeServerStatus(bootstrap, failure);
        order.verify(monitor).start();
        verify(api, never()).lateBindKnowledgeServer(any(), any());
        verify(bootstrap, never()).client();
      }
    }
  }

  @Test
  void pendingInitialStartDoesNotExposeBootstrapToStatusSamplerBeforeHandover()
      throws Exception {
    try (var components = TestEngineComponents.fourComponents()) {
      var head = mock(HeadAssembly.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
      var api = mock(LocalApiServer.class);
      var bootstrap = mock(KnowledgeServerBootstrap.class);
      org.mockito.Mockito.when(head.generativeComponent())
          .thenReturn(components.handle("generative"));
      org.mockito.Mockito.when(api.apiComponent()).thenReturn(components.handle("api"));
      org.mockito.Mockito.when(bootstrap.indexComponent()).thenReturn(components.handle("index"));
      var worker = new java.util.concurrent.CompletableFuture<Object>();
      var constructed = java.util.concurrent.CompletableFuture.completedFuture(bootstrap);
      var apiPhase = new HeadlessApp.ApiPhaseResult(head, api, 0, null);
      var method = HeadlessApp.class.getDeclaredMethod("connectWorker",
          HeadlessApp.ApiPhaseResult.class,
          java.util.concurrent.CompletableFuture.class,
          java.util.concurrent.CompletableFuture.class,
          io.justsearch.core.component.ComponentHandle.class,
          io.justsearch.core.component.EngineComponentRegistry.class,
          io.justsearch.core.component.ComponentHandle.class,
          Runnable.class);
      method.setAccessible(true);

      try (var construction = mockConstruction(KnowledgeServerHealthMonitor.class)) {
        method.invoke(null, apiPhase, worker, constructed, components.handle("index"), components,
            components.handle("encoders"), (Runnable) () -> {});

        verify(api, never()).bindPendingKnowledgeServerStatus(any(), any());
        verify(api, never()).lateBindKnowledgeServer(any(), any());
        verify(construction.constructed().getFirst()).observeInitialStartup(worker);
      }
    }
  }

  private static Object start(LocalApiServer api, BootstrapLateBindings lateBindings)
      throws Exception {
    var bootstrap = mock(HeadAssembly.class);
    var substrate = mock(SubstrateGraph.class);
    var health = mock(SubstrateGraph.HealthSubstrate.class);
    org.mockito.Mockito.when(bootstrap.substrate()).thenReturn(substrate);
    org.mockito.Mockito.when(bootstrap.lateBindings()).thenReturn(lateBindings);
    org.mockito.Mockito.when(substrate.health()).thenReturn(health);
    org.mockito.Mockito.when(bootstrap.generativeComponent())
        .thenReturn(mock(io.justsearch.core.component.ComponentHandle.class));
    org.mockito.Mockito.when(api.apiComponent())
        .thenReturn(mock(io.justsearch.core.component.ComponentHandle.class));
    var knowledgeServer = mock(KnowledgeServerBootstrap.class);
    org.mockito.Mockito.when(knowledgeServer.indexComponent())
        .thenReturn(mock(io.justsearch.core.component.ComponentHandle.class));
    return start(bootstrap, api, knowledgeServer,
        mock(io.justsearch.core.component.ComponentHandle.class));
  }

  private static Object start(
      HeadAssembly bootstrap,
      LocalApiServer api,
      KnowledgeServerBootstrap knowledgeServer,
      io.justsearch.core.component.ComponentHandle encoderComponent) throws Exception {
    var method = HeadlessApp.class.getDeclaredMethod("startHealthMonitor",
        HeadAssembly.class, LocalApiServer.class, KnowledgeServerBootstrap.class,
        io.justsearch.core.component.EngineComponentRegistry.class,
        java.util.concurrent.CompletableFuture.class,
        io.justsearch.core.component.ComponentHandle.class, Runnable.class);
    method.setAccessible(true);
    return method.invoke(null, bootstrap, api, knowledgeServer,
        mock(io.justsearch.core.component.EngineComponentRegistry.class), null,
        encoderComponent, (Runnable) () -> {});
  }
}
