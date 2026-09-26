/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.api.WorkerQuiescenceSnapshot;
import io.justsearch.app.services.lease.OperationLeaseServiceImpl;
import io.justsearch.app.services.worker.KnowledgeClient;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

final class UpgradeControllerTransactionTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void bridgeFailsClosedBeforeItsActionIsBound() {
    var bridge = new UpgradeShutdownBridge();
    assertThrows(IllegalStateException.class, bridge::boundAction);
    assertThrows(IllegalStateException.class, () -> bridge.shutdown("prep", "nonce"));
  }

  @Test
  void preparationReservationStartsBeforeLeaseCancellationSnapshotReturns() throws Exception {
    var leases = spy(new OperationLeaseServiceImpl());
    var cancellationCalls = new AtomicInteger();
    var cancellationSnapshotReady = new CountDownLatch(1);
    var releaseCancellationSnapshot = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              var snapshot = (io.justsearch.app.api.OperationLeaseSnapshot) invocation.callRealMethod();
              if (cancellationCalls.incrementAndGet() == 2) {
                cancellationSnapshotReady.countDown();
                assertTrue(releaseCancellationSnapshot.await(2, TimeUnit.SECONDS));
              }
              return snapshot;
            })
        .when(leases)
        .requestCancellation(anyString());
    var controller = new UpgradeController(leases, () -> {});
    Map<String, Object> capability = prepare(controller);

    var repeatedBody = new AtomicReference<Map<String, Object>>();
    Thread repeatedThread =
        Thread.ofPlatform().start(() -> controller.prepare(jsonContext(repeatedBody)));
    assertTrue(cancellationSnapshotReady.await(2, TimeUnit.SECONDS));

    Context competingPrepare = conflictContext();
    controller.prepare(competingPrepare);
    verify(competingPrepare).status(409);
    Context competingCancel = requestContext(capability);
    controller.cancel(competingCancel);
    verify(competingCancel).status(409);
    Context competingCommit = requestContext(capability);
    controller.commitShutdown(competingCommit);
    verify(competingCommit).status(409);

    releaseCancellationSnapshot.countDown();
    repeatedThread.join(2_000L);
    assertFalse(repeatedThread.isAlive());
    assertEquals(capability.get("preparationId"), repeatedBody.get().get("preparationId"));
    assertEquals(capability.get("shutdownNonce"), repeatedBody.get().get("shutdownNonce"));
    assertEquals(capability.get("preparationId"), leases.snapshot().preparationId());

    controller.cancel(requestContext(capability));
    assertFalse(leases.snapshot().admissionFrozen());
  }

  @Test
  void preparationReservationSpansWorkerAndPreservesRepeatedCapability() throws Exception {
    var leases = new OperationLeaseServiceImpl();
    KnowledgeClient worker = mock(KnowledgeClient.class);
    var prepareCalls = new AtomicInteger();
    var workerEntered = new CountDownLatch(1);
    var releaseWorker = new CountDownLatch(1);
    when(worker.prepareUpgrade(any(String.class), any(io.justsearch.core.context.EngineContext.class)))
        .thenAnswer(
            invocation -> {
              String preparationId = invocation.getArgument(0);
              if (prepareCalls.incrementAndGet() == 2) {
                workerEntered.countDown();
                try {
                  assertTrue(releaseWorker.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError("worker release was interrupted", e);
                }
              }
              return readyWorker(preparationId);
            });
    when(worker.upgradeStatus(any(String.class), any(io.justsearch.core.context.EngineContext.class)))
        .thenAnswer(invocation -> readyWorker(invocation.getArgument(0)));
    when(worker.cancelUpgrade(any(String.class), any(io.justsearch.core.context.EngineContext.class)))
        .thenAnswer(invocation -> readyWorker(invocation.getArgument(0)));
    var controller = new UpgradeController(leases, (preparationId, nonce) -> {}, () -> worker);
    Map<String, Object> capability = prepare(controller);

    var repeatedBody = new AtomicReference<Map<String, Object>>();
    Context repeated = jsonContext(repeatedBody);
    Thread repeatedThread = Thread.ofPlatform().start(() -> controller.prepare(repeated));
    assertTrue(workerEntered.await(2, TimeUnit.SECONDS));

    Context competingPrepare = conflictContext();
    controller.prepare(competingPrepare);
    verify(competingPrepare).status(409);

    Context competingCancel = requestContext(capability);
    controller.cancel(competingCancel);
    verify(competingCancel).status(409);

    Context competingCommit = requestContext(capability);
    controller.commitShutdown(competingCommit);
    verify(competingCommit).status(409);
    assertTrue(leases.snapshot().admissionFrozen());

    releaseWorker.countDown();
    repeatedThread.join(2_000L);
    assertFalse(repeatedThread.isAlive());
    assertEquals(capability.get("preparationId"), repeatedBody.get().get("preparationId"));
    assertEquals(capability.get("shutdownNonce"), repeatedBody.get().get("shutdownNonce"));
    assertEquals(capability.get("preparationId"), leases.snapshot().preparationId());

    Context cancel = requestContext(capability);
    controller.cancel(cancel);
    assertFalse(leases.snapshot().admissionFrozen());
  }

  @Test
  void failedPreparationResponseReleasesReservation() {
    var leases = new OperationLeaseServiceImpl();
    var controller = new UpgradeController(leases, () -> {});
    Context failed = mock(Context.class);
    when(failed.path()).thenReturn("/api/upgrade/prepare");
    when(failed.json(any())).thenThrow(new IllegalStateException("response failed"));

    assertThrows(IllegalStateException.class, () -> controller.prepare(failed));
    Map<String, Object> capability = prepare(controller);
    assertEquals(capability.get("preparationId"), leases.snapshot().preparationId());

    controller.cancel(requestContext(capability));
    assertFalse(leases.snapshot().admissionFrozen());
  }

  @Test
  void localDispatchFollowsSuccessfulFlushExactlyOnce() throws Exception {
    var leases = new OperationLeaseServiceImpl();
    var bridge = new UpgradeShutdownBridge();
    var calls = new AtomicInteger();
    var dispatched = new CountDownLatch(1);
    var flushed = new AtomicBoolean();
    var dispatchedAfterFlush = new AtomicBoolean();
    var dispatchedCapability = new AtomicReference<List<String>>();
    bridge.install((preparationId, nonce) -> {
      calls.incrementAndGet();
      dispatchedAfterFlush.set(flushed.get());
      dispatchedCapability.set(List.of(preparationId, nonce));
      dispatched.countDown();
    });
    var controller = new UpgradeController(leases, bridge, null);
    Map<String, Object> capability = prepare(controller);
    var flushEntered = new CountDownLatch(1);
    var releaseFlush = new CountDownLatch(1);
    Context commit = commitContext(capability, flushEntered, releaseFlush, flushed, false);
    Thread commitThread = Thread.ofPlatform().start(() -> controller.commitShutdown(commit));
    try {
      assertTrue(flushEntered.await(2, TimeUnit.SECONDS));
      assertEquals(0, calls.get());
      Context competingCommit = requestContext(capability);
      controller.commitShutdown(competingCommit);
      verify(competingCommit).status(409);
      Context competingCancel = requestContext(capability);
      controller.cancel(competingCancel);
      verify(competingCancel).status(409);
    } finally {
      releaseFlush.countDown();
    }
    commitThread.join(2_000L);
    assertFalse(commitThread.isAlive());
    assertTrue(dispatched.await(2, TimeUnit.SECONDS));
    assertTrue(dispatchedAfterFlush.get());
    assertEquals(List.of(capability.get("preparationId"), capability.get("shutdownNonce")),
        dispatchedCapability.get());
    Context duplicate = requestContext(capability);
    controller.commitShutdown(duplicate);
    verify(duplicate).status(409);
    assertEquals(1, calls.get());
  }

  @Test
  void blockedLocalCloseDoesNotHoldTheControllerOrItsResponseThread() throws Exception {
    var leases = new OperationLeaseServiceImpl();
    var bridge = new UpgradeShutdownBridge();
    var closeEntered = new CountDownLatch(1);
    var releaseClose = new CountDownLatch(1);
    var closeFinished = new CountDownLatch(1);
    bridge.install((preparationId, nonce) -> {
      closeEntered.countDown();
      try {
        assertTrue(releaseClose.await(2, TimeUnit.SECONDS));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      } finally {
        closeFinished.countDown();
      }
    });
    var controller = new UpgradeController(leases, bridge, null);
    Map<String, Object> capability = prepare(controller);
    Context commit = commitContext(capability, new CountDownLatch(0), new CountDownLatch(0),
        new AtomicBoolean(), false);
    Thread commitThread = Thread.ofPlatform().start(() -> controller.commitShutdown(commit));
    try {
      assertTrue(closeEntered.await(2, TimeUnit.SECONDS));
      commitThread.join(500L);
      assertFalse(commitThread.isAlive());
      Context competingPrepare = conflictContext();
      controller.prepare(competingPrepare);
      verify(competingPrepare).status(409);
      Context competingCancel = requestContext(capability);
      controller.cancel(competingCancel);
      verify(competingCancel).status(409);
    } finally {
      releaseClose.countDown();
    }
    assertTrue(closeFinished.await(2, TimeUnit.SECONDS));
  }

  @Test
  void failedFlushNeverDispatchesAndLeavesBothRetryAndCancellationAvailable() throws Exception {
    for (boolean retry : List.of(false, true)) {
      var leases = new OperationLeaseServiceImpl();
      var bridge = new UpgradeShutdownBridge();
      var calls = new AtomicInteger();
      var dispatched = new CountDownLatch(1);
      bridge.install((preparationId, nonce) -> {
        calls.incrementAndGet();
        dispatched.countDown();
      });
      var controller = new UpgradeController(leases, bridge, null);
      Map<String, Object> capability = prepare(controller);
      Context failed = commitContext(capability, null, null, new AtomicBoolean(), true);
      assertThrows(UncheckedIOException.class, () -> controller.commitShutdown(failed));
      assertFalse(dispatched.await(100, TimeUnit.MILLISECONDS));
      assertEquals(0, calls.get());
      if (retry) {
        controller.commitShutdown(commitContext(capability, new CountDownLatch(0),
            new CountDownLatch(0), new AtomicBoolean(), false));
        assertTrue(dispatched.await(2, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
      } else {
        controller.cancel(requestContext(capability));
        assertFalse(leases.snapshot().admissionFrozen());
        assertEquals(0, calls.get());
      }
    }
  }

  private static Map<String, Object> prepare(UpgradeController controller) {
    var body = new AtomicReference<Map<String, Object>>();
    Context context = jsonContext(body);
    controller.prepare(context);
    return body.get();
  }

  private static Context jsonContext(AtomicReference<Map<String, Object>> body) {
    Context context = mock(Context.class);
    when(context.path()).thenReturn("/api/upgrade/prepare");
    when(context.json(any()))
        .thenAnswer(
            invocation -> {
              Map<String, Object> value = invocation.getArgument(0);
              body.set(value);
              return context;
            });
    return context;
  }

  private static Context requestContext(Map<String, Object> capability) {
    Context context = conflictContext();
    when(context.path()).thenReturn("/api/upgrade/cancel");
    when(context.body()).thenReturn(capabilityBodyUnchecked(capability));
    return context;
  }

  private static Context conflictContext() {
    Context context = mock(Context.class);
    when(context.path()).thenReturn("/api/upgrade/prepare");
    when(context.status(409)).thenReturn(context);
    when(context.json(any())).thenReturn(context);
    return context;
  }

  private static WorkerQuiescenceSnapshot readyWorker(String preparationId) {
    return new WorkerQuiescenceSnapshot(preparationId, true, true, true, "IDLE", List.of());
  }

  private static Context commitContext(
      Map<String, Object> capability,
      CountDownLatch flushEntered,
      CountDownLatch releaseFlush,
      AtomicBoolean flushed,
      boolean failFlush)
      throws Exception {
    Context context = mock(Context.class);
    when(context.path()).thenReturn("/api/upgrade/commit-shutdown");
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(context.body()).thenReturn(capabilityBody(capability));
    when(context.res()).thenReturn(response);
    when(context.contentType(any(String.class))).thenReturn(context);
    when(response.getOutputStream()).thenReturn(discardingOutput());
    if (failFlush) {
      doAnswer(ignored -> { throw new IOException("flush failed"); })
          .when(response)
          .flushBuffer();
    } else {
      doAnswer(
              ignored -> {
                flushEntered.countDown();
                assertTrue(releaseFlush.await(2, TimeUnit.SECONDS));
                flushed.set(true);
                return null;
              })
          .when(response)
          .flushBuffer();
    }
    return context;
  }

  private static ServletOutputStream discardingOutput() {
    return new ServletOutputStream() {
      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setWriteListener(WriteListener listener) {}

      @Override
      public void write(int value) {}
    };
  }

  private static String capabilityBody(Map<String, Object> capability) throws Exception {
    return JSON.writeValueAsString(
        Map.of(
            "schemaVersion", 1,
            "preparationId", capability.get("preparationId"),
            "shutdownNonce", capability.get("shutdownNonce")));
  }

  private static String capabilityBodyUnchecked(Map<String, Object> capability) {
    try {
      return capabilityBody(capability);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

}
