/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.document.FeatureField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.SoftDeletesDirectoryReaderWrapper;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ReferenceManager;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A prune that reports a file deleted removes every row of it (parent, chunks, their vectors), and
 * removes nothing of a file that came back, became uncertain or was renamed meanwhile.
 *
 * <p>The background refresh is suspended throughout, so the search view moves only when something
 * refreshes it on purpose; nothing in these tests refreshes it between the prune and the reads.
 */
class PruneRemovesWholeFileTest extends LuceneExecutorTestBase {

  private static final CommitMetadataSource METADATA = () -> Map.of(
      "index_fingerprint", "prune-whole-file-1.0.0",
      "schema_fp", "test-fingerprint",
      "boosts_fp", "test-boosts",
      "dag_hash", "test-dag-hash",
      "pipeline_budget_profile", "test-profile",
      "field_catalog_hash", "test-catalog-hash",
      "synonyms_hash", "test-synonyms-hash");

  private static final float[] FILE_VECTOR = {1f, 0f, 0f, 0f};

  @TempDir Path indexDir;
  @TempDir Path files;
  private RunningRuntime runtime;
  private Path scope;

  private static FieldCatalogDef catalog() {
    return new FieldCatalogDef("prune-whole-file-v1", List.of(
        new FieldDef("doc_id", "keyword", true, true, List.of("id", "sort"), null, null, false),
        new FieldDef("doc_uid", "keyword", true, true, List.of("sort", "tiebreak"), null, null, false),
        new FieldDef("content", "text", true, false, List.of("highlight"), null, "icu", false),
        new FieldDef("path", "keyword", true, true, List.of("filter", "sort"), null, null, false),
        new FieldDef("is_chunk", "keyword", true, true, List.of("filter"), null, null, false),
        new FieldDef("parent_doc_id", "keyword", true, true, List.of("filter"), null, null, false),
        new FieldDef("embedding_status", "keyword", true, true, List.of("filter"), null, null, false),
        new FieldDef("splade_status", "keyword", true, true, List.of("filter"), null, null, false),
        new FieldDef("vector", "vector", false, false, List.of("vector"),
            new FieldCatalogDef.VectorSpec(4), null, false, "preserve-reread-or-reset:embedding_status"),
        new FieldDef("splade", "splade", false, false, List.of(), null, null, false,
            "reset-status:splade_status")));
  }

  @BeforeEach
  void open() throws IOException {
    runtime = openRuntime();
    scope = Files.createDirectories(files.resolve("scope"));
  }

  @AfterEach
  void close() {
    if (runtime != null) {
      runtime.close();
    }
  }

  private RunningRuntime openRuntime() {
    RunningRuntime opened = IndexSchema.fromCatalog(catalog(), METADATA, metadata -> {})
        .atPath(indexDir).withExecutorRegistrations(testLuceneExecutors()).open();
    opened.commitOps().stopCommitTimer();
    opened.commitOps().suspendNrtRefresh();
    return opened;
  }

  private static String key(Path path) {
    String normalized = path.toAbsolutePath().toString();
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
        ? normalized.toLowerCase(java.util.Locale.ROOT) : normalized;
  }

  private Path createFile(String name) throws IOException {
    return Files.writeString(scope.resolve(name), "content of " + name);
  }

  /** Writes a file's parent and {@code chunks} chunks with fresh chunk ids, as ingestion does. */
  private void writeFile(String path, String word, int chunks) {
    runtime.indexingCoordinator().indexSingle(row(path, path, false, word + " parent"));
    for (int i = 0; i < chunks; i++) {
      runtime.indexingCoordinator().indexSingle(
          row("chunk:" + UUID.randomUUID(), path, true, word + " chunk " + i));
    }
  }

