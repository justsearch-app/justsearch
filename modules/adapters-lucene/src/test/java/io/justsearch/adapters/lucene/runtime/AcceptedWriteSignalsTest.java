/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.TelemetryEvents;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.SoftDeletesDirectoryReaderWrapper;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Accepted physical submissions must reach the existing timer even if their caller later fails. */
class AcceptedWriteSignalsTest extends LuceneExecutorTestBase {
  @TempDir Path tempDir;

  @ParameterizedTest
  @ValueSource(strings = {"hard", "update", "soft"})
  void acceptedBatchPrefixSurvivesActualLaterLuceneRejection(String route) throws Exception {
    try (var runtime = openRuntime(tempDir)) {
      var coordinator = runtime.indexingCoordinator();
      coordinator.indexSingle(doc(route.equals("soft") ? "accepted" : "doomed"));
      runtime.commitOps().commitAndTrack();
      var accepted = route.equals("hard") ? changed("doomed", SchemaFields.HARD_DELETE, true)
          : changed("accepted", SchemaFields.SOFT_DELETE, route.equals("soft"));
      var rejected = changed("rejected", SchemaFields.COLLECTION, "x".repeat(40000));
      if (route.equals("soft")) rejected = with(rejected, SchemaFields.SOFT_DELETE, true);
      List<IndexDocument> batch = List.of(accepted, rejected);
      // All documents map successfully. The observed rejection must come from the live writer.
      for (var document : batch) {
        assertDoesNotThrow(() -> runtime.session().fieldMapper.toDocument(document.fields(), null));
      }
      var failure = assertThrows(IllegalArgumentException.class, () -> coordinator.indexBatch(batch));
      assertTrue(failure.getMessage().contains("immense term"), failure.toString());
      assertTrue(Arrays.stream(failure.getStackTrace())
          .anyMatch(frame -> frame.getClassName().startsWith("org.apache.lucene.index.")));
      assertNull(runtime.session().snapshot.writer().getTragicException());
      assertEquals(0L, runtime.session().queueDepth.get());
      String affected = route.equals("hard") ? "doomed" : "accepted";
      int before = route.equals("update") ? 0 : 1;
      int after = 1 - before;
      assertEquals(after, count(runtime, affected, false), "accepted prefix exists in the live writer");
      assertEquals(0, count(runtime, "rejected", false), "rejected document was not accepted");
      assertEquals(before, count(runtime, affected, true), "prefix is outside the previous commit");
      assertEquals(1L, runtime.session().pendingDocs.get(), "accepted prefix must remain signaled");
      commitByTimer(runtime);
      assertEquals(after, count(runtime, affected, true));
      assertEquals(0, count(runtime, "rejected", true));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"hard", "update", "soft"})
  void acceptedSingleSurvivesThrowingFollowingObserver(String route) throws Exception {
    try (var runtime = openRuntime(tempDir)) {
      runtime.indexingCoordinator().indexSingle(doc("one"));
      runtime.commitOps().commitAndTrack();
      var observerFailure = new IllegalStateException("observer failed after accepted submission");
      var calls = new AtomicInteger();
      runtime.session().telemetryEvents = new TelemetryEvents() {
        @Override public void onHardDelete() { calls.incrementAndGet(); throw observerFailure; }
        @Override public void onVectorFieldDropped(int count) {
          assertEquals(1, count);
          calls.incrementAndGet();
          throw observerFailure;
        }
      };
      var unusableVector = new float[768];
      unusableVector[0] = Float.NaN;
      var document = route.equals("hard") ? changed("one", SchemaFields.HARD_DELETE, true)
          : with(changed("one", SchemaFields.VECTOR, unusableVector), SchemaFields.TITLE, "after observer");
      if (route.equals("soft")) document = with(document, SchemaFields.SOFT_DELETE, true);
      var submitted = document;
      assertSame(observerFailure, assertThrows(IllegalStateException.class,
          () -> runtime.indexingCoordinator().indexSingle(submitted)));
      assertEquals(1, calls.get());
      assertEquals(0L, runtime.session().queueDepth.get());
      assertEquals(0L, runtime.indexingCoordinator().bulkDeleteEpoch());
      int expected = route.equals("update") ? 1 : 0;
      assertEquals(expected, count(runtime, "one", false));
      assertEquals(1, count(runtime, "one", true));
      if (route.equals("update")) {
        assertEquals("after observer", title(runtime, "one", false));
        assertEquals("before", title(runtime, "one", true));
      }
      assertEquals(1L, runtime.session().pendingDocs.get());
      runtime.session().telemetryEvents = null;
      commitByTimer(runtime);
      assertEquals(expected, count(runtime, "one", true));
      if (route.equals("update")) assertEquals("after observer", title(runtime, "one", true));
    }
  }

