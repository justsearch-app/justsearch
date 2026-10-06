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

  static LedgerSink of(ExitLedgerWriter writer) {
    return writer::append;
  }
}
