/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.indexerworker.extract.ValidatedExtractionArtifact;
import java.nio.file.Path;
import io.justsearch.indexerworker.queue.JobQueue;

/**
 * Intermediate state for a job that passed pre-checks and was extracted.
 *
 * <p>Tempdoc 516 Slice 3: lifted from {@code IndexingLoop}'s private nested record
 * so the upcoming Slice 4a {@code JobBatchExtractor} can return it without a circular
 * package dependency on the loop class. The original processing claim now supplies path,
 * collection and provenance through extraction and the eventual commit.
 */
public record ExtractedJob(
    JobQueue.IndexJob claim,
    ValidatedExtractionArtifact artifact,
    long startTime,
    FileEnvelope envelope,
    String sourceSha256,
    String docUid) {

  public Path filePath() { return claim.path(); }

  public String collection() { return claim.collection(); }

  public JobQueue.EnqueueProvenance provenance() { return claim.provenance(); }

  /** Legacy fixture without admission attribution; production carries the claimed snapshot. */
  public ExtractedJob(Path filePath, String collection, ValidatedExtractionArtifact artifact,
      long startTime, FileEnvelope envelope, String sourceSha256, String docUid) {
    this(new JobQueue.IndexJob(filePath, collection), artifact, startTime, envelope, sourceSha256, docUid);
  }

  /** Back-compatible test fixture shape; production extraction always supplies {@code docUid}. */
  public ExtractedJob(
      Path filePath,
      String collection,
      ValidatedExtractionArtifact artifact,
      long startTime,
      FileEnvelope envelope) {
    this(filePath, collection, artifact, startTime, envelope, null, null);
  }

  /** Back-compatible fixture shape from before source-byte provenance was captured. */
  public ExtractedJob(
      Path filePath,
      String collection,
      ValidatedExtractionArtifact artifact,
      long startTime,
      FileEnvelope envelope,
      String docUid) {
    this(filePath, collection, artifact, startTime, envelope, null, docUid);
  }
}
