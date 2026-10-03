/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.InstallPlanPreview;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 840 Phase 4 follow-up — the per-component rows the install UI is built from.
 *
 * <p>These live on the PREVIEW, not on {@code AiInstallStatus.packages}, and this test exists because
 * the difference is easy to get wrong in a way no endpoint check would catch: {@code status.packages}
 * is run bookkeeping and comes back EMPTY on an idle machine that is not fully installed, so a
 * component list sourced from it would show a first-run user nothing at all — precisely the user for
 * whom the list matters most. The preview answers from the registry and the pure plan, so it is
 * complete before any run has happened.
 */
final class InstallPlanPreviewComponentsTest {

  @TempDir Path tmp;
  @TempDir Path settingsDir;

  private UiSettingsStore writableStore() {
    return new UiSettingsStore(
        UiSettingsStore.PersistenceMode.READ_WRITE, settingsDir.resolve("settings.json"));
  }

  private static InstallPlanPreview.ComponentEstimate row(InstallPlanPreview p, String id) {
    return p.components.stream().filter(c -> id.equals(c.id)).findFirst().orElse(null);
  }

  @Test
  @DisplayName("every registry component appears, with the copy the UI needs to describe it")
  void everyComponentAppearsWithItsCopy() {
    InstallPlanPreview preview =
        new AiInstallService(null, null, null, null, tmp).previewInstallPlan();

    Set<String> ids = preview.components.stream().map(c -> c.id).collect(Collectors.toSet());
    assertTrue(
        ids.containsAll(
            List.of("embedding", "splade", "reranker", "ner", "citation-scorer", "chat",
                "cuda-runtime")),
        "the list is the whole registry, not just what this machine will download: " + ids);

    var reranker = row(preview, "reranker");
    assertNotNull(reranker);
    assertFalse(reranker.label.isBlank(), "a row the user reads needs a name");
    assertFalse(
        reranker.description.isBlank(),
        "description has been in the registry since v2 and rendered nowhere — that is the gap");
    assertEquals("improves-results", reranker.necessity);
    assertFalse(reranker.state.isBlank(), "every row states where it stands");
  }

  /**
   * Declinability is DERIVED from necessity, so the three cases must differ without any list of ids
   * being maintained beside the registry.
   */
  @Test
  @DisplayName("declinability follows necessity: core and infrastructure are not the user's to switch off")
  void declinabilityFollowsNecessity() {
    InstallPlanPreview preview =
        new AiInstallService(null, null, null, null, tmp).previewInstallPlan();

    assertFalse(row(preview, "embedding").declinable, "search does not work without it");
    assertFalse(
        row(preview, "cuda-runtime").declinable,
        "infrastructure: declining 'GPU runtime libraries' would remove chat as a side effect");
    assertTrue(row(preview, "reranker").declinable, "improves results — a real choice");
    assertTrue(row(preview, "chat").declinable, "adds a feature — a real choice");
  }

  /**
   * A declined component and an unavailable one must not render alike. Hardware the machine does not
   * have is not a choice the user made, and showing it as an unticked box implies an option that
   * does not exist.
   */
  @Test
  @DisplayName("declined is a choice; unavailable is not — and they are different states")
  void declinedAndUnavailableAreDistinct() throws Exception {
    UiSettingsStore store = writableStore();
    var settings = store.load();
    settings.setDeclinedAiPackages(List.of("reranker"));
    store.replacePrepared(store.prepare(settings, new SettingsWitness(0, null)));

    InstallPlanPreview preview =
        new AiInstallService(null, store, null, null, tmp).previewInstallPlan();

    var reranker = row(preview, "reranker");
    assertTrue(reranker.declined, "the user's standing preference is reflected");
    assertEquals("declined", reranker.state);
    assertEquals(0L, reranker.downloadBytes, "a declined component costs nothing to install");
    assertTrue(
        reranker.unavailableReason.isBlank(),
        "a choice is not an inability — no hardware reason belongs on a declined row");

    // Whatever this machine's hardware decides, an unavailable row must carry its reason and must
    // never be reported as the user's decision.
    for (var c : preview.components) {
      if ("unavailable".equals(c.state)) {
        assertFalse(
            c.unavailableReason.isBlank(),
            "component '" + c.id + "' is unavailable and must say why, in the planner's own words");
        assertFalse(c.declined, "hardware is not a choice the user made");
      }
    }
  }

  @Test
  @DisplayName("a component still to fetch reports its cost; one already present reports none")
  void downloadBytesReflectThePlan() {
    InstallPlanPreview preview =
        new AiInstallService(null, null, null, null, tmp).previewInstallPlan();

    for (var c : preview.components) {
      if ("to-download".equals(c.state)) {
        assertTrue(c.downloadBytes > 0L, "'" + c.id + "' is to-download but claims to cost nothing");
      }
      if ("installed".equals(c.state)) {
        assertEquals(0L, c.downloadBytes, "'" + c.id + "' is installed but still claims a cost");
      }
      assertTrue(c.totalBytes >= 0L, "a footprint is never negative");
    }
  }
}
