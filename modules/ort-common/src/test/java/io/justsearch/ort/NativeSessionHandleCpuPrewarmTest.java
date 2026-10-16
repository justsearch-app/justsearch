/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static io.justsearch.ort.NativeSessionHandleCpuRecourseTest.answering;
import static io.justsearch.ort.NativeSessionHandleCpuRecourseTest.holdBackground;
import static io.justsearch.ort.NativeSessionHandleCpuRecourseTest.hybridLeg;
import static io.justsearch.ort.NativeSessionHandleCpuRecourseTest.set;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The query encoders' prewarm makes a GPU handle's CPU session warm, so that a query the device
 * turns away has a CPU session to run on: one run on a one-row token input (ids 0, attention mask
 * 1). A handle without a GPU has nothing to warm, and a model whose inputs are not token ids is not
 * run on made-up input.
 */
final class NativeSessionHandleCpuPrewarmTest {

  private static final Path MODEL = Path.of("missing", "model.onnx");

  @Test
  void prewarmRunsTheCpuSessionOnceSoATurnedAwayQueryCanUseIt() throws Exception {
    NativeSessionHandle handle = NativeSessionHandleCpuRecourseTest.handle("cpu-prewarm", 85);
    Map<String, long[][]> seen = new LinkedHashMap<>();
    OrtSession cpu = tokenModel(seen);
    set(handle, "cpuSession", cpu);

    // Before the prewarm the CPU session has never run, so a turned-away leg misses its deadline.
    try (SessionHandle.Lease _ = holdBackground(handle)) {
      assertThrows(
          SessionAcquireDeadlineExceededException.class,
          () -> handle.acquire(hybridLeg(Duration.ofMillis(400))));
    }

    assertTrue(handle.prewarmCpuForQueries(prewarmRequest()), "the CPU session is warm");
    verify(cpu, times(1)).run(anyMap());
    assertEquals(3, seen.size(), "every model input was fed");
    assertArrayEquals(new long[16], seen.get("input_ids")[0]);
    long[] ones = new long[16];
    java.util.Arrays.fill(ones, 1L);
    assertArrayEquals(ones, seen.get("attention_mask")[0]);
    assertArrayEquals(new long[16], seen.get("token_type_ids")[0]);

    // Warm already: a second prewarm does not run it again.
    assertTrue(handle.prewarmCpuForQueries(prewarmRequest()));
    verify(cpu, times(1)).run(anyMap());

    try (SessionHandle.Lease _ = holdBackground(handle);
        SessionHandle.Lease lease = handle.acquire(hybridLeg(Duration.ofSeconds(3)))) {
      assertTrue(lease.isCpu(), "after the prewarm the turned-away leg runs on the CPU session");
    }
  }

  @Test
  void aHandleWithoutAGpuHasNoCpuSessionToWarmForTurnedAwayQueries() throws Exception {
    ModelSessionPolicy policy =
        ModelSessionPolicy.forFallback(
            null, null, /* deferCpuSession= */ true, /* gpuRetryEnabled= */ false,
            NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS);
    NativeSessionHandle handle =
        (NativeSessionHandle)
            OrtSessionAssembler.buildManager(
                "cpu-prewarm-cpu-only",
                new Composition(RuntimePolicy.defaults(), policy, new ModelArtifacts(MODEL, MODEL)),
                () -> false);
    OrtSession cpu = tokenModel(new LinkedHashMap<>());
    set(handle, "cpuSession", cpu);

    assertFalse(handle.prewarmCpuForQueries(prewarmRequest()));
    verify(cpu, never()).run(anyMap());
  }

  @Test
  void aModelWhoseInputsAreNotTokenIdsIsNotRunOnMadeUpInput() throws Exception {
    NativeSessionHandle handle =
        NativeSessionHandleCpuRecourseTest.handle("cpu-prewarm-pixels", 87);
    OrtSession cpu = answering();
    when(cpu.getInputInfo())
        .thenReturn(Map.of("pixel_values", input("pixel_values", OnnxJavaType.FLOAT, 4)));
    set(handle, "cpuSession", cpu);

    assertFalse(handle.prewarmCpuForQueries(prewarmRequest()));
    verify(cpu, never()).run(anyMap());
  }

  private static SessionAcquisitionRequest prewarmRequest() {
    return SessionAcquisitionRequest.within(
        SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofSeconds(30));
  }

  /** A CPU session with the three token inputs of a BERT-style encoder; records what it was fed. */
  private static OrtSession tokenModel(Map<String, long[][]> seen) throws Exception {
    OrtSession session = mock(OrtSession.class);
    Map<String, NodeInfo> inputs = new LinkedHashMap<>();
    for (String name : new String[] {"input_ids", "attention_mask", "token_type_ids"}) {
      inputs.put(name, input(name, OnnxJavaType.INT64, 2));
    }
    when(session.getInputInfo()).thenReturn(inputs);
    when(session.run(anyMap()))
        .thenAnswer(
            invocation -> {
              Map<String, ?> fed = invocation.getArgument(0);
              fed.forEach(
                  (name, tensor) -> {
                    try {
                      seen.put(name, (long[][]) ((OnnxTensor) tensor).getValue());
                    } catch (ai.onnxruntime.OrtException e) {
                      throw new IllegalStateException(e);
                    }
                  });
              return mock(OrtSession.Result.class);
            });
    return session;
  }

  private static NodeInfo input(String name, OnnxJavaType type, int rank) throws Exception {
    Constructor<TensorInfo> constructor =
        TensorInfo.class.getDeclaredConstructor(
            long[].class, OnnxJavaType.class, TensorInfo.OnnxTensorType.class);
    constructor.setAccessible(true);
    long[] shape = new long[rank];
    java.util.Arrays.fill(shape, -1L);
    TensorInfo.OnnxTensorType onnxType =
        type == OnnxJavaType.INT64
            ? TensorInfo.OnnxTensorType.ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64
            : TensorInfo.OnnxTensorType.ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT;
    return new NodeInfo(name, constructor.newInstance(shape, type, onnxType));
  }
}
