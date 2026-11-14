/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyHelpExclusionTest {
  @TempDir Path temp;

  @Test
  void untaggedHelpParentsAndChunksAreExcludedByEachStoredSourceIdentity() throws Exception {
    Path ssot = Files.createDirectory(temp.resolve("ssot"));
    Path help = Files.createDirectories(ssot.resolve("docs/help"));
    var previous = ConfigStore.globalOrNull();
    var configured = new ConfigStore(ResolvedConfig.builder()
        .putDefault("justsearch.ssot.path", ssot.toString()).build());
    ConfigStore.setGlobal(configured);
    try (var directory = new ByteBuffersDirectory(); var analyzer = new StandardAnalyzer();
        var writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
      for (String identity : List.of(SchemaFields.PATH, SchemaFields.DOC_ID, SchemaFields.PARENT_DOC_ID)) {
        var doc = new Document();
        doc.add(new StringField(identity, help.resolve(identity + ".md").toString(), Field.Store.NO));
        if (identity.equals(SchemaFields.PARENT_DOC_ID)) {
          doc.add(new StringField(SchemaFields.DOC_ID, "chunk:legacy", Field.Store.NO));
          doc.add(new StringField(SchemaFields.IS_CHUNK, "true", Field.Store.NO));
        }
        writer.addDocument(doc);
      }
      var ordinary = new Document();
      ordinary.add(new StringField(SchemaFields.PATH,
          ssot.resolve("docs/helpful/own.md").toString(), Field.Store.NO));
      writer.addDocument(ordinary);
      try (var reader = DirectoryReader.open(writer)) {
        var searcher = new IndexSearcher(reader);
        var included = LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().includeChunks(true).build();
        var excluded = included.withExcludedCollections(List.of("justsearch-help"));
        assertEquals(4, searcher.count(QueryFilterBuilder.buildFilterQueryOnly(included)),
            "the opt-in exclusion must preserve other callers");
        for (Query query : List.of(
            QueryFilterBuilder.applyRuntimeFilters(new MatchAllDocsQuery(), excluded),
            QueryFilterBuilder.buildFilterQueryOnly(excluded),
            QueryFilterBuilder.buildChunkFilterQuery(excluded))) {
          assertEquals(1, searcher.count(query), "legacy identity must bind before retrieval: " + query);
        }
      }
    } finally { ConfigStore.restoreGlobal(configured, previous); }
  }
}
