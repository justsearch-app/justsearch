/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.app.api.OfflineProcessingOutcome.BlockReason;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.context.EngineContext;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.ipc.VduUpdateOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Production-backed coverage for VDU pass selection, gates, and acknowledged results. */
class VduBatchProcessorTest {
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
    when(gpu.snapshot()).thenReturn(gpuSnapshot(24_000_000_000L));
    when(client.queryPendingVduDocIds(context)).thenReturn(List.of());
    when(client.markVduProcessing(anyString(), anyInt(), any())).thenReturn(0);
    when(client.updateVduResult(anyString(), any(), any(), any(), anyInt(), any()))
        .thenReturn(true);
  }

  @Test
  void lowVramBlocksCapturedPass() throws Exception {
    Path file = file("low-vram.png");
    when(client.queryPendingVduDocIds(context)).thenReturn(List.of(file.toString()));
    when(gpu.snapshot()).thenReturn(gpuSnapshot(1_000_000_000L));

    OfflineProcessingOutcome outcome = run(new ArrayList<>());

    assertEquals(1, outcome.selected());
    assertEquals(1, outcome.remaining());
    assertEquals(BlockReason.INSUFFICIENT_VRAM, outcome.blockedReason());
    verify(processor, never()).enterVduMode();
  }

  @Test
  void emptySelectionDoesNotEnterModelMode() throws Exception {
    OfflineProcessingOutcome outcome = run(new ArrayList<>());

    assertEquals(0, outcome.selected());
    assertEquals(0, outcome.remaining());
    verify(processor, never()).enterVduMode();
  }

  @Test
  void acknowledgedTextIsProcessed() throws Exception {
    Path file = file("text.png");
    select(file);
    when(processor.process(file, context))
        .thenReturn(new VduProcessor.VduResult("text", "{}", 1));

    OfflineProcessingOutcome outcome = run(new ArrayList<>());

    assertEquals(1, outcome.processed());
    assertEquals(0, outcome.failed());
    verify(client).updateVduResult(file.toString(), "text",
        VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT, "{}", 1, context);
  }

  @Test
  void multipleAcknowledgedTextsAreProcessed() throws Exception {
    Path first = file("first.png");
    Path second = file("second.png");
    when(client.queryPendingVduDocIds(context))
        .thenReturn(List.of(first.toString(), second.toString()));
    when(processor.process(any(Path.class), any(EngineContext.class)))
        .thenReturn(new VduProcessor.VduResult("text", "{}", 1));

    OfflineProcessingOutcome outcome = run(new ArrayList<>());

    assertEquals(2, outcome.processed());
    assertEquals(0, outcome.remaining());
  }

  @Test
  void processingRefusalLeavesSelectedUnitRemaining() throws Exception {
    Path file = file("refused.png");
    select(file);
    when(client.markVduProcessing(
        org.mockito.ArgumentMatchers.eq(file.toString()), anyInt(),
        org.mockito.ArgumentMatchers.same(context))).thenReturn(-1);

    OfflineProcessingOutcome outcome = run(new ArrayList<>());

    assertEquals(0, outcome.processed());
    assertEquals(0, outcome.failed());
    assertEquals(1, outcome.remaining());
    assertEquals(BlockReason.PROCESSING_REFUSED, outcome.blockedReason());
    verify(processor, never()).process(any(), any());
  }

  @Test
  void missingFileCountsFailedOnlyAfterFailedWriteAcknowledgement() {
    Path missing = tempDir.resolve("missing.png");
    select(missing);

    OfflineProcessingOutcome outcome = run(new ArrayList<>());

    assertEquals(0, outcome.processed());
    assertEquals(1, outcome.failed());
    verify(client).updateVduResult(
        org.mockito.ArgumentMatchers.eq(missing.toString()),
        org.mockito.ArgumentMatchers.isNull(),
        org.mockito.ArgumentMatchers.eq(VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED),
        anyString(), org.mockito.ArgumentMatchers.eq(0),
        org.mockito.ArgumentMatchers.same(context));
  }

  @Test
  void blankNullAndWhitespaceResultsAreAcknowledgedAsNoText() throws Exception {
    for (String text : new String[] {"", null, "   "}) {
      Path file = file("empty-" + (text == null ? "null" : text.length()) + ".png");
      select(file);
      when(processor.process(file, context))
          .thenReturn(new VduProcessor.VduResult(text, "{\"partial\":true}", 4));

      OfflineProcessingOutcome outcome = run(new ArrayList<>());

      assertEquals(1, outcome.failed());
      verify(client).updateVduResult(
          org.mockito.ArgumentMatchers.eq(file.toString()), org.mockito.ArgumentMatchers.isNull(),
          org.mockito.ArgumentMatchers.eq(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY),
          org.mockito.ArgumentMatchers.contains("no_text_detected"),
          org.mockito.ArgumentMatchers.eq(4), org.mockito.ArgumentMatchers.same(context));
      org.mockito.Mockito.reset(processor, client);
      when(processor.hasVisionCapability()).thenReturn(true);
      when(client.markVduProcessing(anyString(), anyInt(), any())).thenReturn(0);
      when(client.updateVduResult(anyString(), any(), any(), any(), anyInt(), any()))
          .thenReturn(true);
    }
  }

  @Test
  void acknowledgedModelFailureDoesNotPreventFollowingDocument() throws Exception {
    Path first = file("failure.png");
    Path second = file("success.png");
    when(client.queryPendingVduDocIds(context))
        .thenReturn(List.of(first.toString(), second.toString()));
    when(processor.process(first, context))
        .thenThrow(new VduProcessor.VduException("model failed", null));
    when(processor.process(second, context))
        .thenReturn(new VduProcessor.VduResult("text", "{}", 1));

    OfflineProcessingOutcome outcome = run(new ArrayList<>());

    assertEquals(1, outcome.processed());
    assertEquals(1, outcome.failed());
    assertEquals(0, outcome.remaining());
  }

  @Test
  void unacknowledgedResultIsAnErrorRatherThanProgress() throws Exception {
    Path file = file("unacknowledged.png");
    select(file);
    when(processor.process(file, context))
        .thenReturn(new VduProcessor.VduResult("text", "{}", 1));
    when(client.updateVduResult(anyString(), any(), any(), any(), anyInt(), any()))
        .thenReturn(false);

    assertThrows(IllegalStateException.class, () -> run(new ArrayList<>()));
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

  static GpuCapabilities gpuSnapshot(long totalVramBytes) {
    var effective = new GpuCapabilities.Effective(true, "test", GpuCapabilities.Confidence.HIGH,
        "1.0", 1, 0, 1, totalVramBytes, totalVramBytes, 0L,
        GpuCapabilities.Cuda.unknown());
    return new GpuCapabilities(null, null, effective);
  }
}
