/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.OnlineAiServiceImpl;
import io.justsearch.app.inference.chat.ChatHolds;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.chat.ManagedChatSeam;
import io.justsearch.app.services.chat.SlowStartFakeLlamaServer;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.util.EnergyState;
import io.justsearch.app.util.TempFileManager;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.core.scheduling.BulkWorkGate;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.core.scheduling.IndexingHold;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.VduUpdateOutcome;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

/**
 * The picture-reading seam for the resume checks: the real lifecycle manager launching {@link
 * SlowStartFakeLlamaServer} processes, the real chat controller, reconciler, generation tracker,
 * admission, sampler, coordinator, batch and document processor. The index port, OS probes (energy,
 * user activity, bulk and indexing holds) and the clocks of the sampler and breaker are
 * controlled.
 *
 * <p>Time: {@link #advance} moves one virtual clock forward for every clock that paces automatic
 * passes and that a test cannot wait for in real time: the breaker's back-off, the sampler's
 * recorded wall times, the last user activity and the generation idle window. Nothing else
 * happens in between, so an advance never contains user activity.
 *
 * <p>A crash is the real thing: the fake server process is killed with {@code SIGKILL} while it is
 * READY, as a crashing llama-server ends, and the lifecycle manager's own exit handling decides.
 */
final class VduResumeSeam implements AutoCloseable {
  static final long IDLE = VduPacingPolicy.DEFAULT_IDLE_THRESHOLD_MS;
  static final int DOCUMENTS = 3;

  final Path directory;
  final TestEngineExecutors executors = TestEngineExecutors.awaitingTermination();
  final ManagedChatSeam seam;
  final InferenceLifecycleManager manager;
  final InferenceConfig loadedConfig;
  final EngineAdmissionController admission;
  final OnlineAiServiceImpl service;
  final KnowledgeClient index;
  final VduCapabilityState capability = new VduCapabilityState();
  final OfflineCoordinator coordinator;
  final VduOfflineTriggerSampler sampler;
  final KnowledgeServerBootstrap host;
  final BulkWorkGate bulk = new BulkWorkGate();
  final IndexingHold indexingHold = new IndexingHold();
  RuntimeIntentTestFixture intent;
  RuntimeReconciler reconciler;
  AutoCloseable following;

  final List<String> documents = new ArrayList<>();
  final Map<String, String> states = new ConcurrentHashMap<>();
  final Map<String, Integer> retries = new ConcurrentHashMap<>();
  final Map<String, List<VduUpdateOutcome>> outcomes = new ConcurrentHashMap<>();
  final List<String> released = new CopyOnWriteArrayList<>();
  /** Passes that selected their documents (one per procedure that reached the batch). */
  final AtomicInteger passes = new AtomicInteger();
  /** Procedures that started (the coordinator asked for its index client on its own thread). */
  final AtomicInteger procedures = new AtomicInteger();

  final AtomicReference<EnergyState> energy =
      new AtomicReference<>(new EnergyState(EnergyState.Intent.FULL, EnergyState.Source.AC));
  /** Wall time of the last user input, in the virtual clock. */
  final AtomicLong activityAt = new AtomicLong();
  final AtomicLong breakerSkew = new AtomicLong();
  /** Runs when a document is claimed (after the claim is recorded). */
  volatile Consumer<String> onClaim = id -> {};
  /** Runs when a document result is acknowledged (after the write is recorded). */
  volatile Consumer<String> onResult = id -> {};
  /** Runs once, on the procedure's own thread, before it takes the indexing lease. */
  final AtomicReference<Runnable> beforeLease = new AtomicReference<>();

