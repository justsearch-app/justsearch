/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/** One file handshake for identity-verified installed supervisor barriers. */
public final class HarnessBarrierProtocol {
  private HarnessBarrierProtocol() {}

  public static Path reached(Path dataDir, String family) {
    return dataDir.resolve("runtime").resolve(family + "-reached.json");
  }

  public static void await(Path dataDir, String family, String markerJson, boolean selfExit)
      throws IOException, InterruptedException {
    Path runtime = dataDir.resolve("runtime");
    Path reached = reached(dataDir, family);
    Path release = runtime.resolve(family + "-release");
    Files.createDirectories(runtime);
    Path pending = runtime.resolve(family + "-reached.pending");
    Files.writeString(pending, markerJson);
    Files.move(pending, reached, StandardCopyOption.ATOMIC_MOVE);
    if (selfExit) haltHarnessEngine();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
    while (!Files.exists(release)) {
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException("Harness barrier was not released: " + family);
      }
      Thread.sleep(10);
    }
  }

  // An installed crash cut must die before the ordinary shutdown path changes durable state.
  @SuppressWarnings("PMD.DoNotTerminateVM")
  private static void haltHarnessEngine() {
    Runtime.getRuntime().halt(1);
  }
}
