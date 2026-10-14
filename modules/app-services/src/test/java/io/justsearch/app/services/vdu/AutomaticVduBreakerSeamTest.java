/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.OnlineAiServiceImpl;
import io.justsearch.app.inference.chat.ChatActuationRouter;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.chat.ChatHolds;
import io.justsearch.app.inference.chat.GenerationTracker;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.chat.ManagedChatSeam;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Automatic picture-reading passes that launch llama-server and complete nothing stop after three
 * in a row (b100). Real lifecycle, controller, generation tracker, reconciler, sampler, coordinator
 * and processor; controlled process (fake llama-server), index port and OS probes.
 */
@Timeout(150)
final class AutomaticVduBreakerSeamTest {
  private static final long IDLE = VduPacingPolicy.DEFAULT_IDLE_THRESHOLD_MS;
  private static final String REFUSED = "vdu.breaker_open.refused";
  private static final String ENTRY_FAILED = "vdu.breaker_open.entry_failed";
  @TempDir Path directory;
  private final TestEngineExecutors executors = TestEngineExecutors.awaitingTermination();
  private final List<String> documents = new ArrayList<>();
  private final Map<String, String> states = new ConcurrentHashMap<>();
  private final Map<String, Integer> retries = new ConcurrentHashMap<>();
  private final AtomicInteger acknowledged = new AtomicInteger();
  private final AtomicInteger passes = new AtomicInteger();
  private ManagedChatSeam seam;
  private InferenceLifecycleManager manager;
  private OfflineCoordinator coordinator;
  private VduOfflineTriggerSampler sampler;
  private KnowledgeServerBootstrap host;
  private RuntimeIntentTestFixture intent;
  private RuntimeReconciler reconciler;
  private AutoCloseable following;
  private ChatActuator.DrainHold closedGate;
  private VduCapabilityState capability;
  /** Activity during a pass: the batch's own interrupt check. */
  private final AtomicBoolean activity = new AtomicBoolean();
  /** Interrupts the next pass's thread once, as quit's task cancellation does. */
  private final AtomicBoolean cancelNextPass = new AtomicBoolean();
  /** Added to System.nanoTime() for the breaker's clock, so a test can skip the back-off. */
  private final AtomicLong breakerSkew = new AtomicLong();

  @BeforeEach void setUp() throws Exception {
    seam = new ManagedChatSeam(directory, 30);
    manager = seam.manager();
    InferenceConfig base = manager.currentConfig();
    Path projector = Files.writeString(directory.resolve("projector.gguf"), "fixture");
    manager.applyConfig(new InferenceConfig(base.serverExecutable(), base.modelPath(), projector,
        base.serverPort(), base.contextSize(), base.gpuLayers(), false),
        InferenceLifecycleManager.RestartPolicy.RESTART_IF_ONLINE);
    manager.switchToOnlineMode();
    intent = new RuntimeIntentTestFixture(directory.resolve("intent"), true);
    reconciler = new RuntimeReconciler(manager, manager::getCurrentMode,
        () -> manager.view().usingExternalLlamaServer(), null, intent.spec(),
        manager::setActivation, manager::componentRecoveryPending);
    following = reconciler.followHolds(manager.chatHolds());
    reconciler.start();
    reconciler.requestBootConvergence();
    assertTrue(reconciler.awaitQuiescent(10_000));
    idleGeneration();

    var admission = new io.justsearch.app.engine.EngineAdmissionController(16, 64, 1);
    var service = new OnlineAiServiceImpl(admission, manager);
    var processor = new VduProcessor(service, manager.chatHolds(), service,
        new TempFileManager(directory.resolve("images")), new ImagePreparer(), VduMetricCatalog.noop());
    KnowledgeClient index = mock(KnowledgeClient.class);
    for (int i = 0; i < 3; i++) {
      String id = image("document-" + i + ".png").toString();
      documents.add(id);
      states.put(id, "PENDING");
      retries.put(id, 0);
    }
    when(index.countPendingVdu(any())).thenAnswer(call ->
        (int) states.values().stream().filter("PENDING"::equals).count());
    when(index.queryPendingVduDocIds(any())).thenAnswer(call -> {
      passes.incrementAndGet();
      return documents.stream().filter(id -> "PENDING".equals(states.get(id))).toList();
    });
    when(index.markVduProcessing(anyString(), anyInt(), any())).thenAnswer(call -> {
      String id = call.getArgument(0, String.class);
      states.put(id, "PROCESSING");
      if (cancelNextPass.getAndSet(false)) Thread.currentThread().interrupt();
      return retries.merge(id, 1, Integer::sum) - 1;
    });
    doAnswer(call -> {
      String id = call.getArgument(0, String.class);
      states.put(id, "PENDING");
      retries.merge(id, -1, Integer::sum);
      return null;
    }).when(index).releaseVduProcessing(anyString(), any());
    when(index.updateVduResult(anyString(), any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
      states.put(call.getArgument(0, String.class), "DONE");
      acknowledged.incrementAndGet();
      return true;
    });
    var gpu = mock(GpuCapabilitiesService.class);
    when(gpu.snapshot()).thenReturn(VduBatchProcessorTest.gpuSnapshot(24_000_000_000L));
    capability = new VduCapabilityState();
    var batch = new VduBatchProcessor(processor, gpu, () -> index, VduMetricCatalog.noop(),
        capability, (BooleanSupplier) activity::get);
    coordinator = coordinator(admission, batch, index);
    host = mock(KnowledgeServerBootstrap.class);
    when(host.energyState()).thenReturn(new EnergyState(EnergyState.Intent.FULL, EnergyState.Source.AC));
    when(host.msSinceLastUserActivity(anyLong())).thenReturn(IDLE + 1_000);
    when(host.bulkWork()).thenReturn(new BulkWorkGate());
    sampler = sampler(manager.chatHolds(), () -> !manager.chatHolds().vduMayStart());
  }

