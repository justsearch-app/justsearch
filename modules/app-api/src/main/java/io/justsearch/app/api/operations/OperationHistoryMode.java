/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

/** Acceptance-time history projection policy, independent of public retry identity and survival. */
public enum OperationHistoryMode {
  /** No history projection; also the safe migration/default for producers with their own ledger. */
  NONE,
  /** A successful forward invocation projects SUCCESS, with no undo execution identifier. */
  STANDARD,
  /** A successful forward invocation may expose its receipt's undo execution identifier. */
  UNDOABLE,
  /** A successful undo invocation projects UNDONE and never exposes another undo identifier. */
  UNDO
}
