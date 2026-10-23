/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class ConversationInvalidFoldersTest {
  @TempDir Path tmp;

  @Test
  void omitsInvalidLegacyFoldersWithoutReadingOrChangingTheirFilesAndWarnsOnce() throws Exception {
    var store = new FileConversationStore(tmp);
    store.createSession("valid-legacy", "core.free-chat");
    store.appendMessage("valid-legacy", "core.free-chat", Map.of("role", "user", "content", "keep"));
    String meta = "{\"shapeId\":\"core.free-chat\",\"messageCount\":1}";
    for (String id : List.of(" ", "\t", "\u2028")) {
      Path dir = Files.createDirectory(tmp.resolve(id));
      Files.writeString(dir.resolve("meta.json"), meta);
      Files.writeString(dir.resolve("messages.jsonl"), "legacy bytes\n");
    }
    var logger = (Logger) LoggerFactory.getLogger(FileConversationStore.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    var previousLevel = logger.getLevel();
    logger.setLevel(Level.WARN);
    logger.addAppender(appender);
    try {
      for (int attempt = 0; attempt < 3; attempt++) {
        var sessions = assertDoesNotThrow(() -> store.listSessions(null, 100));
        assertEquals(List.of("valid-legacy"), sessions.stream().map(s -> s.sessionId()).toList());
        assertEquals(1, store.listSessions("core.free-chat", 1).size());
        assertTrue(store.listSessions("another-shape", 100).isEmpty());
      }
      // Invalid metadata must never be parsed, even when another filter would hide the row.
      Path invalid = Files.createDirectory(tmp.resolve("CON"));
      Files.writeString(invalid.resolve("meta.json"), "invalid json");
      for (int attempt = 0; attempt < 3; attempt++) {
        assertEquals(1, assertDoesNotThrow(() -> store.listSessions(null, 100)).size());
        assertTrue(assertDoesNotThrow(() -> store.listSessions("another-shape", 100)).isEmpty());
      }
      assertEquals(1, new FileConversationStore(tmp).listSessions(null, 100).size());
      assertEquals(4, appender.list.stream()
          .filter(e -> e.getFormattedMessage().contains("Skipping conversation folder with invalid id")).count());
      assertTrue(appender.list.stream().noneMatch(e ->
          e.getFormattedMessage().codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029)));
      for (String id : List.of(" ", "\t", "\u2028")) {
        assertEquals(meta, Files.readString(tmp.resolve(id).resolve("meta.json")));
        assertEquals("legacy bytes\n", Files.readString(tmp.resolve(id).resolve("messages.jsonl")));
      }
      assertEquals("invalid json", Files.readString(invalid.resolve("meta.json")));
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previousLevel);
      appender.stop();
    }
  }

  @Test
  void refusesAdditionalWindowsDevicesAtConversationStore() {
    var store = new FileConversationStore(tmp.resolve("uncreated"));
    for (String id : List.of("COM¹", "COM².txt", "COM³", "LPT¹", "LPT².txt", "LPT³",
        "CONIN$", "conout$", "NUL .txt", "COM1  .json")) {
      assertAll(id,
          () -> assertThrows(IllegalArgumentException.class, () -> store.loadHistory(id)),
          () -> assertThrows(IllegalArgumentException.class, () -> store.deleteSession(id)),
          () -> assertThrows(IllegalArgumentException.class,
              () -> store.appendMessage(id, "core.free-chat", Map.of())),
          () -> assertFalse(Files.exists(tmp.resolve("uncreated"))));
    }
  }
}
