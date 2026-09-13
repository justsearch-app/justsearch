/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

@DisplayName("InferenceLifecycleManager shutdown")
final class InferenceLifecycleManagerShutdownTest {

  @Test
  @DisplayName("the close directive controls the real manager close path")
  void closeDirectiveControlsServerStop() {
    assertCloseStopsServer(true);
    assertCloseLeavesServerRunning(false);
  }

  private static void assertCloseStopsServer(boolean stop) {
    try (MockedConstruction<LlamaServerOps> construction =
        Mockito.mockConstruction(LlamaServerOps.class)) {
      InferenceLifecycleManager manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), fakeConfig());
      LlamaServerOps fakeServerOps = construction.constructed().getFirst();

      manager.setStopServerOnClose(stop);
      manager.close();

      verify(fakeServerOps).stopLlamaServer();
    }
  }

  private static void assertCloseLeavesServerRunning(boolean stop) {
    try (MockedConstruction<LlamaServerOps> construction =
        Mockito.mockConstruction(LlamaServerOps.class)) {
      InferenceLifecycleManager manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), fakeConfig());
      LlamaServerOps fakeServerOps = construction.constructed().getFirst();

      manager.setStopServerOnClose(stop);
      manager.close();

      verify(fakeServerOps, never()).stopLlamaServer();
    }
  }

  private static InferenceConfig fakeConfig() {
    return new InferenceConfig(
        Path.of("missing-llama-server.exe"),
        Path.of("missing-model.gguf"),
        null,
        9991,
        4096,
        0,
        false);
  }
}
