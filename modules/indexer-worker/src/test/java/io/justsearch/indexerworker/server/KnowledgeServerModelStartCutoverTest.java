/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.app.api.indexing.ProjectionSeedSource;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.embed.onnx.EmbeddingAssembly;
import io.justsearch.indexerworker.embed.onnx.EmbeddingShape;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.ipc.MigrationStartRequest;
import io.justsearch.ipc.TraceDegradation;
import io.justsearch.ort.ModelCapabilities;
import io.justsearch.ort.OrtSessionAssembler;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * A generation switch while the deferred model start is in flight (M1, M3, M4, P1-P3, P5, P9).
 * The real deferred start runs. Where the order is forced, the start is held after it captured its
 * wiring leases on the old view and composed, before it wires and attaches its initial model sets;
 * the switch is requested only once the start is held there. The start is released when the
 * successor is published (the order of the unchanged Engine) or when the switch has been retried
 * once while the start was held (promotion waits for it), whichever comes first.
 */
@Timeout(180)
final class KnowledgeServerModelStartCutoverTest {
  private static final String RETIRED_OWNS = "Retired index serving view still owns resources";
  private static final String SET_RETIRING = "Encoder set is retiring";
  private static final String QUERY_SET_RETIRING = "Query role set is retiring";

  @TempDir Path tempDir;

  // Process-global state this class sets (ConfigStore, the embedding identity cache): each test
  // restores it, and the class checks after its tests that it is what it was before them.
  private static io.justsearch.configuration.resolved.ConfigStore configBeforeClass;
  private static Object embeddingIdentityBeforeClass;
  private io.justsearch.configuration.resolved.ConfigStore configBeforeTest;
  private Object embeddingIdentityBeforeTest;

  @org.junit.jupiter.api.BeforeAll
  static void captureGlobalsBeforeClass() throws Exception {
    configBeforeClass = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    embeddingIdentityBeforeClass = embeddingIdentityCache().get();
  }

  @org.junit.jupiter.api.BeforeEach
  void captureGlobals() throws Exception {
    configBeforeTest = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    embeddingIdentityBeforeTest = embeddingIdentityCache().get();
  }

  @org.junit.jupiter.api.AfterEach
  void restoreGlobals() throws Exception {
    io.justsearch.configuration.resolved.TestResolvedConfigHelper.restoreGlobal(configBeforeTest);
    embeddingIdentityCache().set(embeddingIdentityBeforeTest);
  }

  @org.junit.jupiter.api.AfterAll
  static void globalsAreBackToWhatTheyWereBeforeTheClass() throws Exception {
    assertSame(configBeforeClass, io.justsearch.configuration.resolved.ConfigStore.globalOrNull(),
        "the class restores the process-global ConfigStore");
    assertSame(embeddingIdentityBeforeClass, embeddingIdentityCache().get(),
        "the class restores the JVM-wide embedding identity cache");
  }

  @SuppressWarnings("unchecked")
  private static AtomicReference<Object> embeddingIdentityCache()
      throws Exception {
    var field = io.justsearch.indexerworker.embed.EmbeddingFingerprint.class
        .getDeclaredField("cachedResult");
    field.setAccessible(true);
    return (AtomicReference<Object>) field.get(null);
  }

  @Test
  void switchDuringDeferredModelStartKeepsSuccessorModelsAndRetiresOldGeneration()
      throws Exception {
    try (var forced = new Forced(true)) {
      var server = forced.boot(plainServer(tempDir));
      forced.migrateLive();
      forced.startModelsWhenSwitchReached();
      forced.awaitSwitchAndModels();

      forced.assertOrderForced();
      forced.assertSuccessorModelsAndRetirement(server);
    }
  }

  @Test
  void failedModelStartDuringSwitchReleasesTheSwitch() throws Exception {
    try (var forced = new Forced(true)) {
      forced.failHeldStart = true;
      var server = forced.boot(plainServer(tempDir));
      forced.migrateLive();
      forced.startModelsWhenSwitchReached();
      forced.awaitSwitchAndModels();

      forced.assertOrderForced();
      assertEquals(forced.successorGeneration, forced.manager.readStateBestEffort().active_generation());
      forced.assertOldGenerationRetired(server);
      assertEquals(List.of(), forced.matching(RETIRED_OWNS));
      assertEquals(List.of(), forced.matching(SET_RETIRING));
    }
  }

  @Test
  void shutdownWhileTheSwitchWaitsForAHeldModelStartEndsOnceTheStartExits() throws Exception {
    try (var forced = new Forced(true)) {
      forced.releaseOnSwitchRetry = false;
      var server = forced.boot(plainServer(tempDir));
      var closeEntered = new CountDownLatch(1);
      doAnswer(invocation -> {
        closeEntered.countDown();
        return invocation.callRealMethod();
      }).when(server).close();
      forced.migrateLive();
      forced.startModelsWhenSwitchReached();
      assertTrue(forced.startHeld.await(60, TimeUnit.SECONDS), "the start is held");
      var closed = closeInBackground(server);
      assertTrue(closeEntered.await(30, TimeUnit.SECONDS), "close() was entered");
      // The held start exits only when released; close joins it (unbounded, as before).
      forced.releaseHeld.countDown();
      closed.get(60, TimeUnit.SECONDS);
      assertTrue(server.awaitClosed(0), "close completed");
      assertTrue(server.awaitMigrationCutoverExitForTests(30, TimeUnit.SECONDS),
          "the switch's retry loop ended with shutdown");
    }
  }

  @Test
  void modelsAttachedBeforeTheSwitchStayWithTheSuccessor() throws Exception {
    try (var forced = new Forced(false)) {
      var server = forced.boot(plainServer(tempDir));
      forced.startModels();
      server.deferredModelInit.get(60, TimeUnit.SECONDS);
      EncoderSet before;
      try (var serving = server.captureServingView()) { before = serving.encoderSet(); }
      assertNotNull(before);
      forced.migrateLive();
      assertTrue(server.awaitMigrationCutoverExitForTests(60, TimeUnit.SECONDS));

      try (var serving = server.captureServingView()) {
        assertSame(before, serving.encoderSet(), "the successor keeps the shared index set");
      }
      forced.assertSuccessorModelsAndRetirement(server);
    }
  }

