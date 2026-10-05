/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import java.nio.file.Path;

/**
 * The two exit-ledger files under {@code <dataDir>/runtime/exit-ledger/} (ADR-0051). Each family has
 * one rotating writer, so rotation never races: the Engine rotates {@code children}, the supervisor
 * rotates {@code engine}. The Engine only appends {@code engine-self-intent} to {@code engine}.
 */
public enum LedgerFamily {
  /** Children the Engine holds; written and rotated by the Engine. */
  CHILDREN("children.v1.jsonl", "children.v1.1.jsonl"),
  /** Engine incarnations; rotated by the supervisor, appended to by the Engine for self-intent. */
  ENGINE("engine.v1.jsonl", "engine.v1.1.jsonl");

  private final String currentFileName;
  private final String rotatedFileName;

  LedgerFamily(String currentFileName, String rotatedFileName) {
    this.currentFileName = currentFileName;
    this.rotatedFileName = rotatedFileName;
  }

  /** The ledger directory under a data directory. */
  public static Path directory(Path dataDir) {
    return dataDir.resolve("runtime").resolve("exit-ledger");
  }

  public String currentFileName() {
    return currentFileName;
  }

  /** The single older generation; rotation deletes it and renames the current file to it. */
  public String rotatedFileName() {
    return rotatedFileName;
  }

  public Path current(Path ledgerDir) {
    return ledgerDir.resolve(currentFileName);
  }

  public Path rotated(Path ledgerDir) {
    return ledgerDir.resolve(rotatedFileName);
  }
}
