/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.StatusResponse;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 915 §C.5a — the reported schema-compatibility state on an index that carries no recorded
 * {@code index_fingerprint}.
 *
 * <p>Two indexes look identical at the commit: a brand-new one that has simply not been written to
 * yet, and a pre-upgrade one full of documents whose shape was never recorded. Only the second needs
 * a rebuild. The open-time guard and this status path must agree about which is which, or a fresh
 * install is told on first launch to rebuild an index containing nothing — so both call the same
 * predicate ({@code ParityDiagnostics.isIndexWithoutRecordedFingerprint}).
 */
final class SchemaCompatFreshInstallTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  @TempDir Path tempDir;
  private RunningRuntime runtime;

  @AfterEach
  void tearDown() throws Exception {
    if (runtime != null) {
      runtime.close();
    }
  }

  @Test
  void aFreshEmptyIndexIsCompatibleRatherThanBlockedLegacy() throws Exception {
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open();

    StatusResponse status = buildStatus();
    assertEquals(
        "COMPATIBLE",
        status.getCompatibility().getSchemaCompatState(),
        "an empty index has no content that could have been written under the wrong shape;"
            + " reporting BLOCKED_LEGACY here would demand a rebuild on first launch");
    assertFalse(
        status.getCompatibility().getReindexRequired(),
        "and it must not be counted as needing a reindex");
  }

  @Test
  void anIndexHoldingDocumentsWithNoRecordedShapeIsBlockedLegacy() throws Exception {
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open();
    runtime
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "legacy-doc",
                    SchemaFields.DOC_UID, "legacy-doc#0",
                    SchemaFields.CONTENT, "written before the shape was recorded")));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    StatusResponse status = buildStatus();
    assertEquals(
        "BLOCKED_LEGACY",
        status.getCompatibility().getSchemaCompatState(),
        "documents of unrecorded shape are exactly the case the one-time rebuild exists for");
    assertTrue(status.getCompatibility().getReindexRequired());
  }

  /**
   * The other half of the same rule, added in round 4. A STALE fingerprint on an index holding
   * nothing is as much a non-event as an absent one — the next commit rewrites the whole user-data
   * map — and the guard now declines to migrate it. Reporting BLOCKED_MISMATCH here would put the
   * status surface and the guard back into disagreement about an empty index, in the opposite
   * direction from the case above.
   */
  @Test
  void anEmptyIndexWithAStaleFingerprintIsCompatible() throws Exception {
    Map<String, Object> stale =
        new java.util.HashMap<>(
            new io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource().build());
    stale.put(
        io.justsearch.adapters.lucene.commit.IndexFingerprint.COMMIT_META_KEY, "a".repeat(64));
    Map<String, Object> frozen = Map.copyOf(stale);
    runtime =
        IndexSchema.fromCatalog(
                FieldCatalogDef.forChunkTesting(0),
                () -> frozen,
                new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator())
            .atPath(tempDir).withExecutorRegistrations(testLuceneExecutors())
            .open();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    StatusResponse status =
        buildStatus(
            Map.of(
                io.justsearch.adapters.lucene.commit.IndexFingerprint.COMMIT_META_KEY,
                "a".repeat(64)));
    assertEquals(
        "COMPATIBLE",
        status.getCompatibility().getSchemaCompatState(),
        "a stale shape recorded against no documents describes nothing; the next commit re-stamps"
            + " it, so demanding a rebuild would rebuild emptiness");
    assertFalse(status.getCompatibility().getReindexRequired());
  }

  /** Drives the production {@code buildStatusResponse} over a real index. */
  private StatusResponse buildStatus() {
    return buildStatus(null);
  }

  /**
   * As above, with an explicit open-time commit user-data snapshot. Passing {@code null} (the
   * default) makes {@code safeSchemaFingerprintStored()} return "" — which is right for the
   * absent-fingerprint cases but would silently route a STALE-fingerprint case through the blank
   * branch, so the test would pass without ever reaching the comparison it exists to pin.
   */
  private StatusResponse buildStatus(Map<String, String> openTimeUserData) {
    JobQueue jobQueue = mock(JobQueue.class);
    when(jobQueue.jobStateCounts()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 0, 0));
    when(jobQueue.pendingBytes()).thenReturn(JobQueue.PendingBytes.EMPTY);

    IndexStatusOps ops =
        new IndexStatusOps(
            jobQueue,
            tempDir,
            runtime.indexCountOps(),
            null,
            null,
            null,
            null,
            openTimeUserData == null ? null : () -> openTimeUserData,
            null,
            null,
            null,
            OperationalMetrics.getInstance(),
            mock(IndexingLoop.class),
            mock(WorkerSignalBus.class),
            0L);
    return ops.buildStatusResponse();
  }
}
