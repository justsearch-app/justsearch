/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/** Handler-facing identity and checkpoint reporting; acceptance and terminal writes stay in the runner. */
public interface OperationRecordHandle {
  long id();
  String key();
  /** Report only effects already committed by this operation's effect owner. */
  void checkpoint(String cursor, long completed, long failed);
}
