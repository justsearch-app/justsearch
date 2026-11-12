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
   * The root-identity check every admission and deletion decision here asks (#758). Until the
   * Worker's service wires its persistent one in, an
   * in-memory one remembers the volume of each root this authority has admitted a source under.
   */
  private volatile SourcePresence.RootCheck rootCheck =
      io.justsearch.indexerworker.services.RootWitness.inMemory();

  void useRootCheck(SourcePresence.RootCheck check) {
    this.rootCheck = java.util.Objects.requireNonNull(check, "check");
  }

  SourcePresence.RootCheck rootCheck() {
    return rootCheck;
  }

  /** What can be said about {@code source} under {@code root}, with this authority's root check. */
  SourcePresence.Verdict presence(Path source, Path root) {
    return SourcePresence.classify(source, root, rootCheck);
  }

  SourceAdmission admit(io.justsearch.indexerworker.queue.JobQueue.IndexJob job) throws IOException {
    if (job.ingestionRoot() == null) {
      // Ordinary historical/main admissions retain policy retirement. Only original upgrade
      // debt, or unowned rows quarantined while invalidating an upgrade checkpoint, can still
      // acquire scope from recovery. Keep their content, with the existing seven-day retry bound.
      String revision = job.unitRevision();
      boolean recovery = revision != null && (revision.startsWith("release030/")
          || revision.startsWith("release030-unscoped/"));
      return SourceAdmission.terminal(
          recovery ? SourceAdmissionAction.RETRYABLE_FAILURE : SourceAdmissionAction.SKIP_DONE,
          outcome(
              recovery ? IngestionOutcomeClass.IO_FAILED : IngestionOutcomeClass.SKIPPED_POLICY,
              IngestionReasonCodes.MISSING_INGESTION_BOUNDARY,
              recovery ? IngestionRetryPolicy.RETRY_WITH_BACKOFF : IngestionRetryPolicy.NONE,
              "Missing ingestion policy boundary; fresh admission required"));
    }
    return admit(job.path(), job.ingestionRoot(), job.collection());
  }

  /** Upkeep never transfers ownership or reads replacement storage under an old declaration. */
  boolean upkeepCurrent(io.justsearch.indexerworker.queue.JobQueue.IndexJob claim,
      io.justsearch.indexerworker.queue.JobQueue queue,
      io.justsearch.adapters.lucene.runtime.DocumentFieldOps fields) {
    String owner = claim.upkeepOriginId();
    if (owner == null) return true;
    queue.allowsIngestionBoundary(io.justsearch.indexerworker.queue.JobQueue.IngestionBoundary.HASHING,
        claim.scanId(), claim);
    if (!queue.ownsClaimForPublication(claim)) return false;
    var origin = queue.sourceOrigin(owner).orElse(null);
    if (origin == null || origin.kind() != io.justsearch.app.api.indexing.SourceOrigin.Kind.IMPORT
        || !origin.root().path().equals(claim.ingestionRoot())
        || !claim.path().startsWith(origin.root().path())
        || origin.root().singleFile() && !claim.path().equals(origin.root().path())
        || origin.root().excludedSubtrees().stream().anyMatch(claim.path()::startsWith)
        || io.justsearch.configuration.excludes.ExclusionPatterns.fromPatterns(origin.root().excludePatterns())
            .isExcludedPath(origin.root().path(), claim.path())
        || excludes(claim.path(), origin.root().path(), claim.collection())) return false;
    String key = io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(claim.path());
    var wanted = new java.util.HashSet<>(io.justsearch.indexing.SourceEnvelope.FIELDS);
    wanted.add(io.justsearch.indexing.SchemaFields.DOC_ID);
    java.util.Map<String, String> row;
    try {
      row = fields.getDocumentFieldsBatchOrThrow(java.util.List.of(key), wanted).get(key);
    } catch (IOException unavailable) { throw new java.io.UncheckedIOException(unavailable); }
    // A post-retirement recreation has no parent. Exact queue ownership still fences re-imports
    // and Apply Excludes, which cancel their displaced claims under the same mutation fence.
    if (row == null) return true;
    if (!owner.equals(row.get(io.justsearch.indexing.SchemaFields.ORIGIN_ID))) return false;
    var recorded = io.justsearch.indexing.SourceEnvelope.decode(row).orElse(null);
    if (recorded == null || recorded.storageStatus() != io.justsearch.indexing.SourceEnvelope.StorageStatus.KNOWN) return true;
    var current = io.justsearch.indexerworker.services.StorageObservationSnapshot.capture()
        .observedEnvelope(claim.path(), owner);
    return current.storageStatus() == io.justsearch.indexing.SourceEnvelope.StorageStatus.KNOWN
        && java.util.Objects.equals(recorded.storageMount(), current.storageMount())
        && java.util.Objects.equals(recorded.storageKey(), current.storageKey());
  }

  private volatile java.util.function.BiPredicate<Path, Path> userExclusions = (file, root) -> false;

  void useFileExclusions(java.util.function.BiPredicate<Path, Path> exclusions) {
    userExclusions = java.util.Objects.requireNonNull(exclusions, "exclusions");
  }

  boolean excludes(Path file, Path root, String collection) {
    return IngestionSkipPolicy.shouldSkipWithinRoot(file, root, collection) || userExclusions.test(file, root);
  }

  SourceAdmission admit(Path filePath, Path root, String collection) throws IOException {
    if (excludes(filePath, root, collection)) {
      return SourceAdmission.terminal(
          SourceAdmissionAction.SKIP_DONE,
          outcome(
              IngestionOutcomeClass.SKIPPED_POLICY,
              IngestionSkipPolicy.isProtected(filePath, root, collection)
                  ? IngestionReasonCodes.SKIPPED_PROTECTED : IngestionReasonCodes.SKIPPED_TEMP_OR_SYSTEM,
              IngestionRetryPolicy.NONE,
              IngestionSkipPolicy.isProtected(filePath, root, collection)
                  ? "Skipped protected credential or application data file" : "Skipped by ingestion or user exclusion policy"));
    }

    // #758: "cannot read it" is never "it is gone". The snapshot is taken first
    // (no links followed); a failure is classified, and only a confirmed absence — not found while
    // the root that holds it is present — retires the source and its indexed content.
    FileFreshnessSnapshot freshness;
    try {
      SourceAccess.requireSafeAncestors(filePath, root);
    } catch (IOException | SecurityException unsafeAncestor) {
      return SourceAdmission.terminal(SourceAdmissionAction.RETRYABLE_FAILURE,
          sourceUnreadable(filePath, unsafeAncestor));
    }
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

    // Present regular files use admission authority. Retirement below requires deletion proof
    // even when present-file admission can proceed without a platform volume identity. A changed
    // volume or unreadable root defers the source; its indexed content stays untouched.
    Path normalizedFile = filePath.toAbsolutePath().normalize();
    Path answering = SourcePresence.vouchingFolder(normalizedFile, root);
    boolean rooted = SourcePresence.answersAsRoot(normalizedFile, root);
    boolean exactRecovery = !rooted && rootCheck.isRelease030ExactFile(normalizedFile);
    boolean admitted = exactRecovery || (rooted ? rootCheck.admits(answering) : rootCheck.admitsFile(answering));
    if (!admitted) {
      return SourceAdmission.terminal(SourceAdmissionAction.RETRYABLE_FAILURE, rootUnavailable());
    }

    if (!freshness.regularFile()) {
      if (exactRecovery) {
        return SourceAdmission.terminal(SourceAdmissionAction.RETRYABLE_FAILURE,
            sourceUnreadable(filePath, null));
      }
      boolean canRetire = rooted ? rootCheck.vouches(answering) : rootCheck.vouchesForFile(answering);
      if (!canRetire || io.justsearch.adapters.lucene.runtime.PathPresence.absentBeneathListableFolder(
          normalizedFile, answering, null) == io.justsearch.adapters.lucene.runtime.PathPresence.UNKNOWN) {
        return SourceAdmission.terminal(SourceAdmissionAction.RETRYABLE_FAILURE, rootUnavailable());
      }
      // #758: a file replaced by a folder or a link (dangling or not) is no
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

    if (io.justsearch.indexerworker.ingest.CloudPlaceholderDetector.isCloudPlaceholder(filePath)) {
      return SourceAdmission.terminal(SourceAdmissionAction.RETRYABLE_FAILURE,
          outcome(IngestionOutcomeClass.DEFERRED_POLICY, IngestionReasonCodes.CLOUD_PLACEHOLDER,
              IngestionRetryPolicy.DEFER_WITHOUT_ATTEMPT, "Cloud-only placeholder"));
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
