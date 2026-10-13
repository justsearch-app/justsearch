/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.embed.onnx.EmbeddingAssembly;
import io.justsearch.indexerworker.embed.onnx.EmbeddingShape;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.ipc.HealthCheckRequest;
import io.justsearch.ipc.HealthCheckResponse;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.TraceDegradation;
import io.justsearch.ort.GpuLifecycleCallback;
import io.justsearch.ort.ModelCapabilities;
import io.justsearch.ort.ModelCapabilities.PoolingMode;
import io.justsearch.ort.OrtCudaStatus;
import io.justsearch.ort.OrtRunRecorder;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.SessionAcquireDeadlineExceededException;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.ort.SessionHandle;
import io.justsearch.ort.telemetry.CpuRecreateCause;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Shared fixture of the query prewarm checks (Spec D step 7): a started {@link KnowledgeServer}
 * whose production deferred model initialization the test runs and observes, with only the native
 * session owner replaced by {@link SlowGpuSessions}.
 *
 * <p>Uses only API that exists before the change, so checks built on it can run on the
 * before-state.
 */
final class QueryPrewarmFixture {
  static final long CREATION_MS = 1_500L;
  static final int DIMENSION = 768;

  private QueryPrewarmFixture() {}

  /** A started server whose deferred model initialization the test runs and observes. */
  static final class Boot implements AutoCloseable {
    private final ConfigStore previous = ConfigStore.globalOrNull();
    private final SlowGpuSessions sessions;
    private final Path model;
    private final TestEngineExecutors executors = new TestEngineExecutors();
    private final KnowledgeServer server;
    private final HuggingFaceTokenizer tokenizer = mock(HuggingFaceTokenizer.class);

    Boot(Path dir, String name, SlowGpuSessions sessions, long prewarmBoundMs) throws Exception {
      this(dir, name, sessions, prewarmBoundMs, server -> {});
    }

    /** {@code stubs} adds stubs to the server spy before it starts. */
    Boot(Path dir, String name, SlowGpuSessions sessions, long prewarmBoundMs,
        Consumer<KnowledgeServer> stubs) throws Exception {
      this.sessions = sessions;
      Path models = dir.resolve(name + "-models");
      this.model = createEmbeddingModel(models);
      ResolvedConfig configuration = configuration(dir, name, models, true);
      ConfigStore.setGlobal(new ConfigStore(configuration));
      Encoding encoding = mock(Encoding.class);
      when(encoding.getIds()).thenReturn(new long[] {101, 7592, 102});
      when(encoding.getAttentionMask()).thenReturn(new long[] {1, 1, 1});
      when(encoding.getTypeIds()).thenReturn(new long[] {0, 0, 0});
      when(tokenizer.encode(anyString())).thenReturn(encoding);
      server = spy(new KnowledgeServer(executors, WorkerConfig.load(configuration), null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), null, null,
          configuration));
      doNothing().when(server).startDeferredModelInitialization(any());
      doReturn(prewarmBoundMs).when(server).queryPreparationWarmupBudgetMillis();
      stubs.accept(server);
      server.start();
    }

    CountDownLatch modelReadyLatch() throws Exception {
      return (CountDownLatch) field(server, "modelReadyLatch");
    }

    HealthCheckResponse health() {
      try (var view = server.captureServingView()) {
        return view.services().healthService().check(HealthCheckRequest.getDefaultInstance());
      }
    }

    boolean embeddingReady() {
      return health().getEmbeddingReady();
    }

    /** Runs model initialization while sampling readiness and warmth, as a client would. */
    Observations initializeWhileObservingReadiness() throws Exception {
      var shape = new EmbeddingShape(
          512, false, OnnxEmbeddingEncoder.PoolingStrategy.MEAN_POOL, 4096, DIMENSION);
      var capabilities = new ModelCapabilities(
          PoolingMode.MEAN, 512, DIMENSION, ModelPrecision.FP32, null, null, null,
          Map.of(), List.of());
      var assembly = new EmbeddingAssembly(sessions, shape, tokenizer, capabilities);
      var readyWhileCold = new CopyOnWriteArrayList<String>();
      var done = new AtomicBoolean();
      try (var initializer = Executors.newSingleThreadExecutor();
          var observer = Executors.newSingleThreadExecutor()) {
        Future<?> observing = observer.submit(() -> {
          while (!done.get()) {
            // Readiness first: warmth only grows, so a warm read after a ready read is honest.
            boolean ready = embeddingReady();
            boolean warm = sessions.isQueryWarm();
            if (ready && !warm) readyWhileCold.add("embeddingReady with a cold query session");
            Thread.sleep(10);
          }
          return null;
        });
        Future<?> initialization = initializer.submit(() -> {
          // Mockito static mocks belong to the thread that composes the models.
          try (var assembler = mockStatic(OrtSessionAssembler.class);
              var embeddingAssembly = mockStatic(OnnxEmbeddingEncoder.class)) {
            assembler.when(() -> OrtSessionAssembler.buildManager(anyString(), any(), any(), any()))
                .thenReturn(sessions);
            embeddingAssembly.when(() -> OnnxEmbeddingEncoder.buildAssembly(
                    same(sessions), eq(model), anyInt(), anyInt(), anyBoolean(), any()))
                .thenReturn(assembly);
            invokeDeferredModelInitialization(server);
          }
          return null;
        });
        try {
          initialization.get(60, TimeUnit.SECONDS);
        } finally {
          done.set(true);
          observing.get(10, TimeUnit.SECONDS);
        }
      }
      try (var view = server.captureServingView()) {
        // Dense search is allowed on this index generation; the compatibility controller has its
        // own tests and an empty test index has no recorded embedding fingerprint.
        view.services().searchService().setEmbeddingCompatController(null);
      }
      return new Observations(List.copyOf(readyWhileCold));
    }

