/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OrtException;
import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.indexerworker.bgem3.BgeM3Encoder;
import io.justsearch.indexerworker.bgem3.BgeM3Output;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingConfig;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingBackend;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.fixtures.TestDocumentBuilder;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.BioTagDecoder;
import io.justsearch.indexerworker.ner.NerAssembly;
import io.justsearch.indexerworker.ner.NerConfig;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.ner.NerShape;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.ort.SessionHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spec D G4: every bulk encode reachable from the indexing loop's ops acquires its native session
 * as BACKGROUND, so the device can let a query go first.
 *
 * <p>Each check enters through the production loop op and records the urgency at the native
 * boundary. The embedding checks run the real {@link EmbeddingService} and {@link
 * OnnxEmbeddingBackend}; only the native encoder is replaced. The SPLADE and BGE-M3 checks keep
 * each encoder's real single-text overload and record the urgency it forwards. The NER check runs
 * the real {@link NerService} and records the urgency at its {@link SessionHandle}.
 */
@DisplayName("Bulk encodes acquire BACKGROUND (Spec D G4)")
class BulkEncodeUrgencyTest {

  private static final Logger LOG = LoggerFactory.getLogger(BulkEncodeUrgencyTest.class);

  private final List<String> urgencies = new CopyOnWriteArrayList<>();

  private final DocumentFieldOps documentFieldOps = mock(DocumentFieldOps.class);
  private final IndexingCoordinator indexingCoordinator = mock(IndexingCoordinator.class);
  private final CommitOps commitOps = mock(CommitOps.class);
  private final WorkerSignalBus signalBus = mock(WorkerSignalBus.class);

  @org.junit.jupiter.api.BeforeEach void snapshots() {
    BackfillSnapshotTestSupport.install(documentFieldOps,indexingCoordinator,false,Set.of(SchemaFields.IS_CHUNK));
  }

  private void assertOnlyBackground() {
    assertFalse(urgencies.isEmpty(), "the bulk path must reach the encoder");
    assertEquals(List.of("BACKGROUND"), urgencies.stream().distinct().toList(),
        "bulk work acquired with urgencies " + urgencies);
  }

  /** Real EmbeddingService and ONNX backend; the native encoder records the acquisition urgency. */
  private EmbeddingService recordingEmbeddingService() throws OrtException {
    OnnxEmbeddingEncoder encoder = mock(OnnxEmbeddingEncoder.class);
    when(encoder.embed(anyString(), any(SessionAcquisitionRequest.class))).thenAnswer(inv -> {
      urgencies.add(inv.<SessionAcquisitionRequest>getArgument(1).urgency().name());
      return new OnnxEmbeddingEncoder.EmbedResult(new float[] {1f, 2f}, List.of(), 1);
    });
    // An unusable batch result sends the loop op down its per-item fallback.
    when(encoder.embedBatchPooled(anyList())).thenReturn(List.of());
    var config = new EmbeddingConfig(
        true, Path.of("g4-embedding-model"), "onnx", false, 0, 0, 512, false, 0);
    return EmbeddingService.createWithBackend(new OnnxEmbeddingBackend(encoder, 0, 512), config);
  }

  private EmbeddingBackfillOps.BackfillContext embeddingContext(EmbeddingService service) {
    return new EmbeddingBackfillOps.BackfillContext(
        documentFieldOps, indexingCoordinator, commitOps, signalBus,
        IndexingPacing.unthrottled(), () -> service, () -> true, () -> true, 100, LOG);
  }

  @Test
  void parentEmbeddingBackfillFallbackAcquiresBackground() throws Exception {
    EmbeddingService service = recordingEmbeddingService();
    when(documentFieldOps.queryDocIdsByField(
            eq(SchemaFields.EMBEDDING_STATUS), eq(SchemaFields.EMBEDDING_STATUS_PENDING), anyInt()))
        .thenReturn(List.of("doc1"));
    when(documentFieldOps.getDocumentContent("doc1")).thenReturn("parent content");

    EmbeddingBackfillOps.processEmbeddingBackfill(embeddingContext(service));

    assertOnlyBackground();
  }

  @Test
  void chunkEmbeddingBackfillFallbackAcquiresBackground() throws Exception {
    EmbeddingService service = recordingEmbeddingService();
    when(documentFieldOps.queryDocIdsByField(
            eq(SchemaFields.CHUNK_EMBEDDING_STATUS), eq(SchemaFields.EMBEDDING_STATUS_PENDING),
            anyInt()))
        .thenReturn(List.of("chunk1"));
    when(documentFieldOps.getDocumentContentBatch(List.of("chunk1")))
        .thenReturn(Map.of("chunk1", "chunk content"));

    EmbeddingBackfillOps.processChunkEmbeddingBackfill(embeddingContext(service));

    assertOnlyBackground();
  }

  @Test
  void inlineDocumentEmbeddingAcquiresBackground(@TempDir Path dir) throws Exception {
    EmbeddingService service = recordingEmbeddingService();
    Path file = Files.writeString(dir.resolve("note.txt"), "inline content");

    IndexingDocumentOps.buildDocument(
        file,
        TestDocumentBuilder.validatedArtifact(
            new ExtractionResult("inline content", null, "text/plain")),
        null,
        signalBus,
        service,
        true,
        null,
        null,
        (stageId, durationMs, reasonCode) -> {},
        LOG,
        null,
        null,
        "g4-doc#0");

    assertOnlyBackground();
  }

