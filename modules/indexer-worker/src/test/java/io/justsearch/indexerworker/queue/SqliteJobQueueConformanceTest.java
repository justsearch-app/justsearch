package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Queue totals remain authoritative while the feed projects only its bounded window. */
@DisplayName("Queue depth agrees with status; window PROCESSING rows agree with status")
final class SqliteJobQueueConformanceTest {

  @TempDir Path tempDir;
  private SqliteJobQueue jobQueue;

  @BeforeEach
  void setUp() throws Exception {
    jobQueue = new SqliteJobQueue(tempDir.resolve("jobs.db"));
    jobQueue.open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (jobQueue != null) {
      jobQueue.close();
    }
  }

  /** Verify both projections against their appropriate status counts through a full lifecycle. */
  @Test
  void countAndStreamAgreeAcrossTransitions() throws Exception {
    assertCountsAndWindowAgree(); // empty: 0 == 0

    jobQueue.enqueue(
        List.of(Path.of("/tmp/a.txt"), Path.of("/tmp/b.txt"), Path.of("/tmp/c.txt")));
    assertCountsAndWindowAgree(); // 3 PENDING → depth 3

    var polled = jobQueue.pollPending(2); // 2 → PROCESSING
    assertCountsAndWindowAgree(); // 1 PENDING + 2 PROCESSING → depth 3

    jobQueue.markDone(polled.get(0).path()); // 1 → DONE (terminal)
    assertCountsAndWindowAgree(); // 1 PENDING + 1 PROCESSING + 1 DONE → depth 2
  }

  private void assertCountsAndWindowAgree() throws Exception {
    long depth = jobQueue.queueDepth();
    var snap = jobQueue.changeStream().subscribeWithSnapshot(d -> {});
    try {
      var counts = jobQueue.jobStateCountsStrict();
      assertEquals(depth, counts.pendingCount() + counts.processingCount(),
          "queue depth includes queued work outside the window");
      assertTrue(snap.items().stream().noneMatch(r -> "PENDING".equals(r.state()) || "DONE".equals(r.state())),
          "snapshots exclude queued and completed history");
      long processing =
          snap.items().stream()
              .filter(
                  r -> {
                    String s = r.state();
                    return "PROCESSING".equals(s);
                  })
              .count();
      assertEquals(
          counts.processingCount(),
          processing,
          "every PROCESSING row must remain visible even when PENDING and DONE are outside the window");
    } finally {
      snap.subscription().close();
    }
  }
}
