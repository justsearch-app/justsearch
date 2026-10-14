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
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResult;
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task t-20261013-ef3c41, round 7, scenario S8 — a boot that still has the previous index
 * generation (a stop between a migration's cutover and the old generation's removal) meets a root
 * whose drive letter now holds another volume. The committed boot defers that root instead of
 * failing start-up: every other root is reconciled and served, the root reads unavailable, nothing
 * under it is pruned and the previous generation is kept until the root has been reconciled on its
 * own volume, which happens without a restart once that volume is back.
 *
 * <p>Real conditions, through {@link KnowledgeServer#start}: a SUBST letter is mapped to a folder
 * on one volume ("stick A") whose {@code docs} folder is a declared root, and a first boot records
 * that volume. A migration's successor generation is then committed with the previous generation
 * kept, the letter is mapped to a folder on another volume ("stick B"), and the server boots. A
 * second root on the temp volume is the control. Every mapping and folder this test creates is
 * removed before it returns.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
final class CommittedBootDeferredRootTest {
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  /** Longer than the server's check for a deferred root's volume, with room for its work. */
  private static final long RETURN_MS = 120_000L;

  @TempDir Path tempDir;
  private Path otherVolume;
  private String letter;
  private LetterLock letterLock;
  private KnowledgeServer server;

  private Path root;
  private Path notes;
  private Path controlFile;
  private Path stickA;
  private WorkerBootFixture.Layout layout;
  private IndexGenerationManager.State committed;

  @BeforeEach
  void setUp() {
    // No volume identity off Windows, nor in a run with the no-volume-identity seam on
    // (NoVolumeIdentityExtension, round 9).
    assumeTrue(WINDOWS && !Boolean.getBoolean("justsearch.test.noVolumeIdentity"),
        "SUBST drive letters and volume identity exist on Windows only");
    otherVolume = folderOnAnotherVolumeThan(tempDir, "ef3c41-deferred-volume-");
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
  void aBootWithThePreviousGenerationServesTheOtherRootsWhileARootIsOnAnotherVolume()
      throws Exception {
    prepareBootWithRootOnAnotherVolume();

    assertDoesNotThrow(server::start, "a root on another volume must not fail the boot");

    var fields = server.lifecycleManagerForTests().documentFieldOps();
    assertEquals(SourceContentHash.sha256(controlFile),
        fields.getDocumentField(PathNormalizer.normalizeKey(controlFile), SchemaFields.SOURCE_SHA256),
        "every other root is reconciled at boot");
    assertTrue(searchHits("tamarin").contains(PathNormalizer.normalizeKey(controlFile)),
        "every other root is searchable");
    assertTrue(server.appServices().ingestService()
            .refusesRootVolume(root.toString(), CallContext.none()),
        "the root reads unavailable (ROOT_NOT_DIRECTORY) to the Head");
    assertNull(fields.getDocumentField(
            PathNormalizer.normalizeKey(root.resolve("okapi.txt")), SchemaFields.DOC_UID),
        "nothing of the other volume is indexed into the root");
    // One check of the deferred root's volume passes while the other volume stays mapped.
    Thread.sleep(15_000L);
    assertEquals("notes#0", fields.getDocumentField(
            PathNormalizer.normalizeKey(notes), SchemaFields.DOC_UID),
        "nothing under the deferred root is pruned");
    assertTrue(searchHits("marmoset").contains(PathNormalizer.normalizeKey(notes)),
        "the deferred root's documents stay searchable");
    IndexGenerationManager.State after = layout.genManager().readStateBestEffort();
    assertEquals(committed.active_generation(), after.active_generation());
    assertEquals(committed.previous_generation(), after.previous_generation(),
        "the previous generation is kept while the root is deferred");
    assertTrue(Files.isDirectory(
            layout.genManager().resolveGenerationPathStrict(committed.previous_generation())),
        "the previous generation's folder is kept while the root is deferred");
  }

  @Test
  void theOriginalVolumeReturningReconcilesTheRootAndRemovesThePreviousGeneration()
      throws Exception {
    prepareBootWithRootOnAnotherVolume();
    assertDoesNotThrow(server::start, "a root on another volume must not fail the boot");

    remapLetterTo(stickA);

    Path previous =
        layout.genManager().resolveGenerationPathStrict(committed.previous_generation());
    assertTrue(awaitTrue(() -> {
      IndexGenerationManager.State state = layout.genManager().readStateBestEffort();
      return state != null
          && (state.previous_generation() == null || state.previous_generation().isBlank())
          && !Files.exists(previous);
    }, RETURN_MS), "the previous generation is removed once the root is reconciled on its volume");
    var fields = server.lifecycleManagerForTests().documentFieldOps();
    assertEquals(SourceContentHash.sha256(notes), fields.getDocumentField(
            PathNormalizer.normalizeKey(notes), SchemaFields.SOURCE_SHA256),
        "the root is reconciled against its own volume");
    assertNull(fields.getDocumentField(
            PathNormalizer.normalizeKey(root.resolve("okapi.txt")), SchemaFields.DOC_UID),
        "nothing of the other volume is indexed into the root");

    Path written = root.resolve("capybara.txt");
    Files.writeString(written, "capybara was written after the stick returned");
    String writtenId = PathNormalizer.normalizeKey(written);
    assertTrue(awaitTrue(() -> SourceContentHash.sha256(written).equals(
            server.lifecycleManagerForTests().documentFieldOps()
                .getDocumentField(writtenId, SchemaFields.SOURCE_SHA256)), RETURN_MS),
        "the root's watch resumes: a file written after the volume returned is indexed");
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
    controlFile = control.resolve("control.txt");
    Files.writeString(controlFile, "the control root keeps a tamarin");
    List<RootBinding> roots = List.of(new RootBinding(root, null), new RootBinding(control, null));

    layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "FAIL_CLOSED");
    server = new KnowledgeServer(
        new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()));
    server.bindBootRootBindings(roots);
    server.start();
    assertFalse(server.appServices().ingestService()
            .refusesRootVolume(root.toString(), CallContext.none()),
        "precondition: the first boot sees the root on its own volume, which records it");
    server.close();
    server = null;

    String building =
        layout.genManager().startMigration("committed_boot_deferred_root").building_generation();
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
