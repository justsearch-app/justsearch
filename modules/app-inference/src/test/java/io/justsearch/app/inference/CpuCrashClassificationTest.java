/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.runtime.child.ChildHandle;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CpuCrashClassificationTest {
  @TempDir Path tmp;

  @Test
  void intentionalCudaDiscoveryVetoCannotMakeACpuCrashADeviceFailure() throws Exception {
    var resolved = ResolvedConfig.builder().putSettings("justsearch.data.dir", tmp.toString())
        .putSettings("policy.gpu_acceleration_enabled", "false").build();
    var inference = new InferenceConfig(tmp.resolve("server.exe"), tmp.resolve("model.gguf"),
        null, 8082, 4096, 12, false);
    Path log = tmp.resolve("logs/llama-server.log"); Files.createDirectories(log.getParent());
    Files.writeString(log, "ggml_cuda_init: failed to initialize CUDA: no CUDA-capable device is detected\n"
        + "llama_context: CPU compute buffer size = 384.00 MiB\n");
    var start = new LlamaServerOps.StartResult(new LlamaServerConfigContext(inference, resolved),
        LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS,
        LlamaServerOps.StartDisposition.LAUNCHED_MANAGED,
        "cpu-plan");
    Class<?> ownerType = Class.forName(LlamaServerOps.class.getName() + "$ActiveServer");
    var constructor = ownerType.getDeclaredConstructor(LlamaServerOps.StartResult.class,
        ChildHandle.class, int.class); constructor.setAccessible(true);
    Object owner = constructor.newInstance(start, null, 8082);
    var ops = mock(LlamaServerOps.class, CALLS_REAL_METHODS);
    var describe = LlamaServerOps.class.getDeclaredMethod("describeFailure", ownerType, boolean.class);
    describe.setAccessible(true);
    var failure = (LlamaServerOps.ManagedFailure) describe.invoke(ops, owner, true);
    assertEquals(LlamaServerExitClassifier.CrashClass.OTHER, failure.classification().crashClass(),
        "CPU discovery found no CUDA device by construction, not because the device was lost");
    assertEquals(ChatCrashPolicy.Action.RESTART_SAME, ChatCrashPolicy.afterReadyFailure(
        failure.classification(), ChatCrashPolicy.STABILITY_WINDOW, false, false).action());
    assertEquals(ChatCrashPolicy.Action.FAIL, ChatCrashPolicy.afterReadyFailure(
        failure.classification(), Duration.ZERO, false, false).action(), "early crash remains bounded");
  }
}
