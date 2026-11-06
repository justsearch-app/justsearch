/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.indexerworker.loop.ops.LaneBackfillTestSupport;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.bgem3.*;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.*;
import io.justsearch.indexerworker.loop.ops.*;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.ner.*;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.ort.SessionTemporarilyUnavailableException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

/**
 * E4a owner scenarios: independent sparse sweeps and provider acquisition holds in idle service.
 */
class EnrichmentLaneDecompositionTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  private String previousMode;
  private final Map<Map<String, LongAdder>, Map<String, LongAdder>> previousMetrics =
      new IdentityHashMap<>();

  @BeforeEach
  @SuppressWarnings("unchecked")
  void saveMetrics() throws Exception {
    var metrics = OperationalMetrics.getInstance();
    previousMode = metrics.getBackfillMode();
    for (String name :
        List.of("enrichmentCompleted", "batchTimingMs", "batchTimingCount", "enrichmentOutcomes")) {
      var field = OperationalMetrics.class.getDeclaredField(name);
      field.setAccessible(true);
      var counters = (Map<String, LongAdder>) field.get(metrics);
      var saved = new HashMap<String, LongAdder>();
      counters.forEach(
          (key, value) -> {
            var copy = new LongAdder();
            copy.add(value.sum());
            saved.put(key, copy);
          });
      previousMetrics.put(counters, saved);
    }
  }

  @AfterEach
  void restoreMetrics() {
    previousMetrics.forEach(
        (counters, saved) -> {
          counters.clear();
          counters.putAll(saved);
        });
    OperationalMetrics.getInstance().recordBackfillMode(previousMode);
  }

  @Test
  void sparseLanesKeepIndependentCursorsThroughArrivalsRefreshAndDeferredSuffixes()
      throws Exception {
    try (var h = new Harness()) {
      h.seed("owner", "chunk text", Map.of());
      for (int i = 0; i < 70; i++) {
        h.seed("a%02d".formatted(i), "parent " + i, Map.of(SchemaFields.SPLADE_STATUS, "PENDING"));
        h.chunk(
            "z%02d".formatted(i),
            "owner",
            "chunk text",
            Map.of(SchemaFields.SPLADE_STATUS, "PENDING"));
      }
      h.refresh();
      var selection = new EnrichmentSelection();
      var allowed = new AtomicBoolean(true);
      var texts = new ArrayList<String>();
      when(h.sparse.encodeBatch(anyList()))
          .thenAnswer(
              call -> {
                List<String> batch = call.getArgument(0);
                texts.addAll(batch);
                allowed.set(false);
                return Collections.nCopies(batch.size(), Map.of("term", 1f));
              });
      sparseLane(h, selection, true, allowed::get);
      h.refresh();
      assertEquals(64, texts.size(), "one chunk slice gets an opportunity before lower-ID parents");
      assertTrue(
          texts.stream().allMatch("chunk text"::equals), "chunk selection must exclude parents");
      assertEquals("PENDING", h.value("a00", SchemaFields.SPLADE_STATUS));
      assertEquals("PENDING", h.value("z64", SchemaFields.SPLADE_STATUS));
      texts.clear();
      allowed.set(true);
      sparseLane(h, selection, false, allowed::get);
      h.refresh();
      assertEquals(64, texts.size());
      assertTrue(texts.stream().allMatch(text -> text.startsWith("parent ")));
      h.seed("a-continually-arriving", "new parent", Map.of(SchemaFields.SPLADE_STATUS, "PENDING"));
      h.refresh();
      texts.clear();
      allowed.set(true);
      sparseLane(h, selection, true, allowed::get);
      h.refresh();
      assertEquals(
          Collections.nCopies(6, "chunk text"),
          texts,
          "parent sweep must not reset the chunk suffix");
      texts.clear();
      allowed.set(true);
      sparseLane(h, selection, false, allowed::get);
      h.refresh();
      assertEquals(6, texts.size(), "chunk sweep must not reset the parent suffix");
      assertEquals("PENDING", h.value("a-continually-arriving", SchemaFields.SPLADE_STATUS));
      allowed.set(true);
      sparseLane(h, selection, false, allowed::get);
      h.refresh();
      assertEquals("COMPLETED", h.value("a-continually-arriving", SchemaFields.SPLADE_STATUS));
      for (int arrival = 0; arrival < 3; arrival++) {
        String parent = "a-arrival-" + arrival;
        String chunk = "z-arrival-" + arrival;
        h.seed(parent, "new parent", Map.of(SchemaFields.SPLADE_STATUS, "PENDING"));
        h.chunk(chunk, "owner", "chunk text", Map.of(SchemaFields.SPLADE_STATUS, "PENDING"));
        h.refresh();
        allowed.set(true);
        sparseLane(h, selection, true, allowed::get);
        h.refresh();
        assertEquals("COMPLETED", h.value(chunk, SchemaFields.SPLADE_STATUS));
        assertEquals("PENDING", h.value(parent, SchemaFields.SPLADE_STATUS));
      }
      allowed.set(true);
      sparseLane(h, selection, false, allowed::get);
      h.refresh();
      assertEquals(
          0, h.runtime.indexCountOps().countByField(SchemaFields.SPLADE_STATUS, "PENDING"));
    }
  }

  // The before-state executes its actual mixed operation, so it fails by a selection assertion.
  private void sparseLane(
      Harness h, EnrichmentSelection selection, boolean chunks, BooleanSupplier allowed)
      throws Exception {
    var context =
        new SpladeBackfillOps.BackfillContext(h.runtime.documentFieldOps(),
        h.runtime.indexingCoordinator(),
        IndexingPacing.unthrottled(),
        () -> h.sparse,
        () -> true,
        70,
        true,
        LoggerFactory.getLogger(getClass()),
        allowed);
    try {
      var method =
          SpladeBackfillOps.class.getMethod(
              chunks ? "processChunkBackfill" : "processParentBackfill",
              SpladeBackfillOps.BackfillContext.class,
              EnrichmentSelection.class);
      method.invoke(null, context, selection);
    } catch (NoSuchMethodException before) {
      LaneBackfillTestSupport.runSparse(context, selection);
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({
    "dense,false,batch",
    "dense,true,batch",
    "ner,false,batch",
    "ner,true,batch",
    "bge,false,batch",
    "dense,false,window",
    "dense,true,window",
    "dense,false,single",
    "dense,true,single",
    "dense,false,fallback",
    "dense,true,fallback"
  })
  void acquisitionFailureHoldsProviderWithoutRetryChargeAndPeersContinue(
      String lane, boolean combined, String route) throws Exception {
    try (var h = new Harness()) {
      h.enable(lane);
      h.sparseEnabled = combined;
      h.unavailable.set(true);
      if (route.equals("window")) {
        when(h.dense.documentWindowCount(anyString())).thenReturn(2);
        when(h.dense.embedDocumentWindows(anyString(), anyInt(), anyInt()))
            .thenAnswer(
                call -> {
                  h.acquire("dense");
                  return new EmbeddingProvider.WindowSlice(List.of(vector(), vector()), 0, 2);
                });
      } else if (route.equals("single")) {
        when(h.config.ai().embedding().lateChunkingEnabled()).thenReturn(true);
        when(h.dense.embedWithSpans(anyString(), any(int[][].class)))
            .thenAnswer(
                call -> {
                  h.acquire("dense");
                  return new EmbeddingService.ChunkedEmbedding(vector(), List.of(), 0);
                });
      } else if (route.equals("fallback")) {
        doReturn(null).when(h.dense).embedDocumentBatch(anyList());
      }
      String status =
          lane.equals("ner")
              ? SchemaFields.NER_STATUS
              : lane.equals("bge") ? SchemaFields.SPLADE_STATUS : SchemaFields.EMBEDDING_STATUS;
      String retry =
          lane.equals("ner")
              ? SchemaFields.NER_RETRY_COUNT
              : lane.equals("bge")
                  ? SchemaFields.SPLADE_RETRY_COUNT
                  : SchemaFields.EMBEDDING_RETRY_COUNT;
      h.seed("a", "body a", Map.of(status, "PENDING", retry, "2"));
      h.seed("b", "body b", Map.of(status, "PENDING", retry, "2"));
      if (lane.equals("dense"))
        h.chunk(
            "chunk",
            "a",
            "body a",
            route.equals("batch")
                ? Map.of(
                    SchemaFields.CHUNK_EMBEDDING_STATUS,
                    "PENDING",
                    SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT,
                    "2")
                : Map.of());
      if (combined) h.seed("peer", "peer", Map.of(SchemaFields.SPLADE_STATUS, "PENDING"));
      h.refresh();
      h.scheduler.runIdleCycle();
      h.refresh();
      assertEquals(1, h.failedCalls.get(), "unavailable acquisition must not fan out per document");
      assertEquals("PENDING", h.value("a", status));
      assertEquals("2", h.value("a", retry), "acquisition failure never consumes a row retry");
      assertEquals("2", h.value("b", retry));
      if (lane.equals("dense") && route.equals("batch"))
        assertEquals("2", h.value("chunk", SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));
      if (combined) assertEquals("COMPLETED", h.value("peer", SchemaFields.SPLADE_STATUS));
      h.unavailable.set(false);
      // Introduce an independent peer after the failed turn, without replacing the held provider.
      if (!lane.equals("ner")) {
        h.nerEnabled = true;
        h.seed("next-peer", "peer", Map.of(SchemaFields.NER_STATUS, "PENDING"));
      } else {
        h.sparseEnabled = true;
        h.seed("next-peer", "peer", Map.of(SchemaFields.SPLADE_STATUS, "PENDING"));
      }
      h.refresh();
      int before = h.calls.get();
      h.scheduler.runIdleCycle();
      h.refresh();
      assertEquals(before, h.calls.get(), "idle service must retain the provider hold");
      assertEquals(
          lane.equals("ner") ? "COMPLETED" : "COMPLETED_EMPTY",
          h.value(
              "next-peer",
              lane.equals("ner") ? SchemaFields.SPLADE_STATUS : SchemaFields.NER_STATUS));
      h.clock.set(2 * LoopPacingPolicy.idleSleepMs() * 1_000_000L - 1);
      h.scheduler.runIdleCycle();
      assertEquals(before, h.calls.get());
      h.clock.incrementAndGet();
      for (int i = 0; i < 4; i++) h.scheduler.runIdleCycle();
      h.refresh();
      assertTrue(h.calls.get() > before, "controlled expiry must admit the original provider");
      assertEquals(lane.equals("ner") ? "COMPLETED_EMPTY" : "COMPLETED", h.value("a", status));
      // A fresh acquisition failure is bypassed by replacing only this provider.
      h.seed("replacement", "body replacement", Map.of(status, "PENDING", retry, "2"));
      h.unavailable.set(true);
      h.refresh();
      h.scheduler.runIdleCycle();
      before = h.calls.get();
      h.unavailable.set(false);
      h.replace(lane);
      for (int i = 0; i < 4; i++) h.scheduler.runIdleCycle();
      h.refresh();
      assertTrue(h.calls.get() > before);
      assertEquals(
          lane.equals("ner") ? "COMPLETED_EMPTY" : "COMPLETED", h.value("replacement", status));
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void bothDenseCallersPublishReferencePoolingAndPreserveSparsePayload(
      boolean combined, boolean late) throws Exception {
    try (var h = new Harness()) {
      h.enable("dense");
      h.sparseEnabled = combined;
      when(h.config.ai().embedding().lateChunkingEnabled()).thenReturn(late);
      float[] expected = pooledWindows(96);
      when(h.dense.documentWindowCount(anyString())).thenReturn(96);
      when(h.dense.embedWithSpans(anyString(), any(int[][].class))).thenReturn(null);
      when(h.dense.embedDocumentBatch(anyList()))
          .thenAnswer(
              call -> Collections.nCopies(((List<?>) call.getArgument(0)).size(), expected));
      when(h.dense.embedDocumentWindows(anyString(), anyInt(), anyInt()))
          .thenAnswer(
              call -> {
                int from = call.getArgument(1), cap = call.getArgument(2);
                var vectors = new ArrayList<float[]>();
                for (int i = from; i < Math.min(96, from + cap); i++) vectors.add(window(i));
                return new EmbeddingProvider.WindowSlice(vectors, from, 96);
              });
      h.seed(
          "parent",
          "long body",
          Map.of(
              SchemaFields.EMBEDDING_STATUS,
              "PENDING",
              SchemaFields.EMBEDDING_RETRY_COUNT,
              "2",
              SchemaFields.SPLADE_STATUS,
              "COMPLETED",
              SchemaFields.SPLADE,
              Map.of("old", 2f)));
      h.chunk(
          "chunk",
          "parent",
          "long body",
          Map.of(
              SchemaFields.CHUNK_EMBEDDING_STATUS,
              "PENDING",
              SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT,
              "2",
              SchemaFields.SPLADE_STATUS,
              "COMPLETED",
              SchemaFields.SPLADE,
              Map.of("old", 2f)));
      h.refresh();
      for (int i = 0; i < 5; i++) h.scheduler.runIdleCycle();
      h.refresh();
      h.runtime.commitOps().commitAndTrack();
      assertEquals("COMPLETED", h.value("parent", SchemaFields.EMBEDDING_STATUS));
      assertEquals("COMPLETED", h.value("chunk", SchemaFields.CHUNK_EMBEDDING_STATUS));
      assertEquals("0", h.value("parent", SchemaFields.EMBEDDING_RETRY_COUNT));
      assertEquals("0", h.value("chunk", SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));
      assertNull(h.value("chunk", SchemaFields.EMBEDDING_STATUS));
      assertStoredVector(h, "parent", SchemaFields.VECTOR, expected);
      assertStoredVector(h, "chunk", SchemaFields.CHUNK_VECTOR, expected);
      try (var fs = org.apache.lucene.store.FSDirectory.open(h.path);
          var reader = org.apache.lucene.index.DirectoryReader.open(fs)) {
        assertEquals(
            2,
            new org.apache.lucene.search.IndexSearcher(reader)
                .count(
                    new org.apache.lucene.search.TermQuery(
                        new org.apache.lucene.index.Term(SchemaFields.SPLADE, "old"))));
      }
      verify(h.sparse, never()).encodeBatch(anyList());
      // E4b: one dense lane runs the common kernel; single-pass classification follows only the
      // late-chunking setting, no longer whether a sparse provider is configured.
      if (!late) verify(h.dense, never()).embedWithSpans(anyString(), any(int[][].class));
    }
  }

  @Test
  void combinedDenseRetainsClassificationAndWindowOffsetsThroughIdleCycles() throws Exception {
    assertResumes(true);
  }

  @Test
  void individualDenseUsesTheSameRetainedRoutesAndWindowKernel() throws Exception {
    assertResumes(false);
  }

  private void assertResumes(boolean combined) throws Exception {
    try (var h = new Harness()) {
      h.enable("dense");
      h.sparseEnabled = combined;
      when(h.config.ai().embedding().lateChunkingEnabled()).thenReturn(true);
      var classification = new AtomicInteger();
      var offsets = new ArrayList<Integer>();
      when(h.dense.documentWindowCount(anyString())).thenReturn(96);
      when(h.dense.embedWithSpans(anyString(), any(int[][].class)))
          .thenAnswer(
              call -> {
                classification.incrementAndGet();
                h.clock.addAndGet(6_000_000_000L);
                return null;
              });
      when(h.dense.embedDocumentWindows(anyString(), anyInt(), anyInt()))
          .thenAnswer(
              call -> {
                int from = call.getArgument(1);
                offsets.add(from);
                h.clock.addAndGet(6_000_000_000L);
                var vectors = new ArrayList<float[]>();
                for (int i = from; i < Math.min(96, from + 32); i++) vectors.add(window(i));
                return new EmbeddingProvider.WindowSlice(vectors, from, 96);
              });
      h.seed(
          "parent",
          "long body",
          Map.of(
              SchemaFields.EMBEDDING_STATUS,
              "PENDING",
              SchemaFields.EMBEDDING_RETRY_COUNT,
              "2",
              SchemaFields.SPLADE_STATUS,
              "COMPLETED",
              SchemaFields.SPLADE,
              Map.of("old", 2f)));
      h.chunk("chunk", "parent", "long body", Map.of());
      h.refresh();
      h.scheduler.runIdleCycle();
      h.refresh();
      assertEquals(
          "PENDING",
          h.value("parent", SchemaFields.EMBEDDING_STATUS),
          "classification alone cannot publish a vector");
      assertEquals("2", h.value("parent", SchemaFields.EMBEDDING_RETRY_COUNT));
      var field = BackfillScheduler.class.getDeclaredField("windowedEmbedProgress");
      field.setAccessible(true);
      var progress = (WindowedEmbedProgress) field.get(h.scheduler);
      assertEquals(WindowedEmbedProgress.Route.WINDOW, progress.route("parent", "long body"));
      // E4b: with or without a sparse provider the one dense lane classifies once, then resumes
      // the retained windows one bounded unit per cycle.
      int remainingTurns = 3;
      for (int i = 0; i < remainingTurns; i++) {
        h.scheduler.runIdleCycle();
        h.refresh();
        if (i < remainingTurns - 1) {
          assertEquals("PENDING", h.value("parent", SchemaFields.EMBEDDING_STATUS));
          assertEquals((i + 1) * 32, progress.nextWindow("parent", "long body"));
        }
      }
      assertEquals(List.of(0, 32, 64), offsets);
      assertEquals(1, classification.get());
      assertEquals("COMPLETED", h.value("parent", SchemaFields.EMBEDDING_STATUS));
      assertTrue(progress.owners().isEmpty());
      h.runtime.commitOps().commitAndTrack();
      assertStoredVector(h, "parent", SchemaFields.VECTOR, pooledWindows(96));
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"windows", "fallback"})
  void boundedDenseUnitsRetainTheNextNativeOperation(String route) throws Exception {
    try (var h = new Harness()) {
      h.enable("dense");
      var progress = new WindowedEmbedProgress();
      var selection = new EnrichmentSelection();
      var nativeCalls = new AtomicInteger();
      h.seed(
          "a",
          "long body",
          Map.of(
              SchemaFields.EMBEDDING_STATUS, "PENDING", SchemaFields.EMBEDDING_RETRY_COUNT, "2"));
      if (route.equals("windows")) {
        when(h.config.ai().embedding().lateChunkingEnabled()).thenReturn(true);
        h.chunk("chunk", "a", "long body", Map.of());
        when(h.dense.documentWindowCount(anyString())).thenReturn(96);
        when(h.dense.embedWithSpans(anyString(), any(int[][].class)))
            .thenAnswer(
                call -> {
                  nativeCalls.incrementAndGet();
                  return null;
                });
        when(h.dense.embedDocumentWindows(anyString(), anyInt(), anyInt()))
            .thenAnswer(
                call -> {
                  nativeCalls.incrementAndGet();
                  int from = call.getArgument(1);
                  return new EmbeddingProvider.WindowSlice(
                      Collections.nCopies(32, vector()), from, 96);
                });
      } else {
        h.seed(
            "b",
            "body b",
            Map.of(
                SchemaFields.EMBEDDING_STATUS, "PENDING", SchemaFields.EMBEDDING_RETRY_COUNT, "2"));
        when(h.dense.embedDocumentBatch(anyList()))
            .thenAnswer(
                call -> {
                  nativeCalls.incrementAndGet();
                  return null;
                });
        when(h.dense.embedDocument(anyString(), any()))
            .thenAnswer(
                call -> {
                  nativeCalls.incrementAndGet();
                  return vector();
                });
      }
      h.refresh();
      int turns = route.equals("windows") ? 4 : 3;
      for (int turn = 0; turn < turns; turn++) {
        var context =
            new LaneBackfillTestSupport.BackfillContext(h.runtime.documentFieldOps(),
        h.runtime.indexingCoordinator(),
        IndexingPacing.unthrottled(),
        () -> h.dense,
        () -> null,
        () -> null,
        () -> true,
        () -> true,
        10,
        LoggerFactory.getLogger(getClass()),
        true,
        false,
        h.config.ai().embedding().lateChunkingEnabled(),
        10,
        () -> false,
        () -> false,
        progress);
        CombinedEnrichmentBackfillOps.processDenseUnit(LaneBackfillTestSupport.denseContext(context),
            selection, EnrichmentAccounting.Stage.EMBED);
        h.refresh();
        assertEquals(turn + 1, nativeCalls.get(), "a turn admits exactly one native operation");
        if (turn == 0) {
          assertEquals("PENDING", h.value("a", SchemaFields.EMBEDDING_STATUS));
          assertEquals("2", h.value("a", SchemaFields.EMBEDDING_RETRY_COUNT));
          assertEquals(
              route.equals("windows") ? "WINDOW" : "FALLBACK",
              progress.route("a", "long body").name());
        }
      }
      assertEquals("COMPLETED", h.value("a", SchemaFields.EMBEDDING_STATUS));
      if (route.equals("fallback"))
        assertEquals("COMPLETED", h.value("b", SchemaFields.EMBEDDING_STATUS));
      assertTrue(progress.owners().isEmpty());
      h.runtime.commitOps().commitAndTrack();
      assertStoredVector(h, "a", SchemaFields.VECTOR, vector());
    }
  }

  @Test
  void boundedNerUnitPublishesOneDocumentAndKeepsItsCursor() throws Exception {
    try (var h = new Harness()) {
      h.enable("ner");
      for (String id : List.of("a", "b", "c"))
        h.seed(
            id,
            "body " + id,
            Map.of(SchemaFields.NER_STATUS, "PENDING", SchemaFields.NER_RETRY_COUNT, "2"));
      h.refresh();
      var selection = new EnrichmentSelection();
      for (int turn = 0; turn < 3; turn++) {
        var context =
            new NerBackfillOps.BackfillContext(h.runtime.documentFieldOps(),
        h.runtime.indexingCoordinator(),
        IndexingPacing.unthrottled(),
        () -> h.ner,
        () -> true,
        10,
        LoggerFactory.getLogger(getClass()), () -> true);
        try {
          var method =
              NerBackfillOps.class.getMethod(
                  "processNerUnit",
                  NerBackfillOps.BackfillContext.class,
                  EnrichmentSelection.class);
          method.invoke(null, context, selection);
        } catch (NoSuchMethodException before) {
          LaneBackfillTestSupport.runNer(context, selection);
        }
        h.refresh();
        assertEquals(turn + 1, h.calls.get());
        assertEquals(
            2 - turn, h.runtime.indexCountOps().countByField(SchemaFields.NER_STATUS, "PENDING"));
      }
    }
  }

  private static float[] window(int index) {
    return new float[] {index + 1, index % 3, 0, 1};
  }

  private static float[] pooledWindows(int count) {
    double[] sum = new double[4];
    for (int i = 0; i < count; i++) {
      float[] vector = window(i);
      for (int j = 0; j < 4; j++) sum[j] += vector[j];
    }
    float[] mean = new float[4];
    double norm = 0;
    for (int j = 0; j < 4; j++) {
      mean[j] = (float) (sum[j] / count);
      norm += (double) mean[j] * mean[j];
    }
    float scale = (float) (1 / Math.sqrt(norm));
    for (int j = 0; j < 4; j++) mean[j] *= scale;
    return mean;
  }

  private static void assertStoredVector(Harness h, String id, String field, float[] expected)
      throws Exception {
    try (var fs = org.apache.lucene.store.FSDirectory.open(h.path);
        var reader = org.apache.lucene.index.DirectoryReader.open(fs)) {
      var hits =
          new org.apache.lucene.search.IndexSearcher(reader)
              .search(
                  new org.apache.lucene.search.TermQuery(
                      new org.apache.lucene.index.Term(SchemaFields.DOC_ID, id)),
                  1);
      assertEquals(1, hits.scoreDocs.length);
      int doc = hits.scoreDocs[0].doc;
      var leaf =
          reader.leaves().get(org.apache.lucene.index.ReaderUtil.subIndex(doc, reader.leaves()));
      var vectors = leaf.reader().getFloatVectorValues(field);
      assertNotNull(vectors);
      var it = vectors.iterator();
      assertEquals(doc - leaf.docBase, it.advance(doc - leaf.docBase));
      assertArrayEquals(expected, vectors.vectorValue(it.index()), 0.000001f);
    }
  }

  @Test
  void individualNativeUnitKeepsItsExistingIngestionCancellationPolicy() throws Exception {
    try (var h = new Harness()) {
      h.enable("dense");
      h.seed("parent", "body", Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING"));
      h.refresh();
      when(h.dense.embedDocumentBatch(anyList())).thenAnswer(call -> {
        h.pendingIngest.set(true);
        io.justsearch.indexerworker.inference.LocalSessionAcquisition.checkCancellation();
        return List.of(vector());
      });
      assertDoesNotThrow(h.scheduler::runIdleCycle);
      h.refresh();
      assertEquals("COMPLETED", h.value("parent", SchemaFields.EMBEDDING_STATUS));
      h.runtime.commitOps().commitAndTrack();
      assertStoredVector(h, "parent", SchemaFields.VECTOR, vector());
    }
  }

  private final class Harness implements AutoCloseable {
    final RunningRuntime runtime;
    final Path path = directory.resolve(UUID.randomUUID().toString());
    final ResolvedConfig config = mock(ResolvedConfig.class, RETURNS_DEEP_STUBS);
    final AtomicLong clock = new AtomicLong();
    final AtomicBoolean unavailable = new AtomicBoolean();
    final AtomicBoolean pendingIngest = new AtomicBoolean();
    final AtomicInteger calls = new AtomicInteger(), failedCalls = new AtomicInteger();
    boolean denseEnabled, nerEnabled, sparseEnabled, bgeEnabled;
    String measured;
    EmbeddingProvider dense;
    NerService ner;
    SpladeEncoder sparse;
    BgeM3Encoder bge;
    final BackfillScheduler scheduler;

    Harness() throws Exception {
      var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(4).fields());
      for (String name :
          List.of(
              SchemaFields.SOURCE_SHA256,
              SchemaFields.EMBEDDING_RETRY_COUNT,
              SchemaFields.SPLADE_STATUS,
              SchemaFields.SPLADE_RETRY_COUNT,
              SchemaFields.NER_STATUS,
              SchemaFields.NER_RETRY_COUNT)) {
        if (fields.stream().noneMatch(field -> field.id().equals(name)))
          fields.add(
              new FieldDef(
                  name,
                  name.endsWith("_count") ? "long" : "keyword",
                  true,
                  true,
                  List.of("filter"),
                  null,
                  null,
                  false));
      }
      fields.add(
          new FieldDef(
              SchemaFields.SPLADE,
              "splade",
              false,
              false,
              List.of(),
              null,
              null,
              false,
              "reset-status:splade_status"));
      runtime =
          IndexSchema.fromCatalog(new FieldCatalogDef("e4a-lanes", fields))
              .atPath(path)
              .withExecutorRegistrations(testLuceneExecutors())
              .open();
      runtime.commitOps().stopCommitTimer();
      runtime.commitOps().suspendNrtRefresh();
      replace("dense");
      replace("ner");
      replace("sparse");
      replace("bge");
      var signals = mock(WorkerSignalBus.class);
      when(signals.hasPendingIngest()).thenAnswer(call -> pendingIngest.get());
      var lifecycle = mock(EmbeddingProviderLifecycle.class);
      when(lifecycle.embeddingProvider()).thenAnswer(call -> dense);
      when(lifecycle.allowEmbeddingWrites()).thenReturn(true);
      when(config.ai().backfillPacing()).thenReturn(ResolvedConfig.Ai.BackfillPacing.DEFAULTS);
      when(config.rag().chunkVectorsEnabled()).thenReturn(true);
      when(config.rag().chunkSpladeEnabled()).thenReturn(true);
      scheduler =
          new BackfillScheduler(
              runtime.documentFieldOps(),
              runtime.indexingCoordinator(),
              runtime.indexCountOps(),
              runtime.commitOps(),
              signals,
              IndexingPacing.unthrottled(),
              lifecycle,
              new AtomicBoolean(true),
              () -> config,
              () -> sparseEnabled ? sparse : null,
              () -> bgeEnabled ? bge : null,
              () -> nerEnabled ? ner : null,
              () -> null,
              clock::get);
    }

    void enable(String lane) {
      measured = lane;
      denseEnabled = lane.equals("dense");
      nerEnabled = lane.equals("ner");
      bgeEnabled = lane.equals("bge");
    }

    void acquire(String lane) {
      if (!lane.equals(measured)) return;
      calls.incrementAndGet();
      if (unavailable.get()) {
        failedCalls.incrementAndGet();
        throw new SessionTemporarilyUnavailableException(
            "provider still current, lease unavailable");
      }
    }

    void replace(String lane) throws Exception {
      if (lane.equals("dense")) {
        dense = mock(EmbeddingService.class);
        when(dense.isAvailable()).thenAnswer(call -> denseEnabled);
        when(dense.dimension()).thenReturn(4);
        when(dense.documentWindowCount(anyString())).thenReturn(1);
        when(dense.embedDocumentBatch(anyList()))
            .thenAnswer(
                call -> {
                  acquire("dense");
                  return Collections.nCopies(((List<?>) call.getArgument(0)).size(), vector());
                });
        when(dense.embedDocument(anyString(), any()))
            .thenAnswer(
                call -> {
                  acquire("dense");
                  return vector();
                });
      } else if (lane.equals("ner")) {
        ner = mock(NerService.class);
        when(ner.isAvailable()).thenReturn(true);
        when(ner.extractEntitiesBatch(anyList()))
            .thenAnswer(
                call -> {
                  acquire("ner");
                  return Collections.nCopies(
                      ((List<?>) call.getArgument(0)).size(), NerResult.EMPTY);
                });
      } else if (lane.equals("sparse")) {
        sparse = mock(SpladeEncoder.class);
        when(sparse.encodeBatch(anyList()))
            .thenAnswer(
                call -> {
                  acquire("sparse");
                  return Collections.nCopies(
                      ((List<?>) call.getArgument(0)).size(), Map.of("term", 1f));
                });
      } else {
        bge = mock(BgeM3Encoder.class);
        when(bge.encodeBatch(anyList()))
            .thenAnswer(
                call -> {
                  acquire("bge");
                  return Collections.nCopies(
                      ((List<?>) call.getArgument(0)).size(),
                      new BgeM3Output(vector(), Map.of("term", 1f)));
                });
        when(bge.encode(anyString(), any()))
            .thenAnswer(
                call -> {
                  acquire("bge");
                  return new BgeM3Output(vector(), Map.of("term", 1f));
                });
      }
    }

    void chunk(String id, String parent, String parentContent, Map<String, Object> overrides) {
      var fields = new HashMap<String, Object>(overrides);
      fields.put(SchemaFields.IS_CHUNK, "true");
      fields.put(SchemaFields.PARENT_DOC_ID, parent);
      fields.put(SchemaFields.CHUNK_START_CHAR, "0");
      fields.put(SchemaFields.CHUNK_END_CHAR, "" + parentContent.length());
      fields.put(
          SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(parentContent));
      seed(id, "", fields);
    }

    void seed(String id, String content, Map<String, Object> overrides) {
      var fields = new HashMap<String, Object>();
      fields.put(SchemaFields.DOC_ID, id);
      fields.put(SchemaFields.DOC_UID, "uid-" + id);
      fields.put(SchemaFields.CONTENT, content);
      fields.put(SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex(content));
      fields.put(SchemaFields.SOURCE_SHA256, "source-" + id);
      fields.putAll(overrides);
      runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    }

    void refresh() throws Exception {
      runtime.commitOps().maybeRefreshBlocking();
    }

    String value(String id, String field) {
      return runtime.documentFieldOps().getDocumentField(id, field);
    }

    @Override
    public void close() {
      runtime.close();
    }
  }

  private static float[] vector() {
    return new float[] {1, 0, 0, 0};
  }
}
