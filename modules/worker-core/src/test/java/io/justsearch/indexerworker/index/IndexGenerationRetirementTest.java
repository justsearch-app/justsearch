/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.SafeIndexPathOps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexGenerationRetirementTest {
  private static final String SOURCE = "recorded-retirement-test";
  private static final String FINGERPRINT = "d".repeat(64);
  private static final String KEY = "01994180-0000-7000-8000-000000000131";
  private static final String NEXT_KEY = "01994180-0000-7000-8000-000000000132";

  @TempDir Path temp;

  @Test
  void deletesExactPredecessorBeforeReleasingItsReference() throws Exception {
    Promoted promoted = promote(temp.resolve("retire"));
    Files.writeString(promoted.previousPath().resolve("segments_test"), "closed predecessor");

    IndexGenerationManager.State retired =
        promoted.manager().retirePreviousGeneration(promoted.active(), promoted.previous());

    assertEquals(promoted.active(), retired.active_generation());
    assertNull(retired.previous_generation());
    assertFalse(Files.exists(promoted.previousPath()));
    assertEquals(1, generationDirectoryCount(promoted.base()));
  }

  @Test
  void retryDeletesAnAlreadyRenamedPredecessorThenClearsTheReference() throws Exception {
    Promoted promoted = promote(temp.resolve("renamed"));
    SafeIndexPathOps.MarkResult marked =
        SafeIndexPathOps.markForDeletion(
            promoted.previousPath(), promoted.base().resolve("indices"));
    assertTrue(marked.renamedToDel(), "the closed temp directory should take the rename path");
    assertFalse(Files.exists(promoted.previousPath()));
    assertTrue(Files.exists(marked.effectivePath()));

    IndexGenerationManager.State retired =
        promoted.manager().retirePreviousGeneration(promoted.active(), promoted.previous());

    assertNull(retired.previous_generation());
    assertFalse(Files.exists(marked.effectivePath()));
    assertEquals(1, generationDirectoryCount(promoted.base()));
  }

  @Test
  void retryUsesExactPointerBindingAfterPartialDeleteRemovedOwnershipMetadata()
      throws Exception {
    Promoted promoted = promote(temp.resolve("partial-delete"));
    SafeIndexPathOps.MarkResult marked =
        SafeIndexPathOps.markForDeletion(
            promoted.previousPath(), promoted.base().resolve("indices"));
    Path partial = marked.effectivePath();
    Files.delete(partial.resolve(".justsearch-generation.sentinel"));
    Files.delete(partial.resolve(".justsearch-index-generation.json"));
    Files.writeString(partial.resolve("segments_locked-on-first-delete"), "retry can remove it");

    IndexGenerationManager.State retired =
        promoted.manager().retirePreviousGeneration(promoted.active(), promoted.previous());

    assertNull(retired.previous_generation());
    assertFalse(Files.exists(partial));
    assertEquals(1, generationDirectoryCount(promoted.base()));
  }

  @Test
  void pointerBoundPartialDeleteStillRefusesSurvivingForeignOwnershipEvidence()
      throws Exception {
    Promoted promoted = promote(temp.resolve("foreign-partial"));
    SafeIndexPathOps.MarkResult marked =
        SafeIndexPathOps.markForDeletion(
            promoted.previousPath(), promoted.base().resolve("indices"));
    Path partial = marked.effectivePath();
    Files.delete(partial.resolve(".justsearch-generation.sentinel"));
    String manifest =
        Files.readString(partial.resolve(".justsearch-index-generation.json"));
    Files.writeString(
        partial.resolve(".justsearch-index-generation.json"),
        manifest.replace(promoted.previous(), "g-another-generation"));
    Files.writeString(partial.resolve("segments_locked-on-first-delete"), "must remain");

    assertThrows(
        IOException.class,
        () ->
            promoted
                .manager()
                .retirePreviousGeneration(promoted.active(), promoted.previous()));

    assertTrue(Files.exists(partial));
    assertEquals(
        promoted.previous(),
        promoted.manager().readStateBestEffort().previous_generation());
  }

  @Test
  void missingPredecessorIsADeletionWitnessAndReleasesItsReference() throws Exception {
    Promoted promoted = promote(temp.resolve("missing"));
    io.justsearch.configuration.FileOps.deleteRecursivelyBestEffort(
        promoted.previousPath(), org.slf4j.LoggerFactory.getLogger(getClass()));
    assertFalse(Files.exists(promoted.previousPath()));

    IndexGenerationManager.State retired =
        promoted.manager().retirePreviousGeneration(promoted.active(), promoted.previous());

    assertNull(retired.previous_generation());
  }

  @Test
  void retryWithClearedReferenceStillDeletesTheExactRemainingRepresentation() throws Exception {
    Promoted promoted = promote(temp.resolve("cleared-retry"));
    byte[] sentinel =
        Files.readAllBytes(
            promoted.previousPath().resolve(".justsearch-generation.sentinel"));
    byte[] manifest =
        Files.readAllBytes(
            promoted.previousPath().resolve(".justsearch-index-generation.json"));
    promoted.manager().retirePreviousGeneration(promoted.active(), promoted.previous());

    Files.createDirectory(promoted.previousPath());
    Files.write(
        promoted.previousPath().resolve(".justsearch-generation.sentinel"), sentinel);
    Files.write(
        promoted.previousPath().resolve(".justsearch-index-generation.json"), manifest);

    IndexGenerationManager.State retried =
        promoted.manager().retirePreviousGeneration(promoted.active(), promoted.previous());

    assertNull(retried.previous_generation());
    assertFalse(Files.exists(promoted.previousPath()));
  }

  @Test
  void postMoveFailureUsesOnlyTheStrictClearedPointerWitness() throws Exception {
    Promoted promoted = promote(temp.resolve("post-move"));
    AtomicBoolean injected = new AtomicBoolean();
    IndexGenerationManager ambiguous =
        new IndexGenerationManager(
            promoted.base(),
            state -> {
              if (state.previous_generation() == null
                  && promoted.active().equals(state.active_generation())
                  && injected.compareAndSet(false, true)) {
                throw new IOException("injected after durable retirement pointer move");
              }
            });

    IndexGenerationManager.State retired =
        ambiguous.retirePreviousGeneration(promoted.active(), promoted.previous());

    assertTrue(injected.get());
    assertNull(retired.previous_generation());
    assertFalse(Files.exists(promoted.previousPath()));
  }

  @Test
  void changedIdentityOrPhaseRefusesBeforeDeletingAnything() throws Exception {
    Promoted wrongActive = promote(temp.resolve("wrong-active"));
    assertThrows(
        IOException.class,
        () ->
            wrongActive
                .manager()
                .retirePreviousGeneration("g-different-active", wrongActive.previous()));
    assertTrue(Files.isDirectory(wrongActive.previousPath()));

    Promoted wrongPrevious = promote(temp.resolve("wrong-previous"));
    assertThrows(
        IOException.class,
        () ->
            wrongPrevious
                .manager()
                .retirePreviousGeneration(wrongPrevious.active(), "g-different-previous"));
    assertTrue(Files.isDirectory(wrongPrevious.previousPath()));

    Promoted wrongPhase = promote(temp.resolve("wrong-phase"));
    wrongPhase.manager().updateMigrationState(IndexGenerationManager.MigrationState.FAILED);
    assertThrows(
        IOException.class,
        () ->
            wrongPhase
                .manager()
                .retirePreviousGeneration(wrongPhase.active(), wrongPhase.previous()));
    assertTrue(Files.isDirectory(wrongPhase.previousPath()));
  }

  @Test
  void recordedBuildResumesItsAcceptedTargetButRefusesNewWorkUntilRetirement()
      throws Exception {
    Promoted promoted = promote(temp.resolve("capacity"));

    IndexGenerationManager.State sameTarget =
        promoted
            .manager()
            .startRecordedMigration(KEY, SOURCE, FINGERPRINT, promoted.previous());
    assertEquals(promoted.active(), sameTarget.active_generation());
    assertNull(sameTarget.building_generation());

    assertThrows(
        IOException.class,
        () ->
            promoted
                .manager()
                .startRecordedMigration(
                    NEXT_KEY, SOURCE, FINGERPRINT, promoted.active()));

    promoted.manager().retirePreviousGeneration(promoted.active(), promoted.previous());
    IndexGenerationManager.State next =
        promoted
            .manager()
            .startRecordedMigration(
                NEXT_KEY, SOURCE, FINGERPRINT, promoted.active());
    assertEquals(IndexGenerationManager.recordedGenerationId(NEXT_KEY), next.building_generation());
  }

  @Test
  void unreferencedPhysicalRepresentationStillConsumesRecordedBuildCapacity() throws Exception {
    Path base = temp.resolve("retained-capacity");
    IndexGenerationManager manager = new IndexGenerationManager(base);
    String active = manager.initializeOrLoad().activeGenerationId();
    Files.createDirectory(base.resolve("indices").resolve("g-retained.del-test"));

    assertThrows(
        IOException.class,
        () -> manager.startRecordedMigration(NEXT_KEY, SOURCE, FINGERPRINT, active));
    assertFalse(
        Files.exists(
            base.resolve("indices")
                .resolve(IndexGenerationManager.recordedGenerationId(NEXT_KEY))));
  }

  private static Promoted promote(Path base) throws Exception {
    IndexGenerationManager manager = new IndexGenerationManager(base);
    String previous = manager.initializeOrLoad().activeGenerationId();
    manager.startRecordedMigration(KEY, SOURCE, FINGERPRINT, previous);
    IndexGenerationManager.State promoted = manager.promoteBuildingGenerationToActive();
    return new Promoted(
        base,
        manager,
        promoted.active_generation(),
        previous,
        base.resolve("indices").resolve(previous));
  }

  private static long generationDirectoryCount(Path base) throws IOException {
    try (var entries = Files.list(base.resolve("indices"))) {
      return entries.filter(Files::isDirectory).count();
    }
  }

  private record Promoted(
      Path base,
      IndexGenerationManager manager,
      String active,
      String previous,
      Path previousPath) {}
}
