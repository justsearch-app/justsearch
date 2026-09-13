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
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeServerHealthMonitor;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.ui.api.LocalApiServer;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;

final class HeadlessAppHealthMonitorStartupTest {
  @Test
  void startupFailureRetiresMonitorWithoutPublishingRecoveryAuthority() throws Exception {
    var api = mock(LocalApiServer.class);
    var failure = new EngineExecutorRejectedException(
        EngineExecutorRejectedException.Reason.TIMER_LIMIT, "health", 2);
    var cleanup = new IllegalStateException("cleanup failure");
    try (var construction = mockConstruction(KnowledgeServerHealthMonitor.class, (monitor, context) -> {
      doThrow(failure).when(monitor).start();
      doThrow(cleanup).when(monitor).close();
    })) {
      var thrown = assertThrows(InvocationTargetException.class, () -> start(api));
      assertSame(failure, thrown.getCause());
      assertSame(cleanup, failure.getSuppressed()[0]);
      verify(construction.constructed().getFirst()).close();
      verify(api, never()).bindWorkerRecovery(any());
    }
  }

  @Test
  void recoveryAuthorityIsPublishedOnlyAfterTimerInstallation() throws Exception {
    var api = mock(LocalApiServer.class);
    try (var construction = mockConstruction(KnowledgeServerHealthMonitor.class)) {
      var result = start(api);
      var monitor = construction.constructed().getFirst();
      assertSame(monitor, result);
      var order = inOrder(monitor, api);
      order.verify(monitor).start();
      order.verify(api).bindWorkerRecovery(monitor);
      verify(monitor, never()).close();
    }
  }

  private static Object start(LocalApiServer api) throws Exception {
    var method = HeadlessApp.class.getDeclaredMethod("startHealthMonitor",
        HeadAssembly.class, LocalApiServer.class, KnowledgeServerBootstrap.class);
    method.setAccessible(true);
    return method.invoke(null, mock(HeadAssembly.class), api, mock(KnowledgeServerBootstrap.class));
  }
}
