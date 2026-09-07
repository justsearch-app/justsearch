/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Status;
import io.justsearch.indexerworker.services.WorkerServiceException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 *
 * <p>The nested classes pin the second contract of this file: the two server-streaming helpers are
 * <b>not</b> interchangeable. {@code streamThenComplete} ends the call; {@code streamOpen} leaves it
 * open for frames that arrive after the service method has returned. Using the wrong one at a call
 * site is a silent, compiling mistake — it would either truncate the indexing-jobs SSE feed after
 * its first frame or leave {@code scanRoot} hanging forever. These tests pin the helpers'
 * semantics; {@code DelegatingIngestServiceDelegationTest} pins which helper each RPC uses.
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
    CapturingStreamObserver<String> observer = new CapturingStreamObserver<>();

    WorkerServiceCalls.unary(
        observer,
        () -> {
          throw new WorkerServiceException(
              WorkerServiceException.Status.valueOf(workerStatus), "msg");
        });

    assertTrue(observer.values().isEmpty(), "a failed call must not deliver a response");
    assertEquals(0, observer.completedCount(), "a failed call must not complete the stream");
    Throwable error = observer.error();
    assertInstanceOf(io.grpc.StatusRuntimeException.class, error);
    Status status = Status.fromThrowable(error);
    assertEquals(Status.Code.valueOf(grpcCode), status.getCode());
    assertEquals("msg", status.getDescription());
  }

  @Test
  @DisplayName("a normal return delivers onNext then onCompleted, and no error")
  void normalReturnDeliversNextThenCompleted() {
    CapturingStreamObserver<String> observer = new CapturingStreamObserver<>();

    WorkerServiceCalls.unary(observer, () -> "answer");

    assertEquals(List.of("answer"), observer.values());
    assertEquals(1, observer.completedCount());
    assertNull(observer.error());
    assertEquals(List.of("onNext", "onCompleted"), observer.order());
  }

  /**
   * {@code streamThenComplete} is for a streaming method that has emitted every frame it will ever
   * emit by the time it returns ({@code scanRoot}). Returning therefore means "done", and the
   * helper must end the call.
   */
  @Nested
  @DisplayName("streamThenComplete — the service method's return ENDS the call")
  final class StreamThenComplete {

    @Test
    @DisplayName("frames arrive in order, then onCompleted exactly once")
    void deliversFramesInOrderThenCompletesExactlyOnce() {
      CapturingStreamObserver<String> observer = new CapturingStreamObserver<>();

      WorkerServiceCalls.streamThenComplete(
          observer,
          () -> {
            observer.onNext("first");
            observer.onNext("second");
          });

      assertEquals(List.of("first", "second"), observer.values(), "frames must arrive in order");
      assertEquals(
          1,
          observer.completedCount(),
          "streamThenComplete must complete the call exactly once when the body returns");
      assertNull(observer.error());
      assertEquals(List.of("onNext", "onNext", "onCompleted"), observer.order());
    }

    @Test
    @DisplayName("a thrown WorkerServiceException maps to onError, and the call never completes")
    void failureMapsToOnErrorAndNeverCompletes() {
      CapturingStreamObserver<String> observer = new CapturingStreamObserver<>();

      WorkerServiceCalls.streamThenComplete(
          observer,
          () -> {
            observer.onNext("emitted-before-the-failure");
            throw WorkerServiceException.aborted("scan aborted");
          });

      assertEquals(
          List.of("emitted-before-the-failure"),
          observer.values(),
          "no frame may be delivered after the failure");
      assertEquals(
          0, observer.completedCount(), "a failed stream must not also be completed normally");
      Throwable error = observer.error();
      assertInstanceOf(io.grpc.StatusRuntimeException.class, error);
      assertEquals(Status.Code.ABORTED, Status.fromThrowable(error).getCode());
      assertEquals("scan aborted", Status.fromThrowable(error).getDescription());
      assertEquals(List.of("onNext", "onError"), observer.order());
    }
  }

  /**
   * {@code streamOpen} is for a streaming method that registers a subscription and returns with the
   * stream still live ({@code subscribeIndexingJobs}). Completing on return would kill the Library
   * SSE fan-out after its first snapshot frame, so the helper must leave the call open.
   */
  @Nested
  @DisplayName("streamOpen — the service method's return LEAVES the call open")
  final class StreamOpen {

    @Test
    @DisplayName("frames arrive, the call is never completed, and later frames still reach onNext")
    void leavesTheStreamOpenForFramesEmittedAfterTheCallReturns() {
      CapturingStreamObserver<String> observer = new CapturingStreamObserver<>();

      WorkerServiceCalls.streamOpen(observer, () -> observer.onNext("snapshot"));

      assertEquals(List.of("snapshot"), observer.values());
      assertEquals(
          0,
          observer.completedCount(),
          "streamOpen must NOT complete the call — the subscription is still live");
      assertNull(observer.error());

      // The property the SSE fan-out depends on: the feed's own thread pushes a delta after the
      // registering call has returned, and it must still reach the wire.
      observer.onNext("delta-after-return");

      assertEquals(List.of("snapshot", "delta-after-return"), observer.values());
      assertEquals(0, observer.completedCount(), "a live subscription is still not completed");
      assertEquals(List.of("onNext", "onNext"), observer.order());
    }

    @Test
    @DisplayName("a thrown WorkerServiceException maps to onError, and the call never completes")
    void failureMapsToOnErrorAndNeverCompletes() {
      CapturingStreamObserver<String> observer = new CapturingStreamObserver<>();

      WorkerServiceCalls.streamOpen(
          observer,
          () -> {
            throw WorkerServiceException.unimplemented("job queue has no change feed");
          });

      assertTrue(observer.values().isEmpty(), "a failed subscription delivers no frame");
      assertEquals(0, observer.completedCount(), "a failed stream must not be completed normally");
      Throwable error = observer.error();
      assertInstanceOf(io.grpc.StatusRuntimeException.class, error);
      assertEquals(Status.Code.UNIMPLEMENTED, Status.fromThrowable(error).getCode());
      assertEquals(
          "job queue has no change feed", Status.fromThrowable(error).getDescription());
      assertEquals(List.of("onError"), observer.order());
    }
  }
}
