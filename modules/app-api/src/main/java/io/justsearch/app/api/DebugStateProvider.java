/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import io.justsearch.core.context.EngineContext;

/**
 * SPI for snapshotting head-side debug state into a Jackson-serializable form.
 *
 * <p>Tempdoc 519 §9 Step 3 (Diagnostics extraction): {@code DiagnosticsServiceImpl} (in
 * app-services) takes this interface so it can capture debug state into the diagnostics zip
 * without depending on the ui-side {@code DebugStateController}. Mirrors the §9 pattern used
 * for {@code EnterprisePolicyService} in B1.
 *
 * <p>Production impl: {@code io.justsearch.ui.api.DebugStateController.buildDebugState()}.
 *
 * <p>Stability: stable (API contract).
 */
public interface DebugStateProvider {
  /** Build a Jackson-serializable debug-state object (typically a JsonNode tree). */
  Object buildDebugState(EngineContext engineContext);
}
