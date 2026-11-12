/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.fixtures.TestDocumentBuilder;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.rag.ChunkDocumentWriter;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.VduUpdateOutcome;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Every write path labels the finally stored content from its main text, not its opening. */
class ContentLanguageBodyWritePathsTest extends LuceneExecutorTestBase {
  private static final String MENUS = ContentLanguageBodySampleTest.ENGLISH_MENUS;
  @TempDir Path directory;

  private static String body(String language, int chars) {
    return ContentLanguageBodySampleTest.body(language, chars);
  }

  private static String page(String language, int chars) {
    return MENUS + body(language, chars);
  }

  private static String markdown(String language, int chars) {
    return "---\ntitle: Annual report\nsummary: " + MENUS.replace("\n", " ") + "\n---\n"
        + "# Annual report of the public library\n\n" + MENUS.replace("\n", "\n\n")
        + body(language, chars).replace("\n", "\n\n");
  }

  private RunningRuntime open() {
    var runtime = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(directory.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();
    return runtime;
  }

  private static void refresh(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  /** First indexing, a file update (artifact reuse allowed) or a forced re-index (no reuse). */
  private String index(RunningRuntime runtime, String content, boolean markdown, boolean forced) {
    Path source = directory.resolve(markdown ? "document.md" : "document.txt");
    var extraction = new ExtractionResult(content, null, markdown ? "text/markdown" : "text/plain");
    var metadata = IndexingDocumentOps.deriveParentMetadata(source, extraction, null, null);
    var plan = IndexingDocumentOps.chunkPlan(source, extraction, metadata);
    var document = TestDocumentBuilder.buildDocument(source, extraction, "documents", null, metadata);
    var chunks = IndexingDocumentOps.buildDeclaredChunks(source, extraction, metadata, "documents",
        (String) document.fields().get(SchemaFields.DOC_UID), false, plan, Map.of());
    runtime.indexingCoordinator().replaceTextFamily(
        IndexingDocumentOps.withChunkDeclaration(document, plan), chunks, !forced);
    refresh(runtime);
    return (String) document.fields().get(SchemaFields.DOC_ID);
  }

  private static Set<String> family(RunningRuntime runtime, String id, String language) {
    assertEquals(language, runtime.documentFieldOps().getDocumentField(id, SchemaFields.LANGUAGE), "parent");
    var children = runtime.documentFieldOps().queryDocIdsByField(SchemaFields.PARENT_DOC_ID, id, 1000);
    for (String child : children) {
      assertEquals(language, runtime.documentFieldOps().getDocumentField(child, SchemaFields.LANGUAGE), child);
    }
    return Set.copyOf(children);
  }

  /** The stored preview is still the opening of the plain text, whatever the label. */
  private static void assertPlainPreview(RunningRuntime runtime, String id, String content) {
    String stripped = content.strip();
    assertEquals(stripped.substring(0, Math.min(4096, stripped.length())),
        runtime.documentFieldOps().getDocumentField(id, SchemaFields.CONTENT_PREVIEW));
  }

  private static void vdu(RunningRuntime runtime, String id, VduUpdateOutcome outcome, String text,
      boolean incomplete) throws Exception {
    String existing = runtime.documentFieldOps().getDocumentContent(id);
    assertTrue(VduResultWriter.apply(runtime, UpdateVduResultRequest.newBuilder().setDocId(id)
        .setOutcome(outcome).setExtractedContent(text).setIncompleteCoverage(incomplete).build(),
        false, Map.of(SchemaFields.CONTENT, existing), 5));
    refresh(runtime);
  }

  private static void replace(RunningRuntime runtime, String id, String text) throws Exception {
    vdu(runtime, id, VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT, text, false);
  }

  @ParameterizedTest
  @ValueSource(strings = {"first", "update", "forced", "vdu", "markdown-first", "markdown-vdu"})
  void r4EveryWriterLabelsTheBodyNotTheOpening(String path) throws Exception {
    boolean markdown = path.startsWith("markdown");
    String content = markdown ? markdown("de", 20_000) : page("de", 20_000);
    assertTrue(MENUS.length() > 4096);
    try (var runtime = open()) {
      String id = path.endsWith("first") ? index(runtime, content, markdown, false)
          : index(runtime, body("fr", 20_000), markdown, false);
      if (!path.endsWith("first")) {
        assertFalse(family(runtime, id, "fr").isEmpty());
        switch (path) {
          case "update" -> index(runtime, content, markdown, false);
          case "forced" -> index(runtime, content, markdown, true);
          default -> replace(runtime, id, content);
        }
      }
      assertEquals(content, runtime.documentFieldOps().getDocumentContent(id));
      assertTrue(family(runtime, id, "de").size() > 1);
      if (!markdown) assertPlainPreview(runtime, id, content);
    }
  }

  @Test
  void r4StandaloneRegenerationLabelsPassagesFromStoredContentAndLeavesTheParentLabel() throws Exception {
    String content = page("nl", 20_000);
    try (var runtime = open()) {
      String id = index(runtime, content, false, false);
      runtime.indexingCoordinator().updateDocument(id, Map.of(SchemaFields.LANGUAGE, "fr"));
      refresh(runtime);
      assertTrue(ChunkDocumentWriter.regenerateChunksFromExistingParent(runtime.documentFieldOps(),
          runtime.indexingCoordinator(), id, runtime.documentFieldOps().getDocumentContent(id), false) > 1);
      refresh(runtime);
      assertEquals("fr", runtime.documentFieldOps().getDocumentField(id, SchemaFields.LANGUAGE));
      var children = runtime.documentFieldOps().queryDocIdsByField(SchemaFields.PARENT_DOC_ID, id, 1000);
      assertTrue(children.size() > 1);
      for (String child : children) {
        assertEquals("nl", runtime.documentFieldOps().getDocumentField(child, SchemaFields.LANGUAGE), child);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void r4LabelTransitionsHoldAcrossUpdateForcedReindexVduAndReopen(boolean markdown) throws Exception {
    String id;
    Set<String> passages;
    try (var runtime = open()) {
      id = index(runtime, markdown ? markdown("de", 40_000) : page("de", 40_000), markdown, false);
      Set<String> german = family(runtime, id, "de");
      assertTrue(german.size() > 1);
      index(runtime, markdown ? markdown("fr", 30_000) : page("fr", 30_000), markdown, false);
      Set<String> french = family(runtime, id, "fr");
      assertTrue(french.size() > 1 && french.size() < german.size());
      String dutch = markdown ? markdown("nl", 20_000) : page("nl", 20_000);
      index(runtime, dutch, markdown, true);
      passages = family(runtime, id, "nl");
      assertTrue(passages.size() > 1 && passages.size() < french.size());
      if (!markdown) assertPlainPreview(runtime, id, dutch);
    }
    try (var runtime = open()) {
      assertEquals(passages, family(runtime, id, "nl"));
      String german = page("de", 18_000);
      replace(runtime, id, german);
      passages = family(runtime, id, "de");
      assertTrue(passages.size() > 1);
      assertPlainPreview(runtime, id, german);
    }
    try (var runtime = open()) {
      assertEquals(passages, family(runtime, id, "de"));
      replace(runtime, id, "1234567890");
      assertTrue(family(runtime, id, null).isEmpty());
    }
    try (var runtime = open()) {
      assertTrue(family(runtime, id, null).isEmpty());
    }
  }

  @Test
  void r4IncompleteCoverageLabelsTheCombinedTextAndNonReplacementKeepsIt() throws Exception {
    String id;
    try (var runtime = open()) {
      id = index(runtime, page("en", 2_000), false, false);
      assertFalse(family(runtime, id, "en").isEmpty());
      vdu(runtime, id, VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT, body("de", 25_000), true);
      String stored = runtime.documentFieldOps().getDocumentContent(id);
      assertTrue(stored.startsWith(MENUS) && stored.length() > 25_000);
      assertTrue(family(runtime, id, "de").size() > 1);
      assertPlainPreview(runtime, id, stored);
      for (var outcome : new VduUpdateOutcome[] {VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY,
          VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED, VduUpdateOutcome.VDU_UPDATE_OUTCOME_REJECTED_SUSPECT_TEXT}) {
        vdu(runtime, id, outcome, page("en", 20_000), false);
        assertEquals(stored, runtime.documentFieldOps().getDocumentContent(id));
        assertTrue(family(runtime, id, "de").size() > 1, outcome.name());
      }
    }
    try (var runtime = open()) {
      assertTrue(family(runtime, id, "de").size() > 1);
    }
  }
}
