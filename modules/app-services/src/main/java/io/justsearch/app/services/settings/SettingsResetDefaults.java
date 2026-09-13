/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import io.justsearch.app.api.UiSettings;

/** The existing user-controlled reset subset; caller owns the supplied mutable snapshot. */
public final class SettingsResetDefaults {
  private SettingsResetDefaults() {}

  public static void applyTo(UiSettings current) {
    // Preserve administrator paths, geometry and vimMode, as in the original UI reset contract.
    current.setTheme("system");
    current.setHighContrast(false);
    current.setDensity("comfort");
    current.setDefaultAction("open");
    current.setPauseIndexingDuringAi(false);
    current.setMode("simple");
    current.setTrustLoopNudgeSeen(false);
    current.setExcludePatterns(new java.util.ArrayList<>());
    current.setContextLength(0);
    current.setMaxTokens(1024);
    current.setGpuLayers(0);
  }
}
