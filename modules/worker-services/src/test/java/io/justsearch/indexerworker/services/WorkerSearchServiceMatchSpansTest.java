package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchQuerySyntax;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerSearchServiceMatchSpansTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  @Test
  void searchIncludesPreciseMatchSpansForContentPreview() throws Exception {
    FieldCatalogDef catalog =
        new FieldCatalogDef(
            "test",
            List.of(
                new FieldCatalogDef.FieldDef(
                    SchemaFields.DOC_ID,
                    "keyword",
                    true,
                    true,
                    List.of("id", "sort"),
                    null,
                    null,
                    false),
                new FieldCatalogDef.FieldDef(
                    SchemaFields.DOC_UID,
                    "keyword",
                    false,
                    true,
                    List.of("sort", "tiebreak"),
                    null,
                    null,
                    false),
                new FieldCatalogDef.FieldDef(
                    SchemaFields.TITLE, "text", true, false, List.of("highlight"), null, "icu", false),
                new FieldCatalogDef.FieldDef(
                    SchemaFields.CONTENT, "text", true, false, List.of("highlight"), null, "icu", false),
                new FieldCatalogDef.FieldDef(
                    SchemaFields.CONTENT_PREVIEW,
                    "text",
                    true,
                    false,
                    List.of("highlight"),
                    null,
                    "icu",
                    false)));

    var lifecycle = IndexSchema.fromCatalog(catalog).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    var runtime = lifecycle;

    String q = "needle";
    String preview = "prefix " + q + " suffix";

    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.TITLE, "Title " + q,
                SchemaFields.CONTENT, preview,
                SchemaFields.CONTENT_PREVIEW, preview)));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    var service = new WorkerSearchService(lifecycle);

    SearchResponse response =
        service.search(
            SearchRequest.newBuilder()
                .setQuery(q)
                .setLimit(10)
                .setMode(SearchMode.SEARCH_MODE_TEXT)
                .build(),
            CallContext.none());
    assertNotNull(response);
    assertTrue(response.getResultsCount() >= 1, "Expected at least one hit");

    var hit = response.getResults(0);
    assertTrue(hit.getMatchSpansCount() > 0, "Expected matchSpans to be present");

    boolean found =
        hit.getMatchSpansList().stream()
            .anyMatch(
                s ->
                    SchemaFields.CONTENT_PREVIEW.equals(s.getField())
                        && s.getStartChar() >= 0
                        && s.getEndChar() > s.getStartChar()
                        && preview.substring(s.getStartChar(), s.getEndChar())
                            .toLowerCase(Locale.ROOT)
                            .contains(q));
    assertTrue(found, "Expected at least one content_preview match span for the query term");

    lifecycle.close();
  }

  @Test
  void lucenePhraseQuerySpansOnlyCoverPhraseOccurrence() throws Exception {
    FieldCatalogDef catalog =
        new FieldCatalogDef(
            "test",
            List.of(
                new FieldCatalogDef.FieldDef(
                    SchemaFields.DOC_ID,
                    "keyword",
                    true,
                    true,
                    List.of("id", "sort"),
                    null,
                    null,
                    false),
                new FieldCatalogDef.FieldDef(
                    SchemaFields.DOC_UID,
                    "keyword",
                    false,
                    true,
                    List.of("sort", "tiebreak"),
                    null,
                    null,
                    false),
                new FieldCatalogDef.FieldDef(
                    SchemaFields.TITLE, "text", true, false, List.of("highlight"), null, "icu", false),
                new FieldCatalogDef.FieldDef(
                    SchemaFields.CONTENT, "text", true, false, List.of("highlight"), null, "icu", false),
                new FieldCatalogDef.FieldDef(
                    SchemaFields.CONTENT_PREVIEW,
                    "text",
                    true,
                    false,
                    List.of("highlight"),
                    null,
                    "icu",
                    false)));

    var lifecycle = IndexSchema.fromCatalog(catalog).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    var runtime = lifecycle;

    String preview = "hello one hello world two world";
    int phraseStart = preview.indexOf("hello world");
    int phraseEnd = phraseStart + "hello world".length();

    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.TITLE, "Title hello world",
                SchemaFields.CONTENT, preview,
                SchemaFields.CONTENT_PREVIEW, preview)));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    var service = new WorkerSearchService(lifecycle);

    SearchResponse response =
        service.search(
            SearchRequest.newBuilder()
                .setQuery("\"hello world\"")
                .setQuerySyntax(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE)
                .setLimit(10)
                .setMode(SearchMode.SEARCH_MODE_TEXT)
                .build(),
            CallContext.none());
    assertNotNull(response);
    assertTrue(response.getResultsCount() >= 1, "Expected at least one hit");

    var hit = response.getResults(0);
    var spans =
        hit.getMatchSpansList().stream()
            .filter(s -> SchemaFields.CONTENT_PREVIEW.equals(s.getField()))
            .toList();
    assertTrue(spans.size() > 0, "Expected content_preview match spans for phrase query");

    boolean allWithinPhrase =
        spans.stream()
            .allMatch(
                s ->
                    s.getStartChar() >= phraseStart
                        && s.getEndChar() <= phraseEnd
                        && s.getEndChar() > s.getStartChar());
    assertTrue(allWithinPhrase, "Expected phrase-query spans to be confined to the phrase occurrence");

    lifecycle.close();
  }

  @Test
  void luceneBooleanQuerySpansIncludeBothTerms() throws Exception {
    FieldCatalogDef catalog =
        new FieldCatalogDef(
            "test",
            List.of(
                new FieldCatalogDef.FieldDef(
                    SchemaFields.DOC_ID,
                    "keyword",
                    true,
                    true,
                    List.of("id", "sort"),
                    null,
                    null,
                    false),
                new FieldCatalogDef.FieldDef(
                    SchemaFields.DOC_UID,
                    "keyword",
                    false,
                    true,
                    List.of("sort", "tiebreak"),
                    null,
                    null,
                    false),
                new FieldCatalogDef.FieldDef(
                    SchemaFields.TITLE, "text", true, false, List.of("highlight"), null, "icu", false),
                new FieldCatalogDef.FieldDef(
                    SchemaFields.CONTENT, "text", true, false, List.of("highlight"), null, "icu", false),
                new FieldCatalogDef.FieldDef(
                    SchemaFields.CONTENT_PREVIEW,
                    "text",
                    true,
                    false,
                    List.of("highlight"),
                    null,
                    "icu",
                    false)));

    var lifecycle = IndexSchema.fromCatalog(catalog).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    var runtime = lifecycle;

    String preview = "foo x bar y foo";
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.TITLE, "Title foo bar",
                SchemaFields.CONTENT, preview,
                SchemaFields.CONTENT_PREVIEW, preview)));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    var service = new WorkerSearchService(lifecycle);

    SearchResponse response =
        service.search(
            SearchRequest.newBuilder()
                .setQuery("foo AND bar")
                .setQuerySyntax(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE)
                .setLimit(10)
                .setMode(SearchMode.SEARCH_MODE_TEXT)
                .build(),
            CallContext.none());
    assertNotNull(response);
    assertTrue(response.getResultsCount() >= 1, "Expected at least one hit");

    var hit = response.getResults(0);
    var spans =
        hit.getMatchSpansList().stream()
            .filter(s -> SchemaFields.CONTENT_PREVIEW.equals(s.getField()))
            .toList();
    assertTrue(spans.size() > 0, "Expected content_preview match spans for boolean query");

    boolean hasFoo =
        spans.stream()
            .anyMatch(
                s ->
                    preview.substring(s.getStartChar(), s.getEndChar())
                        .toLowerCase(Locale.ROOT)
                        .contains("foo"));
    boolean hasBar =
        spans.stream()
            .anyMatch(
                s ->
                    preview.substring(s.getStartChar(), s.getEndChar())
                        .toLowerCase(Locale.ROOT)
                        .contains("bar"));
    assertTrue(hasFoo && hasBar, "Expected spans to include both foo and bar matches");

    lifecycle.close();
  }
}
