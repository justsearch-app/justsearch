/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.chat.Demand;
import io.justsearch.app.api.chat.DemandKind;
import io.justsearch.app.api.chat.PressureSignal;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.OnlineAiServiceImpl;
import io.justsearch.app.inference.chat.ChatHolds;
import io.justsearch.app.inference.chat.GenerationTracker;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.chat.ManagedChatSeam;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeStatus;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.util.EnergyState;
import io.justsearch.app.util.TempFileManager;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.core.scheduling.BulkWorkGate;
import io.justsearch.gpu.GpuCapabilitiesService;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real lifecycle, controller, generation leases, sampler, coordinator and document processor.
 * The process endpoints (llama and the index port), admission fixture and OS probes are controlled.
 */
@Timeout(120)
final class AutomaticVduRecoveryTest {
  private static final long IDLE = VduPacingPolicy.DEFAULT_IDLE_THRESHOLD_MS;
  @TempDir Path directory;
  private final TestEngineExecutors executors = TestEngineExecutors.awaitingTermination();
  private final AtomicLong activityAge = new AtomicLong(IDLE + 1_000);
  private final AtomicInteger acknowledged = new AtomicInteger();
  private final List<String> documents = new ArrayList<>();
  private final java.util.concurrent.ConcurrentMap<String, String> documentStates =
      new java.util.concurrent.ConcurrentHashMap<>();
  private ManagedChatSeam seam;
  private InferenceLifecycleManager manager;
  private OfflineCoordinator coordinator;
  private VduOfflineTriggerSampler sampler;
  private KnowledgeServerBootstrap host;
  private OnlineAiServiceImpl service;
  private RuntimeIntentTestFixture intent;
  private io.justsearch.app.engine.EngineAdmissionController admission;
  private VduCapabilityState capability;
  private final AtomicLong uptime = new AtomicLong(IDLE + 1_000);
  private final java.util.concurrent.atomic.AtomicReference<Runnable> onSelect =
      new java.util.concurrent.atomic.AtomicReference<>(() -> {});
  private KnowledgeClient index;
  private RuntimeReconciler reconciler;
  private AutoCloseable following;

  @BeforeEach void setUp() throws Exception {
    seam = new ManagedChatSeam(directory, 30);
    manager = seam.manager();
    InferenceConfig base = manager.currentConfig();
    Path projector = Files.writeString(directory.resolve("projector.gguf"), "fixture");
    manager.applyConfig(new InferenceConfig(base.serverExecutable(), base.modelPath(), projector,
        base.serverPort(), base.contextSize(), base.gpuLayers(), false, "qwen3-4b"),
        InferenceLifecycleManager.RestartPolicy.RESTART_IF_ONLINE);
    manager.switchToOnlineMode();
    idleGeneration();
    intent = new RuntimeIntentTestFixture(directory.resolve("intent"), true);
    reconciler = new RuntimeReconciler(manager, manager::getCurrentMode,
        () -> manager.view().usingExternalLlamaServer(), null, intent.spec(),
        manager::setActivation, manager::componentRecoveryPending);
    following = reconciler.followHolds(manager.chatHolds());
    reconciler.start();
    reconciler.requestBootConvergence();
    assertTrue(reconciler.awaitQuiescent(10_000));
    idleGeneration();

    admission = new io.justsearch.app.engine.EngineAdmissionController(16, 64, 1);
    service = new OnlineAiServiceImpl(admission, manager);
    var processor = new VduProcessor(service, manager.chatHolds(), service,
        new TempFileManager(directory.resolve("images")), new ImagePreparer(), VduMetricCatalog.noop());
    index = mock(KnowledgeClient.class, call -> {
      if ("recoverVduProcessingForOwner".equals(call.getMethod().getName()))
        return index.recoverVduProcessing(call.getArgument(0));
      if ("recoverVduProcessingForRetainedOwner".equals(call.getMethod().getName())) {
        try (var retained = call.getArgument(0, io.justsearch.app.api.EngineWorkHandle.class).retain()) {
          return index.recoverVduProcessing(retained.context());
        }
      }
      return RETURNS_DEFAULTS.answer(call);
    });
    for (int i = 0; i < 3; i++) documents.add(image("document-" + i + ".png").toString());
    for (String document : documents) documentStates.put(document, "PENDING");
    when(index.countPendingVdu(any())).thenAnswer(call ->
        (int) documentStates.values().stream().filter("PENDING"::equals).count());
    when(index.recoverVduProcessing(any())).thenAnswer(call -> {
      // The recovery runs under the still-live procedure owner, before shutdown closes admission.
      try (var retained = admission.attach(call.getArgument(0,
          io.justsearch.core.context.EngineContext.class))) {
        assertTrue(retained.cancellationReason().isEmpty(), "cleanup must retain its live authority");
        int pending = (int) documentStates.values().stream().filter("PROCESSING"::equals).count();
        documentStates.replaceAll((id, state) -> "PROCESSING".equals(state) ? "PENDING" : state);
        return pending;
      }
    });
    when(index.queryPendingVduDocIds(any())).thenAnswer(call -> {
      onSelect.get().run();
      return documents.stream().filter(id -> "PENDING".equals(documentStates.get(id))).toList();
    });
    when(index.markVduProcessing(anyString(), anyInt(), any())).thenAnswer(call -> {
      documentStates.put(call.getArgument(0, String.class), "PROCESSING");
      return 0;
    });
    when(index.updateVduResult(anyString(), any(), any(), any(), anyInt(), any()))
        .thenAnswer(call -> {
          documentStates.put(call.getArgument(0, String.class), "ACKNOWLEDGED");
          acknowledged.incrementAndGet(); return true;
        });
    var gpu = mock(GpuCapabilitiesService.class);
    when(gpu.snapshot()).thenReturn(VduBatchProcessorTest.gpuSnapshot(24_000_000_000L));
    capability = new VduCapabilityState();
    var batch = new VduBatchProcessor(processor, gpu, () -> index, VduMetricCatalog.noop(),
        capability, () -> activityAge.get() < IDLE);
    coordinator = new OfflineCoordinator(executors, admission, batch, () -> index, capability);
    host = mock(KnowledgeServerBootstrap.class);
    when(host.energyState()).thenReturn(new EnergyState(EnergyState.Intent.FULL, EnergyState.Source.AC));
    when(host.msSinceLastUserActivity(anyLong())).thenAnswer(call -> activityAge.get());
    when(host.bulkWork()).thenReturn(new BulkWorkGate());
    sampler = sampler(manager.chatHolds());
  }

