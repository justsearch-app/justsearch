/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.disambiguation.DisambiguationService;
import io.justsearch.indexerworker.loop.BackfillScheduler;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DisambiguationReadFailureTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  @ParameterizedTest @ValueSource(strings = {"readiness", "completedCount", "selection", "fields"})
  void realReaderFailuresCannotLatchIncompleteWork(String point) throws Exception {
    AtomicBoolean fail = new AtomicBoolean(true);
    AtomicBoolean reached = new AtomicBoolean();
    java.util.concurrent.atomic.AtomicInteger storedReads = new java.util.concurrent.atomic.AtomicInteger();
    var catalog = new FieldCatalogDef("strict-entities", List.of(
        new FieldCatalogDef.FieldDef(SchemaFields.DOC_ID, "keyword", true, false, List.of("id"), null, null, false),
        new FieldCatalogDef.FieldDef(SchemaFields.NER_STATUS, "keyword", true, false, List.of(), null, null, false),
        new FieldCatalogDef.FieldDef(SchemaFields.ENTITY_PERSONS_RAW, "keyword", true, true, List.of(), null, null, true)));
    try (var index = new ByteBuffersDirectory(); var analyzer = new StandardAnalyzer();
        var writer = new IndexWriter(index, new IndexWriterConfig(analyzer));
        var service = new DisambiguationService(directory)) {
      Document document = new Document();
      document.add(new StringField(SchemaFields.DOC_ID, "a", Field.Store.YES));
      document.add(new StringField(SchemaFields.NER_STATUS, "COMPLETED", Field.Store.YES));
      document.add(new StringField(SchemaFields.ENTITY_PERSONS_RAW, "john smith", Field.Store.YES));
      document.add(new StringField(SchemaFields.ENTITY_PERSONS_RAW, "JOHN SMITH", Field.Store.YES));
      document.add(new SortedSetDocValuesField(SchemaFields.ENTITY_PERSONS_RAW, new org.apache.lucene.util.BytesRef("john smith")));
      document.add(new SortedSetDocValuesField(SchemaFields.ENTITY_PERSONS_RAW, new org.apache.lucene.util.BytesRef("JOHN SMITH")));
      writer.addDocument(document);
      service.open();
      try (var manager = new SearcherManager(writer, new SearcherFactory() {
        @Override public IndexSearcher newSearcher(IndexReader reader, IndexReader previous) {
          return new IndexSearcher(reader) {
            boolean counting;
            void fault(String current) throws IOException {
              if (fail.get() && point.equals(current)) {
                reached.set(true);
                throw new IOException("injected " + point + " failure");
              }
            }
            @Override public int count(Query query) throws IOException {
              fault(query.toString().contains("PENDING") ? "readiness" : "completedCount");
              counting = true;
              try { return super.count(query); }
              finally { counting = false; }
            }
            @Override public Weight createWeight(Query query, ScoreMode mode, float boost) throws IOException {
              if (!counting) fault("selection");
              return super.createWeight(query, mode, boost);
            }
            @Override public StoredFields storedFields() throws IOException {
              StoredFields delegate = super.storedFields();
              return new StoredFields() {
                @Override public void document(int id, StoredFieldVisitor visitor) throws IOException {
                  if (storedReads.incrementAndGet() >= 2) fault("fields");
                  delegate.document(id, visitor);
                }
              };
            }
          };
        }
      })) {
        var session = new RuntimeSession(IndexSchema.fromCatalog(catalog), testLuceneExecutors());
        session.snapshot = new LifecycleSnapshot(index, writer, manager, directory, false, analyzer);
        var bridge = new SearcherBridge(session);
        var fields = new DocumentFieldOps(session, bridge, SchemaFields.DOC_ID, new ReadPathOps(session, SchemaFields.DOC_ID));
        var counts = new IndexCountOps(bridge);
        var scheduler = new BackfillScheduler(fields, null, counts, null, mock(WorkerSignalBus.class),
            IndexingPacing.unthrottled(), null, new AtomicBoolean(true), () -> null,
            () -> null, () -> null, () -> null, () -> service);
        var cycle = BackfillScheduler.class.getDeclaredMethod("runDisambiguationIfReady", boolean.class);
        cycle.setAccessible(true);
        cycle.invoke(scheduler, false);
        assertTrue(reached.get(), "the injected real Lucene fault must be exercised");
        assertTrue(service.snapshot().isEmpty());
        fail.set(false);
        for (int i = 0; i < 3; i++) cycle.invoke(scheduler, false);
        assertEquals(Set.of("john smith", "JOHN SMITH"), service.snapshot().expandCanonical("PERSON", service.snapshot().getCanonical("PERSON", "john smith")));
      }
    }
  }
}
