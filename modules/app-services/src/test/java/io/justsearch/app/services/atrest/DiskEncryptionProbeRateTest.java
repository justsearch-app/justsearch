/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.atrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Task b6d536 b101: the disk-encryption state is probed at most once per volume per ten minutes
 * under the status and health readers, and a failed probe keeps the last known value marked
 * stale, never reported as protected. Deterministic clock and read mechanism; runs unchanged on
 * b599d4d7e, which probed every five seconds and forgot the value on any failure.
 */
final class DiskEncryptionProbeRateTest {
  private static final Path VOL = Path.of("C:/data");
  private static final long MINUTE = 60_000;

  @Test void readersEveryFewSecondsProbeOncePerTenMinutes() {
    AtomicLong clock = new AtomicLong();
    AtomicInteger probes = new AtomicInteger();
    DiskEncryptionProbe probe = new DiskEncryptionProbe(VOL, clock::get, p -> {
      probes.incrementAndGet();
      return new AtRestProtection(AtRestProtection.State.NOT_ENCRYPTED, "shell-property",
          AtRestProtection.Confidence.MEDIUM);
    });
    // The status lane, the at-rest tap and the conversation tap read every 2.7 s for 30 minutes.
    for (long t = 0; t < 30 * MINUTE; t += 2_700) {
      clock.set(t);
      probe.current();
      probe.current();
      probe.current();
    }
    assertTrue(probes.get() <= 3, "30 minutes of readers: at most one probe per ten minutes, got " + probes.get());
    assertEquals(AtRestProtection.State.NOT_ENCRYPTED, probe.current().state());
  }

  @Test void aFailedProbeKeepsTheLastKnownValueMarkedStale() {
    AtomicLong clock = new AtomicLong();
    AtomicBoolean fail = new AtomicBoolean();
    DiskEncryptionProbe probe = new DiskEncryptionProbe(VOL, clock::get, p -> {
      if (fail.get()) throw new IllegalStateException("powershell timed out");
      return new AtRestProtection(AtRestProtection.State.NOT_ENCRYPTED, "shell-property",
          AtRestProtection.Confidence.MEDIUM);
    });
    probe.current();
    fail.set(true);
    clock.set(61 * MINUTE);
    AtRestProtection stale = probe.current();
    assertEquals(AtRestProtection.State.NOT_ENCRYPTED, stale.state(), "the last known value is kept");
    assertTrue(stale.source().startsWith("stale:"), "and marked stale: " + stale);
    assertEquals(AtRestProtection.Confidence.LOW, stale.confidence());
  }

  @Test void aStaleProtectedValueIsNeverReportedAsProtected() {
    AtomicLong clock = new AtomicLong();
    AtomicBoolean fail = new AtomicBoolean();
    DiskEncryptionProbe probe = new DiskEncryptionProbe(VOL, clock::get, p -> {
      if (fail.get()) throw new IllegalStateException("powershell exited 1");
      return new AtRestProtection(AtRestProtection.State.ENCRYPTED, "shell-property",
          AtRestProtection.Confidence.MEDIUM);
    });
    assertEquals(AtRestProtection.State.ENCRYPTED, probe.current().state());
    fail.set(true);
    clock.set(61 * MINUTE);
    AtRestProtection after = probe.current();
    assertNotEquals(AtRestProtection.State.ENCRYPTED, after.state(), "a stale value never reads as protected");
    assertTrue(after.source().startsWith("stale:"), "the failure is visible as staleness: " + after);
  }
}
