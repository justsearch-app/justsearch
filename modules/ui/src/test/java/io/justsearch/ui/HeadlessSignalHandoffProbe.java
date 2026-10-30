/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import io.justsearch.app.api.NativeQuiescence;
import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.engine.ShutdownRequest.Reason;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.function.IntConsumer;

/** Real signal, production manifest binding and ledger writer; also executable on the baseline. */
public final class HeadlessSignalHandoffProbe {
  public static void main(String[] args) throws Exception {
    Path data = Path.of(args[0]);
    String mode = args[1];
    Object router = null;
    try {
      var type = Class.forName("io.justsearch.app.engine.EngineSignalStop");
      var constructor = type.getDeclaredConstructor(IntConsumer.class, IntConsumer.class, long.class);
      constructor.setAccessible(true);
      router = constructor.newInstance((IntConsumer) System::exit,
          (IntConsumer) code -> Runtime.getRuntime().halt(code), 1_200L);
      type.getMethod("install").invoke(router);
      type.getMethod("beginNativeInitialization").invoke(router);
    } catch (ClassNotFoundException baseline) {
      // Keep the baseline JVM's real default TERM/INT handling.
    }
    var publisher = new RuntimeManifestPublisher(data);
    publisher.publishOwnershipSeed();
    var binding = HeadlessApp.orderedShutdownSteps(null, null, null, null, publisher,
        null, null, null, null, null, null, () -> null, null, null, null).getFirst();
    var steps = new ArrayList<EngineShutdownSequence.Step>();
    steps.add(binding);
    steps.add(new EngineShutdownSequence.Step(EngineShutdownSequence.INDEX_HALF_STEP, reason -> {
      Files.writeString(data.resolve("draining"), reason.name());
      if (mode.equals("handoff-deadline")) Thread.sleep(2_000);
      return mode.equals("handoff-clean") ? "GRACEFUL" : "DRAIN_FAILED";
    }));
    var sequence = new EngineShutdownSequence(data, steps, System::exit,
        code -> Runtime.getRuntime().halt(code),
        () -> mode.equals("handoff-clean") ? NativeQuiescence.QUIESCED : NativeQuiescence.UNQUIESCED,
        ignored -> {}, HeadlessApp.engineSelfIntent(data, publisher.instanceId()));
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try { Files.writeString(data.resolve("hook"), "started"); }
      catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
      sequence.runFromJvmShutdownHook(Reason.QUIT);
    }));
    if (router != null) {
      router.getClass().getMethod("publish", EngineShutdownSequence.class).invoke(router, sequence);
      router.getClass().getMethod("ready").invoke(router);
    }
    Files.writeString(data.resolve("ready"), publisher.instanceId());
    Thread.sleep(30_000);
    throw new AssertionError("signal was not delivered");
  }
}
