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
import io.justsearch.app.engine.ShutdownRequest;
import io.justsearch.app.engine.ShutdownRequestWatcher;
import io.justsearch.app.services.lease.OperationLeaseServiceImpl;
import io.justsearch.app.services.worker.KnowledgeClient;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

final class UpgradeControllerTransactionTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void bridgeFailsClosedWhenVerifierIsMissingOrReturnsNull() {
    var missing = new UpgradeShutdownBridge();
    assertEquals(UpgradeShutdownBridge.Verification.REFUSE, missing.verify("prep", "nonce"));

    var nullDecision = new UpgradeShutdownBridge();
    nullDecision.installVerifier((preparationId, nonce) -> null);
    assertEquals(
        UpgradeShutdownBridge.Verification.REFUSE, nullDecision.verify("prep", "nonce"));
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
    when(worker.prepareUpgrade(any(String.class)))
        .thenAnswer(
            invocation -> {
              String preparationId = invocation.getArgument(0);
              if (prepareCalls.incrementAndGet() == 2) {
                workerEntered.countDown();
                assertTrue(releaseWorker.await(2, TimeUnit.SECONDS));
              }
              return readyWorker(preparationId);
            });
    when(worker.upgradeStatus(any(String.class)))
        .thenAnswer(invocation -> readyWorker(invocation.getArgument(0)));
    when(worker.cancelUpgrade(any(String.class)))
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
    when(failed.json(any())).thenThrow(new IllegalStateException("response failed"));

    assertThrows(IllegalStateException.class, () -> controller.prepare(failed));
    Map<String, Object> capability = prepare(controller);
    assertEquals(capability.get("preparationId"), leases.snapshot().preparationId());

