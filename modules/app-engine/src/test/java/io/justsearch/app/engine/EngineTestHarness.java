/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.server.MigrationTransitionBarrier;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.StatusResponse;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;

/**
 * Lane F stage A item A12 — the in-process replacement for the chaos tier's three-part rig
 * ({@code WorkerProcessManager} + {@code MmfTestHarness} + {@code GrpcTestClient}).
 *
 * <p>Every one of those three existed to reach a <em>second process</em>: spawn it, discover the
 * port it published through the memory-mapped signal file, open a channel to that port. Since item
 * A9 the index half binds no port, so all three were unreachable by construction. This class is
 * what the surviving half of their job collapses to — publish a config, build an {@link EngineRoot},
 * call {@link EngineRoot#start} and drive the returned {@link KnowledgeClient}.
 *
 * <p><b>{@link #restart()} is a TEST ACTION, not a translation of anything production does.</b>
 * This javadoc previously called it "the honest translation of 'the worker restarted'", on the
 * grounds that the migration paths ended in a {@code restartWorkerCallback} wired to
 * {@code KnowledgeServer#initiateShutdown}. The stage-A checkpoint found that framing was the
 * problem: that callback set a flag and counted down a latch no production code read, so it
 * restarted nothing and reopened nothing. Calling {@code restart()} in a test right after a cutover
 * therefore did not simulate what the product does — it supplied, by hand, the one step the product
 * had silently stopped doing, and every assertion downstream passed on evidence the test itself had
 * manufactured.
 *
 * <p>That callback is deleted. What remains true is narrower and must be stated at each call site:
 * closing and re-opening this harness on the same data directory re-reads {@code state.json} the
 * way a genuinely restarted process would. So {@code restart()} is the right tool for asserting
 * <em>"after a restart, X"</em> — and the wrong tool for asserting that an operation took effect
 * without one. A test that wants the second thing must assert against the LIVE engine, before any
 * restart. See {@code EngineMigrationLifecycleTest}, which now does both explicitly.
 */
final class EngineTestHarness implements AutoCloseable {

  /** Base call deadline, matching {@code KnowledgeServerConfig.deadlineMs()}'s dev default. */
  private static final long DEADLINE_MS = 30_000L;

  private static final int BATCH_SIZE = 5_000;

  private final Path dataDir;
  private final Path indexBase;
  private final Map<String, String> extraConfig;
  private final MigrationTransitionBarrier.Hook migrationHook;

  private EngineRoot root;
  private KnowledgeClient client;
  private GpuSchedulingGauge gauge;

  private EngineTestHarness(
      Path dataDir,
      Path indexBase,
      Map<String, String> extraConfig,
      MigrationTransitionBarrier.Hook migrationHook) {
    this.dataDir = dataDir;
    this.indexBase = indexBase;
    this.extraConfig = new LinkedHashMap<>(extraConfig);
    this.migrationHook = migrationHook;
  }

  /** Publishes a config rooted at {@code dataDir} and starts the Engine's index half. */
  static EngineTestHarness start(Path dataDir) throws Exception {
    return start(dataDir, dataDir.resolve("index"), Map.of());
  }

  /** As {@link #start(Path)}, with a checked hook installed before the index half starts. */
  static EngineTestHarness start(Path dataDir, MigrationTransitionBarrier.Hook migrationHook)
      throws Exception {
    return start(dataDir, dataDir.resolve("index"), Map.of(), migrationHook);
  }

  /** As {@link #start(Path)}, with extra resolved-config defaults (recovery policy, pacing, …). */
  static EngineTestHarness start(Path dataDir, Map<String, String> extraConfig) throws Exception {
    return start(dataDir, dataDir.resolve("index"), extraConfig);
  }

  /** As {@link #start(Path)}, with the index base path pointed somewhere other than the default. */
  static EngineTestHarness start(Path dataDir, Path indexBase, Map<String, String> extraConfig)
      throws Exception {
    return start(dataDir, indexBase, extraConfig, MigrationTransitionBarrier.NO_HOOK);
  }

