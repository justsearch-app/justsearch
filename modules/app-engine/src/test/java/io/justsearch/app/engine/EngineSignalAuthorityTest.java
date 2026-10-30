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
  @CsvSource({"signal,upgrade,143", "upgrade,signal,143", "signal,quit,143", "quit,signal,143", "signal,restart,143", "restart,signal,143", "signal,fatal,143", "fatal,signal,143", "signal,upgrade,130", "upgrade,signal,130", "signal,quit,130", "quit,signal,130", "signal,restart,130", "restart,signal,130", "signal,fatal,130", "fatal,signal,130"})
  void competingTriggersCloseOnceAndKeepFirstReason(String first, String second, int signalStatus,
      @TempDir Path dir) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var closes = new AtomicInteger();
    var code = new AtomicInteger(-1);
    var seq = new EngineShutdownSequence(dir, List.of(new EngineShutdownSequence.Step(
        EngineShutdownSequence.INDEX_HALF_STEP, reason -> {
          closes.incrementAndGet();
          entered.countDown();
          if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test did not release");
          return "GRACEFUL";
        })), code::set);
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
    int expected = first.equals("fatal") || second.equals("fatal") ? 1
        : first.equals("signal") ? signalStatus
        : first.equals("restart") ? EngineExit.REQUESTED_RESTART : 0;
    assertEquals(expected, code.get());
    assertEquals(first.equals("upgrade"), Files.exists(dir.resolve("upgrade")
        .resolve(EngineShutdownSequence.RECEIPT_FILE)));
    if (first.equals("upgrade")) {
      String receipt = Files.readString(dir.resolve("upgrade").resolve(EngineShutdownSequence.RECEIPT_FILE));
      assertTrue(receipt.contains("test-nonce"));
      assertTrue(receipt.contains("test-preparation"));
    }
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
