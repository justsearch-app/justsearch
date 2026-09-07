/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.services.worker.HealthServiceCalls;
import io.justsearch.indexerworker.services.WorkerHealthService;

/**
 * Binds {@link HealthServiceCalls} to the converted {@link WorkerHealthService} for one call (lane F stage A item A6).
 *
 * <p>This is the whole of "the ports as direct calls": each method is the same request the wire
 * carried, handed straight to the worker service in this JVM. The health check reads no call-scoped
 * facts, so it takes no context.
 */
final class WorkerHealthCalls implements HealthServiceCalls {

  private final WorkerHealthService service;

  WorkerHealthCalls(WorkerHealthService service) {
    this.service = service;
  }

  @Override
  public io.justsearch.ipc.HealthCheckResponse check(io.justsearch.ipc.HealthCheckRequest request) {
    return service.check(request);
  }
}
