package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.BranchesPreventDeletionException;
import io.justsearch.agent.api.conversation.ConversationStore;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Slice 513 — FileConversationStore branching tests.
 *
 * <p>Covers: id/hash synthesis on append, id/hash backfill for legacy data on
 * load, branchFrom meta layout, lazy prefix walk in loadHistory, parent
 * pointers exposed by listSessions, and parent-cycle truncation.
 */
final class FileConversationStoreTest {

  private static Map<String, Object> userMsg(String content) {
    return Map.of("role", "user", "content", content);
  }

  private static Map<String, Object> assistantMsg(String content) {
    return Map.of("role", "assistant", "content", content);
  }

  @Test
  @DisplayName("appendMessage synthesizes id + hash on every write")
  void appendAddsIdAndHash(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.createSession("s1", "core.free-chat");
    store.appendMessage("s1", "core.free-chat", userMsg("hello"));
    List<Map<String, Object>> history = store.loadHistory("s1");
    assertEquals(1, history.size());
    Map<String, Object> msg = history.get(0);
    assertTrue(msg.get("id") instanceof String, "id should be a String");
    assertFalse(((String) msg.get("id")).isBlank(), "id should not be blank");
    assertTrue(msg.get("hash") instanceof String, "hash should be a String");
    assertEquals(64, ((String) msg.get("hash")).length(), "sha-256 hex is 64 chars");
  }

  @Test
  @DisplayName("new metadata writes declare schema v1")
  void metadataWritesSchemaVersion(@TempDir Path tmp) throws Exception {
    var store = new FileConversationStore(tmp);
    store.createSession("s1", "core.free-chat");
    store.appendMessage("s1", "core.free-chat", userMsg("hello"));
    String meta = Files.readString(tmp.resolve("s1").resolve("meta.json"));
    assertTrue(meta.contains("\"schemaVersion\":1"));
  }

  @Test
  @DisplayName("future metadata is refused without overwrite")
  void futureMetadataIsRefused(@TempDir Path tmp) throws Exception {
    Path session = tmp.resolve("future");
    Files.createDirectories(session);
    Path meta = session.resolve("meta.json");
    String future = "{\"schemaVersion\":99,\"shapeId\":\"core.free-chat\"}";
    Files.writeString(meta, future);

    var store = new FileConversationStore(tmp);
    assertThrows(UnsupportedStoreVersionException.class, () -> store.getSessionMeta("future"));
    assertEquals(future, Files.readString(meta));
  }

  @Test
  @DisplayName("malformed metadata is not skipped from session listings")
  void malformedMetadataFailsLoud(@TempDir Path tmp) throws Exception {
    Path session = tmp.resolve("broken");
    Files.createDirectories(session);
    Path meta = session.resolve("meta.json");
    String malformed = "{not-json";
    Files.writeString(meta, malformed);

    var store = new FileConversationStore(tmp);
    assertThrows(CorruptDurableStoreException.class, () -> store.listSessions(null, 10));
    assertEquals(malformed, Files.readString(meta));
  }

  @Test
  @DisplayName("malformed message content is not reinterpreted as empty history")
  void malformedMessagesFailLoud(@TempDir Path tmp) throws Exception {
    Path session = tmp.resolve("broken");
    Files.createDirectories(session);
    Path messages = session.resolve("messages.jsonl");
    String malformed = "{not-json\n";
    Files.writeString(messages, malformed);

    var store = new FileConversationStore(tmp);
    assertThrows(CorruptDurableStoreException.class, () -> store.loadHistory("broken"));
    assertEquals(malformed, Files.readString(messages));
  }

