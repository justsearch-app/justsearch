/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.QuerySyntax;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import java.util.ArrayList;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real indexes; old signatures and a SIMPLE fallback let these assertions run before the change. */
final class SearchSyntaxRetrievalTest extends RuntimeTestBase {
  private RunningRuntime runtime;

  private static QuerySyntax searchSyntax() {
    try {
      return QuerySyntax.valueOf("SEARCH");
    } catch (IllegalArgumentException beforeSubset) {
      return QuerySyntax.SIMPLE;
    }
  }

  @BeforeEach
  void open() throws Exception {
    Path config = writeConfig("app:\n  data_dir: '" + dataDir().toString().replace('\\', '/')
        + "'\nindex:\n  collections:\n    - name: syntax\n      roots: ['ignored']\nvector:\n  dimension: 4\n");
    System.setProperty("justsearch.config", config.toString());
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(4).fields());
    fields.add(new FieldDef("author", "text", true, false, List.of("highlight"), null, "icu", false));
    runtime = IndexSchema.fromCatalog(new FieldCatalogDef("search-syntax-test", fields)).ephemeral()
        .withExecutorRegistrations(testLuceneExecutors()).open();
    index("phrase", "budget review foo", "budget", "Ada Lovelace");
    index("apart", "budget annual review bar", "budget", "Ada Byron");
    index("reversed", "review budget", "forecast", "Grace Hopper");
    index("split", "review", "budget", "Ada Lovelace");
    index("titlePhrase", "ordinary", "budget review", "NOT");
    index("crossExcluded", "bar", "foo", "Grace Hopper");
    index("safeFoo", "foo red blue pen", "ordinary", "Grace Hopper");
    index("redPhrase", "foo red pen", "ordinary", "Grace Hopper");
    index("booleanPhrase", "Research AND Development", "ordinary", "NOT");
    index("literal", "anchor phrase foo e-mail -5 degrees 10:30 C:\\docs draft +foo path:x a TO b", "budget", "Ada");
    index("prefix", "anchor phrase foobar", "other", "Nobody");
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  @AfterEach
  void close() {
    if (runtime != null) runtime.close();
  }

  private void index(String id, String content, String title, String author) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, id + "#0", SchemaFields.PATH, "/" + id,
        SchemaFields.CONTENT, content, SchemaFields.TITLE, title, SchemaFields.AUTHOR, author)));
  }

  private Set<String> ids(String query, QuerySyntax syntax) throws Exception {
    var built = runtime.textQueryOps().buildTextQuery(query, null, syntax);
    return runtime.readPathOps().search(built, 100, Set.of(SchemaFields.PATH),
        LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE, "").hits().stream()
        .map(hit -> hit.docId()).collect(Collectors.toSet());
  }

  @Test
  void x1PhraseStaysWithinOneField() throws Exception {
    assertEquals(Set.of("phrase", "titlePhrase"), ids("\"budget review\"", searchSyntax()));
  }

  @Test
  void x2ExclusionsApplyAcrossFieldsAndPhrases() throws Exception {
    assertEquals(Set.of("phrase", "safeFoo", "redPhrase", "literal"), ids("foo -bar", searchSyntax()));
    assertEquals(Set.of("phrase", "crossExcluded", "safeFoo", "literal"), ids("foo -\"red pen\"", searchSyntax()));
  }

  @Test
  void x3RequiredClausesComposeAcrossFieldsWithOptionalWords() throws Exception {
    assertEquals(Set.of("phrase", "titlePhrase"), ids("foo \"budget review\"", searchSyntax()));
    assertEquals(Set.of("phrase", "apart", "split", "literal"), ids("title:budget author:Ada", searchSyntax()));
    assertEquals(Set.of("phrase", "apart", "split", "titlePhrase", "literal"), ids("title:budget foo", searchSyntax()));
    assertEquals(Set.of("phrase", "split"), ids("author:\"Ada Lovelace\"", searchSyntax()));
    assertEquals(Set.of("booleanPhrase"), ids("\"Research AND Development\"", searchSyntax()));
    assertEquals(Set.of("titlePhrase", "booleanPhrase"), ids("author:\"NOT\"", searchSyntax()));
    assertEquals(Set.of("titlePhrase", "booleanPhrase"), ids("author:NOT", searchSyntax()));
  }

  @Test
  void x4UnsupportedPunctuationIsLiteralInsideAdvancedQueries() throws Exception {
    for (String literal : List.of("e-mail", "-5 degrees", "10:30", "C:\\docs", "foo*", "(draft)",
        "+foo", "path:x", "[a TO b]", "foo -", "\\\"foo\\\"", "\\-foo", "C:\\\\docs")) {
      // No prefix expansion or wildcard/range/field parsing is admitted in the advanced branch.
      Set<String> matched = ids(literal + " -absent", searchSyntax());
      assertTrue(matched.contains("literal"), literal + " must be analyzed as literal text");
      assertFalse(matched.contains("prefix"), literal + " must not act as an explicit or implicit wildcard");
    }
    assertEquals(Set.of("literal", "prefix"), ids("\"anchor phrase\" path:x", searchSyntax()));
    assertEquals(Set.of("phrase", "titlePhrase"), ids("\"budget review\" path:x", searchSyntax()));
    assertEquals(Set.of("phrase", "apart", "split", "titlePhrase", "literal"), ids("title:budget foo*", searchSyntax()));
    assertEquals(Set.of("phrase", "apart", "split", "titlePhrase", "literal"), ids("title:budget (draft)", searchSyntax()));
  }

  @Test
  void x6InvalidQueriesFailWithSpecificReasons() {
    for (String query : List.of("\"", "\"\"", "title:", "author:", "-foo", "-\"red pen\"",
        "\"a b\" OR c", "\"budget review\" AND forecast", "\"budget review\" NOT forecast")) {
      var exception = assertThrows(IllegalArgumentException.class,
          () -> runtime.textQueryOps().buildTextQuery(query, null, searchSyntax()), query);
      assertFalse(exception.getMessage().isBlank());
      if (query.contains(" OR ") || query.contains(" AND ") || query.contains(" NOT ")) {
        assertEquals("OR, AND and NOT can't be combined with quotes, - or title:/author:", exception.getMessage());
      }
    }
  }

  @Test
  void x7PlainSearchEqualsHistoricalSimpleAndRawLuceneStillWorks() throws Exception {
    for (String query : List.of("budget review", "bud", "foo OR bar", "foo AND bar", "foo NOT bar",
        "e-mail", "-5 degrees", "10:30", "C:\\docs", "foo*", "(draft)", "+foo", "path:x", "[a TO b]", "foo -")) {
      assertEquals(ids(query, QuerySyntax.SIMPLE), ids(query, searchSyntax()), query);
      assertEquals(runtime.textQueryOps().buildTextQuery(query, null, QuerySyntax.SIMPLE),
          runtime.textQueryOps().buildTextQuery(query, null, searchSyntax()), query);
    }
    assertEquals(Set.of("phrase", "titlePhrase"), ids("\"budget review\"", QuerySyntax.LUCENE));
    assertEquals(11, ids("*:*", QuerySyntax.LUCENE).size());
  }
}
