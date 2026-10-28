/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.configuration.model.DownloadProfile;
import io.justsearch.configuration.model.InstallPlan;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 824 §3.3b/§3.3d — the terminal claim an install run publishes is CHECKED against disk,
 * and it counts required files only.
 *
 * <p>Round 16's run ended with five of seven packages failed. Required failures now fail the run; optional-only gaps remain non-blocking. {@code installedFully} was never
 * recomputed within the session that ran the install ({@code maybeRecomputeInstalledFromDisk} is
 * gated on {@code "idle".equals(status.state)}), and {@code repairNeeded} was {@code failedCount >
 * 0} regardless of WHICH file failed — so one 872-byte optional metadata sidecar produced "a
 * required component is missing" while SPLADE served 1 660 inferences on CUDA.
 *
 * <p>The disk verdict is injected here for the same reason {@code applyInstalledFromPlan} takes an
 * injected contract: staging the whole registry's file set to make a real probe answer would be
 * brittle, even though app-services can now load the real {@code ai/model-registry.v2.json} from
 * its own test classpath (tempdoc 840 — the registry ships in {@code modules:configuration}).
 */
final class AiInstallServiceCompletionTruthTest {

  private static final DownloadProfile PROFILE = DownloadProfile.values()[0];

  @TempDir Path tmp;

  private static AiInstallStatus statusOf(AiInstallService svc) throws Exception {
    Field f = AiInstallService.class.getDeclaredField("status");
    f.setAccessible(true);
    return (AiInstallStatus) f.get(svc);
  }

  private static AiInstallStatus.PackageStatus addPackage(
      AiInstallStatus status, String id, String state) {
    var ps = new AiInstallStatus.PackageStatus();
    ps.packageId = id;
    ps.state = state;
    status.packages.add(ps);
    return ps;
  }

  private static InstallCompleteness completeness(List<InstallPlan.PlannedDownload> missing) {
    return InstallCompleteness.compute(
        new InstallPlan(PROFILE, missing, List.of(), 0L, List.of("embedding")), null);
  }

  private static InstallPlan.PlannedDownload missing(String packageId, String path, boolean required) {
    return new InstallPlan.PlannedDownload(
        packageId, "https://example/" + path, path, "sha", 100L, false, false, required);
  }

  /**
   * Test 6 — per-asset isolation (INS-005 stays fixed). One package's asset failing must leave the
   * others installed and the RUN failed, with an honest count. Pins the property the round-16
   * log already demonstrates in production, so a future refactor of the download loop cannot
   * quietly reintroduce the abort-remaining behaviour the docs claimed was still present.
   */
  @Test
  @DisplayName("one failed package of three: A and C installed, B failed, run failed 2/3")
  void perAssetIsolation_oneFailedPackageOfThree() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    addPackage(status, "embedding", "installed");
    addPackage(status, "splade", "failed");
    addPackage(status, "reranker", "installed");

    svc.applyCompletionState(completeness(List.of(missing("splade", "splade/model_fp16.onnx", true))));

