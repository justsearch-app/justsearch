/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeGenerationPromotionTest {
  @TempDir Path temp;

  @Test
  void nativeCandidatePersistsExplicitSourceSetBeforePointer() throws Exception {
    Path base = temp.resolve("source-set");
    var manager = new IndexGenerationManager(base);
    String blue = manager.initializeOrLoad().state().active_generation();
    var created = manager.startMigration("manual", List.of("source-b", "source-a"));
    var reopened = new IndexGenerationManager(base);
    assertEquals(blue, reopened.readStateBestEffort().active_generation());
    assertEquals(created.building_generation(), reopened.readStateBestEffort().building_generation());
    assertEquals(List.of("source-a", "source-b"), reopened.manifestForOwnedPath(
        reopened.resolveGenerationPathStrict(created.building_generation())).projection_source_ids());
  }

  @Test
  void commitsOnlyTheExactLiveSourceAndBuildingPair() throws Exception {
    var manager = new IndexGenerationManager(temp.resolve("index"));
    String blue = manager.initializeOrLoad().state().active_generation();
    String green = manager.startMigration("manual").building_generation();
    manager.updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);

    try (var promotion = manager.beginNativePromotion(blue, green)) {
      assertEquals(IndexGenerationManager.RecordedPromotion.CommitWitness.UNCHANGED,
          promotion.inspectCommitWitness());
      assertEquals(green, promotion.promote().active_generation());
      assertEquals(IndexGenerationManager.RecordedPromotion.CommitWitness.COMMITTED,
          promotion.inspectCommitWitness());
      assertThrows(IllegalStateException.class, promotion::promote);
    }
  }

  @Test
  void refusesSourceOrBuildingDriftWithoutMovingThePointer() throws Exception {
    var manager = new IndexGenerationManager(temp.resolve("index"));
    String blue = manager.initializeOrLoad().state().active_generation();
    String green = manager.startMigration("manual").building_generation();
    manager.updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);

    try (var wrongSource = manager.beginNativePromotion("other-generation", green)) {
      assertThrows(IOException.class, wrongSource::promote);
    }
    try (var wrongBuilding = manager.beginNativePromotion(blue, "other-generation")) {
      assertThrows(IOException.class, wrongBuilding::promote);
    }
    assertEquals(blue, manager.readStateBestEffort().active_generation());
  }

  @Test
  void retainedPredecessorConsumesTheSecondGenerationSlotUntilDeletion() throws Exception {
    var manager = new IndexGenerationManager(temp.resolve("index"));
    String blue = manager.initializeOrLoad().state().active_generation();
    String green = manager.startMigration("first").building_generation();
    manager.updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);
    try (var promotion = manager.beginNativePromotion(blue, green)) {
      promotion.promote();
    }

    assertThrows(IOException.class, () -> manager.startMigration("third"));
    assertEquals(green, manager.readStateBestEffort().active_generation());
    assertEquals(blue, manager.readStateBestEffort().previous_generation());

    manager.retirePreviousGeneration(green, blue);
    assertNotEquals(green, manager.startMigration("next").building_generation());
  }

  @Test
  void freshMigrationRefusesAnExistingCandidateWithAnUnchangedPointer() throws Exception {
    Path base = temp.resolve("fresh-candidate-capacity");
    var manager = new IndexGenerationManager(base);
    manager.initializeOrLoad();
    IndexGenerationManager.State retained = manager.startFreshMigration("first");
    List<String> directoriesBeforeRefusal = generationDirectoryNames(base);

    IOException refused =
        assertThrows(IOException.class, () -> manager.startFreshMigration("second"));

    assertEquals(
        "A migration candidate is already active or retained; resolve it before starting another",
        refused.getMessage());
    assertEquals(retained, new IndexGenerationManager(base).readStateBestEffort());
    assertEquals(directoriesBeforeRefusal, generationDirectoryNames(base));
    assertEquals(2L, generationDirectoryCount(base));
  }

  @Test
  void freshMigrationRefusesARetainedPredecessorWithAnUnchangedPointer() throws Exception {
    Path base = temp.resolve("fresh-predecessor-capacity");
    var manager = new IndexGenerationManager(base);
    String blue = manager.initializeOrLoad().state().active_generation();
    String green = manager.startFreshMigration("first").building_generation();
    manager.updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);
    IndexGenerationManager.State promoted;
    try (var promotion = manager.beginNativePromotion(blue, green)) {
      promoted = promotion.promote();
    }
    List<String> directoriesBeforeRefusal = generationDirectoryNames(base);

    IOException refused =
        assertThrows(IOException.class, () -> manager.startFreshMigration("second"));

    assertEquals("Previous generation still occupies build capacity", refused.getMessage());
    assertEquals(promoted, new IndexGenerationManager(base).readStateBestEffort());
    assertEquals(directoriesBeforeRefusal, generationDirectoryNames(base));
    assertEquals(2L, generationDirectoryCount(base));
  }

  @Test
  void nextBuildPrunesAnAbandonedCandidateBeforeAllocating() throws Exception {
    var manager = new IndexGenerationManager(temp.resolve("index"));
    String active = manager.initializeOrLoad().state().active_generation();
    manager.startMigration("abandoned");
    manager.abandonBuildingGeneration("test cancellation");

    assertNotEquals(active, manager.startMigration("after-prune").building_generation());
    try (var directories = Files.list(temp.resolve("index/indices"))) {
      assertEquals(2L, directories.filter(Files::isDirectory).count());
    }
  }

  private static long generationDirectoryCount(Path base) throws IOException {
    try (var entries = Files.list(base.resolve("indices"))) {
      return entries.filter(Files::isDirectory).count();
    }
  }

  private static List<String> generationDirectoryNames(Path base) throws IOException {
    try (var entries = Files.list(base.resolve("indices"))) {
      return entries
          .filter(Files::isDirectory)
          .map(path -> path.getFileName().toString())
          .sorted()
          .toList();
    }
  }
}
