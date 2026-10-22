/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.encryption.DataKeyState;
import io.justsearch.agent.api.encryption.KeyLockedException;
import io.justsearch.agent.api.encryption.StoreCipher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationHistoryRetentionTest {
  @TempDir Path temp;

  @Test
  void startupKeepsExistingAndNewRunOwnershipPastTheOldPruningAge() throws Exception {
    var first = new AgentRunStore(temp);
    first.runEvents().writeRunMeta("old-run", Map.of("sessionId", "old-run", "conversationId", "conversation"));
    first.runEvents().appendEvent("old-run", "core.agent-run", "done", Map.of("finalAnswer", "kept history"));
    age("old-run");
    var upgraded = new AgentRunStore(temp);
    assertEquals(List.of("old-run"), upgraded.listRunIdsByConversation("conversation"),
        "startup must keep the existing ownership link before pruning can orphan agent history");
    upgraded.runEvents().writeRunMeta("new-run", Map.of("sessionId", "new-run", "conversationId", "conversation"));
    age("new-run");
    var restarted = new AgentRunStore(temp);
    assertEquals(List.of("new-run", "old-run"), restarted.listRunIdsByConversation("conversation"));
    assertEquals(1, restarted.runEvents().readEvents("old-run").size());
    assertEquals(2, restarted.deleteConversationRuns("conversation"));
    assertFalse(Files.exists(temp.resolve("old-run")));
    assertFalse(Files.exists(temp.resolve("new-run")));
  }

  @Test
  void lockedStartupCannotPruneTheSealedOwnershipLink() throws Exception {
    var locked = new DataKeyState() {
      public boolean enabled() { return true; }
      public boolean locked() { return true; }
      public byte[] dek() { throw new KeyLockedException(); }
    };
    Files.createDirectory(temp.resolve("sealed-run"));
    Files.writeString(temp.resolve("sealed-run/meta.json"), "sealed ownership");
    Files.writeString(temp.resolve("sealed-run/events.ndjson"), "sealed history");
    age("sealed-run");
    new AgentRunStore(temp, new StoreCipher(locked));
    assertTrue(Files.exists(temp.resolve("sealed-run/meta.json")),
        "locked startup must retain the link for deletion after unlock");
    assertEquals("sealed ownership", Files.readString(temp.resolve("sealed-run/meta.json")));
    assertEquals("sealed history", Files.readString(temp.resolve("sealed-run/events.ndjson")));
  }

  private void age(String id) throws Exception {
    Files.setLastModifiedTime(temp.resolve(id), FileTime.from(Instant.now().minus(Duration.ofDays(40))));
  }
}
