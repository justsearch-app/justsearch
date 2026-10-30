/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.NativeQuiescence;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Signal exit arbitration; reflection keeps the checks executable on the unmodified baseline. */
final class EngineSignalAuthorityTest {
  @ParameterizedTest(name = "signalExitMatchesNativeQuiescence({index}: {arguments})")
  @CsvSource({"130,true", "143,true", "130,false", "143,false"})
  void signalExitMatchesNativeQuiescence(int status, boolean quiesced,
      @TempDir Path dir) throws Exception {
    var normal = new AtomicInteger(-1);
    var hard = new AtomicInteger(-1);
    var intents = new AtomicInteger();
    var seq = new EngineShutdownSequence(dir, List.of(), normal::set, hard::set,
        () -> quiesced ? NativeQuiescence.QUIESCED : NativeQuiescence.UNQUIESCED,
        ignored -> {}, (reason, detail, code) -> intents.incrementAndGet());
    claim(seq, status).run();
    assertEquals(quiesced ? status : EngineExit.FATAL_OR_UNCAUGHT,
        (quiesced ? normal : hard).get());
    assertEquals(-1, (quiesced ? hard : normal).get());
    assertEquals(1, intents.get(), "preserve the baseline JVM hook's QUIT self-intent");
    assertEquals(EngineExit.ExitClass.TRANSIENT,
        EngineExit.classify((quiesced ? normal : hard).get()));
  }

