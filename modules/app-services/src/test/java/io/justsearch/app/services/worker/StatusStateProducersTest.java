/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ipc.IndexingJobView;
import io.justsearch.ipc.IndexingJobsDelta;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.IndexingJobsSnapshot;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * b73: owner publications advance the status-change counter on state, never on measurements or
 * clocks. Indexing-job counts and watched-root timestamps are not state.
 */
class StatusStateProducersTest {
  @TempDir Path temp;

  private static long statusCounter() {
    try {
      var type = Class.forName("io.justsearch.core.status.StatusChangeCounter");
      return (long) type.getMethod("current").invoke(type.getMethod("process").invoke(null));
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static IndexingJobView job(String pathHash, String state, long updatedMs) {
    return IndexingJobView.newBuilder().setPathHash(pathHash).setState(state).setAttempts(0)
        .setLastUpdatedMs(updatedMs).setErrorMessage("").setRetryAfterMs(0L)
        .setCollection("default").build();
  }

  /** Job counts are measurements; only which job states are present is a publication. */
  @Test void indexingJobCountsWithinTheSameStatesDoNotAdvanceTheCounter() throws Exception {
    var sink = new AtomicReference<Consumer<IndexingJobsFrame>>();
    IndexingJobsSource source = (onFrame, onError, onCompleted) -> {
      sink.set(onFrame);
      onFrame.accept(IndexingJobsFrame.newBuilder().setSeq(1L).setSnapshot(IndexingJobsSnapshot
          .newBuilder().addItems(job("first", "PENDING", 1000L)).build()).build());
      return () -> {};
    };
    try (var executors = new TestEngineExecutors()) {
      var bridge = new RemoteIndexingJobsBridge(executors, () -> source);
      try {
        bridge.start().get(2, TimeUnit.SECONDS);
        long before = statusCounter();
        Consumer<IndexingJobsDelta> delta = new Consumer<>() {
          long seq = 1L;
          @Override public void accept(IndexingJobsDelta d) {
            sink.get().accept(IndexingJobsFrame.newBuilder().setSeq(++seq).setDelta(d).build());
          }
        };
        delta.accept(IndexingJobsDelta.newBuilder().setUpdate(job("second", "PENDING", 1001L)).build());
        delta.accept(IndexingJobsDelta.newBuilder().setUpdate(job("third", "PENDING", 1002L)).build());
        delta.accept(IndexingJobsDelta.newBuilder().setDeletePathHash("second").build());
        assertEquals(before, statusCounter(),
            "more or fewer queued jobs in the same states is a measurement, not a state change");
        delta.accept(IndexingJobsDelta.newBuilder().setUpdate(job("first", "PROCESSING", 2000L)).build());
        assertEquals(before + 1, statusCounter(), "a job state appearing is a state change");
      } finally {
        bridge.stop();
      }
    }
  }

  /** Index and verification timestamps are clocks; repeating a state does not publish. */
  @Test void watchedRootTimestampsDoNotAdvanceTheCounter() {
    var clock = new AtomicLong(1_000L);
    var state = new WatchedRootsState(new ConcurrentHashMap<Path, Instant>(),
        new WatchedRootsStore(temp.resolve("roots.json"), null), new Clock() {
          @Override public ZoneId getZone() { return ZoneOffset.UTC; }
          @Override public Clock withZone(ZoneId zone) { return this; }
          @Override public Instant instant() { return Instant.ofEpochMilli(clock.addAndGet(1_000L)); }
        });
    var root = temp.resolve("docs");
    state.markIndexed(root);
    state.setDeleteDetectionUnverified(root, false);
    long before = statusCounter();
    state.markIndexed(root);
    state.setDeleteDetectionUnverified(root, false);
    assertEquals(before, statusCounter(), "a newer index or verification time alone is not a state change");
    state.setDeleteDetectionUnverified(root, true);
    assertEquals(before + 1, statusCounter(), "an unverified mark is a state change");
  }
}