    assertEquals("failed", status.state, "a required file failed, even though other packages installed");
    assertTrue(status.message.contains("2/3"), "honest count: " + status.message);
    assertTrue(status.message.contains("1 failed"), status.message);
    assertFalse(status.installedFully, "a missing REQUIRED file is still an incomplete install");
    assertTrue(status.repairNeeded);
  }

  /**
   * Test 7 — the round-16 wedge. The only casualty is an OPTIONAL file, so the run's own package
   * bookkeeping ("splade failed") must not become "a required component is missing" — and the
   * MESSAGE printed beside {@code installedFully: true} must not contradict it by counting that
   * package as failed. Flag and message have to come from the same authority or one of them lies.
   */
  @Test
  @DisplayName("round-16 wedge: an optional-only casualty reads installed, not repairable")
  void optionalOnlyCasualty_readsHonestly() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    addPackage(status, "embedding", "installed");
    addPackage(status, "splade", "failed");

    svc.applyCompletionState(
        completeness(List.of(missing("splade", "splade/naver-splade-v3/config.json", false))));

    assertEquals("completed", status.state);
    assertFalse(
        status.repairNeeded,
        "nothing any consumer requires is missing — offering Repair here is the round-16 defect");
    assertTrue(status.installedFully, "the install IS complete on every file that matters");
    assertFalse(
        status.message.contains("failed"),
        "no package may be counted as failed beside installedFully: true — " + status.message);
    assertTrue(status.message.contains("2/2 packages"), status.message);
    assertTrue(
        status.message.contains("optional files missing"),
        "the gap is phrased, not hidden: " + status.message);
    assertEquals(1, status.optionalGaps.size(), "…and the gap is still named");
    assertEquals("splade", status.optionalGaps.get(0).packageId);
    assertEquals("config.json", status.optionalGaps.get(0).fileName);
  }

  /**
   * §3.3d in the other direction: a run whose packages all say "installed" but whose files are NOT
   * on disk must not publish a green terminal claim. This is the {@code unreachable-seed-green}
   * shape — bookkeeping asserting a state nothing verified — and before the recompute the
   * completing session had no path to catch it at all.
   */
  @Test
  @DisplayName("a clean-looking run whose required file is absent from disk cannot read green")
  void cleanRunContradictedByDisk_readsRed() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    addPackage(status, "embedding", "installed");
    addPackage(status, "splade", "installed");

    svc.applyCompletionState(completeness(List.of(missing("splade", "splade/vocab.txt", true))));

    assertEquals("failed", status.state);
    assertFalse(status.installedFully, "disk contradicts the bookkeeping — disk wins");
    assertTrue(status.repairNeeded);
  }

  /** A hardware skip is not a repairable gap, and disk cannot upgrade it to "installed cleanly". */
  @Test
  @DisplayName("a hardware-skipped package still reads 'installed with limitations'")
  void hardwareSkip_isNotUpgradedByDisk() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    addPackage(status, "embedding", "installed");
    addPackage(status, "chat", "skipped").label = "Chat model";

    svc.applyCompletionState(completeness(List.of()));

    assertEquals("completed", status.state);
    assertFalse(status.installedFully, "tempdoc 374 finding #8: skipped is 'with limitations'");
    assertFalse(status.repairNeeded, "…but a skip is not a repairable gap");
    assertTrue(status.message.contains("limitations"), status.message);
  }

  /**
   * §3.4's terminal verdict reaches the WIRE. {@code InstallAttemptMemoryTest} proves the memory
   * decides "three passes is terminal"; this proves the decision is projected onto the package the
   * UI reads, with the URL and destination the manual fallback needs. A field nothing populates is
   * invisible to the FE ({@code wire-emitter-elision}), and the FE test consumes exactly these four.
   */
  @Test
  @DisplayName("the terminal verdict is projected onto the package, with the manual fallback")
  void terminalVerdictReachesThePackageStatus() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    var splade = addPackage(status, "splade", "downloading");

    java.lang.reflect.Method mark =
        AiInstallService.class.getDeclaredMethod(
            "markPackageTerminal", String.class, String.class, int.class, String.class, String.class);
    mark.setAccessible(true);
    mark.invoke(
        svc,
        "splade",
        "TRANSPORT_UNAVAILABLE",
        12,
        "https://example/splade-config.json",
        "C:\\models\\splade\\config.json");

    assertEquals("TRANSPORT_UNAVAILABLE", splade.terminalReason);
    assertEquals(12, splade.attempts);
    assertEquals("https://example/splade-config.json", splade.url);
    assertEquals("C:\\models\\splade\\config.json", splade.targetPath);
  }

  /**
   * The probe is best-effort: when it cannot answer, the package bookkeeping remains the only
   * authority and every pre-824 verdict stands.
   *
   * <p>Tempdoc 840: this comment used to add "this is the branch every app-services test that calls
   * the no-arg {@code applyCompletionState()} actually takes (no registry on the classpath), so it is
   * also what keeps those tests meaningful rather than accidentally green." Both halves are now false,
   * and the second was the more dangerous one — the classpath absence it relied on WAS the accident.
   * The registry now ships from {@code modules:configuration} and is reachable here, and the sibling
   * tests in {@code AiInstallServicePackageStateTest} inject {@code null} explicitly instead of
   * inheriting it from a resource that happened to be missing. Every arm of the decision is now
   * driven deliberately: the no-arg composition by {@code AiInstallCompletionDiskRecomputeTest}
   * (in {@code modules:ui}), the indeterminate arm here, and the real-disk-truth arm above.
   */
  @Test
  @DisplayName("an indeterminate disk probe degrades to the pre-824 package-derived verdict")
  void indeterminateProbe_keepsPackageDerivedVerdict() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    addPackage(status, "embedding", "installed");
    addPackage(status, "splade", "failed");

    svc.applyCompletionState(null);

    assertFalse(status.installedFully);
    assertTrue(status.repairNeeded, "without a disk answer, a failed package is still a gap");
    assertTrue(status.optionalGaps.isEmpty());
  }
}
