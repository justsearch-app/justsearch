/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.ipc.CoreStatus;
import io.justsearch.ipc.EnrichmentCoverage;
import io.justsearch.ipc.StatusResponse;
import org.junit.jupiter.api.Test;

/**
 * Spec E steps E0b and E1: the joint S5 counter and the per-stage availability travel from the
 * Worker status to the status view the UI and the tools read (S20), field for field.
 */
final class WorkerStatusMapperStageAvailabilityTest {

  // embeddedAndSparseCount and the availability booleans map without crossing
  @Test
  void mapsFieldForField() {
    StatusResponse status =
        StatusResponse.newBuilder()
            .setCore(CoreStatus.newBuilder().build())
            .setEnrichment(
                EnrichmentCoverage.newBuilder()
                    .setEmbeddedAndSparseCount(1_234L)
                    .setEmbeddingAvailable(true)
                    .setSpladeAvailable(false)
                    .setNerAvailable(true)
                    .build())
            .build();

    WorkerOperationalView out = WorkerStatusMapper.toUiStatusMap(status, null);

    assertEquals(1_234L, out.enrichment().embeddedAndSparseCount());
    assertTrue(out.enrichment().embeddingAvailable());
    assertFalse(out.enrichment().spladeAvailable());
    assertTrue(out.enrichment().nerAvailable());

    StatusResponse other =
        status.toBuilder()
            .setEnrichment(
                status.getEnrichment().toBuilder()
                    .setEmbeddingAvailable(false)
                    .setSpladeAvailable(true)
                    .setNerAvailable(false))
            .build();
    WorkerOperationalView flipped = WorkerStatusMapper.toUiStatusMap(other, null);
    assertFalse(flipped.enrichment().embeddingAvailable());
    assertTrue(flipped.enrichment().spladeAvailable());
    assertFalse(flipped.enrichment().nerAvailable());
  }
}
