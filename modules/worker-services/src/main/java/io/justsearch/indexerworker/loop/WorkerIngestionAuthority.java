/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.ingest.IngestionSkipPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Single Worker-side authority for source admission and freshness classification. */
final class WorkerIngestionAuthority {
  /**
   * Answers "does the index hold content for this source?" so an unreadable source is reported as
   * "indexed, source unreadable" rather than as a file that was never indexed. Never consulted for
   * a deletion decision.
   */
  private volatile java.util.function.Predicate<Path> indexedSource = ignored -> false;

  void useIndexedSourceProbe(java.util.function.Predicate<Path> probe) {
    this.indexedSource = java.util.Objects.requireNonNull(probe, "probe");
  }

  /**
   * The root-identity check every admission and deletion decision here asks (task
   * t-20261013-ef3c41, rounds 4 and 5). Until the Worker's service wires its persistent one in, an
   * in-memory one remembers the volume of each root this authority has admitted a source under.
   */
  private volatile SourcePresence.RootCheck rootCheck =
      io.justsearch.indexerworker.services.RootWitness.inMemory();

  void useRootCheck(SourcePresence.RootCheck check) {
    this.rootCheck = java.util.Objects.requireNonNull(check, "check");
  }

  /** What can be said about {@code source} under {@code root}, with this authority's root check. */
  SourcePresence.Verdict presence(Path source, Path root) {
    return SourcePresence.classify(source, root, rootCheck);
  }

  SourceAdmission admit(io.justsearch.indexerworker.queue.JobQueue.IndexJob job) throws IOException {
    if (job.ingestionRoot() == null) {
      // Pre-boundary queue and switch-buffer records cannot distinguish explicit files from
      // directory discoveries. Retire this admission before any source read; a fresh scan,
      // witnessed event or explicit-file request can admit it again with its actual boundary.
      return SourceAdmission.terminal(
          SourceAdmissionAction.SKIP_DONE,
          outcome(
              IngestionOutcomeClass.SKIPPED_POLICY,
              IngestionReasonCodes.MISSING_INGESTION_BOUNDARY,
              IngestionRetryPolicy.NONE,
              "Missing ingestion policy boundary; fresh admission required"));
    }
    return admit(job.path(), job.ingestionRoot());
  }

  private volatile java.util.function.BiPredicate<Path, Path> userExclusions = (file, root) -> false;

  void useFileExclusions(java.util.function.BiPredicate<Path, Path> exclusions) {
    userExclusions = java.util.Objects.requireNonNull(exclusions, "exclusions");
  }

  boolean excludes(Path file, Path root) {
    return IngestionSkipPolicy.shouldSkipWithinRoot(file, root) || userExclusions.test(file, root);
  }

