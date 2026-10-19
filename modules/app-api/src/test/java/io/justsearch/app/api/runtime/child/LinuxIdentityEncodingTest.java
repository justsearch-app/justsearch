/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
class LinuxIdentityEncodingTest {
  @Test void canonicalUnsignedTicksAgreeWithTheNativeSignalBoundary() throws Exception {
    Process child = new ProcessBuilder("/bin/sleep", "30").start();
    try {
      var identity = LinuxProcessIdentity.read(child.pid()).orElseThrow();
      for (String ticks : java.util.List.of("0", "18446744073709551615")) {
        assertEquals(3, new LinuxProcessIdentity(identity.kind(), identity.pid(), identity.bootId(), ticks).signal("KILL"));
        assertTrue(child.isAlive(), "a different birth must never signal the occupant");
      }
      for (String ticks : java.util.List.of("0" + identity.startTicks(), "18446744073709551616")) {
        assertThrows(IllegalArgumentException.class,
            () -> new LinuxProcessIdentity(identity.kind(), identity.pid(), identity.bootId(), ticks));
        Process helper = new ProcessBuilder(BoundChildProcess.helper().toString(), "signal",
            Long.toString(child.pid()), identity.bootId(), ticks, "KILL").start();
        try {
          assertTrue(helper.waitFor(2, TimeUnit.SECONDS));
          assertEquals(2, helper.exitValue(), "malformed native arguments must not authorize a signal");
          assertTrue(child.isAlive());
        } finally { helper.destroyForcibly(); assertTrue(helper.waitFor(2, TimeUnit.SECONDS)); }
      }
    } finally { child.destroyForcibly(); assertTrue(child.waitFor(2, TimeUnit.SECONDS)); }
  }
}
