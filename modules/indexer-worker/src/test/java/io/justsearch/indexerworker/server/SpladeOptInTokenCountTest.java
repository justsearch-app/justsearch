/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.JobBatchExtractor;
import io.justsearch.indexerworker.loop.JobBatchWriter;
import io.justsearch.indexerworker.loop.SourcePresence;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.splade.SpladeConfig;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexerworker.splade.SpladeShape;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(60)
final class SpladeOptInTokenCountTest {
  @TempDir Path temp;

  @Test void immediateOrdinaryIngestionUsesSelectedFullTokenizerWhileInferenceIsOff() throws Exception {
    Path model = Files.createDirectories(temp.resolve("model"));
    Files.writeString(model.resolve("tokenizer.json"), """
        {"version":"1.0","truncation":{"direction":"Right","max_length":512,"strategy":"LongestFirst","stride":0},
        "padding":null,"added_tokens":[],"normalizer":{"type":"BertNormalizer","clean_text":true,
        "handle_chinese_chars":true,"strip_accents":null,"lowercase":true},
        "pre_tokenizer":{"type":"BertPreTokenizer"},"post_processor":null,
        "decoder":{"type":"WordPiece","prefix":"##","cleanup":true},
        "model":{"type":"WordPiece","unk_token":"[UNK]","continuing_subword_prefix":"##",
        "max_input_chars_per_word":100,"vocab":{"[UNK]":0,"word":1,"字":2}}}
        """);
    ordinaryIngestion(model, true);
  }

  @Test void corruptTokenizerKeepsTheNonfatalEstimate() throws Exception {
    Path model = Files.createDirectories(temp.resolve("corrupt"));
    Files.writeString(model.resolve("tokenizer.json"), "{corrupt");
    ordinaryIngestion(model, false);
  }

  @Test void missingTokenizerKeepsTheNonfatalEstimate() throws Exception {
    ordinaryIngestion(Files.createDirectories(temp.resolve("missing")), false);
  }

  @Test void unavailableTokenizerNativeLibraryKeepsTheNonfatalEstimate() throws Exception {
    Path model = Files.createDirectories(temp.resolve("unavailable-native"));
    try (var nativeRuntime = mockStatic(io.justsearch.indexerworker.tokenizer.HuggingFaceNativeRuntime.class)) {
      nativeRuntime.when(io.justsearch.indexerworker.tokenizer.HuggingFaceNativeRuntime::prepareCpuCache)
          .thenThrow(new UnsatisfiedLinkError("fixture native tokenizer unavailable"));
      assertDoesNotThrow(() -> ordinaryIngestion(model, false));
    }
  }