  @AfterEach void tearDown() throws Exception {
    try {
      if (closedGate != null) closedGate.close();
      if (sampler != null) sampler.stop();
      if (coordinator != null) coordinator.close();
    } finally {
      try {
        if (following != null) following.close();
        if (reconciler != null) reconciler.close();
        if (intent != null) intent.close();
        if (seam != null) seam.close();
      } finally { executors.close(); }
    }
  }

  // B1: generation is held closed while chat is off; each automatic pass starts chat, relaunches it
  // for vision and has every document refused.
  @Test void chatOffRefusedPassesStopRelaunchingLlamaServerAfterThree() throws Exception {
    switchChatOff();
    closedGate = ((GenerationTracker) manager.generationActivity()).closeNow();
    openByThreeLaunchingTicks();
    assertEquals(REFUSED, capability.snapshot().blockedReason(),
        "status shows automatic picture reading paused because every attempt was refused");
    assertTrue(states.values().stream().allMatch("PENDING"::equals), "document states unchanged: " + states);
    assertTrue(retries.values().stream().allMatch(count -> count == 0), "refusals spend no attempt: " + retries);
  }

  // The chat-off failed-vision-launch loop: the fake server refuses its vision configuration.
  @Test void chatOffFailedVisionEntriesStopRelaunchingLlamaServerAfterThree() throws Exception {
    switchChatOff();
    Files.writeString(seam.fakeHome().resolve("refuse-vision"), "reject VDU config");
    openByThreeLaunchingTicks();
    assertEquals(ENTRY_FAILED, capability.snapshot().blockedReason(),
        "status tells a failed vision entry apart from refused documents");
    assertTrue(retries.values().stream().allMatch(count -> count == 0), "no attempt spent: " + retries);
  }

  // A chat-off automatic pass under the settings-recovery fence starts llama-server, cannot enter
  // picture reading, and must not leave that server running.
  @Test void refusedChatOffEntryUnderTheRecoveryFenceLeavesNoLlamaServerRunning() throws Exception {
    switchChatOff();
    manager.fenceForSettingsRecovery();
    int before = seam.models().size();
    sampler.checkOnce();
    long passEnds = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (coordinator.isProcessing() && System.nanoTime() < passEnds) Thread.sleep(10);
    assertFalse(coordinator.isProcessing(), "pass must release ownership");
    assertTrue(seam.models().size() > before, "the automatic pass launched llama-server");
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while ((manager.isOnline() || !seam.noneAlive()) && System.nanoTime() < until) Thread.sleep(20);
    assertFalse(manager.isOnline(), "chat is off: the server started for picture reading is stopped");
    assertTrue(seam.noneAlive(), "no llama-server process is left running");
  }

  // B4 through the real controller: each close event lets the next automatic pass run at once.
  @Test void committedSettingsApplyClosesTheBreaker() throws Exception {
    openRefused();
    manager.applyConfig(manager.currentConfig(), InferenceLifecycleManager.RestartPolicy.APPLY_ONLY);
    assertNextAutomaticPassRuns("a committed settings apply");
  }

