/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static io.justsearch.app.api.DocumentService.Presence.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.LuceneRuntime;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkerDocumentPresenceTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  @TempDir Path index;

  @Test
  void membershipDoesNotDependOnExtractedTextOrDiskAndReturnsAfterReindex() throws Exception {
    try (RunningRuntime runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
        .atPath(index).withExecutorRegistrations(testLuceneExecutors()).open()) {
      WorkerSearchService service = new WorkerSearchService(runtime);
      service.setPresenceGeneration(ctx -> "idle-generation");
      String id = index.resolve("never-on-disk.txt").toString();
      var document = new IndexDocument(Map.of(SchemaFields.DOC_ID, id,
          SchemaFields.DOC_UID, id + "#0", SchemaFields.PATH, id));
      runtime.indexingCoordinator().indexSingle(document);
      assertEquals(Map.of(id, PRESENT, "missing", ABSENT),
          service.checkDocumentPresence(List.of(id, "missing"), CallContext.none()));
      runtime.indexingCoordinator().deleteById(id);
      assertEquals(ABSENT, service.checkDocumentPresence(List.of(id), CallContext.none()).get(id));
      runtime.indexingCoordinator().indexSingle(document);
      assertEquals(PRESENT, service.checkDocumentPresence(List.of(id), CallContext.none()).get(id));
    }
  }

  @Test
  void failedCountIsUnknownForOnlyThatSourceAndNeverReadsContent() throws Exception {
    LuceneRuntime runtime = mock(RunningRuntime.class, RETURNS_DEEP_STUBS);
    IndexCountOps counts = runtime.indexCountOps();
    when(counts.countByFieldOrThrow(SchemaFields.DOC_ID, "bad")).thenThrow(new IOException("locked"));
    when(counts.countByFieldOrThrow(SchemaFields.DOC_ID, "held")).thenReturn(1);
    WorkerSearchService service = new WorkerSearchService(runtime);
    service.setPresenceGeneration(ctx -> "idle");
    assertEquals(Map.of("bad", UNKNOWN, "held", PRESENT, "gone", ABSENT),
        service.checkDocumentPresence(List.of("bad", "held", "gone"), CallContext.none()));
    verify(runtime.documentFieldOps(), never()).getDocumentContent(anyString());
  }

  @Test
  void missingAuthorityTransitionAndGenerationChangeNeverClaimAbsence() {
    LuceneRuntime runtime = mock(RunningRuntime.class, RETURNS_DEEP_STUBS);
    WorkerSearchService service = new WorkerSearchService(runtime);
    assertTrue(service.checkDocumentPresence(List.of("gone"), CallContext.none()).isEmpty());
    service.setPresenceGeneration(ctx -> { throw WorkerServiceException.unavailable("switching"); });
    assertTrue(service.checkDocumentPresence(List.of("gone"), CallContext.none()).isEmpty());
    AtomicInteger generation = new AtomicInteger();
    service.setPresenceGeneration(ctx -> "generation-" + generation.incrementAndGet());
    assertTrue(service.checkDocumentPresence(List.of("gone"), CallContext.none()).isEmpty());
    var rebuilding = WorkerSearchService.textOnlyCandidateView(runtime);
    rebuilding.setPresenceGeneration(ctx -> "idle");
    assertTrue(rebuilding.checkDocumentPresence(List.of("gone"), CallContext.none()).isEmpty());
  }

  @Test
  void boundsAreEnforcedAtTheWorker() {
    WorkerSearchService service = new WorkerSearchService(mock(RunningRuntime.class, RETURNS_DEEP_STUBS));
    assertThrows(WorkerServiceException.class, () -> service.checkDocumentPresence(
        java.util.Collections.nCopies(65, "id"), CallContext.none()));
    assertThrows(WorkerServiceException.class, () -> service.checkDocumentPresence(List.of(" "), CallContext.none()));
  }
}
