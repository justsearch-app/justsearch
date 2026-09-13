/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 885 item 19 — the reopen-on-demand seam, end to end through a real index.
 *
 * <p>Every test here suspends the background reopen thread first. That is the whole point: with the
 * {@code ControlledRealTimeReopenThread} stopped, the ONLY thing that can make a freshly written
 * document visible is the refresh {@link SearcherBridge} performs before acquiring. A test that
 * left the thread running would pass in both modes and prove nothing.
 */
final class NrtOnDemandRefreshTest extends LuceneExecutorTestBase {

  private static final int DIM = 768;

  private static String config(Path dataDir, String nrtBlock) {
    return "app:\n"
        + "  data_dir: "
        + dataDir.toString().replace("\\", "\\\\")
        + "\n"
        + "index:\n"
        + "  collections:\n"
        + "    - name: runtime\n"
        + "      roots: ['ignored']\n"
        + "  vector:\n"
        + "    dimension: "
        + DIM
        + "\n"
        + nrtBlock;
  }

  private static final String ON_DEMAND =
      "  nrt:\n    mode: on_demand\n    background_reopen_ms: 2000\n"
          + "    on_demand_max_stale_ms: 1000\n";

  private static final String CONTINUOUS = "  nrt:\n    mode: continuous\n";

  /** Opens a runtime under the given NRT config with the background reopen thread suspended. */
  private RunningRuntime openWithBackgroundReopenStopped(String nrtBlock) throws IOException {
    return openWithBackgroundReopenStopped(nrtBlock, () -> true);
  }

  /** Opens a runtime under the given NRT config with the background reopen thread STILL RUNNING. */
  private RunningRuntime open(String nrtBlock) throws IOException {
    Path dataDir = Files.createTempDirectory("justsearch-nrt-ondemand-");
    Path cfg = Files.createTempFile("justsearch-nrt-ondemand-", ".yaml");
    Files.writeString(cfg, config(dataDir, nrtBlock));
    System.setProperty("justsearch.config", cfg.toString());
    return IndexSchema.fromCatalog(
            FieldCatalogDef.forTesting(DIM),
            new SsotCommitMetadataSource(),
            new JsonSchemaCommitMetadataValidator())
        .ephemeral().withExecutorRegistrations(testLuceneExecutors())
        .open();
  }

  /** Reads a {@code ControlledRealTimeReopenThread}'s own nanosecond bound by reflection. */
  private static long reopenThreadStaleNs(Object crtrt, String fieldName) throws Exception {
    var f = crtrt.getClass().getDeclaredField(fieldName);
    f.setAccessible(true);
    return f.getLong(crtrt);
  }

