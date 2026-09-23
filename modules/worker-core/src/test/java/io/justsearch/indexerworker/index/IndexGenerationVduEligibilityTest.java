package io.justsearch.indexerworker.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
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
    assertEquals(initial.activeGenerationId(),
        manager.idleActiveGeneration(capturedActive).orElseThrow());

    IndexGenerationManager.State migrating = manager.startMigration("eligibility-test");
    assertNotNull(migrating.building_generation(), "migration creates a building generation");

    IndexGenerationManager.State promoted = manager.promoteBuildingGenerationToActive();
    assertNotNull(promoted, "promotion produces a state");
    assertNotEquals(
        initial.activeGenerationId(),
        promoted.active_generation(),
        "promotion must advance the active pointer");
    Path promotedPath = manager.resolveGenerationPathStrict(promoted.active_generation());
    assertFalse(
        manager.isIdleActiveGeneration(capturedActive),
        "an IDLE state with a changed active pointer must refuse the old target");
    assertTrue(manager.idleActiveGeneration(capturedActive).isEmpty());
    assertEquals(promoted.active_generation(),
        manager.idleActiveGeneration(promotedPath).orElseThrow());
    assertTrue(
        manager.isIdleActiveGeneration(promotedPath),
        "the promoted active generation is eligible after the transition completes");

    assertFalse(manager.isIdleActiveGeneration(capturedActive),
        "the retired predecessor stays ineligible after publication");
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
  void strictActiveObservationKeepsBlueVisibleWhileGreenBuilds(@TempDir Path tempDir)
      throws Exception {
    Path base = tempDir.resolve("active-during-build");
    IndexGenerationManager manager = new IndexGenerationManager(base);
    IndexGenerationManager.IndexLayout blue = manager.initializeOrLoad();

    IndexGenerationManager.State building = manager.startMigration("applied-observation-test");

    assertNotNull(building.building_generation(), "precondition: Green is building");
    assertEquals(
        blue.activeGenerationId(),
        manager.activeGeneration(blue.activeGenerationPath()).orElseThrow(),
        "the committed serving Blue remains the active applied generation during the build");
    assertTrue(
        manager.idleActiveGeneration(blue.activeGenerationPath()).isEmpty(),
        "the mutation-only idle contract remains stricter than read-only observation");
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

  @Test
  void strictReadWaitsForSameJvmExclusiveStateLockThenReadsTheState(@TempDir Path tempDir)
      throws Exception {
    Path base = tempDir.resolve("locked-read");
    IndexGenerationManager manager = new IndexGenerationManager(base);
    IndexGenerationManager.IndexLayout layout = manager.initializeOrLoad();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch readStarted = new CountDownLatch(1);

    try (FileChannel lockChannel =
        FileChannel.open(layout.statePath(), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      FileLock exclusiveLock = lockChannel.lock();
      try {
        var read =
            executor.submit(
                () -> {
                  readStarted.countDown();
                  return manager.idleActiveGeneration(layout.activeGenerationPath());
                });

        assertTrue(readStarted.await(1, TimeUnit.SECONDS), "reader task must start");
        assertThrows(
            TimeoutException.class,
            () -> read.get(100, TimeUnit.MILLISECONDS),
            "strict read must wait while another channel holds an exclusive state.json lock");

        exclusiveLock.release();
        assertEquals(
            layout.activeGenerationId(),
            read.get(2, TimeUnit.SECONDS).orElseThrow(),
            "after the lock releases, strict eligibility reads the authoritative active generation");
      } finally {
        if (exclusiveLock.isValid()) exclusiveLock.release();
      }
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS), "reader executor must terminate");
    }
  }

  @Test
  void strictReadReopensAfterLockWaitAndRefusesAStateChangedByTheGenerationOwner(
      @TempDir Path tempDir) throws Exception {
    Path base = tempDir.resolve("changed-while-locked");
    IndexGenerationManager manager = new IndexGenerationManager(base);
    IndexGenerationManager.IndexLayout layout = manager.initializeOrLoad();
    assertNotNull(
        manager.readStateBestEffort(), "prime the generation owner's state cache before contention");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch readStarted = new CountDownLatch(1);

    try (FileChannel lockChannel =
        FileChannel.open(layout.statePath(), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      FileLock exclusiveLock = lockChannel.lock();
      try {
        var read =
            executor.submit(
                () -> {
                  readStarted.countDown();
                  return manager.idleActiveGeneration(layout.activeGenerationPath());
                });

        assertTrue(readStarted.await(1, TimeUnit.SECONDS), "reader task must start");
        assertThrows(
            TimeoutException.class,
            () -> read.get(100, TimeUnit.MILLISECONDS),
            "precondition: the strict read is waiting on the old state file");

        manager.updateMigrationState(IndexGenerationManager.MigrationState.MIGRATING);
        exclusiveLock.release();

        assertTrue(
            read.get(2, TimeUnit.SECONDS).isEmpty(),
            "a fresh read after the generation owner replaces state.json must see MIGRATING, not "
                + "return cached eligibility for the former IDLE state");
      } finally {
        if (exclusiveLock.isValid()) exclusiveLock.release();
      }
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS), "reader executor must terminate");
    }
  }

  @Test
  void coldStateOwnerRefusesLockedAuthoritativeReadWithoutAdoptingOrRewritingIt(
      @TempDir Path tempDir) throws Exception {
    Path base = tempDir.resolve("cold-writer-locked");
    IndexGenerationManager bootstrap = new IndexGenerationManager(base);
    IndexGenerationManager.IndexLayout layout = bootstrap.initializeOrLoad();
    Path statePath = layout.statePath();
    Path previousPath = base.resolve("state.json.prev");
    byte[] originalState = Files.readAllBytes(statePath);
    long originalGenerationCount = generationDirectoryCount(base);
    IndexGenerationManager coldInitializer = new IndexGenerationManager(base);
    IndexGenerationManager coldWriter = new IndexGenerationManager(base);

    try (FileChannel lockChannel =
        FileChannel.open(statePath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      FileLock exclusiveLock = lockChannel.lock();
      try {
        assertThrows(
            IOException.class,
            coldInitializer::initializeOrLoad,
            "a cold initializer must propagate temporary authoritative-read unavailability, not "
                + "treat the locked state as absent and adopt an idle generation");
        assertThrows(
            IOException.class,
            () -> coldWriter.updateMigrationState(IndexGenerationManager.MigrationState.MIGRATING),
            "a cold state update must refuse unavailable input rather than write a recovered "
                + "intermediate IDLE state");
        assertEquals(
            originalGenerationCount,
            generationDirectoryCount(base),
            "failed authoritative reads must not create or adopt another generation");
      } finally {
        if (exclusiveLock.isValid()) exclusiveLock.release();
      }
    }

    assertArrayEquals(
        originalState, Files.readAllBytes(statePath), "failed reads must preserve exact state bytes");
    assertFalse(Files.exists(previousPath), "failed reads must not rotate or create state.json.prev");
    assertEquals(originalGenerationCount, generationDirectoryCount(base));

    coldWriter.updateMigrationState(IndexGenerationManager.MigrationState.MIGRATING);
    assertEquals(
        "MIGRATING",
        new IndexGenerationManager(base).readStateBestEffort().migration_state(),
        "the same state transition succeeds once the temporary lock is released");
  }

  @Test
  void strictReadsRacingRealGenerationWritesSeeOnlyCoherentPointersOrTypedMissingIntervals(
      @TempDir Path tempDir) throws Exception {
    Path base = tempDir.resolve("writer-reader-race");
    IndexGenerationManager manager = new IndexGenerationManager(base);
    IndexGenerationManager.IndexLayout initial = manager.initializeOrLoad();
    AtomicReference<Path> activeTarget = new AtomicReference<>(initial.activeGenerationPath());
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      var writer =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                for (int i = 0; i < 24; i++) {
                  manager.startMigration("strict-read-race-" + i);
                  IndexGenerationManager.State promoted = manager.promoteBuildingGenerationToActive();
                  activeTarget.set(manager.resolveGenerationPathStrict(promoted.active_generation()));
                }
                return null;
              });
      var reader =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                for (int i = 0; i < 400; i++) {
                  Path target = activeTarget.get();
                  try {
                    var eligible = manager.idleActiveGeneration(target);
                    if (eligible.isPresent()) {
                      assertEquals(
                          target.getFileName().toString(),
                          eligible.orElseThrow(),
                          "a successful strict read must name the exact target it validated");
                    }
                  } catch (NoSuchFileException expectedAtomicReplacementGap) {
                    // writeState rotates state.json before installing its completed temporary file.
                    // This existing typed absence is an allowed observation of that brief interval.
                  }
                }
                return null;
              });

      assertTrue(ready.await(2, TimeUnit.SECONDS), "writer and reader must be ready together");
      start.countDown();
      writer.get(10, TimeUnit.SECONDS); // Propagate every real generation-writer failure.
      reader.get(10, TimeUnit.SECONDS); // Parse failures and all non-missing I/O failures propagate.

      Path finalTarget = activeTarget.get();
      assertEquals(
          finalTarget.getFileName().toString(),
          manager.idleActiveGeneration(finalTarget).orElseThrow(),
          "after the writer completes, the final promoted generation must be strictly readable");
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS), "race executor must terminate");
    }
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

  private static long generationDirectoryCount(Path base) throws IOException {
    try (var generations = Files.list(base.resolve("indices"))) {
      return generations.filter(Files::isDirectory).count();
    }
  }
}
