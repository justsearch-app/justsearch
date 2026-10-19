/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Structural guard supplemented by the fresh-profile installed-process boot check. */
final class HeadlessAppAutostartOrderingTest {
  @Test
  void autostartSettingsCommitFollowsOwnerSealAndRecoveryReconciliation() throws Exception {
    String entry = Files.readString(Path.of("src/main/java/io/justsearch/ui/HeadlessApp.java"),
        StandardCharsets.UTF_8);
    String service = Files.readString(Path.of("../app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/ServicePhase.java"),
        StandardCharsets.UTF_8);
    int seal = entry.indexOf("settingsComponents.seal();");
    int reconcile = entry.indexOf("reconcileSettingsAfterComposition()", seal);
    int seed = entry.indexOf("InferenceWiring.seedAutostartSpec(", reconcile);
    int ready = entry.indexOf("settingsReconciled.complete(null);", reconcile);
    assertTrue(seal >= 0 && reconcile > seal && seed > reconcile && ready > seed,
        "fresh-profile autostart must commit after all settings owners are sealed and reconciled");
    assertFalse(service.contains("InferenceWiring.seedAutostartSpec(runtimeSpecStore)"),
        "ServicePhase runs before the settings composer can be sealed");
  }
}
