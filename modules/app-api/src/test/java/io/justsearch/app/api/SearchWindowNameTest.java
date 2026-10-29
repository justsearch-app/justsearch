package io.justsearch.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;

final class SearchWindowNameTest {
  @Test
  void searchLabelResolvesFromTheShippedMessageBundle() throws Exception {
    var messages = new Properties();
    try (var stream = getClass().getResourceAsStream("/messages/registry-surface.en.properties")) {
      assertNotNull(stream, "W7: the catalog message bundle must be packaged");
      messages.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
    assertEquals("Search", messages.getProperty("registry-surface.search-v3-surface.label"));
    assertEquals("Search your files and ask questions about them.",
        messages.getProperty("registry-surface.search-v3-surface.description"));
  }
}
