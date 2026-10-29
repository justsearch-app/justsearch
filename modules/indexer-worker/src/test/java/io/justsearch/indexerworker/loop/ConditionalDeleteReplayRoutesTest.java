/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferDelete;
import io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.WatchRootRequest;
import io.justsearch.telemetry.catalog.CounterMetric;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * #852. The ordinary promotion replay and the promoted-pointer
 * recovery replay an accepted watcher DELETE receipt against the path's state as refusal cleanup
 * confirms it. A watched file is replaced by a folder inside a parent folder that can be traversed
 * but not listed while the root vouches: that state is uncertain, so the receipt stays, nothing is
 * queued and the target keeps the file's text. With a listable parent the same folder is a
 * confirmed non-regular source: the receipt settles and the normal policy removes the text.
 */
final class ConditionalDeleteReplayRoutesTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  @Test
  void ordinaryPromotionKeepsADeleteBeneathAnUnlistableParent() throws Exception {
    assertKept(replayWatcherDeleteOverFolder(Route.ORDINARY_PROMOTION, false));
  }

  @Test
  void ordinaryPromotionRetiresAFolderBeneathAListableParent() throws Exception {
    assertRetired(replayWatcherDeleteOverFolder(Route.ORDINARY_PROMOTION, true));
  }

  @Test
  void promotedPointerKeepsADeleteBeneathAnUnlistableParent() throws Exception {
    assertKept(replayWatcherDeleteOverFolder(Route.PROMOTED_POINTER, false));
  }

  @Test
  void promotedPointerRetiresAFolderBeneathAListableParent() throws Exception {
    assertRetired(replayWatcherDeleteOverFolder(Route.PROMOTED_POINTER, true));
  }

  private static void assertKept(Outcome outcome) {
    assertEquals(1, outcome.count(),
        "unlistable parent is uncertainty; the conditional DELETE must not cause removal through a policy job");
    assertFalse(outcome.settled(), "an uncertain path settles nothing");
    assertEquals(1, outcome.receipts(), "the receipt stays owed");
    assertEquals(0, outcome.queued(), "nothing is admitted on uncertainty");
  }

  private static void assertRetired(Outcome outcome) {
    assertEquals(0, outcome.count(), "a confirmed folder at the file's path retires the text by policy");
    assertTrue(outcome.settled(), "a confirmed folder settles the receipt as before");
    assertEquals(0, outcome.receipts(), "the receipt is acknowledged");
    assertEquals(1, outcome.queued(), "the folder is queued once for the normal policy");
  }

  /** The replay's answer, what it left owed and queued, and the target's document count for the path. */
  private record Outcome(boolean settled, int receipts, int queued, long count) {}

  /** The two replay routes besides refusal cleanup that replay a conditional file delete. */
  private enum Route { ORDINARY_PROMOTION, PROMOTED_POINTER }

  /**
   * The target holds root/nested/source.txt; an accepted watcher DELETE receipt for it is
   * buffered; nested is replaced by a folder (listable or not) holding a folder at source.txt.
   * Replays the receipt by {@code route}, then whatever job it queued through the real batch
   * extractor.
   */
  private Outcome replayWatcherDeleteOverFolder(Route route, boolean listableParent)
      throws Exception {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        "needs POSIX folder permissions");
    Path base = temp.resolve("index");
    Path index = new IndexGenerationManager(base).initializeOrLoad().activeGenerationPath();
    Path root = Files.createDirectory(temp.resolve("files"));
    Path parent = Files.createDirectory(root.resolve("nested"));
    Path file = Files.writeString(parent.resolve("source.txt"), "original source");
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"));
        var active = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
            .atPath(index).withExecutorRegistrations(testLuceneExecutors()).open()) {
      queue.open();
      active.commitOps().stopCommitTimer();
      active.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, file.toString(), SchemaFields.PATH, file.toString(),
          SchemaFields.DOC_UID, "original", SchemaFields.CONTENT, "original source",
          SchemaFields.COLLECTION, "docs")));
      active.commitOps().commitAndTrack(CommitReason.DRAIN);
      active.commitOps().maybeRefreshBlocking();
      var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(), base,
          index, active, active, null, 0L);
      assertTrue(service.watchRoot(WatchRootRequest.newBuilder().setRootPath(root.toString())
          .setCollection("docs").setRootAdded(true).build(), CallContext.none()).getWatching());
      var capture = Class.forName("io.justsearch.indexerworker.services.RootIdentity")
          .getDeclaredMethod("capture", Path.class);
      capture.setAccessible(true);
      String identity = capture.invoke(null, root).toString();
      assertTrue(queue.putSwitchBufferForGeneration("green", "path:" + file, "DELETE",
          new SwitchBufferDelete(file.toString(), SwitchBufferDelete.Origin.WATCHER,
              root.toString(), identity, "docs").encode()));
      Files.delete(file);
      Files.move(parent, temp.resolve("old-parent"));
      Files.createDirectory(parent);
      Files.createDirectory(file);
      if (!listableParent) {
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("--x------"));
      }
      try {
        assertEquals(listableParent, Files.isReadable(parent));
        assertTrue(Files.isDirectory(file));
        boolean settled = route == Route.ORDINARY_PROMOTION
            ? KnowledgeServerMigrationOps.drainSwitchBufferStrict(
                new KnowledgeServerMigrationOps.DrainSwitchBufferContext(queue, active, null,
                    IndexingPacing.unthrottled(), base, index, new ObjectMapper(), () -> false,
                    () -> false, LoggerFactory.getLogger(getClass()),
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(2), "green", ignored -> false)
                    .withPublisher(service))
            : KnowledgeServerMigrationOps.settleCommittedNativeFileWitnesses(queue, active,
                "green", List.of(), LoggerFactory.getLogger(getClass()));
        int receipts = queue.listSwitchBufferOpsStrict().size();
        var claims = queue.pollPending(10);
        if (!claims.isEmpty()) {
          var authority = new WorkerIngestionAuthority();
          authority.useRootCheck(service.rootCheck());
          var content = mock(TimeboxedContentExtractor.class);
          when(content.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
          var journal = new IngestionOutcomeJournal(queue, mock(OperationalMetrics.class),
              mock(CounterMetric.class), () -> false);
          var extractor = new JobBatchExtractor(authority, journal, queue, content,
              active.documentFieldOps(), active.indexCountOps(), mock(BatchStats.class),
              mock(StaleSnapshotResolver.class), new StaleSourceHandler(active.indexingCoordinator()),
              IndexingPacing.unthrottled(), new AtomicBoolean(true), new HashSet<>(),
              () -> mock(PathResolutionStore.class), () -> mock(DocumentIdentityStore.class),
              mock(IndexingDocumentOps.StageRecorder.class), () -> false, ignored -> { }, new FileMutationFence());
          extractor.extractAll(claims);
          active.commitOps().commitAndTrack(CommitReason.DRAIN);
          active.commitOps().maybeRefreshBlocking();
        }
        return new Outcome(settled, receipts, claims.size(),
            active.indexCountOps().countByIdAndChunksStrict(file.toString()));
      } finally {
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
      }
    }
  }
}
