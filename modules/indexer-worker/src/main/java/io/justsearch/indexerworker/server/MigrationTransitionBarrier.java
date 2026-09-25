/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.core.harness.HarnessBarrierProtocol;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

/** Harness-only observation points in the live migration transition. */
public final class MigrationTransitionBarrier {
  private MigrationTransitionBarrier() {}

  public record Transition(String point, String sourceGeneration, String buildingGeneration) {}

  @FunctionalInterface
  public interface Hook {
    void await(Transition transition) throws IOException, InterruptedException;
  }

  public static final Hook NO_HOOK = transition -> {};

  private static final Set<String> POINTS = Set.of(
      "migration-green-drained", "migration-before-switching", "migration-switching-entered",
      "migration-before-pointer-commit", "migration-after-pointer-commit",
      "migration-before-live-activation", "migration-after-live-activation",
      "migration-after-first-projection-replay");
  private static final String REPLAY_HALT_POINT = "migration-after-first-projection-replay";

  public static Hook fromEnvironment(Path dataDir, Function<String, String> env) {
    String point = env.apply("JUSTSEARCH_MIGRATION_BARRIER_POINT");
    String selfExitText = env.apply("JUSTSEARCH_MIGRATION_BARRIER_SELF_EXIT");
    if (point == null && selfExitText == null) return NO_HOOK;
    if (!"1".equals(env.apply("JUSTSEARCH_SUPERVISOR_HARNESS"))) {
      throw new IllegalArgumentException("Migration barrier selection requires supervisor harness mode");
    }
    if (!POINTS.contains(point) || (selfExitText != null && !Set.of("0", "1").contains(selfExitText))) {
      throw new IllegalArgumentException("Invalid migration barrier selection");
    }
    boolean selfExit = "1".equals(selfExitText);
    if (REPLAY_HALT_POINT.equals(point) && !selfExit) {
      throw new IllegalArgumentException("Projection replay barrier requires a harness self-exit");
    }
    Path reached = HarnessBarrierProtocol.reached(dataDir, "migration-barrier");
    AtomicBoolean claimed = new AtomicBoolean();
    return transition -> {
      if (!point.equals(transition.point()) || Files.exists(reached)
          || !claimed.compareAndSet(false, true)) return;
      var marker = Map.of(
          "point", transition.point(),
          "sourceGeneration", transition.sourceGeneration() == null ? "" : transition.sourceGeneration(),
          "buildingGeneration", transition.buildingGeneration() == null ? "" : transition.buildingGeneration(),
          "pid", ProcessHandle.current().pid());
      HarnessBarrierProtocol.await(dataDir, "migration-barrier",
          JsonMapper.builder().build().writeValueAsString(marker), selfExit);
    };
  }

  /** A checked in-process barrier; cancellation unwinds the monitor before a pointer move. */
  public static final class Controlled implements Hook {
    private final String point;
    private final CountDownLatch reached = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private final AtomicBoolean claimed = new AtomicBoolean();
    private volatile boolean cancelled;

    public Controlled(String point) {
      if (!POINTS.contains(point)) throw new IllegalArgumentException("Unknown migration point");
      if (REPLAY_HALT_POINT.equals(point)) {
        throw new IllegalArgumentException("Projection replay cut requires a process halt");
      }
      this.point = point;
    }

    @Override public void await(Transition transition) throws InterruptedException {
      if (!point.equals(transition.point()) || !claimed.compareAndSet(false, true)) return;
      reached.countDown();
      if (!released.await(180, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Migration barrier was not released: " + point);
      }
      if (cancelled) throw new InterruptedException("Migration barrier cancelled before pointer mutation");
    }

    public boolean awaitReached(long timeout, TimeUnit unit) throws InterruptedException {
      return reached.await(timeout, unit);
    }

    public void release() { released.countDown(); }

    public void cancel() {
      cancelled = true;
      released.countDown();
    }
  }
}
