package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InferenceHttpHelpersTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("null node returns null")
  void nullNode_returnsNull() {
    assertNull(InferenceHttpHelpers.asIntOrNull(null));
  }

  @Test
  @DisplayName("NullNode returns null")
  void nullJsonNode_returnsNull() {
    assertNull(InferenceHttpHelpers.asIntOrNull(NullNode.getInstance()));
  }

  @Test
  @DisplayName("integer node returns value")
  void intNode_returnsValue() throws Exception {
    JsonNode node = MAPPER.readTree("42");
    assertEquals(42, InferenceHttpHelpers.asIntOrNull(node));
  }

  @Test
  @DisplayName("long node returns int value")
  void longNode_returnsIntValue() throws Exception {
    JsonNode node = MAPPER.readTree("100");
    assertEquals(100, InferenceHttpHelpers.asIntOrNull(node));
  }

  @Test
  @DisplayName("decimal number node returns int part")
  void decimalNode_returnsIntPart() throws Exception {
    JsonNode node = MAPPER.readTree("42.7");
    assertEquals(42, InferenceHttpHelpers.asIntOrNull(node));
  }

  @Test
  @DisplayName("text node with valid number parses value")
  void textNodeWithNumber_parsesValue() throws Exception {
    JsonNode node = MAPPER.readTree("\"123\"");
    assertEquals(123, InferenceHttpHelpers.asIntOrNull(node));
  }

  @Test
  @DisplayName("text node with whitespace-padded number parses value")
  void textNodeWithWhitespacePaddedNumber_parsesValue() throws Exception {
    JsonNode node = MAPPER.readTree("\"  456  \"");
    assertEquals(456, InferenceHttpHelpers.asIntOrNull(node));
  }

  @Test
  @DisplayName("text node with invalid number returns null")
  void textNodeWithInvalid_returnsNull() throws Exception {
    JsonNode node = MAPPER.readTree("\"not-a-number\"");
    assertNull(InferenceHttpHelpers.asIntOrNull(node));
  }

  @Test
  @DisplayName("text node with blank string returns null")
  void textNodeWithBlank_returnsNull() throws Exception {
    JsonNode node = MAPPER.readTree("\"   \"");
    assertNull(InferenceHttpHelpers.asIntOrNull(node));
  }

  @Test
  @DisplayName("empty text node returns null")
  void textNodeWithEmpty_returnsNull() throws Exception {
    JsonNode node = MAPPER.readTree("\"\"");
    assertNull(InferenceHttpHelpers.asIntOrNull(node));
  }

  @Test
  @DisplayName("negative integer returns negative value")
  void negativeInt_returnsNegativeValue() throws Exception {
    JsonNode node = MAPPER.readTree("-99");
    assertEquals(-99, InferenceHttpHelpers.asIntOrNull(node));
  }

  @Test
  @DisplayName("zero returns zero")
  void zero_returnsZero() throws Exception {
    JsonNode node = MAPPER.readTree("0");
    assertEquals(0, InferenceHttpHelpers.asIntOrNull(node));
  }

  @Test
  @DisplayName("boolean node returns null")
  void booleanNode_returnsNull() throws Exception {
    JsonNode node = MAPPER.readTree("true");
    assertNull(InferenceHttpHelpers.asIntOrNull(node));
  }

  @Test
  @DisplayName("array node returns null")
  void arrayNode_returnsNull() throws Exception {
    JsonNode node = MAPPER.readTree("[1, 2, 3]");
    assertNull(InferenceHttpHelpers.asIntOrNull(node));
  }

  @Test
  @DisplayName("object node returns null")
  void objectNode_returnsNull() throws Exception {
    JsonNode node = MAPPER.readTree("{\"value\": 42}");
    assertNull(InferenceHttpHelpers.asIntOrNull(node));
  }
}
