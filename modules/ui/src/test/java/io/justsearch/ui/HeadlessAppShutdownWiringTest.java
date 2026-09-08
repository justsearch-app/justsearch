/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.engine.ShutdownRequest.Reason;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.ShutdownOutcome;
import io.justsearch.app.util.AppInstanceLock;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.ui.api.LocalApiServer;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("HeadlessApp ordered shutdown wiring")
final class HeadlessAppShutdownWiringTest {

  @Test
  @DisplayName("terminal writer waits for the complete ordered shutdown binding, then exits 1")
  void terminalWriterUsesLateBoundOrderedSequence(@TempDir Path tempDir) throws Exception {
    var binding = new CompletableFuture<EngineShutdownSequence>();
    var exitCode = new AtomicInteger(-1);
    var manifestCompleted = new java.util.concurrent.atomic.AtomicBoolean();
    OperationLeaseService leases = mock(OperationLeaseService.class);
    var watcher = mock(io.justsearch.app.engine.ShutdownRequestWatcher.class);
    RuntimeManifestPublisher manifest = mock(RuntimeManifestPublisher.class);
    LocalApiServer api = mock(LocalApiServer.class);
    var health = mock(io.justsearch.app.services.worker.KnowledgeServerHealthMonitor.class);
    HeadAssembly assembly = mock(HeadAssembly.class);
    KnowledgeServerBootstrap knowledge = mock(KnowledgeServerBootstrap.class);
    var tracing = mock(io.justsearch.telemetry.TracingBootstrap.class);
    Telemetry telemetry = mock(Telemetry.class);
    AppInstanceLock instanceLock = mock(AppInstanceLock.class);
    when(knowledge.closeForUpgrade()).thenReturn(ShutdownOutcome.GRACEFUL);
    Thread faultThread =
        Thread.ofPlatform()
            .start(() -> HeadlessApp.terminalWriterFaultAction(binding).accept(1));

    assertTrue(faultThread.isAlive(), "the Root fault thread waits until composition is complete");
    binding.complete(
        new EngineShutdownSequence(
            tempDir,
            HeadlessApp.orderedShutdownSteps(
                api,
                assembly,
                health,
                knowledge,
                manifest,
                tracing,
                telemetry,
                instanceLock,
                leases,
                () -> watcher),
            code -> {
              assertTrue(manifestCompleted.get(), "manifest completion precedes process exit");
              exitCode.set(code);
            },
            preliminary -> {
              manifest.completeShutdown(
                  preliminary.reason().wire(), preliminary.clean(), preliminary.workerOutcome());
              manifestCompleted.set(true);
            }));
    faultThread.join(2_000L);

    assertFalse(faultThread.isAlive());
    assertEquals(1, exitCode.get());
    var order =
        inOrder(
            leases,
            watcher,
            manifest,
            api,
            health,
            assembly,
            knowledge,
            tracing,
            telemetry,
            instanceLock);
    order.verify(leases).freezeAdmission(Reason.RESTART.wire());
    order.verify(watcher).close();
    order.verify(manifest).markShutdownPending(Reason.RESTART.wire());
    order.verify(api).stop();
    order.verify(health).close();
    order.verify(assembly).setStopGenerativeBackendOnClose(false);
    order.verify(assembly).close();
    order.verify(knowledge).closeForUpgrade();
    order.verify(tracing).close();
    order.verify(telemetry).close();
    order.verify(instanceLock).close();
    order.verify(manifest).completeShutdown(Reason.RESTART.wire(), true, "GRACEFUL");
  }

  @Test
  @DisplayName("every reason configures the inference close before HeadAssembly closes")
  void everyReasonConfiguresInferenceCloseBeforeAssemblyClose() {
    for (Reason reason : Reason.values()) {
      HeadAssembly assembly = mock(HeadAssembly.class);
      OperationLeaseService leases = mock(OperationLeaseService.class);
      var sequence =
          new EngineShutdownSequence(
              Path.of("build", "shutdown-wiring", reason.wire()),
              HeadlessApp.orderedShutdownSteps(
                  null, assembly, null, null, null, null, null, null, leases, () -> null),
              code -> {});

      sequence.run(reason);

      var order = inOrder(leases, assembly);
      order.verify(leases).freezeAdmission(reason.wire());
      order.verify(assembly).setStopGenerativeBackendOnClose(reason.stopsGenerativeBackend());
      order.verify(assembly).close();
    }
  }

  @Test
  @DisplayName("an absent production index half reports graceful")
  void absentProductionIndexHalfReportsGraceful() {
    var sequence =
        new EngineShutdownSequence(
            Path.of("build", "shutdown-wiring", "absent-index"),
            HeadlessApp.orderedShutdownSteps(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                OperationLeaseService.noOp(),
                () -> null),
            ignored -> {});

    var result = sequence.run(Reason.QUIT);

    assertEquals("GRACEFUL", result.workerOutcome());
  }

