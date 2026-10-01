/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.justsearch.app.api.knowledge.KnowledgeSearchResponseBuilder;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponseHitBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 915 B6b — the Head half of feedback identity, which is a property of the DERIVED label
 * store, not of a Lucene index.
 *
 * <p>Named for what it proves after tempdoc 931 §C.4. It used to be called
 * {@code LabelStoreSurvivesRebuildTest}, which read as a claim about an index rebuild — and nothing
 * here rebuilds one: it deletes the derived label file and re-projects it from the authored NDJSON
 * stores. That is a real and necessary property (the authored stores outlive the index generation,
 * and re-projection is key-stable), but it is not the Blue→Green claim, and a test whose name
 * overstates its subject is how a contract row ends up believed to be covered when it is not.
 *
 * <p>The index-rebuild half is
 * {@code DocumentIdentityBootImportTest.blueUidIsImportedBeforePausedMigrationReindexesIntoGreen},
 * which drives a real Worker through a Blue→Green migration and reads the surviving {@code doc_uid}
 * back off the production in-process port search response — the exact value this projection keys on.
 */
class LabelStoreRegenerationKeepsUidKeysTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void regeneratingTheDerivedLabelStoreReprojectsTheSameUidKeys(@TempDir Path dataDir)
      throws Exception {
    String uidPreservedByWorkerRebuild = "2c88bc44-7d55-4e72-a348-39e0de07c551";
    String uidShownAcrossRebuild = "8f97be31-42a5-409a-874c-68a9707340b1";
    var responseBeforeRebuild =
        KnowledgeSearchResponseBuilder.builder()
            .results(
                List.of(
                    KnowledgeSearchResponseHitBuilder.builder()
                        .id("C:/docs/before.md")
                        .fields(Map.of("doc_uid", uidPreservedByWorkerRebuild))
                        .build(),
                    KnowledgeSearchResponseHitBuilder.builder()
                        .id("C:/docs/shown.md")
                        .fields(Map.of("doc_uid", uidShownAcrossRebuild))
                        .build()))
            .build();
    FeatureSnapshot snapshot =
        FeatureSnapshots.capture("iid-rebuild", "q", 1L, responseBeforeRebuild);
    assertEquals(uidPreservedByWorkerRebuild, snapshot.hits().getFirst().docId());

    Path feedback = dataDir.resolve("feedback");
    new NdjsonAppendStore<>(feedback.resolve("feature-snapshots.ndjson"), FeatureSnapshot.class)
        .append(snapshot);
    new NdjsonAppendStore<>(feedback.resolve("result-dispositions.ndjson"), ResultDisposition.class)
        .append(
            new ResultDisposition(
                "iid-rebuild",
                uidPreservedByWorkerRebuild,
                ResultDisposition.Kind.OPENED,
                ResultDisposition.Contributor.SEARCH_INTERACTION,
                2L));

    LabelProjection.Result first = FeedbackLabels.rebuild(dataDir);
    List<String> firstKeys = projectedDocIds(dataDir);
    Files.delete(FeedbackLabels.realLabelPath(dataDir));

    // Re-projecting from the authored stores alone, with the derived file deleted: no index is
    // involved, which is the point — Head never touches Lucene, so the only thing it can prove is
    // that its own projection is key-stable. Whether the UID itself survives a Blue→Green rebuild
    // is asserted where the rebuild happens (see this class's javadoc).
    LabelProjection.Result afterRegeneration = FeedbackLabels.rebuild(dataDir);

    assertEquals(first, afterRegeneration);
    assertEquals(List.of(uidPreservedByWorkerRebuild, uidShownAcrossRebuild), firstKeys);
    assertEquals(firstKeys, projectedDocIds(dataDir));
    assertFalse(
        Files.readString(FeedbackLabels.realLabelPath(dataDir), StandardCharsets.UTF_8)
            .contains("C:/docs/before.md"));
  }

  private static List<String> projectedDocIds(Path dataDir) throws Exception {
    return Files.readAllLines(FeedbackLabels.realLabelPath(dataDir), StandardCharsets.UTF_8).stream()
        .filter(line -> !line.isBlank())
        .map(
            line -> {
              try {
                return MAPPER.readTree(line).get("doc_id").asText();
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            })
        .sorted()
        .toList();
  }
}
