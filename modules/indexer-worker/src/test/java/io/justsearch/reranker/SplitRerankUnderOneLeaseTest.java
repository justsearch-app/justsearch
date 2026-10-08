/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import static io.justsearch.indexerworker.splade.DeviceMemoryFixtures.handle;
import static io.justsearch.indexerworker.splade.DeviceMemoryFixtures.injectGate;
import static io.justsearch.indexerworker.splade.DeviceMemoryFixtures.repoFile;
import static io.justsearch.indexerworker.splade.DeviceMemoryFixtures.result;
import static io.justsearch.indexerworker.splade.DeviceMemoryFixtures.waiting;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.ort.DeviceGatePolicy;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.GpuArenaPlan;
import io.justsearch.ort.NativeSessionHandle;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.ort.SessionHandle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Spec E revision 4, check E-MEM6, invariant 7: a rerank the plan splits into several calls holds
 * one lease, and so one device seat, for all of them. On a P8 plan a rerank of 20 pairs at 512
 * tokens is split 16 + 4; a bulk waiter aged at the gate is not seated between the calls. A
 * deadline miss on the second call returns every document in its original order, unscored.
 *
 * <p>Real reranker, tokenizer and {@code NativeSessionHandle}s on their device's real gate (with
 * short aging); the native sessions are mocks answering with real tensors. The plan is resolved
 * from P8 reference facts and read by the reranker through its session handle, as E7b will publish
 * it.
 */
final class SplitRerankUnderOneLeaseTest {

  private final List<String> events = new CopyOnWriteArrayList<>();
  private final List<OrtSession.Result> results = new CopyOnWriteArrayList<>();
  private final List<AutoCloseable> closeAfter = new CopyOnWriteArrayList<>();
  private final CountDownLatch release = new CountDownLatch(1);

  @AfterEach
  void closeAll() throws Exception {
    release.countDown();
    for (AutoCloseable closeable : closeAfter.reversed()) closeable.close();
    for (OrtSession.Result result : results) ((OnnxTensor) result.get(0)).close();
  }

  @Test
  void aSplitRerankHoldsOneSeatSoAnAgedBulkWaiterIsNotSeatedBetweenItsCalls() throws Exception {
    int device = 23_174;
    injectGate(device, new DeviceGatePolicy(1, 1, 50L, 10_000L, 10_000L, 5_000L));
    CountDownLatch firstRunning = new CountDownLatch(1);
    CountDownLatch firstMayReturn = new CountDownLatch(1);
    CrossEncoderReranker reranker = reranker(device, firstRunning, firstMayReturn);
    NativeSessionHandle bulk = handle("embed", device, bulkGpu(), mock(OrtSession.class));
    closeAfter.add(bulk);

    AtomicReference<CrossEncoderReranker.RerankedResult> ranked = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread search = new Thread(
        () -> ranked.set(reranker.rerank("device memory", documents(20), 10_000)), "e-mem6-rerank");
    Thread indexing = new Thread(() -> {
      try (SessionHandle.Lease lease = bulk.acquire(SessionAcquisitionRequest.within(
          SessionAcquisitionRequest.Urgency.BACKGROUND, Duration.ofSeconds(30)))) {
        lease.run(Map.of()).close();
      } catch (Throwable thrown) {
        failure.set(thrown);
      }
    }, "e-mem6-bulk");
    search.start();
    try {
      assertTrue(firstRunning.await(30, TimeUnit.SECONDS), "the rerank's first call ran");
      indexing.start();
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (waiting(device) == 0 && System.nanoTime() < until) Thread.sleep(5);
      assertEquals(1, waiting(device), "the bulk run waits at the gate");
      Thread.sleep(200); // past agingWallMs: the bulk waiter is aged
    } finally {
      firstMayReturn.countDown();
      search.join(30_000);
      indexing.join(30_000);
    }
    assertFalse(search.isAlive());
    assertFalse(indexing.isAlive());
    assertNull(failure.get());

    assertEquals(List.of("rerank-16", "rerank-4", "bulk-run"), events,
        "a bulk run was seated between the calls of one rerank");
    CrossEncoderReranker.RerankedResult result = ranked.get();
    assertEquals(RerankSkipCause.NONE, result.skipCause());
    assertEquals(20, result.scores().size());
    assertEquals(IntStream.range(0, 20).boxed().toList(), result.sortedIndices());
  }

