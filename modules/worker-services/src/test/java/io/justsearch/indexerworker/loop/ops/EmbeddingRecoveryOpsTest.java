/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.BatchUpdateResult;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.EmbeddingFingerprint;
import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class EmbeddingRecoveryOpsTest {
  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(EmbeddingRecoveryOpsTest.class);

  @AfterEach
  void clearFingerprint() { EmbeddingFingerprint.invalidate(); }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "partial", "null", "overcount"})
  void refusesInexactBatch(String fault) {
    var fields = mock(DocumentFieldOps.class);
    var coordinator = mock(IndexingCoordinator.class);
    when(fields.queryDocIdsByField(eq(SchemaFields.EMBEDDING_STATUS),
        eq(SchemaFields.EMBEDDING_STATUS_COMPLETED), anyInt())).thenReturn(List.of("a", "b"));
    when(fields.queryDocIdsByField(eq(SchemaFields.EMBEDDING_STATUS),
        eq(SchemaFields.EMBEDDING_STATUS_FAILED), anyInt())).thenReturn(List.of());
    BatchUpdateResult result = switch (fault) {
      case "missing" -> new BatchUpdateResult(2, 1);
      case "partial" -> new BatchUpdateResult(1, 0);
      case "overcount" -> new BatchUpdateResult(3, 0);
      default -> null;
    };
    when(coordinator.updateDocumentsBatch(anyList())).thenReturn(result);
    assertThrows(IllegalStateException.class,
        () -> EmbeddingRecoveryOps.remarkEmbeddedParentDocsPending(fields, coordinator, 3, LOG));
  }

  @Test
  void refusesFullEnumerationAtCapBeforeMutating() {
    var fields = mock(DocumentFieldOps.class);
    var coordinator = mock(IndexingCoordinator.class);
    when(fields.queryDocIdsByField(eq(SchemaFields.EMBEDDING_STATUS),
        eq(SchemaFields.EMBEDDING_STATUS_COMPLETED), anyInt()))
        .thenAnswer(call -> Collections.nCopies(call.getArgument(2), "a"));
    assertThrows(IllegalStateException.class,
        () -> EmbeddingRecoveryOps.remarkEmbeddedParentDocsPending(fields, coordinator, 1 << 26, LOG));
    verify(coordinator, never()).updateDocumentsBatch(anyList());
  }

  @ParameterizedTest
  @ValueSource(strings = {"complete", "omitted", "failed", "count-fault", "commit-fault", "refresh-fault"})
  void transitionRequiresDurableVisibleCompleteCoverage(String fault) throws Exception {
    EmbeddingFingerprint.setForTesting("current-test-fingerprint");
    var ecc = new EmbeddingCompatibilityController(Map::of, () -> 2L, () -> 2);
    ecc.refresh();
    assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, ecc.state());
    var runtime = mock(RunningRuntime.class);
    var fields = mock(DocumentFieldOps.class);
    var coordinator = mock(IndexingCoordinator.class);
    var counts = mock(IndexCountOps.class);
    var commits = mock(CommitOps.class);
    when(runtime.documentFieldOps()).thenReturn(fields);
    when(runtime.indexingCoordinator()).thenReturn(coordinator);
    when(runtime.indexCountOps()).thenReturn(counts);
    when(runtime.commitOps()).thenReturn(commits);
    when(counts.docCountOrThrow()).thenReturn(2L);
    // Model an omitted query result: the strict post-write counts must still detect old vectors.
    when(fields.queryDocIdsByField(eq(SchemaFields.EMBEDDING_STATUS),
        eq(SchemaFields.EMBEDDING_STATUS_COMPLETED), anyInt())).thenReturn(List.of());
    when(fields.queryDocIdsByField(eq(SchemaFields.EMBEDDING_STATUS),
        eq(SchemaFields.EMBEDDING_STATUS_FAILED), anyInt())).thenReturn(List.of());
    when(counts.countByFieldOrThrow(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED))
        .thenReturn(fault.equals("omitted") ? 2 : 0);
    when(counts.countByFieldOrThrow(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_FAILED))
        .thenReturn(fault.equals("failed") ? 1 : 0);
    if (fault.equals("count-fault")) {
      when(counts.countByFieldOrThrow(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED))
          .thenThrow(new IOException("reader unavailable"));
    } else if (fault.equals("commit-fault")) {
      doThrow(new IllegalStateException("commit failed")).when(commits).commitAndTrack(CommitReason.VDU_RECOVERY);
    } else if (fault.equals("refresh-fault")) {
      doThrow(new IllegalStateException("refresh failed")).when(commits).maybeRefreshBlocking();
    }
    var outcome = EmbeddingRecoveryOps.rescueBlockedLegacyIndex(ecc, runtime, 3, LOG);
    assertEquals(fault.equals("complete"), outcome.rebuildStarted());
    if (fault.equals("complete")) {
      assertEquals(EmbeddingCompatibilityController.State.REBUILDING, ecc.state());
      var order = inOrder(commits, counts);
      order.verify(commits).commitAndTrack(CommitReason.VDU_RECOVERY);
      order.verify(commits).maybeRefreshBlocking();
      order.verify(counts).countByFieldOrThrow(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
      order.verify(counts).countByFieldOrThrow(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_FAILED);
    } else {
      assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, ecc.state());
      assertFalse(outcome.rebuildStarted());
    }
    assertTrue(ecc.fingerprintToStamp().isEmpty());
  }
}
