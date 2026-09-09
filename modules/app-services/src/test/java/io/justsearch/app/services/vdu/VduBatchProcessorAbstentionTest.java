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
import static org.mockito.Mockito.mock;
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
import org.mockito.ArgumentCaptor;

/**
 * Regression tests for tempdoc 677 item 4: {@link VduBatchProcessor}'s consumption of {@link
 * GateVerdict} (produced by {@code VduProcessor}, tested separately in {@link
 * VduProcessorAbstentionTest}). Exercises the real {@link VduBatchProcessor} against a mocked
 * {@link VduProcessor} — mirrors {@link VduBatchProcessorModeScopingTest}'s pattern of testing
 * the real production class rather than {@code VduBatchProcessorTest}'s test-double
 * reimplementation.
 */
@DisplayName("VduBatchProcessor — abstention gate verdict consumption (tempdoc 677)")
class VduBatchProcessorAbstentionTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("a rejected verdict sends REJECTED_SUSPECT_TEXT, omits content, and carries gate evidence")
  void rejectedVerdictSendsRejectedOutcome() throws Exception {
    VduProcessor vduProcessor = mock(VduProcessor.class);
    when(vduProcessor.hasVisionCapability()).thenReturn(true);
    GateVerdict rejectedVerdict =
        new GateVerdict(
            true, GateVerdict.Band.REJECT, VduAbstentionGate.STAGE_INPUT_LEGIBILITY,
            null, null, null, null, 5.0, 0.01, null, null);
    when(vduProcessor.process(any(Path.class), org.mockito.ArgumentMatchers.any(io.justsearch.core.context.EngineContext.class)))
        .thenReturn(new VduProcessor.VduResult("", null, 2, rejectedVerdict));

    KnowledgeClient client = mock(KnowledgeClient.class);
    Path file = writeFile("doc.png");
    when(client.countPendingVdu(any())).thenReturn(1);
    when(client.queryPendingVduDocIds(any())).thenReturn(List.of(file.toString()));
    when(client.markVduProcessing(anyString(), anyInt(), any())).thenReturn(0);
    when(client.updateVduResult(
            anyString(), any(), any(VduUpdateOutcome.class), anyString(), anyInt(), any()))
        .thenReturn(true);

    VduBatchProcessor batchProcessor =
        new VduBatchProcessor(
            vduProcessor, gpuCapabilitiesService(), () -> client, VduMetricCatalog.noop(),
            new VduCapabilityState());

    int processed = batchProcessor.processPendingFiles();

    assertEquals(0, processed, "a rejected document is not counted as processed");

    ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> enrichmentCaptor = ArgumentCaptor.forClass(String.class);
    verify(client)
        .updateVduResult(
            eq(file.toString()),
            contentCaptor.capture(),
            eq(VduUpdateOutcome.VDU_UPDATE_OUTCOME_REJECTED_SUSPECT_TEXT),
            enrichmentCaptor.capture(),
            eq(2), any());

    assertNull(contentCaptor.getValue(), "the suspect/absent text must be omitted from the wire");
    String enrichment = enrichmentCaptor.getValue();
    assertTrue(enrichment.contains("\"gate\""), "gate evidence must be present: " + enrichment);
    assertTrue(enrichment.contains(VduAbstentionGate.STAGE_INPUT_LEGIBILITY));
    assertTrue(enrichment.contains("laplacianVariance"));
    assertTrue(enrichment.contains("rmsContrast"));
    // Stage 1 fields are null on this (Stage 0) verdict and must be omitted, not written null.
    assertFalse(enrichment.contains("meanLogprob"));
    assertFalse(enrichment.contains("lowConfidenceFraction"));
  }

  @Test
  @DisplayName("a Stage 2 (agreement) rejection carries agreement + probedPage evidence")
  void stage2RejectionCarriesAgreementEvidence() throws Exception {
    VduProcessor vduProcessor = mock(VduProcessor.class);
    when(vduProcessor.hasVisionCapability()).thenReturn(true);
    GateVerdict agreementRejectedVerdict =
        VduAbstentionGate.agreementVerdict(0.1).withProbedPage(2);
    when(vduProcessor.process(any(Path.class), org.mockito.ArgumentMatchers.any(io.justsearch.core.context.EngineContext.class)))
        .thenReturn(new VduProcessor.VduResult("suspect text", null, 3, agreementRejectedVerdict));

    KnowledgeClient client = mock(KnowledgeClient.class);
    Path file = writeFile("doc3.png");
    when(client.countPendingVdu(any())).thenReturn(1);
    when(client.queryPendingVduDocIds(any())).thenReturn(List.of(file.toString()));
    when(client.markVduProcessing(anyString(), anyInt(), any())).thenReturn(0);
    when(client.updateVduResult(
            anyString(), any(), any(VduUpdateOutcome.class), anyString(), anyInt(), any()))
        .thenReturn(true);

    VduBatchProcessor batchProcessor =
        new VduBatchProcessor(
            vduProcessor, gpuCapabilitiesService(), () -> client, VduMetricCatalog.noop(),
            new VduCapabilityState());

    batchProcessor.processPendingFiles();

    ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> enrichmentCaptor = ArgumentCaptor.forClass(String.class);
    verify(client)
        .updateVduResult(
            eq(file.toString()),
            contentCaptor.capture(),
            eq(VduUpdateOutcome.VDU_UPDATE_OUTCOME_REJECTED_SUSPECT_TEXT),
            enrichmentCaptor.capture(),
            eq(3), any());

    assertNull(contentCaptor.getValue(), "the suspect text must be omitted from the wire");
    String enrichment = enrichmentCaptor.getValue();
    assertTrue(enrichment.contains(VduAbstentionGate.STAGE_AGREEMENT), enrichment);
    assertTrue(enrichment.contains("\"agreement\":0.1"), enrichment);
    assertTrue(enrichment.contains("\"probedPage\":2"), enrichment);
    // Stage 0/1 fields are null on this (Stage 2) verdict and must be omitted, not written null.
    assertFalse(enrichment.contains("laplacianVariance"));
    assertFalse(enrichment.contains("meanLogprob"));
  }

  @Test
  @DisplayName("a passed verdict with text sends SUCCESS_TEXT unchanged")
  void passedVerdictWithTextSendsSuccessText() throws Exception {
    VduProcessor vduProcessor = mock(VduProcessor.class);
    when(vduProcessor.hasVisionCapability()).thenReturn(true);
    when(vduProcessor.process(any(Path.class), org.mockito.ArgumentMatchers.any(io.justsearch.core.context.EngineContext.class)))
        .thenReturn(
            new VduProcessor.VduResult(
                "genuinely extracted text", "{\"summary\":\"ok\"}", 1, GateVerdict.passed()));

    KnowledgeClient client = mock(KnowledgeClient.class);
    Path file = writeFile("doc2.png");
    when(client.countPendingVdu(any())).thenReturn(1);
    when(client.queryPendingVduDocIds(any())).thenReturn(List.of(file.toString()));
    when(client.markVduProcessing(anyString(), anyInt(), any())).thenReturn(0);
    when(client.updateVduResult(
            anyString(), any(), any(VduUpdateOutcome.class), anyString(), anyInt(), any()))
        .thenReturn(true);

    VduBatchProcessor batchProcessor =
        new VduBatchProcessor(
            vduProcessor, gpuCapabilitiesService(), () -> client, VduMetricCatalog.noop(),
            new VduCapabilityState());

    int processed = batchProcessor.processPendingFiles();

    assertEquals(1, processed);
    verify(client)
        .updateVduResult(
            eq(file.toString()),
            eq("genuinely extracted text"),
            eq(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT),
            eq("{\"summary\":\"ok\"}"),
            eq(1), any());
  }

  private Path writeFile(String name) throws Exception {
    Path file = tempDir.resolve(name);
    java.nio.file.Files.writeString(file, "fake image bytes");
    return file;
  }

  private static GpuCapabilitiesService gpuCapabilitiesService() {
    GpuCapabilitiesService service = mock(GpuCapabilitiesService.class);
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
    when(service.snapshot()).thenReturn(new GpuCapabilities(null, null, effective));
    return service;
  }
}
