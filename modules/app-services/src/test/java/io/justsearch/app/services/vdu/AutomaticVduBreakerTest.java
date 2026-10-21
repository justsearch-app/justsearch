/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.app.api.chat.GenerationUnavailableException;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.util.EnergyState;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.core.scheduling.BulkWorkGate;
import io.justsearch.gpu.GpuCapabilitiesService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The automatic picture-reading breaker over the production idle sampler, pass owner and batch
 * processor. The model boundary (VDU mode entry and the per-document model call) and the index port
 * are scripted; the breaker's back-off runs on a test clock, so no step waits for real time.
 * Automatic passes use the chat-off path, which has no other back-off: every admitted tick enters.
 */
@Timeout(60)
final class AutomaticVduBreakerTest {
  private static final String REFUSED = "vdu.breaker_open.refused";
  private static final String ENTRY_FAILED = "vdu.breaker_open.entry_failed";
  private static final long MINUTE = TimeUnit.MINUTES.toNanos(1);

  enum Action { REFUSE, COMPLETE, FAIL, CANCEL }

  @TempDir Path directory;
  private final TestEngineExecutors executors = TestEngineExecutors.awaitingTermination();
  private final AtomicLong clock = new AtomicLong(1_000_000_000L);
  private final AtomicInteger entries = new AtomicInteger();
  private final AtomicBoolean entryFails = new AtomicBoolean();
  private final AtomicBoolean activity = new AtomicBoolean();
  private final Deque<Action> script = new ArrayDeque<>();
  private volatile Action otherwise = Action.REFUSE;
  private final List<String> documents = new ArrayList<>();
  private final Map<String, String> states = new ConcurrentHashMap<>();
  private final Map<String, Integer> retries = new ConcurrentHashMap<>();
  private EngineAdmissionService admission;
  private VduProcessor processor;
  private KnowledgeClient index;
  private VduCapabilityState capability;
  private OfflineCoordinator coordinator;
  private VduOfflineTriggerSampler sampler;

  @BeforeEach void setUp() throws Exception {
    for (int i = 0; i < 12; i++) {
      String id = Files.writeString(directory.resolve("scan-" + i + ".png"), "scan").toString();
      documents.add(id);
      states.put(id, "PENDING");
      retries.put(id, 0);
    }
    index = VduAttemptTestSupport.client();
    when(index.countPendingVdu(any())).thenAnswer(call ->
        (int) states.values().stream().filter("PENDING"::equals).count());
    when(index.queryPendingVduDocIds(any())).thenAnswer(call ->
        documents.stream().filter(id -> "PENDING".equals(states.get(id))).toList());
    when(index.markVduProcessing(anyString(), anyInt(), any())).thenAnswer(call -> {
      String id = call.getArgument(0, String.class);
      states.put(id, "PROCESSING");
      return retries.merge(id, 1, Integer::sum) - 1;
    });
    doAnswer(call -> {
      String id = call.getArgument(0, String.class);
      states.put(id, "PENDING");
      retries.merge(id, -1, Integer::sum);
      return null;
    }).when(index).releaseVduProcessing(anyString(), any());
    when(index.updateVduResult(anyString(), any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
      states.put(call.getArgument(0, String.class),
          call.getArgument(2) == io.justsearch.ipc.VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED
              ? "FAILED" : "DONE");
      return true;
    });

    processor = mock(VduProcessor.class);
    when(processor.hasVisionCapability()).thenReturn(true);
    doAnswer(call -> {
      entries.incrementAndGet();
      if (entryFails.get()) {
        throw new VduProcessor.VduException("Failed to enter VDU mode: the chat server refused (LOADING)",
            new java.io.IOException("The fake process refuses its vision configuration"));
      }
      return null;
    }).when(processor).enterVduMode();
    when(processor.process(any(), any())).thenAnswer(call -> {
      Action action;
      synchronized (script) { action = script.isEmpty() ? otherwise : script.poll(); }
      return switch (action) {
        case REFUSE -> throw new VduProcessor.VduException("vision call failed",
            new GenerationUnavailableException("Generative component is unavailable"));
        case FAIL -> throw new VduProcessor.VduException("unreadable page", new IllegalStateException("bad page"));
        case CANCEL -> throw new java.util.concurrent.CancellationException("Engine quit");
        case COMPLETE -> new VduProcessor.VduResult("page text", "{}", 1);
      };
    });
    capability = new VduCapabilityState();
    admission = new io.justsearch.app.engine.EngineAdmissionController(16, 64, 1);
    coordinator = coordinator(capability);
    sampler = sampler();
  }