  SourceAdmission admit(Path filePath, Path root) throws IOException {
    if (excludes(filePath, root)) {
      return SourceAdmission.terminal(
          SourceAdmissionAction.SKIP_DONE,
          outcome(
              IngestionOutcomeClass.SKIPPED_POLICY,
              IngestionReasonCodes.SKIPPED_TEMP_OR_SYSTEM,
              IngestionRetryPolicy.NONE,
              "Skipped by ingestion or user exclusion policy"));
    }

    // Task t-20261013-ef3c41: "cannot read it" is never "it is gone". The snapshot is taken first
    // (no links followed); a failure is classified, and only a confirmed absence — not found while
    // the root that holds it is present — retires the source and its indexed content.
    FileFreshnessSnapshot freshness;
    try {
      freshness = FileFreshnessSnapshot.capture(filePath);
    } catch (IOException notCaptured) {
      return switch (presence(filePath, root)) {
        case ABSENT ->
            SourceAdmission.terminal(
                SourceAdmissionAction.STALE_DONE,
                outcome(
                    IngestionOutcomeClass.STALE_SOURCE,
                    IngestionReasonCodes.DELETED_OR_MISSING,
                    IngestionRetryPolicy.NONE,
                    "Source deleted or missing"));
        case ROOT_UNAVAILABLE ->
            SourceAdmission.terminal(SourceAdmissionAction.RETRYABLE_FAILURE, rootUnavailable());
        // PRESENT here means it appeared between the two looks; one retry settles it.
        case PRESENT, SOURCE_UNREADABLE ->
            SourceAdmission.terminal(
                SourceAdmissionAction.RETRYABLE_FAILURE, sourceUnreadable(filePath, notCaptured));
      };
    }

    // Round 5 (S8): a present source is added, updated or retired only while its root is the one
    // it was. Another volume at the same drive letter, or a root that cannot be read, defers the
    // source with the root-level back-off; the original's indexed content is untouched. Round 8
    // (refined decision 2): admission records nothing for a root; only the person's add does.
    Path normalizedFile = filePath.toAbsolutePath().normalize();
    Path answering = SourcePresence.vouchingFolder(normalizedFile, root);
    boolean admitted = SourcePresence.answersAsRoot(normalizedFile, root)
        ? rootCheck.admits(answering)
        : rootCheck.admitsFile(answering);
    if (!admitted) {
      return SourceAdmission.terminal(SourceAdmissionAction.RETRYABLE_FAILURE, rootUnavailable());
    }

    if (!freshness.regularFile()) {
      // Task t-20261013-ef3c41 (S9): a file replaced by a folder or a link (dangling or not) is no
      // longer the indexed source. Its old text is retired instead of staying searchable forever;
      // the walks never follow links, so nothing is indexed in its place.
      return SourceAdmission.terminal(
          SourceAdmissionAction.STALE_DONE,
          outcome(
              IngestionOutcomeClass.SKIPPED_POLICY,
              IngestionReasonCodes.NON_REGULAR_SOURCE,
              IngestionRetryPolicy.NONE,
              "Source is not a regular file"));
    }

    if (!Files.isReadable(filePath)) {
      return SourceAdmission.terminal(
          SourceAdmissionAction.RETRYABLE_FAILURE, sourceUnreadable(filePath, null));
    }
    return SourceAdmission.admitted(FileEnvelope.fromSnapshot(freshness));
  }

  /**
   * The outcome for a source that exists but cannot be read now. Retried with counted, growing
   * back-off and never exhausted; any indexed content is kept.
   */
  IngestionOutcome sourceUnreadable(Path source, Throwable cause) {
    boolean indexed;
    try {
      indexed = source != null && indexedSource.test(source);
    } catch (RuntimeException unknown) {
      indexed = false;
    }
    return outcome(
        IngestionOutcomeClass.IO_FAILED,
        indexed
            ? IngestionReasonCodes.INDEXED_SOURCE_UNREADABLE
            : IngestionReasonCodes.SOURCE_UNREADABLE,
        IngestionRetryPolicy.RETRY_UNTIL_AVAILABLE,
        cause == null
            ? "File not readable"
            : "File not readable: " + cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage()));
  }

  /** The outcome for a source whose folder is unavailable. Retried until the folder is back. */
  static IngestionOutcome rootUnavailable() {
    return outcome(
        IngestionOutcomeClass.IO_FAILED,
        IngestionReasonCodes.ROOT_UNAVAILABLE,
        IngestionRetryPolicy.RETRY_UNTIL_AVAILABLE,
        "Folder unavailable");
  }

  /** The outcome for a {@link SourcePresence.Verdict} that does not authorise a deletion. */
  IngestionOutcome notConfirmedAbsent(
      SourcePresence.Verdict verdict, Path source, Throwable cause) {
    return verdict == SourcePresence.Verdict.ROOT_UNAVAILABLE
        ? rootUnavailable()
        : sourceUnreadable(source, cause);
  }

  IngestionOutcome staleOutcome(
      FileFreshnessSnapshot.SourceValidationResult validation, String timing) {
    if (validation == FileFreshnessSnapshot.SourceValidationResult.DELETED) {
      return outcome(
          IngestionOutcomeClass.STALE_SOURCE,
          IngestionReasonCodes.DELETED_AFTER_SNAPSHOT,
          IngestionRetryPolicy.NONE,
          "Source deleted after snapshot");
    }
    return outcome(
        IngestionOutcomeClass.STALE_SOURCE,
        AdmissionPolicy.staleReasonCode(validation),
        IngestionRetryPolicy.DEFER_WITHOUT_ATTEMPT,
        "Source changed " + timing);
  }

  private static IngestionOutcome outcome(
      IngestionOutcomeClass outcomeClass,
      String reasonCode,
      IngestionRetryPolicy retryPolicy,
      String diagnosticSummary) {
    return IngestionOutcome.of(outcomeClass, reasonCode, retryPolicy, diagnosticSummary);
  }
}
