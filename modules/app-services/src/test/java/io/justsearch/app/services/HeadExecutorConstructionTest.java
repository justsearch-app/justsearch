/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.config.ConfigManagerBootstrap;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.telemetry.Telemetry;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class HeadExecutorConstructionTest {
  @Test void partialRegistrationAndLaterBootFailureRollBackEveryDirectOwner() throws Exception {
    for (int failureAt = 1; failureAt <= 5; failureAt++) {
      var registry = mock(EngineExecutorRegistry.class);
      when(registry.maxConcurrentWork()).thenReturn(4);
      when(registry.limits(any())).thenReturn(new EngineExecutorRegistry.Limits(2, 4));
      var acquired = new ArrayList<EngineExecutorRegistry.Registration>();
      var failure = new IllegalStateException("boot failed at " + failureAt);
      int target = failureAt;
      when(registry.register(any())).thenAnswer(call -> {
        if (acquired.size() + 1 == target) throw failure;
        var registration = mock(EngineExecutorRegistry.Registration.class);
        acquired.add(registration);
        return registration;
      });
      var config = mock(ConfigManagerBootstrap.class);
      when(config.currentSnapshot()).thenThrow(failure);
      var operations = mock(io.justsearch.app.api.operations.OperationStore.class);
      assertSame(failure, assertThrows(IllegalStateException.class, () ->
          new HeadAssembly(operations, registry, mock(Telemetry.class), config, null, null, null,
              mock(ManagedChildRegistry.class), null, mock(EngineAdmissionService.class))));
      verify(operations, never()).close();
      assertEquals(failureAt - 1, acquired.size());
      for (var registration : acquired) verify(registration).close();
    }
  }
}
