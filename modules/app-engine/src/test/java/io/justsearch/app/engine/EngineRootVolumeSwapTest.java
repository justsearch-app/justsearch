/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.services.worker.CancelToken;
import io.justsearch.indexerworker.queue.JobQueue;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * #758 — a drive letter that holds another volume does not put that
 * volume's files in the place of the original's, on the whole running Engine.
 *
 * <p>Real conditions: a SUBST letter is mapped to a folder on one volume ("stick A") whose
 * {@code docs} folder is a watched root, and that root is indexed through the Engine's own add,
 * walk, watcher, sync and admission. The letter is then unmapped and mapped again, to a folder on
 * another volume ("stick B") whose {@code docs} folder holds a same-named file with different text
 * and a file of its own, either across an Engine restart or while it runs. A second watched root
 * on the temp volume is the control: its marker becoming findable shows the pipeline ran, so a B
 * marker that stays unfindable was refused, not merely slow. This also covers the reindex button's
 * recorded walk and a restart while the root reads unavailable. Every mapping and folder this test
 * creates is removed before it returns.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
final class EngineRootVolumeSwapTest {
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  private static final long INDEXED_MS = 90_000L;
  /** After the control is findable, how long a refused B marker is given to (wrongly) appear. */
  private static final long SETTLE_MS = 5_000L;

  @TempDir Path tempDir;
  private Path otherVolume;
  private Path stickA;
  private Path stickB;
  private Path control;
  private String letter;
  private LetterLock letterLock;
  private EngineTestHarness harness;
  private String previousDataDir;
  private int controlMarks;

