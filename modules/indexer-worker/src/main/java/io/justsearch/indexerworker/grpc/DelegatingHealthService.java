/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.grpc;

import io.grpc.stub.StreamObserver;
import io.justsearch.indexerworker.services.WorkerHealthService;
import io.justsearch.ipc.HealthCheckRequest;
import io.justsearch.ipc.HealthCheckResponse;
import io.justsearch.ipc.HealthServiceGrpc;
import java.util.Objects;

/**
 * gRPC adapter for {@link WorkerHealthService}, and the seam that enables runtime service
 * swapping.
 *
 * <p>Registered once with the gRPC server. The single RPC call is forwarded to a {@code volatile}
 * delegate that can be swapped without restarting the gRPC server (hot reload, tempdoc 305).
 *
 * <p>Lane F stage A item A3: the delegate is the converted service, which returns its response
 * instead of writing to a {@code StreamObserver}; this wrapper does the {@code onNext} /
 * {@code onCompleted} and the failure mapping, so the wire behaviour is unchanged. Deleted at
 * item A9.
 */
public final class DelegatingHealthService extends HealthServiceGrpc.HealthServiceImplBase {

  private volatile WorkerHealthService delegate;

  public DelegatingHealthService(WorkerHealthService delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  public void setDelegate(WorkerHealthService delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  @Override
  public void check(HealthCheckRequest req, StreamObserver<HealthCheckResponse> obs) {
    WorkerServiceCalls.unary(obs, () -> delegate.check(req));
  }
}
