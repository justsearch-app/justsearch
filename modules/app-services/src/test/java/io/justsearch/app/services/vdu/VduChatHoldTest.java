/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.app.api.OfflineProcessingOutcome.BlockReason;
import io.justsearch.app.api.OnlineAiRuntimeIntrospection;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.chat.ChatRefusal;
import io.justsearch.app.api.chat.RefusalReason;
import io.justsearch.app.api.chat.VduHold;
import io.justsearch.app.inference.chat.ChatHolds;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.ipc.VduUpdateOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec A step 6 on the VDU side: a VDU batch holds the chat controller instead of switching modes
 * itself, finishes early when the controller asks it to (U12, the part without residency
 * pressure), and the automatic trigger reads the controller (U13).
 */
class VduChatHoldTest {
  @TempDir Path tempDir;

  @Test
  void theBatchTakesOneVduHoldAndClosesItOnExit() throws Exception {
    ChatHolds holds = mock(ChatHolds.class);
    VduHold hold = mock(VduHold.class);
    VduHold duplicate = mock(VduHold.class);
    when(holds.holdVdu("vdu-batch")).thenReturn(hold, duplicate);
    VduProcessor processor = processor(holds);

    processor.enterVduMode();
    assertThrows(VduProcessor.VduException.class, processor::enterVduMode,
        "one batch holds at a time");
    verify(duplicate).close();
    verify(hold, never()).close();
    processor.exitVduMode();
    processor.exitVduMode(); // nothing left to close

    verify(holds, times(2)).holdVdu("vdu-batch");
    verify(hold, times(1)).close();
  }

  @Test
  void aRefusedHoldFailsTheEntryWithTheReason() throws Exception {
    ChatHolds holds = mock(ChatHolds.class);
    ChatRefusal refusal = new ChatRefusal(RefusalReason.SELF_TEST, Optional.empty(), false);
    when(holds.holdVdu("vdu-batch")).thenThrow(refusal);
    VduProcessor processor = processor(holds);

    var failure = assertThrows(VduProcessor.VduException.class, processor::enterVduMode);
    assertTrue(failure.getMessage().contains("SELF_TEST"), failure.getMessage());
    assertInstanceOf(ChatRefusal.class, failure.getCause());
    assertFalse(processor.vduInterruptRequested());
  }

  @Test
  void whenTheControllerAsksTheBatchFinishesEarlyAndLeavesTheRestPending() throws Exception {
    ChatHolds holds = mock(ChatHolds.class);
    VduHold hold = mock(VduHold.class);
    when(holds.holdVdu("vdu-batch")).thenReturn(hold);
    AtomicInteger asked = new AtomicInteger();
    when(hold.interruptRequested()).thenAnswer(call -> asked.getAndIncrement() > 0);
    VduProcessor processor = org.mockito.Mockito.spy(processor(holds));
    org.mockito.Mockito.doReturn(true).when(processor).hasVisionCapability();
    org.mockito.Mockito.doReturn(new VduProcessor.VduResult("text", "{}", 1))
        .when(processor).process(any(Path.class), any(EngineContext.class), any(java.util.function.BooleanSupplier.class));
    KnowledgeClient client = client(3);
    GpuCapabilitiesService gpu = mock(GpuCapabilitiesService.class);
    when(gpu.snapshot()).thenReturn(VduBatchProcessorTest.gpuSnapshot(24_000_000_000L));

    OfflineProcessingOutcome outcome = new VduBatchProcessor(processor, gpu, () -> client,
        VduMetricCatalog.noop(), new VduCapabilityState(), () -> false)
        .processPendingFiles(context, ignored -> {});

    assertEquals(1, outcome.processed());
    assertEquals(2, outcome.remaining());
    assertEquals(BlockReason.PROCESSING_REFUSED, outcome.blockedReason());
    verify(hold).close();
    verify(processor, times(1)).process(any(Path.class), any(EngineContext.class), any(java.util.function.BooleanSupplier.class));
  }

  @Test
  void theTriggerFiresOnlyWhenTheControllerSaysAPassMayStart() throws Exception {
    ChatHolds holds = mock(ChatHolds.class);
    OfflineCoordinator coordinator = mock(OfflineCoordinator.class);
    when(coordinator.isProcessing()).thenReturn(false);
    when(coordinator.getPendingVduCount()).thenReturn(4);
    when(coordinator.startOfflineProcessing(any(), any()))
        .thenReturn(CompletableFuture.completedFuture(
            new OfflineProcessingOutcome(0, 0, 0, BlockReason.NONE,
                OfflineProcessingOutcome.EmbeddingHandoff.NOT_NEEDED)));
    AtomicBoolean mayStart = new AtomicBoolean(false);
    when(holds.vduMayStart()).thenAnswer(call -> mayStart.get());
    var executors = new TestEngineExecutors();
    try {
      // Wired as CoreApiAssembly wires it: the claim is the controller's own answer.
      var sampler = new VduOfflineTriggerSampler(executors, () -> coordinator, () -> null,
          () -> !holds.vduMayStart(), () -> null, () -> Long.MAX_VALUE);
      try {
        // UNLOADED for a self-test, a latched failure or an open hold: the controller says no.
        sampler.checkOnce();
        verify(coordinator, never()).startOfflineProcessing(any(), any());
        mayStart.set(true);
        sampler.checkOnce();
        verify(coordinator, times(1)).startOfflineProcessing(any(), any());
      } finally {
        sampler.stop();
      }
    } finally {
      executors.close();
    }
  }

  private final EngineContext context = TestEngineContexts.durableInternal();

  private VduProcessor processor(ChatHolds holds) {
    return new VduProcessor(mock(OnlineAiRuntimeIntrospection.class), holds,
        mock(OnlineAiService.class), null, null, VduMetricCatalog.noop());
  }

  private KnowledgeClient client(int count) throws Exception {
    KnowledgeClient client = VduAttemptTestSupport.client();
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Path file = tempDir.resolve("doc-" + i + ".png");
      Files.writeString(file, "image");
      ids.add(file.toString());
    }
    when(client.queryPendingVduDocIds(context)).thenReturn(ids);
    when(client.markVduProcessing(anyString(), anyInt(), any())).thenReturn(0);
    when(client.updateVduResult(anyString(), any(), any(VduUpdateOutcome.class), any(), anyInt(),
        any())).thenReturn(true);
    return client;
  }
}
