/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileOperationLogEnumerationInvalidIdsTest {
  @TempDir Path tmp;

  @Test
  void listSkipsInvalidBatchNamesBeforeReadingOrApplyingLimit() throws Exception {
    var log = new FileOperationLog(tmp);
    log.startBatch("valid-batch", "keep", List.of());
    var future = FileTime.from(Instant.now().plusSeconds(60));
    var invalidIds = List.of(" ", "\t", "\u2028", "CON", "NUL.txt", "bad\\id", "bad:stream", "trailing.");
    for (String id : invalidIds) {
      Path file = Files.writeString(tmp.resolve(id + ".json"), "{\"schemaVersion\":99}");
      Files.setLastModifiedTime(file, future);
    }
    assertEquals(List.of("valid-batch"), assertDoesNotThrow(() -> log.listBatches(1))
        .stream().map(m -> m.get("batchId")).toList());
    assertEquals("valid-batch", log.readBatch("valid-batch").get("batchId"));
    for (String id : invalidIds) {
      assertEquals("{\"schemaVersion\":99}", Files.readString(tmp.resolve(id + ".json")));
      assertEquals(future, Files.getLastModifiedTime(tmp.resolve(id + ".json")));
    }
  }

  @Test
  void startupRetentionLeavesInvalidBatchFilesUntouched() throws Exception {
    var old = FileTime.from(Instant.now().minus(java.time.Duration.ofDays(40)));
    for (String name : List.of(" .json", "\t.json", "CON.json", "CON")) {
      Path file = Files.writeString(tmp.resolve(name), "keep");
      Files.setLastModifiedTime(file, old);
    }
    new FileOperationLog(tmp);
    for (String name : List.of(" .json", "\t.json", "CON.json", "CON")) {
      Path file = tmp.resolve(name);
      assertTrue(Files.exists(file), "invalid batch was pruned: " + name);
      assertEquals("keep", Files.readString(file));
      assertEquals(old, Files.getLastModifiedTime(file));
    }
  }
}
