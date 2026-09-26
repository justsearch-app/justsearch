/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.lifecycle.LifecycleSnapshotV2;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentState;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Exact index-owner cause forwarding into the schema-2 lifecycle component. */
final class StatusLifecycleWorkerReasonTest {

  private static LifecycleSnapshotV2.Component indexComponent(
      Consumer<ComponentHandle> publication) {
    try (var components = new StatusComponentFixture()) {
      publication.accept(components.index());
      return components.projection().lifecycle().components().index();
    }
  }

  @Test
  @DisplayName("a lost worker reports worker.lost, not worker.spawn.failed")
  void lostWorkerReportsLost() {
    LifecycleSnapshotV2.Component component = indexComponent(handle -> handle.transition(
        ComponentState.FAILED,
        LifecycleReasonCode.WORKER_LOST.code(),
        "Health check failed"));
    assertEquals(ComponentState.FAILED, component.state());
    assertEquals(LifecycleReasonCode.WORKER_LOST.code(), component.reason_code());
  }

  @Test
  void corruptIndexReportsPreciseCause() {
    LifecycleSnapshotV2.Component component = indexComponent(handle -> handle.transition(
        ComponentState.FAILED,
        LifecycleReasonCode.WORKER_INDEX_CORRUPT.code(),
        "Set index.recovery.policy=BACKUP_REBUILD"));
    assertEquals(LifecycleReasonCode.WORKER_INDEX_CORRUPT.code(), component.reason_code());
  }

  @Test
  void schemaMismatchSurvivesRecoveryLadder() {
    LifecycleSnapshotV2.Component component = indexComponent(handle -> {
      handle.transition(
          ComponentState.FAILED,
          LifecycleReasonCode.WORKER_INDEX_SCHEMA_MISMATCH.code(),
          "Set index.schema_mismatch.policy=BLUE_GREEN_MIGRATE");
      handle.transition(
          ComponentState.RELOADING,
          LifecycleReasonCode.WORKER_RECOVERING.code(),
          "attempt 1");
      handle.transition(
          ComponentState.FAILED,
          LifecycleReasonCode.WORKER_SPAWN_RECOVERY_EXHAUSTED.code(),
          "attempts exhausted");
    });
    assertEquals(ComponentState.FAILED, component.state());
    assertEquals(
        LifecycleReasonCode.WORKER_INDEX_SCHEMA_MISMATCH.code(), component.reason_code());
  }

  @Test
  void spawnFailureAndRecoveryExhaustionPassThrough() {
    assertEquals(
        LifecycleReasonCode.WORKER_SPAWN_FAILED.code(),
        indexComponent(handle -> handle.transition(
            ComponentState.FAILED,
            LifecycleReasonCode.WORKER_SPAWN_FAILED.code(),
            "start failed")).reason_code());
    assertEquals(
        LifecycleReasonCode.WORKER_SPAWN_RECOVERY_EXHAUSTED.code(),
        indexComponent(handle -> handle.transition(
            ComponentState.FAILED,
            LifecycleReasonCode.WORKER_SPAWN_RECOVERY_EXHAUSTED.code(),
            "attempts exhausted")).reason_code());
  }

  @Test
  void unknownReasonIsForwardedWithoutInventingAnotherCause() {
    assertEquals(
        "worker.future_reason",
        indexComponent(handle -> handle.transition(
            ComponentState.FAILED, "worker.future_reason", "newer producer evidence"))
            .reason_code());
  }

  @Test
  void absenceDistinguishesShutdownFromNeverConfigured() {
    assertEquals(
        LifecycleReasonCode.WORKER_SHUT_DOWN.code(),
        indexComponent(handle -> handle.transition(
            ComponentState.ABSENT,
            LifecycleReasonCode.WORKER_SHUT_DOWN.code(),
            "Worker shut down")).reason_code());
    assertEquals(
        LifecycleReasonCode.WORKER_NOT_CONFIGURED.code(),
        indexComponent(handle -> handle.transition(
            ComponentState.ABSENT,
            LifecycleReasonCode.WORKER_NOT_CONFIGURED.code(),
            "Worker not configured")).reason_code());
  }

  @Test
  void reloadingKeepsRecoveryCause() {
    LifecycleSnapshotV2.Component component = indexComponent(handle -> handle.transition(
        ComponentState.RELOADING,
        LifecycleReasonCode.WORKER_RECOVERING.code(),
        "attempt 1"));
    assertEquals(ComponentState.RELOADING, component.state());
    assertEquals(LifecycleReasonCode.WORKER_RECOVERING.code(), component.reason_code());
  }

  @Test
  void readyPublishesNoReason() {
    LifecycleSnapshotV2.Component component = indexComponent(handle ->
        handle.transition(ComponentState.READY, null, "healthy"));
    assertEquals(ComponentState.READY, component.state());
    assertNull(component.reason_code());
  }
}
