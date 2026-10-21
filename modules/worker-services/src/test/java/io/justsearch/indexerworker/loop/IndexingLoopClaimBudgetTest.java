/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class IndexingLoopClaimBudgetTest {
  @Test void budgetStoppedEmptyBatchPollsAgainBeforeAnyIdleWork() throws Exception {
    var queue=mock(JobQueue.class);
    var polls=new AtomicInteger();
    var branch=new AtomicReference<String>();
    var chosen=new CountDownLatch(1);
    var release=new CountDownLatch(1);
    when(queue.pollPending(anyInt())).thenAnswer(call -> {
      if(polls.incrementAndGet()>1) {
        branch.compareAndSet(null,"poll"); chosen.countDown(); release.await();
      }
      return List.of();
    });
    when(queue.pollPendingBudgetExhausted()).thenAnswer(call -> polls.get()==1);
    var loop=new IndexingLoop(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(),
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),queue,
        mock(IndexingCoordinator.class),mock(CommitOps.class),mock(DocumentFieldOps.class),mock(IndexCountOps.class),
        () -> null,mock(WorkerSignalBus.class),IndexingPacing.unthrottled(),null,null,null,null,null,null,null);
    var backfill=mock(BackfillScheduler.class);
    when(backfill.runIdleCycle()).thenAnswer(call -> {
      branch.compareAndSet(null,"idle"); chosen.countDown(); return false;
    });
    var field=IndexingLoop.class.getDeclaredField("backfillScheduler");field.setAccessible(true);field.set(loop,backfill);
    loop.start();
    try {
      assertTrue(chosen.await(2,TimeUnit.SECONDS),"loop must choose its next action promptly");
      assertEquals("poll",branch.get(),"budget exhaustion is not a drained queue or an idle cycle");
      verify(backfill,never()).runIdleCycle();
    } finally { release.countDown(); loop.close(); }
  }
}