  @AfterEach void tearDown() throws Exception {
    try {
      sampler.stop();
      coordinator.close();
    } finally { executors.close(); }
  }

  // ---------------------------------------------------------------- B1

  @Test void refusedAutomaticPassesStopAfterThreeAndStatusShowsThePause() throws Exception {
    refusedTicks(3);
    assertEquals(3, entries.get());
    for (int i = 0; i < 6; i++) {
      clock.addAndGet(MINUTE / 2);
      tick();
    }
    assertEquals(3, entries.get(), "no fourth automatic entry within the five-minute back-off");
    assertEquals(REFUSED, capability.snapshot().blockedReason(),
        "status shows that automatic picture reading is paused because passes were refused");
    assertTrue(String.valueOf(detail()).contains("Generative component is unavailable"),
        "status names the refusal the passes saw: " + detail());
    assertTrue(states.values().stream().allMatch("PENDING"::equals), "document states unchanged");
    assertTrue(retries.values().stream().allMatch(count -> count == 0), "refusals spend no attempt");
  }

  // ---------------------------------------------------------------- B2 and the mixed pass

  @Test void aPassThatCompletesADocumentResetsTheCount() throws Exception {
    assertProgressResets(Action.COMPLETE);
  }

  @Test void aRefusalMixedWithARealFailureCountsAsProgress() throws Exception {
    assertProgressResets(Action.FAIL);
  }

  private void assertProgressResets(Action progress) throws Exception {
    refusedTicks(2);
    synchronized (script) { script.add(progress); }   // one document finishes, the next is refused
    tick();
    assertEquals(3, entries.get());
    refusedTicks(2);
    assertEquals(5, entries.get());
    assertNotPaused("two refused passes after progress");
    tick();
    assertEquals(6, entries.get(), "the count restarted at the progressing pass, so this pass runs");
    tick();
    assertEquals(6, entries.get(), "the third refused pass in a row since progress opens the breaker");
    assertEquals(REFUSED, capability.snapshot().blockedReason());
  }

  // ---------------------------------------------------------------- B3

  @Test void halfOpenProbeBacksOffFiveTenTwentyFortyThenSixtyAndClosesOnProgress() throws Exception {
    refusedTicks(3);
    int expected = 3;
    for (long backoff : new long[] {5, 10, 20, 40, 60, 60}) {
      clock.addAndGet(backoff * MINUTE - 1);
      tick();
      assertEquals(expected, entries.get(), "no probe before the " + backoff + " min back-off ends");
      clock.addAndGet(1);
      tick();
      assertEquals(++expected, entries.get(), "one probe pass after " + backoff + " min");
      assertEquals(REFUSED, capability.snapshot().blockedReason(), "a refused probe reopens the breaker");
    }
    clock.addAndGet(60 * MINUTE);
    synchronized (script) { script.add(Action.COMPLETE); }
    tick();
    assertEquals(++expected, entries.get());
    assertNotPaused("a probe that completes a document closes the breaker");
    refusedTicks(2);
    assertEquals(expected + 2, entries.get(), "closed: passes run without waiting");
    tick();
    clock.addAndGet(5 * MINUTE - 1);
    tick();
    assertEquals(expected + 3, entries.get(), "after closing, the back-off restarts at five minutes");
    clock.addAndGet(1);
    tick();
    assertEquals(expected + 4, entries.get());
  }

