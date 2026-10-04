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
  @DisplayName("a lost worker reports index.failed, not index.failed")
  void lostWorkerReportsLost() {
    LifecycleSnapshotV2.Component component = indexComponent(handle -> handle.transition(
        ComponentState.FAILED,
        LifecycleReasonCode.INDEX_FAILED.code(),
        "Health check failed"));
    assertEquals(ComponentState.FAILED, component.state());
    assertEquals(LifecycleReasonCode.INDEX_FAILED.code(), component.reason_code());
  }

  @Test
  void corruptIndexReportsPreciseCause() {
    LifecycleSnapshotV2.Component component = indexComponent(handle -> handle.transition(
        ComponentState.FAILED,
        LifecycleReasonCode.INDEX_CORRUPT.code(),
        "Set index.recovery.policy=BACKUP_REBUILD"));
    assertEquals(LifecycleReasonCode.INDEX_CORRUPT.code(), component.reason_code());
  }

  @Test
  void schemaMismatchSurvivesRecoveryLadder() {
    LifecycleSnapshotV2.Component component = indexComponent(handle -> {
      handle.transition(
          ComponentState.FAILED,
          LifecycleReasonCode.INDEX_SCHEMA_OPEN_REFUSED.code(),
          "Set index.schema_mismatch.policy=BLUE_GREEN_MIGRATE");
      handle.transition(
          ComponentState.RELOADING,
          LifecycleReasonCode.COMPONENT_RECOVERING.code(),
          "attempt 1");
      handle.transition(
          ComponentState.FAILED,
          LifecycleReasonCode.COMPONENT_RECOVERY_EXHAUSTED.code(),
          "attempts exhausted");
    });
    assertEquals(ComponentState.FAILED, component.state());
    assertEquals(
        LifecycleReasonCode.INDEX_SCHEMA_OPEN_REFUSED.code(), component.reason_code());
  }

  @Test
  void spawnFailureAndRecoveryExhaustionPassThrough() {
    assertEquals(
        LifecycleReasonCode.INDEX_FAILED.code(),
        indexComponent(handle -> handle.transition(
            ComponentState.FAILED,
            LifecycleReasonCode.INDEX_FAILED.code(),
            "start failed")).reason_code());
    assertEquals(
        LifecycleReasonCode.COMPONENT_RECOVERY_EXHAUSTED.code(),
        indexComponent(handle -> handle.transition(
            ComponentState.FAILED,
            LifecycleReasonCode.COMPONENT_RECOVERY_EXHAUSTED.code(),
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
        LifecycleReasonCode.INDEX_SHUT_DOWN.code(),
        indexComponent(handle -> handle.transition(
            ComponentState.ABSENT,
            LifecycleReasonCode.INDEX_SHUT_DOWN.code(),
            "Worker shut down")).reason_code());
    assertEquals(
        LifecycleReasonCode.INDEX_UNAVAILABLE.code(),
        indexComponent(handle -> handle.transition(
            ComponentState.ABSENT,
            LifecycleReasonCode.INDEX_UNAVAILABLE.code(),
            "Worker not configured")).reason_code());
  }

  @Test
  void reloadingKeepsRecoveryCause() {
    LifecycleSnapshotV2.Component component = indexComponent(handle -> handle.transition(
        ComponentState.RELOADING,
        LifecycleReasonCode.COMPONENT_RECOVERING.code(),
        "attempt 1"));
    assertEquals(ComponentState.RELOADING, component.state());
    assertEquals(LifecycleReasonCode.COMPONENT_RECOVERING.code(), component.reason_code());
  }

  @Test
  void readyPublishesNoReason() {
    LifecycleSnapshotV2.Component component = indexComponent(handle ->
        handle.transition(ComponentState.READY, null, "healthy"));
    assertEquals(ComponentState.READY, component.state());
    assertNull(component.reason_code());
  }
}
