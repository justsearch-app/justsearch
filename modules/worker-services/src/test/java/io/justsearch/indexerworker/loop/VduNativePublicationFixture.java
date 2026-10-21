/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

public final class VduNativePublicationFixture {
  private VduNativePublicationFixture() {}
  public static void publish(io.justsearch.adapters.lucene.runtime.RunningRuntime runtime, java.nio.file.Path file,
      String content, String uid) throws Exception {
    var queue=org.mockito.Mockito.mock(io.justsearch.indexerworker.queue.JobQueue.class);
    org.mockito.Mockito.when(queue.ownsClaimForPublication(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    var extractor=org.mockito.Mockito.mock(io.justsearch.indexerworker.extract.TimeboxedContentExtractor.class);
    org.mockito.Mockito.when(extractor.extractionPolicy()).thenReturn(io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults());
    var artifact=io.justsearch.indexerworker.fixtures.TestDocumentBuilder.validatedArtifact(
        new io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult(content,file.getFileName().toString(),"image/png"));
    long modified=java.nio.file.Files.getLastModifiedTime(file).toMillis();
    var envelope=new FileEnvelope(file,
        io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(file),"path-hash",null,
        java.nio.file.Files.size(file),modified,true,modified);
    var job=new ExtractedJob(file,null,artifact,System.currentTimeMillis(),envelope,
        SourceContentHash.sha256(file),uid);
    var writer=new JobBatchWriter(runtime.indexingCoordinator(),
        org.mockito.Mockito.mock(io.justsearch.indexerworker.coordination.WorkerSignalBus.class),
        org.mockito.Mockito.mock(EmbeddingProviderLifecycle.class),()->null,
        org.mockito.Mockito.mock(IngestionOutcomeJournal.class),queue,extractor,
        org.mockito.Mockito.mock(io.justsearch.indexerworker.metrics.OperationalMetrics.class),
        org.mockito.Mockito.mock(io.justsearch.indexerworker.loop.ops.BatchStats.class),
        org.mockito.Mockito.mock(StaleSnapshotResolver.class),ignored->{},
        org.mockito.Mockito.mock(io.justsearch.indexerworker.loop.ops.IndexingDocumentOps.StageRecorder.class),
        ()->false,()->false,()->null,new FileMutationFence());
    writer.write(job,null);
    runtime.commitOps().commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.TIMER);
    runtime.commitOps().maybeRefreshBlocking();
  }
}
