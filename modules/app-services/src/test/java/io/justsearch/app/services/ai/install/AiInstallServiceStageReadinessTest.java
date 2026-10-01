/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.AiInstallStatus;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 840 R2 — when a stage's capabilities count as delivered.
 *
 * <p>The rule used to be "every package of the stage reached {@code installed}", and {@code
 * populateStatusPackages} gives a hardware-SKIPPED package a real stage. {@code cuda-runtime}
 * declares {@code requiresCuda} and lives in the CORE stage, so on every machine without an NVIDIA
 * GPU it is skipped there and CORE could never be delivered — suppressing the "Search is ready"
 * notice (the FE gates it on {@code retrieval-core}) for an entire hardware class whose search works
 * perfectly well.
 *
 * <p>So {@code skipped} is neutral: it neither delivers a stage nor blocks it. A package the machine
 * cannot run, the mode does not want, or the user declined is out of scope, not a failure to
 * deliver. Everything else still fails closed, which is what the remaining cases pin.
 */
final class AiInstallServiceStageReadinessTest {

  @TempDir Path tmp;

  private static AiInstallStatus statusOf(AiInstallService svc) throws Exception {
    Field f = AiInstallService.class.getDeclaredField("status");
    f.setAccessible(true);
    return (AiInstallStatus) f.get(svc);
  }

  private static void markStage(AiInstallService svc, InstallStage stage, String state)
      throws Exception {
    Method m =
        AiInstallService.class.getDeclaredMethod("markStage", InstallStage.class, String.class);
    m.setAccessible(true);
    m.invoke(svc, stage, state);
  }

  /** Seeds a stage row and its packages the way {@code publishStagePlan} + the run would. */
  private AiInstallService serviceWith(InstallStage stage, String... packageIdStatePairs)
      throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    var st = new AiInstallStatus.StageStatus();
    st.stage = stage.id();
    st.label = stage.label();
    st.state = "running";
    st.capabilities.addAll(stage.tierIds());
    status.stages.add(st);
    for (int i = 0; i < packageIdStatePairs.length; i += 2) {
      var ps = new AiInstallStatus.PackageStatus();
      ps.packageId = packageIdStatePairs[i];
      ps.stage = stage.id();
      ps.state = packageIdStatePairs[i + 1];
      status.packages.add(ps);
    }
    return svc;
  }

  @Test
  @DisplayName("CPU-only: the core stage is ready with the embedding installed and cuda-runtime skipped")
  void cpuOnlyCoreStageIsReady() throws Exception {
    AiInstallService svc =
        serviceWith(InstallStage.CORE, "embedding", "installed", "cuda-runtime", "skipped");

    markStage(svc, InstallStage.CORE, "completed");

    assertEquals(
        InstallStage.CORE.tierIds(),
        statusOf(svc).readyCapabilities,
        "search works on this machine; the hardware-skipped CUDA payload is out of scope, not a"
            + " failure to deliver retrieval-core");
  }

  @Test
  @DisplayName("a stage whose every package was declined or skipped delivers nothing")
  void allSkippedStageIsNotReady() throws Exception {
    AiInstallService svc =
        serviceWith(InstallStage.ENRICHMENT, "splade", "skipped", "reranker", "skipped");

    markStage(svc, InstallStage.ENRICHMENT, "completed");

    assertTrue(
        statusOf(svc).readyCapabilities.isEmpty(),
        "zero installed packages is not a delivery, however cleanly the stage ended");
  }

  @Test
  @DisplayName("a failed package keeps its stage unready even beside an installed one")
  void failedPackageBlocksTheStage() throws Exception {
    AiInstallService svc =
        serviceWith(InstallStage.CORE, "embedding", "installed", "cuda-runtime", "failed");

    markStage(svc, InstallStage.CORE, "completed");

    assertTrue(
        statusOf(svc).readyCapabilities.isEmpty(),
        "a capability whose model did not land is not usable however far the run got");
  }

  @Test
  @DisplayName("a stage still mid-flight is not ready in any of its non-terminal states")
  void inFlightStageIsNotReady() throws Exception {
    for (String inFlight : new String[] {"pending", "downloading", "verifying"}) {
      AiInstallService svc =
          serviceWith(InstallStage.CORE, "embedding", "installed", "cuda-runtime", inFlight);

      markStage(svc, InstallStage.CORE, "completed");

      assertTrue(
          statusOf(svc).readyCapabilities.isEmpty(),
          "'" + inFlight + "' means the stage is not finished with that package yet");
    }
  }

  /**
   * The second fact readiness rests on is unchanged: a stage that ended in a state whose
   * configuration pass never ran has the bytes on disk but no configuration applied for them.
   */
  @Test
  @DisplayName("a cancelled stage announces nothing even though its packages installed")
  void cancelledStageIsNotReady() throws Exception {
    AiInstallService svc =
        serviceWith(InstallStage.CORE, "embedding", "installed", "cuda-runtime", "skipped");

    markStage(svc, InstallStage.CORE, "cancelled");

    assertTrue(statusOf(svc).readyCapabilities.isEmpty());
    assertFalse(statusOf(svc).stages.isEmpty());
    assertEquals("cancelled", statusOf(svc).stages.get(0).state);
  }
}
