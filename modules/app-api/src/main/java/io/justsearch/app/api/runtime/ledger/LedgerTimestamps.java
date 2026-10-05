/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Ledger timestamps. A timestamp is known only if it parses as ISO-8601 UTC and is at or after
 * 2001-01-01T00:00:00Z; {@code null}, {@code 0}, negative values, FILETIME 0 and anything earlier
 * read as unknown. Readers never compute an exit class from a timestamp.
 */
public final class LedgerTimestamps {

  /** The earliest known timestamp. */
  public static final Instant EARLIEST_KNOWN = Instant.parse("2001-01-01T00:00:00Z");

  private LedgerTimestamps() {}

  /** The wire form: ISO-8601 UTC with millisecond precision. */
  public static String format(Instant instant) {
    return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.MILLIS));
  }

  /** The instant a wire value names, or empty when it is unknown. */
  public static Optional<Instant> known(String value) {
    if (value == null || !value.endsWith("Z")) return Optional.empty();
    try {
      Instant instant = Instant.parse(value);
      return instant.isBefore(EARLIEST_KNOWN) ? Optional.empty() : Optional.of(instant);
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}
