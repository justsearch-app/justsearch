/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexingJobsSubscriberRetirementTest {
  @TempDir Path temp;

  @Test void cancelledSubscriberInCapturedBatchIsSkipped() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("cancelled.db"))) {
      queue.open();
      var retired = new AtomicReference<IndexingJobChangeFeed.Subscription>();
      var calls = new AtomicInteger();
      var first = queue.changeStream().subscribe(delta -> retired.get().close());
      retired.set(queue.changeStream().subscribe(delta -> calls.incrementAndGet()));
      try {
        queue.enqueue(List.of(temp.resolve("file")));
        assertEquals(0, calls.get(), "a COW iterator must not deliver to its retired subscriber");
      } finally { first.close(); retired.get().close(); }
    }
  }

  @Test void closingFeedInsideCapturedBatchSkipsItsRemainingSubscribers() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("closed.db"))) {
      queue.open();
      var feed = queue.changeStream();
      var calls = new AtomicInteger();
      feed.subscribe(delta -> feed.close());
      feed.subscribe(delta -> calls.incrementAndGet());
      queue.enqueue(List.of(temp.resolve("file")));
      assertEquals(0, calls.get(), "feed closure invalidates captured subscriber callbacks");
    }
  }
}
