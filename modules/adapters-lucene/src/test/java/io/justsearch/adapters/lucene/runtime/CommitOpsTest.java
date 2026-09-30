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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
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

      long elapsed = ops.commit();
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

      long elapsed = ops.commit();
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
    java.util.concurrent.atomic.AtomicReference<CommitReason> received =
        new java.util.concurrent.atomic.AtomicReference<>();
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

    assertThrows(RuntimeException.class, () -> ops.commitAndTrack(CommitReason.INDEXING_LOOP_IDLE));
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
      assertThrows(RuntimeException.class, ops::commit);
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
