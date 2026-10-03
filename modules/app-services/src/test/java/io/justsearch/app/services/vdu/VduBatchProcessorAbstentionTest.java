/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.context.EngineContext;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.ipc.VduUpdateOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/** Production-backed coverage for abstention outcome and evidence projection. */
class VduBatchProcessorAbstentionTest {
  @TempDir Path tempDir;

  @Test
  void rejectedVerdictIsAcknowledgedAsFailedWithGateEvidence() throws Exception {
    GateVerdict verdict = new GateVerdict(true, GateVerdict.Band.REJECT,
        VduAbstentionGate.STAGE_INPUT_LEGIBILITY, null, null, null, null,
        5.0, 0.01, null, null);
    Fixture fixture = fixture("rejected.png",
        new VduProcessor.VduResult("", null, 2, verdict));

    OfflineProcessingOutcome outcome = fixture.run();

    assertEquals(0, outcome.processed());
    assertEquals(1, outcome.failed());
    ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> enrichment = ArgumentCaptor.forClass(String.class);
    verify(fixture.client).updateVduResult(eq(fixture.file.toString()), content.capture(),
        eq(VduUpdateOutcome.VDU_UPDATE_OUTCOME_REJECTED_SUSPECT_TEXT),
        enrichment.capture(), eq(2), same(fixture.context));
    assertNull(content.getValue());
    assertTrue(enrichment.getValue().contains("\"gate\""));
    assertTrue(enrichment.getValue().contains(VduAbstentionGate.STAGE_INPUT_LEGIBILITY));
    assertTrue(enrichment.getValue().contains("laplacianVariance"));
    assertTrue(enrichment.getValue().contains("rmsContrast"));
    assertFalse(enrichment.getValue().contains("meanLogprob"));
  }

  @Test
  void agreementRejectionCarriesAgreementAndProbedPage() throws Exception {
    GateVerdict verdict = VduAbstentionGate.agreementVerdict(0.1).withProbedPage(2);
    Fixture fixture = fixture("agreement.png",
        new VduProcessor.VduResult("suspect", null, 3, verdict));

    OfflineProcessingOutcome outcome = fixture.run();

    assertEquals(1, outcome.failed());
    ArgumentCaptor<String> enrichment = ArgumentCaptor.forClass(String.class);
    verify(fixture.client).updateVduResult(eq(fixture.file.toString()), any(),
        eq(VduUpdateOutcome.VDU_UPDATE_OUTCOME_REJECTED_SUSPECT_TEXT),
        enrichment.capture(), eq(3), same(fixture.context));
    assertTrue(enrichment.getValue().contains(VduAbstentionGate.STAGE_AGREEMENT));
    assertTrue(enrichment.getValue().contains("\"agreement\":0.1"));
    assertTrue(enrichment.getValue().contains("\"probedPage\":2"));
    assertFalse(enrichment.getValue().contains("laplacianVariance"));
    assertFalse(enrichment.getValue().contains("meanLogprob"));
  }

  @Test
  void passedVerdictWithTextIsAcknowledgedAsProcessed() throws Exception {
    Fixture fixture = fixture("passed.png", new VduProcessor.VduResult(
        "extracted text", "{\"summary\":\"ok\"}", 1, GateVerdict.passed()));

    OfflineProcessingOutcome outcome = fixture.run();

    assertEquals(1, outcome.processed());
    verify(fixture.client).updateVduResult(fixture.file.toString(), "extracted text",
        VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT, "{\"summary\":\"ok\"}",
        1, fixture.context);
  }

  private Fixture fixture(String name, VduProcessor.VduResult result) throws Exception {
    Path file = tempDir.resolve(name);
    Files.writeString(file, "image");
    EngineContext context = TestEngineContexts.durableInternal();
    VduProcessor processor = mock(VduProcessor.class);
    when(processor.hasVisionCapability()).thenReturn(true);
    when(processor.process(file, context)).thenReturn(result);
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.queryPendingVduDocIds(context)).thenReturn(List.of(file.toString()));
    when(client.markVduProcessing(anyString(), anyInt(), same(context))).thenReturn(0);
    when(client.updateVduResult(anyString(), any(), any(), anyString(), anyInt(), same(context)))
        .thenReturn(true);
    GpuCapabilitiesService gpu = mock(GpuCapabilitiesService.class);
    when(gpu.snapshot()).thenReturn(VduBatchProcessorTest.gpuSnapshot(24_000_000_000L));
    return new Fixture(file, context, processor, client, gpu);
  }

  private record Fixture(Path file, EngineContext context, VduProcessor processor,
      KnowledgeClient client, GpuCapabilitiesService gpu) {
    OfflineProcessingOutcome run() {
      return new VduBatchProcessor(processor, gpu, () -> client, VduMetricCatalog.noop(),
          new VduCapabilityState()).processPendingFiles(context, ignored -> {});
    }
  }
}
