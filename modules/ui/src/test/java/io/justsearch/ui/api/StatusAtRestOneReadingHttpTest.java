/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.services.atrest.AtRestProtection;
import io.justsearch.app.services.atrest.DiskEncryptionProbe;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task b6d536 b101: /api/status shows the disk-encryption value and its read time from one reading.
 * The probe's clock crosses the ten-minute expiry between two consecutive reads of it, and the probe
 * after the expiry answers differently: the status must pair the new value with the new time, not
 * the old reading's value with the new reading's time. Real status route over HTTP.
 */
final class StatusAtRestOneReadingHttpTest {
  @TempDir Path temp;

  @Test void statusPairsTheDiskValueWithTheTimeOfTheSameReading() throws Exception {
    AtomicLong time = new AtomicLong();
    AtomicInteger probes = new AtomicInteger();
    // The clock reads 599 999 ms once, then 600 000 ms: the expiry falls between two reads.
    LongSupplier crossing = () -> time.getAndUpdate(v -> v == 599_999L ? 600_000L : v);
    Function<Path, AtRestProtection> mechanism = volume -> new AtRestProtection(
        probes.getAndIncrement() == 0 ? AtRestProtection.State.ENCRYPTED : AtRestProtection.State.NOT_ENCRYPTED,
        "test-shell", AtRestProtection.Confidence.MEDIUM);
    var seam = DiskEncryptionProbe.class.getDeclaredConstructor(Path.class, LongSupplier.class, Function.class);
    seam.setAccessible(true);
    DiskEncryptionProbe probe = seam.newInstance(temp.resolve("volume"), crossing, mechanism);
    assertEquals(AtRestProtection.State.ENCRYPTED, probe.current().state(), "first reading at 0 ms");
    time.set(599_999L);
    try (var fixture = new StatusReadDeadlineHttpTest.Fixture(temp)) {
      fixture.handler.setAtRestTap(null, probe);
      fixture.handler.sampleAndBuildStatusSnapshot();
      var atRest = fixture.read("").body().path("atRestProtection");
      long readAt = atRest.path("probedAtMs").asLong(-1);
      String value = atRest.path("diskEncryption").asText();
      // Either reading is acceptable alone; value and time must come from the same one.
      String pair = value + "@" + readAt;
      if (readAt == 600_000L) {
        assertEquals("NOT_ENCRYPTED@600000", pair, "the reading at 600 000 ms was NOT_ENCRYPTED: " + atRest);
      } else {
        assertEquals("ENCRYPTED@0", pair, "the reading at 0 ms was ENCRYPTED: " + atRest);
      }
    }
  }
}
