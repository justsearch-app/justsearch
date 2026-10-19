/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.extract.ExtractionArtifact;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The indexing loop's real {@link JobBatchWriter}, writing inside a given file-mutation fence and
 * held at a latch before one of its row publications. It has already passed its claim and
 * freshness checks when it is held, as a writer that checked its file just before it was deleted.
 */
public final class HeldFileWriter {
  private static final long MODIFIED = 1_700_000_000_000L;

  private final JobBatchWriter writer;
  private final CountDownLatch held = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);

  /**
   * @param holdBefore the 1-based row publication the writer is held before: 1 holds it before
   *     the file's first row, 2 after the parent and before its first chunk
   */
  public HeldFileWriter(RunningRuntime target, FileMutationFence fence, int holdBefore) {
    IndexingCoordinator coordinator = spy(target.indexingCoordinator());
    AtomicInteger publications = new AtomicInteger();
    doAnswer(call -> {
      if (publications.incrementAndGet() == holdBefore) {
        held.countDown();
        if (!release.await(30, TimeUnit.SECONDS)) {
          throw new IllegalStateException("held writer was never released");
        }
      }
      return call.callRealMethod();
    }).when(coordinator).indexSingle(any(IndexDocument.class));
    var jobQueue = mock(JobQueue.class);
    when(jobQueue.ownsClaimForPublication(any())).thenReturn(true);
    var extractor = mock(TimeboxedContentExtractor.class);
    when(extractor.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
    // The freshness check passes: the file was there when the writer checked it.
    var staleResolver = mock(StaleSnapshotResolver.class);
    writer = new JobBatchWriter(
        coordinator,
        mock(WorkerSignalBus.class),
        mock(EmbeddingProviderLifecycle.class),
        () -> null,
        mock(IngestionOutcomeJournal.class),
        jobQueue,
        extractor,
        mock(OperationalMetrics.class),
        mock(BatchStats.class),
        staleResolver,
        delta -> {},
        mock(IndexingDocumentOps.StageRecorder.class),
        () -> false,
        () -> false,
        () -> null,
        fence);
  }

  /** Content long enough for the parent to carry several chunks. */
  public static String chunkedContent() {
    return "alpha beta gamma delta epsilon zeta\n\n".repeat(500);
  }

  /** Writes {@code file} with {@code content}; returns once the write has finished. */
  public void write(Path file, String content) {
    writer.write(job(file, content), null);
  }

  /** Waits until the writer is held, inside the fence. */
  public boolean awaitHeld() throws InterruptedException {
    return held.await(30, TimeUnit.SECONDS);
  }

  public void release() {
    release.countDown();
  }

  private static ExtractedJob job(Path file, String content) {
    var artifact = ExtractionArtifact.full(
        new ExtractionResult(content, file.getFileName().toString(), "text/plain"),
        TikaExtractionPolicy.defaults(), "test-fixture", false);
    try {
      var envelope = new FileEnvelope(file, PathNormalizer.normalizeKey(file),
          "hash-" + file.getFileName(), null, content.length(), MODIFIED, true, MODIFIED);
      return new ExtractedJob(file, null, artifact.validate(TikaExtractionPolicy.defaults(), null),
          System.currentTimeMillis(), envelope, null, "uid-" + file.getFileName());
    } catch (ContentExtractor.ExtractionException e) {
      throw new IllegalStateException("fixture artifact failed validation", e);
    }
  }
}
