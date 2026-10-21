package io.justsearch.configuration.persistence;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class StoreIdsUnicodeExtensionsTest {
  @Test
  void refusesDeviceNamesWithUnicodeLineSeparatorExtensions() {
    String line = Character.toString(0x2028);
    String paragraph = Character.toString(0x2029);
    for (String id : List.of("CON." + line + "txt", "nUl." + paragraph + "json",
        "LPT1." + line + "log", "com9." + paragraph + "bak")) {
      assertThrows(IllegalArgumentException.class, () -> StoreIds.requireValid(id), id);
    }
  }
}
