/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.chat.Demand;
import io.justsearch.app.api.chat.DemandKind;
import io.justsearch.app.api.chat.PressureSignal;
import io.justsearch.app.api.chat.VduHold;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.OnlineAiServiceImpl;
import io.justsearch.app.inference.chat.ChatHolds;
import io.justsearch.app.inference.chat.GenerationTracker;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.chat.ManagedChatSeam;
import io.justsearch.app.services.runtimestate.RuntimeGpuLease;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeStatus;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.util.EnergyState;
import io.justsearch.app.util.TempFileManager;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.core.scheduling.BulkWorkGate;
import io.justsearch.gpu.GpuCapabilitiesService;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
final class AutomaticVduLoadedChatTest {
  private static final long IDLE = VduPacingPolicy.DEFAULT_IDLE_THRESHOLD_MS;
  @TempDir Path directory;
  private final TestEngineExecutors executors = TestEngineExecutors.awaitingTermination();
  private final AtomicLong activityAge = new AtomicLong(IDLE + 1_000);
  private final AtomicInteger acknowledged = new AtomicInteger();
  private final List<String> documents = new ArrayList<>();
  private ManagedChatSeam seam;
  private InferenceLifecycleManager manager;
  private OfflineCoordinator coordinator;
  private VduOfflineTriggerSampler sampler;
  private KnowledgeServerBootstrap host;
  private OnlineAiServiceImpl service;
  private RuntimeIntentTestFixture intent;
  private RuntimeReconciler reconciler;
  private AutoCloseable following;

