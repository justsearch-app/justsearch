/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;

class CorpusProfileCacheTest {
  @Test
  void concurrentMissesScanOnceAndReaderRefreshInvalidatesTheProfile() throws Exception {
    try (var directory = new ByteBuffersDirectory();
        var writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()));
        var reader = DirectoryReader.open(writer)) {
      var searcher = spy(new IndexSearcher(reader));
      var current = new AtomicReference<>(searcher);
      var scans = new AtomicInteger();
      var scanEntered = new CountDownLatch(1);
      var releaseScan = new CountDownLatch(1);
      var secondVersionRead = new CountDownLatch(1);
      doAnswer(invocation -> {
        scans.incrementAndGet();
        scanEntered.countDown();
        assertTrue(releaseScan.await(2, TimeUnit.SECONDS));
        return invocation.callRealMethod();
      }).when(searcher).count(any(Query.class));
      var bridge = mock(SearcherBridge.class);
      doAnswer(invocation -> {
        ReadPathOps.SearcherOperation<?> operation = invocation.getArgument(0);
        Object result = operation.execute(current.get());
        if (Thread.currentThread().getName().equals("second-profile")) secondVersionRead.countDown();
        return result;
      }).when(bridge).withSearcher(any());
      var counts = new IndexCountOps(bridge);
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
      try (var refreshed = DirectoryReader.openIfChanged(reader, writer)) {
        current.set(new IndexSearcher(refreshed));
        assertEquals(1, counts.getOrComputeCorpusProfile().parentDocCount());
      }
    }
  }
}