  @Test void preparedSettingsPublishClosesTheBreaker() throws Exception {
    openRefused();
    var next = seam.withModel("prepared-breaker.gguf");
    var resolvedMethod = ManagedChatSeam.class.getDeclaredMethod("resolvedWithModel", String.class);
    resolvedMethod.setAccessible(true);
    var resolved = (io.justsearch.configuration.resolved.ResolvedConfig) resolvedMethod.invoke(seam,
        "prepared-breaker.gguf");
    var prepared = manager.prepareResolvedConfig(next, resolved, true, true);
    prepared.withLifecycleLock(prepared::installAfterSettingsCommit);
    prepared.notifyAfterSettingsCommit();
    prepared.retireAfterSettingsCommit();
    assertNextAutomaticPassRuns("a committed prepared settings publish");
  }

  @Test void switchingChatOffClosesTheBreaker() throws Exception {
    openRefused();
    manager.setActivation(new ChatActuationRouter.Activation(false, TransitionReason.USER_SWITCH))
        .toCompletableFuture().get(20, TimeUnit.SECONDS);
    assertNextAutomaticPassRuns("a person switching chat off");
  }

  @Test void anExplicitPassClosesTheBreaker() throws Exception {
    openRefused();
    explicit().toCompletableFuture().get(60, TimeUnit.SECONDS);
    awaitPass();
    assertNextAutomaticPassRuns("a person's explicit pass");
  }

  // B5: an explicit pass while the breaker is open runs normally.
  @Test void anExplicitPassWhileOpenReadsTheDocuments() throws Exception {
    openRefused();
    closedGate.close();
    closedGate = null;
    var outcome = explicit().toCompletableFuture().get(60, TimeUnit.SECONDS);
    awaitPass();
    assertEquals(3, outcome.processed(), "the explicit pass read every document: " + outcome);
    assertEquals(3, acknowledged.get());
    assertNotEquals(REFUSED, capability.snapshot().blockedReason());
  }

  // b100 counting rule: a refusal of the entry that launched nothing (here GPU pressure that began
  // after the sampler's check) neither counts nor resets. On bc5978095 nothing pauses, so the pass
  // after it runs there too.
  @Test void anEntryRefusalThatLaunchedNothingNeitherCountsNorResets() throws Exception {
    switchChatOff();
    closedGate = ((GenerationTracker) manager.generationActivity()).closeNow();
    refusedTicks(2);
    ChatHolds holds = manager.chatHolds();
    var residency = (io.justsearch.app.inference.chat.ChatResidency) holds;
    var racing = sampler(holds, () -> false);
    try {
      residency.reportPressure(io.justsearch.app.api.chat.PressureSignal.EXTERNAL_MEMORY);
      int launches = seam.models().size();
      int ran = passes.get();
      racing.checkOnce();
      awaitPass();
      assertEquals(ran + 1, passes.get(), "the automatic pass started and was refused at entry");
      assertEquals(launches, seam.models().size(), "the refused entry launched nothing");
    } finally {
      residency.reportPressure(io.justsearch.app.api.chat.PressureSignal.NONE);
      racing.stop();
    }
    refusedTick("the refusal that launched nothing did not count: this automatic pass runs");
    if (breakerPresent()) {
      assertPaused("and it did not reset: this was the third counted pass in a row");
    }
    assertTrue(retries.values().stream().allMatch(count -> count == 0), "no attempt spent: " + retries);
  }

  // B2 at the seam: two refused passes, one that completes documents, two refused: still running;
  // the third refused pass since the progress pauses.
  @Test void aPassThatCompletesDocumentsResetsTheCount() throws Exception {
    switchChatOff();
    closedGate = ((GenerationTracker) manager.generationActivity()).closeNow();
    refusedTicks(2);
    closedGate.close();
    closedGate = null;
    int read = acknowledged.get();
    tick();
    assertTrue(acknowledged.get() > read, "the progressing pass completed documents");
    resetDocuments();
    closedGate = ((GenerationTracker) manager.generationActivity()).closeNow();
    refusedTicks(2);
    refusedTick("the count restarted at the progressing pass, so this pass runs");
    assertPaused("the third refused pass in a row since progress pauses automatic passes");
    assertEquals(REFUSED, capability.snapshot().blockedReason());
  }

  // A refusal mixed with a real failure in one pass is progress: the failed document spends its
  // attempt, so the count resets.
  @Test void aRefusalMixedWithARealFailureCountsAsProgress() throws Exception {
    switchChatOff();
    closedGate = ((GenerationTracker) manager.generationActivity()).closeNow();
    refusedTicks(2);
    String missing = directory.resolve("missing.png").toString();
    states.put(missing, "PENDING");
    retries.put(missing, 0);
    documents.add(0, missing);
    refusedTick("the mixed pass runs");
    assertEquals(1, retries.get(missing), "the missing document really failed and spent an attempt");
    assertNotEquals("PENDING", states.get(missing), "the failed document left the backlog");
    refusedTicks(2);
    refusedTick("the mixed pass reset the count, so this pass runs");
    assertPaused("the third refused pass in a row since the mixed pass pauses automatic passes");
  }

