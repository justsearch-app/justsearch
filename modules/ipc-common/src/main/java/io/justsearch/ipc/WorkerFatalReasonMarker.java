/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ipc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Durable worker→Head "why the worker process exited fatally" marker (tempdoc 628 Stage D-part2).
 *
 * <p>The Worker calls {@code System.exit} when it cannot serve — e.g. an unrecoverable corrupt index
 * under the conservative {@code FAIL_CLOSED} recovery policy (G2's self-heal default keeps it alive, so
 * this is the opt-in path). The Head's supervisor otherwise saw only a bare exit code and could not
 * tell a corruption death — which a rebuild fixes — from a GPU/OOM/other crash, which it does
 * not. (That supervisor was {@code WorkerSpawner}, deleted at lane F stage A item A11 along with the
 * child process whose exit code it read.) The dying Worker stamps the reason here on its way out (a controlled throw → {@code System.exit},
 * not a {@code kill -9}, so the write is reliable); the Head reads + clears it when the worker goes down
 * and offers a "Rebuild index" affordance ONLY for the corruption reason — never falsely for an
 * unrelated crash (628's fail-loud-with-the-RIGHT-reason thesis).
 *
 * <p>Lives in {@code ipc-common} because it is exactly a Head↔Worker IPC contract; both sides know the
 * data directory.
 */
public final class WorkerFatalReasonMarker {

  /** Reason value: the index was corrupt and could not be auto-recovered. */
  public static final String INDEX_CORRUPT = "index_corrupt";

  /**
   * Reason value: the committed index shape is not the one this runtime writes, and {@code
   * index.schema_mismatch.policy} refuses to rebuild it. Distinct from {@link #INDEX_CORRUPT}
   * because the remedy differs - the index is intact, it is the wrong shape - and because without
   * it the refusal reaches the Head as a bare non-zero exit, indistinguishable from a crash.
   */
  public static final String INDEX_SCHEMA_MISMATCH = "index_schema_mismatch";

  private static final String FILE = "worker-fatal-reason";

  private WorkerFatalReasonMarker() {}

  /** The marker file path for a given data directory. */
  public static Path pathFor(Path dataDir) {
    return dataDir.resolve(FILE);
  }

  /** Worker side: record why this process is exiting fatally (best-effort; never throws). */
  public static void write(Path dataDir, String reason) {
    if (dataDir == null) {
      return;
    }
    try {
      Files.createDirectories(dataDir);
      Files.writeString(pathFor(dataDir), reason == null ? "" : reason, StandardCharsets.UTF_8);
    } catch (IOException e) {
      // best-effort: a missing marker just means the Head shows a generic worker-down condition.
    }
  }

  /**
   * Head side: read the fatal reason and clear it, so a later clean restart does not re-trigger the
   * affordance. Returns {@code null} if no marker / unreadable.
   */
  public static String readAndClear(Path dataDir) {
    if (dataDir == null) {
      return null;
    }
    Path p = pathFor(dataDir);
    try {
      if (!Files.exists(p)) {
        return null;
      }
      String reason = Files.readString(p, StandardCharsets.UTF_8).trim();
      Files.deleteIfExists(p);
      return reason.isEmpty() ? null : reason;
    } catch (IOException e) {
      return null;
    }
  }
}
