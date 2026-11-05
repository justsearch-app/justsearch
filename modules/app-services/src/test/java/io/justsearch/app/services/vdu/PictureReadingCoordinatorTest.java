/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.BrainRuntimeService;
import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.registry.operations.handlers.TriggerOfflineProcessingHandler;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.gpu.GpuCapabilitiesService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(20)
final class PictureReadingCoordinatorTest {
  @TempDir Path directory;

  private final EngineContext context = TestEngineContexts.durableInternal();
  private final AtomicBoolean enabled = new AtomicBoolean(true);
  private final KnowledgeClient client = mock(KnowledgeClient.class);
  private final VduProcessor processor = mock(VduProcessor.class);

  private OfflineCoordinator coordinator(TestEngineExecutors executors) throws Exception {
    var admission = mock(EngineAdmissionService.class);
    var work = mock(EngineWorkHandle.class);
    when(work.context()).thenReturn(context);
    when(work.cancellationReason()).thenReturn(Optional.empty());
    when(work.onCancel(any())).thenReturn(mock(EngineWorkHandle.Registration.class));
    when(admission.attach(context)).thenReturn(work);
    var gpu = mock(GpuCapabilitiesService.class);
    when(gpu.snapshot()).thenReturn(VduBatchProcessorTest.gpuSnapshot(24_000_000_000L));
    when(processor.hasVisionCapability()).thenReturn(true);
    var image = Files.writeString(directory.resolve("scan.png"), "fixture");
    when(client.countPendingVdu(context)).thenReturn(1);
    when(client.queryPendingVduDocIds(context)).thenReturn(List.of(image.toString()));
    when(client.markVduProcessing(anyString(), anyInt(), anyString(), eq(context))).thenReturn(1);
    when(client.releaseVduProcessing(anyString(), anyString(), eq(context))).thenReturn(true);
    var state = new VduCapabilityState();
    var batch = new VduBatchProcessor(processor, gpu, () -> client, VduMetricCatalog.noop(), state);
    var coordinator = new OfflineCoordinator(executors, admission, batch, () -> client, state);
    coordinator.pictureReadingEnabled(enabled::get);
    return coordinator;
  }

  private TriggerOfflineProcessingHandler handler(OfflineCoordinator coordinator) {
    var runtime = mock(BrainRuntimeService.class);
    when(runtime.triggerOfflineProcessing(any(), any())).thenAnswer(call ->
        coordinator.startExplicitProcessing(call.getArgument(0), call.getArgument(1)));
    return new TriggerOfflineProcessingHandler(() -> runtime);
  }

  @Test
  void offBlocksAllProcedureEntriesBeforeRecoveryOrClaims() throws Exception {
    try (var executors = TestEngineExecutors.awaitingTermination(); var coordinator = coordinator(executors)) {
      enabled.set(false);
      var refusal = handler(coordinator).execute("{}", context);
      assertFalse(refusal.success());
      assertEquals("PICTURE_READING_OFF", refusal.errorCode().orElseThrow());
      assertFalse(refusal.retryable().orElseThrow());
      assertTrue(refusal.message().contains("Picture reading is off"));
      assertEquals(0, coordinator.startExplicitProcessing(context, ignored -> {}).toCompletableFuture().get().selected());
      assertEquals(0, coordinator.startOfflineProcessing(context, ignored -> {}).toCompletableFuture().get().selected());
      assertEquals(0, coordinator.startAutomaticProcessing(context, () -> true).toCompletableFuture().get().selected());
      verify(client, never()).recoverVduProcessing(any());
      verify(client, never()).markVduProcessing(anyString(), anyInt(), anyString(), any());
      verify(processor, never()).enterVduMode();
    }
  }

