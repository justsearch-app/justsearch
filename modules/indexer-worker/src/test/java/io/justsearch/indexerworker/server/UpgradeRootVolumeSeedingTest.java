/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.ScanMode;
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.ScanRootRequest;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResult;
import io.justsearch.ipc.SyncDirectoryRequest;
import io.justsearch.ipc.SyncDirectoryResponse;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task t-20261013-ef3c41, round 8 (refined decision 2) — the upgrade from a version that kept no
 * record of each root's volume. At the first boot without the record, the volume of each declared
 * root is confirmed once, on evidence: a root whose indexed files are on the drive at its path with
 * their indexed size and modification time is recorded there, a root with nothing indexed is
 * recorded like an add, and a root whose drive cannot be confirmed records nothing and reads
 * unavailable with the reason "drive not confirmed after upgrade", keeping its text searchable and
 * deleting nothing until its own drive returns, which the periodic check then adopts.
 *
 * <p>Real conditions, through {@link KnowledgeServer#start}: a first boot indexes the roots through
 * the person's add ({@code WatchRoot}, then {@code ScanRoot}); the volume record files are then
 * deleted, which is what an index written by a version without them looks like, and the server
 * boots again over the same index. For the other-volume case a SUBST letter is mapped to a folder
 * on one volume ("stick A") and, before the upgrade boot, to a folder on another volume ("stick
 * B"). Every mapping and folder this test creates is removed before it returns.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
final class UpgradeRootVolumeSeedingTest {
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  private static final long CHECK_MS = 120_000L;
  private static final String UNCONFIRMED_REASON =
      "ROOT_NOT_DIRECTORY: drive not confirmed after upgrade";
  private static final String NOT_WRITABLE_YET = "Index runtime not available";
  private static final List<String> RECORD_FILES =
      List.of("root-volumes.properties", "root-volumes-pending.properties");

  @TempDir Path tempDir;
  private Path otherVolume;
  private String letter;
  private LetterLock letterLock;
  private KnowledgeServer server;
  private WorkerBootFixture.Layout layout;

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
  void anUpgradeWithTheSameDrivesRecordsEveryRootOnEvidenceAndKeepsItsIndex() throws Exception {
    Path letters = Files.createDirectories(tempDir.resolve("letters"));
    Path photos = Files.createDirectories(tempDir.resolve("photos"));
    Path letterFile = Files.writeString(letters.resolve("letter.txt"), "a capybara letter");
    Path photoNotes = Files.writeString(photos.resolve("notes.txt"), "photo notes on a quokka");
    List<RootBinding> roots = List.of(new RootBinding(letters, null), new RootBinding(photos, null));
    firstBootIndexes(roots, List.of(letterFile, photoNotes));

    bootAfterUpgrade(roots);

    assertFalse(recordedVolumesUnder(letters).isEmpty(),
        "the upgrade boot records the root's volume on the evidence of its indexed files");
    assertFalse(recordedVolumesUnder(photos).isEmpty(),
        "the upgrade boot records the root's volume on the evidence of its indexed files");
    for (Path root : List.of(letters, photos)) {
      SyncDirectoryResponse synced = sync(root);
      assertEquals("", synced.getError(), "the root stays indexed: " + root);
      assertFalse(synced.getSkipped(), "precondition: the sync ran: " + root);
      assertEquals(0, synced.getFilesAdded(), "nothing is extracted again: " + root);
      assertEquals(0, synced.getFilesDeleted(), "nothing is deleted: " + root);
    }
    assertTrue(searchHits("capybara").contains(PathNormalizer.normalizeKey(letterFile)),
        "the root's text stays searchable");
    assertTrue(searchHits("quokka").contains(PathNormalizer.normalizeKey(photoNotes)),
        "the root's text stays searchable");
  }

  @Test
  void anUpgradeRecordsARootWithNothingIndexedLikeAnAdd() throws Exception {
    Path letters = Files.createDirectories(tempDir.resolve("letters"));
    Path empty = Files.createDirectories(tempDir.resolve("empty"));
    Path letterFile = Files.writeString(letters.resolve("letter.txt"), "a capybara letter");
    List<RootBinding> roots = List.of(new RootBinding(letters, null), new RootBinding(empty, null));
    firstBootIndexes(roots, List.of(letterFile));

    bootAfterUpgrade(roots);

    assertFalse(recordedVolumesUnder(empty).isEmpty(),
        "a root with nothing indexed is recorded on the drive at its path");
    assertEquals("", sync(empty).getError(), "the root with nothing indexed is synced");
  }

  @Test
  void anUpgradeWhereTheLetterHoldsAnotherVolumeRecordsNothingUntilTheDriveReturns()
      throws Exception {
    assumeTrue(WINDOWS, "SUBST drive letters exist on Windows only");
    otherVolume = folderOnAnotherVolumeThan(tempDir, "ef3c41-upgrade-volume-");
    assumeTrue(otherVolume != null, "needs a second volume besides " + tempDir);
    Path stickA = Files.createDirectories(tempDir.resolve("stick-a"));
    Files.createDirectories(stickA.resolve("docs"));
    Path root = mapLetterTo(stickA).resolve("docs");
    Path notes = Files.writeString(root.resolve("notes.txt"), "marmoset notes on the first stick");
    Path control = Files.createDirectories(tempDir.resolve("control"));
    Path controlFile = Files.writeString(control.resolve("control.txt"), "a tamarin control");
    List<RootBinding> roots = List.of(new RootBinding(root, null), new RootBinding(control, null));
    firstBootIndexes(roots, List.of(notes, controlFile));
    Files.createDirectories(otherVolume.resolve("docs"));
    Files.writeString(otherVolume.resolve("docs").resolve("notes.txt"), "pangolin text on stick B");
    Files.writeString(otherVolume.resolve("docs").resolve("okapi.txt"), "okapi on stick B");
    remapLetterTo(otherVolume);

    bootAfterUpgrade(roots);

    assertEquals(List.of(), recordedVolumesUnder(root),
        "nothing is recorded for a root whose drive is not confirmed");
    assertFalse(recordedVolumesUnder(control).isEmpty(), "the control root is recorded");
    // The Head re-registers each root's watch after a restart, without the add marker.
    WatchRootResponse rewatched = server.appServices().ingestService().watchRoot(
        WatchRootRequest.newBuilder().setRootPath(root.toString()).build(), CallContext.none());
    SyncDirectoryResponse refused = sync(root);
    assertEquals(UNCONFIRMED_REASON, refused.getError(),
        "the root reads unavailable with the reason the drive is not confirmed after the upgrade");
    assertEquals(0, refused.getFilesAdded(), "nothing of stick B is queued");
    assertEquals(0, refused.getFilesDeleted(), "nothing is deleted");
    assertTrue(server.appServices().ingestService()
            .refusesRootVolume(root.toString(), CallContext.none()),
        "the periodic volume check refuses the root while stick B is mapped");
    String notesId = PathNormalizer.normalizeKey(notes);
    assertTrue(searchHits("marmoset").contains(notesId), "the root's text stays searchable");
    assertTrue(searchHits("pangolin").isEmpty(), "nothing of stick B is searchable");
    assertTrue(searchHits("okapi").isEmpty(), "nothing of stick B is searchable");
    assertEquals(List.of(), recordedVolumesUnder(root),
        "the watch, the sync and the volume check record nothing: " + rewatched);

    remapLetterTo(stickA);

    assertFalse(server.appServices().ingestService()
            .refusesRootVolume(root.toString(), CallContext.none()),
        "the periodic volume check adopts the original drive once it is back");
    assertFalse(recordedVolumesUnder(root).isEmpty(), "the original drive is recorded");
    SyncDirectoryResponse back = sync(root);
    assertEquals("", back.getError(), "the root is synced again on its drive");
    assertEquals(0, back.getFilesDeleted(), "nothing is deleted");
    assertTrue(searchHits("marmoset").contains(notesId), "the root's text is searchable");
    assertTrue(searchHits("pangolin").isEmpty(), "nothing of stick B is searchable");
  }

  /**
   * A first boot that declares {@code roots}, adds each one as the person does and waits until
   * every file of {@code files} is indexed; the server is then closed and the volume record files
   * are deleted, as an index written by a version without them has none.
   */
  private void firstBootIndexes(List<RootBinding> roots, List<Path> files) throws Exception {
    layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "FAIL_CLOSED");
    server = new KnowledgeServer(
        new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()));
    server.bindBootRootBindings(roots);
    server.start();
    var ingest = server.appServices().ingestService();
    for (RootBinding binding : roots) {
      String rootPath = binding.path().toString();
      WatchRootResponse watched = ingest.watchRoot(
          added(WatchRootRequest.newBuilder().setRootPath(rootPath)).build(), CallContext.none());
      assertTrue(watched.getWatching(), "precondition: the root is added: " + rootPath);
      AtomicReference<ScanRootProgress> terminal = new AtomicReference<>();
      ingest.scanRoot(
          added(ScanRootRequest.newBuilder().setRootPath(rootPath)
              .setMode(ScanMode.SCAN_MODE_INITIAL)).build(),
          progress -> {
            if (progress.getComplete()) terminal.set(progress);
          },
          CallContext.none());
      assertEquals("", terminal.get() == null ? "NO_TERMINAL" : terminal.get().getTerminalReasonCode(),
          "precondition: the root is walked: " + rootPath);
    }
    for (Path file : files) {
      String id = PathNormalizer.normalizeKey(file);
      String hash = SourceContentHash.sha256(file);
      assertTrue(awaitTrue(() -> hash.equals(server.lifecycleManagerForTests().documentFieldOps()
              .getDocumentField(id, SchemaFields.SOURCE_SHA256)), CHECK_MS),
          "precondition: the file is indexed: " + file);
    }
    server.close();
    server = null;
    try (Stream<Path> tree = Files.walk(layout.dataDir())) {
      for (Path record : tree.filter(p -> RECORD_FILES.contains(p.getFileName().toString()))
          .toList()) {
        Files.delete(record);
      }
    }
  }

  private void bootAfterUpgrade(List<RootBinding> roots) throws IOException {
    server = new KnowledgeServer(
        new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()));
    server.bindBootRootBindings(roots);
    server.start();
  }

  /**
   * The periodic sync of {@code root}, which queues only files missing from the index, once the
   * server takes writes: until its writer is open a sync answers that the index runtime is not
   * available and does nothing.
   */
  private SyncDirectoryResponse sync(Path root) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CHECK_MS);
    while (true) {
      SyncDirectoryResponse response = server.appServices().ingestService().syncDirectory(
          SyncDirectoryRequest.newBuilder().setRootPath(root.toString()).build(), CallContext.none());
      if (!NOT_WRITABLE_YET.equals(response.getError()) || System.nanoTime() > deadline) {
        return response;
      }
      Thread.sleep(500L);
    }
  }

  /** {@code request} with the add marker set, on a build that has the marker. */
  private static <B extends com.google.protobuf.Message.Builder> B added(B request) {
    var marker = request.getDescriptorForType().findFieldByName("root_added");
    if (marker != null) {
      request.setField(marker, Boolean.TRUE);
    }
    return request;
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
   * after it is unmapped, so SUBST tests running at once never pick or remap the same letter.
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
