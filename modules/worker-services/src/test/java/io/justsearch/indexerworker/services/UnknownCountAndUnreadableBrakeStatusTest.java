/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.google.protobuf.Descriptors;
import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.CoreStatus;
import io.justsearch.ipc.StatusResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #758 — what the Worker's status says when (a) the document count
 * cannot be read from the very first read, and (b) the automatic-rebuild brake cannot be read.
 *
 * <p>(a) With no earlier count to fall back on, status says the count is unknown ({@code
 * doc_count_unknown}) instead of reporting 0 documents as if the index were empty. The field is read
 * through the descriptor so the check compiles against a revision without it and fails there by
 * assertion. The count failure is injected as in {@code SchemaCompatUnknownTest}: a Lucene count is
 * read from an already-open in-memory reader, so no file-system condition makes it fail on demand;
 * {@code docCount()} answers 0 and {@code docCountOrThrow()} throws, as {@link IndexCountOps} does.
 *
 * <p>(b) A brake that cannot be read counts as braked: an index that needs a rebuild is reported
 * held by the brake, with a reason naming the unreadable brake. The state record is made
 * unreadable for real — both {@code state.json} and its backup are written so they do not parse.
 */
final class UnknownCountAndUnreadableBrakeStatusTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  private static final String FOREIGN_SHAPE = "a".repeat(64);

  @TempDir Path tempDir;
  private RunningRuntime runtime;
  private IndexCountOps counts;
  private final AtomicBoolean countsFail = new AtomicBoolean();

  @BeforeEach
  void setUp() throws Exception {
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir.resolve("index"))
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
    doAnswer(inv -> {
          if (countsFail.get()) throw new IOException("reader unavailable");
          return inv.callRealMethod();
        })
        .when(counts).docCountOrThrow();
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
  void aCountThatFailsFromTheFirstReadIsReportedUnknown() {
    countsFail.set(true);

    CoreStatus core = ops(null, null).buildStatusResponse().getCore();

    Descriptors.FieldDescriptor unknown = CoreStatus.getDescriptor().findFieldByName("doc_count_unknown");
    assertNotNull(unknown, "status has no way to say the document count is unknown");
    assertEquals(Boolean.TRUE, core.getField(unknown),
        "a count never read was reported as a known 0: an empty index");
  }

  @Test
  void aReadableCountIsNotReportedUnknown() {
    // Must not break: a counted index keeps its count, and nothing marks it unknown.
    CoreStatus core = ops(null, null).buildStatusResponse().getCore();

    assertEquals(3, core.getDocCount());
    Descriptors.FieldDescriptor unknown = CoreStatus.getDescriptor().findFieldByName("doc_count_unknown");
    assertTrue(unknown == null || Boolean.FALSE.equals(core.getField(unknown)),
        "a counted index was marked unknown");
  }

  @Test
  void aMismatchWithAnUnreadableBrakeIsReportedBrakedNamingTheUnreadableBrake() throws IOException {
    // Before, the failed read meant "no brake", so status described an index that needs a rebuild
    // as one the Worker would rebuild by itself.
    Path generations = Files.createDirectories(tempDir.resolve("generations-unreadable"));
    Files.writeString(generations.resolve("state.json"), "{ not json");
    Files.writeString(generations.resolve("state.json.prev"), "{ not json either");

    StatusResponse status =
        ops(() -> Map.of(IndexFingerprint.COMMIT_META_KEY, FOREIGN_SHAPE), new IndexGenerationManager(generations))
            .buildStatusResponse();

    assertEquals("BLOCKED_REBUILD_BRAKE", status.getCompatibility().getSchemaCompatState(),
        "an unreadable brake read as no brake");
    assertEquals("rebuild_brake_unreadable", status.getCompatibility().getReindexRequiredReason());
    assertEquals(true, status.getCompatibility().getReindexRequired());
  }

  @Test
  void aMismatchWithNoBrakeRecordedIsStillAPlainMismatch() throws IOException {
    // Must not break: no state record at all is a released brake, not an unreadable one.
    Path generations = Files.createDirectories(tempDir.resolve("generations-fresh"));

    StatusResponse status =
        ops(() -> Map.of(IndexFingerprint.COMMIT_META_KEY, FOREIGN_SHAPE), new IndexGenerationManager(generations))
            .buildStatusResponse();

    assertEquals("BLOCKED_MISMATCH", status.getCompatibility().getSchemaCompatState());
    assertEquals("schema_mismatch", status.getCompatibility().getReindexRequiredReason());
  }

  private IndexStatusOps ops(
      Supplier<Map<String, String>> openTimeUserData, IndexGenerationManager generations) {
    JobQueue jobQueue = mock(JobQueue.class);
    when(jobQueue.jobStateCounts()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 0, 0));
    when(jobQueue.pendingBytes()).thenReturn(JobQueue.PendingBytes.EMPTY);
    return new IndexStatusOps(
        jobQueue,
        tempDir.resolve("index"),
        counts,
        null,
        null,
        null,
        null,
        openTimeUserData,
        null,
        generations,
        null,
        OperationalMetrics.getInstance(),
        mock(IndexingLoop.class),
        mock(WorkerSignalBus.class),
        0L);
  }
}
