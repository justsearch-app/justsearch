/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * The unary HealthService calls, as a plain Java interface (lane F stage A item A6).
 *
 * <p>One call: the worker liveness/version probe.
 *
 * <p><b>Why this interface exists.</b> Until A6 the executor seam
 * ({@link SearchRpcExecutor}, {@link IngestRpcExecutor}) was typed on the <em>generated gRPC
 * blocking stub</em>, a final-ish class that only a {@code Channel} can produce. That made the
 * whole ops layer ({@code SearchRpcOps}, {@code MigrationOps}, {@code VduOps}, {@code SyncOps},
 * {@code RootLifecycleOps}) reachable only over the wire, even though none of its logic is about
 * a network. This interface is the same call surface with the same method names and signatures,
 * so every existing {@code stub -> stub.foo(request)} lambda compiles verbatim; what changes is
 * that a second implementation is now possible — the in-process one in
 * {@code io.justsearch.app.engine}, which calls the converted worker service directly.
 *
 * <p>Proto DTOs at the signature are transitional (design §6): without a wire they carry its cost
 * and none of its benefit, and a named follow-up replaces them with the {@code app-api} records.
 */
public interface HealthServiceCalls {

  /** {@code HealthService/Check}. */
  io.justsearch.ipc.HealthCheckResponse check(io.justsearch.ipc.HealthCheckRequest request);
}
