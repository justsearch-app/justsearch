/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.coordination;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.scheduling.GpuSchedulingGauge;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lane F stage A review S2 — the dev hot-reload trigger, re-homed off the memory-mapped bus.
 *
 * <p>The trigger was the one part of hot reload that genuinely lived in the shared memory region:
 * the dev MCP {@code reload} tool wrote a byte at offset 29 of {@code worker_signal.lock} and the
 * Worker's sentinel thread polled it. Item A6 put the index half inside the Head's JVM, where the
 * signal bus became {@link InProcessWorkerSignalBus} — whose {@code isReloadRequested()} was the
 * interface default, {@code false}. Deferring the re-homing to item A18 therefore did not postpone
 * the change; it turned hot reload off, silently and for the whole of stage A, while the MCP tool
 * went on reporting a successful bytecode push. Nothing could fail: the producer wrote to a file
 * nobody read, and the consumer read a default nobody wrote.
 *
 * <p>Which is why these are the assertions. Not "the file format is right" — there is no format —
 * but that the two halves meet: a request appearing is observed, and consuming it removes it, so
 * one push causes one reload and a reload does not loop.
 */
@DisplayName("InProcessWorkerSignalBus — dev reload request (review S2)")
final class InProcessWorkerSignalBusReloadTest {

  @TempDir Path tempDir;

  private Path request(Path runtimeDir) {
    return runtimeDir.resolve(InProcessWorkerSignalBus.RELOAD_REQUEST_FILENAME);
  }

  @Test
  @DisplayName("a request file appearing is a reload request; consuming it removes it")
  void theRequestFileIsTheSignal() throws Exception {
    Path runtimeDir = Files.createDirectories(tempDir.resolve("runtime"));
    InProcessWorkerSignalBus bus =
        new InProcessWorkerSignalBus(new GpuSchedulingGauge(), runtimeDir);

    assertFalse(bus.isReloadRequested(), "no request, no reload");

    Files.writeString(request(runtimeDir), "2026-09-07T00:00:00Z reload requested\n");
    assertTrue(bus.isReloadRequested(), "the sentinel must see the dev tool's request");

    bus.clearReloadSignal();
    assertFalse(
        Files.exists(request(runtimeDir)),
        "consuming the request must delete it, or the reload loops on every sentinel tick");
    assertFalse(bus.isReloadRequested());
  }

  @Test
  @DisplayName("a request arriving during a reload is not consumed by that reload")
  void aRequestArrivingDuringAReloadSurvives() throws Exception {
    // DevReloadManager clears FIRST and then does the work, precisely so that a compile landing
    // mid-reload leaves a fresh request behind and gets its own reload. That ordering only buys
    // anything if a second request written after the clear is still visible afterwards.
    Path runtimeDir = Files.createDirectories(tempDir.resolve("runtime"));
    InProcessWorkerSignalBus bus =
        new InProcessWorkerSignalBus(new GpuSchedulingGauge(), runtimeDir);

    Files.writeString(request(runtimeDir), "first\n");
    assertTrue(bus.isReloadRequested());
    bus.clearReloadSignal(); // the reload begins here

    Files.writeString(request(runtimeDir), "second, written while the reload was running\n");
    assertTrue(
        bus.isReloadRequested(),
        "the second push must get its own reload, not be swallowed by the first");
  }

  @Test
  @DisplayName("clearing a request that is not there is not an error")
  void clearingAnAbsentRequestIsSilent() throws Exception {
    Path runtimeDir = Files.createDirectories(tempDir.resolve("runtime"));
    InProcessWorkerSignalBus bus =
        new InProcessWorkerSignalBus(new GpuSchedulingGauge(), runtimeDir);
    bus.clearReloadSignal();
    assertFalse(bus.isReloadRequested());
  }

  @Test
  @DisplayName("a runtime directory that does not exist is not a reload request")
  void anAbsentRuntimeDirectoryIsNotARequest() {
    // The sentinel polls this once a second from boot, before anything has created the directory.
    InProcessWorkerSignalBus bus =
        new InProcessWorkerSignalBus(new GpuSchedulingGauge(), tempDir.resolve("never-created"));
    assertFalse(bus.isReloadRequested());
    bus.clearReloadSignal();
  }

  @Test
  @DisplayName("a bus with no runtime directory has hot reload off, not pointed at a guess")
  void noRuntimeDirectoryMeansNoHotReload() throws Exception {
    // The no-arg constructor is what tests and any composition without a data dir use. It must
    // not invent a path: a bus polling a directory the dev tool never writes to would look wired
    // and never fire, which is the defect this whole item is about.
    InProcessWorkerSignalBus bus = new InProcessWorkerSignalBus(new GpuSchedulingGauge());
    Path runtimeDir = Files.createDirectories(tempDir.resolve("runtime"));
    Files.writeString(request(runtimeDir), "ignored\n");
    assertFalse(bus.isReloadRequested());
  }
}
