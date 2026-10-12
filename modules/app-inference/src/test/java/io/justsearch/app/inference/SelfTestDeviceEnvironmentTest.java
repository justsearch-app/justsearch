/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.ChildHandle;
import io.justsearch.app.api.runtime.child.ChildSpec;
import io.justsearch.app.api.runtime.child.ExitOutcome;
import io.justsearch.app.inference.chat.ChildSelfTestLauncher;
import io.justsearch.app.inference.chat.SelfTestSpec;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SelfTestDeviceEnvironmentTest {
  @TempDir Path tmp;

  @Test
  void childControlReceivesTheSameDeviceEnvironmentAndGpuOwnershipAsTheCommand() throws Exception {
    for (int layers : new int[] {0, 99}) {
      var devices = LlamaDevicePlan.resolve(ResolvedConfig.builder().build(), layers,
          Optional.of("GPU-pinned"));
      var control = mock(ChildControl.class);
      var handle = mock(ChildHandle.class);
      when(handle.exit()).thenReturn(new CompletableFuture<ExitOutcome>());
      when(control.stop(any(), any())).thenReturn(CompletableFuture.completedFuture(mock(ExitOutcome.class)));
      when(control.awaitGpuRelease(any())).thenReturn(true);
      AtomicReference<ChildSpec> captured = new AtomicReference<>();
      when(control.start(any(), any())).thenAnswer(call -> {
        captured.set(call.getArgument(0));
        return handle;
      });
      List<String> command = new ArrayList<>(List.of("llama-server", "-m", "chat.gguf"));
      command.addAll(devices.layerFlags());
      command.addAll(devices.deviceFlags());
      var probe = new SelfTestSpec.Probe() {
        @Override public boolean healthy(int port) { return true; }
        @Override public void exercise(int port) {}
      };
      var run = new ChildSelfTestLauncher(control).run(new SelfTestSpec(command, tmp,
          List.of(tmp), tmp.resolve("selftest.log"), 8082, Duration.ofSeconds(1), probe,
          devices.environment()));
      assertTrue(run.exercised(), run.failure());
      var child = captured.get();
      assertEquals(layers > 0, child.gpu());
      assertEquals(devices.environment().get("CUDA_VISIBLE_DEVICES"),
          child.envOverrides().get("CUDA_VISIBLE_DEVICES"));
      assertEquals(command, child.argv());
    }
  }
}
