package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.AiInstallStatus;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AiInstallServicePackageStateTest {

  @TempDir Path tmp;

  private static AiInstallStatus statusOf(AiInstallService svc) throws Exception {
    Field f = AiInstallService.class.getDeclaredField("status");
    f.setAccessible(true);
    return (AiInstallStatus) f.get(svc);
  }

  private static void invoke(AiInstallService svc, String name, Class<?>[] types, Object... args)
      throws Exception {
    Method m = AiInstallService.class.getDeclaredMethod(name, types);
    m.setAccessible(true);
    m.invoke(svc, args);
  }

  /**
   * Drives the bookkeeping-only completion decision this class pins (INS-005 and friends), via the
   * explicit-injection overload with {@code diskTruth == null} — the same seam {@code
   * AiInstallServiceCompletionTruthTest} drives deliberately. Tempdoc 840: before the registry
   * relocation, {@code applyCompletionState()} (no-arg) reached this branch only because {@code
   * ai/model-registry.v2.json} was absent from app-services' test classpath, so {@code
   * recomputeCompletenessFromDiskBestEffort()} always failed closed to {@code null}. Now that the
   * registry ships from {@code modules:configuration} and IS reachable here, the no-arg entry point
   * would instead exercise the real disk-recompute path against these tests' synthetic package ids
   * and an empty {@code @TempDir} — a different decision than the one this class is testing.
   * Injecting {@code null} directly keeps these tests pinned to the bookkeeping-only property
   * regardless of what else is on the classpath.
   */
  private static void applyCompletionStateBookkeepingOnly(AiInstallService svc) throws Exception {
    invoke(svc, "applyCompletionState", new Class<?>[] {InstallCompleteness.class}, (Object) null);
  }

  @Test
  void failedPackageStaysFailedAcrossLaterMultiFileTransitions() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    var pkg = new AiInstallStatus.PackageStatus();
    pkg.packageId = "splade";
    pkg.state = "pending";
    pkg.bytesDownloaded = 10;
    pkg.bytesTotal = 100;
    status.packages.add(pkg);

    invoke(
        svc,
        "failPackage",
        new Class<?>[] {String.class, String.class},
        "splade",
        "Download failed for splade/naver-splade-v3/idf.json");
    invoke(svc, "updatePackageState", new Class<?>[] {String.class, String.class}, "splade", "downloading");
    invoke(
        svc,
        "updatePackageProgress",
        new Class<?>[] {String.class, long.class, long.class},
        "splade",
        90L,
        100L);
    invoke(svc, "updatePackageState", new Class<?>[] {String.class, String.class}, "splade", "installed");

    assertEquals("failed", pkg.state);
    assertEquals("Download failed for splade/naver-splade-v3/idf.json", pkg.error);
    assertEquals(10, pkg.bytesDownloaded);
  }

  /**
   * INS-005 regression: a multi-package run where one asset fails but the rest succeed must report
   * {@code state == "failed"} with an honest count and {@code installedFully == false};
   * successful packages are retained and no package is left {@code pending}. Exercises the extracted
   * {@code applyCompletionState()} terminal decision.
   */
  @Test
  void partialFailure_reportsFailed_andHonestCount() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    for (int i = 0; i < 23; i++) {
      addPackage(status, "pkg-" + i, "installed");
    }
    addPackage(status, "reranker-fp16", "failed");

    applyCompletionStateBookkeepingOnly(svc);

    assertEquals("failed", status.state);
    assertFalse(status.installedFully);
    assertTrue(status.message.contains("23/24"), "message should report 23/24: " + status.message);
    assertTrue(status.message.contains("1 failed"), "message should name the failure count");
  }

  /**
   * A package left in a non-terminal state (pending/downloading/verifying) must NOT read as a clean
   * install — {@code installedFully} is computed from the positive "installed" count, so a run that
   * failed to terminalize every package cannot lie. Guards the leftover-pending honesty gap.
   */
  @Test
  void nonTerminalPackage_isNotCountedAsInstalled() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    addPackage(status, "embedding", "installed");
    addPackage(status, "reranker", "pending");

    applyCompletionStateBookkeepingOnly(svc);

    assertEquals("completed", status.state);
    assertFalse(
        status.installedFully, "a leftover pending package must not report a fully-installed run");
  }

  /** All packages installed → clean completion, installedFully true. */
  @Test
  void allInstalled_reportsFullyInstalled() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    addPackage(status, "embedding", "installed");
    addPackage(status, "reranker", "installed");

    applyCompletionStateBookkeepingOnly(svc);

    assertEquals("completed", status.state);
    assertTrue(status.installedFully);
    assertEquals("AI installed.", status.message);
  }

  /** A hardware-skipped package (not failed) → completed with limitations, installedFully false. */
  @Test
  void skippedPackage_distinguishedFromFailed() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    addPackage(status, "embedding", "installed");
    var chat = addPackage(status, "chat", "skipped");
    chat.label = "Chat model";

    applyCompletionStateBookkeepingOnly(svc);

    assertEquals("completed", status.state);
    assertFalse(status.installedFully);
    assertTrue(
        status.message.contains("limitations"), "skipped path should read as limitations, not failure");
  }

  private static AiInstallStatus.PackageStatus addPackage(
      AiInstallStatus status, String id, String state) {
    var ps = new AiInstallStatus.PackageStatus();
    ps.packageId = id;
    ps.state = state;
    status.packages.add(ps);
    return ps;
  }
}
