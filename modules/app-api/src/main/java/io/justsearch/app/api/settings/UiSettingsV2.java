/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.settings;

/**
 * UI-only settings in the v2 canonical contract.
 *
 * <p>Matches the frontend's {@code UISettings} interface.
 */
public record UiSettingsV2(
    String theme,
    Boolean highContrast,
    String density,
    Boolean vimMode,
    String defaultAction,
    Integer inspectorWidth,
    Boolean pauseIndexingDuringAi,
    String mode,
    Boolean hasSeenTrustLoopNudge,
    java.util.List<String> excludePatterns,
    // Tempdoc 737 Phase 1 — desired-state chat bit (RuntimeSpec). Nullable: null = never set.
    Boolean chatEnabled,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    java.util.List<String> invalidExcludePatterns
) {
  public UiSettingsV2(String theme, Boolean highContrast, String density, Boolean vimMode,
      String defaultAction, Integer inspectorWidth, Boolean pauseIndexingDuringAi, String mode,
      Boolean hasSeenTrustLoopNudge, java.util.List<String> excludePatterns, Boolean chatEnabled) {
    this(theme, highContrast, density, vimMode, defaultAction, inspectorWidth, pauseIndexingDuringAi,
        mode, hasSeenTrustLoopNudge, excludePatterns, chatEnabled, null);
  }

  public static UiSettingsV2 defaults() {
    return new UiSettingsV2("system", false, "comfort", false, "open", null, false, "simple", false, java.util.List.of(), false);
  }
}