  @AfterEach void tearDown() throws Exception {
    Files.deleteIfExists(directory.resolve("fake-llama/pause-completion"));
    Files.deleteIfExists(directory.resolve("fake-llama/refuse-normal"));
    try {
      if (sampler != null) sampler.stop();
      if (coordinator != null) coordinator.close();
    } finally {
      try {
        if (following != null) following.close();
        if (reconciler != null) reconciler.close();
        if (intent != null) intent.close();
        if (seam != null) seam.close();
      }
      finally { executors.close(); }
    }
  }

  @Test void failedAutomaticRestoreLeavesNormalConfigurationOfflineAndHumanRequestRecovers()
      throws Exception {
    failedRestore(false, false);
  }

  @Test void failedExplicitRestoreAlsoLeavesNormalConfigurationOfflineAndRecovers()
      throws Exception {
    failedRestore(true, false);
  }

  @Test void failedRestoreRetriesWithBackoffAndRecoversWithoutHumanDemand() throws Exception {
    failedRestore(false, true);
  }

  private void failedRestore(boolean explicit, boolean timer) throws Exception {
    InferenceConfig previous = manager.currentConfig();
    String normalArgs = Files.readString(seam.fakeHome().resolve("last-args.txt"));
    Files.writeString(seam.fakeHome().resolve("pause-completion"), "pause");
    if (explicit) coordinator.startOfflineProcessing(TestEngineContexts.ui(), outcome -> {});
    else sampler.checkOnce();
    assertTrue(awaitFor(() -> Files.exists(seam.fakeHome().resolve("requests.txt")), 10));
    Files.writeString(seam.fakeHome().resolve("refuse-normal"), "refuse restore");
    activityAge.set(0);
    Files.delete(seam.fakeHome().resolve("pause-completion"));
    awaitPass();
    assertFalse(manager.currentConfig().vduMode(), "a failed restore must leave VDU configuration");
    assertFalse(manager.isOnline(), "failed normal chat must never be reported online in vision mode");
    assertEquals(previous, manager.currentConfig(), "retain the previous profile and configuration");
    assertEquals("config_apply_failed", manager.view().lastFailure().wireCode());
    int launches = seam.models().size();
    activityAge.set(IDLE + 1_000);
    for (int i = 0; i < 5; i++) sampler.checkOnce();
    assertFalse(coordinator.isProcessing(), "pending restoration cannot become a chat-off automatic pass");
    Thread.sleep(200);
    assertEquals(launches, seam.models().size(), "sampler ticks cannot bypass restoration backoff");
    if (timer) {
      final int initialLaunches = launches;
      assertTrue(awaitFor(() -> {
        try { return seam.models().size() > initialLaunches; }
        catch (Exception failure) { throw new RuntimeException(failure); }
      }, 8), "first bounded retry must run even without a person");
      assertTrue(awaitFor(() -> manager.getCurrentMode() == io.justsearch.app.api.Mode.OFFLINE
          && manager.view().lastFailure() != null, 5));
      launches = seam.models().size();
      Thread.sleep(200);
      assertEquals(launches, seam.models().size(), "repeated restore failure cannot relaunch in a loop");
    }
    Files.delete(seam.fakeHome().resolve("refuse-normal"));
    if (timer) assertTrue(awaitFor(manager::isOnline, 15), "controller retries normal chat after 5 s");
    else manager.chatResidency().demand(new Demand(DemandKind.HUMAN_CHAT,
        TestEngineContexts.ui(), Duration.ofSeconds(30))).toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertTrue(manager.isOnline());
    assertEquals(previous, manager.currentConfig());
    String restoredArgs = Files.readString(seam.fakeHome().resolve("last-args.txt"));
    assertTrue(restoredArgs.contains("-np\n2"), "normal chat uses the previous parallelism");
    assertEquals(profileArgs(normalArgs), profileArgs(restoredArgs),
        "every previous profile flag is restored exactly (the serving port may be reallocated)");
    assertEquals(launches + 1, seam.models().size(), "one successful restoration, no VDU rollback");
  }

  @Test void racingLeaseAtEntryLeavesNoFalseOfflineReason() throws Exception {
    var lease = new java.util.concurrent.atomic.AtomicReference<io.justsearch.app.api.chat.GenerationLease>();
    onSelect.set(() -> lease.set(manager.admitExchange(GenerationTracker.Kind.ASSIST, null)));
    sampler.checkOnce();
    assertTrue(awaitFor(() -> lease.get() != null, 5));
    awaitPass();
    try {
      assertNull(capability.snapshot().blockedReason(), "BUSY is a deferral, not AI offline");
      assertTrue(manager.isOnline());
      assertFalse(manager.currentConfig().vduMode());
      assertEquals(1, seam.models().size());
      assertEquals(0, acknowledged.get());
    } finally { lease.get().close(); }
    onSelect.set(() -> {});
    var retry = VduOfflineTriggerSampler.class.getDeclaredField("retryAfter");
    retry.setAccessible(true);
    assertTrue(awaitFor(() -> {
      try { return retry.getLong(sampler) > System.currentTimeMillis(); }
      catch (IllegalAccessException failure) { throw new RuntimeException(failure); }
    }, 5), "BUSY is deferred for another idle window");
    sampler.checkOnce();
    assertFalse(coordinator.isProcessing(), "no immediate retry loop after harmless BUSY");
    // Advance only the deferral deadline: no fresh human activity is needed for the next window.
    retry.setLong(sampler, System.currentTimeMillis() - 1);
    idleGeneration();
    sampler.checkOnce();
    assertTrue(awaitFor(() -> acknowledged.get() == 3, 10), "the next eligible idle window retries");
    awaitPass();
    assertFalse(manager.currentConfig().vduMode());
  }

