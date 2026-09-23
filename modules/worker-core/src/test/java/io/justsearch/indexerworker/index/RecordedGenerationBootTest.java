/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Boot ownership must decide before native recovery can rewrite generation evidence. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class RecordedGenerationBootTest {
  private static final String SOURCE = "recorded-bulk-test";
  private static final String OTHER_SOURCE = "recorded-rebuild-test";
  private static final String FINGERPRINT = "b".repeat(64);
  private static final String OTHER_FINGERPRINT = "c".repeat(64);
  private static final String KEY = "01994180-0000-7000-8000-000000000121";
  private static final String MANIFEST = ".justsearch-index-generation.json";
  private static final String SENTINEL = ".justsearch-generation.sentinel";
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path temp;

  @Test
  void recordedPromotionLeaseRetainsStateOwnerThroughStrictPointerReplacement() throws Exception {
    Started started = startRecorded(temp.resolve("promotion-lease"));
    var manager = new IndexGenerationManager(started.base());
    try (var other = Executors.newSingleThreadExecutor()) {
      var attempted = new CountDownLatch(1);
      java.util.concurrent.Future<?> stateChange;
      try (var promotion = manager.beginRecordedPromotion(
          KEY, SOURCE, FINGERPRINT, started.sourceGeneration())) {
        stateChange = other.submit(() -> {
          attempted.countDown();
          manager.updateMigrationState(IndexGenerationManager.MigrationState.FAILED);
          return null;
        });
        assertTrue(attempted.await(2, TimeUnit.SECONDS));
        assertTrue(awaitQueuedStateControl(),
            "competing writer must actually reach the state guard before the hold assertion");
        assertThrows(TimeoutException.class, () -> stateChange.get(100, TimeUnit.MILLISECONDS),
            "another generation state writer must wait until publication releases this owner");
        assertEquals(started.targetGeneration(), promotion.promote().active_generation());
        assertTrue(awaitQueuedStateControl(),
            "nested strict promotion must keep the competing writer queued");
        assertThrows(TimeoutException.class, () -> stateChange.get(100, TimeUnit.MILLISECONDS),
            "nested strict promotion must not release the outer state owner");
        assertThrows(IllegalStateException.class, promotion::promote,
            "one lease cannot replace the durable pointer twice");
      }
      stateChange.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void recordedPromotionLeaseRefusesChangedAcceptedBindingWithoutPointerMutation() throws Exception {
    Started started = startRecorded(temp.resolve("promotion-binding-refusal"));
    var manager = new IndexGenerationManager(started.base());
    Snapshot before = snapshot(started.base());
    try (var promotion = manager.beginRecordedPromotion(
        KEY, SOURCE, OTHER_FINGERPRINT, started.sourceGeneration())) {
      assertThrows(IOException.class, promotion::promote);
      assertEquals(IndexGenerationManager.RecordedPromotion.CommitWitness.UNCHANGED,
          promotion.inspectCommitWitness());
    }
    assertUnchanged(started.base(), before);
  }

  @Test
  void recordedPromotionLeaseRecognizesCommittedPointerAfterPostMoveIoFailure() throws Exception {
    Started started = startRecorded(temp.resolve("promotion-post-move"));
    var manager = new IndexGenerationManager(started.base(), state -> {
      if (started.targetGeneration().equals(state.active_generation())
          && state.building_generation() == null) {
        throw new IOException("injected after durable pointer move");
      }
    });
    try (var promotion = manager.beginRecordedPromotion(
        KEY, SOURCE, FINGERPRINT, started.sourceGeneration())) {
      assertEquals(started.targetGeneration(), promotion.promote().active_generation());
      assertEquals(IndexGenerationManager.RecordedPromotion.CommitWitness.COMMITTED,
          promotion.inspectCommitWitness());
    }
    var boot = new IndexGenerationManager(started.base()).initializeForBoot(
        recorded(started, true), FINGERPRINT);
    assertEquals(IndexGenerationManager.BootDisposition.PROMOTED, boot.disposition());
  }

  @Test
  void revokedContinuationFencesUncommittedBuildButRecoversCommittedPointer() throws Exception {
    Started started = startRecorded(temp.resolve("revoked-after-commit"));
    var denied = new IndexGenerationManager.BootOwnership.Recorded(
        KEY, started.sourceGeneration(), SOURCE, FINGERPRINT, true, false);
    var manager = new IndexGenerationManager(started.base());
    assertEquals(IndexGenerationManager.BootDisposition.FENCED,
        manager.initializeForBoot(denied, FINGERPRINT).disposition());

    try (var promotion = manager.beginRecordedPromotion(
        KEY, SOURCE, FINGERPRINT, started.sourceGeneration())) {
      assertEquals(started.targetGeneration(), promotion.promote().active_generation());
    }
    assertEquals(IndexGenerationManager.BootDisposition.PROMOTED,
        new IndexGenerationManager(started.base()).initializeForBoot(denied, FINGERPRINT)
            .disposition());
  }

  @ParameterizedTest
  @EnumSource(PointerDamage.class)
  void pendingAndFencedBootRefuseMissingOrCorruptCurrentWithoutTouchingPrevOrDirectories(
      PointerDamage damage) throws Exception {
    Started started = startRecorded(temp.resolve("strict-" + damage.name().toLowerCase(java.util.Locale.ROOT)));
    damageCurrentPointer(started.base(), damage);
    Snapshot before = snapshot(started.base());

    assertThrows(IOException.class,
        () -> new IndexGenerationManager(started.base()).initializeForBoot(
            recorded(started, true), FINGERPRINT),
        "pending recorded work cannot acquire Blue authority through state.json.prev");
    assertUnchanged(started.base(), before);

    assertThrows(IOException.class,
        () -> new IndexGenerationManager(started.base()).initializeForBoot(
            new IndexGenerationManager.BootOwnership.Fenced(), FINGERPRINT),
        "a fenced boot also requires the strict current pointer");
    assertUnchanged(started.base(), before);
  }

  @ParameterizedTest
  @EnumSource(PointerDamage.class)
  void nativeBootRefusesDamagedCurrentWhenRecordedOrphanEvidenceExists(PointerDamage damage)
      throws Exception {
    Started started = startRecorded(temp.resolve("native-orphan-" + damage.name().toLowerCase(java.util.Locale.ROOT)));
    damageCurrentPointer(started.base(), damage);
    Snapshot before = snapshot(started.base());

    assertThrows(IOException.class,
        () -> new IndexGenerationManager(started.base()).initializeForBoot(
            new IndexGenerationManager.BootOwnership.Native(), FINGERPRINT),
        "native fallback must not use .prev when a recorded target directory is present");
    assertUnchanged(started.base(), before);
  }

  @Test
  void nativeBootRetainsOrdinaryBackupRecoveryWithoutRecordedEvidence() throws Exception {
    Path base = temp.resolve("native-prev-recovery");
    IndexGenerationManager manager = new IndexGenerationManager(base);
    String active = manager.initializeOrLoad().activeGenerationId();
    manager.setMigrationPaused(true, "create a valid backup snapshot");
    Path current = base.resolve("state.json");
    Path previous = base.resolve("state.json.prev");
    byte[] previousBytes = Files.readAllBytes(previous);
    Files.writeString(current, "{broken current pointer", StandardCharsets.UTF_8);
    assertTrue(Files.exists(previous));
    assertEquals(active, JSON.readTree(previousBytes).path("active_generation").asText());

    var boot = new IndexGenerationManager(base).initializeForBoot(
        new IndexGenerationManager.BootOwnership.Native(), FINGERPRINT);

    assertEquals(IndexGenerationManager.BootDisposition.NATIVE, boot.disposition());
    assertEquals(active, boot.layout().activeGenerationId());
    assertEquals(active, JSON.readTree(Files.readAllBytes(current)).path("active_generation").asText());
    assertFalse(Files.exists(base.resolve("indices").resolve("g-" + KEY)));
  }

  @Test
  void strictBootInspectionDistinguishesAbsentFromMalformedWithoutRepairing() throws Exception {
    Path absentBase = temp.resolve("strict-inspection-absent");
    assertTrue(
        new IndexGenerationManager(absentBase).inspectCurrentLayoutForBoot().isEmpty(),
        "a missing state.json is an absent store, not a malformed state");
    assertFalse(Files.exists(absentBase), "inspection must not create the index base");

    Path base = temp.resolve("strict-inspection-malformed");
    var manager = new IndexGenerationManager(base);
    var initialized = manager.initializeOrLoad();
    Snapshot valid = snapshot(base);
    var inspected = manager.inspectCurrentLayoutForBoot();
    assertTrue(inspected.isPresent(), "a current state.json must produce a layout");
    assertEquals(initialized.activeGenerationId(), inspected.orElseThrow().activeGenerationId());
    assertUnchanged(base, valid);

    manager.setMigrationPaused(true, "retain a valid previous state");
    Path state = base.resolve("state.json");
    Files.writeString(state, "{malformed state", StandardCharsets.UTF_8);
    Snapshot malformed = snapshot(base);
    assertThrows(
        IOException.class,
        () -> new IndexGenerationManager(base).inspectCurrentLayoutForBoot(),
        "a malformed current pointer must remain distinct from absent state");
    assertUnchanged(base, malformed);

    Files.delete(state);
    Snapshot absentWithBackup = snapshot(base);
    assertTrue(
        new IndexGenerationManager(base).inspectCurrentLayoutForBoot().isEmpty(),
        "an absent current pointer stays absent even when state.json.prev exists");
    assertUnchanged(base, absentWithBackup);
  }

  @Test
  void nativeBootRetainsV1StateNormalization() throws Exception {
    Path base = temp.resolve("native-v1-upgrade");
    String active = new IndexGenerationManager(base).initializeOrLoad().activeGenerationId();
    Path current = base.resolve("state.json");
    ObjectNode v1 = (ObjectNode) JSON.readTree(Files.readAllBytes(current));
    v1.put("format_version", 1);
    v1.remove("migration_state");
    Files.write(current, JSON.writeValueAsBytes(v1));

    var boot = new IndexGenerationManager(base).initializeForBoot(
        new IndexGenerationManager.BootOwnership.Native(), FINGERPRINT);

    assertEquals(IndexGenerationManager.BootDisposition.NATIVE, boot.disposition());
    assertEquals(active, boot.layout().activeGenerationId());
    JsonNode upgraded = JSON.readTree(Files.readAllBytes(current));
    assertEquals(2, upgraded.path("format_version").asInt());
    assertEquals("IDLE", upgraded.path("migration_state").asText());
  }

  @Test
  void currentIdleRecordedGenerationRemainsUsableAfterItsOperationRowIsGone() throws Exception {
    Started started = startRecorded(temp.resolve("completed-no-row"));
    var promoter = new IndexGenerationManager(started.base());
    var promoted = promoter.promoteBuildingGenerationToActive();
    assertEquals(started.targetGeneration(), promoted.active_generation());
    assertNull(promoted.building_generation());

    Snapshot before = snapshot(started.base());
    var boot = new IndexGenerationManager(started.base()).initializeForBoot(
        new IndexGenerationManager.BootOwnership.Native(), FINGERPRINT);

    assertEquals(IndexGenerationManager.BootDisposition.NATIVE, boot.disposition());
    assertEquals(started.targetGeneration(), boot.layout().activeGenerationId());
    assertEquals("IDLE", boot.layout().state().migration_state());
    assertNull(boot.layout().state().building_generation());
    assertRecordedMetadata(started.base(), started.targetGeneration(), SOURCE, FINGERPRINT);
    assertUnchanged(started.base(), before);
  }

  @Test
  void exactRecordedTargetMovesFromCapturingToBuildingAndPromotedWithoutWrites() throws Exception {
    Path captureBase = temp.resolve("capture-in-progress");
    var nativeLayout = new IndexGenerationManager(captureBase).initializeOrLoad();
    var captureOwner = new IndexGenerationManager.BootOwnership.Recorded(
        KEY, nativeLayout.activeGenerationId(), SOURCE, FINGERPRINT, false);
    Snapshot beforeCapture = snapshot(captureBase);

    var capture = new IndexGenerationManager(captureBase).initializeForBoot(captureOwner, FINGERPRINT);
    assertEquals(IndexGenerationManager.BootDisposition.CAPTURING, capture.disposition());
    assertEquals(nativeLayout.activeGenerationId(), capture.layout().activeGenerationId());
    assertUnchanged(captureBase, beforeCapture);
    assertFalse(Files.exists(generation(captureBase)));

    Started started = startRecorded(temp.resolve("building"));
    for (String phase : new String[] {"MIGRATING", "SWITCHING"}) {
      if ("SWITCHING".equals(phase)) {
        new IndexGenerationManager(started.base()).updateMigrationState(
            IndexGenerationManager.MigrationState.SWITCHING);
      }
      Snapshot beforeBuilding = snapshot(started.base());
      var building = new IndexGenerationManager(started.base()).initializeForBoot(
          recorded(started, true), FINGERPRINT);
      assertEquals(IndexGenerationManager.BootDisposition.BUILDING, building.disposition());
      assertEquals(started.sourceGeneration(), building.layout().activeGenerationId());
      assertEquals(started.targetGeneration(), building.layout().state().building_generation());
      assertRecordedMetadata(started.base(), started.targetGeneration(), SOURCE, FINGERPRINT);
      assertUnchanged(started.base(), beforeBuilding);
    }

    var promoter = new IndexGenerationManager(started.base());
    promoter.promoteBuildingGenerationToActive();
    Snapshot beforePromoted = snapshot(started.base());
    var promoted = new IndexGenerationManager(started.base()).initializeForBoot(
        recorded(started, true), FINGERPRINT);
    assertEquals(IndexGenerationManager.BootDisposition.PROMOTED, promoted.disposition());
    assertEquals(started.targetGeneration(), promoted.layout().activeGenerationId());
    assertUnchanged(started.base(), beforePromoted);
  }

  @ParameterizedTest
  @EnumSource(RecordedGuard.class)
  void incompleteOrStaleRecordedBuildingIsFencedOnStrictBlueWithoutWrites(RecordedGuard guard)
      throws Exception {
    Started started = startRecorded(temp.resolve("building-guard-" + guard.name().toLowerCase(java.util.Locale.ROOT)));
    IndexGenerationManager.BootOwnership.Recorded ownership = recorded(started, true);
    String effectiveFingerprint = FINGERPRINT;
    switch (guard) {
      case CAPTURE_INCOMPLETE -> ownership = recorded(started, false);
      case SOURCE_GENERATION_CHANGED -> ownership = new IndexGenerationManager.BootOwnership.Recorded(
          KEY, "g-not-the-current-source", SOURCE, FINGERPRINT, true);
      case EFFECTIVE_FINGERPRINT_CHANGED -> effectiveFingerprint = OTHER_FINGERPRINT;
      case PHASE_FAILED -> new IndexGenerationManager(started.base()).updateMigrationState(
          IndexGenerationManager.MigrationState.FAILED);
    }
    Snapshot before = snapshot(started.base());

    var boot = new IndexGenerationManager(started.base()).initializeForBoot(ownership, effectiveFingerprint);

    assertEquals(IndexGenerationManager.BootDisposition.FENCED, boot.disposition());
    assertEquals(started.sourceGeneration(), boot.layout().activeGenerationId(),
        "before promotion only the strict current Blue remains serving authority");
    assertEquals(started.targetGeneration(), boot.layout().state().building_generation());
    assertUnchanged(started.base(), before);
  }

  @ParameterizedTest
  @EnumSource(OwnershipDamage.class)
  void recordedBuildingWithMismatchedManifestOrSentinelRefusesWithoutSecondaryEffects(
      OwnershipDamage damage) throws Exception {
    Started started = startRecorded(temp.resolve("metadata-" + damage.name().toLowerCase(java.util.Locale.ROOT)));
    damageOwnershipMetadata(started, damage);
    Snapshot before = snapshot(started.base());

    assertThrows(IOException.class,
        () -> new IndexGenerationManager(started.base()).initializeForBoot(
            recorded(started, true), FINGERPRINT),
        "pending Green must match its exact accepted manifest and sentinel");
    assertUnchanged(started.base(), before);
  }

  @Test
  void incompleteProofAfterPromotionFencesCurrentGreenWithoutRewindingToPrevious() throws Exception {
    Started started = startRecorded(temp.resolve("promoted-incomplete"));
    new IndexGenerationManager(started.base()).promoteBuildingGenerationToActive();
    Snapshot before = snapshot(started.base());

    var boot = new IndexGenerationManager(started.base()).initializeForBoot(
        recorded(started, false), FINGERPRINT);

    assertEquals(IndexGenerationManager.BootDisposition.FENCED, boot.disposition());
    assertEquals(started.targetGeneration(), boot.layout().activeGenerationId(),
        "the strict current pointer remains authoritative after promotion; boot must not rewind to .prev");
    assertEquals(started.sourceGeneration(), boot.layout().state().previous_generation());
    assertUnchanged(started.base(), before);
  }

  @Test
  void promotedTargetWithWrongAcceptedSourceGenerationIsFencedAtCurrentIdentity() throws Exception {
    Started started = startRecorded(temp.resolve("promoted-wrong-source"));
    new IndexGenerationManager(started.base()).promoteBuildingGenerationToActive();
    var wrongSource = new IndexGenerationManager.BootOwnership.Recorded(
        KEY, "g-not-the-previous-generation", SOURCE, FINGERPRINT, true);
    Snapshot before = snapshot(started.base());

    var boot = new IndexGenerationManager(started.base()).initializeForBoot(wrongSource, FINGERPRINT);

    assertEquals(IndexGenerationManager.BootDisposition.FENCED, boot.disposition());
    assertEquals(started.targetGeneration(), boot.layout().activeGenerationId(),
        "a bad owner cannot rewind or replace the strict current active identity");
    assertEquals(started.sourceGeneration(), boot.layout().state().previous_generation());
    assertUnchanged(started.base(), before);
  }

  @Test
  void nativeBootFencesAnUnownedRecordedBuildingButKeepsCurrentBlue() throws Exception {
    Started started = startRecorded(temp.resolve("unowned-building"));
    Snapshot before = snapshot(started.base());

    var boot = new IndexGenerationManager(started.base()).initializeForBoot(
        new IndexGenerationManager.BootOwnership.Native(), FINGERPRINT);

    assertEquals(IndexGenerationManager.BootDisposition.FENCED, boot.disposition());
    assertEquals(started.sourceGeneration(), boot.layout().activeGenerationId());
    assertEquals(started.targetGeneration(), boot.layout().state().building_generation());
    assertUnchanged(started.base(), before);
  }

  @Test
  void strictRecordedBootRefusesV1WithoutNormalizationOrFallbackWrites() throws Exception {
    Started started = startRecorded(temp.resolve("v1-strict"));
    Path current = started.base().resolve("state.json");
    ObjectNode state = (ObjectNode) JSON.readTree(Files.readAllBytes(current));
    state.put("format_version", 1);
    Files.write(current, JSON.writeValueAsBytes(state));
    Snapshot before = snapshot(started.base());

    assertThrows(IOException.class,
        () -> new IndexGenerationManager(started.base()).initializeForBoot(
            new IndexGenerationManager.BootOwnership.Fenced(), FINGERPRINT),
        "recorded/fenced boot must not upgrade v1 state before ownership is decided");
    assertUnchanged(started.base(), before);
  }

  @Test
  void nativeCurrentWithPristineUnreferencedRecordedTargetIsFencedWithoutRepair() throws Exception {
    Started started = startRecorded(temp.resolve("pristine-orphan"));
    Files.write(started.base().resolve("state.json"), Files.readAllBytes(started.base().resolve("state.json.prev")));
    Snapshot before = snapshot(started.base());
    var boot = new IndexGenerationManager(started.base()).initializeForBoot(
        new IndexGenerationManager.BootOwnership.Native(), FINGERPRINT);
    assertEquals(IndexGenerationManager.BootDisposition.FENCED, boot.disposition());
    assertEquals(started.sourceGeneration(), boot.layout().activeGenerationId());
    assertUnchanged(started.base(), before);
    Files.delete(generation(started.base()).resolve(MANIFEST));
    Snapshot partial = snapshot(started.base());
    assertEquals(IndexGenerationManager.BootDisposition.FENCED,
        new IndexGenerationManager(started.base()).initializeForBoot(
            new IndexGenerationManager.BootOwnership.Native(), FINGERPRINT).disposition());
    assertUnchanged(started.base(), partial);
  }

  @Test
  void nativeCurrentDoesNotFenceNonPristineRecordedArchiveBeyondPrevious() throws Exception {
    Started started = startRecorded(temp.resolve("completed-archive"));
    var manager = new IndexGenerationManager(started.base());
    manager.promoteBuildingGenerationToActive();
    // The manager does not parse Lucene contents; a completed generation owns content
    // beyond its two generation metadata files, even when the corpus was empty.
    Files.writeString(generation(started.base()).resolve("segments_1"), "retained completed commit");
    manager.startMigration("later-native-one");
    manager.promoteBuildingGenerationToActive();
    manager.startMigration("later-native-two");
    var current = manager.promoteBuildingGenerationToActive();
    assertFalse(started.targetGeneration().equals(current.active_generation()));
    assertFalse(started.targetGeneration().equals(current.previous_generation()));
    Snapshot before = snapshot(started.base());
    var boot = new IndexGenerationManager(started.base()).initializeForBoot(
        new IndexGenerationManager.BootOwnership.Native(), OTHER_FINGERPRINT);
    assertEquals(IndexGenerationManager.BootDisposition.NATIVE, boot.disposition());
    assertEquals(current.active_generation(), boot.layout().activeGenerationId());
    assertUnchanged(started.base(), before);
  }

  @Test
  void nativeRecordedActiveRequiresACompletedCurrentIdleShape() throws Exception {
    Started started = startRecorded(temp.resolve("active-recorded-invalid-phase"));
    new IndexGenerationManager(started.base()).promoteBuildingGenerationToActive();
    Path current = started.base().resolve("state.json");
    ObjectNode state = (ObjectNode) JSON.readTree(Files.readAllBytes(current));
    state.put("migration_state", "FAILED");
    Files.write(current, JSON.writeValueAsBytes(state));
    Snapshot failed = snapshot(started.base());
    assertEquals(IndexGenerationManager.BootDisposition.FENCED,
        new IndexGenerationManager(started.base()).initializeForBoot(
            new IndexGenerationManager.BootOwnership.Native(), FINGERPRINT).disposition());
    assertUnchanged(started.base(), failed);
    state.put("migration_state", "UNRECOGNIZED");
    Files.write(current, JSON.writeValueAsBytes(state));
    Snapshot unknown = snapshot(started.base());
    assertThrows(IOException.class, () -> new IndexGenerationManager(started.base()).initializeForBoot(
        new IndexGenerationManager.BootOwnership.Native(), FINGERPRINT));
    assertUnchanged(started.base(), unknown);
  }

  @Test
  void completedRecordedActiveCanParticipateInLaterNativeMigration() throws Exception {
    Started started = startRecorded(temp.resolve("recorded-blue-native-green"));
    var manager = new IndexGenerationManager(started.base());
    manager.promoteBuildingGenerationToActive();
    var nativeBuild = manager.startMigration("later-schema-upgrade");
    Snapshot before = snapshot(started.base());
    var boot = new IndexGenerationManager(started.base()).initializeForBoot(
        new IndexGenerationManager.BootOwnership.Native(), OTHER_FINGERPRINT);
    assertEquals(IndexGenerationManager.BootDisposition.NATIVE, boot.disposition());
    assertEquals(started.targetGeneration(), boot.layout().activeGenerationId());
    assertEquals(nativeBuild.building_generation(), boot.layout().state().building_generation());
    assertUnchanged(started.base(), before);
  }

  private static boolean awaitQueuedStateControl() throws Exception {
    var field = IndexGenerationManager.class.getDeclaredField("STATE_CONTROL");
    field.setAccessible(true);
    var lock = (java.util.concurrent.locks.ReentrantLock) field.get(null);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!lock.hasQueuedThreads() && System.nanoTime() < deadline) Thread.onSpinWait();
    return lock.hasQueuedThreads();
  }

  private static Started startRecorded(Path base) throws Exception {
    var manager = new IndexGenerationManager(base);
    var initial = manager.initializeOrLoad();
    byte[] sourceState = Files.readAllBytes(base.resolve("state.json"));
    var started = manager.startRecordedMigration(KEY, SOURCE, FINGERPRINT);
    String target = started.building_generation();
    assertNotNull(target);
    assertArrayEquals(sourceState, Files.readAllBytes(base.resolve("state.json.prev")),
        "the start fixture must leave a real valid Blue snapshot in .prev");
    assertRecordedMetadata(base, target, SOURCE, FINGERPRINT);
    return new Started(base, initial.activeGenerationId(), target);
  }

  private static IndexGenerationManager.BootOwnership.Recorded recorded(Started started, boolean complete) {
    return new IndexGenerationManager.BootOwnership.Recorded(
        KEY, started.sourceGeneration(), SOURCE, FINGERPRINT, complete);
  }

  private static Path generation(Path base) throws IOException {
    return base.resolve("indices").resolve(IndexGenerationManager.recordedGenerationId(KEY));
  }

  private static void damageCurrentPointer(Path base, PointerDamage damage) throws IOException {
    Path current = base.resolve("state.json");
    if (damage == PointerDamage.CORRUPT) {
      Files.writeString(current, "{broken current pointer", StandardCharsets.UTF_8);
    } else {
      Files.delete(current);
    }
    assertTrue(Files.exists(base.resolve("state.json.prev")), "fixture has the valid pre-start Blue backup");
  }

  private static void damageOwnershipMetadata(Started started, OwnershipDamage damage) throws Exception {
    Path directory = generation(started.base());
    Path manifestPath = directory.resolve(MANIFEST);
    Path sentinelPath = directory.resolve(SENTINEL);
    if (damage == OwnershipDamage.CORRUPT_MANIFEST) {
      Files.writeString(manifestPath, "{broken manifest", StandardCharsets.UTF_8);
    } else if (damage == OwnershipDamage.WRONG_SOURCE || damage == OwnershipDamage.WRONG_FINGERPRINT) {
      ObjectNode manifest = (ObjectNode) JSON.readTree(Files.readAllBytes(manifestPath));
      if (damage == OwnershipDamage.WRONG_SOURCE) manifest.put("source", OTHER_SOURCE);
      else manifest.put("target_index_fingerprint", OTHER_FINGERPRINT);
      Files.write(manifestPath, JSON.writeValueAsBytes(manifest));
    } else {
      Files.writeString(sentinelPath,
          Files.readString(sentinelPath).replace("generation_id=" + started.targetGeneration(),
              "generation_id=g-foreign"), StandardCharsets.UTF_8);
    }
  }

  private static void assertRecordedMetadata(Path base, String target, String source, String fingerprint)
      throws Exception {
    Path directory = base.resolve("indices").resolve(target);
    Path manifestPath = directory.resolve(MANIFEST);
    Path sentinelPath = directory.resolve(SENTINEL);
    assertTrue(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS));
    assertTrue(Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS));
    assertTrue(Files.isRegularFile(sentinelPath, LinkOption.NOFOLLOW_LINKS));
    JsonNode manifest = JSON.readTree(Files.readAllBytes(manifestPath));
    assertEquals(2, manifest.path("format_version").asInt());
    assertEquals(target, manifest.path("generation_id").asText());
    assertEquals(source, manifest.path("source").asText());
    assertEquals(fingerprint, manifest.path("target_index_fingerprint").asText());
    long created = manifest.path("created_at_ms").asLong();
    assertTrue(created > 0);
    assertEquals("justsearch_generation_sentinel_v1\ngeneration_id=" + target
            + "\ncreated_at_ms=" + created + "\n",
        Files.readString(sentinelPath, StandardCharsets.UTF_8));
  }

  private static Snapshot snapshot(Path base) throws IOException {
    Map<Path, byte[]> files = new HashMap<>();
    Set<Path> directories = new HashSet<>();
    try (var paths = Files.walk(base)) {
      for (Path path : paths.toList()) {
        Path relative = base.relativize(path);
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
          directories.add(relative);
        } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
          files.put(relative, Files.readAllBytes(path));
        }
      }
    }
    return new Snapshot(files, directories);
  }

  private static void assertUnchanged(Path base, Snapshot expected) throws IOException {
    Snapshot actual = snapshot(base);
    assertEquals(
        expected.directories(), actual.directories(),
        "boot refusal/disposition cannot add or remove directories");
    assertEquals(
        expected.files().keySet(), actual.files().keySet(),
        "boot refusal/disposition cannot add or remove files");
    expected.files().forEach((relative, bytes) ->
        assertArrayEquals(bytes, actual.files().get(relative), "boot must preserve bytes at " + relative));
  }

  private record Started(Path base, String sourceGeneration, String targetGeneration) {}
  private record Snapshot(Map<Path, byte[]> files, Set<Path> directories) {}

  private enum PointerDamage { MISSING, CORRUPT }
  private enum OwnershipDamage { CORRUPT_MANIFEST, WRONG_SOURCE, WRONG_FINGERPRINT, FOREIGN_SENTINEL }
  private enum RecordedGuard {
    CAPTURE_INCOMPLETE,
    SOURCE_GENERATION_CHANGED,
    EFFECTIVE_FINGERPRINT_CHANGED,
    PHASE_FAILED
  }
}
