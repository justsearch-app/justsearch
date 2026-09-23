/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.settings;

import java.util.List;

/**
 * Canonical v2 settings contract matching the frontend's {@code AppSettings} shape.
 *
 * <p>This is the server-side DTO for {@code GET/POST /api/settings/v2}.
 */
public record SettingsV2(
    UiSettingsV2 ui,
    LlmSettingsV2 llm,
    List<String> indexPaths,
    String settingsMode,
    SettingsWitness witness,
    String operationKey,
    String state,
    Integer apiPort,
    Boolean restartScheduled
) {
  public SettingsV2 {
    // Do not replace nulls with defaults here.
    // Null means "absent from request" — partial merging uses null checks
    // to decide what to merge. Use SettingsV2.empty() for a default instance.
    indexPaths = indexPaths != null ? List.copyOf(indexPaths) : null;
    if (apiPort != null && (apiPort < 0 || apiPort > 65535)) {
      throw new IllegalArgumentException("apiPort out of range: " + apiPort);
    }
    // settingsMode is server-set only (ignored in POST body).
  }

  /** Compatibility projection without an observed or committed witness. */
  public SettingsV2(UiSettingsV2 ui, LlmSettingsV2 llm, List<String> indexPaths, String settingsMode) {
    this(ui, llm, indexPaths, settingsMode, null, null, null, null, null);
  }

  /** Compatibility constructor for receipts created before the desired API-port field. */
  public SettingsV2(UiSettingsV2 ui, LlmSettingsV2 llm, List<String> indexPaths, String settingsMode,
      SettingsWitness witness, String operationKey, String state) {
    this(ui, llm, indexPaths, settingsMode, witness, operationKey, state, null, null);
  }

  /** Compatibility constructor for callers that do not project restart scheduling. */
  public SettingsV2(UiSettingsV2 ui, LlmSettingsV2 llm, List<String> indexPaths, String settingsMode,
      SettingsWitness witness, String operationKey, String state, Integer apiPort) {
    this(ui, llm, indexPaths, settingsMode, witness, operationKey, state, apiPort, null);
  }

  public static SettingsV2 empty() {
    return new SettingsV2(UiSettingsV2.defaults(), LlmSettingsV2.defaults(), List.of(), null);
  }
}
