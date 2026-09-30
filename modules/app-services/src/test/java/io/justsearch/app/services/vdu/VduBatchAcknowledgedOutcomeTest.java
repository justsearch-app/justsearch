/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.app.api.OfflineProcessingOutcome.BlockReason;
import io.justsearch.app.api.OfflineProcessingOutcome.EmbeddingHandoff;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.context.EngineContext;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.ipc.VduUpdateOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression proof that pass progress represents acknowledged Worker outcomes only. */
class VduBatchAcknowledgedOutcomeTest {
  @TempDir Path tempDir;

  private VduProcessor processor;
  private KnowledgeClient client;
  private GpuCapabilitiesService gpu;
  private EngineContext context;

  @BeforeEach
  void setUp() {
    processor = mock(VduProcessor.class);
    client = mock(KnowledgeClient.class);
    gpu = mock(GpuCapabilitiesService.class);
    context = TestEngineContexts.durableInternal();
    when(processor.hasVisionCapability()).thenReturn(true);
    when(gpu.snapshot()).thenReturn(VduBatchProcessorTest.gpuSnapshot(24_000_000_000L));
    when(client.markVduProcessing(anyString(), anyInt(), same(context))).thenReturn(0);
    when(client.updateVduResult(anyString(), any(), any(), any(), anyInt(), same(context)))
        .thenReturn(true);
  }

  @Test
  void exactCallerContextFlowsThroughEveryPortAndModelCall() throws Exception {
    Path file = file("context.png");
    select(file);
    when(processor.process(file, context))
        .thenReturn(new VduProcessor.VduResult("text", "{}", 1));

    OfflineProcessingOutcome outcome = run(new ArrayList<>());

    assertEquals(1, outcome.processed());
    assertEquals(EmbeddingHandoff.NOT_EVALUATED, outcome.embeddingHandoff());
    verify(client).queryPendingVduDocIds(same(context));
    verify(client).markVduProcessing(anyString(), anyInt(), same(context));
    verify(processor).process(eq(file), same(context));
    verify(client).updateVduResult(anyString(), any(), any(), any(), anyInt(), same(context));
  }

  @Test
  void noTextAndRejectionAdvanceFailureCountOnlyAfterTrueAcknowledgement() throws Exception {
    Path noText = file("no-text.png");
    Path rejected = file("rejected.png");
    when(client.queryPendingVduDocIds(context))
        .thenReturn(List.of(noText.toString(), rejected.toString()));
    when(processor.process(noText, context))
        .thenReturn(new VduProcessor.VduResult("", "{}", 1));
    GateVerdict rejectedVerdict = new GateVerdict(true, GateVerdict.Band.REJECT,
        VduAbstentionGate.STAGE_INPUT_LEGIBILITY, null, null, null, null,
        1.0, 0.01, null, null);
    when(processor.process(rejected, context))
        .thenReturn(new VduProcessor.VduResult("suspect", null, 1, rejectedVerdict));
    List<OfflineProcessingOutcome> progress = new ArrayList<>();

    OfflineProcessingOutcome outcome = run(progress);

    assertEquals(2, outcome.failed());
    assertTrue(progress.stream().anyMatch(p -> p.failed() == 1 && p.remaining() == 1));
    assertTrue(progress.stream().anyMatch(p -> p.failed() == 2 && p.remaining() == 0));
  }

  @Test
  void unacknowledgedNoTextDoesNotAdvanceFailureCheckpoint() throws Exception {
    assertUnacknowledgedFailureDoesNotAdvance(
        new VduProcessor.VduResult("", "{}", 1));
  }

  @Test
  void unacknowledgedRejectionDoesNotAdvanceFailureCheckpoint() throws Exception {
    GateVerdict rejectedVerdict = new GateVerdict(true, GateVerdict.Band.REJECT,
        VduAbstentionGate.STAGE_INPUT_LEGIBILITY, null, null, null, null,
        1.0, 0.01, null, null);
    assertUnacknowledgedFailureDoesNotAdvance(
        new VduProcessor.VduResult("suspect", null, 1, rejectedVerdict));
  }

  private void assertUnacknowledgedFailureDoesNotAdvance(VduProcessor.VduResult result)
      throws Exception {
    Path file = file("unacknowledged-failed-unit.png");
    select(file);
    when(processor.process(file, context)).thenReturn(result);
    when(client.updateVduResult(anyString(), any(), any(), any(), anyInt(), same(context)))
        .thenReturn(false);
    List<OfflineProcessingOutcome> progress = new ArrayList<>();

    assertThrows(IllegalStateException.class, () -> run(progress));

    OfflineProcessingOutcome checkpoint = progress.get(progress.size() - 1);
    assertEquals(0, checkpoint.failed());
    assertEquals(1, checkpoint.remaining());
  }

