package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.agent.api.encryption.DataKeyState;
import io.justsearch.agent.api.encryption.KeyLockedException;
import io.justsearch.agent.api.encryption.StoreCipher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 859 slice C PR-2 — the run store's CONVERSATION projection and the scoped delete beside it.
 *
 * <p>A delegate run persists a whole conversation here and no {@code ConversationStore} row, so these
 * two methods are what make such a conversation listable and — the completing half — removable.
 */
final class AgentRunStoreConversationsTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("many runs of ONE conversation are one row, spanning their earliest and latest")
  void runsGroupIntoOneConversation() {
    Path root = tempDir.resolve("agent-runs");
    AgentRunStore store = new AgentRunStore(root);
    writeRun(store, root, "run-old", "conv-1", "2026-08-01T10:00:00Z", "2026-08-01T10:01:00Z",
        "why did the renewal fail?");
    writeRun(store, root, "run-new", "conv-1", "2026-08-01T11:00:00Z", "2026-08-01T11:30:00Z",
        "and what did we change?");

    List<Map<String, Object>> rows = store.listConversations(20);

    assertEquals(1, rows.size(), "two runs of one conversation are ONE conversation");
    Map<String, Object> row = rows.get(0);
    assertEquals("conv-1", row.get("conversationId"));
    assertEquals(2, row.get("runCount"));
    assertEquals(millis("2026-08-01T10:00:00Z"), row.get("createdAtMs"), "the EARLIEST run started it");
    assertEquals(millis("2026-08-01T11:30:00Z"), row.get("lastActiveAtMs"), "the LATEST run ended it");
    assertEquals(
        "why did the renewal fail?",
        row.get("firstUserMessage"),
        "the label is the request that OPENED the conversation, not its most recent one");
    assertFalse(
        row.containsKey("messageCount"),
        "honestly absent: a count would mean projecting the whole event stream per row");
  }

  @Test
  @DisplayName("standalone runs and non-agent shapes are not conversations of this list")
  void onlyAgentRunsWithAConversationAreListed() {
    Path root = tempDir.resolve("agent-runs");
    AgentRunStore store = new AgentRunStore(root);
    writeRun(store, root, "run-1", "conv-1", "2026-08-01T10:00:00Z", "2026-08-01T10:01:00Z", "q");
    writeRun(store, root, "run-standalone", null, "2026-08-01T10:02:00Z", "2026-08-01T10:03:00Z", "q");
    Map<String, Object> workflow =
        meta("wf-1", "conv-workflow", "2026-08-01T10:04:00Z", "2026-08-01T10:05:00Z", "q");
    workflow.put("shapeId", "core.workflow-run");
    store.runEvents().writeRunMeta("wf-1", workflow);

    List<Map<String, Object>> rows = store.listConversations(20);

    assertEquals(List.of("conv-1"), rows.stream().map(r -> r.get("conversationId")).toList());
  }

  @Test
  @DisplayName("the limit bounds CONVERSATIONS, newest first — not run directories")
  void limitCountsDistinctConversations() {
    Path root = tempDir.resolve("agent-runs");
    AgentRunStore store = new AgentRunStore(root);
    // Two runs each, one hour apart per conversation, so the six directories carry six distinct
    // mtimes and "newest first" is a real order rather than whatever the filesystem returned.
    int hour = 10;
    for (String conv : List.of("conv-a", "conv-b", "conv-c")) {
      String h = String.format("%02d", hour);
      writeRun(store, root, conv + "-r1", conv, "2026-08-01T" + h + ":00:00Z",
          "2026-08-01T" + h + ":01:00Z", conv);
      writeRun(store, root, conv + "-r2", conv, "2026-08-01T" + h + ":02:00Z",
          "2026-08-01T" + h + ":03:00Z", conv);
      hour++;
    }

    List<Map<String, Object>> rows = store.listConversations(2);

    assertEquals(2, rows.size(), "two conversations, however many runs they hold between them");
    assertEquals(
        List.of("conv-c", "conv-b"),
        rows.stream().map(r -> r.get("conversationId")).toList(),
        "newest directory first, so the limit keeps the most recent conversations");
  }

  @Test
  @DisplayName("a sealed + locked store lists NOTHING rather than failing")
  void lockedStoreListsNothing() {
    TestKey key = new TestKey();
    Path root = tempDir.resolve("agent-runs");
    AgentRunStore store = new AgentRunStore(root, new StoreCipher(key));
    writeRun(store, root, "run-1", "conv-1", "2026-08-01T10:00:00Z", "2026-08-01T10:01:00Z", "q");
    assertEquals(1, store.listConversations(20).size(), "readable while unlocked");

    key.locked = true;
    assertTrue(store.listConversations(20).isEmpty(), "and empty — not an error — while locked");

    key.locked = false;
    assertEquals(1, store.listConversations(20).size(), "the record was never touched");
  }

  @Test
  @DisplayName("deleting a conversation removes ITS runs and only those")
  void deleteRemovesOnlyThatConversationsRuns() {
    Path root = tempDir.resolve("agent-runs");
    AgentRunStore store = new AgentRunStore(root);
    writeRun(store, root, "run-1", "conv-1", "2026-08-01T10:00:00Z", "2026-08-01T10:01:00Z", "q1");
    writeRun(store, root, "run-2", "conv-1", "2026-08-01T10:02:00Z", "2026-08-01T10:03:00Z", "q1");
    writeRun(store, root, "run-3", "conv-2", "2026-08-01T10:04:00Z", "2026-08-01T10:05:00Z", "q2");

    assertEquals(2, store.deleteConversationRuns("conv-1"));

    assertFalse(Files.exists(root.resolve("run-1")));
    assertFalse(Files.exists(root.resolve("run-2")));
    assertTrue(Files.exists(root.resolve("run-3")), "the neighbour conversation is untouched");
    assertEquals(
        List.of("conv-2"),
        store.listConversations(20).stream().map(r -> r.get("conversationId")).toList());
    assertEquals(0, store.deleteConversationRuns("conv-1"), "and deleting it again removes nothing");
  }

  @Test
  @DisplayName("deleteFailsClosedWhileLocked(a locked store refuses deletion without removing anything)")
  void deleteFailsClosedWhileLocked() {
    // green-masked-destructive: the delete resolves its targets by READING each run's meta for the
    // join key. A locked store answers "no runs", and the danger to prove absent is the opposite
    // reading — an unfiltered sweep of the root — so the adverse precondition is tested, not assumed.
    TestKey key = new TestKey();
    Path root = tempDir.resolve("agent-runs");
    AgentRunStore store = new AgentRunStore(root, new StoreCipher(key));
    writeRun(store, root, "run-1", "conv-1", "2026-08-01T10:00:00Z", "2026-08-01T10:01:00Z", "q");
    writeRun(store, root, "run-2", "conv-2", "2026-08-01T10:02:00Z", "2026-08-01T10:03:00Z", "q");

    key.locked = true;
    assertThrows(KeyLockedException.class, () -> store.deleteConversationRuns("conv-1"));

    key.locked = false;
    assertTrue(Files.exists(root.resolve("run-1")), "the target survived the locked attempt");
    assertTrue(Files.exists(root.resolve("run-2")), "and so did every other conversation's run");
  }

  // ── harness ──────────────────────────────────────────────────────────────────────────────────

  private static void writeRun(
      AgentRunStore store,
      Path root,
      String runId,
      String conversationId,
      String startedAt,
      String updatedAt,
      String question) {
    store.runEvents().writeRunMeta(runId, meta(runId, conversationId, startedAt, updatedAt, question));
    // The list orders by directory mtime, so pin it to the run's own updatedAt rather than trusting
    // the order the filesystem happened to write these in.
    try {
      Files.setLastModifiedTime(
          root.resolve(runId),
          java.nio.file.attribute.FileTime.fromMillis(millis(updatedAt)));
    } catch (java.io.IOException e) {
      throw new AssertionError("could not stamp the run directory", e);
    }
  }

  private static Map<String, Object> meta(
      String runId, String conversationId, String startedAt, String updatedAt, String question) {
    var meta = new LinkedHashMap<String, Object>();
    meta.put("sessionId", runId);
    meta.put("shapeId", "core.agent-run");
    meta.put("conversationId", conversationId);
    meta.put("startedAt", startedAt);
    meta.put("updatedAt", updatedAt);
    meta.put("messages", List.of(Map.of("role", "user", "content", question)));
    return meta;
  }

  private static long millis(String iso) {
    return java.time.Instant.parse(iso).toEpochMilli();
  }

  /** A data key that can be locked mid-test, as an encrypted install's is before unlock. */
  private static final class TestKey implements DataKeyState {
    private final byte[] dek = new byte[32];
    private volatile boolean locked;

    TestKey() {
      new SecureRandom().nextBytes(dek);
    }

    @Override
    public boolean enabled() {
      return true;
    }

    @Override
    public boolean locked() {
      return locked;
    }

    @Override
    public byte[] dek() {
      if (locked) {
        throw new KeyLockedException();
      }
      return dek.clone();
    }
  }
}
