package io.justsearch.systemtests.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class HttpHealthAssertionsTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode health(int schema, String indexState) throws Exception {
    return MAPPER.readTree(
        """
        {"schema_version": %d, "components": {
          "api": {"state": "READY"}, "index": {"state": "%s"},
          "encoders": {"state": "DISABLED"}, "generative": {"state": "DISABLED"}
        }}
        """.formatted(schema, indexState));
  }

  @Test
  void readySchema2NeedsNoWorkerOrAiComponents() throws Exception {
    JsonNode health = health(2, "READY");
    assertDoesNotThrow(() -> HttpHealthAssertions.assertIndexReady(health));
  }

  @Test
  void unexpectedSchemaFailsInsteadOfAborting() throws Exception {
    JsonNode health = health(1, "READY");
    assertThrows(AssertionError.class, () -> HttpHealthAssertions.assertIndexReady(health));
  }

  @Test
  void unreadyIndexFailsInsteadOfAborting() throws Exception {
    JsonNode health = health(2, "STARTING");
    assertThrows(AssertionError.class, () -> HttpHealthAssertions.assertIndexReady(health));
  }

  @Test
  void missingSchema2ComponentFailsInsteadOfAborting() throws Exception {
    JsonNode health = MAPPER.readTree("{\"schema_version\":2,\"components\":{}}");
    assertThrows(AssertionError.class, () -> HttpHealthAssertions.assertIndexReady(health));
  }
}
