/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.app.api.status.EnrichmentProgressView;
import io.justsearch.ipc.EnrichmentCoverage;
import io.justsearch.ipc.StatusResponse;
import org.junit.jupiter.api.Test;

/** S33.2: preserve observed CPU/GPU placement and unknown across the process boundary. */
final class WorkerStatusMapperBackfillPlacementTest {
  @Test
  void mapsGpuCpuAndUnknownWithoutCollapsingAbsenceToCpu() {
    var field = EnrichmentCoverage.getDescriptor().findFieldByName("backfill_gpu_bound");
    assertNotNull(field, "S33.2 requires backfill placement on worker status");
    var accessor = assertDoesNotThrow(() -> EnrichmentProgressView.class.getMethod("backfillGpuBound"));
    for (Boolean placement : new Boolean[] {true, false, null}) {
      var enrichment = EnrichmentCoverage.newBuilder();
      if (placement != null) enrichment.setField(field, placement);
      var mapped = WorkerStatusMapper.toUiStatusMap(StatusResponse.newBuilder()
          .setEnrichment(enrichment).build()).enrichment();
      assertEquals(placement, assertDoesNotThrow(() -> accessor.invoke(mapped)));
    }
  }
}
