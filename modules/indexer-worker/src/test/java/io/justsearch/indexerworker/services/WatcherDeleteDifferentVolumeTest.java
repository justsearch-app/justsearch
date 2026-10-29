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
import io.justsearch.ipc.PruneRequest;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #758 — the watcher's delete confirmation asks the same
 * root-identity question the prune asks.
 *
 * <p>A SUBST letter is mapped to a folder on one volume and its drive root is indexed and pruned
 * once (which records the volume). After a restart (a new service over the same index and queue)
 * the letter holds a folder on another volume, without the file. A witnessed DELETE for the file
 * is then confirmed. Before this change the confirmation only asked whether the folder was listable,
 * so the document was removed. Every mapping and folder this test creates is removed before it
 * returns.
 */
final class WatcherDeleteDifferentVolumeTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

  @TempDir Path tempDir;
  private Path otherVolume;
  private String letter;
  private LetterLock letterLock;
  private SqliteJobQueue jobQueue;
  private RunningRuntime runtime;
  private IndexGenerationManager.IndexLayout layout;

  @BeforeEach
  void setUp() throws Exception {
    // No volume identity off Windows, nor in a run with the no-volume-identity seam on
    // (NoVolumeIdentityExtension, round 9).
    assumeTrue(WINDOWS && !Boolean.getBoolean("justsearch.test.noVolumeIdentity"),
        "SUBST drive letters and volume identity exist on Windows only");
    otherVolume = folderOnAnotherVolumeThan(tempDir, "ef3c41-watch-volume-");
    assumeTrue(otherVolume != null, "needs a second volume besides " + tempDir);
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
      try {
        run("subst", letter, "/d");
      } finally {
        letterLock.close();
      }
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
  void aWatcherDeleteOnAnotherVolumeAtTheSameLetterRemovesNothing() throws Exception {
    Path root = mapLetterTo(Files.createDirectory(tempDir.resolve("stick-a")));
    Path file = Files.writeString(root.resolve("notes.txt"), "on the first stick");
    String id = index(file);
    addRoot(root);
    assertEquals(0, service().pruneMissing(request(root), CallContext.none()).getPrunedCount(),
        "precondition: the root's first prune finds nothing missing");

    remapLetterTo(otherVolume);
    AtomicBoolean effect = new AtomicBoolean();
    service().acceptWatcherDelete(id, () -> removeDocument(id, effect));

    assertFalse(effect.get(),
        "another volume at the same letter cannot confirm that the file was deleted");
    runtime.commitOps().maybeRefreshBlocking();
    assertNotNull(runtime.documentFieldOps().getDocumentField(id, SchemaFields.CONTENT),
        "the original's document stays searchable");
  }

  @Test
  void aWatcherDeleteOnTheSameVolumeStillRemovesTheDocument() throws Exception {
    // P1 (must not break).
    Path root = mapLetterTo(Files.createDirectory(tempDir.resolve("stick-b")));
    Path file = Files.writeString(root.resolve("notes.txt"), "obsolete");
    String id = index(file);
    addRoot(root);
    assertEquals(0, service().pruneMissing(request(root), CallContext.none()).getPrunedCount());

    Files.delete(file);
    AtomicBoolean effect = new AtomicBoolean();
    service().acceptWatcherDelete(id, () -> removeDocument(id, effect));

    assertTrue(effect.get(), "a confirmed delete runs its effect");
    runtime.commitOps().maybeRefreshBlocking();
    assertNull(runtime.documentFieldOps().getDocumentField(id, SchemaFields.CONTENT));
  }

  /** The watcher's direct effect outside a migration: the document leaves the index. */
  private void removeDocument(String id, AtomicBoolean effect) {
    effect.set(true);
    runtime.indexingCoordinator().deleteByIdAndChunks(id);
    runtime.commitOps().commitAndTrack();
  }

  /**
   * The person's add of {@code root}: its watch registration with the add marker. Round 8 (refined
   * decision 2): only the add records the root's volume; a build without the marker recorded it at
   * the first registration, walk or prune.
   */
  private void addRoot(Path root) {
    var request = io.justsearch.ipc.WatchRootRequest.newBuilder().setRootPath(root.toString());
    var marker = io.justsearch.ipc.WatchRootRequest.getDescriptor().findFieldByName("root_added");
    if (marker != null) {
      request.setField(marker, Boolean.TRUE);
    }
    assertTrue(service().watchRoot(request.build(), CallContext.none()).getWatching(),
        "precondition: the person adds the root");
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

  private Path mapLetterTo(Path target) throws Exception {
    Set<Character> used =
        java.util.Arrays.stream(File.listRoots())
            .map(r -> Character.toUpperCase(r.getPath().charAt(0)))
            .collect(Collectors.toSet());
    for (char c = 'U'; c >= 'M'; c--) {
      if (used.contains(c)) continue;
      LetterLock held = LetterLock.tryHold(c);
      if (held == null) continue;
      String candidate = c + ":";
      if (run("subst", candidate, target.toString()) == 0) {
        letter = candidate;
        letterLock = held;
        return Path.of(letter + "\\");
      }
      held.close();
    }
    assumeTrue(false, "no free drive letter for SUBST");
    throw new IllegalStateException("unreachable");
  }

  private void remapLetterTo(Path target) throws Exception {
    assertEquals(0, run("subst", letter, "/d"), "could not unmap " + letter);
    assertEquals(0, run("subst", letter, target.toString()), "could not remap " + letter);
  }

  /**
   * A new folder on a volume other than {@code near}'s: the module's build folder when that is on
   * another volume, otherwise the first other local volume that takes a new folder at its root.
   * {@code null} when this machine offers no second volume.
   */
  private static Path folderOnAnotherVolumeThan(Path near, String prefix) {
    Object volume;
    try {
      volume = volumeSerial(near);
    } catch (IOException | UnsupportedOperationException unknown) {
      return null;
    }
    List<Path> bases = new ArrayList<>();
    bases.add(Path.of("build").toAbsolutePath());
    for (File drive : File.listRoots()) {
      bases.add(drive.toPath());
    }
    for (Path base : bases) {
      try {
        if (Files.isDirectory(base) && !volume.equals(volumeSerial(base))) {
          return Files.createTempDirectory(base, prefix);
        }
      } catch (IOException | UnsupportedOperationException | SecurityException notUsable) {
        // try the next candidate
      }
    }
    return null;
  }

  private static Object volumeSerial(Path path) throws IOException {
    return Files.getFileStore(path).getAttribute("volume:vsn");
  }

  private static int run(String... command) throws Exception {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    process.getInputStream().readAllBytes();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "subst did not finish");
    return process.exitValue();
  }

  /**
   * A hold on one SUBST drive letter shared by every test process on this machine: a lock on a
   * per-letter file in the user's temp folder, taken before the letter is mapped and released only
   * after it is unmapped, so SUBST tests running at once (Gradle forks, or modules built together)
   * never pick, remap into or take over the same letter.
   */
  private static final class LetterLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private LetterLock(FileChannel channel, FileLock lock) {
      this.channel = channel;
      this.lock = lock;
    }

    /** The hold on {@code letter}, or {@code null} while another test holds it. */
    static LetterLock tryHold(char letter) throws IOException {
      Path folder =
          Path.of(System.getenv().getOrDefault("TEMP", System.getProperty("java.io.tmpdir")));
      FileChannel channel = FileChannel.open(folder.resolve("justsearch-subst-" + letter + ".lock"),
          StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      FileLock lock = null;
      try {
        lock = channel.tryLock();
      } catch (OverlappingFileLockException heldInThisProcess) {
        // Another test class in this JVM holds the letter.
      } finally {
        if (lock == null) {
          channel.close();
        }
      }
      return lock == null ? null : new LetterLock(channel, lock);
    }

    @Override
    public void close() throws IOException {
      try {
        lock.release();
      } finally {
        channel.close();
      }
    }
  }
}
