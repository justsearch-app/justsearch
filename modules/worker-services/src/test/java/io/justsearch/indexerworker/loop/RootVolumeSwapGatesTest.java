/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.queue.JobQueue;
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
 * #758 — a drive letter that comes back holding a different
 * volume does not turn the original's files into deletions on the paths outside the prune.
 *
 * <p>Real conditions: a SUBST letter is mapped to a folder on one volume holding {@code notes.txt},
 * with the drive root as the ingestion root, and the file is admitted there. The letter is then
 * unmapped and mapped again, to a folder on another volume that does not hold the file. The folder
 * at the root is present and listable, so before this change admission (and the freshness check,
 * re-hash and pre-write, which share one decision) read the file as deleted and retired it. Every
 * mapping and folder this test creates is removed before it returns.
 */
final class RootVolumeSwapGatesTest {
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

  @TempDir Path tempDir;
  private Path otherVolume;
  private String letter;
  private LetterLock letterLock;

  @BeforeEach
  void setUp() throws Exception {
    // No volume identity off Windows, nor in a run with the no-volume-identity seam on
    // (NoVolumeIdentityExtension, round 9).
    assumeTrue(WINDOWS && !Boolean.getBoolean("justsearch.test.noVolumeIdentity"),
        "SUBST drive letters and volume identity exist on Windows only");
    // The scenario needs a second volume besides the test JVM's temp folder; it is found, not
    // assumed, and a machine without one skips the test rather than passing it.
    otherVolume = folderOnAnotherVolumeThan(tempDir, "ef3c41-swap-volume-");
    assumeTrue(otherVolume != null, "needs a second volume besides " + tempDir);
  }

  @AfterEach
  void tearDown() throws Exception {
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

  @Test
  void admissionKeepsTheSourceWhenItsDriveLetterNowHoldsAnotherVolume() throws Exception {
    Path root = mapLetterTo(Files.createDirectory(tempDir.resolve("stick-a")));
    Files.writeString(root.resolve("notes.txt"), "on the first stick");
    Path file = root.resolve("notes.txt");
    WorkerIngestionAuthority authority = new WorkerIngestionAuthority();
    assertEquals(SourceAdmissionAction.ADMIT, authority.admit(rooted(file, root)).action(),
        "precondition: the file is admitted from the first volume");

    remapLetterTo(otherVolume);
    assertTrue(Files.isDirectory(root), "precondition: the root is present on the other volume");

    SourceAdmission admission = authority.admit(rooted(file, root));

    assertNotEquals(SourceAdmissionAction.STALE_DONE, admission.action(),
        "another volume at the same letter cannot tell whether the file was deleted");
    assertEquals(SourceAdmissionAction.RETRYABLE_FAILURE, admission.action());
    assertEquals("ROOT_UNAVAILABLE", admission.outcome().reasonCode(),
        "the root shows its root-level state");
  }

  @Test
  void theFreshnessCheckKeepsTheDocumentWhenItsDriveLetterNowHoldsAnotherVolume()
      throws Exception {
    Path root = mapLetterTo(Files.createDirectory(tempDir.resolve("stick-b")));
    Path file = Files.writeString(root.resolve("notes.txt"), "on the first stick");
    WorkerIngestionAuthority authority = new WorkerIngestionAuthority();
    SourceAdmission admitted = authority.admit(rooted(file, root));
    assertEquals(SourceAdmissionAction.ADMIT, admitted.action(), "precondition: admitted");
    FileEnvelope envelope = FileEnvelope.fromSnapshot(FileFreshnessSnapshot.capture(file));

    remapLetterTo(otherVolume);
    StaleSourceHandler handler = mock(StaleSourceHandler.class);
    JobQueue jobQueue = mock(JobQueue.class);
    org.mockito.Mockito.doCallRealMethod().when(jobQueue).ownsClaimForPublication(any());
    StaleSnapshotResolver resolver = resolver(authority, handler, jobQueue);

    boolean handled = resolver.tryHandleStale(
        file, envelope, "default", null, "before publish", null, rooted(file, root));

    assertTrue(handled, "the claim must not proceed to publish");
    assertEquals(0, calls(handler, "deleteMissingSource"),
        "the indexed document must not be deleted on the strength of another volume");
    assertEquals(0, calls(jobQueue, "markClaimDone"), "the queue item is retried, not ended");
  }

  @Test
  void admissionStillRetiresASourceDeletedFromTheSameVolume() throws Exception {
    // P1 (must not break): not found while the same volume is present is a deletion.
    Path root = mapLetterTo(Files.createDirectory(tempDir.resolve("stick-c")));
    Path file = Files.writeString(root.resolve("notes.txt"), "obsolete");
    WorkerIngestionAuthority authority = new WorkerIngestionAuthority();
    assertEquals(SourceAdmissionAction.ADMIT, authority.admit(rooted(file, root)).action());
    Files.delete(file);

    SourceAdmission admission = authority.admit(rooted(file, root));

    assertEquals(SourceAdmissionAction.STALE_DONE, admission.action());
    assertEquals("DELETED_OR_MISSING", admission.outcome().reasonCode());
  }

  // ---- helpers ------------------------------------------------------------------------------

  private Path mapLetterTo(Path target) throws Exception {
    Set<Character> used =
        java.util.Arrays.stream(File.listRoots())
            .map(r -> Character.toUpperCase(r.getPath().charAt(0)))
            .collect(Collectors.toSet());
    for (char c = 'V'; c >= 'M'; c--) {
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

  private static JobQueue.IndexJob rooted(Path file, Path root) {
    return new JobQueue.IndexJob(file, null, null, null, null, null, false, null, root);
  }

  private static StaleSnapshotResolver resolver(
      WorkerIngestionAuthority authority, StaleSourceHandler handler, JobQueue jobQueue) {
    IngestionOutcomeJournal journal = mock(IngestionOutcomeJournal.class);
    doAnswer(invocation -> {
          ((Runnable) invocation.getArgument(2)).run();
          return null;
        })
        .when(journal)
        .recordOutcomeSafely(any(), anyString(), any());
    TimeboxedContentExtractor extractor = mock(TimeboxedContentExtractor.class);
    when(extractor.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
    return new StaleSnapshotResolver(authority, handler, journal, jobQueue, extractor, delta -> {}, new FileMutationFence());
  }

  private static long calls(Object mock, String method) {
    return mockingDetails(mock).getInvocations().stream()
        .filter(invocation -> invocation.getMethod().getName().equals(method))
        .count();
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
