/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.child.OwnedHelper;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.mockito.Mockito;

/**
 * Task b6d536 b101: GPU snapshots on the status, telemetry and saturation ticks start no
 * nvidia-smi when NVML answers in-process, and share one nvidia-smi reading per five seconds when
 * it does not. Counts real owned launches through the helper ledger; runs unchanged on
 * b599d4d7e, where every snapshot started three nvidia-smi processes.
 */
@EnabledOnOs(OS.WINDOWS)
final class GpuSnapshotNvidiaSmiRateTest {
  private final List<String> launches = new CopyOnWriteArrayList<>();
  private Object savedRecorder;

  @BeforeEach void record() throws Exception {
    var field = OwnedHelper.class.getDeclaredField("recorder");
    field.setAccessible(true);
    savedRecorder = field.get(null);
    OwnedHelper.recordTo((type, fields) -> {
      if ("helper-start".equals(type.token()) && String.valueOf(fields.get("purpose")).contains("nvidia-smi")) {
        launches.add(String.valueOf(fields.get("pid")));
      }
      return true;
    }, new EngineIdentity(ProcessHandle.current().pid(), Optional.empty(), "gpu-rate"));
  }

  @AfterEach void restore() throws Exception {
    var field = OwnedHelper.class.getDeclaredField("recorder");
    field.setAccessible(true);
    field.set(null, savedRecorder);
  }

  private static NvmlService nvml(boolean gpu) {
    NvmlService nvml = Mockito.mock(NvmlService.class);
    var snapshot = new GpuCapabilities.Nvml(gpu, null, null, gpu ? null : "NVML unavailable",
        gpu ? "591.59" : null, gpu ? 591 : null, gpu ? 59 : null, gpu ? 1 : 0,
        gpu ? 12L << 30 : null, gpu ? 10L << 30 : null, gpu ? 2L << 30 : null, null, null);
    Mockito.when(nvml.probe()).thenReturn(snapshot);
    Mockito.when(nvml.probe(Mockito.anyString())).thenReturn(snapshot);
    return nvml;
  }

  private static boolean nvidiaSmiOnPath() {
    try {
      Process where = new ProcessBuilder("where", "nvidia-smi").redirectErrorStream(true).start();
      where.getInputStream().readAllBytes();
      return where.waitFor(10, TimeUnit.SECONDS) && where.exitValue() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  @Test void snapshotsStartNoNvidiaSmiWhenNvmlAnswersInProcess() {
    org.junit.jupiter.api.Assumptions.assumeTrue(nvidiaSmiOnPath(), "nvidia-smi is not on PATH");
    var service = new GpuCapabilitiesService(nvml(true), new VramDetector());
    for (int i = 0; i < 20; i++) {
      GpuCapabilities snapshot = i % 2 == 0 ? service.snapshot() : service.snapshotOn("00000000:01:00.0");
      assertEquals("nvml", snapshot.effective().source());
      assertEquals(10L << 30, snapshot.effective().freeVramBytes(), "the VRAM budget reads NVML's live figures");
    }
    assertEquals(List.of(), launches, "NVML answered: no nvidia-smi process for 20 snapshots");
  }

  @Test void withoutNvmlConcurrentSnapshotsShareOneReadingPerFiveSeconds() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(nvidiaSmiOnPath(), "nvidia-smi is not on PATH");
    var services = List.of(new GpuCapabilitiesService(nvml(false), new VramDetector()),
        new GpuCapabilitiesService(nvml(false), new VramDetector()));
    services.getFirst().invalidateNvidiaSmiCache(); // start from a fresh reading
    long started = System.nanoTime();
    List<Thread> readers = new ArrayList<>();
    for (int t = 0; t < 4; t++) {
      var service = services.get(t % 2);
      readers.add(Thread.ofPlatform().start(() -> {
        for (int i = 0; i < 5; i++) service.snapshot();
      }));
    }
    for (Thread reader : readers) reader.join(60_000);
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    long allowed = 1 + elapsedMs / NvidiaSmiReadingsLimits.MIN_INTERVAL_MS;
    assertTrue(launches.size() <= allowed, "20 snapshots by 4 readers over " + elapsedMs
        + " ms may start at most " + allowed + " nvidia-smi processes, started " + launches.size());
    assertTrue(services.getFirst().snapshot().nvidiaSmi().available(), "the shared reading still answers");
  }

  /** The sharing interval, as a literal so this check also compiles on b599d4d7e. */
  private static final class NvidiaSmiReadingsLimits {
    static final long MIN_INTERVAL_MS = 5_000;
  }
}
