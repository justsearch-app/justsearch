/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.app.api.InstallPlanPreview;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.ai.install.AiInstallService;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.model.CapabilityTier;
import io.justsearch.configuration.model.InstallPlan;
import io.justsearch.configuration.model.InstallPlanner;
import io.justsearch.configuration.model.SkipCause;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 840 Phase 2 — the service actually reads the user's per-component intent.
 *
 * <p>{@code InstallPlanner.plan} and {@code InstallCompleteness.compute} are pure functions that take
 * the declined set as a PARAMETER; the wiring that reads {@link UiSettings} and passes it in lives in
 * {@code AiInstallService} alone. A pure function nobody feeds is invisible, so this drives the real
 * service against a real settings store and asserts the pre-install PREVIEW — the surface the consent
 * dialog quotes — reflects the choice.
 *
 * <p>Uses {@code splade}: the only enrichment package that is planned on every hardware profile (no
 * VRAM floor, no {@code requiresCuda}), so the assertions hold on CPU and GPU machines alike.
 */
final class AiInstallDeclinedComponentsTest {

  @TempDir Path aiHome;
  @TempDir Path settingsDir;

  private AiInstallService serviceDeclining(String... declined) throws Exception {
    UiSettingsStore store =
        new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsDir.resolve("settings.json"));
    UiSettings s = store.load();
    s.setDeclinedAiPackages(List.of(declined));
    store.replacePrepared(store.prepare(s, new SettingsWitness(0, null)));
    return new AiInstallService(null, store, null, null, aiHome);
  }

  @Test
  void previewInstallPlan_dropsADeclinedComponentFromTheConsentTotal() throws Exception {
    AiInstallService baseline = serviceDeclining();
    InstallPlanPreview before = baseline.previewInstallPlan();
    long spladeBytes = plannedBytesFor(baseline, "splade");
    assumeTrue(spladeBytes > 0, "splade is not planned on this machine's hardware");

    AiInstallService declined = serviceDeclining("splade");
    InstallPlanPreview after = declined.previewInstallPlan();

    assertEquals(
        before.totalDownloadBytes - spladeBytes,
        after.totalDownloadBytes,
        "the consent total must drop by exactly the declined package's bytes — a preview that still"
            + " quotes them is asking the user to consent to a download that will not happen");
    long enrichmentBefore = tierDownloadBytes(before, CapabilityTier.RETRIEVAL_ENRICHMENT);
    long enrichmentAfter = tierDownloadBytes(after, CapabilityTier.RETRIEVAL_ENRICHMENT);
    assertEquals(enrichmentBefore - spladeBytes, enrichmentAfter, "…and so must its tier's estimate");
  }

  @Test
  void declinedComponent_isSkippedWithTheUserDeclinedCause_whileRequiredOnesSurvive() throws Exception {
    AiInstallService svc = serviceDeclining("splade", "embedding", "cuda-runtime");
    svc.previewInstallPlan();

    InstallPlan plan =
        InstallPlanner.plan(
            svc.getManifest(),
            svc.buildHardwareProfile(),
            svc.installIntent(),
            Set.of("splade", "embedding", "cuda-runtime"),
            svc.modelsDir(),
            svc.aiHome());

    assertTrue(
        plan.skipped().stream()
            .anyMatch(
                s -> s.packageId().equals("splade") && s.cause() == SkipCause.USER_DECLINED),
        "a declinable component named in settings is skipped, with the typed cause");
    assertTrue(
        plan.downloads().stream().anyMatch(d -> d.packageId().equals("embedding")),
        "the REQUIRED embedding model is installed even though settings name it — search does not"
            + " work without it, so the preference is advisory there");
    assertTrue(
        plan.skipped().stream()
            .noneMatch(
                s ->
                    s.cause() == SkipCause.USER_DECLINED
                        && (s.packageId().equals("embedding")
                            || s.packageId().equals("cuda-runtime"))),
        "and neither a REQUIRED nor an INFRASTRUCTURE package may be recorded as user-declined");
  }

  /** Bytes the plan (with nothing declined) still owes for {@code packageId}. */
  private static long plannedBytesFor(AiInstallService svc, String packageId) {
    InstallPlan plan =
        InstallPlanner.plan(
            svc.getManifest(),
            svc.buildHardwareProfile(),
            svc.installIntent(),
            svc.modelsDir(),
            svc.aiHome());
    return plan.downloads().stream()
        .filter(d -> d.packageId().equals(packageId))
        .mapToLong(InstallPlan.PlannedDownload::sizeBytes)
        .sum();
  }

  private static long tierDownloadBytes(InstallPlanPreview preview, CapabilityTier tier) {
    return preview.tiers.stream()
        .filter(t -> t.tier.equals(tier.id()))
        .mapToLong(t -> t.downloadBytes)
        .sum();
  }
}
