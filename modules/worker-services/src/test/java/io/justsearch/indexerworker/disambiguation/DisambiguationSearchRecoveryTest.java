/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.disambiguation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.server.EncoderBindings;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.*;
import io.opentelemetry.api.GlobalOpenTelemetry;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DisambiguationSearchRecoveryTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  private final List<AutoCloseable> restorers = new ArrayList<>();

  @BeforeEach void preserveGlobalState() throws Exception {
    // SearchResponseBuilder records search counters, fallback counters and latency histograms.
    var metrics = OperationalMetrics.getInstance();
    for (var field : OperationalMetrics.class.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers())) continue;
      field.setAccessible(true);
      Object value = field.get(metrics);
      if (value instanceof LongAdder counter) {
        long previous = counter.sum();
        restorers.add(() -> { counter.reset(); counter.add(previous); });
      } else if (value instanceof AtomicLong gauge) {
        long previous = gauge.get();
        restorers.add(() -> gauge.set(previous));
      }
    }
    // SearchExecutor's tracer lookup can initialize a previously unset global registration.
    for (String name : List.of("globalOpenTelemetry", "setGlobalCaller")) {
      var field = GlobalOpenTelemetry.class.getDeclaredField(name);
      field.setAccessible(true);
      Object previous = field.get(null);
      restorers.add(() -> field.set(null, previous));
    }
  }

  @AfterEach void restoreGlobalState() throws Exception {
    try {
      for (int i = restorers.size() - 1; i >= 0; i--) restorers.get(i).close();
    } finally { restorers.clear(); }
  }

  @Test void zeroNewFormReplayRepairsFiltersFacetLabelsCountsAndVariants() throws Exception {
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(4).fields());
    fields.add(new FieldCatalogDef.FieldDef(SchemaFields.ENTITY_PERSONS_RAW, "keyword", true, true,
        List.of("filter", "facet"), null, null, true));
    var store = spy(new EntityClusterStore(directory.resolve("entity-clusters.db")));
    try (var runtime = IndexSchema.fromCatalog(new FieldCatalogDef("entity-search", fields))
            .atPath(directory.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
        var service = new DisambiguationService(store)) {
      service.open();
      var mentions = Map.of("PERSON", List.of("john smith", "JOHN SMITH"));
      for (String raw : mentions.get("PERSON")) {
        runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
            SchemaFields.DOC_ID, raw, SchemaFields.DOC_UID, raw, SchemaFields.PATH, raw + ".txt",
            SchemaFields.CONTENT, "entity content", SchemaFields.ENTITY_PERSONS_RAW, List.of(raw))));
      }
      runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
      AtomicInteger reads = new AtomicInteger();
      doAnswer(call -> {
        if (reads.incrementAndGet() == 2) throw new SQLException("snapshot publication fault");
        return call.callRealMethod();
      }).when(store).loadAll();
      assertThrows(SQLException.class, () -> service.processBatch(mentions));
      assertTrue(service.snapshot().isEmpty());
      assertEquals(0, service.processBatch(mentions), "both mappings were already persisted");
      var bindings = new EncoderBindings(); bindings.bindDisambiguationService(service);
      var search = new WorkerSearchService(runtime, null, bindings);
      search.setClusterSnapshotSupplier(service::snapshot);
      for (String raw : mentions.get("PERSON")) {
        var response = search.search(SearchRequest.newBuilder().setQuery("entity").setLimit(20)
            .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true))
            .setFilters(SearchFilters.newBuilder().addEntityPersons(raw))
            .setFacets(FacetSpec.newBuilder().setInclude(true).addFields(
                FacetFieldSpec.newBuilder().setField(SchemaFields.ENTITY_PERSONS_RAW).setSize(20))).build(),
            CallContext.none());
        assertEquals(2, response.getTotalHits());
        assertEquals(Map.of("john smith", 2L), response.getFacetsOrThrow(SchemaFields.ENTITY_PERSONS_RAW).getCountsMap());
        var breakdown = response.getEntityFacetVariantsOrThrow(SchemaFields.ENTITY_PERSONS_RAW).getEntries(0);
        assertEquals(2, breakdown.getTotalCount());
        assertEquals(Set.of("john smith", "JOHN SMITH"), breakdown.getVariantsMap().keySet());
      }
    }
  }
}
