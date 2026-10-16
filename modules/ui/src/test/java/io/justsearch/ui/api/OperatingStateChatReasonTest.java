/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.api.chat.ChatState;
import io.justsearch.app.api.lifecycle.LifecycleSnapshotV2;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.InferenceRuntimeView;
import io.justsearch.app.api.status.LifecycleCounters;
import io.justsearch.app.api.status.PowerStatusView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.app.api.status.operating.OperatingStateView;
import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.core.component.ComponentState;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/*
 * Spec A step 7 in the operating block: with the engine down, chat says why it failed, and a start
 * the boot order holds back reads as loading.
 */
final class OperatingStateChatReasonTest {

  private static final String OBSERVED_AT = "2026-10-06T10:15:02.114Z";
  private static final Instant NOW = Instant.parse("2026-10-06T10:15:02.200Z");
  private static final long SAMPLED_AT_MS = Instant.parse("2026-10-06T10:15:01Z").toEpochMilli();

  /*
   * A failed chat names its classified failure in the FAILED vocabulary, from the generative reason
   * code of the same pass. A code that several failures share (crashed) names none.
   */
  @Test
  void aFailedChatNamesItsClassifiedFailure() {
    String[][] rows = {
      {"inference.out_of_memory", "RESOURCE_EXHAUSTED"},
      {"inference.startup_timeout", "STARTUP_TIMEOUT"},
      {"inference.start_failed", "START_FAILED"},
      {"inference.model_load_failed", "MODEL_LOAD_FAILED"},
      {"inference.crashed", null},
    };
    for (String[] row : rows) {
      String reasonCode = row[0];
      OperatingStateView view = assemble(ComponentState.FAILED, reasonCode);

      assertEquals(ChatState.FAILED, view.chat().state(), reasonCode);
      assertEquals(row[1], view.chat().reason(), reasonCode);
      assertEquals(reasonCode, view.chat().reasonCode(), reasonCode);
    }
  }

  /*
   * Spec A §6.2: while the first GPU start waits for the query encoders the engine is down but the
   * component already reports the start, so chat is LOADING rather than OFF.
   */
  @Test
  void aStartWaitingWhileTheEngineIsDownIsLoading() {
    assertEquals(ChatState.LOADING,
        assemble(ComponentState.STARTING, "inference.starting").chat().state());
  }

  private static OperatingStateView assemble(ComponentState generative, String reason) {
    return new OperatingStateAssembler().assemble(
        snapshot(generative, reason), inference(), PowerStatusView.unknown(), worker(),
        SAMPLED_AT_MS, false, 2_000L, NOW, null);
  }

  private static LifecycleSnapshotV2 snapshot(ComponentState generative, String generativeReason) {
    var ready = new LifecycleSnapshotV2.Component(ComponentState.READY, null, OBSERVED_AT);
    return new LifecycleSnapshotV2(
        LifecycleSnapshotV2.SCHEMA_VERSION,
        OBSERVED_AT,
        new LifecycleSnapshotV2.Lifecycle(LifecycleState.LIFECYCLE_STATE_READY, null, null),
        new LifecycleSnapshotV2.Components(
            ready,
            ready,
            ready,
            new LifecycleSnapshotV2.Component(
                generative, generativeReason, "2026-10-06T10:00:00Z")));
  }

  /** The engine as the status surface reports it when chat's server is down. */
  private static InferenceRuntimeView inference() {
    return new InferenceRuntimeView(
        "ONLINE", null, false, null, new LifecycleCounters(0L, 0L, 0L), true, "Down", "", "", "");
  }

  private static WorkerOperationalView worker() {
    return WorkerOperationalViewBuilder.builder(WorkerOperationalView.fallback("SERVING"))
        .core(new CoreIndexView(true, 10, 0, "SERVING", 0, 0))
        .build();
  }
}
