/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.api.NativeQuiescence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Isolated child for proving that native refusal bypasses JVM shutdown hooks. */
public final class EngineShutdownSequenceExitProbe {
  private EngineShutdownSequenceExitProbe() {}

  public static void main(String[] args) {
    Path directory = Path.of(args[0]);
    NativeQuiescence status = NativeQuiescence.valueOf(args[1]);
    boolean race = args.length > 2 && "hook-race".equals(args[2]);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var indexStep = new EngineShutdownSequence.Step(EngineShutdownSequence.INDEX_HALF_STEP,
        ignored -> "GRACEFUL");
    var sequence = new EngineShutdownSequence(directory,
        race ? List.of(new EngineShutdownSequence.Step("held-native", ignored -> {
          entered.countDown();
          if (!release.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Competing shutdown hook did not run");
          }
          return null;
        }), indexStep) : List.of(indexStep),
        System::exit, code -> Runtime.getRuntime().halt(code), () -> status, ignored -> {});
    if (race) {
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        try {
          if (!entered.await(10, TimeUnit.SECONDS)) return;
          Files.writeString(directory.resolve("competing-hook-ran"), "yes");
        } catch (java.io.IOException failure) {
          throw new java.io.UncheckedIOException(failure);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        } finally {
          release.countDown();
        }
      }));
    }
    if (args.length > 2 && ("hook".equals(args[2]) || race)) {
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        sequence.runFromJvmShutdownHook(ShutdownRequest.Reason.QUIT);
        writeMarker(directory);
      }));
      System.exit(0);
      throw new AssertionError("JVM exit returned");
    }
    Runtime.getRuntime().addShutdownHook(new Thread(() -> writeMarker(directory)));
    sequence.runAndExit(ShutdownRequest.Reason.QUIT);
    throw new AssertionError("Process termination returned");
  }

  private static void writeMarker(Path directory) {
    try { Files.writeString(directory.resolve("jvm-hook-ran"), "yes"); }
    catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
  }
}
