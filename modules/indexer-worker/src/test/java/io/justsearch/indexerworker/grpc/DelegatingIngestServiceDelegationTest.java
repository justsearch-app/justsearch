/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.grpc.stub.StreamObserver;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.IngestServiceGrpc;
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.ScanRootRequest;
import io.justsearch.ipc.SubscribeIndexingJobsRequest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

/**
 * Lane F stage A item A3 — proves every {@link DelegatingIngestService} forward reaches the right
 * {@link WorkerIngestService} method with the caller's own request, and that each of the two
 * server-streaming forwards uses the completion behaviour its RPC actually needs.
 *
 * <p>Why this exists: after the conversion the 38 forwards are near-identical three-line bodies. A
 * copy-paste error — RPC {@code a} calling {@code delegate.b(...)} — compiles wherever the two
 * share a request/response type (three RPCs share {@code UpgradeQuiescenceRequest} alone) and is
 * invisible to every other test in the repo. The same is true of swapping {@code
 * streamThenComplete} for {@code streamOpen} on the two streaming forwards, which in production
 * would truncate the Library SSE feed after one frame or hang {@code scanRoot} forever.
 *
 * <p>The delegate is a Mockito mock of the {@code final} {@code WorkerIngestService} — the inline
 * mock maker (Mockito 5 default) handles final classes — driven by a single default {@code Answer}
 * that records {@code (methodName, firstArgument)} and returns the response registered for that
 * name. One answer covers all 38 RPCs without 38 stubs, and recording the <b>service</b> method
 * name is what makes a mis-routed forward visible.
 */
@DisplayName("DelegatingIngestService — every forward routes to its own service method")
final class DelegatingIngestServiceDelegationTest {

  /**
   * The 36 unary RPCs, written out by hand on purpose: {@link #tableCoversEveryOverriddenRpc()}
   * checks this list against the methods the adapter actually overrides, so a new RPC added without
   * a row here fails loudly instead of being silently uncovered.
   */
  private static final List<String> UNARY_RPCS =
      List.of(
          "cancelIndexingJob",
          "cancelUpgrade",
          "clearFailedJobs",
          "countJobsByPathPrefix",
          "deleteByCollection",
          "deleteById",
          "deleteByPath",
          "getSessionPolicies",
          "indexStatus",
          "ingestionOutcomeSummary",
          "listFailedJobs",
          "listFailedJobsByPathPrefix",
          "lookupPathByHash",
          "markVduProcessing",
          "pauseMigration",
          "prepareUpgrade",
          "pruneMissing",
          "queryPendingVdu",
          "recentIngestionEvents",
          "recoverVduProcessing",
          "reloadRuntime",
          "requestCutover",
          "resetIndex",
          "resumeMigration",
          "retryIndexingJob",
          "rollbackMigration",
          "runIndexGc",
          "settleIndex",
          "startMigration",
          "submitBatch",
          "syncDirectory",
          "unwatchRoot",
          "updateDocumentPaths",
          "updateVduResult",
          "upgradeStatus",
          "watchRoot");

  /** The two server-streaming RPCs, asserted separately because their service shape differs. */
  private static final List<String> STREAMING_RPCS = List.of("scanRoot", "subscribeIndexingJobs");

  /** What the mock recorded: which service method was called, and with which request object. */
  private record ServiceCall(String method, Object firstArgument) {}

  static List<String> unaryRpcs() {
    return UNARY_RPCS;
  }

  // ==================== the 36 unary forwards ====================

  @ParameterizedTest(name = "{0} forwards to WorkerIngestService.{0}")
  @MethodSource("unaryRpcs")
  @DisplayName("a unary forward calls its own service method with the caller's request")
  void unaryForwardRoutesToItsOwnServiceMethod(String rpc) throws Exception {
    Method adapterMethod = adapterMethod(rpc);
    Object request = freshMessage(adapterMethod.getParameterTypes()[0]);
    Object response = freshMessage(responseType(adapterMethod));

    List<ServiceCall> calls = new ArrayList<>();
    DelegatingIngestService adapter =
        new DelegatingIngestService(recordingDelegate(calls, Map.of(rpc, response)));
    CapturingStreamObserver<Object> observer = new CapturingStreamObserver<>();

    invoke(adapterMethod, adapter, request, observer);

    assertEquals(
        List.of(rpc),
        calls.stream().map(ServiceCall::method).toList(),
        "the forward must call exactly one service method, and it must be " + rpc);
    assertSame(
        request,
        calls.get(0).firstArgument(),
        rpc + " must pass the caller's own request object through, unmodified");
    assertEquals(
        List.of(response), observer.values(), rpc + " must deliver the service response via onNext");
    assertSame(response, observer.values().get(0), "the response instance must not be re-created");
    assertEquals(1, observer.completedCount(), "a unary call completes exactly once");
    assertNull(observer.error(), rpc + " must not fail");
    assertEquals(List.of("onNext", "onCompleted"), observer.order());
  }

