/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
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
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task t-20261013-ef3c41, scenarios S5 and S6 — when the document count or the recorded index
 * shape or metadata cannot be read, the status says unknown ({@code UNAVAILABLE}): never
 * compatible, never 0 documents, never legacy or rebuild required.
 *
 * <p>The count and metadata failures are injected: a Lucene count is read from an already-open
 * in-memory reader, and the recorded metadata from that reader's commit, so no file-system
 * condition a test can create makes those reads fail on demand. The count injection mirrors what
 * {@link IndexCountOps} does on a real failure: {@code docCount()} answers 0 and {@code
 * docCountOrThrow()} throws. The metadata injection is a metadata source whose read throws.
 */
final class SchemaCompatUnknownTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  @TempDir Path tempDir;
  private RunningRuntime runtime;
  private IndexCountOps counts;
  private final AtomicBoolean countsFail = new AtomicBoolean();

  @BeforeEach
  void setUp() throws Exception {
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir)
        .withExecutorRegistrations(testLuceneExecutors()).open();
    for (int i = 0; i < 3; i++) {
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "doc-" + i,
          SchemaFields.DOC_UID, "doc-" + i + "#0",
          SchemaFields.CONTENT, "indexed text " + i)));
    }
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    counts = spy(runtime.indexCountOps());
    doAnswer(inv -> countsFail.get() ? 0L : inv.callRealMethod()).when(counts).docCount();
    org.mockito.stubbing.Answer<Object> countRead = inv -> {
      if (countsFail.get()) throw new IOException("reader unavailable");
      return inv.callRealMethod();
    };
    doAnswer(countRead).when(counts).docCountOrThrow();
    doAnswer(countRead).when(counts).parentDocCountOrThrow();
    doAnswer(inv -> countsFail.get() ? 0 : inv.callRealMethod())
        .when(counts).countByField(anyString(), anyString());
    doAnswer(inv -> {
          if (countsFail.get()) throw new IOException("reader unavailable");
          return inv.callRealMethod();
        })
        .when(counts).countByFieldOrThrow(anyString(), anyString());
  }

  @AfterEach
  void tearDown() throws Exception {
    if (runtime != null) {
      runtime.close();
    }
  }

  @Test
  void anUnreadableCountWithADifferentFingerprintIsNeverCompatible() {
    countsFail.set(true);

    String state = status(Map.of(IndexFingerprint.COMMIT_META_KEY, "a".repeat(64)))
        .getCompatibility().getSchemaCompatState();

    assertNotEquals("COMPATIBLE", state,
        "a different shape holding documents read as compatible because the failed count was 0");
    assertEquals("UNAVAILABLE", state);
  }

  @Test
  void anUnreadableCountWithNoRecordedFingerprintIsUnknownNotCompatible() {
    countsFail.set(true);

    String state = status(null).getCompatibility().getSchemaCompatState();

    assertEquals("UNAVAILABLE", state, "neither compatible nor legacy: the count is unknown");
  }

  @Test
  void anUnreadableCountNeverShowsZeroDocuments() {
    IndexStatusOps ops = ops(null);
    assertEquals(3, ops.buildStatusResponse().getCore().getDocCount(), "precondition: a readable count");
    countsFail.set(true);

    StatusResponse status = ops.buildStatusResponse();

    assertEquals(3, status.getCore().getDocCount(), "a failed read must not report an empty index");
    assertEquals(3, status.getMigration().getActiveDocCount());
  }

  @Test
  void anUnparseableStoredFingerprintIsUnknownNotRebuildRequired() {
    // S6: the count is readable; the recorded shape is not.
    StatusResponse status = status(Map.of(IndexFingerprint.COMMIT_META_KEY, "not-a-digest"));

    assertEquals("UNAVAILABLE", status.getCompatibility().getSchemaCompatState(),
        "an unreadable fingerprint is unknown: never compatible, never rebuild required");
    assertEquals(false, status.getCompatibility().getReindexRequired());
  }

  @Test
  void unreadableStoredMetadataIsUnknownNotLegacy() {
    // S5: the recorded index metadata cannot be read (the read throws); the count is readable.
    // Before the change the failed read became "nothing recorded", so a healthy index holding
    // documents was reported as a legacy index that needs a rebuild.
    StatusResponse status = opsWithMetadata(() -> {
      throw new IllegalStateException("commit metadata unreadable");
    }).buildStatusResponse();

    assertEquals("UNAVAILABLE", status.getCompatibility().getSchemaCompatState(),
        "unreadable metadata is unknown: never legacy, never compatible");
    assertEquals(false, status.getCompatibility().getReindexRequired(),
        "an unanswered question must not tell the user to rebuild");
  }

  @Test
  void aReadableDifferentFingerprintWithDocumentsIsStillAMismatch() {
    // P2 (must not break).
    String state = status(Map.of(IndexFingerprint.COMMIT_META_KEY, "a".repeat(64)))
        .getCompatibility().getSchemaCompatState();

    assertEquals("BLOCKED_MISMATCH", state);
  }

  private StatusResponse status(Map<String, String> openTimeUserData) {
    return ops(openTimeUserData).buildStatusResponse();
  }

  private IndexStatusOps ops(Map<String, String> openTimeUserData) {
    return opsWithMetadata(openTimeUserData == null ? null : () -> openTimeUserData);
  }

  private IndexStatusOps opsWithMetadata(Supplier<Map<String, String>> openTimeUserData) {
    JobQueue jobQueue = mock(JobQueue.class);
    when(jobQueue.jobStateCounts()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 0, 0));
    when(jobQueue.pendingBytes()).thenReturn(JobQueue.PendingBytes.EMPTY);
    return new IndexStatusOps(
        jobQueue,
        tempDir,
        counts,
        null,
        null,
        null,
        null,
        openTimeUserData,
        null,
        null,
        null,
        OperationalMetrics.getInstance(),
        mock(IndexingLoop.class),
        mock(WorkerSignalBus.class),
        0L);
  }
}
