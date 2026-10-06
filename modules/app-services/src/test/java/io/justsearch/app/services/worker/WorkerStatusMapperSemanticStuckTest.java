/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.ipc.CoreStatus;
import io.justsearch.ipc.HealthCheckResponse;
import io.justsearch.ipc.StatusResponse;
import org.junit.jupiter.api.Test;

/**
 * Spec D §6.3, the producer path of K3: the worker health's breaker open-since time reaches the
 * Head's worker view; 0 (every breaker closed) and a missing health check map to null.
 */
final class WorkerStatusMapperSemanticStuckTest {

  @Test
  void toUiStatusMapCarriesTheBreakerOpenSinceTime() {
    StatusResponse status = StatusResponse.newBuilder().setCore(CoreStatus.newBuilder()).build();
    HealthCheckResponse stuck = HealthCheckResponse.newBuilder()
        .setEmbeddingReady(true).setSemanticStuckSinceMs(1_800_000_000_000L).build();

    assertEquals(1_800_000_000_000L,
        WorkerStatusMapper.toUiStatusMap(status, stuck).semanticStuckSinceMs());
    assertNull(WorkerStatusMapper.toUiStatusMap(status,
        HealthCheckResponse.newBuilder().setEmbeddingReady(true).build()).semanticStuckSinceMs());
    assertNull(WorkerStatusMapper.toUiStatusMap(status, null).semanticStuckSinceMs());
  }
}
