/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import io.justsearch.ipc.DeleteByPathRequest;
import io.justsearch.ipc.PruneRequest;
import io.justsearch.ipc.PruneResponse;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task t-20261013-ef3c41, scenario S8: a different volume at the same drive letter, appearing while
 * the app was not running, does not remove the original's documents. Real conditions: one SUBST
 * letter mapped first to a folder on one volume, then, after a simulated restart (a new service
 * over the same index and queue), to a folder of the same name on another volume. Every mapping
 * and folder the test creates is removed before it returns.
 */
final class PruneMissingDifferentVolumeTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

  @TempDir Path tempDir;
  private Path otherVolume;
  private String letter;
  private SqliteJobQueue jobQueue;
  private RunningRuntime runtime;
  private IndexGenerationManager.IndexLayout layout;

  @BeforeEach
  void setUp() throws Exception {
    assumeTrue(WINDOWS, "SUBST drive letters exist on Windows only");
    // The test JVM's temp folder and the module's build folder sit on different volumes on the
    // machines this runs on; the scenario needs that, so it is checked rather than assumed.
    otherVolume = Files.createDirectories(
        Path.of("build", "ef3c41-other-volume-" + System.nanoTime()).toAbsolutePath());
    assumeTrue(!volumeSerial(tempDir).equals(volumeSerial(otherVolume)),
        "needs two different volumes: " + tempDir + " and " + otherVolume);
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
    if (letter != null) {
      run("subst", letter, "/d");
    }
    if (runtime != null) {
      runtime.close();
    }
    if (jobQueue != null) {
      jobQueue.close();
    }
    if (otherVolume != null && Files.exists(otherVolume)) {
      try (Stream<Path> tree = Files.walk(otherVolume)) {
        for (Path p : tree.sorted(Comparator.reverseOrder()).toList()) {
          Files.delete(p);
        }
      }
    }
  }

  @Test
  void aDifferentVolumeAtTheSameLetterAfterARestartPrunesNothing() throws Exception {
    Path stickA = Files.createDirectories(tempDir.resolve("stick-a").resolve("music"));
    Files.writeString(stickA.resolve("song.txt"), "lyrics");
    Files.createDirectories(otherVolume.resolve("music"));
    letter = freeLetter();
    assertEquals(0, run("subst", letter, stickA.getParent().toString()));
    Path root = Path.of(letter + "\\music");
    String id = index(root.resolve("song.txt"));
    assertEquals(0, service().pruneMissing(request(root), CallContext.none()).getPrunedCount(),
        "precondition: nothing is missing on the original stick");

    // The app stops; the stick is swapped for another one that has a music folder too.
    assertEquals(0, run("subst", letter, "/d"));
    assertEquals(0, run("subst", letter, otherVolume.toString()));
    PruneResponse response = service().pruneMissing(request(root), CallContext.none());

    assertEquals(0, response.getPrunedCount(),
        "a different volume at the same letter must not remove the original's documents");
    runtime.commitOps().maybeRefreshBlocking();
    assertNotNull(runtime.documentFieldOps().getDocumentField(id, SchemaFields.CONTENT));
    assertFalse(response.getError().isEmpty(), "the run reports itself incomplete");
  }

  @Test
  void theSameVolumeAfterARestartStillPrunesAGenuineDelete() throws Exception {
    // P1 (must not break).
    Path stick = Files.createDirectories(tempDir.resolve("stick").resolve("music"));
    Path gone = Files.writeString(stick.resolve("gone.txt"), "old");
    letter = freeLetter();
    assertEquals(0, run("subst", letter, stick.getParent().toString()));
    Path root = Path.of(letter + "\\music");
    String id = index(root.resolve("gone.txt"));
    assertEquals(0, service().pruneMissing(request(root), CallContext.none()).getPrunedCount());

    Files.delete(gone);
    PruneResponse response = service().pruneMissing(request(root), CallContext.none());

    assertEquals(1, response.getPrunedCount());
    runtime.commitOps().maybeRefreshBlocking();
    assertNull(runtime.documentFieldOps().getDocumentField(id, SchemaFields.CONTENT));
  }

  @Test
  void aRootRemovedAndAddedAgainOnAnotherVolumeIsPrunedThere() throws Exception {
    // Once the person removes the folder, the recorded volume no longer stands.
    Path stickA = Files.createDirectories(tempDir.resolve("stick-a").resolve("music"));
    Files.writeString(stickA.resolve("song.txt"), "lyrics");
    letter = freeLetter();
    assertEquals(0, run("subst", letter, stickA.getParent().toString()));
    Path root = Path.of(letter + "\\music");
    index(root.resolve("song.txt"));
    assertEquals(0, service().pruneMissing(request(root), CallContext.none()).getPrunedCount());
    service().deleteByPath(
        DeleteByPathRequest.newBuilder().setPath(root.toString()).build(), CallContext.none());

    Path stickB = Files.createDirectories(otherVolume.resolve("music"));
    Path gone = Files.writeString(stickB.resolve("gone.txt"), "old");
    assertEquals(0, run("subst", letter, "/d"));
    assertEquals(0, run("subst", letter, otherVolume.toString()));
    String id = index(root.resolve("gone.txt"));
    assertEquals(0, service().pruneMissing(request(root), CallContext.none()).getPrunedCount());
    Files.delete(gone);

    PruneResponse response = service().pruneMissing(request(root), CallContext.none());

    assertEquals(1, response.getPrunedCount(), response.getError());
    runtime.commitOps().maybeRefreshBlocking();
    assertNull(runtime.documentFieldOps().getDocumentField(id, SchemaFields.CONTENT));
  }

  /** A fresh service over the same index and queue: what a restart of the worker sees. */
  private WorkerIngestService service() {
    return new WorkerIngestService(
        jobQueue, mock(IndexingLoop.class), mock(WorkerSignalBus.class),
        IndexingPacing.unthrottled(), layout.basePath(), layout.activeGenerationPath(),
        runtime, runtime, null, 0L);
  }

  private static PruneRequest request(Path root) {
    return PruneRequest.newBuilder().setPathPrefix(root.toString()).build();
  }

  private String index(Path file) throws Exception {
    String id = PathNormalizer.normalizePath(file.toString());
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, id,
        SchemaFields.DOC_UID, id + "#0",
        SchemaFields.PATH, id,
        SchemaFields.CONTENT, Files.readString(file))));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    return id;
  }

  private static Object volumeSerial(Path path) throws Exception {
    return Files.getFileStore(path).getAttribute("volume:vsn");
  }

  private static String freeLetter() {
    Set<Character> used =
        java.util.Arrays.stream(File.listRoots())
            .map(r -> Character.toUpperCase(r.getPath().charAt(0)))
            .collect(Collectors.toSet());
    for (char c = 'W'; c >= 'M'; c--) {
      if (!used.contains(c)) return c + ":";
    }
    assumeTrue(false, "no free drive letter for SUBST");
    throw new IllegalStateException("unreachable");
  }

  private static int run(String... command) throws Exception {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    process.getInputStream().readAllBytes();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "subst did not finish");
    return process.exitValue();
  }
}
