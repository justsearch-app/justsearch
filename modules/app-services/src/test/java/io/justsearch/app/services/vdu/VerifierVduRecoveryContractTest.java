/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.aibackend.backend.EngineCircuitBreaker;
import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.app.api.OfflineProcessingOutcome.BlockReason;
import io.justsearch.app.inference.chat.ChatHolds;
import io.justsearch.app.inference.chat.GenerationTracker;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.util.EnergyState;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.VduUpdateOutcome;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Independent behavioral checks. No processing loop is reproduced here. */
final class VerifierVduRecoveryContractTest {
  @TempDir Path directory;
  private final EngineContext context = TestEngineContexts.durableInternal();
  private VduProcessor processor;
  private KnowledgeClient index;
  private GpuCapabilitiesService gpu;
  private VduCapabilityState status;
  private VduBatchProcessor batch;
  private List<String> selection;
  private final List<String> claims = new ArrayList<>();
  private final List<Write> writes = new ArrayList<>();
  private final AtomicBoolean interrupt = new AtomicBoolean();
  private record Write(String id, String text, VduUpdateOutcome outcome, String enrichment) {}

  @BeforeEach void setup() throws Exception {
    selection = new ArrayList<>();
    processor = mock(VduProcessor.class);
    index = mock(KnowledgeClient.class);
    gpu = mock(GpuCapabilitiesService.class);
    status = new VduCapabilityState();
    when(gpu.snapshot()).thenReturn(VduBatchProcessorTest.gpuSnapshot(24_000_000_000L));
    when(processor.hasVisionCapability()).thenReturn(true);
    doReturn(new VduProcessor.VduResult("recovered unique text", "{}", 1)).when(processor).process(any(Path.class), any(EngineContext.class), any(BooleanSupplier.class));
    when(index.queryPendingVduDocIds(any())).thenAnswer(call -> List.copyOf(selection));
    when(index.markVduProcessing(anyString(), anyInt(), anyString(), any())).thenAnswer(call -> {
      assertEquals(SchemaFields.VDU_MAX_RETRIES, call.<Integer>getArgument(1));
      assertFalse(call.<String>getArgument(2).isBlank());
      claims.add(call.getArgument(0));
      return 1;
    });
    when(index.releaseVduProcessing(anyString(), anyString(), any())).thenReturn(true);
    when(index.updateVduResult(anyString(), nullable(String.class), any(), nullable(String.class),
        anyInt(), anyString(), any())).thenAnswer(call -> {
      writes.add(new Write(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3)));
      return true;
    });
    when(index.updateVduResult(anyString(), nullable(String.class), any(), nullable(String.class),
        anyInt(), anyString(), nullable(String.class), anyBoolean(), any())).thenAnswer(call -> {
      writes.add(new Write(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3)));
      return true;
    });
    batch = new VduBatchProcessor(processor, gpu, () -> index, VduMetricCatalog.noop(), status,
        (BooleanSupplier) interrupt::get);
  }

  private Path file(String name) throws Exception {
    return Files.writeString(directory.resolve(name), "fixture image bytes: model is controlled");
  }
  private void select(Path... paths) {
    selection = Arrays.stream(paths).map(Path::toString).toList();
  }
  private OfflineProcessingOutcome pass() { return batch.processPendingFiles(context, ignored -> {}); }

  // The baseline has no clock injection. Move only its opening timestamp; retain the exact
  // production breaker, timeout, state machine and admission checks, without a minute of sleeping.
  private EngineCircuitBreaker breaker() throws Exception {
    Field field = VduBatchProcessor.class.getDeclaredField("circuitBreaker");
    field.setAccessible(true);
    return (EngineCircuitBreaker) field.get(batch);
  }
  @SuppressWarnings("unchecked")
  private void coolDown() throws Exception {
    Field field = EngineCircuitBreaker.class.getDeclaredField("openedAt");
    field.setAccessible(true);
    ((AtomicReference<Instant>) field.get(breaker())).set(Instant.now().minusSeconds(61));
  }
  private void tripWithServiceFailures(boolean fatal) throws Exception {
    List<Path> paths = new ArrayList<>();
    for (int i = 0; i < 6; i++) paths.add(file("trip-" + i + ".png"));
    select(paths.toArray(Path[]::new));
    doThrow(new VduProcessor.VduException(fatal ? "native_error: server crash" : "generation failed", null)).when(processor).process(any(Path.class), any(EngineContext.class), any(BooleanSupplier.class));
    var outcome = pass();
    assertEquals(fatal ? 1 : 5, outcome.failed(), "service failures must actually open the breaker");
    assertEquals(BlockReason.CIRCUIT_OPEN, outcome.blockedReason());
    doReturn(new VduProcessor.VduResult("recovered unique text", "{}", 1)).when(processor).process(any(Path.class), any(EngineContext.class), any(BooleanSupplier.class));
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void s1SamplerRetriesInSameIdleWindowAfterCooldown(boolean loadedChat) throws Exception {
    tripWithServiceFailures(false);
    Path first = file("pending-first.png"), second = file("pending-second.png");
    select(first, second);
    var coordinator = mock(OfflineCoordinator.class);
    var automatic = new AutomaticVduBreaker(status, System::nanoTime);
    when(coordinator.getPendingVduCount()).thenReturn(2);
    when(coordinator.automaticBreaker()).thenReturn(automatic);
    when(coordinator.vduCapabilityState()).thenReturn(status);
    // The adapter controls dispatch only; the real batch processes every document and owns its breaker.
    when(coordinator.startOfflineProcessing(any(), any())).thenAnswer(call -> CompletableFuture.completedFuture(pass()));
    when(coordinator.startAutomaticProcessing(any(), any())).thenAnswer(call ->
        CompletableFuture.completedFuture(batch.processPendingFiles(context, ignored -> {}, call.getArgument(1))));
    var host = mock(KnowledgeServerBootstrap.class);
    long idleAtStart = VduPacingPolicy.DEFAULT_IDLE_THRESHOLD_MS + 10_000;
    long start = System.currentTimeMillis();
    when(host.msSinceLastUserActivity(anyLong())).thenAnswer(call -> idleAtStart + System.currentTimeMillis() - start);
    when(host.energyState()).thenReturn(new EnergyState(EnergyState.Intent.FULL, EnergyState.Source.AC));
    var holds = mock(ChatHolds.class);
    when(holds.automaticVduMayStart(any(Duration.class))).thenReturn(true);
    try (var executors = TestEngineExecutors.awaitingTermination()) {
      var sampler = new VduOfflineTriggerSampler(executors, () -> coordinator, () -> host,
          () -> loadedChat, () -> holds, () -> Long.MAX_VALUE);
      try {
        sampler.checkOnce(); // a real blocked pass must not consume the loaded-chat idle opportunity forever
        coolDown();
        sampler.checkOnce();
        assertAll("S1: recovery in the same idle window; loaded=" + loadedChat,
            () -> assertEquals(2, writes.stream().filter(w -> w.outcome() == VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT).count(),
                "S1: both pending documents must be read after cool-down without activity, settings or restart"),
            () -> {
              if (loadedChat) verify(coordinator, times(2)).startAutomaticProcessing(any(), any());
              else verify(coordinator, times(2)).startOfflineProcessing(any(), any());
            });
        assertEquals(EngineCircuitBreaker.State.CLOSED, breaker().state());
      } finally { sampler.stop(); }
    }
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void s2FailedTrialReopensThenNextCooldownRecovers(boolean originallyFatal) throws Exception {
    tripWithServiceFailures(originallyFatal);
    Path trial = file("trial.png");
    select(trial);
    coolDown();
    int before = claims.size();
    doThrow(new VduProcessor.VduException("vision timeout", null)).when(processor).process(eq(trial), any(), any());
    pass();
    assertEquals(before + 1, claims.size(), "S2: one service trial must be attempted after cool-down");
    assertEquals(EngineCircuitBreaker.State.OPEN, breaker().state(), "S2: a failed trial must reopen even below threshold");
    pass();
    assertEquals(before + 1, claims.size(), "S2: no retry during renewed cool-down");
    coolDown();
    doReturn(new VduProcessor.VduResult("recovered", null, 1)).when(processor).process(eq(trial), any(), any());
    assertEquals(1, pass().processed(), "S2: next cool-down must permit a successful trial");
    assertEquals(EngineCircuitBreaker.State.CLOSED, breaker().state());
  }

  @Test void s2bMissingTrialDocumentDoesNotStrandFollowingValidDocument() throws Exception {
    tripWithServiceFailures(false);
    select(directory.resolve("gone-trial.png"), file("valid-trial.png"));
    coolDown();
    var result = pass();
    assertEquals(1, result.processed(), "S2b: a missing trial document must leave recovery available for a valid document");
    assertEquals(1, result.failed());
    assertEquals(EngineCircuitBreaker.State.CLOSED, breaker().state());
  }

  @Test void s2bDeferredTrialDoesNotStrandFollowingPass() throws Exception {
    tripWithServiceFailures(false);
    Path trial = file("deferred-trial.png");
    select(trial);
    coolDown();
    doThrow(new VduProcessor.EligibilityDeferred()).when(processor).process(eq(trial), any(), any());
    var deferred = pass();
    assertEquals(BlockReason.ACTIVITY_OR_ENERGY, deferred.blockedReason(), "S2b: recovery must reach the deferred document");
    verify(index).releaseVduProcessing(eq(trial.toString()), anyString(), any());
    doReturn(new VduProcessor.VduResult("valid", null, 1)).when(processor).process(eq(trial), any(), any());
    assertEquals(1, pass().processed(), "S2b: deferral must release the trial permit without claiming service recovery");
  }

  @Test void s3FiveMissingFilesDoNotBlockValidDocument() throws Exception {
    List<Path> paths = new ArrayList<>();
    for (int i = 0; i < 5; i++) paths.add(directory.resolve("gone-" + i + ".png"));
    paths.add(file("valid.png"));
    select(paths.toArray(Path[]::new));
    var result = pass();
    assertEquals(1, result.processed(), "S3: five missing documents must not open the service breaker");
    assertEquals(5, result.failed());
    assertEquals(EngineCircuitBreaker.State.CLOSED, breaker().state());
  }

  @Test void s4CoolingPassesDoNotRelaunchOrClaim() throws Exception {
    tripWithServiceFailures(false);
    select(file("waiting.png"));
    int before = claims.size();
    clearInvocations(processor);
    for (int i = 0; i < 4; i++) assertEquals(BlockReason.CIRCUIT_OPEN, pass().blockedReason());
    assertAll("S4: blocked ticks must be cheap and leave retries intact",
        () -> verify(processor, never()).enterVduMode(),
        () -> assertEquals(before, claims.size(), "blocked documents must not be claimed"));
  }

  @Test void s4AutomaticBackoffProbeAndExplicitResetArePreserved() {
    var clock = new AtomicLong();
    var automatic = new AutomaticVduBreaker(status, clock::get);
    var refusal = new AutomaticVduBreaker.Result.Counted(AutomaticVduBreaker.Cause.REFUSED, "gate");
    for (int i = 0; i < 3; i++) { assertTrue(automatic.tryStartAutomatic()); automatic.passEnded(refusal); }
    assertFalse(automatic.tryStartAutomatic());
    clock.addAndGet(Duration.ofMinutes(5).toNanos());
    assertTrue(automatic.tryStartAutomatic());
    assertFalse(automatic.tryStartAutomatic(), "only one probe may run");
    automatic.passEnded(refusal);
    assertEquals(Duration.ofMinutes(10).toMillis(), automatic.snapshot().backoffMillis());
    assertFalse(automatic.tryStartAutomatic());
    automatic.close("explicit run");
    assertTrue(automatic.tryStartAutomatic());
    assertFalse(automatic.snapshot().paused());
  }

  @Test void s5LastFailureReportsOpenImmediatelyAndRecoveryClearsIt() throws Exception {
    List<Path> paths = new ArrayList<>();
    for (int i = 0; i < 5; i++) paths.add(file("last-failure-" + i + ".png"));
    select(paths.toArray(Path[]::new));
    doThrow(new VduProcessor.VduException("service generation error", null)).when(processor).process(any(), any(), any());
    pass();
    assertEquals(VduCapabilityState.REASON_CIRCUIT_OPEN, status.snapshot().blockedReason(),
        "S5: the threshold failure at the end of a batch must publish circuit-open status immediately");
    coolDown();
    select(file("recover-status.png"));
    doReturn(new VduProcessor.VduResult("text", null, 1)).when(processor).process(any(), any(), any());
    assertEquals(1, pass().processed());
    assertNull(status.snapshot().blockedReason(), "S5: recovery must clear circuit-open status");
  }

  @Test void p1FiveServiceFailuresStopBeforeSixthClaimAndFatalStopsAtOnce() throws Exception {
    tripWithServiceFailures(false);
    assertEquals(5, claims.size());
    assertEquals(5, writes.size());
    assertTrue(writes.stream().allMatch(w -> w.outcome() == VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED));
    breaker().reset();
    claims.clear(); writes.clear();
    tripWithServiceFailures(true);
    assertEquals(1, claims.size());
    assertEquals(1, writes.size());
  }

  @Test void p1SuccessResetsConsecutiveServiceFailureCount() throws Exception {
    Path path = file("intermittent.png");
    select(path);
    for (int i = 0; i < 4; i++) {
      doThrow(new VduProcessor.VduException("timeout", null)).when(processor).process(any(), any(), any());
      assertEquals(1, pass().failed());
    }
    doReturn(new VduProcessor.VduResult("ok", null, 1)).when(processor).process(any(), any(), any());
    assertEquals(1, pass().processed());
    for (int i = 0; i < 4; i++) {
      doThrow(new VduProcessor.VduException("timeout", null)).when(processor).process(any(), any(), any());
      assertEquals(1, pass().failed());
    }
    assertEquals(EngineCircuitBreaker.State.CLOSED, breaker().state(), "failures must be consecutive");
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void p2DeferralsReturnClaimWithoutFailureWrite(boolean generationUnavailable) throws Exception {
    Path path = file("defer.png"); select(path);
    VduProcessor.VduException failure = new VduProcessor.EligibilityDeferred();
    if (generationUnavailable) {
      var gate = new GenerationTracker(work -> () -> {}, Runnable::run);
      try (var drain = gate.closeNow()) {
        failure = new VduProcessor.VduException("vision refused", assertThrows(IllegalStateException.class, gate::requireOpen));
      }
    }
    doThrow(failure).when(processor).process(any(), any(), any());
    var result = pass();
    assertEquals(0, result.failed());
    assertEquals(1, result.remaining());
    assertEquals(generationUnavailable ? BlockReason.AI_OFFLINE : BlockReason.ACTIVITY_OR_ENERGY, result.blockedReason());
    verify(index).releaseVduProcessing(eq(path.toString()), anyString(), any());
    assertTrue(writes.isEmpty(), "deferrals spend no failed-document retry");
    assertEquals(0, breaker().consecutiveFailures());
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void p2ActivityEnergyOrChatInterruptBeforeClaim(boolean chatInterrupt) throws Exception {
    select(file("interrupted.png"));
    if (chatInterrupt) when(processor.vduInterruptRequested()).thenReturn(true);
    else interrupt.set(true);
    var result = pass();
    assertEquals(chatInterrupt ? BlockReason.PROCESSING_REFUSED : BlockReason.ACTIVITY_OR_ENERGY, result.blockedReason());
    assertTrue(claims.isEmpty()); assertTrue(writes.isEmpty());
  }

  @Test void p3FailureUsesRetryLimitAndFailureOutcomeWhileBlockedKeepsClaims() throws Exception {
    select(directory.resolve("missing.png"));
    assertEquals(1, pass().failed());
    assertEquals(VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED, writes.getFirst().outcome());
    assertTrue(writes.getFirst().enrichment().contains("File no longer exists"));
    verify(index).markVduProcessing(anyString(), eq(SchemaFields.VDU_MAX_RETRIES), anyString(), any());
    breaker().trip("service unavailable");
    int before = claims.size();
    select(file("blocked.png"));
    pass();
    assertEquals(before, claims.size());
  }

  @Test void p5TextEmptyGateRejectionAndRejectedIndexWriteStayDistinct() throws Exception {
    Path text = file("text.png"), empty = file("empty.png"), rejected = file("rejected.png");
    select(text, empty, rejected);
    doReturn(new VduProcessor.VduResult("", null, 1)).when(processor).process(eq(empty), any(), any());
    var verdict = new GateVerdict(true, GateVerdict.Band.REJECT, "input_legibility", null, null, null, null, 0.0, 0.0, null, null);
    doReturn(new VduProcessor.VduResult("suspect", null, 1, verdict)).when(processor).process(eq(rejected), any(), any());
    var result = pass();
    assertEquals(1, result.processed()); assertEquals(2, result.failed());
    assertEquals(List.of(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT,
        VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY,
        VduUpdateOutcome.VDU_UPDATE_OUTCOME_REJECTED_SUSPECT_TEXT), writes.stream().map(Write::outcome).toList());
    assertNull(writes.get(1).text()); assertNull(writes.get(2).text());
    when(index.updateVduResult(anyString(), nullable(String.class), any(), nullable(String.class),
        anyInt(), anyString(), nullable(String.class), anyBoolean(), any())).thenReturn(false);
    select(text);
    assertThrows(IllegalStateException.class, this::pass, "a rejected index write must fail the pass");
  }

  @Test void statusOtherRestrictionsSurviveCircuitRestriction() throws Exception {
    tripWithServiceFailures(false); select(file("restricted.png"));
    when(gpu.snapshot()).thenReturn(VduBatchProcessorTest.gpuSnapshot(1_000_000_000L));
    assertEquals(BlockReason.INSUFFICIENT_VRAM, pass().blockedReason());
    assertEquals(VduCapabilityState.REASON_INSUFFICIENT_VRAM, status.snapshot().blockedReason());
    when(gpu.snapshot()).thenReturn(VduBatchProcessorTest.gpuSnapshot(24_000_000_000L));
    when(processor.hasVisionCapability()).thenReturn(false);
    assertEquals(BlockReason.MISSING_VISION, pass().blockedReason());
    assertEquals(VduCapabilityState.REASON_MISSING_MMPROJ, status.snapshot().blockedReason());
    // Offline entry is checked when entry is allowed; cooling passes must not attempt entry.
    breaker().reset();
    when(processor.hasVisionCapability()).thenReturn(true);
    doThrow(new VduProcessor.VduException("server offline", null)).when(processor).enterVduMode();
    assertThrows(IllegalStateException.class, this::pass);
    assertEquals(VduCapabilityState.REASON_AI_OFFLINE, status.snapshot().blockedReason());
  }
}
