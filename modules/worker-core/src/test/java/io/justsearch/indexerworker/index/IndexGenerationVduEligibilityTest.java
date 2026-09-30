package io.justsearch.indexerworker.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.JacksonException;

/** Exercises the strict authoritative-generation predicate used by VDU mutations. */
final class IndexGenerationVduEligibilityTest {

  @Test
  void onlyIdleStateWithoutBuildingGenerationMayTargetCapturedActiveGeneration(
      @TempDir Path tempDir) throws Exception {
    Path base = tempDir.resolve("index");
    IndexGenerationManager manager = new IndexGenerationManager(base);
    IndexGenerationManager.IndexLayout initial = manager.initializeOrLoad();
    Path capturedActive = initial.activeGenerationPath();

    assertTrue(
        manager.isIdleActiveGeneration(capturedActive),
        "an unchanged IDLE state must accept its active generation");
    org.junit.jupiter.api.Assertions.assertEquals(initial.activeGenerationId(),
        manager.idleActiveGeneration(capturedActive).orElseThrow());

    IndexGenerationManager.State migrating = manager.startMigration("eligibility-test");
    assertNotNull(migrating.building_generation(), "migration creates a building generation");

    IndexGenerationManager.State promoted = manager.promoteBuildingGenerationToActive();
    assertNotNull(promoted, "promotion produces a state");
    assertNotEquals(
        initial.activeGenerationId(),
        promoted.active_generation(),
        "promotion must advance the active pointer before the rollback check");
    Path promotedPath = manager.resolveGenerationPathStrict(promoted.active_generation());
    assertFalse(
        manager.isIdleActiveGeneration(capturedActive),
        "an IDLE state with a changed active pointer must refuse the old target");
    assertTrue(manager.idleActiveGeneration(capturedActive).isEmpty());
    org.junit.jupiter.api.Assertions.assertEquals(promoted.active_generation(),
        manager.idleActiveGeneration(promotedPath).orElseThrow());
    assertTrue(
        manager.isIdleActiveGeneration(promotedPath),
        "the promoted active generation is eligible after the transition completes");

    IndexGenerationManager.State rolledBack = manager.rollbackToPreviousGeneration();
    assertNotNull(rolledBack, "rollback produces a state");
    assertTrue(
        manager.isIdleActiveGeneration(capturedActive),
        "rollback restores eligibility for the previous active generation");
    assertFalse(
        manager.isIdleActiveGeneration(promotedPath),
        "rollback makes the former promoted generation ineligible");
  }

  @Test
  void transitionStatesRefuseEvenWhenNoBuildingGenerationExists(@TempDir Path tempDir)
      throws Exception {
    for (IndexGenerationManager.MigrationState transition :
        new IndexGenerationManager.MigrationState[] {
          IndexGenerationManager.MigrationState.MIGRATING,
          IndexGenerationManager.MigrationState.SWITCHING,
          IndexGenerationManager.MigrationState.FAILED
        }) {
      Path base = tempDir.resolve(transition.name());
      IndexGenerationManager manager = new IndexGenerationManager(base);
      IndexGenerationManager.IndexLayout layout = manager.initializeOrLoad();

      manager.updateMigrationState(transition);
      IndexGenerationManager.State state = manager.readStateBestEffort();
      assertNotNull(state, "transition state remains readable");
      assertNull(
          state.building_generation(), "this case isolates the migration-state condition");
      assertFalse(
          manager.isIdleActiveGeneration(layout.activeGenerationPath()),
          transition + " must refuse even without a building generation");
    }
  }

  @Test
  void idleStateWithBuildingGenerationRefusesEvenWithoutTransitionState(@TempDir Path tempDir)
      throws Exception {
    Path base = tempDir.resolve("idle-building");
    IndexGenerationManager manager = new IndexGenerationManager(base);
    IndexGenerationManager.IndexLayout layout = manager.initializeOrLoad();
    IndexGenerationManager.State migrating = manager.startMigration("idle-building-test");
    assertNotNull(migrating.building_generation(), "precondition: a building generation exists");

    manager.updateMigrationState(IndexGenerationManager.MigrationState.IDLE);
    assertFalse(
        manager.isIdleActiveGeneration(layout.activeGenerationPath()),
        "IDLE with a building generation must refuse the captured target");
  }

  @Test
  void validStateCannotCreateOrAuthorizeAnAbsentGenerationLayout(@TempDir Path tempDir)
      throws Exception {
    var original = new IndexGenerationManager(tempDir.resolve("original")).initializeOrLoad();
    Path absentBase = tempDir.resolve("absent-layout");
    Files.createDirectories(absentBase);
    Files.copy(original.statePath(), absentBase.resolve("state.json"));
    Path indices = absentBase.resolve("indices");
    Path target = indices.resolve(original.activeGenerationId());
    var manager = new IndexGenerationManager(absentBase);
    byte[] authoritative = Files.readAllBytes(absentBase.resolve("state.json"));

    assertFalse(manager.isIdleActiveGeneration(target));
    assertFalse(Files.exists(indices), "eligibility must not create the indices directory");
    assertArrayEquals(authoritative, Files.readAllBytes(absentBase.resolve("state.json")));

    Files.createDirectories(indices);
    assertFalse(manager.isIdleActiveGeneration(target), "a missing generation is not an eligible target");
    assertFalse(Files.exists(target), "eligibility must not create the target generation");
  }

