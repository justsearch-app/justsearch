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
  void shutdownWhileTheSwitchWaitsForAHeldModelStartIsBounded() throws Exception {
    try (var forced = new Forced(true)) {
      forced.releaseOnSwitchRetry = false;
      var server = forced.boot(plainServer(tempDir));
      forced.migrateLive();
      forced.startModelsWhenSwitchReached();
      assertTrue(forced.startHeld.await(60, TimeUnit.SECONDS), "the start is held");
      var closed = closeInBackground(server);
      assertFalse(closed.isDone(), "close joins the held initializer");
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
        Path model = tempDir.resolve("m4-models").resolve("onnx").resolve("gte-multilingual-base");
        var assembly = embeddingAssembly(sessions);
        forced.initializer = () -> {
          // Mockito static mocks belong to the thread that composes the models.
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
              // Generation representations are directories; sibling control files are not.
              assertEquals(List.of(), entries.filter(Files::isDirectory)
                  .map(path -> path.getFileName().toString())
                  .filter(name -> name.contains(oldGeneration)).toList(),
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
              // Generation representations are directories; sibling control files are not.
              assertEquals(List.of(), entries.filter(Files::isDirectory)
                  .map(path -> path.getFileName().toString())
                  .filter(name -> name.contains(oldGeneration)).toList(),
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
        if (server != null && !server.awaitClosed(0)) server.close();
      } finally {
        serverLog.detachAppender(appender);
        prewarmLog.detachAppender(appender);
        prewarmLog.setLevel(prewarmLevel);
        appender.stop();
      }
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

  private static CompletableFuture<Void> closeInBackground(KnowledgeServer server) {
    var result = new CompletableFuture<Void>();
    Thread.ofVirtual().start(() -> {
      try { server.close(); result.complete(null); }
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
