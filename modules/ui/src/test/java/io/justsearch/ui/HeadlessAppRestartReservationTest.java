/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.app.api.NativeQuiescence;
import io.justsearch.app.api.runtime.ledger.ExitLedgerHealth;
import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.engine.ShutdownRequest.Reason;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HeadlessAppRestartReservationTest {
  @Test void reservationPersistenceFailureStillRefusesBeforeTerminalShutdown(@TempDir Path dir) throws Exception {
    var intent = HeadlessApp.engineSelfIntent(dir, "blocked-before-reservation");
    java.nio.file.Files.createDirectories(LedgerFamily.directory(dir));
    Path blocked = java.nio.file.Files.createDirectory(LedgerFamily.ENGINE.current(LedgerFamily.directory(dir)));
    try {
      assertThrows(IllegalStateException.class, intent::reserveDeviceLost,
          "the existing durable reservation refusal must happen before chat becomes terminal");
    } finally { java.nio.file.Files.delete(blocked); }
  }

  @Test void aQuitWinningAfterReservationDoesNotSpendTheRestartBudget(@TempDir Path dir) throws Exception {
    var intent = HeadlessApp.engineSelfIntent(dir, "quit-wins");
    var sequence = new EngineShutdownSequence(dir, List.of(), ignored -> {}, ignored -> {},
        () -> NativeQuiescence.QUIESCED, result -> {}, intent);
    intent.reserveDeviceLost();
    sequence.runAndExit(Reason.QUIT);
    sequence.runAndExitDeviceLost();
    assertEquals(0, HeadlessApp.deviceLostRecovery(dir, intent::healthy, () -> {}).priorRestarts().size(),
        "an unused DEVICE_LOST reservation is not a restart");
  }

  @Test void aRestartBeforeWriterReadinessUsesMemoryAndReportsItsUnpersistedCount(@TempDir Path dir) throws Exception {
    var oldReservation = originalBootReservation();
    if (oldReservation != null) {
      var notReady = new java.util.concurrent.atomic.AtomicReference<HeadlessApp.LedgerSelfIntent>();
      assertDoesNotThrow(() -> oldReservation.invoke(null, notReady),
          "the real main binding must permit a device-loss restart before writer readiness");
      return;
    }
    var intent = HeadlessApp.engineSelfIntent(dir, "writer-not-ready");
    // Reproduce the composition root's pre-writer state in its real recorder, without starting an Engine.
    var writer = intent.getClass().getDeclaredField("writer"); writer.setAccessible(true); writer.set(intent, null);
    assertDoesNotThrow(intent::reserveDeviceLost,
        "writer readiness must not refuse the base's early device-loss restart");
    var history = intent.getClass().getDeclaredMethod("priorRestarts", Path.class); history.setAccessible(true);
    assertEquals(1, ((List<?>) history.invoke(intent, dir)).size(), "the early reservation is budgeted in memory");
    var health = ExitLedgerHealth.current(LedgerFamily.directory(dir));
    assertTrue(health.isPresent(), "status must say the restart count could not be persisted");
    assertEquals("device_lost_restart_unpersisted", health.orElseThrow().getClass().getMethod("cause").invoke(health.orElseThrow()));
    var code = new java.util.concurrent.atomic.AtomicInteger(-1);
    var sequence = new EngineShutdownSequence(dir, List.of(), code::set, code::set,
        () -> NativeQuiescence.QUIESCED, result -> {}, intent);
    assertDoesNotThrow(sequence::runAndExitDeviceLost);
    assertEquals(4, code.get());
    assertEquals(1, ((List<?>) history.invoke(intent, dir)).size(), "commit consumes rather than duplicates the reservation");
  }
  /** Before-state compatibility: invoke main's actual compiled reservation callback. */
  private static java.lang.reflect.Method originalBootReservation() throws Exception {
    var name = new java.util.concurrent.atomic.AtomicReference<String>();
    try (var bytes = HeadlessApp.class.getResourceAsStream("HeadlessApp.class")) {
      new net.bytebuddy.jar.asm.ClassReader(bytes).accept(new net.bytebuddy.jar.asm.ClassVisitor(
          net.bytebuddy.jar.asm.Opcodes.ASM9) {
        @Override public net.bytebuddy.jar.asm.MethodVisitor visitMethod(
            int access, String method, String descriptor, String signature, String[] exceptions) {
          if (!method.startsWith("lambda$main$") || !descriptor.equals("(Ljava/util/concurrent/atomic/AtomicReference;)V")) return null;
          return new net.bytebuddy.jar.asm.MethodVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9) {
            @Override public void visitLdcInsn(Object value) {
              if ("Engine self-intent writer is not ready".equals(value)) name.set(method);
            }
          };
        }
      }, net.bytebuddy.jar.asm.ClassReader.SKIP_DEBUG | net.bytebuddy.jar.asm.ClassReader.SKIP_FRAMES);
    }
    if (name.get() == null) return null;
    var method = HeadlessApp.class.getDeclaredMethod(name.get(), java.util.concurrent.atomic.AtomicReference.class);
    method.setAccessible(true);
    return method;
  }
}