  @Test
  void strictPredicateRefusesMissingMalformedFutureAndUnsafeAuthoritativeState(
      @TempDir Path tempDir) throws Exception {
    assertStrictRefusal(tempDir.resolve("missing"), null, true, IOException.class);
    assertStrictRefusal(
        tempDir.resolve("malformed"), "{ malformed", false, JacksonException.class);
    assertStrictRefusal(
        tempDir.resolve("future"),
        "{\"format_version\":99,\"active_generation\":\"future\",\"migration_state\":\"IDLE\"}",
        false,
        IOException.class);
    assertStrictRefusal(
        tempDir.resolve("unsafe"),
        "{\"format_version\":2,\"active_generation\":\"../outside\",\"migration_state\":\"IDLE\"}",
        false,
        IOException.class);
  }

  @Test
  void strictPredicateIgnoresCachedStateAndNeverRecoversOrWritesBackup(
      @TempDir Path tempDir) throws Exception {
    Path base = tempDir.resolve("index");
    IndexGenerationManager manager = new IndexGenerationManager(base);
    IndexGenerationManager.IndexLayout layout = manager.initializeOrLoad();
    Path statePath = layout.statePath();
    Path previousPath = base.resolve("state.json.prev");
    byte[] validState = Files.readAllBytes(statePath);
    Files.copy(statePath, previousPath, StandardCopyOption.REPLACE_EXISTING);

    assertNotNull(manager.readStateBestEffort(), "prime the observational cache from valid state");

    byte[] malformedState = "{ malformed authoritative state".getBytes(StandardCharsets.UTF_8);
    Files.write(statePath, malformedState);

    assertThrows(
        JacksonException.class,
        () -> manager.isIdleActiveGeneration(layout.activeGenerationPath()),
        "strict eligibility must refuse malformed state even when a cached state and valid .prev exist");
    assertArrayEquals(
        malformedState, Files.readAllBytes(statePath), "strict read must not restore state.json");
    assertArrayEquals(
        validState, Files.readAllBytes(previousPath), "strict read must not alter state.json.prev");
  }

  @Test
  void strictPredicateDoesNotRecoverMissingStateFromCacheOrBackup(@TempDir Path tempDir)
      throws Exception {
    Path base = tempDir.resolve("missing-after-cache");
    IndexGenerationManager manager = new IndexGenerationManager(base);
    IndexGenerationManager.IndexLayout layout = manager.initializeOrLoad();
    Path statePath = layout.statePath();
    Path previousPath = base.resolve("state.json.prev");
    byte[] validState = Files.readAllBytes(statePath);
    Files.copy(statePath, previousPath, StandardCopyOption.REPLACE_EXISTING);

    assertNotNull(manager.readStateBestEffort(), "prime the observational cache from valid state");
    Files.delete(statePath);

    assertThrows(
        IOException.class,
        () -> manager.isIdleActiveGeneration(layout.activeGenerationPath()),
        "strict eligibility must refuse a missing authoritative state despite cache and .prev");
    assertFalse(Files.exists(statePath), "strict read must not restore missing state.json");
    assertArrayEquals(
        validState, Files.readAllBytes(previousPath), "strict read must not alter state.json.prev");
  }

  private static void assertStrictRefusal(
      Path base, String stateJson, boolean removeState, Class<? extends Throwable> expectedType)
      throws Exception {
    IndexGenerationManager manager = new IndexGenerationManager(base);
    IndexGenerationManager.IndexLayout layout = manager.initializeOrLoad();
    if (removeState) {
      Files.delete(layout.statePath());
    } else if (stateJson.startsWith("{\"format_version\"")) {
      var mapper = new tools.jackson.databind.ObjectMapper();
      var valid = (tools.jackson.databind.node.ObjectNode) mapper.readTree(Files.readAllBytes(layout.statePath()));
      var replacement = mapper.readTree(stateJson);
      valid.put("format_version", replacement.path("format_version").asInt());
      valid.put("active_generation", replacement.path("active_generation").asText());
      Files.writeString(layout.statePath(), mapper.writeValueAsString(valid), StandardCharsets.UTF_8);
    } else {
      Files.writeString(layout.statePath(), stateJson, StandardCharsets.UTF_8);
    }

    assertThrows(
        expectedType,
        () -> manager.isIdleActiveGeneration(layout.activeGenerationPath()),
        "invalid authoritative state must refuse VDU eligibility");
  }
}
