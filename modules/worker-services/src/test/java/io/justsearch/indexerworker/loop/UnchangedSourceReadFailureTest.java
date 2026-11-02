/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.JobQueue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class UnchangedSourceReadFailureTest {
  @TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(strings = {"mutated", "deleted", "read-failed"})
  void freshnessReadCannotAcknowledgeAnUnstableOrUnreadableSource(String change) throws Exception {
    check(change);
  }

  @org.junit.jupiter.api.Test
  void plannedSourceIdentityStillBypassesCurrentFreshness() throws Exception {
    check("planned-hash");
  }

  private void check(String change) throws Exception {
    Path file = Files.writeString(temporary.resolve("file.txt"), "source");
    var fields = mock(DocumentFieldOps.class, call -> {
      if (call.getMethod().getName().equals("isUnmodified")) return true;
      return RETURNS_DEFAULTS.answer(call);
    });
    var counts = mock(IndexCountOps.class);
    when(counts.docCountOrThrow()).thenReturn(1L);
    var parser = mock(TimeboxedContentExtractor.class);
    when(parser.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
    when(parser.extractArtifact(any())).thenThrow(new IOException("injected unavailable source"));
    var queue = mock(JobQueue.class);
    var journal = mock(IngestionOutcomeJournal.class);
    when(journal.outcome(any(), any(), any(), any())).thenCallRealMethod();
    doCallRealMethod().when(journal).recordOutcomeSafely(any(), any(), any());
    var identity = mock(DocumentIdentityStore.class);
    when(identity.resolve(anyString(), anyLong()))
        .thenReturn(new DocumentIdentityStore.Identity("hash", "uid", 1, 1));
    var authority = new WorkerIngestionAuthority();
    var roots = mock(SourcePresence.RootCheck.class);
    when(roots.admits(any())).thenReturn(true);
    when(roots.vouches(any())).thenReturn(true);
    authority.useRootCheck(roots);
    var extractor = new JobBatchExtractor(authority, journal, queue, parser,
        fields, counts, new BatchStats(), mock(StaleSnapshotResolver.class), mock(StaleSourceHandler.class),
        IndexingPacing.unthrottled(), new AtomicBoolean(true), new java.util.HashSet<>(),
        () -> mock(PathResolutionStore.class), () -> identity, (stage, ms, reason) -> {},
        () -> false, delta -> {}, new FileMutationFence());
    var claim = new JobQueue.IndexJob(file, null, null, null, null, null, false,
        change.equals("planned-hash") ? SourceContentHash.sha256(file) : null, temporary);
    try (var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
      hashes.when(() -> SourceContentHash.sha256WithinRoot(file, temporary)).thenAnswer(call -> {
        if (change.equals("read-failed")) throw new IOException("injected hash read failure");
        String hash = SourceContentHash.sha256(file);
        if (change.equals("deleted")) Files.delete(file);
        else if (!change.equals("planned-hash")) Files.writeString(file, "changed size during freshness read");
        return hash;
      });
      assertTrue(assertDoesNotThrow(() -> extractor.extractAll(List.of(claim))).isEmpty());
    }
    var outcome = org.mockito.ArgumentCaptor.forClass(IngestionOutcome.class);
    verify(queue).markClaimFailed(eq(claim), outcome.capture(), any());
    assertEquals(IngestionRetryPolicy.RETRY_WITH_BACKOFF, outcome.getValue().retryPolicy());
    if (change.equals("planned-hash")) {
      assertTrue(mockingDetails(fields).getInvocations().stream()
          .noneMatch(call -> call.getMethod().getName().equals("isUnmodified")),
          "a migration planned hash must bypass the unchanged shortcut");
      verify(parser).extractArtifact(file);
    }
    verify(queue, never()).markClaimDone(any(), any(), any());
    verify(journal, never()).recordOutcomeSafely(any(), eq("UNCHANGED"), any());
  }
}
