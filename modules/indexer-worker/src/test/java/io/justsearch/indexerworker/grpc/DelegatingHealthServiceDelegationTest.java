/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.justsearch.indexerworker.services.WorkerHealthService;
import io.justsearch.indexerworker.services.WorkerServiceException;
import io.justsearch.ipc.HealthCheckRequest;
import io.justsearch.ipc.HealthCheckResponse;
import io.justsearch.ipc.HealthServiceGrpc;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The {@link DelegatingIngestServiceDelegationTest} counterpart for the health wire: one RPC, same
 * contract.
 *
 * <p>Kept as its own file rather than a nested class because it mocks a different service type
 * ({@link WorkerHealthService}, whose {@code check} takes no {@code CallContext}) and enumerates a
 * different {@code ImplBase}; folding it into the ingest file would mean two unrelated mocks and
 * two completeness sets in one class.
 */
@DisplayName("DelegatingHealthService — the single forward routes to WorkerHealthService.check")
final class DelegatingHealthServiceDelegationTest {

  /** The health RPCs covered below; checked against the adapter's overrides by the last test. */
  private static final List<String> RPCS = List.of("check");

  private record ServiceCall(String method, Object firstArgument) {}

  @Test
  @DisplayName("check calls WorkerHealthService.check with the caller's request")
  void checkForwardsTheCallersRequest() {
    HealthCheckRequest request = HealthCheckRequest.newBuilder().build();
    HealthCheckResponse response =
        HealthCheckResponse.newBuilder().setServing(true).setVersion("test").build();
    assertNotSame(
        HealthCheckRequest.getDefaultInstance(),
        request,
        "the identity assertion below needs a request distinct from the shared singleton");

    List<ServiceCall> calls = new ArrayList<>();
    DelegatingHealthService adapter =
        new DelegatingHealthService(recordingDelegate(calls, response));
    CapturingStreamObserver<HealthCheckResponse> observer = new CapturingStreamObserver<>();

    adapter.check(request, observer);

    assertEquals(List.of("check"), calls.stream().map(ServiceCall::method).toList());
    assertSame(request, calls.get(0).firstArgument(), "the caller's own request must be passed");
    assertSame(response, observer.values().get(0), "the service response must reach onNext");
    assertEquals(1, observer.completedCount(), "a unary call completes exactly once");
    assertNull(observer.error());
    assertEquals(List.of("onNext", "onCompleted"), observer.order());
  }

  @Test
  @DisplayName("a thrown WorkerServiceException reaches onError with the mapped status")
  void checkMapsAThrownFailureOntoTheWire() {
    WorkerHealthService delegate =
        Mockito.mock(
            WorkerHealthService.class,
            invocation -> {
              if ("check".equals(invocation.getMethod().getName())) {
                throw WorkerServiceException.internal("health probe blew up");
              }
              return "toString".equals(invocation.getMethod().getName()) ? "healthMock" : null;
            });
    CapturingStreamObserver<HealthCheckResponse> observer = new CapturingStreamObserver<>();

    new DelegatingHealthService(delegate).check(HealthCheckRequest.newBuilder().build(), observer);

    assertEquals(List.of(), observer.values(), "a failed call delivers no response");
    assertEquals(0, observer.completedCount(), "a failed call must not complete the stream");
    assertInstanceOf(io.grpc.StatusRuntimeException.class, observer.error());
    assertEquals(Status.Code.INTERNAL, Status.fromThrowable(observer.error()).getCode());
    assertEquals(
        "health probe blew up", Status.fromThrowable(observer.error()).getDescription());
  }

  @Test
  @DisplayName("the delegation table covers every RPC the adapter overrides")
  void tableCoversEveryOverriddenRpc() {
    Set<String> overridden = rpcShapedMethodNames(DelegatingHealthService.class);
    assertEquals(
        overridden,
        new TreeSet<>(RPCS),
        "every RPC DelegatingHealthService forwards needs a delegation-test row");
    // HealthServiceImplBase declares only bindService(); the per-RPC default methods live on the
    // AsyncService interface it implements, which is the generated enumeration of the RPCs.
    assertEquals(
        rpcShapedMethodNames(HealthServiceGrpc.AsyncService.class),
        overridden,
        "DelegatingHealthService must override every HealthService RPC");
  }

  private static WorkerHealthService recordingDelegate(
      List<ServiceCall> calls, HealthCheckResponse response) {
    return Mockito.mock(
        WorkerHealthService.class,
        invocation -> {
          String name = invocation.getMethod().getName();
          if (!"check".equals(name)) {
            return "toString".equals(name) ? "recordingWorkerHealthServiceMock" : null;
          }
          calls.add(new ServiceCall(name, invocation.getArgument(0)));
          return response;
        });
  }

  private static Set<String> rpcShapedMethodNames(Class<?> type) {
    return Arrays.stream(type.getDeclaredMethods())
        .filter(
            m ->
                Modifier.isPublic(m.getModifiers())
                    && !m.isSynthetic()
                    && !m.isBridge()
                    && m.getReturnType() == void.class
                    && m.getParameterCount() == 2
                    && m.getParameterTypes()[1] == StreamObserver.class)
        .map(Method::getName)
        .collect(Collectors.toCollection(TreeSet::new));
  }
}
