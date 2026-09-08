/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.StatusRequest;
import io.justsearch.ipc.StatusResponse;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 915 (live validation D1) — during a blue/green migration the compatibility surface
 * describes the generation being SEARCHED, not the one being written.
 *
 * <p>It described the wrong one. `IndexStatusOps`'s stored-fingerprint suppliers were wired to the
 * INGEST runtime, which during a migration is Green — a brand-new generation stamped with the shape
 * this runtime writes. So `current.equals(stored)` held, `/api/status` reported
 * `indexSchemaCompatState=COMPATIBLE` and `reindexRequired=false`, and every query being answered
 * from the stale-shape Blue was described as fine. Live validation observed it twice, in two
 * independent arms.
 *
 * <p>This is a wiring test on purpose: it drives a real {@link WorkerIngestService} with two DIFFERENT
 * runtimes rather than handing {@code IndexStatusOps} the values under test, because supplying them
 * by hand is exactly the mistake — the defect was never in the comparison, it was in which index the
 * comparison was pointed at.
 */
final class MidMigrationCompatSurfaceTest {

  private static final String OLD_SHAPE = "b".repeat(64);

  private RunningRuntime blue;
  private RunningRuntime green;

  @AfterEach
  void tearDown() {
    for (RunningRuntime r : new RunningRuntime[] {green, blue}) {
      if (r != null) {
        try {
          r.close();
        } catch (Exception ignored) {
          // teardown best-effort
        }
      }
    }
  }

  @Test
  void midMigrationTheSurfaceDescribesBlueAndAsksForTheReindexItIsPerforming(@TempDir Path tempDir)
      throws Exception {
    blue = open(tempDir.resolve("blue"), OLD_SHAPE, 2);
    green = open(tempDir.resolve("green"), null, 1);

    StatusResponse status = statusOf(green, blue, tempDir);

    assertEquals(
        OLD_SHAPE,
        status.getCompatibility().getSchemaFpStored(),
        "the stored shape reported is the one the user's searches reach");
    assertEquals(
        "BLOCKED_MISMATCH",
        status.getCompatibility().getSchemaCompatState(),
        "Blue carries a shape this runtime does not write, so the ordinary comparison says so —"
            + " no MIGRATING special case needed, and no COMPATIBLE lie either");
    assertEquals(
        "schema_mismatch",
        status.getCompatibility().getReindexRequiredReason(),
        "and names the reason the Head maps to index.schema_mismatch");
    assertTrue(status.getCompatibility().getReindexRequired());
    assertEquals(
        1,
        status.getCore().getDocCount(),
        "documents indexed still counts GREEN while one is being built — that is the build's"
            + " progress, and reporting Blue there would hide it");
    assertEquals(
        2, status.getCore().getSearchableDocCount(), "while searchable counts what Blue serves");
  }

  /** After the cutover the served generation IS the new one, and the same comparison clears. */
  @Test
  void afterTheCutoverTheSameComparisonReportsCompatible(@TempDir Path tempDir) throws Exception {
    green = open(tempDir.resolve("promoted"), null, 1);

    StatusResponse status = statusOf(green, green, tempDir);

    assertEquals("COMPATIBLE", status.getCompatibility().getSchemaCompatState());
    assertFalse(status.getCompatibility().getReindexRequired());
  }

  private static RunningRuntime open(Path path, String fingerprintOverride, int docs)
      throws Exception {
    Map<String, Object> meta = new HashMap<>(new SsotCommitMetadataSource().build());
    if (fingerprintOverride != null) {
      meta.put(IndexFingerprint.COMMIT_META_KEY, fingerprintOverride);
    }
    Map<String, Object> frozen = Map.copyOf(meta);
    RunningRuntime r =
        IndexSchema.fromCatalog(
                FieldCatalogDef.forChunkTesting(0),
                () -> frozen,
                new JsonSchemaCommitMetadataValidator())
            .atPath(path)
            .open();
    for (int i = 0; i < docs; i++) {
      r.indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID, "d-" + i,
                      SchemaFields.DOC_UID, "d-" + i + "#0",
                      SchemaFields.CONTENT, "document " + i)));
    }
    r.commitOps().commitAndTrack();
    r.commitOps().maybeRefreshBlocking();
    return r;
  }

  /** Drives the production {@code indexStatus} RPC over the real service wiring. */
  private static StatusResponse statusOf(
      RunningRuntime ingest, io.justsearch.adapters.lucene.runtime.LuceneRuntime search, Path dir) {
    JobQueue jobQueue = mock(JobQueue.class);
    when(jobQueue.jobStateCounts()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 0, 0));
    when(jobQueue.pendingBytes()).thenReturn(JobQueue.PendingBytes.EMPTY);

    WorkerIngestService service =
        new WorkerIngestService(
            jobQueue,
            null,
            // Non-null: buildCore reads the heartbeat unguarded, and WorkerIngestService turns any
            // RuntimeException into a blank ERROR payload - which would make every assertion below
            // fail on an empty string instead of on the value under test.
            mock(io.justsearch.indexerworker.coordination.WorkerSignalBus.class),
            IndexingPacing.unthrottled(),
            null,
            dir,
            ingest,
            search,
            null,
            0L);

    return service.indexStatus(StatusRequest.newBuilder().build(), CallContext.none());
  }
}
