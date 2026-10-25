/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** K1/K4/K5: current full-pipeline proof, independent of prior healthy or failed batches. */
final class PipelineHealthProofTest {
  @TempDir Path temp;

  @ParameterizedTest(name = "interleavedTerminalPoison({0})")
  @ValueSource(booleans = {false, true})
  @Timeout(35)
  void interleavedParserRejectionsTerminateImmediatelyInEitherOrder(boolean healthyFirst) throws Exception {
    var h = new PipelineTestFixture(); h.temp = temp; h.init(); h.openStore(false);
    var probes = new AtomicInteger();
    try (var owners = new TestEngineExecutors();
         var parser = new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool)) {
      ExtractionSandbox sandbox = source -> {
        if (source.getFileName().toString().startsWith("justsearch-extraction-canary-")) probes.incrementAndGet();
        if (source.getFileName().toString().startsWith("poison-")) {
          throw new ContentExtractor.ExtractionException("this document is rejected");
        }
        return parser.extractArtifact(source);
      };
      try (var extractor = new TimeboxedContentExtractor(PipelineTestFixture.registration(owners, "proof", 1),
          sandbox, Duration.ofSeconds(5), null)) {
        h.loop = PipelineTestFixture.withExtractor(h, extractor); h.loop.start();
        long initialCount = h.store.identityCount();
        for (int i = 0; i < 4; i++) {
          if (healthyFirst) { h.enqueue("healthy-first-" + i + ".txt"); h.awaitPathState("healthy-first-" + i + ".txt", "DONE", 5); }
          h.enqueue("poison-" + i + ".txt");
          h.awaitPolicy(8);
          int poisonIndex = i;
          assertEquals("FAILED", h.rows().stream().filter(r -> r.path().endsWith("poison-" + poisonIndex + ".txt")).findFirst().orElseThrow().state(), "first durable poison outcome is charged immediately");
          if (!healthyFirst) { h.enqueue("healthy-last-" + i + ".txt"); h.awaitPathState("healthy-last-" + i + ".txt", "DONE", 5); }
          assertEquals(i + 1, probes.get(), "one immediate canary for each poison-only batch");
        }
        h.stopLoopForOtherAssertions();
        assertTrue(h.rows().stream().filter(r -> r.path().contains("poison-")).allMatch(r -> r.attempts() == 1));
        assertEquals(initialCount + 8, h.store.identityCount(), "rollback-only probes leave no synthetic identity");
        assertTrue(h.store.lookup(DocumentIdentityStore.pathHash("justsearch-ingestion-canary")).isEmpty());
      }
    } finally { h.close(); }
  }

  @Test @Timeout(25)
  void invalidSharedParserOutputCannotProvePipelineHealth() throws Exception {
    var h = new PipelineTestFixture(); h.temp = temp; h.init(); h.openStore(false);
    var broken = new java.util.concurrent.atomic.AtomicBoolean(true);
    try (var owners = new TestEngineExecutors();
         var parser = new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool)) {
      ExtractionSandbox sandbox = source -> {
        var actual = parser.extractArtifact(source);
        return broken.get() ? new ExtractionArtifact(actual.status(), actual.result(),
            "untyped-stale-shared-policy", actual.parserId(), actual.truncated(), actual.warnings()) : actual;
      };
      try (var extractor = new TimeboxedContentExtractor(PipelineTestFixture.registration(owners, "invalid-policy", 1),
          sandbox, Duration.ofSeconds(5), null)) {
        h.enqueue("healthy-policy-a.txt", "healthy-policy-b.txt");
        h.loop = PipelineTestFixture.withExtractor(h, extractor); h.loop.start(); h.awaitPolicy(8);
        var during = h.rows();
        h.assertFree(during);
        assertTrue(during.stream().allMatch(r -> r.retry() <= System.currentTimeMillis() + 8_000));
        var service = new io.justsearch.indexerworker.services.WorkerIngestService(h.queue, h.loop,
            mock(WorkerSignalBus.class), IndexingPacing.unthrottled(), temp, temp.resolve("index"), null, null, null, 0L);
        assertEquals("WAITING", service.indexStatus(io.justsearch.ipc.StatusRequest.getDefaultInstance(),
            io.justsearch.indexerworker.services.CallContext.none()).getCore().getState());
        broken.set(false); h.awaitAllState("DONE", 12); h.stopLoopForOtherAssertions();
        assertTrue(h.rows().stream().allMatch(r -> r.attempts() == 0));
      }
    } finally { h.close(); }
  }

  @Test @Timeout(35)
  void unavailableProjectionWriterCannotHideBehindHealthyDestination() throws Exception {
    var h = new PipelineTestFixture(); h.temp = temp; h.init(); h.openStore(false);
    var fault = new java.util.concurrent.atomic.AtomicBoolean(true);
    var writes = new AtomicInteger(); var probes = new AtomicInteger();
    var projectionWriter = mock(IndexingCoordinator.class, call -> {
      String method = call.getMethod().getName();
      if (method.equals("indexSingle")) writes.incrementAndGet();
      if (method.equals("probeIngestion")) probes.incrementAndGet();
      if (fault.get() && (method.equals("indexSingle") || method.equals("probeIngestion"))) {
        throw new IllegalStateException("untyped shared lexical projection unavailable");
      }
      return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
    });
    var source = mock(RunningRuntime.class);
    when(source.indexingCoordinator()).thenReturn(projectionWriter);
    when(source.documentFieldOps()).thenReturn(mock(DocumentFieldOps.class));
    when(source.commitOps()).thenReturn(h.commitOps);
    try {
      h.enqueue("healthy-projection-a.txt", "healthy-projection-b.txt");
      h.loop = h.newLoop(); h.loop.wireActiveLexicalSource(source); h.loop.start();
      long end = System.nanoTime() + 20_000_000_000L;
      while (System.nanoTime() < end && (writes.get() < 8
          || h.rows().stream().anyMatch(r -> r.state().equals("PROCESSING")))) Thread.sleep(20);
      assertTrue(writes.get() >= 8, "drive four whole failed batches through the actual projection path");
      var during = h.rows();
      verify(h.writer, never()).indexSingle(any());
      if (during.stream().allMatch(r -> r.attempts() == 0)) {
        long now = System.currentTimeMillis();
        assertTrue(during.stream().allMatch(r -> r.retry() > now && r.retry() <= now + 8_000));
        var status = new io.justsearch.indexerworker.services.WorkerIngestService(h.queue, h.loop,
            mock(WorkerSignalBus.class), IndexingPacing.unthrottled(), temp, temp.resolve("index"), null, null, null, 0L)
            .indexStatus(io.justsearch.ipc.StatusRequest.getDefaultInstance(), io.justsearch.indexerworker.services.CallContext.none());
        assertEquals("WAITING", status.getCore().getState());
        assertEquals(during.getFirst().retry(), status.getFailure().getNextRetryAtMs());
      }
      fault.set(false);
      if (during.stream().allMatch(r -> r.attempts() == 0)) h.awaitAllState("DONE", 12);
      h.stopLoopForOtherAssertions();
      h.assertFree(during);
      assertEquals(4, probes.get(), "one shared writer-health probe per pause, including the projection");
      assertTrue(h.rows().stream().allMatch(r -> r.state().equals("DONE") && r.attempts() == 0));
    } finally { h.close(); }
  }

  @Test @Timeout(20)
  void documentValueLimitIsChargedWhenNormalCanaryRoundTripPasses() throws Exception {
    var h = new PipelineTestFixture(); h.temp = temp; h.init(); h.openStore(false);
    try (var paths = new SqlitePathResolutionStore(h.db);
         var owners = new TestEngineExecutors();
         var parser = new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool);
         var extractor = new TimeboxedContentExtractor(PipelineTestFixture.registration(owners, "value-limit", 1),
             parser, Duration.ofSeconds(5), null)) {
      var field = SqlitePathResolutionStore.class.getDeclaredField("connection"); field.setAccessible(true);
      var connection = (org.sqlite.SQLiteConnection) field.get(paths);
      // Read shared schema first: its cold decoding also exceeds the row-value limit.
      paths.lookup(DocumentIdentityStore.pathHash("schema-warmup"));
      connection.getDatabase().limit(0, 260);
      var source = h.enqueue("document-" + "x".repeat(180) + ".txt").getFirst();
      String hash = DocumentIdentityStore.pathHash(source.toAbsolutePath().toString());
      var failure = assertThrows(IllegalStateException.class, () -> paths.record(hash, source.toString(), System.currentTimeMillis()));
      assertEquals(18, ((java.sql.SQLException) failure.getCause()).getErrorCode() & 255);
      h.loop = new IndexingLoop(null, null, h.queue, h.writer, h.commitOps,
          mock(DocumentFieldOps.class), mock(IndexCountOps.class), () -> null, mock(WorkerSignalBus.class),
          IndexingPacing.unthrottled(), null, null, null, null, extractor, null,
          new IndexingLoopOptions(false, paths, h.store, null, null, null));
      h.loop.start(); h.awaitPolicy(8); h.stopLoopForOtherAssertions();
      var row = h.rows().getFirst();
      assertEquals(1, row.attempts(), "only document values exceed this real SQLite limit");
      assertEquals("RETRY_WITH_BACKOFF", row.policy());
      assertTrue(row.retry() > System.currentTimeMillis() + 55_000);
      assertTrue(paths.lookup(DocumentIdentityStore.pathHash("justsearch-ingestion-canary")).isEmpty(), "path probe rolls back");
      assertEquals(0, h.store.identityCount(), "identity probe rolls back even when another store rejected the document");
    } finally { h.close(); }
  }
}
