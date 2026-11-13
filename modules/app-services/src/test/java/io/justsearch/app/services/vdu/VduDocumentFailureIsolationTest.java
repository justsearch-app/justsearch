/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OfflineProcessingOutcome.BlockReason;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.ipc.VduUpdateOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VduDocumentFailureIsolationTest {
  @TempDir Path directory;

  @Test
  void missingDocumentNeitherCountsNorResetsServiceFailures() throws Exception {
    var processor = mock(VduProcessor.class);
    var index = mock(KnowledgeClient.class);
    var gpu = mock(GpuCapabilitiesService.class);
    var context = TestEngineContexts.durableInternal();
    var ids = new ArrayList<String>();
    for (int i = 0; i < 7; i++) {
      Path path = directory.resolve("document-" + i + ".png");
      if (i != 4) Files.writeString(path, "controlled processor input");
      ids.add(path.toString());
    }
    when(processor.hasVisionCapability()).thenReturn(true);
    when(processor.process(any(Path.class), eq(context), any(BooleanSupplier.class)))
        .thenThrow(new VduProcessor.VduException("generation failed", null));
    when(gpu.snapshot()).thenReturn(VduBatchProcessorTest.gpuSnapshot(24_000_000_000L));
    when(index.queryPendingVduDocIds(context)).thenReturn(ids);
    when(index.markVduProcessing(anyString(), anyInt(), anyString(), eq(context))).thenReturn(1);
    when(index.updateVduResult(anyString(), isNull(), eq(VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED),
        anyString(), eq(0), anyString(), eq(context))).thenReturn(true);

    var result = new VduBatchProcessor(processor, gpu, () -> index)
        .processPendingFiles(context, ignored -> {});

    // Four service errors, one missing input, then the fifth service error opens the circuit.
    assertEquals(6, result.failed());
    assertEquals(1, result.remaining());
    assertEquals(BlockReason.CIRCUIT_OPEN, result.blockedReason());
    verify(processor, times(5)).process(any(Path.class), eq(context), any(BooleanSupplier.class));
    verify(index).updateVduResult(eq(ids.get(5)), isNull(), eq(VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED),
        anyString(), eq(0), anyString(), eq(context));
    verify(index, never()).markVduProcessing(eq(ids.get(6)), anyInt(), anyString(), eq(context));
  }
}