  /** As above, with an explicit foreground predicate — the seam's gate. */
  private RunningRuntime openWithBackgroundReopenStopped(
      String nrtBlock, java.util.function.BooleanSupplier foregroundActive) throws IOException {
    Path dataDir = Files.createTempDirectory("justsearch-nrt-ondemand-");
    Path cfg = Files.createTempFile("justsearch-nrt-ondemand-", ".yaml");
    Files.writeString(cfg, config(dataDir, nrtBlock));
    System.setProperty("justsearch.config", cfg.toString());
    RunningRuntime runtime =
        IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(DIM),
                new SsotCommitMetadataSource(),
                new JsonSchemaCommitMetadataValidator())
            .ephemeral().withExecutorRegistrations(testLuceneExecutors())
            .withForegroundActive(foregroundActive)
            .open();
    runtime.commitOps().suspendNrtRefresh();
    return runtime;
  }

  private static void index(RunningRuntime runtime, int count) {
    for (int i = 0; i < count; i++) {
      runtime
          .indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID, "doc-" + i,
                      SchemaFields.DOC_UID, "doc-" + i + "#0",
                      SchemaFields.PATH, "test/doc-" + i + ".txt",
                      SchemaFields.CONTENT, "content " + i)));
    }
  }

  @Test
  @DisplayName("on_demand: a search after N added docs sees them with the background thread stopped")
  void onDemandSearchSeesNewDocumentsWithoutTheBackgroundThread() throws Exception {
    String prev = System.getProperty("justsearch.config");
    RunningRuntime runtime = null;
    try {
      runtime = openWithBackgroundReopenStopped(ON_DEMAND);
      assertEquals(0L, runtime.indexCountOps().docCount(), "empty index before any write");
      long reopensBefore = runtime.runtimeGaugesSnapshot().reopenCount();

      index(runtime, 3);

      // No commit and no background thread: only the on-demand refresh can make these visible.
      assertEquals(3L, runtime.indexCountOps().docCount());
      assertTrue(
          runtime.runtimeGaugesSnapshot().reopenCount() > reopensBefore,
          "the foreground acquire must have reopened the searcher");
    } finally {
      closeQuietly(runtime);
      restore(prev);
    }
  }

  @Test
  @DisplayName("on_demand: an untouched index is already current, so the first search skips too")
  void onDemandDoesNotReopenOnAnUntouchedIndex() throws Exception {
    String prev = System.getProperty("justsearch.config");
    RunningRuntime runtime = null;
    try {
      runtime = openWithBackgroundReopenStopped(ON_DEMAND);
      for (int i = 0; i < 5; i++) {
        assertEquals(0L, runtime.indexCountOps().docCount());
      }
      // The SearcherManager is built over a reader opened from the writer, so it covers everything
      // written so far — which on a fresh index is nothing. Without seeding the watermark at open,
      // the sentinel would never match the writer's sequence number and every query would refresh.
      assertEquals(0L, runtime.runtimeGaugesSnapshot().reopenCount());
    } finally {
      closeQuietly(runtime);
      restore(prev);
    }
  }

  @Test
  @DisplayName("on_demand: an idle index does not reopen, however many searches arrive")
  void onDemandDoesNotReopenWhenNothingWasWritten() throws Exception {
    String prev = System.getProperty("justsearch.config");
    RunningRuntime runtime = null;
    try {
      runtime = openWithBackgroundReopenStopped(ON_DEMAND);
      index(runtime, 2);
      assertEquals(2L, runtime.indexCountOps().docCount());

      long reopensAfterFirstSearch = runtime.runtimeGaugesSnapshot().reopenCount();
      for (int i = 0; i < 10; i++) {
        assertEquals(2L, runtime.indexCountOps().docCount());
      }
      assertEquals(
          reopensAfterFirstSearch,
          runtime.runtimeGaugesSnapshot().reopenCount(),
          "a fresh searcher must not be reopened again — age alone is not a reason to reopen");
    } finally {
      closeQuietly(runtime);
      restore(prev);
    }
  }

  /**
   * The discriminator for the mode gate. Same suspended-thread setup, same writes; in continuous
   * mode the foreground path must NOT refresh, so the documents stay invisible. If the seam ever
   * stopped consulting {@code nrtMode} this is the test that reds.
   */
  @Test
  @DisplayName("continuous: the foreground path does not refresh, so new docs stay invisible")
  void continuousModeDoesNotRefreshOnTheForegroundPath() throws Exception {
    String prev = System.getProperty("justsearch.config");
    RunningRuntime runtime = null;
    try {
      runtime = openWithBackgroundReopenStopped(CONTINUOUS);
      index(runtime, 3);
      assertEquals(
          0L,
          runtime.indexCountOps().docCount(),
          "continuous mode relies on the background thread, which this test stopped");
      assertEquals(0L, runtime.runtimeGaugesSnapshot().reopenCount());
    } finally {
      closeQuietly(runtime);
      restore(prev);
    }
  }

  @Test
  @DisplayName("segments_since_reopen counts the writer's new segments and resets on reopen")
  void segmentsSinceReopenTracksTheReopenBacklog() throws Exception {
    String prev = System.getProperty("justsearch.config");
    RunningRuntime runtime = null;
    try {
      runtime = openWithBackgroundReopenStopped(CONTINUOUS);
      assertEquals(0L, runtime.runtimeGaugesSnapshot().segmentsSinceReopen());

      index(runtime, 2);
      // commit() flushes the RAM buffer into a new segment; nothing has reopened over it yet.
      runtime.commitOps().commitAndTrack();
      assertTrue(
          runtime.runtimeGaugesSnapshot().segmentsSinceReopen() > 0,
          "a flushed segment the current searcher cannot see is the next reopen's backlog");
      assertEquals(1L, runtime.runtimeGaugesSnapshot().commitCount());

      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(
          0L,
          runtime.runtimeGaugesSnapshot().segmentsSinceReopen(),
          "the reopen consumed the backlog");
      assertEquals(1L, runtime.runtimeGaugesSnapshot().reopenCount());
    } finally {
      closeQuietly(runtime);
      restore(prev);
    }
  }

  @Test
  @DisplayName("a refresh with nothing to reopen does not increment the reopen count")
  void refreshWithNothingNewDoesNotCountAsAReopen() throws Exception {
    String prev = System.getProperty("justsearch.config");
    RunningRuntime runtime = null;
    try {
      runtime = openWithBackgroundReopenStopped(CONTINUOUS);
      index(runtime, 1);
      runtime.commitOps().maybeRefreshBlocking();
      long after = runtime.runtimeGaugesSnapshot().reopenCount();
      assertEquals(1L, after);

      // This is the mechanism the on_demand background thread relies on to idle: Lucene's
      // openIfChanged returns null on an unchanged index, so afterRefresh(false) fires and the
      // counter does not move.
      runtime.commitOps().maybeRefreshBlocking();
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(after, runtime.runtimeGaugesSnapshot().reopenCount());
    } finally {
      closeQuietly(runtime);
      restore(prev);
    }
  }

  private static void closeQuietly(RunningRuntime runtime) {
    if (runtime == null) return;
    try {
      runtime.close();
    } catch (RuntimeException e) {
      /* best-effort */
    }
  }

  private static void restore(String prev) {
    if (prev == null) System.clearProperty("justsearch.config");
    else System.setProperty("justsearch.config", prev);
  }

  /**
   * The polarity fix (885 live window). Enrichment backfill reads every document it enriches
   * through {@code DocumentFieldOps}, i.e. through this same bridge, so before the foreground gate
   * every backfill fetch reopened the searcher: reopen count 193 -> 568 and indexing throughput
   * -15% on the live scifact arm. With the predicate false, the identical read must not reopen.
   */
  @Test
  @DisplayName("on_demand: a BACKGROUND read does not refresh, even with new documents pending")
  void onDemandBackgroundReadDoesNotRefresh() throws Exception {
    String prev = System.getProperty("justsearch.config");
    RunningRuntime runtime = null;
    try {
      runtime = openWithBackgroundReopenStopped(ON_DEMAND, () -> false);
      index(runtime, 3);

      assertEquals(
          0L,
          runtime.indexCountOps().docCount(),
          "a background read must not reopen, so it sees the pre-write searcher");
      assertEquals(0L, runtime.runtimeGaugesSnapshot().reopenCount());
    } finally {
      closeQuietly(runtime);
      restore(prev);
    }
  }

  /**
   * The control for the test above: same mode, same writes, same suspended reopen thread, and the
   * ONLY difference is the predicate. Without this pair, "background does not refresh" would also
   * pass if the seam had stopped working altogether.
   */
  @Test
  @DisplayName("on_demand: a FOREGROUND read refreshes under identical conditions")
  void onDemandForegroundReadRefreshes() throws Exception {
    String prev = System.getProperty("justsearch.config");
    RunningRuntime runtime = null;
    try {
      runtime = openWithBackgroundReopenStopped(ON_DEMAND, () -> true);
      index(runtime, 3);

      assertEquals(3L, runtime.indexCountOps().docCount());
      assertTrue(runtime.runtimeGaugesSnapshot().reopenCount() > 0);
    } finally {
      closeQuietly(runtime);
      restore(prev);
    }
  }

  /** A flipping predicate: the same runtime serves a background read then a foreground one. */
  @Test
  @DisplayName("on_demand: the gate is consulted per read, not once at open")
  void onDemandGateIsConsultedPerRead() throws Exception {
    String prev = System.getProperty("justsearch.config");
    RunningRuntime runtime = null;
    java.util.concurrent.atomic.AtomicBoolean foreground =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    try {
      runtime = openWithBackgroundReopenStopped(ON_DEMAND, foreground::get);
      index(runtime, 2);

      assertEquals(0L, runtime.indexCountOps().docCount(), "background read: no reopen");
      foreground.set(true);
      assertEquals(2L, runtime.indexCountOps().docCount(), "foreground read: reopens and sees both");
    } finally {
      closeQuietly(runtime);
      restore(prev);
    }
  }

  /**
   * 885 review B1. {@code CommitOps.resumeNrtRefresh} rebuilds the reopen thread after every
   * bulk-backfill suspend ({@code BackfillScheduler} wraps its enrichment tight loop in
   * {@code withNrtSuspended}). It used to rebuild from the RAW {@code index.nrt.*} pair, so
   * on_demand's 2 s background cadence silently reverted to the continuous 500 ms on the first
   * backfill — the mode looked configured and behaved like the default from then on.
   *
   * <p>Asserts the thread's own nanosecond field before and after a suspend/resume cycle. Against
   * the pre-fix code the second assertion reads 500,000,000 instead of 2,000,000,000.
   */
  @Test
  @DisplayName("on_demand: the background cadence survives a bulk-backfill suspend/resume")
  void onDemandCadenceSurvivesSuspendResume() throws Exception {
    String prev = System.getProperty("justsearch.config");
    RunningRuntime runtime = null;
    try {
      runtime = open(ON_DEMAND);
      Object before = runtime.session().crtrt;
      assertNotNull(before, "on_demand still runs a background thread, just a slow one");
      assertEquals(2_000_000_000L, reopenThreadStaleNs(before, "targetMaxStaleNS"));

      runtime.commitOps().withNrtSuspended(() -> {});

      Object after = runtime.session().crtrt;
      assertNotNull(after, "resume must rebuild the thread");
      assertEquals(
          2_000_000_000L,
          reopenThreadStaleNs(after, "targetMaxStaleNS"),
          "the rebuilt thread must keep the MODE-RESOLVED cadence, not the raw index.nrt.* pair");
      assertEquals(2_000_000_000L, reopenThreadStaleNs(after, "targetMinStaleNS"));
    } finally {
      closeQuietly(runtime);
      restore(prev);
    }
  }

  /** The continuous control: the same cycle must keep the 500/50 pair. */
  @Test
  @DisplayName("continuous: suspend/resume keeps the configured index.nrt.* bounds")
  void continuousCadenceSurvivesSuspendResume() throws Exception {
    String prev = System.getProperty("justsearch.config");
    RunningRuntime runtime = null;
    try {
      runtime = open(CONTINUOUS);
      runtime.commitOps().withNrtSuspended(() -> {});
      Object after = runtime.session().crtrt;
      assertNotNull(after);
      assertEquals(500_000_000L, reopenThreadStaleNs(after, "targetMaxStaleNS"));
      assertEquals(50_000_000L, reopenThreadStaleNs(after, "targetMinStaleNS"));
    } finally {
      closeQuietly(runtime);
      restore(prev);
    }
  }
}
