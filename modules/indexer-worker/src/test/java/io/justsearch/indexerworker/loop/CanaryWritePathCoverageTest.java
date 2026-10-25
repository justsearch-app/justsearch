/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.ipc.StatusRequest;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.store.AlreadyClosedException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** D10 A1 repair and the orchestrator's accepted A2 companion/recovery boundary. */
final class CanaryWritePathCoverageTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  @Test @Timeout(45)
  void closedSharedAnalyzerCannotBeProvenHealthyByDirectoryOnlyCanary() throws Exception {
    var h = new PipelineTestFixture(); h.temp = temp; h.init(); h.openStore(false);
    var schema = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0));
    var analyzer = new StandardAnalyzer();
    var cacheField = schema.analyzerRegistry().getClass().getDeclaredField("analyzersById");
    cacheField.setAccessible(true);
    @SuppressWarnings("unchecked")
    var cache = (Map<String, Analyzer>) cacheField.get(schema.analyzerRegistry());
    for (String id : schema.analyzerRegistry().analyzerIds()) cache.put(id, analyzer);
    var failures = new CopyOnWriteArrayList<RuntimeException>();
    try (var runtime = schema.atPath(temp.resolve("index"))
            .withExecutorRegistrations(testLuceneExecutors()).open();
        var owners = new TestEngineExecutors();
        var provider = new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool);
        var extractor = new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,
            "analyzer-warmup", 1), provider, Duration.ofSeconds(8), null)) {
      h.writer = mock(IndexingCoordinator.class, call -> {
        try {
          return call.getMethod().invoke(runtime.indexingCoordinator(), call.getArguments());
        } catch (InvocationTargetException wrapped) {
          if (call.getMethod().getName().equals("indexSingle")
              && wrapped.getCause() instanceof RuntimeException failure) failures.add(failure);
          throw wrapped.getCause();
        }
      });
      h.commitOps = runtime.commitOps();
      h.enqueue("warmup.txt"); h.loop = PipelineTestFixture.withExtractor(h, extractor);
      h.loop.start(); h.awaitPathState("warmup.txt", "DONE", 10); h.stopLoopForOtherAssertions();
      analyzer.close();
      // A fresh loop owner cannot reuse the warm-up thread's analyzer components.
      try (var restartedProvider = new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool);
          var restartedExtractor = new TimeboxedContentExtractor(PipelineTestFixture.registration(
              owners, "analyzer-restarted", 1), restartedProvider, Duration.ofSeconds(8), null)) {
        h.enqueue("healthy-a.txt", "healthy-b.txt");
        h.loop = PipelineTestFixture.withExtractor(h, restartedExtractor); h.loop.start(); h.awaitPolicy(12);
        var rows = h.rows().stream().filter(r -> !r.path().endsWith("warmup.txt")).toList();
        System.out.println("A1 closed shared analyzer, actual writer failures=" + failures + " rows=" + rows);
        assertTrue(failures.size() >= 2, "both healthy writes reach the real retired analyzer");
        assertTrue(failures.stream().allMatch(e -> e instanceof AlreadyClosedException));
        var sessionField = RunningRuntime.class.getDeclaredField("session");
        sessionField.setAccessible(true);
        Object session = sessionField.get(runtime);
        var snapshotField = session.getClass().getDeclaredField("snapshot");
        snapshotField.setAccessible(true);
        Object snapshot = snapshotField.get(session);
        var writerMethod = snapshot.getClass().getDeclaredMethod("writer");
        writerMethod.setAccessible(true);
        assertTrue(((org.apache.lucene.index.IndexWriter) writerMethod.invoke(snapshot)).isOpen(),
            "the analyzer outage leaves the actual writer open");
        h.assertFree(rows);
        long now = System.currentTimeMillis();
        assertTrue(rows.stream().allMatch(r -> r.retry() > now && r.retry() <= now + 8_000),
            "shared analyzer deadlines stay within seconds");
        var status = new WorkerIngestService(h.queue, h.loop, mock(WorkerSignalBus.class),
            IndexingPacing.unthrottled(), temp, temp.resolve("index"), null, null, null, 0L);
        assertEquals("WAITING", status.indexStatus(StatusRequest.getDefaultInstance(),
            CallContext.none()).getCore().getState());
        h.stopLoopForOtherAssertions();
      }
    } finally { h.close(); analyzer.close(); }
  }

  @Test @Timeout(40)
  void companionIsDeferredDuringOutageAndChargedInFirstRecoveredBatch() throws Exception {
    var h = new PipelineTestFixture(); h.temp = temp; h.init(); h.openStore(false);
    var current = new AtomicReference<RunningRuntime>();
    var actualIo = new CopyOnWriteArrayList<IOException>();
    try (var unavailable = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0))
            .atPath(temp.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
        var owners = new TestEngineExecutors();
        var parser = new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool)) {
      var source = h.enqueue("a-companion-source.txt", "b-healthy.txt").getFirst();
      ContentExtractorProvider provider = new ContentExtractorProvider() {
        @Override public ContentExtractor.ExtractionResult extract(Path path)
            throws IOException, ContentExtractor.ExtractionException {
          if (path.equals(source)) {
            try { Files.readString(path.resolveSibling("missing-for-this-document.txt")); }
            catch (IOException failure) { actualIo.add(failure); throw failure; }
          }
          return parser.extractArtifact(path).result();
        }
        @Override public String detectMimeType(Path path) { return parser.detectMimeType(path); }
      };
      assertTrue(parser.extractArtifact(source).result().content().contains("healthy"));
      current.set(unavailable); unavailable.close();
      h.writer = mock(IndexingCoordinator.class, call -> {
        try { return call.getMethod().invoke(current.get().indexingCoordinator(), call.getArguments()); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
      });
      h.commitOps = mock(CommitOps.class, call -> {
        try { return call.getMethod().invoke(current.get().commitOps(), call.getArguments()); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
      });
      try (var extractor = new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,
          "companion-recovery", 1), provider, Duration.ofSeconds(8), null)) {
        h.loop = PipelineTestFixture.withExtractor(h, extractor); h.loop.start(); h.awaitPolicy(12);
        var deferred = h.rows(); h.assertFree(deferred);
        assertTrue(Files.isReadable(source));
        assertEquals(1, actualIo.size(), "one companion failure in the first outage batch");
        try (var recovered = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0))
            .atPath(temp.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open()) {
          current.set(recovered);
          h.awaitPathState("b-healthy.txt", "DONE", 10);
          var rows = h.rows();
          var companion = rows.stream().filter(r -> r.path().endsWith("a-companion-source.txt"))
              .findFirst().orElseThrow();
          System.out.println("A2 deferred=" + deferred + " first recovered batch=" + rows
              + " companion failures=" + actualIo.size());
          assertEquals(1, companion.attempts(), "first recovered batch charges the companion");
          assertEquals("RETRY_WITH_BACKOFF", companion.policy());
          assertTrue(companion.retry() >= System.currentTimeMillis() + 55_000);
          assertEquals(2, actualIo.size(), "exactly one outage batch and one recovered batch");
          assertEquals(0, rows.stream().filter(r -> r.path().endsWith("b-healthy.txt"))
              .findFirst().orElseThrow().attempts());
          h.stopLoopForOtherAssertions();
        }
      }
    } finally { h.close(); }
  }
}