  @Test
  void falseWriteAfterFirstSuccessPreservesLastCheckpointAndStopsWithoutFallback() throws Exception {
    assertSecondWriteFailure(false);
  }

  @Test
  void throwingWriteAfterFirstSuccessPreservesLastCheckpointAndStopsWithoutFallback() throws Exception {
    assertSecondWriteFailure(true);
  }

  private void assertSecondWriteFailure(boolean throwsFailure) throws Exception {
    Path first = file("first-" + throwsFailure + ".png");
    Path second = file("second-" + throwsFailure + ".png");
    Path third = file("third-" + throwsFailure + ".png");
    when(client.queryPendingVduDocIds(context))
        .thenReturn(List.of(first.toString(), second.toString(), third.toString()));
    when(processor.process(any(Path.class), same(context)))
        .thenReturn(new VduProcessor.VduResult("text", "{}", 1));
    AtomicInteger writes = new AtomicInteger();
    when(client.updateVduResult(anyString(), any(), any(), any(), anyInt(), same(context)))
        .thenAnswer(invocation -> {
          if (writes.incrementAndGet() == 1) return true;
          if (throwsFailure) throw new IllegalStateException("write failed");
          return false;
        });
    List<OfflineProcessingOutcome> progress = new ArrayList<>();

    assertThrows(IllegalStateException.class, () -> run(progress));

    assertEquals(2, writes.get(), "there must be no fallback FAILED write");
    OfflineProcessingOutcome checkpoint = progress.get(progress.size() - 1);
    assertEquals(1, checkpoint.processed());
    assertEquals(0, checkpoint.failed());
    assertEquals(2, checkpoint.remaining());
    verify(processor, never()).process(third, context);
  }

  @Test
  void negativeMarkLeavesUnitUnacknowledgedAndRemaining() throws Exception {
    Path file = file("mark-refused.png");
    select(file);
    when(client.markVduProcessing(anyString(), anyInt(), same(context))).thenReturn(-1);
    List<OfflineProcessingOutcome> progress = new ArrayList<>();

    OfflineProcessingOutcome outcome = run(progress);

    assertEquals(BlockReason.PROCESSING_REFUSED, outcome.blockedReason());
    assertEquals(0, outcome.failed());
    assertEquals(1, outcome.remaining());
    assertSame(outcome, progress.get(progress.size() - 1));
  }

  @Test
  void bodyFailureRemainsPrimaryWhenModeExitAlsoFails() throws Exception {
    Path file = file("body-and-exit.png");
    select(file);
    when(processor.process(file, context))
        .thenReturn(new VduProcessor.VduResult("text", "{}", 1));
    when(client.updateVduResult(anyString(), any(), any(), any(), anyInt(), same(context)))
        .thenReturn(false);
    IllegalStateException exitFailure = new IllegalStateException("exit failed");
    doThrow(exitFailure).when(processor).exitVduMode();

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> run(new ArrayList<>()));

    assertEquals("VDU result was not acknowledged by the index", thrown.getMessage());
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(exitFailure, thrown.getSuppressed()[0]);
  }

  @Test
  void cancellationEscapesWithoutWritingFailedOutcome() throws Exception {
    Path file = file("cancel.png");
    select(file);
    CancellationException cancellation = new CancellationException("cancelled");
    when(processor.process(file, context)).thenThrow(cancellation);

    CancellationException thrown =
        assertThrows(CancellationException.class, () -> run(new ArrayList<>()));

    assertSame(cancellation, thrown);
    verify(client, never()).updateVduResult(anyString(), any(), any(), any(), anyInt(), any());
    verify(processor).exitVduMode();
  }

  @Test
  void openCircuitReportsAcknowledgedFailuresAndRemainingWork() throws Exception {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 6; i++) ids.add(file("circuit-" + i + ".png").toString());
    when(client.queryPendingVduDocIds(context)).thenReturn(ids);
    when(processor.process(any(Path.class), same(context)))
        .thenThrow(new VduProcessor.VduException("model failed", null));

    OfflineProcessingOutcome outcome = run(new ArrayList<>());

    assertEquals(BlockReason.CIRCUIT_OPEN, outcome.blockedReason());
    assertEquals(5, outcome.failed());
    assertEquals(1, outcome.remaining());
    verify(client, times(5)).updateVduResult(anyString(), any(),
        same(VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED), any(), anyInt(), same(context));
  }

  private OfflineProcessingOutcome run(List<OfflineProcessingOutcome> progress) {
    return new VduBatchProcessor(processor, gpu, () -> client, VduMetricCatalog.noop(),
        new VduCapabilityState()).processPendingFiles(context, progress::add);
  }

  private void select(Path file) {
    when(client.queryPendingVduDocIds(context)).thenReturn(List.of(file.toString()));
  }

  private Path file(String name) throws Exception {
    Path file = tempDir.resolve(name);
    Files.writeString(file, "image");
    return file;
  }
}
