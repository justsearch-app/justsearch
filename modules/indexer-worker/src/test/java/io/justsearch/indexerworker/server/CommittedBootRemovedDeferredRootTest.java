/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.DeleteByPathRequest;
import io.justsearch.ipc.DeleteByPathResponse;
import io.justsearch.ipc.ScanMode;
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.ScanRootRequest;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResult;
import io.justsearch.ipc.UnwatchRootRequest;
import io.justsearch.ipc.WatchRootRequest;
import io.justsearch.ipc.WatchRootResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task t-20261013-ef3c41, round 8, scenario S8 — a root the committed boot deferred (its drive
 * letter held another volume, "stick B", while the previous index generation was kept) is removed
 * by the person while stick B is still mapped. Nothing of stick B is ever indexed into the removed
 * root, no watcher is registered on it and no volume is recorded for it; the removal resolves the
 * deferral, so the previous generation is removed. Adding the root again once stick A is back
 * indexes stick A and nothing of stick B.
 *
 * <p>Real conditions, through {@link KnowledgeServer#start}: a SUBST letter is mapped to a folder
 * on one volume ("stick A") whose {@code docs} folder is a declared root, and a first boot records
 * that volume. A migration's successor generation is committed with the previous generation kept,
 * the letter is mapped to a folder on another volume and the server boots. The root is removed
 * through the two Worker calls the Head's removal makes ({@code UnwatchRoot}, then
 * {@code DeleteByPath}) and added again through the two its add makes ({@code WatchRoot}, then
 * {@code ScanRoot}). A second root on the temp volume is the control. Every mapping and folder this
 * test creates is removed before it returns.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
final class CommittedBootRemovedDeferredRootTest {
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  /** Longer than the server's check for a deferred root's volume, with room for its work. */
  private static final long CHECK_MS = 120_000L;
  /** More than one period of the server's deferred-root check. */
  private static final long ONE_CHECK_MS = 15_000L;

  @TempDir Path tempDir;
  private Path otherVolume;
  private String letter;
  private LetterLock letterLock;
  private KnowledgeServer server;

  private Path root;
  private Path notes;
  private Path stickA;
  private WorkerBootFixture.Layout layout;
  private IndexGenerationManager.State committed;