  /** A test DataKeyState with a fixed key whose lock state the test toggles. */
  private static final class FakeKey implements io.justsearch.agent.api.encryption.DataKeyState {
    private final byte[] dek = new byte[32];
    private boolean locked;

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
      if (locked) throw new io.justsearch.agent.api.encryption.KeyLockedException();
      return dek;
    }
  }

  @Test
  @DisplayName("629 LAYER — meta content is sealed; the list survives a locked store (not deleted-looking)")
  void metaContentSealedAndListSurvivesLocked(@TempDir Path tmp) throws Exception {
    var key = new FakeKey();
    var cipher = new io.justsearch.agent.api.encryption.StoreCipher(key);
    var store = new FileConversationStore(tmp, cipher);
    store.createSession("s1", "core.free-chat");
    store.appendMessage("s1", "core.free-chat", userMsg("SENSITIVE-first-message"));

    // The first user message is sealed in meta.json — plaintext is NOT on disk.
    String meta = Files.readString(tmp.resolve("s1").resolve("meta.json"), StandardCharsets.UTF_8);
    assertFalse(meta.contains("SENSITIVE-first-message"), "first message is not plaintext in meta.json");
    assertTrue(meta.contains("JSEv1:"), "the meta content field is sealed");

    // Unlocked: the list decrypts the title.
    List<ConversationStore.SessionSummary> open = store.listSessions("core.free-chat", 20);
    assertEquals(1, open.size());
    assertEquals("SENSITIVE-first-message", open.get(0).firstUserMessage());

    // Locked: the list still returns the session (NOT dropped, NOT thrown) with the title hidden.
    key.locked = true;
    List<ConversationStore.SessionSummary> locked = store.listSessions("core.free-chat", 20);
    assertEquals(1, locked.size(), "the locked session stays in the list");
    assertEquals("", locked.get(0).firstUserMessage(), "title is hidden while locked");
  }

  @Test
  @DisplayName(
      "629 LAYER regression (tempdoc 727) — an UNLOCKED live cipher decrypts sealed messages back to"
          + " plaintext, proving a disabled() cipher (the pre-fix ResourceApiModule bug) could never do"
          + " this")
  void unlockedLiveCipherDecryptsSealedMessages(@TempDir Path tmp) {
    var key = new FakeKey();
    var cipher = new io.justsearch.agent.api.encryption.StoreCipher(key);
    var store = new FileConversationStore(tmp, cipher);
    store.createSession("s1", "core.free-chat");
    store.appendMessage("s1", "core.free-chat", userMsg("SENSITIVE-content"));

    // The line on disk is ciphertext, not plaintext — proves the message was actually sealed.
    String raw = readMessagesJsonl(tmp, "s1");
    assertTrue(raw.startsWith("JSEv1:"), "the persisted line is sealed");
    assertFalse(raw.contains("SENSITIVE-content"), "plaintext is not on disk");

    // A disabled() cipher (pre-fix: ResourceApiModule's single-arg ctor) can NEVER open this line —
    // StoreCipher.open throws KeyLockedException unconditionally because DISABLED.enabled() == false
    // (StoreCipher.java :95-97), regardless of lock state. The live, UNLOCKED cipher used here is the
    // only one that can decrypt it back.
    List<Map<String, Object>> history = store.loadHistory("s1");
    assertEquals(1, history.size());
    assertEquals("SENSITIVE-content", history.get(0).get("content"), "unlocked reads decrypt");
    assertEquals("user", history.get(0).get("role"));
    assertNull(history.get(0).get("locked"), "an unlocked, successfully-opened message has no locked marker");
  }

  @Test
  @DisplayName(
      "629 LAYER regression (tempdoc 727) — loadHistory on a LOCKED store degrades per-message"
          + " (keeps the position, hides the content) instead of throwing / 500ing the whole session")
  void loadHistoryDegradesOnLockedInsteadOfThrowing(@TempDir Path tmp) {
    var key = new FakeKey();
    var cipher = new io.justsearch.agent.api.encryption.StoreCipher(key);
    var store = new FileConversationStore(tmp, cipher);
    store.createSession("s1", "core.free-chat");
    store.appendMessage("s1", "core.free-chat", userMsg("q1"));
    store.appendMessage("s1", "core.free-chat", assistantMsg("a1"));

    key.locked = true;

    // Pre-fix: loadOwnMessages caught only IOException, so cipher.open's KeyLockedException (a
    // RuntimeException) propagated out of loadHistory uncaught — the caller (e.g.
    // InteractionThreadController.handleGet, whose catch-all is `catch (Exception e)`) turned that
    // into an HTTP 500 for the WHOLE unified thread, not just this session's chat turns.
    List<Map<String, Object>> history = store.loadHistory("s1");

    assertEquals(2, history.size(), "both messages stay in the transcript (not dropped, not thrown)");
    for (Map<String, Object> msg : history) {
      assertEquals("locked", msg.get("role"), "role is opaque while locked (the whole line is sealed)");
      assertEquals("", msg.get("content"), "content is hidden, not fabricated");
      assertEquals(Boolean.TRUE, msg.get("locked"), "the locked marker distinguishes this from a real empty message");
      assertNotNull(msg.get("id"), "the position-preserving id lets the transcript keep its order");
    }

    // Unlocking restores the real content — proves the locked reads above were a degrade, not data loss.
    key.locked = false;
    List<Map<String, Object>> unlocked = store.loadHistory("s1");
    assertEquals(List.of("q1", "a1"), unlocked.stream().map(m -> m.get("content")).toList());
  }

  private static String readMessagesJsonl(Path tmp, String sessionId) {
    try {
      return Files.readString(tmp.resolve(sessionId).resolve("messages.jsonl"), StandardCharsets.UTF_8).strip();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @Test
  @DisplayName("loadHistory synthesizes id + hash for legacy messages without them")
  void loadBackfillsLegacyMessages(@TempDir Path tmp) throws Exception {
    // Write a JSONL file directly, simulating pre-513 storage (no id, no hash).
    Path sessionDir = tmp.resolve("legacy");
    Files.createDirectories(sessionDir);
    Files.writeString(
        sessionDir.resolve("messages.jsonl"),
        "{\"role\":\"user\",\"content\":\"old message\"}\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        sessionDir.resolve("meta.json"),
        "{\"shapeId\":\"core.free-chat\",\"createdAtMs\":0,\"lastActiveAtMs\":0,\"messageCount\":1}",
        StandardCharsets.UTF_8);

    var store = new FileConversationStore(tmp);
    List<Map<String, Object>> history = store.loadHistory("legacy");
    assertEquals(1, history.size());
    assertEquals("idx-0", history.get(0).get("id"), "legacy id should be deterministic idx-N");
    assertNotNull(history.get(0).get("hash"));
  }

  @Test
  @DisplayName("branchFrom creates a child session whose loadHistory prepends parent prefix")
  void branchFromPrependsPrefix(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.createSession("parent", "core.free-chat");
    store.appendMessage("parent", "core.free-chat", userMsg("q1"));
    store.appendMessage("parent", "core.free-chat", assistantMsg("a1"));
    store.appendMessage("parent", "core.free-chat", userMsg("q2"));
    store.appendMessage("parent", "core.free-chat", assistantMsg("a2"));

    List<Map<String, Object>> parentHistory = store.loadHistory("parent");
    assertEquals(4, parentHistory.size());
    String branchPoint = (String) parentHistory.get(1).get("id"); // assistant of turn 1

    store.branchFrom("parent", branchPoint, "branchA");
    List<Map<String, Object>> branchHistory = store.loadHistory("branchA");
    assertEquals(2, branchHistory.size(), "branch should see parent prefix up to branch point");
    assertEquals("q1", branchHistory.get(0).get("content"));
    assertEquals("a1", branchHistory.get(1).get("content"));

    // Branch's own messages append on top of the parent prefix.
    store.createSession("branchA", "core.free-chat");
    store.appendMessage("branchA", "core.free-chat", userMsg("q2-alt"));
    store.appendMessage("branchA", "core.free-chat", assistantMsg("a2-alt"));
    List<Map<String, Object>> branchHistoryAfter = store.loadHistory("branchA");
    assertEquals(4, branchHistoryAfter.size());
    assertEquals("q2-alt", branchHistoryAfter.get(2).get("content"));
    assertEquals("a2-alt", branchHistoryAfter.get(3).get("content"));

    // Parent unchanged.
    assertEquals(4, store.loadHistory("parent").size());
  }

  @Test
  @DisplayName("listSessions exposes parent pointers for branched sessions")
  void listSessionsExposesParentPointers(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.createSession("root", "core.free-chat");
    store.appendMessage("root", "core.free-chat", userMsg("root msg"));
    List<Map<String, Object>> rootHistory = store.loadHistory("root");
    String branchPoint = (String) rootHistory.get(0).get("id");
    store.branchFrom("root", branchPoint, "child");

    List<ConversationStore.SessionSummary> sessions =
        store.listSessions("core.free-chat", 10);
    assertEquals(2, sessions.size());
    ConversationStore.SessionSummary child =
        sessions.stream().filter(s -> "child".equals(s.sessionId())).findFirst().orElseThrow();
    assertEquals("root", child.parentSessionId());
    assertEquals(branchPoint, child.branchPointMessageId());

    ConversationStore.SessionSummary root =
        sessions.stream().filter(s -> "root".equals(s.sessionId())).findFirst().orElseThrow();
    assertNull(root.parentSessionId());
    assertNull(root.branchPointMessageId());
  }

  @Test
  @DisplayName("Slice 515 FIX-2: branchFrom rejects non-existent branch point")
  void branchFromRejectsUnknownBranchPoint(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.createSession("p", "core.free-chat");
    store.appendMessage("p", "core.free-chat", userMsg("only"));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.branchFrom("p", "nonexistent-id", "b"));
    // No branch directory should have been created.
    assertFalse(tmp.resolve("b").toFile().exists(),
        "rejected branch should not leave a partial session dir");
  }

  @Test
  @DisplayName("Slice 515 FIX-2: branchFrom rejects non-existent parent session")
  void branchFromRejectsUnknownParent(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    assertThrows(
        IllegalArgumentException.class,
        () -> store.branchFrom("no-such-parent", "any-id", "child"));
  }

  @Test
  @DisplayName("Slice 515 FIX-9: branchFrom works with synthesized idx-N id from legacy data")
  void branchFromWithSynthesizedIdxN(@TempDir Path tmp) throws Exception {
    // Write a legacy session (no per-line id/hash) directly to disk.
    Path sessionDir = tmp.resolve("legacy");
    Files.createDirectories(sessionDir);
    Files.writeString(sessionDir.resolve("messages.jsonl"),
        "{\"role\":\"user\",\"content\":\"q1\"}\n"
            + "{\"role\":\"assistant\",\"content\":\"a1\"}\n"
            + "{\"role\":\"user\",\"content\":\"q2\"}\n",
        StandardCharsets.UTF_8);
    Files.writeString(sessionDir.resolve("meta.json"),
        "{\"shapeId\":\"core.free-chat\",\"createdAtMs\":0,\"lastActiveAtMs\":0,\"messageCount\":3}",
        StandardCharsets.UTF_8);

    var store = new FileConversationStore(tmp);
    // The store synthesises idx-0, idx-1, idx-2 on read. Branching from idx-1
    // should yield a 2-message prefix.
    store.branchFrom("legacy", "idx-1", "branch-of-legacy");
    List<Map<String, Object>> branchHistory = store.loadHistory("branch-of-legacy");
    assertEquals(2, branchHistory.size());
    assertEquals("q1", branchHistory.get(0).get("content"));
    assertEquals("a1", branchHistory.get(1).get("content"));
  }

  @Test
  @DisplayName("Slice 515 FIX-9: branch's prefix is bounded by branch-point — parent appends after branch are NOT inherited")
  void branchPrefixIsBoundedByBranchPoint(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.createSession("p", "core.free-chat");
    store.appendMessage("p", "core.free-chat", userMsg("q1"));
    store.appendMessage("p", "core.free-chat", assistantMsg("a1"));
    var parentHistory = store.loadHistory("p");
    String branchPoint = (String) parentHistory.get(1).get("id");

    store.branchFrom("p", branchPoint, "b");
    // Parent gets MORE messages after the branch was created.
    store.appendMessage("p", "core.free-chat", userMsg("q2-late"));
    store.appendMessage("p", "core.free-chat", assistantMsg("a2-late"));

    // Branch's history still only sees the 2-message prefix up to the
    // branch point — NOT the late additions.
    List<Map<String, Object>> branchHistory = store.loadHistory("b");
    assertEquals(2, branchHistory.size(),
        "branch prefix must be bounded by branch-point even if parent grows");
    assertEquals("q1", branchHistory.get(0).get("content"));
    assertEquals("a1", branchHistory.get(1).get("content"));
  }

  @Test
  @DisplayName("Slice 515 FIX-3: deleteSession blocks when child branches exist")
  void deleteSessionBlocksWhenChildBranchesExist(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.createSession("p", "core.free-chat");
    store.appendMessage("p", "core.free-chat", userMsg("q1"));
    var history = store.loadHistory("p");
    String branchPoint = (String) history.get(0).get("id");
    store.branchFrom("p", branchPoint, "b");

    var ex = assertThrows(
        BranchesPreventDeletionException.class,
        () -> store.deleteSession("p"));
    assertTrue(ex.childSessionIds().contains("b"),
        "exception should carry the orphan child session id");
    // Parent dir is still intact.
    assertTrue(tmp.resolve("p").toFile().exists());
  }

  @Test
  @DisplayName("Slice 515 FIX-3: deleteSession of a leaf session succeeds")
  void deleteSessionOfLeafSucceeds(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.createSession("leaf", "core.free-chat");
    store.appendMessage("leaf", "core.free-chat", userMsg("only"));
    store.deleteSession("leaf");
    assertFalse(tmp.resolve("leaf").toFile().exists());
  }

  @Test
  @DisplayName("Slice 515 FIX-7: getSessionMeta returns SessionSummary for known session")
  void getSessionMetaForKnownSession(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.createSession("s", "core.free-chat");
    store.appendMessage("s", "core.free-chat", userMsg("hi"));
    var meta = store.getSessionMeta("s");
    assertTrue(meta.isPresent());
    assertEquals("s", meta.get().sessionId());
    assertEquals("core.free-chat", meta.get().shapeId());
    assertEquals(1, meta.get().messageCount());
    assertNull(meta.get().parentSessionId());
  }

  @Test
  @DisplayName("Slice 515 FIX-7: getSessionMeta returns empty for unknown session")
  void getSessionMetaForUnknownSession(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    assertFalse(store.getSessionMeta("does-not-exist").isPresent());
  }

  @Test
  void throwawayParentWithExistingChildCannotLoseInheritedHistory(@TempDir Path tmp) throws Exception {
    var store = new FileConversationStore(tmp);
    store.createSession("_title_x", "core.free-chat");
    store.appendMessage("_title_x", "core.free-chat", Map.of("id", "q", "role", "user", "content", "question"));
    store.appendMessage("_title_x", "core.free-chat", Map.of("id", "a", "role", "assistant", "content", "answer"));
    // A child saved by an older Engine must stay readable after branching is forbidden.
    Files.createDirectory(tmp.resolve("uc-child"));
    Files.writeString(tmp.resolve("uc-child/meta.json"),
        "{\"parentSessionId\":\"_title_x\",\"branchPointMessageId\":\"a\",\"shapeId\":\"core.free-chat\"}");
    var inherited = store.loadHistory("uc-child");
    assertEquals(2, inherited.size());
    assertThrows(BranchesPreventDeletionException.class, () -> store.deleteSession("_title_x"));
    assertEquals(inherited, store.loadHistory("uc-child"));
    assertEquals(2, store.loadHistory("_title_x").size());
  }

  @Test
  void throwawayParentCannotAcquireNewBranches(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.createSession("_title_x", "core.free-chat");
    store.appendMessage("_title_x", "core.free-chat", Map.of("id", "a", "role", "assistant", "content", "answer"));
    for (String point : List.of("a", ConversationStore.EMPTY_PREFIX_SENTINEL)) {
      assertThrows(IllegalArgumentException.class, () -> store.branchFrom("_title_x", point, "uc-child"));
      assertFalse(Files.exists(tmp.resolve("uc-child")));
    }
  }

  @Test
  @DisplayName("TN4: throwaway cleanup succeeds without children while parents with children are protected")
  void deleteThrowawaySessionWithoutChildren(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    // A different conversation has a dependent child and must stay protected.
    store.createSession("parent", "core.free-chat");
    store.appendMessage("parent", "core.free-chat", userMsg("hi"));
    var history = store.loadHistory("parent");
    String branchPoint = (String) history.get(0).get("id");
    store.branchFrom("parent", branchPoint, "child");
    // This throwaway has no children; internal auto-title cleanup still succeeds.
    store.createSession(ConversationStore.THROWAWAY_SESSION_PREFIX + "auto-1", "core.free-chat");
    store.appendMessage(
        ConversationStore.THROWAWAY_SESSION_PREFIX + "auto-1",
        "core.free-chat", userMsg("title-gen"));
    store.deleteSession(ConversationStore.THROWAWAY_SESSION_PREFIX + "auto-1");
    assertFalse(tmp.resolve(ConversationStore.THROWAWAY_SESSION_PREFIX + "auto-1").toFile().exists());
    // Non-throwaway parent still has the branches-prevent-deletion guard.
    assertThrows(
        BranchesPreventDeletionException.class,
        () -> store.deleteSession("parent"));
  }

  @Test
  @DisplayName("parent cycle is detected and truncates the prefix walk")
  void parentCycleIsTruncated(@TempDir Path tmp) throws Exception {
    var store = new FileConversationStore(tmp);
    // Manually create two sessions whose parent pointers form a cycle.
    Path sA = tmp.resolve("A");
    Path sB = tmp.resolve("B");
    Files.createDirectories(sA);
    Files.createDirectories(sB);
    Files.writeString(sA.resolve("messages.jsonl"),
        "{\"id\":\"a1\",\"role\":\"user\",\"content\":\"in A\"}\n", StandardCharsets.UTF_8);
    Files.writeString(sB.resolve("messages.jsonl"),
        "{\"id\":\"b1\",\"role\":\"user\",\"content\":\"in B\"}\n", StandardCharsets.UTF_8);
    Files.writeString(sA.resolve("meta.json"),
        "{\"shapeId\":\"core.free-chat\",\"createdAtMs\":0,\"lastActiveAtMs\":0,"
            + "\"messageCount\":1,\"parentSessionId\":\"B\"}", StandardCharsets.UTF_8);
    Files.writeString(sB.resolve("meta.json"),
        "{\"shapeId\":\"core.free-chat\",\"createdAtMs\":0,\"lastActiveAtMs\":0,"
            + "\"messageCount\":1,\"parentSessionId\":\"A\"}", StandardCharsets.UTF_8);

    // Should not loop forever; should return a bounded history. With A→B→A
    // the seen-guard truncates the third-level recursion, so the walk yields
    // a finite prefix (a1, b1) plus A's own messages (a1).
    List<Map<String, Object>> history = store.loadHistory("A");
    assertTrue(history.size() <= 5, "cycle should be truncated, not unbounded");
  }

  @Test
  @DisplayName("Tempdoc 610: empty-prefix branch inherits NOTHING from parent")
  void emptyPrefixBranchInheritsNothing(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.createSession("parent", "core.free-chat");
    store.appendMessage("parent", "core.free-chat", userMsg("q1"));
    store.appendMessage("parent", "core.free-chat", assistantMsg("a1"));

    // Branch with the empty-prefix sentinel — used to edit/retry the FIRST
    // message, where there is no preceding message to branch from.
    store.branchFrom("parent", ConversationStore.EMPTY_PREFIX_SENTINEL, "fresh");
    assertEquals(0, store.loadHistory("fresh").size(),
        "empty-prefix branch must start with no inherited messages");

    // Its own messages still append normally.
    store.createSession("fresh", "core.free-chat");
    store.appendMessage("fresh", "core.free-chat", userMsg("q1-edited"));
    List<Map<String, Object>> after = store.loadHistory("fresh");
    assertEquals(1, after.size());
    assertEquals("q1-edited", after.get(0).get("content"));

    // Parent is untouched and is the empty-prefix branch's recorded parent.
    assertEquals(2, store.loadHistory("parent").size());
    assertEquals("parent", store.getSessionMeta("fresh").orElseThrow().parentSessionId());
  }

  @Test
  @DisplayName("Tempdoc 610: branchFrom accepts the empty-prefix sentinel without an existence check")
  void branchFromAcceptsEmptyPrefixSentinel(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.createSession("p", "core.free-chat");
    store.appendMessage("p", "core.free-chat", userMsg("only"));
    // The sentinel is not a real message id; branchFrom must NOT throw for it
    // (contrast branchFromRejectsUnknownBranchPoint, which rejects a real-looking
    // but absent id).
    store.branchFrom("p", ConversationStore.EMPTY_PREFIX_SENTINEL, "b");
    assertTrue(tmp.resolve("b").toFile().exists(),
        "empty-prefix branch dir should be created");
    assertEquals(0, store.loadHistory("b").size());
  }

  @Test
  @DisplayName("Tempdoc 610: context floor trims loadEffectiveContext but not loadHistory")
  void contextFloorTrimsEffectiveContextOnly(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.createSession("s", "core.free-chat");
    store.appendMessage("s", "core.free-chat", userMsg("q1"));
    store.appendMessage("s", "core.free-chat", assistantMsg("a1"));
    store.appendMessage("s", "core.free-chat", userMsg("q2"));
    store.appendMessage("s", "core.free-chat", assistantMsg("a2"));
    List<Map<String, Object>> full = store.loadHistory("s");
    assertEquals(4, full.size());
    String floorId = (String) full.get(2).get("id"); // q2

    store.setContextFloor("s", floorId);

    // Display history is unchanged…
    assertEquals(4, store.loadHistory("s").size());
    // …but the effective (prompt) context starts at the floor (q2, a2).
    List<Map<String, Object>> eff = store.loadEffectiveContext("s");
    assertEquals(2, eff.size());
    assertEquals("q2", eff.get(0).get("content"));
    assertEquals("a2", eff.get(1).get("content"));
    // The floor is surfaced on the session meta for the FE divider.
    assertEquals(floorId, store.getSessionMeta("s").orElseThrow().contextFloor());

    // Clearing restores full effective context.
    store.setContextFloor("s", null);
    assertEquals(4, store.loadEffectiveContext("s").size());
    assertNull(store.getSessionMeta("s").orElseThrow().contextFloor());
  }

  @Test
  @DisplayName("Tempdoc 610 §E.3: excludeMessage drops a turn from effective context, not the transcript")
  void excludeMessageFiltersEffectiveContextOnly(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.createSession("s", "core.free-chat");
    store.appendMessage("s", "core.free-chat", userMsg("q1"));
    store.appendMessage("s", "core.free-chat", assistantMsg("a1"));
    store.appendMessage("s", "core.free-chat", userMsg("q2"));
    List<Map<String, Object>> full = store.loadHistory("s");
    String a1Id = (String) full.get(1).get("id");

    store.excludeMessage("s", a1Id, true);

    // Transcript unchanged…
    assertEquals(3, store.loadHistory("s").size());
    // …but a1 is dropped from the prompt.
    List<Map<String, Object>> eff = store.loadEffectiveContext("s");
    assertEquals(2, eff.size());
    assertEquals("q1", eff.get(0).get("content"));
    assertEquals("q2", eff.get(1).get("content"));
    assertEquals(List.of(a1Id), store.excludedMessageIds("s"));

    // Including it back restores it.
    store.excludeMessage("s", a1Id, false);
    assertEquals(3, store.loadEffectiveContext("s").size());
    assertEquals(List.of(), store.excludedMessageIds("s"));
  }

  @Test
  @DisplayName("Tempdoc 610 §J.3: excludeSource round-trips independently of excludeMessage")
  void excludeSourceRoundTrips(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.createSession("s", "core.free-chat");
    store.appendMessage("s", "core.free-chat", userMsg("q1"));
    // The real id is unit-separator-joined (parentDocId + 0x1F + chunkIndex) - exercise the meta.json control-char round-trip.
    String sourceId = "C:/docs/reliability.md" + (char) 0x1F + "2";

    assertEquals(List.of(), store.excludedSourceIds("s"));
    store.excludeSource("s", sourceId, true);
    assertEquals(List.of(sourceId), store.excludedSourceIds("s"));
    // Independent of the per-message set (different axis, different meta.json key).
    assertEquals(List.of(), store.excludedMessageIds("s"));

    // Survives a reload (fresh store over the same dir).
    var reopened = new FileConversationStore(tmp);
    assertEquals(List.of(sourceId), reopened.excludedSourceIds("s"));

    store.excludeSource("s", sourceId, false);
    assertEquals(List.of(), store.excludedSourceIds("s"));
  }

  @Test
  @DisplayName("Tempdoc 610 §E.3: per-message exclusion applies BEFORE the floor trim")
  void excludeAppliesBeforeFloor(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.createSession("s", "core.free-chat");
    store.appendMessage("s", "core.free-chat", userMsg("q1"));
    store.appendMessage("s", "core.free-chat", assistantMsg("a1"));
    store.appendMessage("s", "core.free-chat", userMsg("q2"));
    store.appendMessage("s", "core.free-chat", assistantMsg("a2"));
    List<Map<String, Object>> full = store.loadHistory("s");
    String floorId = (String) full.get(2).get("id"); // q2
    String a2Id = (String) full.get(3).get("id");

    store.setContextFloor("s", floorId); // prompt = q2, a2
    store.excludeMessage("s", a2Id, true); // exclude a2 (below the floor)

    // Exclusion filters the whole history first, then the floor trims: q2 only.
    List<Map<String, Object>> eff = store.loadEffectiveContext("s");
    assertEquals(1, eff.size());
    assertEquals("q2", eff.get(0).get("content"));
  }

  @Test
  void excludingTheFloorMessageKeepsTheFloor(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.createSession("s", "core.free-chat");
    store.appendMessage("s", "core.free-chat", userMsg("q1"));
    store.appendMessage("s", "core.free-chat", assistantMsg("a1"));
    store.appendMessage("s", "core.free-chat", userMsg("q2"));
    store.appendMessage("s", "core.free-chat", assistantMsg("a2"));
    List<Map<String, Object>> full = store.loadHistory("s");
    String floorId = (String) full.get(2).get("id"); // q2

    store.setContextFloor("s", floorId);
    store.excludeMessage("s", floorId, true);

    // The floor still starts the prompt at q2; q2 itself is excluded, so a2 is all that is left.
    List<Map<String, Object>> eff = store.loadEffectiveContext("s");
    assertEquals(List.of("a2"), eff.stream().map(m -> m.get("content")).toList());
  }

  @Test
  @DisplayName("Tempdoc 610: loadEffectiveContext fails safe to full history for an unknown floor id")
  void contextFloorUnknownIdFailsSafe(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.createSession("s", "core.free-chat");
    store.appendMessage("s", "core.free-chat", userMsg("q1"));
    store.appendMessage("s", "core.free-chat", assistantMsg("a1"));
    store.setContextFloor("s", "no-such-id");
    // A stale/unknown floor must NOT silently drop the whole context.
    assertEquals(2, store.loadEffectiveContext("s").size());
  }

  @Test
  @DisplayName("Tempdoc 610: with no floor, loadEffectiveContext equals loadHistory")
  void noFloorEffectiveEqualsHistory(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.createSession("s", "core.free-chat");
    store.appendMessage("s", "core.free-chat", userMsg("q1"));
    store.appendMessage("s", "core.free-chat", assistantMsg("a1"));
    assertEquals(
        store.loadHistory("s").size(), store.loadEffectiveContext("s").size());
  }

  @Test
  @DisplayName("Tempdoc 610: compactContext prepends the summary to effective context, clears on rewind")
  void compactPrependsSummary(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.createSession("s", "core.free-chat");
    store.appendMessage("s", "core.free-chat", userMsg("q1"));
    store.appendMessage("s", "core.free-chat", assistantMsg("a1"));
    store.appendMessage("s", "core.free-chat", userMsg("q2"));
    String floorId = (String) store.loadHistory("s").get(2).get("id"); // q2

    store.compactContext("s", floorId, "Earlier: the user asked q1 and got a1.");

    List<Map<String, Object>> eff = store.loadEffectiveContext("s");
    // [summary(system), q2]
    assertEquals("system", eff.get(0).get("role"));
    assertTrue(((String) eff.get(0).get("content")).contains("Earlier:"),
        "summary text is prepended");
    assertEquals("q2", eff.get(eff.size() - 1).get("content"));
    // Display history is untouched.
    assertEquals(3, store.loadHistory("s").size());
    // Surfaced on the session meta for the FE divider.
    assertEquals("Earlier: the user asked q1 and got a1.",
        store.getSessionMeta("s").orElseThrow().contextFloorSummary());

    // A plain rewind (setContextFloor) clears the attached summary.
    store.setContextFloor("s", floorId);
    assertNull(store.getSessionMeta("s").orElseThrow().contextFloorSummary());
    assertEquals("user", store.loadEffectiveContext("s").get(0).get("role"),
        "no summary message after rewind");
  }

  // ── Tempdoc 838: the conversation's NAME is a fact of the store ────────────────────────────────

  @Test
  @DisplayName("Tempdoc 838: a title round-trips, with its provenance, and DELETE clears both")
  void titleRoundTrips(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.createSession("s", "core.free-chat");
    store.appendMessage("s", "core.free-chat", userMsg("why did the renewal fail?"));

    store.setTitle("s", "Renewal postmortem", ConversationStore.TITLE_SOURCE_USER);
    ConversationStore.SessionSummary named = store.getSessionMeta("s").orElseThrow();
    assertEquals("Renewal postmortem", named.title());
    assertEquals("user", named.titleSource());
    assertEquals("Renewal postmortem", store.listSessions("core.free-chat", 10).get(0).title());

    // A blank title is the CLEAR — the provenance goes with it, because there is nothing left to
    // have a provenance about.
    store.setTitle("s", null, null);
    ConversationStore.SessionSummary cleared = store.getSessionMeta("s").orElseThrow();
    assertNull(cleared.title());
    assertNull(cleared.titleSource());
  }

  @Test
  @DisplayName("A late name (automatic, or a browser's legacy copy) is stored only while there is none")
  void titleIfUntitledNeverReplacesAName(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    assertFalse(store.setTitleIfUntitled("missing", "Name", ConversationStore.TITLE_SOURCE_AUTO),
        "no conversation is minted for a name");
    assertTrue(store.getSessionMeta("missing").isEmpty());

    store.createSession("s", "core.free-chat");

    store.appendMessage("s", "core.free-chat", userMsg("why did the renewal fail?"));
    assertTrue(store.setTitleIfUntitled("s", "Renewal lock failure", ConversationStore.TITLE_SOURCE_AUTO));
    assertEquals("Renewal lock failure", store.getSessionMeta("s").orElseThrow().title());
    assertEquals("auto", store.getSessionMeta("s").orElseThrow().titleSource());

    store.setTitle("s", "Renewal postmortem", ConversationStore.TITLE_SOURCE_USER);
    assertFalse(store.setTitleIfUntitled("s", "Legacy", ConversationStore.TITLE_SOURCE_USER));
    assertEquals("Renewal postmortem", store.getSessionMeta("s").orElseThrow().title());
    assertEquals("user", store.getSessionMeta("s").orElseThrow().titleSource());
  }

  @Test
  @DisplayName("A sealed name counts as a name: a late write does not replace it")
  void titleIfUntitledSeesASealedName(@TempDir Path tmp) {
    var cipher = new io.justsearch.agent.api.encryption.StoreCipher(new FakeKey());
    var store = new FileConversationStore(tmp, cipher);
    store.createSession("s", "core.free-chat");
    store.appendMessage("s", "core.free-chat", userMsg("first"));
    store.setTitle("s", "Renewal postmortem", ConversationStore.TITLE_SOURCE_USER);
    assertFalse(store.setTitleIfUntitled("s", "Legacy", ConversationStore.TITLE_SOURCE_USER));
    assertEquals("Renewal postmortem", store.getSessionMeta("s").orElseThrow().title());
  }

  @Test
  @DisplayName("Tempdoc 838: an appended message does not strip or re-seal the stored title")
  void titleSurvivesAppendMessage(@TempDir Path tmp) {
    var key = new FakeKey();
    var cipher = new io.justsearch.agent.api.encryption.StoreCipher(key);
    var store = new FileConversationStore(tmp, cipher);
    store.createSession("s", "core.free-chat");
    store.appendMessage("s", "core.free-chat", userMsg("why did the renewal fail?"));
    store.setTitle("s", "Renewal postmortem", ConversationStore.TITLE_SOURCE_USER);

    // The per-message append path reads RAW meta and re-writes it; a sealed value must pass through
    // untouched rather than being decrypted-and-re-encrypted, or worse dropped.
    store.appendMessage("s", "core.free-chat", assistantMsg("the lock held"));
    store.appendMessage("s", "core.free-chat", userMsg("and what did it cost?"));
    assertEquals("Renewal postmortem", store.getSessionMeta("s").orElseThrow().title());
    assertEquals("user", store.getSessionMeta("s").orElseThrow().titleSource());
  }

  @Test
  @DisplayName("Tempdoc 838: a v1 meta written before the field reads as no title, no upcaster")
  void absentTitleReadsAsNull(@TempDir Path tmp) throws Exception {
    Path session = tmp.resolve("legacy");
    Files.createDirectories(session);
    Files.writeString(
        session.resolve("meta.json"),
        "{\"schemaVersion\":1,\"shapeId\":\"core.free-chat\",\"messageCount\":2}");

    var store = new FileConversationStore(tmp);
    assertNull(store.getSessionMeta("legacy").orElseThrow().title());
    assertNull(store.getSessionMeta("legacy").orElseThrow().titleSource());
    assertNull(store.listSessions("core.free-chat", 10).get(0).title());
  }

  @Test
  @DisplayName("Tempdoc 838: the title is SEALED — hidden while locked, back after unlock")
  void titleIsSealedAndHiddenWhileLocked(@TempDir Path tmp) throws Exception {
    var key = new FakeKey();
    var cipher = new io.justsearch.agent.api.encryption.StoreCipher(key);
    var store = new FileConversationStore(tmp, cipher);
    store.createSession("s", "core.free-chat");
    store.appendMessage("s", "core.free-chat", userMsg("first"));
    store.setTitle("s", "SENSITIVE-conversation-name", ConversationStore.TITLE_SOURCE_USER);

    // A reader's name is a sentence about the conversation, so it is content: not plaintext on disk.
    String meta = Files.readString(tmp.resolve("s").resolve("meta.json"), StandardCharsets.UTF_8);
    assertFalse(meta.contains("SENSITIVE-conversation-name"), "the title is not plaintext at rest");
    // The provenance beside it is structural, and stays readable.
    assertTrue(meta.contains("\"titleSource\":\"user\""), "provenance is not sealed");

    key.locked = true;
    List<ConversationStore.SessionSummary> locked = store.listSessions("core.free-chat", 10);
    assertEquals(1, locked.size(), "the locked session stays in the list");
    assertNull(locked.get(0).title(), "the name is hidden while locked, like every other content field");
    assertEquals("user", locked.get(0).titleSource(), "but the window can still tell who named it");

    key.locked = false;
    assertEquals("SENSITIVE-conversation-name", store.listSessions("core.free-chat", 10).get(0).title());
  }

  @Test
  @DisplayName("Tempdoc 838: naming an unknown session creates NOTHING — no ghost row in the list")
  void setTitleOnUnknownSessionCreatesNothing(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.setTitle("never-existed", "Renewal postmortem", ConversationStore.TITLE_SOURCE_USER);

    assertFalse(Files.exists(tmp.resolve("never-existed")), "no session directory was materialised");
    assertTrue(store.getSessionMeta("never-existed").isEmpty());
    assertTrue(store.listSessions(null, 10).isEmpty(), "and nothing to list");
  }

  @Test
  @DisplayName("Tempdoc 838: an over-long title is capped at 200 characters, trimmed first")
  void titleIsCappedAndTrimmed(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.createSession("s", "core.free-chat");
    store.appendMessage("s", "core.free-chat", userMsg("first"));

    store.setTitle("s", "  Renewal postmortem  ", ConversationStore.TITLE_SOURCE_USER);
    assertEquals("Renewal postmortem", store.getSessionMeta("s").orElseThrow().title());

    store.setTitle("s", "x".repeat(400), ConversationStore.TITLE_SOURCE_USER);
    assertEquals(200, store.getSessionMeta("s").orElseThrow().title().length());
  }

  @Test
  @DisplayName("Tempdoc 838: an unrecognised provenance is stored as the reader's, never verbatim")
  void unknownProvenanceFallsBackToUser(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.createSession("s", "core.free-chat");
    store.appendMessage("s", "core.free-chat", userMsg("first"));
    store.setTitle("s", "Renewal postmortem", "something-else");
    // The controller rejects an unknown source with a 400; the store still refuses to persist one,
    // because a value outside the two legal ones would be read back as no provenance at all.
    assertEquals("user", store.getSessionMeta("s").orElseThrow().titleSource());
  }

  @Test
  @DisplayName(
      "863 A-10.1/F5: shapeId is first-wins, and a BLANK inherited value is not a declaration")
  void shapeIdIsFirstWinsAndBlankIsNotADeclaration(@TempDir Path tmp) throws Exception {
    // A conversation's shape is the shape that OPENED it. Before 863 this was overwritten on EVERY
    // append, so one turn in another mode silently relabelled the whole conversation — which decides
    // what `listSessions(shapeId, …)` returns for it and what `UnifiedChatView` re-tags its entire
    // transcript as. 863 made a delegate turn an appender, turning a pre-existing sharp edge routine.
    var store = new FileConversationStore(tmp);
    store.createSession("mixed", "core.free-chat");
    store.appendMessage("mixed", "core.free-chat", userMsg("hello"));
    store.appendMessage("mixed", "core.agent-run", userMsg("now delegate"));
    assertEquals("core.free-chat", store.getSessionMeta("mixed").orElseThrow().shapeId());

    // BLANK is not a declaration (F5). `branchFrom` seeds a child with
    // `parentMeta.getOrDefault("shapeId", "")`, so a branch of a session whose meta predates the
    // field starts at `""`. An absent-only guard would read that as "already declared" and freeze the
    // branch at empty forever — first-wins is about the first REAL declaration.
    Path sessionDir = tmp.resolve("blank");
    Files.createDirectories(sessionDir);
    Files.writeString(
        sessionDir.resolve("meta.json"),
        "{\"shapeId\":\"\",\"createdAtMs\":0,\"lastActiveAtMs\":0,\"messageCount\":0}",
        StandardCharsets.UTF_8);

    store.createSession("blank", "core.rag-ask");

    store.appendMessage("blank", "core.rag-ask", userMsg("and now?"));

    assertEquals(
        "core.rag-ask",
        store.getSessionMeta("blank").orElseThrow().shapeId(),
        "the first real declaration fills a blank rather than being refused by it");
  }
}
