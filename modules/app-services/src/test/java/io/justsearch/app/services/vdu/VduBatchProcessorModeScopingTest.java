/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.ipc.VduUpdateOutcome;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test for tempdoc 672's follow-up fix: VDU mode must be entered/exited once per
 * batch, not once per document (each transition is a full {@code llama-server} restart,
 * ~10-12s). Exercises the real {@link VduBatchProcessor} against a mocked {@link VduProcessor}
 * so the assertion is on the real production interaction, not a test-double reimplementation
 * (the existing {@code TestableVduBatchProcessor} in {@code VduBatchProcessorTest} never modeled
 * mode transitions at all, so it could not have caught this regression either way).
 */
@DisplayName("VduBatchProcessor — VDU mode batch scoping (tempdoc 672 follow-up)")
class VduBatchProcessorModeScopingTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("enterVduMode/exitVduMode are each called exactly once for a multi-document batch")
  void modeTransitionsAreBatchScopedNotPerDocument() throws Exception {
    VduProcessor vduProcessor = mock(VduProcessor.class);
    when(vduProcessor.hasVisionCapability()).thenReturn(true);
    when(vduProcessor.process(any(Path.class)))
        .thenReturn(new VduProcessor.VduResult("extracted text", "{}", 1));

    KnowledgeClient client = mock(KnowledgeClient.class);
    Path file1 = writeFile("doc1.png");
    Path file2 = writeFile("doc2.png");
    Path file3 = writeFile("doc3.png");
    List<String> docIds = List.of(file1.toString(), file2.toString(), file3.toString());
    when(client.countPendingVdu()).thenReturn(docIds.size());
    when(client.queryPendingVduDocIds()).thenReturn(docIds);
    when(client.markVduProcessing(anyString(), anyInt())).thenReturn(0);
    when(client.updateVduResult(
            anyString(), any(), any(VduUpdateOutcome.class), any(), anyInt()))
        .thenReturn(true);

    GpuCapabilitiesService gpuCapabilitiesService = mock(GpuCapabilitiesService.class);
    when(gpuCapabilitiesService.snapshot()).thenReturn(highVramSnapshot());

    VduBatchProcessor batchProcessor =
        new VduBatchProcessor(
            vduProcessor,
            gpuCapabilitiesService,
            () -> client,
            VduMetricCatalog.noop(),
            new VduCapabilityState());

    int processed = batchProcessor.processPendingFiles();

    assertEquals(3, processed, "all three documents should have been processed");
    verify(vduProcessor, times(1)).enterVduMode();
    verify(vduProcessor, times(1)).exitVduMode();
    verify(vduProcessor, times(3)).process(any(Path.class));
  }

  @Test
  @DisplayName("a failure to enter VDU mode skips the whole batch (not a retry per document)")
  void enterVduModeFailureSkipsWholeBatch() throws Exception {
    VduProcessor vduProcessor = mock(VduProcessor.class);
    when(vduProcessor.hasVisionCapability()).thenReturn(true);
    org.mockito.Mockito.doThrow(new VduProcessor.VduException("simulated failure", null))
        .when(vduProcessor)
        .enterVduMode();

    KnowledgeClient client = mock(KnowledgeClient.class);
    List<String> docIds = List.of(writeFile("doc1.png").toString(), writeFile("doc2.png").toString());
    when(client.countPendingVdu()).thenReturn(docIds.size());
    when(client.queryPendingVduDocIds()).thenReturn(docIds);

    GpuCapabilitiesService gpuCapabilitiesService = mock(GpuCapabilitiesService.class);
    when(gpuCapabilitiesService.snapshot()).thenReturn(highVramSnapshot());

    VduBatchProcessor batchProcessor =
        new VduBatchProcessor(
            vduProcessor,
            gpuCapabilitiesService,
            () -> client,
            VduMetricCatalog.noop(),
            new VduCapabilityState());

    int processed = batchProcessor.processPendingFiles();

    assertEquals(0, processed, "batch should be skipped entirely, not attempted per-document");
    verify(vduProcessor, times(1)).enterVduMode();
    verify(vduProcessor, times(0)).process(any(Path.class));
    // exitVduMode is only meaningful once mode was actually entered; the batch bails before that.
    verify(vduProcessor, times(0)).exitVduMode();
  }

  @Test
  @DisplayName("shouldInterruptBatch stops the batch early, leaving remaining docs unprocessed, but still exits VDU mode")
  void interruptStopsEarlyButStillExitsVduMode() throws Exception {
    VduProcessor vduProcessor = mock(VduProcessor.class);
    when(vduProcessor.hasVisionCapability()).thenReturn(true);
    when(vduProcessor.process(any(Path.class)))
        .thenReturn(new VduProcessor.VduResult("extracted text", "{}", 1));

    KnowledgeClient client = mock(KnowledgeClient.class);
    List<String> docIds =
        List.of(
            writeFile("doc1.png").toString(),
            writeFile("doc2.png").toString(),
            writeFile("doc3.png").toString());
    when(client.countPendingVdu()).thenReturn(docIds.size());
    when(client.queryPendingVduDocIds()).thenReturn(docIds);
    when(client.markVduProcessing(anyString(), anyInt())).thenReturn(0);
    when(client.updateVduResult(
            anyString(), any(), any(VduUpdateOutcome.class), any(), anyInt()))
        .thenReturn(true);

    GpuCapabilitiesService gpuCapabilitiesService = mock(GpuCapabilitiesService.class);
    when(gpuCapabilitiesService.snapshot()).thenReturn(highVramSnapshot());

    // Interrupt becomes true after the first document — simulates the user becoming active
    // mid-batch (tempdoc 672 follow-up).
    java.util.concurrent.atomic.AtomicInteger checkCount = new java.util.concurrent.atomic.AtomicInteger(0);
    java.util.function.BooleanSupplier shouldInterrupt = () -> checkCount.getAndIncrement() > 0;

    VduBatchProcessor batchProcessor =
        new VduBatchProcessor(
            vduProcessor,
            gpuCapabilitiesService,
            () -> client,
            VduMetricCatalog.noop(),
            new VduCapabilityState(),
            shouldInterrupt);

    int processed = batchProcessor.processPendingFiles();

    assertEquals(1, processed, "only the first document should have been processed before the interrupt");
    verify(vduProcessor, times(1)).enterVduMode();
    verify(vduProcessor, times(1)).exitVduMode();
    verify(vduProcessor, times(1)).process(any(Path.class));
  }

  private Path writeFile(String name) throws Exception {
    Path file = tempDir.resolve(name);
    java.nio.file.Files.writeString(file, "fake image bytes");
    return file;
  }

  private static GpuCapabilities highVramSnapshot() {
    var effective =
        new GpuCapabilities.Effective(
            true,
            "test",
            GpuCapabilities.Confidence.HIGH,
            "1.0",
            1,
            0,
            1,
            24_000_000_000L,
            20_000_000_000L,
            4_000_000_000L,
            GpuCapabilities.Cuda.unknown());
    return new GpuCapabilities(null, null, effective);
  }
}
