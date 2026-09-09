/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorSpec;
import org.junit.jupiter.api.Test;

class EngineExecutorLifetimeTest {
  @Test
  void closingRestartableIndexHostDoesNotCloseProcessExecutors() throws Exception {
    var root = new EngineRoot(ignored -> { throw new AssertionError("No index startup expected"); },
        1_000, 16);
    try (var executors = root.executors()) {
      root.close();
      try (var registration = executors.register(EngineExecutorSpec.virtual(
          "after-index-close", EngineExecutorSpec.Kind.FOREGROUND, 1))) {
        assertEquals(42, registration.openVirtual().submit(() -> 42).get());
      }
    }
    assertThrows(EngineExecutorRejectedException.class, () -> root.executors().register(
        EngineExecutorSpec.virtual("after-process-close", EngineExecutorSpec.Kind.FOREGROUND, 1)));
  }
}
