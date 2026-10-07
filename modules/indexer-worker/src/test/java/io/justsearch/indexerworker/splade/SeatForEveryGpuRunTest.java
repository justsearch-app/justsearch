/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.splade;

import static io.justsearch.indexerworker.splade.DeviceMemoryFixtures.handle;
import static io.justsearch.indexerworker.splade.DeviceMemoryFixtures.repoFile;
import static io.justsearch.indexerworker.splade.DeviceMemoryFixtures.result;
import static io.justsearch.indexerworker.splade.DeviceMemoryFixtures.tokenizer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.djl.modality.nlp.DefaultVocabulary;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.indexerworker.embed.onnx.EmbeddingShape;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder;
import io.justsearch.indexerworker.ner.BertNerInference;
import io.justsearch.indexerworker.ner.NerShape;
import io.justsearch.ort.NativeSessionHandle;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.reranker.CrossEncoderReranker;
import io.justsearch.reranker.RerankerShape;
import io.justsearch.reranker.RerankerTokenizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

/**
 * Spec E revision 4, check E-MEM1 (a), invariant 1: every GPU run holds its device's seat. With
 * one seat, at most one native GPU run is in flight on the device across a search's rerank, the
 * query embedding, the SPLADE query, an inline document embedding, embedWithSpans, a NER
 * single-text run, bulk SPLADE and the query prewarm, run concurrently.
 *
 * <p>Real encoders and tokenizers over real {@code NativeSessionHandle}s built through the
 * production assembler on one device's real gate; the GPU sessions are mocks that count the
 * native runs in flight and answer with real tensors shaped from their input.
 */
final class SeatForEveryGpuRunTest {

  private static final int DEVICE = 173;
  private static final int VOCAB = 8;
  private static final int ROUNDS = 12;

  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicInteger maxInFlight = new AtomicInteger();
  private final Map<String, AtomicInteger> runs = new ConcurrentHashMap<>();
  private final List<OrtSession.Result> results = Collections.synchronizedList(new ArrayList<>());

