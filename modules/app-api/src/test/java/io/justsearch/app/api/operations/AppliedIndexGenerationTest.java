/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class AppliedIndexGenerationTest {
  @Test
  void retainsTheValidatedTargetAndGenerationIdentity() throws Exception {
    IndexTargetSnapshot target = target("{}");

    AppliedIndexGeneration applied = new AppliedIndexGeneration("g-active", target);

    assertEquals("g-active", applied.generationId());
    assertEquals(target, applied.target());
  }

  @Test
  void rejectsMissingIdentityOrTarget() throws Exception {
    IndexTargetSnapshot target = target("{}");

    assertThrows(IllegalArgumentException.class, () -> new AppliedIndexGeneration(null, target));
    assertThrows(IllegalArgumentException.class, () -> new AppliedIndexGeneration("  ", target));
    assertThrows(NullPointerException.class, () -> new AppliedIndexGeneration("g-active", null));
  }

  private static IndexTargetSnapshot target(String inputs) throws Exception {
    byte[] bytes = inputs.getBytes(StandardCharsets.UTF_8);
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    return new IndexTargetSnapshot(digest, inputs);
  }
}
