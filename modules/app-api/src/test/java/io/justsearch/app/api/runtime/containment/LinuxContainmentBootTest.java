/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.runtime.child.OwnedHelper;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Also compiles on the before-state: fails there because the Linux backend is absent. */
@EnabledOnOs(OS.LINUX)
final class LinuxContainmentBootTest {
  @Test void bootInstallsLinuxBackendAndKnowsOnlyItsBoundChildren() throws Exception {
    String launcher = ProcessHandle.current().info().command().orElseThrow();
    Process probe = new ProcessBuilder(launcher, "-cp", System.getProperty("java.class.path"),
        Probe.class.getName()).redirectErrorStream(true).start();
    try {
      assertTrue(probe.waitFor(15, TimeUnit.SECONDS), "fresh JVM probe finishes");
      assertEquals(0, probe.exitValue(), new String(probe.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    } finally {
      probe.destroyForcibly();
      assertTrue(probe.waitFor(2, TimeUnit.SECONDS));
    }
  }

  /** Boot is permanent; never install it into a shared unit-test JVM. */
  public static final class Probe {
    public static void main(String[] args) throws Exception {
    EngineContainment backend = EngineContainment.installAtBoot();
    assertEquals(EngineContainment.Status.CONTAINED, backend.status(), backend.toString());
    assertSame(backend, EngineContainment.installAtBoot());
    assertTrue(backend.unavailableReason().isEmpty());
    Process bound = OwnedHelper.start(new ProcessBuilder("/bin/sleep", "30"), "containment probe");
    Process foreign = new ProcessBuilder("/bin/sleep", "30").start();
    try {
      assertEquals(java.util.Optional.of(true), backend.contains(bound.toHandle()));
      assertTrue(backend.contains(foreign.toHandle()).isEmpty(), "a foreign child has no binding receipt");
    } finally {
      bound.destroyForcibly(); foreign.destroyForcibly();
      assertTrue(bound.waitFor(2, TimeUnit.SECONDS));
      assertTrue(foreign.waitFor(2, TimeUnit.SECONDS));
    }
    }
  }
}
