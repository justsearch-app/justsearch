/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

/** COMPLETE_WITH_GAPS still awaits a decision; it is never a terminal success. */
public enum OperationState {
  ACCEPTED, RUNNING, COMPLETE_WITH_GAPS, COMPLETE, FAILED, CANCELLED;

  public boolean terminal() { return this == COMPLETE || this == FAILED || this == CANCELLED; }
}
