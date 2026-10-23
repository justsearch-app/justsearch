/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

final class WatchedRootsDeclarationsTest {
  @TempDir Path temp;

  @Test
  void declarationsIncludeUnavailableRootsAndAcceptBothStoredFormats() throws Exception {
    Path unavailable = temp.resolve("away").resolve("nested");
    String encoded = JsonMapper.builder().build().writeValueAsString(unavailable.toString());
    for (String document : new String[] {"[" + encoded + "]",
        "{\"schemaVersion\":1,\"roots\":[{\"path\":" + encoded + "}]}"}) {
      Files.writeString(temp.resolve("watched_roots.json"), document);
      assertEquals(Set.of(unavailable), WatchedRootsFormat.declaredPaths(temp));
      assertFalse(Files.exists(unavailable), "declaration reads never mount or create a root");
    }
  }

  @Test
  void corruptDeclarationsFailInsteadOfCertifyingAnEmptyScope() throws Exception {
    for (String document : new String[] {"{}", "null", "[{}]", "{\"schemaVersion\":999,\"roots\":[]}"}) {
      Files.writeString(temp.resolve("watched_roots.json"), document);
      assertThrows(Exception.class, () -> WatchedRootsFormat.declaredPaths(temp));
    }
  }
}