  @BeforeEach void setUp() throws Exception {
    seam = new ManagedChatSeam(directory, 30);
    manager = seam.manager();
    InferenceConfig base = manager.currentConfig();
    Path projector = Files.writeString(directory.resolve("projector.gguf"), "fixture");
    manager.applyConfig(new InferenceConfig(base.serverExecutable(), base.modelPath(), projector,
        base.serverPort(), base.contextSize(), base.gpuLayers(), false),
        InferenceLifecycleManager.RestartPolicy.RESTART_IF_ONLINE);
    manager.switchToOnlineMode();
    idleGeneration();
    intent = new RuntimeIntentTestFixture(directory.resolve("intent"), true);
    reconciler = new RuntimeReconciler(manager, manager::getCurrentMode,
        () -> manager.view().usingExternalLlamaServer(), null, intent.spec(),
        new RuntimeGpuLease(), manager::setActivation, manager::componentRecoveryPending);
    following = reconciler.followHolds(manager.chatHolds());
    reconciler.start();
    reconciler.requestBootConvergence();
    assertTrue(reconciler.awaitQuiescent(10_000));
    idleGeneration();

    EngineAdmissionService admission = mock(EngineAdmissionService.class);
    when(admission.attach(any())).thenAnswer(call -> {
      EngineWorkHandle work = mock(EngineWorkHandle.class);
      when(work.context()).thenReturn(call.getArgument(0, EngineContext.class));
      when(work.cancellationReason()).thenReturn(Optional.empty());
      when(work.onCancel(any())).thenReturn(() -> {});
      when(work.retain()).thenReturn(work);
      return work;
    });
    service = new OnlineAiServiceImpl(admission, manager);
    var processor = new VduProcessor(service, manager.chatHolds(), service,
        new TempFileManager(directory.resolve("images")), new ImagePreparer(), VduMetricCatalog.noop());
    KnowledgeClient index = mock(KnowledgeClient.class);
    for (int i = 0; i < 3; i++) documents.add(image("document-" + i + ".png").toString());
    when(index.countPendingVdu(any())).thenAnswer(call -> documents.size() - acknowledged.get());
    when(index.queryPendingVduDocIds(any())).thenAnswer(call -> List.copyOf(documents));
    when(index.markVduProcessing(anyString(), anyInt(), any())).thenReturn(0);
    when(index.updateVduResult(anyString(), any(), any(), any(), anyInt(), any()))
        .thenAnswer(call -> { acknowledged.incrementAndGet(); return true; });
    var gpu = mock(GpuCapabilitiesService.class);
    when(gpu.snapshot()).thenReturn(VduBatchProcessorTest.gpuSnapshot(24_000_000_000L));
    var capability = new VduCapabilityState();
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

  @Test void idleLoadedChatProcessesOneBoundedPassAndRestoresItsConfiguration() throws Exception {
    InferenceConfig previous = manager.currentConfig();
    sampler.checkOnce();
    assertTrue(awaitFor(() -> acknowledged.get() == 3, 10),
        "idle loaded chat must automatically read the captured image backlog");
    awaitPass();
    assertEquals(3, seam.models().size(), "one initial start, one VDU relaunch, one restoration");
    assertEquals(previous, manager.currentConfig());
    assertTrue(manager.isOnline());
    assertTrue(manager.generationActivity().inFlight() == 0);
  }

  @Test void humanDemandFinishesAfterOnlyTheCurrentDocumentAndOneRestore() throws Exception {
    Files.writeString(seam.fakeHome().resolve("pause-completion"), "pause current model exchange");
    sampler.checkOnce();
    assertTrue(awaitFor(() -> Files.exists(seam.fakeHome().resolve("requests.txt")), 10),
        "automatic pass must reach the real inference request");
    assertTrue(awaitFor(() -> reconciler.current().condition(RuntimeStatus.Axis.PROCEDURE)
        .map(condition -> "VDU_BATCH".equals(condition.status())).orElse(false), 5));
    assertEquals("VDU_BATCH", reconciler.current().condition(RuntimeStatus.Axis.PROCEDURE)
        .orElseThrow().status(), "the existing status procedure identifies the pass for the UI");
    var demanded = manager.chatResidency().demand(new Demand(
        DemandKind.HUMAN_CHAT, TestEngineContexts.ui(), Duration.ofSeconds(30))).toCompletableFuture();
    assertFalse(demanded.isDone(), "the person waits for the document boundary and restoration");
    Files.delete(seam.fakeHome().resolve("pause-completion"));
    demanded.get(5, TimeUnit.SECONDS);
    awaitPass();
    assertEquals(1, acknowledged.get(), "no second document starts after human demand");
    assertEquals(3, seam.models().size());
    assertFalse(manager.currentConfig().vduMode());
    assertTrue(manager.isOnline());
    assertDoesNotThrow(() -> manager.admitExchange(GenerationTracker.Kind.GENERATE, null).close());
    assertEquals("A clear document with visible words.", service.chatCompletion(
        List.of(java.util.Map.of("role", "user", "content", "hello")), 32,
        io.justsearch.app.api.SamplingParams.VDU, TestEngineContexts.ui()).get(2, TimeUnit.SECONDS));
  }

  @Test void activityBetweenDocumentsRestoresChatAndDoesNotRelaunchAgainInTheSameIdlePeriod()
      throws Exception {
    Files.writeString(seam.fakeHome().resolve("pause-completion"), "pause");
    sampler.checkOnce();
    assertTrue(awaitFor(() -> Files.exists(seam.fakeHome().resolve("requests.txt")), 10));
    activityAge.set(0);
    Files.delete(seam.fakeHome().resolve("pause-completion"));
    awaitPass();
    assertEquals(1, acknowledged.get());
    assertFalse(manager.currentConfig().vduMode());
    // No new input: merely seeing a still-idle machine must not loop through relaunches.
    activityAge.set(Long.MAX_VALUE);
    for (int i = 0; i < 5; i++) sampler.checkOnce();
    assertFalse(coordinator.isProcessing());
    assertEquals(3, seam.models().size());
  }

  @Test void generationAuxLeasePowerPressureAndShortIdleAllBlockAutomaticEntry() throws Exception {
    for (var kind : GenerationTracker.Kind.values()) {
      try (var _ = manager.admitExchange(kind, null)) {
        sampler.checkOnce();
        assertFalse(coordinator.isProcessing(), "open " + kind + " lease must block entry");
      }
      idleGeneration();
    }
    try (var _ = ((GenerationTracker) manager.generationActivity()).queue(
        GenerationTracker.Kind.GENERATE, null)) {
      sampler.checkOnce();
      assertFalse(coordinator.isProcessing(), "queued demand blocks automatic entry too");
    }
    manager.admitExchange(GenerationTracker.Kind.GENERATE, null).close();
    sampler.checkOnce();
    assertFalse(coordinator.isProcessing(), "the full window restarts when generation finishes");
    idleGeneration();
    for (var source : List.of(EnergyState.Source.BATTERY, EnergyState.Source.UNKNOWN)) {
      when(host.energyState()).thenReturn(new EnergyState(EnergyState.Intent.FULL, source));
      sampler.checkOnce();
      assertFalse(coordinator.isProcessing(), source + " must not borrow loaded chat");
    }
    when(host.energyState()).thenReturn(new EnergyState(EnergyState.Intent.REDUCED, EnergyState.Source.AC));
    sampler.checkOnce();
    assertFalse(coordinator.isProcessing());
    when(host.energyState()).thenReturn(new EnergyState(EnergyState.Intent.FULL, EnergyState.Source.AC));
    for (var pressure : List.of(PressureSignal.GAME, PressureSignal.EXTERNAL_MEMORY)) {
      manager.chatResidency().reportPressure(pressure);
      sampler.checkOnce();
      assertFalse(coordinator.isProcessing(), pressure + " must block entry");
    }
    manager.chatResidency().reportPressure(PressureSignal.NONE);
    activityAge.set(IDLE - 1);
    sampler.checkOnce();
    assertFalse(coordinator.isProcessing());
    assertEquals(1, seam.models().size());
  }

  @Test void failedAutomaticLaunchRestoresLoadedChatAndBacksOff() throws Exception {
    Files.writeString(seam.fakeHome().resolve("refuse-vision"), "reject VDU config");
    sampler.checkOnce();
    assertTrue(awaitFor(() -> seam.models().size() >= 2, 10), "automatic launch must be attempted");
    awaitPass();
    assertTrue(manager.isOnline(), "failed VDU launch must roll back to loaded chat");
    assertFalse(manager.currentConfig().vduMode());
    int attempts = seam.models().size();
    assertEquals(3, attempts, "failed VDU start and exactly one rollback start");
    for (int i = 0; i < 5; i++) sampler.checkOnce();
    assertEquals(attempts, seam.models().size(), "failure cannot create a relaunch loop");
  }

  @Test void automaticEntryRefusesARacingLeaseWithoutWaitingOrChangingChat() throws Exception {
    var entered = new AtomicInteger();
    var lease = new java.util.concurrent.atomic.AtomicReference<AutoCloseable>();
    BooleanSupplier checkpoint = () -> {
      if (entered.incrementAndGet() == 2) lease.set(manager.admitExchange(GenerationTracker.Kind.AUX, null));
      return true;
    };
    try {
      long started = System.nanoTime();
      assertThrows(Exception.class, () -> automaticHold(checkpoint));
      assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2),
          "automatic admission refuses immediately instead of draining the racing lease");
      assertTrue(entered.get() >= 2, "the candidate must recheck at the controller boundary");
      assertEquals(1, seam.models().size());
      assertTrue(manager.isOnline());
      assertFalse(manager.currentConfig().vduMode());
    } finally { if (lease.get() != null) lease.get().close(); }
  }

  @Test void shutdownDuringAutomaticPassStopsTheServerAndRefusesTheWaitingPerson() throws Exception {
    Files.writeString(seam.fakeHome().resolve("pause-completion"), "pause");
    sampler.checkOnce();
    assertTrue(awaitFor(() -> Files.exists(seam.fakeHome().resolve("requests.txt")), 10));
    var demand = manager.chatResidency().demand(new Demand(
        DemandKind.HUMAN_CHAT, TestEngineContexts.ui(), Duration.ofSeconds(30))).toCompletableFuture();
    seam.quit();
    assertThrows(Exception.class, () -> demand.get(5, TimeUnit.SECONDS));
    assertTrue(awaitFor(seam::noneAlive, 30));
    assertEquals(2, seam.models().size(), "terminal shutdown never restarts chat");
  }

  @Test void explicitPassStillWorksWithoutAnIdleWindow() throws Exception {
    activityAge.set(IDLE + 1);
    manager.admitExchange(GenerationTracker.Kind.GENERATE, null).close();
    var outcome = coordinator.startOfflineProcessing(TestEngineContexts.durableInternal(), ignored -> {})
        .toCompletableFuture().get(30, TimeUnit.SECONDS);
    assertEquals(3, outcome.processed() + outcome.failed());
    assertEquals(3, seam.models().size());
    assertTrue(manager.isOnline());
    assertFalse(manager.currentConfig().vduMode());
  }

  @Test void chatOffAutomaticPathStillReturnsToTheOffIntent() throws Exception {
    following.close();
    reconciler.close();
    intent.close();
    intent = new RuntimeIntentTestFixture(directory.resolve("off-intent"), false);
    reconciler = new RuntimeReconciler(manager, manager::getCurrentMode,
        () -> manager.view().usingExternalLlamaServer(), null, intent.spec(),
        new RuntimeGpuLease(), manager::setActivation, manager::componentRecoveryPending);
    following = reconciler.followHolds(manager.chatHolds());
    reconciler.start();
    reconciler.requestBootConvergence();
    assertTrue(reconciler.awaitQuiescent(10_000));
    assertFalse(manager.isOnline());
    sampler.checkOnce();
    assertTrue(awaitFor(() -> acknowledged.get() == 3, 10));
    awaitPass();
    assertTrue(reconciler.awaitQuiescent(10_000));
    assertFalse(manager.isOnline(), "the existing chat-off automatic path restores the off intent");
    assertFalse(manager.currentConfig().vduMode());
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
      var constructor = VduOfflineTriggerSampler.class.getConstructor(EngineExecutorRegistry.class,
          Supplier.class, Supplier.class, BooleanSupplier.class, Supplier.class);
      return constructor.newInstance(executors, coordinators, hosts, claimed, (Supplier<ChatHolds>) () -> holds);
    } catch (NoSuchMethodException before) {
      return new VduOfflineTriggerSampler(executors, coordinators, hosts, claimed);
    }
  }

  private VduHold automaticHold(BooleanSupplier eligible) throws Exception {
    try {
      return (VduHold) ChatHolds.class.getMethod("holdAutomaticVdu", String.class, Duration.class,
          BooleanSupplier.class).invoke(manager.chatHolds(), "test-auto", Duration.ofMillis(IDLE), eligible);
    } catch (NoSuchMethodException before) {
      assertTrue(manager.chatHolds().vduMayStart(), "loaded idle chat needs automatic hold admission");
      return manager.chatHolds().holdVdu("test-auto");
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof Exception exception) throw exception;
      throw failure;
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
