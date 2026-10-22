/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Additional independent counterexample: real Lucene filesystem availability failure. */
final class VerifierReadOnlyWriterTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
  @Test void realUnwritableIndexDoesNotChargeHealthySourceFiles() throws Exception {
    Path index=temp.resolve("index");
    var config=new ResolvedConfigBuilder();
    config.putDefault("index.writer.max_buffered_docs","2");
    config.putDefault("index.nrt.mode","on_demand");
    config.putDefault("index.nrt.background_reopen_ms","600000");
    var runtime=IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(index)
        .withConfig(config.build()).withExecutorRegistrations(testLuceneExecutors()).open();
    var scenario=new Scenario();
    scenario.temp=temp;
    scenario.init();
    var permissions=Files.getPosixFilePermissions(index);
    var failures=new CopyOnWriteArrayList<RuntimeException>();
    try {
      // A real multi-chunk source forces a segment flush while writing that one file.
      scenario.extractedText="A healthy readable sentence about filesystem recovery and indexing. ".repeat(600);
      scenario.commitOps=runtime.commitOps();
      Files.setPosixFilePermissions(index,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_EXECUTE));
      assertFalse(Files.isWritable(index),"real index directory must be unwritable to this process");
      scenario.writer=mock(IndexingCoordinator.class, org.mockito.AdditionalAnswers.delegatesTo(runtime.indexingCoordinator()));
      doAnswer(call -> {
        try { runtime.indexingCoordinator().indexSingle(call.getArgument(0)); }
        catch (RuntimeException failure) { failures.add(failure); throw failure; }
        return null;
      }).when(scenario.writer).indexSingle(any());
      scenario.openStore(false);
      var source=scenario.enqueue("healthy-source.txt").getFirst();
      Files.writeString(source,scenario.extractedText);
      scenario.queue.enqueueEntries(List.of(io.justsearch.indexerworker.queue.JobQueue.EnqueueEntry.stat(source).withinRoot(temp)));
      scenario.loop=scenario.newLoop(); scenario.loop.start();
      long deadline=System.nanoTime()+18_000_000_000L;
      while(System.nanoTime()<deadline) {
        var rows=scenario.rows();
        if(rows.stream().anyMatch(r -> r.attempts()>0)) break;
        Thread.sleep(20);
      }
      scenario.stopLoopForOtherAssertions();
      for(var failure:failures) {
        System.out.println("real unwritable writer: "+failure);
        if(failure.getCause()!=null) System.out.println("writer cause: "+failure.getCause());
      }
      System.out.println("unwritable writer durable rows="+scenario.rows());
      assertFalse(failures.isEmpty(),"must actually exercise the failing writer");
      assertTrue(failures.stream().anyMatch(e -> e.getCause() instanceof java.nio.file.AccessDeniedException),
          "failure must originate from actual filesystem access refusal: "+failures);
      assertTrue(scenario.rows().stream().allMatch(r -> r.attempts()==0
          && r.state().equals("PENDING") && "DEFER_WITHOUT_ATTEMPT".equals(r.policy())),
          "shared writer filesystem outage must never be charged to the healthy source: "+scenario.rows());
    } finally {
      Files.setPosixFilePermissions(index,permissions);
      try { scenario.close(); } finally { runtime.close(); }
    }
  }

  @Test
  void provenSharedOutageRemainsFreeAfterSuccessfulPeer() {
    var service = new BatchServiceFailures();
    var deferred = new ArrayList<Boolean>();
    service.begin();
    service.success();
    service.failure(new IndexRuntimeIOException(IndexRuntimeIOException.Reason.DISK_FULL,
        "shared index storage", null), deferred::add);
    service.finish();
    assertEquals(List.of(true), deferred,
        "a successful earlier write does not turn a proven storage outage into a document failure");
  }

  @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
  @Test
  void actualDirectoryFailureRemainsFreeBeyondGrace() throws Exception {
    Path index = temp.resolve("policy-index");
    var config = new ResolvedConfigBuilder();
    config.putDefault("index.writer.max_buffered_docs", "2");
    config.putDefault("index.nrt.mode", "on_demand");
    config.putDefault("index.nrt.background_reopen_ms", "600000");
    var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(index)
        .withConfig(config.build()).withExecutorRegistrations(testLuceneExecutors()).open();
    var permissions = Files.getPosixFilePermissions(index);
    try {
      Files.setPosixFilePermissions(index,
          Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
      assertFalse(Files.isWritable(index));
      var failure = assertThrows(IndexRuntimeIOException.class, () -> {
        for (int i = 0; i < 2; i++) {
          runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
              SchemaFields.DOC_ID, "healthy-" + i,
              SchemaFields.DOC_UID, "uid-" + i,
              SchemaFields.CONTENT, "healthy extracted content")));
        }
      });
      assertInstanceOf(java.nio.file.AccessDeniedException.class, failure.getCause());
      var service = new BatchServiceFailures();
      var deferred = new ArrayList<Boolean>();
      for (int batch = 0; batch < 8; batch++) {
        service.begin();
        service.failure(failure, deferred::add);
        service.finish();
      }
      assertEquals(Collections.nCopies(8, true), deferred,
          "actual index-directory I/O must remain free beyond ambiguous grace");
    } finally {
      Files.setPosixFilePermissions(index, permissions);
      runtime.close();
    }
  }

  /** Only the fixture methods required by the verifier's unchanged regression scenario. */
  private static final class Scenario {
    Path temp;
    io.justsearch.indexerworker.queue.SqliteJobQueue queue;
    io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore store;
    IndexingLoop loop;
    IndexingCoordinator writer;
    CommitOps commitOps;
    Path db;
    String extractedText;

    void init() throws Exception {
      db = temp.resolve("jobs.db");
      queue = new io.justsearch.indexerworker.queue.SqliteJobQueue(db);
      queue.open();
    }

    void openStore(boolean readOnly) {
      store = new io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore(
          readOnly ? Path.of("file:" + db + "?mode=ro") : db);
    }

    IndexingLoop newLoop() throws Exception {
      var content = mock(io.justsearch.indexerworker.extract.TimeboxedContentExtractor.class);
      when(content.extractionPolicy()).thenReturn(io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults());
      when(content.extractArtifact(any())).thenReturn(io.justsearch.indexerworker.extract.ExtractionArtifact.full(
          new io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult(extractedText, null, "text/plain"),
          io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults(), "verifier", false));
      return new IndexingLoop(null, null, queue, writer, commitOps,
          mock(DocumentFieldOps.class), mock(IndexCountOps.class), () -> null,
          mock(io.justsearch.indexerworker.coordination.WorkerSignalBus.class),
          io.justsearch.indexerworker.loop.pacing.IndexingPacing.unthrottled(), null, null, null, null,
          content, null, new IndexingLoopOptions(false, null, store, null, null, null));
    }

    List<Path> enqueue(String... names) throws Exception {
      var files = new ArrayList<Path>();
      for (String name : names) files.add(Files.writeString(temp.resolve(name), "healthy readable content"));
      queue.enqueueEntries(files.stream().map(file -> io.justsearch.indexerworker.queue.JobQueue.EnqueueEntry.stat(file)
          .withinRoot(temp)).toList());
      return files;
    }

    record Row(String path, String state, int attempts, long retry, String policy) {}

    List<Row> rows() throws Exception {
      try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
          var s = c.createStatement();
          var rows = s.executeQuery("SELECT path,state,attempts,retry_after,last_retry_policy FROM jobs ORDER BY path")) {
        var result = new ArrayList<Row>();
        while (rows.next()) result.add(new Row(rows.getString(1), rows.getString(2), rows.getInt(3),
            rows.getLong(4), rows.getString(5)));
        return result;
      }
    }

    void stopLoopForOtherAssertions() throws Exception {
      loop.close();
      loop = null;
    }

    void close() throws Exception {
      if (loop != null) loop.close();
      if (store != null) store.close();
      if (queue != null) queue.close();
    }
  }
}
