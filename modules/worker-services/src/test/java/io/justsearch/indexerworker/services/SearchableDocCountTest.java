/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.CoreStatus;
import io.justsearch.ipc.StatusResponse;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 811 C-4 — {@code CoreStatus.searchable_doc_count} against a real Lucene index.
 *
 * <p>The defect this pins: {@code doc_count} is "every non-chunk document", which includes the
 * agent-run transcripts the DEFAULT search scope excludes, so a "Searching N documents" string
 * built from it described a corpus the user cannot see or enumerate. The two numbers must therefore
 * DISAGREE on this fixture — an assertion that only checked {@code searchable == N} would still
 * pass if the field were wired to {@code doc_count} by mistake.
 */
@DisplayName("searchable doc count (tempdoc 811 C-4)")
final class SearchableDocCountTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  /** Documents in the default search scope: 2 user docs + 1 bundled help doc + 1 ad-hoc ingest. */
  private static final int DEFAULT_SCOPE_DOCS = 4;

  /** Agent-run transcripts — indexed, but excluded from the default scope. */
  private static final int AGENT_HISTORY_DOCS = 3;

  @TempDir Path tempDir;
  private RunningRuntime runtime;

  @BeforeEach
  void setUp() throws Exception {
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open();
    seedCorpus();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (runtime != null) {
      runtime.close();
    }
  }

  @Test
  @DisplayName("counts the default-scope population, while docCount keeps counting the whole index")
  void searchableExcludesDefaultExcludedCollections() {
    CoreStatus core = buildStatus().getCore();

    assertEquals(
        DEFAULT_SCOPE_DOCS + AGENT_HISTORY_DOCS,
        core.getDocCount(),
        "docCount keeps its meaning: every non-chunk document, transcripts included");
    assertEquals(
        DEFAULT_SCOPE_DOCS,
        core.getSearchableDocCount(),
        "searchableDocCount is what a DEFAULT-scope search can return — no agent-history");
    assertNotEquals(
        core.getDocCount(),
        core.getSearchableDocCount(),
        "the fixture must exercise the discrepancy, not accidentally agree");
  }

  @Test
  @DisplayName("help and ad-hoc-ingest documents COUNT — they are in the default scope")
  void appInternalButSearchableCollectionsAreCounted() {
    CoreStatus core = buildStatus().getCore();
    // 811 C-4 decision: only DEFAULT-EXCLUDED collections are subtracted. justsearch-help and
    // mcp-ingest documents are returnable by a default search, so counting them is the honest
    // number; a per-collection breakdown is deferred, not silently applied here.
    assertEquals(DEFAULT_SCOPE_DOCS, core.getSearchableDocCount());
  }

  @Test
  @DisplayName("chunk documents are outside both counts")
  void chunksAreExcludedFromBothCounts() {
    CoreStatus before = buildStatus().getCore();

    index(chunk("chunk-user", "/docs/a.txt", null));
    index(chunk("chunk-transcript", "/runs/r1.md", SchemaFields.AGENT_HISTORY_COLLECTION));
    commit();

    CoreStatus after = buildStatus().getCore();
    assertEquals(before.getDocCount(), after.getDocCount(), "chunks never enter docCount");
    assertEquals(
        before.getSearchableDocCount(),
        after.getSearchableDocCount(),
        "chunks never enter searchableDocCount either — it is a doc-tier number");
  }

  // ---- fixture ----------------------------------------------------------------

  private void seedCorpus() {
    index(doc("u1", "/docs/a.txt", null));
    index(doc("u2", "/docs/b.txt", "my-notes"));
    index(doc("h1", "/app/help/getting-started.md", "justsearch-help"));
    index(doc("m1", "/elsewhere/spec.md", "mcp-ingest"));
    index(doc("r1", "/runs/r1.md", SchemaFields.AGENT_HISTORY_COLLECTION));
    index(doc("r2", "/runs/r2.md", SchemaFields.AGENT_HISTORY_COLLECTION));
    index(doc("r3", "/runs/r3.md", SchemaFields.AGENT_HISTORY_COLLECTION));
    commit();
  }

  private void index(IndexDocument doc) {
    runtime.indexingCoordinator().indexSingle(doc);
  }

  private void commit() {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  /** A {@code null} collection omits the field — the untagged "index default" shape. */
  private static IndexDocument doc(String id, String path, String collection) {
    Map<String, Object> fields = baseFields(id, path);
    if (collection != null) {
      fields.put(SchemaFields.COLLECTION, collection);
    }
    return new IndexDocument(fields);
  }

  private static IndexDocument chunk(String id, String parentPath, String collection) {
    Map<String, Object> fields = baseFields(id, parentPath);
    fields.put(SchemaFields.IS_CHUNK, "true");
    fields.put(SchemaFields.PARENT_DOC_ID, parentPath);
    if (collection != null) {
      fields.put(SchemaFields.COLLECTION, collection);
    }
    return new IndexDocument(fields);
  }

  private static Map<String, Object> baseFields(String id, String path) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, id);
    fields.put(SchemaFields.DOC_UID, id + "#0");
    fields.put(SchemaFields.PATH, path);
    fields.put(SchemaFields.CONTENT, "content of " + id);
    return fields;
  }

  /**
   * Drives the production {@code buildStatusResponse} so the assertions cover the wiring as well as
   * the count: the counts come from a REAL {@link io.justsearch.adapters.lucene.runtime.IndexCountOps}
   * over the seeded index; only the queue/loop/bus collaborators are stubbed.
   */
  private StatusResponse buildStatus() {
    JobQueue jobQueue = mock(JobQueue.class);
    when(jobQueue.jobStateCounts()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 0, 0));
    when(jobQueue.pendingBytes()).thenReturn(JobQueue.PendingBytes.EMPTY);

    IndexStatusOps ops =
        new IndexStatusOps(
            jobQueue,
            tempDir,
            runtime.indexCountOps(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            // The real singleton, not a mock: the enrichment sub-message feeds proto setters that
            // reject null, and this test asserts nothing about metrics — only reads them.
            OperationalMetrics.getInstance(),
            mock(IndexingLoop.class),
            mock(WorkerSignalBus.class),
            0L);
    return ops.buildStatusResponse();
  }
}