  // B3 at the seam: the probe after 5 minutes, then 10, 20, 40 and at most 60; progress closes.
  @Test @Timeout(300) void halfOpenProbeBacksOffFiveTenTwentyFortyThenSixtyAndClosesOnProgress()
      throws Exception {
    switchChatOff();
    closedGate = ((GenerationTracker) manager.generationActivity()).closeNow();
    refusedTicks(3);
    assertPaused("three refused passes pause automatic passes");
    String detail = detail();
    assertNotNull(detail, "status keeps the pause's detail");
    assertTrue(detail.contains("Next try at "), "status says when the next try is: " + detail);
    for (long minutes : new long[] {5, 10, 20, 40, 60, 60}) {
      breakerSkew.addAndGet(TimeUnit.MINUTES.toNanos(minutes) - TimeUnit.SECONDS.toNanos(60));
      assertPaused("no probe before the " + minutes + " min back-off ends");
      breakerSkew.addAndGet(TimeUnit.SECONDS.toNanos(60));
      refusedTick("one probe pass after " + minutes + " min");
      assertEquals(REFUSED, capability.snapshot().blockedReason(), "a refused probe pauses again");
    }
    breakerSkew.addAndGet(TimeUnit.MINUTES.toNanos(60));
    closedGate.close();
    closedGate = null;
    int read = acknowledged.get();
    tick();
    assertTrue(acknowledged.get() > read, "the probe completed documents");
    assertNotEquals(REFUSED, capability.snapshot().blockedReason(), "progress ends the pause");
    resetDocuments();
    closedGate = ((GenerationTracker) manager.generationActivity()).closeNow();
    refusedTick("after progress automatic passes run without waiting");
  }

  // A pass interrupted by activity entered picture reading but neither counts nor resets.
  @Test void activityInterruptedPassNeitherCountsNorResets() throws Exception {
    switchChatOff();
    closedGate = ((GenerationTracker) manager.generationActivity()).closeNow();
    refusedTicks(2);
    activity.set(true);
    try {
      refusedTick("the interrupted pass enters");
    } finally {
      activity.set(false);
    }
    refusedTick("the interrupted pass did not count: this is only the third refused pass");
    assertPaused("and it did not reset: three refused passes in a row pause automatic passes");
    assertEquals(REFUSED, capability.snapshot().blockedReason());
  }

  // A pass cancelled the way quit cancels it (its thread interrupted mid-pass) neither counts nor
  // resets.
  @Test void cancelledPassNeitherCountsNorResets() throws Exception {
    switchChatOff();
    closedGate = ((GenerationTracker) manager.generationActivity()).closeNow();
    refusedTicks(2);
    cancelNextPass.set(true);
    refusedTick("the cancelled pass enters");
    assertFalse(cancelNextPass.get(), "the pass was cancelled at its first document");
    resetDocuments();
    refusedTick("the cancelled pass did not count: this is only the third refused pass");
    assertPaused("and it did not reset: three refused passes in a row pause automatic passes");
    assertEquals(REFUSED, capability.snapshot().blockedReason());
  }

  private void tick() throws Exception {
    sampler.checkOnce();
    awaitPass();
  }

  private void refusedTicks(int count) throws Exception {
    for (int i = 1; i <= count; i++) refusedTick("automatic pass " + i + " runs");
  }

  /** One automatic pass that runs and launches llama-server. */
  private void refusedTick(String why) throws Exception {
    int launches = seam.models().size();
    int ran = passes.get();
    tick();
    assertEquals(ran + 1, passes.get(), why);
    assertTrue(seam.models().size() > launches, why + ": it launches llama-server");
  }

  /** One tick that starts no automatic pass and launches nothing. */
  private void assertPaused(String why) throws Exception {
    int launches = seam.models().size();
    int ran = passes.get();
    tick();
    assertEquals(ran, passes.get(), why + ": no automatic pass enters picture reading");
    assertEquals(launches, seam.models().size(), why + ": llama-server is not relaunched");
  }

  private void resetDocuments() {
    for (String id : documents) {
      states.put(id, "PENDING");
      retries.put(id, 0);
    }
  }

  private static boolean breakerPresent() {
    try {
      OfflineCoordinator.class.getMethod("automaticBreaker");
      return true;
    } catch (NoSuchMethodException beforeBreaker) {
      return false;
    }
  }

