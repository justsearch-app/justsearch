/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

/** Closed durable-record vocabulary; catalog policy projects this same vocabulary in C2-3. */
public enum OperationKind {
  OPERATION("operation"), RECONFIGURE("reconfigure"), REINDEX("reindex"), INGEST("ingest"),
  SETTINGS_APPLY("settings-apply"), ACCEPT_GAPS("accept-gaps"), SCHEDULED_RUN("scheduled-run");

  private final String wireValue;
  OperationKind(String wireValue) { this.wireValue = wireValue; }
  public String wireValue() { return wireValue; }
  public static OperationKind fromWire(String value) {
    for (OperationKind kind : values()) if (kind.wireValue.equals(value)) return kind;
    throw new IllegalArgumentException("Unknown operation kind");
  }
}
