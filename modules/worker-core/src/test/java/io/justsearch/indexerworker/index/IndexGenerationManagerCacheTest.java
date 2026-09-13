package io.justsearch.indexerworker.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 589 Fix A — {@code readStateBestEffort()}'s cache must reflect writes and be a true cache.
 *
 * <p>The race the fix targets — a non-volatile {@code lastReadVersion} paired non-atomically with the
 * cached {@code State} and a non-atomic {@code stateVersion++} — is removed by-construction (one
 * immutable holder behind a single {@code volatile}); it is not deterministically unit-testable. This
 * pins the single-threaded contract the holder must preserve: a cache HIT returns the same instance
 * when nothing changed, and a write INVALIDATES the cache so the next read re-loads the new state.
 * {@link IndexGenerationManager} had no unit test before this.
 */
final class IndexGenerationManagerCacheTest {

  @Test
  void readStateBestEffort_cachesUntilWrite_thenReflectsTheWrite(@TempDir Path tempDir)
      throws Exception {
    IndexGenerationManager mgr = new IndexGenerationManager(tempDir.resolve("index"));
    var layout = mgr.initializeOrLoad();
    assertNotNull(layout, "layout created");

    IndexGenerationManager.State read1 = mgr.readStateBestEffort();
    IndexGenerationManager.State read2 = mgr.readStateBestEffort();
    assertNotNull(read1, "state present after initializeOrLoad");
    assertSame(read1, read2, "second read is a cache hit (same instance, no re-parse)");

    // A real write (set the pause flag) must invalidate the cache. (setMigrationPaused persists
    // via writeState without creating a generation directory, so it's a clean cache-invalidation
    // probe.)
    mgr.setMigrationPaused(true, "test");
    IndexGenerationManager.State read3 = mgr.readStateBestEffort();
    assertNotSame(read1, read3, "cache invalidated on write — a fresh State is re-loaded from disk");
    assertEquals(Boolean.TRUE, read3.migration_paused(), "re-read reflects the write");
    assertEquals("test", read3.pause_reason(), "re-read carries the persisted reason");

    // The new state is itself cached until the next write.
    assertSame(read3, mgr.readStateBestEffort(), "post-write read is cached again");
  }

  /**
   * Lane F stage A item A12 — a write through ONE manager must be visible to every other manager
   * over the same {@code state.json}.
   *
   * <p>This is not hypothetical layering: one index base path has several managers in a single JVM.
   * {@code KnowledgeServer} builds one for the migration enumerator and the cutover monitor
   * (KnowledgeServer.java:611); {@code WorkerIngestService} builds its own from the same
   * {@code indexBasePath} for the migration control calls (WorkerIngestService.java:177); the
   * switch-buffer replay builds two more temporaries (KnowledgeServerMigrationOps.java:542, :718).
   * Before the stamp check, a cache was invalidated only by its OWN writes, so
   * {@code resumeMigration} — which writes through the service's manager — was invisible to the
   * enumerator's manager, which went on serving {@code migration_paused=true} out of its cache and
   * never resumed. The in-process migration test
   * ({@code app-engine .../EngineMigrationLifecycleTest}) is the end-to-end guard; this is the unit
   * one.
   */
  @Test
  void aWriteThroughOneManagerIsVisibleToAnotherOverTheSameStateFile(@TempDir Path tempDir)
      throws Exception {
    Path indexBase = tempDir.resolve("index");
    IndexGenerationManager writer = new IndexGenerationManager(indexBase);
    assertNotNull(writer.initializeOrLoad(), "layout created");

    IndexGenerationManager reader = new IndexGenerationManager(indexBase);
    IndexGenerationManager.State beforePause = reader.readStateBestEffort();
    assertNotNull(beforePause, "the second manager sees the same state file");
    assertEquals(
        Boolean.FALSE,
        Boolean.TRUE.equals(beforePause.migration_paused()),
        "not paused to begin with");

    writer.setMigrationPaused(true, "cross-instance");
    assertEquals(
        Boolean.TRUE,
        Boolean.TRUE.equals(reader.readStateBestEffort().migration_paused()),
        "the reader must observe the pause written through the other manager");

    writer.setMigrationPaused(false, null);
    assertEquals(
        Boolean.FALSE,
        Boolean.TRUE.equals(reader.readStateBestEffort().migration_paused()),
        "and the resume too — this is the direction that silently wedged the migration enumerator");
  }

  /**
   * The stamp must be content, not (mtime, size) — review pass, lane F stage A major 11.
   *
   * <p>The cross-instance test above passes under EITHER stamp, because its two writes happen to
   * differ in length ({@code "migration_paused":true} vs {@code false}) and far enough apart in time
   * to land on different timestamps. That made it look like the stamp was verified when only the
   * easy case was. This is the hard case, and it is not contrived: {@code MIGRATING} and
   * {@code SWITCHING} are both nine characters, so the cutover's own state transition rewrites
   * state.json to exactly the same byte length. Pair that with a filesystem timestamp that has not
   * ticked — routine on Windows, where NTFS updates the last-write time lazily — and (mtime, size)
   * reports "unchanged" for a file whose content changed.
   *
   * <p>The consequence is not a delayed refresh. It is the cache serving a pre-cutover state to a
   * component that has to see the cutover, which is the same class of wedge the cross-instance
   * defect caused. So the mtime is pinned back explicitly here rather than hoped for: the test
   * asserts the property under the adverse precondition instead of waiting for a machine fast
   * enough to produce it by chance.
   */
  @Test
  void aSameLengthWriteWithinOneTimestampTickIsStillSeen(@TempDir Path tempDir) throws Exception {
    Path indexBase = tempDir.resolve("index");
    IndexGenerationManager writer = new IndexGenerationManager(indexBase);
    var layout = writer.initializeOrLoad();
    assertNotNull(layout, "layout created");
    Path statePath = layout.statePath();

    // Get the file into MIGRATING first. Written directly because the point is the byte-level
    // collision, and going through the migration API would drag in generation directories the
    // cache has nothing to do with.
    String migrating =
        java.nio.file.Files.readString(statePath, java.nio.charset.StandardCharsets.UTF_8)
            .replace("\"IDLE\"", "\"MIGRATING\"");
    java.nio.file.Files.writeString(statePath, migrating, java.nio.charset.StandardCharsets.UTF_8);

    IndexGenerationManager reader = new IndexGenerationManager(indexBase);
    assertEquals(
        "MIGRATING",
        reader.readStateBestEffort().migration_state(),
        "precondition: the reader has parsed and cached the MIGRATING revision");

    java.nio.file.attribute.FileTime frozen = java.nio.file.Files.getLastModifiedTime(statePath);
    String switching = migrating.replace("\"MIGRATING\"", "\"SWITCHING\"");
    assertEquals(
        migrating.length(),
        switching.length(),
        "precondition: this test only means something if the two revisions are the same length");
    java.nio.file.Files.writeString(statePath, switching, java.nio.charset.StandardCharsets.UTF_8);
    // The tick that did not happen.
    java.nio.file.Files.setLastModifiedTime(statePath, frozen);

    assertEquals(
        "SWITCHING",
        reader.readStateBestEffort().migration_state(),
        "a same-length rewrite under an unchanged mtime must still invalidate the cache. Under an"
            + " (mtime, size) stamp this returns MIGRATING — the cutover is invisible to every"
            + " reader holding a cache, which is the stale read the stamp was added to prevent.");
  }
}
