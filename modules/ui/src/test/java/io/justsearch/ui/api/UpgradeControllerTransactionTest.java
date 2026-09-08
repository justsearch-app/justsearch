/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.engine.ShutdownRequest;
import io.justsearch.app.engine.ShutdownRequestWatcher;
import io.justsearch.app.services.lease.OperationLeaseServiceImpl;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
  void watcherDefersDuringBlockedFlushAndDispatchesOnlyAfterAcknowledgement(@TempDir Path tempDir)
      throws Exception {
    Path runtimeDir = Files.createDirectories(tempDir.resolve("runtime"));
    var leases = new OperationLeaseServiceImpl();
    var bridge = new UpgradeShutdownBridge();
    var persisted = new CountDownLatch(1);
    bridge.install(
        (preparationId, nonce) -> {
          writeRequest(runtimeDir, preparationId, nonce);
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
    try (var watcher =
        new ShutdownRequestWatcher(
            runtimeDir,
            request -> {
              var decision = bridge.verify(request.preparationId(), request.nonce());
              if (decision == UpgradeShutdownBridge.Verification.DEFER) deferred.countDown();
              return acceptance(decision);
            },
            ignored -> {
              dispatchedAfterFlush.set(flushed.get());
              dispatched.countDown();
            },
            20L)) {
      watcher.start();
      Thread commitThread = Thread.ofPlatform().start(() -> controller.commitShutdown(commit));

      assertTrue(persisted.await(2, TimeUnit.SECONDS));
      assertTrue(flushEntered.await(2, TimeUnit.SECONDS));
      assertTrue(deferred.await(2, TimeUnit.SECONDS));
      assertTrue(Files.isRegularFile(ShutdownRequest.pathIn(runtimeDir)));
      assertFalse(watcher.hasFired());

      releaseFlush.countDown();
      commitThread.join(2_000L);
      assertFalse(commitThread.isAlive());
      assertTrue(dispatched.await(2, TimeUnit.SECONDS));
      assertTrue(dispatchedAfterFlush.get());
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
    bridge.install((preparationId, nonce) -> writeRequest(runtimeDir, preparationId, nonce));
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
    Context context = mock(Context.class);
    var body = new AtomicReference<Map<String, Object>>();
    when(context.json(any()))
        .thenAnswer(
            invocation -> {
              Map<String, Object> value = invocation.getArgument(0);
              body.set(value);
              return context;
            });
    controller.prepare(context);
    return body.get();
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
      case ACCEPT -> ShutdownRequestWatcher.Acceptance.ACCEPT;
      case DEFER -> ShutdownRequestWatcher.Acceptance.DEFER;
      case REFUSE -> ShutdownRequestWatcher.Acceptance.REFUSE;
    };
  }

  private static void writeRequest(Path runtimeDir, String preparationId, String nonce) {
    try {
      new ShutdownRequest(
              ShutdownRequest.Reason.UPGRADE,
              Long.MAX_VALUE,
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

  private static boolean waitForAbsent(Path path) throws Exception {
    for (int i = 0; i < 100; i++) {
      if (!Files.exists(path)) return true;
      TimeUnit.MILLISECONDS.sleep(20L);
    }
    return false;
  }
}