  @Test
  void workHeldOnTheOldViewDelaysRetirementAndCannotCloseSharedModels() throws Exception {
    try (var forced = new Forced(false)) {
      forced.captureAtSwitch = true;
      var server = forced.boot(plainServer(tempDir));
      forced.migrateLive();
      assertTrue(server.awaitMigrationCutoverExitForTests(60, TimeUnit.SECONDS));
      // Work issued against the view the switch retires, and its forked child, held through it.
      var issued = Objects.requireNonNull(forced.issuedAtSwitch, "lease issued at the switch");
      var child = issued.fork();
      try {
        assertEquals(forced.successorGeneration,
            forced.manager.readStateBestEffort().active_generation());
        // The start begins after promotion, while the retired view is still held: it attaches its
        // sets to the serving view and to the held old view.
        forced.startModels();
        server.deferredModelInit.get(60, TimeUnit.SECONDS);
        assertSame(issued.encoderSet(), serving(server).encoderSet(),
            "the held old view and the successor share the start's index set");

        issued.close();
        forced.invokeRetirement();
        assertTrue(Files.exists(forced.oldGenerationPath()), "the forked child still holds A");
        child.close();
      } finally {
        issued.close();
        child.close();
      }
      forced.assertSuccessorModelsAndRetirement(server, false);
    }
  }

  @Test
  void successorQueryEncodersRunAfterTheSwitchDuringModelStart() throws Exception {
    // The index generation records no embedding identity (the stub model has none), so the switch
    // does not wait for Green's embedding stamp; this check is about the query encoders.
    io.justsearch.indexerworker.embed.EmbeddingFingerprint.setForTesting(null);
    var sessions = new QueryPrewarmFixture.SlowGpuSessions(50L);
    var captured = new AtomicReference<KnowledgeServer>();
    try (var forced = new Forced(true)) {
      try (var boot = new QueryPrewarmFixture.Boot(tempDir, "m4", sessions, 10_000L, server -> {
        captured.set(server);
        forced.install(server);
        forced.projectionSource(server);
      })) {
        var server = captured.get();
        forced.attached(server);
        forced.initializer = embeddingInitializer(server, sessions,
            tempDir.resolve("m4-models").resolve("onnx").resolve("gte-multilingual-base"));
        forced.migrateLive();
        forced.startModelsWhenSwitchReached();
        forced.awaitSwitchAndModels();
        forced.assertOrderForced();
        forced.assertSuccessorModelsAndRetirement(server);

        int creations = sessions.creations.get();
        assertTrue(creations > 0, "a prewarm pass ran the configured query encoder");
        assertTrue(sessions.isQueryWarm(), "the successor's query encoder is warm");
        try (var serving = server.captureServingView()) {
          serving.services().searchService().setEmbeddingCompatController(null);
        }
        int acquisitions = sessions.acquisitions.get();
        TraceDegradation degradation = boot.hybridSearch();
        assertTrue(sessions.acquisitions.get() > acquisitions, "the search ran its dense leg");
        assertFalse(degradation.getHybridFallback(),
            () -> "the search fell back: " + degradation.getHybridFallbackReason());
        // The producer moved to the successor keeps a usable model lease (P3).
        try (var serving = server.captureServingView()) {
          var producer = (DefaultWorkerAppServices) serving.services();
          assertFalse(producer.producerClosed());
          serving.encoderSet().acquire().close();
        }
        assertEquals(List.of(), forced.matching(SET_RETIRING));
        assertEquals(List.of(), forced.matching(QUERY_SET_RETIRING));
      }
    }
  }

  @Test
  void aRetiringServingSetIsReportedOnceAndApartFromWarm() throws Exception {
    var sessions = new QueryPrewarmFixture.SlowGpuSessions(50L);
    var events = new CopyOnWriteArrayList<ILoggingEvent>();
    var serverLog = (Logger) LoggerFactory.getLogger(KnowledgeServer.class);
    var appender = new AppenderBase<ILoggingEvent>() {
      @Override protected void append(ILoggingEvent event) { events.add(event); }
    };
    var prewarmLog = (Logger) LoggerFactory.getLogger(QueryEncoderPrewarm.class);
    Level prewarmLevel = prewarmLog.getLevel();
    appender.start();
    serverLog.addAppender(appender);
    prewarmLog.addAppender(appender);
    prewarmLog.setLevel(Level.DEBUG);
    try (var boot = new QueryPrewarmFixture.Boot(tempDir, "m2", sessions, 10_000L)) {
      boot.initializeWhileObservingReadiness();
      var warm = boot.health();
      assertFalse(warm.getQueryEncodersCold(), "warm before retirement");
      assertFalse(warm.getVersion().contains("Query models unavailable"));

      EncoderSet serving;
      try (var view = bootServer(boot).captureServingView()) { serving = view.encoderSet(); }
      var held = serving.acquire();
      CompletableFuture<Void> retiring = CompletableFuture.runAsync(() -> {
        try { serving.close(); } catch (IllegalStateException retained) { /* deadline */ }
      });
      try {
        assertTrue(QueryPrewarmFixture.waitFor(java.time.Duration.ofSeconds(15),
            () -> boot.health().getVersion().contains("Query models unavailable")),
            "health reports the serving set as unavailable");
        var health = boot.health();
        assertTrue(health.getQueryEncodersCold(), "not reported warm");
        assertTrue(health.getAiReady(), "not reported as no models");
        assertTrue(health.getEmbeddingReady(), "readiness already reached is not withdrawn");
        // The prewarm check selects every second; after three refused selections, one warning.
        assertTrue(QueryPrewarmFixture.waitFor(java.time.Duration.ofSeconds(15),
            () -> events.stream().filter(event -> event.getFormattedMessage()
                .contains("No query encoders to prewarm")).count() >= 3), "selections repeat");
        long warnings = events.stream().filter(event -> event.getLevel() == Level.WARN
            && event.getFormattedMessage().contains("Serving model set cannot be acquired")).count();
        assertEquals(1L, warnings, "one warning-level line");
      } finally {
        held.close();
        retiring.get(30, TimeUnit.SECONDS);
        serverLog.detachAppender(appender);
        prewarmLog.detachAppender(appender);
        prewarmLog.setLevel(prewarmLevel);
        appender.stop();
      }
    }
  }