  VduResumeSeam(Path directory, boolean chatLoaded) throws Exception {
    this.directory = directory;
    activityAt.set(System.currentTimeMillis() - IDLE - 60_000);
    seam = new ManagedChatSeam(directory, 30);
    manager = seam.manager();
    InferenceConfig base = manager.currentConfig();
    Path projector = Files.writeString(directory.resolve("projector.gguf"), "fixture");
    manager.applyConfig(new InferenceConfig(base.serverExecutable(), base.modelPath(), projector,
        base.serverPort(), base.contextSize(), base.gpuLayers(), false, "qwen3-4b"),
        InferenceLifecycleManager.RestartPolicy.RESTART_IF_ONLINE);
    manager.switchToOnlineMode();
    loadedConfig = manager.currentConfig();
    follow(chatLoaded);
    idleGeneration();

    admission = new EngineAdmissionController(16, 64, 1);
    service = new OnlineAiServiceImpl(admission, manager);
    var processor = new VduProcessor(service, manager.chatHolds(), service,
        new TempFileManager(directory.resolve("images")), new ImagePreparer(), VduMetricCatalog.noop());
    index = VduAttemptTestSupport.client(call -> {
      String method = call.getMethod().getName();
      if ("recoverVduProcessingForOwner".equals(method)) return recover();
      if ("recoverVduProcessingForRetainedOwner".equals(method)) return recover();
      if ("indexingHold".equals(method)) return indexingHold;
      return RETURNS_DEFAULTS.answer(call);
    });
    for (int i = 0; i < DOCUMENTS; i++) {
      String id = image("document-" + i + ".png").toString();
      documents.add(id);
      states.put(id, "PENDING");
      retries.put(id, 0);
    }
    when(index.indexingHold()).thenReturn(indexingHold);
    when(index.countPendingVdu(any())).thenAnswer(call -> pending());
    when(index.recoverVduProcessing(any())).thenAnswer(call -> recover());
    when(index.queryPendingVduDocIds(any())).thenAnswer(call -> {
      passes.incrementAndGet();
      return documents.stream().filter(id -> "PENDING".equals(states.get(id))).toList();
    });
    when(index.markVduProcessing(anyString(), anyInt(), any())).thenAnswer(call -> {
      String id = call.getArgument(0, String.class);
      int max = call.getArgument(1, Integer.class);
      int spent = retries.get(id);
      if (spent >= max) return -1;
      states.put(id, "PROCESSING");
      retries.put(id, spent + 1);
      onClaim.accept(id);
      return spent;
    });
    doAnswer(call -> {
      String id = call.getArgument(0, String.class);
      states.put(id, "PENDING");
      retries.merge(id, -1, Integer::sum);
      released.add(id);
      return true;
    }).when(index).releaseVduProcessing(anyString(), any());
    when(index.updateVduResult(anyString(), any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
      String id = call.getArgument(0, String.class);
      VduUpdateOutcome outcome = call.getArgument(2, VduUpdateOutcome.class);
      outcomes.computeIfAbsent(id, ignored -> new CopyOnWriteArrayList<>()).add(outcome);
      // As the Worker's result writer: a failure goes back to PENDING until its retries run out.
      if (outcome == VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED) {
        states.put(id, retries.get(id) >= SchemaFields.VDU_MAX_RETRIES ? "FAILED" : "PENDING");
      } else {
        states.put(id, "DONE");
      }
      onResult.accept(id);
      return true;
    });
    Supplier<KnowledgeClient> clients = () -> {
      if ("offline-procedure".equals(Thread.currentThread().getName())) {
        procedures.incrementAndGet();
        Runnable hook = beforeLease.getAndSet(null);
        if (hook != null) hook.run();
      }
      return index;
    };
    var gpu = mock(GpuCapabilitiesService.class);
    when(gpu.snapshot()).thenReturn(VduBatchProcessorTest.gpuSnapshot(24_000_000_000L));
    // As the Engine wires it: activity recorded after the pass was accepted interrupts it.
    Supplier<BooleanSupplier> interrupt = () -> {
      long acceptedAt = System.currentTimeMillis();
      return () -> activityAt.get() >= acceptedAt;
    };
    var batch = new VduBatchProcessor(processor, gpu, clients, VduMetricCatalog.noop(),
        capability, interrupt);
    coordinator = new OfflineCoordinator(executors, admission, batch, clients, capability,
        () -> System.nanoTime() + breakerSkew.get());
    host = mock(KnowledgeServerBootstrap.class);
    when(host.energyState()).thenAnswer(call -> energy.get());
    when(host.msSinceLastUserActivity(anyLong()))
        .thenAnswer(call -> Math.max(0, call.getArgument(0, Long.class) - activityAt.get()));
    when(host.bulkWork()).thenReturn(bulk);
    when(host.gpuScheduling()).thenReturn(new GpuSchedulingGauge(indexingHold));
    sampler = sampler(manager.chatHolds());
  }

