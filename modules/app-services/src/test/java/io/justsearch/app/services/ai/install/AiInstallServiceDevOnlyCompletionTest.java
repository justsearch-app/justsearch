/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.InstallIntent;
import io.justsearch.configuration.model.InstallPlan;
import io.justsearch.configuration.model.InstallPlanner;
import io.justsearch.configuration.model.ModelRegistry;
import io.justsearch.configuration.model.SkipCause;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 941 round 19 finding F3 — a clean, complete install must be able to satisfy the product's
 * own {@code installedFully} contract, and a skip must never be described by a cause that did not
 * produce it.
 *
 * <p>The observed run: a fresh GPU_FULL install downloaded 10.89 GB, installed all seven user
 * packages, and the CUDA runtime went on to serve chat — yet {@code GET /api/ai/install/status}
 * reported {@code installedFully:false} with {@code "Installed with limitations: Chat model
 * (compact) skipped on this hardware."}. Two defects met:
 *
 * <ul>
 *   <li>{@code chat-compact} is {@code devOnly} in the registry — {@link InstallPlanner} skips it
 *       with {@link SkipCause#DEV_ONLY} ahead of every other gate, so it is in NO user install plan
 *       and no hardware can change that. It nonetheless became a {@code state:"skipped"} row in
 *       {@code status.packages}, and {@code installedFully} was {@code skippedCount == 0}. A
 *       development-only package in the registry therefore made {@code installedFully:true}
 *       UNREACHABLE on every machine — the contract could not be satisfied by any install.
 *   <li>the completion message hardcoded "skipped on this hardware" for every skip regardless of its
 *       typed cause, so an intentional packaging exclusion (and, equally, a user's own decline) was
 *       reported to the user as a limitation of their machine.
 * </ul>
 */
final class AiInstallServiceDevOnlyCompletionTest {

  @TempDir Path tmp;

  private static AiInstallStatus statusOf(AiInstallService svc) throws Exception {
    Field f = AiInstallService.class.getDeclaredField("status");
    f.setAccessible(true);
    return (AiInstallStatus) f.get(svc);
  }

  private static void populate(AiInstallService svc, InstallPlan plan, ModelRegistry registry)
      throws Exception {
    Method m =
        AiInstallService.class.getDeclaredMethod(
            "populateStatusPackages", InstallPlan.class, ModelRegistry.class);
    m.setAccessible(true);
    m.invoke(svc, plan, registry);
  }

  private static AiInstallStatus.PackageStatus addPackage(
      AiInstallStatus status, String id, String state) {
    var ps = new AiInstallStatus.PackageStatus();
    ps.packageId = id;
    ps.state = state;
    status.packages.add(ps);
    return ps;
  }

  /**
   * The whole observed shape, driven through the REAL registry rather than a hand-built plan: only
   * the real registry can prove that the package which broke the contract is still flagged {@code
   * devOnly} and still reaches the planner.
   */
  @Test
  @DisplayName("a fresh GPU install with every user package installed reports installedFully:true")
  void devOnlyPackage_doesNotDefeatTheCompletenessContract() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    ModelRegistry registry = svc.getManifest();
    InstallPlan plan =
        InstallPlanner.plan(
            registry,
            HardwareProfile.gpuFull(24L * 1024 * 1024 * 1024),
            InstallIntent.FULL_DESKTOP,
            Set.of(),
            tmp.resolve("models"),
            tmp.resolve("home"));

    assertTrue(
        plan.skipped().stream()
            .anyMatch(s -> "chat-compact".equals(s.packageId()) && s.cause() == SkipCause.DEV_ONLY),
        "precondition: the registry still carries a devOnly package the planner skips");

    AiInstallStatus status = statusOf(svc);
    populate(svc, plan, registry);
    // The run succeeded: every package the plan actually wanted is on disk.
    for (var ps : status.packages) {
      if (!"skipped".equals(ps.state)) {
        ps.state = "installed";
      }
    }

    svc.applyCompletionState(null);

    assertEquals("completed", status.state);
    assertTrue(
        status.packages.stream().noneMatch(ps -> "chat-compact".equals(ps.packageId)),
        "a development-only package is not a component of a user install: "
            + status.packages.stream().map(ps -> ps.packageId).toList());
    assertTrue(
        status.installedFully,
        "every user package installed — the completeness contract must be satisfiable: "
            + status.message);
    assertFalse(
        status.message.contains("hardware"),
        "nothing about this machine limited the install: " + status.message);
    assertFalse(
        status.message.contains("limitations"),
        "a complete install is not 'installed with limitations': " + status.message);
  }

  /**
   * The message must classify by the planner's typed cause, not assume the one cause that used to be
   * the only producer. A user who declined a component was told their hardware could not run it.
   */
  @Test
  @DisplayName("a declined package is not reported as a hardware skip, nor as an incomplete install")
  void userDeclinedSkip_isNotDescribedAsHardware() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    addPackage(status, "embedding", "installed");
    var reranker = addPackage(status, "reranker", "skipped");
    reranker.label = "Reranker";
    reranker.skipCause = SkipCause.USER_DECLINED.id();
    reranker.skipReason = "Reranker was declined — you chose not to install it.";

    svc.applyCompletionState(null);

    assertEquals("completed", status.state);
    assertFalse(
        status.message.contains("hardware"),
        "the user's own choice is not a property of their machine: " + status.message);
    assertTrue(
        status.installedFully,
        "an install the user shaped by declining a component is complete (tempdoc 840): "
            + status.message);
  }

  /** The hardware arm is unchanged — the honest case keeps its honest copy. */
  @Test
  @DisplayName("a hardware skip still reads 'installed with limitations ... on this hardware'")
  void hardwareSkip_keepsItsCopy() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    addPackage(status, "embedding", "installed");
    var chat = addPackage(status, "chat", "skipped");
    chat.label = "Chat model";
    chat.skipCause = SkipCause.HARDWARE.id();

    svc.applyCompletionState(null);

    assertFalse(status.installedFully, "tempdoc 374 finding #8: a hardware skip IS a limitation");
    assertTrue(status.message.contains("limitations"), status.message);
    assertTrue(status.message.contains("Chat model"), status.message);
    assertTrue(status.message.contains("hardware"), status.message);
  }

  /**
   * A row with no recorded cause (a pre-fix status, or a code path that forgets to stamp one) keeps
   * the pre-fix verdict: unknown degrades to the alarming answer, never to a green claim.
   */
  @Test
  @DisplayName("a skip with no recorded cause degrades to the limiting verdict, not to green")
  void unknownSkipCause_failsClosed() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    addPackage(status, "embedding", "installed");
    addPackage(status, "chat", "skipped").label = "Chat model";

    svc.applyCompletionState(null);

    assertFalse(status.installedFully, "an unclassified skip must not be assumed harmless");
    assertTrue(status.message.contains("limitations"), status.message);
  }

  /** The planner's typed cause reaches the wire row, so no consumer has to parse the prose. */
  @Test
  @DisplayName("the typed skip cause is projected onto the package the UI reads")
  void skipCauseReachesThePackageStatus() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    ModelRegistry registry = svc.getManifest();
    InstallPlan plan =
        InstallPlanner.plan(
            registry,
            HardwareProfile.cpuOnly(),
            InstallIntent.FULL_DESKTOP,
            Set.of(),
            tmp.resolve("models"),
            tmp.resolve("home"));

    AiInstallStatus status = statusOf(svc);
    populate(svc, plan, registry);

    List<AiInstallStatus.PackageStatus> skipped =
        status.packages.stream().filter(ps -> "skipped".equals(ps.state)).toList();
    assertFalse(skipped.isEmpty(), "a CPU-only machine skips the GGUF chat model");
    for (var ps : skipped) {
      assertFalse(
          ps.skipCause == null || ps.skipCause.isBlank(),
          "every skipped row carries the planner's typed cause: " + ps.packageId);
    }

    // …and it survives the deep copy the endpoint actually serializes. A field the snapshot drops is
    // a field that reaches no consumer, which is how a wire addition becomes decorative.
    List<AiInstallStatus.PackageStatus> served =
        status.snapshot().packages.stream().filter(ps -> "skipped".equals(ps.state)).toList();
    assertEquals(
        skipped.stream().map(ps -> ps.packageId + "=" + ps.skipCause).toList(),
        served.stream().map(ps -> ps.packageId + "=" + ps.skipCause).toList(),
        "the snapshot the API serves must carry the typed cause");
  }
}
