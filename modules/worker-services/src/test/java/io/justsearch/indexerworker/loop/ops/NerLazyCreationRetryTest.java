/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ort.SessionTemporarilyUnavailableException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** M3a: a lazy session failure is retried and then FAILED, never COMPLETED_EMPTY. */
final class NerLazyCreationRetryTest {
  @Test
  void acquisitionFailuresFollowTheExistingDocumentRetryPolicy() throws Exception {
    DocumentFieldOps fields = mock(DocumentFieldOps.class);
    IndexingCoordinator index = mock(IndexingCoordinator.class);
    CommitOps commits = mock(CommitOps.class);
    NerService ner = mock(NerService.class);
    BackfillSnapshotTestSupport.install(fields, index, true, Set.of());
    when(fields.queryDocIdsByField(eq(SchemaFields.NER_STATUS), eq(SchemaFields.NER_STATUS_PENDING), anyInt()))
        .thenReturn(List.of("doc"));
    when(fields.getDocumentContent("doc")).thenReturn("Ada Lovelace in London");
    when(ner.extractEntitiesBatch(anyList())).thenThrow(
        new SessionTemporarilyUnavailableException("ner: CPU session creation failed"));
    var context = new NerBackfillOps.BackfillContext(fields, index, commits,
        IndexingPacing.unthrottled(), () -> ner, () -> true, 10,
        LoggerFactory.getLogger(NerLazyCreationRetryTest.class));
    List<Map<String, Object>> writes = new ArrayList<>();
    when(index.updateDocumentsBatch(anyList())).thenAnswer(call -> {
      List<Map.Entry<String, Map<String, Object>>> updates = call.getArgument(0);
      assertEquals(1, updates.size());
      assertEquals("doc", updates.get(0).getKey());
      writes.add(new HashMap<>(updates.get(0).getValue()));
      return new io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.BatchUpdateResult(1, 0);
    });
    for (int attempt = 0; attempt < SchemaFields.NER_MAX_RETRIES; attempt++) {
      when(fields.getDocumentField("doc", SchemaFields.NER_RETRY_COUNT)).thenReturn(Integer.toString(attempt));
      var outcome = assertDoesNotThrow(() -> NerBackfillOps.processNerBackfill(context));
      assertTrue(outcome.success());
      assertEquals(0, outcome.docsProcessed());
      Map<String, Object> write = writes.get(attempt);
      assertEquals(Integer.toString(attempt + 1), write.get(SchemaFields.NER_RETRY_COUNT));
      if (attempt + 1 < SchemaFields.NER_MAX_RETRIES) assertFalse(write.containsKey(SchemaFields.NER_STATUS));
      else assertEquals(SchemaFields.NER_STATUS_FAILED, write.get(SchemaFields.NER_STATUS));
      assertFalse(write.containsKey(SchemaFields.ENTITY_PERSONS_RAW));
    }
    verify(index, never()).publishEnrichmentBatch(argThat(updates -> updates.stream().anyMatch(entry ->
        SchemaFields.NER_STATUS_COMPLETED.equals(entry.getValue().get(SchemaFields.NER_STATUS))
            || SchemaFields.NER_STATUS_COMPLETED_EMPTY.equals(entry.getValue().get(SchemaFields.NER_STATUS)))), anyMap());
  }
}
