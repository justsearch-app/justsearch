/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.nio.file.Path;

/** Child used only by Q12: abrupt exit leaves SQLite to recover the uncommitted migration. */
public final class QueueScaleMigrationCrash {
  private QueueScaleMigrationCrash() {}

  @SuppressWarnings("PMD.DoNotTerminateVM") // Q12 deliberately crashes this isolated child.
  public static void main(String[] args) throws Exception {
    try (var queue = new SqliteJobQueue(Path.of(args[0]), 3, null, version -> {
      if (version == Integer.parseInt(args[1])) Runtime.getRuntime().halt(74);
    })) {
      queue.open();
      throw new IllegalStateException("Migration did not reach the requested crash step");
    }
  }
}
