/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import io.justsearch.ipc.PruneResponse;
import io.justsearch.ipc.ScanMode;
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.ScanRootRequest;
import io.justsearch.ipc.WatchRootRequest;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Present files remain admissible without a volume identity; absence never authorizes pruning. */
final class NoVolumeIdentityRootTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  @TempDir Path tempDir;
  private boolean identityWasUnavailable;
  private SqliteJobQueue jobQueue;
  private RunningRuntime runtime;
  private IndexGenerationManager.IndexLayout layout;

  @BeforeEach
  void setUp() throws Exception {
    identityWasUnavailable = RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.getAndSet(true);
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
    try {
      if (runtime != null) {
        runtime.close();
      }
      if (jobQueue != null) {
        jobQueue.close();
      }
    } finally {
      RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.set(identityWasUnavailable);
    }
  }

  @Test
  void withNoVolumeIdentityARootAdmitsPresentFilesAndKeepsUnprovenDeletions()
      throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("documents"));
    Path gone = Files.writeString(root.resolve("gone.txt"), "an obsolete draft");
    String goneId = index(gone);
    String keptId = index(Files.writeString(root.resolve("kept.txt"), "the current draft"));
    WorkerIngestService service = service();
    service.seedRootVolumesAfterUpgrade(List.of(root));
    assertTrue(service.watchRoot(watch(root, true), CallContext.none()).getWatching(),
        "the person adds the root");

    assertEquals("", service.rootVolumeRefusal(root.toString(), CallContext.none()),
        "the root never reads unavailable for lack of a volume record");
    assertFalse(service.rootOnRecordedVolume(root.toString()),
        "present-file admission never supplies missing volume proof");

    // A restart: the Head registers its roots again, without the add marker.
    WorkerIngestService restarted = service();
    assertTrue(restarted.watchRoot(watch(root, false), CallContext.none()).getWatching(),
        "the root is watched again at once, not held for its volume");
    Files.writeString(root.resolve("added.txt"), "a note added later");
    Files.delete(gone);
    ScanRootProgress terminal = scan(restarted, root);
    assertEquals("", terminal.getTerminalReasonCode(), "the root is walked, not deferred");
    assertEquals(2, terminal.getFilesAdmitted(), "its present files are admitted");

    PruneResponse pruned = restarted.pruneMissing(
        PruneRequest.newBuilder().setPathPrefix(root.toString()).build(), CallContext.none());
    assertTrue(pruned.getError().startsWith("prune_incomplete"), "the prune reports missing proof");
    assertEquals(0, pruned.getPrunedCount(), "absence without volume evidence keeps the family");
    runtime.commitOps().maybeRefreshBlocking();
    assertNotNull(runtime.documentFieldOps().getDocumentField(goneId, SchemaFields.CONTENT));
    assertNotNull(runtime.documentFieldOps().getDocumentField(keptId, SchemaFields.CONTENT),
        "the file still on disk keeps its document");
    assertEquals("", restarted.rootVolumeRefusal(root.toString(), CallContext.none()),
        "the root still never reads unavailable");
    assertFalse(restarted.rootOnRecordedVolume(root.toString()));
    assertFalse(recordedVolumes().stream().anyMatch(volume -> !volume.isBlank()),
        "no volume is recorded where the platform has none");
  }

  private WorkerIngestService service() {
    return new WorkerIngestService(
        jobQueue, mock(IndexingLoop.class), mock(WorkerSignalBus.class),
        IndexingPacing.unthrottled(), layout.basePath(), layout.activeGenerationPath(),
        runtime, runtime, null, 0L);
  }

  private static WatchRootRequest watch(Path root, boolean added) {
    return WatchRootRequest.newBuilder().setRootPath(root.toString()).setRootAdded(added).build();
  }

  private static ScanRootProgress scan(WorkerIngestService service, Path root) {
    List<ScanRootProgress> frames = new ArrayList<>();
    service.scanRoot(ScanRootRequest.newBuilder().setRootPath(root.toString())
        .setMode(ScanMode.SCAN_MODE_INITIAL).build(), frames::add, CallContext.none());
    assertTrue(!frames.isEmpty() && frames.getLast().getComplete(), "the walk ends with a terminal");
    return frames.getLast();
  }

  private String index(Path file) throws Exception {
    String id = PathNormalizer.normalizePath(file.toAbsolutePath().toString());
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, id,
        SchemaFields.DOC_UID, id + "#0",
        SchemaFields.PATH, id,
        SchemaFields.CONTENT, Files.readString(file))));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    return id;
  }

  /** The volumes the Worker's record names, empty when it has no record file. */
  private List<String> recordedVolumes() throws Exception {
    Path record = layout.basePath().resolve(RootVolumeLedger.FILE_NAME);
    List<String> volumes = new ArrayList<>();
    if (Files.exists(record)) {
      Properties entries = new Properties();
      try (InputStream in = Files.newInputStream(record)) {
        entries.load(in);
      }
      for (String name : entries.stringPropertyNames()) {
        volumes.add(entries.getProperty(name));
      }
    }
    return volumes;
  }
}
