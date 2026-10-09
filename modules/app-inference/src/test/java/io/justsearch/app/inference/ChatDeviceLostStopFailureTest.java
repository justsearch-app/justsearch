/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockConstruction;

import io.justsearch.app.api.chat.FailedCode;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** S11: the real device-lost actuator defines the failed stop before returning its failure. */
final class ChatDeviceLostStopFailureTest {
  @TempDir Path directory;

  @Test
  void aFailedDeviceLostStopLatchesStopFailedWithoutReadingTheRestartLimit() throws Exception {
    var stopFailure = new IllegalStateException("injected failed device-loss stop");
    Path executable = Files.createFile(directory.resolve("llama-server.exe"));
    Path model = Files.createFile(directory.resolve("model.gguf"));
    var config = new InferenceConfig(executable, model, null, 18081, 4096, 0, false);
    var resolved = TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.data.dir", directory.resolve("data").toString()));
    var readLimit = new AtomicBoolean();
    var server = mockConstruction(LlamaServerOps.class, (mock, context) ->
            doThrow(stopFailure).doNothing().when(mock).stopLlamaServer(any()));
    try (server;
        var executors = new TestEngineExecutors();
        var manager = new InferenceLifecycleManager(executors, config,
            NoopInferenceTelemetryEvents.INSTANCE, ManagedChildRegistry.noop(), resolved)) {
      var actuator = manager.chatActuator();
      var thrown = assertThrows(IllegalStateException.class, () -> actuator.execute(
          actuator.deviceLostStop(() -> { readLimit.set(true); return true; }), null));
      assertSame(stopFailure, thrown);
      assertEquals(FailedCode.STOP_FAILED, manager.lastChatFailedCode(),
          "a surviving server is FAILED(stop_failed), ready for an operator retry");
      assertEquals(false, readLimit.get(), "the limit is read only after a confirmed stop");
      actuator.noteResume();
      assertNull(manager.lastChatFailedCode(), "the failure can be cleared again");
    }
  }
}