  // ==================== the two streaming forwards ====================

  @Test
  @DisplayName("scanRoot streams frames and COMPLETES the call when the service method returns")
  void scanRootCompletesTheStreamOnReturn() {
    ScanRootRequest request = ScanRootRequest.newBuilder().build();
    ScanRootProgress first = ScanRootProgress.newBuilder().setScanId("s").setFilesWalked(1).build();
    ScanRootProgress last =
        ScanRootProgress.newBuilder().setScanId("s").setFilesWalked(2).setComplete(true).build();

    List<ServiceCall> calls = new ArrayList<>();
    WorkerIngestService delegate =
        Mockito.mock(
            WorkerIngestService.class,
            invocation -> {
              String name = invocation.getMethod().getName();
              if (!"scanRoot".equals(name)) {
                return unrecordedDefault(name);
              }
              calls.add(new ServiceCall(name, invocation.getArgument(0)));
              Consumer<ScanRootProgress> sink = invocation.getArgument(1);
              sink.accept(first);
              sink.accept(last);
              return null;
            });
    DelegatingIngestService adapter = new DelegatingIngestService(delegate);
    CapturingStreamObserver<ScanRootProgress> observer = new CapturingStreamObserver<>();

    adapter.scanRoot(request, observer);

    assertEquals(List.of("scanRoot"), calls.stream().map(ServiceCall::method).toList());
    assertSame(request, calls.get(0).firstArgument(), "scanRoot must pass the caller's request");
    assertSame(first, observer.values().get(0), "frames must arrive through the sink, in order");
    assertSame(last, observer.values().get(1));
    assertEquals(
        1,
        observer.completedCount(),
        "scanRoot returns only once the walk is over, so the adapter must complete the stream "
            + "(streamThenComplete). Leaving it open hangs the caller.");
    assertNull(observer.error());
    assertEquals(List.of("onNext", "onNext", "onCompleted"), observer.order());
  }

  @Test
  @DisplayName("subscribeIndexingJobs LEAVES the call open, so post-return deltas still arrive")
  void subscribeIndexingJobsLeavesTheStreamOpenOnReturn() {
    SubscribeIndexingJobsRequest request = SubscribeIndexingJobsRequest.newBuilder().build();
    IndexingJobsFrame snapshot = IndexingJobsFrame.newBuilder().setSeq(1).build();
    IndexingJobsFrame delta = IndexingJobsFrame.newBuilder().setSeq(2).build();

    List<ServiceCall> calls = new ArrayList<>();
    AtomicReference<Consumer<IndexingJobsFrame>> feedSink = new AtomicReference<>();
    WorkerIngestService delegate =
        Mockito.mock(
            WorkerIngestService.class,
            invocation -> {
              String name = invocation.getMethod().getName();
              if (!"subscribeIndexingJobs".equals(name)) {
                return unrecordedDefault(name);
              }
              calls.add(new ServiceCall(name, invocation.getArgument(0)));
              Consumer<IndexingJobsFrame> sink = invocation.getArgument(1);
              // What the real service does: emit the snapshot, register the change feed, return
              // with the stream still live.
              sink.accept(snapshot);
              feedSink.set(sink);
              return null;
            });
    DelegatingIngestService adapter = new DelegatingIngestService(delegate);
    CapturingStreamObserver<IndexingJobsFrame> observer = new CapturingStreamObserver<>();

    adapter.subscribeIndexingJobs(request, observer);

    assertEquals(List.of("subscribeIndexingJobs"), calls.stream().map(ServiceCall::method).toList());
    assertSame(request, calls.get(0).firstArgument());
    assertSame(snapshot, observer.values().get(0));
    assertEquals(
        0,
        observer.completedCount(),
        "subscribeIndexingJobs returns with the change-feed subscription still live, so the "
            + "adapter must NOT complete the stream (streamOpen). Completing here truncates the "
            + "Library SSE fan-out after its first snapshot frame.");

    // The property the fan-out depends on: a delta emitted from the feed's own thread, after the
    // registering call returned, still reaches the wire.
    feedSink.get().accept(delta);

    assertEquals(List.of(snapshot, delta), observer.values());
    assertEquals(0, observer.completedCount(), "a live subscription is still not completed");
    assertNull(observer.error());
    assertEquals(List.of("onNext", "onNext"), observer.order());
  }

