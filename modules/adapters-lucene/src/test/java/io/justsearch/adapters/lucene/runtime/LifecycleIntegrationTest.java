package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.runtime.IndexOpenGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.TopDocs;
import org.junit.jupiter.api.Test;

class LifecycleIntegrationTest extends RuntimeTestBase {

  @Test
  void closeInvalidatesCollaboratorsAndSearchFailsFast() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\nindex:\n  collections:\n    - name: restarttest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    // Index, verify search works
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.CONTENT, "hello world")));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    SearchResult r1 = runtime.readPathOps().search(new MatchAllDocsQuery(), 10, Set.of(), RuntimeSearchSort.RELEVANCE, null);
    assertEquals(1, r1.hits().size(), "Should find 1 doc before close");

    // Close and verify collaborators are invalidated
    runtime.close();

    // Search after close should throw IllegalStateException, not NPE
    assertThrows(
        IllegalStateException.class,
        () -> runtime.readPathOps().search(new MatchAllDocsQuery(), 10, Set.of(), RuntimeSearchSort.RELEVANCE, null),
        "Search after close should throw IllegalStateException");
  }

  @Test
  void concurrentSearchDuringCloseDoesNotThrowNpe() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\nindex:\n  collections:\n    - name: racetest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    // Index some docs so search has work to do
    for (int i = 0; i < 20; i++) {
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-" + i,
                  SchemaFields.DOC_UID, "doc-" + i + "#0",
                  SchemaFields.CONTENT, "content for document number " + i)));
    }
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    // Race: multiple search threads vs close()
    int searchThreads = 4;
    CyclicBarrier barrier = new CyclicBarrier(searchThreads + 1);
    AtomicBoolean npeObserved = new AtomicBoolean(false);
    ExecutorService executor = Executors.newFixedThreadPool(searchThreads);
    List<Future<?>> futures = new ArrayList<>();

    for (int t = 0; t < searchThreads; t++) {
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await(5, TimeUnit.SECONDS);
                  for (int i = 0; i < 50; i++) {
                    try {
                      runtime.readPathOps().search(new MatchAllDocsQuery(), 5, Set.of(), RuntimeSearchSort.RELEVANCE, null);
                    } catch (IllegalStateException e) {
                      // Expected: "IndexRuntime not started" or "ReadPathOps not available"
                      break;
                    } catch (NullPointerException e) {
                      npeObserved.set(true);
                      break;
                    }
                  }
                } catch (Exception e) {
                  // barrier timeout or interrupt -- acceptable
                }
              }));
    }

    // Main thread triggers close after barrier
    barrier.await(5, TimeUnit.SECONDS);
    Thread.sleep(1); // Let search threads start a few iterations
    runtime.close();

    // Collect results
    for (Future<?> f : futures) {
      f.get(5, TimeUnit.SECONDS);
    }
    executor.shutdown();

    assertFalse(
        npeObserved.get(),
        "Search during close should throw IllegalStateException, not NullPointerException");
  }

  // ==========================================================================
  // Tempdoc 406 Phase 4 — TDD regression tests for the holder-swap pattern
  // ==========================================================================

  /**
   * Phase 4 test: build → close → build-again on the same path produces a fresh
   * runtime that sees the prior runtime's persisted index. This is the core
   * "consumer-as-holder" pattern: each open period is a single-shot phase value;
   * restart is "build a new value via the same builder."
   */
  @Test
  void holderSwapRoundtrip() throws Exception {
    Path base = dataDir();
    Path indexPath = tempDir.resolve("holder-swap-index");
    Files.createDirectories(indexPath);
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\nindex:\n  collections:\n    - name: holderswap\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    IndexSchema schema = buildSchemaWithDim(4);

    // Cycle 1: open, index doc-1, commit, close.
    RunningRuntime r1 = schema.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).open();
    r1.indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "doc-1",
                    SchemaFields.DOC_UID, "doc-1#0",
                    SchemaFields.CONTENT, "first cycle")));
    r1.commitOps().commitAndTrack();
    r1.commitOps().maybeRefreshBlocking();
    SearchResult c1 =
        r1.readPathOps()
            .search(new MatchAllDocsQuery(), 10, Set.of(), RuntimeSearchSort.RELEVANCE, null);
    assertEquals(1, c1.hits().size(), "Cycle 1 should see 1 doc");
    r1.close();

    // Cycle 2: build new RunningRuntime via same builder/path; reopens the persisted index.
    RunningRuntime r2 = schema.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).open();
    SearchResult c2InitialHits =
        r2.readPathOps()
            .search(new MatchAllDocsQuery(), 10, Set.of(), RuntimeSearchSort.RELEVANCE, null);
    assertEquals(
        1, c2InitialHits.hits().size(), "Cycle 2 fresh open should see persisted doc-1");
    r2.indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "doc-2",
                    SchemaFields.DOC_UID, "doc-2#0",
                    SchemaFields.CONTENT, "second cycle")));
    r2.commitOps().commitAndTrack();
    r2.commitOps().maybeRefreshBlocking();
    SearchResult c2 =
        r2.readPathOps()
            .search(new MatchAllDocsQuery(), 10, Set.of(), RuntimeSearchSort.RELEVANCE, null);
    assertEquals(2, c2.hits().size(), "Cycle 2 after index should see both docs");
    r2.close();
  }

  /**
   * Phase 4 test: openDeferred returns a DeferredRuntime that supports search.
   * upgradeWriter consumes the deferred runtime and returns a RunningRuntime.
   * Double-upgrade throws ISE. A fresh DeferredRuntime built via the same
   * builder is independently consumable (the consumed-flag is per-instance).
   */
  @Test
  void deferredWriterUpgradeRoundtrip() throws Exception {
    Path base = dataDir();
    Path indexPath = tempDir.resolve("deferred-index");
    Files.createDirectories(indexPath);
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\nindex:\n  collections:\n    - name: deferred\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    IndexSchema schema = buildSchemaWithDim(4);

    // Seed the index so deferred-writer mode has segments to open read-only against.
    RunningRuntime seed = schema.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).open();
    seed.indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "seed",
                    SchemaFields.DOC_UID, "seed#0",
                    SchemaFields.CONTENT, "seed doc")));
    seed.commitOps().commitAndTrack();
    seed.close();

    // Cycle 1: openDeferred → search works → upgradeWriter → search + write.
    DeferredRuntime deferred = schema.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).openDeferred();
    SearchResult deferredSearch =
        deferred
            .readPathOps()
            .search(new MatchAllDocsQuery(), 10, Set.of(), RuntimeSearchSort.RELEVANCE, null);
    assertEquals(1, deferredSearch.hits().size(), "DeferredRuntime should serve search");

    RunningRuntime upgraded = deferred.upgradeWriter();
    assertNotNull(upgraded, "upgradeWriter should return a RunningRuntime");

    // Double-upgrade throws ISE.
    assertThrows(
        IllegalStateException.class,
        deferred::upgradeWriter,
        "Second upgradeWriter call must throw ISE (one-shot)");

    // Write through the upgraded runtime.
    upgraded
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "after-upgrade",
                    SchemaFields.DOC_UID, "after-upgrade#0",
                    SchemaFields.CONTENT, "post-upgrade write")));
    upgraded.commitOps().commitAndTrack();
    upgraded.commitOps().maybeRefreshBlocking();
    SearchResult upgradedSearch =
        upgraded
            .readPathOps()
            .search(new MatchAllDocsQuery(), 10, Set.of(), RuntimeSearchSort.RELEVANCE, null);
    assertEquals(2, upgradedSearch.hits().size(), "Upgraded runtime should see both docs");
    upgraded.close();

    // Cycle 2: build a new DeferredRuntime via the same builder; consumed-flag is fresh.
    DeferredRuntime deferred2 = schema.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).openDeferred();
    RunningRuntime upgraded2 = deferred2.upgradeWriter();
    assertNotNull(upgraded2, "Second instance of DeferredRuntime should upgrade independently");
    upgraded2.close();
  }

  /**
   * Phase 4 test: a searcher acquired from a runtime before {@code close()} survives
   * the close (Lucene IndexSearcher refcount contract). After close, a new runtime
   * built on the same path opens cleanly. This is the safety property that lets
   * consumers do a holder-swap without cancelling in-flight reads.
   */
  @Test
  void holderSwapPreservesInflightSearch() throws Exception {
    Path base = dataDir();
    Path indexPath = tempDir.resolve("inflight-index");
    Files.createDirectories(indexPath);
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\nindex:\n  collections:\n    - name: inflight\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    IndexSchema schema = buildSchemaWithDim(4);
    RunningRuntime r1 = schema.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).open();

    for (int i = 0; i < 5; i++) {
      r1.indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID, "doc-" + i,
                      SchemaFields.DOC_UID, "doc-" + i + "#0",
                      SchemaFields.CONTENT, "content " + i)));
    }
    r1.commitOps().commitAndTrack();
    r1.commitOps().maybeRefreshBlocking();

    // Acquire searcher BEFORE close — Lucene's IndexSearcher refcount keeps the
    // underlying SegmentReaders alive even after SearcherManager.close().
    IndexSearcher searcher = new LifecycleTestAccessor(r1).acquireSearcher();

    CountDownLatch closeFinished = new CountDownLatch(1);
    AtomicReference<TopDocs> resultRef = new AtomicReference<>();
    AtomicReference<Throwable> errorRef = new AtomicReference<>();

    Thread searchThread =
        new Thread(
            () -> {
              try {
                // Wait until close has finished, then run search on captured searcher.
                assertTrue(
                    closeFinished.await(5, TimeUnit.SECONDS), "close should finish in time");
                TopDocs hits = searcher.search(new MatchAllDocsQuery(), 10);
                resultRef.set(hits);
              } catch (Throwable t) {
                errorRef.set(t);
              }
            });
    searchThread.start();

    // Close the runtime while the search thread is waiting.
    r1.close();
    closeFinished.countDown();

    searchThread.join(5_000);
    assertNotNull(resultRef.get(), "In-flight searcher should survive runtime close: error="
        + (errorRef.get() == null ? "null" : errorRef.get().toString()));
    assertEquals(
        5L,
        resultRef.get().totalHits.value(),
        "Captured searcher should still see the 5 indexed docs after runtime close");

    // After close, a new RunningRuntime built on the same path opens cleanly.
    RunningRuntime r2 = schema.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).open();
    r2.commitOps().maybeRefreshBlocking();
    SearchResult fresh =
        r2.readPathOps()
            .search(new MatchAllDocsQuery(), 10, Set.of(), RuntimeSearchSort.RELEVANCE, null);
    assertEquals(5, fresh.hits().size(), "Fresh runtime on same path sees the persisted docs");
    r2.close();
  }

  /**
   * Phase 4a (C3 / A4 fix): if {@code DeferredRuntime.upgradeWriter()} fails because
   * the new {@code RuntimeSession} ctor throws (write-lock contention, parity guard
   * failure, IO error, etc.), the deferred runtime must remain recoverable: the
   * {@code consumed} flag resets so the caller can either retry or close the
   * deferred session cleanly. Pre-fix, a failure left the runtime unusable
   * (consumed but no upgraded RunningRuntime; close() became a no-op; resource
   * leak on the deferred SM/Directory/Writer).
   */
  @Test
  void deferredUpgradeFailureIsRecoverable() throws Exception {
    Path base = dataDir();
    Path indexPath = tempDir.resolve("upgrade-fail-index");
    Files.createDirectories(indexPath);
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\nindex:\n  collections:\n    - name: upgradefail\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    IndexSchema schema = buildSchemaWithDim(4);

    // Seed the index so deferred-writer mode has segments to open read-only against.
    RunningRuntime seed = schema.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).open();
    seed.indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "seed",
                    SchemaFields.DOC_UID, "seed#0",
                    SchemaFields.CONTENT, "seed doc")));
    seed.commitOps().commitAndTrack();
    seed.close();

    // A guard that always throws on checkOnOpen. ComponentsFactory swallows the
    // exception when readOnly=true (deferred opens read-only) but propagates it
    // when readOnly=false (the upgrade builds read-write). So:
    //   - openDeferred() succeeds (guard logged, not thrown)
    //   - upgradeWriter() fails (guard throws inside new RuntimeSession ctor)
    IndexOpenGuard failingGuard =
        () -> {
          throw new IllegalStateException("test-injected upgrade failure");
        };

    DeferredRuntime deferred =
        schema.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).withIndexOpenGuard(failingGuard).openDeferred();

    // First upgrade attempt fails because the new RuntimeSession ctor throws.
    IllegalStateException upgradeFailure =
        assertThrows(IllegalStateException.class, deferred::upgradeWriter);
    assertTrue(
        upgradeFailure.getMessage().contains("test-injected upgrade failure"),
        () -> "Expected guard failure to propagate, got: " + upgradeFailure.getMessage());

    // After failure, consumed flag was reset so close() actually closes the
    // deferred session (no-op pre-fix). assertDoesNotThrow + we then verify
    // the index path is releasable by opening a fresh runtime on it.
    assertDoesNotThrow(deferred::close, "deferred should close cleanly after failed upgrade");

    // Verify no resource leak: a fresh RunningRuntime opens on the same path.
    // If the deferred session leaked an IndexWriter or Directory, this would
    // fail with a write-lock contention error or similar.
    RunningRuntime fresh = schema.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).open();
    SearchResult result =
        fresh.readPathOps()
            .search(new MatchAllDocsQuery(), 10, Set.of(), RuntimeSearchSort.RELEVANCE, null);
    assertEquals(1, result.hits().size(), "seed doc should still be visible after recovery");
    fresh.close();
  }
}
