/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.core.context.RetainedStateBudget;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerSearchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Engine admission while every semantic native run stalls past its search's deadline (verifier
 * finding d14c-1: 429 ADMISSION_ENGINE_LIMIT on MCP searches during a backlog drain with eight
 * hybrid clients). Concurrent hybrid searches run through the real Engine client, the real worker
 * search service and the real query encoder, over a native session whose GPU run never returns
 * until the test ends.
 */
@Timeout(60)
final class EngineSearchAdmissionUnderStallTest {

  private static final long DEADLINE_MS = 4_000;
  private static final int CALLERS = 8;

  /**
   * An admission slot is held only while its caller waits: the slots in use never exceed the
   * searches whose callers have not returned, one slot per search, and all are free once every
   * caller returned, while the stalled native runs are still in flight.
   */
  @Test
  void slotsInUseNeverExceedLiveCallersAndAllReturnWhileRunsStillStall() throws Exception {
    var admission = new EngineAdmissionController(64, 64, 1);
    try (var stack = new Stack(admission)) {
      AtomicInteger started = new AtomicInteger();
      AtomicInteger returned = new AtomicInteger();
      AtomicInteger excess = new AtomicInteger();
      AtomicBoolean sampling = new AtomicBoolean(true);
      Thread sampler = Thread.ofPlatform().start(() -> {
        while (sampling.get()) {
          // A search counts as started before it admits and as returned after its caller got the
          // answer. Reading returned, then the slots, then started bounds the callers that may
          // have been waiting at the instant the slots were read; a slot over that bound is held
          // by work whose caller was already released.
          int returnedBefore = returned.get();
          int slots = admission.activeWorkCount();
          int mayBeWaiting = started.get() - returnedBefore;
          if (slots > mayBeWaiting) excess.accumulateAndGet(slots - mayBeWaiting, Math::max);
          Thread.onSpinWait();
        }
      });
      List<Thread> callers = new ArrayList<>();
      for (int c = 0; c < CALLERS; c++) {
        callers.add(Thread.ofPlatform().start(() -> {
          for (int round = 0; round < 3; round++) {
            started.incrementAndGet();
            try {
              stack.search();
            } catch (RuntimeException outcomeNotUnderTest) {
              // The answer of a stalled search is not under test here, only its slot.
            } finally {
              returned.incrementAndGet();
            }
          }
        }));
      }
      for (Thread caller : callers) caller.join(TimeUnit.SECONDS.toMillis(40));
      sampling.set(false);
      sampler.join();
      for (Thread caller : callers) assertTrue(!caller.isAlive(), "every caller returned");

      assertTrue(stack.stalledRuns.get() > 0, "the semantic runs stalled in native code");
      assertEquals(0, excess.get(), "slots held beyond the live callers");
      assertEquals(0, admission.activeWorkCount(),
          "every slot is free once the callers returned, with native runs still stalled");
    }
  }