  /**
   * M5: a deferred model start that begins and completes (or fails after wiring) while the switch
   * prepares its successor. The recorded switch's settings preparation runs outside the runtime
   * lock; the test lifecycle holds the first preparation until the start has exited.
   */
  @org.junit.jupiter.params.ParameterizedTest(name = "start fails after wiring: {0}")
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void aModelStartInsideSuccessorPreparationIsWhatTheSuccessorServes(boolean failAfterWiring)
      throws Exception {
    var previous = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    Path models = tempDir.resolve("m5-models");
    Path reranker = models.resolve("onnx").resolve("reranker");
    Files.createDirectories(reranker);
    for (String file : List.of("model.onnx", "model_fp16.onnx", "tokenizer.json", "config.json")) {
      Files.writeString(reranker.resolve(file), "stub");
    }
    var values = new java.util.HashMap<String, String>();
    values.put(io.justsearch.configuration.EnvRegistry.DATA_DIR.configKey(),
        tempDir.resolve("m5").toString());
    values.put("justsearch.index.base_path", tempDir.resolve("m5-index").toString());
    values.put(io.justsearch.configuration.EnvRegistry.MODELS_DIR.configKey(), models.toString());
    values.put(io.justsearch.configuration.EnvRegistry.SPARSE_MODEL.configKey(), "splade");
    for (var key : new io.justsearch.configuration.EnvRegistry[] {
        io.justsearch.configuration.EnvRegistry.AI_EMBED_ENABLED,
        io.justsearch.configuration.EnvRegistry.SPLADE_ENABLED,
        io.justsearch.configuration.EnvRegistry.NER_ENABLED,
        io.justsearch.configuration.EnvRegistry.CITATION_SCORER_ENABLED,
        io.justsearch.configuration.EnvRegistry.BGE_M3_ENABLED}) {
      values.put(key.configKey(), "false");
    }
    values.put(io.justsearch.configuration.EnvRegistry.RERANK_ENABLED.configKey(), "true");
    values.put(io.justsearch.configuration.EnvRegistry.RERANK_MODEL_PATH.configKey(),
        reranker.toString());
    values.put(io.justsearch.configuration.EnvRegistry.RERANK_GPU_ENABLED.configKey(), "false");
    var configuration = io.justsearch.configuration.resolved.TestResolvedConfigHelper
        .fromEntries(values);
    io.justsearch.configuration.resolved.ConfigStore.setGlobal(
        new io.justsearch.configuration.resolved.ConfigStore(configuration));
    var lifecycle = new PreparationWindowLifecycle();
    try (var executors = new TestEngineExecutors(); var forced = new Forced(false)) {
      var server = spy(new KnowledgeServer(executors,
          io.justsearch.indexerworker.WorkerConfig.load(configuration), null,
          io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), lifecycle, null, null,
          configuration));
      forced.install(server);
      if (failAfterWiring) {
        // Wiring runs in order; a disambiguation binding that cannot be wired fails the start
        // after the earlier bindings were applied, and its cleanup rewires the services.
        doAnswer(invocation -> {
          invocation.<java.util.function.Consumer<Object>>getArgument(1).accept(null);
          return null;
        }).when(server).initializeDisambiguationService(any(), any());
      }
      var preparedSuccessors = new CopyOnWriteArrayList<DefaultWorkerAppServices>();
      doAnswer(invocation -> {
        var prepared = (DefaultWorkerAppServices) invocation.callRealMethod();
        preparedSuccessors.add(prepared);
        return prepared;
      }).when(server).prepareServingSuccessor(any());
      server.start();
      forced.attached(server);
      forced.initializer = () -> composeWithReranker(server);
      var fingerprint = KnowledgeServer.class.getDeclaredMethod("expectedIndexFingerprintOrNull");
      fingerprint.setAccessible(true);
      lifecycle.fingerprint = (String) fingerprint.invoke(server);
      lifecycle.source = forced.oldGeneration;
      forced.successorGeneration = forced.manager.startRecordedMigration(
          PreparationWindowLifecycle.OPERATION, "recorded-test", lifecycle.fingerprint,
          lifecycle.source, List.of()).building_generation();
      lifecycle.authorized = true;
      assertTrue(server.beginRecordedBuildingLiveAsync(PreparationWindowLifecycle.OPERATION,
          forced.restarts::incrementAndGet).get(30, TimeUnit.SECONDS), "Green opens live");

      assertTrue(lifecycle.preparing.await(60, TimeUnit.SECONDS),
          "the switch is preparing its successor outside the runtime lock");
      forced.startModels();
      server.deferredModelInit.handle((ignored, failure) -> null).get(60, TimeUnit.SECONDS);
      Map<String, Object> wired;
      try (var serving = server.captureServingView()) {
        assertEquals(forced.oldGenerationPath(), serving.activeGenerationPath(),
            "the start ran entirely while the old generation served");
        wired = queryBindings(serving.services());
      }
      if (!failAfterWiring) assertNotNull(wired.get("searchReranker"), "the start wired a reranker");
      lifecycle.resume.countDown();

      assertTrue(server.awaitMigrationCutoverExitForTests(60, TimeUnit.SECONDS), "the switch ends");
      assertEquals(forced.successorGeneration,
          forced.manager.readStateBestEffort().active_generation(), "the switch completed");
      try (var serving = server.captureServingView()) {
        assertEquals(forced.manager.resolveGenerationPathStrict(forced.successorGeneration),
            serving.activeGenerationPath());
        var published = queryBindings(serving.services());
        assertAll(wired.keySet().stream().map(name -> () -> assertSame(wired.get(name),
            published.get(name), name + " of the published successor is the one the start left")));
        // The successor prepared before the start was abandoned: closed, its projection aborted;
        // the producer it borrowed stays with the published successor and usable.
        var first = preparedSuccessors.getFirst();
        assertTrue(first != serving.services(), "the published successor was prepared again");
        assertTrue(first.producerClosed(), "the abandoned successor is closed");
        assertTrue(lifecycle.firstAborted.get(), "its prepared projection was aborted");
        var producer = (DefaultWorkerAppServices) serving.services();
        assertFalse(producer.producerClosed(), "the published successor's producer is usable");
        assertTrue(producer.ownsNativeProducer(), "the published successor owns the producer");
        if (!failAfterWiring) {
          var view = getDeclared(serving, "captured");
          var query = (QueryRoleSet) getDeclared(view, "queryRoleSet");
          assertNotNull(query, "the successor serves the start's query set");
          assertSame(query.reranker(), published.get("searchReranker"));
        }
      }
    } finally {
      lifecycle.resume.countDown();
      io.justsearch.configuration.resolved.TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  /**
   * P10: a model start that never finishes, with watcher reconciliation debt pending and Green's
   * embeddings pending, so the switch neither reaches promotion nor its deadline. Its own bound
   * (shortened here) fails the switch on the existing failure path.
   */
  @Test
  @Timeout(120)
  void aModelStartThatNeverFinishesCannotHoldTheSwitch() throws Exception {
    var sessions = new QueryPrewarmFixture.SlowGpuSessions(50L);
    var captured = new AtomicReference<KnowledgeServer>();
    // A resolvable embedding identity: Green earns no embedding stamp while the held start
    // provides no embeddings, so the switch keeps deferring at its embedding finalization.
    io.justsearch.indexerworker.embed.EmbeddingFingerprint.setForTesting("p10-embedding");
    try (var forced = new Forced(true)) {
      forced.releaseOnSwitchRetry = false;
      try (var boot = new QueryPrewarmFixture.Boot(tempDir, "p10", sessions, 10_000L, server -> {
        captured.set(server);
        forced.install(server);
        forced.projectionSource(server);
        shortenModelWiringBound(server, 5_000L);
      })) {
        var server = captured.get();
        forced.attached(server);
        forced.initializer = embeddingInitializer(server, sessions,
            tempDir.resolve("p10-models").resolve("onnx").resolve("gte-multilingual-base"));
        forced.migrateLive();
        var root = Files.createDirectories(tempDir.resolve("p10-watched"));
        var debt = new io.justsearch.indexerworker.queue.WatcherRootReconciliation(root.toString(),
            "p10-root-incarnation", "notes");
        var queue = (io.justsearch.indexerworker.queue.SqliteJobQueue) getField(server, "jobQueue");
        assertTrue(queue.putSwitchBuffer(debt.key(),
            io.justsearch.indexerworker.queue.WatcherRootReconciliation.OPERATION, debt.encode()));
        assertTrue(io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps
            .hasWatcherReconciliationDebt(queue), "watcher reconciliation debt is pending");
        forced.startModelsWhenSwitchReached();
        assertTrue(forced.startHeld.await(60, TimeUnit.SECONDS), "the start is held");
        // Green has no embedding stamp the held start could earn: the switch defers at its
        // embedding finalization on every iteration and never reaches promotion.
        var finalization = KnowledgeServer.class.getDeclaredMethod(
            "finalizeEmbeddingRebuildBeforeCutover");
        finalization.setAccessible(true);
        assertFalse((Boolean) finalization.invoke(server),
            "the switch defers at its embedding finalization");

        // A bounded window, twelve times the bound: before the change the switch waits for the
        // start for ever, and this assertion fails when the window closes.
        assertTrue(server.awaitMigrationCutoverExitForTests(60, TimeUnit.SECONDS),
            "the switch ends (FAILED at its 5 s wiring bound) within 60 s");
        var state = forced.manager.readStateBestEffort();
        assertEquals("FAILED", state.migration_state(), "the switch took its failure path");
        assertEquals(forced.oldGeneration, state.active_generation(), "the source stays active");
        assertEquals(forced.successorGeneration, state.building_generation(),
            "the candidate identity is kept for recovery");
        assertTrue(Files.isDirectory(forced.oldGenerationPath()), "the source index is intact");
        assertTrue(queue.listSwitchBufferOpsStrict().stream().anyMatch(op ->
            io.justsearch.indexerworker.queue.WatcherRootReconciliation.OPERATION.equals(op.op())),
            "the journal keeps the reconciliation debt");
        assertFalse(forced.matching("model wiring timeout").isEmpty(), "the warning names it");
        assertTrue(boot.health().getServing(), "the source generation keeps serving");
        assertEquals(0, forced.restarts.get());

        forced.releaseHeld.countDown();
        server.deferredModelInit.handle((ignored, failure) -> null).get(60, TimeUnit.SECONDS);
        try (var serving = server.captureServingView()) {
          assertEquals(forced.oldGenerationPath(), serving.activeGenerationPath());
          serving.encoderSet().acquire().close();
        }
      }
    }
  }

  /**
   * The wiring bound runs only while the switch is otherwise ready (SWITCHING, unpaused): a
   * migration still building, or one an operator paused, outlives an in-flight model start's bound
   * and completes once the start finishes (P5, the existing pause behaviour).
   */
  @org.junit.jupiter.params.ParameterizedTest(name = "paused: {0}")
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void aModelStartInFlightPastTheBoundDoesNotFailAMigrationThatIsNotReadyToSwitch(boolean paused)
      throws Exception {
    try (var forced = new Forced(true)) {
      forced.releaseOnSwitchRetry = false;
      forced.enumerationGate = new CountDownLatch(1);
      var target = plainServer(tempDir);
      shortenModelWiringBound(target, 1_000L);
      var server = forced.boot(target);
      forced.migrateLive();
      if (paused) forced.manager.setMigrationPaused(true, "operator pause (test)");
      forced.startModels();
      assertTrue(forced.startHeld.await(60, TimeUnit.SECONDS), "the start is held");
      // Paused: enumeration finishes; the pause alone keeps the switch from becoming ready.
      if (paused) forced.enumerationGate.countDown();

      // Three times the bound, while the start stays in flight.
      assertFalse(QueryPrewarmFixture.waitFor(java.time.Duration.ofSeconds(3), () ->
          "FAILED".equals(forced.manager.readStateBestEffort().migration_state())
              || !cutoverAlive(server)), "the migration is not failed by the wiring bound");
      var state = forced.manager.readStateBestEffort();
      assertEquals("MIGRATING", state.migration_state());
      assertEquals(paused, Boolean.TRUE.equals(state.migration_paused()), "the pause is kept");
      assertTrue(forced.matching("model wiring timeout").isEmpty());

      forced.releaseHeld.countDown();
      server.deferredModelInit.handle((ignored, failure) -> null).get(60, TimeUnit.SECONDS);
      if (paused) forced.manager.setMigrationPaused(false, null);
      else forced.enumerationGate.countDown();
      assertTrue(server.awaitMigrationCutoverExitForTests(60, TimeUnit.SECONDS), "the switch ends");
      forced.assertSuccessorModelsAndRetirement(server);
    }
  }

  /**
   * A migration requested right after boot, before a boot-time cutover monitor first reads the
   * generation pointer (an order a loaded machine produces), still opens Green live and switches
   * without an Engine restart. The pointer lock is held from boot until the request has created
   * the candidate, so any boot-time monitor reads the pointer only after that.
   */
  @Test
  void aMigrationRequestedBeforeTheBootMonitorFirstReadsStillOpensGreenLive() throws Exception {
    var field = IndexGenerationManager.class.getDeclaredField("STATE_CONTROL");
    field.setAccessible(true);
    var stateControl = (java.util.concurrent.locks.ReentrantLock) field.get(null);
    try (var forced = new Forced(false)) {
      KnowledgeServer server;
      stateControl.lock();
      try {
        server = forced.boot(plainServer(tempDir));
        var started = server.appServices().ingestService().startMigration(
            MigrationStartRequest.newBuilder().setReason("manual")
                .setProjectionSourceIdsPresent(true).addProjectionSourceIds("memory").build(),
            CallContext.none());
        assertTrue(started.getAccepted(), started::getError);
        forced.successorGeneration = started.getBuildingGenerationId();
        var bootMonitor = (Thread) getField(server, "migrationCutoverThread");
        if (bootMonitor != null) {
          assertTrue(QueryPrewarmFixture.waitFor(java.time.Duration.ofSeconds(30),
                  () -> stateControl.hasQueuedThread(bootMonitor) || !bootMonitor.isAlive()),
              "the boot monitor waits for the pointer or has ended");
        }
      } finally {
        stateControl.unlock();
      }
      assertTrue(server.beginUnrecordedBuildingLiveAsync(forced.successorGeneration,
          forced.restarts::incrementAndGet).get(30, TimeUnit.SECONDS), "Green opens live");
      assertTrue(server.awaitMigrationCutoverExitForTests(60, TimeUnit.SECONDS), "the switch ends");
      assertEquals(forced.successorGeneration,
          forced.manager.readStateBestEffort().active_generation(), "Green is promoted");
      assertEquals(0, forced.restarts.get(), "no Engine restart");
    }
  }

  // --- harness ---------------------------------------------------------------------------------

  /** The forced order, the migration it switches, and the Engine log it observes. */
  private static final class Forced implements AutoCloseable {
    private final boolean holdStart;
    final CountDownLatch switchReached = new CountDownLatch(1);
    final CountDownLatch startHeld = new CountDownLatch(1);
    final CountDownLatch promoted = new CountDownLatch(1);
    final CountDownLatch releaseHeld = new CountDownLatch(1);
    final AtomicBoolean switchWaitedForStart = new AtomicBoolean();
    final AtomicBoolean startReleased = new AtomicBoolean();
    final AtomicBoolean promotionInsideHeldStart = new AtomicBoolean();
    final AtomicInteger cutoverAttempts = new AtomicInteger();
    final AtomicInteger restarts = new AtomicInteger();
    final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
    volatile boolean failHeldStart;
    volatile CountDownLatch enumerationGate;
    volatile boolean captureAtSwitch;
    volatile KnowledgeServer.ServingLease issuedAtSwitch;
    volatile boolean releaseOnSwitchRetry = true;
    volatile Runnable initializer;
    KnowledgeServer server;
    IndexGenerationManager manager;
    String oldGeneration;
    String successorGeneration;
    private final Logger serverLog = (Logger) LoggerFactory.getLogger(KnowledgeServer.class);
    private final Logger prewarmLog = (Logger) LoggerFactory.getLogger(QueryEncoderPrewarm.class);
    private final Level prewarmLevel = prewarmLog.getLevel();
    private final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
      @Override protected void append(ILoggingEvent event) {
        events.add(event);
        if (event.getFormattedMessage().contains("Finalizing cutover")
            && cutoverAttempts.incrementAndGet() >= 2 && releaseOnSwitchRetry) {
          releaseHeld.countDown();
        }
      }
    };

    Forced(boolean holdStart) {
      this.holdStart = holdStart;
      appender.start();
      serverLog.addAppender(appender);
      prewarmLog.addAppender(appender);
      prewarmLog.setLevel(Level.DEBUG);
    }

    /** Installs the barrier and the hold point; before start. */
    void install(KnowledgeServer target) {
      doNothing().when(target).startDeferredModelInitialization(any());
      try {
        setField(target, "migrationRestartAction", (Runnable) restarts::incrementAndGet);
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
      target.installMigrationBarrierForTests(transition -> {
        switch (transition.point()) {
          case "migration-before-switching" -> {
            if (captureAtSwitch) issuedAtSwitch = target.captureServingView();
            switchReached.countDown();
            // The switch is requested only once the start holds its wiring leases.
            if (holdStart) switchWaitedForStart.set(startHeld.await(60, TimeUnit.SECONDS));
          }
          case "migration-after-live-activation" -> {
            promoted.countDown();
            releaseHeld.countDown();
          }
          default -> { }
        }
      });
      doAnswer(invocation -> {
        if (holdStart) {
          startHeld.countDown();
          startReleased.set(releaseHeld.await(60, TimeUnit.SECONDS));
          promotionInsideHeldStart.set(promoted.getCount() == 0);
          if (failHeldStart) throw new IllegalStateException("model start failed (test)");
        }
        return invocation.callRealMethod();
      }).when(target).initializeDisambiguationService(any(), any());
    }

    void projectionSource(KnowledgeServer target) {
      target.installProjectionSeedSources(List.of(new ProjectionSeedSource() {
        @Override public String sourceId() { return "memory"; }
        @Override public void enumerate(java.util.function.Consumer<AcceptedProjection> sink) {
          var gate = enumerationGate;
          try {
            if (gate != null && !gate.await(90, TimeUnit.SECONDS)) {
              throw new IllegalStateException("enumeration gate not released");
            }
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
          }
          sink.accept(new AcceptedProjection("memory", "note", 1,
              AcceptedProjection.Kind.UPSERT, "{\"content\":\"revision 1\"}"));
        }
      }));
    }

    KnowledgeServer boot(KnowledgeServer target) throws Exception {
      install(target);
      projectionSource(target);
      target.start();
      attached(target);
      return target;
    }

    void attached(KnowledgeServer target) throws Exception {
      server = target;
      ((CountDownLatch) getField(target, "modelReadyLatch")).countDown();
      manager = (IndexGenerationManager) getField(target, "indexGenerationManager");
      oldGeneration = manager.readStateBestEffort().active_generation();
      if (initializer == null) {
        var initDeferredModels = KnowledgeServer.class.getDeclaredMethod("initDeferredModels");
        initDeferredModels.setAccessible(true);
        initializer = () -> {
          try {
            initDeferredModels.invoke(target);
          } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalStateException(failure);
          }
        };
      }
    }

    void migrateLive() throws Exception {
      var started = server.appServices().ingestService().startMigration(
          MigrationStartRequest.newBuilder().setReason("manual")
              .setProjectionSourceIdsPresent(true).addProjectionSourceIds("memory").build(),
          CallContext.none());
      assertTrue(started.getAccepted(), started::getError);
      successorGeneration = started.getBuildingGenerationId();
      assertTrue(server.beginUnrecordedBuildingLiveAsync(successorGeneration,
          restarts::incrementAndGet).get(30, TimeUnit.SECONDS), "Green opens live");
    }

    void startModels() {
      doCallRealMethod().when(server).startDeferredModelInitialization(any());
      server.startDeferredModelInitialization(initializer);
    }

    void startModelsWhenSwitchReached() throws InterruptedException {
      assertTrue(switchReached.await(60, TimeUnit.SECONDS), "the switch is requested");
      startModels();
    }

    void awaitSwitchAndModels() throws Exception {
      server.deferredModelInit.handle((ignored, failure) -> null).get(60, TimeUnit.SECONDS);
      assertTrue(server.awaitMigrationCutoverExitForTests(90, TimeUnit.SECONDS),
          "the switch completes");
    }

    void assertOrderForced() {
      assertTrue(switchWaitedForStart.get(), "the switch was requested while the start was held");
      assertTrue(startReleased.get(),
          "the start was released by the published successor or by a retried switch");
    }

    Path oldGenerationPath() throws java.io.IOException {
      return manager.resolveGenerationPathStrict(oldGeneration);
    }

    void invokeRetirement() throws Exception {
      var retirement = KnowledgeServer.class.getDeclaredMethod("retryCommittedGenerationRetirement");
      retirement.setAccessible(true);
      retirement.invoke(server);
    }

    void assertOldGenerationRetired(KnowledgeServer target) throws Exception {
      invokeRetirement();
      Path indices = oldGenerationPath().getParent();
      assertAll(
          () -> assertTrue(((List<?>) getField(target, "retiredServingViews")).isEmpty(),
              "the old view is cleaned up"),
          () -> assertNull(manager.readStateBestEffort().previous_generation(),
              "previous_generation is cleared"),
          () -> {
            try (var entries = Files.list(indices)) {
              // Exactly the old generation's canonical directory or a representation of it.
              assertEquals(List.of(), entries.filter(Files::isDirectory)
                  .map(path -> path.getFileName().toString())
                  .filter(name -> representsGeneration(name, oldGeneration)).toList(),
                  "neither the old generation nor a marked-for-deletion copy remains");
            }
          });
    }

    void assertSuccessorModelsAndRetirement(KnowledgeServer target) throws Exception {
      assertSuccessorModelsAndRetirement(target, true);
    }

    /**
     * {@code noRetainedViewError}: a view held through the switch legitimately defers the old
     * runtime's cleanup with that error until it is released; model-set closes must still not fail.
     */
    void assertSuccessorModelsAndRetirement(KnowledgeServer target, boolean noRetainedViewError)
        throws Exception {
      assertEquals(successorGeneration, manager.readStateBestEffort().active_generation());
      assertEquals(0, restarts.get(), "no Engine restart");
      invokeRetirement();
      Path indices = oldGenerationPath().getParent();
      assertAll(
          () -> {
            try (var serving = target.captureServingView()) {
              assertEquals(manager.resolveGenerationPathStrict(successorGeneration),
                  serving.activeGenerationPath());
              EncoderSet index = serving.encoderSet();
              assertNotNull(index, "the successor view has an index model set");
              index.acquire().close();
            }
          },
          () -> {
            try (var serving = target.captureServingView()) {
              var view = getDeclared(serving, "captured");
              var query = (QueryRoleSet) getDeclared(view, "queryRoleSet");
              assertNotNull(query, "the successor view has a query model set");
              query.acquire().close();
            }
          },
          () -> assertTrue(((List<?>) getField(target, "retiredServingViews")).isEmpty(),
              "the old view is cleaned up"),
          () -> assertNull(manager.readStateBestEffort().previous_generation(),
              "previous_generation is cleared"),
          () -> {
            try (var entries = Files.list(indices)) {
              // Exactly the old generation's canonical directory or a representation of it.
              assertEquals(List.of(), entries.filter(Files::isDirectory)
                  .map(path -> path.getFileName().toString())
                  .filter(name -> representsGeneration(name, oldGeneration)).toList(),
                  "neither the old generation nor a marked-for-deletion copy remains");
            }
          },
          () -> assertEquals(List.of(), noRetainedViewError ? matching(RETIRED_OWNS)
              : matching("Encoder-set retirement exceeded its close deadline")),
          () -> assertEquals(List.of(), matching(SET_RETIRING)),
          () -> assertEquals(List.of(), matching(QUERY_SET_RETIRING)));
    }

    /** Events whose message or any cause in their throwable names {@code text}. */
    List<String> matching(String text) {
      return events.stream().filter(event -> {
        if (event.getFormattedMessage().contains(text)) return true;
        for (IThrowableProxy cause = event.getThrowableProxy(); cause != null;
            cause = cause.getCause()) {
          if (String.valueOf(cause.getMessage()).contains(text)) return true;
        }
        return false;
      }).map(event -> event.getThreadName() + ": " + event.getFormattedMessage()
          + (event.getThrowableProxy() == null ? "" : " / " + event.getThrowableProxy().getMessage()))
          .toList();
    }

    @Override public void close() throws Exception {
      startHeld.countDown();
      releaseHeld.countDown();
      promoted.countDown();
      try {
        if (server != null && !server.awaitClosed(0)) closeServer(server);
      } finally {
        serverLog.detachAppender(appender);
        prewarmLog.detachAppender(appender);
        prewarmLog.setLevel(prewarmLevel);
        appender.stop();
      }
    }
  }

