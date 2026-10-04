package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import io.justsearch.indexing.runtime.CommitMetadataValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.MMapDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommitOpsTest extends LuceneExecutorTestBase {

  @TempDir Path tempDir;

  private static IndexSchema schemaWith(
      java.util.function.Supplier<CommitMetadataSource> sourceSupplier,
      CommitMetadataValidator validator) {
    return new IndexSchema(
        new FieldMapper(FieldCatalogDef.forTesting(768)),
        new io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry(),
        sourceSupplier,
        validator,
        null);
  }

  // -- buildMetadataSnapshot tests --

  @Test
  void buildMetadataSnapshotReturnsImmutableCopy() {
    RuntimeSession session =
        new RuntimeSession(
            schemaWith(() -> () -> new HashMap<>(Map.of("key", "value")), metadata -> {}));
    CommitOps ops = new CommitOps(session, LuceneRuntimeTypes.BuildState.COMPLETE);
    Map<String, Object> snapshot = ops.buildMetadataSnapshot();
    assertEquals("value", snapshot.get("key"));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.put("extra", "fail"));
  }

  @Test
  void buildMetadataSnapshotThrowsOnNullSource() {
    RuntimeSession session = new RuntimeSession(schemaWith(() -> null, metadata -> {}));
    CommitOps ops = new CommitOps(session, LuceneRuntimeTypes.BuildState.COMPLETE);
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, ops::buildMetadataSnapshot);
    assertTrue(ex.getMessage().contains("null CommitMetadataSource"));
  }

  @Test
  void buildMetadataSnapshotThrowsOnNullMap() {
    RuntimeSession session = new RuntimeSession(schemaWith(() -> () -> null, metadata -> {}));
    CommitOps ops = new CommitOps(session, LuceneRuntimeTypes.BuildState.COMPLETE);
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, ops::buildMetadataSnapshot);
    assertTrue(ex.getMessage().contains("null metadata map"));
  }

  // -- commit tests --

  @Test
  void commitWithMetadataEnabledStampsRequiredFields() throws IOException {
    AtomicInteger validatorCalls = new AtomicInteger();
    CommitMetadataSource source =
        () ->
            Map.of(
                "index_fingerprint", "test-1.0",
                "schema_fp", "fp",
                "boosts_fp", "afp",
                "field_catalog_hash", "fch",
                "synonyms_hash", "sh");
    CommitMetadataValidator validator = metadata -> validatorCalls.incrementAndGet();

    try (MMapDirectory dir = new MMapDirectory(tempDir);
        IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig())) {
      RuntimeSession session = new RuntimeSession(schemaWith(() -> source, validator));
      session.snapshot = new LifecycleSnapshot(dir, writer, null, tempDir, false, null);
      session.commitMetadataEnabled = true;
      CommitOps ops = new CommitOps(session, LuceneRuntimeTypes.BuildState.BUILDING);

      long elapsed = ops.commit(null).elapsedMs();
      assertTrue(elapsed >= 0, "elapsed should be non-negative");
      assertEquals(1, validatorCalls.get(), "validator should be called once");

      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        Map<String, String> userData = reader.getIndexCommit().getUserData();
        assertNotNull(userData.get("build_state"));
        assertEquals("BUILDING", userData.get("build_state"));
        assertNotNull(userData.get("commit_id"));
        assertNotNull(userData.get("commit_time"));
        assertEquals("test-1.0", userData.get("index_fingerprint"));
      }
    }
  }

  @Test
  void commitWithMetadataDisabledSkipsStamping() throws IOException {
    AtomicInteger validatorCalls = new AtomicInteger();
    CommitMetadataValidator validator = metadata -> validatorCalls.incrementAndGet();

    try (MMapDirectory dir = new MMapDirectory(tempDir);
        IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig())) {
      RuntimeSession session =
          new RuntimeSession(schemaWith(() -> () -> Map.of("ignored", "data"), validator));
      session.snapshot = new LifecycleSnapshot(dir, writer, null, tempDir, false, null);
      session.commitMetadataEnabled = false;
      CommitOps ops = new CommitOps(session, LuceneRuntimeTypes.BuildState.COMPLETE);

      long elapsed = ops.commit(null).elapsedMs();
      assertTrue(elapsed >= 0);
      assertEquals(0, validatorCalls.get(), "validator should not be called when disabled");

      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        Map<String, String> userData = reader.getIndexCommit().getUserData();
        assertTrue(userData.isEmpty(), "expected no commit metadata when disabled");
      }
    }
  }

  // -- CommitCompletedListener tests (tempdoc 516 Slice 3 substrate; W2.3) --

  @Test
  void commitCompletedListenerFiresAfterSuccessfulCommitWithReason() throws IOException {
    AtomicInteger callCount = new AtomicInteger();
    AtomicReference<CommitReason> received =
        new AtomicReference<>();
    try (MMapDirectory dir = new MMapDirectory(tempDir);
        IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig())) {
      RuntimeSession session = new RuntimeSession(schemaWith(() -> () -> Map.of(), metadata -> {}));
      session.snapshot = new LifecycleSnapshot(dir, writer, null, tempDir, false, null);
      CommitOps ops = new CommitOps(session, LuceneRuntimeTypes.BuildState.COMPLETE);
      ops.setCommitCompletedListener(
          reason -> {
            callCount.incrementAndGet();
            received.set(reason);
          });

      ops.commitAndTrack(CommitReason.INDEXING_LOOP_IDLE);
      assertEquals(1, callCount.get(), "listener fires exactly once per successful commit");
      assertEquals(CommitReason.INDEXING_LOOP_IDLE, received.get(), "reason propagates to listener");

      ops.commitAndTrack(CommitReason.INDEXING_LOOP_BUFFER);
      assertEquals(2, callCount.get(), "listener fires again on second commit");
      assertEquals(CommitReason.INDEXING_LOOP_BUFFER, received.get(), "second-call reason propagates");
    }
  }

  @Test
  void commitCompletedListenerNotFiredIfCommitFails() {
    AtomicInteger callCount = new AtomicInteger();
    // No snapshot wired — commit() throws IllegalStateException before reaching the listener.
    RuntimeSession session = new RuntimeSession(schemaWith(() -> () -> Map.of(), metadata -> {}));
    CommitOps ops = new CommitOps(session, LuceneRuntimeTypes.BuildState.COMPLETE);
    ops.setCommitCompletedListener(reason -> callCount.incrementAndGet());
    session.pendingDocs.set(3L);

    assertThrows(RuntimeException.class, () -> ops.commitAndTrack(CommitReason.INDEXING_LOOP_IDLE));
    assertEquals(3L, session.pendingDocs.get(), "failed commits must retain pending signals");
    assertEquals(0, callCount.get(),
        "listener must NOT fire when commit() throws — caller should not see a 'completed' event for a failed commit");
  }

  @Test
  void commitCompletedListenerSwallowsListenerExceptionWithoutFailingCommit() throws IOException {
    try (MMapDirectory dir = new MMapDirectory(tempDir);
        IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig())) {
      RuntimeSession session = new RuntimeSession(schemaWith(() -> () -> Map.of(), metadata -> {}));
      session.snapshot = new LifecycleSnapshot(dir, writer, null, tempDir, false, null);
      CommitOps ops = new CommitOps(session, LuceneRuntimeTypes.BuildState.COMPLETE);
      ops.setCommitCompletedListener(
          reason -> {
            throw new RuntimeException("listener boom");
          });

      // The commit succeeded (no exception); the listener's throw is caught + logged.
      assertDoesNotThrow(() -> ops.commitAndTrack(CommitReason.INDEXING_LOOP_IDLE),
          "a misbehaving listener cannot fail a commit that has already succeeded");
    }
  }

  @Test
  void commitCompletedListenerNullClearsRegistration() throws IOException {
    AtomicInteger callCount = new AtomicInteger();
    try (MMapDirectory dir = new MMapDirectory(tempDir);
        IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig())) {
      RuntimeSession session = new RuntimeSession(schemaWith(() -> () -> Map.of(), metadata -> {}));
      session.snapshot = new LifecycleSnapshot(dir, writer, null, tempDir, false, null);
      CommitOps ops = new CommitOps(session, LuceneRuntimeTypes.BuildState.COMPLETE);
      ops.setCommitCompletedListener(reason -> callCount.incrementAndGet());
      ops.commitAndTrack(CommitReason.INDEXING_LOOP_IDLE);
      assertEquals(1, callCount.get());

      ops.setCommitCompletedListener(null); // clear
      ops.commitAndTrack(CommitReason.INDEXING_LOOP_IDLE);
      assertEquals(1, callCount.get(), "null clears the listener — subsequent commits don't fire it");
    }
  }

  @Test
  void commitOnClosedWriterThrowsAlreadyClosedException() throws IOException {
    try (MMapDirectory dir = new MMapDirectory(tempDir)) {
      IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig());
      RuntimeSession session =
          new RuntimeSession(schemaWith(() -> () -> Map.of("index_fingerprint", "v1"), metadata -> {}));
      session.snapshot = new LifecycleSnapshot(dir, writer, null, tempDir, false, null);
      session.commitMetadataEnabled = true;
      CommitOps ops = new CommitOps(session, LuceneRuntimeTypes.BuildState.COMPLETE);

      writer.close(); // close writer before commit

      // AlreadyClosedException is a RuntimeException (not IOException), so it propagates
      // unwrapped. In production, the facade's ensureStarted()/guardWritable() prevents
      // reaching a closed writer.
      assertThrows(RuntimeException.class, () -> ops.commit(null));
    }
  }

  // -- commit accounting tests --

  @Test
  void coordinatorRmwAfterPrimaryCommitIsCommittedByTimer() throws Exception {
    try (var runtime = openAccountingRuntime()) {
      runtime.indexingCoordinator().indexSingle(accountingDoc("one"));
      runtime.commitOps().commitAndTrack(CommitReason.INDEXING_LOOP_BUFFER);
      assertEquals(0L, runtime.session().pendingDocs.get());

      assertTrue(runtime.indexingCoordinator().updateDocument("one", Map.of(SchemaFields.TITLE, "enriched")));
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals("enriched", runtime.documentFieldOps().getDocumentField("one", SchemaFields.TITLE));
      assertEquals("before", committedField(runtime, "one", SchemaFields.TITLE));
      assertEquals(1L, runtime.session().pendingDocs.get(), "the production RMW must signal its write");

      commitPendingByTimer(runtime);
      assertEquals("enriched", committedField(runtime, "one", SchemaFields.TITLE));
      assertEquals(0L, runtime.session().pendingDocs.get());
    }
  }

  @Test
  void coordinatorBatchAndRenameAccountEachSuccessfulRmwAndIgnoreMissingDocuments() throws Exception {
    try (var runtime = openAccountingRuntime()) {
      runtime.indexingCoordinator().indexSingle(accountingDoc("one"));
      runtime.indexingCoordinator().indexSingle(accountingDoc("two"));
      runtime.commitOps().commitAndTrack(CommitReason.INDEXING_LOOP_BUFFER);
      assertFalse(runtime.indexingCoordinator().updateDocument("missing", Map.of(SchemaFields.TITLE, "ignored")));
      assertFalse(runtime.indexingCoordinator().updateDocument("one", Map.of()));
      assertEquals(0L, runtime.session().pendingDocs.get());

      var result = runtime.indexingCoordinator().updateDocumentsBatch(List.of(
          Map.entry("one", Map.of(SchemaFields.TITLE, "first")),
          Map.entry("missing", Map.of(SchemaFields.TITLE, "ignored")),
          Map.entry("two", Map.of(SchemaFields.TITLE, "second"))));
      assertEquals(2, result.updatedCount());
      assertEquals(1, result.notFoundCount());
      assertEquals(2L, runtime.session().pendingDocs.get());
      runtime.commitOps().commitAndTrack(CommitReason.INDEXING_LOOP_IDLE);

      assertEquals(1, runtime.indexingCoordinator().updateDocumentPaths("one", "renamed"));
      assertEquals(1L, runtime.session().pendingDocs.get(), "rename shares the RMW accounting path");
      assertEquals(0, runtime.indexingCoordinator().updateDocumentPaths("missing", "ignored"));
      assertEquals(1L, runtime.session().pendingDocs.get());
      commitPendingByTimer(runtime);
      assertEquals("renamed", committedField(runtime, "renamed", SchemaFields.PATH));
    }
  }

  @Test
  void successfulRmwBeforePartialBatchFailureRemainsPendingAndBecomesDurable() throws Exception {
    try (var runtime = openAccountingRuntime()) {
      runtime.indexingCoordinator().indexSingle(accountingDoc("one"));
      runtime.indexingCoordinator().indexSingle(accountingDoc("two"));
      runtime.commitOps().commitAndTrack(CommitReason.INDEXING_LOOP_BUFFER);

      var failure = assertThrows(IndexRuntimeIOException.class,
          () -> runtime.indexingCoordinator().updateDocumentsBatch(List.of(
              Map.entry("one", Map.of(SchemaFields.TITLE, "applied before failure")),
              Map.entry("two", Map.of(SchemaFields.EMBEDDING_STATUS, "COMPLETED")))));
      assertTrue(failure.getMessage().contains("status_without_artifact"));
      assertEquals(1L, runtime.session().pendingDocs.get(), "successful prefix must survive batch failure");
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals("applied before failure", runtime.documentFieldOps().getDocumentField("one", SchemaFields.TITLE));
      assertEquals("before", runtime.documentFieldOps().getDocumentField("two", SchemaFields.TITLE));
      assertEquals("before", committedField(runtime, "one", SchemaFields.TITLE));

      commitPendingByTimer(runtime);
      assertEquals("applied before failure", committedField(runtime, "one", SchemaFields.TITLE));
      assertEquals("before", committedField(runtime, "two", SchemaFields.TITLE));
    }
  }

  private RunningRuntime openAccountingRuntime() {
    var config = new io.justsearch.configuration.resolved.ResolvedConfigBuilder()
        .put("index.commit.timer_interval_ms", 500, "jvm_arg", "test", "1")
        .put("index.commit.meta.enabled", 500, "jvm_arg", "test", "false")
        .build();
    var runtime = schemaWith(() -> () -> Map.of(), metadata -> {}).atPath(tempDir)
        .withConfig(config).withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();
    return runtime;
  }

  private static IndexDocument accountingDoc(String id) {
    return new IndexDocument(Map.of(
        SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, id + "#0", SchemaFields.PATH, id,
        SchemaFields.CONTENT, "body", SchemaFields.TITLE, "before"));
  }

  private static String committedField(RunningRuntime runtime, String id, String field) throws IOException {
    try (var reader = DirectoryReader.open(runtime.session().snapshot.directory())) {
      var docs = new IndexSearcher(reader).search(new TermQuery(new Term(SchemaFields.DOC_ID, id)), 1);
      assertEquals(1, docs.scoreDocs.length);
      return reader.storedFields().document(docs.scoreDocs[0].doc).get(field);
    }
  }

  private static void commitPendingByTimer(RunningRuntime runtime) throws InterruptedException {
    CountDownLatch committed = new CountDownLatch(1);
    runtime.commitOps().setCommitCompletedListener(reason -> {
      if (reason == CommitReason.TIMER) committed.countDown();
    });
    try {
      runtime.commitOps().startCommitTimer();
      assertTrue(committed.await(5, TimeUnit.SECONDS), "timer must commit without another primary batch");
    } finally {
      runtime.commitOps().stopCommitTimer();
    }
  }

  @Test
  void overlappingCommitsRetireEachPendingSignalOnlyOnce() throws Exception {
    CountDownLatch firstPublished = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondMetadataBuilt = new CountDownLatch(1);
    AtomicInteger metadataCalls = new AtomicInteger();
    RuntimeSession session = new RuntimeSession(schemaWith(() -> () -> Map.of(), metadata -> {
      if (metadataCalls.incrementAndGet() == 2) secondMetadataBuilt.countDown();
    }));
    session.commitMetadataEnabled = true;
    AtomicReference<IndexWriter> currentWriter = new AtomicReference<>();
    AtomicBoolean injectWrite = new AtomicBoolean();
    try (var dir = new FilterDirectory(new MMapDirectory(tempDir)) {
      @Override
      public void syncMetaData() throws IOException {
        super.syncMetaData();
        if (injectWrite.get() && DirectoryReader.indexExists(this)
            && injectWrite.compareAndSet(true, false)) {
          currentWriter.get().addDocument(new Document()); // B is outside the first commit.
          session.pendingDocs.incrementAndGet();
          firstPublished.countDown();
          try {
            if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
              throw new IOException("test did not release first commit");
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
          }
        }
      }
    }; var writer = new IndexWriter(dir, new IndexWriterConfig())) {
      currentWriter.set(writer);
      session.snapshot = new LifecycleSnapshot(dir, writer, null, tempDir, false, null);
      writer.addDocument(new Document()); // A
      session.pendingDocs.incrementAndGet();
      injectWrite.set(true);
      CommitOps ops = new CommitOps(session, LuceneRuntimeTypes.BuildState.COMPLETE);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Runnable commit = () -> {
        try {
          ops.commitAndTrack(CommitReason.INDEXING_LOOP_IDLE);
        } catch (Throwable e) {
          failure.compareAndSet(null, e);
        }
      };
      Thread first = new Thread(commit, "first-accounting-commit");
      Thread second = new Thread(commit, "second-accounting-commit");
      try {
        first.start();
        assertTrue(firstPublished.await(5, TimeUnit.SECONDS));
        assertEquals(2L, session.pendingDocs.get());
        second.start();
        assertTrue(secondMetadataBuilt.await(5, TimeUnit.SECONDS));
        // Both the commit monitor and Lucene's writer monitor are held by the first caller.
        // Without commit serialization, the second caller snapshots 2 before blocking in Lucene;
        // the two callers then subtract 1 and 2 from the same total and leave a negative counter.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (second.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
          Thread.onSpinWait();
        }
        assertEquals(Thread.State.BLOCKED, second.getState());
      } finally {
        releaseFirst.countDown();
        first.join(TimeUnit.SECONDS.toMillis(5));
        second.join(TimeUnit.SECONDS.toMillis(5));
      }
      assertFalse(first.isAlive());
      assertFalse(second.isAlive());
      assertNull(failure.get());
      assertEquals(2L, session.commitCount.get());
      assertEquals(0L, session.pendingDocs.get(), "each commit must retire only its own snapshot");
      try (var reader = DirectoryReader.open(dir)) {
        assertEquals(2, reader.numDocs(), "the second commit must cover B");
      }
    }
  }

  @Test
  void postCommitWriteRemainsPendingAndNextTimerTickMakesItDurable() throws Exception {
    RuntimeSession session = new RuntimeSession(
        schemaWith(() -> () -> Map.of(), metadata -> {}), testLuceneExecutors());
    session.resolvedConfig = new io.justsearch.configuration.resolved.ResolvedConfigBuilder()
        .put("index.commit.timer_interval_ms", 500, "jvm_arg", "test", "1")
        .build();
    AtomicReference<IndexWriter> currentWriter = new AtomicReference<>();
    AtomicBoolean injectWrite = new AtomicBoolean();
    // Lucene has published and synced the commit before this hook writes B, but CommitOps has
    // not yet retired A's pending signal. A disk reader below proves B is outside that commit.
    try (var dir = new FilterDirectory(new MMapDirectory(tempDir)) {
      @Override
      public void syncMetaData() throws IOException {
        super.syncMetaData();
        // prepareCommit also syncs metadata before segments_N exists; inject only after publish.
        if (injectWrite.get() && DirectoryReader.indexExists(this)
            && injectWrite.compareAndSet(true, false)) {
          currentWriter.get().addDocument(new Document());
          session.pendingDocs.incrementAndGet();
        }
      }
    }; var writer = new IndexWriter(dir, new IndexWriterConfig())) {
      currentWriter.set(writer);
      session.snapshot = new LifecycleSnapshot(dir, writer, null, tempDir, false, null);
      writer.addDocument(new Document()); // A
      session.pendingDocs.incrementAndGet();
      injectWrite.set(true);
      CommitOps ops = new CommitOps(session, LuceneRuntimeTypes.BuildState.COMPLETE);
      CountDownLatch firstTick = new CountDownLatch(1);
      CountDownLatch releaseFirstTick = new CountDownLatch(1);
      CountDownLatch secondTick = new CountDownLatch(1);
      AtomicInteger commits = new AtomicInteger();
      AtomicLong pendingAfterFirst = new AtomicLong(-1L);
      ops.setCommitCompletedListener(reason -> {
        if (commits.incrementAndGet() == 1) {
          pendingAfterFirst.set(session.pendingDocs.get());
          firstTick.countDown();
          try {
            if (!releaseFirstTick.await(5, TimeUnit.SECONDS)) {
              throw new IllegalStateException("test did not release first timer tick");
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
        } else {
          secondTick.countDown();
        }
      });
      try {
        ops.startCommitTimer();
        assertTrue(firstTick.await(5, TimeUnit.SECONDS), "first timer commit must finish");
        assertFalse(injectWrite.get(), "the durability-boundary hook must write B");
        try (var reader = DirectoryReader.open(dir)) {
          assertEquals(1, reader.numDocs(), "first commit covers only A");
        }
        assertTrue(writer.hasUncommittedChanges(), "B must still need a covering commit");
        assertEquals(1L, pendingAfterFirst.get(), "post-commit B must remain pending");
        releaseFirstTick.countDown();
        assertTrue(secondTick.await(5, TimeUnit.SECONDS), "a later timer tick must commit B");
        try (var reader = DirectoryReader.open(dir)) {
          assertEquals(2, reader.numDocs(), "B must become durable without any further writes");
        }
        assertEquals(0L, session.pendingDocs.get());
      } finally {
        releaseFirstTick.countDown();
        ops.stopCommitTimer();
      }
    }
  }

  // -- refresh lag tests --

  @Test
  void refreshLagDropsAfterRefresh() throws Exception {
    var meta = new SsotCommitMetadataSource();
    var val = new JsonSchemaCommitMetadataValidator();
    Path dir = Files.createTempDirectory("lucene-lag");
    var r = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), meta, val).atPath(dir).withExecutorRegistrations(testLuceneExecutors()).open();
    r.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(SchemaFields.DOC_ID, "lag-1", SchemaFields.DOC_UID, "lag-1#0")));
    r.commitOps().commitAndTrack();
    long lagBefore = r.commitOps().refreshLagMs();
    assertTrue(lagBefore > 0, "lag should be non-zero after commit before refresh");
    r.commitOps().maybeRefresh();
    long lagAfter = r.commitOps().refreshLagMs();
    assertTrue(lagAfter >= 0);
    // After explicit refresh, lag should not increase and typically goes to zero or near-zero.
    assertTrue(lagAfter <= lagBefore);
    r.close();
  }

  // -- Tempdoc 885 tracked item: the safety-net commit timer's period is configurable ------------
  // It was a hardcoded 10 s that fires whenever pendingDocs > 0, which is why the live window's
  // commit-cadence arm could not work: deferring the indexing loop's own commits just handed this
  // timer more work (16 -> 46). Asserting on the SCHEDULED delay, not on the resolver, is what
  // makes these discriminate — the value has to reach scheduleAtFixedRate.

  private static long scheduledInitialDelayMs(CommitOps ops) throws Exception {
    var field = CommitOps.class.getDeclaredField("commitTimerFuture");
    field.setAccessible(true);
    var future = (ScheduledFuture<?>) field.get(ops);
    assertNotNull(future, "the timer must actually have been scheduled");
    return future.getDelay(TimeUnit.MILLISECONDS);
  }

  private CommitOps opsWithIndexConfig(String key, String value) {
    RuntimeSession session =
        new RuntimeSession(schemaWith(() -> () -> new HashMap<>(Map.of("k", "v")), m -> {}),
            testLuceneExecutors());
    var builder = io.justsearch.configuration.resolved.ResolvedConfig.builder();
    if (key != null) {
      builder.put(key, 500, "jvm_arg", key, value);
    }
    session.resolvedConfig = builder.build();
    return new CommitOps(session, LuceneRuntimeTypes.BuildState.COMPLETE);
  }

  @Test
  void commitTimerUsesTheConfiguredInterval() throws Exception {
    CommitOps ops = opsWithIndexConfig("index.commit.timer_interval_ms", "45000");
    ops.startCommitTimer();
    try {
      assertTrue(
          scheduledInitialDelayMs(ops) > 20_000L,
          "the configured 45 s must reach the scheduler; the hardcoded 10 s would land near 10000");
    } finally {
      ops.stopCommitTimer();
    }
  }

  @Test
  void commitTimerDefaultsToTenSecondsAndRefusesANonPositiveInterval() throws Exception {
    CommitOps unset = opsWithIndexConfig(null, null);
    unset.startCommitTimer();
    try {
      long delay = scheduledInitialDelayMs(unset);
      assertTrue(delay > 5_000L && delay <= 10_000L, "unchanged default behaviour, got " + delay);
    } finally {
      unset.stopCommitTimer();
    }

    // A zero period would spin the commit thread at a fixed rate; the knob must not be able to.
    CommitOps zero = opsWithIndexConfig("index.commit.timer_interval_ms", "0");
    zero.startCommitTimer();
    try {
      long delay = scheduledInitialDelayMs(zero);
      assertTrue(delay > 5_000L && delay <= 10_000L, "must fall back, got " + delay);
    } finally {
      zero.stopCommitTimer();
    }
  }

  @Test
  void stopCommitTimerWaitsForAnInterruptedCallbackBeforeClearingAndAllowsReuse() throws Exception {
    try (MMapDirectory dir = new MMapDirectory(tempDir);
        IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig())) {
      RuntimeSession session =
          new RuntimeSession(
              schemaWith(() -> () -> Map.of(), metadata -> {}), testLuceneExecutors());
      session.snapshot = new LifecycleSnapshot(dir, writer, null, tempDir, false, null);
      session.resolvedConfig =
          new io.justsearch.configuration.resolved.ResolvedConfigBuilder()
              .put(
                  "index.commit.timer_interval_ms",
                  500,
                  "jvm_arg",
                  "index.commit.timer_interval_ms",
                  "1")
              .build();
      session.pendingDocs.set(1L);
      CommitOps ops = new CommitOps(session, LuceneRuntimeTypes.BuildState.COMPLETE);
      CountDownLatch callbackStarted = new CountDownLatch(1);
      CountDownLatch releaseCallback = new CountDownLatch(1);
      CountDownLatch callbackInterrupted = new CountDownLatch(1);
      ops.setCommitCompletedListener(
          reason -> {
            callbackStarted.countDown();
            boolean interrupted = false;
            while (true) {
              try {
                releaseCallback.await();
                if (interrupted) Thread.currentThread().interrupt();
                return;
              } catch (InterruptedException ignored) {
                interrupted = true;
                callbackInterrupted.countDown();
              }
            }
          });

      Thread stopper = null;
      try {
        ops.startCommitTimer();
        assertTrue(callbackStarted.await(5, TimeUnit.SECONDS), "the real timer callback must run");
        ScheduledFuture<?> future = timerFuture(ops);
        ScheduledExecutorService executor = timerExecutor(ops);

        assertThrows(IllegalStateException.class,
            () -> ops.stopCommitTimerUntil(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)));
        assertSame(future, timerFuture(ops), "a timed-out callback must remain owned");
        assertSame(executor, timerExecutor(ops));
        assertFalse(executor.isTerminated());
        assertTrue(writer.isOpen());

        AtomicBoolean stopCompleted = new AtomicBoolean();
        AtomicBoolean stopRestoredInterrupt = new AtomicBoolean();
        stopper =
            new Thread(
                () -> {
                  try {
                    ops.stopCommitTimer();
                  } finally {
                    stopRestoredInterrupt.set(Thread.currentThread().isInterrupted());
                    stopCompleted.set(true);
                  }
                },
                "commit-timer-stop-test");
        stopper.start();
        awaitShutdown(executor);
        stopper.interrupt();

        assertTrue(
            callbackInterrupted.await(1, TimeUnit.SECONDS),
            "shutdownNow must interrupt the running timer callback");
        stopper.join(100);
        assertTrue(stopper.isAlive(), "stop must still wait for the interrupted callback");
        assertFalse(
            stopCompleted.get(),
            "stop must wait for the running callback instead of clearing live executor references");
        assertSame(future, timerFuture(ops));
        assertSame(executor, timerExecutor(ops));

        releaseCallback.countDown();
        stopper.join(TimeUnit.SECONDS.toMillis(5));
        assertTrue(stopCompleted.get(), "stop must finish after the callback actually exits");
        assertTrue(executor.isTerminated(), "close must return only after termination");
        assertTrue(
            stopRestoredInterrupt.get(),
            "close must restore an interruption received while waiting");
        assertNull(timerFuture(ops));
        assertNull(timerExecutor(ops));

        // The logical registration stays open: a terminated instance is pruned and a new timer can
        // consume the freed instance slot without rebuilding the Lucene executor registrations.
        ops.startCommitTimer();
        ScheduledExecutorService replacement = timerExecutor(ops);
        assertNotSame(executor, replacement);
        ops.stopCommitTimer();
        assertTrue(replacement.isTerminated());
      } finally {
        // Keep cleanup inside the writer's resource scope: an assertion must not strand a live
        // callback while try-with-resources closes the Lucene writer.
        releaseCallback.countDown();
        if (stopper != null) {
          stopper.interrupt();
          stopper.join(TimeUnit.SECONDS.toMillis(5));
        }
        ops.stopCommitTimer();
        if (stopper != null) {
          stopper.interrupt();
          stopper.join(TimeUnit.SECONDS.toMillis(5));
        }
      }
    }
  }

  private static ScheduledFuture<?> timerFuture(CommitOps ops) throws ReflectiveOperationException {
    var field = CommitOps.class.getDeclaredField("commitTimerFuture");
    field.setAccessible(true);
    return (ScheduledFuture<?>) field.get(ops);
  }

  private static ScheduledExecutorService timerExecutor(CommitOps ops)
      throws ReflectiveOperationException {
    var field = CommitOps.class.getDeclaredField("commitTimer");
    field.setAccessible(true);
    return (ScheduledExecutorService) field.get(ops);
  }

  private static void awaitShutdown(ScheduledExecutorService executor) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (!executor.isShutdown() && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertTrue(
        executor.isShutdown(), "stop must shut down the timer before waiting for termination");
  }

  @Test
  void refreshLagZeroBeforeAnyCommit() throws Exception {
    var meta = new SsotCommitMetadataSource();
    var val = new JsonSchemaCommitMetadataValidator();
    Path dir = Files.createTempDirectory("lucene-lag-0");
    var r = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), meta, val).atPath(dir).withExecutorRegistrations(testLuceneExecutors()).open();
    assertTrue(r.commitOps().refreshLagMs() == 0L);
    r.close();
  }
}
