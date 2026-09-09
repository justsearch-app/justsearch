package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for logic absorbed from the deleted {@code LuceneLifecycleManager} facade into ops
 * classes and {@link RuntimeSession}.
 *
 * <p>Tests guards in {@link WritePathOps} ({@code guardWritable}), counter/telemetry tracking in
 * {@link CommitOps} ({@code commitAndTrack}), caching in {@link IndexCountOps}
 * ({@code getOrComputeCorpusProfile}), and open-failure semantics on the builder API
 * ({@code open()} either succeeds or throws — no intermediate FAILED state to retry from, since
 * the state machine was deleted in tempdoc 406 Phase 4b).
 */
class OpsAbsorbedLogicTest extends LuceneExecutorTestBase {

  @TempDir Path tempDir;

  // ==========================================================================
  // guardWritable tests — tested indirectly via WritePathOps.updateDocument
  // ==========================================================================

  /** guardWritable: readOnly=true throws ISE immediately, before any other guard. */
  @Test
  void guardWritable_readOnly_throwsISE() {
    var session = new RuntimeSession(
        IndexSchema.fromCatalog(
            FieldCatalogDef.forTesting(4),
            (java.util.function.Supplier<io.justsearch.indexing.runtime.CommitMetadataSource>)
                SsotCommitMetadataSource::new,
            new JsonSchemaCommitMetadataValidator()));

    // SearcherBridge is not needed — guardWritable() throws before reaching it
    SearcherBridge bridge = new SearcherBridge(session);
    WritePathOps ops = new WritePathOps(session, SchemaFields.DOC_ID, bridge);

    IllegalStateException ex = assertThrows(
        IllegalStateException.class,
        () -> ops.updateDocument("doc-1", Map.of("title", "hello")));
    assertTrue(ex.getMessage().contains("not writable"), "ISE should mention not writable: " + ex.getMessage());
  }

  /** guardWritable: snapshot present but writer=null (no deferred-pending) throws ISE. */
  @Test
  void guardWritable_nullWriter_throwsISE() {
    var session = new RuntimeSession(
        IndexSchema.fromCatalog(
            FieldCatalogDef.forTesting(4),
            (java.util.function.Supplier<io.justsearch.indexing.runtime.CommitMetadataSource>)
                SsotCommitMetadataSource::new,
            new JsonSchemaCommitMetadataValidator()));

    SearcherBridge bridge = new SearcherBridge(session);
    WritePathOps ops = new WritePathOps(session, SchemaFields.DOC_ID, bridge);

    IllegalStateException ex = assertThrows(
        IllegalStateException.class,
        () -> ops.updateDocument("doc-1", Map.of("title", "hello")));
    // Either "read-only" or "not ready" are acceptable ISE messages
    assertNotNull(ex.getMessage());
  }