  private static IndexDocument row(String id, String path, boolean chunk, String content) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, id);
    fields.put(SchemaFields.DOC_UID, id + "#uid");
    fields.put(SchemaFields.PATH, path);
    fields.put(SchemaFields.CONTENT, content);
    fields.put("vector", FILE_VECTOR);
    fields.put("splade", Map.of("zebraterm", 2.0f));
    if (chunk) {
      fields.put(SchemaFields.IS_CHUNK, "true");
      fields.put(SchemaFields.PARENT_DOC_ID, path);
    }
    return new IndexDocument(fields);
  }

  private void commitAndRefresh() {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private PruneOps.PruneResult prune(PruneOps.PresenceGuard guard, List<String> marks) {
    return runtime.pruneOps().pruneByPathPrefix(scope.toString(), () -> false, 100, guard, marks::add);
  }

  private PruneOps.PresenceGuard rootGuard() {
    return PruneOps.PresenceGuard.forRoot(scope);
  }

  private static Query rowsOf(String path) {
    return new BooleanQuery.Builder()
        .add(new TermQuery(new Term(SchemaFields.PATH, path)), BooleanClause.Occur.SHOULD)
        .add(new TermQuery(new Term(SchemaFields.DOC_ID, path)), BooleanClause.Occur.SHOULD)
        .add(new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, path)), BooleanClause.Occur.SHOULD)
        .build();
  }

  /** The rows of {@code path} in the last commit, read from disk independently of the runtime. */
  private int committedRows(String path) throws IOException {
    try (var directory = FSDirectory.open(indexDir);
        var reader = new SoftDeletesDirectoryReaderWrapper(
            DirectoryReader.open(directory), SchemaFields.SOFT_DELETE)) {
      return new IndexSearcher(reader).count(rowsOf(path));
    }
  }

  /** Counts {@code query} on a searcher newly acquired from the search view, without refreshing. */
  private int searchNow(Query query) throws IOException {
    SearcherManager manager = runtime.session().snapshot.searcherManager();
    IndexSearcher searcher = manager.acquire();
    try {
      return searcher.count(query);
    } finally {
      manager.release(searcher);
    }
  }

  /** The current rows of {@code path}, after a test-side commit and refresh. */
  private int currentRows(String path) throws IOException {
    commitAndRefresh();
    return searchNow(rowsOf(path));
  }

  @Test
  void reindexBeforeThePruneLeavesNoChunkOfTheDeletedFile() throws Exception {
    Path file = createFile("report.txt");
    String path = key(file);
    writeFile(path, "first", 3);
    commitAndRefresh();
    // Re-index: the chunks are replaced with new ids; the search view is not refreshed.
    runtime.indexingCoordinator().deleteChunksForParentDocId(path);
    writeFile(path, "second", 3);
    runtime.commitOps().commitAndTrack();
    Files.delete(file);

    PruneOps.PruneResult result = prune(rootGuard(), new ArrayList<>());

    assertEquals(PruneOps.Completion.COMPLETE, result.completion());
    assertEquals(0, committedRows(path),
        "the committed index must hold no parent or chunk of the file the prune removed");
  }

  @Test
  void aFileWrittenAfterTheLastRefreshIsFoundOrTheRunIsIncomplete() throws Exception {
    Path file = createFile("unseen.txt");
    String path = key(file);
    // Committed, but the search view never saw the parent or any chunk.
    writeFile(path, "unseen", 3);
    runtime.commitOps().commitAndTrack();
    Files.delete(file);

    PruneOps.PruneResult result = prune(rootGuard(), new ArrayList<>());

    assertTrue(committedRows(path) == 0 || !result.complete(),
        "a complete prune must not leave rows of a deleted file; got " + committedRows(path)
            + " rows and " + result.completion());
  }

  @Test
  void aFileThatReappearsBeforeTheBatchIsAppliedKeepsItsRowsAndGetsNoMark() throws Exception {
    Path file = createFile("back.txt");
    String path = key(file);
    writeFile(path, "back", 3);
    commitAndRefresh();
    Files.delete(file);
    AtomicBoolean observed = new AtomicBoolean();
    AtomicBoolean recreated = new AtomicBoolean();
    PruneOps.PresenceGuard guard = new PruneOps.PresenceGuard() {
      @Override public boolean rootCurrent() {
        // The root check that vouches for the batch: the file comes back at this moment.
        if (observed.get() && recreated.compareAndSet(false, true)) {
          try {
            Files.writeString(file, "recreated");
          } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
        }
        return PathPresence.isReadableDirectory(scope);
      }

      @Override public PathPresence presence(Path probed) {
        observed.set(true);
        return PathPresence.probe(probed);
      }
    };
    List<String> marks = new ArrayList<>();

    prune(guard, marks);

    assertTrue(recreated.get(), "precondition: the file came back before the batch was applied");
    assertEquals(4, currentRows(path), "a file that exists again keeps its parent and chunks");
    assertEquals(List.of(), marks, "a file that exists again gets no deletion mark");
  }

  @Test
  void aFileThatBecomesUncertainBeforeTheBatchIsAppliedKeepsItsRows() throws Exception {
    Path file = createFile("uncertain.txt");
    String path = key(file);
    writeFile(path, "uncertain", 3);
    commitAndRefresh();
    Files.delete(file);
    AtomicBoolean observed = new AtomicBoolean();
    AtomicBoolean uncertain = new AtomicBoolean();
    PruneOps.PresenceGuard guard = new PruneOps.PresenceGuard() {
      @Override public boolean rootCurrent() {
        if (observed.get()) {
          uncertain.set(true);
        }
        return PathPresence.isReadableDirectory(scope);
      }

      @Override public PathPresence presence(Path probed) {
        observed.set(true);
        return uncertain.get() ? PathPresence.UNKNOWN : PathPresence.probe(probed);
      }
    };
    List<String> marks = new ArrayList<>();

    PruneOps.PruneResult result = prune(guard, marks);

    assertTrue(uncertain.get(), "precondition: the file became uncertain before the batch");
    assertEquals(4, currentRows(path), "a file whose absence cannot be told keeps its rows");
    assertEquals(List.of(), marks, "a file whose absence cannot be told gets no deletion mark");
    assertEquals(PruneOps.Completion.INCOMPLETE, result.completion());
  }

  @Test
  void aRootSwappedWhileTheBatchIsCheckedAgainRemovesNothing() throws Exception {
    Path file = createFile("swapped.txt");
    String path = key(file);
    writeFile(path, "swapped", 3);
    commitAndRefresh();
    Files.delete(file);
    AtomicInteger rootChecks = new AtomicInteger();
    AtomicBoolean swapped = new AtomicBoolean();
    PruneOps.PresenceGuard guard = new PruneOps.PresenceGuard() {
      @Override public boolean rootCurrent() {
        rootChecks.incrementAndGet();
        return !swapped.get() && PathPresence.isReadableDirectory(scope);
      }

      @Override public PathPresence presence(Path probed) {
        PathPresence seen = PathPresence.probe(probed);
        // The scan's looks come after the prune's first root check; the batch's look at the file
        // comes after the batch's root check: the root is replaced while that look is taken.
        if (rootChecks.get() >= 2) {
          swapped.set(true);
        }
        return seen;
      }
    };
    List<String> marks = new ArrayList<>();

    PruneOps.PruneResult result = prune(guard, marks);

    assertTrue(swapped.get(), "precondition: the root changed during the batch's last check");
    assertEquals(4, currentRows(path), "nothing is removed on the strength of a changed root");
    assertEquals(List.of(), marks, "no deletion mark on the strength of a changed root");
    assertEquals(PruneOps.Completion.INCOMPLETE, result.completion());
  }

  @Test
  void aFileThatReappearsWhileTheBatchRefreshesItsViewKeepsItsRows() throws Exception {
    Path file = createFile("refreshed.txt");
    String path = key(file);
    writeFile(path, "refreshed", 3);
    commitAndRefresh();
    Files.delete(file);
    AtomicInteger rootChecks = new AtomicInteger();
    AtomicBoolean recreated = new AtomicBoolean();
    // The batch's refresh is its slow step; the file comes back while it runs.
    ReferenceManager.RefreshListener recreate = new ReferenceManager.RefreshListener() {
      @Override public void beforeRefresh() throws IOException {
        if (rootChecks.get() >= 2 && recreated.compareAndSet(false, true)) {
          Files.writeString(file, "recreated");
        }
      }

      @Override public void afterRefresh(boolean didRefresh) {}
    };
    runtime.session().snapshot.searcherManager().addListener(recreate);
    PruneOps.PresenceGuard guard = new PruneOps.PresenceGuard() {
      @Override public boolean rootCurrent() {
        rootChecks.incrementAndGet();
        return PathPresence.isReadableDirectory(scope);
      }

      @Override public PathPresence presence(Path probed) {
        return PathPresence.probe(probed);
      }
    };
    List<String> marks = new ArrayList<>();

    prune(guard, marks);

    assertTrue(recreated.get(), "precondition: the file came back during the batch's refresh");
    assertEquals(4, currentRows(path), "a file that exists again keeps its parent and chunks");
    assertEquals(List.of(), marks, "a file that exists again gets no deletion mark");
  }

  @Test
  void aRootLostOnceTheRemovalsArePublishedMakesTheRunIncomplete() throws Exception {
    Path file = createFile("published.txt");
    String path = key(file);
    writeFile(path, "published", 3);
    commitAndRefresh();
    Files.delete(file);
    AtomicBoolean lost = new AtomicBoolean();
    // The root goes away as soon as a refreshed view no longer holds the removed file.
    SearcherManager manager = runtime.session().snapshot.searcherManager();
    manager.addListener(new ReferenceManager.RefreshListener() {
      @Override public void beforeRefresh() {}

      @Override public void afterRefresh(boolean didRefresh) throws IOException {
        IndexSearcher searcher = manager.acquire();
        try {
          if (searcher.count(rowsOf(path)) == 0) {
            lost.set(true);
          }
        } finally {
          manager.release(searcher);
        }
      }
    });
    PruneOps.PresenceGuard guard = new PruneOps.PresenceGuard() {
      @Override public boolean rootCurrent() {
        return !lost.get() && PathPresence.isReadableDirectory(scope);
      }

      @Override public PathPresence presence(Path probed) {
        return PathPresence.probe(probed);
      }
    };

    PruneOps.PruneResult result = prune(guard, new ArrayList<>());

    assertTrue(lost.get(), "precondition: the root was lost after the removals were published");
    assertEquals(0, committedRows(path), "the removals made while the root held stay made");
    assertEquals(PruneOps.Completion.INCOMPLETE, result.completion(),
        "a run whose root is gone by its end does not report itself complete");
  }

  @Test
  void aFileRenamedBeforeTheBatchIsAppliedKeepsItsRowsUnderTheNewPath() throws Exception {
    Path file = createFile("old-name.txt");
    String oldPath = key(file);
    Path renamed = scope.resolve("new-name.txt");
    String newPath = key(renamed);
    writeFile(oldPath, "renamed", 3);
    commitAndRefresh();
    Files.move(file, renamed);
    AtomicBoolean moved = new AtomicBoolean();
    PruneOps.PresenceGuard guard = new PruneOps.PresenceGuard() {
      @Override public boolean rootCurrent() {
        return PathPresence.isReadableDirectory(scope);
      }

      @Override public PathPresence presence(Path probed) {
        // The scan has read the old rows; the index learns of the rename now.
        if (moved.compareAndSet(false, true)) {
          assertEquals(4, runtime.indexingCoordinator().updateDocumentPaths(oldPath, newPath));
        }
        return PathPresence.probe(probed);
      }
    };
    List<String> marks = new ArrayList<>();

    prune(guard, marks);

    assertTrue(moved.get(), "precondition: the rename happened after the scan read the file");
    assertEquals(4, currentRows(newPath), "the renamed file keeps its parent and chunks");
    assertEquals(0, currentRows(oldPath));
    assertEquals(List.of(), marks, "a renamed file's old path gets no deletion mark");
  }

  @Test
  void searchesStartedRightAfterThePruneAndAReopenSeeNoRowOfTheDeletedFile() throws Exception {
    Path file = createFile("gone.txt");
    String path = key(file);
    Path live = createFile("live.txt");
    writeFile(path, "zebrafile", 3);
    writeFile(key(live), "livefile", 1);
    commitAndRefresh();
    Query text = new TermQuery(new Term(SchemaFields.CONTENT, "zebrafile"));
    Query sparse = new BooleanQuery.Builder()
        .add(FeatureField.newLinearQuery("splade", "zebraterm", 1f), BooleanClause.Occur.MUST)
        .add(new TermQuery(new Term(SchemaFields.PATH, path)), BooleanClause.Occur.FILTER)
        .build();
    Query dense = new BooleanQuery.Builder()
        .add(new KnnFloatVectorQuery("vector", FILE_VECTOR, 10), BooleanClause.Occur.MUST)
        .add(new TermQuery(new Term(SchemaFields.PATH, path)), BooleanClause.Occur.FILTER)
        .build();
    Query document = new TermQuery(new Term(SchemaFields.DOC_ID, path));
    assertEquals(4, searchNow(text), "precondition: text search finds the file");
    assertEquals(4, searchNow(sparse), "precondition: sparse search finds the file");
    assertEquals(4, searchNow(dense), "precondition: dense search finds the file");
    assertEquals(1, searchNow(document), "precondition: the document is found");
    Files.delete(file);

    PruneOps.PruneResult result = prune(rootGuard(), new ArrayList<>());

    assertEquals(PruneOps.Completion.COMPLETE, result.completion());
    assertEquals(0, searchNow(document), "document lookup after the prune");
    assertEquals(0, searchNow(text), "text search after the prune");
    assertEquals(0, searchNow(dense), "dense search after the prune");
    assertEquals(0, searchNow(sparse), "sparse search after the prune");
    assertEquals(0, searchNow(rowsOf(path)), "no row of the file after the prune");
    assertEquals(2, searchNow(rowsOf(key(live))), "the live file is untouched");

    runtime.close();
    runtime = openRuntime();
    assertEquals(0, searchNow(rowsOf(path)), "the removal survives a reopen");
    assertEquals(2, searchNow(rowsOf(key(live))), "the live file survives a reopen");
  }

  @Test
  void aRuntimeClosedDuringThePruneEndsItWithoutSuccessOrHang() throws Exception {
    Path file = createFile("closing.txt");
    String path = key(file);
    writeFile(path, "closing", 3);
    commitAndRefresh();
    Files.delete(file);
    AtomicBoolean closed = new AtomicBoolean();
    PruneOps.PresenceGuard guard = new PruneOps.PresenceGuard() {
      @Override public boolean rootCurrent() {
        return PathPresence.isReadableDirectory(scope);
      }

      @Override public PathPresence presence(Path probed) {
        if (closed.compareAndSet(false, true)) {
          Thread closer = new Thread(runtime::close, "prune-close-race");
          closer.start();
          try {
            closer.join(10_000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          assertFalse(closer.isAlive(), "close must not wait for the prune");
        }
        return PathPresence.probe(probed);
      }
    };

    assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
      List<String> marks = new ArrayList<>();
      assertThrows(RuntimeException.class, () -> prune(guard, marks),
          "a prune whose runtime closed under it must not report success");
      assertEquals(List.of(), marks, "nothing is marked deleted by a prune that failed");
    });
    runtime = null;
  }

  @Test
  void aSearchViewThatCannotBeRefreshedEndsThePruneWithoutSuccess() throws Exception {
    Path file = createFile("unrefreshable.txt");
    String path = key(file);
    writeFile(path, "unrefreshable", 3);
    commitAndRefresh();
    Files.delete(file);
    AtomicBoolean broken = new AtomicBoolean();
    PruneOps.PresenceGuard guard = new PruneOps.PresenceGuard() {
      @Override public boolean rootCurrent() {
        return PathPresence.isReadableDirectory(scope);
      }

      @Override public PathPresence presence(Path probed) {
        if (broken.compareAndSet(false, true)) {
          try {
            runtime.session().snapshot.searcherManager().close();
          } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
        }
        return PathPresence.probe(probed);
      }
    };

    assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
        assertThrows(RuntimeException.class, () -> prune(guard, new ArrayList<>()),
            "a prune that cannot make its removals visible must not report success"));
    assertTrue(broken.get());
  }
}
