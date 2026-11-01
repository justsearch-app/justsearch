/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.JobQueue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexStatusReadFailureTest {
  @TempDir Path directory;

  @Test
  void failedCompletenessReadDoesNotPublishSuccessfulEmptyStages() {
    var status = ops(mock(IndexCountOps.class)).buildStatusResponse();
    assertTrue(status.getEnrichment().getCompletenessList().isEmpty());
    assertEquals(-1, status.getEnrichment().getPendingNerCount());
    assertEquals(-1, status.getEnrichment().getEmbedding().getPendingCount());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"advance,true", "size,true", "close,true",
      "advance,false", "size,false", "close,false"})
  void optionalSizeFailuresKeepStatusAndLastSuccessfulSize(String failure, boolean measured) throws Exception {
    Path file = Files.write(directory.resolve("index-data"), new byte[37]);
    IndexStatusOps ops = ops(null);
    var service = mock(WorkerIngestService.class, CALLS_REAL_METHODS);
    var statusOps = WorkerIngestService.class.getDeclaredField("statusOps");
    statusOps.setAccessible(true);
    statusOps.set(service, ops);
    if (measured) assertEquals(37, service.indexStatus(io.justsearch.ipc.StatusRequest.getDefaultInstance(),
        CallContext.none()).getCore().getIndexSizeBytes());
    var observedAt = IndexStatusOps.class.getDeclaredField("indexSizeObservedAtMs");
    observedAt.setAccessible(true);
    observedAt.setLong(ops, 0);
    Stream<Path> walk = failure.equals("advance")
        ? Stream.of(file).map(path -> { throw new UncheckedIOException(new IOException("advance")); })
        : Stream.of(file);
    if (failure.equals("close")) walk = walk.onClose(() -> { throw new UncheckedIOException(new IOException("close")); });
    try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
      files.when(() -> Files.walk(directory)).thenReturn(walk);
      if (failure.equals("size")) files.when(() -> Files.size(file)).thenThrow(new IOException("size"));
      var status = service.indexStatus(io.justsearch.ipc.StatusRequest.getDefaultInstance(), CallContext.none());
      assertEquals("IDLE", status.getCore().getState());
      assertTrue(status.getCore().getIsHealthy());
      assertEquals(measured ? 37 : 0, status.getCore().getIndexSizeBytes(), failure);
      assertEquals(0, observedAt.getLong(ops), "retry must remain due");
    }
    Files.write(file, new byte[41]);
    assertEquals(41, service.indexStatus(io.justsearch.ipc.StatusRequest.getDefaultInstance(),
        CallContext.none()).getCore().getIndexSizeBytes());
  }

  @Test
  void fallbackCountsRetainTheirObservationUntilRecovery() throws Exception {
    IndexCountOps counts = mock(IndexCountOps.class);
    stubParents(counts, 12);
    when(counts.countQueryOrThrow(any())).thenReturn(9);
    var ops = ops(counts);
    var fresh = ops.buildStatusResponse();
    var active = observation(fresh, "active");
    var searchable = observation(fresh, "searchable");
    assertEquals("fresh", active.get("state"));
    assertTrue((Long) active.get("observed_at_ms") > 0);
    failParents(counts);
    when(counts.countQueryOrThrow(any())).thenThrow(new IOException("reader lost"));
    for (int repeat = 0; repeat < 2; repeat++) {
      var stale = ops.buildStatusResponse();
      assertEquals(12, stale.getCore().getDocCount());
      assertEquals(9, stale.getCore().getSearchableDocCount());
      assertEquals("stale", observation(stale, "active").get("state"));
      assertEquals(active.get("observed_at_ms"), observation(stale, "active").get("observed_at_ms"));
      assertEquals(searchable.get("observed_at_ms"), observation(stale, "searchable").get("observed_at_ms"));
    }
    stubParents(counts, 18);
    doReturn(15).when(counts).countQueryOrThrow(any());
    var recovered = ops.buildStatusResponse();
    assertEquals(18, recovered.getCore().getDocCount());
    assertEquals(15, recovered.getCore().getSearchableDocCount());
    assertEquals("fresh", observation(recovered, "active").get("state"));
    assertEquals("fresh", observation(recovered, "searchable").get("state"));
  }

  @Test
  void failedFilteredCountDoesNotBorrowTheUnfilteredPopulation() throws Exception {
    IndexCountOps counts = mock(IndexCountOps.class);
    stubParents(counts, 7);
    when(counts.countQueryOrThrow(any())).thenThrow(new IOException("filtered read failed"));
    var status = ops(counts).buildStatusResponse();
    assertTrue(status.getCore().getDocCountUnknown());
    assertEquals("fresh", observation(status, "active").get("state"));
    assertEquals("unknown", observation(status, "searchable").get("state"));
    assertEquals(0L, observation(status, "searchable").get("observed_at_ms"));
  }

  @Test
  void parentCountDoesNotSubtractCountsFromDifferentReaders() throws Exception {
    for (int chunksAfterRefresh : new int[] {0, 3, 8}) {
      IndexCountOps counts = mock(IndexCountOps.class);
      stubParents(counts, 1);
      when(counts.docCountOrThrow()).thenReturn(2L);
      when(counts.countByFieldOrThrow("is_chunk", "true")).thenReturn(chunksAfterRefresh);
      assertEquals(1, ops(counts).buildStatusResponse().getCore().getDocCount());
    }
  }

  @Test
  void buildingFallbackIsIndependentAndCannotCrossGenerations() throws Exception {
    IndexCountOps serving = mock(IndexCountOps.class);
    IndexCountOps building = mock(IndexCountOps.class);
    stubParents(serving, 12);
    stubParents(building, 4);
    when(serving.countQueryOrThrow(any())).thenReturn(9);
    var ops = ops(building, serving);
    var fresh = ops.buildStatusResponse();
    assertEquals(12, fresh.getMigration().getActiveDocCount());
    assertEquals(4, fresh.getMigration().getBuildingDocCount());
    var original = observation(fresh, "building");
    failParents(building);
    var stale = ops.buildStatusResponse();
    assertEquals("fresh", observation(stale, "active").get("state"));
    assertEquals("stale", observation(stale, "building").get("state"));
    assertEquals(original.get("observed_at_ms"), observation(stale, "building").get("observed_at_ms"));
    var next = mock(IndexCountOps.class);
    failParents(next);
    var replaced = ops(next, serving);
    var memory = IndexStatusOps.class.getDeclaredField("lastKnownWrittenDocCount");
    memory.setAccessible(true);
    @SuppressWarnings("unchecked")
    var oldMemory = (java.util.concurrent.atomic.AtomicReference<Object>) memory.get(ops);
    @SuppressWarnings("unchecked")
    var newMemory = (java.util.concurrent.atomic.AtomicReference<Object>) memory.get(replaced);
    newMemory.set(oldMemory.get());
    var unknown = replaced.buildStatusResponse();
    assertEquals(0, unknown.getMigration().getBuildingDocCount());
    assertEquals("unknown", observation(unknown, "building").get("state"));
    assertEquals(0L, observation(unknown, "building").get("observed_at_ms"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void unavailableRawCountTelemetryDoesNotDiscardCountProvenance(boolean ioFailure) throws Exception {
    IndexCountOps counts = mock(IndexCountOps.class, invocation -> {
      if (invocation.getMethod().getName().equals("mergeCountsOrThrow")) {
        if (ioFailure) throw new IOException("reader acquisition failed");
        throw new IllegalStateException("reader closed");
      }
      return RETURNS_DEFAULTS.answer(invocation);
    });
    stubParents(counts, 7);
    when(counts.countQueryOrThrow(any())).thenReturn(5);
    if (!ioFailure) when(counts.maxDoc()).thenThrow(new IllegalStateException("reader closed"));
    var status = ops(counts).buildStatusResponse();
    assertEquals("IDLE", status.getCore().getState());
    assertEquals(7, status.getCore().getDocCount());
    assertEquals(5, status.getCore().getSearchableDocCount());
    assertEquals("fresh", observation(status, "active").get("state"));
    assertEquals(-1, status.getCore().getIndexMaxDoc());
    assertEquals(-1, status.getCore().getIndexNumDocs());
  }

  private static void stubParents(IndexCountOps counts, long value) throws Exception {
    doReturn(value).when(counts).docCountOrThrow();
    try {
      IndexCountOps.class.getMethod("parentDocCountOrThrow").invoke(doReturn(value).when(counts));
    } catch (NoSuchMethodException baseline) {
      // The baseline acquires the total and chunk counts separately.
    }
  }

  private static void failParents(IndexCountOps counts) throws Exception {
    doThrow(new IOException("reader lost")).when(counts).docCountOrThrow();
    try {
      IndexCountOps.class.getMethod("parentDocCountOrThrow").invoke(
          doThrow(new IOException("reader lost")).when(counts));
    } catch (NoSuchMethodException baseline) {
      // The baseline has no snapshot parent-count operation.
    }
  }

  private static java.util.Map<String, Object> observation(io.justsearch.ipc.StatusResponse status, String key) {
    var field = status.getDescriptorForType().findFieldByName("count_observations");
    assertNotNull(field, "Count fallback must publish observation provenance");
    for (Object item : (java.util.List<?>) status.getField(field)) {
      var entry = (com.google.protobuf.Message) item;
      if (!key.equals(entry.getField(entry.getDescriptorForType().findFieldByName("key")))) continue;
      var value = (com.google.protobuf.Message) entry.getField(entry.getDescriptorForType().findFieldByName("value"));
      return java.util.Map.of("state", value.getField(value.getDescriptorForType().findFieldByName("state")),
          "observed_at_ms", value.getField(value.getDescriptorForType().findFieldByName("observed_at_ms")));
    }
    fail("No provenance for " + key);
    return java.util.Map.of();
  }

  private IndexStatusOps ops(IndexCountOps counts) {
    return ops(counts, null);
  }

  private IndexStatusOps ops(IndexCountOps counts, IndexCountOps serving) {
    JobQueue queue = mock(JobQueue.class);
    when(queue.jobStateCounts()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 0, 0));
    when(queue.failureSummary()).thenReturn(new JobQueue.FailureSummary(0, null, null, null, null));
    return new IndexStatusOps(queue, directory, counts, serving, null, null, null, null, null,
        null, null, OperationalMetrics.getInstance(), null, mock(WorkerSignalBus.class), 0L);
  }
}
