/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.justsearch.ipc.UnwatchRootRequest;
import io.justsearch.ipc.WatchRootRequest;
import java.io.InputStream;
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
 * Task t-20261013-ef3c41, round 8b (decision 2) — no walk ever writes a volume record; only a
 * deliberate add does. After a Worker restart, and before the Head registers its roots again, the
 * Worker cannot tell which root a walked folder belongs to. A walk of a folder with no volume
 * recorded for it then is deferred: it records nothing, queues nothing, and a prune under it
 * deletes nothing. Once the roots are registered again, a root the person added (recorded) is
 * walked normally, and a root never proven stays refused with nothing deleted.
 *
 * <p>Real conditions: real folders and files, a real index holding a document of a file that is
 * gone from its folder, the Worker's real volume record file, and a new service over the same index
 * and queue standing for the restarted Worker.
 */
final class WalkBeforeReregistrationTest
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
  void aWalkAfterARestartBeforeTheRootsAreRegisteredRecordsAndDeletesNothingThenProceeds()
      throws Exception {
    // No volume identity off Windows, nor in a run with the no-volume-identity seam on
    // (NoVolumeIdentityExtension, round 9).
    assumeTrue(WINDOWS && !Boolean.getBoolean("justsearch.test.noVolumeIdentity"),
        "a volume is recorded only where the platform exposes a volume identity");
    Path letters = Files.createDirectories(tempDir.resolve("letters")).toAbsolutePath().normalize();
    Path drafts = Files.createDirectories(tempDir.resolve("drafts")).toAbsolutePath().normalize();
    Files.writeString(drafts.resolve("notes.txt"), "notes in the drafts folder");
    String goneId = index(drafts.resolve("gone.txt"), "a draft that is gone from the folder");
    // Before the restart the person added "letters", which records its volume; "drafts" was
    // declared but never proven, so nothing is recorded for it.
    WorkerIngestService before = service();
    ScanRootProgress added = scan(before, letters, true);
    assertEquals("", added.getTerminalReasonCode(), "precondition: the person's add walks");
    Path record = layout.basePath().resolve(RECORD_FILE);
    assertFalse(recordedUnder(record, letters).isEmpty(),
        "precondition: the person's add records the root's volume");
    // A new file arrives while the Worker is down.
    Files.writeString(letters.resolve("letter.txt"), "a letter written while the Worker was down");

    WorkerIngestService restarted = service();
    long queuedBefore = jobQueue.queueDepth();

    ScanRootProgress early = scan(restarted, drafts, false);
    int prunedEarly = prune(restarted, drafts);

    assertTrue(early.getTerminalReasonCode().startsWith(ROOT_UNAVAILABLE_REASON),
        "a walk before re-registration of a folder with nothing recorded is deferred: "
            + early.getTerminalReasonCode());
    assertEquals(List.of(), recordedUnder(record, drafts), "the early walk records nothing");
    assertEquals(queuedBefore, jobQueue.queueDepth(), "the early walk queues nothing");
    assertEquals(0, prunedEarly, "a prune before re-registration deletes nothing");
    assertKept(goneId);

    // The Head registers its roots again after the restart, without the add marker.
    for (Path root : List.of(letters, drafts)) {
      restarted.watchRoot(
          WatchRootRequest.newBuilder().setRootPath(root.toString()).build(), CallContext.none());
    }
    try {
      ScanRootProgress lettersWalk = scan(restarted, letters, false);
      ScanRootProgress draftsWalk = scan(restarted, drafts, false);
      int prunedLater = prune(restarted, drafts);

      assertEquals("", lettersWalk.getTerminalReasonCode(),
          "after re-registration the recorded root is walked normally");
      assertEquals(queuedBefore + 1, jobQueue.queueDepth(),
          "the recorded root's new file is queued, nothing of the unproven root");
      assertTrue(draftsWalk.getTerminalReasonCode().startsWith(ROOT_UNAVAILABLE_REASON),
          "the root never proven stays refused: " + draftsWalk.getTerminalReasonCode());
      assertEquals(0, prunedLater, "nothing is deleted under the unproven root");
      assertEquals(List.of(), recordedUnder(record, drafts), "nothing is recorded for it");
      assertKept(goneId);
    } finally {
      for (Path root : List.of(letters, drafts)) {
        restarted.unwatchRoot(
            UnwatchRootRequest.newBuilder().setRootPath(root.toString()).build(),
            CallContext.none());
      }
    }
  }

  private void assertKept(String id) throws Exception {
    runtime.commitOps().maybeRefreshBlocking();
    assertNotNull(runtime.documentFieldOps().getDocumentField(id, SchemaFields.CONTENT),
        "the indexed document is kept");
  }

  private static int prune(WorkerIngestService service, Path root) {
    return service.pruneMissing(
        PruneRequest.newBuilder().setPathPrefix(root.toString()).build(), CallContext.none())
        .getPrunedCount();
  }

  private static ScanRootProgress scan(WorkerIngestService service, Path root, boolean added) {
    List<ScanRootProgress> frames = new ArrayList<>();
    ScanRootRequest.Builder request = ScanRootRequest.newBuilder().setRootPath(root.toString())
        .setMode(ScanMode.SCAN_MODE_INITIAL);
    var marker = request.getDescriptorForType().findFieldByName("root_added");
    if (added && marker != null) {
      request.setField(marker, Boolean.TRUE);
    }
    service.scanRoot(request.build(), frames::add, CallContext.none());
    assertTrue(!frames.isEmpty() && frames.getLast().getComplete(), "the walk ends with a terminal");
    return frames.getLast();
  }

  private static List<String> recordedUnder(Path record, Path folder) throws Exception {
    List<String> recorded = new ArrayList<>();
    if (!Files.exists(record)) {
      return recorded;
    }
    Properties entries = new Properties();
    try (InputStream in = Files.newInputStream(record)) {
      entries.load(in);
    }
    String key = folder.toString().toLowerCase(Locale.ROOT);
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

  /** A service over the index and queue: a Worker, and after a restart the restarted one. */
  private WorkerIngestService service() {
    return new WorkerIngestService(
        jobQueue, mock(IndexingLoop.class), mock(WorkerSignalBus.class),
        IndexingPacing.unthrottled(), layout.basePath(), layout.activeGenerationPath(),
        runtime, runtime, null, 0L);
  }
}
