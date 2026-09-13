/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import io.justsearch.app.api.UiSettings;

/**
 * Desired runtime state (tempdoc 737 §12a). The user/policy-owned, persisted answer to "what
 * does the user want the AI runtime to be doing" — minimal in Phase 1: whether the chat engine
 * should be up ({@code chatEnabled}), plus the already-persisted model reference it points at.
 *
 * <p>{@code chatEnabled} is resolved from the nullable {@link UiSettings#getChatEnabled()}:
 * {@code null} (never persisted) resolves to {@code false} (autostart-default-false), and
 * {@code chatEnabledExplicit} records whether the bit was ever explicitly written — the boot
 * autostart-seed only fires when it was not (see {@code RuntimeSpecStore}).
 *
 * <p>This is a read projection of {@link UiSettings}; it is not a second authority. Writes go
 * through {@code RuntimeSpecStore}, which submits the single-field candidate to the accepted settings owner.
 */
public record RuntimeSpec(boolean chatEnabled, boolean chatEnabledExplicit, String llmModelPath) {

  /** Builds the spec from a loaded settings snapshot, resolving the nullable chat bit. */
  public static RuntimeSpec fromSettings(UiSettings settings) {
    Boolean raw = settings == null ? null : settings.getChatEnabled();
    String model = settings == null ? "" : settings.getLlmModelPath();
    return new RuntimeSpec(raw != null && raw, raw != null, model == null ? "" : model);
  }
}
