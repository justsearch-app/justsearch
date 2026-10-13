/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static io.justsearch.app.api.runtime.child.ChildControlHarness.await;
import static io.justsearch.app.api.runtime.child.ChildControlHarness.gpuSpec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec B §2.6 step 8: a GPU child's pending VRAM release is a barrier for the next GPU start. A
 * caller woken by the child's exit (a model switch starting the replacement from the exit) must
 * already see that barrier: it is registered before the exit is published, never after.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
final class DefaultChildControlGpuBarrierTest {
  private static final long GIB = 1024L * 1024 * 1024;

  @TempDir Path dataDir;
  private ChildControlHarness h;

  @BeforeEach
  void harness() {
    h = new ChildControlHarness(dataDir);
  }

  @AfterEach
  void conforms() throws IOException {
    h.close();
    h.assertLedgerConforms();
  }

  @Test
  void synchronousExitContinuationStartsReplacementAfterGpuRelease() throws Exception {
    h.virtualTime();
    h.vram = new ChildControlHarness.ScriptedVram(GpuMemorySource.NVML, 8 * GIB, 7 * GIB);
    ChildHandle first = h.control().start(gpuSpec("sleep", OptionalLong.of(GIB)));
    CompletableFuture<ChildHandle> next = new CompletableFuture<>();
    first.exit().whenComplete((outcome, failure) -> {
      try { next.complete(h.control().start(gpuSpec("sleep", OptionalLong.of(GIB)))); }
      catch (Exception refused) { next.completeExceptionally(refused); }
    });
    await(h.control().stop(first, StopRequest.of(StopReason.MODEL_SWITCH, StopMode.NOW)));
    ChildHandle replacement = next.get(20, TimeUnit.SECONDS);
    List<String> types = h.records().stream().map(r -> r.type().token()).toList();
    assertTrue(types.contains("gpu-release"), "release must already be recorded: " + types);
    assertTrue(types.indexOf("gpu-release") < types.lastIndexOf("child-spawn"), types.toString());
    await(h.control().stop(replacement, StopRequest.of(StopReason.QUIT, StopMode.NOW)));
  }

  @Test
  void pendingGpuReleaseRefusesLaunchAndAllowsRetry() throws Exception {
    h.virtualTime();
    CountDownLatch release = new CountDownLatch(1);
    ChildControlHarness.ScriptedVram vram = new ChildControlHarness.ScriptedVram(GpuMemorySource.NVML, 8 * GIB, 7 * GIB);
    vram.onRead = n -> { if (n == 2) release.await(); };
    h.vram = vram;
    ChildHandle first = h.control().start(gpuSpec("sleep", OptionalLong.of(GIB)));
    try {
      await(h.control().stop(first, StopRequest.of(StopReason.MODEL_SWITCH, StopMode.NOW)));
      IOException refused = assertThrows(IOException.class,
          () -> h.control().start(gpuSpec("sleep", OptionalLong.of(GIB))));
      assertTrue(refused.getMessage().contains("retry"));
      assertEquals(1, h.records(LedgerRecordType.CHILD_SPAWN).size());
    } finally { release.countDown(); }
    ChildHandle replacement = h.control().start(gpuSpec("sleep", OptionalLong.of(GIB)));
    await(h.control().stop(replacement, StopRequest.of(StopReason.QUIT, StopMode.NOW)));
  }

  @Test
  void exitCompletionPublishesGpuBarrierBeforeNotifyingWaiters() throws Exception {
    h.virtualTime();
    CountDownLatch release = new CountDownLatch(1);
    ChildControlHarness.ScriptedVram vram = new ChildControlHarness.ScriptedVram(
        GpuMemorySource.NVML, 8 * GIB, 7 * GIB);
    vram.onRead = n -> {
      if (n == 2) release.await(); // the release poll's first sample stays pending until released
    };
    h.vram = vram;
    ChildHandle first = h.control().start(gpuSpec("sleep", OptionalLong.of(GIB)));
    List<Boolean> releasedAtExit = new ArrayList<>();
    List<Integer> spawnsAtExit = new ArrayList<>();
    List<CompletableFuture<ChildHandle>> replacement = new ArrayList<>();
    // A model switch's caller: woken by the exit, it starts the replacement at once.
    first.exit().whenComplete((outcome, failure) -> {
      try {
        releasedAtExit.add(h.control().awaitGpuRelease(Duration.ZERO));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      replacement.add(CompletableFuture.supplyAsync(() -> {
        try {
          return h.control().start(gpuSpec("sleep", OptionalLong.of(GIB)));
        } catch (IOException e) {
          throw new IllegalStateException(e);
        }
      }));
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
      while (System.nanoTime() < until
          && h.records(LedgerRecordType.CHILD_SPAWN).size() < 2) {
        Thread.onSpinWait();
      }
      spawnsAtExit.add(h.records(LedgerRecordType.CHILD_SPAWN).size());
    });

    await(h.control().stop(first, StopRequest.of(StopReason.MODEL_SWITCH, StopMode.NOW)));

    assertEquals(List.of(false), releasedAtExit,
        "the release is pending when the exit is published");
    assertEquals(List.of(1), spawnsAtExit,
        "a replacement started from the exit waits for the previous child's release");
    while (vram.reads.get() < 2) Thread.sleep(5);
    assertFalse(replacement.getFirst().isDone(), "the replacement still waits on the barrier");
    release.countDown();
    ChildHandle next = replacement.getFirst().get(60, TimeUnit.SECONDS);
    List<String> types = h.records().stream().map(r -> r.type().token()).toList();
    assertTrue(types.indexOf("gpu-release") < types.lastIndexOf("child-spawn"), types.toString());
    await(h.control().stop(next, StopRequest.of(StopReason.QUIT, StopMode.NOW)));
  }
}
