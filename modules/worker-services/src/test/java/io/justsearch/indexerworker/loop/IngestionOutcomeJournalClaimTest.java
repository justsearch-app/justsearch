/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.OutcomeWriteException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

final class IngestionOutcomeJournalClaimTest {
  private static final String CONTENT_HASH = "a".repeat(64);

  @Test
  void discardedEqualValuedClaimCannotRemoveAFailedCurrentTransition() {
    JobQueue queue = mock();
    var journal = new IngestionOutcomeJournal(queue, mock(), mock(), () -> false);
    var stale = new JobQueue.IndexJob(Path.of("same.txt"), null);
    var current = new JobQueue.IndexJob(Path.of("same.txt"), null);
    var staleTransition = new JobQueue.IngestionLedgerTransition(stale, null, CONTENT_HASH);
    var currentTransition = new JobQueue.IngestionLedgerTransition(current, null, CONTENT_HASH);
    assertEquals(staleTransition, currentTransition, "equal values are distinct ownership");
    journal.enqueueTransition(staleTransition);
    journal.enqueueTransition(currentTransition);
    verifyNoInteractions(queue);

    List<JobQueue.IngestionLedgerTransition> singletonCalls = new ArrayList<>();
    doAnswer(
            invocation -> {
              Collection<JobQueue.IngestionLedgerTransition> transitions = invocation.getArgument(0);
              if (transitions.size() == 2) {
                throw new OutcomeWriteException("batch rollback", null);
              }
              JobQueue.IngestionLedgerTransition transition = transitions.iterator().next();
              singletonCalls.add(transition);
              if (singletonCalls.size() == 2) {
                throw new OutcomeWriteException("current rollback", null);
              }
              return null;
            })
        .when(queue)
        .markDoneTransitions(anyCollection(), any());

    journal.drainPending();
    assertEquals(1, journal.pendingTransitionsForTest().size());
    assertSame(currentTransition, journal.pendingTransitionsForTest().getFirst());
    assertEquals(2, singletonCalls.size());
    assertSame(staleTransition, singletonCalls.get(0));
    assertSame(currentTransition, singletonCalls.get(1));
    assertEquals(CONTENT_HASH, journal.pendingTransitionsForTest().getFirst().committedContentHash());

    journal.drainPending();
    assertTrue(journal.pendingTransitionsForTest().isEmpty());
    assertEquals(3, singletonCalls.size());
    assertSame(currentTransition, singletonCalls.get(2));
    assertEquals(CONTENT_HASH, singletonCalls.get(2).committedContentHash());
  }
}