  private void ordinaryIngestion(Path model, boolean usable) throws Exception {
    var entries = new HashMap<String, String>();
    entries.put(EnvRegistry.DATA_DIR.configKey(), temp.resolve("data").toString());
    entries.put(EnvRegistry.INDEX_BASE_PATH.configKey(), temp.resolve("index").toString());
    entries.put(EnvRegistry.EXTRACTION_SANDBOX_MODE.configKey(), "in_process");
    entries.put(EnvRegistry.SPLADE_MODEL_PATH.configKey(), model.toString());
    entries.put(EnvRegistry.RERANK_CHUNKS_ENABLED.configKey(), "false");
    for (var key : new EnvRegistry[] {EnvRegistry.AI_EMBED_ENABLED, EnvRegistry.SPLADE_ENABLED,
        EnvRegistry.NER_ENABLED, EnvRegistry.BGE_M3_ENABLED, EnvRegistry.RERANK_ENABLED,
        EnvRegistry.CITATION_SCORER_ENABLED}) entries.put(key.configKey(), "false");
    var config = TestResolvedConfigHelper.fromEntries(entries);
    ConfigStore previous = ConfigStore.globalOrNull();
    var metrics = io.justsearch.indexerworker.metrics.OperationalMetrics.getInstance();
    @SuppressWarnings("unchecked")
    var registrations = (Map<String, io.justsearch.indexerworker.metrics.EncoderProfileAccumulator>)
        field(metrics, "encoderAccumulators");
    var previousProfile = registrations.get("splade");
    ConfigStore.setGlobal(new ConfigStore(config));
    Object ownedCounter = null;
    try (var executors = new TestEngineExecutors()) {
      var server = spy(new KnowledgeServer(executors, WorkerConfig.load(config), null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), null, null, config));
      doNothing().when(server).startDeferredModelInitialization(any());
      try {
        server.start();
        var services = server.appServices();
        var loop = (IndexingLoop) field(services, "indexingLoop");
        assertTrue(loop.quiesceForUpgrade(10_000));
        // Deterministic ordinary extraction/publication before any deferred model initialization.
        ((AtomicBoolean) field(loop, "running")).set(true);
        var roots = mock(SourcePresence.RootCheck.class);
        when(roots.admits(any())).thenReturn(true);
        when(roots.admitsFile(any())).thenReturn(true);
        loop.useRootCheck(roots);
        var queue = (JobQueue) field(server, "jobQueue");
        var runtime = (RunningRuntime) field(server, "ingestLifecycle");
        var extractor = (JobBatchExtractor) field(loop, "extractor");
        var writer = (JobBatchWriter) field(loop, "writer");
        for (String text : List.of("word ".repeat(511), "word ".repeat(513), "字".repeat(2400))) {
          Path file = Files.writeString(temp.resolve("doc-" + text.length() + ".txt"), text);
          assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(temp))));
          var jobs = extractor.extractAll(queue.pollPending(1));
          assertEquals(1, jobs.size());
          writer.write(jobs.getFirst(), null);
          runtime.commitOps().commitAndTrack();
          runtime.commitOps().maybeRefreshBlocking();
          String id = PathNormalizer.normalizeKey(file);
          String indexedText = jobs.getFirst().artifact().result().content();
          long expected = IndexingDocumentOps.estimateTokenCount(indexedText);
          if (usable) {
            var tokenizer = HuggingFaceTokenizer.newInstance(model.resolve("tokenizer.json"),
                Map.of("truncation", "false", "padding", "false"));
            var sessions = mock(io.justsearch.ort.SessionHandle.class);
            when(sessions.retirementStatus()).thenReturn(io.justsearch.ort.SessionHandle.RetirementStatus.RETIRED);
            var vocabulary = mock(ai.djl.modality.nlp.Vocabulary.class);
            try (var ready = new SpladeEncoder(sessions, new SpladeShape(512, false,
                    null, "logits"), tokenizer, vocabulary, null,
                    new SpladeConfig(true, model, 512, false, 0, 0, "onnx", "log1p"))) {
              expected = ready.tokenCount(indexedText);
            }
            assertTrue(expected > 500, "fixture straddles classification and inference thresholds");
          }
          assertEquals(Long.toString(expected), runtime.documentFieldOps().getDocumentField(id,
              SchemaFields.PARENT_TOKEN_COUNT), "S15: immediate off ingestion must match the ready tokenizer, without truncation");
          assertEquals("PENDING", runtime.documentFieldOps().getDocumentField(id, SchemaFields.SPLADE_STATUS));
          int chunks = 0;
          try (var directory = org.apache.lucene.store.FSDirectory.open((Path) field(server, "activeIndexPath"));
              var reader = org.apache.lucene.index.DirectoryReader.open(directory)) {
            for (var leaf : reader.leaves()) {
              var stored = leaf.reader().storedFields();
              var live = leaf.reader().getLiveDocs();
              for (int doc = 0; doc < leaf.reader().maxDoc(); doc++) {
                if (live != null && !live.get(doc)) continue;
                var chunk = stored.document(doc);
                if (id.equals(chunk.get(SchemaFields.PARENT_DOC_ID))) {
                  chunks++;
                  assertEquals(Long.toString(expected), chunk.get(SchemaFields.PARENT_TOKEN_COUNT),
                      "S15: chunk copies retain the full parent count");
                }
              }
            }
          }
          assertTrue(chunks > 0, "the threshold-adjacent and CJK fixtures exercise chunk copies");
        }
        if (usable) {
          // A later desired snapshot must not make the serving runtime's omitted role applicable.
          var later = TestResolvedConfigHelper.fromEntries(Map.of(
              "justsearch.splade.enabled", "true", "rag.chunk_splade.enabled", "true"));
          services.ingestService().setResolvedConfigSupplier(() -> later);
          assertFalse(services.ingestService().indexStatus(io.justsearch.ipc.StatusRequest.getDefaultInstance(),
              io.justsearch.indexerworker.services.CallContext.none())
              .getEnrichment().getChunk().getSpladeEnabled(), "S13: serving permission survives a later desired snapshot");
          ownedCounter = field(writer, "tokenCounter");
        }
      } finally { server.close(); }
      if (usable) {
        var counter = (java.util.function.ToLongFunction<String>) ownedCounter;
        assertNotNull(counter);
        assertThrows(IllegalStateException.class, () -> counter.applyAsLong("word"), "S15: producer shutdown retires its tokenizer");
      }
    } finally {
      if (previousProfile == null) registrations.remove("splade");
      else registrations.put("splade", previousProfile);
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  private static Object field(Object owner, String name) throws Exception {
    Class<?> type = owner.getClass();
    while (type != null) {
      try {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
      } catch (NoSuchFieldException absent) { type = type.getSuperclass(); }
    }
    throw new NoSuchFieldException(name);
  }
}