  @Test
  void groupedDeleteAndRepeatedRewritesCountPhysicalSubmissions() throws Exception {
    try (var runtime = openRuntime(tempDir)) {
      var coordinator = runtime.indexingCoordinator();
      coordinator.indexBatch(List.of(doc("deleted-one"), doc("deleted-two"), doc("soft")));
      runtime.commitOps().commitAndTrack();
      var hardDeletes = new AtomicInteger();
      var softDeletes = new AtomicInteger();
      runtime.session().telemetryEvents = new TelemetryEvents() {
        @Override public void onHardDelete(int count) { hardDeletes.addAndGet(count); }
        @Override public void onSoftDelete(int count) { softDeletes.addAndGet(count); }
      };
      coordinator.indexBatch(List.of(
          changed("deleted-one", SchemaFields.HARD_DELETE, true),
          changed("deleted-two", SchemaFields.HARD_DELETE, true),
          changed("deleted-one", SchemaFields.HARD_DELETE, true),
          changed("repeated", SchemaFields.TITLE, "first"),
          changed("repeated", SchemaFields.TITLE, "last"),
          changed("soft", SchemaFields.SOFT_DELETE, true)));
      assertEquals(4L, runtime.session().pendingDocs.get(), "one delete group plus three rewrites");
      assertEquals(3, hardDeletes.get(), "telemetry retains logical input counts");
      assertEquals(1, softDeletes.get());
      assertEquals(0L, coordinator.bulkDeleteEpoch());
      commitByTimer(runtime);
      assertEquals(0, count(runtime, "deleted-one", true));
      assertEquals(0, count(runtime, "deleted-two", true));
      assertEquals("last", title(runtime, "repeated", true));
      assertEquals(0, count(runtime, "soft", true));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"hard", "update", "soft"})
  void successfulSingleRoutesCountOnceAndPreserveEmbeddingResult(String route) throws Exception {
    try (var runtime = openRuntime(tempDir)) {
      if (route.equals("soft")) {
        runtime.indexingCoordinator().indexSingle(doc("one"));
        runtime.commitOps().commitAndTrack();
      }
      var vector = new float[768];
      vector[0] = 1;
      var document = with(changed("one", SchemaFields.VECTOR, vector),
          SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
      if (route.equals("hard")) document = with(document, SchemaFields.HARD_DELETE, true);
      if (route.equals("soft")) document = with(document, SchemaFields.SOFT_DELETE, true);
      assertEquals(!route.equals("hard"), runtime.indexingCoordinator().indexSingle(document));
      assertEquals(1L, runtime.session().pendingDocs.get());
      commitByTimer(runtime);
      assertEquals(route.equals("update") ? 1 : 0, count(runtime, "one", true));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"prefix", "prefix-survivor", "prefix-exclusion", "prefix-both",
      "collection", "collection-survivor", "all", "zero-prefix", "zero-collection", "zero-all"})
  void broadDeletesPublishByTimerAndPreserveTheirSelection(String route) throws Exception {
    try (var runtime = openRuntime(tempDir)) {
      var coordinator = runtime.indexingCoordinator();
      boolean empty = route.equals("zero-all");
      if (!empty) coordinator.indexBatch(List.of(doc("/scope/remove"), doc("/scope/keep"),
          doc("/scope/excluded/nested"), with(doc("/outside/other"), SchemaFields.COLLECTION, "other"),
          chunk("chunk:keep", "/scope/keep"), chunk("chunk:remove", "/scope/remove")));
      runtime.commitOps().commitAndTrack();
      int matched = -1;
      switch (route) {
        case "prefix" -> coordinator.deleteByPathPrefix("/scope");
        case "prefix-survivor" -> coordinator.deleteByPathPrefixExcludingAcceptedSurvivors(
            "/scope", List.of("/scope/keep"));
        case "prefix-exclusion" -> coordinator.deleteByPathPrefixExcludingAcceptedSurvivors(
            "/scope", List.of(), List.of("/scope/excluded"));
        case "prefix-both" -> coordinator.deleteByPathPrefixExcludingAcceptedSurvivors(
            "/scope", List.of("/scope/keep"), List.of("/scope/excluded"));
        case "collection" -> matched = coordinator.deleteByCollection("target");
        case "collection-survivor" -> matched = coordinator.deleteByCollectionExcludingAcceptedSurvivors(
            "target", List.of("/scope/keep"));
        case "all", "zero-all" -> coordinator.executeNow(IndexWriteOperation.DeleteAll.of()).completion().join();
        case "zero-prefix" -> coordinator.deleteByPathPrefix("/absent");
        case "zero-collection" -> matched = coordinator.deleteByCollection("absent");
        default -> throw new IllegalArgumentException(route);
      }
      if (route.equals("collection")) assertEquals(5, matched);
      if (route.equals("collection-survivor")) assertEquals(3, matched);
      if (route.equals("zero-collection")) assertEquals(0, matched);
      assertEquals(1L, coordinator.bulkDeleteEpoch());
      boolean zeroMatch = route.startsWith("zero-");
      boolean survivor = route.contains("survivor") || route.equals("prefix-both");
      boolean exclusion = route.equals("prefix-exclusion") || route.equals("prefix-both");
      assertEquals(zeroMatch && !empty ? 1 : 0, count(runtime, "/scope/remove", false));
      assertEquals(1L, runtime.session().pendingDocs.get(), "even zero-match submissions are signals");
      commitByTimer(runtime);
      assertEquals(zeroMatch && !empty ? 1 : 0, count(runtime, "/scope/remove", true));
      assertEquals(zeroMatch && !empty || survivor ? 1 : 0, count(runtime, "/scope/keep", true));
      assertEquals(zeroMatch && !empty || survivor ? 1 : 0, count(runtime, "chunk:keep", true));
      assertEquals(zeroMatch && !empty ? 1 : 0, count(runtime, "chunk:remove", true));
      assertEquals(zeroMatch && !empty || exclusion ? 1 : 0, count(runtime, "/scope/excluded/nested", true));
      assertEquals(route.equals("all") || empty ? 0 : 1, count(runtime, "/outside/other", true));
    }
  }

  @Test
  void parentAndChunkDeleteCountsTwoAndKeepsOtherFamilies() throws Exception {
    try (var runtime = openRuntime(tempDir)) {
      runtime.indexingCoordinator().indexBatch(List.of(doc("parent"), chunk("chunk:one", "parent"),
          doc("other"), chunk("chunk:other", "other")));
      runtime.commitOps().commitAndTrack();
      runtime.indexingCoordinator().deleteByIdAndChunks("parent");
      assertEquals(2L, runtime.session().pendingDocs.get());
      assertEquals(0L, runtime.indexingCoordinator().bulkDeleteEpoch());
      commitByTimer(runtime);
      assertEquals(0, count(runtime, "parent", true));
      assertEquals(0, count(runtime, "chunk:one", true));
      assertEquals(1, count(runtime, "other", true));
      assertEquals(1, count(runtime, "chunk:other", true));
    }
  }

  @Test
  void configuredSingleHardDeleteAndCanonicalBatchHardDeleteKeepTheirRouting() throws Exception {
    try (var runtime = openRuntime(tempDir)) {
      var coordinator = runtime.indexingCoordinator();
      coordinator.indexBatch(List.of(doc("single"), doc("batch")));
      runtime.commitOps().commitAndTrack();
      runtime.session().hardDeleteField = "custom_delete";
      assertFalse(coordinator.indexSingle(changed("single", "custom_delete", true)));
      coordinator.indexBatch(List.of(changed("batch", SchemaFields.HARD_DELETE, true),
          changed("batch-kept", "custom_delete", true)));
      assertEquals(3L, runtime.session().pendingDocs.get());
      commitByTimer(runtime);
      assertEquals(0, count(runtime, "single", true));
      assertEquals(0, count(runtime, "batch", true));
      assertEquals(1, count(runtime, "batch-kept", true));
    }
  }

  @Test
  void existingDirectDeleteAndChunkDeleteStillSignalOnce() throws Exception {
    try (var runtime = openRuntime(tempDir)) {
      var coordinator = runtime.indexingCoordinator();
      coordinator.indexBatch(List.of(doc("parent"), chunk("chunk:one", "parent"), doc("other")));
      runtime.commitOps().commitAndTrack();
      coordinator.deleteChunksForParentDocId("parent");
      assertEquals(1L, runtime.session().pendingDocs.get());
      coordinator.deleteById("other");
      assertEquals(2L, runtime.session().pendingDocs.get());
      assertEquals(0L, coordinator.bulkDeleteEpoch());
      commitByTimer(runtime);
      assertEquals(1, count(runtime, "parent", true));
      assertEquals(0, count(runtime, "chunk:one", true));
      assertEquals(0, count(runtime, "other", true));
    }
  }

  @Test
  void rejectedWorkDoesNotSignalOrMutate() throws Exception {
    try (var runtime = openRuntime(tempDir)) {
      var coordinator = runtime.indexingCoordinator();
      coordinator.indexSingle(doc("existing"));
      runtime.commitOps().commitAndTrack();
      coordinator.indexBatch(List.of());
      assertThrows(IndexRuntimeIOException.class, () -> coordinator.indexBatch(List.of(
          doc("never"), new IndexDocument(Map.of(SchemaFields.DOC_UID, "missing-id")))));
      var mappingFailure = changed("bad-map", SchemaFields.CHUNK_VECTOR, new float[] {1});
      assertDoesNotThrow(() -> coordinator.validate(mappingFailure));
      var failure = assertThrows(IllegalArgumentException.class,
          () -> coordinator.indexBatch(List.of(doc("never"), mappingFailure)));
      assertTrue(failure.getMessage().contains("vector dimension mismatch for chunk_vector"));
      assertFalse(coordinator.updateDocument("missing", Map.of(SchemaFields.TITLE, "ignored")));
      assertFalse(coordinator.updateDocument("existing", Map.of()));
      assertFalse(coordinator.updateDocumentIf("existing", Map.of(SchemaFields.TITLE, "stale"),
          Map.of(SchemaFields.TITLE, "ignored")));
      long previousLimit = runtime.session().maxQueueDepth;
      runtime.session().maxQueueDepth = 0;
      try { assertThrows(IndexRuntimeIOException.class, () -> coordinator.indexSingle(doc("blocked"))); }
      finally { runtime.session().maxQueueDepth = previousLimit; }
      runtime.session().draining = true;
      try { assertThrows(IndexRuntimeIOException.class, () -> coordinator.indexSingle(doc("draining"))); }
      finally { runtime.session().draining = false; }
      assertEquals(0L, runtime.session().queueDepth.get());
      assertEquals(0L, runtime.session().pendingDocs.get());
      assertEquals(0L, coordinator.bulkDeleteEpoch());
      assertEquals(0, count(runtime, "never", false));
      assertEquals(0, count(runtime, "bad-map", false));
      assertEquals(0, count(runtime, "blocked", false));
      assertEquals(0, count(runtime, "draining", false));
      assertEquals("before", title(runtime, "existing", true));
    }
  }

  @Test
  void acceptedParentPrefixSurvivesLaterQueryConstructionFailureInIsolatedJvm() throws Exception {
    var classpath = new LinkedHashSet<String>();
    for (ClassLoader loader = getClass().getClassLoader(); loader != null; loader = loader.getParent()) {
      if (loader instanceof URLClassLoader urls) {
        for (var url : urls.getURLs()) classpath.add(Path.of(url.toURI()).toString());
      }
    }
    classpath.addAll(List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator)));
    Path output = tempDir.resolve("parent-prefix-child.log");
    var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "--enable-native-access=ALL-UNNAMED", "-cp", String.join(java.io.File.pathSeparator, classpath),
        getClass().getName(), tempDir.resolve("child-index").toString())
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertTrue(child.waitFor(30, TimeUnit.SECONDS), "isolated parent-prefix fixture timed out");
      assertEquals(0, child.exitValue(), () -> {
        try { return Files.readString(output); }
        catch (IOException failure) { return failure.toString(); }
      });
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        assertTrue(child.waitFor(5, TimeUnit.SECONDS), "child must terminate before returning");
      }
    }
  }

  /** The Lucene process-wide clause limit is changed only in this short-lived child JVM. */
  public static void main(String[] args) throws Exception {
    var fixture = new AcceptedWriteSignalsTest();
    try (var runtime = fixture.openRuntime(Path.of(args[0]))) {
      runtime.indexingCoordinator().indexBatch(List.of(doc("parent"), chunk("chunk:one", "parent")));
      runtime.commitOps().commitAndTrack();
      int previous = IndexSearcher.getMaxClauseCount();
      try {
        IndexSearcher.setMaxClauseCount(1);
        assertThrows(IndexSearcher.TooManyClauses.class,
            () -> runtime.indexingCoordinator().deleteByIdAndChunks("parent"));
      } finally { IndexSearcher.setMaxClauseCount(previous); }
      assertEquals(0, count(runtime, "parent", false), "first real delete was accepted");
      assertEquals(1, count(runtime, "chunk:one", false), "second delete was never submitted");
      assertEquals(1, count(runtime, "parent", true), "accepted delete is not yet committed");
      assertEquals(1L, runtime.session().pendingDocs.get(), "parent prefix must remain signaled");
      commitByTimer(runtime);
      assertEquals(0, count(runtime, "parent", true));
      assertEquals(1, count(runtime, "chunk:one", true));
    } finally { fixture.closeTestExecutors(); }
  }

  private RunningRuntime openRuntime(Path path) {
    var config = new ResolvedConfigBuilder()
        .put("index.commit.timer_interval_ms", 500, "jvm_arg", "test", "1")
        .put("index.commit.meta.enabled", 500, "jvm_arg", "test", "false").build();
    var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(768),
        () -> Map.<String, Object>of(), metadata -> {}).atPath(path)
        .withConfig(config).withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();
    return runtime;
  }

  private static IndexDocument doc(String id) {
    return new IndexDocument(Map.of(SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, id + "#0",
        SchemaFields.PATH, id, SchemaFields.TITLE, "before", SchemaFields.CONTENT, "body",
        SchemaFields.COLLECTION, "target"));
  }

  private static IndexDocument changed(String id, String field, Object value) {
    return with(doc(id), field, value);
  }

  private static IndexDocument with(IndexDocument document, String field, Object value) {
    var fields = new HashMap<>(document.fields());
    fields.put(field, value);
    return new IndexDocument(fields);
  }

  private static IndexDocument chunk(String id, String parent) {
    return with(with(changed(id, SchemaFields.PATH, parent), SchemaFields.PARENT_DOC_ID, parent),
        SchemaFields.IS_CHUNK, true);
  }

  private static int count(RunningRuntime runtime, String id, boolean committed) throws IOException {
    try (var reader = new SoftDeletesDirectoryReaderWrapper(
        committed ? DirectoryReader.open(runtime.session().snapshot.directory())
            : DirectoryReader.open(runtime.session().snapshot.writer()), runtime.session().softDeleteField)) {
      return new IndexSearcher(reader).count(new TermQuery(new Term(SchemaFields.DOC_ID, id)));
    }
  }

  private static String title(RunningRuntime runtime, String id, boolean committed) throws IOException {
    try (var reader = committed ? DirectoryReader.open(runtime.session().snapshot.directory())
        : DirectoryReader.open(runtime.session().snapshot.writer())) {
      var hits = new IndexSearcher(reader).search(new TermQuery(new Term(SchemaFields.DOC_ID, id)), 1);
      assertEquals(1, hits.scoreDocs.length);
      return reader.storedFields().document(hits.scoreDocs[0].doc).get(SchemaFields.TITLE);
    }
  }

  private static void commitByTimer(RunningRuntime runtime) throws InterruptedException {
    var committed = new CountDownLatch(1);
    runtime.commitOps().setCommitCompletedListener(reason -> {
      if (reason == CommitReason.TIMER) committed.countDown();
    });
    try {
      runtime.commitOps().startCommitTimer();
      assertTrue(committed.await(5, TimeUnit.SECONDS), "actual timer must publish without another write");
    } finally { runtime.commitOps().stopCommitTimer(); }
    assertEquals(0L, runtime.session().pendingDocs.get());
  }
}
