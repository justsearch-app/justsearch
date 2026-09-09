/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.indexerworker.extract.ValidatedExtractionArtifact;
import java.nio.file.Path;

/**
 * Intermediate state for a job that passed pre-checks and was extracted.
 *
 * <p>Tempdoc 516 Slice 3: lifted from {@code IndexingLoop}'s private nested record
 * so the upcoming Slice 4a {@code JobBatchExtractor} can return it without a circular
 * package dependency on the loop class. The record shape is unchanged.
 */
public record ExtractedJob(
    Path filePath,
    String collection,
    ValidatedExtractionArtifact artifact,
    long startTime,
    FileEnvelope envelope,
    String sourceSha256,
    String docUid,
    io.justsearch.indexerworker.queue.JobQueue.EnqueueProvenance provenance) {

  /** Legacy fixture without admission attribution; production carries the claimed snapshot. */
  public ExtractedJob(Path filePath, String collection, ValidatedExtractionArtifact artifact,
      long startTime, FileEnvelope envelope, String sourceSha256, String docUid) {
    this(filePath, collection, artifact, startTime, envelope, sourceSha256, docUid, null);
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
