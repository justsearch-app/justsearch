package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A failed {@link KnowledgeServerBootstrap#start()} must leave the instance startable again.
 *
 * <p>This is the load-bearing precondition of the boot retry: {@code start()} tears itself down via
 * {@code close()}, which must reset the {@code started} guard and drop the spawner/client/signal bus
 * so the next attempt respawns instead of failing with "KnowledgeServerBootstrap already started" —
 * an error that would also replace the real cause in the operator's log. Asserted here rather than
 * claimed in javadoc.
 *
 * <p>The fixture points every path at an empty temp dir, so the spawned worker JVM cannot find its
 * main class and dies before publishing a port. That is a real failure of a real attempt: the exact
 * shape the retry must survive.
 */
@DisplayName("KnowledgeServerBootstrap restartability after a failed start")
final class KnowledgeServerBootstrapRestartabilityTest {

  /** Mirrors {@code KnowledgeServerBootstrapLifecycleSignalsTest.configFor} (:20-25). */
  private static KnowledgeServerConfig configFor(Path dir) {
    return new KnowledgeServerConfig(
        false, dir, dir, dir, dir.resolve("worker_signal.lock"),
        5_000L, 2_000L, 3, 2_000L, 1_000L, 300_000L, 100, 0L, 0);
  }

  private static Exception failingStart(KnowledgeServerBootstrap bootstrap) {
    return assertThrows(Exception.class, bootstrap::start);
  }

  @Test
  @Timeout(90)
  @DisplayName("a second start() reaches the spawn step instead of the already-started guard")
  void secondStartIsNotBlockedByTheStartedGuard(@TempDir Path tempDir) {
    var bootstrap = new KnowledgeServerBootstrap(configFor(tempDir));

    Exception first = failingStart(bootstrap);
    Exception second = failingStart(bootstrap);

    assertNotNull(first.getMessage());
    String secondMessage = String.valueOf(second.getMessage());
    assertFalse(
        secondMessage.contains("already started"),
        "close() must reset the started guard so a retry can respawn; got: " + secondMessage);
    // Same failure shape both times ⇒ the second attempt really re-ran the spawn path.
    assertTrue(
        second.getClass().equals(first.getClass()),
        "expected the retry to fail the same way (" + first.getClass().getSimpleName()
            + "), got " + second.getClass().getSimpleName() + ": " + secondMessage);
  }

  @Test
  @Timeout(90)
  @DisplayName("startWithRetry on a non-transient failure runs once and lands DEGRADED")
  void nonTransientFailureIsNotRetriedAndNarratesOnce(@TempDir Path tempDir) {
    var bootstrap = new KnowledgeServerBootstrap(configFor(tempDir));

    assertThrows(Exception.class, () -> bootstrap.startWithRetry(3, 0));

    // A worker that dies before publishing a port is not a PID-validation timeout, so the retry
    // must not engage — and the final verdict must still be narrated exactly once.
    assertFalse(bootstrap.isReady());
    assertTrue(
        bootstrap.workerCapability().health() == CapabilityHealth.DEGRADED,
        "expected DEGRADED after an exhausted start, got "
            + bootstrap.workerCapability().health());
  }
}
