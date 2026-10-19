/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import io.justsearch.app.api.runtime.child.StopDetail;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * One readable ledger record: the v1 envelope, parsed, plus the whole line for the type-specific
 * fields. Unknown fields are kept in {@link #line} and otherwise ignored (v1 grows additively).
 *
 * @param at the writer clock, empty when unknown ({@link LedgerTimestamps})
 * @param line the whole record as read, envelope included
 */
public record LedgerRecord(
    int schemaVersion,
    LedgerRecordType type,
    long seq,
    Optional<Instant> at,
    LedgerWriterId writer,
    JsonNode line) {

  public LedgerRecord {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(writer, "writer");
    Objects.requireNonNull(line, "line");
    if (schemaVersion != ExitLedger.SCHEMA_VERSION) {
      throw new IllegalArgumentException("schemaVersion must be " + ExitLedger.SCHEMA_VERSION);
    }
    if (seq < 1) throw new IllegalArgumentException("seq starts at 1");
  }

  /** A type-specific field, or a missing node when absent. */
  public JsonNode field(String name) {
    return line.path(name);
  }

  /** A type-specific string field; empty when absent, null or not a string. */
  public Optional<String> text(String name) {
    JsonNode node = line.get(name);
    return node != null && node.isString() ? Optional.of(node.asString()) : Optional.empty();
  }

  /**
   * The stop detail, read tolerantly: a token this build does not know reads as {@link
   * StopDetail#UNKNOWN} (keep {@link #detailRaw} for display), never as a rejected record. Empty
   * when the record carries no detail.
   */
  public Optional<StopDetail> detail() {
    return detailRaw().map(StopDetail::fromWireOrUnknown);
  }

  /** The {@code detail} token exactly as written; empty when absent, null or not a string. */
  public Optional<String> detailRaw() {
    return text("detail");
  }
}
