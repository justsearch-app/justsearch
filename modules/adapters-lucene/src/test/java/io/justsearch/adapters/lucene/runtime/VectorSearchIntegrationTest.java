package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VectorSearchIntegrationTest extends RuntimeTestBase {

  @Test
  void searchVectorReturnsNearestNeighbors() throws Exception {
    Path base = dataDir();

    // Create config with vector dimension = 4 (small for testing)
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: vectortest\n      roots: ['ignored']\n  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    // Index documents with vectors (4-dimensional for simplicity)
    float[] vec1 = new float[] {1.0f, 0.0f, 0.0f, 0.0f}; // Pointing in +X direction
    float[] vec2 = new float[] {0.0f, 1.0f, 0.0f, 0.0f}; // Pointing in +Y direction
    float[] vec3 = new float[] {0.9f, 0.1f, 0.0f, 0.0f}; // Close to vec1

    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.VECTOR, vec1)));
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-2",
                SchemaFields.DOC_UID, "doc-2#0",
                SchemaFields.VECTOR, vec2)));
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-3",
                SchemaFields.DOC_UID, "doc-3#0",
                SchemaFields.VECTOR, vec3)));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    // Search for vectors similar to vec1 (should return doc-1 and doc-3)
    float[] queryVector = new float[] {1.0f, 0.0f, 0.0f, 0.0f};
    SearchResult result = runtime.readPathOps().searchVector(queryVector, 3);

    assertNotNull(result);
    assertEquals(3, result.hits().size(), "Should return all 3 documents");

    // The first result should be doc-1 (exact match) or doc-3 (very close)
    String firstDocId = result.hits().get(0).docId();
    assertTrue(
        firstDocId.equals("doc-1") || firstDocId.equals("doc-3"),
        "First result should be doc-1 or doc-3, got: " + firstDocId);

    // doc-2 should be last (orthogonal to query)
    String lastDocId = result.hits().get(2).docId();
    assertEquals("doc-2", lastDocId, "Last result should be doc-2 (orthogonal)");

    runtime.close();
  }

  @Test
  void vectorEfSearchOverrideIncreasesInternalQueryKButPreservesApiLimit() throws Exception {
    Path base = dataDir();

    // Explicit ef_search should be treated as an override (not a default), and increase the
    // internal k for vector search breadth (while still returning only `limit` hits).
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: efsearchtest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n    ef_search: 32\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.VECTOR, new float[] {1.0f, 0.0f, 0.0f, 0.0f})));
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-2",
                SchemaFields.DOC_UID, "doc-2#0",
                SchemaFields.VECTOR, new float[] {0.0f, 1.0f, 0.0f, 0.0f})));
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-3",
                SchemaFields.DOC_UID, "doc-3#0",
                SchemaFields.VECTOR, new float[] {0.9f, 0.1f, 0.0f, 0.0f})));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    // resolveVectorQueryK is package-private on ReadPathOps — access via session ctx for testing
    ReadPathOps readOps = new ReadPathOps(runtime.session(), "doc_id");
    assertEquals(
        32,
        readOps.resolveVectorQueryK(10),
        "Expected ef_search to raise internal query k above limit");
    assertEquals(
        64,
        readOps.resolveVectorQueryK(64),
        "Expected ef_search not to reduce internal query k");

    SearchResult sr = runtime.readPathOps().searchVector(new float[] {1.0f, 0.0f, 0.0f, 0.0f}, 3);
    assertNotNull(sr);
    assertNotNull(sr.hits());
    assertEquals(3, sr.hits().size(), "Expected searchVector to still return exactly `limit` hits");

    runtime.close();
  }

  /**
   * Lane F PR 0b — {@code index.vector.exhaustive_search} makes the dense leg EXACT.
   *
   * <p>Two properties, both observable from outside the query: the leg's {@code totalHits} becomes
   * the number of vector-bearing documents (the query ran with {@code k >= reader.maxDoc()} and a
   * filter, the only shape Lucene 10.4 gives an exact path), and the returned ranking is the true
   * ranking — asserted against a brute-force scan the test computes itself under the SAME
   * similarity the index uses ({@code KnnFloatVectorField}'s 2-arg constructor is EUCLIDEAN, so
   * "nearest" is smallest squared L2 distance; see {@code HybridSearchOps}'s note).
   *
   * <p>The switched-OFF half is the discriminator: with the same corpus and the same call, the
   * approximate leg's {@code totalHits} is bounded by {@code resolveVectorQueryK(limit)}, so a
   * green here cannot mean "the corpus was small enough to be exact anyway".
   */
  @Test
  void exhaustiveSearchMakesTheDenseLegExactAndReportsEveryVectorBearingDoc() throws Exception {
    int docCount = 40;
    int limit = 5;
    float[] queryVector = new float[] {1.0f, 0.0f, 0.0f, 0.0f};

    // Switch ON.
    SearchResult on = runVectorProbe(true, docCount, limit, queryVector);
    assertEquals(
        docCount,
        on.totalHits(),
        "exhaustive mode must consider (and report) every vector-bearing document");
    assertEquals(limit, on.hits().size(), "the API limit still bounds the returned hits");
    assertEquals(
        bruteForceNearest(docCount, queryVector, limit),
        on.hits().stream().map(h -> h.docId()).toList(),
        "exhaustive mode must return the true nearest neighbours (EUCLIDEAN)");

    // Switch OFF — same corpus, same call.
    SearchResult off = runVectorProbe(false, docCount, limit, queryVector);
    assertTrue(
        off.totalHits() <= limit,
        "approximate mode's totalHits is bounded by the internal query k ("
            + limit
            + "), got "
            + off.totalHits()
            + " — without this the ON assertion above would pass for the wrong reason");
  }

  /** Deterministic 4-dim fixture vector for document {@code i}; all distances are distinct. */
  private static float[] probeVector(int i) {
    return new float[] {1.0f - (i / 100.0f), i / 200.0f, 0.0f, 0.0f};
  }

  /** The doc ids of the {@code limit} nearest fixture vectors under EUCLIDEAN similarity. */
  private static java.util.List<String> bruteForceNearest(
      int docCount, float[] queryVector, int limit) {
    record Scored(String docId, double distanceSquared) {}
    java.util.List<Scored> all = new java.util.ArrayList<>();
    for (int i = 0; i < docCount; i++) {
      float[] v = probeVector(i);
      double d2 = 0.0;
      for (int j = 0; j < v.length; j++) {
        double delta = v[j] - queryVector[j];
        d2 += delta * delta;
      }
      all.add(new Scored("doc-" + i, d2));
    }
    all.sort(java.util.Comparator.comparingDouble(Scored::distanceSquared));
    return all.stream().limit(limit).map(Scored::docId).toList();
  }

  /** Opens a fresh index with the switch in the given position, indexes the fixture, searches. */
  private SearchResult runVectorProbe(
      boolean exhaustive, int docCount, int limit, float[] queryVector) throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: exhaustivetest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n    exhaustive_search: "
            + exhaustive
            + "\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);
    try {
      for (int i = 0; i < docCount; i++) {
        runtime.indexingCoordinator().indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "doc-" + i,
                    SchemaFields.DOC_UID, "doc-" + i + "#0",
                    SchemaFields.VECTOR, probeVector(i))));
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      return runtime.readPathOps().searchVector(queryVector, limit);
    } finally {
      runtime.close();
    }
  }

  @Test
  void searchVectorWithNullVectorThrows() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: nulltest\n      roots: ['ignored']\n  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    // Should throw on null vector
    assertThrows(
        IllegalArgumentException.class, () -> runtime.readPathOps().searchVector(null, 10));

    // Should throw on empty vector
    assertThrows(
        IllegalArgumentException.class, () -> runtime.readPathOps().searchVector(new float[0], 10));

    runtime.close();
  }
}
