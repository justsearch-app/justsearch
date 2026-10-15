/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Task b6d536 b101, verifier round 2: a start without an end lasts until a proven owner end, not its declared deadline. */
final class OpenWindowLifetimeTest {
  @Test void startWithoutEndCoversLaunchesPastDeadlineUntilProvenOwnerExit() {
    Instant start = Instant.parse("2026-10-14T12:00:00Z");
    long owner = 912340;
    String birth = "133900000000000000";
    ProcessIntents.windowOpened("open-" + UUID.randomUUID(), "tesseract.exe", "open OCR",
        start, start.plusSeconds(1), owner, birth);
    var live = ProcessIntents.resolve(912341, "133900000000000001", "C:\\test\\tesseract.exe",
        start.plusSeconds(2), start.plusSeconds(3), true, owner, birth).orElseThrow();
    assertEquals("REQUESTED", live.exitClass(), "No window end or owner exit was proven: " + live);
    ProcessIntents.outcome(owner, birth, "UNEXPECTED", "owner crash", start.plusSeconds(4));
    var afterDeath = ProcessIntents.resolve(912342, "133900000000000002", "C:\\test\\tesseract.exe",
        start.plusSeconds(5), start.plusSeconds(6), true, owner, birth).orElseThrow();
    assertEquals("UNEXPECTED", afterDeath.exitClass(), "Owner's proven exit is the end: " + afterDeath);
  }
}
