/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

/**
 * Exit ledger format v1 (ADR-0051): append-only JSON lines under
 * {@code <dataDir>/runtime/exit-ledger/}, one record per line, recorded by the owner of a process
 * before it acts on it. The line schema is {@code governance/exit-ledger.v1.schema.json}.
 *
 * <p>This class holds the format's version and size bounds; {@link ExitLedgerWriter} and {@link
 * ExitLedgerReader} enforce them.
 */
public final class ExitLedger {

  /** The record format version this code writes and the newest it reads. */
  public static final int SCHEMA_VERSION = 1;

  /** The largest record a writer appends, newline included (one atomic append on Windows). */
  public static final int MAX_RECORD_BYTES = 4 * 1024;

  /** Readers skip any line longer than this as corrupt. */
  public static final int MAX_READ_LINE_BYTES = 8 * 1024;

  /** A rotating writer rotates at session start when the file is larger than this. */
  public static final long SESSION_ROTATE_BYTES = 1024L * 1024L;

  /** A rotating writer rotates before an append that would take the file past this. */
  public static final long HARD_CAP_BYTES = 2L * 1024L * 1024L;

  private ExitLedger() {}
}