  @Test
  void atMostOneNativeRunIsInFlightOnTheDevice() throws Exception {
    NativeSessionHandle embedSessions =
        handle("embed", DEVICE, gpu("embed", s -> new float[(int) s[0]][(int) s[1]][8]), unused());
    NativeSessionHandle spladeSessions =
        handle("splade", DEVICE, gpu("splade", s -> new float[(int) s[0]][(int) s[1]][VOCAB]),
            unused());
    NativeSessionHandle nerSessions =
        handle("ner", DEVICE, gpu("ner", s -> new float[(int) s[0]][(int) s[1]][9]), unused());
    NativeSessionHandle rerankSessions =
        handle("reranker", DEVICE, gpu("reranker", s -> new float[(int) s[0]][1]), unused());

    OnnxEmbeddingEncoder embedder =
        new OnnxEmbeddingEncoder(
            embedSessions,
            new EmbeddingShape(512, false, OnnxEmbeddingEncoder.PoolingStrategy.MEAN_POOL, 0, 0),
            tokenizer("models/onnx/gte-multilingual-base/tokenizer.json"));
    List<String> vocabulary = new ArrayList<>(List.of("[PAD]", "[UNK]"));
    for (int i = 2; i < VOCAB; i++) vocabulary.add("token" + i);
    SpladeEncoder splade =
        new SpladeEncoder(
            spladeSessions,
            new SpladeShape(512, true, SpladeEncoder.OutputFormat.MLM_LOGITS, "logits"),
            tokenizer("models/splade/naver-splade-v3/tokenizer.json"),
            DefaultVocabulary.builder().add(vocabulary).optUnknownToken("[UNK]").build(),
            null,
            new SpladeConfig(true, repoFile("models/splade/naver-splade-v3"), 512, true, DEVICE, 0,
                "onnx", "log1p"));
    BertNerInference ner =
        new BertNerInference(
            nerSessions, new NerShape(512, false), tokenizer("models/onnx/ner/tokenizer.json"));
    CrossEncoderReranker reranker =
        new CrossEncoderReranker(
            rerankSessions,
            new RerankerShape(512, true),
            new RerankerTokenizer(repoFile("models/onnx/reranker/tokenizer.json"), 512));

    Map<String, Callable<Object>> workloads = Map.of(
        "rerank", () -> {
          var ranked = reranker.rerank("device memory", List.of("a gpu arena", "a cpu session",
              "the device seat", "bulk indexing"), 5_000, queryLeg());
          assertFalse(ranked.skipped(), "the rerank ran: " + ranked.skipCause());
          return ranked;
        },
        "embedQuery", () -> embedder.embed("device memory",
            SessionAcquisitionRequest.from(queryLeg())),
        "spladeQuery", () -> splade.encode("device memory", queryLeg()),
        "embedDocument", () -> embedder.embed("an inline document about device memory"),
        "embedWithSpans", () -> embedder.embedWithSpans("one chunk. two chunk.",
            new int[][] {{0, 10}, {11, 21}}),
        "nerSingleText", () -> ner.infer("Alice met Bob in Paris"),
        "bulkSplade", () -> splade.encodeBatch(List.of("first document", "second document text",
            "a third document of bulk work")),
        "prewarm", () -> {
          InferenceRequest prewarm = InferenceRequest.within(
              InferenceRequest.Urgency.FOREGROUND, Duration.ofSeconds(10), () -> false);
          assertNotNull(embedder.embed("query prewarm", SessionAcquisitionRequest.from(prewarm)));
          return splade.encode("query prewarm", prewarm);
        });

    ExecutorService pool = Executors.newFixedThreadPool(workloads.size());
    try {
      List<Future<Object>> done = new ArrayList<>();
      for (int round = 0; round < ROUNDS; round++) {
        for (Callable<Object> workload : workloads.values()) done.add(pool.submit(workload));
      }
      for (Future<Object> future : done) assertNotNull(future.get(60, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
      splade.close();
      ner.close();
      reranker.close();
      embedSessions.close();
      for (OrtSession.Result result : results) ((OnnxTensor) result.get(0)).close();
    }

    assertEquals(1, maxInFlight.get(), "native GPU runs overlapped on the device");
    for (String role : List.of("embed", "splade", "ner", "reranker")) {
      assertTrue(runs.get(role).get() > 0, role + " ran on the GPU");
    }
  }

  /** A search's semantic leg with a keyword fallback, released at its deadline. */
  private static InferenceRequest queryLeg() {
    return InferenceRequest.narrowed(
        InferenceRequest.foreground(),
        System.nanoTime() + TimeUnit.SECONDS.toNanos(10),
        InferenceRequest.SemanticBudget.FALLBACK);
  }

  /** A GPU session that counts its native runs in flight and answers with {@code output}. */
  private OrtSession gpu(String role, Function<long[], Object> output) throws OrtException {
    runs.put(role, new AtomicInteger());
    OrtSession session = mock(OrtSession.class);
    Answer<OrtSession.Result> run = invocation -> {
      Map<String, OnnxTensor> inputs = invocation.getArgument(0);
      long[] shape = inputs.get("input_ids").getInfo().getShape();
      enter(role);
      try {
        TimeUnit.MILLISECONDS.sleep(2);
        OrtSession.Result result = result(output.apply(shape));
        results.add(result);
        return result;
      } finally {
        inFlight.decrementAndGet();
      }
    };
    when(session.run(anyMap())).thenAnswer(run);
    when(session.run(anyMap(), any(OrtSession.RunOptions.class))).thenAnswer(run);
    when(session.run(anyMap(), anySet(), anyMap(), any())).thenAnswer(invocation -> {
      enter(role);
      try {
        TimeUnit.MILLISECONDS.sleep(2);
        return mock(OrtSession.Result.class);
      } finally {
        inFlight.decrementAndGet();
      }
    });
    return session;
  }

  private void enter(String role) {
    int now = inFlight.incrementAndGet();
    maxInFlight.accumulateAndGet(now, Math::max);
    runs.get(role).incrementAndGet();
  }

  /** A CPU session no run of this check may use. */
  private static OrtSession unused() throws OrtException {
    OrtSession session = mock(OrtSession.class);
    when(session.run(anyMap())).thenThrow(new AssertionError("a run went to the CPU session"));
    return session;
  }
}
