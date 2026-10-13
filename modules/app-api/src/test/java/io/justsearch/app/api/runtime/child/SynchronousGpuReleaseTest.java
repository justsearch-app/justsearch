/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static io.justsearch.app.api.runtime.child.ChildControlHarness.await;
import static io.justsearch.app.api.runtime.child.ChildControlHarness.gpuSpec;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SynchronousGpuReleaseTest {
  private static final long GIB = 1L << 30;
  @TempDir Path directory;

  @Test
  void synchronousExitContinuationStartsReplacementAfterGpuRelease() throws Exception {
    try (var h = new ChildControlHarness(directory)) {
      h.virtualTime();
      h.vram = new ChildControlHarness.ScriptedVram(GpuMemorySource.NVML, 8 * GIB, 7 * GIB);
      ChildHandle first = h.control().start(gpuSpec("sleep", OptionalLong.of(GIB)));
      var replacement = first.exit().thenApply(outcome -> {
        try {
          return h.control().start(gpuSpec("sleep", OptionalLong.of(GIB)));
        } catch (IOException failure) {
          throw new java.io.UncheckedIOException(failure);
        }
      });
      await(h.control().stop(first, StopReason.MODEL_SWITCH, StopMode.NOW));
      ChildHandle next = assertDoesNotThrow(() -> await(replacement));
      var records = h.records();
      long spawn = records.stream().filter(r -> r.type() == LedgerRecordType.CHILD_SPAWN)
          .mapToLong(r -> r.seq()).max().orElseThrow();
      assertTrue(records.stream().anyMatch(r -> r.type() == LedgerRecordType.GPU_RELEASE
          && r.seq() < spawn && r.text("result").orElse("").equals("recovered")),
          "GPU recovery must be recorded before a synchronous exit continuation spawns again");
      await(h.control().stop(next, StopReason.QUIT, StopMode.NOW));
      h.assertLedgerConforms();
    }
  }

  @Test
  void pendingGpuReleaseTimeoutRefusesSpawnAndAllowsRetryAfterRelease() throws Exception {
    CountDownLatch sample = new CountDownLatch(1);
    try (var h = new ChildControlHarness(directory)) {
      h.virtualTime();
      var vram = new ChildControlHarness.ScriptedVram(GpuMemorySource.NVML, 8 * GIB, 7 * GIB);
      vram.onRead = n -> { if (n == 2) sample.await(); };
      h.vram = vram;
      ChildHandle first = h.control().start(gpuSpec("sleep", OptionalLong.of(GIB)));
      await(h.control().stop(first, StopReason.MODEL_SWITCH, StopMode.NOW));
      try {
        IOException refusal = assertThrows(IOException.class,
            () -> h.control().start(gpuSpec("sleep", OptionalLong.of(GIB))));
        assertTrue(refusal.getMessage().contains("GPU release"), refusal.getMessage());
        assertEquals(1, h.records(LedgerRecordType.CHILD_SPAWN).size());
      } finally {
        sample.countDown();
      }
      assertTrue(h.control().awaitGpuRelease(Duration.ofSeconds(10)));
      ChildHandle next = assertDoesNotThrow(
          () -> h.control().start(gpuSpec("sleep", OptionalLong.of(GIB))));
      await(h.control().stop(next, StopReason.QUIT, StopMode.NOW));
      h.assertLedgerConforms();
    } finally {
      sample.countDown();
    }
  }
}
