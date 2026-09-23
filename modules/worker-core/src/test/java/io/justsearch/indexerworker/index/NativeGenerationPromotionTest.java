/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeGenerationPromotionTest {
  @TempDir Path temp;

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
}
