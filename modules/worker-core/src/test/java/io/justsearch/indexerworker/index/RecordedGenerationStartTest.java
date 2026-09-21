/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exact operation identity and owned metadata are required before a recorded Green can be adopted. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class RecordedGenerationStartTest {
  private static final String SOURCE = "recorded-bulk-test";
  private static final String OTHER_SOURCE = "recorded-rebuild-test";
  private static final String FINGERPRINT = "b".repeat(64);
  private static final String OTHER_FINGERPRINT = "c".repeat(64);
  private static final String KEY = "01994180-0000-7000-8000-000000000121";
  private static final String OTHER_KEY = "01994180-0000-7000-8000-000000000122";
  private static final String SENTINEL = ".justsearch-generation.sentinel";
  private static final String MANIFEST = ".justsearch-index-generation.json";
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path temp;

  @Test
  void createsExactTargetIsIdempotentAndSurvivesPromotionAndNewManager() throws Exception {
    Path base = temp.resolve("exact-start");
    var manager = new IndexGenerationManager(base);
    String expectedSource = manager.initializeOrLoad().state().active_generation();

    var created = manager.startRecordedMigration(KEY, SOURCE, FINGERPRINT, expectedSource);
    String target = IndexGenerationManager.recordedGenerationId(KEY);
    assertEquals(target, created.building_generation());
    assertEquals("MIGRATING", created.migration_state());
    assertRecordedMetadata(base, target, SOURCE, FINGERPRINT);
    assertEquals(created, manager.startRecordedMigration(KEY, SOURCE, FINGERPRINT, expectedSource),
        "retry against the same building target returns the original state");
    assertEquals(2, directoryCount(base.resolve("indices")), "idempotent retry cannot allocate another Green");

    Path targetDirectory = generation(base, KEY);
    String manifestBytes = Files.readString(targetDirectory.resolve(MANIFEST));
    String sentinelBytes = Files.readString(targetDirectory.resolve(SENTINEL));
    var promoted = manager.promoteRecordedGenerationToActive(KEY, SOURCE, FINGERPRINT, expectedSource);
    assertEquals(target, promoted.active_generation());
    assertNull(promoted.building_generation());
    assertEquals(expectedSource, promoted.previous_generation());
    assertEquals("IDLE", promoted.migration_state());
    String promotedStateBytes = Files.readString(base.resolve("state.json"));

    assertEquals(promoted,
        manager.promoteRecordedGenerationToActive(KEY, SOURCE, FINGERPRINT, expectedSource),
        "retry against the already-promoted exact target is idempotent");
    assertEquals(promotedStateBytes, Files.readString(base.resolve("state.json")),
        "idempotent promotion does not rewrite the state pointer");
    assertEquals(manifestBytes, Files.readString(targetDirectory.resolve(MANIFEST)));
    assertEquals(sentinelBytes, Files.readString(targetDirectory.resolve(SENTINEL)));

    var acceptedActive = manager.startRecordedMigration(KEY, SOURCE, FINGERPRINT, expectedSource);
    assertEquals(target, acceptedActive.active_generation(), "the exact already-active target is accepted");
    assertNull(acceptedActive.building_generation());

    var reopened = new IndexGenerationManager(base);
    assertEquals(target, reopened.readStateBestEffort().active_generation());
    var retained = reopened.startRecordedMigration(KEY, SOURCE, FINGERPRINT, expectedSource);
    assertEquals(target, retained.active_generation());
    assertRecordedMetadata(base, target, SOURCE, FINGERPRINT);
  }

  @Test
  void wrongExpectedSourceRefusesBeforeStateOrGenerationMutation() throws Exception {
    Path base = temp.resolve("wrong-expected-source");
    var manager = new IndexGenerationManager(base);
    var initial = manager.initializeOrLoad().state();
    String stateBytes = Files.readString(base.resolve("state.json"));
    String wrongSource = IndexGenerationManager.recordedGenerationId(OTHER_KEY);

    assertThrows(IOException.class,
        () -> manager.startRecordedMigration(KEY, SOURCE, FINGERPRINT, wrongSource));

    assertEquals(stateBytes, Files.readString(base.resolve("state.json")));
    assertEquals(initial.active_generation(), new IndexGenerationManager(base)
        .readStateBestEffort().active_generation());
    assertEquals(1, directoryCount(base.resolve("indices")),
        "a source mismatch must be rejected before allocating the recorded target");
    assertFalse(Files.exists(generation(base, KEY), LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void differentBuildingAndSameTargetFailedStateRefuseWithoutSuffixAllocation() throws Exception {
    Path base = temp.resolve("conflict-and-failed");
    var manager = new IndexGenerationManager(base);
    manager.initializeOrLoad();
    var started = manager.startRecordedMigration(KEY, SOURCE, FINGERPRINT);
    long before = directoryCount(base.resolve("indices"));

    assertThrows(IOException.class,
        () -> manager.startRecordedMigration(OTHER_KEY, SOURCE, FINGERPRINT),
        "a different operation key cannot take over the current building generation");
    assertFalse(Files.exists(generation(base, OTHER_KEY), LinkOption.NOFOLLOW_LINKS));

    manager.updateMigrationState(IndexGenerationManager.MigrationState.FAILED);
    assertThrows(IOException.class,
        () -> manager.startRecordedMigration(KEY, SOURCE, FINGERPRINT),
        "a failed exact target is not replaced by a suffixed generation");
    assertEquals(before, directoryCount(base.resolve("indices")));
    var current = new IndexGenerationManager(base).readStateBestEffort();
    assertEquals(started.building_generation(), current.building_generation());
    assertEquals("FAILED", current.migration_state());
  }

  @Test
  void fingerprintAndSourceMismatchRefuseTheExistingExactBuilding() throws Exception {
    Path base = temp.resolve("metadata-mismatch");
    var manager = new IndexGenerationManager(base);
    String expectedSource = manager.initializeOrLoad().state().active_generation();
    var started = manager.startRecordedMigration(KEY, SOURCE, FINGERPRINT, expectedSource);
    String stateBytes = Files.readString(base.resolve("state.json"));
    Path target = generation(base, KEY);
    String manifestBytes = Files.readString(target.resolve(MANIFEST));
    String sentinelBytes = Files.readString(target.resolve(SENTINEL));

    assertThrows(IOException.class,
        () -> manager.startRecordedMigration(KEY, SOURCE, OTHER_FINGERPRINT));
    assertThrows(IOException.class,
        () -> manager.startRecordedMigration(KEY, OTHER_SOURCE, FINGERPRINT));

    assertThrows(IOException.class,
        () -> manager.promoteRecordedGenerationToActive(KEY, OTHER_SOURCE, FINGERPRINT, expectedSource));
    assertEquals(stateBytes, Files.readString(base.resolve("state.json")),
        "a source mismatch cannot promote or rewrite the state pointer");
    assertEquals(manifestBytes, Files.readString(target.resolve(MANIFEST)));
    assertEquals(sentinelBytes, Files.readString(target.resolve(SENTINEL)));

    assertThrows(IOException.class,
        () -> manager.promoteRecordedGenerationToActive(KEY, SOURCE, OTHER_FINGERPRINT, expectedSource));
    assertEquals(stateBytes, Files.readString(base.resolve("state.json")));
    assertEquals(manifestBytes, Files.readString(target.resolve(MANIFEST)));
    assertEquals(sentinelBytes, Files.readString(target.resolve(SENTINEL)));
    assertEquals(started.building_generation(), new IndexGenerationManager(base)
        .readStateBestEffort().building_generation());
    assertEquals(2, directoryCount(base.resolve("indices")));
  }

  @Test
  void invalidKeySourceAndFingerprintCannotCreateARecordedTarget() throws Exception {
    Path base = temp.resolve("invalid-inputs");
    var manager = new IndexGenerationManager(base);
    manager.initializeOrLoad();
    String initialState = Files.readString(base.resolve("state.json"));

    assertThrows(IOException.class, () -> manager.startRecordedMigration("not-a-uuid", SOURCE, FINGERPRINT));
    assertThrows(IOException.class, () -> manager.startRecordedMigration(KEY, "  ", FINGERPRINT));
    assertThrows(IOException.class,
        () -> manager.startRecordedMigration(KEY, "x".repeat(257), FINGERPRINT));
    assertThrows(IOException.class,
        () -> manager.startRecordedMigration(KEY, Character.toString((char) 1), FINGERPRINT));
    assertThrows(IOException.class,
        () -> manager.startRecordedMigration(KEY, SOURCE, OTHER_FINGERPRINT.toUpperCase(java.util.Locale.ROOT)));
    assertThrows(IOException.class,
        () -> manager.startRecordedMigration(KEY, SOURCE, "invalid"));

    assertEquals(initialState, Files.readString(base.resolve("state.json")));
    assertEquals(1, directoryCount(base.resolve("indices")));
    assertFalse(Files.exists(generation(base, KEY), LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void adoptsOnlyTheOwnedPristineOrphanAfterRestoringPreStartState() throws Exception {
    Path base = temp.resolve("owned-orphan");
    Orphan orphan = createOrphan(base, KEY);
    assertEquals(orphan.initialStateBytes(), Files.readString(base.resolve("state.json")),
        "the fixture restores the pre-start authoritative pointer before adoption");
    assertRecordedMetadata(base, IndexGenerationManager.recordedGenerationId(KEY), SOURCE, FINGERPRINT);

    var adopted = new IndexGenerationManager(base).startRecordedMigration(KEY, SOURCE, FINGERPRINT);
    assertEquals(orphan.started().building_generation(), adopted.building_generation());
    assertEquals("MIGRATING", adopted.migration_state());
    assertEquals(2, directoryCount(base.resolve("indices")));
    assertRecordedMetadata(base, orphan.started().building_generation(), SOURCE, FINGERPRINT);
  }

  @ParameterizedTest
  @EnumSource(OrphanMutation.class)
  void corruptForeignOrNonPristineOrphanIsNeverAdopted(OrphanMutation mutation) throws Exception {
    Path base = temp.resolve("orphan-" + mutation.name().toLowerCase(java.util.Locale.ROOT));
    Orphan orphan = createOrphan(base, KEY);
    Path target = generation(base, KEY);
    Path manifest = target.resolve(MANIFEST);
    Path sentinel = target.resolve(SENTINEL);
    switch (mutation) {
      case CORRUPT_MANIFEST -> Files.writeString(manifest, "{invalid", StandardCharsets.UTF_8);
      case FOREIGN_SENTINEL -> Files.writeString(sentinel,
          Files.readString(sentinel).replace("generation_id=" + IndexGenerationManager.recordedGenerationId(KEY),
              "generation_id=g-foreign"), StandardCharsets.UTF_8);
      case NON_PRISTINE -> Files.writeString(target.resolve("unowned.data"), "not a pristine orphan");
    }

    assertOrphanRemainsUnadopted(base, orphan);
  }

  @Test
  void symlinkedOwnershipMetadataIsNotAnAdoptableOrphan() throws Exception {
    Path base = temp.resolve("orphan-symlink");
    Orphan orphan = createOrphan(base, KEY);
    Path target = generation(base, KEY);
    Path sentinel = target.resolve(SENTINEL);
    Path linkTarget = temp.resolve("sentinel-outside-generation.txt");
    Files.move(sentinel, linkTarget, StandardCopyOption.REPLACE_EXISTING);
    try {
      Files.createSymbolicLink(sentinel, linkTarget);
    } catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
      assumeTrue(false, "This filesystem does not permit a test-owned symlink: " + unsupported);
    }
    assertTrue(Files.isSymbolicLink(sentinel));
    assertOrphanRemainsUnadopted(base, orphan);
  }

  @ParameterizedTest
  @EnumSource(CurrentPointer.class)
  void missingOrCorruptCurrentPointerCannotCreateOrAdoptServingState(CurrentPointer pointer) throws Exception {
    Path base = temp.resolve("current-pointer-" + pointer.name().toLowerCase(java.util.Locale.ROOT));
    Path state = base.resolve("state.json");
    if (pointer == CurrentPointer.CORRUPT) {
      Files.createDirectories(base);
      Files.writeString(state, "{broken current pointer", StandardCharsets.UTF_8);
    }
    String corruptBytes = pointer == CurrentPointer.CORRUPT ? Files.readString(state) : null;

    assertThrows(IOException.class,
        () -> new IndexGenerationManager(base).startRecordedMigration(KEY, SOURCE, FINGERPRINT));
    if (corruptBytes == null) {
      assertFalse(Files.exists(state), "recorded start cannot synthesize an authoritative state pointer");
    } else {
      assertEquals(corruptBytes, Files.readString(state), "corrupt authoritative bytes remain untouched");
    }
    assertFalse(Files.exists(base.resolve("indices")), "no fallback serving generation may be created");
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing-phase", "duplicate-phase", "trailing-document", "unknown-field"})
  void ambiguousCurrentPointerCannotAuthorizeExactStart(String corruption) throws Exception {
    Path base = temp.resolve(corruption);
    var manager = new IndexGenerationManager(base);
    manager.initializeOrLoad();
    Path state = base.resolve("state.json");
    var node = (tools.jackson.databind.node.ObjectNode) JSON.readTree(Files.readString(state));
    String bytes;
    if (corruption.equals("missing-phase")) {
      node.remove("migration_state");
      bytes = JSON.writeValueAsString(node);
    } else if (corruption.equals("duplicate-phase")) {
      bytes = JSON.writeValueAsString(node).replaceFirst("\\{", "{\"migration_state\":\"FAILED\",");
    } else if (corruption.equals("trailing-document")) {
      bytes = JSON.writeValueAsString(node) + " {}";
    } else {
      node.put("unknown_authority", true);
      bytes = JSON.writeValueAsString(node);
    }
    Files.writeString(state, bytes);
    assertThrows(IOException.class, () -> manager.startRecordedMigration(KEY, SOURCE, FINGERPRINT));
    assertEquals(bytes, Files.readString(state));
    assertFalse(Files.exists(base.resolve("indices").resolve("g-" + KEY)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"MIGRATING", "SWITCHING"})
  void servingGenerationCannotAlsoBeTheRecordedBuildingTarget(String phase) throws Exception {
    Path base = temp.resolve("same-active-building-" + phase);
    var manager = new IndexGenerationManager(base);
    manager.initializeOrLoad();
    manager.startRecordedMigration(KEY, SOURCE, FINGERPRINT);
    manager.promoteBuildingGenerationToActive();
    Path state = base.resolve("state.json");
    var node = (tools.jackson.databind.node.ObjectNode) JSON.readTree(Files.readString(state));
    node.put("migration_state", phase);
    node.put("building_generation", IndexGenerationManager.recordedGenerationId(KEY));
    String bytes = JSON.writeValueAsString(node);
    Files.writeString(state, bytes);
    String manifest = Files.readString(generation(base, KEY).resolve(MANIFEST));
    assertThrows(IOException.class, () -> manager.startRecordedMigration(KEY, SOURCE, FINGERPRINT));
    assertEquals(bytes, Files.readString(state));
    assertEquals(manifest, Files.readString(generation(base, KEY).resolve(MANIFEST)));
  }

  @Test
  void concurrentExactStartsAcrossManagersChooseOneTargetWithoutLosingDirectory() throws Exception {
    Path base = temp.resolve("concurrent-start");
    new IndexGenerationManager(base).initializeOrLoad();
    var firstManager = new IndexGenerationManager(base);
    var secondManager = new IndexGenerationManager(base);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<StartAttempt> first = workers.submit(() -> startTogether(firstManager, KEY, ready, start));
      Future<StartAttempt> second = workers.submit(() -> startTogether(secondManager, OTHER_KEY, ready, start));
      assertTrue(ready.await(5, TimeUnit.SECONDS), "both distinct manager instances reach the start gate");
      start.countDown();
      StartAttempt firstAttempt = first.get(15, TimeUnit.SECONDS);
      StartAttempt secondAttempt = second.get(15, TimeUnit.SECONDS);

      assertEquals(1, (firstAttempt.state() == null ? 0 : 1) + (secondAttempt.state() == null ? 0 : 1));
      StartAttempt winner = firstAttempt.state() != null ? firstAttempt : secondAttempt;
      StartAttempt loser = firstAttempt.state() == null ? firstAttempt : secondAttempt;
      assertNotNull(loser.failure(), "the losing exact start must refuse with IOException");
      String winnerKey = firstAttempt.state() != null ? KEY : OTHER_KEY;
      String losingKey = firstAttempt.state() == null ? KEY : OTHER_KEY;
      assertEquals(IndexGenerationManager.recordedGenerationId(winnerKey), winner.state().building_generation());
      assertTrue(Files.isDirectory(generation(base, winnerKey)));
      assertFalse(Files.exists(generation(base, losingKey), LinkOption.NOFOLLOW_LINKS),
          "the losing operation key cannot leave an unpointed target directory");
      assertEquals(2, directoryCount(base.resolve("indices")), "only the initial active and winner exist");
      assertEquals(winner.state().building_generation(), new IndexGenerationManager(base)
          .readStateBestEffort().building_generation());
    } finally {
      start.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private static StartAttempt startTogether(IndexGenerationManager manager, String key,
      CountDownLatch ready, CountDownLatch start) throws InterruptedException {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start gate timed out");
    try {
      return new StartAttempt(manager.startRecordedMigration(key, SOURCE, FINGERPRINT), null);
    } catch (IOException refused) {
      return new StartAttempt(null, refused);
    }
  }

  private static void assertOrphanRemainsUnadopted(Path base, Orphan orphan) throws Exception {
    assertThrows(IOException.class,
        () -> new IndexGenerationManager(base).startRecordedMigration(KEY, SOURCE, FINGERPRINT));
    assertEquals(orphan.initialStateBytes(), Files.readString(base.resolve("state.json")),
        "refusal cannot replace the authoritative pre-start state");
    assertEquals(2, directoryCount(base.resolve("indices")));
    assertTrue(Files.exists(generation(base, KEY), LinkOption.NOFOLLOW_LINKS),
        "refusal leaves the untrusted orphan for inspection");
  }

  private static Orphan createOrphan(Path base, String key) throws Exception {
    var manager = new IndexGenerationManager(base);
    manager.initializeOrLoad();
    String initialStateBytes = Files.readString(base.resolve("state.json"));
    var started = manager.startRecordedMigration(key, SOURCE, FINGERPRINT);
    Files.writeString(base.resolve("state.json"), initialStateBytes, StandardCharsets.UTF_8);
    return new Orphan(initialStateBytes, started);
  }

  private static void assertRecordedMetadata(Path base, String generationId, String source,
      String fingerprint) throws Exception {
    Path directory = base.resolve("indices").resolve(generationId);
    Path manifestPath = directory.resolve(MANIFEST);
    Path sentinelPath = directory.resolve(SENTINEL);
    assertTrue(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS));
    assertTrue(Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS));
    assertTrue(Files.isRegularFile(sentinelPath, LinkOption.NOFOLLOW_LINKS));
    try (var paths = Files.list(directory)) {
      assertEquals(Set.of(MANIFEST, SENTINEL),
          paths.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
    }

    JsonNode manifest = JSON.readTree(Files.readString(manifestPath, StandardCharsets.UTF_8));
    assertEquals(2, manifest.path("format_version").asInt());
    assertEquals(generationId, manifest.path("generation_id").asText());
    assertEquals(source, manifest.path("source").asText());
    assertEquals(fingerprint, manifest.path("target_index_fingerprint").asText());
    long created = manifest.path("created_at_ms").asLong();
    assertTrue(created > 0);
    assertEquals("justsearch_generation_sentinel_v1\ngeneration_id=" + generationId
            + "\ncreated_at_ms=" + created + "\n",
        Files.readString(sentinelPath, StandardCharsets.UTF_8));
  }

  private static Path generation(Path base, String key) throws IOException {
    return base.resolve("indices").resolve(IndexGenerationManager.recordedGenerationId(key));
  }

  private static long directoryCount(Path indices) throws IOException {
    if (!Files.isDirectory(indices)) return 0;
    try (var entries = Files.list(indices)) {
      return entries.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).count();
    }
  }

  private record Orphan(String initialStateBytes, IndexGenerationManager.State started) {}

  private record StartAttempt(IndexGenerationManager.State state, IOException failure) {}

  private enum OrphanMutation { CORRUPT_MANIFEST, FOREIGN_SENTINEL, NON_PRISTINE }

  private enum CurrentPointer { ABSENT, CORRUPT }
}
