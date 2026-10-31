/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.indexerworker.ingest.IngestionSkipPolicy;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferUpsert;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerIngestionAuthorityTest {
  @TempDir Path tempDir;

  @AfterEach
  void resetPolicy() {
    IngestionSkipPolicy.resetToDefaults();
  }

  @Test
  void unrootedLegacyWatcherScanAndReplayRequireFreshAdmission() throws Exception {
    IngestionSkipPolicy.installResolved(new IngestionSkipPolicy(null, null, Set.of("private")));
    Path root = Files.createDirectory(tempDir.resolve("watched"));
    Path file = Files.writeString(Files.createDirectory(root.resolve("private")).resolve("notes.txt"),
        "private content");
    var authority = new WorkerIngestionAuthority();
    var watcher = new JobQueue.EnqueueProvenance("system", "watcher");
    for (var claim : java.util.List.of(
        new JobQueue.IndexJob(file, null, watcher),
        new JobQueue.IndexJob(file, null, null, "old-scan", "old-unit"),
        new JobQueue.IndexJob(Path.of(SwitchBufferUpsert.decode(file.toString()).path()), null))) {
      var admission = authority.admit(claim);
      assertEquals(SourceAdmissionAction.SKIP_DONE, admission.action(),
          "ordinary unscoped jobs retain main retirement without reading the source");
      assertEquals(IngestionReasonCodes.MISSING_INGESTION_BOUNDARY, admission.outcome().reasonCode());
      assertEquals(io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SKIPPED_POLICY,
          admission.outcome().outcomeClass());
      assertEquals(io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE,
          admission.outcome().retryPolicy());
    }
    // Fresh discovery still observes exclusions; an intentional explicit file has its own boundary.
    assertEquals(SourceAdmissionAction.SKIP_DONE, authority.admit(rooted(file, root)).action());
    assertEquals(SourceAdmissionAction.ADMIT, authority.admit(rooted(file, file)).action());
  }

  @Test
  void onlyUpgradeUnscopedDebtUsesTheBoundedRetryLadder() throws Exception {
    Path file = tempDir.resolve("unreadable.txt");
    var authority = new WorkerIngestionAuthority();
    for (String revision : java.util.List.of("release030/job/123/unit", "release030-unscoped/unit")) {
      var admission = authority.admit(new JobQueue.IndexJob(file, null, null, null, revision));
      assertEquals(SourceAdmissionAction.RETRYABLE_FAILURE, admission.action());
      assertEquals(IngestionReasonCodes.MISSING_INGESTION_BOUNDARY, admission.outcome().reasonCode());
      assertEquals(io.justsearch.indexerworker.ingest.IngestionRetryPolicy.RETRY_WITH_BACKOFF,
          admission.outcome().retryPolicy(), "upgrade uncertainty has the existing seven-day bound");
    }
  }

  @Test
  void unwatchedScanBoundaryIgnoresExcludedNamesAboveAndAtItsRoot() throws Exception {
    IngestionSkipPolicy.installResolved(new IngestionSkipPolicy(null, null, Set.of("private")));
    Path root = Files.createDirectories(tempDir.resolve("private").resolve("watched"));
    Path file = Files.writeString(root.resolve("notes.txt"), "public content");
    var authority = new WorkerIngestionAuthority();
    assertEquals(SourceAdmissionAction.ADMIT, authority.admit(rooted(file, root)).action());
    Path namedRoot = Files.createDirectory(root.resolve("private"));
    Path namedRootFile = Files.writeString(namedRoot.resolve("notes.txt"), "explicit root");
    assertEquals(SourceAdmissionAction.ADMIT,
        authority.admit(rooted(namedRootFile, namedRoot)).action());
    assertEquals(SourceAdmissionAction.SKIP_DONE,
        authority.admit(rooted(namedRootFile, root)).action());
  }

  @Test
  void queuedAdmissionRechecksExcludedAncestorsAfterPolicyChange() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("watched"));
    Path file = Files.writeString(Files.createDirectory(root.resolve("private")).resolve("notes.txt"),
        "private content");
    var authority = new WorkerIngestionAuthority();
    var claim = new JobQueue.IndexJob(
        file, null, null, null, null, null, false, null, root);
    assertEquals(SourceAdmissionAction.ADMIT, authority.admit(claim).action());
    IngestionSkipPolicy.installResolved(new IngestionSkipPolicy(null, null, Set.of("private")));
    assertEquals(SourceAdmissionAction.SKIP_DONE, authority.admit(claim).action());
    assertEquals(SourceAdmissionAction.ADMIT,
        authority.admit(Files.writeString(root.resolve("public.txt"), "public"), null).action());
  }

  @Test
  void C10PresentFileAdmissionDoesNotAuthorizeRetirementOfNonregularReplacement() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("unproven"));
    Path file = Files.writeString(root.resolve("source.txt"), "present readable content");
    var authority = new WorkerIngestionAuthority();
    authority.useRootCheck(new SourcePresence.RootCheck() {
      @Override public boolean admits(Path folder) { return true; }
      @Override public boolean vouches(Path folder) { return false; }
    });
    assertEquals(SourceAdmissionAction.ADMIT, authority.admit(rooted(file, root)).action());
    Files.delete(file); Files.createDirectory(file);
    var replacement = authority.admit(rooted(file, root));
    assertEquals(SourceAdmissionAction.RETRYABLE_FAILURE, replacement.action());
    assertEquals("ROOT_UNAVAILABLE", replacement.outcome().reasonCode());
  }

  private static JobQueue.IndexJob rooted(Path file, Path root) {
    return new JobQueue.IndexJob(file, null, null, null, null, null, false, null, root);
  }
}
