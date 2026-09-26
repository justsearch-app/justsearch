/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import java.util.Locale;
import java.util.Set;

/**
 * Compatibility vocabulary for stored LLM model-path attribution.
 * Server executable ownership uses resolver provenance; its property marker is retired.
 * No current model-path promotion writes a marker. Bootstrap retains the legacy reader
 * and the reserved profile marker for the separately designed profile persistence path.
 */
public final class ModelPathSource {

  private ModelPathSource() {}

  /**
   * Companion system property that records who wrote {@code justsearch.llm.model_path}.
   *
   * <p>No writer ships today — see the class javadoc. It is read by
   * {@code InferenceConfig.classifyModelPathOwner}, where an absent marker correctly means
   * "operator", and is kept for tempdoc 842's pending profile-persistence writer.
   */
  public static final String SOURCE_PROP_LLM_MODEL_PATH = "justsearch.llm.model_path.source";

  /** Legacy settings-origin model-path marker; no current promotion writes it. */
  public static final String UI_SETTINGS = "ui_settings";

  /**
   * Written when a chat profile resolves the (model, mmproj) pair and persists the model path.
   * Declared now so a profile-written path is recognized as system-owned by the readers that
   * already ship; the writer lands with the persistence slice of tempdoc 842.
   */
  public static final String PROFILE_RESOLVED = "profile_resolved";

  private static final Set<String> SYSTEM_OWNED =
      Set.of(UI_SETTINGS, PROFILE_RESOLVED);

  /**
   * Returns true when {@code marker} names a system-owned (re-derivable) stored model path.
   *
   * <p>A null, blank, or unrecognized marker is deliberately <b>not</b> system-owned: an unmarked
   * value is an operator value, and an unknown marker is treated the same way so a future writer
   * that forgets to publish its label cannot silently downgrade an operator lock.
   *
   * @param marker the raw marker value (trimmed and compared case-insensitively), may be null
   */
  public static boolean isSystemOwned(String marker) {
    if (marker == null) return false;
    String norm = marker.trim().toLowerCase(Locale.ROOT);
    return !norm.isEmpty() && SYSTEM_OWNED.contains(norm);
  }
}
