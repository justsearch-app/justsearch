/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.encryption.DataKeyState;
import io.justsearch.agent.api.encryption.KeyLockedException;
import io.justsearch.agent.api.encryption.StoreCipher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationDeleteRefusalTest {
  @TempDir Path temp;

  @Test
  void lockedRunDeleteIsARefusalRatherThanAnEmptySuccess() throws Exception {
    var key = new DataKeyState() {
      public boolean enabled() { return true; }
      public boolean locked() { return true; }
      public byte[] dek() { throw new KeyLockedException(); }
    };
    Files.createDirectories(temp.resolve("run"));
    Files.writeString(temp.resolve("run/meta.json"), "retained");
    var store = new AgentRunStore(temp, new StoreCipher(key));
    assertThrows(KeyLockedException.class, () -> store.deleteConversationRuns("conversation"));
    assertEquals("retained", Files.readString(temp.resolve("run/meta.json")));
  }

  @Test
  void reconciliationOwnsTheConversationUntilItsLastEventWrite() {
    var store = new AgentRunStore(temp);
    store.runEvents().writeRunMeta("run", Map.of("sessionId", "run", "conversationId", "conversation",
        "state", "READY_FOR_LLM"));
    for (String id : java.util.List.of("one", "two")) {
      store.runEvents().appendEvent("run", "core.agent-run", "directive_acknowledged", Map.of(
          "directiveId", id, "directiveText", id, "outcome", "queued"));
    }
    var refused = new java.util.concurrent.atomic.AtomicBoolean();
    store.addEventListener((id, event) -> {
      if (event.get("payload") instanceof Map<?, ?> payload && "not_applied".equals(payload.get("outcome"))) {
        try { store.deleteConversationRuns("conversation"); }
        catch (IllegalStateException busy) { refused.set(true); }
      }
    });
    new AgentRunReconciler(store).reconcile();
    assertTrue(refused.get(), "reconciliation must refuse deletion before a later directive can recreate the run");
    assertTrue(Files.exists(temp.resolve("run/meta.json")));
    assertEquals(4, store.runEvents().readEvents("run").size());
    assertEquals(1, store.deleteConversationRuns("conversation"));
  }

  @Test
  void liveRunDeleteRefusesBeforeRemovingAnyRun() throws Exception {
    var store = new AgentRunStore(temp);
    store.runEvents().writeRunMeta("run", Map.of("conversationId", "conversation", "sessionId", "run"));
    store.markLive("run");
    try {
      assertThrows(IllegalStateException.class, () -> store.deleteConversationRuns("conversation"));
      assertTrue(Files.exists(temp.resolve("run/meta.json")));
    } finally { store.clearLive("run"); }
  }
}
