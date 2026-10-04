/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ActionEventJournalDurabilityTest {
  @TempDir Path directory;
  private static final ActionEvent EVENT = new ActionEvent.Operation("operation:accepted-key", Instant.EPOCH,
      "agent", "AGENT_LOOP", "core.remember", "SUCCESS", Optional.empty(), Optional.empty());

  @Test
  void failedForceCannotAcknowledgeAndRetryForcesTheExistingLineWithoutDuplicatingIt() throws Exception {
    var calls = new AtomicInteger();
    var journal = ActionEventJournal.at(directory, 4096, path -> {
      assertTrue(Files.readString(path).contains(EVENT.id()), "The record must precede its durability barrier");
      if (calls.incrementAndGet() == 1) throw new IOException("durability unavailable");
      try (var channel = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.WRITE)) { channel.force(true); }
    });
    assertFalse(journal.append(EVENT), "Uncertain bytes cannot acknowledge the source row");
    assertEquals(1, calls.get());
    assertTrue(journal.append(EVENT), "Retry must cross durability even when the line already exists");
    assertEquals(2, calls.get());
    assertEquals(1, Files.readAllLines(journal.activeFile()).size());
    assertEquals(1, ActionEventJournal.at(directory).tail(10).size());
  }

  @Test
  void reopenedRetainedIdentityStillNeedsTheBarrierBeforeItCanAcknowledge() {
    assertTrue(ActionEventJournal.at(directory).append(EVENT));
    var calls = new AtomicInteger();
    var reopened = ActionEventJournal.at(directory, 4096, path -> {
      calls.incrementAndGet(); throw new IOException("retained force unavailable");
    });
    assertFalse(reopened.append(EVENT));
    assertEquals(1, calls.get());
  }

  @Test
  void uncertainLineRotatedByAnotherAppendForcesItsRetainedGenerationOnRetry() throws Exception {
    var calls = new AtomicInteger();
    var forcePaths = new java.util.ArrayList<Path>();
    var refuseRotated = new java.util.concurrent.atomic.AtomicBoolean(true);
    var journal = ActionEventJournal.at(directory, 1, path -> {
      forcePaths.add(path);
      if (calls.incrementAndGet() == 1) throw new IOException("first line uncertain");
      if (calls.get() > 2) {
        assertEquals(directory.resolve("action-ledger.1.jsonl"), path, "Retry must force the generation containing its id");
        if (refuseRotated.get()) throw new IOException("rotated line uncertain");
      }
      try (var channel = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.WRITE)) { channel.force(true); }
    });
    assertFalse(journal.append(EVENT));
    var newer = new ActionEvent.Operation("operation:newer-key", Instant.ofEpochSecond(1),
        "agent", "AGENT_LOOP", "core.remember", "SUCCESS", Optional.empty(), Optional.empty());
    assertTrue(journal.append(newer));
    assertFalse(journal.append(EVENT));
    refuseRotated.set(false);
    assertTrue(journal.append(EVENT));
    assertEquals(4, forcePaths.size());
    assertEquals(1, Files.readAllLines(journal.activeFile()).size());
    assertEquals(1, Files.readAllLines(journal.generation(1)).size());
    assertEquals(2, ActionEventJournal.at(directory).tail(10).size());
  }
}
