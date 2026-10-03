/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.core.component.EngineComponentRegistry.ApplyAttempt;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorSpec;
import org.junit.jupiter.api.Test;

class EngineExecutorLifetimeTest {
  @Test
  void closingRestartableIndexHostDoesNotCloseProcessExecutors() throws Exception {
    var operations = org.mockito.Mockito.mock(io.justsearch.app.api.operations.OperationStore.class);
    var root = new EngineRoot(operations, org.mockito.Mockito.mock(io.justsearch.app.api.operations.OperationAttemptRunner.class), (ignored, ignoredExecutors, ignoredIngestion, indexComponent, encoderComponent) -> { throw new AssertionError("No index startup expected"); },
        1_000, 16);
    var processResources = root.processResources();
    try {
      root.close();
      org.junit.jupiter.api.Assertions.assertSame(operations, root.operations());
      org.mockito.Mockito.verify(operations, org.mockito.Mockito.never()).close();
      try (var registration = processResources.executors().register(EngineExecutorSpec.virtual(
          "after-index-close", EngineExecutorSpec.Kind.FOREGROUND, 1))) {
        assertEquals(42, registration.openVirtual().submit(() -> 42).get());
      }
      assertInstanceOf(ApplyAttempt.Acquired.class, processResources.components().tryApply())
          .lease().close();
    } finally {
      processResources.close();
    }

    processResources.close();
    assertEquals(ApplyAttempt.Reason.CLOSED,
        assertInstanceOf(ApplyAttempt.Refused.class,
            processResources.components().tryApply()).reason());
    assertThrows(EngineExecutorRejectedException.class, () -> processResources.executors().register(
        EngineExecutorSpec.virtual("after-process-close", EngineExecutorSpec.Kind.FOREGROUND, 1)));
  }
}