    controller.cancel(requestContext(capability));
    assertFalse(leases.snapshot().admissionFrozen());
  }

  @Test
  void watcherDefersDuringBlockedFlushAndDispatchesOnlyAfterAcknowledgement(@TempDir Path tempDir)
      throws Exception {
    Path runtimeDir = Files.createDirectories(tempDir.resolve("runtime"));
    var leases = new OperationLeaseServiceImpl();
    var bridge = new UpgradeShutdownBridge();
    var persisted = new CountDownLatch(1);
    bridge.install(
        (preparationId, nonce) -> {
          writeRequest(runtimeDir, preparationId, nonce, System.currentTimeMillis() + 150L);
          persisted.countDown();
        });
    var controller = new UpgradeController(leases, bridge, null);
    Map<String, Object> capability = prepare(controller);
    var flushEntered = new CountDownLatch(1);
    var releaseFlush = new CountDownLatch(1);
    var flushed = new AtomicBoolean();
    Context commit = commitContext(capability, flushEntered, releaseFlush, flushed, false);
    var deferred = new CountDownLatch(1);
    var dispatched = new CountDownLatch(1);
    var dispatchedAfterFlush = new AtomicBoolean();
    var dispatchedRequest = new AtomicReference<ShutdownRequest>();
    try (var watcher =
        new ShutdownRequestWatcher(
            runtimeDir,
            request -> {
              var decision = bridge.verify(request.preparationId(), request.nonce());
              if (decision == UpgradeShutdownBridge.Verification.DEFER) deferred.countDown();
              return acceptance(decision);
            },
            request -> {
              dispatchedRequest.set(request);
              dispatchedAfterFlush.set(flushed.get());
              dispatched.countDown();
            },
            20L)) {
      watcher.start();
      Thread commitThread = Thread.ofPlatform().start(() -> controller.commitShutdown(commit));

      assertTrue(persisted.await(2, TimeUnit.SECONDS));
      assertTrue(flushEntered.await(2, TimeUnit.SECONDS));
      assertTrue(deferred.await(2, TimeUnit.SECONDS));
      TimeUnit.MILLISECONDS.sleep(200L);
      long originalDeadline = ShutdownRequest.read(runtimeDir).orElseThrow().deadlineEpochMs();
      assertTrue(originalDeadline < System.currentTimeMillis());
      assertTrue(Files.isRegularFile(ShutdownRequest.pathIn(runtimeDir)));
      assertFalse(watcher.hasFired());

      releaseFlush.countDown();
      commitThread.join(2_000L);
      assertFalse(commitThread.isAlive());
      assertTrue(dispatched.await(2, TimeUnit.SECONDS));
      assertTrue(dispatchedAfterFlush.get());
      assertEquals(originalDeadline, dispatchedRequest.get().deadlineEpochMs());
    }
  }

  @Test
  void verifierRemainsResponsiveWhilePersistenceIsBlocked() throws Exception {
    var leases = new OperationLeaseServiceImpl();
    var bridge = new UpgradeShutdownBridge();
    var writerEntered = new CountDownLatch(1);
    var releaseWriter = new CountDownLatch(1);
    bridge.install(
        (preparationId, nonce) -> {
          writerEntered.countDown();
          try {
            assertTrue(releaseWriter.await(2, TimeUnit.SECONDS));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
        });
    var controller = new UpgradeController(leases, bridge, null);
    Map<String, Object> capability = prepare(controller);
    Context commit =
        commitContext(
            capability,
            new CountDownLatch(0),
            new CountDownLatch(0),
            new AtomicBoolean(),
            false);
    Thread commitThread = Thread.ofPlatform().start(() -> controller.commitShutdown(commit));
    assertTrue(writerEntered.await(2, TimeUnit.SECONDS));

    var verification =
        java.util.concurrent.CompletableFuture.supplyAsync(
                () ->
                    bridge.verify(
                        (String) capability.get("preparationId"),
                        (String) capability.get("shutdownNonce")))
            .get(1, TimeUnit.SECONDS);
    assertEquals(UpgradeShutdownBridge.Verification.DEFER, verification);

    releaseWriter.countDown();
    commitThread.join(2_000L);
    assertFalse(commitThread.isAlive());
  }

  @Test
  void failedFlushRestoresOpenAndRetainedRequestCannotDispatch(@TempDir Path tempDir)
      throws Exception {
    Path runtimeDir = Files.createDirectories(tempDir.resolve("runtime"));
    var leases = new OperationLeaseServiceImpl();
    var bridge = new UpgradeShutdownBridge();
    bridge.install(
        (preparationId, nonce) -> writeRequest(runtimeDir, preparationId, nonce, Long.MAX_VALUE));
    var controller = new UpgradeController(leases, bridge, null);
    Map<String, Object> capability = prepare(controller);
    Context commit = commitContext(capability, null, null, new AtomicBoolean(), true);

    assertThrows(UncheckedIOException.class, () -> controller.commitShutdown(commit));
    assertEquals(
        UpgradeShutdownBridge.Verification.REFUSE,
        bridge.verify(
            (String) capability.get("preparationId"), (String) capability.get("shutdownNonce")));

    var dispatched = new AtomicBoolean();
    try (var watcher =
        new ShutdownRequestWatcher(
            runtimeDir,
            request -> acceptance(bridge.verify(request.preparationId(), request.nonce())),
            ignored -> dispatched.set(true),
            20L)) {
      watcher.start();
      assertTrue(waitForAbsent(ShutdownRequest.pathIn(runtimeDir)));
      assertFalse(dispatched.get());
      assertFalse(watcher.hasFired());
    }

    Context cancel = mock(Context.class);
    when(cancel.body()).thenReturn(capabilityBody(capability));
    when(cancel.json(any())).thenReturn(cancel);
    controller.cancel(cancel);
    assertFalse(leases.snapshot().admissionFrozen());
  }

  private static Map<String, Object> prepare(UpgradeController controller) {
    var body = new AtomicReference<Map<String, Object>>();
    Context context = jsonContext(body);
    controller.prepare(context);
    return body.get();
  }

  private static Context jsonContext(AtomicReference<Map<String, Object>> body) {
    Context context = mock(Context.class);
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
    when(context.body()).thenReturn(capabilityBodyUnchecked(capability));
    return context;
  }

  private static Context conflictContext() {
    Context context = mock(Context.class);
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

  private static ShutdownRequestWatcher.Acceptance acceptance(
      UpgradeShutdownBridge.Verification verification) {
    return switch (verification) {
      case ACCEPT -> ShutdownRequestWatcher.Acceptance.ACCEPT_COMMITTED;
      case DEFER -> ShutdownRequestWatcher.Acceptance.DEFER;
      case REFUSE -> ShutdownRequestWatcher.Acceptance.REFUSE;
    };
  }

  private static void writeRequest(Path runtimeDir, String preparationId, String nonce) {
    writeRequest(runtimeDir, preparationId, nonce, Long.MAX_VALUE);
  }

  private static void writeRequest(
      Path runtimeDir, String preparationId, String nonce, long deadlineEpochMs) {
    try {
      new ShutdownRequest(
              ShutdownRequest.Reason.UPGRADE,
              deadlineEpochMs,
              nonce,
              "controller-test",
              preparationId)
          .writeTo(runtimeDir);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
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

  private static boolean waitForAbsent(Path path) throws Exception {
    for (int i = 0; i < 100; i++) {
      if (!Files.exists(path)) return true;
      TimeUnit.MILLISECONDS.sleep(20L);
    }
    return false;
  }
}
