/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FilterDirectoryReader;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic CPU-only publication measurement; opt in with -PincludeExperiment=true. */
@Tag("experiment")
final class SparsePreservationBenchmarkTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  @Test
  @org.junit.jupiter.api.Timeout(value = 10, unit = java.util.concurrent.TimeUnit.MINUTES)
  void populatedSparseRewriteCost() throws Exception {
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(384).fields());
    fields.add(new FieldDef(SchemaFields.SPLADE, "splade", false, false, List.of(), null,
        null, false, "reset-status:splade_status"));
    fields.add(new FieldDef(SchemaFields.SPLADE_STATUS, "keyword", true, true,
        List.of("filter"), null, null, false));
    fields.add(new FieldDef(SchemaFields.SPLADE_RETRY_COUNT, "long", true, true,
        List.of(), null, null, false));
    var legacyFields = List.copyOf(fields);
    fields.add(new FieldDef("splade_payload", "binary-stored", true, false, List.of(), null, null, false));
    for (int documents : List.of(5_000, 50_000)) {
      try (var runtime = IndexSchema.fromCatalog(new FieldCatalogDef("sparse-cost", fields))
          .atPath(directory.resolve("index-" + documents))
          .withExecutorRegistrations(testLuceneExecutors()).open()) {
        runtime.commitOps().stopCommitTimer();
        runtime.session().snapshot.writer().getConfig().setMergePolicy(org.apache.lucene.index.NoMergePolicy.INSTANCE);
        int vocabulary = 30_000;
        int featuresPerDocument = 128;
        for (int doc = 0; doc < documents; doc++) {
          Map<String, Float> sparse = new HashMap<>();
          for (int feature = 0; feature < featuresPerDocument; feature++) {
            sparse.put("term-" + ((doc * 127 + feature) % vocabulary), 1f + feature / 128f);
          }
          runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
              SchemaFields.DOC_ID, "doc-" + doc, SchemaFields.DOC_UID, "uid-" + doc,
              SchemaFields.CONTENT, "fixed body " + doc + " abcdefghijklmnopqrstuvwxyz".repeat(64),
              SchemaFields.SPLADE, sparse, SchemaFields.SPLADE_STATUS, "COMPLETED",
              SchemaFields.SPLADE_RETRY_COUNT, "0")));
          if ((doc + 1) % (documents / 10) == 0) runtime.commitOps().commitAndTrack();
        }
        var cacheType = java.util.Arrays.stream(WritePathOps.class.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals("ParentSliceCache")).findFirst().orElseThrow();
        var cacheConstructor = cacheType.getDeclaredConstructor();
        cacheConstructor.setAccessible(true);
        var reread = WritePathOps.class.getDeclaredMethod("sparseForBatch", IndexSearcher.class, List.class, cacheType);
        reread.setAccessible(true);
        var preserve = WritePathOps.class.getDeclaredMethod("applyRmwPolicies", IndexSearcher.class,
            int.class, Map.class, Map.class, cacheType);
        preserve.setAccessible(true);
        for (int batchSize : List.of(1, 8, 32, 128)) {
          List<Long> sparseTimes = new ArrayList<>();
          List<Long> batchTimes = new ArrayList<>();
          var updates = new ArrayList<Map.Entry<String, Map<String, Object>>>();
          for (int doc = 0; doc < batchSize; doc++) {
            float[] dense = new float[384];
            dense[0] = 1f;
            updates.add(Map.entry("doc-" + (doc * documents / batchSize), Map.of(
                SchemaFields.VECTOR, dense, SchemaFields.EMBEDDING_STATUS, "COMPLETED")));
          }
          // Reuse an immutable populated snapshot so every sample has the same segment shape.
          AtomicLong visited = new AtomicLong(), storedReads = new AtomicLong();
          try (var reader = counted(DirectoryReader.open(runtime.session().snapshot.writer()), visited, storedReads)) {
            assertTrue(reader.leaves().size() >= 10);
            var searcher = new IndexSearcher(reader);
            var ids = new HashSet<Integer>();
            for (var update : updates) ids.add(searcher.search(new org.apache.lucene.search.TermQuery(
                new org.apache.lucene.index.Term(SchemaFields.DOC_ID, update.getKey())), 1).scoreDocs[0].doc);
            long expectedTerms = 0;
            for (int pass = 0; pass < 7; pass++) {
              visited.set(0); storedReads.set(0);
              long started = System.nanoTime();
              var cache = cacheConstructor.newInstance();
              reread.invoke(runtime.session().writePathOps, searcher, updates, cache);
              var storedField = cacheType.getDeclaredField("stored");
              storedField.setAccessible(true);
              @SuppressWarnings("unchecked")
              var stored = (Map<Integer, org.apache.lucene.document.Document>) storedField.get(cache);
              long features = 0;
              long payloadBytes = 0, textBytes = 0;
              for (int id : ids) {
                var merged = new HashMap<String, Object>();
                merged.put(SchemaFields.SPLADE_STATUS, "COMPLETED");
                var payload = stored.get(id).getBinaryValue("splade_payload");
                payloadBytes += payload.length;
                textBytes += stored.get(id).get(SchemaFields.CONTENT).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                merged.put("splade_payload", payload);
                preserve.invoke(runtime.session().writePathOps, searcher, id, Map.of(), merged, cache);
                features += ((Map<?, ?>) merged.get(SchemaFields.SPLADE)).size();
              }
              long elapsed = System.nanoTime() - started;
              assertEquals((long) batchSize * featuresPerDocument, features);
              assertEquals(expectedTerms, visited.get(), "payload preservation visits no sparse terms");
              assertEquals(batchSize, storedReads.get(), "one unfiltered stored-field read per row");
              if (pass == 6) System.out.println("SPARSE_STORED_READ documents=" + documents + " batchSize=" + batchSize
                  + " payloadBytes=" + payloadBytes + " textBytes=" + textBytes + " storedDocumentsLoaded=" + stored.size());
              sparseTimes.add(elapsed);
              storedReads.set(0);
              started = System.nanoTime();
              assertEquals(batchSize, runtime.session().writePathOps.readModifyWriteBatch(searcher, updates).updatedCount());
              batchTimes.add(System.nanoTime() - started);
              assertEquals(batchSize, storedReads.get(), "RMW reuses loaded fields without a payload seek");
            }
            var reads = sparseTimes.subList(2, sparseTimes.size()).stream().sorted().toList();
            var writes = batchTimes.subList(2, batchTimes.size()).stream().sorted().toList();
            System.out.println("SPARSE_COST documents=" + documents + " batchSize=" + batchSize
                + " leaves=" + reader.leaves().size() + " termsVisited=" + expectedTerms
                + " featuresRead=" + (long) batchSize * featuresPerDocument
                + " rereadMedianNs=" + reads.get(2) + " batchMedianNs=" + writes.get(2)
                + " rereadPerRewriteNs=" + reads.get(2) / batchSize
                + " batchPerRewriteNs=" + writes.get(2) / batchSize);
            System.out.println("SPARSE_READ_SAMPLES_NS=" + sparseTimes);
            System.out.println("SPARSE_BATCH_SAMPLES_NS=" + batchTimes);
          }
        }
      }
      measureStorage(documents, fields, legacyFields);
    }
  }

  private void measureStorage(int parents, List<FieldDef> payloadFields, List<FieldDef> legacyFields) throws Exception {
    long[][] sizes = new long[2][2];
    for (int variant = 0; variant < 2; variant++) {
      Path path = directory.resolve("storage-" + parents + "-" + variant);
      try (var fs = org.apache.lucene.store.FSDirectory.open(path)) {
        var config = new org.apache.lucene.index.IndexWriterConfig(new org.apache.lucene.analysis.standard.StandardAnalyzer());
        config.setMergePolicy(org.apache.lucene.index.NoMergePolicy.INSTANCE);
        var mapper = new FieldMapper(new FieldCatalogDef("size", variant == 0 ? legacyFields : payloadFields));
        try (var writer = new org.apache.lucene.index.IndexWriter(fs, config)) {
          for (int id = 0; id < parents; id++) {
            Map<String, Float> sparse = new HashMap<>();
            for (int feature = 0; feature < 128; feature++)
              sparse.put("term-" + ((id * 127 + feature) % 30_000), 1f + feature / 128f);
            for (boolean chunk : List.of(false, true)) {
              var fields = new HashMap<String, Object>(Map.of(
                  SchemaFields.DOC_ID, (chunk ? "chunk-" : "doc-") + id,
                  SchemaFields.DOC_UID, (chunk ? "chunk-" : "uid-") + id,
                  SchemaFields.SPLADE, sparse, SchemaFields.SPLADE_STATUS, "COMPLETED",
                  SchemaFields.SPLADE_RETRY_COUNT, 0));
              if (chunk) {
                fields.put(SchemaFields.IS_CHUNK, true);
                fields.put(SchemaFields.PARENT_DOC_ID, "doc-" + id);
                fields.put(SchemaFields.CHUNK_CONTENT, "fixed body " + id + " abcdefghijklmnopqrstuvwxyz".repeat(64));
              } else fields.put(SchemaFields.CONTENT, "fixed body " + id + " abcdefghijklmnopqrstuvwxyz".repeat(64));
              writer.addDocument(mapper.toDocument(fields, null));
            }
            if ((id + 1) % (parents / 10) == 0) writer.commit();
          }
          writer.commit(); sizes[variant][0] = indexBytes(fs);
          writer.getConfig().setMergePolicy(new org.apache.lucene.index.TieredMergePolicy());
          writer.forceMerge(1); writer.commit(); sizes[variant][1] = indexBytes(fs);
        }
      }
    }
    System.out.println("SPARSE_STORAGE parents=" + parents + " chunks=" + parents
        + " legacyCompressed=" + sizes[0][0] + " payloadCompressed=" + sizes[1][0]
        + " legacyMerged=" + sizes[0][1] + " payloadMerged=" + sizes[1][1]
        + " mergedGrowthPercent=" + 100.0 * (sizes[1][1] - sizes[0][1]) / sizes[0][1]);
  }

  private static long indexBytes(org.apache.lucene.store.Directory directory) throws java.io.IOException {
    long size = 0;
    for (String file : directory.listAll()) if (!file.equals("write.lock")) size += directory.fileLength(file);
    return size;
  }

  private static DirectoryReader counted(DirectoryReader reader, AtomicLong visited, AtomicLong storedReads) throws Exception {
    return new FilterDirectoryReader(reader, new FilterDirectoryReader.SubReaderWrapper() {
      @Override public LeafReader wrap(LeafReader leaf) {
        return new FilterLeafReader(leaf) {
          @Override public org.apache.lucene.index.StoredFields storedFields() throws java.io.IOException {
            var delegate = super.storedFields();
            return new org.apache.lucene.index.StoredFields() {
              @Override public void document(int doc, org.apache.lucene.index.StoredFieldVisitor visitor) throws java.io.IOException {
                storedReads.incrementAndGet(); delegate.document(doc, visitor);
              }
            };
          }
          @Override public Terms terms(String field) throws java.io.IOException {
            Terms terms = super.terms(field);
            if (terms == null || !SchemaFields.SPLADE.equals(field)) return terms;
            return new FilterTerms(terms) {
              @Override public TermsEnum iterator() throws java.io.IOException {
                return new FilterTermsEnum(super.iterator()) {
                  @Override public BytesRef next() throws java.io.IOException {
                    BytesRef term = super.next();
                    if (term != null) visited.incrementAndGet();
                    return term;
                  }
                };
              }
            };
          }
          @Override public IndexReader.CacheHelper getCoreCacheHelper() { return null; }
          @Override public IndexReader.CacheHelper getReaderCacheHelper() { return null; }
        };
      }
    }) {
      @Override protected DirectoryReader doWrapDirectoryReader(DirectoryReader in) { throw new UnsupportedOperationException(); }
      @Override public IndexReader.CacheHelper getReaderCacheHelper() { return null; }
    };
  }
}