    TraceDegradation hybridSearch() {
      try (var view = server.captureServingView()) {
        return view.services().searchService().search(
                SearchRequest.newBuilder().setQuery("hello").setLimit(10)
                    .setMode(SearchMode.SEARCH_MODE_HYBRID)
                    .setPipeline(PipelineConfig.newBuilder()
                        .setSparseEnabled(true).setDenseEnabled(true))
                    .build(),
                foreground(Duration.ofSeconds(3)))
            .getSearchTrace().getDegradation();
      }
    }

    @Override
    public void close() throws Exception {
      try {
        server.close();
      } finally {
        executors.close();
        TestResolvedConfigHelper.restoreGlobal(previous);
      }
    }
  }

  record Observations(List<String> readyWhileCold) {}

  /**
   * A GPU session owner whose session takes {@code creationMs} to create. As the native owner does,
   * it never creates a session for an acquisition that may not create one: such an acquisition
   * gets the session only once it is warm, and is otherwise refused with phase {@code LOADING}. A
   * session is warm once a foreground run that was allowed to create it has completed on it.
   */
  static final class SlowGpuSessions implements SessionHandle {
    private final long creationMs;
    private final Object lock = new Object();
    private final OrtSession session = mock(OrtSession.class);
    private final List<Runnable> generationListeners = new CopyOnWriteArrayList<>();
    final AtomicInteger creations = new AtomicInteger();
    final AtomicInteger creationAttempts = new AtomicInteger();
    final AtomicInteger acquisitions = new AtomicInteger();
    final AtomicInteger queryRefusals = new AtomicInteger();
    volatile RuntimeException creationFailure;
    volatile boolean cpuOnly;
    volatile boolean retryDue;
    private volatile boolean created;
    private volatile boolean warm;
    private volatile long generation;
    private volatile boolean closed;

    SlowGpuSessions(long creationMs) {
      this.creationMs = creationMs;
      try {
        OnnxValue hidden = mock(OnnxValue.class);
        when(hidden.getValue()).thenAnswer(invocation -> new float[1][3][DIMENSION]);
        OrtSession.Result result = mock(OrtSession.Result.class);
        when(result.get(0)).thenReturn(hidden);
        when(session.run(anyMap())).thenReturn(result);
        when(session.run(anyMap(), any(OrtSession.RunOptions.class))).thenReturn(result);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    /** Whether a query that may not create a session would run now. */
    public boolean isQueryWarm() {
      return cpuOnly || warm;
    }

    public boolean isGpuRetryDue() {
      return cpuOnly && retryDue;
    }

    /** Runs {@code listener} after the session is created or released. */
    public void addGenerationListener(Runnable listener) {
      generationListeners.add(listener);
    }

    @Override
    public Lease acquire(SessionAcquisitionRequest request) {
      acquisitions.incrementAndGet();
      request.remainingNanos();
      if (cpuOnly && retryDue && request.mayCreateGpuSession()) cpuOnly = false;
      if (cpuOnly) return new Lease(session, null, () -> {}, true, OrtRunRecorder.NOOP, request);
      long leaseGeneration;
      boolean createdNow = false;
      synchronized (lock) {
        if (!request.mayCreateGpuSession() && !warm) {
          queryRefusals.incrementAndGet();
          throw new SessionAcquireDeadlineExceededException(
              "test owner: no warm query session yet",
              SessionAcquireDeadlineExceededException.Phase.LOADING);
        }
        if (!created) {
          creationAttempts.incrementAndGet();
          RuntimeException failure = creationFailure;
          if (failure != null) throw failure;
          try {
            Thread.sleep(creationMs);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("creation interrupted");
          }
          created = true;
          generation++;
          creations.incrementAndGet();
          createdNow = true;
        }
        leaseGeneration = generation;
      }
      if (createdNow) fireGenerationChanged();
      boolean warms = request.urgency() == SessionAcquisitionRequest.Urgency.FOREGROUND
          && request.mayCreateGpuSession();
      OrtRunRecorder recorder = elapsed -> {
        if (!warms) return;
        synchronized (lock) {
          if (created && generation == leaseGeneration) warm = true;
        }
      };
      return new Lease(session, null, () -> {}, false, recorder, request);
    }

    @Override
    public Lease acquireCpu(SessionAcquisitionRequest request) {
      throw new UnsupportedOperationException("no CPU session in this test owner");
    }

    @Override
    public OrtEnvironment environment() {
      return OrtEnvironment.getEnvironment();
    }

    @Override
    public boolean isGpuAvailable() {
      return !cpuOnly && created;
    }

    @Override
    public OrtCudaStatus status() {
      return OrtCudaStatus.notConfigured();
    }

    @Override
    public void releaseGpu() {
      synchronized (lock) {
        created = false;
        warm = false;
        generation++;
      }
      fireGenerationChanged();
    }

    private void fireGenerationChanged() {
      for (Runnable listener : generationListeners) listener.run();
    }

    @Override
    public void reportCpuSessionFailure(CpuRecreateCause cause) {}

    @Override
    public void setLifecycleCallback(GpuLifecycleCallback callback) {}

    @Override
    public void setOrtRunRecorder(OrtRunRecorder recorder) {}

    @Override
    public RetirementStatus retirementStatus() {
      return closed ? RetirementStatus.RETIRED : RetirementStatus.ACTIVE;
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  static ResolvedConfig configuration(Path dir, String name, Path modelsDir, boolean embedding) {
    var values = new HashMap<String, String>();
    values.put(EnvRegistry.DATA_DIR.configKey(), dir.resolve(name).toString());
    values.put("justsearch.index.base_path", dir.resolve(name + "-index").toString());
    values.put(EnvRegistry.MODELS_DIR.configKey(), modelsDir.toString());
    values.put(EnvRegistry.SPARSE_MODEL.configKey(), "splade");
    values.put(EnvRegistry.AI_EMBED_ENABLED.configKey(), Boolean.toString(embedding));
    values.put(EnvRegistry.EMBED_CONTEXT_LENGTH.configKey(), "512");
    values.put(EnvRegistry.EMBED_LATE_CHUNKING_CONTEXT_LENGTH.configKey(), "4096");
    for (var key :
        new EnvRegistry[] {
          EnvRegistry.SPLADE_ENABLED,
          EnvRegistry.NER_ENABLED,
          EnvRegistry.RERANK_ENABLED,
          EnvRegistry.CITATION_SCORER_ENABLED,
          EnvRegistry.BGE_M3_ENABLED
        }) {
      values.put(key.configKey(), "false");
    }
    return TestResolvedConfigHelper.fromEntries(values);
  }

  static Path createEmbeddingModel(Path modelsDir) throws Exception {
    Path modelDir = modelsDir.resolve("onnx").resolve("gte-multilingual-base");
    Files.createDirectories(modelDir);
    Files.writeString(modelDir.resolve("model.onnx"), "stub");
    Files.writeString(modelDir.resolve("tokenizer.json"), "stub");
    return modelDir;
  }

  /** A foreground (interactive) call whose request deadline is {@code budget} from now. */
  static CallContext foreground(Duration budget) {
    CallContext none = CallContext.none();
    EngineContext interactive = new EngineContext(
        EngineContext.ClientKind.INTERNAL, "query-prewarm-test", Optional.empty(),
        Optional.empty(), "TRUSTED", "SYSTEM_INTERNAL", EngineContext.Survival.DURABLE,
        EngineContext.Urgency.FOREGROUND);
    return new CallContext(null, null, CallContext.CancelSignal.NEVER, interactive,
        none.provenance(), none.childLifetime(), System.nanoTime() + budget.toNanos());
  }

  static boolean waitFor(Duration bound, java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + bound.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return true;
      Thread.sleep(20);
    }
    return condition.getAsBoolean();
  }

  static void invokeDeferredModelInitialization(KnowledgeServer server) throws Exception {
    var method = KnowledgeServer.class.getDeclaredMethod("initDeferredModels");
    method.setAccessible(true);
    method.invoke(server);
  }

  static Object field(Object target, String name) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }
}
