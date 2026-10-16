/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.atrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Task b6d536 b101: a cached disk-encryption reading carries its age and read time, and a failed
 * probe does not reset the age of the value it keeps.
 */
final class DiskEncryptionReadingTest {
  private final AtomicLong clock = new AtomicLong();
  private final AtomicInteger probes = new AtomicInteger();
  private final AtomicBoolean fail = new AtomicBoolean();

  private DiskEncryptionProbe probe() {
    return new DiskEncryptionProbe(Path.of("D:/index"), clock::get, p -> {
      probes.incrementAndGet();
      if (fail.get()) return null;
      return new AtRestProtection(AtRestProtection.State.NOT_ENCRYPTED, "shell-property",
          AtRestProtection.Confidence.MEDIUM);
    });
  }

  @Test void cachedReadsShowTheirAge() {
    var probe = probe();
    probe.current();
    clock.set(90_000);
    var reading = probe.reading();
    assertEquals(Duration.ofSeconds(90), reading.age());
    assertEquals(0L, reading.probedAtMs(), "the read time is the probe's, not this read's");
    assertFalse(reading.stale());
    assertEquals(1, probes.get());
  }

  @Test void aFailedProbeKeepsTheValuesOwnAgeAndRetriesAfterTheTtl() {
    var probe = probe();
    probe.current();
    fail.set(true);
    clock.set(DiskEncryptionProbe.CACHE_TTL.toMillis());
    var stale = probe.reading();
    assertTrue(stale.stale());
    assertEquals(DiskEncryptionProbe.CACHE_TTL, stale.age(), "the kept value is as old as its last success");
    clock.addAndGet(60_000);
    probe.current();
    assertEquals(2, probes.get(), "a failure is retried after the TTL, not on every read");
    fail.set(false);
    clock.addAndGet(DiskEncryptionProbe.CACHE_TTL.toMillis());
    assertEquals("shell-property", probe.current().source(), "a later success clears the stale mark");
  }

  @Test void eachVolumeIsProbedOnItsFirstRead() {
    AtomicInteger other = new AtomicInteger();
    var data = probe();
    var elsewhere = new DiskEncryptionProbe(Path.of("E:/models"), clock::get, p -> {
      other.incrementAndGet();
      return AtRestProtection.unknown();
    });
    data.current();
    elsewhere.current();
    assertEquals(1, probes.get());
    assertEquals(1, other.get());
  }
}
