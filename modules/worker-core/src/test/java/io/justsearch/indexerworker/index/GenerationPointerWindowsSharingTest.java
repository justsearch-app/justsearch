/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.index;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
@Timeout(30)
class GenerationPointerWindowsSharingTest {
  @TempDir Path temporary;

  @Test
  void windowsSharingDenialRetriesUntilTheReaderReleasesTheCurrentPointer() throws Exception {
    var manager = new IndexGenerationManager(temporary);
    manager.initializeOrLoad();
    String script = "$f = [IO.File]::Open($args[0], 'Open', 'Read', 'Read'); "
        + "[Console]::WriteLine('OPEN'); [Console]::Out.Flush(); "
        + "[Console]::ReadLine() | Out-Null; $f.Dispose(); [Console]::WriteLine('CLOSED')";
    Path helper = temporary.resolve("hold-pointer.ps1");
    Files.writeString(helper, script);
    Process reader = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
        "-ExecutionPolicy", "Bypass", "-File", helper.toString(),
        temporary.resolve("state.json").toString()).redirectErrorStream(true).start();
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    Thread writer = new Thread(() -> {
      try { manager.setMigrationPaused(true, "reader sharing denial"); }
      catch (Throwable error) { failure.set(error); }
    }, "generation-pointer-sharing-test");
    try (var response = reader.inputReader(); var release = reader.outputWriter()) {
      assertEquals("OPEN", response.readLine());
      writer.start();
      boolean retrying = false;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
      while (writer.isAlive() && System.nanoTime() < deadline) {
        retrying = writer.getState() == Thread.State.TIMED_WAITING
            && java.util.Arrays.stream(writer.getStackTrace()).anyMatch(frame ->
                frame.getClassName().endsWith("AtomicFileWrites")
                    && frame.getMethodName().equals("moveAtomicReplace"));
        if (retrying) break;
        Thread.onSpinWait();
      }
      // Release before asserting, so a failed assertion never holds up its own writer cleanup.
      release.write("release\n");
      release.flush();
      assertEquals("CLOSED", response.readLine());
      writer.join(10_000);
      assertTrue(retrying, "pointer publication must retry the observed Windows sharing denial");
      assertFalse(writer.isAlive());
      assertNull(failure.get());
      assertTrue(new IndexGenerationManager(temporary).readStateBestEffort().migration_paused());
    } finally {
      if (reader.isAlive()) {
        reader.destroyForcibly();
        assertTrue(reader.waitFor(5, TimeUnit.SECONDS));
      }
      if (writer.isAlive()) { writer.interrupt(); writer.join(5_000); }
    }
  }

}