  @Test
  void aDeadlineMissOnTheSecondCallReturnsEveryDocumentInItsOriginalOrder() throws Exception {
    int device = 175;
    CountDownLatch ran = new CountDownLatch(1);
    CrossEncoderReranker reranker = reranker(device, ran, null);
    InferenceRequest search =
        InferenceRequest.narrowed(
            InferenceRequest.foreground(),
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500),
            InferenceRequest.SemanticBudget.FALLBACK);
    List<Integer> original = new ArrayList<>(IntStream.range(0, 20).boxed().toList());

    CrossEncoderReranker.RerankedResult result =
        reranker.rerank("device memory", documents(20), 10_000, search);

    assertEquals(List.of("rerank-16", "rerank-4"), events, "the second call started");
    assertEquals(RerankSkipCause.RUN_DEADLINE_EXCEEDED, result.skipCause());
    assertEquals(original, result.sortedIndices(), "every document keeps its original order");
    assertTrue(result.scores().isEmpty(), "scored and unscored documents are not mixed");
  }

  /**
   * A reranker on {@code device} whose handle publishes a P8 plan. Its first call signals {@code
   * firstRunning} and waits for {@code firstMayReturn}; without one, its second call does not
   * return until the test ends. Scores fall with each pair, so the scored order is 0..19.
   */
  private CrossEncoderReranker reranker(
      int device, CountDownLatch firstRunning, CountDownLatch firstMayReturn) throws Exception {
    AtomicInteger scored = new AtomicInteger();
    OrtSession gpu = mock(OrtSession.class);
    when(gpu.run(anyMap())).thenAnswer(invocation -> {
      Map<String, OnnxTensor> inputs = invocation.getArgument(0);
      int pairs = (int) inputs.get("input_ids").getInfo().getShape()[0];
      int call = events.size();
      events.add("rerank-" + pairs);
      if (call == 0) {
        firstRunning.countDown();
        if (firstMayReturn != null) assertTrue(firstMayReturn.await(30, TimeUnit.SECONDS));
      } else if (firstMayReturn == null) {
        assertTrue(release.await(60, TimeUnit.SECONDS));
      }
      float[][] scores = new float[pairs][1];
      for (int i = 0; i < pairs; i++) scores[i][0] = 100f - scored.getAndIncrement();
      OrtSession.Result result = result(scores);
      results.add(result);
      return result;
    });
    when(gpu.run(anyMap(), any(OrtSession.RunOptions.class)))
        .thenThrow(new AssertionError("the reranker's handle has no run options here"));
    NativeSessionHandle real = handle("reranker", device, gpu, mock(OrtSession.class));
    GpuArenaPlan p8 =
        GpuArenaPlan.resolve(
            new GpuArenaPlan.Facts(
                OptionalDouble.of(8_188),
                Map.of(EncoderRole.EMBEDDING, 598.3, EncoderRole.SPLADE, 474.2,
                    EncoderRole.NER, 257.2, EncoderRole.RERANKER, 600.3),
                Set.of(ChatModelProfile.STANDARD, ChatModelProfile.COMPACT),
                Map.of(),
                GpuArenaPlan.ExternalChat.NONE,
                1),
            GpuArenaPlan.Settings.none());
    assertEquals(GpuArenaPlan.Profile.P8, p8.profile());
    assertTrue(p8.planned());
    SessionHandle planned =
        (SessionHandle)
            Proxy.newProxyInstance(
                SessionHandle.class.getClassLoader(),
                new Class<?>[] {SessionHandle.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("arenaPlan") && method.getParameterCount() == 0) {
                    return Optional.of(p8);
                  }
                  try {
                    return method.invoke(real, args);
                  } catch (InvocationTargetException thrown) {
                    throw thrown.getCause();
                  }
                });
    CrossEncoderReranker reranker =
        new CrossEncoderReranker(
            planned,
            new RerankerShape(512, true),
            new RerankerTokenizer(repoFile("models/onnx/reranker/tokenizer.json"), 512));
    closeAfter.add(reranker);
    return reranker;
  }

  private OrtSession bulkGpu() throws OrtException {
    OrtSession gpu = mock(OrtSession.class);
    when(gpu.run(anyMap())).thenAnswer(invocation -> {
      events.add("bulk-run");
      return mock(OrtSession.Result.class);
    });
    return gpu;
  }

  /** {@code count} documents long enough that every pair is truncated to 512 tokens. */
  private static List<String> documents(int count) {
    String body = "the device seat holds one run at a time ".repeat(80);
    return IntStream.range(0, count).mapToObj(i -> "document " + i + " " + body).toList();
  }
}
