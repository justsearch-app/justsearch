/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

/** Why records are missing from a ledger ({@code ledger-gap.reason}). */
public enum LedgerGapReason {
  /** A record could not be written. */
  WRITE_FAILED,
  /** The file could not be rotated, so it grew past its cap. */
  ROTATE_FAILED,
  /** A reader skipped lines it could not parse. */
  CORRUPT_LINES,
  /** A reader skipped lines of a newer schema version. */
  UNKNOWN_SCHEMA
}
