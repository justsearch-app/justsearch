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
import io.justsearch.app.services.runtimestate.RuntimeGpuLease;
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
        new RuntimeGpuLease(), manager::setActivation, manager::componentRecoveryPending);
    following = reconciler.followHolds(manager.chatHolds());
    reconciler.start();
    reconciler.requestBootConvergence();
    assertTrue(reconciler.awaitQuiescent(10_000));
    idleGeneration();

    admission = new io.justsearch.app.engine.EngineAdmissionController(16, 64, 1);
    service = new OnlineAiServiceImpl(admission, manager);
    var processor = new VduProcessor(service, manager.chatHolds(), service,
        new TempFileManager(directory.resolve("images")), new ImagePreparer(), VduMetricCatalog.noop());
    KnowledgeClient index = mock(KnowledgeClient.class);
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

  private void switchChatOff() throws Exception {
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
