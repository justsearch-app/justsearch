/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.agent.api.registry.SourceTier;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class OperationAuthorizationBasisTest {
  @Test
  void roundTripsAllBasisVariantsWithCanonicalWireValues() {
    assertEquals("jsa1:auto", new OperationAuthorizationBasis.StructuralAuto().encode());
    assertEquals("jsa1:capsule", new OperationAuthorizationBasis.EphemeralCapsule().encode());
    assertEquals(new OperationAuthorizationBasis.StructuralAuto(),
        OperationAuthorizationBasis.decode("jsa1:auto"));
    assertEquals(new OperationAuthorizationBasis.EphemeralCapsule(),
        OperationAuthorizationBasis.decode("jsa1:capsule"));

    String key = "01994180-0000-7000-8000-000000000abc";
    UUID nonce = UUID.fromString("00000000-0000-4000-8000-000000000abc");
    var continuation = new OperationAuthorizationBasis.PreparedContinuation(key, nonce);
    assertEquals("jsa1:continuation:" + key + ":" + nonce, continuation.encode());
    assertEquals(continuation, OperationAuthorizationBasis.decode(continuation.encode()));

    for (SourceTier tier : SourceTier.values()) {
      for (String target : List.of("core.ingest", "file operations/ß:未来")) {
        var operation = new OperationAuthorizationBasis.OperationGrant(target, tier);
        var family = new OperationAuthorizationBasis.FamilyGrant(target, tier);
        assertEquals(operation, OperationAuthorizationBasis.decode(operation.encode()));
        assertEquals(family, OperationAuthorizationBasis.decode(family.encode()));
        assertEquals(tier, ((OperationAuthorizationBasis.OperationGrant)
            OperationAuthorizationBasis.decode(operation.encode())).sourceTier());
      }
    }
  }

  @Test
  void operationAndFamilyGrantsRemainDistinctEvenWithSameTargetAndTier() {
    var operation = new OperationAuthorizationBasis.OperationGrant("files", SourceTier.TRUSTED);
    var family = new OperationAuthorizationBasis.FamilyGrant("files", SourceTier.TRUSTED);
    assertEquals("jsa1:op:TRUSTED:ZmlsZXM", operation.encode());
    assertEquals("jsa1:family:TRUSTED:ZmlsZXM", family.encode());
    assertEquals(operation, OperationAuthorizationBasis.decode(operation.encode()));
    assertEquals(family, OperationAuthorizationBasis.decode(family.encode()));
  }

  @Test
  void rejectsUnknownVersionsKindsTiersAndNonCanonicalBase64() {
    for (String malformed : List.of(
        "jsa2:auto", "jsa1:unknown", "jsa1:auto:extra", "jsa1:op:BOGUS:Zg",
        "jsa1:op:TRUSTED:Zg==", "jsa1:op:TRUSTED:Zh", "jsa1:op:TRUSTED:Zg%")) {
      assertThrows(IllegalArgumentException.class,
          () -> OperationAuthorizationBasis.decode(malformed), malformed);
    }
  }

  @Test
  void rejectsMalformedPreparedContinuationKeyAndNonce() {
    String key = "01994180-0000-7000-8000-000000000abc";
    String nonce = "00000000-0000-4000-8000-000000000abc";
    for (String malformed : List.of(
        "jsa2:continuation:" + key + ":" + nonce,
        "jsa1:continuation:01994180-0000-4000-8000-000000000121:" + nonce,
        "jsa1:continuation:01994180-0000-7000-c000-000000000abc:" + nonce,
        "jsa1:continuation:" + key.toUpperCase(java.util.Locale.ROOT) + ":" + nonce,
        "jsa1:continuation:" + key + ":00000000-0000-4000-8000-00000000012Z",
        "jsa1:continuation:" + key + ":" + nonce.toUpperCase(java.util.Locale.ROOT),
        "jsa1:continuation:" + key + ":" + nonce + ":extra")) {
      assertThrows(IllegalArgumentException.class,
          () -> OperationAuthorizationBasis.decode(malformed), malformed);
    }

    assertThrows(IllegalArgumentException.class,
        () -> new OperationAuthorizationBasis.PreparedContinuation(
            "00000000-0000-4000-8000-000000000121", UUID.fromString(nonce)));
  }

  @Test
  void rejectsMalformedUtf8AndInvalidTargets() {
    String malformedUtf8 = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(new byte[] {(byte) 0xc3, 0x28});
    assertThrows(IllegalArgumentException.class,
        () -> OperationAuthorizationBasis.decode("jsa1:op:TRUSTED:" + malformedUtf8));
    assertThrows(IllegalArgumentException.class,
        () -> OperationAuthorizationBasis.decode("jsa1:op:TRUSTED:AA"));
    assertThrows(IllegalArgumentException.class,
        () -> new OperationAuthorizationBasis.OperationGrant(" ", SourceTier.TRUSTED));
    assertThrows(IllegalArgumentException.class,
        () -> new OperationAuthorizationBasis.OperationGrant("bad\nvalue", SourceTier.TRUSTED));
    assertThrows(IllegalArgumentException.class,
        () -> new OperationAuthorizationBasis.OperationGrant("bad\ud800value", SourceTier.TRUSTED));
    assertThrows(NullPointerException.class,
        () -> new OperationAuthorizationBasis.OperationGrant(null, SourceTier.TRUSTED));
    assertThrows(NullPointerException.class,
        () -> new OperationAuthorizationBasis.OperationGrant("target", null));
  }

  @Test
  void acceptsExactly256EncodedCharactersAndRejectsOneMore() {
    String atLimit = "a".repeat(180);
    var basis = new OperationAuthorizationBasis.OperationGrant(atLimit, SourceTier.TRUSTED);
    assertEquals(256, basis.encode().length());
    assertEquals(basis, OperationAuthorizationBasis.decode(basis.encode()));

    assertThrows(IllegalArgumentException.class,
        () -> new OperationAuthorizationBasis.OperationGrant("a".repeat(181), SourceTier.TRUSTED));
    assertThrows(IllegalArgumentException.class,
        () -> OperationAuthorizationBasis.decode(basis.encode() + "a"));
  }

  @Test
  void constructorValidationPreventsUnencodableNonAsciiAndControlForms() {
    var unicode = new OperationAuthorizationBasis.FamilyGrant("é".repeat(80), SourceTier.MEDIUM);
    assertDoesNotThrow(unicode::encode);
    assertEquals(unicode, OperationAuthorizationBasis.decode(unicode.encode()));
    assertThrows(IllegalArgumentException.class,
        () -> new OperationAuthorizationBasis.FamilyGrant("\u007f", SourceTier.UNTRUSTED));
    assertThrows(IllegalArgumentException.class,
        () -> new OperationAuthorizationBasis.FamilyGrant("a".repeat(200), SourceTier.UNTRUSTED));
    assertEquals("file/ß", new OperationAuthorizationBasis.OperationGrant(
        "file/ß", SourceTier.TRUSTED).target());
    assertEquals("file/ß".getBytes(StandardCharsets.UTF_8).length, 7);
  }
}
