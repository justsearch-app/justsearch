/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Deterministic retention and quarantine boundary clock. */
final class OperationTestClock extends Clock {
  private long millis;
  OperationTestClock(long millis) { this.millis = millis; }
  void setMillis(long value) { millis = value; }
  @Override public long millis() { return millis; }
  @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
  @Override public ZoneId getZone() { return ZoneOffset.UTC; }
  @Override public Clock withZone(ZoneId zone) { return fixed(instant(), zone); }
}
