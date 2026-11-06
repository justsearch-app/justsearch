/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.configuration.resolved.*;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.*;
import java.util.*;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class AliasedHistoryOwnershipTest {
  @TempDir Path temp;
  @ParameterizedTest
  @ValueSource(strings = {"present", "dangling", "removed"})
  void allQueryBranchesFollowStoredSourceAndParentOwnershipWithPresentAndDanglingAliases(String state) throws Exception {
    Path data = Files.createDirectory(temp.resolve("data"));
    Files.writeString(Files.createDirectory(data.resolve("agent-history")).resolve("run.md"), "private");
    Path alias = Files.createSymbolicLink(temp.resolve("alias"), data);
    Path ordinary = Files.writeString(temp.resolve("notes.txt"), "public");
    var previous = ConfigStore.globalOrNull();
    var configured = new ConfigStore(ResolvedConfig.builder().putDefault("justsearch.data.dir", data.toString()).build());
    ConfigStore.setGlobal(configured);
    try (var directory = new ByteBuffersDirectory(); var analyzer = new StandardAnalyzer();
        var writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
      writer.addDocument(row("legacy-parent", alias.resolve("agent-history/run.md").toString(), null, null));
      writer.addDocument(row("legacy-chunk", null, "legacy-parent", null));
      writer.addDocument(row("tagged-parent", null, null, "agent-history"));
      writer.addDocument(row("untagged-child", null, "tagged-parent", null));
      var columnOwned = row("column-history", null, null, null);
      columnOwned.add(new StringField(SchemaFields.COLLECTION, "public", Field.Store.YES));
      columnOwned.add(new SortedDocValuesField(SchemaFields.COLLECTION, new org.apache.lucene.util.BytesRef("agent-history")));
      writer.addDocument(columnOwned);
      writer.addDocument(row("ordinary", ordinary.toString(), null, "public"));
      try (var reader = DirectoryReader.open(writer)) {
        var searcher = new IndexSearcher(reader);
        if (state.equals("dangling")) Files.move(data, temp.resolve("offline-data"));
        if (state.equals("removed")) Files.delete(alias);
        var filters = LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().includeChunks(true).build();
        for (Query query : List.of(QueryFilterBuilder.applyRuntimeFilters(new MatchAllDocsQuery(), filters),
            QueryFilterBuilder.buildFilterQueryOnly(filters), QueryFilterBuilder.buildChunkFilterQuery(filters))) {
          var hits = searcher.search(query, 10);
          var ownership = new IndexReadOwnership(searcher);
          var visible = new ArrayList<Integer>();
          for (var hit : hits.scoreDocs) {
            if (!io.justsearch.configuration.ReadOwnership.hiddenFromDefault(ownership.owner(hit.doc))) visible.add(hit.doc);
          }
          assertEquals(1, visible.size(), state + ": " + query);
          assertEquals("ordinary", searcher.storedFields().document(visible.getFirst()).get(SchemaFields.DOC_ID));
        }
      }
    } finally { ConfigStore.restoreGlobal(configured, previous); }
  }
  private static Document row(String id, String path, String parent, String collection) {
    var doc = new Document();
    doc.add(new StringField(SchemaFields.DOC_ID, id, Field.Store.YES));
    if (path != null) doc.add(new StringField(SchemaFields.PATH, path, Field.Store.YES));
    if (parent != null) doc.add(new StringField(SchemaFields.PARENT_DOC_ID, parent, Field.Store.YES));
    if (collection != null) {
      doc.add(new StringField(SchemaFields.COLLECTION, collection, Field.Store.YES));
      doc.add(new SortedDocValuesField(SchemaFields.COLLECTION, new org.apache.lucene.util.BytesRef(collection)));
    }
    return doc;
  }
}