  // ==================== chat intent ====================

  void follow(boolean chatLoaded) throws Exception {
    if (following != null) following.close();
    if (reconciler != null) reconciler.close();
    if (intent != null) intent.close();
    intent = new RuntimeIntentTestFixture(
        directory.resolve(chatLoaded ? "intent-on-" + System.nanoTime() : "intent-off-" + System.nanoTime()),
        chatLoaded);
    reconciler = new RuntimeReconciler(manager, manager::getCurrentMode,
        () -> manager.view().usingExternalLlamaServer(), null, intent.spec(),
        manager::setActivation, manager::componentRecoveryPending);
    following = reconciler.followHolds(manager.chatHolds());
    reconciler.start();
    reconciler.requestBootConvergence();
    assertTrue(reconciler.awaitQuiescent(10_000));
    assertEquals(chatLoaded, manager.isOnline(), "chat intent " + (chatLoaded ? "on" : "off"));
  }

  // ==================== passes ====================

  /** One sampler tick and, when it started a pass, that pass to its end. */
  void tick() throws Exception {
    sampler.checkOnce();
    awaitPass();
  }

  /** A sampler tick that starts a pass whose first model request is held; returns at the request. */
  void tickHeldAtFirstRequest() throws Exception {
    int before = requests();
    Files.writeString(seam.fakeHome().resolve("pause-completion"), "hold the model exchange");
    sampler.checkOnce();
    assertTrue(awaitFor(() -> requests() > before, 20),
        "the automatic pass must reach a model request; passes=" + passes + " states=" + states);
  }

  /** An explicit pass whose first model request is held; returns the pass and waits for the request. */
  CompletionStage<OfflineProcessingOutcome> explicitHeldAtFirstRequest() throws Exception {
    int before = requests();
    Files.writeString(seam.fakeHome().resolve("pause-completion"), "hold the model exchange");
    var pass = coordinator.startExplicitProcessing(TestEngineContexts.ui(), outcome -> {});
    assertTrue(awaitFor(() -> requests() > before, 20), "the explicit pass must reach a model request");
    return pass;
  }

  /** Kills the newest llama-server process the way a crash ends it, and waits until it is gone. */
  long killNewestServer() throws Exception {
    var launch = SlowStartFakeLlamaServer.launches(seam.fakeHome()).getLast();
    ProcessHandle server = ProcessHandle.of(launch.pid())
        .filter(handle -> handle.info().startInstant().filter(launch.birth()::equals).isPresent())
        .orElseThrow(() -> new AssertionError("the newest llama-server is not running: " + launch));
    server.destroyForcibly();
    server.onExit().get(10, TimeUnit.SECONDS);
    return launch.pid();
  }

  /** The newest launch was started in picture-reading mode ({@code -np 1}). */
  boolean newestIsVision() throws Exception {
    return Files.readString(seam.fakeHome().resolve("last-args.txt")).contains("-np\n1");
  }

  void releaseRequests() throws Exception {
    Files.deleteIfExists(seam.fakeHome().resolve("pause-completion"));
  }

  /** Kills the vision server at the held first request of an automatic pass, then waits it out. */
  void automaticPassWithVisionCrash() throws Exception {
    tickHeldAtFirstRequest();
    assertTrue(newestIsVision(), "the request is served by the picture-reading server");
    killNewestServer();
    releaseRequests();
    awaitPass();
  }

  void awaitPass() throws Exception {
    assertTrue(awaitFor(() -> !coordinator.isProcessing(), 40), "the pass must release ownership");
    if (reconciler != null) assertTrue(reconciler.awaitQuiescent(20_000), "the reconciler settles");
    Thread.sleep(150); // the sampler records the finished pass in the stage's completion callback
  }

  /**
   * Lets virtual time pass with no user activity, ticking the sampler after each step, until every
   * document has been read or {@code limit} of virtual time has passed. Steps of a little more than
   * the idle window, so an idle-window deferral, the sampler's retry window and every breaker
   * back-off (5 to 60 minutes) can elapse within the limit.
   */
  boolean resumesWithoutUserAction(Duration limit) throws Exception {
    Duration step = Duration.ofMillis(IDLE + 1_000);
    for (Duration passed = Duration.ZERO; passed.compareTo(limit) <= 0; passed = passed.plus(step)) {
      if (allRead()) return true;
      tick();
      if (allRead()) return true;
      advance(step);
    }
    return allRead();
  }

