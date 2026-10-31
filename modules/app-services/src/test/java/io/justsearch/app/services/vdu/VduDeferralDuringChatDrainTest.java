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
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.chat.GenerationTracker;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.context.EngineContext;
import io.justsearch.gpu.GpuCapabilitiesService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A VDU pass that meets a generative gate refusing because chat drains, starts or stops defers the
 * document exactly as when AI is offline: it is not counted failed, stays remaining for the next
 * pass, and the pass reports the AI-offline block (b6d536 item 5). The refusal is the production
 * {@link GenerationTracker}'s, wrapped as {@code VduProcessor} wraps every model failure.
 */
class VduDeferralDuringChatDrainTest {
  @TempDir Path tempDir;

  @Test
  void aDrainingGateDefersTheDocumentInsteadOfFailingIt() throws Exception {
    Path image = tempDir.resolve("scan.png");
    Files.writeString(image, "image");
    VduProcessor processor = VduAttemptTestSupport.processor();
    KnowledgeClient client = VduAttemptTestSupport.client();
    GpuCapabilitiesService gpu = mock(GpuCapabilitiesService.class);
    EngineContext context = TestEngineContexts.durableInternal();
    when(processor.hasVisionCapability()).thenReturn(true);
    when(gpu.snapshot()).thenReturn(VduBatchProcessorTest.gpuSnapshot(24_000_000_000L));
    when(client.queryPendingVduDocIds(context)).thenReturn(List.of(image.toString()));
    when(client.markVduProcessing(anyString(), anyInt(), any())).thenReturn(0);
    when(client.updateVduResult(anyString(), any(), any(), any(), anyInt(), any()))
        .thenReturn(true);
    when(client.releaseVduProcessing(anyString(), any())).thenReturn(true);
    when(processor.process(image, context))
        .thenThrow(new VduProcessor.VduException("vision call failed", drainingGateRefusal()));
    var capability = new VduCapabilityState();

    OfflineProcessingOutcome outcome =
        new VduBatchProcessor(processor, gpu, () -> client, VduMetricCatalog.noop(), capability)
            .processPendingFiles(context, new ArrayList<OfflineProcessingOutcome>()::add);

    assertEquals(0, outcome.failed(), "a refused admission is not the document's failure");
    assertEquals(0, outcome.processed());
    assertEquals(1, outcome.remaining(), "the document is retried on the next pass");
    assertEquals(BlockReason.AI_OFFLINE, outcome.blockedReason(), "the same block as AI offline");
    verify(client, never()).updateVduResult(anyString(), any(), any(), any(), anyInt(), any());
  }

  private static RuntimeException drainingGateRefusal() {
    var gate = new GenerationTracker(work -> () -> {}, Runnable::run);
    ChatActuator.DrainHold drain = gate.closeNow();
    try (drain) {
      return assertThrows(IllegalStateException.class, gate::requireOpen);
    }
  }
}
