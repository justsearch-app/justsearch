/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SavedRunIdsTest {
  @TempDir Path tmp;

  @Test
  void invalidLastSessionPointerReadsAsMissingWithoutChangingSavedBytes() throws Exception {
    var store = new AgentRunStore(tmp);
    store.runEvents().writeRunMeta("valid", Map.of("sessionId", "valid"));
    for (String id : List.of("a.", "CON", "x:y", "../outside", "valid ", "valid\n")) {
      Path pointer = Files.writeString(tmp.resolve("last-session.txt"), id);
      assertNull(assertDoesNotThrow(store::readLastSnapshot), id);
      assertEquals(id, Files.readString(pointer));
    }
  }

  @Test
  void sessionIndexesSkipInvalidSavedSessionReferences() throws Exception {
    var store = new AgentRunStore(tmp);
    store.runEvents().writeRunMeta("valid", Map.of("sessionId", "valid", "background", true));
    store.runEvents().writeRunMeta("legacy", Map.of("sessionId", "CON", "background", true));
    byte[] legacy = Files.readAllBytes(tmp.resolve("legacy/meta.json"));
    assertAll(
        () -> assertEquals(List.of("valid"), store.listSessions(100).stream().map(m -> m.get("sessionId")).toList()),
        () -> assertEquals(List.of("valid"), store.presenceRunsSince(null).stream().map(m -> m.get("sessionId")).toList()),
        () -> assertArrayEquals(legacy, Files.readAllBytes(tmp.resolve("legacy/meta.json"))));
  }
}
