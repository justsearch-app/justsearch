package io.justsearch.app.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.OperationKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies the wire-format decision from tempdoc 441: {@link OperationRef} and
 * {@link I18nKey} serialize as bare JSON strings, not {@code {"value": "..."}}
 * objects.
 *
 * <p>Surfaced by slice 430 smoke run finding F1: pre-fix, {@code OperationRef}
 * serialized as {@code {"value": "core.health-events"}} which forced clients
 * (FE Zod schemas, smoke scripts, agent introspection) to unwrap the
 * single-field object. The fix uses {@code @JsonValue} + {@code @JsonCreator}
 * on the value-class record to flatten to bare-string convention, matching how
 * enums serialize.
 */
@DisplayName("Value-class wire format (tempdoc 441)")
final class ValueClassWireFormatTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  @Test
  void recordKindsRoundTripUsingThePersistentSpelling() {
    for (OperationKind kind : OperationKind.values()) {
      String json = MAPPER.writeValueAsString(kind);
      assertEquals("\"" + kind.wireValue() + "\"", json);
      assertEquals(kind, MAPPER.readValue(json, OperationKind.class));
    }
    assertThrows(RuntimeException.class, () -> MAPPER.readValue("\"REINDEX\"", OperationKind.class));
    assertThrows(RuntimeException.class, () -> MAPPER.readValue("\"unknown\"", OperationKind.class));
  }

  @Test
  @DisplayName("OperationRef serializes as bare JSON string")
  void operationIdSerializesAsBareString() throws Exception {
    OperationRef id = new OperationRef("core.health-events");
    String json = MAPPER.writeValueAsString(id);
    assertEquals("\"core.health-events\"", json);
  }

  @Test
  @DisplayName("OperationRef deserializes from bare JSON string")
  void operationIdDeserializesFromBareString() throws Exception {
    OperationRef id = MAPPER.readValue("\"core.restart-worker\"", OperationRef.class);
    assertEquals("core.restart-worker", id.value());
  }

  @Test
  @DisplayName("OperationRef deserialization rejects malformed namespace")
  void operationIdRejectsMalformedNamespace() {
    assertThrows(
        Exception.class,
        () -> MAPPER.readValue("\"NotANamespace\"", OperationRef.class));
  }

  @Test
  @DisplayName("I18nKey serializes as bare JSON string")
  void i18nKeySerializesAsBareString() throws Exception {
    I18nKey key = new I18nKey("ops.restart-worker.label");
    String json = MAPPER.writeValueAsString(key);
    assertEquals("\"ops.restart-worker.label\"", json);
  }

  @Test
  @DisplayName("I18nKey deserializes from bare JSON string")
  void i18nKeyDeserializesFromBareString() throws Exception {
    I18nKey key = MAPPER.readValue("\"some.key\"", I18nKey.class);
    assertEquals("some.key", key.value());
  }

  @Test
  @DisplayName("I18nKey deserialization rejects blank")
  void i18nKeyRejectsBlank() {
    assertThrows(
        Exception.class,
        () -> MAPPER.readValue("\"\"", I18nKey.class));
  }
}
