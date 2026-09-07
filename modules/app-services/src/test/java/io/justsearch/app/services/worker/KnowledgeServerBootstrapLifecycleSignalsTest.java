package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.util.EnergyState;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 630: the Head-lifecycle status signals the bootstrap exposes for /api/status — the
 * energy-intent ("Paused — saving energy") and the post-resume "Catching up after sleep" window.
 * Exercised without starting a worker (the energy poller is constructed but never started; resume is
 * a plain timestamp). Lane F item A5 moved the poll off {@code WorkerSpawner} into
 * {@code EnergyStatePoller}, so the UNKNOWN answer below no longer depends on a spawned process.
 */
final class KnowledgeServerBootstrapLifecycleSignalsTest {

  /** Minimal config pointing at a temp dir (avoids KnowledgeServerConfig.load()'s lib-dir probe). */
  private static KnowledgeServerConfig configFor(Path dir) {
    return new KnowledgeServerConfig(
        false, dir, dir, dir, dir.resolve("worker_signal.lock"),
        5_000L, 15_000L, 3, "256m", 5_000L, 5_000L, 300_000L, 100, 0L, 0);
  }

  @Test
  @DisplayName("energyState() is UNKNOWN — not reduced, not null — before the first poll")
  void energyStateNullSafe(@TempDir Path tempDir) {
    var bootstrap = new KnowledgeServerBootstrap(configFor(tempDir));
    EnergyState e = bootstrap.energyState();
    assertEquals(EnergyState.Intent.UNKNOWN, e.intent());
    assertFalse(e.reduced());
  }

  @Test
  @DisplayName("recentlyResumed is false until a resume is marked, true inside the window, then clears")
  void resumeWindow(@TempDir Path tempDir) {
    var bootstrap = new KnowledgeServerBootstrap(configFor(tempDir));
    long t0 = 1_000_000_000L;
    assertFalse(bootstrap.recentlyResumed(t0), "no resume yet");

    bootstrap.markResumed(t0);
    assertTrue(bootstrap.recentlyResumed(t0 + 5_000), "5s after resume ⇒ catching up");
    assertTrue(bootstrap.recentlyResumed(t0 + 29_000), "just inside the 30s window");
    assertFalse(bootstrap.recentlyResumed(t0 + 31_000), "past the window ⇒ auto-cleared");
  }
}
