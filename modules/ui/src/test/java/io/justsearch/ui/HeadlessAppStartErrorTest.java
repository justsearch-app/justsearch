/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeServerConfig;
import io.justsearch.ipc.WorkerFatalReasonMarker;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 915 R1 — {@code knowledgeServerStartError} is rendered verbatim to the user (the 503 body
 * and the failed-worker capability detail), so it must name the REFUSAL, not the spawn symptom the
 * Head happened to observe.
 *
 * <p>Live arm 2 published "Worker process crashed (exit code 1) before writing port to signal file"
 * for an index the worker had deliberately left byte-identical under
 * {@code index.schema_mismatch.policy=FAIL_CLOSED}. Every word of that sentence was true about the
 * process and useless about the cause.
 */
@DisplayName("915 R1: the start error names the refusal, not the crash")
final class HeadlessAppStartErrorTest {

  private static KnowledgeServerConfig configFor(Path dir) {
    return new KnowledgeServerConfig(
        false, dir, dir, dir, dir.resolve("worker_signal.lock"),
        5_000L, 1_000L, 3, "256m", 1_000L, 1_000L, 300_000L, 100, 0L, 0);
  }

  @Test
  @Timeout(180)
  @DisplayName("a latched schema mismatch replaces the crash message with its remedy")
  void refusalDetailReplacesTheSpawnSymptom(@TempDir Path tempDir) {
    WorkerFatalReasonMarker.write(tempDir, WorkerFatalReasonMarker.INDEX_SCHEMA_MISMATCH);
    var bootstrap = new KnowledgeServerBootstrap(configFor(tempDir));
    Exception boom =
        assertThrows(Exception.class, () -> bootstrap.startWithRetry(3, 0));

    String startError = HeadlessApp.startErrorFor(bootstrap, boom);

    assertTrue(
        startError.contains("index.schema_mismatch.policy"),
        "the user must be told which setting produced the refusal; got: " + startError);
    assertTrue(
        startError.contains("BLUE_GREEN_MIGRATE"),
        "and the one action that resolves it; got: " + startError);
  }

  @Test
  @Timeout(180)
  @DisplayName("an ordinary failure is untouched — the exception message is still the truth there")
  void plainFailureKeepsTheExceptionMessage(@TempDir Path tempDir) {
    var bootstrap = new KnowledgeServerBootstrap(configFor(tempDir));
    Exception boom = assertThrows(Exception.class, () -> bootstrap.startWithRetry(3, 0));

    assertEquals(boom.getMessage(), HeadlessApp.startErrorFor(bootstrap, boom));
  }

  @Test
  @DisplayName("no bootstrap at all still summarizes, rather than NPEing on the null")
  void nullBootstrapFallsBackToTheSummary() {
    assertEquals(
        "IllegalStateException", HeadlessApp.startErrorFor(null, new IllegalStateException()));
  }
}
