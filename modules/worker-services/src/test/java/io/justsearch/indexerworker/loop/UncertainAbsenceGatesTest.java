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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.queue.JobQueue;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * #758 — the checks that can end in a deletion never remove a source's indexed
 * content, or mark its queue item done, unless the source is confirmed absent: not found while the
 * folder that holds it is present.
 *
 * <p>Every case uses a real file-system condition: a root renamed away or a drive letter unmapped
 * (SUBST) for an unavailable root, a handle opened without sharing for a locked file, a deny ACL on
 * a file this test creates for access denied, and a real directory or junction in place of a file.
 * Each ACL, mapping and handle is removed before the test returns. The admission gate is driven
 * directly; the pre-publish freshness check, the post-extraction re-hash and the writer's pre-write
 * check all decide through {@link StaleSnapshotResolver#tryHandleStale}, which is driven directly
 * with its collaborators mocked so the deletion and the queue transition are observable.
 */
@Tag("cross-platform")
final class UncertainAbsenceGatesTest {
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

  @TempDir Path tempDir;

  // ---- S1: admission gate -------------------------------------------------------------------

  @Test
  void admissionKeepsTheSourceWhenItsRootWasRenamedAway() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("watched"));
    Path file = Files.writeString(root.resolve("report.txt"), "indexed text");
    Files.move(root, tempDir.resolve("watched-renamed"));

    SourceAdmission admission = new WorkerIngestionAuthority().admit(rooted(file, root));

    assertNotEquals(SourceAdmissionAction.STALE_DONE, admission.action(),
        "a source whose root is gone is not confirmed absent; retiring it deletes its content");
    assertEquals(SourceAdmissionAction.RETRYABLE_FAILURE, admission.action());
    assertEquals("RETRY_UNTIL_AVAILABLE", admission.outcome().retryPolicy().name());
    assertEquals("ROOT_UNAVAILABLE", admission.outcome().reasonCode());
  }

  @Test
  void admissionKeepsTheSourceWhenItsDriveLetterIsUnmapped() throws Exception {
    assumeTrue(WINDOWS, "SUBST drive letters exist on Windows only");
    Path target = Files.createDirectory(tempDir.resolve("subst-target"));
    Files.createDirectory(target.resolve("docs"));
    Files.writeString(target.resolve("docs").resolve("notes.txt"), "on the removable drive");
    try (SubstDrive drive = SubstDrive.map(target)) {
      Path root = drive.root().resolve("docs");
      Path file = root.resolve("notes.txt");
      assertTrue(Files.isRegularFile(file), "the mapped drive must expose the file");
      drive.unmap(); // the drive is unplugged

      SourceAdmission admission = new WorkerIngestionAuthority().admit(rooted(file, root));

      assertNotEquals(SourceAdmissionAction.STALE_DONE, admission.action(),
          "an unplugged drive reports its files as not found; that is not a deletion");
      assertEquals("RETRY_UNTIL_AVAILABLE", admission.outcome().retryPolicy().name());
    }
  }

  @Test
  void admissionStillRetiresAGenuinelyDeletedSource() throws Exception {
    // P1 (must not break): not found while the root is present is a deletion.
    Path root = Files.createDirectory(tempDir.resolve("present-root"));
    Path file = Files.writeString(root.resolve("gone.txt"), "obsolete");
    Files.delete(file);

    SourceAdmission admission = new WorkerIngestionAuthority().admit(rooted(file, root));

    assertEquals(SourceAdmissionAction.STALE_DONE, admission.action());
    assertEquals("DELETED_OR_MISSING", admission.outcome().reasonCode());
  }

  // ---- S7: an unreadable source is retried with growing back-off and no time limit ------------

  @Test
  void aLockedSourceIsRetriedWithoutATimeLimit() throws Exception {
    assumeTrue(WINDOWS, "share modes are a Windows file-system feature");
    Path root = Files.createDirectory(tempDir.resolve("locked-root"));
    Path file = Files.writeString(root.resolve("open-in-word.docx"), "being edited");
    try (FileChannel ignored = openWithoutSharing(file)) {
      SourceAdmission admission = new WorkerIngestionAuthority().admit(rooted(file, root));

      assertEquals(SourceAdmissionAction.RETRYABLE_FAILURE, admission.action());
      assertEquals("RETRY_UNTIL_AVAILABLE", admission.outcome().retryPolicy().name(),
          "a file locked by another program must not run into the retry window's end");
      assertEquals("SOURCE_UNREADABLE", admission.outcome().reasonCode());
    }
  }

  @Test
  void anAccessDeniedSourceIsRetriedWithoutATimeLimit() throws Exception {
    assumeTrue(WINDOWS, "the deny ACL is set through the Windows ACL view");
    Path root = Files.createDirectory(tempDir.resolve("denied-root"));
    Path file = Files.writeString(root.resolve("private.txt"), "not for this user right now");
    try (DenyRead ignored = DenyRead.on(file)) {
      SourceAdmission admission = new WorkerIngestionAuthority().admit(rooted(file, root));

      assertEquals(SourceAdmissionAction.RETRYABLE_FAILURE, admission.action());
      assertEquals("RETRY_UNTIL_AVAILABLE", admission.outcome().retryPolicy().name());
      assertEquals("SOURCE_UNREADABLE", admission.outcome().reasonCode());
    }
  }

  // ---- S9: a file replaced by a directory or a link is retired --------------------------------

  @Test
  void aFileReplacedByADirectoryIsRetired() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("replaced-root"));
    Path file = Files.writeString(root.resolve("notes"), "old text");
    Files.delete(file);
    Files.createDirectory(file);

    SourceAdmission admission = new WorkerIngestionAuthority().admit(rooted(file, root));

    assertEquals(SourceAdmissionAction.STALE_DONE, admission.action(),
        "the old text must leave the index; SKIP_DONE kept it searchable forever");
    assertEquals("NON_REGULAR_SOURCE", admission.outcome().reasonCode());
  }

  @Test
  void aFileReplacedByADanglingJunctionIsRetired() throws Exception {
    assumeTrue(WINDOWS, "junctions are a Windows reparse point");
    Path root = Files.createDirectory(tempDir.resolve("junction-root"));
    Path file = Files.writeString(root.resolve("notes"), "old text");
    Files.delete(file);
    Path missingTarget = tempDir.resolve("no-such-target");
    createJunction(file, missingTarget);
    try {
      SourceAdmission admission = new WorkerIngestionAuthority().admit(rooted(file, root));

      assertEquals(SourceAdmissionAction.STALE_DONE, admission.action(),
          "a link where the file was is not the indexed source; its old text must leave");
    } finally {
      Files.deleteIfExists(file); // removes the junction itself, never its (absent) target
    }
  }

  // ---- S1: pre-publish freshness, post-extraction re-hash and writer pre-write ---------------

  @Test
  void theFreshnessCheckKeepsTheDocumentWhenTheRootWasRenamedAway() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("fresh-root"));
    Path file = Files.writeString(root.resolve("report.txt"), "indexed text");
    FileEnvelope envelope = FileEnvelope.fromSnapshot(FileFreshnessSnapshot.capture(file));
    Files.move(root, tempDir.resolve("fresh-root-renamed"));
    Resolver resolver = resolver();
    JobQueue.IndexJob claim = rooted(file, root);

    boolean handled =
        resolver.resolver().tryHandleStale(file, envelope, "default", null, "before publish",
            null, claim);

    assertTrue(handled, "the claim must not proceed to publish");
    assertEquals(0, calls(resolver.staleSourceHandler(), "deleteMissingSource"),
        "the indexed document must not be deleted when the source's absence is not confirmed");
    assertEquals(0, calls(resolver.jobQueue(), "markClaimDone"),
        "the queue item must not be ended; it is retried later");
    ArgumentCaptor<IngestionOutcome> failed = ArgumentCaptor.forClass(IngestionOutcome.class);
    verify(resolver.jobQueue()).markClaimFailed(any(), failed.capture(), any());
    assertEquals("RETRY_UNTIL_AVAILABLE", failed.getValue().retryPolicy().name(),
        "the item is retried later, after the root is back");
  }

  @Test
  void theFreshnessCheckStillRetiresAGenuinelyDeletedSource() throws Exception {
    // P1 (must not break).
    Path root = Files.createDirectory(tempDir.resolve("fresh-present"));
    Path file = Files.writeString(root.resolve("gone.txt"), "obsolete");
    FileEnvelope envelope = FileEnvelope.fromSnapshot(FileFreshnessSnapshot.capture(file));
    Files.delete(file);
    Resolver resolver = resolver();

    assertTrue(resolver.resolver().tryHandleStale(
        file, envelope, "default", null, "before publish", null, rooted(file, root)));

    verify(resolver.staleSourceHandler()).deleteMissingSource(file);
    verify(resolver.jobQueue()).markClaimDone(any(), any(), any());
  }

  // ---- helpers ------------------------------------------------------------------------------

  /** How often {@code method} was called on {@code mock}; asserted, so a breach is an assertion. */
  private static long calls(Object mock, String method) {
    return mockingDetails(mock).getInvocations().stream()
        .filter(invocation -> invocation.getMethod().getName().equals(method))
        .count();
  }

  private record Resolver(
      StaleSnapshotResolver resolver, StaleSourceHandler staleSourceHandler, JobQueue jobQueue) {}

  private static Resolver resolver() {
    StaleSourceHandler handler = mock(StaleSourceHandler.class);
    JobQueue jobQueue = mock(JobQueue.class);
    org.mockito.Mockito.doCallRealMethod().when(jobQueue).ownsClaimForPublication(any());
    IngestionOutcomeJournal journal = mock(IngestionOutcomeJournal.class);
    doAnswer(invocation -> {
          ((Runnable) invocation.getArgument(2)).run();
          return null;
        })
        .when(journal)
        .recordOutcomeSafely(any(), anyString(), any());
    TimeboxedContentExtractor extractor = mock(TimeboxedContentExtractor.class);
    when(extractor.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
    return new Resolver(
        new StaleSnapshotResolver(
            new WorkerIngestionAuthority(), handler, journal, jobQueue, extractor, delta -> {}, new FileMutationFence()),
        handler,
        jobQueue);
  }

  private static JobQueue.IndexJob rooted(Path file, Path root) {
    return new JobQueue.IndexJob(file, null, null, null, null, null, false, null, root);
  }

  private static FileChannel openWithoutSharing(Path file) throws IOException {
    return FileChannel.open(
        file,
        StandardOpenOption.READ,
        com.sun.nio.file.ExtendedOpenOption.NOSHARE_READ,
        com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE,
        com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE);
  }

  private static void createJunction(Path link, Path target) throws Exception {
    Process process =
        new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes());
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "mklink did not finish");
    assertEquals(0, process.exitValue(), "mklink /J failed: " + output);
  }

  /** A deny-read ACE for the current user on a file this test created; removed on close. */
  static final class DenyRead implements AutoCloseable {
    private final AclFileAttributeView view;
    private final List<AclEntry> original;

    private DenyRead(AclFileAttributeView view, List<AclEntry> original) {
      this.view = view;
      this.original = original;
    }

    static DenyRead on(Path file) throws IOException {
      AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
      List<AclEntry> original = view.getAcl();
      UserPrincipal me =
          file.getFileSystem().getUserPrincipalLookupService()
              .lookupPrincipalByName(System.getProperty("user.name"));
      List<AclEntry> denied = new ArrayList<>();
      denied.add(
          AclEntry.newBuilder()
              .setType(AclEntryType.DENY)
              .setPrincipal(me)
              .setPermissions(AclEntryPermission.READ_DATA)
              .build());
      denied.addAll(original);
      view.setAcl(denied);
      return new DenyRead(view, original);
    }

    @Override
    public void close() throws IOException {
      view.setAcl(original);
    }
  }

  /** A SUBST drive letter mapped to a folder this test created; unmapped on close. */
  static final class SubstDrive implements AutoCloseable {
    private final String letter;
    private final LetterLock held;
    private boolean mapped = true;

    private SubstDrive(String letter, LetterLock held) {
      this.letter = letter;
      this.held = held;
    }

    static SubstDrive map(Path target) throws Exception {
      Set<Character> used =
          java.util.Arrays.stream(File.listRoots())
              .map(r -> Character.toUpperCase(r.getPath().charAt(0)))
              .collect(Collectors.toSet());
      for (char c = 'Y'; c >= 'M'; c--) {
        if (used.contains(c)) continue;
        LetterLock held = LetterLock.tryHold(c);
        if (held == null) continue;
        String letter = c + ":";
        if (run("subst", letter, target.toString()) == 0) {
          return new SubstDrive(letter, held);
        }
        held.close();
      }
      assumeTrue(false, "no free drive letter for SUBST");
      throw new IllegalStateException("unreachable");
    }

    Path root() {
      return Path.of(letter + "\\");
    }

    void unmap() throws Exception {
      if (mapped) {
        assertEquals(0, run("subst", letter, "/d"), "could not remove SUBST " + letter);
        mapped = false;
      }
    }

    @Override
    public void close() throws Exception {
      try {
        unmap();
      } finally {
        held.close();
      }
    }

    private static int run(String... command) throws Exception {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      process.getInputStream().readAllBytes();
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "subst did not finish");
      return process.exitValue();
    }
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
