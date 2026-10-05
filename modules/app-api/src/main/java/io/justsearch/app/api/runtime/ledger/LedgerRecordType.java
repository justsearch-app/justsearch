/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

/** The record types of exit-ledger format v1 and the family each belongs to. */
public enum LedgerRecordType {
  CHILD_SPAWN("child-spawn", LedgerFamily.CHILDREN),
  CHILD_START("child-start", LedgerFamily.CHILDREN),
  STOP_INTENT("stop-intent", LedgerFamily.CHILDREN),
  STOP_ESCALATE("stop-escalate", LedgerFamily.CHILDREN),
  CHILD_EXIT("child-exit", LedgerFamily.CHILDREN),
  CHILD_SURVIVED("child-survived", LedgerFamily.CHILDREN),
  GPU_RELEASE("gpu-release", LedgerFamily.CHILDREN),
  CHILD_NOTE("child-note", LedgerFamily.CHILDREN),
  OWNER_DIED("owner-died", LedgerFamily.CHILDREN),
  ENGINE_START("engine-start", LedgerFamily.ENGINE),
  ENGINE_INTENT("engine-intent", LedgerFamily.ENGINE),
  ENGINE_SELF_INTENT("engine-self-intent", LedgerFamily.ENGINE),
  ENGINE_EXIT("engine-exit", LedgerFamily.ENGINE),
  ENGINE_OWNER_DIED("engine-owner-died", LedgerFamily.ENGINE),
  /** Written by any writer, on the next successful write after records were lost. */
  LEDGER_GAP("ledger-gap", null);

  private final String token;
  /** The one family this type belongs to, or null for a type of every family. */
  private final LedgerFamily family;

  LedgerRecordType(String token, LedgerFamily family) {
    this.token = token;
    this.family = family;
  }

  @JsonValue
  public String token() {
    return token;
  }

  public boolean belongsTo(LedgerFamily candidate) {
    return family == null || family == candidate;
  }

  public static Optional<LedgerRecordType> fromToken(String token) {
    return Arrays.stream(values()).filter(t -> t.token.equals(token)).findFirst();
  }
}
