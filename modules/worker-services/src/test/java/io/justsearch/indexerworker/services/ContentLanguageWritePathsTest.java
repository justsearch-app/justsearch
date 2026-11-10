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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContentLanguageWritePathsTest extends LuceneExecutorTestBase {
  private static final String ENGLISH = "The public library opens every morning and welcomes readers from the surrounding villages. The collection includes books about history, science and the natural world. ";
  private static final String GERMAN = "Die öffentliche Bibliothek öffnet jeden Morgen und begrüßt Leser aus den umliegenden Dörfern. Die Sammlung enthält Bücher über Geschichte, Wissenschaft und die natürliche Welt. ";
  @TempDir Path directory;

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

  private String index(RunningRuntime runtime, String content, boolean markdown) {
    Path source = directory.resolve(markdown ? "document.md" : "document.txt");
    var extraction = new ExtractionResult(content, null, markdown ? "text/markdown" : "text/plain");
    var metadata = IndexingDocumentOps.deriveParentMetadata(source, extraction, null, null);
    var plan = IndexingDocumentOps.chunkPlan(source, extraction, metadata);
    var document = TestDocumentBuilder.buildDocument(source, extraction, "documents", null, metadata);
    runtime.indexingCoordinator().indexSingle(IndexingDocumentOps.withChunkDeclaration(document, plan));
    IndexingDocumentOps.indexDeclaredChunks(source, extraction, runtime.indexingCoordinator(), metadata,
        "documents", (String) document.fields().get(SchemaFields.DOC_UID), false, plan);
    refresh(runtime);
    return (String) document.fields().get(SchemaFields.DOC_ID);
  }

  private static int family(RunningRuntime runtime, String id, String language) {
    assertEquals(language, runtime.documentFieldOps().getDocumentField(id, SchemaFields.LANGUAGE));
    var children = runtime.documentFieldOps().queryDocIdsByField(SchemaFields.PARENT_DOC_ID, id, 1000);
    for (String child : children) {
      assertEquals(language, runtime.documentFieldOps().getDocumentField(child, SchemaFields.LANGUAGE), child);
    }
    return children.size();
  }

  private static void replace(RunningRuntime runtime, String id, String text, boolean incomplete) throws Exception {
    String existing = runtime.documentFieldOps().getDocumentContent(id);
    assertTrue(VduResultWriter.apply(runtime, UpdateVduResultRequest.newBuilder().setDocId(id)
        .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT).setExtractedContent(text)
        .setIncompleteCoverage(incomplete).build(), false, Map.of(SchemaFields.CONTENT, existing), 5));
    refresh(runtime);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void indexingUpdateVduRegenerationAndReopenAgreeAndRetireObsoletePassages(boolean markdown) throws Exception {
    String id;
    int fewer;
    String prefix = markdown ? "---\ntitle: " + ENGLISH.repeat(50) + "\nsummary: " + GERMAN + "\n---\n# " + ENGLISH + "\n\n" : "";
    try (var runtime = open()) {
      id = index(runtime, ENGLISH.repeat(200), markdown);
      int oldCount = family(runtime, id, "en");
      assertTrue(oldCount > 1);
      assertEquals(id, index(runtime, prefix + GERMAN.repeat(100), markdown));
      int updatedCount = family(runtime, id, "de");
      assertTrue(updatedCount > 1);
      replace(runtime, id, prefix + GERMAN.repeat(40), false);
      fewer = family(runtime, id, "de");
      assertTrue(fewer > 1 && fewer < updatedCount);
      ChunkDocumentWriter.regenerateChunksFromExistingParent(runtime.documentFieldOps(),
          runtime.indexingCoordinator(), id, runtime.documentFieldOps().getDocumentContent(id), false);
      refresh(runtime);
      assertEquals(fewer, family(runtime, id, "de"));
    }
    try (var runtime = open()) {
      assertEquals(fewer, family(runtime, id, "de"));
      replace(runtime, id, GERMAN, false);
      assertEquals(0, family(runtime, id, "de"));
    }
    try (var runtime = open()) {
      assertEquals(0, family(runtime, id, "de"));
      replace(runtime, id, "1234567890", false);
      assertEquals(0, family(runtime, id, null));
    }
    try (var runtime = open()) {
      assertEquals(0, family(runtime, id, null));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"update", "vdu", "markdown-vdu", "regenerate"})
  void eachWriterLabelsItsOwnText(String path) throws Exception {
    boolean markdown = path.equals("markdown-vdu");
    try (var runtime = open()) {
      String id = index(runtime, GERMAN.repeat(100), markdown);
      // Give the old implementation the correct pre-existing parent label, so each
      // writer is tested independently of the first-indexing detector.
      runtime.indexingCoordinator().updateDocument(id, Map.of(SchemaFields.LANGUAGE, "de"));
      refresh(runtime);
      if (path.equals("update")) {
        index(runtime, GERMAN.repeat(100), markdown);
      } else if (path.equals("regenerate")) {
        ChunkDocumentWriter.regenerateChunksFromExistingParent(runtime.documentFieldOps(),
            runtime.indexingCoordinator(), id, GERMAN.repeat(100), false);
        refresh(runtime);
      } else {
        String prefix = markdown ? "---\ntitle: " + ENGLISH.repeat(50) + "\nsummary: " + GERMAN
            + "\n---\n# " + ENGLISH + "\n\n" : "";
        replace(runtime, id, prefix + GERMAN.repeat(100), false);
      }
      assertTrue(family(runtime, id, "de") > 1);
    }
  }

  @Test
  void incompleteCoverageLabelsFinallyStoredWindowAndNonReplacementRetainsIt() throws Exception {
    String id;
    try (var runtime = open()) {
      id = index(runtime, GERMAN.repeat(100), false);
      replace(runtime, id, ENGLISH.repeat(20), true);
      assertTrue(family(runtime, id, "de") > 1);
      String stored = runtime.documentFieldOps().getDocumentContent(id);
      for (var outcome : new VduUpdateOutcome[] {VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY,
          VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED, VduUpdateOutcome.VDU_UPDATE_OUTCOME_REJECTED_SUSPECT_TEXT}) {
        assertTrue(VduResultWriter.apply(runtime, UpdateVduResultRequest.newBuilder().setDocId(id)
            .setOutcome(outcome).setExtractedContent(ENGLISH).build(), false, Map.of(), 5));
        refresh(runtime);
        assertEquals(stored, runtime.documentFieldOps().getDocumentContent(id));
        assertTrue(family(runtime, id, "de") > 1);
      }
    }
    try (var runtime = open()) {
      assertTrue(family(runtime, id, "de") > 1);
    }
  }
}