  @Test void aProbeThatNeitherCountsNorProgressesKeepsThePauseAndTheNextTickProbesAgain() throws Exception {
    refusedTicks(3);
    clock.addAndGet(5 * MINUTE);
    activity.set(true);
    tick();
    activity.set(false);
    assertEquals(4, entries.get(), "the probe entered and was interrupted by activity");
    assertEquals(REFUSED, capability.snapshot().blockedReason(), "an undecided probe keeps the pause");
    tick();
    assertEquals(5, entries.get(), "the elapsed back-off lets the next eligible tick probe again");
    tick();
    clock.addAndGet(10 * MINUTE - 1);
    tick();
    assertEquals(5, entries.get(), "the refused probe doubled the back-off to ten minutes");
    clock.addAndGet(1);
    tick();
    assertEquals(6, entries.get());
  }

  // ---------------------------------------------------------------- B4 (explicit) and B5

  @Test void anExplicitPassWhileOpenRunsNormallyAndClosesTheBreaker() throws Exception {
    refusedTicks(3);
    assertEquals(REFUSED, capability.snapshot().blockedReason());
    otherwise = Action.COMPLETE;
    var outcome = explicit().toCompletableFuture().get(30, TimeUnit.SECONDS);
    assertEquals(4, entries.get(), "the explicit pass entered picture reading while the breaker was open");
    assertEquals(12, outcome.processed(), "the explicit pass behaves as today");
    assertNotPaused("an explicit pass closes the breaker");
  }

  @Test void anExplicitPassClosesTheBreakerEvenWhenItIsRefusedToo() throws Exception {
    refusedTicks(3);
    assertEquals(REFUSED, capability.snapshot().blockedReason());
    var outcome = explicit().toCompletableFuture().get(30, TimeUnit.SECONDS);
    assertEquals(OfflineProcessingOutcome.BlockReason.AI_OFFLINE, outcome.blockedReason());
    assertEquals(4, entries.get());
    tick();
    assertEquals(5, entries.get(), "the explicit pass reset the count: the next automatic tick runs");
    refusedTicks(1);
    tick();
    assertEquals(7, entries.get(), "three new refused automatic passes are needed to open it again");
    tick();
    assertEquals(7, entries.get(), "the third new refused automatic pass opened it again");
  }

  // ---------------------------------------------------------------- failed vision entry

  @Test void failedVisionEntriesCountAndAreReportedApart() throws Exception {
    entryFails.set(true);
    for (int i = 0; i < 3; i++) tick();
    assertEquals(3, entries.get());
    for (int i = 0; i < 5; i++) tick();
    assertEquals(3, entries.get(), "no fourth vision launch after three failed entries");
    assertEquals(ENTRY_FAILED, capability.snapshot().blockedReason(),
        "status tells a failed entry apart from refused documents");
    assertTrue(String.valueOf(detail()).contains("refuses its vision configuration"), String.valueOf(detail()));
    entryFails.set(false);
    clock.addAndGet(5 * MINUTE);
    synchronized (script) { script.add(Action.COMPLETE); }
    tick();
    assertEquals(4, entries.get());
    assertNotPaused("a progressing probe closes it");
  }

  // ---------------------------------------------------------------- neutral passes

  @Test void activityInterruptedPassNeitherCountsNorResets() throws Exception {
    refusedTicks(2);
    activity.set(true);
    tick();
    activity.set(false);
    assertEquals(3, entries.get(), "the interrupted pass entered");
    tick();
    assertEquals(4, entries.get(), "the interrupted pass did not count: this is only the third refusal");
    tick();
    assertEquals(4, entries.get(), "and it did not reset: three refusals in a row open the breaker");
    assertEquals(REFUSED, capability.snapshot().blockedReason());
  }

  @Test void cancelledPassNeitherCountsNorResets() throws Exception {
    refusedTicks(2);
    synchronized (script) { script.add(Action.CANCEL); }
    tick();
    assertEquals(3, entries.get());
    tick();
    assertEquals(4, entries.get(), "the cancelled pass did not count");
    tick();
    assertEquals(4, entries.get(), "and it did not reset");
    assertEquals(REFUSED, capability.snapshot().blockedReason());
  }

  // ---------------------------------------------------------------- Engine restart

