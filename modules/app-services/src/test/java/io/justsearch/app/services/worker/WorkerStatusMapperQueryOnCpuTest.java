/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.ipc.CoreStatus;
import io.justsearch.ipc.HealthCheckResponse;
import io.justsearch.ipc.StatusResponse;
import org.junit.jupiter.api.Test;

/**
 * Contract X2 of b6d536, the producer path of the query-on-CPU status: the worker health's time of
 * the last query leg the CPU recourse took reaches the Head's worker view; 0 (never) and a missing
 * health check map to null.
 */
final class WorkerStatusMapperQueryOnCpuTest {

  @Test
  void toUiStatusMapCarriesTheTimeTheCpuRecourseLastTookAQueryLeg() {
    StatusResponse status = StatusResponse.newBuilder().setCore(CoreStatus.newBuilder()).build();
    HealthCheckResponse onCpu = HealthCheckResponse.newBuilder()
        .setEmbeddingReady(true).setQueryCpuRecourseAtMs(1_800_000_000_000L).build();

    assertEquals(1_800_000_000_000L,
        WorkerStatusMapper.toUiStatusMap(status, onCpu).queryCpuRecourseAtMs());
    assertNull(WorkerStatusMapper.toUiStatusMap(status,
        HealthCheckResponse.newBuilder().setEmbeddingReady(true).build()).queryCpuRecourseAtMs());
    assertNull(WorkerStatusMapper.toUiStatusMap(status, null).queryCpuRecourseAtMs());
  }
}
