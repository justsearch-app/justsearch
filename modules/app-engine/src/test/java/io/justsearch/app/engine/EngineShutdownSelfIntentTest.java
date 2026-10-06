/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.api.NativeQuiescence;
import io.justsearch.app.engine.EngineShutdownSequence.Step;
import io.justsearch.app.engine.ShutdownRequest.Reason;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec B §6.1: the Engine records one {@code engine-self-intent} before an ordered close it starts
 * itself, with the reason and the exit code it expects to leave, and none before a fatal exit.
 *
 * <p>The sequence's self-intent seam is reached by name, so on a build without it every case runs
 * and observes that nothing was recorded.
 */
final class EngineShutdownSelfIntentTest {

  /** One sequence over a single recording step, with the intent recorder when the build has it. */
  private static final class Probe {
    final List<String> events = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger code = new AtomicInteger(-1);
    final EngineShutdownSequence sequence;

    Probe(Path dataDir, boolean failingRecorder) throws Exception {
      List<Step> steps = List.of(new Step(EngineShutdownSequence.INDEX_HALF_STEP, reason -> {
        events.add("close " + reason.wire());
        return "GRACEFUL";
      }));
      IntConsumer exit = code::set;
      Supplier<NativeQuiescence> quiesced = () -> NativeQuiescence.QUIESCED;
      EngineShutdownSequence.CompletionAction done = preliminary -> {};
      Class<?> seam = selfIntentSeam();
      if (seam == null) {
        sequence = new EngineShutdownSequence(dataDir, steps, exit, exit, quiesced, done);
        return;
      }
      Object recorder = Proxy.newProxyInstance(seam.getClassLoader(), new Class<?>[] {seam},
          (proxy, method, args) -> {
            if (!method.getName().equals("record")) return method.invoke(this, args);
            if (failingRecorder) throw new java.io.IOException("ledger unavailable");
            String detail = ((Optional<?>) args[1]).map(EngineShutdownSelfIntentTest::token)
                .orElse("-");
            events.add("intent " + args[0] + " " + detail + " " + args[2]);
            return null;
          });
      Constructor<EngineShutdownSequence> withSeam = EngineShutdownSequence.class.getConstructor(
          Path.class, List.class, IntConsumer.class, IntConsumer.class, Supplier.class,
          EngineShutdownSequence.CompletionAction.class, seam);
      sequence = withSeam.newInstance(dataDir, steps, exit, exit, quiesced, done, recorder);
    }
  }

  private static Class<?> selfIntentSeam() {
    try {
      return Class.forName(EngineShutdownSequence.class.getName() + "$SelfIntent");
    } catch (ClassNotFoundException absent) {
      return null;
    }
  }

  private static String token(Object detail) {
    try {
      return String.valueOf(detail.getClass().getMethod("token").invoke(detail));
    } catch (ReflectiveOperationException e) {
      return String.valueOf(detail);
    }
  }

  @Test
  void eachCooperativeExitRecordsItsReasonAndExpectedCodeBeforeTheClose(@TempDir Path dataDir)
      throws Exception {
    List<List<String>> seen = new ArrayList<>();
    for (Reason reason : List.of(Reason.QUIT, Reason.RESTART, Reason.UPGRADE, Reason.HANG)) {
      Probe probe = new Probe(dataDir, false);
      probe.sequence.runAndExit(reason);
      seen.add(List.copyOf(probe.events));
    }
    assertEquals(List.of(
        List.of("intent QUIT - 0", "close quit"),
        List.of("intent ENGINE_RESTART - 4", "close restart"),
        List.of("intent UPGRADE - 0", "close upgrade"),
        List.of("intent ENGINE_RESTART hang 0", "close hang")), seen);
  }

  @Test
  void escalatedRestartRecordsAnEngineRestartExpectingCodeFive(@TempDir Path dataDir)
      throws Exception {
    Probe probe = new Probe(dataDir, false);
    probe.sequence.runAndExitEscalated();
    assertEquals(List.of("intent ENGINE_RESTART escalated 5", "close restart"), probe.events);
    assertEquals(EngineExit.ESCALATED_RESTART, probe.code.get());
  }

  @Test
  void updaterExitRecordsAnUpgradeExpectingCodeZero(@TempDir Path dataDir) throws Exception {
    Probe probe = new Probe(dataDir, false);
    probe.sequence.runAndExitWithReceipt("prep-1", "nonce-1");
    assertEquals(List.of("intent UPGRADE - 0", "close upgrade"), probe.events);
    assertEquals(0, probe.code.get());
  }

  @Test
  void aCloseTheJvmHookStartsRecordsItsReasonOnce(@TempDir Path dataDir) throws Exception {
    Probe probe = new Probe(dataDir, false);
    probe.sequence.runFromJvmShutdownHook(Reason.QUIT);
    probe.sequence.runFromJvmShutdownHook(Reason.QUIT);
    assertEquals(List.of("intent QUIT - 0", "close quit"), probe.events);
  }

  /** Preservation: a fatal exit stays UNEXPECTED, so it records no intent, nor does its hook. */
  @Test
  void aFatalExitRecordsNoIntentAndItsHookAddsNone(@TempDir Path dataDir) throws Exception {
    Probe probe = new Probe(dataDir, false);
    probe.sequence.runAndExitFatal(Reason.RESTART);
    probe.sequence.runFromJvmShutdownHook(Reason.QUIT);
    assertEquals(List.of("close restart"), probe.events);
    assertEquals(EngineExit.FATAL_OR_UNCAUGHT, probe.code.get());
  }

  /** Preservation: the hook joining a cooperative exit adds nothing to it. */
  @Test
  void theHookJoiningACooperativeExitAddsNoSecondIntent(@TempDir Path dataDir) throws Exception {
    Probe probe = new Probe(dataDir, false);
    probe.sequence.runAndExit(Reason.RESTART);
    probe.sequence.runFromJvmShutdownHook(Reason.QUIT);
    assertEquals(EngineExit.REQUESTED_RESTART, probe.code.get());
    assertEquals(1, probe.events.stream().filter(e -> e.startsWith("close")).count());
    assertEquals(0, probe.events.stream().filter(e -> e.startsWith("intent QUIT")).count());
  }

  /** Preservation: the intent is evidence, never a gate; a failed record leaves the exit as it was. */
  @Test
  void aFailedIntentRecordDoesNotChangeTheExit(@TempDir Path dataDir) throws Exception {
    Probe probe = new Probe(dataDir, true);
    probe.sequence.runAndExit(Reason.QUIT);
    assertEquals(List.of("close quit"), probe.events);
    assertEquals(EngineExit.OK, probe.code.get());
  }
}
