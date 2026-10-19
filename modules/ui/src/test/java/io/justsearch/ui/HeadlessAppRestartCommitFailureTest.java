/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.NativeQuiescence;
import io.justsearch.app.api.chat.DeviceLostEvidence;
import io.justsearch.app.api.chat.FailedCode;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.runtime.ledger.*;
import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.engine.ShutdownRequest.Reason;
import io.justsearch.app.inference.chat.*;
import io.justsearch.app.services.chat.ChatServerControllerImpl;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Actual composition-root recorder and dispatcher; no JVM exit or process termination is used. */
@Timeout(15)
final class HeadlessAppRestartCommitFailureTest {
  @Test void aFailedDispatchReleasesItsUnusedReservation(@TempDir Path dir) throws Exception {
    var intent = HeadlessApp.engineSelfIntent(dir, "dispatch-refused");
    var halt = new AtomicInteger(-1);
    var sequence = new EngineShutdownSequence(dir, List.of(), ignored -> {}, ignored -> {},
        () -> NativeQuiescence.QUIESCED, result -> {}, intent);
    var restart = HeadlessApp.deviceLostRestartAction(CompletableFuture.completedFuture(sequence),
        reason -> null, halt::set, action -> { throw new java.util.concurrent.RejectedExecutionException("fixture"); });
    var recovery = HeadlessApp.deviceLostRecovery(dir, intent, restart);
    recovery.reserveRestart(); recovery.requestRestart();
    assertEquals(0, recovery.priorRestarts().size(), "a refused dispatch must not leave an unused reservation in the restart budget");
    assertEquals(-1, halt.get());
  }

  @Test void failedCommitRefusesRestartWithoutClosingOrHalting(@TempDir Path dir) throws Exception {
    try (var f = new Fixture(dir)) {
      f.intent.reserveDeviceLost();
      f.blockCommit();
      assertDoesNotThrow(f.restart::run, "a refused commit must never escape the restart dispatcher");
      assertEquals(-1, f.halt.get(), "a failed second write refuses restart rather than charging crash recovery");
      assertEquals(-1, f.exit.get(), "the Engine remains running");
      assertEquals(0, f.closed.get(), "no ordered close on refusal");
      assertEquals(0, f.published.get(), "no restart hand-off before durable commit");
      assertTrue(ExitLedgerHealth.current(LedgerFamily.directory(dir)).isPresent(), "the refusal reason is visible");
      f.restoreLedger();
      assertEquals(List.of(), DeviceLostRestartHistory.read(LedgerFamily.directory(dir)), "no restart budget spent");
      assertFalse(ExitLedgerReader.read(LedgerFamily.directory(dir), LedgerFamily.ENGINE).records().stream()
          .anyMatch(r -> r.type() == LedgerRecordType.ENGINE_SELF_INTENT), "a pending reservation is never an exit intent");
    }
  }

  @Test void aLaterQuitRecordsItsOwnIntentAndExitsCleanly(@TempDir Path dir) throws Exception {
    try (var f = new Fixture(dir)) {
      f.intent.reserveDeviceLost(); f.blockCommit(); f.restart.run(); f.restoreLedger();
      f.sequence.runAndExit(Reason.QUIT);
      var records = ExitLedgerReader.read(LedgerFamily.directory(dir), LedgerFamily.ENGINE).records();
      assertTrue(records.stream().anyMatch(r -> r.type() == LedgerRecordType.ENGINE_SELF_INTENT
          && r.text("reason").orElse("").equals("QUIT")), "a failed commit must release the self-intent run-once flag");
      assertEquals(0, f.exit.get());
      assertEquals(1, f.closed.get());
      assertEquals(List.of(), DeviceLostRestartHistory.read(LedgerFamily.directory(dir)));
      assertTrue(ExitLedgerHealth.current(LedgerFamily.directory(dir)).isEmpty(), "durable recovery clears the reason");
    }
  }

  @Test void commitFailureKeepsChatLatchedAndRetryable(@TempDir Path dir) throws Exception {
    try (var f = new Fixture(dir); var executors = new TestEngineExecutors()) {
      var latch = new AtomicReference<FailedCode>();
      var refused = new CountDownLatch(1);
      ChatActuator actuator = new ChatActuator() {
        public DrainHold drain(Duration bound) { return () -> {}; }
        public DrainHold drain(ChatDrainPolicy policy) { return () -> {}; }
        public ChatActuation<Void> activation(ChatActuationRouter.Activation a) {
          return ChatActuation.of(ChatActuation.Kind.APPLY, drained -> null);
        }
        public ChatActuation<Boolean> deviceLostStop(BooleanSupplier restart) {
          return ChatActuation.draining(ChatActuation.Kind.DEVICE_LOST, failure -> false, drained -> {
            if (restart.getAsBoolean()) { f.blockCommit(); return true; }
            latch.set(FailedCode.DEVICE_LOST_LIMIT); refused.countDown(); return false;
          });
        }
        public FailedCode failureLatch() { return latch.get(); }
        public <T> T execute(ChatActuation<T> step, DrainHold drained) throws Exception {
          try { return step.run(drained); } finally { if (drained != null) drained.close(); }
        }
        public void terminalStop(StopReason reason, Duration timeout) {}
      };
      var recovery = HeadlessApp.deviceLostRecovery(dir, f.intent, f.restart);
      var chat = new ChatServerControllerImpl(executors, actuator, spec -> SelfTestRun.passed(), recovery);
      try {
        chat.reportDeviceLost(new DeviceLostEvidence(DeviceLostEvidence.Source.ORT_CUDA, "719"));
        assertTrue(refused.await(5, TimeUnit.SECONDS), "commit refusal must latch chat like a failed reservation");
        assertEquals(FailedCode.DEVICE_LOST_LIMIT, latch.get());
        assertNull(chat.desiredInputs().terminal(), "operator retry remains available after refusal");
        assertEquals(-1, f.halt.get()); assertEquals(-1, f.exit.get());
      } finally { chat.shutdown(StopReason.QUIT, Duration.ofSeconds(2)); }
    }
  }

  private static final class Fixture implements AutoCloseable {
    final Path ledger;
    final Path saved;
    final HeadlessApp.LedgerSelfIntent intent;
    final EngineShutdownSequence sequence;
    final Runnable restart;
    final AtomicInteger halt = new AtomicInteger(-1), exit = new AtomicInteger(-1);
    final AtomicInteger closed = new AtomicInteger(), published = new AtomicInteger();
    Fixture(Path dir) {
      ledger = LedgerFamily.ENGINE.current(LedgerFamily.directory(dir)); saved = ledger.resolveSibling("saved-ledger.jsonl");
      intent = HeadlessApp.engineSelfIntent(dir, "second-write-failure");
      sequence = new EngineShutdownSequence(dir, List.of(new EngineShutdownSequence.Step("owner", reason -> {
        closed.incrementAndGet(); return "GRACEFUL";
      })), exit::set, exit::set, () -> NativeQuiescence.QUIESCED, result -> {}, intent);
      restart = HeadlessApp.deviceLostRestartAction(CompletableFuture.completedFuture(sequence),
          reason -> { published.incrementAndGet(); return null; }, halt::set, Runnable::run);
    }
    void blockCommit() throws Exception { Files.move(ledger, saved); Files.createDirectory(ledger); }
    void restoreLedger() throws Exception {
      if (Files.exists(saved)) { Files.delete(ledger); Files.move(saved, ledger); }
    }
    public void close() throws Exception { restoreLedger(); }
  }
}
