/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.FieldCatalogDef;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;

class CorpusProfileCacheTest {
  @Test
  void refreshBetweenVersionProbeAndProfilingUsesTheProfiledReadersVersion() throws Exception {
    var scans = new AtomicInteger();
    var session = new RuntimeSession(IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)));
    session.nrtMode = NrtMode.ON_DEMAND;
    try (var directory = new ByteBuffersDirectory();
        var writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()));
        var original = new SearcherManager(writer, new SearcherFactory())) {
      writer.addDocument(new Document());
      try (var refreshed = new SearcherManager(writer, countingFactory(scans, () -> {}))) {
        session.snapshot = snapshot(original);
        var acquisitions = new AtomicInteger();
        session.foregroundActive = () -> {
          // The bridge captures the manager before consulting the foreground predicate.
          // Both probes therefore borrow the old reader; profiling sees the new snapshot.
          if (acquisitions.incrementAndGet() == 2) session.snapshot = snapshot(refreshed);
          return true;
        };
        var counts = new IndexCountOps(new SearcherBridge(session));
        var profile = counts.getOrComputeCorpusProfile();
        assertEquals(1, profile.parentDocCount(), "the traversal must use the refreshed reader");
        assertSame(profile, counts.getOrComputeCorpusProfile(),
            "an unchanged profiled reader must hit the cache, even when the preceding probe was older");
        assertEquals(1, scans.get(), "the refreshed reader must be counted exactly once");
      }
    }
  }

  @Test
  void concurrentMissesScanOnceAndReaderRefreshInvalidatesTheProfile() throws Exception {
    var scans = new AtomicInteger();
    var scanEntered = new CountDownLatch(1);
    var releaseScan = new CountDownLatch(1);
    var secondVersionRead = new CountDownLatch(1);
    var session = new RuntimeSession(IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)));
    session.nrtMode = NrtMode.ON_DEMAND;
    try (var directory = new ByteBuffersDirectory();
        var writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()));
        var manager = new SearcherManager(writer, countingFactory(scans, () -> {
          scanEntered.countDown();
          try {
            assertTrue(releaseScan.await(2, TimeUnit.SECONDS));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("corpus scan interrupted", interrupted);
          }
        }))) {
      session.snapshot = snapshot(manager);
      session.foregroundActive = () -> {
        if (Thread.currentThread().getName().equals("second-profile")) secondVersionRead.countDown();
        return true;
      };
      var counts = new IndexCountOps(new SearcherBridge(session));
      var first = new FutureTask<>(counts::getOrComputeCorpusProfile);
      var second = new FutureTask<>(counts::getOrComputeCorpusProfile);
      Thread.ofPlatform().daemon().name("first-profile").start(first);
      try {
        assertTrue(scanEntered.await(2, TimeUnit.SECONDS));
        Thread.ofPlatform().daemon().name("second-profile").start(second);
        assertTrue(secondVersionRead.await(2, TimeUnit.SECONDS));
      } finally {
        releaseScan.countDown();
      }
      assertSame(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS));
      assertEquals(1, scans.get(), "concurrent cache misses must share one corpus traversal");
      assertSame(first.get(), counts.getOrComputeCorpusProfile());

      writer.addDocument(new Document());
      manager.maybeRefreshBlocking();
      assertEquals(1, counts.getOrComputeCorpusProfile().parentDocCount());
      assertEquals(2, scans.get(), "reader refresh must trigger another corpus traversal");
    }
  }

  private static LifecycleSnapshot snapshot(SearcherManager manager) {
    return new LifecycleSnapshot(null, null, manager, null, false, null);
  }

  private static SearcherFactory countingFactory(AtomicInteger scans, Runnable beforeCount) {
    return new SearcherFactory() {
      @Override
      public IndexSearcher newSearcher(IndexReader reader, IndexReader previousReader) {
        return new IndexSearcher(reader) {
          @Override
          public int count(Query query) throws IOException {
            scans.incrementAndGet();
            beforeCount.run();
            return super.count(query);
          }
        };
      }
    };
  }
}
