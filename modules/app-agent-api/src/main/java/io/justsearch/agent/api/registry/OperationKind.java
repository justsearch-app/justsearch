/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** One closed vocabulary for catalog classification and durable operation records. */
public enum OperationKind {
  OPERATION("operation"), RECONFIGURE("reconfigure"), REINDEX("reindex"), INGEST("ingest"),
  SETTINGS_APPLY("settings-apply"), ACCEPT_GAPS("accept-gaps"), SCHEDULED_RUN("scheduled-run"),
  MEMORY("memory"), NOTE("note");

  private final String wireValue;
  OperationKind(String wireValue) { this.wireValue = wireValue; }
  @JsonValue
  public String wireValue() { return wireValue; }
  @JsonCreator
  public static OperationKind fromWire(String value) {
    for (OperationKind kind : values()) if (kind.wireValue.equals(value)) return kind;
    throw new IllegalArgumentException("Unknown operation kind");
  }
}
