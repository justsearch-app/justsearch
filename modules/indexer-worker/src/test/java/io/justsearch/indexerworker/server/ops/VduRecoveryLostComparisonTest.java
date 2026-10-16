/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * A replayed VDU recovery whose conditional reset loses its comparison, on a real index: a
 * selected document that is no longer PROCESSING is resolved, and a selected document that is gone
 * stays a miss.
 */
final class VduRecoveryLostComparisonTest extends LuceneExecutorTestBase {

  private static final String BUFFER_KEY = "vdu_recovery:lost-comparison";

  @TempDir Path tempDir;
  private RunningRuntime runtime;
  private SqliteJobQueue queue;

  @BeforeEach
  void setUp() throws Exception {
    var layout = new IndexGenerationManager(tempDir.resolve("index")).initializeOrLoad();
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTestingWithVduRetryCount(0))
        .atPath(layout.activeGenerationPath())
        .withExecutorRegistrations(testLuceneExecutors())
        .open();
    queue = new SqliteJobQueue(tempDir.resolve("jobs.db"));
    queue.open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (queue != null) queue.close();
    if (runtime != null) runtime.close();
  }

  @Test
  void selectedDocumentAlreadyCompletedIsNotResetAndDoesNotKeepTheRow() throws Exception {
    String doc = processingDocument("completed-before-reset");
    seedRecoveryRow();

    drainWhileSelecting(selected -> setStatus(selected, SchemaFields.VDU_STATUS_COMPLETED));

    assertEquals(SchemaFields.VDU_STATUS_COMPLETED, status(doc),
        "a selected document that already completed is not reset to PENDING");
    assertEquals(0, queue.switchBufferDepth(),
        "a resolved document is not a miss and must not keep the recovery row");
  }

  @Test
  void selectedDocumentRemovedBeforeItsResetStaysAMissAndKeepsTheRow() throws Exception {
    String doc = processingDocument("removed-before-reset");
    seedRecoveryRow();

    drainWhileSelecting(selected -> {
      runtime.indexingCoordinator().deleteById(selected);
      commit();
    });

    assertNull(status(doc), "the selected document is gone");
    assertEquals(1, queue.switchBufferDepth(),
        "a missing selected document is a miss and keeps the recovery row for a later replay");
  }

  /** Drains the replay; the change lands after recovery selects the document, before its reset. */
  private void drainWhileSelecting(Consumer<String> change) throws Exception {
    DocumentFieldOps fields = spy(runtime.documentFieldOps());
    doAnswer(call -> {
      @SuppressWarnings("unchecked")
      List<String> selected = (List<String>) call.callRealMethod();
      assertEquals(1, selected.size(), "recovery selects the one PROCESSING document");
      change.accept(selected.get(0));
      return selected;
    }).when(fields).queryDocIdsByFieldOrThrow(
        eq(SchemaFields.VDU_STATUS), eq(SchemaFields.VDU_STATUS_PROCESSING), anyInt());
    RunningRuntime facade = mock(RunningRuntime.class);
    when(facade.documentFieldOps()).thenReturn(fields);
    when(facade.indexingCoordinator()).thenReturn(runtime.indexingCoordinator());
    when(facade.commitOps()).thenReturn(runtime.commitOps());

    KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(
        new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
            queue,
            facade,
            null,
            IndexingPacing.unthrottled(),
            null,
            null,
            new ObjectMapper(),
            () -> false,
            () -> true,
            LoggerFactory.getLogger(VduRecoveryLostComparisonTest.class)));
  }

  private String processingDocument(String name) throws Exception {
    String docId = tempDir.resolve(name + ".pdf").toAbsolutePath().toString();
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, docId);
    fields.put(SchemaFields.DOC_UID, UUID.randomUUID().toString());
    fields.put(SchemaFields.PATH, docId);
    fields.put(SchemaFields.CONTENT, "baseline content");
    fields.put(SchemaFields.CONTENT_PREVIEW, "baseline content");
    fields.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PROCESSING);
    fields.put(SchemaFields.INDEXED_AT, System.currentTimeMillis());
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    commit();
    assertEquals(SchemaFields.VDU_STATUS_PROCESSING, status(docId));
    return docId;
  }

  private void seedRecoveryRow() {
    assertTrue(queue.putSwitchBuffer(BUFFER_KEY, "VDU_RECOVER_PROCESSING", "{}"));
    assertEquals(1, queue.switchBufferDepth());
  }

  private void setStatus(String docId, String status) {
    assertTrue(runtime.indexingCoordinator().updateDocument(
        docId, Map.of(SchemaFields.VDU_STATUS, status)));
    commit();
  }

  private void commit() {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private String status(String docId) throws Exception {
    return runtime.documentFieldOps().getDocumentFieldOrThrow(docId, SchemaFields.VDU_STATUS);
  }
}
