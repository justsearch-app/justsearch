/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

final class OperationKeysTest {
  @Test
  void rfc9562VectorAndCanonicalForm() {
    assertEquals(1645557742000L, OperationKeys.timestampMillis("017f22e2-79b0-7cc3-98c4-dc0c0c07398f"));
    assertThrows(IllegalArgumentException.class,
        () -> OperationKeys.timestampMillis("017F22E2-79B0-7CC3-98C4-DC0C0C07398F"));
    assertThrows(IllegalArgumentException.class, () -> OperationKeys.timestampMillis(java.util.UUID.randomUUID().toString()));
  }

  @Test
  void generatorPreservesTimestampAndHasRandomUniquenessWithinOneMillisecond() {
    Clock clock = Clock.fixed(Instant.ofEpochMilli(1645557742000L), ZoneOffset.UTC);
    var keys = new java.util.HashSet<String>();
    for (int i = 0; i < 100; i++) {
      String key = OperationKeys.generate(clock);
      assertEquals(clock.millis(), OperationKeys.timestampMillis(key));
      assertTrue(keys.add(key));
    }
  }

  @Test
  void canonicalArgumentDigestPreservesCapsuleSemanticsAndNoContent() {
    assertEquals(CanonicalOperationArguments.digest("{\"outer\":{\"b\":2,\"a\":1}}"),
        CanonicalOperationArguments.digest("{ \"outer\": { \"a\": 1, \"b\": 2 } }"));
    assertNotEquals(CanonicalOperationArguments.digest("[1,2]"), CanonicalOperationArguments.digest("[2,1]"));
    assertNotEquals(CanonicalOperationArguments.digest("not JSON"), CanonicalOperationArguments.digest("not JSON "));
    assertEquals(64, CanonicalOperationArguments.digest("private body").length());
  }
}
