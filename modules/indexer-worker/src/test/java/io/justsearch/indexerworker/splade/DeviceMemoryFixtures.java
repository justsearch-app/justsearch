/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.splade;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.justsearch.ort.Composition;
import io.justsearch.ort.DeviceEncoderGate;
import io.justsearch.ort.DeviceEncoderGates;
import io.justsearch.ort.DeviceGatePolicy;
import io.justsearch.ort.GpuSessionConfig;
import io.justsearch.ort.ModelArtifacts;
import io.justsearch.ort.ModelSessionPolicy;
import io.justsearch.ort.NativeSessionHandle;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.RuntimePolicy;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Real {@code NativeSessionHandle}s for the Spec E device-memory checks of the encoders: built
 * through the production assembler on their device's real gate, with mock native sessions.
 */
public final class DeviceMemoryFixtures {

  private static final Path MODEL = Path.of("missing", "model.onnx");

  private DeviceMemoryFixtures() {}

  /** A GPU handle on {@code device} whose GPU session is {@code gpu} and CPU session {@code cpu}. */
  public static NativeSessionHandle handle(String name, int device, OrtSession gpu, OrtSession cpu)
      throws Exception {
    ModelSessionPolicy policy =
        ModelSessionPolicy.forFallback(
            new GpuSessionConfig(device, 512L * 1024 * 1024),
            null,
            /* deferCpuSession= */ true,
            /* gpuRetryEnabled= */ false,
            /* gpuRetryIntervalMs= */ 60_000L);
    NativeSessionHandle handle =
        (NativeSessionHandle)
            OrtSessionAssembler.buildManager(
                name,
                new Composition(RuntimePolicy.defaults(), policy, new ModelArtifacts(MODEL, MODEL)),
                () -> true);
    set(handle, "gpuSession", gpu);
    set(handle, "gpuSessionAttempted", true);
    set(handle, "gpuAvailable", true);
    set(handle, "cpuSession", cpu);
    return handle;
  }

  /** Gives {@code device} a gate with {@code policy}, before any handle of the device seats. */
  public static void injectGate(int device, DeviceGatePolicy policy) throws Exception {
    Field field = DeviceEncoderGates.class.getDeclaredField("GATES");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<Integer, DeviceEncoderGate> gates = (Map<Integer, DeviceEncoderGate>) field.get(null);
    DeviceEncoderGate prior =
        gates.putIfAbsent(device, new DeviceEncoderGate(policy, System::nanoTime));
    assertTrue(prior == null || prior.policy().equals(policy),
        "device " + device + " already has a gate with another policy");
  }

  /** How many acquisitions wait at {@code device}'s gate. */
  public static int waiting(int device) throws Exception {
    Method waiting = DeviceEncoderGate.class.getDeclaredMethod("waiting");
    waiting.setAccessible(true);
    return (int) waiting.invoke(DeviceEncoderGates.forDevice(device));
  }

  /** A result whose first output is {@code output}, as a real tensor. */
  public static OrtSession.Result result(Object output) throws OrtException {
    OnnxTensor tensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), output);
    OrtSession.Result result = mock(OrtSession.Result.class);
    when(result.get(0)).thenReturn(tensor);
    return result;
  }

  /** A tracked file of the repository, by its path from the repository root. */
  public static Path repoFile(String relative) {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) dir = dir.getParent();
    assertTrue(dir != null, "the repository root is above the test's working directory");
    return dir.resolve(relative);
  }

  public static HuggingFaceTokenizer tokenizer(String relative) throws Exception {
    return HuggingFaceTokenizer.newInstance(
        repoFile(relative), Map.of("truncation", "false", "padding", "false"));
  }

  private static void set(NativeSessionHandle handle, String name, Object value) throws Exception {
    Field field = NativeSessionHandle.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(handle, value);
  }
}
