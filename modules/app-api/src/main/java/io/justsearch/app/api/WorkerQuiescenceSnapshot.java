/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.List;

/**
 * Point-in-time Worker quiescence state for an upgrade preparation.
 *
 * <p>Contract-layer projection of the Worker's gRPC {@code UpgradeQuiescenceResponse}. The Head's
 * REST layer consumes this record rather than the proto message: {@code ui.api} must not depend on
 * ipc proto types (enforced by {@code UiApiGuardrailsTest}), so the mapping happens once at the
 * gRPC boundary in {@code KnowledgeClient} instead of leaking generated types into the
 * controllers.
 */
public record WorkerQuiescenceSnapshot(
    String preparationId,
    boolean ready,
    boolean loopQuiesced,
    boolean queueCheckpointed,
    String migrationState,
    List<String> blockers) {

  public WorkerQuiescenceSnapshot {
    preparationId = preparationId == null ? "" : preparationId;
    migrationState = migrationState == null ? "" : migrationState;
    blockers = blockers == null ? List.of() : List.copyOf(blockers);
  }
}
