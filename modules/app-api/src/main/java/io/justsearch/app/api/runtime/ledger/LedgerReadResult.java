/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import java.util.List;
import java.util.Objects;

/**
 * What {@link ExitLedgerReader} found: the readable records in file order, and how much it had to
 * skip. Skipped lines are reported, never fatal; a consumer that needs completeness (an exit census,
 * a fail-closed count) checks {@link #skippedLines()}.
 *
 * @param tornTail whether a file ended in a line without a newline (a write cut short)
 */
public record LedgerReadResult(
    List<LedgerRecord> records,
    int corruptLines,
    int unknownSchemaLines,
    int unknownTypeLines,
    boolean tornTail) {

  public LedgerReadResult {
    records = List.copyOf(Objects.requireNonNull(records, "records"));
    if (corruptLines < 0 || unknownSchemaLines < 0 || unknownTypeLines < 0) {
      throw new IllegalArgumentException("counts must be non-negative");
    }
  }

  /** Lines the reader could not use, for any reason. */
  public int skippedLines() {
    return corruptLines + unknownSchemaLines + unknownTypeLines;
  }

  public List<LedgerRecord> ofType(LedgerRecordType type) {
    return records.stream().filter(r -> r.type() == type).toList();
  }
}
