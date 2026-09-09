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

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.engine.EngineAdmissionController;
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
    EngineAdmissionService admission = mock(EngineAdmissionService.class);
    var watcher = mock(io.justsearch.app.engine.ShutdownRequestWatcher.class);
    RuntimeManifestPublisher manifest = mock(RuntimeManifestPublisher.class);
    LocalApiServer api = mock(LocalApiServer.class);
    var health = mock(io.justsearch.app.services.worker.KnowledgeServerHealthMonitor.class);
    HeadAssembly assembly = mock(HeadAssembly.class);
    KnowledgeServerBootstrap knowledge = mock(KnowledgeServerBootstrap.class);
    var tracing = mock(io.justsearch.telemetry.TracingBootstrap.class);
    Telemetry telemetry = mock(Telemetry.class);
    var executors = mock(io.justsearch.core.execution.EngineExecutorRegistry.class);
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
                admission,
                executors,
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
            admission,
            watcher,
            manifest,
            api,
            health,
            assembly,
            knowledge,
            tracing,
            telemetry,
            executors,
            instanceLock);
    order.verify(manifest).markShutdownPending(Reason.RESTART.wire());
    order.verify(leases).freezeAdmission(Reason.RESTART.wire());
    order.verify(admission).cancelInteractive(Reason.RESTART.wire());
    order.verify(watcher).close();
    order.verify(api).stop();
    order.verify(health).close();
    order.verify(assembly).setStopGenerativeBackendOnClose(false);
    order.verify(assembly).close();
    order.verify(knowledge).closeForUpgrade();
    order.verify(tracing).close();
    order.verify(telemetry).close();
    order.verify(executors).close();
    order.verify(instanceLock).close();
    order.verify(manifest).completeShutdown(Reason.RESTART.wire(), true, "GRACEFUL");
  }

  @Test
  @DisplayName("every reason configures the inference close before HeadAssembly closes")
  void everyReasonConfiguresInferenceCloseBeforeAssemblyClose() {
    for (Reason reason : Reason.values()) {
      HeadAssembly assembly = mock(HeadAssembly.class);
      OperationLeaseService leases = mock(OperationLeaseService.class);
      EngineAdmissionService admission = mock(EngineAdmissionService.class);
      LocalApiServer api = mock(LocalApiServer.class);
      var sequence =
          new EngineShutdownSequence(
              Path.of("build", "shutdown-wiring", reason.wire()),
              HeadlessApp.orderedShutdownSteps(
                  api, assembly, null, null, null, null, null, null, leases, admission, mock(io.justsearch.core.execution.EngineExecutorRegistry.class), () -> null),
              code -> {});

      sequence.run(reason);

      var order = inOrder(leases, admission, api, assembly);
      order.verify(leases).freezeAdmission(reason.wire());
      order.verify(admission).cancelInteractive(reason.wire());
      order.verify(api).stop();
      order.verify(assembly).setStopGenerativeBackendOnClose(reason.stopsGenerativeBackend());
      order.verify(assembly).close();
    }
  }

  @Test
  @DisplayName("shutdown freezes the real admission front and cancels only interactive work")
  void shutdownCancellationExcludesDurableWorkWithRealController() {
    var admission = new EngineAdmissionController(2, 2, 1);
    var durableContext =
        new io.justsearch.core.context.EngineContext(
            io.justsearch.core.context.EngineContext.ClientKind.MCP_CLIENT,
            "durable-shutdown-test",
            java.util.Optional.of("durable-shutdown-test"),
            java.util.Optional.empty(),
            "UNTRUSTED",
            "MCP",
            io.justsearch.core.context.EngineContext.Survival.DURABLE,
            io.justsearch.core.context.EngineContext.Urgency.FOREGROUND);
    try (var interactive = admission.admit(io.justsearch.ui.api.TestRequestContexts.browser(), false);
        var durable = admission.admit(durableContext, false)) {
      var sequence =
          new EngineShutdownSequence(
              Path.of("build", "shutdown-wiring", "durable-exclusion"),
              HeadlessApp.orderedShutdownSteps(
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  admission,
                  admission,
                  mock(io.justsearch.core.execution.EngineExecutorRegistry.class),
                  () -> null),
              ignored -> {});

      sequence.run(Reason.RESTART);

      assertEquals(java.util.Optional.of("restart"), interactive.cancellationReason());
      assertTrue(durable.cancellationReason().isEmpty());
      var refusal = assertThrows(io.justsearch.app.api.EngineAdmissionException.class,
          () -> admission.admit(io.justsearch.ui.api.TestRequestContexts.browser(), false));
      assertEquals(io.justsearch.app.api.EngineAdmissionException.Reason.FROZEN, refusal.reason());
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
                null,
                mock(io.justsearch.core.execution.EngineExecutorRegistry.class),
                () -> null),
            ignored -> {});

    var result = sequence.run(Reason.QUIT);

    assertEquals("GRACEFUL", result.workerOutcome());
  }

  @Test
  @DisplayName("boot discards a request left by the prior Engine incarnation")
  void bootDiscardsPreexistingShutdownRequest(@TempDir Path tempDir) throws Exception {
    Path runtime = Files.createDirectories(tempDir.resolve("runtime"));
    writeRequest(new io.justsearch.app.engine.ShutdownRequest(
            Reason.RESTART, Long.MAX_VALUE, null, "prior-incarnation", null), runtime);
    HeadlessApp.clearPriorShutdownRequest(runtime, Files::deleteIfExists);
    var fired = new CountDownLatch(1);

    try (var _ =
        HeadlessApp.startShutdownRequestWatcher(new io.justsearch.core.execution.TestEngineExecutors(),
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
    writeRequest(new io.justsearch.app.engine.ShutdownRequest(
            Reason.RESTART, Long.MAX_VALUE, null, "current-incarnation", null), runtime);
    var fired = new CountDownLatch(1);

    try (var _ =
        HeadlessApp.startShutdownRequestWatcher(new io.justsearch.core.execution.TestEngineExecutors(),
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
    writeRequest(new io.justsearch.app.engine.ShutdownRequest(
            Reason.RESTART, Long.MAX_VALUE, null, "prior-incarnation", null), runtime);
    var watcherStarted = new java.util.concurrent.atomic.AtomicBoolean();

    assertThrows(
        java.io.IOException.class,
        () -> {
          HeadlessApp.clearPriorShutdownRequest(runtime, ignored -> false);
          watcherStarted.set(true);
          HeadlessApp.startShutdownRequestWatcher(new io.justsearch.core.execution.TestEngineExecutors(),
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
                null,
                mock(io.justsearch.core.execution.EngineExecutorRegistry.class),
                watcherRef::get),
            ignored -> {});

    try (var _ =
        HeadlessApp.startShutdownRequestWatcher(new io.justsearch.core.execution.TestEngineExecutors(),
            runtime,
            ignored -> io.justsearch.app.engine.ShutdownRequestWatcher.Acceptance.ACCEPT,
            request -> sequence.run(request.reason()),
            20L,
            watcherRef::set)) {
      writeRequest(new io.justsearch.app.engine.ShutdownRequest(
              Reason.QUIT, Long.MAX_VALUE, null, "test", null), runtime);
      assertTrue(laterStep.await(2, TimeUnit.SECONDS));
      assertFalse(laterStepInterrupted.get());
      Thread thread = callbackThread.get();
      thread.join(2_000L);
      assertFalse(thread.isAlive(), "the production close step must terminate the watcher thread");
    }
  }
  /** Host-file fixture only; the Engine has no production request writer. */
  static void writeRequest(io.justsearch.app.engine.ShutdownRequest request, Path runtimeDir)
      throws java.io.IOException {
    var fields = new java.util.LinkedHashMap<String, Object>();
    fields.put("reason", request.reason().wire());
    fields.put("deadlineEpochMs", request.deadlineEpochMs());
    if (request.nonce() != null) fields.put("nonce", request.nonce());
    if (request.issuedBy() != null) fields.put("issuedBy", request.issuedBy());
    if (request.preparationId() != null) fields.put("preparationId", request.preparationId());
    Files.createDirectories(runtimeDir);
    Path staged = runtimeDir.resolve("shutdown-request.v1.json.tmp");
    Files.writeString(staged, new tools.jackson.databind.ObjectMapper().writeValueAsString(fields));
    Files.move(staged, io.justsearch.app.engine.ShutdownRequest.pathIn(runtimeDir),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }
}