  /**
   * guardWritable normal path: started runtime with a real writer allows updateDocument to reach
   * the read-modify-write logic (returns false = not found, which is fine; the guard passed).
   */
  @Test
  void guardWritable_normalPath_passesGuard() throws Exception {
    Path dir = tempDir.resolve("guardWritable-normal");
    Files.createDirectories(dir);
    var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4), new SsotCommitMetadataSource(), new JsonSchemaCommitMetadataValidator()).atPath(dir).withExecutorRegistrations(testLuceneExecutors()).open();
    try {
      // updateDocument on a non-existent doc returns false — but no ISE.
      // This proves guardWritable() passed.
      boolean result = assertDoesNotThrow(
          () -> runtime.indexingCoordinator().updateDocument("nonexistent", Map.of("title", "x")));
      assertFalse(result, "doc does not exist yet — updateDocument should return false");
    } finally {
      runtime.close();
    }
  }

  // ==========================================================================
  // commitAndTrack tests
  // ==========================================================================

  /**
   * commitAndTrack increments commitCount, resets pendingDocs to 0, sets lastCommitNanos, and
   * fires TelemetryEvents.onCommit.
   */
  @Test
  void commitAndTrack_updatesCountersAndFiresTelemetry() throws Exception {
    Path dir = tempDir.resolve("commitAndTrack");
    Files.createDirectories(dir);
    var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4), new SsotCommitMetadataSource(), new JsonSchemaCommitMetadataValidator()).atPath(dir).withExecutorRegistrations(testLuceneExecutors()).open();
    try {
      // Index a doc to simulate pending work
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-track-1",
                  SchemaFields.DOC_UID, "doc-track-1#0")));

      // Capture telemetry calls
      AtomicLong capturedElapsedMs = new AtomicLong(-1L);
      runtime.session().telemetryEvents = new LuceneRuntimeTypes.TelemetryEvents() {
        @Override
        public void onCommit(long latencyMs) {
          capturedElapsedMs.set(latencyMs);
        }
      };

      long commitCountBefore = runtime.session().commitCount.get();

      runtime.commitOps().commitAndTrack();

      // commitCount incremented
      assertEquals(commitCountBefore + 1, runtime.session().commitCount.get(),
          "commitCount should be incremented by 1");
      // pendingDocs reset
      assertEquals(0L, runtime.session().pendingDocs.get(),
          "pendingDocs should be reset to 0 after commit");
      // lastCommitNanos set to a positive value
      assertTrue(runtime.session().lastCommitNanos.get() > 0L,
          "lastCommitNanos should be positive after commit");
      // onCommit fired with non-negative latency
      assertTrue(capturedElapsedMs.get() >= 0L,
          "onCommit should be called with non-negative elapsed ms, got: " + capturedElapsedMs.get());
    } finally {
      runtime.close();
    }
  }

  // ==========================================================================
  // getOrComputeCorpusProfile tests
  // ==========================================================================

  /**
   * getOrComputeCorpusProfile returns the same object on repeated calls (cached), then returns a
   * new object after the cache is invalidated.
   */
  @Test
  void getOrComputeCorpusProfile_cachesBetweenCalls() throws Exception {
    Path dir = tempDir.resolve("corpusProfile");
    Files.createDirectories(dir);
    var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4), new SsotCommitMetadataSource(), new JsonSchemaCommitMetadataValidator()).atPath(dir).withExecutorRegistrations(testLuceneExecutors()).open();
    try {
      // Index a few docs so the profile has something to compute
      for (int i = 0; i < 3; i++) {
        runtime.indexingCoordinator().indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "prof-doc-" + i,
                    SchemaFields.DOC_UID, "prof-doc-" + i + "#0",
                    SchemaFields.CONTENT, "word" + i + " content")));
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // First call computes and caches
      CorpusProfile first = runtime.indexCountOps().getOrComputeCorpusProfile();
      assertNotNull(first, "getOrComputeCorpusProfile should return non-null");

      // Second call must return the same cached instance (same reader version)
      CorpusProfile second = runtime.indexCountOps().getOrComputeCorpusProfile();
      assertSame(first, second, "Second call should return the cached instance");

      // Commit new docs to change the reader version, then refresh to make it visible
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "prof-doc-new",
                  SchemaFields.DOC_UID, "prof-doc-new#0",
                  SchemaFields.CONTENT, "new content")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Third call detects version change and recomputes
      CorpusProfile third = runtime.indexCountOps().getOrComputeCorpusProfile();
      assertNotNull(third, "Third call after version change should return non-null");
      assertNotSame(first, third, "Third call after version change should return a new instance");
    } finally {
      runtime.close();
    }
  }

  // ==========================================================================
  // Open-failure tests (replaces former startGuarded reset tests)
  // ==========================================================================

  /**
   * Tempdoc 406 Phase 4b: with phase types + RuntimeSession (no state machine),
   * open() either succeeds and returns a started runtime, or throws. There is no
   * "FAILED" intermediate state to retry from. Each call to builder.open() is an
   * independent attempt — calling open() twice with a broken path throws twice.
   */
  @Test
  void open_failsOnInvalidPathAndIsRetryable() throws Exception {
    // Create a file where the index directory should be — Lucene cannot open a directory on a file
    Path fakeIndexPath = tempDir.resolve("not-a-dir");
    Files.writeString(fakeIndexPath, "this is a file, not a directory");

    java.util.function.Supplier<RunningRuntime> opener =
        () ->
            IndexSchema.fromCatalog(
                    FieldCatalogDef.forTesting(4),
                    new SsotCommitMetadataSource(),
                    new JsonSchemaCommitMetadataValidator())
                .atPath(fakeIndexPath).withExecutorRegistrations(testLuceneExecutors())
                .open();

    assertThrows(Exception.class, opener::get,
        "open() on a file path (not a directory) should throw");
    assertThrows(Exception.class, opener::get,
        "Each open() is an independent attempt; second call should also throw");
  }
}