  @Test
  @DisplayName("boot discards a request left by the prior Engine incarnation")
  void bootDiscardsPreexistingShutdownRequest(@TempDir Path tempDir) throws Exception {
    Path runtime = Files.createDirectories(tempDir.resolve("runtime"));
    new io.justsearch.app.engine.ShutdownRequest(
            Reason.RESTART, Long.MAX_VALUE, null, "prior-incarnation", null)
        .writeTo(runtime);
    HeadlessApp.clearPriorShutdownRequest(runtime, Files::deleteIfExists);
    var fired = new CountDownLatch(1);

    try (var _ =
        HeadlessApp.startShutdownRequestWatcher(
            runtime,
            r -> io.justsearch.app.engine.ShutdownRequestWatcher.Acceptance.ACCEPT,
            r -> fired.countDown(),
            20L,
            ignored -> {})) {
      assertFalse(fired.await(200, TimeUnit.MILLISECONDS));
    }

    assertFalse(Files.exists(io.justsearch.app.engine.ShutdownRequest.pathIn(runtime)));
  }

  @Test
  @DisplayName("a request written after the boot clear survives until watcher dispatch")
  void currentIncarnationRequestSurvivesWatcherStart(@TempDir Path tempDir) throws Exception {
    Path runtime = Files.createDirectories(tempDir.resolve("runtime"));
    HeadlessApp.clearPriorShutdownRequest(runtime, Files::deleteIfExists);
    new io.justsearch.app.engine.ShutdownRequest(
            Reason.RESTART, Long.MAX_VALUE, null, "current-incarnation", null)
        .writeTo(runtime);
    var fired = new CountDownLatch(1);

    try (var _ =
        HeadlessApp.startShutdownRequestWatcher(
            runtime,
            r -> io.justsearch.app.engine.ShutdownRequestWatcher.Acceptance.ACCEPT,
            r -> fired.countDown(),
            20L,
            ignored -> {})) {
      assertTrue(fired.await(2, TimeUnit.SECONDS));
    }
  }

  @Test
  @DisplayName("boot does not start the watcher when a predecessor request cannot be removed")
  void failedBootClearPreventsWatcherStart(@TempDir Path tempDir) throws Exception {
    Path runtime = Files.createDirectories(tempDir.resolve("runtime"));
    new io.justsearch.app.engine.ShutdownRequest(
            Reason.RESTART, Long.MAX_VALUE, null, "prior-incarnation", null)
        .writeTo(runtime);
    var watcherStarted = new java.util.concurrent.atomic.AtomicBoolean();

    assertThrows(
        java.io.IOException.class,
        () -> {
          HeadlessApp.clearPriorShutdownRequest(runtime, ignored -> false);
          watcherStarted.set(true);
          HeadlessApp.startShutdownRequestWatcher(
              runtime,
              r -> io.justsearch.app.engine.ShutdownRequestWatcher.Acceptance.ACCEPT,
              ignored -> {},
              20L,
              ignored -> {});
        });

    assertFalse(watcherStarted.get());
    assertTrue(Files.exists(io.justsearch.app.engine.ShutdownRequest.pathIn(runtime)));
  }

  @Test
  @DisplayName("a watcher callback closes itself through the production ordered steps")
  void watcherCallbackClosesProductionStepWithoutInterruptingLaterClose(
      @TempDir Path tempDir) throws Exception {
    Path runtime = Files.createDirectories(tempDir.resolve("runtime"));
    var watcherRef =
        new java.util.concurrent.atomic.AtomicReference<
            io.justsearch.app.engine.ShutdownRequestWatcher>();
    var laterStep = new CountDownLatch(1);
    var laterStepInterrupted = new java.util.concurrent.atomic.AtomicBoolean(true);
    var callbackThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
    AppInstanceLock instanceLock = mock(AppInstanceLock.class);
    doAnswer(
            ignored -> {
              callbackThread.set(Thread.currentThread());
              laterStepInterrupted.set(Thread.currentThread().isInterrupted());
              laterStep.countDown();
              return null;
            })
        .when(instanceLock)
        .close();
    var sequence =
        new EngineShutdownSequence(
            tempDir,
            HeadlessApp.orderedShutdownSteps(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                instanceLock,
                OperationLeaseService.noOp(),
                watcherRef::get),
            ignored -> {});

    try (var _ =
        HeadlessApp.startShutdownRequestWatcher(
            runtime,
            ignored -> io.justsearch.app.engine.ShutdownRequestWatcher.Acceptance.ACCEPT,
            request -> sequence.run(request.reason()),
            20L,
            watcherRef::set)) {
      new io.justsearch.app.engine.ShutdownRequest(
              Reason.QUIT, Long.MAX_VALUE, null, "test", null)
          .writeTo(runtime);
      assertTrue(laterStep.await(2, TimeUnit.SECONDS));
      assertFalse(laterStepInterrupted.get());
      Thread thread = callbackThread.get();
      thread.join(2_000L);
      assertFalse(thread.isAlive(), "the production close step must terminate the watcher thread");
    }
  }
}