  private static EngineTestHarness start(
      Path dataDir,
      Path indexBase,
      Map<String, String> extraConfig,
      MigrationTransitionBarrier.Hook migrationHook)
      throws Exception {
    EngineTestHarness harness =
        new EngineTestHarness(
            dataDir,
            indexBase,
            extraConfig,
            java.util.Objects.requireNonNull(migrationHook, "migrationHook"));
    harness.open();
    return harness;
  }

  /**
   * Publishes the resolved config globally. Package-private and static so a test that needs a
   * SECOND index owner in this JVM (the index-base-path lock) can share the same config.
   */
  static void publishConfig(Path dataDir, Path indexBase, Map<String, String> extraConfig)
      throws java.io.IOException {
    Files.createDirectories(dataDir);
    Files.createDirectories(indexBase);
    ResolvedConfigBuilder builder =
        new ResolvedConfigBuilder()
            .contributeBaseSources()
            // The corpus belongs to this fixture, including after a Green migration/restart.
            .put("justsearch.ssot.path", ResolvedConfigBuilder.ORDINAL_JVM_ARG, "test_fixture",
                "isolated_ssot", extraConfig.getOrDefault("justsearch.ssot.path",
                    dataDir.resolve("SSOT").toAbsolutePath().toString()))
            .putDefault("justsearch.data.dir", dataDir.toAbsolutePath().toString())
            .putDefault("justsearch.index.base_path", indexBase.toAbsolutePath().toString());
    for (Map.Entry<String, String> entry : extraConfig.entrySet()) {
      builder = builder.putDefault(entry.getKey(), entry.getValue());
    }
    ConfigStore.setGlobal(new ConfigStore(builder.build()));
  }

  private void open() throws Exception {
    publishConfig(dataDir, indexBase, extraConfig);
    gauge = new GpuSchedulingGauge();
    if (migrationHook == MigrationTransitionBarrier.NO_HOOK) {
      root = new EngineRoot(
          org.mockito.Mockito.mock(io.justsearch.app.api.operations.OperationStore.class),
          org.mockito.Mockito.mock(io.justsearch.app.api.operations.OperationAttemptRunner.class),
          DEADLINE_MS, BATCH_SIZE);
      client = root.start(gauge, IpcTelemetry.noop());
      return;
    }
    EngineRoot.ServerFactory production = productionServerFactory();
    EngineRoot.ServerFactory hooked =
        new EngineRoot.ServerFactory() {
          @Override
          public io.justsearch.configuration.resolved.ResolvedConfig captureConfiguration() {
            return production.captureConfiguration();
          }

          @Override
          public KnowledgeServer create(
              GpuSchedulingGauge scheduling,
              io.justsearch.core.execution.EngineExecutorRegistry executors,
              io.justsearch.indexerworker.server.RecordedIngestionLifecycle ingestion,
              io.justsearch.core.component.ComponentHandle indexComponent,
              io.justsearch.core.component.ComponentHandle encoderComponent) {
            return installMigrationHook(
                production.create(
                    scheduling, executors, ingestion, indexComponent, encoderComponent));
          }

          @Override
          public KnowledgeServer create(
              GpuSchedulingGauge scheduling,
              io.justsearch.core.execution.EngineExecutorRegistry executors,
              io.justsearch.indexerworker.server.RecordedIngestionLifecycle ingestion,
              io.justsearch.core.component.ComponentHandle indexComponent,
              io.justsearch.core.component.ComponentHandle encoderComponent,
              io.justsearch.configuration.resolved.ResolvedConfig startupConfiguration) {
            return installMigrationHook(
                production.create(
                    scheduling,
                    executors,
                    ingestion,
                    indexComponent,
                    encoderComponent,
                    startupConfiguration));
          }
        };
    root = new EngineRoot(
        org.mockito.Mockito.mock(io.justsearch.app.api.operations.OperationStore.class),
        org.mockito.Mockito.mock(io.justsearch.app.api.operations.OperationAttemptRunner.class),
        hooked, DEADLINE_MS, BATCH_SIZE);
    client = root.start(gauge, IpcTelemetry.noop());
  }