  /** The deferred start with the stub embedding encoder; static mocks belong to its thread. */
  private static Runnable embeddingInitializer(KnowledgeServer server,
      QueryPrewarmFixture.SlowGpuSessions sessions, Path model) {
    var assembly = embeddingAssembly(sessions);
    return () -> {
      try (var assembler = mockStatic(OrtSessionAssembler.class);
          var embeddingAssembly = mockStatic(OnnxEmbeddingEncoder.class)) {
        assembler.when(() -> OrtSessionAssembler.buildManager(anyString(), any(), any(), any()))
            .thenReturn(sessions);
        embeddingAssembly.when(() -> OnnxEmbeddingEncoder.buildAssembly(
                same(sessions), eq(model), anyInt(), anyInt(), anyBoolean(), any()))
            .thenReturn(assembly);
        QueryPrewarmFixture.invokeDeferredModelInitialization(server);
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
    };
  }

  /** The deferred start with a stand-in reranker; only the native boundaries are replaced. */
  private static void composeWithReranker(KnowledgeServer server) {
    var handle = mock(io.justsearch.ort.SessionHandle.class);
    when(handle.retirementStatus()).thenReturn(io.justsearch.ort.SessionHandle.RetirementStatus.RETIRED);
    try (var assembler = mockStatic(OrtSessionAssembler.class);
        var rerankerAssembly = mockStatic(io.justsearch.reranker.CrossEncoderReranker.class);
        var inference = org.mockito.Mockito.mockConstruction(
            io.justsearch.reranker.CrossEncoderReranker.class)) {
      assembler.when(() -> OrtSessionAssembler.buildManager(anyString(), any(), any(), any()))
          .thenReturn(handle);
      rerankerAssembly.when(() -> io.justsearch.reranker.CrossEncoderReranker.buildAssembly(
              any(), any(), any(), anyInt()))
          .thenAnswer(call -> new io.justsearch.reranker.RerankerAssembly(call.getArgument(0),
              new io.justsearch.reranker.RerankerShape(512, false),
              mock(io.justsearch.reranker.RerankerTokenizer.class)));
      QueryPrewarmFixture.invokeDeferredModelInitialization(server);
      if (inference.constructed().isEmpty()) {
        throw new IllegalStateException("the start composed no reranker");
      }
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  /** The query services a successor copies from its incumbent when it is prepared. */
  private static Map<String, Object> queryBindings(Object services) throws Exception {
    var bindings = new java.util.LinkedHashMap<String, Object>();
    for (String name : List.of("searchReranker", "citationScorer", "spladeIdfQueryEncoder",
        "gpuDiagnostics", "stageAvailability")) {
      var field = DefaultWorkerAppServices.class.getDeclaredField(name);
      field.setAccessible(true);
      bindings.put(name, field.get(services));
    }
    return bindings;
  }

  /** Test seam for the bound, where it exists; before the change there is no bound to shorten. */
  private static boolean cutoverAlive(KnowledgeServer server) {
    try {
      var cutover = (Thread) getField(server, "migrationCutoverThread");
      return cutover != null && cutover.isAlive();
    } catch (Exception unavailable) {
      return false;
    }
  }

  private static void shortenModelWiringBound(KnowledgeServer server, long millis) {
    try {
      var bound = KnowledgeServer.class.getDeclaredMethod("modelWiringBoundMillis");
      bound.setAccessible(true);
      bound.invoke(org.mockito.Mockito.doReturn(millis).when(server));
    } catch (NoSuchMethodException absent) {
      // The unchanged Engine has no own bound for an in-flight model start.
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException(failure);
    }
  }

  /** A recorded bulk switch whose settings preparation is held once, outside the runtime lock. */
  private static final class PreparationWindowLifecycle implements RecordedIngestionLifecycle {
    static final String OPERATION = "01994180-0000-7000-8000-000000000301";
    final CountDownLatch preparing = new CountDownLatch(1);
    final CountDownLatch resume = new CountDownLatch(1);
    final AtomicInteger preparations = new AtomicInteger();
    final AtomicBoolean firstAborted = new AtomicBoolean();
    volatile String source;
    volatile String fingerprint;
    volatile boolean authorized;
    volatile boolean published;

    @Override public IndexGenerationManager.BootOwnership bootOwnership(
        io.justsearch.indexerworker.queue.JobQueue queue) {
      return !authorized || published ? new IndexGenerationManager.BootOwnership.Native()
          : new IndexGenerationManager.BootOwnership.Recorded(OPERATION, source, "recorded-test",
              fingerprint, true, true, List.of());
    }
    @Override public boolean recordedCutoverReady(String key) { return authorized; }
    @Override public io.justsearch.indexerworker.queue.JobQueue.RecordedClaimDecision
        recordedClaimDecision(String key) {
      return io.justsearch.indexerworker.queue.JobQueue.RecordedClaimDecision.ALLOW;
    }
    @Override public Attachment attach(io.justsearch.indexerworker.queue.JobQueue queue,
        CheckedServingGeneration generation, java.util.function.BooleanSupplier online) {
      return () -> {};
    }
    @Override public IndexGenerationManager.State promoteRecordedGeneration(String key,
        io.justsearch.indexerworker.queue.JobQueue queue, CheckedPromotion promotion)
        throws java.io.IOException {
      var promoted = promotion.promote();
      if (promoted != null) published = true;
      return promoted;
    }
    @Override public PreparedCompositeProjection prepareRecordedGenerationProjection(String key,
        io.justsearch.indexerworker.queue.JobQueue queue) throws java.io.IOException {
      var prepared = RecordedIngestionLifecycle.super.prepareRecordedGenerationProjection(key, queue);
      if (preparations.incrementAndGet() != 1) return prepared;
      preparing.countDown();
      try {
        if (!resume.await(60, TimeUnit.SECONDS)) throw new java.io.IOException("not resumed");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new java.io.IOException(interrupted);
      }
      return new PreparedCompositeProjection() {
        @Override public IndexGenerationManager.State withOwnerLocks(CheckedPromotion promotion)
            throws java.io.IOException {
          return prepared.withOwnerLocks(promotion);
        }
        @Override public CommittedProjection callbacks() { return prepared.callbacks(); }
        @Override public void abortBeforePointer() {
          firstAborted.set(true);
          prepared.abortBeforePointer();
        }
      };
    }
  }

  /** Whether a directory name is {@code generation} itself or a backup or marked copy of it. */
  private static boolean representsGeneration(String name, String generation) {
    try {
      return io.justsearch.adapters.lucene.runtime.IndexOwnership.representation(name).identity()
          .equals(generation);
    } catch (java.io.IOException notAGeneration) {
      return false;
    }
  }

  private static KnowledgeServer bootServer(QueryPrewarmFixture.Boot boot) throws Exception {
    var field = QueryPrewarmFixture.Boot.class.getDeclaredField("server");
    field.setAccessible(true);
    return (KnowledgeServer) field.get(boot);
  }

  private static KnowledgeServer plainServer(Path tempDir) throws Exception {
    var layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");
    return spy(new KnowledgeServer(new TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()), null));
  }

  private static KnowledgeServer.ServingLease serving(KnowledgeServer server) {
    try (var lease = server.captureServingView()) {
      return lease;
    }
  }

  private static EmbeddingAssembly embeddingAssembly(QueryPrewarmFixture.SlowGpuSessions sessions) {
    var tokenizer = mock(HuggingFaceTokenizer.class);
    Encoding encoding = mock(Encoding.class);
    when(encoding.getIds()).thenReturn(new long[] {101, 7592, 102});
    when(encoding.getAttentionMask()).thenReturn(new long[] {1, 1, 1});
    when(encoding.getTypeIds()).thenReturn(new long[] {0, 0, 0});
    when(tokenizer.encode(anyString())).thenReturn(encoding);
    int dimension = QueryPrewarmFixture.DIMENSION;
    var shape = new EmbeddingShape(
        512, false, OnnxEmbeddingEncoder.PoolingStrategy.MEAN_POOL, 4096, dimension);
    var capabilities = new ModelCapabilities(
        ModelCapabilities.PoolingMode.MEAN, 512, dimension, ModelPrecision.FP32, null, null, null,
        Map.of(), List.of());
    return new EmbeddingAssembly(sessions, shape, tokenizer, capabilities);
  }

  private static void closeServer(KnowledgeServer server) throws java.io.IOException {
    for (int attempt = 1; ; attempt++) {
      try {
        server.close();
        return;
      } catch (java.io.IOException failure) {
        // A loaded loop may outlive the product's 5 s join. Retry only its retained shutdown;
        // any other failure (including an additional suppressed failure) must still fail the test.
        if (attempt >= 6
            || !"Application services still own resources".equals(failure.getMessage())
            || failure.getSuppressed().length != 0
            || !(failure.getCause() instanceof java.io.IOException retained)
            || !"Indexing loop still running; resources retained for shutdown retry"
                .equals(retained.getMessage())
            || retained.getCause() != null || retained.getSuppressed().length != 0) {
          throw failure;
        }
      }
    }
  }

  private static CompletableFuture<Void> closeInBackground(KnowledgeServer server) {
    var result = new CompletableFuture<Void>();
    Thread.ofVirtual().start(() -> {
      try { closeServer(server); result.complete(null); }
      catch (Throwable failure) { result.completeExceptionally(failure); }
    });
    return result;
  }

  private static Object getDeclared(Object target, String name) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static Object getField(KnowledgeServer server, String name) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(server);
  }

  private static void setField(KnowledgeServer server, String name, Object value) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(server, value);
  }
}
