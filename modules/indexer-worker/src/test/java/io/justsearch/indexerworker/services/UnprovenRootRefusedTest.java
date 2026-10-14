/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.PruneRequest;
import io.justsearch.ipc.ScanMode;
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.ScanRootRequest;
import io.justsearch.ipc.SyncDirectoryRequest;
import io.justsearch.ipc.SyncDirectoryResponse;
import io.justsearch.ipc.UnwatchRootRequest;
import io.justsearch.ipc.WatchRootRequest;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task t-20261013-ef3c41, round 8 (refined decision 2) — after the one-time upgrade seeding, only
 * the person's add records a root's volume. A background path that meets a declared root with no
 * volume recorded (the walk, the periodic sync and its volume check, the prune) refuses it as
 * unproven: it records nothing, queues nothing and deletes nothing. The root is declared the way it
 * is after a restart: the Head re-registers its watch without the add marker.
 *
 * <p>Real conditions: a real folder with a file, a real index holding a document of a file that is
 * gone from the folder, and a volume record that exists without a record for the folder, which is
 * what the record looks like once the upgrade seeding is done.
 */
final class UnprovenRootRefusedTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  private static final String ROOT_UNAVAILABLE_REASON = "ROOT_NOT_DIRECTORY";
  private static final String RECORD_FILE = "root-volumes.properties";

  @TempDir Path tempDir;
  private SqliteJobQueue jobQueue;
  private RunningRuntime runtime;
  private IndexGenerationManager.IndexLayout layout;

  @BeforeEach
  void setUp() throws Exception {
    jobQueue = new SqliteJobQueue(tempDir.resolve("jobs.db"));
    jobQueue.open();
    layout = new IndexGenerationManager(tempDir.resolve("indexBase")).initializeOrLoad();
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
        .atPath(layout.activeGenerationPath())
        .withExecutorRegistrations(testLuceneExecutors())
        .open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (runtime != null) {
      runtime.close();
    }
    if (jobQueue != null) {
      jobQueue.close();
    }
  }

  @Test
  void aBackgroundPathMeetingARootWithNoRecordedVolumeRefusesItAndRecordsNothing()
      throws Exception {
    // No volume identity off Windows, nor in a run with the no-volume-identity seam on
    // (NoVolumeIdentityExtension, round 9).
    assumeTrue(WINDOWS && !Boolean.getBoolean("justsearch.test.noVolumeIdentity"),
        "a volume is recorded only where the platform exposes a volume identity");
    Path root = Files.createDirectories(tempDir.resolve("share")).toAbsolutePath().normalize();
    Files.writeString(root.resolve("notes.txt"), "notes in the shared folder");
    String goneId = index(root.resolve("gone.txt"), "a file that is gone from the folder");
    Path record = Files.createDirectories(layout.basePath()).resolve(RECORD_FILE);
    try (OutputStream out = Files.newOutputStream(record)) {
      new Properties().store(out, "the upgrade seeding is done; nothing recorded for the folder");
    }
    long queuedBefore = jobQueue.queueDepth();
    WorkerIngestService worker = service();
    // The root is declared: the Head re-registers its watch after a restart, without the add.
    worker.watchRoot(
        WatchRootRequest.newBuilder().setRootPath(root.toString()).build(), CallContext.none());

    try {
      ScanRootProgress walked = scan(worker, root);
      SyncDirectoryResponse synced = worker.syncDirectory(
          SyncDirectoryRequest.newBuilder().setRootPath(root.toString()).setForce(true).build(),
          CallContext.none());
      boolean volumeRefused = worker.refusesRootVolume(root.toString(), CallContext.none());
      int pruned = worker.pruneMissing(
          PruneRequest.newBuilder().setPathPrefix(root.toString()).build(), CallContext.none())
          .getPrunedCount();

      assertTrue(walked.getTerminalReasonCode().startsWith(ROOT_UNAVAILABLE_REASON),
          "a walk without the add refuses the unproven root: " + walked.getTerminalReasonCode());
      assertTrue(synced.getError().startsWith(ROOT_UNAVAILABLE_REASON),
          "the periodic sync refuses the unproven root: " + synced.getError());
      assertEquals(0, synced.getFilesDeleted(), "the refused sync deletes nothing");
      assertTrue(volumeRefused, "the periodic volume check refuses the unproven root");
      assertEquals(0, pruned, "the prune deletes nothing under the unproven root");
      assertEquals(queuedBefore, jobQueue.queueDepth(), "nothing is queued");
      runtime.commitOps().maybeRefreshBlocking();
      assertNotNull(runtime.documentFieldOps().getDocumentField(goneId, SchemaFields.CONTENT),
          "the indexed document is kept");
      assertEquals(List.of(), recordedUnder(record, root), "no volume is recorded for the root");
    } finally {
      worker.unwatchRoot(
          UnwatchRootRequest.newBuilder().setRootPath(root.toString()).build(), CallContext.none());
    }
  }

  private static ScanRootProgress scan(WorkerIngestService service, Path root) {
    List<ScanRootProgress> frames = new ArrayList<>();
    service.scanRoot(ScanRootRequest.newBuilder().setRootPath(root.toString())
        .setMode(ScanMode.SCAN_MODE_INITIAL).build(), frames::add, CallContext.none());
    assertTrue(!frames.isEmpty() && frames.getLast().getComplete(), "the walk ends with a terminal");
    return frames.getLast();
  }

  private static List<String> recordedUnder(Path record, Path folder) throws Exception {
    Properties entries = new Properties();
    try (InputStream in = Files.newInputStream(record)) {
      entries.load(in);
    }
    String key = folder.toString().toLowerCase(Locale.ROOT);
    List<String> recorded = new ArrayList<>();
    for (String name : entries.stringPropertyNames()) {
      if (name.toLowerCase(Locale.ROOT).contains(key)) {
        recorded.add(name);
      }
    }
    return recorded;
  }

  private String index(Path file, String content) throws Exception {
    String id = PathNormalizer.normalizePath(file.toString());
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, id,
        SchemaFields.DOC_UID, id + "#0",
        SchemaFields.PATH, id,
        SchemaFields.CONTENT, content)));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    return id;
  }

  /** A service over the index and queue: what a restarted worker is. */
  private WorkerIngestService service() {
    return new WorkerIngestService(
        jobQueue, mock(IndexingLoop.class), mock(WorkerSignalBus.class),
        IndexingPacing.unthrottled(), layout.basePath(), layout.activeGenerationPath(),
        runtime, runtime, null, 0L);
  }
}
