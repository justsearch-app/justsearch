/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.grpc;

import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerServiceException;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.util.function.Supplier;

/**
 * The bridge between the gRPC wire and the converted worker services.
 *
 * <p>Lane F stage A item A3: the three worker services now take plain request objects and throw
 * {@link WorkerServiceException}. The {@code Delegating*Service} wrappers stay on the generated
 * {@code ImplBase} and use the helpers here to translate — so every existing gRPC integration
 * test and the Head's {@code RemoteKnowledgeClient} observe exactly the statuses they observed
 * before the conversion. Deleted at item A9 with the rest of the wire.
 */
public final class WorkerServiceCalls {

  private WorkerServiceCalls() {}

  /**
   * Builds the call context the converted services used to read out of {@code io.grpc.Context}
   * statics: the propagated trace id, the request id, and the call's cancellation signal.
   *
   * <p>Called on the gRPC handler thread, which is where the two interceptors' context is
   * current — the same thread and the same values {@code openRequestMdc()} read in place.
   */
  public static CallContext callContext(StreamObserver<?> responseObserver) {
    SpanContext spanCtx =
        Span.fromContext(TracingServerInterceptor.currentOtelContext()).getSpanContext();
    String traceId = spanCtx.isValid() ? spanCtx.getTraceId() : null;
    String requestId = RequestMetadataInterceptor.currentRequestId();
    if (responseObserver instanceof ServerCallStreamObserver<?> sco) {
      return new CallContext(traceId, requestId, new ServerCallCancelSignal(sco));
    }
    return CallContext.of(traceId, requestId);
  }

  /** Maps the re-homed failure vocabulary back onto the wire's status codes, 1:1. */
  public static Status toStatus(WorkerServiceException e) {
    return switch (e.status()) {
      case INVALID_ARGUMENT -> Status.INVALID_ARGUMENT;
      case FAILED_PRECONDITION -> Status.FAILED_PRECONDITION;
      case ABORTED -> Status.ABORTED;
      case RESOURCE_EXHAUSTED -> Status.RESOURCE_EXHAUSTED;
      case UNAVAILABLE -> Status.UNAVAILABLE;
      case UNIMPLEMENTED -> Status.UNIMPLEMENTED;
      // Lane F item A6 gave these two a producer (the in-process deadline and CancelToken). They
      // are unreachable over the wire, where gRPC raises the same codes itself, but the mapping is
      // exhaustive on purpose: an unmapped member would fall to INTERNAL and re-label the failure.
      case DEADLINE_EXCEEDED -> Status.DEADLINE_EXCEEDED;
      case CANCELLED -> Status.CANCELLED;
      case INTERNAL -> Status.INTERNAL;
    };
  }

  /**
   * Runs a converted unary service method and completes the observer with its result, or fails
   * the call with the mapped status.
   */
  public static <T> void unary(StreamObserver<T> responseObserver, Supplier<T> call) {
    T response;
    try {
      response = call.get();
    } catch (WorkerServiceException e) {
      responseObserver.onError(toStatus(e).withDescription(e.getMessage()).asRuntimeException());
      return;
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  /**
   * Runs a converted server-streaming service method that ends when it returns (the service has
   * emitted every frame it will emit), then completes the stream.
   */
  public static void streamThenComplete(StreamObserver<?> responseObserver, Runnable call) {
    try {
      call.run();
    } catch (WorkerServiceException e) {
      responseObserver.onError(toStatus(e).withDescription(e.getMessage()).asRuntimeException());
      return;
    }
    responseObserver.onCompleted();
  }

  /**
   * Runs a converted server-streaming service method that stays open after it returns — the
   * service has registered a subscription and further frames arrive from other threads, so the
   * stream is completed by cancellation, not by this call.
   */
  public static void streamOpen(StreamObserver<?> responseObserver, Runnable call) {
    try {
      call.run();
    } catch (WorkerServiceException e) {
      responseObserver.onError(toStatus(e).withDescription(e.getMessage()).asRuntimeException());
    }
  }

  /** The cancellation signal a server call exposes: a poll plus a one-shot registration. */
  private record ServerCallCancelSignal(ServerCallStreamObserver<?> observer)
      implements CallContext.CancelSignal {

    @Override
    public boolean isCancelled() {
      return observer.isCancelled();
    }

    @Override
    public void onCancel(Runnable handler) {
      observer.setOnCancelHandler(handler);
    }
  }
}
