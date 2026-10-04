/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class LocalApiServerConfigStoreCompositionTest {
  private static final Path LOCAL_API_SOURCE =
      Path.of("src", "main", "java", "io", "justsearch", "ui", "api", "LocalApiServer.java");
  private static final Path CORE_ASSEMBLY_SOURCE =
      Path.of("src", "main", "java", "io", "justsearch", "ui", "api", "CoreApiAssembly.java");
  private static final Path HEADLESS_SOURCE =
      Path.of("src", "main", "java", "io", "justsearch", "ui", "HeadlessApp.java");

  @Test
  void oneBorrowedAuthorityFeedsEagerSearchAndLateSearchPlusRetrieve() throws Exception {
    ConfigStore borrowed = new ConfigStore(TestResolvedConfigHelper.withDefaults());
    LocalApiServer.Builder builder =
        LocalApiServer.builder(
                new io.justsearch.core.execution.TestEngineExecutors(),
                null,
                Path.of("build", "config-store-composition"))
            .configStore(borrowed);
    assertSame(borrowed, builder.configStore, "the builder must retain the borrowed authority");

    assertTrue(Files.isRegularFile(HEADLESS_SOURCE));
    assertTrue(Files.isRegularFile(CORE_ASSEMBLY_SOURCE));
    assertTrue(Files.isRegularFile(LOCAL_API_SOURCE));
    String headless = normalizedSource(HEADLESS_SOURCE);
    String eager = normalizedSource(CORE_ASSEMBLY_SOURCE);
    String late = normalizedSource(LOCAL_API_SOURCE);

    assertTrue(
        headless.contains(".configStore(configStore)"),
        "HeadlessApp must lend its existing store to the HTTP builder");
    assertTrue(
        eager.contains("apiCatalog,\n            b.configStore)"),
        "eager search composition must receive the builder's borrowed store");
    assertEquals(
        2,
        occurrences(late, "this.core.configStore()"),
        "late composition must pass the same retained authority to search and retrieve");
  }

  private static int occurrences(String text, String needle) {
    int count = 0;
    for (int offset = 0; (offset = text.indexOf(needle, offset)) >= 0; offset += needle.length()) {
      count++;
    }
    return count;
  }

  private static String normalizedSource(Path path) throws Exception {
    return Files.readString(path).replace("\r\n", "\n");
  }
}