  /**
   * Task t-20261005-b6d536, FP1, FP4 and FP10 (changed intent: the ninth search used to be refused
   * at once). With the eight inference threads held by stalled native runs, the ninth to sixteenth
   * concurrent hybrid searches wait for a thread instead of being refused, the seventeenth finds
   * the threads and the queue full and is refused with its own code, and the waiting searches end
   * by their own deadline, all with admission far below {@code aggregateLimit}.
   */
  @Test
  void searchesBeyondTheInferenceThreadsWaitAndOnlyAFullQueueRefusesAsInferenceBusy()
      throws Exception {
    var admission = new EngineAdmissionController(64, 64, 1);
    try (var stack = new Stack(admission)) {
      List<FutureTask<Object>> running = new ArrayList<>();
      for (int c = 0; c < CALLERS; c++) {
        var caller = new FutureTask<Object>(stack::search);
        running.add(caller);
        Thread.ofPlatform().start(caller);
      }
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (stack.stalledOrWaiting() < CALLERS && System.nanoTime() < deadline) {
        Thread.sleep(1);
      }
      assertEquals(CALLERS, admission.activeWorkCount(), "eight searches in flight, one slot each");
      // Admission precedes the hand-off to a call thread; let the eighth reach its thread.
      Thread.sleep(300);

      long submitted = System.nanoTime();
      List<FutureTask<Object>> waiting = new ArrayList<>();
      for (int c = 0; c < CALLERS; c++) {
        var caller = new FutureTask<Object>(stack::search);
        waiting.add(caller);
        Thread.ofPlatform().start(caller);
      }
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (stack.queuedInference() < CALLERS && waiting.stream().noneMatch(FutureTask::isDone)
          && System.nanoTime() < deadline) {
        Thread.sleep(1);
      }
      for (var caller : waiting) {
        assertTrue(!caller.isDone(),
            () -> "a search beyond the threads ended at once: " + outcome(caller));
      }
      assertEquals(CALLERS, stack.queuedInference(),
          "the ninth to sixteenth searches wait for a thread");

      var refusal = org.junit.jupiter.api.Assertions.assertThrows(
          EngineAdmissionException.class, stack::search);
      assertEquals("INFERENCE_BUSY", refusal.reason().name());
      assertTrue(admission.activeWorkCount() <= 2 * CALLERS,
          "the refusal came with " + admission.activeWorkCount() + " of 64 slots in use");

      for (var caller : waiting) {
        try {
          caller.get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException outcome) {
          assertTrue(outcome.getCause()
                  instanceof io.justsearch.app.api.knowledge.KnowledgeClientException failure
              && failure.status()
                  == io.justsearch.app.api.knowledge.KnowledgeClientException.Status.DEADLINE_EXCEEDED,
              "a waiting search behind stalled runs ends by its own deadline: " + outcome.getCause());
        }
      }
      assertTrue(System.nanoTime() - submitted < TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS + 2_000),
          "the waiting searches outlived their deadline");
      for (var caller : running) {
        try {
          caller.get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException outcome) {
          assertTrue(!(outcome.getCause() instanceof EngineAdmissionException),
              "none of the eight earlier searches was refused: " + outcome.getCause());
        }
      }
    }
  }

  private static String outcome(FutureTask<Object> caller) {
    try {
      return String.valueOf(caller.get(0, TimeUnit.MILLISECONDS));
    } catch (Exception e) {
      return String.valueOf(e.getCause() == null ? e : e.getCause());
    }
  }

  /** The Engine client over a real worker search service whose GPU query runs stall. */
  private static final class Stack implements AutoCloseable {
    private final CountDownLatch mayReturn = new CountDownLatch(1);
    private final AtomicInteger stalledRuns = new AtomicInteger();
    private final io.justsearch.ort.SessionHandle nativeHandle;
    private final io.justsearch.indexerworker.embed.EmbeddingService embedding;
    private final DefaultEngineExecutorRegistry registry;
    private final EngineKnowledgeClient client;
    private final EngineAdmissionController admission;

    private Stack(EngineAdmissionController admission) throws Exception {
      this.admission = admission;
      var model = java.nio.file.Path.of("missing", "model_fp16.onnx");
      var variant = io.justsearch.configuration.model.VariantSelection.optimal(model,
          io.justsearch.configuration.model.ModelPrecision.FP16,
          io.justsearch.configuration.model.ExecutionProvider.CUDA);
      var policy = new io.justsearch.ort.ModelSessionPolicy(variant,
          new io.justsearch.ort.ModelSessionPolicy.Gpu(512L * 1024 * 1024, 0,
              java.util.Optional.empty()),
          new io.justsearch.ort.ModelSessionPolicy.Cpu(
              ai.onnxruntime.OrtSession.SessionOptions.OptLevel.BASIC_OPT),
          new io.justsearch.ort.ModelSessionPolicy.Lifecycle(true, false, 0),
          new io.justsearch.ort.ModelSessionPolicy.RunOptions(true));
      nativeHandle = io.justsearch.ort.OrtSessionAssembler.buildManager("stall-admission",
          new io.justsearch.ort.Composition(io.justsearch.ort.RuntimePolicy.defaults(), policy,
              new io.justsearch.ort.ModelArtifacts(model, model)), () -> true);
      var gpu = mock(ai.onnxruntime.OrtSession.class);
      org.mockito.stubbing.Answer<Object> stall = invocation -> {
        stalledRuns.incrementAndGet();
        mayReturn.await();
        throw new ai.onnxruntime.OrtException("stalled run released at test end");
      };
      when(gpu.run(anyMap())).thenAnswer(stall);
      when(gpu.run(anyMap(), any(ai.onnxruntime.OrtSession.RunOptions.class))).thenAnswer(stall);
      setNativeField(nativeHandle, "gpuSession", gpu);
      setNativeField(nativeHandle, "gpuAvailable", true);
      setNativeField(nativeHandle, "gpuSessionAttempted", true);
      var tokenizer = mock(ai.djl.huggingface.tokenizers.HuggingFaceTokenizer.class);
      var encoding = mock(ai.djl.huggingface.tokenizers.Encoding.class);
      when(tokenizer.encode(any(String.class))).thenReturn(encoding);
      when(encoding.getIds()).thenReturn(new long[] {0, 1});
      when(encoding.getAttentionMask()).thenReturn(new long[] {1, 1});
      when(encoding.getTypeIds()).thenReturn(new long[] {0, 0});
      embedding = io.justsearch.indexerworker.embed.EmbeddingService.createWithBackend(
          new io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingBackend(
              new io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder(nativeHandle,
                  new io.justsearch.indexerworker.embed.onnx.EmbeddingShape(8, false,
                      io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder.PoolingStrategy
                          .MEAN_POOL,
                      8, 0), tokenizer), 1, 8),
          new io.justsearch.indexerworker.embed.EmbeddingConfig(true, model.getParent(), "onnx",
              true, 0, 512L * 1024 * 1024, 8, false, 8));
      var runtime = mock(io.justsearch.adapters.lucene.runtime.RunningRuntime.class,
          org.mockito.Mockito.RETURNS_DEEP_STUBS);
      when(runtime.resolvedConfig()).thenReturn(
          io.justsearch.configuration.resolved.ResolvedConfig.builder().build());
      when(runtime.indexCountOps().getOrComputeCorpusProfile()).thenReturn(
          io.justsearch.adapters.lucene.runtime.CorpusProfile.EMPTY);
      var workerService = new WorkerSearchService(
          runtime, embedding, new io.justsearch.indexerworker.server.EncoderBindings());
      var services = mock(WorkerAppServices.class);
      when(services.searchService()).thenReturn(workerService);
      var view = mock(KnowledgeServer.ServingLease.class);
      when(view.services()).thenReturn(services);
      registry = registry();
      client = new EngineKnowledgeClient(registry, () -> services,
          new ForegroundLoadGate(new ForegroundLoad()), DEADLINE_MS, 100, IpcTelemetry.noop(),
          () -> {}, admission, io.justsearch.app.services.worker.WatchedRootsState.inMemory(),
          () -> view);
    }

    /** One hybrid search, the way the HTTP and MCP fronts issue it. */
    private Object search() {
      var request = io.justsearch.ipc.SearchRequest.newBuilder().setQuery("stalled").setLimit(10)
          .setPipeline(io.justsearch.ipc.PipelineConfig.newBuilder().setDenseEnabled(true))
          .build();
      return client.search(request, TestEngineContexts.FOREGROUND);
    }

    /** Searches waiting for a foreground inference thread. */
    private int queuedInference() {
      return registry.snapshot().registrations().stream()
          .filter(row -> row.spec().name().equals("engine-knowledge-inference-foreground"))
          .findFirst().orElseThrow().queuedTasks();
    }

    /** Searches holding a slot (each waits in, or behind, a stalled native run). */
    private int stalledOrWaiting() {
      return admission.activeWorkCount();
    }

    @Override
    public void close() throws Exception {
      mayReturn.countDown();
      try {
        client.close();
      } finally {
        try {
          registry.close();
        } finally {
          embedding.close();
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
          while (System.nanoTime() < deadline) {
            try {
              nativeHandle.close();
              break;
            } catch (IllegalStateException runsStillReturning) {
              Thread.sleep(10);
            }
          }
        }
      }
    }
  }

  /** The packaged execution policy's foreground shape: 16 threads (8 for inference), queue 48. */
  private static DefaultEngineExecutorRegistry registry() {
    Map<String, Integer> execution = new LinkedHashMap<>();
    execution.put("perContextLimit", 64);
    execution.put("aggregateLimit", 64);
    execution.put("retryAfterSeconds", 1);
    execution.put("foregroundThreads", 16);
    execution.put("foregroundQueue", 48);
    execution.put("backgroundThreads", 4);
    execution.put("backgroundQueue", 64);
    execution.put("timerRegistrations", 256);
    execution.put("directMemoryMiB", 1);
    return new DefaultEngineExecutorRegistry(
        new EngineResourcePolicy(Map.copyOf(execution), new RetainedStateBudget()),
        Duration.ofSeconds(1));
  }

  private static void setNativeField(Object handle, String name, Object value) throws Exception {
    var field = handle.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(handle, value);
  }
}