  @Test void anEngineRestartStartsClosed() throws Exception {
    refusedTicks(3);
    tick();
    assertEquals(3, entries.get());
    assertEquals(REFUSED, capability.snapshot().blockedReason(), "the first Engine's breaker is open");
    // Restart: the old Engine's owners close, a new Engine assembles fresh ones.
    sampler.stop();
    coordinator.close();
    capability = new VduCapabilityState();
    coordinator = coordinator(capability);
    sampler = sampler();
    tick();
    assertEquals(4, entries.get(), "a new Engine's breaker is closed: memory only");
    assertNotPaused("a new Engine");
  }

  // ---------------------------------------------------------------- helpers

  private void refusedTicks(int count) throws Exception {
    for (int i = 0; i < count; i++) {
      int before = entries.get();
      tick();
      assertEquals(before + 1, entries.get(), "refused automatic pass " + (i + 1) + " enters");
    }
  }

  private void assertNotPaused(String why) {
    String reason = capability.snapshot().blockedReason();
    assertFalse(REFUSED.equals(reason) || ENTRY_FAILED.equals(reason),
        why + ": status must not claim paused, was " + reason);
  }

  private void tick() throws Exception {
    sampler.checkOnce();
    awaitIdle(coordinator);
  }

  private static void awaitIdle(OfflineCoordinator owner) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (owner.isProcessing()) {
      if (System.nanoTime() > until) throw new AssertionError("the pass did not finish");
      Thread.onSpinWait();
      Thread.yield();
    }
    // The sampler records the result in the pass's completion callback; let it run.
    long settle = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
    while (System.nanoTime() < settle) Thread.yield();
  }

  private String detail() {
    var snapshot = capability.snapshot();
    try {
      return (String) snapshot.getClass().getMethod("detail").invoke(snapshot);
    } catch (ReflectiveOperationException beforeBreaker) {
      return null;
    }
  }

  private CompletionStage<OfflineProcessingOutcome> explicit() throws Exception {
    EngineContext context = TestEngineContexts.ui();
    Consumer<OfflineProcessingOutcome> progress = outcome -> {};
    try {
      @SuppressWarnings("unchecked")
      var stage = (CompletionStage<OfflineProcessingOutcome>) OfflineCoordinator.class
          .getMethod("startExplicitProcessing", EngineContext.class, Consumer.class)
          .invoke(coordinator, context, progress);
      return stage;
    } catch (NoSuchMethodException beforeBreaker) {
      // The explicit route before the breaker existed.
      return coordinator.startOfflineProcessing(context, progress);
    }
  }

  private OfflineCoordinator coordinator(VduCapabilityState status) throws Exception {
    var gpu = mock(GpuCapabilitiesService.class);
    when(gpu.snapshot()).thenReturn(VduBatchProcessorTest.gpuSnapshot(24_000_000_000L));
    var batch = new VduBatchProcessor(processor, gpu, () -> index, VduMetricCatalog.noop(), status,
        () -> activity.get());
    Supplier<KnowledgeClient> client = () -> index;
    try {
      return OfflineCoordinator.class.getConstructor(EngineExecutorRegistry.class,
              EngineAdmissionService.class, VduBatchProcessor.class, Supplier.class,
              VduCapabilityState.class, LongSupplier.class)
          .newInstance(executors, admission, batch, client, status, (LongSupplier) clock::get);
    } catch (NoSuchMethodException beforeBreaker) {
      return new OfflineCoordinator(executors, admission, batch, client, status);
    }
  }

  /** Chat-off automatic passes: no chat claim, no loaded-chat holds; idle well past the threshold. */
  private VduOfflineTriggerSampler sampler() {
    var host = mock(KnowledgeServerBootstrap.class);
    when(host.energyState()).thenReturn(new EnergyState(EnergyState.Intent.FULL, EnergyState.Source.AC));
    when(host.msSinceLastUserActivity(anyLong())).thenReturn(VduPacingPolicy.DEFAULT_IDLE_THRESHOLD_MS + 1_000);
    when(host.bulkWork()).thenReturn(new BulkWorkGate());
    return new VduOfflineTriggerSampler(executors, () -> coordinator, () -> host, () -> false, () -> null,
        () -> VduPacingPolicy.DEFAULT_IDLE_THRESHOLD_MS + 1_000);
  }
}