  @Test
  void spladeBackfillFallbackAcquiresBackground() throws Exception {
    SpladeEncoder encoder = mock(SpladeEncoder.class);
    when(encoder.encodeBatch(anyList())).thenThrow(new OrtException("batch unavailable"));
    // The single-text overload keeps its production body, so the urgency it forwards is recorded.
    doCallRealMethod().when(encoder).encode(anyString());
    when(encoder.encode(anyString(), any(InferenceRequest.class))).thenAnswer(inv -> {
      urgencies.add(inv.<InferenceRequest>getArgument(1).urgency().name());
      return Map.of("tok", 1.0f);
    });
    when(documentFieldOps.queryDocIdsByField(
            eq(SchemaFields.SPLADE_STATUS), eq(SchemaFields.SPLADE_STATUS_PENDING), anyInt()))
        .thenReturn(List.of("doc1"));
    when(documentFieldOps.getDocumentContentBatch(List.of("doc1")))
        .thenReturn(Map.of("doc1", "sparse content"));

    SpladeBackfillOps.processSpladeBackfill(new SpladeBackfillOps.BackfillContext(
        documentFieldOps, indexingCoordinator, commitOps, IndexingPacing.unthrottled(),
        () -> encoder, () -> true, 100, false, true, LOG));

    assertOnlyBackground();
  }

  @Test
  void bgeM3BackfillFallbackAcquiresBackground() throws Exception {
    BgeM3Encoder encoder = mock(BgeM3Encoder.class);
    when(encoder.encodeBatch(anyList())).thenThrow(new OrtException("batch unavailable"));
    doCallRealMethod().when(encoder).encode(anyString());
    when(encoder.encode(anyString(), any(InferenceRequest.class))).thenAnswer(inv -> {
      urgencies.add(inv.<InferenceRequest>getArgument(1).urgency().name());
      return new BgeM3Output(new float[] {1f, 2f}, Map.of("tok", 1.0f));
    });
    when(documentFieldOps.queryDocIdsByField(
            eq(SchemaFields.SPLADE_STATUS), eq(SchemaFields.SPLADE_STATUS_PENDING), anyInt()))
        .thenReturn(List.of("doc1"));
    when(documentFieldOps.getDocumentContentBatch(List.of("doc1")))
        .thenReturn(Map.of("doc1", "dense and sparse content"));
    when(documentFieldOps.getDocumentFieldsBatch(List.of("doc1"), Set.of(SchemaFields.IS_CHUNK)))
        .thenReturn(Map.of("doc1", Map.of()));

    BgeM3BackfillOps.processBgeM3Backfill(new BgeM3BackfillOps.BackfillContext(
        documentFieldOps, indexingCoordinator, commitOps, signalBus, () -> encoder, () -> true,
        100, false, true, LOG));

    assertOnlyBackground();
  }

  @Test
  void nerBackfillAcquiresBackground() throws Exception {
    BackfillSnapshotTestSupport.install(documentFieldOps,indexingCoordinator,true,Set.of());
    Encoding encoding = mock(Encoding.class);
    when(encoding.getIds()).thenReturn(new long[] {101, 7, 102});
    when(encoding.getAttentionMask()).thenReturn(new long[] {1, 1, 1});
    when(encoding.getTokens()).thenReturn(new String[] {"[CLS]", "alice", "[SEP]"});
    when(encoding.getWordIds()).thenReturn(new long[] {-1, 0, -1});
    HuggingFaceTokenizer tokenizer = mock(HuggingFaceTokenizer.class);
    when(tokenizer.encode(anyString())).thenReturn(encoding);
    SessionHandle sessions = mock(SessionHandle.class);
    when(sessions.isGpuAvailable()).thenReturn(true);
    when(sessions.acquire(any(SessionAcquisitionRequest.class))).thenAnswer(inv -> {
      urgencies.add(inv.<SessionAcquisitionRequest>getArgument(0).urgency().name());
      throw new IllegalStateException("G4 records the acquisition and stops before the native run");
    });
    var assembly = new NerAssembly(sessions, new NerShape(512, false), tokenizer,
        BioTagDecoder.LabelMapping.bertBaseNer(), null);
    var nerService = new NerService(
        assembly, new NerConfig(true, Path.of("g4-ner-model"), 512, 0.5f, true, 0, 0));
    when(documentFieldOps.queryDocIdsByField(
            eq(SchemaFields.NER_STATUS), eq(SchemaFields.NER_STATUS_PENDING), anyInt()))
        .thenReturn(List.of("doc1"));
    when(documentFieldOps.getDocumentContent("doc1")).thenReturn("Alice met Bob in Paris.");

    NerBackfillOps.processNerBackfill(new NerBackfillOps.BackfillContext(
        documentFieldOps, indexingCoordinator, commitOps, IndexingPacing.unthrottled(),
        () -> nerService, () -> true, 100, LOG));

    assertOnlyBackground();
  }
}
