package io.justsearch.app.services.agenthistory;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentHistorySafeIdsTest {
  @TempDir Path tmp;

  @Test
  void refusesUnsafeRunIdsBeforeSchedulingOrReadingTranscripts() throws Exception {
    Path root = tmp.resolve("history");
    Path sentinel = Files.writeString(tmp.resolve("sentinel.md"), "keep");
    try (var indexer = new AgentHistoryIndexer(new TestEngineExecutors(), root, () -> null)) {
      for (String id : List.of("", " ", ".", "..", "../sentinel", "a/b", "a\\b", "..\\..", "C:drive", "x:stream", "/absolute", "a\u0000b", "a\nb")) {
        var event = Map.<String, Object>of("eventType", "done", "payload", Map.of("finalResponse", "overwrite"));
        assertThrows(IllegalArgumentException.class, () -> indexer.onEvent(id, event), id);
        assertThrows(IllegalArgumentException.class, () -> indexer.reindexRestoredRun(id, List.of(event)), id);
        assertThrows(IllegalArgumentException.class, () -> indexer.reconcileNow(() -> List.of(id), ignored -> List.of(event)), id);
        assertFalse(Files.exists(root));
        assertEquals("keep", Files.readString(sentinel));
      }
    }
  }
}