  @Test void shutdownMidLoadedAutomaticDocumentDrainsCleanlyAndLeavesItPending() throws Exception {
    shutdownDocument(false, false);
  }

  @Test void shutdownMidChatOffAutomaticDocumentDrainsCleanlyAndLeavesItPending() throws Exception {
    shutdownDocument(true, false);
  }

  @Test void shutdownMidExplicitDocumentDrainsCleanlyAndLeavesItPending() throws Exception {
    shutdownDocument(false, true);
  }

  private void shutdownDocument(boolean chatOff, boolean explicit) throws Exception {
    if (chatOff) switchChatOff();
    Files.writeString(seam.fakeHome().resolve("pause-completion"), "pause");
    if (explicit) coordinator.startOfflineProcessing(TestEngineContexts.ui(), outcome -> {});
    else sampler.checkOnce();
    assertTrue(awaitFor(() -> Files.exists(seam.fakeHome().resolve("requests.txt")), 10));
    int launches = seam.models().size();
    long start = System.nanoTime();
    // These public shutdown seams did not exist on the rejected revision. Its original terminal
    // stop is still driven there; absence of cancellation must fail the drain assertion below.
    invokeIfPresent(manager, "beginTerminalShutdown");
    Object cancellation = invokeIfPresent(coordinator, "cancelForShutdown");
    manager.stopServerForTerminalShutdown(Duration.ofSeconds(2));
    if (cancellation instanceof java.util.concurrent.CompletionStage<?> exited) {
      exited.toCompletableFuture().get(2, TimeUnit.SECONDS);
    }
    admission.beginClosing();
    assertTrue(admission.awaitDrained(Duration.ofSeconds(5)), "shutdown must drain the current VDU document");
    awaitPass();
    assertTrue(awaitFor(seam::noneAlive, 3), "no recorded fake llama child remains alive");
    assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5), "clean drain bound is 5 seconds");
    assertEquals(launches, seam.models().size(), "shutdown never relaunches normal chat");
    assertEquals(0, acknowledged.get(), "abandoned document is pending; no success or FAILED update");
    assertEquals(3, coordinator.getPendingVduCount());
    assertTrue(documentStates.values().stream().allMatch("PENDING"::equals),
        "shutdown restores the durable pending status, not merely an unacknowledged PROCESSING mark");
    assertFalse(manager.currentConfig().vduMode(), "shutdown also releases the temporary configuration");
  }

  @Test void explicitEntryShutdownPreservesGeneratingChatUntilInteractiveCancellation()
      throws Exception {
    try (var human = manager.admitExchange(GenerationTracker.Kind.GENERATE, null)) {
      assertNotNull(human);
      coordinator.startOfflineProcessing(TestEngineContexts.ui(), outcome -> {});
      assertTrue(awaitFor(coordinator::isProcessing, 5));
      assertTrue(awaitFor(() -> !((GenerationTracker) manager.generationActivity()).admissionOpen(), 5),
          "the explicit entry must be waiting in its real generation drain");
      assertTrue(manager.generationActivity().generating());
      assertFalse(manager.currentConfig().vduMode(), "the explicit pass is still draining normal chat");
      int launches = seam.models().size();
      var head = mock(io.justsearch.app.services.HeadAssembly.class, CALLS_REAL_METHODS);
      var inferenceField = head.getClass().getDeclaredField("inferenceManager");
      inferenceField.setAccessible(true); inferenceField.set(head, manager);
      var coordinatorField = head.getClass().getDeclaredField("offlineCoordinator");
      coordinatorField.setAccessible(true); coordinatorField.set(head, coordinator);
      head.quiesceEnrichmentForTerminalShutdown(
          io.justsearch.app.api.runtime.child.StopRequest.of(
              io.justsearch.app.api.runtime.child.StopReason.QUIT,
              io.justsearch.app.api.runtime.child.StopMode.NOW, "engine-shutdown"), Duration.ofSeconds(12));
      assertFalse(awaitFor(seam::noneAlive, 1),
          "existing human chat survives until the ordinary interactive cancellation step");
      assertEquals(launches, seam.models().size(), "entry cancellation never relaunches chat");
      assertTrue(documentStates.values().stream().allMatch("PENDING"::equals));
      assertTrue(manager.generationActivity().generating(), "the held human exchange is still open");
    } finally {
      manager.stopServerForTerminalShutdown(Duration.ofSeconds(12));
      awaitPass();
    }
    admission.beginClosing();
    assertTrue(admission.awaitDrained(Duration.ofSeconds(5)));
    assertTrue(awaitFor(seam::noneAlive, 3));
  }

  @Test void shutdownFenceRejectsModelLaunchBeforeCancellingTheCurrentDocument() throws Exception {
    Files.writeString(seam.fakeHome().resolve("pause-completion"), "pause");
    sampler.checkOnce();
    assertTrue(awaitFor(() -> Files.exists(seam.fakeHome().resolve("requests.txt")), 10));
    var opsField = InferenceLifecycleManager.class.getDeclaredField("serverOps");
    opsField.setAccessible(true);
    Object ops = opsField.get(manager);
    var ownershipField = ops.getClass().getDeclaredField("processOwnership");
    ownershipField.setAccessible(true);
    Object ownership = ownershipField.get(ops);
    var requireLaunch = ownership.getClass().getDeclaredMethod("requireLaunchAllowed");
    requireLaunch.setAccessible(true);
    manager.beginTerminalShutdown();
    try {
      assertThrows(java.io.IOException.class, () -> {
        try { requireLaunch.invoke(ownership); }
        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
      }, "the terminal fence must reject a normal relaunch before physical stop begins");
    } finally {
      coordinator.cancelForShutdown().toCompletableFuture().get(5, TimeUnit.SECONDS);
      manager.stopServerForTerminalShutdown(Duration.ofSeconds(12));
      awaitPass();
    }
  }

  @Test void slowOwnedModelStopDuringPassUsesTerminalAllowanceAndKeepsDocumentPending()
      throws Exception {
    Files.writeString(seam.fakeHome().resolve("pause-completion"), "pause");
    sampler.checkOnce();
    assertTrue(awaitFor(() -> Files.exists(seam.fakeHome().resolve("requests.txt")), 10));
    var opsField = InferenceLifecycleManager.class.getDeclaredField("serverOps");
    opsField.setAccessible(true);
    Object ops = opsField.get(manager);
    var ownershipField = ops.getClass().getDeclaredField("processOwnership");
    ownershipField.setAccessible(true);
    Object ownership = ownershipField.get(ops);
    var controlField = ownership.getClass().getDeclaredField("control");
    controlField.setAccessible(true);
    var original = (io.justsearch.app.api.runtime.child.ChildControl) controlField.get(ownership);
    var owner = executors.register(new io.justsearch.core.execution.EngineExecutorSpec(
        "test.slow-model-stop", io.justsearch.core.execution.EngineExecutorSpec.Kind.BACKGROUND,
        io.justsearch.core.execution.EngineExecutorSpec.Mode.PLATFORM, 1, 4, 1));
    var executor = owner.open(Thread.ofPlatform().name("test-slow-stop-", 0).factory());
    var stopped = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<?>>();
    var delayed = mock(io.justsearch.app.api.runtime.child.ChildControl.class, call -> {
      if ("stopAll".equals(call.getMethod().getName())) {
        var exit = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
          try { Thread.sleep(3000); }
          catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
          return original.stopAll(call.getArgument(0), call.getArgument(1)).toCompletableFuture().join();
        }, executor);
        stopped.set(exit);
        return exit;
      }
      try { return call.getMethod().invoke(original, call.getArguments()); }
      catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
    });
    controlField.set(ownership, delayed);
    var head = mock(io.justsearch.app.services.HeadAssembly.class, CALLS_REAL_METHODS);
    var inferenceField = head.getClass().getDeclaredField("inferenceManager");
    inferenceField.setAccessible(true); inferenceField.set(head, manager);
    var coordinatorField = head.getClass().getDeclaredField("offlineCoordinator");
    coordinatorField.setAccessible(true); coordinatorField.set(head, coordinator);
    Duration allowance;
    try { allowance = (Duration) io.justsearch.app.services.HeadAssembly.class
        .getField("TERMINAL_GENERATIVE_ALLOWANCE").get(null); }
    catch (NoSuchFieldException beforeNormalAllowance) { allowance = Duration.ofSeconds(2); }
    final Duration budget = allowance;
    try {
      assertDoesNotThrow(() -> head.quiesceEnrichmentForTerminalShutdown(
          io.justsearch.app.api.runtime.child.StopRequest.of(
              io.justsearch.app.api.runtime.child.StopReason.QUIT,
              io.justsearch.app.api.runtime.child.StopMode.NOW, "engine-shutdown"), budget),
          "an owned model exiting at 3s fits the normal terminal allowance");
      admission.beginClosing();
      assertTrue(admission.awaitDrained(Duration.ofSeconds(5)));
      awaitPass();
      assertTrue(awaitFor(seam::noneAlive, 3));
      assertTrue(documentStates.values().stream().allMatch("PENDING"::equals));
      assertEquals(0, acknowledged.get());
    } finally {
      if (stopped.get() != null) stopped.get().get(5, TimeUnit.SECONDS);
      owner.close();
    }
  }

  @Test void quitDuringActivePassEndsInsideTheQuitBudgetWithTheDocumentPendingAndItsRetryCount()
      throws Exception {
    // The quit's production order (HeadlessApp.orderedShutdownSteps under a QuitBudget): QB1 fences
    // and cancels the pass without stopping llama-server; the controller's chat stop runs beside
    // the drain; the pass's end is awaited from the live-work budget.
    var retries = new java.util.concurrent.ConcurrentHashMap<String, Integer>();
    var claims = new java.util.concurrent.ConcurrentHashMap<String, Integer>();
    for (String document : documents) retries.put(document, 1);
    when(index.markVduProcessing(anyString(), anyInt(), any())).thenAnswer(call -> {
      String id = call.getArgument(0, String.class);
      claims.put(id, retries.get(id));
      documentStates.put(id, "PROCESSING");
      return retries.merge(id, 1, Integer::sum);
    });
    // Owner-scoped recovery (orderly cancel) returns the pre-claim count; general startup recovery
    // keeps the spent one. Only the first may run here.
    doAnswer(call -> {
      // Force the hosted-runner ordering: recovery starts only after the admission fence.
      assertTrue(awaitFor(admission::isClosing, 1), "quit closes admission before cleanup proceeds");
      try (var retained = call.getArgument(0, io.justsearch.app.api.EngineWorkHandle.class).retain()) {
        assertTrue(retained.cancellationReason().isEmpty(), "cleanup must retain its live authority");
        int recovered = 0;
        for (var entry : documentStates.entrySet()) {
          if (!"PROCESSING".equals(entry.getValue())) continue;
          entry.setValue("PENDING");
          retries.put(entry.getKey(), claims.remove(entry.getKey()));
          recovered++;
        }
        return recovered;
      }
    }).when(index).recoverVduProcessingForRetainedOwner(any());
    var generalRecoveries = new AtomicInteger();
    doAnswer(call -> {
      generalRecoveries.incrementAndGet();
      int recovered = 0;
      for (var entry : documentStates.entrySet()) {
        if (!"PROCESSING".equals(entry.getValue())) continue;
        entry.setValue("PENDING");
        claims.remove(entry.getKey());
        recovered++;
      }
      return recovered;
    }).when(index).recoverVduProcessing(any());
    Files.writeString(seam.fakeHome().resolve("pause-completion"), "pause");
    sampler.checkOnce();
    assertTrue(awaitFor(() -> Files.exists(seam.fakeHome().resolve("requests.txt")), 10));
    int launches = seam.models().size();
    var head = mock(io.justsearch.app.services.HeadAssembly.class, CALLS_REAL_METHODS);
    var inferenceField = head.getClass().getDeclaredField("inferenceManager");
    inferenceField.setAccessible(true); inferenceField.set(head, manager);
    var coordinatorField = head.getClass().getDeclaredField("offlineCoordinator");
    coordinatorField.setAccessible(true); coordinatorField.set(head, coordinator);
    var stop = io.justsearch.app.api.runtime.child.StopRequest.of(
        io.justsearch.app.api.runtime.child.StopReason.QUIT,
        io.justsearch.app.api.runtime.child.StopMode.NOW, "engine-shutdown");
    var budget = io.justsearch.app.engine.QuitBudget.fromNow();

    int generalBeforeQuit = generalRecoveries.get();
    var exited = head.cancelEnrichmentForQuit(stop);
    assertNotNull(exited, "an owned pass is cancelled at QB1");
    assertFalse(seam.noneAlive(), "QB1 never stops llama-server itself");
    var chatStop = java.util.concurrent.CompletableFuture.runAsync(() -> {
      head.recordTerminalStop(stop);
      head.stopGenerativeBackendForTerminalShutdown(budget.waitFor(Duration.ofSeconds(12),
          io.justsearch.app.engine.QuitBudget.QB3_CHAT_STOP_MS));
    });
    admission.beginClosing();
    long window = io.justsearch.app.engine.QuitBudget.QB2_COOPERATIVE_MS
        + io.justsearch.app.engine.QuitBudget.QB2_CANCEL_ACK_MS;
    assertNull(io.justsearch.app.services.HeadAssembly.awaitEnrichmentCancelForQuit(
        exited, budget.waitFor(Duration.ofSeconds(5), window)), "the orderly cancel ends in the budget");
    assertTrue(admission.awaitDrained(budget.waitFor(Duration.ofSeconds(5), window)));
    chatStop.get(budget.waitFor(Duration.ofSeconds(12),
        io.justsearch.app.engine.QuitBudget.QB3_CHAT_STOP_MS).toMillis() + 1, TimeUnit.MILLISECONDS);
    awaitPass();
    assertTrue(budget.elapsedMs() < budget.engineBudgetMs(),
        "the quit ends before the supervisor's force: " + budget);
    assertTrue(awaitFor(seam::noneAlive, 1), "the controller's chat stop ended llama-server");
    verify(head, never()).quiesceEnrichmentForTerminalShutdown(any(), any());
    assertEquals(launches, seam.models().size(), "a quit never relaunches normal chat");
    assertEquals(0, acknowledged.get(), "abandoned document is pending; no success or FAILED update");
    assertTrue(documentStates.values().stream().allMatch("PENDING"::equals), documentStates.toString());
    assertTrue(retries.values().stream().allMatch(count -> count == 1),
        "the cancelled document keeps its pre-claim retry count: " + retries);
    assertEquals(generalBeforeQuit, generalRecoveries.get(),
        "the quit's orderly cancel uses owner-scoped recovery, not general startup recovery");
  }

  @Test void chatOffAutomaticPassWaitsForTheFullProcessStartupIdleWindow() throws Exception {
    startupIdle(true);
  }

  @Test void loadedAutomaticPassWaitsForTheFullProcessStartupIdleWindow() throws Exception {
    startupIdle(false);
  }

  private void startupIdle(boolean chatOff) throws Exception {
    if (chatOff) switchChatOff();
    activityAge.set(Long.MAX_VALUE);
    uptime.set(0);
    int launches = seam.models().size();
    sampler.checkOnce();
    assertFalse(coordinator.isProcessing(), "startup is activity: no automatic pass before full idle window");
    assertEquals(launches, seam.models().size());
    uptime.set(IDLE - 1);
    sampler.checkOnce();
    assertFalse(coordinator.isProcessing());
    uptime.set(IDLE + 1);
    sampler.checkOnce();
    assertTrue(awaitFor(() -> acknowledged.get() == 3, 10), "full startup window permits automatic work");
    awaitPass();
  }

  @Test void statusIdentifiesOnlyAutomaticBorrowingOfLoadedChat() throws Exception {
    Files.writeString(seam.fakeHome().resolve("pause-completion"), "pause");
    sampler.checkOnce();
    assertTrue(awaitFor(() -> Files.exists(seam.fakeHome().resolve("requests.txt")), 10));
    assertTrue(awaitFor(() -> reconciler.current().condition(RuntimeStatus.Axis.ENGINE)
        .map(c -> "automatic-vdu".equals(c.reason())).orElse(false), 5),
        "the wire engine reason must identify automatic borrowing of loaded chat");
    activityAge.set(0);
    Files.delete(seam.fakeHome().resolve("pause-completion"));
    awaitPass();
    assertFalse(reconciler.current().condition(RuntimeStatus.Axis.ENGINE)
        .map(c -> "automatic-vdu".equals(c.reason())).orElse(false));
  }

  @Test void newerRunningConfigurationSupersedesPendingRestore() throws Exception {
    pendingRestore();
    Files.delete(seam.fakeHome().resolve("refuse-normal"));
    var next = seam.withModel("new-normal.gguf");
    manager.applyConfig(next, InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS);
    int launches = seam.models().size();
    advanceControllerSeconds(6);
    Thread.sleep(250);
    humanDemand().toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(next, manager.currentConfig(), "a later normal apply supersedes the saved restore");
    assertEquals(launches, seam.models().size(), "the timer and human cannot relaunch the old snapshot");
    assertTrue(Files.readString(seam.fakeHome().resolve("last-args.txt")).contains("new-normal.gguf"));
  }

  @Test void newerRecordedConfigurationIsStartedByPendingHumanDemand() throws Exception {
    pendingRestore();
    Files.delete(seam.fakeHome().resolve("refuse-normal"));
    var next = seam.withModel("recorded-normal.gguf");
    manager.applyConfig(next, InferenceLifecycleManager.RestartPolicy.APPLY_ONLY);
    humanDemand().toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(next, manager.currentConfig(), "offline normal apply must replace the restoration target");
    assertTrue(Files.readString(seam.fakeHome().resolve("last-args.txt")).contains("recorded-normal.gguf"));
    assertTrue(manager.isOnline());
  }

  @Test void newerResolvedConfigurationSupersedesPendingRestore() throws Exception {
    pendingRestore();
    Files.delete(seam.fakeHome().resolve("refuse-normal"));
    var next = seam.withModel("resolved-normal.gguf");
    manager.applyResolvedConfig(next, resolvedModel("resolved-normal.gguf"),
        InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS);
    int launches = seam.models().size();
    advanceControllerSeconds(6);
    Thread.sleep(250);
    humanDemand().toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(next, manager.currentConfig(), "resolved config is the newest normal authority");
    assertEquals(launches, seam.models().size());
    assertTrue(Files.readString(seam.fakeHome().resolve("last-args.txt")).contains("resolved-normal.gguf"));
  }

  @Test void newerPreparedPublicationSupersedesPendingRestore() throws Exception {
    pendingRestore();
    Files.delete(seam.fakeHome().resolve("refuse-normal"));
    var next = seam.withModel("published-normal.gguf");
    var prepared = manager.prepareResolvedConfig(next, resolvedModel("published-normal.gguf"));
    prepared.withLifecycleLock(prepared::installAfterSettingsCommit);
    prepared.notifyAfterSettingsCommit();
    prepared.retireAfterSettingsCommit();
    int launches = seam.models().size();
    advanceControllerSeconds(6);
    Thread.sleep(250);
    humanDemand().toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(next, manager.currentConfig(), "committed publication supersedes the saved restore");
    assertEquals(launches, seam.models().size());
    assertTrue(Files.readString(seam.fakeHome().resolve("last-args.txt")).contains("published-normal.gguf"));
  }

  private io.justsearch.configuration.resolved.ResolvedConfig resolvedModel(String name) throws Exception {
    var resolved = ManagedChatSeam.class.getDeclaredMethod("resolvedWithModel", String.class);
    resolved.setAccessible(true);
    return (io.justsearch.configuration.resolved.ResolvedConfig) resolved.invoke(seam, name);
  }

  @Test void pendingRestorationWaitsFortySecondsUnderGamePressure() throws Exception {
    gpuFixture();
    pendingRestore();
    var controller = (io.justsearch.app.services.chat.ChatServerControllerImpl) manager.chatResidency();
    controller.setProfile(io.justsearch.app.api.status.operating.Profile.P8);
    controller.reportPressure(PressureSignal.GAME);
    int launches = seam.models().size();
    for (int i = 0; i < 8; i++) { advanceControllerSeconds(5); Thread.sleep(100); }
    assertEquals(launches, seam.models().size(), "no restoration launch during forty seconds of game pressure");
    assertEquals(io.justsearch.app.api.runtime.child.StopDetail.GAME_MODE,
        controller.unloaded().orElseThrow(), "offline status must tell the pressure reason");
    Files.delete(seam.fakeHome().resolve("refuse-normal"));
    controller.reportPressure(PressureSignal.NONE);
    advanceControllerSeconds(1);
    Thread.sleep(100);
    assertEquals(launches, seam.models().size(), "the original two-minute reload dwell still applies");
    advanceControllerSeconds(120);
    assertTrue(awaitFor(manager::isOnline, 5));
    assertEquals(launches + 1, seam.models().size(), "one restoration after pressure clears");
  }

  @Test void pendingHumanDemandUnderGameGetsUsualRefusalAndLoadAnyway() throws Exception {
    gpuFixture();
    pendingRestore();
    var controller = (io.justsearch.app.services.chat.ChatServerControllerImpl) manager.chatResidency();
    controller.setProfile(io.justsearch.app.api.status.operating.Profile.P8);
    controller.reportPressure(PressureSignal.GAME);
    int launches = seam.models().size();
    var refusal = assertThrows(io.justsearch.app.api.chat.ChatRefusal.class, this::humanDemand);
    assertEquals(io.justsearch.app.api.chat.RefusalReason.GAME_MODE, refusal.reason());
    assertTrue(refusal.forceAllowed());
    assertEquals(launches, seam.models().size());
    controller.reportPressure(PressureSignal.NONE);
    advanceControllerSeconds(1);
    var backoff = assertThrows(io.justsearch.app.api.chat.ChatRefusal.class, this::humanDemand);
    assertEquals(io.justsearch.app.api.chat.RefusalReason.RELOAD_BACKOFF, backoff.reason());
    assertTrue(backoff.forceAllowed());
    controller.reportPressure(PressureSignal.GAME);
    Files.delete(seam.fakeHome().resolve("refuse-normal"));
    controller.loadAnyway(TestEngineContexts.ui()).toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertTrue(manager.isOnline());
    assertFalse(manager.currentConfig().vduMode());
    assertEquals(launches + 1, seam.models().size());
  }

  @Test void pendingRestorationWaitsUnderExternalMemoryPressure() throws Exception {
    gpuFixture();
    pendingRestore();
    var controller = (io.justsearch.app.services.chat.ChatServerControllerImpl) manager.chatResidency();
    controller.reportPressure(PressureSignal.EXTERNAL_MEMORY);
    int launches = seam.models().size();
    advanceControllerSeconds(40);
    Thread.sleep(250);
    assertEquals(launches, seam.models().size(), "GPU busy prevents the restoration timer");
    var refusal = assertThrows(io.justsearch.app.api.chat.ChatRefusal.class, this::humanDemand);
    assertEquals(io.justsearch.app.api.chat.RefusalReason.GPU_BUSY, refusal.reason());
  }

  @Test void humanDemandDuringExplicitHoldWithPendingRestoreMatchesOriginalExplicitPass()
      throws Exception {
    pendingRestore();
    Files.delete(seam.fakeHome().resolve("refuse-normal"));
    manager.switchToOnlineMode();
    try (var hold = manager.chatHolds().holdVdu("explicit-during-pending")) {
      assertDoesNotThrow(() -> humanDemand().toCompletableFuture().get(1, TimeUnit.SECONDS),
          "the original explicit demand gate completed; generation admission owns the BUSY refusal");
      assertFalse(hold.interruptRequested(), "the explicit hold keeps its original admission response");
    }
  }

  @Test void samplerStartRaceRetriesAtNextWindowWithoutHumanActivity() throws Exception {
    var selected = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    onSelect.set(() -> { selected.countDown(); try { release.await(); }
      catch (InterruptedException stop) { Thread.currentThread().interrupt(); throw new RuntimeException(stop); } });
    var once = new java.util.concurrent.atomic.AtomicBoolean();
    when(index.countPendingVdu(any())).thenAnswer(call -> {
      if (once.compareAndSet(false, true)) coordinator.startOfflineProcessing(TestEngineContexts.ui(), o -> {});
      return (int) documentStates.values().stream().filter("PENDING"::equals).count();
    });
    sampler.checkOnce();
    assertTrue(selected.await(5, TimeUnit.SECONDS));
    release.countDown();
    awaitPass();
    onSelect.set(() -> {});
    documentStates.replaceAll((id, state) -> "PENDING");
    acknowledged.set(0);
    idleGeneration();
    var retry = VduOfflineTriggerSampler.class.getDeclaredField("retryAfter");
    retry.setAccessible(true);
    assertTrue(retry.getLong(sampler) > System.currentTimeMillis(), "race must defer a window");
    retry.setLong(sampler, System.currentTimeMillis() - 1);
    sampler.checkOnce();
    assertTrue(awaitFor(() -> acknowledged.get() == 3, 5),
        "a lost start race must be eligible at the next window without a person's activity");
    awaitPass();
  }

  @Test void waitingHumanSeesConfigApplyFailedWhenAutomaticRestorationFails() throws Exception {
    Files.writeString(seam.fakeHome().resolve("pause-completion"), "pause");
    sampler.checkOnce();
    assertTrue(awaitFor(() -> Files.exists(seam.fakeHome().resolve("requests.txt")), 10));
    var waiting = humanDemand();
    Files.writeString(seam.fakeHome().resolve("refuse-normal"), "refuse");
    Files.delete(seam.fakeHome().resolve("pause-completion"));
    assertThrows(java.util.concurrent.ExecutionException.class,
        () -> waiting.toCompletableFuture().get(10, TimeUnit.SECONDS));
    awaitPass();
    assertEquals("config_apply_failed", manager.view().lastFailure().wireCode());
    assertFalse(manager.isOnline());
  }

  @Test void cpuPendingRestoreKeepsNormalResidencyPressureExemption() throws Exception {
    pendingRestore();
    var controller = (io.justsearch.app.services.chat.ChatServerControllerImpl) manager.chatResidency();
    controller.setProfile(io.justsearch.app.api.status.operating.Profile.P8);
    controller.reportPressure(PressureSignal.GAME);
    Files.delete(seam.fakeHome().resolve("refuse-normal"));
    assertDoesNotThrow(() -> humanDemand().toCompletableFuture().get(5, TimeUnit.SECONDS),
        "normal residency leaves CPU chat alone under graphics pressure");
    assertTrue(manager.isOnline());
    assertFalse(manager.chatActuator().gpuChat());
  }

  private void gpuFixture() throws Exception {
    // Real capability merge and residency policy; only OS probes and the model process are fake.
    var capabilitiesField = InferenceLifecycleManager.class.getDeclaredField("gpuCapabilitiesService");
    capabilitiesField.setAccessible(true);
    Object capabilities = capabilitiesField.get(manager);
    var nvml = mock(io.justsearch.gpu.NvmlService.class);
    when(nvml.probe()).thenReturn(new io.justsearch.gpu.GpuCapabilities.Nvml(
        true, null, null, null, "999.0", 999, 0, 1,
        24_000_000_000L, 24_000_000_000L, 0L, 0, 0));
    var detector = mock(io.justsearch.gpu.VramDetector.class);
    when(detector.getTotalVramBytes()).thenReturn(24_000_000_000L);
    when(detector.getAvailableVramBytes()).thenReturn(24_000_000_000L);
    var nvmlField = capabilities.getClass().getDeclaredField("nvml");
    nvmlField.setAccessible(true); nvmlField.set(capabilities, nvml);
    var detectorField = capabilities.getClass().getDeclaredField("vramDetector");
    detectorField.setAccessible(true); detectorField.set(capabilities, detector);
    var opsField = InferenceLifecycleManager.class.getDeclaredField("serverOps");
    opsField.setAccessible(true);
    Object ops = opsField.get(manager);
    var pin = ops.getClass().getDeclaredField("pinnedCudaUuid");
    pin.setAccessible(true);
    pin.set(ops, (Supplier<java.util.Optional<String>>) java.util.Optional::empty);
    var controlField = ops.getClass().getDeclaredField("childControl");
    controlField.setAccessible(true);
    var original = (io.justsearch.app.api.runtime.child.ChildControl) controlField.get(ops);
    var process = mock(io.justsearch.app.api.runtime.child.ChildControl.class, call -> {
      if ("awaitGpuRelease".equals(call.getMethod().getName())) return true;
      try { return call.getMethod().invoke(original, call.getArguments()); }
      catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
    });
    controlField.set(ops, process);
    var ownershipField = ops.getClass().getDeclaredField("processOwnership");
    ownershipField.setAccessible(true);
    Object ownership = ownershipField.get(ops);
    var ownedControl = ownership.getClass().getDeclaredField("control");
    ownedControl.setAccessible(true); ownedControl.set(ownership, process);
    var old = manager.currentConfig();
    var builder = new io.justsearch.configuration.resolved.ResolvedConfigBuilder();
    builder.putDefault("justsearch.home", directory.toString());
    builder.putDefault("justsearch.data.dir", directory.resolve("data").toString());
    builder.putDefault("justsearch.server.exe", old.serverExecutable().toString());
    builder.putDefault("justsearch.llm.model_path", old.modelPath().toString());
    builder.putDefault("justsearch.gpu.layers", "1");
    builder.putDefault("policy.gpu_acceleration_enabled", "true");
    builder.putDefault("justsearch.llm.enabled", "true");
    builder.putDefault("justsearch.ai.disabled", "false");
    manager.applyResolvedConfig(new InferenceConfig(old.serverExecutable(), old.modelPath(),
        old.mmprojPath(), old.serverPort(), old.contextSize(), 1, false, old.chatProfileId()),
        builder.build(), InferenceLifecycleManager.RestartPolicy.APPLY_ONLY);
    ((io.justsearch.app.services.chat.ChatServerControllerImpl) manager.chatResidency())
        .reportQueryEncodersReady();
    assertTrue(manager.chatActuator().gpuChat(), "the residency checks must exercise GPU chat");
  }

  private void pendingRestore() throws Exception {
    // Stop reconciliation from adding an activation while the test drives the exact retry owner.
    following.close(); following = null;
    reconciler.close(); reconciler = null;
    installControllerClock();
    Files.writeString(seam.fakeHome().resolve("refuse-normal"), "refuse");
    try (var hold = manager.chatHolds().holdVdu("failed-restore")) {
      assertTrue(manager.currentConfig().vduMode());
      assertThrows(IllegalStateException.class, hold::close);
    }
    assertFalse(manager.isOnline());
    assertEquals("config_apply_failed", manager.view().lastFailure().wireCode());
  }

  private final AtomicLong controllerTime = new AtomicLong();
  private void installControllerClock() throws Exception {
    controllerTime.set(System.nanoTime());
    var controller = manager.chatHolds();
    var field = controller.getClass().getDeclaredField("nanoTime");
    field.setAccessible(true);
    field.set(controller, (java.util.function.LongSupplier) controllerTime::get);
  }

  private void advanceControllerSeconds(long seconds) throws Exception {
    controllerTime.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
    var controller = manager.chatHolds();
    var monitor = controller.getClass().getDeclaredField("monitor");
    monitor.setAccessible(true);
    Object lock = monitor.get(controller);
    synchronized (lock) { lock.notifyAll(); }
  }

  private java.util.concurrent.CompletionStage<Void> humanDemand() throws Exception {
    return manager.chatResidency().demand(new Demand(DemandKind.HUMAN_CHAT,
        TestEngineContexts.ui(), Duration.ofSeconds(30)));
  }

  private void switchChatOff() throws Exception {
    following.close();
    reconciler.close();
    intent.close();
    intent = new RuntimeIntentTestFixture(directory.resolve("off-intent"), false);
    reconciler = new RuntimeReconciler(manager, manager::getCurrentMode,
        () -> manager.view().usingExternalLlamaServer(), null, intent.spec(),
        manager::setActivation, manager::componentRecoveryPending);
    following = reconciler.followHolds(manager.chatHolds());
    reconciler.start();
    reconciler.requestBootConvergence();
    assertTrue(reconciler.awaitQuiescent(10_000));
  }

  private static Object invokeIfPresent(Object target, String method) throws Exception {
    try { return target.getClass().getMethod(method).invoke(target); }
    catch (NoSuchMethodException beforeRepair) { return null; }
  }

  private static List<String> profileArgs(String arguments) {
    var result = new ArrayList<String>();
    var values = arguments.split("\n");
    for (int i = 0; i < values.length; i++) {
      if ("--port".equals(values[i])) { i++; continue; }
      result.add(values[i]);
    }
    return result;
  }

  private void idleGeneration() throws Exception {
    var activity = manager.generationActivity();
    var field = activity.getClass().getDeclaredField("idleSinceNanos");
    field.setAccessible(true);
    field.setLong(activity, System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(IDLE + 1_000));
  }

  private VduOfflineTriggerSampler sampler(ChatHolds holds) throws Exception {
    Supplier<OfflineCoordinator> coordinators = () -> coordinator;
    Supplier<KnowledgeServerBootstrap> hosts = () -> host;
    BooleanSupplier claimed = () -> !holds.vduMayStart();
    try {
      var clocked = VduOfflineTriggerSampler.class.getDeclaredConstructor(EngineExecutorRegistry.class,
          Supplier.class, Supplier.class, BooleanSupplier.class, Supplier.class,
          java.util.function.LongSupplier.class);
      clocked.setAccessible(true);
      return clocked.newInstance(executors, coordinators, hosts, claimed,
          (Supplier<ChatHolds>) () -> holds, (java.util.function.LongSupplier) uptime::get);
    } catch (NoSuchMethodException beforeStartupRepair) { /* use the original seam below */ }
    try {
      var constructor = VduOfflineTriggerSampler.class.getConstructor(EngineExecutorRegistry.class,
          Supplier.class, Supplier.class, BooleanSupplier.class, Supplier.class);
      return constructor.newInstance(executors, coordinators, hosts, claimed, (Supplier<ChatHolds>) () -> holds);
    } catch (NoSuchMethodException before) {
      return new VduOfflineTriggerSampler(executors, coordinators, hosts, claimed);
    }
  }

  private void awaitPass() throws Exception {
    assertTrue(awaitFor(() -> !coordinator.isProcessing(), 15), "pass must release ownership");
  }

  private static boolean awaitFor(BooleanSupplier condition, int seconds) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
    return condition.getAsBoolean();
  }

  private Path image(String name) throws Exception {
    var raster = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
      raster.setRGB(x, y, (x / 3 + y / 3) % 2 == 0 ? 0 : 0xFFFFFF);
    }
    Path path = directory.resolve(name);
    ImageIO.write(raster, "png", path.toFile());
    return path;
  }
}
