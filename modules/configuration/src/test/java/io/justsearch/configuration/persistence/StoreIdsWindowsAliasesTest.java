/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreIdsWindowsAliasesTest {
  @TempDir Path tmp;

  @Test
  void refusesAdditionalWindowsDevicesBeforeResolving() {
    Path root = tmp.resolve("uncreated");
    for (String id : List.of("COM¹", "com².txt", "COM³.log", "LPT¹", "lpt².txt", "LPT³.log",
        "CONIN$", "conout$", "CONIN$.txt", "conout$.json", "NUL .txt", "con  .json",
        "COM1 .log", "LPT²  .txt")) {
      assertAll(id,
          () -> assertThrows(IllegalArgumentException.class, () -> StoreIds.requireValid(id)),
          () -> assertThrows(IllegalArgumentException.class, () -> StoreIds.resolve(root, id)),
          () -> assertFalse(Files.exists(root)));
    }
  }

  @Test
  void preservesOrdinaryNamesNearWindowsDevices() {
    for (String id : List.of("COM0", "COM10", "LPT0", "LPT10", "COM⁴", "NULled.txt",
        "CONIN", "CONOUT", "a .txt", "旧会話 1")) {
      assertDoesNotThrow(() -> StoreIds.requireValid(id), id);
      assertEquals(tmp.toAbsolutePath().resolve(id), StoreIds.resolve(tmp, id));
    }
  }
}
