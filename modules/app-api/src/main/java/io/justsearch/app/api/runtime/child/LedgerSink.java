/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import io.justsearch.app.api.runtime.ledger.ExitLedgerWriter;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import java.util.Map;

/**
 * Where child records go: the Engine's {@code children} ledger writer in production, a recorder in
 * tests. Same contract as {@link ExitLedgerWriter#append}: true when the record is durable.
 */
@FunctionalInterface
public interface LedgerSink {

  boolean append(LedgerRecordType type, Map<String, ?> fields);

  /** A record whose loss a reader reconstructs; written without its own forced flush where supported. */
  default boolean appendUnforced(LedgerRecordType type, Map<String, ?> fields) {
    return append(type, fields);
  }

  /** Forces every record appended so far to disk; true when they are durable. */
  default boolean flush() {
    return true;
  }

  static LedgerSink of(ExitLedgerWriter writer) {
    return new LedgerSink() {
      @Override public boolean append(LedgerRecordType type, Map<String, ?> fields) {
        return writer.append(type, fields);
      }

      @Override public boolean appendUnforced(LedgerRecordType type, Map<String, ?> fields) {
        return writer.appendUnforced(type, fields);
      }

      @Override public boolean flush() {
        return writer.flush();
      }
    };
  }
}
