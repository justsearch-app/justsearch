/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.core.scheduling.BulkWorkGate;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.fixtures.TestDocumentBuilder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spec E section 1.3 item 3 (IndexingDocumentOps, inline primary embedding) under the bulk work
 * gate, through the production {@link InProcessWorkerSignalBus} over the Engine's gate:
 *
 * <ul>
 *   <li>E-P7d, inline half (RV3 F3): with energy saver on, the inline embedding still runs as
 *       before, and the gate's hold is not set; energy saver is status only.
 *   <li>Held for external memory use (P12), the inline embedding is deferred to PENDING like a main
 *       GPU claim, so no bulk GPU encode starts at ingest.
 * </ul>
 *
 * <p>The bus is built over the gate when the bus can take one; a bus that cannot (the base before
 * step E3) is built over the gauge alone, so these checks run against the before-state's API.
 */
final class InlineEmbeddingBulkHoldTest {

  private static final Logger LOG = LoggerFactory.getLogger(InlineEmbeddingBulkHoldTest.class);

  // E-P7d inline half: energy saver on, the inline embed still runs and held() is false
  @Test
  void energySaverStillEmbedsInline(@TempDir Path dir) throws Exception {
    var gauge = new GpuSchedulingGauge();
    gauge.setEnergyReduced(true);
    var gate = new BulkWorkGate();
    gate.setProfile(BulkWorkGate.Profile.P12);
    gate.setEnergyReduced(true);
    assertFalse(gate.held(), "energy saver never sets the hold");
    assertEquals(List.of(BulkWorkGate.Reason.ENERGY_SAVER), gate.verdict().reasons());
    EmbeddingProvider provider = embeddingProvider();

    IndexDocument doc = build(dir, busOver(gauge, gate), provider);

    assertEquals(1, embedCalls(provider), "energy saver leaves the inline embed running");
    assertEquals(SchemaFields.EMBEDDING_STATUS_COMPLETED,
        doc.fields().get(SchemaFields.EMBEDDING_STATUS));
    assertNotNull(doc.fields().get(SchemaFields.VECTOR));
  }

  // section 1.3 item 3: held for external use, the inline embed is deferred to PENDING
  @Test
  void heldBulkWorkDefersInlineEmbedding(@TempDir Path dir) throws Exception {
    var gauge = new GpuSchedulingGauge();
    var gate = new BulkWorkGate();
    gate.setProfile(BulkWorkGate.Profile.P12);
    gate.setExternalPressure(true);
    EmbeddingProvider provider = embeddingProvider();

    IndexDocument doc = build(dir, busOver(gauge, gate), provider);

    assertEquals(0, embedCalls(provider), "no inline embed starts while bulk work is held");
    assertEquals(SchemaFields.EMBEDDING_STATUS_PENDING,
        doc.fields().get(SchemaFields.EMBEDDING_STATUS));
    assertNull(doc.fields().get(SchemaFields.VECTOR));
  }

  private static long embedCalls(EmbeddingProvider provider) {
    return org.mockito.Mockito.mockingDetails(provider).getInvocations().stream()
        .filter(inv -> inv.getMethod().getName().equals("embedDocument"))
        .count();
  }

  private static EmbeddingProvider embeddingProvider() {
    EmbeddingProvider provider = mock(EmbeddingProvider.class);
    when(provider.isAvailable()).thenReturn(true);
    when(provider.embedDocument(anyString(), any(InferenceRequest.class)))
        .thenReturn(new float[] {1f, 2f});
    return provider;
  }

  private static IndexDocument build(Path dir, WorkerSignalBus bus, EmbeddingProvider provider)
      throws Exception {
    Path file = Files.writeString(dir.resolve("note.txt"), "inline content");
    return IndexingDocumentOps.buildDocument(
        file,
        TestDocumentBuilder.validatedArtifact(
            new ExtractionResult("inline content", null, "text/plain")),
        null,
        bus,
        provider,
        true,
        null,
        null,
        (stageId, durationMs, reasonCode) -> {},
        LOG,
        null,
        null,
        "e3-inline#0");
  }

  /** The production bus over the Engine's gate; over the gauge alone where it takes no gate. */
  private static WorkerSignalBus busOver(GpuSchedulingGauge gauge, BulkWorkGate gate)
      throws ReflectiveOperationException {
    try {
      return InProcessWorkerSignalBus.class
          .getConstructor(GpuSchedulingGauge.class, Path.class, BulkWorkGate.class)
          .newInstance(gauge, null, gate);
    } catch (NoSuchMethodException beforeStepE3) {
      return new InProcessWorkerSignalBus(gauge);
    }
  }
}
