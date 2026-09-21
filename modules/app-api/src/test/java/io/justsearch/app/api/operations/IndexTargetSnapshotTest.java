/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class IndexTargetSnapshotTest {
  @Test
  void preservesTheExactUtf8InputsWhoseDigestItValidates() throws Exception {
    String inputs = "{ \"shape\" : [1, 2] }\n";

    IndexTargetSnapshot snapshot = new IndexTargetSnapshot(sha256(inputs), inputs);

    assertEquals(inputs, snapshot.canonicalInputsJson());
    assertEquals(sha256(inputs), snapshot.fingerprint());
  }

  @Test
  void rejectsInvalidOrMismatchedFingerprint() throws Exception {
    String inputs = "{}";

    assertThrows(IllegalArgumentException.class,
        () -> new IndexTargetSnapshot(null, inputs));
    assertThrows(IllegalArgumentException.class,
        () -> new IndexTargetSnapshot("A".repeat(64), inputs));
    assertThrows(IllegalArgumentException.class,
        () -> new IndexTargetSnapshot("a".repeat(64), inputs));
    assertThrows(IllegalArgumentException.class,
        () -> new IndexTargetSnapshot("a".repeat(64), null));
  }

  @Test
  void appliesLimitToUtf8BytesAndAllowsTheExactBoundary() throws Exception {
    String atLimit = "é".repeat(IndexTargetSnapshot.MAX_CANONICAL_INPUTS_BYTES / 2);
    assertEquals(
        atLimit,
        new IndexTargetSnapshot(sha256(atLimit), atLimit).canonicalInputsJson());

    String overLimit = atLimit + "é";
    assertThrows(IllegalArgumentException.class,
        () -> new IndexTargetSnapshot(sha256(overLimit), overLimit));
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