  @Test
  void offDuringReadingReturnsTheClaimAndOnUsesANewAttempt() throws Exception {
    when(client.updateVduResult(anyString(), any(), any(), any(), anyInt(), anyString(), anyString(), anyBoolean(), eq(context)))
        .thenReturn(true);
    var entered = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    when(processor.process(any(), eq(context), any())).thenAnswer(call -> {
      entered.countDown();
      assertTrue(proceed.await(5, TimeUnit.SECONDS));
      if (!((BooleanSupplier) call.getArgument(2)).getAsBoolean()) throw new VduProcessor.EligibilityDeferred();
      return new VduProcessor.VduResult("read text", "{}", 2);
    });
    try (var executors = TestEngineExecutors.awaitingTermination(); var coordinator = coordinator(executors)) {
      var first = coordinator.startExplicitProcessing(context, ignored -> {}).toCompletableFuture();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var stopping = CompletableFuture.supplyAsync(coordinator::fencePictureReading);
      try {
        awaitFence(coordinator);
        assertThrows(TimeoutException.class, () -> stopping.get(100, TimeUnit.MILLISECONDS));
        proceed.countDown();
        assertEquals(0, first.get(5, TimeUnit.SECONDS).failed());
        try (var _ = stopping.get(5, TimeUnit.SECONDS)) {
          enabled.set(false);
          verify(client).releaseVduProcessing(anyString(), anyString(), eq(context));
          verify(client, never()).updateVduResult(anyString(), any(), any(), any(), anyInt(), anyString(), anyString(), anyBoolean(), any());
        }
        enabled.set(true);
        assertEquals(1, coordinator.startExplicitProcessing(context, ignored -> {}).toCompletableFuture().get(5, TimeUnit.SECONDS).processed());
        var attempts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).markVduProcessing(anyString(), anyInt(), attempts.capture(), eq(context));
        assertNotEquals(attempts.getAllValues().get(0), attempts.getAllValues().get(1));
        verify(client).releaseVduProcessing(eq(directory.resolve("scan.png").toString()),
            eq(attempts.getAllValues().get(0)), eq(context));
        verify(client).updateVduResult(anyString(), eq("read text"), any(), any(), eq(2), eq(attempts.getAllValues().get(1)), anyString(), eq(false), eq(context));
      } finally { proceed.countDown(); }
    }
  }

  @Test
  void offWaitsForAResultAlreadyBeingSavedAndKeepsIt() throws Exception {
    var saving = new CountDownLatch(1);
    var saved = new CountDownLatch(1);
    when(processor.process(any(), eq(context), any())).thenReturn(new VduProcessor.VduResult("searchable text", "{}", 1));
    when(client.updateVduResult(anyString(), any(), any(), any(), anyInt(), anyString(), anyString(), anyBoolean(), eq(context)))
        .thenAnswer(call -> { saving.countDown(); assertTrue(saved.await(5, TimeUnit.SECONDS)); return true; });
    try (var executors = TestEngineExecutors.awaitingTermination(); var coordinator = coordinator(executors)) {
      var pass = coordinator.startOfflineProcessing(context, ignored -> {}).toCompletableFuture();
      assertTrue(saving.await(5, TimeUnit.SECONDS));
      var stopping = CompletableFuture.supplyAsync(coordinator::fencePictureReading);
      try {
        awaitFence(coordinator);
        assertThrows(TimeoutException.class, () -> stopping.get(100, TimeUnit.MILLISECONDS));
        saved.countDown();
        try (var _ = stopping.get(5, TimeUnit.SECONDS)) {
          assertEquals(1, pass.get(5, TimeUnit.SECONDS).processed());
          enabled.set(false);
        }
        verify(client, never()).releaseVduProcessing(anyString(), anyString(), any());
        verify(client).updateVduResult(anyString(), eq("searchable text"), any(), eq("{}"), eq(1), anyString(), anyString(), eq(false), eq(context));
        assertFalse(coordinator.pictureReadingMayStart());
      } finally { saved.countDown(); }
    }
  }

  @Test
  void offWithdrawsAnExplicitWaitWithoutResumingTheIndexingPolicy() throws Exception {
    var hold = new io.justsearch.core.scheduling.IndexingHold();
    hold.pause(null, "USER_PAUSED");
    when(client.indexingHold()).thenReturn(hold);
    try (var executors = TestEngineExecutors.awaitingTermination(); var coordinator = coordinator(executors)) {
      try {
        var execution = handler(coordinator).executeRecorded("{}", null, context, null);
        var pass = execution.completion().toCompletableFuture();
        assertEquals("Enrichment pass started", execution.response().message());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (hold.snapshot().waiting() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(1, hold.snapshot().waiting(), "the explicit pass must first observe the real indexing hold");
        var stopping = CompletableFuture.supplyAsync(coordinator::fencePictureReading);
        try (var _ = stopping.get(5, TimeUnit.SECONDS)) {
          var refusal = pass.get(5, TimeUnit.SECONDS);
          assertFalse(refusal.success());
          assertEquals("PICTURE_READING_OFF", refusal.errorCode().orElseThrow());
          assertFalse(refusal.retryable().orElseThrow());
          assertTrue(refusal.message().contains("Picture reading is off"));
          assertTrue(hold.held(), "stopping picture work must not resume indexing");
          assertEquals(0, hold.snapshot().waiting());
          verify(client, never()).recoverVduProcessing(any());
          verify(client, never()).markVduProcessing(anyString(), anyInt(), anyString(), any());
          verify(processor, never()).enterVduMode();
          enabled.set(false);
        }
      } finally { hold.resume(); }
    }
  }

  @Test
  void failedPublicationDuringOffRefusesTheFenceAndKeepsThePriorChoice() throws Exception {
    var saving = new CountDownLatch(1);
    var failWrite = new CountDownLatch(1);
    when(processor.process(any(), eq(context), any())).thenReturn(new VduProcessor.VduResult("read text", "{}", 1));
    when(client.updateVduResult(anyString(), any(), any(), any(), anyInt(), anyString(), anyString(), anyBoolean(), eq(context)))
        .thenAnswer(call -> {
          saving.countDown();
          assertTrue(failWrite.await(5, TimeUnit.SECONDS));
          throw new IllegalStateException("index write was not acknowledged");
        });
    try (var executors = TestEngineExecutors.awaitingTermination(); var coordinator = coordinator(executors)) {
      var pass = coordinator.startOfflineProcessing(context, ignored -> {}).toCompletableFuture();
      assertTrue(saving.await(5, TimeUnit.SECONDS));
      var stopping = CompletableFuture.supplyAsync(coordinator::fencePictureReading);
      try {
        awaitFence(coordinator);
        failWrite.countDown();
        assertThrows(java.util.concurrent.ExecutionException.class, () -> pass.get(5, TimeUnit.SECONDS));
        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> stopping.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(coordinator.pictureReadingMayStart(), "a refused change must release its temporary fence");
      } finally { failWrite.countDown(); }
    }
  }

  @Test
  void offDuringServerEntryDoesNotClaimAnyPicture() throws Exception {
    var entering = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    doAnswer(call -> {
      entering.countDown();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      return null;
    }).when(processor).enterVduMode();
    try (var executors = TestEngineExecutors.awaitingTermination(); var coordinator = coordinator(executors)) {
      var pass = coordinator.startExplicitProcessing(context, ignored -> {}).toCompletableFuture();
      assertTrue(entering.await(5, TimeUnit.SECONDS));
      var stopping = CompletableFuture.supplyAsync(coordinator::fencePictureReading);
      try {
        awaitFence(coordinator);
        entered.countDown();
        try (var _ = stopping.get(5, TimeUnit.SECONDS)) {
          assertEquals(0, pass.get(5, TimeUnit.SECONDS).processed());
          verify(client, never()).markVduProcessing(anyString(), anyInt(), anyString(), any());
          verify(processor).exitVduMode();
        }
      } finally { entered.countDown(); }
    }
  }

  @Test
  void onResumesPendingOnlyWithoutRereadingCompletedPictures() throws Exception {
    when(processor.process(any(), eq(context), any())).thenReturn(new VduProcessor.VduResult("kept text", "{}", 1));
    try (var executors = TestEngineExecutors.awaitingTermination(); var coordinator = coordinator(executors)) {
      var pending = new AtomicBoolean(true);
      when(client.countPendingVdu(context)).thenAnswer(call -> pending.get() ? 1 : 0);
      when(client.updateVduResult(anyString(), any(), any(), any(), anyInt(), anyString(), anyString(), anyBoolean(), eq(context)))
          .thenAnswer(call -> { pending.set(false); return true; });
      assertEquals(1, coordinator.startOfflineProcessing(context, ignored -> {}).toCompletableFuture().get(5, TimeUnit.SECONDS).processed());
      try (var _ = coordinator.fencePictureReading()) { enabled.set(false); }
      assertEquals(0, coordinator.startExplicitProcessing(context, ignored -> {}).toCompletableFuture().get(5, TimeUnit.SECONDS).selected());
      enabled.set(true);
      assertEquals(0, coordinator.startExplicitProcessing(context, ignored -> {}).toCompletableFuture().get(5, TimeUnit.SECONDS).selected());
      verify(processor, times(1)).process(any(), eq(context), any());
      verify(client, times(1)).queryPendingVduDocIds(context);
    }
  }

  private static void awaitFence(OfflineCoordinator coordinator) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (coordinator.pictureReadingMayStart() && System.nanoTime() < deadline) Thread.sleep(5);
    assertFalse(coordinator.pictureReadingMayStart(), "the transition must close picture admission");
  }
}
