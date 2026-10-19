package io.justsearch.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileOperationLogSafeIdsTest {
  @TempDir Path tmp;

  @Test
  void refusesTraversalBeforeReadingOrUpdatingExternalJournal() throws Exception {
    Path root = tmp.resolve("data/file-operations");
    var log = new FileOperationLog(root);
    String journal = "{\"batchId\":\"private\",\"executed\":[],\"operations\":[],\"finalized\":\"then\"}";
    Path beside = Files.writeString(root.getParent().resolve("sentinel.json"), journal);
    Path above = Files.writeString(tmp.resolve("sentinel.json"), journal);
    for (String id : List.of("../sentinel", "../../sentinel", "..\\sentinel", "..\\..\\sentinel",
        "", " ", ".", "..", "a/b", "C:drive", "a\u0000b")) {
      List<org.junit.jupiter.api.function.Executable> operations = List.of(
          () -> log.readBatch(id), () -> log.startBatch(id, "overwrite", List.of()),
          () -> log.recordSuccess(id, 0, null), () -> log.recordFailure(id, 0, "failed"),
          () -> log.recordSkip(id, 0, "skip"),
          () -> log.recordRename(id, 0, tmp.resolve("a"), tmp.resolve("b"), null),
          () -> log.finalizeBatch(id));
      for (var operation : operations) {
        assertThrows(IllegalArgumentException.class, operation, id);
        assertEquals(journal, Files.readString(beside));
        assertEquals(journal, Files.readString(above));
      }
    }
  }

  @Test
  void refusesWindowsAliasesBeforeJournalAccess() {
    var log = new FileOperationLog(tmp.resolve("logs"));
    for (String id : List.of("...", "a.", "a ", "CON", "con.json", "PrN", "AUX", "NUL", "nul.txt",
        "COM1", "com9.ext", "LPT1", "lpt9.ext")) {
      assertAll(id,
          () -> assertThrows(IllegalArgumentException.class, () -> log.readBatch(id)),
          () -> assertThrows(IllegalArgumentException.class, () -> log.startBatch(id, "x", List.of())),
          () -> assertThrows(IllegalArgumentException.class, () -> log.finalizeBatch(id)));
    }
  }

  @Test
  void legacyAndCreatedBatchIdsKeepTheirJournalBytes() throws Exception {
    Path root = tmp.resolve("logs");
    var log = new FileOperationLog(root);
    for (String id : List.of("550e8400-e29b-41d4-a716-446655440000", "b-1", "batch_1", "旧履歴 1", "COM10", "connection")) {
      Path file = root.resolve(id + ".json");
      String legacy = "{\"batchId\":\"" + id + "\",\"operations\":[],\"executed\":[]}";
      Files.writeString(file, legacy);
      assertEquals(id, log.readBatch(id).get("batchId"));
      assertEquals(legacy, Files.readString(file));
      log.finalizeBatch(id);
      assertNotNull(log.readBatch(id).get("finalized"));
    }
  }
}
