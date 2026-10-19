/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static io.justsearch.app.api.runtime.child.ChildControlHarness.await;
import static io.justsearch.app.api.runtime.child.ChildControlHarness.gpuSpec;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** A hung adapter memory reading ends its release poll as unmeasured instead of holding GPU starts. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
final class GpuReadBoundTest {
  private static final long GIB = 1L << 30;
  @TempDir Path directory;

  @Test
  void aReadThatNeverReturnsEndsThePollUnmeasuredAndGpuStartsProceed() throws Exception {
    CountDownLatch never = new CountDownLatch(1);
    try (var h = new ChildControlHarness(directory)) {
      h.virtualTime();
      var vram = new ChildControlHarness.ScriptedVram(GpuMemorySource.NVML, 8 * GIB, 7 * GIB);
      // The stop's sample answers; every reading after it never returns while this check runs.
      vram.onRead = n -> {
        if (n >= 2) never.await();
      };
      h.vram = vram;
      ChildHandle first = h.control().start(gpuSpec("sleep", OptionalLong.of(GIB)));
      await(h.control().stop(first, StopReason.MODEL_SWITCH, StopMode.NOW));

      long started = System.nanoTime();
      ChildHandle second = assertDoesNotThrow(
          () -> h.control().start(gpuSpec("sleep", OptionalLong.of(GIB))),
          "the GPU start proceeds once the hung reading's bound has passed");
      Duration waited = Duration.ofNanos(System.nanoTime() - started);
      assertTrue(waited.compareTo(DefaultChildControl.GPU_RELEASE_BOUND) < 0,
          "the start waited " + waited.toMillis() + " ms");
      List<String> results = h.records(LedgerRecordType.GPU_RELEASE, first.childId()).stream()
          .map(r -> r.text("result").orElse("")).toList();
      assertEquals(List.of("unmeasured"), results, "the poll ended as unmeasured");
      assertTrue(h.records(LedgerRecordType.CHILD_NOTE, first.childId()).stream()
          .anyMatch(r -> r.text("token").orElse("").startsWith("gpu_read_unanswered:")),
          "the unanswered reading is recorded");

      // While that reading still hangs, the next switch neither waits on it nor queues behind it.
      await(h.control().stop(second, StopReason.MODEL_SWITCH, StopMode.NOW));
      started = System.nanoTime();
      ChildHandle third = assertDoesNotThrow(
          () -> h.control().start(gpuSpec("sleep", OptionalLong.of(GIB))));
      waited = Duration.ofNanos(System.nanoTime() - started);
      // Well inside one reading's bound: nothing waits on the hung reading again.
      assertTrue(waited.compareTo(Duration.ofSeconds(2)) < 0,
          "the next start waited " + waited.toMillis() + " ms");
      assertEquals(List.of("unmeasured"), h.records(LedgerRecordType.GPU_RELEASE, second.childId())
          .stream().map(r -> r.text("result").orElse("")).toList());
      await(h.control().stop(third, StopReason.QUIT, StopMode.NOW));
      h.assertLedgerConforms();
    } finally {
      never.countDown();
    }
  }
}
