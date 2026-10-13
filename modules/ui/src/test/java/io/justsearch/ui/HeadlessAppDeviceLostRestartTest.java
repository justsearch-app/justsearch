/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.app.api.NativeQuiescence;
import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.engine.EngineExit;
import io.justsearch.app.engine.EngineShutdownSequence;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec A Ã‚Â§7.3 and reconciliation F10 at the composition root: the chat controller's device-lost
 * restart path counts this data directory's earlier DEVICE_LOST self-intents, fails closed when a
 * self-intent write failed, and its restart records the DEVICE_LOST self-intent the next
 * incarnation counts, closes in restart order and exits 4.
 */
@Timeout(10)
final class HeadlessAppDeviceLostRestartTest {

  @Test
  void failedSelfIntentPersistenceCannotAuthorizeADeviceLossRestart(@TempDir Path dataDir) throws Exception {
    Path dir = java.nio.file.Files.createDirectories(dataDir.resolve("runtime/exit-ledger"));
    Path blocked = java.nio.file.Files.createDirectory(dir.resolve("engine.v1.jsonl"));
    var code = new AtomicInteger(-1);
    var recorder = HeadlessApp.engineSelfIntent(dataDir, "failed-reservation");
    var sequence = new EngineShutdownSequence(dataDir, List.of(), code::set, code::set,
        () -> NativeQuiescence.QUIESCED, result -> {}, recorder);
    try {
      try { sequence.runAndExitDeviceLost(); } catch (IllegalStateException refused) { /* refused */ }
      assertEquals(-1, code.get(), "unwritten DEVICE_LOST must not become an uncounted restart");
    } finally { java.nio.file.Files.delete(blocked); }
    var durable = HeadlessApp.engineSelfIntent(dataDir, "durable-reservation");
    durable.record(StopReason.DEVICE_LOST, Optional.empty(), EngineExit.REQUESTED_RESTART);
    assertEquals(1, recovery(dataDir, durable, () -> {}).priorRestarts().size(),
        "reopening the history must count the durable reservation");
  }

  @Test
  void theEarlierRestartsAreThisDataDirectorysDeviceLostSelfIntents(@TempDir Path dataDir)
      throws Exception {
    var recorder = HeadlessApp.engineSelfIntent(dataDir, "inst-1");
    recorder.record(StopReason.DEVICE_LOST, Optional.empty(), EngineExit.REQUESTED_RESTART);
    recorder.record(StopReason.ENGINE_RESTART, Optional.of(StopDetail.HANG), 0);
    recorder.record(StopReason.DEVICE_LOST, Optional.empty(), EngineExit.REQUESTED_RESTART);

    var recovery = recovery(dataDir, recorder, () -> { });
    List<Instant> prior = recovery.priorRestarts();

    assertEquals(2, prior.size(), prior.toString());
  }

  @Test
  void aFailedSelfIntentWriteFailsTheCountClosed(@TempDir Path dataDir) throws Exception {
    var recorder = HeadlessApp.engineSelfIntent(dataDir, "failed-history");
    java.nio.file.Files.createDirectories(dataDir.resolve("runtime/exit-ledger/engine.v1.jsonl"));
    recorder.record(StopReason.ENGINE_RESTART, Optional.empty(), EngineExit.REQUESTED_RESTART);
    var recovery = recovery(dataDir, recorder, () -> { });

    assertThrows(IOException.class, recovery::priorRestarts);
  }

  @Test
  void theRestartRecordsTheIntentTheNextIncarnationCountsAndExitsFour(@TempDir Path dataDir)
      throws Exception {
    List<String> events = Collections.synchronizedList(new ArrayList<>());
    var exitCode = new AtomicInteger(-1);
    var halted = new AtomicBoolean();
    var recorder = HeadlessApp.engineSelfIntent(dataDir, "inst-1");
    var sequence = new EngineShutdownSequence(dataDir, List.of(
        new EngineShutdownSequence.Step(EngineShutdownSequence.INDEX_HALF_STEP, reason -> {
          events.add("close " + reason.wire());
          return "GRACEFUL";
        })), exitCode::set, exitCode::set, () -> NativeQuiescence.QUIESCED, preliminary -> { },
        recorder);
    Runnable restart = HeadlessApp.deviceLostRestartAction(
        CompletableFuture.completedFuture(sequence),
        reason -> {
          events.add("publish " + reason.wire());
          return null;
        },
        code -> halted.set(true),
        Runnable::run);
    var recovery = recovery(dataDir, recorder, restart);
    assertEquals(List.of(), recovery.priorRestarts());

    recovery.requestRestart();

    assertEquals(List.of("publish restart", "close restart"), events);
    assertEquals(EngineExit.REQUESTED_RESTART, exitCode.get());
    assertFalse(halted.get(), "no fatal halt");
    assertEquals(1, recovery(dataDir, recorder, () -> { })
        .priorRestarts().size(), "the next incarnation counts this restart");
  }

  /** Uses production's recorder binding; the fallback runs the same entry point on 61ff9b955. */
  private static io.justsearch.app.api.chat.DeviceLostRecovery recovery(
      Path dir, HeadlessApp.LedgerSelfIntent recorder, Runnable restart) throws Exception {
    java.lang.reflect.Method bind;
    Object owner;
    try {
      bind = HeadlessApp.class.getDeclaredMethod("deviceLostRecovery", Path.class, recorder.getClass(), Runnable.class);
      owner = recorder;
    } catch (NoSuchMethodException beforeRecorderBinding) {
      bind = HeadlessApp.class.getDeclaredMethod("deviceLostRecovery", Path.class, java.util.function.BooleanSupplier.class, Runnable.class);
      owner = (java.util.function.BooleanSupplier) recorder::healthy;
    }
    bind.setAccessible(true);
    return (io.justsearch.app.api.chat.DeviceLostRecovery) bind.invoke(null, dir, owner, restart);
  }
}
