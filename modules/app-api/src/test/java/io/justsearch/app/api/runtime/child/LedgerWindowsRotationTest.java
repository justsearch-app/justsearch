/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Task b6d536 b101, verifier round 2: the shared ledger reader follows a rename even if the replacement file already grew. */
final class LedgerWindowsRotationTest {
  @TempDir Path dir;

  @Test void rotationDoesNotSkipAWindowWhenNewCurrentIsAtLeastThePreviousOffset() throws Exception {
    Path current = LedgerFamily.CHILDREN.current(dir);
    Path rotated = LedgerFamily.CHILDREN.rotated(dir);
    // The witness attached to a pre-existing current ledger and starts at its end, as in product.
    Files.writeString(current, "\n".repeat(200), StandardCharsets.UTF_8);
    var reader = new LedgerWindows(dir);
    Files.move(current, rotated);
    String window = """
        {"type":"helper-window-start","writer":{"pid":23401},"owner":{"pid":23401,"creationFileTime":"133900000000000000"},"intentId":"new-current-window","image":"tesseract.exe","reason":"rotation OCR","requestedAt":"2026-10-14T12:00:00Z","deadlineAt":"2026-10-14T12:05:00Z"}
        """;
    Files.writeString(current, window + "\n".repeat(200), StandardCharsets.UTF_8);
    assertEquals(1, reader.read().size(),
        "a rotation changed the file identity; its new window must be read even when new size >= old offset");
  }
}
