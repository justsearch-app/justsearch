/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.adapters.lucene.runtime.PruneOps;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task t-20261013-ef3c41, scenarios S2 and S8 — the prune-side root authority. A root vouches for
 * absence only while it is the same present, listable folder, checked per batch: renamed away, a
 * drive letter unmapped, or a different folder mapped at the same letter all stop it vouching.
 * Real conditions only (rename and SUBST); every mapping is removed before the test returns.
 */
final class RootPresenceGuardTest {
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

  @TempDir Path tempDir;

  @Test
  void aPresentRootVouchesUntilItIsRenamedAway() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("root"));
    PruneOps.PresenceGuard guard = RootPresenceGuard.capture(root, RootIdentity.capture(root));
    assertTrue(guard.rootCurrent());

    Files.move(root, tempDir.resolve("root-renamed"));

    assertFalse(guard.rootCurrent(), "a renamed root no longer vouches for any absence");
  }

  @Test
  void aRootMissingAtCaptureNeverVouches() throws Exception {
    PruneOps.PresenceGuard guard = RootPresenceGuard.capture(tempDir.resolve("absent"), null);
    assertFalse(guard.rootCurrent());
  }

  @Test
  void aDifferentFolderMappedAtTheSameLetterDoesNotVouch() throws Exception {
    assumeTrue(WINDOWS, "SUBST drive letters exist on Windows only");
    Path original = Files.createDirectories(tempDir.resolve("stick-a").resolve("music"));
    Path other = Files.createDirectories(tempDir.resolve("stick-b").resolve("music"));
    LetterLock held = holdFreeLetter();
    String letter = held.letter;
    try {
      assertEquals(0, run("subst", letter, original.getParent().toString()));
      Path root = Path.of(letter + "\\music");
      RootIdentity registered = RootIdentity.capture(root);
      assertTrue(RootPresenceGuard.capture(root, registered).rootCurrent(),
          "precondition: the registered volume vouches");

      // The original stick is pulled and another one appears at the same letter.
      assertEquals(0, run("subst", letter, "/d"));
      assertEquals(0, run("subst", letter, other.getParent().toString()));

      assertFalse(RootPresenceGuard.capture(root, registered).rootCurrent(),
          "a different volume at the same letter must not vouch for the original's deletions");
    } finally {
      try {
        run("subst", letter, "/d");
      } finally {
        held.close();
      }
    }
  }

  @Test
  void anUnreadableVolumeRecordVouchesForNothing() throws Exception {
    assumeTrue(WINDOWS, "volume identity and NOSHARE locks are Windows features");
    Path root = Files.createDirectory(tempDir.resolve("root"));
    Path base = Files.createDirectory(tempDir.resolve("indexBase"));
    RootVolumeLedger volumes = RootVolumeLedger.under(base);
    // The person's add records the root's volume (round 8, refined decision 2).
    volumes.recordIfAbsent(root, RootIdentity.capture(root).volume());
    assertTrue(RootPresenceGuard.capture(root, null, volumes).rootCurrent(),
        "precondition: the root on its recorded volume vouches");
    Path ledger = base.resolve(RootVolumeLedger.FILE_NAME);
    assertTrue(Files.exists(ledger), "the volume is recorded");

    try (var ignoredLock = FileChannel.open(ledger,
        StandardOpenOption.READ,
        com.sun.nio.file.ExtendedOpenOption.NOSHARE_READ,
        com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE,
        com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)) {
      assertFalse(RootPresenceGuard.capture(root, null, volumes).rootCurrent(),
          "a volume record that cannot be read cannot confirm the volume: nothing is removed");
    }
    assertTrue(RootPresenceGuard.capture(root, null, volumes).rootCurrent());
  }

  @Test
  void aRecordedVolumeIsForgottenWhenTheRootIsRemoved() throws Exception {
    Path base = Files.createDirectory(tempDir.resolve("indexBase"));
    RootVolumeLedger volumes = RootVolumeLedger.under(base);
    Path root = tempDir.resolve("music");
    Path sibling = tempDir.resolve("music-videos");
    volumes.recordIfAbsent(root, "windows-volume:1");
    volumes.recordIfAbsent(root.resolve("live"), "windows-volume:1");
    volumes.recordIfAbsent(sibling, "windows-volume:1");
    volumes.recordIfAbsent(root, "windows-volume:2");
    assertEquals(java.util.Optional.of("windows-volume:1"), volumes.recorded(root),
        "the first volume stands");

    volumes.forgetUnder(root.toString());

    assertEquals(java.util.Optional.empty(), volumes.recorded(root));
    assertEquals(java.util.Optional.empty(), volumes.recorded(root.resolve("live")));
    assertEquals(java.util.Optional.of("windows-volume:1"), volumes.recorded(sibling),
        "a folder that merely shares the name prefix is not removed");
  }

  /** A letter no drive uses, held for the calling test until it closes the hold. */
  private static LetterLock holdFreeLetter() throws IOException {
    Set<Character> used =
        java.util.Arrays.stream(File.listRoots())
            .map(r -> Character.toUpperCase(r.getPath().charAt(0)))
            .collect(Collectors.toSet());
    for (char c = 'Y'; c >= 'M'; c--) {
      if (used.contains(c)) continue;
      LetterLock held = LetterLock.tryHold(c);
      if (held != null) return held;
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

  /**
   * A hold on one SUBST drive letter shared by every test process on this machine: a lock on a
   * per-letter file in the user's temp folder, taken before the letter is mapped and released only
   * after it is unmapped, so SUBST tests running at once (Gradle forks, or modules built together)
   * never pick, remap into or take over the same letter.
   */
  private static final class LetterLock implements AutoCloseable {
    private final String letter;
    private final FileChannel channel;
    private final FileLock lock;

    private LetterLock(char letter, FileChannel channel, FileLock lock) {
      this.letter = letter + ":";
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
      return lock == null ? null : new LetterLock(letter, channel, lock);
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
