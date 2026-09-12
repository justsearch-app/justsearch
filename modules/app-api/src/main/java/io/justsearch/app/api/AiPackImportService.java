/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/**
 * Helper service that orchestrates offline AI Pack imports (preflight, validate, stage, install,
 * apply settings). Composed by {@link PackImportService} implementations.
 *
 * <p>Interface added as part of tempdoc 519 §9 Block B2. The concrete implementation lives in
 * {@code modules/ui/.../ai/pack/} with the same simple name; consumers in {@code app-services}
 * import this interface from {@code app-api}.
 *
 * <p>Stability: stable (API contract).
 */
public interface AiPackImportService {

  /** Return the current import status snapshot. Mutable internally; callers treat as point-in-time. */
  AiPackImportStatus getStatus();

  /** Return the persisted record of installed packs (schema, packs, files). */
  InstalledPacksRecord getInstalledPacks();

  /**
   * Preflight a pack file: parse manifest, validate identity. Throws
   * {@link AiPackPreflightException} on validation failure.
   */
  AiPackPreflightResult preflight(Path packPath);

  record Attempt(AiPackImportStatus started, CompletionStage<AiPackImportStatus> completion) {}

  /** Start one import; a live owner refuses a duplicate. Completion follows owner cleanup. */
  Attempt startImport(Path packPath, boolean allowDowngrade);
}
