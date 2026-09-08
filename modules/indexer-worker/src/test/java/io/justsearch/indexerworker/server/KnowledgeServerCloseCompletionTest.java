/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stage-A checkpoint (re-review) — {@code awaitClosed} answers a question that has two answers.
 *
 * <p><b>The bug this test exists because of.</b> The re-review found the "production consumer" added
 * for {@code isRunning()} was vacuous: {@code EngineRoot.close()} warned if {@code s.isRunning()}
 * was still true after {@code s.close()} returned, but {@code close()} sets {@code running = false}
 * in its first line and {@code isRunning()} is {@code running && latch > 0}, so the predicate was
 * constant-false at that point and the warning could never fire. A check that cannot fail was added
 * in the course of removing checks that could not fail.
 *
 * <p>What this test pins is the two-state property: the predicate is FALSE on a server that has not
 * completed a close and TRUE on one that has. That is precisely what the old read lacked, and
 * without both directions asserted a future refactor could quietly restore a constant-valued
 * predicate — which is what happened once already.
 *
 * <p><b>What it does NOT pin, stated because the falsification proved it.</b> Moving the latch back
 * to its old mid-{@code close()} position does not red this test, and the mutation was run to check:
 * both positions satisfy "false before, true after a NORMAL close". The latch's position only
 * matters for a close that throws PARTWAY — between the old countdown point and the end — and
 * asserting that needs a fault injected into one of the intervening steps (a Lucene runtime whose
 * close throws), which this unit has no seam for. So the position is argued in the production
 * comment rather than pinned here, and this file does not claim otherwise.
 */
@DisplayName("KnowledgeServer.awaitClosed — close completion is observable")
final class KnowledgeServerCloseCompletionTest {

  @BeforeAll
  static void ensureGlobalConfig() {
    if (ConfigStore.globalOrNull() == null) {
      ConfigStore.setGlobal(new ConfigStore(ResolvedConfig.builder().contributeEnvRegistry().build()));
    }
  }

  @Test
  @DisplayName("false before any close, true after one that completes")
  void awaitClosedDistinguishesTheTwoStates(@TempDir Path tempDir) throws Exception {
    KnowledgeServer server =
        new KnowledgeServer(WorkerBootFixture.workerConfig(tempDir.resolve("data")), null);

    assertFalse(
        server.awaitClosed(0),
        "a server that has never been closed must NOT report a completed close. If this is true"
            + " here, the latch is being counted down somewhere other than the end of close() and"
            + " the predicate has gone constant again.");

    server.close();

    assertTrue(
        server.awaitClosed(0),
        "after close() returns, the latch must be down — close() counts it down as its last"
            + " statement, so a false here means close() did not reach the end. This is the"
            + " direction EngineRoot.close() warns on.");
  }
}