  @BeforeEach
  void setUp() {
    // No volume identity off Windows, nor in a run with the no-volume-identity seam on
    // (NoVolumeIdentityExtension, round 9).
    assumeTrue(WINDOWS && !Boolean.getBoolean("justsearch.test.noVolumeIdentity"),
        "SUBST drive letters and volume identity exist on Windows only");
    otherVolume = folderOnAnotherVolumeThan(tempDir, "ef3c41-removed-volume-");
    assumeTrue(otherVolume != null, "needs a second volume besides " + tempDir);
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      if (server != null) {
        server.close();
        server = null;
      }
    } finally {
      if (letter != null) {
        try {
          run("subst", letter, "/d");
        } finally {
          letterLock.close();
        }
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
  void aDeferredRootRemovedWhileAnotherVolumeIsMappedIsNeverIndexedWatchedOrRecorded()
      throws Exception {
    prepareBootWithRootOnAnotherVolume();
    assertDoesNotThrow(server::start, "a root on another volume must not fail the boot");

    removeRoot();

    assertPreviousGenerationRemoved(
        "the removal resolves the deferral: the previous generation is removed");
    // At least one more check of the deferred roots passes after the removal.
    Thread.sleep(ONE_CHECK_MS);
    Path later = root.resolve("gecko.txt");
    Files.writeString(later, "gecko was added to stick B after the root was removed");
    Thread.sleep(ONE_CHECK_MS);

    assertTrue(searchHits("pangolin").isEmpty(), "nothing of stick B is searchable");
    assertTrue(searchHits("okapi").isEmpty(), "nothing of stick B is searchable");
    assertTrue(searchHits("gecko").isEmpty(),
        "no watcher is registered on the removed root: a file added to stick B is not indexed");
    var fields = server.lifecycleManagerForTests().documentFieldOps();
    assertNull(fields.getDocumentField(PathNormalizer.normalizeKey(later), SchemaFields.DOC_UID),
        "no watcher is registered on the removed root");
    assertEquals(List.of(), recordedVolumesUnder(root),
        "no volume is recorded for the removed root");
  }

  @Test
  void theRemovedRootAddedAgainWithTheOriginalVolumeIndexesItAndNothingOfTheOther()
      throws Exception {
    prepareBootWithRootOnAnotherVolume();
    assertDoesNotThrow(server::start, "a root on another volume must not fail the boot");
    removeRoot();
    assertPreviousGenerationRemoved(
        "precondition: the removal resolves the deferral and the deferred-root check ran");
    Thread.sleep(ONE_CHECK_MS);

    remapLetterTo(stickA);
    var ingest = server.appServices().ingestService();
    // The person's add: both calls carry the add marker where the build has one (round 8).
    WatchRootResponse watched = ingest.watchRoot(
        added(WatchRootRequest.newBuilder().setRootPath(root.toString())).build(),
        CallContext.none());
    AtomicReference<ScanRootProgress> terminal = new AtomicReference<>();
    ingest.scanRoot(
        added(ScanRootRequest.newBuilder().setRootPath(root.toString())
            .setMode(ScanMode.SCAN_MODE_INITIAL)).build(),
        progress -> {
          if (progress.getComplete()) terminal.set(progress);
        },
        CallContext.none());

    assertTrue(watched.getWatching(),
        "the root added again with stick A is watched: " + watched.getErrorMessage());
    assertEquals("", terminal.get() == null ? "NO_TERMINAL" : terminal.get().getTerminalReasonCode(),
        "the root added again with stick A is walked");
    String notesId = PathNormalizer.normalizeKey(notes);
    String notesHash = SourceContentHash.sha256(notes);
    assertTrue(awaitTrue(() -> notesHash.equals(server.lifecycleManagerForTests()
            .documentFieldOps().getDocumentField(notesId, SchemaFields.SOURCE_SHA256)), CHECK_MS),
        "the root added again indexes stick A");
    assertTrue(awaitTrue(() -> searchHits("marmoset").contains(notesId), CHECK_MS),
        "stick A's text is searchable");
    assertTrue(searchHits("pangolin").isEmpty(), "nothing of stick B is searchable");
    assertTrue(searchHits("okapi").isEmpty(), "nothing of stick B is searchable");
  }

  /** {@code request} with the add marker set, on a build that has the marker. */
  private static <B extends com.google.protobuf.Message.Builder> B added(B request) {
    var marker = request.getDescriptorForType().findFieldByName("root_added");
    if (marker != null) {
      request.setField(marker, Boolean.TRUE);
    }
    return request;
  }

  /** The two Worker calls the Head's removal of a root makes, in its order. */
  private void removeRoot() {
    var ingest = server.appServices().ingestService();
    ingest.unwatchRoot(
        UnwatchRootRequest.newBuilder().setRootPath(root.toString()).build(), CallContext.none());
    DeleteByPathResponse deleted = ingest.deleteByPath(
        DeleteByPathRequest.newBuilder().setPath(root.toString()).build(), CallContext.none());
    assertEquals("", deleted.getError(), "precondition: the removal's delete is accepted");
  }

  private void assertPreviousGenerationRemoved(String message) throws Exception {
    Path previous =
        layout.genManager().resolveGenerationPathStrict(committed.previous_generation());
    assertTrue(awaitTrue(() -> {
      IndexGenerationManager.State state = layout.genManager().readStateBestEffort();
      return state != null
          && (state.previous_generation() == null || state.previous_generation().isBlank())
          && !Files.exists(previous);
    }, CHECK_MS), message);
  }

  /**
   * Stick A's root and a control root; a first boot records stick A's volume; a successor
   * generation holding the root's document is committed with the previous generation kept; the
   * letter then holds stick B and a server is constructed for the next boot.
   */
  private void prepareBootWithRootOnAnotherVolume() throws Exception {
    stickA = Files.createDirectories(tempDir.resolve("stick-a"));
    Files.createDirectories(stickA.resolve("docs"));
    root = mapLetterTo(stickA).resolve("docs");
    notes = root.resolve("notes.txt");
    Files.writeString(notes, "marmoset notes on the first stick");
    Path control = Files.createDirectories(tempDir.resolve("control"));
    Files.writeString(control.resolve("control.txt"), "the control root keeps a tamarin");
    List<RootBinding> roots = List.of(new RootBinding(root, null), new RootBinding(control, null));

    layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "FAIL_CLOSED");
    server = new KnowledgeServer(
        new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()));
    server.bindBootRootBindings(roots);
    server.start();
    WatchRootResponse firstWatch = server.appServices().ingestService().watchRoot(
        WatchRootRequest.newBuilder().setRootPath(root.toString()).build(), CallContext.none());
    assertTrue(firstWatch.getWatching(),
        "precondition: the first boot watches the root on its own volume");
    assertFalse(recordedVolumesUnder(root).isEmpty(),
        "precondition: stick A's volume is recorded for the root");
    server.close();
    server = null;

    String building =
        layout.genManager().startMigration("committed_boot_removed_root").building_generation();
    Path green = layout.genManager().resolveGenerationPathStrict(building);
    WorkerBootFixture.seedDocument(green, null, PathNormalizer.normalizeKey(notes), "notes#0",
        "marmoset notes on the first stick");
    committed = layout.genManager().promoteBuildingGenerationToActive();
    assertTrue(committed.previous_generation() != null && !committed.previous_generation().isBlank(),
        "precondition: the previous generation is kept after the cutover");

    Files.createDirectories(otherVolume.resolve("docs"));
    Files.writeString(otherVolume.resolve("docs").resolve("okapi.txt"), "okapi on another volume");
    Files.writeString(otherVolume.resolve("docs").resolve("notes.txt"), "pangolin text on stick B");
    remapLetterTo(otherVolume);

    server = new KnowledgeServer(
        new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()));
    server.bindBootRootBindings(roots);
  }

  /** The folders at or under {@code folder} that the Worker's volume record names. */
  private List<String> recordedVolumesUnder(Path folder) throws IOException {
    String key = folder.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    List<String> recorded = new ArrayList<>();
    try (Stream<Path> files = Files.walk(layout.dataDir())) {
      for (Path file : files.filter(p -> p.getFileName().toString()
          .equals("root-volumes.properties")).toList()) {
        Properties entries = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
          entries.load(in);
        }
        for (String name : entries.stringPropertyNames()) {
          String lower = name.toLowerCase(Locale.ROOT);
          if (lower.equals(key) || lower.startsWith(key + "\\")) {
            recorded.add(name);
          }
        }
      }
    }
    return recorded;
  }

  private List<String> searchHits(String word) {
    return server.appServices().searchService()
        .search(SearchRequest.newBuilder().setQuery(word).setLimit(10).build(), CallContext.none())
        .getResultsList().stream().map(SearchResult::getId).toList();
  }

  /** A condition read from the running server, which may throw while it is mid-change. */
  @FunctionalInterface
  private interface Condition {
    boolean holds() throws Exception;
  }

  private static boolean awaitTrue(Condition condition, long timeoutMs) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    while (System.nanoTime() < deadline) {
      try {
        if (condition.holds()) return true;
      } catch (IOException | RuntimeException notYet) {
        // The index or state is mid-change; look again.
      }
      Thread.sleep(500L);
    }
    return condition.holds();
  }

  private Path mapLetterTo(Path target) throws Exception {
    Set<Character> used =
        java.util.Arrays.stream(File.listRoots())
            .map(r -> Character.toUpperCase(r.getPath().charAt(0)))
            .collect(Collectors.toSet());
    for (char c = 'S'; c >= 'M'; c--) {
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
    Objects.requireNonNull(letter, "letter");
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
