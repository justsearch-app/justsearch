/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.justsearch.ipc.PruneRequest;
import io.justsearch.ipc.ScanMode;
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.ScanRootRequest;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task t-20261013-ef3c41, round 4, scenario S8 — a root whose drive letter now holds another
 * volume shows the root-level state, rather than reading as an available folder whose files are
 * the original's.
 *
 * <p>Real conditions: a SUBST letter is mapped to a folder on one volume, its drive root is scanned
 * and pruned once (which records the volume), and after a restart (a new service over the same
 * index and queue) the letter holds a folder on another volume with a different file. The Worker's
 * walk of the root is what the Head turns into the root's state: a terminal reason of {@code
 * ROOT_NOT_DIRECTORY} is the one it shows as unavailable. Every mapping and folder this test
 * creates is removed before it returns.
 */
final class DifferentVolumeRootStateTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  /** The root-level reason the Head shows as "unavailable". */
  private static final String ROOT_UNAVAILABLE_REASON = "ROOT_NOT_DIRECTORY";

  @TempDir Path tempDir;
  private Path otherVolume;
  private String letter;
  private LetterLock letterLock;
  private SqliteJobQueue jobQueue;
  private RunningRuntime runtime;
  private IndexGenerationManager.IndexLayout layout;

  @BeforeEach
  void setUp() throws Exception {
    assumeTrue(WINDOWS, "SUBST drive letters exist on Windows only");
    otherVolume = folderOnAnotherVolumeThan(tempDir, "ef3c41-scan-volume-");
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
  void aRootWhoseLetterNowHoldsAnotherVolumeShowsTheRootLevelState() throws Exception {
    Path root = mapLetterTo(Files.createDirectory(tempDir.resolve("stick-a")));
    Files.writeString(root.resolve("notes.txt"), "on the first stick");
    addRoot(root);
    assertEquals("", scan(service(), root).getTerminalReasonCode(), "precondition: first walk");
    assertEquals(0, service().pruneMissing(request(root), CallContext.none()).getPrunedCount(),
        "precondition: the root's first prune finds nothing missing");
    long queuedBefore = jobQueue.queueDepth();

    Files.writeString(otherVolume.resolve("someone-else.txt"), "on another volume");
    remapLetterTo(otherVolume);
    ScanRootProgress terminal = scan(service(), root);

    assertEquals(ROOT_UNAVAILABLE_REASON, terminal.getTerminalReasonCode(),
        "a root whose letter now holds another volume shows the root-level unavailable state");
    assertEquals(0, terminal.getFilesAdmitted(),
        "the other volume's files are not taken for the root's own");
    assertEquals(queuedBefore, jobQueue.queueDepth());
  }

  @Test
  void aRootOnTheSameVolumeStillWalksNormally() throws Exception {
    // S8 / P1 (must not break): the volume check does not hold back the root's own volume.
    Path root = mapLetterTo(Files.createDirectory(tempDir.resolve("stick-b")));
    Files.writeString(root.resolve("notes.txt"), "on the stick");
    addRoot(root);
    assertEquals("", scan(service(), root).getTerminalReasonCode());
    assertEquals(0, service().pruneMissing(request(root), CallContext.none()).getPrunedCount());

    Files.writeString(root.resolve("added.txt"), "added later");
    ScanRootProgress terminal = scan(service(), root);

    assertEquals("", terminal.getTerminalReasonCode(), "the root's own volume walks normally");
    assertEquals(2, terminal.getFilesAdmitted());
  }

  private static ScanRootProgress scan(WorkerIngestService service, Path root) {
    List<ScanRootProgress> frames = new ArrayList<>();
    service.scanRoot(ScanRootRequest.newBuilder().setRootPath(root.toString())
        .setMode(ScanMode.SCAN_MODE_INITIAL).build(), frames::add, CallContext.none());
    assertTrue(!frames.isEmpty() && frames.getLast().getComplete(), "the walk ends with a terminal");
    return frames.getLast();
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

  private Path mapLetterTo(Path target) throws Exception {
    Set<Character> used =
        java.util.Arrays.stream(File.listRoots())
            .map(r -> Character.toUpperCase(r.getPath().charAt(0)))
            .collect(Collectors.toSet());
    for (char c = 'T'; c >= 'M'; c--) {
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
