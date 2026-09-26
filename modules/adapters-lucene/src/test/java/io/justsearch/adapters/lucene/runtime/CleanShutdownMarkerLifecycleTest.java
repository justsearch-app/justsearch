/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 915 open item O14 — who is allowed to invalidate the clean-shutdown marker.
 *
 * <p>The marker exists to catch a WRITER dying mid-commit, so the next open can escalate to a FULL
 * integrity scan. Consuming it was fused into the read: any open cleared it, including a read-only
 * one that takes no writer and therefore cannot leave the index unclean. A runtime that serves the
 * active generation read-only for its whole life — a blue/green migration, and every boot of the
 * exhausted-brake state — deleted a marker it would never re-write, so the following boot declared a
 * crash that had not happened and paid a FULL verification for it. Live validation observed exactly
 * that on one generation across five consecutive boots.
 *
 * <p>Reading the answer and invalidating it are now separate acts, and only the writer does the
 * second.
 */
final class CleanShutdownMarkerLifecycleTest extends LuceneExecutorTestBase {

  private static final CommitMetadataSource META = () -> new SsotCommitMetadataSource().build();

  @Test
  void repeatedReadOnlyOpensNeverInvalidateTheMarker(@TempDir Path tempDir) throws Exception {
    Path index = tempDir.resolve("blue");
    seedAndCloseCleanly(index);
    assertTrue(
        CleanShutdownMarker.wasClean(index),
        "precondition: a clean writable session recorded its shutdown");

    // The g-20260903-052152 shape: the same generation served read-only, boot after boot.
    for (int boot = 1; boot <= 5; boot++) {
      try (LuceneRuntime _ = builder(index).openReadOnly()) {
        assertTrue(
            CleanShutdownMarker.wasClean(index),
            "boot " + boot + ": a read-only open writes nothing, so it cannot make the index"
                + " unclean — and must not report that it did");
      }
      assertTrue(
          CleanShutdownMarker.wasClean(index),
          "boot " + boot + ": nor may closing one consume the marker it never earned");
    }
  }

  @Test
  void aWritableOpenConsumesItAndACleanCloseWritesItBack(@TempDir Path tempDir) throws Exception {
    Path index = tempDir.resolve("active");
    seedAndCloseCleanly(index);
    assertTrue(CleanShutdownMarker.wasClean(index), "precondition");

    RunningRuntime writable = builder(index).open();
    assertFalse(
        CleanShutdownMarker.wasClean(index),
        "a writer exists now, so this session CAN die mid-commit: the previous clean shutdown is"
            + " no longer evidence about the next boot");
    writable.close();
    assertTrue(
        CleanShutdownMarker.wasClean(index),
        "and a clean close earns it back — otherwise every restart would pay a FULL scan");
  }

  /** A crash is an unconsumed absence: the writer opened, never closed, marker gone. */
  @Test
  void aWriterThatNeverClosesLeavesTheIndexMarkedUnclean(@TempDir Path tempDir) throws Exception {
    Path index = tempDir.resolve("crashed");
    seedAndCloseCleanly(index);

    RunningRuntime writable = builder(index).open();
    assertFalse(CleanShutdownMarker.wasClean(index), "the writer consumed it");
    // No close() — the shape of a process that died. The file handle is released by the JVM at
    // test exit; what matters is that nothing re-wrote the marker.
    assertFalse(
        CleanShutdownMarker.wasClean(index),
        "so the next open escalates to FULL, which is the whole point of the marker");
    writable.close();
  }

  @Test
  void terminalWriterCannotEarnACleanShutdownMarker(@TempDir Path tempDir) throws Exception {
    Path index = tempDir.resolve("terminal");
    seedAndCloseCleanly(index);

    RunningRuntime writable = builder(index).open();
    var writer = writable.session().snapshot.writer();
    writer.onTragicEvent(new IOException("forced terminal writer"), "test");
    writer.rollback();
    assertFalse(writer.isOpen(), "precondition: Lucene permanently closed the writer");

    writable.close();

    assertFalse(
        CleanShutdownMarker.wasClean(index),
        "a normally-returning close cannot relabel an already-terminal writer as clean");
  }

  private LuceneRuntimeBuilder builder(Path index) {
    return IndexSchema.fromCatalog(
            FieldCatalogDef.forTesting(768), () -> META, new JsonSchemaCommitMetadataValidator())
        .atPath(index).withExecutorRegistrations(testLuceneExecutors());
  }

  private void seedAndCloseCleanly(Path index) throws Exception {
    Files.createDirectories(index);
    try (RunningRuntime r = builder(index).open()) {
      r.indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID, "seed",
                      SchemaFields.DOC_UID, "seed#0",
                      SchemaFields.CONTENT, "a document Blue keeps serving")));
      r.commitOps().commitAndTrack(CommitReason.DRAIN);
    }
    assertEquals(
        1,
        Files.exists(CleanShutdownMarker.pathFor(index)) ? 1 : 0,
        "the seeding session closed cleanly");
  }
}
