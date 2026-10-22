/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.conversation.ConversationWork;
import io.justsearch.agent.api.encryption.DataKeyState;
import io.justsearch.agent.api.encryption.KeyLockedException;
import io.justsearch.agent.api.encryption.StoreCipher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeferredConversationWorkTest {
  @TempDir Path temp;

  @Test
  void resumedApprovalOwnsConversationUntilItFinishes() {
    var store = seed();
    store.updateCheckpoint("run", "WAITING_APPROVAL", request().messages(), 1, 0, 100, "");
    var calls = new AtomicInteger();
    var queries = queries(store, (request, sink, context) -> {
      calls.incrementAndGet();
      var refusal = assertThrows(ConversationWork.Refused.class, () -> store.deleteConversationRuns("conversation"));
      assertEquals("CONVERSATION_BUSY", refusal.code());
      assertNotNull(store.readSnapshot("run"));
    });
    queries.resumeSession("run", event -> {}, EngineContextTestFixtures.AGENT_LOOP);
    assertEquals(1, calls.get());
    assertEquals(1, store.deleteConversationRuns("conversation"));
  }

  @Test
  void forkOwnsSourceConversationUntilItFinishes() {
    var store = seed();
    var queries = queries(store, (request, sink, context) -> {
      assertThrows(ConversationWork.Refused.class, () -> store.deleteConversationRuns("conversation"));
      assertNotNull(store.readSnapshot("run"));
    });
    queries.forkSession("run", "retry", event -> {}, EngineContextTestFixtures.AGENT_LOOP);
    assertEquals(1, store.deleteConversationRuns("conversation"));
  }

  @Test
  void staleResumeSnapshotCannotStartAfterSourceDeletion() throws Exception {
    var store = seed();
    Map<String, Object> snapshot = store.readSnapshot("run");
    store.deleteConversationRuns("conversation");
    var calls = new AtomicInteger();
    var queries = queries(store, (request, sink, context) -> calls.incrementAndGet());
    var resume = AgentRunQueryService.class.getDeclaredMethod("resumeFromSnapshot", Map.class,
        Consumer.class, io.justsearch.core.context.EngineContext.class);
    resume.setAccessible(true);
    assertDoesNotThrow(() -> resume.invoke(queries, snapshot,
        (Consumer<io.justsearch.agent.api.AgentEvent>) event -> {}, EngineContextTestFixtures.AGENT_LOOP));
    assertEquals(0, calls.get(), "a captured resume must recheck its durable source before starting");
  }

  @Test
  void plaintextRunOnlyIdentityStaysListedWhileLocked() throws Exception {
    var store = seed();
    var bytes = Files.readAllBytes(temp.resolve("run/meta.json"));
    var locked = new AgentRunStore(temp, new StoreCipher(lockedKey()));
    var rows = locked.listConversations(20);
    assertEquals(List.of("conversation"), rows.stream().map(r -> r.get("conversationId")).toList());
    var unlockedRow = store.listConversations(20).get(0);
    assertEquals(unlockedRow.get("createdAtMs"), rows.get(0).get("createdAtMs"));
    assertEquals(unlockedRow.get("lastActiveAtMs"), rows.get(0).get("lastActiveAtMs"),
        "locking must not push a recent run-only row out of the merged list window");
    assertEquals("", rows.get(0).get("firstUserMessage"), "locked listing needs identity, not plaintext content");
    assertThrows(KeyLockedException.class, () -> locked.deleteConversationRuns("conversation"));
    assertArrayEquals(bytes, Files.readAllBytes(temp.resolve("run/meta.json")));
    assertEquals("conversation", store.listConversations(20).get(0).get("conversationId"));
  }

  private AgentRunStore seed() {
    var store = new AgentRunStore(temp);
    store.startRun("run", request(), request().messages(), 100);
    return store;
  }

  private static AgentRequest request() {
    return new AgentRequest(List.of(Map.of("role", "user", "content", "private question")),
        List.of(), 5, List.of(), null, null, "conversation");
  }

  private static AgentRunQueryService queries(AgentRunStore store, AgentRunQueryService.SessionRunner runner) {
    return new AgentRunQueryService(store, null, null, null,
        (sink, message, code, errorClass, retry, attempt) -> {}, runner);
  }

  private static DataKeyState lockedKey() {
    return new DataKeyState() {
      public boolean enabled() { return true; }
      public boolean locked() { return true; }
      public byte[] dek() { throw new KeyLockedException(); }
    };
  }
}