  private KnowledgeServer installMigrationHook(KnowledgeServer server) {
    try {
      Method install = KnowledgeServer.class.getDeclaredMethod(
          "installMigrationBarrierForTests", MigrationTransitionBarrier.Hook.class);
      install.setAccessible(true);
      install.invoke(server, migrationHook);
      return server;
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("Cannot install the pre-start migration hook", unwrap(failure));
    }
  }

  private static EngineRoot.ServerFactory productionServerFactory() {
    try {
      Method factory = EngineRoot.class.getDeclaredMethod("serverFactory",
          io.justsearch.app.api.runtime.ManagedChildRegistry.class, Supplier.class);
      factory.setAccessible(true);
      Supplier<ConfigStore> authority = ConfigStore::global;
      return (EngineRoot.ServerFactory) factory.invoke(
          null, io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), authority);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("Cannot decorate the production Engine server factory",
          unwrap(failure));
    }
  }

  private static Throwable unwrap(ReflectiveOperationException failure) {
    return failure instanceof InvocationTargetException invocation && invocation.getCause() != null
        ? invocation.getCause() : failure;
  }

  /**
   * Closes and re-opens the Engine on the same data directory — what "the worker restarted" means
   * when there is only one process. Every generation pointer, switch buffer and job queue is on
   * disk, so the reopened Engine sees exactly what a respawned process saw.
   */
  void restart() throws Exception {
    close();
    open();
  }

  KnowledgeClient client() {
    return client;
  }

  /** Restart fixtures require the published Green owner, not merely the accepted async start. */
  boolean awaitLiveGreen(long timeoutMs) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      try (var view = captureServingView()) {
        if (!view.searchRuntime().openedIndexPath().equals(view.ingestRuntime().openedIndexPath())) {
          return true;
        }
      }
      Thread.sleep(50);
    }
    return false;
  }

  /** Hold the actual published Worker view across a cutover in lifetime tests. */
  KnowledgeServer.ServingLease captureServingView() throws ReflectiveOperationException {
    Field serverField = EngineRoot.class.getDeclaredField("server");
    serverField.setAccessible(true);
    return ((KnowledgeServer) serverField.get(root)).captureServingView();
  }

  Path dataDir() {
    return dataDir;
  }

  Path indexBase() {
    return indexBase;
  }

  StatusResponse status() {
    // Observation must not create the foreground contention these helpers are waiting on.
    return client.getStatus(TestEngineContexts.BACKGROUND);
  }

  /** The worker's coarse lifecycle state ({@code IDLE}, {@code INDEXING}, {@code PAUSED}, …). */
  String workerState() {
    return status().getCore().getState();
  }

  /**
   * The chaos tier's {@code GrpcTestClient.awaitIndexing} (GrpcTestClient.java:410-434), verbatim
   * in its condition: the queue has drained AND the index holds at least the expected count.
   */
  boolean awaitIndexed(long expectedDocCount, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      try {
        StatusResponse status = status();
        if (status.getCore().getQueueDepth() == 0
            && status.getCore().getDocCount() >= expectedDocCount) {
          return true;
        }
      } catch (RuntimeException stillSettling) {
        // A status call can fail while the index half swaps a writer; the loop re-reads.
      }
      Thread.sleep(200);
    }
    return false;
  }

  /** True once {@code marker} is findable. Polls, because the searcher refreshes asynchronously. */
  boolean awaitSearchable(String marker, long timeoutMs) throws InterruptedException {
    return awaitSearchCount(marker, timeoutMs, count -> count > 0);
  }

  /** True once {@code marker} is NOT findable — the delete/prune direction. */
  boolean awaitNotSearchable(String marker, long timeoutMs) throws InterruptedException {
    return awaitSearchCount(marker, timeoutMs, count -> count == 0);
  }

  /** Independent NRT read: neither the exact parent nor any of its chunks may survive deletion. */
  boolean awaitDocumentAbsent(String docId, long timeoutMs) throws Exception {
    return awaitIndexAbsent(documentAndChunks(docId), timeoutMs);
  }

  /** Long enough for several chunks; every chunk retains the fixture's single-token marker. */
  static String chunkedContent(String marker) {
    return ("Chunk deletion witness " + marker + " carries searchable source text. "
        + "Each paragraph must survive indexing until the parent is deleted or replaced.\n\n")
        .repeat(100);
  }

  /** Independent read of the mutation target, requiring old-marker chunks before their removal. */
  boolean awaitIngestChunks(String docId, String marker, boolean requireGreen, long timeoutMs)
      throws Exception {
    Query chunks = new BooleanQuery.Builder()
        .add(new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, docId)), BooleanClause.Occur.FILTER)
        .add(new TermQuery(new Term(SchemaFields.CHUNK_CONTENT, marker.toLowerCase(Locale.ROOT))),
            BooleanClause.Occur.MUST)
        .build();
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      try (var view = captureServingView()) {
        boolean green = !view.ingestRuntime().openedIndexPath()
            .equals(view.searchRuntime().openedIndexPath());
        if ((!requireGreen || green)
            && view.ingestRuntime().readPathOps().search(chunks, 1, Set.of(), null, null).totalHits()
                >= 2) {
          return true;
        }
      }
      Thread.sleep(100);
    }
    return false;
  }

  /** Replacement keeps the parent id, but must retire the old text in parent and chunk rows. */
  boolean awaitIndexedMarkerAbsent(String docId, String marker, long timeoutMs) throws Exception {
    Query oldText = new BooleanQuery.Builder()
        .add(new TermQuery(new Term(SchemaFields.CONTENT, marker)), BooleanClause.Occur.SHOULD)
        .add(new TermQuery(new Term(SchemaFields.CHUNK_CONTENT, marker)), BooleanClause.Occur.SHOULD)
        .build();
    return awaitIndexAbsent(new BooleanQuery.Builder()
        .add(documentAndChunks(docId), BooleanClause.Occur.FILTER)
        .add(oldText, BooleanClause.Occur.MUST)
        .build(), timeoutMs);
  }

  private static Query documentAndChunks(String docId) {
    return new BooleanQuery.Builder()
        .add(new TermQuery(new Term(SchemaFields.DOC_ID, docId)), BooleanClause.Occur.SHOULD)
        .add(new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, docId)), BooleanClause.Occur.SHOULD)
        .build();
  }

  private boolean awaitIndexAbsent(Query query, long timeoutMs) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      try (var view = captureServingView()) {
        if (view.searchRuntime().readPathOps().search(query, 1, Set.of(), null, null).totalHits()
            == 0) {
          return true;
        }
      }
      Thread.sleep(100);
    }
    return false;
  }

  static void requireExecutedSearch(SearchResponse response) {
    if (!response.hasSearchTrace() || response.getSearchTrace().getDecisionKind().isBlank()
        || "blocked".equals(response.getSearchTrace().getDecisionKind())
        || "empty_query".equals(response.getSearchTrace().getDecisionKind())) {
      throw new IllegalStateException("Search did not execute: " + response.getSearchTrace());
    }
  }

  private boolean awaitSearchCount(
      String marker, long timeoutMs, java.util.function.IntPredicate satisfied)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      try {
        SearchResponse response = client.search(marker, 10, TestEngineContexts.FOREGROUND);
        requireExecutedSearch(response);
        if (satisfied.test(response.getResultsCount())) {
          return true;
        }
      } catch (RuntimeException stillSettling) {
        // Same: a search during a generation swap can fail; the loop re-reads.
      }
      Thread.sleep(250);
    }
    return false;
  }

  @Override
  public void close() {
    if (root != null) {
      root.close();
    }
    root = null;
    client = null;
    gauge = null;
  }
}