  // ==================== completeness ====================

  @Test
  @DisplayName("the delegation table covers every RPC the adapter overrides")
  void tableCoversEveryOverriddenRpc() {
    Set<String> covered = new TreeSet<>(UNARY_RPCS);
    covered.addAll(STREAMING_RPCS);
    assertEquals(
        UNARY_RPCS.size() + STREAMING_RPCS.size(),
        covered.size(),
        "the delegation table must not list an RPC twice");

    Set<String> overridden = rpcShapedMethodNames(DelegatingIngestService.class);
    assertEquals(
        overridden,
        covered,
        "every RPC DelegatingIngestService forwards needs a delegation-test row (and vice versa)");

    // And no proto RPC may be left on the generated UNIMPLEMENTED default. IngestServiceImplBase
    // declares only bindService(); the per-RPC default methods live on the AsyncService interface
    // it implements, which is therefore the generated enumeration of the service's RPCs.
    assertEquals(
        rpcShapedMethodNames(IngestServiceGrpc.AsyncService.class),
        overridden,
        "DelegatingIngestService must override every IngestService RPC");
  }

  // ==================== helpers ====================

  /**
   * A delegate whose single default {@code Answer} records the call and returns the pre-registered
   * response for that method name.
   */
  private static WorkerIngestService recordingDelegate(
      List<ServiceCall> calls, Map<String, Object> responses) {
    Set<String> rpcNames = rpcShapedMethodNames(DelegatingIngestService.class);
    return Mockito.mock(
        WorkerIngestService.class,
        invocation -> {
          String name = invocation.getMethod().getName();
          if (!rpcNames.contains(name)) {
            return unrecordedDefault(name);
          }
          calls.add(new ServiceCall(name, invocation.getArgument(0)));
          return responses.get(name);
        });
  }

  /** What the mock returns for a non-RPC method (Mockito routes {@code toString} to the answer). */
  private static Object unrecordedDefault(String methodName) {
    return "toString".equals(methodName) ? "recordingWorkerIngestServiceMock" : null;
  }

  /** The adapter's {@code (Request, StreamObserver)} method for an RPC name. */
  private static Method adapterMethod(String rpc) {
    return Arrays.stream(DelegatingIngestService.class.getDeclaredMethods())
        .filter(m -> m.getName().equals(rpc) && isRpcShaped(m))
        .findFirst()
        .orElseThrow(() -> new AssertionError("DelegatingIngestService has no RPC named " + rpc));
  }

  /** The {@code X} in the method's {@code StreamObserver<X>} parameter. */
  private static Class<?> responseType(Method adapterMethod) {
    ParameterizedType observer = (ParameterizedType) adapterMethod.getGenericParameterTypes()[1];
    return (Class<?>) observer.getActualTypeArguments()[0];
  }

  /**
   * A brand-new protobuf message of the given type. Distinct from {@code getDefaultInstance()} by
   * identity, which is what makes the {@code assertSame} checks above meaningful — the assertion
   * below stops that guarantee from silently degrading.
   */
  private static Object freshMessage(Class<?> messageType) throws Exception {
    Object builder = messageType.getMethod("newBuilder").invoke(null);
    Object message = builder.getClass().getMethod("build").invoke(builder);
    assertNotSame(
        messageType.getMethod("getDefaultInstance").invoke(null),
        message,
        messageType.getSimpleName() + ": newBuilder().build() must not return the shared singleton");
    return message;
  }

  /** Invokes an adapter method, unwrapping the reflection wrapper so failures read normally. */
  private static void invoke(Method method, Object target, Object... args) throws Exception {
    try {
      method.invoke(target, args);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Exception cause) {
        throw cause;
      }
      throw new AssertionError(e.getCause());
    }
  }

  /** Public {@code void name(Request, StreamObserver)} methods a class declares — i.e. its RPCs. */
  private static Set<String> rpcShapedMethodNames(Class<?> type) {
    Set<String> names =
        Arrays.stream(type.getDeclaredMethods())
            .filter(DelegatingIngestServiceDelegationTest::isRpcShaped)
            .map(Method::getName)
            .collect(Collectors.toCollection(TreeSet::new));
    assertFalse(names.isEmpty(), type.getSimpleName() + " declares no RPC-shaped methods");
    return names;
  }

  private static boolean isRpcShaped(Method m) {
    return Modifier.isPublic(m.getModifiers())
        && !m.isSynthetic()
        && !m.isBridge()
        && m.getReturnType() == void.class
        && m.getParameterCount() == 2
        && m.getParameterTypes()[1] == StreamObserver.class;
  }
}