  /** The pause detail the Head keeps, or null where the capability snapshot has none. */
  private String detail() throws Exception {
    Object snapshot = capability.snapshot();
    try {
      return (String) snapshot.getClass().getMethod("detail").invoke(snapshot);
    } catch (NoSuchMethodException beforeBreaker) {
      return null;
    }
  }

  private OfflineCoordinator coordinator(io.justsearch.app.engine.EngineAdmissionController admission,
      VduBatchProcessor batch, KnowledgeClient index) throws Exception {
    Supplier<KnowledgeClient> clients = () -> index;
    try {
      return OfflineCoordinator.class.getConstructor(EngineExecutorRegistry.class,
              io.justsearch.app.api.EngineAdmissionService.class, VduBatchProcessor.class,
              Supplier.class, VduCapabilityState.class, java.util.function.LongSupplier.class)
          .newInstance(executors, admission, batch, clients, capability,
              (java.util.function.LongSupplier) () -> System.nanoTime() + breakerSkew.get());
    } catch (NoSuchMethodException beforeBreaker) {
      return new OfflineCoordinator(executors, admission, batch, clients, capability);
    }
  }

  private void openRefused() throws Exception {
    switchChatOff();
    closedGate = ((GenerationTracker) manager.generationActivity()).closeNow();
    openByThreeLaunchingTicks();
    assertEquals(REFUSED, capability.snapshot().blockedReason());
  }

  /** Three automatic passes that launch llama-server, then ticks that launch nothing. */
  private void openByThreeLaunchingTicks() throws Exception {
    for (int i = 1; i <= 3; i++) {
      int launches = seam.models().size();
      sampler.checkOnce();
      awaitPass();
      assertTrue(seam.models().size() > launches, "automatic pass " + i + " launches llama-server");
    }
    int launches = seam.models().size();
    int ran = passes.get();
    for (int i = 4; i <= 6; i++) {
      sampler.checkOnce();
      awaitPass();
    }
    assertEquals(launches, seam.models().size(),
        "after three automatic passes without progress no automatic pass relaunches llama-server; "
            + "launches: " + seam.models());
    assertEquals(ran, passes.get(), "no automatic pass enters picture reading while paused");
  }

  private void assertNextAutomaticPassRuns(String event) throws Exception {
    assertNotEquals(REFUSED, capability.snapshot().blockedReason(), event + " clears the pause");
    int ran = passes.get();
    sampler.checkOnce();
    awaitPass();
    assertEquals(ran + 1, passes.get(), "after " + event + " the next automatic pass runs at once");
  }

  private CompletionStage<OfflineProcessingOutcome> explicit() throws Exception {
    var context = TestEngineContexts.ui();
    Consumer<OfflineProcessingOutcome> progress = outcome -> {};
    try {
      @SuppressWarnings("unchecked")
      var stage = (CompletionStage<OfflineProcessingOutcome>) OfflineCoordinator.class
          .getMethod("startExplicitProcessing", io.justsearch.core.context.EngineContext.class,
              Consumer.class)
          .invoke(coordinator, context, progress);
      return stage;
    } catch (NoSuchMethodException beforeBreaker) {
      return coordinator.startOfflineProcessing(context, progress);
    }
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
    assertFalse(manager.isOnline());
  }

  private void idleGeneration() throws Exception {
    var activity = manager.generationActivity();
    var field = activity.getClass().getDeclaredField("idleSinceNanos");
    field.setAccessible(true);
    field.setLong(activity, System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(IDLE + 1_000));
  }

  private VduOfflineTriggerSampler sampler(ChatHolds holds, BooleanSupplier claimed) throws Exception {
    Supplier<OfflineCoordinator> coordinators = () -> coordinator;
    Supplier<KnowledgeServerBootstrap> hosts = () -> host;
    var clocked = VduOfflineTriggerSampler.class.getDeclaredConstructor(EngineExecutorRegistry.class,
        Supplier.class, Supplier.class, BooleanSupplier.class, Supplier.class,
        java.util.function.LongSupplier.class);
    clocked.setAccessible(true);
    return clocked.newInstance(executors, coordinators, hosts, claimed,
        (Supplier<ChatHolds>) () -> holds, (java.util.function.LongSupplier) () -> IDLE + 1_000);
  }

  private void awaitPass() throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (coordinator.isProcessing() && System.nanoTime() < until) Thread.sleep(10);
    assertFalse(coordinator.isProcessing(), "pass must release ownership");
    assertTrue(reconciler.awaitQuiescent(20_000), "the reconciler settles after the pass");
    Thread.sleep(100); // the sampler records the finished pass in the stage's completion callback
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
