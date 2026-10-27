/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.io.IOException;
import java.nio.file.Path;

/** Recovery checkpoints for tests without a native v13 connection. */
final class Release030RecoveryStateFixture {
  private Release030RecoveryStateFixture() {}

  static void capture(Path database) throws IOException {
    try { Release030RecoveryState.capture(database, null); }
    catch (java.sql.SQLException impossible) { throw new IOException(impossible); }
  }
}
