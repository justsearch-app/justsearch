/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import java.time.Clock;
import java.util.UUID;

/** RFC9562 UUIDv7 keys; their timestamp is the expiry fence, never the row ordering stamp. */
public final class OperationKeys {
  private OperationKeys() {}

  public static String generate(Clock clock) {
    long millis = clock.millis();
    if (millis < 0 || millis > 0xffffffffffffL) throw new IllegalArgumentException("UUIDv7 clock range");
    UUID random = UUID.randomUUID();
    return new UUID((millis << 16) | 0x7000L | (random.getMostSignificantBits() & 0xfffL),
        random.getLeastSignificantBits()).toString();
  }

  public static long timestampMillis(String key) {
    if (key == null) throw new IllegalArgumentException("Operation key is required");
    UUID uuid;
    try { uuid = UUID.fromString(key); }
    catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException("Operation key must be a canonical UUIDv7", failure);
    }
    if (uuid.version() != 7 || uuid.variant() != 2 || !uuid.toString().equals(key)) {
      throw new IllegalArgumentException("Operation key must be a canonical UUIDv7");
    }
    return uuid.getMostSignificantBits() >>> 16;
  }
}
