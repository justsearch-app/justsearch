/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
final class LinuxReapedSpawnTest {
  @Test void originalSpawnKeepsItsExitFactsAfterReaping() throws Exception {
    Process process = new ProcessBuilder("/bin/sh", "-c", "exit 0").start();
    assertTrue(process.waitFor(5, TimeUnit.SECONDS));
    var held = ProcessTerminator.forCurrentOs().open(process);
    try {
      assertEquals(0, held.exitCode().orElseThrow());
      assertTrue(held.awaitExit(Duration.ZERO));
      assertEquals(TerminateResult.Status.ALREADY_EXITED, held.terminate(137).status());
      assertEquals(Optional.empty(), held.exitedAt(), "observation time is never an invented exit time");
      assertEquals(Optional.empty(), held.creationFileTime(), "Linux never invents a Windows birth");
    } finally { held.close(); }
  }
}