  @BeforeEach
  void setUp() throws Exception {
    assumeTrue(WINDOWS, "SUBST drive letters exist on Windows only");
    // The scenario needs a second volume besides the test JVM's temp folder; it is found, not
    // assumed, and a machine without one skips the test rather than passing it.
    otherVolume = folderOnAnotherVolumeThan(tempDir, "ef3c41-engine-volume-");
    assumeTrue(otherVolume != null, "needs a second volume besides " + tempDir);
    stickA = Files.createDirectories(tempDir.resolve("stick-a").resolve("docs")).getParent();
    stickB = Files.createDirectories(otherVolume.resolve("docs")).getParent();
    control = Files.createDirectories(tempDir.resolve("control"));
    // The Head keeps its watched roots under the data directory it resolves itself.
    previousDataDir = System.getProperty("justsearch.data.dir");
    System.setProperty("justsearch.data.dir", tempDir.resolve("data").toString());
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      if (harness != null) {
        harness.close();
        harness = null;
      }
    } finally {
      if (previousDataDir == null) System.clearProperty("justsearch.data.dir");
      else System.setProperty("justsearch.data.dir", previousDataDir);
      if (letter != null) {
        try {
          run("subst", letter, "/d");
        } finally {
          letterLock.close();
        }
        letter = null;
      }
      if (otherVolume != null && Files.exists(otherVolume)) {
        try (Stream<Path> tree = Files.walk(otherVolume)) {
          for (Path p : tree.sorted(Comparator.reverseOrder()).toList()) {
            Files.delete(p);
          }
        }
      }
    }
  }

  @Test
  void aRestartOntoAnotherVolumeKeepsTheOriginalText() throws Exception {
    Path root = indexOnStickA();
    harness.close();
    harness = null;
    putStickBInPlace();
    remapLetterTo(stickB);

    harness = EngineTestHarness.start(tempDir.resolve("data"));
    awaitIndexHalfReady();
    // What a boot does, then what the periodic sync and a re-walk do within the next minute.
    harness.client().reindexPersistedRoots(TestEngineContexts.BACKGROUND);
    harness.client().syncDirectory(root.toString(), false, TestEngineContexts.BACKGROUND);
    harness.client().reindexWatchedRoots(false, TestEngineContexts.BACKGROUND);
    awaitControl();

    assertFalse(harness.awaitSearchable("pangolin", SETTLE_MS),
        "the other volume's same-named file must not replace the original's text");
    assertFalse(harness.awaitSearchable("okapi", 1_000),
        "the other volume's own files must not be indexed into the root");
    assertTrue(harness.awaitSearchable("marmoset", 10_000),
        "the original's text stays searchable");
  }

  @Test
  void aSwapWhileRunningAddsNothingAndShowsTheRootUnavailableAtOnce() throws Exception {
    Path root = indexOnStickA();
    putStickBInPlace();
    remapLetterTo(stickB);

    // The periodic sync's own call: force=false, the watched root.
    harness.client().syncDirectory(root.toString(), false, TestEngineContexts.BACKGROUND);

    assertEquals("ROOT_NOT_DIRECTORY", walkError(root),
        "the root reads unavailable as soon as the sync refused it, without a restart");
    awaitControl();
    assertFalse(harness.awaitSearchable("okapi", SETTLE_MS),
        "the periodic sync must add nothing from the other volume");
    assertFalse(harness.awaitSearchable("pangolin", 1_000),
        "the other volume's same-named file must not replace the original's text");
    assertTrue(harness.awaitSearchable("marmoset", 10_000),
        "the original's text stays searchable");
  }

  @Test
  void watcherEventsOnAnotherVolumeIndexNothing() throws Exception {
    Path root = indexOnStickA();
    harness.close();
    harness = null;
    putStickBInPlace();
    remapLetterTo(stickB);
    harness = EngineTestHarness.start(tempDir.resolve("data"));
    awaitIndexHalfReady();
    harness.client().reindexPersistedRoots(TestEngineContexts.BACKGROUND);
    awaitRoot(control, r -> r.walkCompleted(), "the control root's boot walk");

    // A create and a change on the other volume while it is mounted at the root's letter.
    Files.writeString(root.resolve("quokka.txt"), "quokka lives on the other stick");
    Files.writeString(root.resolve("alpha.txt"), "tapir changed the other stick's file");
    awaitControl();

    assertFalse(harness.awaitSearchable("quokka", SETTLE_MS),
        "a create event on the other volume must index nothing");
    assertFalse(harness.awaitSearchable("tapir", 1_000),
        "a change event on the other volume must index nothing");
    assertTrue(harness.awaitSearchable("marmoset", 10_000),
        "the original's text stays searchable");
  }

  @Test
  void watchingResumesWithoutARestartWhenTheOriginalVolumeReturnsAfterABoot() throws Exception {
    Path root = indexOnStickA();
    harness.close();
    harness = null;
    remapLetterTo(stickB);
    harness = EngineTestHarness.start(tempDir.resolve("data"));
    awaitIndexHalfReady();
    harness.client().reindexPersistedRoots(TestEngineContexts.BACKGROUND);
    awaitRoot(control, r -> r.walkCompleted(), "the control root's boot walk");

    remapLetterTo(stickA);
    // The periodic sync is what notices the original is back.
    harness.client().syncDirectory(root.toString(), false, TestEngineContexts.BACKGROUND);
    Files.writeString(root.resolve("alpha.txt"), "capybara was written after the stick returned");

    assertTrue(harness.awaitSearchable("capybara", INDEXED_MS),
        "a change on the returned volume is indexed by the watcher without a restart");
    IndexingService.WatchedRoot state = rootState(root);
    assertNull(state.walkError(), "the root reads indexed again once its volume is back");
    assertNotNull(state.lastIndexed(), "the root reads indexed again once its volume is back");
  }

  @Test
  void theOriginalVolumeReturningIsIndexedAndPrunedAsBefore() throws Exception {
    Path root = indexOnStickA();
    Files.writeString(root.resolve("gone.txt"), "wombat will be deleted on the original");
    assertTrue(harness.awaitSearchable("wombat", INDEXED_MS), "precondition: gone.txt is indexed");
    putStickBInPlace();
    remapLetterTo(stickB);
    harness.client().syncDirectory(root.toString(), false, TestEngineContexts.BACKGROUND);

    remapLetterTo(stickA);
    harness.client().syncDirectory(root.toString(), false, TestEngineContexts.BACKGROUND);
    Files.writeString(root.resolve("alpha.txt"), "capybara is a real change on the original");
    Files.delete(root.resolve("gone.txt"));
    harness.client().syncDirectory(root.toString(), false, TestEngineContexts.BACKGROUND);

    assertTrue(harness.awaitSearchable("capybara", INDEXED_MS),
        "a real change on the original volume is indexed");
    assertTrue(harness.awaitNotSearchable("wombat", INDEXED_MS),
        "a genuine deletion on the original volume is pruned");
    IndexingService.WatchedRoot state = rootState(root);
    assertNull(state.walkError(), "the root reads indexed once its volume is back");
    assertNotNull(state.lastIndexed(), "the root reads indexed once its volume is back");
  }

  @Test
  void aReindexRefusedOnAnotherVolumeShowsTheRootUnavailableAtOnce() throws Exception {
    Path root = indexOnStickA();
    Instant indexedAt = rootState(root).lastIndexed();
    putStickBInPlace();
    remapLetterTo(stickB);

    // The walk the reindex button's recorded operation runs for this watched root.
    var client = (EngineKnowledgeClient) harness.client();
    var plan = new RecordedRootPlan(client.captureServingGeneration(TestEngineContexts.BACKGROUND),
        List.of(new RecordedRootPlan.Root(root, "documents", false, false, List.of(), List.of())));
    JobQueue.WalkEnumerationOutcome outcome = client
        .enumerateWatchedRecordedRoot(plan, "ef3c41-reindex-" + System.nanoTime(), 1L,
            TestEngineContexts.FOREGROUND, new CancelToken())
        .toCompletableFuture().get(INDEXED_MS, TimeUnit.MILLISECONDS);

    assertEquals(JobQueue.WalkEnumerationOutcome.FAILED, outcome,
        "precondition: the Worker refused the reindex walk");
    assertEquals("ROOT_NOT_DIRECTORY", walkError(root),
        "the root reads unavailable as soon as the reindex walk is refused");
    assertEquals(indexedAt, rootState(root).lastIndexed(), "the last-indexed time is kept");
    awaitControl();
    assertFalse(harness.awaitSearchable("okapi", SETTLE_MS),
        "the reindex walk must add nothing from the other volume");
    assertTrue(harness.awaitSearchable("marmoset", 10_000),
        "the original's text stays searchable");
  }

  @Test
  void aRestartWhileUnavailableKeepsTheLastIndexedTime() throws Exception {
    Path root = indexOnStickA();
    Instant indexedAt = rootState(root).lastIndexed();
    harness.close();
    harness = null;
    remapLetterTo(stickB);

    harness = EngineTestHarness.start(tempDir.resolve("data"));
    awaitIndexHalfReady();
    harness.client().reindexPersistedRoots(TestEngineContexts.BACKGROUND);
    awaitRoot(root, r -> "ROOT_NOT_DIRECTORY".equals(r.walkError()),
        "precondition: the boot walk of the root on another volume");

    assertEquals(indexedAt, rootState(root).lastIndexed(),
        "after a restart while unavailable the root still shows when it was last indexed");
  }

  /** Maps the letter to stick A, starts the Engine and indexes {@code docs} there plus the control. */
  private Path indexOnStickA() throws Exception {
    Files.writeString(stickA.resolve("docs").resolve("alpha.txt"), "marmoset lives on stick A");
    mapLetterTo(stickA);
    Path root = Path.of(letter + "\\docs");
    harness = EngineTestHarness.start(tempDir.resolve("data"));
    awaitIndexHalfReady();
    harness.client().addWatchedRoot("documents", root, TestEngineContexts.FOREGROUND);
    harness.client().addWatchedRoot("control", control, TestEngineContexts.FOREGROUND);
    assertTrue(harness.awaitSearchable("marmoset", INDEXED_MS),
        "precondition: the original's file is indexed from stick A");
    awaitRoot(root, r -> r.lastIndexed() != null && r.walkError() == null,
        "precondition: the root reads indexed on stick A");
    awaitRoot(control, r -> r.walkCompleted(), "precondition: the control root's walk");
    return root;
  }

  /**
   * Waits for the index half the Engine composes in the background after a start: what the
   * product's boot waits for (a healthy observation) before it re-walks the persisted roots.
   */
  private void awaitIndexHalfReady() throws InterruptedException {
    long deadline = System.currentTimeMillis() + INDEXED_MS;
    String error = "no answer";
    while (System.currentTimeMillis() < deadline) {
      var response =
          harness.client().syncDirectory(control.toString(), false, TestEngineContexts.BACKGROUND);
      error = response == null ? "no answer" : response.getError();
      // An answer from the Worker's ingest service means the index half is composed. Round 8
      // (refined decision 2): before the control is added, its sync is refused as unproven.
      if (error.isEmpty() || error.startsWith("ROOT_NOT_DIRECTORY")) return;
      Thread.sleep(200);
    }
    throw new AssertionError("the Engine's index half did not become ready: " + error);
  }

  private void putStickBInPlace() throws IOException {
    Files.writeString(stickB.resolve("docs").resolve("alpha.txt"), "pangolin lives on stick B");
    Files.writeString(stickB.resolve("docs").resolve("extra.txt"), "okapi lives on stick B");
  }

  /** A new control file, synced and watched; returns once the Engine has indexed it. */
  private void awaitControl() throws Exception {
    String marker = "axolotl" + (char) ('a' + controlMarks++);
    Files.writeString(control.resolve(marker + ".txt"), marker + " is the control");
    harness.client().syncDirectory(control.toString(), false, TestEngineContexts.BACKGROUND);
    assertTrue(harness.awaitSearchable(marker, INDEXED_MS),
        "the control root must index its new file, or nothing here was exercised");
  }

  private String walkError(Path root) {
    return rootState(root).walkError();
  }

  private IndexingService.WatchedRoot rootState(Path root) {
    Path key = root.toAbsolutePath().normalize();
    return harness.client().getWatchedRoots(TestEngineContexts.FOREGROUND).stream()
        .filter(r -> key.equals(r.path()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("not a watched root: " + key));
  }

  private void awaitRoot(Path root, Predicate<IndexingService.WatchedRoot> done, String what)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + INDEXED_MS;
    while (System.currentTimeMillis() < deadline) {
      if (done.test(rootState(root))) return;
      Thread.sleep(100);
    }
    throw new AssertionError(what + " did not finish: " + rootState(root));
  }

  private void mapLetterTo(Path target) throws Exception {
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
        return;
      }
      held.close();
    }
    assumeTrue(false, "no free drive letter for SUBST");
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
