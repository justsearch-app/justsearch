/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.justsearch.app.api.chat.ChatServerController;
import io.justsearch.app.api.chat.DeviceLostEvidence;
import io.justsearch.ort.Composition;
import io.justsearch.ort.GpuSessionConfig;
import io.justsearch.ort.ModelArtifacts;
import io.justsearch.ort.ModelSessionPolicy;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.QueryDevice;
import io.justsearch.ort.RuntimePolicy;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.ort.SessionHandle;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Spec E test E-S11c (S11): an encoder run that finds its CUDA device lost is reported to the chat
 * server controller once per Engine incarnation, as {@code reportDeviceLost(ORT_CUDA, 719)}; the
 * handle's session generation moves and its query device becomes CPU.
 *
 * <p>Seam: real session handles built by {@link OrtSessionAssembler#buildManager}, whose GPU runs go
 * through the production run choke point; only the native sessions and the controller are mocks.
 * The devices (71, 72) are used by no other test in this module.
 */
final class EncoderDeviceLossReporterTest {

  private static final Path MODEL = Path.of("missing", "model.onnx");

  private static final String CUDA_719 =
      "Error code - ORT_RUNTIME_EXCEPTION - message: Non-zero status code returned while running"
          + " MatMul node. Name:'/MatMul' Status Message: CUDA failure 719: unspecified launch"
          + " failure ; GPU=0 ; hostname=HOST ; file=cuda_call.cc ; line=129 ;"
          + " expr=cudaStreamSynchronize(stream); ";

  @Test
  @SuppressWarnings("unchecked")
  void aLostDeviceIsReportedOncePerIncarnationAndItsQueriesMoveToCpu() throws Exception {
    ChatServerController<Object, Object, Object> controller = mock(ChatServerController.class);
    AtomicInteger runs = new AtomicInteger();
    OrtSession gpu = mock(OrtSession.class);
    when(gpu.run(anyMap()))
        .thenAnswer(
            invocation -> {
              if (runs.incrementAndGet() == 1) return mock(OrtSession.Result.class);
              throw new OrtException(CUDA_719);
            });
    SessionHandle first = gpuHandle("e-s11c-embedding", 71, gpu);
    SessionHandle second = gpuHandle("e-s11c-other-device", 72, failingGpu());
    AtomicInteger generationChanges = new AtomicInteger();
    first.addGenerationListener(generationChanges::incrementAndGet);

    try (EncoderDeviceLossReporter _ = EncoderDeviceLossReporter.start(controller)) {
      try (SessionHandle.Lease warm = first.acquire(required())) {
        warm.run(Map.of()).close();
      }
      assertEquals(QueryDevice.GPU, first.queryDevice(), "precondition: a warm GPU session");
      int generationBefore = generationChanges.get();

      try (SessionHandle.Lease lease = first.acquire(required())) {
        assertFalse(lease.isCpu(), "precondition: the failing run is on the GPU");
        assertThrows(OrtException.class, () -> lease.run(Map.of()));
      }
      // A second device lost in the same incarnation: the restart the first report asks for
      // re-probes every device, so it is not reported again.
      try (SessionHandle.Lease lease = second.acquire(required())) {
        assertFalse(lease.isCpu(), "precondition: the other device's run is on the GPU");
        assertThrows(OrtException.class, () -> lease.run(Map.of()));
      }

      verify(controller, times(1))
          .reportDeviceLost(new DeviceLostEvidence(DeviceLostEvidence.Source.ORT_CUDA, "719"));
      verify(controller, times(1)).reportDeviceLost(any());
      assertTrue(generationChanges.get() > generationBefore, "the session generation moved");
      assertEquals(QueryDevice.CPU, first.queryDevice(), "queries select CPU while latched");
    }
  }

  private static OrtSession failingGpu() throws OrtException {
    OrtSession gpu = mock(OrtSession.class);
    when(gpu.run(anyMap())).thenThrow(new OrtException(CUDA_719));
    return gpu;
  }

  private static SessionAcquisitionRequest required() {
    return SessionAcquisitionRequest.within(
        SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofSeconds(30));
  }

  private static SessionHandle gpuHandle(String name, int device, OrtSession gpu)
      throws Exception {
    ModelSessionPolicy policy =
        ModelSessionPolicy.forFallback(
            new GpuSessionConfig(device, 512L * 1024 * 1024),
            null,
            /* deferCpuSession= */ true,
            /* gpuRetryEnabled= */ false,
            60_000L);
    SessionHandle handle =
        OrtSessionAssembler.buildManager(
            name,
            new Composition(RuntimePolicy.defaults(), policy, new ModelArtifacts(MODEL, MODEL)),
            () -> true);
    set(handle, "gpuSession", gpu);
    set(handle, "gpuSessionAttempted", true);
    set(handle, "gpuAvailable", true);
    set(handle, "cpuSession", mock(OrtSession.class));
    return handle;
  }

  private static void set(Object handle, String name, Object value) throws Exception {
    Field field = handle.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(handle, value);
  }
}
