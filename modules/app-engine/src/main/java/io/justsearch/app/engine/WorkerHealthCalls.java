/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.services.worker.HealthServiceCalls;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerHealthService;

/**
 * Binds {@link HealthServiceCalls} to the converted {@link WorkerHealthService} for one call (lane F stage A item A6).
 *
 * <p>This is the whole of "the ports as direct calls": each method is the same request the wire
 * carried, handed straight to the worker service in this JVM.
 *
 * <p>The health check reads no call-scoped facts <em>today</em>, so {@code ctx} is accepted and not
 * forwarded — but it is accepted, because review B3 found this was the one call the client made
 * with no context at all, and a call that carries no cancellation signal is a call its budget
 * cannot bound. The moment {@code WorkerHealthService.check} takes a context, this passes it.
 */
final class WorkerHealthCalls implements HealthServiceCalls {

  private final WorkerHealthService service;

  @SuppressWarnings("unused") // see the class javadoc: accepted now, forwarded when check() takes one
  private final CallContext ctx;

  WorkerHealthCalls(WorkerHealthService service, CallContext ctx) {
    this.service = service;
    this.ctx = ctx;
  }

  @Override
  public io.justsearch.ipc.HealthCheckResponse check(io.justsearch.ipc.HealthCheckRequest request) {
    return service.check(request);
  }
}
