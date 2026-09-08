package io.justsearch.adapters.lucene.runtime;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.MergePolicy;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.store.Directory;

/**
 * Test-only accessor for runtime internals. Unwraps the sealed phase types
 * ({@link RunningRuntime} / {@link ReadOnlyRuntime} / {@link DeferredRuntime})
 * to their owned {@link RuntimeSession} for white-box assertions.
 */
final class LifecycleTestAccessor {
  private final RuntimeSession session;

  LifecycleTestAccessor(RunningRuntime runtime) {
    this.session = runtime.session();
  }

  LifecycleTestAccessor(DeferredRuntime runtime) {
    this.session = runtime.session();
  }

  LifecycleTestAccessor(ReadOnlyRuntime runtime) {
    this.session = runtime.session();
  }

  /** Returns the underlying RuntimeSession for white-box assertions. */
  RuntimeSession session() {
    return session;
  }

  private LifecycleSnapshot currentSnapshot() {
    return session.snapshot;
  }

  void refreshConfigForTests() {
    session.resolvedConfig = RuntimeSession.resolveFromConfigStore();
  }

  Directory directory() {
    LifecycleSnapshot snap = currentSnapshot();
    return snap != null ? snap.directory() : null;
  }

  MergePolicy mergePolicy() {
    IndexWriter w = writer();
    return w == null ? null : w.getConfig().getMergePolicy();
  }

  Path indexPath() {
    return session.indexPath;
  }

  double ramBufferMb() {
    IndexWriter w = writer();
    return w == null ? -1 : w.getConfig().getRAMBufferSizeMB();
  }

  int maxBufferedDocs() {
    IndexWriter w = writer();
    return w == null ? -1 : w.getConfig().getMaxBufferedDocs();
  }

  Sort indexSort() {
    IndexWriter w = writer();
    return w == null ? null : w.getConfig().getIndexSort();
  }

  KnnVectorsFormat knnVectorsFormat() {
    return session.knnVectorsFormat;
  }

  Integer vectorEfSearchOverrideOrNull() {
    return session.vectorEfSearchOverrideOrNull;
  }

  boolean vectorExhaustiveSearch() {
    return session.vectorExhaustiveSearch;
  }

  Analyzer indexAnalyzer() {
    LifecycleSnapshot snap = currentSnapshot();
    return snap != null ? snap.indexAnalyzer() : null;
  }

  String softDeletesField() {
    IndexWriter w = writer();
    return w == null ? null : w.getConfig().getSoftDeletesField();
  }

  long lastRefreshTargetMs() {
    return session.lastRefreshTargetMs.get();
  }

  IndexSearcher acquireSearcher() throws IOException {
    LifecycleSnapshot snap = currentSnapshot();
    SearcherManager mgr = snap != null ? snap.searcherManager() : null;
    if (mgr == null) {
      throw new IllegalStateException("SearcherManager not initialized");
    }
    return mgr.acquire();
  }

  void releaseSearcher(IndexSearcher searcher) throws IOException {
    LifecycleSnapshot snap = currentSnapshot();
    SearcherManager mgr = snap != null ? snap.searcherManager() : null;
    if (mgr != null && searcher != null) {
      mgr.release(searcher);
    }
  }

  void addRawDocument(Document document) throws IOException {
    IndexWriter w = writer();
    if (w == null) {
      throw new IllegalStateException("IndexWriter not initialized");
    }
    w.addDocument(document);
  }

  private IndexWriter writer() {
    LifecycleSnapshot snap = currentSnapshot();
    return snap != null ? snap.writer() : null;
  }
}
