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
    String mode,
    Boolean hasSeenTrustLoopNudge,
    java.util.List<Object> excludePatterns,
    // Tempdoc 737 Phase 1 — desired-state chat bit (RuntimeSpec). Nullable: null = never set.
    Boolean chatEnabled,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    java.util.List<String> invalidExcludePatterns,
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    IndexingPausePolicy indexingPause
) {
  public UiSettingsV2(String theme, Boolean highContrast, String density, Boolean vimMode,
      String defaultAction, Integer inspectorWidth, String mode,
      Boolean hasSeenTrustLoopNudge, java.util.Collection<?> excludePatterns, Boolean chatEnabled) {
    this(theme, highContrast, density, vimMode, defaultAction, inspectorWidth,
        mode, hasSeenTrustLoopNudge, entries(excludePatterns), chatEnabled, null, null);
  }

  public UiSettingsV2(String theme, Boolean highContrast, String density, Boolean vimMode,
      String defaultAction, Integer inspectorWidth, String mode,
      Boolean hasSeenTrustLoopNudge, java.util.Collection<?> excludePatterns, Boolean chatEnabled,
      java.util.List<String> invalidExcludePatterns, IndexingPausePolicy indexingPause) {
    this(theme, highContrast, density, vimMode, defaultAction, inspectorWidth, mode,
        hasSeenTrustLoopNudge, entries(excludePatterns), chatEnabled, invalidExcludePatterns, indexingPause);
  }

  private static java.util.List<Object> entries(java.util.Collection<?> source) {
    return source == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(source));
  }

  public static UiSettingsV2 defaults() {
    return new UiSettingsV2("system", false, "comfort", false, "open", null, "simple", false, java.util.List.of(), false);
  }
}
