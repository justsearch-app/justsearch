/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.OutcomeWriteException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class IngestionOutcomeJournalClaimTest {
  @Test
  void discardedEqualValuedClaimCannotRemoveAFailedCurrentTransition() {
    JobQueue queue = mock();
    var journal = new IngestionOutcomeJournal(queue, mock(), mock(), () -> false);
    var stale = new JobQueue.IndexJob(Path.of("same.txt"), null);
    var current = new JobQueue.IndexJob(Path.of("same.txt"), null);
    var staleTransition = new JobQueue.IngestionLedgerTransition(stale, null);
    var currentTransition = new JobQueue.IngestionLedgerTransition(current, null);
    assertEquals(staleTransition, currentTransition, "equal values are distinct ownership");
    journal.enqueueTransition(staleTransition);
    journal.enqueueTransition(currentTransition);
    doThrow(new OutcomeWriteException("batch rollback", null))
        .when(queue).markDoneTransitions(anyCollection(), any());
    when(queue.markClaimDone(same(stale), any(), isNull())).thenReturn(false);
    when(queue.markClaimDone(same(current), any(), isNull()))
        .thenThrow(new OutcomeWriteException("current rollback", null)).thenReturn(true);

    journal.drainPending();
    assertEquals(1, journal.pendingTransitionsForTest().size());
    assertSame(currentTransition, journal.pendingTransitionsForTest().getFirst());
    journal.drainPending();
    assertTrue(journal.pendingTransitionsForTest().isEmpty());
  }
}
