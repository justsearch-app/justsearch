/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ActionEventJournalRetryTest {
  @TempDir Path temp;

  @Test
  void failedAppendDoesNotAppearInTheDurableTail() throws Exception {
    Path audit = Files.writeString(temp.resolve("audit"), "blocks the directory");
    var journal = ActionEventJournal.at(audit);
    assertFalse(journal.append(event("row-1")));
    assertTrue(journal.tail(10).isEmpty(), "the durable projection must not advertise an unwritten event");
  }

  @Test
  void retryAfterJournalFailurePersistsWithoutDuplicatingTheLiveEvent() throws Exception {
    Path audit = Files.writeString(temp.resolve("audit"), "blocks the directory");
    var journal = ActionEventJournal.at(audit);
    var registry = new ActionLedgerChangeRegistry(journal);
    List<ActionEvent> seen = new ArrayList<>();
    registry.addEventListener(seen::add);
    registry.broadcastActionEvent(event("row-1"));
    assertEquals(1, seen.size(), "the live observation remains visible during journal failure");
    Files.delete(audit);
    registry.broadcastActionEvent(event("row-1"));
    assertTrue(Files.exists(journal.activeFile()), "ring deduplication must not suppress a durable retry");
    assertEquals(1, Files.readAllLines(journal.activeFile()).size());
    assertEquals(1, seen.size(), "persisting a retried event must not repeat live delivery");
    assertEquals(List.of("row-1"), ActionEventJournal.at(audit).tail(10).stream().map(ActionEvent::id).toList());
  }

  @Test
  void restartDeduplicatesRetainedIdsBeyondTheReadTail() throws Exception {
    Path audit = temp.resolve("audit");
    var journal = ActionEventJournal.at(audit);
    for (int i = 0; i < ActionEventJournal.TAIL_CAPACITY + 3; i++) journal.append(event("row-" + i));
    long bytes = Files.size(journal.activeFile());
    var reopened = ActionEventJournal.at(audit);
    assertFalse(reopened.tail(500).stream().anyMatch(row -> row.id().equals("row-0")));
    assertTrue(reopened.append(event("row-0")));
    assertEquals(bytes, Files.size(journal.activeFile()), "a retained row outside the read tail is still already journaled");
  }

  @Test
  void rotationRetiresOnlyIdsWhoseLastRetainedCopyWasDropped() throws Exception {
    var journal = ActionEventJournal.at(temp.resolve("audit"), 1);
    for (int i = 0; i < 11; i++) assertTrue(journal.append(event("row-" + i)));
    var reopened = ActionEventJournal.at(temp.resolve("audit"), 1);
    assertEquals(journal.tail(500), reopened.tail(500));
    assertEquals(8, reopened.tail(500).size());
    assertTrue(reopened.append(event("row-3")));
    assertEquals(8, reopened.tail(500).size(), "retained duplicates cannot rotate out newer entries");
    assertTrue(reopened.append(event("row-0")), "a dropped id is outside the journal dedup window");
    assertEquals("row-0", reopened.tail(1).getFirst().id());
    assertEquals(8, ActionEventJournal.at(temp.resolve("audit"), 1).tail(500).size());
  }

  @Test
  void appendAfterATornLineRemainsReadableOnTheNextRestart() throws Exception {
    Path audit = temp.resolve("audit");
    var journal = ActionEventJournal.at(audit);
    assertTrue(journal.append(event("row-0")));
    Files.writeString(journal.activeFile(), "{\"id\":\"torn-", java.nio.file.StandardOpenOption.APPEND);
    assertTrue(ActionEventJournal.at(audit).append(event("row-1")));
    assertEquals(List.of("row-0", "row-1"), ActionEventJournal.at(audit).tail(10).stream().map(ActionEvent::id).toList());
  }

  private static ActionEvent event(String id) {
    return new ActionEvent.Operation(id, Instant.parse("2026-09-13T10:00:00Z"), "system",
        "SYSTEM_INTERNAL", "core.completion-fixture", "SUCCESS", Optional.empty(), Optional.empty());
  }
}
