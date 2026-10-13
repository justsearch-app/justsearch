/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * A model switch stops the chat server and starts the next one at once, with no lifecycle-level
 * wait (the config-apply path). On an adapter whose memory never shows as released, the release
 * poll runs its full bound every time. The start must wait for that poll to end and then proceed,
 * never refuse because the poll ended a little after the start's own wait, and never spawn before
 * the poll's record.
 */
@EnabledOnOs(OS.WINDOWS)
final class GpuSwitchBarrierTest {
  private static final int ROUNDS = 20;

  @Test
  @Timeout(400)
  void aSwitchStartWaitsForTheReleasePollEveryTime() throws Exception {
    List<String> records = Collections.synchronizedList(new ArrayList<>());
    LedgerSink sink = (type, fields) -> {
      records.add(type.token() + " " + fields.get("childId"));
      return true;
    };
    VramProbe neverReleased = new VramProbe() {
      @Override public GpuMemorySource source() {
        return GpuMemorySource.NVML;
      }

      @Override public OptionalLong adapterUsedBytes(Optional<GpuDevice> device) {
        return OptionalLong.of(8L << 30);
      }
    };
    ProcessTerminator terminator = ProcessTerminator.forCurrentOs();
    long self = ProcessHandle.current().pid();
    List<String> refusals = new ArrayList<>();
    List<String> early = new ArrayList<>();
    try (var control = new DefaultChildControl(sink, terminator, neverReleased, Clock.systemUTC(),
        DefaultChildControl.Sleeper.REAL, ManagedChildRegistry.noop(),
        new EngineIdentity(self, terminator.creationFileTime(self), "gpu-switch-check"),
        Thread.ofPlatform().daemon().name("gpu-switch-check-", 0).factory(),
        DefaultChildControl.JobMembership.NONE)) {
      ChildHandle current = control.start(serverSpec());
      for (int round = 0; round < ROUNDS; round++) {
        control.stop(current, StopRequest.of(StopReason.MODEL_SWITCH, StopMode.NOW, "check"))
            .toCompletableFuture().get(15, TimeUnit.SECONDS);
        ChildHandle next;
        try {
          next = control.start(serverSpec());
        } catch (IOException refused) {
          refusals.add("round " + round + ": " + refused.getMessage());
          // Let this round's poll end so the next round measures the same race afresh.
          control.awaitGpuRelease(java.time.Duration.ofSeconds(10));
          next = control.start(serverSpec());
        }
        int released = records.indexOf(LedgerRecordType.GPU_RELEASE.token() + " " + current.childId());
        int spawned = records.indexOf(LedgerRecordType.CHILD_SPAWN.token() + " " + next.childId());
        if (released < 0 || released > spawned) {
          early.add("round " + round + ": release record at " + released + ", spawn at " + spawned);
        }
        current = next;
      }
      control.stop(current, StopRequest.of(StopReason.QUIT, StopMode.NOW, "check"))
          .toCompletableFuture().get(15, TimeUnit.SECONDS);
    }
    assertEquals(List.of(), refusals, "a switch start is refused while its release poll ends");
    assertTrue(early.isEmpty(), "a start began before the release poll ended: " + early);
  }

  private static ChildSpec serverSpec() {
    List<String> idle = List.of(
        Path.of(System.getenv("SystemRoot"), "System32", "ping.exe").toString(), "-t", "127.0.0.1");
    return new ChildSpec(ChildKind.LLAMA_SERVER, idle, null, Map.of(), new ChildSpec.Stdio.Pipes(),
        true, OptionalLong.empty(), Optional.empty(), ChildSpec.CooperativeStop.NONE,
        "gpu-switch-check");
  }
}
