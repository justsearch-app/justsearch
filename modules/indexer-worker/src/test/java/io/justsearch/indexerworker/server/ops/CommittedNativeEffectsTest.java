/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.QueryFilterBuilder;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.queue.SwitchBufferUpsert;
import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * RED contract for committed native broad effects.
 *
 * <p>The receipt list is the accepted order from one committed candidate.  A broad delete is
 * certified against the actual B reader; it is never replayed as a new mutation.  The fixture
 * deliberately uses real indexed/stored StringFields so a mocked runtime cannot manufacture a
 * count or field witness.
 */
final class CommittedNativeEffectsTest {
  private static final String GENERATION = "green";
  private static final String HASH = "a".repeat(64);

  @Test
  void prefixDeleteThenLaterUpsertKeepsFileAndOwnedChunkWithoutBroadMutation() throws Exception {
    String root = absolute("lane-f-prefix-order");
    String prefix = QueryFilterBuilder.normalizePathPrefix(root);
    String path = absoluteUnder(prefix, "later.txt");
    var delete = op("prefix:" + prefix, "DELETE_PREFIX", prefix, 1);
    var upsert = fileUpsert(path, "notes", "unit-2", 2);

    try (var fixture = fixture(file(path, "notes", HASH), chunk(path))) {
      var harness = harness(fixture, List.of(delete, upsert), List.of());
      when(harness.queue.removeReplayedSwitchBufferOps(List.of(delete, upsert))).thenReturn(2);

      assertTrue(settle(harness));
      assertEquals(1, fixture.count(new TermQuery(new Term(SchemaFields.DOC_ID, path))));
      assertEquals(1, fixture.count(new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, path))));
      verify(harness.indexing, never()).deleteByPathPrefix(prefix);
      verify(harness.indexing, never()).deleteByIdAndChunks(anyString());
      verify(harness.queue, never()).deleteByPathPrefix(anyString());
      verify(harness.queue, never()).deleteByExactPath(anyString());
    }
  }

  @Test
  void prefixDeleteAfterUpsertCertifiesEmptyReaderWithoutFileWitness() throws Exception {
    String root = absolute("lane-f-prefix-empty");
    String prefix = QueryFilterBuilder.normalizePathPrefix(root);
    String path = absoluteUnder(prefix, "gone.txt");
    var upsert = fileUpsert(path, "notes", "unit-1", 1);
    var delete = op("prefix:" + prefix, "DELETE_PREFIX", prefix, 2);

    try (var fixture = fixture()) {
      var harness = harness(fixture, List.of(upsert, delete), List.of());
      when(harness.queue.removeReplayedSwitchBufferOps(List.of(upsert, delete))).thenReturn(2);

      assertTrue(settle(harness));
      assertEquals(0, fixture.count(new PrefixQuery(new Term(SchemaFields.PATH, prefix))));
      verify(harness.queue, never()).matchesAcceptedFileProjection(
          anyString(), anyString(), anyString(), anyString());
      verify(harness.indexing, never()).deleteByPathPrefix(prefix);
    }
  }

  @Test
  void collectionDeleteThenLaterUpsertKeepsFileAndOwnedChunkWithoutCollectionMutation()
      throws Exception {
    String collection = "notes";
    String path = absolute("lane-f-collection-order", "later.txt");
    var delete = op("collection:" + collection, "DELETE_COLLECTION", collection, 1);
    var upsert = fileUpsert(path, collection, "unit-2", 2);

    try (var fixture = fixture(file(path, collection, HASH), chunk(path))) {
      var harness = harness(fixture, List.of(delete, upsert), List.of());
      when(harness.queue.removeReplayedSwitchBufferOps(List.of(delete, upsert))).thenReturn(2);

      assertTrue(settle(harness));
      assertEquals(1, fixture.count(new TermQuery(new Term(SchemaFields.DOC_ID, path))));
      assertEquals(1, fixture.count(new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, path))));
      verify(harness.indexing, never()).deleteByCollection(collection);
      verify(harness.indexing, never()).deleteByIdAndChunks(anyString());
    }
  }

  @Test
  void collectionDeleteAfterUpsertCertifiesEmptyReaderWithoutFileWitness() throws Exception {
    String collection = "notes";
    String path = absolute("lane-f-collection-empty", "gone.txt");
    var upsert = fileUpsert(path, collection, "unit-1", 1);
    var delete = op("collection:" + collection, "DELETE_COLLECTION", collection, 2);

    try (var fixture = fixture()) {
      var harness = harness(fixture, List.of(upsert, delete), List.of());
      when(harness.queue.removeReplayedSwitchBufferOps(List.of(upsert, delete))).thenReturn(2);

      assertTrue(settle(harness));
      assertEquals(0, fixture.count(new TermQuery(new Term(SchemaFields.COLLECTION, collection))));
      verify(harness.queue, never()).matchesAcceptedFileProjection(
          anyString(), anyString(), anyString(), anyString());
      verify(harness.indexing, never()).deleteByCollection(collection);
    }
  }

  @Test
  void unexpectedDocumentInDeletedPrefixRefusesEntireReceiptSnapshot() throws Exception {
    String root = absolute("lane-f-prefix-unexpected-doc");
    String prefix = QueryFilterBuilder.normalizePathPrefix(root);
    String unexpected = absoluteUnder(prefix, "unclaimed.txt");
    var delete = op("prefix:" + prefix, "DELETE_PREFIX", prefix, 1);

    try (var fixture = fixture(file(unexpected, "notes", HASH))) {
      var harness = harness(fixture, List.of(delete), List.of());

      assertFalse(settle(harness));
      verify(harness.queue, never()).removeReplayedSwitchBufferOps(anyList());
      verify(harness.indexing, never()).deleteByPathPrefix(prefix);
    }
  }

  @Test
  void unexpectedOwnedChunkInDeletedPrefixRefusesEntireReceiptSnapshot() throws Exception {
    String root = absolute("lane-f-prefix-unexpected-chunk");
    String prefix = QueryFilterBuilder.normalizePathPrefix(root);
    String unexpected = absoluteUnder(prefix, "unclaimed.txt");
    var delete = op("prefix:" + prefix, "DELETE_PREFIX", prefix, 1);

    try (var fixture = fixture(chunk(unexpected))) {
      var harness = harness(fixture, List.of(delete), List.of());

      assertFalse(settle(harness));
      verify(harness.queue, never()).removeReplayedSwitchBufferOps(anyList());
    }
  }

  @Test
  void projectionShapedChunkCannotMasqueradeAsAnOwnedLaterFile() throws Exception {
    String root = absolute("lane-f-prefix-projection-spoof");
    String prefix = QueryFilterBuilder.normalizePathPrefix(root);
    String path = absoluteUnder(prefix, "later.txt");
    var delete = op("prefix:" + prefix, "DELETE_PREFIX", prefix, 1);
    var upsert = fileUpsert(path, "notes", "unit-2", 2);

    try (var fixture = fixture(file(path, "notes", HASH), projectionChunk(path))) {
      var harness = harness(fixture, List.of(delete, upsert), List.of());

      assertFalse(settle(harness));
      verify(harness.queue, never()).removeReplayedSwitchBufferOps(anyList());
    }
  }

  @Test
  void indexedPathMustAgreeWithTheLaterFileReceipt() throws Exception {
    String root = absolute("lane-f-indexed-path-mismatch");
    String prefix = QueryFilterBuilder.normalizePathPrefix(root);
    String path = absoluteUnder(prefix, "later.txt");
    String wrongPath = absolute("lane-f-indexed-path-mismatch-other", "later.txt");
    var delete = op("prefix:" + prefix, "DELETE_PREFIX", prefix, 1);
    var upsert = fileUpsert(path, "notes", "unit-2", 2);

    try (var fixture = fixture(fileAt(path, wrongPath, "notes", HASH))) {
      var harness = harness(fixture, List.of(delete, upsert), List.of());

      assertFalse(settle(harness));
      verify(harness.queue, never()).removeReplayedSwitchBufferOps(anyList());
    }
  }

  @Test
  void indexedCollectionMustAgreeWithTheLaterFileReceipt() throws Exception {
    String collection = "notes";
    String path = absolute("lane-f-indexed-collection-mismatch", "later.txt");
    var delete = op("collection:" + collection, "DELETE_COLLECTION", collection, 1);
    var upsert = fileUpsert(path, collection, "unit-2", 2);

    try (var fixture = fixture(fileAt(path, path, "other", HASH))) {
      var harness = harness(fixture, List.of(delete, upsert), List.of());

      assertFalse(settle(harness));
      verify(harness.queue, never()).removeReplayedSwitchBufferOps(anyList());
    }
  }

  @Test
  void unknownEffectKindRetainsCommittedReceipt() throws Exception {
    var unknown = op("prefix:/lane-f-unknown/", "DELETE_PREFIX_RENAMED", "/lane-f-unknown/", 1);
    try (var fixture = fixture()) {
      var harness = harness(fixture, List.of(unknown), List.of());

      assertFalse(settle(harness));
      verify(harness.queue, never()).removeReplayedSwitchBufferOps(anyList());
    }
  }

  @Test
  void refreshEvidenceFailureRetainsCommittedReceipt() throws Exception {
    String root = absolute("lane-f-refresh-failure");
    String prefix = QueryFilterBuilder.normalizePathPrefix(root);
    var delete = op("prefix:" + prefix, "DELETE_PREFIX", prefix, 1);
    try (var fixture = fixture()) {
      var harness = harness(fixture, List.of(delete), List.of());
      org.mockito.Mockito.doThrow(new IllegalStateException("reader unavailable"))
          .when(harness.commits).maybeRefreshBlocking();

      assertFalse(settle(harness));
      verify(harness.queue, never()).removeReplayedSwitchBufferOps(anyList());
    }
  }

  @Test
  void foreignGenerationRemainsUntouchedWhenTargetSnapshotSettles() throws Exception {
    String root = absolute("lane-f-foreign-generation");
    String prefix = QueryFilterBuilder.normalizePathPrefix(root);
    var selected = op("prefix:" + prefix, "DELETE_PREFIX", prefix, 1);
    var foreign = new SwitchBufferCapableQueue.SwitchBufferOp(
        "blue", "prefix:" + prefix, "DELETE_PREFIX", prefix, 2, "foreign");
    try (var fixture = fixture()) {
      var harness = harness(fixture, List.of(selected, foreign), List.of(foreign));
      when(harness.queue.removeReplayedSwitchBufferOps(List.of(selected))).thenReturn(1);

      assertTrue(settle(harness));
      verify(harness.queue).removeReplayedSwitchBufferOps(List.of(selected));
      verify(harness.queue, never()).removeReplayedSwitchBufferOps(List.of(foreign));
    }
  }

  @Test
  void conditionalReceiptRemovalFailureRetainsCommittedSnapshot() throws Exception {
    String root = absolute("lane-f-remove-failure");
    String prefix = QueryFilterBuilder.normalizePathPrefix(root);
    var delete = op("prefix:" + prefix, "DELETE_PREFIX", prefix, 1);
    try (var fixture = fixture()) {
      var harness = harness(fixture, List.of(delete), List.of(delete));
      when(harness.queue.removeReplayedSwitchBufferOps(List.of(delete))).thenReturn(0);

      assertFalse(settle(harness));
      verify(harness.queue).removeReplayedSwitchBufferOps(List.of(delete));
    }
  }

  @Test
  void nonterminalPrefixQueueWorkRefusesBroadReceiptAcknowledgement() throws Exception {
    String root = absolute("lane-f-live-prefix-queue");
    String prefix = QueryFilterBuilder.normalizePathPrefix(root);
    var delete = op("prefix:" + prefix, "DELETE_PREFIX", prefix, 1);
    try (var fixture = fixture()) {
      var harness = harness(fixture, List.of(delete), List.of());
      when(harness.queue.hasNonterminalJobsByPathPrefixStrict(prefix)).thenReturn(true);

      assertFalse(settle(harness));
      verify(harness.queue, never()).removeReplayedSwitchBufferOps(anyList());
    }
  }

  @Test
  void unreadablePrefixQueueScopeRefusesBroadReceiptAcknowledgement() throws Exception {
    String root = absolute("lane-f-live-prefix-queue-error");
    String prefix = QueryFilterBuilder.normalizePathPrefix(root);
    var delete = op("prefix:" + prefix, "DELETE_PREFIX", prefix, 1);
    try (var fixture = fixture()) {
      var harness = harness(fixture, List.of(delete), List.of());
      when(harness.queue.hasNonterminalJobsByPathPrefixStrict(prefix))
          .thenThrow(new IllegalStateException("queue reader unavailable"));

      assertFalse(settle(harness));
      verify(harness.queue, never()).removeReplayedSwitchBufferOps(anyList());
    }
  }

  @Test
  void nonterminalCollectionQueueWorkRefusesBroadReceiptAcknowledgement() throws Exception {
    String collection = "notes";
    var delete = op("collection:" + collection, "DELETE_COLLECTION", collection, 1);
    try (var fixture = fixture()) {
      var harness = harness(fixture, List.of(delete), List.of());
      when(harness.queue.hasNonterminalJobsByCollectionStrict(collection)).thenReturn(true);

      assertFalse(settle(harness));
      verify(harness.queue, never()).removeReplayedSwitchBufferOps(anyList());
    }
  }

  @Test
  void unreadableCollectionQueueScopeRefusesBroadReceiptAcknowledgement() throws Exception {
    String collection = "notes";
    var delete = op("collection:" + collection, "DELETE_COLLECTION", collection, 1);
    try (var fixture = fixture()) {
      var harness = harness(fixture, List.of(delete), List.of());
      when(harness.queue.hasNonterminalJobsByCollectionStrict(collection))
          .thenThrow(new IllegalStateException("queue reader unavailable"));

      assertFalse(settle(harness));
      verify(harness.queue, never()).removeReplayedSwitchBufferOps(anyList());
    }
  }

  @Test
  void strictPrefixCountFailureRetainsBroadReceipt() throws Exception {
    String root = absolute("lane-f-strict-count-error");
    String prefix = QueryFilterBuilder.normalizePathPrefix(root);
    var delete = op("prefix:" + prefix, "DELETE_PREFIX", prefix, 1);
    try (var fixture = fixture()) {
      var harness = harness(fixture, List.of(delete), List.of());
      when(harness.queue.hasNonterminalJobsByPathPrefixStrict(prefix)).thenReturn(false);
      org.mockito.Mockito.doThrow(new IOException("count reader unavailable"))
          .when(harness.counts).countPathPrefixExcludingAcceptedSurvivorsStrict(
              anyString(), anyList(), anyList());

      assertFalse(settle(harness));
      verify(harness.queue, never()).removeReplayedSwitchBufferOps(anyList());
    }
  }

  @Test
  void strictFieldReadFailureRetainsBroadReceipt() throws Exception {
    String root = absolute("lane-f-strict-field-error");
    String prefix = QueryFilterBuilder.normalizePathPrefix(root);
    String path = absoluteUnder(prefix, "later.txt");
    var delete = op("prefix:" + prefix, "DELETE_PREFIX", prefix, 1);
    var upsert = fileUpsert(path, "notes", "unit-2", 2);
    try (var fixture = fixture(file(path, "notes", HASH))) {
      var harness = harness(fixture, List.of(delete, upsert), List.of());
      org.mockito.Mockito.doThrow(new IOException("strict field reader unavailable"))
          .when(harness.fields).getDocumentFieldOrThrow(anyString(), anyString());

      assertFalse(settle(harness));
      verify(harness.queue, never()).removeReplayedSwitchBufferOps(anyList());
    }
  }

  @Test
  void strictPrefixCountUsesTermSetForMoreThanLuceneBooleanClauseLimit() throws Exception {
    String root = absolute("lane-f-large-certified-set");
    String prefix = QueryFilterBuilder.normalizePathPrefix(root);
    String survivor = absoluteUnder(prefix, "survivor.txt");
    String unknown = absoluteUnder(prefix, "unknown.txt");
    try (var fixture = fixture(
        file(survivor, "notes", HASH), chunk(survivor), file(unknown, "notes", HASH))) {
      var harness = harness(fixture, List.of(), List.of());
      var certified = new ArrayList<String>();
      certified.add(survivor);
      for (int i = 0; i < 2049; i++) {
        certified.add(absolute("lane-f-large-certified-set", "synthetic-" + i + ".txt"));
      }

      assertEquals(1, harness.counts.countPathPrefixExcludingAcceptedSurvivorsStrict(
          prefix, certified, List.of()));
      certified.add(unknown);
      assertEquals(0, harness.counts.countPathPrefixExcludingAcceptedSurvivorsStrict(
          prefix, certified, List.of()));
    }
  }

  private static boolean settle(Harness h) {
    return KnowledgeServerMigrationOps.settleCommittedNativeFileWitnesses(
        h.queue, h.runtime, GENERATION, List.of(), LoggerFactory.getLogger(CommittedNativeEffectsTest.class));
  }

  private static Harness harness(LuceneFixture fixture,
      List<SwitchBufferCapableQueue.SwitchBufferOp> selected,
      List<SwitchBufferCapableQueue.SwitchBufferOp> afterRemoval) throws Exception {
    var queue = mock(SwitchBufferCapableQueue.class);
    var runtime = mock(RunningRuntime.class);
    var commits = mock(CommitOps.class);
    var fields = mock(DocumentFieldOps.class);
    var counts = mock(IndexCountOps.class);
    var indexing = mock(IndexingCoordinator.class);
    when(runtime.commitOps()).thenReturn(commits);
    when(runtime.documentFieldOps()).thenReturn(fields);
    when(runtime.indexCountOps()).thenReturn(counts);
    when(runtime.indexingCoordinator()).thenReturn(indexing);
    when(queue.listSwitchBufferOpsStrict()).thenReturn(selected, afterRemoval);
    when(queue.matchesAcceptedFileProjection(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(true);
    when(counts.countPathPrefixExcludingAcceptedSurvivorsStrict(
        anyString(), anyList(), anyList())).thenCallRealMethod();
    when(counts.countCollectionExcludingAcceptedSurvivorsStrict(
        anyString(), anyList(), anyList())).thenCallRealMethod();
    when(counts.countByIdAndChunksStrict(anyString())).thenCallRealMethod();
    when(fields.getDocumentField(anyString(), anyString())).thenAnswer(invocation ->
        fixture.field(invocation.getArgument(0), invocation.getArgument(1)));
    when(fields.getDocumentFieldOrThrow(anyString(), anyString())).thenAnswer(invocation ->
        fixture.field(invocation.getArgument(0), invocation.getArgument(1)));
    when(counts.countQueryOrThrow(any(Query.class))).thenAnswer(invocation ->
        fixture.count(invocation.getArgument(0)));
    return new Harness(queue, runtime, commits, indexing, fields, counts);
  }

  private static SwitchBufferCapableQueue.SwitchBufferOp fileUpsert(
      String path, String collection, String unitRevision, long revision) {
    String payload = new SwitchBufferUpsert(path, collection, (JobQueue.EnqueueProvenance) null,
        unitRevision, HASH).encode();
    return op("path:" + path, "UPSERT", payload, revision);
  }

  private static SwitchBufferCapableQueue.SwitchBufferOp op(
      String key, String kind, String payload, long revision) {
    return new SwitchBufferCapableQueue.SwitchBufferOp(
        GENERATION, key, kind, payload, revision, "v" + revision);
  }

  private static Document file(String path, String collection, String sourceHash) {
    return fileAt(path, path, collection, sourceHash);
  }

  private static Document fileAt(
      String docId, String indexedPath, String collection, String sourceHash) {
    var document = new Document();
    add(document, SchemaFields.DOC_ID, docId);
    add(document, SchemaFields.PATH, indexedPath);
    add(document, SchemaFields.COLLECTION, collection);
    add(document, SchemaFields.IS_CHUNK, "false");
    add(document, SchemaFields.SOURCE_SHA256, sourceHash);
    return document;
  }

  private static Document chunk(String parentPath) {
    return chunk(parentPath, "notes");
  }

  private static Document chunk(String parentPath, String collection) {
    var document = new Document();
    add(document, SchemaFields.DOC_ID, "chunk:owned");
    add(document, SchemaFields.IS_CHUNK, "true");
    add(document, SchemaFields.PARENT_DOC_ID, parentPath);
    add(document, SchemaFields.PATH, parentPath);
    add(document, SchemaFields.COLLECTION, collection);
    return document;
  }

  private static Document projectionChunk(String parentPath) {
    var document = new Document();
    add(document, SchemaFields.DOC_ID, "projection:spoofed");
    add(document, SchemaFields.IS_CHUNK, "true");
    add(document, SchemaFields.PARENT_DOC_ID, parentPath);
    add(document, SchemaFields.PATH, parentPath);
    add(document, SchemaFields.COLLECTION, "notes");
    add(document, SchemaFields.PROJECTION_SOURCE_ID, "memory");
    add(document, SchemaFields.PROJECTION_SOURCE_REVISION, "7");
    add(document, SchemaFields.PROJECTION_DIGEST, "digest");
    return document;
  }

  private static void add(Document document, String name, String value) {
    document.add(new StringField(name, value, Field.Store.YES));
  }

  private static LuceneFixture fixture(Document... documents) throws IOException {
    return new LuceneFixture(List.of(documents));
  }

  private static String absolute(String... parts) {
    Path path = Path.of(System.getProperty("java.io.tmpdir"), parts);
    String value = path.toAbsolutePath().toString();
    return java.io.File.separatorChar == '\\' ? value.toLowerCase(Locale.ROOT) : value;
  }

  private static String absoluteUnder(String normalizedPrefix, String leaf) {
    return normalizedPrefix + leaf;
  }

  private record Harness(
      SwitchBufferCapableQueue queue, RunningRuntime runtime,
      CommitOps commits, IndexingCoordinator indexing,
      DocumentFieldOps fields, IndexCountOps counts) {}

  private static final class LuceneFixture implements AutoCloseable {
    private final Directory directory = new ByteBuffersDirectory();
    private final DirectoryReader reader;
    private final IndexSearcher searcher;

    LuceneFixture(List<Document> documents) throws IOException {
      try (var writer = new IndexWriter(directory, new IndexWriterConfig())) {
        for (Document document : documents) writer.addDocument(document);
        writer.commit();
      }
      reader = DirectoryReader.open(directory);
      searcher = new IndexSearcher(reader);
    }

    int count(Query query) throws IOException {
      return searcher.count(query);
    }

    String field(String docId, String fieldName) throws IOException {
      var hits = searcher.search(new TermQuery(new Term(SchemaFields.DOC_ID, docId)), 1);
      if (hits.scoreDocs.length == 0) return null;
      return searcher.storedFields().document(hits.scoreDocs[0].doc).get(fieldName);
    }

    @Override
    public void close() throws IOException {
      reader.close();
      directory.close();
    }
  }
}