  @ParameterizedTest(name = "competingTriggersCloseOnceAndKeepFirstReason({index}: {arguments})")
  @CsvSource({
      "quit,signal,143,true",
      "signal,quit,143,true",
      "restart,signal,143,true",
      "signal,restart,143,true",
      "upgrade,signal,143,true",
      "signal,upgrade,143,true",
      "fatal,signal,143,true",
      "signal,fatal,143,true",
      "quit,signal,143,false",
      "signal,quit,143,false",
      "restart,signal,143,false",
      "signal,restart,143,false",
      "upgrade,signal,143,false",
      "signal,upgrade,143,false",
      "fatal,signal,143,false",
      "signal,fatal,143,false",
      "quit,signal,130,true",
      "signal,quit,130,true",
      "restart,signal,130,true",
      "signal,restart,130,true",
      "upgrade,signal,130,true",
      "signal,upgrade,130,true",
      "fatal,signal,130,true",
      "signal,fatal,130,true",
      "quit,signal,130,false",
      "signal,quit,130,false",
      "restart,signal,130,false",
      "signal,restart,130,false",
      "upgrade,signal,130,false",
      "signal,upgrade,130,false",
      "fatal,signal,130,false",
      "signal,fatal,130,false"})
  void competingTriggersCloseOnceAndKeepFirstReason(String first, String second,
      int signalStatus, boolean quiesced, @TempDir Path dir) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var closes = new AtomicInteger();
    var code = new AtomicInteger(-1);
    var intent = new AtomicReference<String>();
    var seq = new EngineShutdownSequence(dir, List.of(new EngineShutdownSequence.Step(
        EngineShutdownSequence.INDEX_HALF_STEP, reason -> {
          closes.incrementAndGet();
          entered.countDown();
          if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test did not release");
          return "GRACEFUL";
        })), code::set, code::set,
        () -> quiesced ? NativeQuiescence.QUIESCED : NativeQuiescence.UNQUIESCED,
        ignored -> {}, (reason, detail, expected) -> intent.set(reason + ":" + expected));
    // Reserve synchronously, just as the production signal handler does before dispatch.
    Runnable initial = action(seq, first, signalStatus);
    Thread owner = Thread.ofPlatform().start(initial);
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      action(seq, second, signalStatus).run();
    } finally {
      release.countDown();
      owner.join(3_000);
    }
    assertFalse(owner.isAlive());
    assertEquals(1, closes.get());
    var reason = switch (first) {
      case "upgrade" -> ShutdownRequest.Reason.UPGRADE;
      case "restart", "fatal" -> ShutdownRequest.Reason.RESTART;
      default -> ShutdownRequest.Reason.QUIT;
    };
    assertEquals(reason, seq.resultIfRun().reason());
    assertEquals(quiesced ? signalStatus : 1, code.get());
    assertEquals(switch (first) {
      case "fatal" -> null;
      case "restart" -> "ENGINE_RESTART:4";
      case "upgrade" -> "UPGRADE:0";
      default -> "QUIT:0";
    }, intent.get());
    assertFalse(Files.exists(dir.resolve("upgrade").resolve(EngineShutdownSequence.RECEIPT_FILE)));
  }

  @ParameterizedTest(name = "signalAfterSelectionKeepsOwnerStatus({index}: {arguments})")
  @CsvSource({"quit,130,0", "quit,143,0", "restart,130,4", "restart,143,4",
      "upgrade,130,0", "upgrade,143,0", "fatal,130,1", "fatal,143,1"})
  void signalAfterSelectionKeepsOwnerStatus(String owner, int signalStatus, int expected,
      @TempDir Path dir) throws Exception {
    var code = new AtomicInteger(-1);
    var sequence = new AtomicReference<EngineShutdownSequence>();
    var seq = new EngineShutdownSequence(dir, List.of(), selected -> {
      try {
        assertNull(claim(sequence.get(), signalStatus));
      } catch (Exception failure) {
        throw new AssertionError(failure);
      }
      code.set(selected);
    });
    sequence.set(seq);
    action(seq, owner, signalStatus).run();
    assertEquals(expected, code.get());
    assertEquals(expected, method("signalDeadlineExitCode", int.class).invoke(seq, 1));
    assertEquals(owner.equals("upgrade"), Files.exists(dir.resolve("upgrade")
        .resolve(EngineShutdownSequence.RECEIPT_FILE)));
  }

  @Test
  void upgradeAcknowledgementBeforeDispatchDoesNotOverrideSignalWinner(@TempDir Path dir)
      throws Exception {
    var code = new AtomicInteger(-1);
    var seq = new EngineShutdownSequence(dir, List.of(), code::set);
    Runnable signal = claim(seq, 130);
    seq.runAndExitWithReceipt("acknowledged", "nonce");
    signal.run();
    assertEquals(130, code.get());
    assertFalse(Files.exists(dir.resolve("upgrade").resolve(EngineShutdownSequence.RECEIPT_FILE)));
  }

  @Test
  void fatalPrecedenceReachesDeadlineWithoutWaitingForClose(@TempDir Path dir) throws Exception {
    var seq = new EngineShutdownSequence(dir, List.of(), ignored -> {});
    Runnable signal = claim(seq, 143);
    seq.runAndExitFatal(ShutdownRequest.Reason.RESTART);
    Method deadline = method("signalDeadlineExitCode", int.class);
    assertEquals(1, deadline.invoke(seq, 143));
    signal.run();
  }

  private static Runnable action(EngineShutdownSequence seq, String trigger, int status) throws Exception {
    return switch (trigger) {
      case "signal" -> { Runnable claimed = claim(seq, status); yield claimed == null ? () -> {} : claimed; }
      case "upgrade" -> () -> seq.runAndExitWithReceipt("test-preparation", "test-nonce");
      case "quit" -> () -> seq.runAndExit(ShutdownRequest.Reason.QUIT);
      case "restart" -> () -> seq.runAndExit(ShutdownRequest.Reason.RESTART);
      case "fatal" -> () -> seq.runAndExitFatal(ShutdownRequest.Reason.RESTART);
      default -> throw new IllegalArgumentException(trigger);
    };
  }

  private static Runnable claim(EngineShutdownSequence seq, int code) throws Exception {
    return (Runnable) method("claimSignalStop", int.class, QuitBudget.class)
        .invoke(seq, code, QuitBudget.fromNow());
  }

  private static Method method(String name, Class<?>... arguments) {
    Method found = null;
    try { found = EngineShutdownSequence.class.getMethod(name, arguments); }
    catch (NoSuchMethodException absent) { /* assertion below describes the missing authority */ }
    assertNotNull(found, "the shutdown sequence must reserve signal exits through its exit authority: " + name);
    return found;
  }
}
