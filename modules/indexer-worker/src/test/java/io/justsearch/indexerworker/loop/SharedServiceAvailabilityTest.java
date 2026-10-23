/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.*;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.ipc.StatusRequest;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Shared-service lifecycle matrix, including the verifier D6 real closed-identity reproduction. */
final class SharedServiceAvailabilityTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  enum State {
    WRITER_CLOSED, WRITER_CLOSING, WRITER_DEFERRED, WRITER_CONNECTION_LOST,
    WRITER_DIRECTORY_CLOSED, WRITER_DRAINING, WRITER_BACKPRESSURE,
    WRITER_READ_ONLY, WRITER_DIRECTORY_LOST, WRITER_OPEN_FAILED, WRITER_OPEN_LOCKED,
    IDENTITY_UNWIRED, IDENTITY_CLOSED, IDENTITY_CONNECTION_LOST, IDENTITY_READ_ONLY,
    IDENTITY_SCHEMA_LOST, IDENTITY_FILE_LOST, IDENTITY_OPEN_FAILED, IDENTITY_BUSY,
    IDENTITY_CORRUPT,
    PATH_CLOSED, PATH_CONNECTION_LOST, PATH_READ_ONLY, PATH_SCHEMA_LOST, PATH_OPEN_FAILED,
    EXTRACTOR_CLOSED, EXTRACTION_EXECUTOR_CLOSED, EXTRACTION_SANDBOX_CLOSED,
    EXTRACTION_START_FAILED, EXTRACTION_READER_CLOSED, EXTRACTOR_CLOSING, EXTRACTION_SANDBOX_CLOSING,
    EXTRACTOR_REGISTRY_CLOSED, EXTRACTOR_REGISTRY_EMPTY, EXTRACTOR_REGISTRY_CLOSING,
    EXTRACTOR_POLICY_CLOSED, EXTRACTOR_STRUCTURED_CLOSED
  }

  @org.junit.jupiter.api.Timeout(45)
  @ParameterizedTest(name = "{0}")
  @EnumSource(State.class)
  void sharedServiceStatesDeferHealthyFilesAndRecover(State state) throws Exception {
    try (var h = new Scenario()) {
      h.install(state);
      h.enqueue("capped-backlog-0.txt", "capped-backlog-1.txt");
      h.loop = h.newLoop();
      var status = new WorkerIngestService(h.queue, h.loop, mock(WorkerSignalBus.class),
          IndexingPacing.unthrottled(), temp, temp.resolve("index"), null, null, null, 0L);
      h.loop.start();
      await(() -> h.errors.size() >= 8 && h.paused() || h.rows().stream()
          .anyMatch(row -> row.attempts() > 0 || row.state().equals("DONE")), 25_000);
      var rows = h.rows();
      System.out.println("availability state=" + state + " errors=" + h.errors + " rows=" + rows);
      assertEquals(2, rows.size());
      assertTrue(rows.stream().allMatch(row -> row.attempts() == 0),
          "shared-service unavailability must never charge healthy source files: " + state + rows);
      assertTrue(h.errors.size() >= 8, "the service must refuse four real batches: " + state);
      assertTrue(h.paused(), "outage is paced by the running loop");
      assertTrue(rows.stream().allMatch(row -> row.state().equals("PENDING")
          && "DEFER_WITHOUT_ATTEMPT".equals(row.policy())), "all rows remain durable deferrals");
      long deadline = rows.getFirst().retry();
      assertTrue(deadline >= System.currentTimeMillis() + 6_000
          && deadline <= System.currentTimeMillis() + 8_000,
          "four failed batches reach the eight-second cap without identity successes resetting another service");
      assertTrue(rows.stream().allMatch(row -> row.retry() == deadline));
      var observed = status.indexStatus(StatusRequest.getDefaultInstance(), CallContext.none());
      assertEquals("WAITING", observed.getCore().getState());
      assertEquals(0, observed.getMigration().getPendingReadyJobsCount());
      assertEquals(0, observed.getMigration().getProcessingJobsCount());
      assertEquals(deadline, observed.getFailure().getNextRetryAtMs());
      assertTrue(h.errors.stream().allMatch(SharedServiceAvailabilityTest::typedOutage),
          "every service boundary must expose a typed outage, including its first failure: " + h.errors);
      h.recover();
      await(() -> h.writes.get() == 2, 9_000);
      h.loop.close(); h.loop = null;
      assertTrue(h.rows().stream().allMatch(row -> row.state().equals("DONE") && row.attempts() == 0),
          "all claims complete promptly after recovery");
      h.runtime.commitOps().maybeRefreshBlocking();
      assertTrue(h.runtime.indexCountOps().docCount() >= 2, "healthy documents reached real Lucene");
      System.out.println("availability state=" + state + " recovered=2 attempts=0 deadline=" + deadline);
    }
  }

  private static boolean typedOutage(Throwable failure) {
    return failure instanceof IndexRuntimeIOException || failure instanceof IndexWriterUnavailableException
        || failure.getClass().getSimpleName().equals("IngestionServiceUnavailableException")
        || failure.getClass().getSimpleName().equals("SandboxServiceUnavailableException");
  }

  private record Row(String state, int attempts, long retry, String policy) {}

  private final class Scenario implements AutoCloseable {
    final Path jobs = temp.resolve("jobs.db");
    final Path authority = temp.resolve("authority.db");
    final Path index = temp.resolve("index");
    final SqliteJobQueue queue = new SqliteJobQueue(jobs);
    final AtomicReference<DocumentIdentityStore> identities = new AtomicReference<>();
    final AtomicReference<PathResolutionStore> paths = new AtomicReference<>(PathResolutionStore.NOOP);
    final AtomicReference<IndexingCoordinator> writer = new AtomicReference<>();
    final AtomicReference<TimeboxedContentExtractor> extractor = new AtomicReference<>();
    final CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();
    final AtomicInteger writes = new AtomicInteger();
    final TestEngineExecutors extractionExecutors = new TestEngineExecutors();
    final List<AutoCloseable> owned = new ArrayList<>();
    RunningRuntime runtime;
    LuceneRuntime extraRuntime;
    SqliteDocumentIdentityStore identity;
    SqlitePathResolutionStore pathStore;
    Connection blocker;
    Runnable releaseLease;
    Thread closing;
    Set<PosixFilePermission> permissions;
    Path movedIndex;
    IndexingLoop loop;
    State fault;
    final AtomicReference<Throwable> closeError = new AtomicReference<>();

    Scenario() throws Exception {
      queue.open();
      // Tiny schemas, no scale fixtures: authority can fail independently of durable queue writes.
      try (var seed = new SqliteJobQueue(authority)) { seed.open(); }
      identity = new SqliteDocumentIdentityStore(authority); owned.add(identity); identities.set(identity);
      runtime = builder().open(); writer.set(runtime.indexingCoordinator());
      extractor.set(mockContent());
    }

    LuceneRuntimeBuilder builder() { return builder(index); }

    LuceneRuntimeBuilder builder(Path location) {
      var config = new ResolvedConfigBuilder();
      config.putDefault("index.writer.max_buffered_docs", "2");
      config.putDefault("index.nrt.mode", "on_demand");
      config.putDefault("index.nrt.background_reopen_ms", "600000");
      return IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(location)
          .withConfig(config.build()).withExecutorRegistrations(testLuceneExecutors());
    }

    void install(State state) throws Exception {
      fault = state;
      switch (state) {
        case WRITER_CLOSED -> runtime.close();
        case WRITER_CLOSING -> {
          releaseLease = runtime.taskLifetime().retain();
          closing = new Thread(runtime::close, "availability-test-close"); closing.start();
          await(() -> !runtime.isAcceptingWrites(), 1_000);
        }
        case WRITER_DEFERRED -> {
          runtime.close();
          extraRuntime = builder().openDeferred();
          writer.set((IndexingCoordinator) field(session(extraRuntime), "indexingCoordinator"));
        }
        case WRITER_CONNECTION_LOST -> luceneWriter().rollback();
        case WRITER_DIRECTORY_CLOSED -> ((org.apache.lucene.store.Directory)
            accessor(field(session(runtime), "snapshot"), "directory")).close();
        case WRITER_DRAINING -> setField(session(runtime), "draining", true);
        case WRITER_BACKPRESSURE -> setField(session(runtime), "maxQueueDepth", 0L);
        case WRITER_READ_ONLY -> {
          permissions = Files.getPosixFilePermissions(index);
          Files.setPosixFilePermissions(index, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
          assertFalse(Files.isWritable(index), "filesystem fault must be real");
        }
        case WRITER_DIRECTORY_LOST -> {
          movedIndex = temp.resolve("moved-index"); Files.move(index, movedIndex);
        }
        case WRITER_OPEN_FAILED, WRITER_OPEN_LOCKED -> {
          Path requested = index;
          if (state == State.WRITER_OPEN_FAILED) {
            Path nonDirectory = temp.resolve("not-a-directory"); Files.writeString(nonDirectory, "file");
            requested = nonDirectory.resolve("index");
          }
          Path opening = requested;
          var unavailable = mock(IndexingCoordinator.class);
          doAnswer(call -> {
            try (var candidate = builder(opening).open()) {
              candidate.indexingCoordinator().indexSingle(call.getArgument(0));
            }
            return null;
          }).when(unavailable).indexSingle(any());
          doAnswer(call -> {
            try (var candidate = builder(opening).open()) { candidate.indexingCoordinator().probeIngestion(); }
            return null;
          }).when(unavailable).probeIngestion();
          writer.set(unavailable);
        }
        case IDENTITY_UNWIRED -> identities.set(DocumentIdentityStore.UNAVAILABLE);
        // D6 exactly: open production store on jobs.db, close it, leave receiver wired to live loop.
        case IDENTITY_CLOSED -> {
          identity.close(); identity = new SqliteDocumentIdentityStore(jobs); owned.add(identity);
          identity.close(); identities.set(identity);
        }
        case IDENTITY_CONNECTION_LOST -> connection(identity).close();
        case IDENTITY_READ_ONLY -> {
          identity.close(); identity = new SqliteDocumentIdentityStore(Path.of("file:" + authority + "?mode=ro"));
          owned.add(identity); identities.set(identity);
        }
        case IDENTITY_SCHEMA_LOST -> execute(connection(identity), "DROP TABLE document_identity");
        case IDENTITY_FILE_LOST -> Files.delete(authority);
        case IDENTITY_OPEN_FAILED -> identities.set(openingIdentity(temp.resolve("missing-parent/identity.db")));
        case IDENTITY_BUSY -> {
          execute(connection(identity), "PRAGMA busy_timeout=0");
          blocker = DriverManager.getConnection("jdbc:sqlite:" + authority);
          execute(blocker, "BEGIN IMMEDIATE");
        }
        case IDENTITY_CORRUPT -> {
          Path bad = temp.resolve("corrupt.db"); Files.writeString(bad, "broken sqlite".repeat(100));
          identities.set(openingIdentity(bad));
        }
        case PATH_CLOSED, PATH_CONNECTION_LOST, PATH_READ_ONLY, PATH_SCHEMA_LOST, PATH_OPEN_FAILED -> {
          pathStore = new SqlitePathResolutionStore(state == State.PATH_READ_ONLY
              ? Path.of("file:" + authority + "?mode=ro") : authority);
          owned.add(pathStore); paths.set(pathStore);
          switch (state) {
            case PATH_CLOSED -> pathStore.close();
            case PATH_CONNECTION_LOST -> connection(pathStore).close();
            case PATH_SCHEMA_LOST -> execute(connection(pathStore), "DROP TABLE path_resolution");
            case PATH_OPEN_FAILED -> {
              var unavailable = mock(PathResolutionStore.class);
              doAnswer(call -> { new SqlitePathResolutionStore(temp.resolve("absent/path.db")); return null; })
                  .when(unavailable).record(anyString(), anyString(), anyLong());
              paths.set(unavailable);
            }
            default -> { }
          }
        }
        case EXTRACTOR_REGISTRY_CLOSED, EXTRACTOR_REGISTRY_EMPTY, EXTRACTOR_REGISTRY_CLOSING,
            EXTRACTOR_POLICY_CLOSED, EXTRACTOR_STRUCTURED_CLOSED -> {
          ContentExtractorProvider provider;
          if (state == State.EXTRACTOR_STRUCTURED_CLOSED) {
            var structured = new StructuredContentExtractor();
            structured.close(); provider = structured;
          } else {
            var tika = new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool);
            owned.add(tika);
            if (state == State.EXTRACTOR_POLICY_CLOSED) { tika.close(); provider = tika; }
            else {
              var registry = ExtractorContributionRegistry.withCoreTika(tika);
              owned.add(registry); provider = registry;
              if (state == State.EXTRACTOR_REGISTRY_CLOSED) {
                Path healthy = Path.of(System.getProperty("justsearch.test.outageCorpus", temp.resolve("sources").toString()))
                    .resolve("capped-backlog-0.txt");
                Files.createDirectories(healthy.getParent());
                if (!Files.exists(healthy)) Files.writeString(healthy, "healthy readable content");
                assertTrue(registry.extractArtifact(healthy).result().content().contains("healthy readable content"));
                registry.close();
                var signal = assertThrows(IllegalStateException.class, () -> registry.extractArtifact(healthy));
                assertEquals("Extractor registry is closed", signal.getMessage());
              } else if (state == State.EXTRACTOR_REGISTRY_EMPTY) registry.uninstall("core.tika");
              else {
                registry.uninstall("core.tika");
                var entered = new java.util.concurrent.CountDownLatch(1);
                var retired = new java.util.concurrent.CountDownLatch(1);
                ContentExtractorProvider blocked = mock(ContentExtractorProvider.class);
                when(blocked.extract(any())).thenAnswer(call -> {
                  entered.countDown();
                  assertTrue(retired.await(10, java.util.concurrent.TimeUnit.SECONDS));
                  throw new java.io.IOException("provider retired during extraction");
                });
                registry.install(ExtractorContributionRegistry.ExtractorContribution.catchAll("core.retiring",
                    ExtractorContributionRegistry.ExtractorTrust.CORE, blocked));
                closing = new Thread(() -> {
                  try {
                    assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
                    registry.close();
                  } catch (Throwable failure) { closeError.set(failure); }
                  finally { retired.countDown(); }
                }, "availability-registry-close");
                closing.start();
              }
            }
          }
          var registration = extractionExecutors.register(new EngineExecutorSpec("availability-registry",
              EngineExecutorSpec.Kind.BACKGROUND, EngineExecutorSpec.Mode.PLATFORM, 1, 8, 2));
          var service = new TimeboxedContentExtractor(registration, provider, Duration.ofSeconds(5), null);
          owned.add(service); extractor.set(service);
        }
        case EXTRACTOR_CLOSED, EXTRACTION_EXECUTOR_CLOSED, EXTRACTION_SANDBOX_CLOSED,
            EXTRACTION_START_FAILED, EXTRACTION_READER_CLOSED, EXTRACTOR_CLOSING,
            EXTRACTION_SANDBOX_CLOSING -> {
          var registration = extractionExecutors.register(new EngineExecutorSpec("availability-extraction",
              EngineExecutorSpec.Kind.BACKGROUND, EngineExecutorSpec.Mode.PLATFORM, 1, 8, 2));
          ExtractionSandbox sandbox;
          var entered = new java.util.concurrent.CountDownLatch(1);
          PersistentExtractionSandbox closingPool = null;
          Path ready = temp.resolve("closing-child-ready");
          if (state == State.EXTRACTION_SANDBOX_CLOSED || state == State.EXTRACTION_START_FAILED
              || state == State.EXTRACTION_READER_CLOSED || state == State.EXTRACTION_SANDBOX_CLOSING) {
            var reader = extractionExecutors.register(new EngineExecutorSpec("availability-reader",
                EngineExecutorSpec.Kind.BACKGROUND, EngineExecutorSpec.Mode.PLATFORM, 1, 8, 2));
            List<String> command = state == State.EXTRACTION_SANDBOX_CLOSING
                ? List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Djustsearch.test.closingReady=" + ready, "-cp", System.getProperty("java.class.path"),
                    BlockingChild.class.getName())
                : state == State.EXTRACTION_READER_CLOSED
                ? List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), ExtractionSandboxChild.class.getName())
                : List.of(temp.resolve("missing-child-executable").toString());
            var persistent = new PersistentExtractionSandbox(reader, command,
                TikaExtractionPolicy.defaults(), null, Duration.ofSeconds(5), 1, 10, null);
            if (state == State.EXTRACTION_SANDBOX_CLOSED) persistent.close();
            if (state == State.EXTRACTION_READER_CLOSED) reader.close();
            sandbox = persistent;
            if (state == State.EXTRACTION_SANDBOX_CLOSING) closingPool = persistent;
          } else if (state == State.EXTRACTOR_CLOSING) {
            sandbox = file -> {
              entered.countDown();
              try { new java.util.concurrent.CountDownLatch(1).await(); }
              catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("extractor closed during in-flight request");
              }
              return artifact();
            };
          } else {
            sandbox = file -> artifact();
          }
          var service = new TimeboxedContentExtractor(registration, sandbox, Duration.ofSeconds(5), null);
          owned.add(service); extractor.set(service);
          if (state == State.EXTRACTOR_CLOSED) service.close();
          if (state == State.EXTRACTION_EXECUTOR_CLOSED) registration.close();
          if (state == State.EXTRACTOR_CLOSING || state == State.EXTRACTION_SANDBOX_CLOSING) {
            PersistentExtractionSandbox pool = closingPool;
            closing = new Thread(() -> {
              try {
                if (pool == null) assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
                else await(() -> Files.exists(ready), 10_000);
                if (pool == null) service.close(); else pool.close();
              } catch (Throwable failure) { closeError.set(failure); }
            }, "availability-extraction-close");
            closing.start();
          }
        }
      }
    }

    DocumentIdentityStore openingIdentity(Path location) {
      var proxy = mock(DocumentIdentityStore.class);
      when(proxy.resolve(anyString(), anyLong())).thenAnswer(call -> {
        try (var service = new SqliteDocumentIdentityStore(location)) {
          return service.resolve(call.getArgument(0), call.getArgument(1));
        }
      });
      return proxy;
    }

    org.apache.lucene.index.IndexWriter luceneWriter() throws Exception {
      return (org.apache.lucene.index.IndexWriter) accessor(field(session(runtime), "snapshot"), "writer");
    }

    void recover() throws Exception {
      if (permissions != null) { Files.setPosixFilePermissions(index, permissions); permissions = null; }
      if (blocker != null) { blocker.close(); blocker = null; }
      if (releaseLease != null) { releaseLease.run(); releaseLease = null; closing.join(5_000); }
      if (closing != null) {
        closing.join(5_000);
        assertFalse(closing.isAlive(), "the real close owner must finish");
        assertNull(closeError.get(), "close failure: " + closeError.get());
      }
      if (extraRuntime != null) { extraRuntime.close(); extraRuntime = null; }
      runtime.close();
      if (movedIndex != null) { Files.move(movedIndex, index); movedIndex = null; }
      runtime = builder().open(); writer.set(runtime.indexingCoordinator());
      writes.set(0);
      // Recreate only missing authority schema; keep existing identities for ordinary lifecycle recovery.
      identity.close();
      if (pathStore != null) pathStore.close();
      try (var seed = new SqliteJobQueue(authority)) { seed.open(); }
      try (var repair = DriverManager.getConnection("jdbc:sqlite:" + authority)) {
        execute(repair, SqliteSchema.CREATE_DOCUMENT_IDENTITY_TABLE);
        execute(repair, SqliteSchema.CREATE_PATH_RESOLUTION_TABLE);
        boolean deletedAt = false;
        try (var columns = repair.createStatement(); var info = columns.executeQuery("PRAGMA table_info(document_identity)")) {
          while (info.next()) deletedAt |= "deleted_at".equals(info.getString("name"));
        }
        if (!deletedAt) execute(repair, "ALTER TABLE document_identity ADD COLUMN deleted_at INTEGER DEFAULT NULL");
      }
      identity = new SqliteDocumentIdentityStore(authority); owned.add(identity); identities.set(identity);
      if (pathStore != null) {
        pathStore = new SqlitePathResolutionStore(authority); owned.add(pathStore); paths.set(pathStore);
      }
      if (fault.name().startsWith("EXTRACT")) {
        var registration = extractionExecutors.register(new EngineExecutorSpec("recovered-extraction",
            EngineExecutorSpec.Kind.BACKGROUND, EngineExecutorSpec.Mode.PLATFORM, 1, 8, 2));
        ExtractionSandbox healthy = file -> artifact();
        if (fault == State.EXTRACTION_SANDBOX_CLOSED || fault == State.EXTRACTION_START_FAILED
            || fault == State.EXTRACTION_READER_CLOSED || fault == State.EXTRACTION_SANDBOX_CLOSING) {
          var reader = extractionExecutors.register(new EngineExecutorSpec("recovered-reader",
              EngineExecutorSpec.Kind.BACKGROUND, EngineExecutorSpec.Mode.PLATFORM, 1, 8, 2));
          healthy = new PersistentExtractionSandbox(reader,
              List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-cp", System.getProperty("java.class.path"), ExtractionSandboxChild.class.getName()),
              TikaExtractionPolicy.defaults(), null, Duration.ofSeconds(5), 1, 10, null);
        }
        var service = new TimeboxedContentExtractor(registration, healthy, Duration.ofSeconds(5), null);
        owned.add(service);
        if (healthy instanceof PersistentExtractionSandbox) {
          // Publish a ready replacement: cold parser startup is not outage-recovery latency.
          Path corpus = Path.of(System.getProperty("justsearch.test.outageCorpus", temp.resolve("sources").toString()));
          service.extractArtifact(corpus.resolve("capped-backlog-0.txt"));
        }
        extractor.set(service);
      }

    }

    IndexingLoop newLoop() throws Exception {
      var routedWriter = mock(IndexingCoordinator.class);
      doAnswer(call -> {
        try {
          io.justsearch.indexing.api.IndexDocument document = call.getArgument(0);
          writer.get().indexSingle(document);
          if (!Boolean.parseBoolean(String.valueOf(document.fields().get(SchemaFields.IS_CHUNK)))) {
            writes.incrementAndGet();
          }
        }
        catch (RuntimeException failure) { errors.add(failure); throw failure; }
        return null;
      }).when(routedWriter).indexSingle(any());
      doAnswer(call -> { writer.get().probeIngestion(); return null; }).when(routedWriter).probeIngestion();
      var routedIdentities = mock(DocumentIdentityStore.class);
      when(routedIdentities.resolve(anyString(), anyLong())).thenAnswer(call -> {
        try { return identities.get().resolve(call.getArgument(0), call.getArgument(1)); }
        catch (RuntimeException failure) { errors.add(failure); throw failure; }
      });
      doAnswer(call -> { identities.get().probe(); return null; }).when(routedIdentities).probe();
      var routedPaths = mock(PathResolutionStore.class);
      doAnswer(call -> {
        try { paths.get().record(call.getArgument(0), call.getArgument(1), call.getArgument(2)); }
        catch (RuntimeException failure) { errors.add(failure); throw failure; }
        return null;
      }).when(routedPaths).record(anyString(), anyString(), anyLong());
      doAnswer(call -> { paths.get().probe(); return null; }).when(routedPaths).probe();
      var routedContent = mock(TimeboxedContentExtractor.class);
      when(routedContent.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
      when(routedContent.extractArtifact(any())).thenAnswer(call -> {
        try { return extractor.get().extractArtifact(call.getArgument(0)); }
        catch (java.io.IOException | ContentExtractor.ExtractionException | RuntimeException failure) { errors.add(failure); throw failure; }
      });
      when(routedContent.probeArtifact(any())).thenAnswer(call -> extractor.get().probeArtifact(call.getArgument(0)));
      var commits = mock(CommitOps.class);
      doAnswer(call -> { runtime.commitOps().commitAndTrack(call.getArgument(0)); return null; })
          .when(commits).commitAndTrack(any());
      return new IndexingLoop(null, null, queue, routedWriter, commits,
          mock(DocumentFieldOps.class), mock(IndexCountOps.class), () -> null,
          mock(WorkerSignalBus.class), IndexingPacing.unthrottled(), null, null, null, null,
          routedContent, null, new IndexingLoopOptions(false, routedPaths, routedIdentities, null, null, null));
    }

    TimeboxedContentExtractor mockContent() throws Exception {
      var content = mock(TimeboxedContentExtractor.class);
      when(content.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
      when(content.extractArtifact(any())).thenReturn(artifact());
      return content;
    }

    ExtractionArtifact artifact() {
      String text = "Healthy readable source content for shared service recovery. ".repeat(600);
      return ExtractionArtifact.full(new ContentExtractor.ExtractionResult(text, null, "text/plain"),
          TikaExtractionPolicy.defaults(), "availability", false);
    }

    void enqueue(String... names) throws Exception {
      Path corpus = Path.of(System.getProperty("justsearch.test.outageCorpus", temp.resolve("sources").toString()));
      Files.createDirectories(corpus);
      for (String name : names) {
        Path file = corpus.resolve(name);
        if (!Files.exists(file)) Files.writeString(file, "healthy readable content");
        queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(corpus)));
      }
    }

    List<Row> rows() throws Exception {
      try (var db = DriverManager.getConnection("jdbc:sqlite:" + jobs);
          var query = db.createStatement();
          var rows = query.executeQuery("SELECT state,attempts,retry_after,last_retry_policy FROM jobs ORDER BY path")) {
        var result = new ArrayList<Row>();
        while (rows.next()) result.add(new Row(rows.getString(1), rows.getInt(2), rows.getLong(3), rows.getString(4)));
        return result;
      }
    }

    boolean paused() throws Exception {
      Thread owner = (Thread) field(loop, "loopThread");
      return Arrays.stream(owner.getStackTrace()).anyMatch(frame -> frame.getMethodName().equals("sleepAfterError"));
    }

    @Override public void close() throws Exception {
      if (loop != null) loop.close();
      if (permissions != null) Files.setPosixFilePermissions(index, permissions);
      if (releaseLease != null) releaseLease.run();
      if (closing != null) closing.join(5_000);
      if (blocker != null) blocker.close();
      if (extraRuntime != null) extraRuntime.close();
      runtime.close();
      for (var service : owned.reversed()) service.close();
      extractionExecutors.close(); queue.close();
    }
  }

  public static final class BlockingChild {
    public static void main(String[] args) throws Exception {
      if (System.in.read() >= 0) {
        Files.writeString(Path.of(System.getProperty("justsearch.test.closingReady")), "ready");
        while (System.in.read() >= 0) { /* close owns the EOF */ }
      }
    }
  }

  private static Object session(LuceneRuntime runtime) throws Exception { return field(runtime, "session"); }
  private static Connection connection(Object store) throws Exception { return (Connection) field(store, "connection"); }
  private static Object field(Object receiver, String name) throws Exception {
    var field = receiver.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(receiver);
  }
  private static void setField(Object receiver, String name, Object value) throws Exception {
    var field = receiver.getClass().getDeclaredField(name); field.setAccessible(true); field.set(receiver, value);
  }
  private static Object accessor(Object receiver, String name) throws Exception {
    var method = receiver.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(receiver);
  }
  private static void execute(Connection connection, String sql) throws Exception {
    try (var statement = connection.createStatement()) { statement.execute(sql); }
  }
  private static void await(Check condition, long timeoutMs) throws Exception {
    long deadline = System.nanoTime() + timeoutMs * 1_000_000;
    while (!condition.ready() && System.nanoTime() < deadline) Thread.sleep(10);
    assertTrue(condition.ready(), "running loop did not reach the requested service state");
  }
  @FunctionalInterface private interface Check { boolean ready() throws Exception; }
}
