package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.api.AiInstallStatus;
import java.lang.reflect.Field;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 575 §17 Face C — the liveness backstop's <em>end-to-end</em> behavior: {@code getStatus()}
 * must TERMINALIZE a wedged "running" install whose owner stopped beating, so the UI never polls a
 * dead "running" forever (install/pack previously had no backstop, unlike the worker's
 * {@code recoverStuckJobs} reaper).
 *
 * <p>{@link PolledStateLivenessTest} pins the shared <em>law</em> ({@code isStaleRunning}); this pins
 * the <em>reaper</em> that consumes it — i.e. that a stale running status, when read, actually flips
 * to a terminal {@code failed}/STALLED state. (The live-browser film of this needs the worktree's own
 * backend, which the shared dev-runner does not serve under the no-merge constraint; this regression
 * test is the verification tier for the backend behavior — the {@code audit-driven-fixes-need-test}
 * discipline.) Uses reflection to stage the private status — no production test-seam, no class growth.
 */
final class AiInstallServiceReaperTest {

  @TempDir Path tmp;

  private static AiInstallStatus statusOf(AiInstallService svc) throws Exception {
    Field f = AiInstallService.class.getDeclaredField("status");
    f.setAccessible(true);
    return (AiInstallStatus) f.get(svc);
  }

  @Test
  void getStatus_terminalizesAWedgedRunningInstall() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    status.state = "running";
    // Stale far past the 5-min STALE_RUNNING_MS window — a dead owner.
    status.updatedAtEpochMs = System.currentTimeMillis() - (10 * 60_000L);

    AiInstallStatus after = svc.getStatus(); // runs the lazy reaper

    assertEquals(
        "failed",
        after.state,
        "the reaper must terminalize a wedged running install on read (575 §17 Face C) — the gap"
            + " this fixes: install had no backstop, so a dead 'running' owner was polled forever");
    assertEquals("STALLED", after.errorCode, "the terminal state carries the STALLED reason code");
  }

  @Test
  void getStatusNeverRevokesALiveOwnersGuardBecauseProgressIsStale() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    status.state = "running";
    status.updatedAtEpochMs = System.currentTimeMillis() - (10 * 60_000L);
    Field field = AiInstallService.class.getDeclaredField("running");
    field.setAccessible(true);
    var running = (java.util.concurrent.atomic.AtomicBoolean) field.get(svc);
    running.set(true);

    assertEquals("running", svc.getStatus().state);
    org.junit.jupiter.api.Assertions.assertTrue(running.get());
    var refusal = org.junit.jupiter.api.Assertions.assertThrows(
        io.justsearch.app.api.AiInstallException.class, () -> svc.startInstall(true));
    assertEquals(io.justsearch.app.api.ApiErrorCode.INSTALL_ALREADY_RUNNING, refusal.errorCode());
  }

  @Test
  void getStatus_leavesAFreshRunningInstallAlone() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    status.state = "running";
    status.updatedAtEpochMs = System.currentTimeMillis(); // fresh — a live owner

    assertEquals(
        "running",
        svc.getStatus().state,
        "a fresh running install (live owner) must NOT be reaped — only a stale one is");
  }
}