  /** Every document was read with a successful result. */
  boolean allRead() {
    return documents.stream().allMatch(id -> "DONE".equals(states.get(id))
        && outcomes.getOrDefault(id, List.of()).stream()
            .anyMatch(outcome -> outcome == VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT));
  }

  int launches() {
    return seam.models().size();
  }

  int requests() {
    try {
      Path file = seam.fakeHome().resolve("requests.txt");
      return Files.exists(file) ? Files.readAllLines(file).size() : 0;
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  // ==================== virtual time ====================

  /**
   * Moves the virtual clock forward: the breaker's back-off clock, every wall time the sampler
   * recorded (any of its long fields holding an epoch-millisecond value), the last user input and
   * the generation tracker's idle start. Call only between passes.
   */
  void advance(Duration by) throws Exception {
    long millis = by.toMillis();
    breakerSkew.addAndGet(by.toNanos());
    activityAt.addAndGet(-millis);
    long now = System.currentTimeMillis();
    for (Field field : VduOfflineTriggerSampler.class.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers()) || field.getType() != long.class) continue;
      field.setAccessible(true);
      long value = field.getLong(sampler);
      // An epoch-millisecond reading of this run: within a day of now (virtual or real).
      if (Math.abs(value - now) < TimeUnit.DAYS.toMillis(1)) field.setLong(sampler, value - millis);
    }
    var activity = manager.generationActivity();
    Field idle = activity.getClass().getDeclaredField("idleSinceNanos");
    idle.setAccessible(true);
    long since = idle.getLong(activity);
    if (since != Long.MIN_VALUE) idle.setLong(activity, since - by.toNanos());
  }

  /** The generation tracker has been idle for longer than the automatic window. */
  void idleGeneration() throws Exception {
    var activity = manager.generationActivity();
    Field field = activity.getClass().getDeclaredField("idleSinceNanos");
    field.setAccessible(true);
    field.setLong(activity, System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(IDLE + 1_000));
  }

  /** User input now. */
  void userActivity() {
    activityAt.set(System.currentTimeMillis());
  }

  // ==================== index ====================

  int pending() {
    return (int) states.values().stream().filter("PENDING"::equals).count();
  }

  private int recover() {
    int recovered = 0;
    for (var entry : states.entrySet()) {
      if ("PROCESSING".equals(entry.getValue())) {
        entry.setValue("PENDING");
        recovered++;
      }
    }
    return recovered;
  }

  List<VduUpdateOutcome> outcomesOf(String id) {
    return outcomes.getOrDefault(id, List.of());
  }

  // ==================== plumbing ====================

  private VduOfflineTriggerSampler sampler(ChatHolds holds) throws Exception {
    Supplier<OfflineCoordinator> coordinators = () -> coordinator;
    Supplier<KnowledgeServerBootstrap> hosts = () -> host;
    BooleanSupplier claimed = () -> !holds.vduMayStart();
    var clocked = VduOfflineTriggerSampler.class.getDeclaredConstructor(EngineExecutorRegistry.class,
        Supplier.class, Supplier.class, BooleanSupplier.class, Supplier.class,
        java.util.function.LongSupplier.class);
    clocked.setAccessible(true);
    return clocked.newInstance(executors, coordinators, hosts, claimed,
        (Supplier<ChatHolds>) () -> holds, (java.util.function.LongSupplier) () -> Long.MAX_VALUE / 4);
  }

  static boolean awaitFor(BooleanSupplier condition, int seconds) throws Exception {
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

  @Override
  public void close() throws Exception {
    Files.deleteIfExists(seam.fakeHome().resolve("pause-completion"));
    Files.deleteIfExists(seam.fakeHome().resolve("refuse-vision"));
    indexingHold.resume();
    try {
      sampler.stop();
      coordinator.close();
    } finally {
      try {
        if (following != null) following.close();
        if (reconciler != null) reconciler.close();
        if (intent != null) intent.close();
        seam.close();
      } finally {
        executors.close();
      }
    }
  }
}
