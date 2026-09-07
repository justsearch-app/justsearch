/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.justsearch.indexerworker.services.WorkerServiceException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Lane F stage A item A3 — the regression test that proves the wire behaviour survived the
 * conversion.
 *
 * <p>The converted worker services no longer hand a {@code io.grpc.Status...asException()} to a
 * {@code StreamObserver}; they throw {@link WorkerServiceException}. Everything a caller across the
 * still-live wire observes now depends on {@link WorkerServiceCalls} mapping that failure back onto
 * the identical status code and description. Every enum constant is covered here, not just the
 * three the search service happens to emit, because the mapping is the contract — an unmapped
 * constant added later must fail here, not in production.
 */
@DisplayName("WorkerServiceCalls — status mapping across the still-live wire")
final class WorkerServiceCallsTest {

  @ParameterizedTest(name = "{0} maps to io.grpc.Status.{1}")
  @CsvSource({
    "INVALID_ARGUMENT, INVALID_ARGUMENT",
    "FAILED_PRECONDITION, FAILED_PRECONDITION",
    "ABORTED, ABORTED",
    "RESOURCE_EXHAUSTED, RESOURCE_EXHAUSTED",
    "UNAVAILABLE, UNAVAILABLE",
    "UNIMPLEMENTED, UNIMPLEMENTED",
    "INTERNAL, INTERNAL"
  })
  @DisplayName("a thrown WorkerServiceException reaches onError with the same code and description")
  void mapsEveryStatusOntoTheWire(String workerStatus, String grpcCode) {
    CapturingObserver<String> observer = new CapturingObserver<>();

    WorkerServiceCalls.unary(
        observer,
        () -> {
          throw new WorkerServiceException(
              WorkerServiceException.Status.valueOf(workerStatus), "msg");
        });

    assertTrue(observer.values.isEmpty(), "a failed call must not deliver a response");
    assertEquals(0, observer.completedCount, "a failed call must not complete the stream");
    Throwable error = observer.error;
    assertInstanceOf(io.grpc.StatusRuntimeException.class, error);
    Status status = Status.fromThrowable(error);
    assertEquals(Status.Code.valueOf(grpcCode), status.getCode());
    assertEquals("msg", status.getDescription());
  }

  @Test
  @DisplayName("a normal return delivers onNext then onCompleted, and no error")
  void normalReturnDeliversNextThenCompleted() {
    CapturingObserver<String> observer = new CapturingObserver<>();

    WorkerServiceCalls.unary(observer, () -> "answer");

    assertEquals(List.of("answer"), observer.values);
    assertEquals(1, observer.completedCount);
    assertNull(observer.error);
    assertEquals(List.of("onNext", "onCompleted"), observer.order);
  }

  /** Records what the adapter delivered, and in what order. */
  private static final class CapturingObserver<T> implements StreamObserver<T> {
    private final List<T> values = new ArrayList<>();
    private final List<String> order = new ArrayList<>();
    private Throwable error;
    private int completedCount;

    @Override
    public void onNext(T value) {
      values.add(value);
      order.add("onNext");
    }

    @Override
    public void onError(Throwable t) {
      error = t;
      order.add("onError");
    }

    @Override
    public void onCompleted() {
      completedCount++;
      order.add("onCompleted");
    }
  }
}
