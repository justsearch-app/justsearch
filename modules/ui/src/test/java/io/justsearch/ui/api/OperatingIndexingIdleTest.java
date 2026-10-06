/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.justsearch.app.api.lifecycle.LifecycleSnapshotV2;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.EnrichmentProgressView;
import io.justsearch.app.api.status.EnrichmentProgressViewBuilder;
import io.justsearch.app.api.status.InferenceRuntimeView;
import io.justsearch.app.api.status.LifecycleCounters;
import io.justsearch.app.api.status.PowerStatusView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.app.api.status.operating.IndexingState;
import io.justsearch.app.api.status.operating.OperatingIndexingView;
import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.core.component.ComponentState;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spec E E-IDLE rows 1 and 2 (S7: "the app shows indexing slowed and why"): IDLE means nothing
 * left to drain, not only an empty job queue. Written against the status API as it stood before
 * the availability booleans existed, so row 1 runs unchanged on the base revision; rows 3 and 4
 * need those booleans and live in {@code OperatingIndexingAvailabilityTest}.
 */
@DisplayName("operating indexing IDLE: an enrichment backlog in an enabled stage is not idle")
final class OperatingIndexingIdleTest {

  private static final String OBSERVED_AT = "2026-10-06T08:00:00.000Z";
  private static final Instant NOW = Instant.parse("2026-10-06T08:00:00.100Z");

  // row 1: queue empty, embedding enabled with 3,000 pending -> not IDLE
  @Test
  void enabledEmbeddingBacklogIsNotIdle() {
    OperatingIndexingView indexing = assemble(worker(embeddingBacklog(true)));

    assertNotEquals(IndexingState.IDLE, indexing.state(),
        "3,000 documents still to embed: reading IDLE here is the base defect");
    assertEquals(IndexingState.RUNNING, indexing.state());
    assertEquals(0L, indexing.pendingJobs());
  }

  // row 2: the same backlog with embedding disabled -> IDLE
  @Test
  void disabledEmbeddingBacklogIsIdle() {
    OperatingIndexingView indexing = assemble(worker(embeddingBacklog(false)));

    assertEquals(IndexingState.IDLE, indexing.state(),
        "nothing will drain a disabled stage, so it never holds IDLE off");
  }

  private static EnrichmentProgressView embeddingBacklog(boolean embeddingEnabled) {
    return EnrichmentProgressViewBuilder.builder(EnrichmentProgressView.empty())
        .embeddingEnabled(embeddingEnabled)
        .embeddingDocCount(3_000L)
        .embeddingPendingCount(3_000L)
        .build();
  }

  private static WorkerOperationalView worker(EnrichmentProgressView enrichment) {
    return WorkerOperationalViewBuilder.builder(WorkerOperationalView.fallback("SERVING"))
        .core(new CoreIndexView(true, 3_000, 0, "SERVING", 0, 0))
        .enrichment(enrichment)
        .build();
  }

  private static OperatingIndexingView assemble(WorkerOperationalView worker) {
    var ready = new LifecycleSnapshotV2.Component(ComponentState.READY, null, OBSERVED_AT);
    var lifecycle =
        new LifecycleSnapshotV2(
            LifecycleSnapshotV2.SCHEMA_VERSION,
            OBSERVED_AT,
            new LifecycleSnapshotV2.Lifecycle(LifecycleState.LIFECYCLE_STATE_READY, null, null),
            new LifecycleSnapshotV2.Components(ready, ready, ready, ready));
    var inference =
        new InferenceRuntimeView(
            "ONLINE", null, false, null, new LifecycleCounters(0L, 0L, 0L), true, "Healthy", "",
            "", "");
    return new OperatingStateAssembler()
        .assemble(
            lifecycle,
            inference,
            new PowerStatusView(false, "AC"),
            worker,
            NOW.toEpochMilli(),
            false,
            2_000L,
            NOW,
            null)
        .indexing();
  }
}
