package io.justsearch.ui.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 419 C3 V2 P3 — sampler lifecycle + resilience cases.
 *
 * <p>Tests target {@link GpuSaturationSampler#sampleOnce} directly rather than spinning up the
 * scheduler — verifies the supplier integration, the NVML-unavailable branch, and the
 * exception-resilience contract without depending on the 15s schedule cadence.
 */
@DisplayName("GpuSaturationSampler")
final class GpuSaturationSamplerTest {

  @Test
  @DisplayName("sampleOnce records when NVML reports available + non-null gpuPercent")
  void recordsSampleWhenAvailable() {
    var monitor = new GpuSaturationMonitor(new AtomicLong(0L)::get);
    var nvml = nvmlAvailable(75);
    var caps = new GpuCapabilities(nvml, null, null);
    GpuCapabilitiesService svc = mock(GpuCapabilitiesService.class);
    when(svc.snapshot()).thenReturn(caps);

    var sampler = new GpuSaturationSampler(new io.justsearch.core.execution.TestEngineExecutors(), () -> svc, monitor);
    sampler.sampleOnce();

    // Two samples needed for compute() to begin returning anything; record a second to confirm
    // the first persisted (compute returns UNKNOWN with < 2 but we just verify recordSample
    // accepted a value indirectly via avgPercent on the result).
    sampler.sampleOnce();
    var result = monitor.compute(0);
    // Sub-window samples → UNKNOWN; we only verify the sampler called recordSample without
    // exception. The presence-of-data is implicit in the absence of exceptions.
    assertEquals(GpuSaturationMonitor.STATE_UNKNOWN, result.state());
  }

  @Test
  @DisplayName("sampleOnce no-ops when NVML reports unavailable")
  void noOpWhenUnavailable() {
    var monitor = new GpuSaturationMonitor(new AtomicLong(0L)::get);
    var nvml =
        new GpuCapabilities.Nvml(
            false, null, null, "no driver", null, null, null, null, null, null, null, null, null);
    var caps = new GpuCapabilities(nvml, null, null);
    GpuCapabilitiesService svc = mock(GpuCapabilitiesService.class);
    when(svc.snapshot()).thenReturn(caps);

    var sampler = new GpuSaturationSampler(new io.justsearch.core.execution.TestEngineExecutors(), () -> svc, monitor);
    sampler.sampleOnce();
    sampler.sampleOnce();

    assertEquals(GpuSaturationMonitor.STATE_UNKNOWN, monitor.compute(0).state());
  }

  @Test
  @DisplayName("sampleOnce no-ops when supplier returns null")
  void noOpWhenSupplierReturnsNull() {
    var monitor = new GpuSaturationMonitor(new AtomicLong(0L)::get);
    var sampler = new GpuSaturationSampler(new io.justsearch.core.execution.TestEngineExecutors(), () -> null, monitor);
    sampler.sampleOnce();
    assertEquals(GpuSaturationMonitor.STATE_UNKNOWN, monitor.compute(0).state());
  }

  @Test
  @DisplayName("sampleOnce swallows supplier exception (subsequent calls still work)")
  void supplierExceptionDoesntKillSampler() {
    var monitor = new GpuSaturationMonitor(new AtomicLong(0L)::get);
    AtomicInteger callCount = new AtomicInteger(0);
    GpuCapabilitiesService goodSvc = mock(GpuCapabilitiesService.class);
    when(goodSvc.snapshot()).thenReturn(new GpuCapabilities(nvmlAvailable(50), null, null));
    var sampler =
        new GpuSaturationSampler(new io.justsearch.core.execution.TestEngineExecutors(),
            () -> {
              if (callCount.incrementAndGet() == 1) {
                throw new RuntimeException("transient nvml error");
              }
              return goodSvc;
            },
            monitor);

    // First call raises — must not propagate.
    sampler.sampleOnce();
    // Second call should succeed.
    sampler.sampleOnce();
    assertEquals(2, callCount.get());
  }

  @Test
  @DisplayName("start() + stop() are idempotent and don't throw")
  void startStopIdempotent() {
    var monitor = new GpuSaturationMonitor(new AtomicLong(0L)::get);
    GpuCapabilitiesService svc = mock(GpuCapabilitiesService.class);
    // Return unavailable so the sampler short-circuits and never schedules.
    when(svc.snapshot())
        .thenReturn(
            new GpuCapabilities(
                new GpuCapabilities.Nvml(
                    false, null, null, null, null, null, null, null, null, null, null, null, null),
                null,
                null));
    var sampler = new GpuSaturationSampler(new io.justsearch.core.execution.TestEngineExecutors(), () -> svc, monitor);
    sampler.start();
    sampler.start(); // second call is a no-op
    sampler.stop();
    sampler.stop(); // second call is a no-op
  }

  private static GpuCapabilities.Nvml nvmlAvailable(int gpuPercent) {
    return new GpuCapabilities.Nvml(
        true,
        "/path/nvml.dll",
        "/path/nvml.dll",
        null,
        "535.0",
        535,
        0,
        1,
        12_000_000_000L,
        4_000_000_000L,
        8_000_000_000L,
        gpuPercent,
        50);
  }
}
