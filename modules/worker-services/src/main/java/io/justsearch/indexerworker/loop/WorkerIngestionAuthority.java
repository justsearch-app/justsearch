/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.ingest.IngestionSkipPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Single Worker-side authority for source admission and freshness classification. */
final class WorkerIngestionAuthority {
  private volatile java.util.function.Function<Path, Path> rootResolver = path -> path.getRoot();

  void setRootResolver(java.util.function.Function<Path, Path> resolver) {
    rootResolver = java.util.Objects.requireNonNull(resolver);
  }

  SourceAdmission admit(Path filePath) throws IOException {
    Path absolute = filePath.toAbsolutePath().normalize();
    return admit(filePath, rootResolver.apply(absolute));
  }

  SourceAdmission admit(Path filePath, Path root) throws IOException {
    if (IngestionSkipPolicy.shouldSkipWithinRoot(filePath, root)) {
      return SourceAdmission.terminal(
          SourceAdmissionAction.SKIP_DONE,
          outcome(
              IngestionOutcomeClass.SKIPPED_POLICY,
              IngestionReasonCodes.SKIPPED_TEMP_OR_SYSTEM,
              IngestionRetryPolicy.NONE,
              "Skipped temporary or system file"));
    }

    if (!Files.exists(filePath, LinkOption.NOFOLLOW_LINKS)) {
      return SourceAdmission.terminal(
          SourceAdmissionAction.STALE_DONE,
          outcome(
              IngestionOutcomeClass.STALE_SOURCE,
              IngestionReasonCodes.DELETED_OR_MISSING,
              IngestionRetryPolicy.NONE,
              "Source deleted or missing"));
    }

    if (!Files.isReadable(filePath)) {
      return SourceAdmission.terminal(
          SourceAdmissionAction.RETRYABLE_FAILURE,
          outcome(
              IngestionOutcomeClass.IO_FAILED,
              IngestionReasonCodes.UNREADABLE,
              IngestionRetryPolicy.RETRY_WITH_BACKOFF,
              "File not readable"));
    }

    FileFreshnessSnapshot freshness = FileFreshnessSnapshot.capture(filePath);
    if (!freshness.regularFile()) {
      return SourceAdmission.terminal(
          SourceAdmissionAction.SKIP_DONE,
          outcome(
              IngestionOutcomeClass.SKIPPED_POLICY,
              IngestionReasonCodes.NON_REGULAR_SOURCE,
              IngestionRetryPolicy.NONE,
              "Source is not a regular file"));
    }
    return SourceAdmission.admitted(FileEnvelope.fromSnapshot(freshness));
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
