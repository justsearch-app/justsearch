package io.justsearch.indexerworker.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 598 reopen (B-1): the "restart opens the promoted generation" half of the durability proof.
 *
 * <p>{@link EmbeddingFingerprintDurabilityTest} proves a stamped fingerprint survives a commit reopen;
 * this proves the orthogonal property the blue/green rebuild relies on — after a cutover promotes the
 * (re-embedded, fingerprinted) green via the on-disk {@code state.json active_generation} pointer, a
 * fresh manager on the same dir (a worker restart) opens THAT generation, not the old blue. Together
 * they show a completed rebuild durably escapes BLOCKED_LEGACY; the reopen B-1 symptom therefore maps
 * to "no green was promoted" (the cutover never stamped/verified), not a generation-selection bug.
 */
class IndexGenerationManagerRestartTest {

  @Test
  void openedPathIdentityRejectsAnotherGenerationsManifest() throws Exception {
    Path base = Files.createTempDirectory("genmgr-opened-identity");
    IndexGenerationManager manager = new IndexGenerationManager(base);
    String blue = manager.initializeOrLoad().activeGenerationId();
    String green = manager.startMigration("opened-identity").building_generation();
    Path bluePath = manager.resolveGenerationPathStrict(blue);
    Path greenPath = manager.resolveGenerationPathStrict(green);

    assertEquals(blue, manager.generationIdForOpenedPath(bluePath));
    assertEquals(green, manager.generationIdForOpenedPath(greenPath));
    Files.copy(bluePath.resolve(".justsearch-index-generation.json"),
        greenPath.resolve(".justsearch-index-generation.json"), StandardCopyOption.REPLACE_EXISTING);
    assertThrows(java.io.IOException.class, () -> manager.generationIdForOpenedPath(greenPath));
    assertThrows(java.io.IOException.class,
        () -> manager.generationIdForOpenedPath(base.resolve("outside")));
  }

  @Test
  @DisplayName("A promoted generation survives a restart — a fresh manager opens it via state.json (B-1)")
  void promotedGenerationSurvivesRestart() throws Exception {
    Path base = Files.createTempDirectory("genmgr-restart");

    IndexGenerationManager mgr = new IndexGenerationManager(base);
    IndexGenerationManager.IndexLayout layout0 = mgr.initializeOrLoad();
    String activeBefore = layout0.activeGenerationId();
    assertNotNull(activeBefore, "initial active generation");

    // tempdoc 628 G4 / obs #484: generation ids are second-precision ("g-yyyyMMdd-HHmmss"), so a
    // migration in the SAME wall-clock second as the initial generation once collided and threw. The
    // uniqueness-suffix fix (newUniqueGenerationId) now disambiguates instead — no clock-tick wait
    // workaround needed, and a fast back-to-back migration is exercised directly below.

    // Build a green generation and promote it (the cutover's pointer advance).
    mgr.startMigration("test-rebuild");
    IndexGenerationManager.State promoted = mgr.promoteBuildingGenerationToActive();
    assertNotNull(promoted, "promotion result");
    String activeAfter = promoted.active_generation();
    assertNotEquals(activeBefore, activeAfter, "promotion must advance the active generation pointer");

    // Simulate a worker restart: a brand-new manager on the same base dir must resolve the PROMOTED
    // generation from the persisted state.json, not revert to the old one.
    IndexGenerationManager reopened = new IndexGenerationManager(base);
    IndexGenerationManager.IndexLayout layout1 = reopened.initializeOrLoad();
    assertEquals(
        activeAfter,
        layout1.activeGenerationId(),
        "restart must open the promoted generation (state.json active_generation), not the old blue");
  }

  @Test
  @DisplayName("Back-to-back migrations in the same wall-clock second get distinct ids, no collision (628 G4)")
  void sameSecondMigrationsDoNotCollide() throws Exception {
    Path base = Files.createTempDirectory("genmgr-same-second");

    IndexGenerationManager mgr = new IndexGenerationManager(base);
    mgr.initializeOrLoad();

    // First migration creates a building generation.
    IndexGenerationManager.State first = mgr.startMigration("rebuild-1");
    String green1 = first.building_generation();
    assertNotNull(green1, "first migration must create a building generation");

    // Retire Blue after promotion so its physical representation releases the second slot. Then
    // immediately (same wall-clock second, no sleep) allocate another building generation.
    var promoted = mgr.promoteBuildingGenerationToActive();
    mgr.retirePreviousGeneration(green1, promoted.previous_generation());
    IndexGenerationManager.State second = mgr.startMigration("rebuild-2");
    String green2 = second.building_generation();
    assertNotNull(green2, "second same-second migration must create a building generation, not throw");
    assertNotEquals(green1, green2, "same-second migrations must produce distinct generation ids");
  }

  @Test
  @DisplayName("Crash between state.json rotate and atomic-move recovers from state.json.prev (628 Stage F)")
  void stateJsonPrevFallbackRecoversAfterTornWrite() throws Exception {
    Path base = Files.createTempDirectory("genmgr-prev-fallback");
    IndexGenerationManager mgr = new IndexGenerationManager(base);
    String active = mgr.initializeOrLoad().activeGenerationId();
    assertNotNull(active, "initial active generation");

    // Simulate a crash AFTER writeState rotated state.json -> state.json.prev but BEFORE the atomic
    // tmp -> state.json move completed: state.json holds a torn (invalid) write, state.json.prev holds
    // the last good state. A naive reader would lose the active-generation pointer.
    Path statePath = base.resolve("state.json");
    Path statePrev = base.resolve("state.json.prev");
    Files.copy(statePath, statePrev, StandardCopyOption.REPLACE_EXISTING);
    Files.writeString(statePath, "{ \"format_version\": 2, torn-write garbage not valid json");

    // A fresh manager (worker restart) must recover the active generation from state.json.prev.
    IndexGenerationManager reopened = new IndexGenerationManager(base);
    IndexGenerationManager.IndexLayout recovered = reopened.initializeOrLoad();
    assertEquals(
        active,
        recovered.activeGenerationId(),
        "must recover the active generation from state.json.prev when state.json is a torn write");
    assertTrue(
        Files.exists(statePath), "state.json must be restored from the .prev backup after recovery");
  }
}
