/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.BatchRequest;
import io.justsearch.ipc.MigrationStartRequest;
import io.justsearch.ipc.MigrationStartResponse;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResult;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
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
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * #758 — a configured collection ({@code
 * index.collections}) is the person's configuration, not a root the person added, so no add ever
 * records its volume. Its files are indexed when a migration builds a new generation, and nothing
 * else walks or prunes them. When nothing is recorded for its root (an upgrade from a version
 * without the volume record), the root is seeded once by the upgrade rule: its indexed files must
 * be on the drive at its path with their indexed size and modification time. A root whose drive is
 * not confirmed records nothing, reads unavailable with the reason "drive not confirmed after
 * upgrade", and a migration then keeps the active generation, so nothing indexed under it is lost.
 * A collection on its own drive keeps indexing new files.
 *
 * <p>Real conditions, through {@link KnowledgeServer#start}: the collection is configured through
 * the resolved configuration; a first boot indexes a file of it through an explicit ingest; the
 * volume record files are then deleted, which is what an index written by a version without them
 * looks like; the server boots again over the same index and the person's rebuild runs as the
 * Engine drives it. For the other-volume case a SUBST letter is mapped to a folder on one volume
 * ("stick A") and, before the second boot, to a folder on another volume ("stick B"). Every mapping
 * and folder this test creates is removed before it returns.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
final class ConfiguredCollectionVolumeTest {
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  private static final long CHECK_MS = 120_000L;
  private static final String UNCONFIRMED_REASON =
      "ROOT_NOT_DIRECTORY: drive not confirmed after upgrade";
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
  void aConfiguredCollectionWhoseLetterHoldsAnotherVolumeRecordsNothingAndKeepsItsIndex()
      throws Exception {
    // No volume identity off Windows, nor in a run with the no-volume-identity seam on
    // (NoVolumeIdentityExtension, round 9).
    assumeTrue(WINDOWS && !Boolean.getBoolean("justsearch.test.noVolumeIdentity"),
        "SUBST drive letters and volume identity exist on Windows only");
    otherVolume = folderOnAnotherVolumeThan(tempDir, "ef3c41-collection-volume-");
    assumeTrue(otherVolume != null, "needs a second volume besides " + tempDir);
    Path stickA = Files.createDirectories(tempDir.resolve("stick-a"));
    Files.createDirectories(stickA.resolve("docs"));
    Path root = mapLetterTo(stickA).resolve("docs");
    Path notes = Files.writeString(root.resolve("notes.txt"), "marmoset notes on the first stick");
    configure(root);
    firstBootIndexes(notes);
    Files.createDirectories(otherVolume.resolve("docs"));
    Files.writeString(otherVolume.resolve("docs").resolve("notes.txt"), "pangolin text on stick B");
    Files.writeString(otherVolume.resolve("docs").resolve("okapi.txt"), "okapi on stick B");
    remapLetterTo(otherVolume);

    boot();

    assertEquals(List.of(), recordedVolumesUnder(root),
        "nothing is recorded for a configured root whose drive is not confirmed");
    assertEquals(UNCONFIRMED_REASON, refusal(root),
        "the configured root reads unavailable: its drive is not confirmed after the upgrade");
    String activeBefore = activeGeneration();
    rebuildAndAwaitItsEnd();

    String notesId = PathNormalizer.normalizeKey(notes);
    assertTrue(searchHits("marmoset").contains(notesId),
        "the configured collection's indexed text stays searchable");
    assertTrue(searchHits("pangolin").isEmpty(), "nothing of stick B is searchable");
    assertTrue(searchHits("okapi").isEmpty(), "nothing of stick B is searchable");
    assertEquals(activeBefore, activeGeneration(),
        "the rebuild keeps the active generation rather than drop the collection's index");
    assertEquals(List.of(), recordedVolumesUnder(root), "the rebuild records nothing");
  }

  @Test
  void aConfiguredCollectionOnItsOwnDriveKeepsItsIndexAndIndexesNewFilesAfterAnUpgrade()
      throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("collection")).toAbsolutePath().normalize();
    Path old = Files.writeString(root.resolve("old.txt"), "a capybara note indexed before");
    configure(root);
    firstBootIndexes(old);
    Path added = Files.writeString(root.resolve("new.txt"), "a quokka note added later");

    boot();
    String activeBefore = activeGeneration();
    rebuildAndAwaitItsEnd();

    assertNotEquals(activeBefore, activeGeneration(), "the rebuild completes");
    assertTrue(searchHits("capybara").contains(PathNormalizer.normalizeKey(old)),
        "the collection's indexed file stays searchable");
    assertTrue(searchHits("quokka").contains(PathNormalizer.normalizeKey(added)),
        "the collection's new file is indexed");
  }

  private void configure(Path root) throws Exception {
    layout = WorkerBootFixture.layout(tempDir);
    String roots = "[{\"name\":\"sticks\",\"roots\":[\""
        + root.toString().replace("\\", "\\\\") + "\"]}]";
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE",
        Map.of("index.collections", roots));
  }

  /**
   * A first boot that indexes {@code file} through an explicit ingest and waits until it is
   * indexed; the server is then closed and the volume record files are deleted, as an index
   * written by a version without them has none.
   */
  private void firstBootIndexes(Path file) throws Exception {
    boot();
    BatchRequest.Builder batch = BatchRequest.newBuilder().addFilePaths(file.toString());
    var marker = batch.getDescriptorForType().findFieldByName("root_added");
    if (marker != null) {
      batch.setField(marker, Boolean.TRUE);
    }
    server.appServices().ingestService().submitBatch(batch.build(), CallContext.none());
    String id = PathNormalizer.normalizeKey(file);
    String hash = SourceContentHash.sha256(file);
    assertTrue(awaitTrue(() -> hash.equals(server.lifecycleManagerForTests().documentFieldOps()
            .getDocumentField(id, SchemaFields.SOURCE_SHA256)), CHECK_MS),
        "precondition: the file is indexed: " + file);
    server.close();
    server = null;
    try (Stream<Path> tree = Files.walk(layout.dataDir())) {
      for (Path record : tree.filter(p -> RECORD_FILES.contains(p.getFileName().toString()))
          .toList()) {
        Files.delete(record);
      }
    }
  }

  /** A boot as the Engine makes it: the Head's declared roots (none here) are bound first. */
  private void boot() throws Exception {
    server = new KnowledgeServer(
        new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()));
    server.bindBootRootBindings(List.of());
    server.start();
    var field = KnowledgeServer.class.getDeclaredField("modelReadyLatch");
    field.setAccessible(true);
    ((CountDownLatch) field.get(server)).countDown();
  }

  /** The person's rebuild, as the Engine drives it, until the migration has ended either way. */
  private void rebuildAndAwaitItsEnd() throws Exception {
    MigrationStartResponse started = server.appServices().ingestService().startMigration(
        MigrationStartRequest.newBuilder().setReason("manual").build(), CallContext.none());
    assertTrue(started.getAccepted(), "precondition: the rebuild is accepted: " + started.getError());
    AtomicInteger restarts = new AtomicInteger();
    assertTrue(server.beginUnrecordedBuildingLiveAsync(
            started.getBuildingGenerationId(), restarts::incrementAndGet)
        .get(60, TimeUnit.SECONDS), "precondition: the rebuild opens live");
    IndexGenerationManager manager = server.indexGenerationManagerForTests();
    assertTrue(awaitTrue(() -> {
      var state = manager.readStateBestEffort();
      return state != null && ("IDLE".equals(state.migration_state())
          || "FAILED".equals(state.migration_state()));
    }, CHECK_MS), "precondition: the rebuild ends");
    assertEquals(0, restarts.get(), "precondition: no restart was asked for");
  }

  private String activeGeneration() {
    return server.indexGenerationManagerForTests().readStateBestEffort().active_generation();
  }

  /** The Worker's root-level refusal reason for {@code root}, or "" when it has none. */
  private String refusal(Path root) throws Exception {
    var ingest = server.appServices().ingestService();
    Method reason;
    try {
      reason = ingest.getClass().getMethod("rootVolumeRefusal", String.class, CallContext.class);
    } catch (NoSuchMethodException none) {
      return "";
    }
    return (String) reason.invoke(ingest, root.toString(), CallContext.none());
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
          if (name.toLowerCase(Locale.ROOT).contains(key)) {
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
